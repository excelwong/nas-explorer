package com.example.nasbrowser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.nasbrowser.databinding.FragmentRecycleBinding
import com.example.nasbrowser.model.SortMode
import com.example.nasbrowser.model.ViewMode
import com.example.nasbrowser.smb.SmbEntry
import com.example.nasbrowser.util.RecycleItem
import com.example.nasbrowser.util.ThumbnailLoader
import com.example.nasbrowser.util.formatSize
import com.example.nasbrowser.viewmodel.BrowserViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ES recycle bin (v4.1).
 *
 * Uses the SAME nine view modes, 4 x 2 sort matrix, round-checkbox multi-selection, thumbnails
 * and Activity-owned selection chrome as the file browser. Non-selection bottom bar is
 * 全部清除 / 刷新 / 视图 / 窗口 / 历史记录; in selection mode it becomes
 * 删除 / 恢复 / 属性 / 网络搜索 / 更多.
 */
class RecycleFragment : Fragment(), PageActions {

    private var _binding: FragmentRecycleBinding? = null
    private val binding get() = _binding!!
    private val vm: BrowserViewModel by activityViewModels()
    private lateinit var adapter: FileAdapter
    private lateinit var selection: SelectionHelper
    private var entries = listOf<SmbEntry>()
    private var viewMode = ViewMode.DEFAULT
    private var sortMode = SortMode.DATE_DESC
    private var adapterSignature = ""
    private val fmt = SimpleDateFormat("yyyy/MM/dd HH:mm", Locale.getDefault())

    private fun prefs(): android.content.SharedPreferences =
        requireContext().getSharedPreferences("nas_recycle", Context.MODE_PRIVATE)

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentRecycleBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewMode = ViewMode.fromKey(prefs().getString("view", null))
        sortMode = SortMode.of(SortMode.Field.DATE, false)
        prefs().getString("sort", null)?.let { sortMode = SortMode.fromKey(it) }

        selection = SelectionHelper(
            keys = { entries.map { it.path } },
            publish = { (activity as? SelectionHost)?.publishSelection(it) },
            actions = ::selectionActions,
            onChange = {
                if (::adapter.isInitialized) adapter.setSelected(selection.selected)
                render()
            },
            onRangeStart = {
                Toast.makeText(requireContext(), R.string.sel_range_hint, Toast.LENGTH_SHORT).show()
            }
        )
        rebuildAdapter(true)

        binding.btnEmpty.setOnClickListener { confirmEmpty() }
        vm.recycleItems.observe(viewLifecycleOwner) {
            selection.refresh()
            render()
        }
        render()
    }

    override fun onResume() {
        super.onResume()
        vm.reloadRecycle()
    }

    override fun onDestroyView() {
        (activity as? SelectionHost)?.publishSelection(null)
        super.onDestroyView()
        _binding = null
    }

    // ---- data / rendering ---------------------------------------------------------------

    private fun toEntry(i: RecycleItem) = SmbEntry(
        name = i.name,
        path = i.id,
        isDirectory = i.isDirectory,
        size = i.size,
        lastModified = i.deletedAt
    )

    private fun itemFor(e: SmbEntry): RecycleItem? = vm.recycleItems.value.firstOrNull { it.id == e.path }

    private fun sorted(list: List<SmbEntry>): List<SmbEntry> {
        val primary: Comparator<SmbEntry> = compareByDescending { it.isDirectory }
        val secondary: Comparator<SmbEntry> = when (sortMode.field) {
            SortMode.Field.NAME -> compareBy { it.name.lowercase() }
            SortMode.Field.TYPE -> compareBy { it.name.substringAfterLast('.', "").lowercase() }
            SortMode.Field.SIZE -> compareBy { it.size }
            SortMode.Field.DATE -> compareBy { it.lastModified }
        }
        return list.sortedWith(
            primary.then(if (sortMode.ascending) secondary else secondary.reversed())
        )
    }

    private fun render() {
        if (_binding == null) return
        entries = sorted(vm.recycleItems.value.map(::toEntry))
        adapter.submitList(entries)
        val empty = entries.isEmpty()
        binding.tvEmpty.visibility = if (empty) View.VISIBLE else View.GONE
        binding.recycler.visibility = if (empty) View.GONE else View.VISIBLE
    }

    private fun rebuildAdapter(force: Boolean = false) {
        if (_binding == null) return
        val widthDp = resources.configuration.screenWidthDp
        val sig = listOf(viewMode, widthDp).joinToString("|")
        if (!force && sig == adapterSignature && ::adapter.isInitialized) { render(); return }
        adapterSignature = sig
        adapter = FileAdapter(
            viewMode = viewMode,
            showThumbnails = true,
            showNewFiles = false,
            newThreshold = 0L,
            isSelecting = { selection.isActive() },
            onToggleSelect = { toggleSelect(it) },
            onActivate = { showItemActions(it) },
            onLongPress = { toggleSelect(it) },
            onOpen = { showItemActions(it) },
            loadThumb = { e, iv -> loadThumb(e, iv) }
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

    /** Local bin entries decode from the full file; remote ones use the SMB3 session. */
    private fun loadThumb(e: SmbEntry, target: ImageView) {
        val item = itemFor(e) ?: return
        val key = "recycle|${item.id}"
        target.tag = key
        ThumbnailLoader.get(key)?.let {
            target.scaleType = ImageView.ScaleType.CENTER_CROP
            target.setImageBitmap(it)
            return
        }
        viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val bmp = if (item.host.isBlank() && item.share.isBlank()) {
                ThumbnailLoader.decodeFile(item.trashPath, 320)
            } else {
                vm.readRecycleHead(item, 12 * 1024 * 1024)?.let { ThumbnailLoader.decode(it, 320) }
            }
            if (bmp != null) ThumbnailLoader.put(key, bmp)
            withContext(Dispatchers.Main) {
                if (target.tag == key && bmp != null && _binding != null) {
                    target.scaleType = ImageView.ScaleType.CENTER_CROP
                    target.setImageBitmap(bmp)
                }
            }
        }
    }

    // ---- selection ----------------------------------------------------------------------

    private fun toggleSelect(e: SmbEntry) {
        val idx = entries.indexOfFirst { it.path == e.path }
        selection.toggle(e.path, idx)
    }

    private fun selectedItems(): List<RecycleItem> =
        vm.recycleItems.value.filter { selection.isSelected(it.id) }

    private fun selectionActions(): List<SelAction> = listOf(
        SelAction(getString(R.string.recycle_batch_delete), R.drawable.ic_sel_delete) { purgeSelected() },
        SelAction(getString(R.string.recycle_batch_restore), R.drawable.ic_sel_restore) { restoreSelected() },
        SelAction(getString(R.string.recycle_properties), R.drawable.ic_sel_info) {
            selectedItems().firstOrNull()?.let(::showProperties)
        },
        SelAction(getString(R.string.sel_web_search), R.drawable.ic_sel_web) {
            val name = selectedItems().firstOrNull()?.name
            if (name == null) {
                Toast.makeText(requireContext(), R.string.more_need_selection, Toast.LENGTH_SHORT).show()
            } else webSearch(name)
        },
        SelAction(getString(R.string.sel_more), R.drawable.ic_action_more) {
            showMoreMenu(binding.recycler)
        }
    )

    private fun showItemActions(e: SmbEntry) {
        val item = itemFor(e) ?: return
        val labels = arrayOf(
            getString(R.string.recycle_restore),
            getString(R.string.recycle_purge),
            getString(R.string.recycle_properties)
        )
        AlertDialog.Builder(requireContext())
            .setTitle(item.name)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> restoreOne(item)
                    1 -> confirmPurgeOne(item)
                    2 -> showProperties(item)
                }
            }
            .show()
    }

    private fun restoreOne(item: RecycleItem) = vm.restoreMany(listOf(item)) { out ->
        report(out, R.string.recycle_restored, R.string.recycle_restored_partial,
            R.string.recycle_restored_partial)
    }

    private fun confirmPurgeOne(item: RecycleItem) {
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.recycle_purge)
            .setMessage(getString(R.string.recycle_confirm_purge, item.name))
            .setPositiveButton(R.string.ok) { _, _ ->
                vm.purgeMany(listOf(item)) { out ->
                    report(out, R.string.recycle_purged, R.string.recycle_purged_soft,
                        R.string.recycle_purged_soft)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** One batch call instead of N racing coroutines; records are removed in a single write. */
    private fun restoreSelected() {
        val picked = selectedItems()
        if (picked.isEmpty()) return
        vm.restoreMany(picked) { out ->
            selection.cancel()
            report(out, R.string.recycle_restored, R.string.recycle_restored_partial,
                R.string.recycle_restored_partial)
        }
    }

    private fun purgeSelected() {
        val picked = selectedItems()
        if (picked.isEmpty()) return
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.recycle_purge)
            .setMessage(getString(R.string.recycle_confirm_batch_delete, picked.size))
            .setPositiveButton(R.string.ok) { _, _ ->
                vm.purgeMany(picked) { out ->
                    selection.cancel()
                    report(out, R.string.recycle_purged, R.string.recycle_purged_soft,
                        R.string.recycle_purged_soft)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Honest feedback: never claim success while rows are still in the bin. */
    private fun report(
        out: BrowserViewModel.RecycleOutcome,
        allOk: Int,
        partialRes: Int,
        keptRes: Int
    ) {
        if (!isAdded) return
        val msg = when {
            out.kept > 0 ->
                getString(keptRes, out.removed, out.kept, out.error.orEmpty())
            out.soft > 0 ->
                getString(partialRes, out.removed, out.soft, out.error.orEmpty())
            out.removed == 0 -> getString(R.string.recycle_empty)
            else -> getString(allOk)
        }
        Toast.makeText(requireContext(), msg, Toast.LENGTH_LONG).show()
        render()
    }

    private fun confirmEmpty() {
        if (vm.recycleItems.value.isEmpty()) {
            Toast.makeText(requireContext(), R.string.recycle_empty, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.recycle_empty_all)
            .setMessage(R.string.recycle_confirm_clear)
            .setPositiveButton(R.string.ok) { _, _ ->
                vm.emptyRecycle { out ->
                    selection.cancel()
                    report(out, R.string.recycle_cleared, R.string.recycle_cleared_soft,
                        R.string.recycle_cleared_soft)
                }
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    private fun showProperties(item: RecycleItem) {
        val body = getString(
            R.string.recycle_prop_body,
            item.name,
            if (item.isDirectory) getString(R.string.folder) else
                item.name.substringAfterLast('.', "?").uppercase(),
            if (item.isDirectory) "—" else formatSize(item.size),
            fmt.format(Date(item.deletedAt)),
            "${item.host}/${item.share}/${item.path}".trim('/')
        )
        AlertDialog.Builder(requireContext())
            .setTitle(R.string.recycle_properties)
            .setMessage(body)
            .setPositiveButton(R.string.ok, null)
            .show()
    }

    private fun webSearch(name: String) {
        try {
            val url = "https://www.baidu.com/s?wd=" + URLEncoder.encode(name, "UTF-8")
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            Toast.makeText(requireContext(), R.string.no_player, Toast.LENGTH_SHORT).show()
        }
    }

    // ---- ES overflow menu (same sixteen entries as the file pages) ----------------------

    private fun showMoreMenu(anchor: View) {
        val ctx = requireContext()
        val env = MoreEnv(
            ctx = ctx,
            selected = { selectedItems().map(::toEntry) },
            isLocal = true,
            bookmark = { Toast.makeText(ctx, R.string.more_need_local, Toast.LENGTH_SHORT).show() },
            play = { openLocal(it) },
            share = { localFile(it)?.let { f -> FileOps.share(ctx, f) } },
            compress = { Toast.makeText(ctx, R.string.more_need_local, Toast.LENGTH_SHORT).show() },
            moveCopy = { _, _ -> Toast.makeText(ctx, R.string.more_need_local, Toast.LENGTH_SHORT).show() },
            pin = { list ->
                FileOps.pin(ctx, list.map { it.path })
                Toast.makeText(ctx, R.string.op_done, Toast.LENGTH_SHORT).show()
            },
            properties = { e -> itemFor(e)?.let(::showProperties) },
            openWith = { e -> localFile(e)?.let { f -> FileOps.openWith(ctx, f) } },
            shortcut = { e ->
                localFile(e)?.let { f -> FileOps.addShortcut(ctx, f, e.name) }
            }
        )
        val extras = listOf(
            MoreItem(getString(R.string.recycle_clear_all), R.drawable.ic_sel_delete) { confirmEmpty() },
            MoreItem(getString(R.string.tool_view), R.drawable.ic_action_view) { showViewDialog() },
            MoreItem(getString(R.string.tool_refresh), R.drawable.ic_action_refresh) { vm.reloadRecycle() }
        )
        MoreMenu.show(ctx, anchor, MoreActions.build(env) + extras)
    }

    private fun localFile(e: SmbEntry): File? {
        val item = itemFor(e) ?: return null
        if (item.host.isNotBlank() || item.share.isNotBlank()) return null
        val f = File(item.trashPath)
        return if (f.exists()) f else null
    }

    private fun openLocal(e: SmbEntry) {
        val f = localFile(e)
        if (f == null) {
            Toast.makeText(requireContext(), R.string.more_need_local, Toast.LENGTH_SHORT).show()
        } else {
            FileOps.openWith(requireContext(), f)
        }
    }

    // ---- PageActions --------------------------------------------------------------------

    override fun toolbarActions(): List<Action> =
        listOf(Action.EMPTY_ALL, Action.REFRESH, Action.VIEW, Action.WINDOW, Action.HISTORY)

    override fun onToolbarAction(a: Action): Boolean {
        if (_binding == null) return false
        return when (a) {
            Action.EMPTY_ALL -> { confirmEmpty(); true }
            Action.REFRESH -> { vm.reloadRecycle(); true }
            Action.VIEW -> { showViewDialog(); true }
            Action.MORE -> { showMoreMenu(binding.recycler); true }
            else -> false
        }
    }

    override fun onBackPressed(): Boolean {
        if (_binding == null) return false
        return if (selection.isActive()) {
            selection.cancel()
            true
        } else false
    }

    private fun showViewDialog() {
        ViewSortDialog.show(
            requireContext(), viewMode, sortMode,
            onView = {
                viewMode = it
                prefs().edit().putString("view", it.key).apply()
                rebuildAdapter(true)
            },
            onSort = {
                sortMode = it
                prefs().edit().putString("sort", it.key).apply()
                render()
            }
        )
    }
}
