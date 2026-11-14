package com.example.nasbrowser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.StatFs
import android.provider.OpenableColumns
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nasbrowser.databinding.FragmentBrowserBinding
import com.example.nasbrowser.model.ConnectionParams
import com.example.nasbrowser.model.SortMode
import com.example.nasbrowser.model.ViewMode
import com.example.nasbrowser.smb.SmbEntry
import com.example.nasbrowser.util.Bookmark
import com.example.nasbrowser.util.BookmarkStore
import com.example.nasbrowser.util.ClipboardState
import com.example.nasbrowser.util.ClipItem
import com.example.nasbrowser.util.ClipMode
import com.example.nasbrowser.util.ClipSource
import com.example.nasbrowser.util.FileClipboard
import com.example.nasbrowser.util.PasteSpec
import com.example.nasbrowser.util.PasteTaskItem
import com.example.nasbrowser.util.PrefsStore
import com.example.nasbrowser.util.ThumbnailLoader
import com.example.nasbrowser.util.UiPrefs
import com.example.nasbrowser.util.formatSize
import com.example.nasbrowser.util.guessMime
import com.example.nasbrowser.viewmodel.BrowserViewModel
import com.example.nasbrowser.viewmodel.OpenResult
import com.example.nasbrowser.viewmodel.SearchMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder

/**
 * SMB 2/3 directory page (v4.1).
 *
 *  - ES-style clickable breadcrumb bar, nine view modes, 4 x 2 sort matrix in one dialog.
 *  - ES multi-selection: round checkboxes, blue "N/M 全选/区间选择/取消" top bar and a
 *    contextual bottom toolbar (复制/剪切/删除/重命名/更多) driven by [SelectionHost].
 *  - ES's full sixteen-entry overflow menu plus the directory-level settings.
 *  - Whole-image thumbnails (no truncated JPEG) and center-cropped large-icon tiles.
 */
class BrowserFragment : Fragment(), PageActions {

    private var _binding: FragmentBrowserBinding? = null
    private val binding get() = _binding!!
    private val vm: BrowserViewModel by activityViewModels()
    private lateinit var adapter: FileAdapter
    private var query = ""
    private var recursiveUi = false
    private var adapterSignature = ""
    private var tabId = ""
    private var selecting = false
    private var rangeMode = false
    private var rangeAnchor = -1
    private var pinnedSet = emptySet<String>()

    private val uploadLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = displayName(uri) ?: "upload_${System.currentTimeMillis()}"
            Toast.makeText(requireContext(), R.string.uploading, Toast.LENGTH_SHORT).show()
            vm.uploadLocal(uri, name) { err -> reportResult(err) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabId = arguments?.getString(KEY_TAB).orEmpty()
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBrowserBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        pinnedSet = FileOps.pinned(requireContext())
        rebuildAdapter()

        binding.etSearch.doOnTextChanged { text, _, _, _ ->
            query = text?.toString()?.trim().orEmpty()
            if (!recursiveUi && vm.searchMode.value != SearchMode.RECURSIVE) {
                adapter.submitList(filtered())
                updateEmpty()
            }
        }
        binding.btnSearchMode.setOnClickListener {
            if (vm.searchMode.value == SearchMode.RECURSIVE || recursiveUi) exitSearchMode()
            else {
                recursiveUi = true
                updateSearchUi()
                Toast.makeText(requireContext(), R.string.search_recursive_hint, Toast.LENGTH_SHORT).show()
            }
        }
        binding.btnSearchGo.setOnClickListener {
            if (query.isBlank()) {
                Toast.makeText(requireContext(), R.string.search_empty_query, Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            vm.searchRecursive(query) { count, err ->
                if (err != null) {
                    Toast.makeText(
                        requireContext(),
                        getString(R.string.search_failed, err.message ?: ""), Toast.LENGTH_LONG
                    ).show()
                } else {
                    recursiveUi = false
                    updateSearchUi()
                    Toast.makeText(
                        requireContext(), getString(R.string.search_results, count), Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }

        vm.items.observe(viewLifecycleOwner) {
            adapter.submitList(filtered())
            updateEmpty()
            updateCrumbs()
        }
        vm.connection.observe(viewLifecycleOwner) { updateCrumbs(); updateDiskBar() }
        vm.currentShare.observe(viewLifecycleOwner) { updateCrumbs(); updateDiskBar() }
        vm.currentPath.observe(viewLifecycleOwner) { updateCrumbs(); updateDiskBar() }
        vm.searchMode.observe(viewLifecycleOwner) { updateSearchUi(); updateCrumbs() }
        vm.viewMode.observe(viewLifecycleOwner) { rebuildAdapter() }
        vm.showThumbnails.observe(viewLifecycleOwner) { rebuildAdapter() }
        vm.showNewFiles.observe(viewLifecycleOwner) { rebuildAdapter() }
        vm.newFileDays.observe(viewLifecycleOwner) { rebuildAdapter() }
        vm.showHidden.observe(viewLifecycleOwner) { adapter.submitList(filtered()); updateEmpty() }
        vm.onlyNewFiles.observe(viewLifecycleOwner) { adapter.submitList(filtered()); updateEmpty() }
        vm.showDiskSpace.observe(viewLifecycleOwner) { updateDiskBar() }
        vm.diskSpace.observe(viewLifecycleOwner) { updateDiskBar() }
        vm.isBusy.observe(viewLifecycleOwner) { b ->
            binding.progress.visibility = if (b || vm.openProgress.value) View.VISIBLE else View.GONE
        }
        vm.visibleSelection.observe(viewLifecycleOwner) { sel ->
            adapter.setSelected(sel.keys)
            selecting = sel.isNotEmpty()
            // The Activity-owned top/bottom chrome replaces the old in-page selection bar.
            binding.selectionBar.visibility = View.GONE
            publishSelection(sel)
        }
        vm.error.observe(viewLifecycleOwner) { err ->
            if (!err.isNullOrEmpty() && isMyTab()) {
                Toast.makeText(requireContext(), err, Toast.LENGTH_LONG).show()
                vm.clearError()
            }
        }
        vm.manualShareNeeded.observe(viewLifecycleOwner) { needed ->
            if (needed && isMyTab()) {
                vm.clearManualShare()
                showManualShareDialog()
            }
        }
        vm.openResult.observe(viewLifecycleOwner) { res ->
            if (res != null) {
                openWithSystem(res)
                vm.clearOpenResult()
            }
        }

        updateSearchUi()
        updateDiskBar()
        updateCrumbs()
    }

    private fun isMyTab(): Boolean = vm.activeTabId.value == tabId
    private val host: SelectionHost? get() = activity as? SelectionHost

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        rebuildAdapter()
    }

    // ---- ES multi-selection chrome -------------------------------------------------------

    private fun selectedEntries(): List<SmbEntry> =
        vm.visibleSelection.value.values.map { it.entry }

    private fun publishSelection(sel: Map<String, BrowserViewModel.Selected>) {
        val h = host ?: return
        if (sel.isEmpty()) {
            rangeMode = false
            rangeAnchor = -1
            h.publishSelection(null)
            return
        }
        h.publishSelection(
            SelectionState(
                selected = sel.size,
                total = filtered().size,
                onSelectAll = { vm.selectPaths(filtered()) },
                onRange = {
                    rangeMode = true
                    rangeAnchor = -1
                    Toast.makeText(requireContext(), R.string.sel_range_hint, Toast.LENGTH_SHORT).show()
                    h.publishSelection(currentSelectionState())
                },
                onCancel = { clearSelection() },
                actions = listOf(
                    SelAction(getString(R.string.sel_copy), R.drawable.ic_sel_copy) { clipSelected(false) },
                    SelAction(getString(R.string.sel_cut), R.drawable.ic_sel_cut) { clipSelected(true) },
                    SelAction(getString(R.string.sel_delete), R.drawable.ic_sel_delete) { confirmDeleteBatch() },
                    SelAction(getString(R.string.sel_rename), R.drawable.ic_sel_rename) {
                        selectedEntries().firstOrNull()?.let(::showRenameDialog)
                    },
                    SelAction(getString(R.string.sel_more), R.drawable.ic_action_more) {
                        showMoreMenu(binding.pathBar.pathScroll)
                    }
                )
            )
        )
    }

    /** Rebuild the same state object after 区间选择 changes a flag (count/total stay put). */
    private fun currentSelectionState(): SelectionState {
        val sel = vm.visibleSelection.value
        return SelectionState(
            selected = sel.size,
            total = filtered().size,
            onSelectAll = { vm.selectPaths(filtered()) },
            onRange = { rangeMode = true; rangeAnchor = -1 },
            onCancel = { clearSelection() },
            actions = listOf(
                SelAction(getString(R.string.sel_copy), R.drawable.ic_sel_copy) { clipSelected(false) },
                SelAction(getString(R.string.sel_cut), R.drawable.ic_sel_cut) { clipSelected(true) },
                SelAction(getString(R.string.sel_delete), R.drawable.ic_sel_delete) { confirmDeleteBatch() },
                SelAction(getString(R.string.sel_rename), R.drawable.ic_sel_rename) {
                    selectedEntries().firstOrNull()?.let(::showRenameDialog)
                },
                SelAction(getString(R.string.sel_more), R.drawable.ic_action_more) {
                    showMoreMenu(binding.pathBar.pathScroll)
                }
            )
        )
    }

    private fun toggleSelect(e: SmbEntry) {
        val list = filtered()
        val idx = list.indexOfFirst { it.path == e.path }
        if (rangeMode && idx >= 0) {
            if (rangeAnchor < 0) {
                rangeAnchor = idx
                vm.selectPaths(listOf(e))
                Toast.makeText(requireContext(), R.string.sel_range_from, Toast.LENGTH_SHORT).show()
            } else {
                val from = minOf(rangeAnchor, idx)
                val to = maxOf(rangeAnchor, idx)
                vm.addSelectionPaths(list.subList(from, to + 1))
                rangeMode = false
                rangeAnchor = -1
            }
        } else {
            vm.toggleSelection(e)
        }
    }

    // ---- PageActions (Activity's bottom toolbar) ----------------------------------------

    override fun toolbarActions(): List<Action> =
        listOf(Action.NEW, Action.SEARCH, Action.REFRESH, Action.VIEW, Action.MORE)

    override fun onToolbarAction(a: Action): Boolean {
        if (_binding == null) return false
        return when (a) {
            Action.NEW -> { showNewMenu(); true }
            Action.SEARCH -> { toggleSearch(); true }
            Action.REFRESH -> { vm.reload(); true }
            Action.VIEW -> { showViewDialog(); true }
            Action.MORE -> { showMoreMenu(binding.pathBar.pathScroll); true }
            Action.PASTE -> { pasteHere(); true }
            else -> false
        }
    }

    override fun refreshForClipboard() {
        if (::adapter.isInitialized) adapter.notifyDataSetChanged()
    }

    override fun onBackPressed(): Boolean {
        if (_binding == null) return false
        if (selecting || rangeMode) { clearSelection(); return true }
        if (vm.searchMode.value == SearchMode.RECURSIVE || recursiveUi) { exitSearchMode(); return true }
        if (binding.searchRow.visibility == View.VISIBLE) { toggleSearch(); return true }
        if (vm.currentShare.value != null) { vm.browseUp(); return true }
        return false
    }

    private fun toggleSearch() {
        val show = binding.searchRow.visibility != View.VISIBLE
        binding.searchRow.visibility = if (show) View.VISIBLE else View.GONE
        if (!show) {
            query = ""
            binding.etSearch.setText("")
            adapter.submitList(filtered())
            updateEmpty()
        } else binding.etSearch.requestFocus()
    }

    private fun clearSelection() {
        vm.clearSelection()
        selecting = false
        rangeMode = false
        rangeAnchor = -1
        binding.selectionBar.visibility = View.GONE
        adapter.setSelected(emptySet())
        host?.publishSelection(null)
    }

    // ---- list rendering -----------------------------------------------------------------

    private fun rebuildAdapter() {
        if (_binding == null) return
        val mode = vm.viewMode.value
        val widthDp = resources.configuration.screenWidthDp
        val sig = listOf(
            mode, vm.showThumbnails.value, vm.showNewFiles.value, vm.newFileDays.value, widthDp
        ).joinToString("|")
        if (sig == adapterSignature && ::adapter.isInitialized) {
            adapter.submitList(filtered())
            updateEmpty()
            return
        }
        adapterSignature = sig
        adapter = FileAdapter(
            viewMode = mode,
            showThumbnails = vm.showThumbnails.value,
            showNewFiles = vm.showNewFiles.value,
            newThreshold = vm.newThreshold,
            isSelecting = { selecting },
            onToggleSelect = { toggleSelect(it) },
            onActivate = { activateEntry(it) },
            onLongPress = { toggleSelect(it) },
            onOpen = { openMedia(it) },
            loadThumb = { e, iv -> loadThumb(e, iv) },
            cutKeys = { FileClipboard.cutKeys() }
        )
        adapter.setSelected(vm.visibleSelection.value.keys)
        binding.recycler.adapter = adapter
        binding.recycler.layoutManager = if (mode.isGrid) {
            GridLayoutManager(requireContext(), (widthDp / mode.scale.spanDp).coerceIn(2, 8))
        } else {
            LinearLayoutManager(requireContext())
        }
        adapter.submitList(filtered())
        updateEmpty()
    }

    private fun filtered(): List<SmbEntry> {
        var list = vm.items.value
        if (!vm.showHidden.value) list = list.filter { !it.name.startsWith(".") }
        if (vm.onlyNewFiles.value) {
            val t = vm.newThreshold
            list = list.filter { it.isDirectory || (it.lastModified > 0L && it.lastModified >= t) }
        }
        if (vm.searchMode.value != SearchMode.RECURSIVE && query.isNotEmpty()) {
            list = list.filter { it.name.contains(query, ignoreCase = true) }
        }
        if (pinnedSet.isNotEmpty()) list = list.sortedByDescending { it.path in pinnedSet }
        return list
    }

    private fun updateEmpty() {
        if (_binding == null) return
        val empty = filtered().isEmpty()
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recycler.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvEmpty.text = when {
            vm.searchMode.value == SearchMode.RECURSIVE -> getString(R.string.no_search_results)
            vm.currentShare.value == null -> getString(R.string.no_shares)
            else -> getString(R.string.empty_folder)
        }
    }

    private fun loadThumb(e: SmbEntry, target: ImageView) {
        val key = "${vm.shareName()}|${e.path}|${e.lastModified}|${e.size}"
        target.tag = key
        ThumbnailLoader.get(key)?.let {
            target.scaleType = ImageView.ScaleType.CENTER_CROP
            target.setImageBitmap(it)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            // Read the WHOLE image; even a large photo gets a 12 MB budget before truncation.
            val budget = if (e.size in 1..12L * 1024 * 1024) e.size.toInt() else 12 * 1024 * 1024
            val bytes = vm.readHead(e.path, budget)
            val bmp = bytes?.let { ThumbnailLoader.decode(it, 320) }
            if (bmp != null) ThumbnailLoader.put(key, bmp)
            withContext(Dispatchers.Main) {
                if (target.tag == key && bmp != null && _binding != null) {
                    target.scaleType = ImageView.ScaleType.CENTER_CROP
                    target.setImageBitmap(bmp)
                }
            }
        }
    }

    // ---- breadcrumb path bar (every segment clickable) ----------------------------------

    private fun updateCrumbs() {
        if (_binding == null) return
        val hostName = vm.connection.value?.host ?: ""
        val share = vm.currentShare.value
        val labels = mutableListOf<String>()
        val targets = mutableListOf<Pair<String?, String>>()

        labels.add(if (hostName.isEmpty()) "/" else "\\\\$hostName")
        targets.add(null to "")
        if (share != null) {
            labels.add(share)
            targets.add(share to "")
            var acc = ""
            vm.currentPath.value.split('/').filter { it.isNotEmpty() }.forEach { part ->
                acc = if (acc.isEmpty()) part else "$acc/$part"
                labels.add(part)
                targets.add(share to acc)
            }
        }
        if (vm.searchMode.value == SearchMode.RECURSIVE) {
            labels.add("${getString(R.string.crumb_search)}: ${vm.searchLabel.value}")
            targets.add((share ?: "") to vm.currentPath.value)
        }

        PathBarHelper.render(requireContext(), binding.pathBar.pathCrumbs, labels) { idx ->
            val t = targets.getOrNull(idx) ?: return@render
            vm.gotoPath(t.first, t.second)
        }
        binding.pathBar.pathScroll.post { binding.pathBar.pathScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun updateDiskBar() {
        if (_binding == null) return
        if (!vm.showDiskSpace.value) {
            binding.pathBar.diskBadge.visibility = View.GONE
            return
        }
        binding.pathBar.diskBadge.visibility = View.VISIBLE
        val ds = vm.diskSpace.value
        val pct = if (ds != null) ds.percent else {
            val stat = StatFs(requireContext().filesDir.absolutePath)
            val total = stat.totalBytes
            val free = stat.availableBytes
            if (total > 0L) (((total - free) * 100L) / total).toInt().coerceIn(0, 100) else 0
        }
        binding.pathBar.tvDiskPct.text = "$pct%"
    }

    private fun updateSearchUi() {
        if (_binding == null) return
        val rec = recursiveUi || vm.searchMode.value == SearchMode.RECURSIVE
        binding.btnSearchMode.text = getString(
            if (rec) R.string.search_mode_recursive else R.string.search_mode_filter
        )
        binding.btnSearchGo.visibility = if (rec) View.VISIBLE else View.GONE
        binding.etSearch.hint = getString(
            if (rec) R.string.search_recursive_hint else R.string.search_hint
        )
    }

    private fun exitSearchMode() {
        recursiveUi = false
        query = ""
        binding.etSearch.setText("")
        vm.exitSearch()
        updateSearchUi()
    }

    // ---- menus --------------------------------------------------------------------------

    /** Only real "create" actions stay here; 新建窗口 / 上传 were removed in v4.1.5. */
    private fun showNewMenu() {
        val labels = arrayOf(
            getString(R.string.action_mkdir),
            getString(R.string.menu_new_text)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.tool_new)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showNewFolderDialog()
                    1 -> showNewTextFileDialog()
                }
            }
            .show()
    }

    /** 9 view modes + 4 x 2 sort matrix, in the single ES dialog. */
    private fun showViewDialog() {
        ViewSortDialog.show(
            requireContext(), vm.viewMode.value, vm.sortMode.value,
            onView = { vm.setViewMode(it) },
            onSort = { vm.setSortMode(it) }
        )
    }

    private fun showSortDialog() = showViewDialog()

    /** ES's sixteen-entry overflow menu plus this page's directory-level settings. */
    private fun showMoreMenu(anchor: View) {
        val ctx = requireContext()
        val env = MoreEnv(
            ctx = ctx,
            selected = ::selectedEntries,
            isLocal = false,
            bookmark = { addBookmark() },
            bookmarkCurrent = { addBookmark() },
            play = { openMedia(it) },
            share = { shareRemote(it) },
            compress = { askZipName(it) },
            moveCopy = { list, move ->
                val children = vm.items.value.filter { it.isDirectory }.map { it.path }
                FileOps.chooseTargetDir(ctx, vm.currentPath.value, children) { target ->
                    vm.copyOrMove(list, target, move) { err -> reportResult(err) }
                }
            },
            pin = { list ->
                FileOps.pin(ctx, list.map { it.path })
                pinnedSet = FileOps.pinned(ctx)
                rebuildAdapter()
            },
            properties = { FileOps.properties(ctx, it) },
            openWith = { shareRemote(it, openWith = true) },
            shortcut = { Toast.makeText(ctx, R.string.more_need_local, Toast.LENGTH_SHORT).show() }
        )
        val extras = listOf(
            MoreItem(getString(R.string.menu_display), R.drawable.ic_action_view) { showDisplayDialog() },
            MoreItem(getString(R.string.menu_only_new), R.drawable.ic_action_add) {
                val on = !vm.onlyNewFiles.value
                vm.setOnlyNewFiles(on)
                Toast.makeText(
                    ctx, if (on) R.string.only_new_on else R.string.only_new_off, Toast.LENGTH_SHORT
                ).show()
            },
            MoreItem(getString(R.string.menu_dir_settings), R.drawable.ic_folder_blue) { showDirSettingsDialog() },
            MoreItem(getString(R.string.menu_analysis), R.drawable.ic_pie) { showAnalysisDialog() },
            MoreItem(getString(R.string.menu_recycle), R.drawable.ic_sel_delete) {
                (activity as? MainActivity)?.navigateTo(R.id.nav_recycle)
            },
            MoreItem(getString(R.string.menu_lan), R.drawable.ic_cloud) {
                (activity as? MainActivity)?.navigateTo(R.id.nav_lan)
            },
            MoreItem(getString(R.string.action_upload), R.drawable.ic_action_add) { uploadLauncher.launch("*/*") },
            MoreItem(getString(R.string.menu_edit_text), R.drawable.ic_sel_rename) { editSelectedText() }
        )
        MoreMenu.show(ctx, anchor, MoreActions.build(env) + extras)
    }

    private fun shareRemote(e: SmbEntry, openWith: Boolean = false) {
        Toast.makeText(requireContext(), R.string.sharing, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            val f = vm.cacheForShare(e)
            if (f == null) {
                Toast.makeText(requireContext(), R.string.no_player, Toast.LENGTH_SHORT).show()
            } else if (openWith) FileOps.openWith(requireContext(), f)
            else FileOps.share(requireContext(), f)
        }
    }

    private fun askZipName(list: List<SmbEntry>) {
        val input = EditText(requireContext()).apply { setText(getString(R.string.compress_default)) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.more_compress)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { getString(R.string.compress_default) }
                vm.compressSelected(list, if (name.endsWith(".zip", true)) name else "$name.zip") { err ->
                    if (err == null) {
                        Toast.makeText(
                            requireContext(), getString(R.string.compress_done, name), Toast.LENGTH_SHORT
                        ).show()
                    } else reportResult(err)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /**
     * Windows semantics: 复制 / 剪切 only stage the selection in the shared clipboard. Nothing
     * is copied or moved until the user navigates elsewhere and taps 粘贴.
     */
    private fun clipSelected(move: Boolean) {
        val entries = selectedEntries()
        if (entries.isEmpty()) {
            Toast.makeText(requireContext(), R.string.more_need_selection, Toast.LENGTH_SHORT).show()
            return
        }
        FileClipboard.put(
            if (move) ClipMode.CUT else ClipMode.COPY,
            ClipSource(
                local = false,
                host = vm.connection.value?.host ?: "",
                share = vm.currentShare.value ?: "",
                fromDir = vm.currentPath.value,
                items = entries.map { ClipItem(it.path, it.name, it.isDirectory, it.size) }
            )
        )
        clearSelection()
        host?.refreshChrome()
        Toast.makeText(
            requireContext(),
            getString(if (move) R.string.clip_cut else R.string.clip_copied, entries.size),
            Toast.LENGTH_SHORT
        ).show()
    }

    /** Execute a staged copy / move into the directory currently shown by this window. */
    private fun pasteHere() {
        val clip = FileClipboard.get()
        if (clip == null) {
            Toast.makeText(requireContext(), R.string.clip_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val src = clip.source
        if (src.local) {
            pasteFromLocal(clip)
            return
        }
        val share = vm.currentShare.value ?: ""
        val hostNow = vm.connection.value?.host ?: ""
        if (src.host != hostNow || src.share != share) {
            Toast.makeText(
                requireContext(),
                getString(R.string.clip_wrong_share, src.share),
                Toast.LENGTH_LONG
            ).show()
            return
        }
        // Normalise trailing slashes: copying a file onto itself would open the same handle
        // for reading and (over)writing and could corrupt it.
        val targetDir = vm.currentPath.value.trimEnd('/')
        if (targetDir == src.fromDir.trimEnd('/')) {
            Toast.makeText(requireContext(), R.string.clip_same_dir, Toast.LENGTH_SHORT).show()
            return
        }
        val dirs = src.items.count { it.isDirectory }
        val files = src.items.filter { !it.isDirectory }
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), R.string.clip_dir_only, Toast.LENGTH_SHORT).show()
            return
        }
        val spec = PasteSpec(
            move = clip.mode == ClipMode.CUT,
            fromLabel = "smb://${src.host}/${src.share}/${src.fromDir.trimEnd('/')}/",
            toLabel = "smb://$hostNow/$share/$targetDir/",
            items = files.map { PasteTaskItem(it.name, it.path, it.size) },
            targetLocal = false,
            targetSmbDir = targetDir,
            skippedDirs = dirs
        )
        launchPaste(vm, clip, spec) { vm.reload() }
    }

    /**
     * Cross-type paste 本机 -> NAS: upload the staged local files into the directory currently
     * shown by this window. A CUT removes each local original only after a successful upload.
     */
    private fun pasteFromLocal(clip: ClipboardState) {
        val src = clip.source
        val dirs = src.items.count { it.isDirectory }
        val files = src.items.map { File(it.path) }
            .filter { !it.isDirectory && it.isFile && it.exists() }
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), R.string.clip_source_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val targetDir = vm.currentPath.value.trimEnd('/')
        val share = vm.currentShare.value ?: ""
        val host = vm.connection.value?.host ?: ""
        val spec = PasteSpec(
            move = clip.mode == ClipMode.CUT,
            fromLabel = "${src.fromDir.trimEnd('/')}/",
            toLabel = "smb://$host/$share/$targetDir/",
            items = files.map { localTaskItem(it) },
            targetLocal = false,
            targetSmbDir = targetDir,
            skippedDirs = dirs
        )
        launchPaste(vm, clip, spec) { vm.reload() }
    }

    private fun showDisplayDialog() {
        val labels = arrayOf(
            getString(R.string.display_thumb), getString(R.string.display_new),
            getString(R.string.display_space), getString(R.string.display_hidden)
        )
        val flags = booleanArrayOf(
            vm.showThumbnails.value, vm.showNewFiles.value,
            vm.showDiskSpace.value, vm.showHidden.value
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.display_title)
            .setMultiChoiceItems(labels, flags) { _, which, checked ->
                when (which) {
                    0 -> vm.setShowThumbnails(checked)
                    1 -> vm.setShowNewFiles(checked)
                    2 -> vm.setShowDiskSpace(checked)
                    3 -> vm.setShowHidden(checked)
                }
            }
            .setNeutralButton(R.string.menu_new_file_days) { _, _ -> showNewDaysDialog() }
            .setPositiveButton(R.string.ok) { _, _ -> rebuildAdapter(); updateDiskBar() }
            .show()
    }

    private fun showNewDaysDialog() {
        val input = EditText(requireContext()).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(vm.newFileDays.value.toString())
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.menu_new_file_days)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                vm.setNewFileDays(input.text.toString().toIntOrNull() ?: 7)
                rebuildAdapter()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun currentPathText(): String {
        val hostName = vm.connection.value?.host ?: ""
        val share = vm.currentShare.value ?: return "\\\\$hostName"
        return "\\\\$hostName\\$share" +
            if (vm.currentPath.value.isEmpty()) "" else "\\${vm.currentPath.value.replace('/', '\\')}"
    }

    private fun showDirSettingsDialog() {
        val items = arrayOf(
            getString(R.string.dir_set_start), getString(R.string.dir_clear_start),
            getString(R.string.dir_reset)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dir_title)
            .setMessage(getString(R.string.dir_info, currentPathText()))
            .setItems(items) { _, which ->
                when (which) {
                    0 -> {
                        val share = vm.currentShare.value
                        if (share == null) {
                            Toast.makeText(requireContext(), R.string.need_share, Toast.LENGTH_SHORT).show()
                        } else {
                            UiPrefs.saveStartLocation(
                                requireContext(),
                                UiPrefs.StartLocation(vm.connection.value?.host ?: "", share, vm.currentPath.value)
                            )
                            Toast.makeText(requireContext(), R.string.dir_start_set, Toast.LENGTH_SHORT).show()
                        }
                    }
                    1 -> {
                        UiPrefs.clearStartLocation(requireContext())
                        Toast.makeText(requireContext(), R.string.dir_start_cleared, Toast.LENGTH_SHORT).show()
                    }
                    2 -> vm.resetCurrentDirPrefs()
                }
            }
            .show()
    }

    private fun requireSelected(): SmbEntry? {
        val e = selectedEntries().firstOrNull()
        if (e == null) {
            Toast.makeText(requireContext(), R.string.more_need_selection, Toast.LENGTH_SHORT).show()
        }
        return e
    }

    private fun showNewFolderDialog() {
        val input = EditText(requireContext()).apply { hint = getString(R.string.prompt_name) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_mkdir)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) vm.createFolder(name) { reportResult(it) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showNewTextFileDialog() {
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.text_filename_hint)
            setText("新建文本.txt")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.text_new_title)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                var name = input.text.toString().trim()
                if (name.isEmpty()) name = "新建文本.txt"
                if (!name.contains('.')) name += ".txt"
                showTextEditor(name, null, "")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun isTextFile(name: String): Boolean =
        name.substringAfterLast('.', "").lowercase() in setOf(
            "txt", "md", "log", "csv", "json", "xml", "srt", "ass", "ini", "conf", "yaml", "yml"
        )

    private fun editSelectedText() {
        val e = requireSelected() ?: return
        if (e.isDirectory || !isTextFile(e.name)) {
            Toast.makeText(requireContext(), R.string.text_only, Toast.LENGTH_SHORT).show()
            return
        }
        vm.readTextFile(e) { text, err ->
            if (text != null) showTextEditor(null, e, text)
            else Toast.makeText(
                requireContext(), getString(R.string.text_read_failed, err?.message ?: ""), Toast.LENGTH_LONG
            ).show()
        }
    }

    private fun showTextEditor(name: String?, entry: SmbEntry?, initial: String) {
        val v = layoutInflater.inflate(R.layout.dialog_text_editor, null)
        val et = v.findViewById<EditText>(R.id.etText)
        et.setText(initial)
        et.setSelection(et.text.length)
        AlertDialog.Builder(requireContext())
            .setTitle(
                if (entry == null) getString(R.string.text_new_title)
                else getString(R.string.text_edit_title, entry.name)
            )
            .setView(v)
            .setPositiveButton(R.string.ok) { _, _ ->
                val content = et.text.toString()
                if (entry == null) vm.createTextFile(name!!, content) { reportResult(it) }
                else vm.saveTextFile(entry, content) { reportResult(it) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showRenameDialog(e: SmbEntry) {
        val input = EditText(requireContext()).apply { setText(e.name) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_rename)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == e.name) return@setPositiveButton
                // A "/" or "\" would turn the new name into a cross-folder move, and a leading
                // dot would make the listing filter hide the file (it looks like a deletion).
                if (name.contains('/') || name.contains('\\') || name.startsWith(".")) {
                    Toast.makeText(requireContext(), R.string.rename_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                vm.renameEntry(e, name) { reportResult(it) }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun confirmDeleteBatch() {
        val list = selectedEntries()
        if (list.isEmpty()) {
            Toast.makeText(requireContext(), R.string.more_need_selection, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_delete)
            .setMessage(getString(R.string.confirm_delete, "${list.size} 项"))
            .setPositiveButton(R.string.ok) { _, _ ->
                vm.deleteEntries(list) { err ->
                    if (err == null) {
                        clearSelection()
                        Toast.makeText(requireContext(), R.string.deleted_to_recycle, Toast.LENGTH_SHORT).show()
                    } else reportResult(err)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun addBookmark() {
        val share = vm.currentShare.value
        if (share == null) {
            Toast.makeText(requireContext(), R.string.need_share, Toast.LENGTH_SHORT).show()
            return
        }
        val hostName = vm.connection.value?.host ?: ""
        val picked = selectedEntries().firstOrNull()
        val (path, isDir, defaultName) = if (picked != null && !picked.isDirectory) {
            Triple(picked.path, false, picked.name)
        } else {
            val p = vm.currentPath.value
            Triple(p, true, if (p.isEmpty()) share else p.substringAfterLast('/'))
        }
        val input = EditText(requireContext()).apply {
            hint = getString(R.string.bookmark_name_hint)
            setText(defaultName)
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.action_bookmark)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { defaultName }
                BookmarkStore.add(
                    requireContext(),
                    Bookmark(name, share, path, hostName, isDir, isLocal = false)
                )
                Toast.makeText(requireContext(), R.string.added_bookmark, Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    // ---- storage analyser ---------------------------------------------------------------

    private fun showAnalysisDialog() {
        val v = layoutInflater.inflate(R.layout.dialog_analysis, null)
        val box = v.findViewById<LinearLayout>(R.id.analysisContainer)
        addAnalysisText(box, getString(R.string.analysis_calc), true)
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.analysis_title)
            .setView(v)
            .setPositiveButton(android.R.string.ok, null)
            .show()

        vm.analyzeCurrent { result, err ->
            if (_binding == null) return@analyzeCurrent
            box.removeAllViews()
            if (result == null) {
                addAnalysisText(box, getString(R.string.analysis_failed, err?.message ?: ""), true)
                return@analyzeCurrent
            }
            addAnalysisText(
                box,
                getString(R.string.analysis_summary, result.fileCount, result.dirCount, formatSize(result.totalBytes)),
                true
            )
            if (result.truncated) addAnalysisText(box, getString(R.string.analysis_truncated))
            if (result.totalBytes == 0L) {
                addAnalysisText(box, getString(R.string.analysis_empty))
                return@analyzeCurrent
            }
            addAnalysisText(box, getString(R.string.analysis_by_type), true)
            result.categories.forEach { c ->
                addAnalysisText(box, getString(R.string.analysis_category, c.label, c.count, formatSize(c.bytes)))
                addAnalysisBar(box, c.bytes, result.totalBytes)
            }
            addAnalysisText(box, getString(R.string.analysis_largest_files), true)
            result.largestFiles.forEach { addAnalysisText(box, "  ${it.name}    ${formatSize(it.size)}") }
            addAnalysisText(box, getString(R.string.analysis_largest_dirs), true)
            result.largestDirs.forEach { (p, size) ->
                addAnalysisText(box, "  ${p.ifEmpty { "/" }}    ${formatSize(size)}")
            }
        }
    }

    private fun addAnalysisText(box: LinearLayout, text: String, bold: Boolean = false) {
        box.addView(TextView(requireContext()).apply {
            this.text = text
            textSize = 14f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding(0, 8, 0, 4)
            if (bold) setTypeface(null, Typeface.BOLD)
        })
    }

    private fun addAnalysisBar(box: LinearLayout, bytes: Long, total: Long) {
        val pb = ProgressBar(requireContext(), null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = if (total > 0L) ((bytes * 100L) / total).toInt().coerceIn(0, 100) else 0
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 12)
        }
        box.addView(pb)
    }

    private fun reportResult(err: Throwable?) {
        val msg = if (err == null) getString(R.string.op_done)
        else getString(R.string.op_failed, err.message ?: err.javaClass.simpleName)
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
    }

    private fun displayName(uri: Uri): String? = try {
        val cursor = requireContext().contentResolver.query(uri, null, null, null, null)
        if (cursor == null) null else cursor.use {
            val idx = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (it.moveToFirst() && idx >= 0) it.getString(idx) else null
        }
    } catch (_: Exception) {
        null
    }

    // ---- opening entries ----------------------------------------------------------------

    private fun activateEntry(entry: SmbEntry) {
        if (entry.isDirectory) {
            val share = vm.currentShare.value
            clearSelection()
            if (share == null) vm.enterShare(entry.name) else vm.enterFolder(entry.path)
        } else {
            openMedia(entry)
        }
    }

    private fun isMedia(entry: SmbEntry): Boolean {
        if (entry.isDirectory) return false
        val mime = guessMime(entry.path)
        return mime.startsWith("image/") || mime.startsWith("video/")
    }

    private fun openMedia(entry: SmbEntry) {
        val mime = guessMime(entry.path)
        when {
            mime.startsWith("video/") -> {
                val conn = vm.connection.value
                val share = vm.currentShare.value
                if (conn == null || share == null) {
                    Toast.makeText(requireContext(), R.string.opening_file, Toast.LENGTH_SHORT).show()
                    vm.openEntry(entry)
                } else openVideoWithVlc(buildSmbUrl(conn, share, entry.path), mime)
            }
            mime.startsWith("image/") -> openImageGallery(entry)
            else -> {
                Toast.makeText(requireContext(), R.string.opening_file, Toast.LENGTH_SHORT).show()
                vm.openEntry(entry)
            }
        }
    }

    private fun openImageGallery(entry: SmbEntry) {
        val share = vm.currentShare.value ?: return
        val images = vm.items.value.filter { isImage(it) }
        if (images.isEmpty()) return
        val start = images.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
        val frag = ImageViewerFragment.newInstance(share, ArrayList(images), start)
        parentFragmentManager.beginTransaction()
            .add(R.id.pageContainer, frag, "imageViewer")
            .addToBackStack("imageViewer")
            .commit()
    }

    private fun isImage(entry: SmbEntry): Boolean =
        !entry.isDirectory && guessMime(entry.path).startsWith("image/")

    private fun openVideoWithVlc(smbUrl: String, mime: String) {
        val vlcPkg = "org.videolan.vlc"
        val vlcInstalled = try {
            requireContext().packageManager.getPackageInfo(vlcPkg, 0)
            true
        } catch (_: Exception) {
            false
        }
        val intent = Intent(Intent.ACTION_VIEW).apply { setDataAndType(Uri.parse(smbUrl), mime) }
        if (vlcInstalled) intent.setPackage(vlcPkg)
        else Toast.makeText(requireContext(), R.string.vlc_not_installed, Toast.LENGTH_LONG).show()
        try {
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.no_player, Toast.LENGTH_LONG).show()
        }
    }

    private fun buildSmbUrl(p: ConnectionParams, share: String, path: String): String {
        val enc = { s: String -> URLEncoder.encode(s, "UTF-8").replace("+", "%20") }
        val userPart = if (p.domain.isNotBlank()) "${enc(p.domain)};${enc(p.username)}" else enc(p.username)
        val cred = "${userPart}:${enc(p.password)}@"
        val pathEnc = path.split('/').filter { it.isNotBlank() }.joinToString("/") { enc(it) }
        return "smb://$cred${p.host}/${enc(share)}/$pathEnc"
    }

    private fun openWithSystem(res: OpenResult) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(), requireContext().packageName + ".fileprovider", res.file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, res.mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.no_player, Toast.LENGTH_LONG).show()
        }
    }

    private fun showManualShareDialog() {
        val input = EditText(requireContext()).apply { hint = getString(R.string.manual_share_hint) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.manual_share_title)
            .setView(input)
            .setPositiveButton(R.string.manual_share_ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isNotEmpty()) vm.enterShare(name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        host?.publishSelection(null)
        _binding = null
    }

    private fun ensureNotifyPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            val perm = Manifest.permission.POST_NOTIFICATIONS
            if (ContextCompat.checkSelfPermission(requireContext(), perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(arrayOf(perm), REQ_NOTIFY)
            }
        }
    }

    companion object {
        private const val KEY_TAB = "tabId"
        private const val REQ_NOTIFY = 1001
        private const val REQ_UNUSED = 0

        fun newInstance(tabId: String) = BrowserFragment().apply {
            arguments = Bundle().apply { putString(KEY_TAB, tabId) }
        }
    }
}
