package com.example.nasbrowser

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import android.text.InputType
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import androidx.core.content.PermissionChecker
import androidx.core.widget.doOnTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nasbrowser.databinding.FragmentLocalBinding
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
import com.example.nasbrowser.util.RecycleItem
import com.example.nasbrowser.util.RecycleStore
import com.example.nasbrowser.util.ThumbnailLoader
import com.example.nasbrowser.util.UiPrefs
import com.example.nasbrowser.util.formatSize
import com.example.nasbrowser.util.guessMime
import com.example.nasbrowser.viewmodel.BrowserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/**
 * Local file browser (v4.1).
 *
 * Android 11+ needs MANAGE_EXTERNAL_STORAGE for File.listFiles() on /storage/emulated/0; the
 * banner guides the user there and re-lists on return. v4.1 adds ES multi-selection, the nine
 * view modes, the full overflow menu and complete (non-truncated) image thumbnails.
 */
class LocalFilesFragment : Fragment(), PageActions {

    private var _binding: FragmentLocalBinding? = null
    private val binding get() = _binding!!
    private val nasVm: BrowserViewModel by activityViewModels()
    private lateinit var adapter: FileAdapter
    private lateinit var selection: SelectionHelper
    private var tabId = ""
    private var current: File = Environment.getExternalStorageDirectory()
    private var allItems = listOf<SmbEntry>()
    private var query = ""
    private var viewMode = ViewMode.DEFAULT
    private var sortMode = SortMode.NAME_ASC
    private var showHidden = false
    private var adapterSignature = ""
    private var pinnedSet = emptySet<String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (hasAllFilesAccess()) {
            Toast.makeText(requireContext(), R.string.local_permission_granted, Toast.LENGTH_SHORT).show()
        }
        load(current)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tabId = arguments?.getString(KEY_TAB).orEmpty()
        val start = arguments?.getString(KEY_PATH)
        if (!start.isNullOrBlank()) current = File(start)
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentLocalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val ctx = requireContext()
        viewMode = UiPrefs.loadViewMode(ctx)
        sortMode = UiPrefs.loadSortMode(ctx)
        showHidden = UiPrefs.showHidden(ctx)
        pinnedSet = FileOps.pinned(ctx)

        selection = SelectionHelper(
            keys = { visible().map { it.path } },
            publish = { (activity as? SelectionHost)?.publishSelection(it) },
            actions = ::selectionActions,
            onChange = {
                if (::adapter.isInitialized) adapter.setSelected(selection.selected)
                binding.selectionBar.visibility = View.GONE
                render()
            },
            onRangeStart = {
                Toast.makeText(ctx, R.string.sel_range_hint, Toast.LENGTH_SHORT).show()
            }
        )
        rebuildAdapter(true)

        binding.etSearch.doOnTextChanged { text, _, _, _ ->
            query = text?.toString()?.trim().orEmpty()
            render()
        }
        binding.btnPermission.setOnClickListener { requestAccess() }

        load(current)
        if (!hasAllFilesAccess()) requestAccess()
    }

    override fun onResume() {
        super.onResume()
        if (_binding != null) load(current)
    }

    override fun onDestroyView() {
        (activity as? MainActivity)?.saveLocalPath(tabId, current.absolutePath)
        (activity as? SelectionHost)?.publishSelection(null)
        super.onDestroyView()
        _binding = null
    }

    fun currentPath(): String = current.absolutePath

    // ---- permission ---------------------------------------------------------------------

    private fun hasAllFilesAccess(): Boolean = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        PermissionChecker.checkSelfPermission(
            requireContext(), Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PermissionChecker.PERMISSION_GRANTED
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(requireContext())
                .setTitle(R.string.local_permission_title)
                .setMessage(R.string.local_permission_msg)
                .setPositiveButton(R.string.local_permission_go) { _, _ ->
                    try {
                        permissionLauncher.launch(
                            Intent(
                                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                Uri.parse("package:${requireContext().packageName}")
                            )
                        )
                    } catch (_: Exception) {
                        permissionLauncher.launch(
                            Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                        )
                    }
                }
                .setNegativeButton(R.string.local_permission_later, null)
                .show()
        } else {
            requestPermissions(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ), REQ_STORAGE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_STORAGE && _binding != null) load(current)
    }

    // ---- listing ------------------------------------------------------------------------

    private fun load(dir: File) {
        if (_binding == null) return
        current = dir
        val files = try {
            dir.listFiles()?.toList() ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        allItems = files
            .filter { showHidden || !it.name.startsWith(".") }
            .filter { !it.name.startsWith(".trash_") }
            .map {
                SmbEntry(
                    name = it.name,
                    path = it.absolutePath,
                    isDirectory = it.isDirectory,
                    size = if (it.isDirectory) 0L else it.length(),
                    lastModified = it.lastModified()
                )
            }
            .let(::sorted)
        binding.permissionBar.visibility = if (hasAllFilesAccess()) View.GONE else View.VISIBLE
        if (::selection.isInitialized) selection.refresh()
        render()
        updateCrumbs()
        updateDiskBadge()
    }

    private fun sorted(list: List<SmbEntry>): List<SmbEntry> {
        val primary: Comparator<SmbEntry> = compareByDescending { it.isDirectory }
        val secondary: Comparator<SmbEntry> = when (sortMode.field) {
            SortMode.Field.NAME -> compareBy { it.name.lowercase() }
            SortMode.Field.TYPE -> compareBy { it.name.substringAfterLast('.', "").lowercase() }
            SortMode.Field.SIZE -> compareBy { it.size }
            SortMode.Field.DATE -> compareBy { it.lastModified }
        }
        val cmp = primary.then(if (sortMode.ascending) secondary else secondary.reversed())
        return list.sortedWith(cmp)
    }

    private fun visible(): List<SmbEntry> {
        var list = if (query.isEmpty()) allItems
        else allItems.filter { it.name.contains(query, ignoreCase = true) }
        if (pinnedSet.isNotEmpty()) list = list.sortedByDescending { it.path in pinnedSet }
        return list
    }

    private fun render() {
        if (_binding == null) return
        val list = visible()
        adapter.submitList(list)
        val empty = list.isEmpty()
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recycler.visibility = if (empty) View.GONE else View.VISIBLE
        binding.tvEmpty.text = if (!hasAllFilesAccess()) {
            getString(R.string.local_permission_msg)
        } else getString(R.string.local_empty)
    }

    private fun rebuildAdapter(force: Boolean = false) {
        if (_binding == null) return
        val widthDp = resources.configuration.screenWidthDp
        val sig = listOf(viewMode, showHidden, widthDp).joinToString("|")
        if (!force && sig == adapterSignature && ::adapter.isInitialized) { render(); return }
        adapterSignature = sig
        adapter = FileAdapter(
            viewMode = viewMode,
            showThumbnails = UiPrefs.showThumbnails(requireContext()),
            showNewFiles = UiPrefs.showNewFiles(requireContext()),
            newThreshold = System.currentTimeMillis() -
                UiPrefs.newFileDays(requireContext()) * 24L * 3600L * 1000L,
            isSelecting = { selection.isActive() },
            onToggleSelect = { toggleSelection(it) },
            onActivate = { activate(it) },
            onLongPress = { toggleSelection(it) },
            onOpen = { activate(it) },
            loadThumb = { e, iv -> loadThumb(e, iv) },
            cutKeys = { FileClipboard.cutKeys() }
        )
        adapter.setSelected(selection.selected)
        binding.recycler.adapter = adapter
        binding.recycler.layoutManager = if (viewMode.isGrid) {
            GridLayoutManager(requireContext(), (widthDp / viewMode.scale.spanDp).coerceIn(2, 8))
        } else {
            LinearLayoutManager(requireContext())
        }
        render()
    }

    /** Decode the WHOLE local image (no 384 KB prefix) so large-icon tiles are complete. */
    private fun loadThumb(e: SmbEntry, target: ImageView) {
        val key = "${e.path}|${e.lastModified}|${e.size}"
        target.tag = key
        ThumbnailLoader.get(key)?.let {
            target.scaleType = ImageView.ScaleType.CENTER_CROP
            target.setImageBitmap(it)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val bmp = ThumbnailLoader.decodeFile(e.path, 320)
            if (bmp != null) ThumbnailLoader.put(key, bmp)
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (target.tag == key && bmp != null && _binding != null) {
                    target.scaleType = ImageView.ScaleType.CENTER_CROP
                    target.setImageBitmap(bmp)
                }
            }
        }
    }

    // ---- breadcrumb path bar ------------------------------------------------------------

    private fun pathChain(dir: File): List<File> {
        val out = mutableListOf<File>()
        var f: File? = dir
        while (f != null) { out.add(0, f); f = f.parentFile }
        return out
    }

    private fun updateCrumbs() {
        if (_binding == null) return
        val chain = pathChain(current)
        val labels = chain.mapIndexed { i, f -> if (i == 0) "/" else f.name }
        PathBarHelper.render(requireContext(), binding.pathBar.pathCrumbs, labels) { idx ->
            chain.getOrNull(idx)?.let(::load)
        }
        binding.pathBar.pathScroll.post { binding.pathBar.pathScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun updateDiskBadge() {
        if (_binding == null) return
        val pct = try {
            val st = StatFs(current.absolutePath)
            val total = st.totalBytes
            val free = st.availableBytes
            if (total > 0L) (((total - free) * 100L) / total).toInt().coerceIn(0, 100) else 0
        } catch (_: Exception) {
            0
        }
        binding.pathBar.tvDiskPct.text = "$pct%"
    }

    // ---- ES multi-selection & file operations -------------------------------------------

    private fun toggleSelection(e: SmbEntry) {
        val idx = visible().indexOfFirst { it.path == e.path }
        selection.toggle(e.path, idx)
    }

    private fun clearSelection() = selection.cancel()

    private fun selectedEntries(): List<SmbEntry> =
        visible().filter { selection.isSelected(it.path) }

    private fun selectedFiles(): List<File> = selectedEntries().map { File(it.path) }

    private fun selectionActions(): List<SelAction> = listOf(
        SelAction(getString(R.string.sel_copy), R.drawable.ic_sel_copy) { clipSelected(false) },
        SelAction(getString(R.string.sel_cut), R.drawable.ic_sel_cut) { clipSelected(true) },
        SelAction(getString(R.string.sel_delete), R.drawable.ic_sel_delete) { deleteSelected() },
        SelAction(getString(R.string.sel_rename), R.drawable.ic_sel_rename) { renameSelected() },
        SelAction(getString(R.string.sel_more), R.drawable.ic_action_more) {
            showMoreMenu(binding.pathBar.pathScroll)
        }
    )

    private fun activate(e: SmbEntry) {
        val f = File(e.path)
        if (e.isDirectory) {
            clearSelection()
            load(f)
            return
        }
        if (f.extension.equals("zip", true)) {
            try {
                com.example.nasbrowser.util.ZipUtils.unzip(f, File(f.parentFile, f.nameWithoutExtension))
                Toast.makeText(requireContext(), R.string.unzip_done, Toast.LENGTH_SHORT).show()
                load(current)
            } catch (ex: Exception) {
                Toast.makeText(
                    requireContext(), getString(R.string.op_failed, ex.message ?: ""), Toast.LENGTH_LONG
                ).show()
            }
            return
        }
        // Pictures open in the app's own gallery, with 上一张 / 下一张 inside the current folder,
        // instead of handing a single file to an external viewer.
        if (guessMime(f.name).startsWith("image/")) {
            openImageGallery(e)
            return
        }
        openExternal(f)
    }

    /** Browse all images visible in the current (possibly filtered/sorted) listing. */
    private fun openImageGallery(entry: SmbEntry) {
        val images = visible().filter {
            !it.isDirectory && guessMime(it.name).startsWith("image/")
        }
        if (images.isEmpty()) {
            openExternal(File(entry.path))
            return
        }
        val start = images.indexOfFirst { it.path == entry.path }.coerceAtLeast(0)
        val frag = ImageViewerFragment.newInstanceLocal(ArrayList(images), start)
        parentFragmentManager.beginTransaction()
            .add(R.id.pageContainer, frag, "imageViewer")
            .addToBackStack("imageViewer")
            .commit()
    }

    private fun openExternal(f: File) {
        try {
            val uri = FileProvider.getUriForFile(
                requireContext(), requireContext().packageName + ".fileprovider", f
            )
            val intent = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, guessMime(f.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            startActivity(intent)
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.local_open_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun renameSelected() {
        val e = selectedEntries().firstOrNull() ?: run {
            Toast.makeText(requireContext(), R.string.more_need_selection, Toast.LENGTH_SHORT).show()
            return
        }
        val input = EditText(requireContext()).apply { setText(e.name) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.local_rename)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty() || name == e.name) return@setPositiveButton
                if (name.contains('/') || name.contains('\\') || name.startsWith(".")) {
                    Toast.makeText(requireContext(), R.string.rename_invalid, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val old = File(e.path)
                val target = File(old.parentFile, name)
                if (target.exists()) {
                    Toast.makeText(requireContext(), R.string.rename_exists, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val ok = old.renameTo(target)
                Toast.makeText(
                    requireContext(),
                    if (ok) R.string.op_done else R.string.local_open_failed,
                    Toast.LENGTH_SHORT
                ).show()
                clearSelection()
                load(current)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Batch soft-delete into the recycle bin (hidden same-directory rename). */
    private fun deleteSelected() {
        val entries = selectedEntries()
        if (entries.isEmpty()) {
            Toast.makeText(requireContext(), R.string.more_need_selection, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.local_delete)
            .setMessage(getString(R.string.confirm_delete, "${entries.size} 项"))
            .setPositiveButton(R.string.ok) { _, _ ->
                var okCount = 0
                entries.forEach { e ->
                    val f = File(e.path)
                    try {
                        val hidden = File(f.parentFile, ".trash_${System.currentTimeMillis()}_${f.name}")
                        if (!f.renameTo(hidden)) throw IllegalStateException("rename failed")
                        RecycleStore.add(
                            requireContext(),
                            RecycleItem(
                                id = UUID.randomUUID().toString(),
                                host = "", share = "",
                                path = f.absolutePath,
                                trashPath = hidden.absolutePath,
                                name = f.name,
                                isDirectory = f.isDirectory,
                                size = f.length(),
                                deletedAt = System.currentTimeMillis()
                            )
                        )
                        okCount++
                    } catch (_: Exception) { }
                }
                Toast.makeText(
                    requireContext(),
                    if (okCount > 0) R.string.deleted_to_recycle else R.string.local_open_failed,
                    Toast.LENGTH_SHORT
                ).show()
                clearSelection()
                load(current)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** 「更多 → 复制到 / 移动到」: pick a target directory and execute immediately. */
    private fun moveCopyTo(files: List<File>, move: Boolean) {
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), R.string.more_need_selection, Toast.LENGTH_SHORT).show()
            return
        }
        val children = allItems.filter { it.isDirectory }.map { it.path }
        FileOps.chooseTargetDir(requireContext(), current.absolutePath, children) { target ->
            viewLifecycleOwner.lifecycleScope.launch {
                val err = FileOps.localMoveCopy(files, current, target, move)
                val msg = if (err == null) getString(R.string.op_done)
                else getString(R.string.op_failed, err.message ?: "")
                Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                clearSelection()
                load(current)
            }
        }
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
                local = true,
                fromDir = current.absolutePath,
                items = entries.map { ClipItem(it.path, it.name, it.isDirectory, it.size) }
            )
        )
        clearSelection()
        (activity as? SelectionHost)?.refreshChrome()
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
        if (!src.local) {
            pasteFromNas(clip)
            return
        }
        val target = current.absolutePath
        if (target.trimEnd(File.separatorChar) == src.fromDir.trimEnd(File.separatorChar)) {
            Toast.makeText(requireContext(), R.string.clip_same_dir, Toast.LENGTH_SHORT).show()
            return
        }
        val files = src.items.map { File(it.path) }.filter { it.exists() }
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), R.string.clip_source_missing, Toast.LENGTH_SHORT).show()
            return
        }
        val spec = PasteSpec(
            move = clip.mode == ClipMode.CUT,
            fromLabel = "${src.fromDir.trimEnd(File.separatorChar)}/",
            toLabel = "$target/",
            items = files.map { localTaskItem(it) },
            targetLocal = true,
            targetDir = current
        )
        launchPaste(nasVm, clip, spec) { load(current) }
    }

    /**
     * Cross-type paste NAS -> 本机. The Activity-scoped SMB pool survives a tab switch, so the
     * staged remote paths can be streamed straight into [current] without re-connecting.
     */
    private fun pasteFromNas(clip: ClipboardState) {
        val src = clip.source
        val dirs = src.items.count { it.isDirectory }
        val files = src.items.filter { !it.isDirectory }
        if (files.isEmpty()) {
            Toast.makeText(requireContext(), R.string.clip_dir_only, Toast.LENGTH_SHORT).show()
            return
        }
        val spec = PasteSpec(
            move = clip.mode == ClipMode.CUT,
            fromLabel = "smb://${src.host}/${src.share}/${src.fromDir.trimEnd('/')}/",
            toLabel = "${current.absolutePath}/",
            items = files.map { PasteTaskItem(it.name, it.path, it.size) },
            targetLocal = true,
            targetDir = current,
            skippedDirs = dirs
        )
        launchPaste(nasVm, clip, spec) { load(current) }
    }

    private fun createFolder() {
        val input = EditText(requireContext()).apply { hint = getString(R.string.prompt_name) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.local_new_folder)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim()
                if (name.isEmpty()) return@setPositiveButton
                val ok = File(current, name).mkdirs()
                Toast.makeText(
                    requireContext(),
                    if (ok) R.string.local_mkdir_done else R.string.local_open_failed,
                    Toast.LENGTH_SHORT
                ).show()
                load(current)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun createTextFile() {
        val nameView = EditText(requireContext()).apply {
            hint = getString(R.string.text_filename_hint)
            setText("新建文本.txt")
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.local_new_text)
            .setView(nameView)
            .setPositiveButton(R.string.ok) { _, _ ->
                var name = nameView.text.toString().trim()
                if (name.isEmpty()) name = "新建文本.txt"
                if (!name.contains('.')) name += ".txt"
                showTextEditor(File(current, name), "")
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showTextEditor(file: File, initial: String) {
        val v = layoutInflater.inflate(R.layout.dialog_text_editor, null)
        val et = v.findViewById<EditText>(R.id.etText)
        et.setText(initial)
        AlertDialog.Builder(requireContext())
            .setTitle(file.name)
            .setView(v)
            .setPositiveButton(R.string.ok) { _, _ ->
                try {
                    file.writeText(et.text.toString())
                    Toast.makeText(requireContext(), R.string.local_text_saved, Toast.LENGTH_SHORT).show()
                } catch (ex: Exception) {
                    Toast.makeText(
                        requireContext(), getString(R.string.op_failed, ex.message ?: ""), Toast.LENGTH_LONG
                    ).show()
                }
                load(current)
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun analyzeLocal() {
        val ctx = requireContext()
        AlertDialog.Builder(ctx)
            .setTitle(R.string.analysis_title)
            .setMessage(R.string.home_analyzer_running)
            .show()
        viewLifecycleOwner.lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            var files = 0
            var bytes = 0L
            val stack = ArrayDeque<File>()
            stack.add(current)
            var guard = 0
            while (stack.isNotEmpty() && guard++ < 20000) {
                val d = stack.removeLast()
                d.listFiles()?.forEach {
                    if (it.isDirectory) stack.add(it)
                    else { files++; bytes += it.length() }
                }
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                if (!isAdded) return@withContext
                AlertDialog.Builder(ctx)
                    .setTitle(R.string.analysis_title)
                    .setMessage(getString(R.string.home_analyzer_result, files, formatSize(bytes)))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
        }
    }

    // ---- PageActions --------------------------------------------------------------------

    override fun toolbarActions(): List<Action> =
        listOf(Action.NEW, Action.SEARCH, Action.REFRESH, Action.VIEW, Action.MORE)

    override fun onToolbarAction(a: Action): Boolean {
        if (_binding == null) return false
        return when (a) {
            Action.NEW -> {
                val labels = arrayOf(
                    getString(R.string.local_new_folder), getString(R.string.local_new_text)
                )
                AlertDialog.Builder(requireContext())
                    .setTitle(R.string.tool_new)
                    .setItems(labels) { _, i ->
                        when (i) {
                            0 -> createFolder()
                            1 -> createTextFile()
                        }
                    }.show()
                true
            }
            Action.SEARCH -> {
                val show = binding.searchRow.visibility != View.VISIBLE
                binding.searchRow.visibility = if (show) View.VISIBLE else View.GONE
                if (!show) { query = ""; binding.etSearch.setText(""); render() }
                else binding.etSearch.requestFocus()
                true
            }
            Action.REFRESH -> { load(current); true }
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
        if (selection.isActive()) { clearSelection(); return true }
        if (binding.searchRow.visibility == View.VISIBLE) {
            binding.searchRow.visibility = View.GONE
            query = ""
            binding.etSearch.setText("")
            render()
            return true
        }
        val parent = current.parentFile
        if (parent != null) { load(parent); return true }
        return false
    }

    private fun showViewDialog() {
        ViewSortDialog.show(
            requireContext(), viewMode, sortMode,
            onView = {
                viewMode = it
                UiPrefs.saveViewMode(requireContext(), it)
                rebuildAdapter(true)
            },
            onSort = {
                sortMode = it
                UiPrefs.saveSortMode(requireContext(), it)
                load(current)
            }
        )
    }

    /** The same ES sixteen-entry menu as the SMB page, with local-file implementations. */
    private fun showMoreMenu(anchor: View) {
        val ctx = requireContext()
        val env = MoreEnv(
            ctx = ctx,
            selected = ::selectedEntries,
            isLocal = true,
            bookmark = { e -> addLocalBookmark(e.name, e.path, e.isDirectory) },
            bookmarkCurrent = {
                addLocalBookmark(
                    current.name.ifEmpty { getString(R.string.tab_local) },
                    current.absolutePath,
                    true
                )
            },
            play = { openExternal(File(it.path)) },
            share = { FileOps.share(ctx, File(it.path)) },
            compress = { askZipName(it) },
            moveCopy = { list, move -> moveCopyTo(list.map { File(it.path) }, move) },
            pin = { list ->
                FileOps.pin(ctx, list.map { it.path })
                pinnedSet = FileOps.pinned(ctx)
                render()
            },
            properties = { FileOps.properties(ctx, it) },
            openWith = { FileOps.openWith(ctx, File(it.path)) },
            shortcut = { e -> FileOps.addShortcut(ctx, File(e.path), e.name) }
        )
        val extras = listOf(
            MoreItem(getString(R.string.display_hidden), R.drawable.ic_action_view) {
                showHidden = !showHidden
                UiPrefs.saveShowHidden(ctx, showHidden)
                load(current)
            },
            MoreItem(getString(R.string.menu_analysis), R.drawable.ic_pie) { analyzeLocal() },
            MoreItem(getString(R.string.menu_recycle), R.drawable.ic_sel_delete) {
                (activity as? MainActivity)?.navigateTo(R.id.nav_recycle)
            },
            MoreItem(getString(R.string.menu_dir_settings), R.drawable.ic_folder_blue) { showDirSettingsDialog() },
            MoreItem(getString(R.string.action_mkdir), R.drawable.ic_action_add) { createFolder() },
            MoreItem(getString(R.string.menu_new_text), R.drawable.ic_sel_rename) { createTextFile() }
        )
        MoreMenu.show(ctx, anchor, MoreActions.build(env) + extras)
    }

    /**
     * Local favourites must carry isLocal=true: v4.1 stored them with an empty host/share,
     * which made MainActivity.openBookmark() treat them as SMB locations and fail to open.
     */
    private fun addLocalBookmark(name: String, path: String, isDir: Boolean) {
        val ctx = requireContext()
        BookmarkStore.add(
            ctx,
            Bookmark(
                name, "", File(path).absolutePath, "",
                isDirectory = isDir, isLocal = true
            )
        )
        Toast.makeText(ctx, R.string.favorite_done, Toast.LENGTH_SHORT).show()
    }

    private fun askZipName(list: List<SmbEntry>) {
        val input = EditText(requireContext()).apply { setText(getString(R.string.compress_default)) }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.more_compress)
            .setView(input)
            .setPositiveButton(R.string.ok) { _, _ ->
                val name = input.text.toString().trim().ifEmpty { getString(R.string.compress_default) }
                val zipName = if (name.endsWith(".zip", true)) name else "$name.zip"
                viewLifecycleOwner.lifecycleScope.launch {
                    val err = FileOps.localCompress(
                        list.map { File(it.path) }, current, zipName
                    )
                    Toast.makeText(
                        requireContext(),
                        if (err == null) getString(R.string.compress_done, zipName)
                        else getString(R.string.op_failed, err.message ?: ""),
                        Toast.LENGTH_SHORT
                    ).show()
                    load(current)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showDirSettingsDialog() {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.dir_title)
            .setMessage(getString(R.string.dir_info, current.absolutePath))
            .setItems(
                arrayOf(getString(R.string.dir_set_start), getString(R.string.dir_clear_start))
            ) { _, which ->
                if (which == 0) {
                    requireContext().getSharedPreferences("nas_ui_v3", 0)
                        .edit().putString("localStartDir", current.absolutePath).apply()
                    Toast.makeText(requireContext(), R.string.dir_start_set, Toast.LENGTH_SHORT).show()
                } else {
                    requireContext().getSharedPreferences("nas_ui_v3", 0)
                        .edit().remove("localStartDir").apply()
                    Toast.makeText(requireContext(), R.string.dir_start_cleared, Toast.LENGTH_SHORT).show()
                }
            }
            .show()
    }

    companion object {
        private const val KEY_TAB = "tabId"
        private const val KEY_PATH = "path"
        private const val REQ_STORAGE = 2002

        fun newInstance(tabId: String, path: String = "") = LocalFilesFragment().apply {
            arguments = Bundle().apply {
                putString(KEY_TAB, tabId)
                putString(KEY_PATH, path)
            }
        }
    }
}
