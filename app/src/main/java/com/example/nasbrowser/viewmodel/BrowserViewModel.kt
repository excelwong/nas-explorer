package com.example.nasbrowser.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.nasbrowser.CancelHub
import com.example.nasbrowser.DownloadService
import com.example.nasbrowser.model.ConnectionParams
import com.example.nasbrowser.model.SortMode
import com.example.nasbrowser.model.TransferItem
import com.example.nasbrowser.model.TransferState
import com.example.nasbrowser.model.ViewMode
import com.example.nasbrowser.smb.AnalysisResult
import com.example.nasbrowser.smb.DiskSpace
import com.example.nasbrowser.smb.SmbClientManager
import com.example.nasbrowser.smb.SmbConnectionPool
import com.example.nasbrowser.smb.SmbEntry
import com.example.nasbrowser.smb.SmbQueryOps
import com.example.nasbrowser.smb.SmbShareLister
import com.example.nasbrowser.smb.SmbWriteOps
import com.example.nasbrowser.util.ClipboardState
import com.example.nasbrowser.util.ConflictAnswer
import com.example.nasbrowser.util.ConflictDecision
import com.example.nasbrowser.util.CrossPasteResult
import com.example.nasbrowser.util.PasteCancelledException
import com.example.nasbrowser.util.PasteProgress
import com.example.nasbrowser.util.PasteSpec
import com.example.nasbrowser.util.PasteSummary
import com.example.nasbrowser.util.PasteTaskItem
import com.example.nasbrowser.util.PrefsStore
import com.example.nasbrowser.util.RecycleItem
import com.example.nasbrowser.util.RecycleStore
import com.example.nasbrowser.util.TransferRepository
import com.example.nasbrowser.util.UiPrefs
import com.example.nasbrowser.util.ZipUtils
import com.example.nasbrowser.util.guessMime
import com.example.nasbrowser.util.movePartToDownloads
import com.example.nasbrowser.util.partTempFile
import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2CreateOptions
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.share.DiskShare
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.util.EnumSet
import java.util.LinkedHashMap
import java.util.UUID

/** Result of preparing a media file locally so it can be opened by an external app. */
data class OpenResult(val file: File, val mime: String)

/** The search box either filters the current page or walks the whole subtree under it. */
enum class SearchMode { FILTER, RECURSIVE }

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    companion object {
        /** Connection-pool size for *browsing* (separate from the download service's pool). */
        const val CONCURRENCY = 4
        /** Application-side read buffer for SMB file transfers (1 MB -> big READs, fewer round-trips). */
        private const val READ_BUF = 1024 * 1024
    }

    private val _connection = MutableStateFlow<ConnectionParams?>(null)
    val connection = _connection.asStateFlow()

    /** The share currently being browsed, or null when showing the server's share list (root). */
    private val _currentShare = MutableStateFlow<String?>(null)
    val currentShare = _currentShare.asStateFlow()

    /** Path within the current share (ignored when _currentShare is null). */
    private val _currentPath = MutableStateFlow("")
    val currentPath = _currentPath.asStateFlow()

    private val _items = MutableStateFlow<List<SmbEntry>>(emptyList())
    val items = _items.asStateFlow()

    private val _isBusy = MutableStateFlow(false)
    val isBusy = _isBusy.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    /** SMB dialect negotiated with the NAS (shown in the wide-screen detail pane). */
    private val _dialect = MutableStateFlow("")
    val dialect = _dialect.asStateFlow()

    /** Set when automatic share enumeration fails; the UI then prompts for a share name. */
    private val _manualShareNeeded = MutableStateFlow(false)
    val manualShareNeeded = _manualShareNeeded.asStateFlow()

    // ---- display preferences -----------------------------------------------------------

    private val _sortMode = MutableStateFlow(UiPrefs.loadSortMode(app))
    val sortMode = _sortMode.asStateFlow()

    private val _viewMode = MutableStateFlow(UiPrefs.loadViewMode(app))
    val viewMode = _viewMode.asStateFlow()

    private val _showThumbnails = MutableStateFlow(UiPrefs.showThumbnails(app))
    val showThumbnails = _showThumbnails.asStateFlow()

    private val _showNewFiles = MutableStateFlow(UiPrefs.showNewFiles(app))
    val showNewFiles = _showNewFiles.asStateFlow()

    private val _showDiskSpace = MutableStateFlow(UiPrefs.showDiskSpace(app))
    val showDiskSpace = _showDiskSpace.asStateFlow()

    private val _showHidden = MutableStateFlow(UiPrefs.showHidden(app))
    val showHidden = _showHidden.asStateFlow()

    private val _onlyNewFiles = MutableStateFlow(UiPrefs.onlyNewFiles(app))
    val onlyNewFiles = _onlyNewFiles.asStateFlow()

    private val _newFileDays = MutableStateFlow(UiPrefs.newFileDays(app))
    val newFileDays = _newFileDays.asStateFlow()

    /** Entries modified after this instant are flagged "新" when the option is on. */
    val newThreshold: Long
        get() = System.currentTimeMillis() - _newFileDays.value * 24L * 3600L * 1000L

    // ---- search / analysis / capacity / recycle bin -------------------------------------

    private val _searchMode = MutableStateFlow(SearchMode.FILTER)
    val searchMode = _searchMode.asStateFlow()

    private val _searchLabel = MutableStateFlow("")
    val searchLabel = _searchLabel.asStateFlow()

    private val _diskSpace = MutableStateFlow<DiskSpace?>(null)
    val diskSpace = _diskSpace.asStateFlow()

    private val _recycleItems = MutableStateFlow<List<RecycleItem>>(emptyList())
    val recycleItems = _recycleItems.asStateFlow()

    // ---- selection ---------------------------------------------------------------------

    internal data class Selected(val entry: SmbEntry, val share: String)
    private val _selection = MutableStateFlow<LinkedHashMap<String, Selected>>(LinkedHashMap())
    internal val selection = _selection.asStateFlow()

    /**
     * The subset of [_selection] whose paths are actually present on the page currently shown.
     * Using the rendered item list (instead of "direct child of current path") makes multi-select
     * work for recursive search results too, and still guarantees that navigating away from a
     * folder can never drag its stale selection into a download batch.
     */
    internal val visibleSelection: kotlinx.coroutines.flow.StateFlow<Map<String, Selected>> =
        combine(_selection, _currentShare, _items) { sel, share, items ->
            val shown = items.mapTo(HashSet()) { it.path }
            sel.filterValues { it.share == share && it.entry.path in shown }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    // ---- transfers ---------------------------------------------------------------------

    /** Transfer list owned by [TransferRepository] (shared with the background download service). */
    val transfers = TransferRepository.transfers

    private val _openProgress = MutableStateFlow(false)
    val openProgress = _openProgress.asStateFlow()

    private val _openResult = MutableStateFlow<OpenResult?>(null)
    val openResult = _openResult.asStateFlow()

    // ---- per-window (tab) directory state -----------------------------------------------

    private val _activeTabId = MutableStateFlow("")
    /** Lets hidden pages ignore toasts / dialogs emitted by another tab. */
    val activeTabId = _activeTabId.asStateFlow()

    private data class TabSnapshot(
        val params: ConnectionParams?,
        val share: String?,
        val path: String,
        val items: List<SmbEntry>,
        val diskSpace: DiskSpace?,
        val searchMode: SearchMode,
        val searchLabel: String,
        val selection: LinkedHashMap<String, Selected>,
        val sortMode: SortMode,
        val viewMode: ViewMode
    )

    private val snapshots = HashMap<String, TabSnapshot>()

    private var manager: SmbClientManager? = null
    private var pool: SmbConnectionPool? = null

    /** Serializes every use of the single browsing session (list / query / write). */
    private val shareLock = Any()
    private var globalSort: SortMode = _sortMode.value
    private var globalView: ViewMode = _viewMode.value

    init {
        TransferRepository.attach(getApplication())
        refreshRecycle()
    }

    private fun toSmbPath(p: String): String = p.replace('/', '\\').trim('\\')

    /** Run a block on the browsing DiskShare, serialized against list() calls. */
    private fun <T> runShare(share: String, block: (DiskShare) -> T): T =
        synchronized(shareLock) { manager!!.withShare(share, block) }

    private fun listOn(share: String, path: String): List<SmbEntry> =
        synchronized(shareLock) { manager!!.list(share, path) }

    // ---- per-window (tab) directory state -----------------------------------------------

    /**
     * Switch the shared browsing session to window [id]. Every directory tab keeps its own
     * listing, path, selection, view and sort, so opening a second folder never disturbs the
     * first one. A brand-new SMB window is connected through [launch].
     */
    fun activateTab(id: String, launch: com.example.nasbrowser.SmbLaunch? = null) {
        if (id.isEmpty() || id == _activeTabId.value) return
        if (_activeTabId.value.isNotEmpty()) snapshots[_activeTabId.value] = capture()
        _activeTabId.value = id
        val snap = snapshots[id]
        when {
            snap != null -> restore(snap)
            launch != null && launch.share.isBlank() -> connect(launch.params)
            launch != null -> connectAndOpen(launch.params, launch.share, launch.path)
        }
    }

    fun dropTab(id: String) { snapshots.remove(id) }

    /** Location + credentials of one SMB window, used when persisting the session. */
    data class WindowLocation(
        val params: ConnectionParams?,
        val share: String,
        val path: String
    )

    /** Every SMB window's location; the active one is read live rather than from its snapshot. */
    fun windowStates(): Map<String, WindowLocation> {
        val out = HashMap<String, WindowLocation>()
        snapshots.forEach { (id, s) ->
            out[id] = WindowLocation(s.params, s.share.orEmpty(), s.path)
        }
        val live = _activeTabId.value
        if (live.isNotEmpty()) {
            out[live] = WindowLocation(
                _connection.value, _currentShare.value.orEmpty(), _currentPath.value
            )
        }
        return out
    }

    private fun capture() = TabSnapshot(
        _connection.value,
        _currentShare.value,
        _currentPath.value,
        _items.value,
        _diskSpace.value,
        _searchMode.value,
        _searchLabel.value,
        LinkedHashMap(_selection.value),
        _sortMode.value,
        _viewMode.value
    )

    private fun restore(s: TabSnapshot) {
        if (s.params != null && s.params.host != _connection.value?.host) {
            // A window pointing at a different NAS: rebuild the session, then re-list its folder.
            viewModelScope.launch(Dispatchers.IO) {
                _isBusy.value = true
                try {
                    establish(s.params)
                    if (s.share != null) openLocationInternal(s.share, s.path) else loadRoot()
                } catch (e: Exception) {
                    _error.value = "切换窗口失败: ${e.message}"
                } finally {
                    _isBusy.value = false
                }
            }
            return
        }
        _items.value = s.items
        _currentShare.value = s.share
        _currentPath.value = s.path
        _diskSpace.value = s.diskSpace
        _searchMode.value = s.searchMode
        _searchLabel.value = s.searchLabel
        _selection.value = LinkedHashMap(s.selection)
        _sortMode.value = s.sortMode
        _viewMode.value = s.viewMode
        manager?.sortMode = s.sortMode
    }

    /**
     * Jump to an arbitrary breadcrumb segment. [share] null means the server's share list;
     * an empty [path] means the share root.
     */
    fun gotoPath(share: String?, path: String) {
        if (share == null) loadRoot()
        else if (path.isEmpty()) enterShare(share)
        else enterFolder(path)
    }

    // ---- connection & navigation --------------------------------------------------------

    fun connect(params: ConnectionParams) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            _error.value = null
            _manualShareNeeded.value = false
            try {
                establish(params)
                // "目录设置 -> 设为起始目录": open that location directly after connecting.
                val start = UiPrefs.loadStartLocation(getApplication())
                if (start != null && start.host == params.host && start.share.isNotBlank()) {
                    openLocationInternal(start.share, start.path)
                } else {
                    loadRoot()
                }
            } catch (e: Exception) {
                _error.value = "连接失败: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Connect to a (possibly different) NAS and jump straight into a bookmarked location. */
    fun connectAndOpen(params: ConnectionParams, share: String, path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            _error.value = null
            try {
                establish(params)
                openLocationInternal(share, path)
            } catch (e: Exception) {
                _error.value = "打开书签失败: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Rebuild manager + pool from [params]; must be called on Dispatchers.IO. */
    private fun establish(params: ConnectionParams) {
        manager?.disconnect()
        pool?.close()
        val m = SmbClientManager(params.host, params.username, params.password, params.domain)
        m.connect()
        m.sortMode = _sortMode.value
        manager = m
        pool = SmbConnectionPool(params.host, params.username, params.password, params.domain, CONCURRENCY)
        _dialect.value = m.negotiatedDialect
        _connection.value = params
    }

    /** List the server's shares as the "root directory". Falls back to manual entry on failure. */
    fun loadRoot() {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            _error.value = null
            try {
                clearSelection()
                _searchMode.value = SearchMode.FILTER
                _searchLabel.value = ""
                val p = _connection.value ?: throw IllegalStateException("尚未连接")
                val names = SmbShareLister.listShares(p.host, p.username, p.password, p.domain)
                _items.value = names.map { share ->
                    SmbEntry(name = share, path = share, isDirectory = true, size = 0, lastModified = 0)
                }
                _currentShare.value = null
                _currentPath.value = ""
                _diskSpace.value = null
                _manualShareNeeded.value = false
            } catch (e: Exception) {
                _items.value = emptyList()
                _currentShare.value = null
                _currentPath.value = ""
                _manualShareNeeded.value = true
                _error.value = "无法自动列出共享目录: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Re-list the current view (root share list, or the current folder). */
    fun reload() {
        if (_currentShare.value == null) loadRoot() else enterFolder(_currentPath.value)
    }

    fun enterShare(share: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            _error.value = null
            try {
                applyDirPrefs(share, "")
                _items.value = listOn(share, "")
                _currentShare.value = share
                _currentPath.value = ""
                _manualShareNeeded.value = false
                _searchMode.value = SearchMode.FILTER
                _diskSpace.value = queryDiskSpace(share)
            } catch (e: Exception) {
                if (reconnect()) {
                    try {
                        applyDirPrefs(share, "")
                        _items.value = listOn(share, "")
                        _currentShare.value = share
                        _currentPath.value = ""
                        _manualShareNeeded.value = false
                        _diskSpace.value = queryDiskSpace(share)
                    } catch (e2: Exception) {
                        _error.value = "打开共享失败: ${e2.message}"
                    }
                } else {
                    _error.value = "打开共享失败: ${e.message}"
                }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun enterFolder(path: String) {
        val share = _currentShare.value ?: return
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                applyDirPrefs(share, path)
                _items.value = listOn(share, path)
                _currentPath.value = path
                _searchMode.value = SearchMode.FILTER
                _searchLabel.value = ""
                _error.value = null
                _diskSpace.value = queryDiskSpace(share)
            } catch (e: Exception) {
                if (reconnect()) {
                    try {
                        applyDirPrefs(share, path)
                        _items.value = listOn(share, path)
                        _currentPath.value = path
                        _error.value = null
                        _diskSpace.value = queryDiskSpace(share)
                    } catch (e2: Exception) {
                        _error.value = "列目录失败: ${e2.message}"
                    }
                } else {
                    _error.value = "列目录失败: ${e.message}"
                }
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Go up one level; at a share's root, return to the server's share list. */
    fun browseUp() {
        val share = _currentShare.value ?: return
        val cur = _currentPath.value
        if (cur.isEmpty()) loadRoot() else enterFolder(cur.substringBeforeLast('/', ""))
    }

    /** The share name of the active connection (used to label the current path in the UI). */
    fun shareName(): String = _currentShare.value ?: ""

    /** Jump straight to a bookmarked share + path with a single list call. */
    fun openLocation(share: String, path: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                openLocationInternal(share, path)
            } catch (e: Exception) {
                _error.value = "打开书签失败: ${e.message}"
            } finally {
                _isBusy.value = false
            }
        }
    }

    private fun openLocationInternal(share: String, path: String) {
        applyDirPrefs(share, path)
        _items.value = listOn(share, path)
        _currentShare.value = share
        _currentPath.value = path
        _searchMode.value = SearchMode.FILTER
        _searchLabel.value = ""
        _diskSpace.value = queryDiskSpace(share)
        _error.value = null
    }

    private fun queryDiskSpace(share: String): DiskSpace? = try {
        runShare(share) { SmbQueryOps.diskSpace(it) }
    } catch (_: Exception) {
        null
    }

    // ---- selection, sorting and display preferences ------------------------------------

    fun toggleSelection(entry: SmbEntry) {
        val share = _currentShare.value ?: return
        // IMPORTANT: copy first. Mutating the live StateFlow value in place and reassigning an
        // equal instance suppresses the emission because StateFlow compares by equals().
        val map = LinkedHashMap(_selection.value)
        if (map.containsKey(entry.path)) map.remove(entry.path) else map[entry.path] = Selected(entry, share)
        _selection.value = map
    }

    fun clearSelection() {
        _selection.value = LinkedHashMap()
    }

    /** Replace the selection with exactly [entries] (used by 全选). */
    fun selectPaths(entries: List<SmbEntry>) {
        val share = _currentShare.value ?: return
        val map = LinkedHashMap<String, Selected>()
        entries.forEach { map[it.path] = Selected(it, share) }
        _selection.value = map
    }

    /** Add a whole range to the current selection (used by 区间选择). */
    fun addSelectionPaths(entries: List<SmbEntry>) {
        val share = _currentShare.value ?: return
        val map = LinkedHashMap(_selection.value)
        entries.forEach { map[it.path] = Selected(it, share) }
        _selection.value = map
    }

    /**
     * Batch soft-delete, one entry at a time. A single dead session used to abort the whole
     * batch: files already renamed on the server never got recycle-bin records and the list was
     * never refreshed. Now each entry gets at most one reconnect-and-retry, successes are always
     * recorded, and the folder is re-listed even when some entries fail.
     */
    fun deleteEntries(entries: List<SmbEntry>, done: (Throwable?) -> Unit) {
        val host = _connection.value?.host ?: ""
        val share = _currentShare.value ?: ""
        viewModelScope.launch(Dispatchers.IO) {
            var okCount = 0
            var lastError: Throwable? = null
            for (e in entries) {
                var hiddenPath: String? = null
                var attempt = 0
                while (attempt < 2 && hiddenPath == null) {
                    attempt++
                    try {
                        val p = pool ?: throw IllegalStateException("尚未连接")
                        val (_, hp) = p.withShare(share) { ds -> SmbWriteOps.moveToTrash(ds, e) }
                        hiddenPath = hp
                    } catch (ex: Exception) {
                        lastError = ex
                        if (attempt == 1 &&
                            SmbConnectionPool.isTransportFailure(ex) && reconnect()
                        ) continue
                        break
                    }
                }
                val hp = hiddenPath ?: continue
                RecycleStore.add(
                    getApplication(),
                    RecycleItem(
                        id = UUID.randomUUID().toString(),
                        host = host,
                        share = share,
                        path = e.path,
                        trashPath = hp,
                        name = e.name,
                        isDirectory = e.isDirectory,
                        size = e.size,
                        deletedAt = System.currentTimeMillis()
                    )
                )
                okCount++
            }
            refreshRecycle()
            val err = if (okCount == entries.size) null else IllegalStateException(
                "已删除 $okCount/${entries.size} 项：${lastError?.message ?: "连接中断"}"
            )
            withContext(Dispatchers.Main) { clearSelection(); done(err); reload() }
        }
    }

    /** 复制 / 剪切: stream files into [targetDir] (a path inside the current share). */
    fun copyOrMove(entries: List<SmbEntry>, targetDir: String, move: Boolean, done: (Throwable?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = pool ?: throw IllegalStateException("尚未连接")
                val share = _currentShare.value ?: throw IllegalStateException("尚未进入共享目录")
                if (entries.any { it.isDirectory }) {
                    throw IllegalStateException("目录的复制/移动暂不支持，请逐项选择文件")
                }
                p.withShare(share) { ds ->
                    for (e in entries) {
                        val dest = if (targetDir.isBlank()) e.name else "${targetDir.trimEnd('/')}/${e.name}"
                        SmbWriteOps.copyFile(ds, e.path, dest)
                        if (move) SmbWriteOps.delete(ds, e)
                    }
                }
                withContext(Dispatchers.Main) { clearSelection(); done(null); reload() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { done(e) }
            }
        }
    }

    /**
     * v4.1.11: unified clipboard paste engine for all four routes
     * (NAS->NAS, device->device, device->NAS, NAS->device).
     *
     * For every item it checks the destination, asks the UI about a name collision (the UI
     * owns the "应用到全部" policy), streams the bytes with a per-chunk progress callback and,
     * on a CUT, removes the source only after the destination is complete. 取消 aborts between
     * chunks; already finished items are kept, exactly like ES / Windows Explorer.
     */
    suspend fun runPasteTransfer(
        clip: ClipboardState,
        spec: PasteSpec,
        ask: suspend (String) -> ConflictAnswer?,
        report: (PasteProgress) -> Unit,
        cancelled: () -> Boolean
    ): PasteSummary = withContext(Dispatchers.IO) {
        val src = clip.source
        val p = pool
        val share = if (spec.targetLocal) src.share else _currentShare.value.orEmpty()
        val targetSmbDir = spec.targetSmbDir.trimEnd('/')
        val targetDir = spec.targetDir
        val bothLocal = src.local && spec.targetLocal

        if (!bothLocal && p == null) {
            return@withContext PasteSummary(0, 0, spec.items.size, "尚未连接 NAS", false)
        }
        if (!spec.targetLocal && share.isBlank()) {
            return@withContext PasteSummary(0, 0, spec.items.size, "尚未进入共享目录", false)
        }
        if (spec.targetLocal && targetDir == null) {
            return@withContext PasteSummary(0, 0, spec.items.size, "未指定本机目标目录", false)
        }
        if (spec.targetLocal && !src.local && src.host.isNotBlank() &&
            _connection.value?.host != src.host
        ) {
            return@withContext PasteSummary(
                0, 0, spec.items.size, "请先回到源 NAS 所在的窗口", false
            )
        }

        // Local folders have no cheap size; walk them (bounded) so the total progress is honest.
        val tasks: List<PasteTaskItem> = spec.items.map { item ->
            if (bothLocal && item.isDirectory && item.size <= 0L) {
                item.copy(size = dirSize(File(item.path)))
            } else item
        }
        val totalItems = tasks.size
        val totalBytes = tasks.fold(0L) { acc, item -> acc + item.size.coerceAtLeast(0L) }
        var doneBytes = 0L
        var ok = 0
        var skipped = 0
        var failed = 0
        var firstError: String? = null
        var wasCancelled = false

        fun progress(
            index: Int,
            name: String,
            currentBytes: Long,
            currentTotal: Long,
            includeCurrent: Boolean = true
        ) {
            val cur = currentBytes.coerceAtLeast(0L)
            report(
                PasteProgress(
                    currentName = name,
                    currentBytes = cur,
                    currentTotal = currentTotal.coerceAtLeast(0L),
                    doneItems = index,
                    totalItems = totalItems,
                    doneBytes = doneBytes + if (includeCurrent) cur else 0L,
                    totalBytes = totalBytes
                )
            )
        }

        for ((index, item) in tasks.withIndex()) {
            if (cancelled()) {
                wasCancelled = true
                break
            }
            val itemSize = item.size.coerceAtLeast(0L)
            val exists = try {
                when {
                    spec.targetLocal -> File(targetDir!!, item.name).exists()
                    share.isBlank() -> false
                    else -> p!!.withShare(share) { ds ->
                        SmbWriteOps.exists(
                            ds,
                            if (targetSmbDir.isBlank()) item.name
                            else "$targetSmbDir/${item.name}"
                        )
                    }
                }
            } catch (_: Exception) {
                false
            }
            val overwrite = exists
            if (exists) {
                val answer = withContext(Dispatchers.Main) { ask(item.name) }
                if (answer == null || answer.decision == ConflictDecision.SKIP) {
                    skipped++
                    doneBytes += itemSize
                    progress(index + 1, item.name, itemSize, itemSize, false)
                    continue
                }
            }

            var transferred = 0L
            val onBytes: (Long) -> Unit = { n ->
                transferred += n
                progress(index, item.name, transferred, itemSize)
                if (cancelled()) throw PasteCancelledException()
            }
            try {
                when {
                    bothLocal -> copyLocal(item, targetDir!!, spec.move, onBytes)
                    !src.local && !spec.targetLocal ->
                        copyRemote(p!!, share, item, targetSmbDir, spec.move, overwrite, onBytes)
                    src.local && !spec.targetLocal ->
                        uploadOne(p!!, share, item, targetSmbDir, spec.move, overwrite, onBytes)
                    else -> downloadOne(p!!, src.share, item, targetDir!!, spec.move, onBytes)
                }
                ok++
                doneBytes += itemSize
                progress(index + 1, item.name, itemSize, itemSize, false)
            } catch (_: PasteCancelledException) {
                wasCancelled = true
                break
            } catch (e: Exception) {
                failed++
                if (firstError == null) firstError = e.message ?: e.javaClass.simpleName
                doneBytes += itemSize
                progress(index + 1, item.name, itemSize, itemSize, false)
            }
        }
        PasteSummary(ok, skipped, failed, firstError, wasCancelled)
    }

    private fun dirSize(dir: File): Long {
        var total = 0L
        var count = 0
        for (child in dir.walkTopDown()) {
            if (child.isFile) {
                total += child.length()
                if (++count >= 5000) break
            }
        }
        return total
    }

    /** device -> device. Rename is tried first for CUT so it stays instantaneous on one volume. */
    private fun copyLocal(
        item: PasteTaskItem,
        target: File,
        move: Boolean,
        onBytes: (Long) -> Unit
    ) {
        val src = File(item.path)
        if (!src.exists()) throw IllegalStateException("源文件不存在：${item.name}")
        val dest = File(target, item.name)
        if (src.absolutePath == dest.absolutePath) {
            throw IllegalStateException("源和目标相同：${item.name}")
        }
        // A same-volume CUT is still instantaneous, but never onto an existing entry: Java's
        // renameTo() behaviour there is platform-dependent.
        if (move && !dest.exists() && src.renameTo(dest)) {
            onBytes(item.size)
            return
        }
        // Write into a hidden temp file first so 取消 (or a broken pipe) cannot leave a
        // half-written file behind, and cannot damage an existing file we were asked to replace.
        val tmp = File(target, ".naspart_${UUID.randomUUID()}")
        try {
            if (src.isDirectory) {
                src.copyRecursively(tmp, overwrite = true)
                onBytes(item.size)
            } else {
                src.inputStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1024 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            onBytes(n.toLong())
                        }
                    }
                }
            }
            if (dest.exists()) {
                val gone = if (dest.isDirectory) dest.deleteRecursively() else dest.delete()
                if (!gone && dest.exists()) {
                    throw IllegalStateException("无法覆盖同名文件 ${item.name}")
                }
            }
            if (!tmp.renameTo(dest)) {
                if (tmp.isDirectory) tmp.copyRecursively(dest, overwrite = true)
                else tmp.copyTo(dest, overwrite = true)
                tmp.deleteRecursively()
            }
            if (move) {
                val gone = if (src.isDirectory) src.deleteRecursively() else src.delete()
                if (!gone && src.exists()) {
                    throw IllegalStateException("本机源文件未能删除：${item.name}")
                }
            }
        } catch (e: PasteCancelledException) {
            try { tmp.deleteRecursively() } catch (_: Exception) {}
            throw e
        } finally {
            try { if (tmp.exists()) tmp.deleteRecursively() } catch (_: Exception) {}
        }
    }

    /** NAS -> NAS: server-side copy, then remove the source on a CUT. */
    private fun copyRemote(
        p: SmbConnectionPool,
        share: String,
        item: PasteTaskItem,
        targetSmbDir: String,
        move: Boolean,
        overwrite: Boolean,
        onBytes: (Long) -> Unit
    ) {
        val dest = if (targetSmbDir.isBlank()) item.name else "$targetSmbDir/${item.name}"
        try {
            p.withShare(share) { ds ->
                SmbWriteOps.copyFile(ds, item.path, dest, onBytes)
                if (move) {
                    SmbWriteOps.delete(ds, SmbEntry(item.name, item.path, false, item.size, 0L))
                }
            }
        } catch (e: PasteCancelledException) {
            // Only a brand-new destination can be cleaned up safely; an overwritten file was
            // already replaced by FILE_OVERWRITE_IF and must not be removed a second time.
            if (!overwrite) {
                try {
                    p.withShare(share) { ds -> SmbWriteOps.removeFile(ds, targetSmbDir, item.name) }
                } catch (_: Exception) {}
            }
            throw e
        }
    }

    /** device -> NAS: stream the local file up, then remove the local original on a CUT. */
    private fun uploadOne(
        p: SmbConnectionPool,
        share: String,
        item: PasteTaskItem,
        targetSmbDir: String,
        move: Boolean,
        overwrite: Boolean,
        onBytes: (Long) -> Unit
    ) {
        val f = File(item.path)
        if (!f.isFile) throw IllegalStateException("源文件不存在：${item.name}")
        try {
            p.withShare(share) { ds ->
                SmbWriteOps.upload(ds, targetSmbDir, item.name, FileInputStream(f), onBytes)
            }
        } catch (e: PasteCancelledException) {
            if (!overwrite) {
                try {
                    p.withShare(share) { ds -> SmbWriteOps.removeFile(ds, targetSmbDir, item.name) }
                } catch (_: Exception) {}
            }
            throw e
        }
        if (move && !f.delete() && f.exists()) {
            throw IllegalStateException("本机源文件未能删除：${item.name}")
        }
    }

    /** NAS -> device: download into a hidden temp file, then atomically replace the target. */
    private fun downloadOne(
        p: SmbConnectionPool,
        share: String,
        item: PasteTaskItem,
        target: File,
        move: Boolean,
        onBytes: (Long) -> Unit
    ) {
        val dest = File(target, item.name)
        val tmp = File(target, ".naspart_${UUID.randomUUID()}")
        try {
            p.withShare(share) { ds ->
                val remote = ds.openFile(
                    toSmbPath(item.path),
                    EnumSet.of(AccessMask.GENERIC_READ),
                    EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                    EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                    SMB2CreateDisposition.FILE_OPEN,
                    EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                )
                try {
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(READ_BUF)
                        var offset = 0L
                        while (true) {
                            val n = remote.read(buf, offset)
                            if (n <= 0) break
                            out.write(buf, 0, n)
                            offset += n
                            onBytes(n.toLong())
                        }
                    }
                } finally {
                    try { remote.close() } catch (_: Exception) {}
                }
            }
            if (dest.exists() && !dest.delete()) {
                throw IllegalStateException("无法覆盖同名文件 ${item.name}")
            }
            if (!tmp.renameTo(dest)) {
                tmp.copyTo(dest, overwrite = true)
                tmp.delete()
            }
            if (move) {
                p.withShare(share) { ds ->
                    SmbWriteOps.delete(ds, SmbEntry(item.name, item.path, false, item.size, 0L))
                }
            }
        } finally {
            try { if (tmp.exists()) tmp.delete() } catch (_: Exception) {}
        }
    }

    /**
     * Cross-type paste NAS -> device: stream the staged remote files straight into [targetDir].
     *
     * The SMB pool is Activity-scoped and survives a switch to a 本机 tab, so no re-login is
     * needed. Every file goes through a `.naspart_*` temp file first; a CUT deletes its remote
     * original only after that file landed completely, so a broken pipe can never lose data.
     */
    suspend fun downloadFromNas(
        host: String,
        share: String,
        files: List<SmbEntry>,
        targetDir: File,
        move: Boolean
    ): CrossPasteResult = withContext(Dispatchers.IO) {
        val p = pool
            ?: return@withContext CrossPasteResult(0, files.size, "尚未连接 NAS")
        if (host.isNotBlank() && _connection.value?.host != host) {
            return@withContext CrossPasteResult(0, files.size, "请先回到源 NAS 所在的窗口")
        }
        var ok = 0
        var failed = 0
        var firstError: String? = null
        for (e in files) {
            val dest = File(targetDir, e.name)
            val tmp = File(targetDir, ".naspart_${UUID.randomUUID()}")
            try {
                p.withShare(share) { ds ->
                    val remote = ds.openFile(
                        toSmbPath(e.path),
                        EnumSet.of(AccessMask.GENERIC_READ),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                    )
                    try {
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(READ_BUF)
                            var offset = 0L
                            while (true) {
                                val n = remote.read(buf, offset)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                offset += n
                            }
                        }
                    } finally {
                        try { remote.close() } catch (_: Exception) {}
                    }
                }
                if (dest.exists() && !dest.delete()) {
                    throw IllegalStateException("无法覆盖同名文件 ${e.name}")
                }
                if (!tmp.renameTo(dest)) {
                    tmp.copyTo(dest, overwrite = true)
                    tmp.delete()
                }
                if (move) p.withShare(share) { ds -> SmbWriteOps.delete(ds, e) }
                ok++
            } catch (ex: Exception) {
                failed++
                if (firstError == null) firstError = ex.message ?: ex.javaClass.simpleName
                try { tmp.delete() } catch (_: Exception) {}
            }
        }
        CrossPasteResult(ok, failed, firstError)
    }

    /**
     * Cross-type paste device -> NAS: upload the staged local files into the current remote
     * directory. On CUT each local original is removed only after its upload succeeded.
     */
    suspend fun uploadToNas(files: List<File>, move: Boolean): CrossPasteResult =
        withContext(Dispatchers.IO) {
            val p = pool
                ?: return@withContext CrossPasteResult(0, files.size, "尚未连接 NAS")
            val share = _currentShare.value
                ?: return@withContext CrossPasteResult(0, files.size, "尚未进入共享目录")
            val parent = _currentPath.value
            var ok = 0
            var failed = 0
            var firstError: String? = null
            for (f in files) {
                try {
                    p.withShare(share) { ds ->
                        FileInputStream(f).use { input ->
                            SmbWriteOps.upload(ds, parent, f.name, input)
                        }
                    }
                    ok++
                    if (move && !f.delete()) {
                        failed++
                        if (firstError == null) firstError = "本机源文件未能删除：${f.name}"
                    }
                } catch (ex: Exception) {
                    failed++
                    if (firstError == null) firstError = ex.message ?: ex.javaClass.simpleName
                }
            }
            CrossPasteResult(ok, failed, firstError)
        }

    /** 压缩: download the selected remote files to cache, zip them, upload the archive here. */
    fun compressSelected(entries: List<SmbEntry>, zipName: String, done: (Throwable?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = pool ?: throw IllegalStateException("尚未连接")
                val share = _currentShare.value ?: throw IllegalStateException("尚未进入共享目录")
                val cached = entries.filter { !it.isDirectory }
                    .mapNotNull { fetchToCache(share, it) }
                if (cached.isEmpty()) throw IllegalStateException("没有可压缩的文件")
                val zip = File(getApplication<Application>().cacheDir, zipName)
                ZipUtils.zipFiles(cached, zip)
                p.withShare(share) { ds ->
                    FileInputStream(zip).use { input ->
                        SmbWriteOps.upload(ds, _currentPath.value, zipName, input)
                    }
                }
                zip.delete()
                withContext(Dispatchers.Main) { clearSelection(); done(null); reload() }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { done(e) }
            }
        }
    }

    /** Share / open a remote file: materialize it in the private cache first. */
    suspend fun cacheForShare(entry: SmbEntry): File? {
        val share = _currentShare.value ?: return null
        return fetchToCache(share, entry)
    }

    /** Read a recycle-bin image's bytes so its thumbnail can be shown in the bin page. */
    suspend fun readRecycleHead(item: RecycleItem, maxBytes: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val p = pool ?: return@withContext null
            try {
                p.withShare(item.share) { ds ->
                    // Legacy records may point at the wrong folder; also try the share root.
                    val candidates = listOf(
                        item.trashPath, item.trashPath.substringAfterLast('/')
                    ).distinct()
                    for (c in candidates) {
                        val bytes = try {
                            SmbQueryOps.readHead(ds, c, maxBytes)
                        } catch (_: Exception) {
                            null
                        }
                        if (bytes != null && bytes.isNotEmpty()) return@withShare bytes
                    }
                    null
                }
            } catch (_: Exception) {
                null
            }
        }

    /** Global defaults plus an optional per-directory override. */
    private fun applyDirPrefs(share: String, path: String) {
        val key = UiPrefs.dirKey(_connection.value?.host ?: "", share, path)
        val p = UiPrefs.loadDirPrefs(getApplication(), key)
        val sort = p?.sort ?: globalSort
        val view = p?.view ?: globalView
        _sortMode.value = sort
        _viewMode.value = view
        manager?.sortMode = sort
    }

    private fun saveCurrentDirPrefs() {
        val share = _currentShare.value ?: return
        val key = UiPrefs.dirKey(_connection.value?.host ?: "", share, _currentPath.value)
        UiPrefs.saveDirPrefs(getApplication(), key, _viewMode.value, _sortMode.value)
    }

    fun currentDirKey(): String = UiPrefs.dirKey(
        _connection.value?.host ?: "",
        _currentShare.value ?: "",
        _currentPath.value
    )

    fun setSortMode(mode: SortMode) {
        globalSort = mode
        UiPrefs.saveSortMode(getApplication(), mode)
        _sortMode.value = mode
        manager?.sortMode = mode
        saveCurrentDirPrefs()
        reload()
    }

    fun setViewMode(mode: ViewMode) {
        globalView = mode
        UiPrefs.saveViewMode(getApplication(), mode)
        _viewMode.value = mode
        saveCurrentDirPrefs()
    }

    /** Forget this directory's view/sort override and fall back to the global defaults. */
    fun resetCurrentDirPrefs() {
        UiPrefs.clearDirPrefs(getApplication(), currentDirKey())
        _viewMode.value = globalView
        _sortMode.value = globalSort
        manager?.sortMode = globalSort
        reload()
    }

    fun setShowThumbnails(v: Boolean) {
        _showThumbnails.value = v
        UiPrefs.saveShowThumbnails(getApplication(), v)
    }

    fun setShowNewFiles(v: Boolean) {
        _showNewFiles.value = v
        UiPrefs.saveShowNewFiles(getApplication(), v)
    }

    fun setShowDiskSpace(v: Boolean) {
        _showDiskSpace.value = v
        UiPrefs.saveShowDiskSpace(getApplication(), v)
    }

    fun setShowHidden(v: Boolean) {
        _showHidden.value = v
        UiPrefs.saveShowHidden(getApplication(), v)
    }

    /** "只看新文件": fold the list down to entries modified within [newFileDays]. */
    fun setOnlyNewFiles(v: Boolean) {
        _onlyNewFiles.value = v
        UiPrefs.saveOnlyNewFiles(getApplication(), v)
    }

    fun setNewFileDays(d: Int) {
        _newFileDays.value = d
        UiPrefs.saveNewFileDays(getApplication(), d)
    }

    // ---- recursive search / storage analyser -------------------------------------------

    fun searchRecursive(query: String, done: (Int, Throwable?) -> Unit) {
        val share = _currentShare.value
        if (share == null) {
            done(0, IllegalStateException("请先进入共享目录"))
            return
        }
        val root = _currentPath.value
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                val result = runShare(share) { SmbQueryOps.search(it, root, query) }
                _searchMode.value = SearchMode.RECURSIVE
                _searchLabel.value = query
                _items.value = result
                clearSelection()
                withContext(Dispatchers.Main) { done(result.size, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { done(0, e) }
            } finally {
                _isBusy.value = false
            }
        }
    }

    fun exitSearch() {
        _searchMode.value = SearchMode.FILTER
        _searchLabel.value = ""
        reload()
    }

    fun analyzeCurrent(done: (AnalysisResult?, Throwable?) -> Unit) {
        val share = _currentShare.value
        if (share == null) {
            done(null, IllegalStateException("请先进入共享目录"))
            return
        }
        val root = _currentPath.value
        viewModelScope.launch(Dispatchers.IO) {
            _isBusy.value = true
            try {
                val result = runShare(share) { SmbQueryOps.analyze(it, root) }
                withContext(Dispatchers.Main) { done(result, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { done(null, e) }
            } finally {
                _isBusy.value = false
            }
        }
    }

    /** Read the head of a remote file for the thumbnail loader; uses the concurrent pool. */
    suspend fun readHead(path: String, maxBytes: Int): ByteArray? = withContext(Dispatchers.IO) {
        val share = _currentShare.value ?: return@withContext null
        val p = pool ?: return@withContext null
        try {
            p.withShare(share) { SmbQueryOps.readHead(it, path, maxBytes) }
        } catch (_: Exception) {
            null
        }
    }

    // ---- text files ---------------------------------------------------------------------

    fun createTextFile(name: String, content: String, done: (Throwable?) -> Unit) =
        runWrite(done) { ds, path -> SmbWriteOps.createText(ds, path, name, content) }

    fun readTextFile(entry: SmbEntry, done: (String?, Throwable?) -> Unit) {
        val share = _currentShare.value
        if (share == null) {
            done(null, IllegalStateException("请先进入共享目录"))
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val text = runShare(share) { SmbQueryOps.readText(it, entry.path) }
                withContext(Dispatchers.Main) { done(text, null) }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { done(null, e) }
            }
        }
    }

    fun saveTextFile(entry: SmbEntry, content: String, done: (Throwable?) -> Unit) =
        runWrite(done) { ds, _ -> SmbWriteOps.writeText(ds, entry.path, content) }

    // ---- recycle bin --------------------------------------------------------------------

    /**
     * Honest result of a recycle-bin operation:
     *  removed - records actually dropped from the bin
     *  soft    - records dropped even though the hidden file could not be physically removed
     *            (typically the NAS is offline); the bin still becomes empty, which is what
     *            "清空 / 删除" must guarantee
     *  kept    - records kept because restoring failed (the hidden file is still there)
     *  error   - first failure reason, surfaced in the toast instead of being swallowed
     */
    data class RecycleOutcome(
        val removed: Int = 0,
        val soft: Int = 0,
        val kept: Int = 0,
        val error: String? = null
    )

    private fun refreshRecycle() {
        _recycleItems.value = RecycleStore.list(getApplication())
    }

    /** Local deletions are written straight to [RecycleStore], so the page refreshes on resume. */
    fun reloadRecycle() = refreshRecycle()

    /** Local entries (host + share both blank) are hidden renames on the device, not SMB. */
    private fun isLocalRecycleItem(item: RecycleItem) =
        item.host.isBlank() && item.share.isBlank()

    /** Returns true when nothing is left on disk. An already-missing trash file counts as done. */
    private fun deleteRecursively(f: File): Boolean {
        if (!f.exists()) return true
        if (f.isDirectory) f.listFiles()?.forEach { deleteRecursively(it) }
        return f.delete() || !f.exists()
    }

    private fun remoteReady(item: RecycleItem): Boolean =
        manager != null && _connection.value?.host == item.host

    /**
     * Recycle-bin I/O uses the multi-connection pool instead of the single browsing session,
     * so a stalled NAS cannot hold [shareLock] and freeze ordinary directory listings.
     */
    private fun withRecycleShare(item: RecycleItem, block: (DiskShare) -> Unit) {
        val p = pool
        if (p != null) p.withShare(item.share, block) else runShare(item.share, block)
    }

    private fun unreachableCount(items: List<RecycleItem>): Int =
        items.count { !isLocalRecycleItem(it) && !remoteReady(it) }

    /** Physically remove the hidden item; null on success, otherwise a human-readable reason. */
    private fun purgePhysical(item: RecycleItem): String? = try {
        when {
            isLocalRecycleItem(item) ->
                if (deleteRecursively(File(item.trashPath))) null else "本机文件删除失败：${item.name}"
            !remoteReady(item) -> "未连接到 ${item.host}"
            else -> {
                withRecycleShare(item) { SmbWriteOps.purge(it, item) }
                null
            }
        }
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    /** Physically restore the hidden item; null on success, otherwise a human-readable reason. */
    private fun restorePhysical(item: RecycleItem): String? = try {
        if (isLocalRecycleItem(item)) {
            val trash = File(item.trashPath)
            val target = File(item.path)
            when {
                !trash.exists() -> "回收站中的文件已不存在"
                target.exists() -> "目标位置已存在同名文件"
                !trash.renameTo(target) -> "还原失败"
                else -> null
            }
        } else {
            if (!remoteReady(item)) {
                "请先连接到 ${item.host}"
            } else {
                withRecycleShare(item) { SmbWriteOps.restore(it, item) }
                null
            }
        }
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }

    /**
     * Delete bin entries in ONE coroutine with ONE atomic store write. The old version launched
     * a coroutine per item, each doing its own read-modify-write of SharedPreferences; their
     * snapshots overwrote one another and already-deleted rows reappeared.
     */
    fun purgeMany(items: List<RecycleItem>, done: (RecycleOutcome) -> Unit) {
        if (items.isEmpty()) {
            done(RecycleOutcome())
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val ids = LinkedHashSet<String>()
            items.forEach { ids.add(it.id) }
            val soft = unreachableCount(items)
            // Records drive the UI: drop them first so rows disappear immediately and the
            // callback always fires, then clean up hidden files off the UI path.
            RecycleStore.removeAll(getApplication(), ids)
            refreshRecycle()
            val out = RecycleOutcome(
                removed = ids.size, soft = soft,
                error = if (soft > 0) "未连接到 NAS" else null
            )
            try {
                withContext(Dispatchers.Main) { done(out) }
            } catch (_: Exception) {
                // The page may already be gone; records are cleared regardless.
            } finally {
                items.forEach { purgePhysical(it) }
            }
        }
    }

    /** Restore in one batch. A failed restore KEEPS its record so it can be retried later. */
    fun restoreMany(items: List<RecycleItem>, done: (RecycleOutcome) -> Unit) {
        if (items.isEmpty()) {
            done(RecycleOutcome())
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val okIds = LinkedHashSet<String>()
            var kept = 0
            var error: String? = null
            for (item in items) {
                val reason = restorePhysical(item)
                if (reason == null) okIds.add(item.id)
                else {
                    kept++
                    if (error == null) error = reason
                }
            }
            RecycleStore.removeAll(getApplication(), okIds)
            refreshRecycle()
            val out = RecycleOutcome(removed = okIds.size, kept = kept, error = error)
            withContext(Dispatchers.Main) {
                done(out)
                if (okIds.isNotEmpty() && manager != null) reload()
            }
        }
    }

    /**
     * Empty the whole bin. Unlike the old version this does NOT filter by the currently
     * connected host and does NOT silently swallow failures while still reporting success.
     * Records are always cleared; hidden files are removed best-effort and the toast says so.
     */
    fun emptyRecycle(done: (RecycleOutcome) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val items = RecycleStore.list(getApplication())
            val soft = unreachableCount(items)
            // "清空" must always empty the list. The old version did N blocking SMB deletes
            // first; one stalled NAS meant clear() and the callback were never reached.
            RecycleStore.clear(getApplication())
            refreshRecycle()
            val out = RecycleOutcome(
                removed = items.size, soft = soft,
                error = if (soft > 0) "未连接到 NAS" else null
            )
            try {
                withContext(Dispatchers.Main) { done(out) }
            } catch (_: Exception) {
                // The page may already be gone; records are cleared regardless.
            } finally {
                items.forEach { purgePhysical(it) }
            }
        }
    }

    // ---- connection recovery ------------------------------------------------------------

    @Synchronized
    private fun reconnect(): Boolean {
        val p = _connection.value ?: return false
        return try {
            manager?.disconnect()
            pool?.close()
            val m = SmbClientManager(p.host, p.username, p.password, p.domain)
            m.connect()
            _dialect.value = m.negotiatedDialect
            m.sortMode = _sortMode.value
            manager = m
            pool = SmbConnectionPool(p.host, p.username, p.password, p.domain, CONCURRENCY)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun clearError() { _error.value = null }
    fun clearManualShare() { _manualShareNeeded.value = false }

    /**
     * Prepare a media file for playback: download it into the app's private external-files cache
     * (`.nasopen/<share>/<path>`) so the system default viewer/player can open it via a
     * FileProvider content URI. Uses the *browsing* pool (independent of the download service).
     */
    fun openEntry(entry: SmbEntry) {
        viewModelScope.launch(Dispatchers.IO) {
            _openProgress.value = true
            try {
                val p = pool ?: throw IllegalStateException("尚未连接")
                val share = _currentShare.value ?: throw IllegalStateException("尚未进入共享")
                val extDir = getApplication<Application>().getExternalFilesDir(null)
                    ?: getApplication<Application>().cacheDir
                val target = File(extDir, ".nasopen/$share/${entry.path}")
                target.parentFile?.mkdirs()
                p.withShare(share) { ds ->
                    val file = ds.openFile(
                        toSmbPath(entry.path),
                        EnumSet.of(AccessMask.GENERIC_READ),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                    )
                    try {
                        target.outputStream().use { out ->
                            val buf = ByteArray(READ_BUF)
                            var offset = 0L
                            while (true) {
                                val n = file.read(buf, offset)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                offset += n
                            }
                        }
                    } finally {
                        try { file.close() } catch (_: Exception) {}
                    }
                }
                _openResult.value = OpenResult(target, guessMime(entry.path))
            } catch (e: Exception) {
                _error.value = "打开失败: ${e.message}"
            } finally {
                _openProgress.value = false
            }
        }
    }

    /**
     * Download a single entry into the app's private cache (`.nasopen/<share>/<path>`) and return
     * the local [File]. A sibling `.meta` file records the remote size + modification time; a
     * file replaced on the NAS under the SAME name must never resolve to the stale local copy.
     */
    suspend fun fetchToCache(share: String, entry: SmbEntry): File? = withContext(Dispatchers.IO) {
        val extDir = getApplication<Application>().getExternalFilesDir(null)
            ?: getApplication<Application>().cacheDir
        val target = File(extDir, ".nasopen/$share/${entry.path}")
        val meta = File(target.parentFile, "${target.name}.meta")
        val stamp = "${entry.size}|${entry.lastModified}"
        val usable = try {
            target.exists() && target.length() > 0L && meta.readText() == stamp
        } catch (_: Exception) {
            false
        }
        if (usable) return@withContext target

        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, "${target.name}.tmp")
        try { tmp.delete() } catch (_: Exception) {}

        val fetch: () -> Boolean = fetch@{
            val p = pool ?: return@fetch false
            try {
                p.withShare(share) { ds ->
                    val file = ds.openFile(
                        toSmbPath(entry.path),
                        EnumSet.of(AccessMask.GENERIC_READ),
                        EnumSet.of(FileAttributes.FILE_ATTRIBUTE_NORMAL),
                        EnumSet.of(SMB2ShareAccess.FILE_SHARE_READ),
                        SMB2CreateDisposition.FILE_OPEN,
                        EnumSet.of(SMB2CreateOptions.FILE_NON_DIRECTORY_FILE)
                    )
                    try {
                        tmp.outputStream().use { out ->
                            val buf = ByteArray(READ_BUF)
                            var offset = 0L
                            while (true) {
                                val n = file.read(buf, offset)
                                if (n <= 0) break
                                out.write(buf, 0, n)
                                offset += n
                            }
                        }
                    } finally {
                        try { file.close() } catch (_: Exception) {}
                    }
                }
                true
            } catch (_: Exception) {
                false
            }
        }

        var ok = fetch()
        if (!ok && reconnect()) ok = fetch()
        if (!ok) {
            tmp.delete()
            return@withContext null
        }
        // Only a complete download becomes a valid cache entry, then stamp its version.
        if (target.exists() && !target.delete()) {
            tmp.delete()
            return@withContext null
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return@withContext null
        }
        try {
            meta.writeText(stamp)
        } catch (_: Exception) {
            target.delete()
            return@withContext null
        }
        target
    }

    fun clearOpenResult() { _openResult.value = null }

    /** Download the entries selected on the current page; folders are collected recursively. */
    fun downloadSelected() {
        val sel = visibleSelection.value.values.toList()
        if (sel.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            val mgr = manager ?: run { _error.value = "尚未连接"; return@launch }
            val items = mutableListOf<TransferItem>()
            try {
                for (s in sel) {
                    val e = s.entry
                    val share = s.share
                    val base = e.path.substringAfterLast('/')
                    if (e.isDirectory) {
                        val collected = try {
                            mgr.collectFiles(share, e.path)
                        } catch (ex: Exception) {
                            if (reconnect()) mgr.collectFiles(share, e.path) else throw ex
                        }
                        for ((fpath, fsize) in collected) {
                            val sub = fpath.removePrefix("${e.path}/")
                            val rel = buildString {
                                append("NasFiles/").append(base)
                                if (sub.isNotEmpty()) append('/').append(sub)
                            }
                            items.add(
                                TransferItem(
                                    id = UUID.randomUUID().toString(),
                                    name = fpath.substringAfterLast('/'),
                                    path = fpath,
                                    share = share,
                                    relativePath = rel,
                                    size = fsize,
                                    bytes = 0L,
                                    state = TransferState.QUEUED
                                )
                            )
                        }
                    } else {
                        items.add(
                            TransferItem(
                                id = UUID.randomUUID().toString(),
                                name = e.name,
                                path = e.path,
                                share = share,
                                relativePath = "NasFiles/$base",
                                size = e.size,
                                bytes = 0L,
                                state = TransferState.QUEUED
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                _error.value = "收集文件失败: ${e.message}"
                return@launch
            }
            if (items.isEmpty()) {
                _error.value = "所选目录中没有可下载的文件"
                return@launch
            }

            TransferRepository.enqueueNew(items)
            clearSelection()
            DownloadService.enqueue(getApplication(), _connection.value)
        }
    }

    fun deleteTransfer(id: String) {
        TransferRepository.remove(id)
        CancelHub.clear(id)
        DownloadService.remove(getApplication(), id)
    }

    fun pauseAllDownloads() {
        TransferRepository.transfers.value.forEach { t ->
            if (t.state == TransferState.ACTIVE || t.state == TransferState.QUEUED) {
                CancelHub.mark(t.id)
                TransferRepository.patch(t.id) { it.copy(state = TransferState.PAUSED) }
            }
        }
        TransferRepository.persist()
        DownloadService.pauseAll(getApplication())
    }

    fun resumeAllDownloads() {
        TransferRepository.transfers.value.forEach { t ->
            if (t.state == TransferState.PAUSED || t.state == TransferState.FAILED) {
                CancelHub.clear(t.id)
                TransferRepository.patch(t.id) { it.copy(state = TransferState.QUEUED, error = null) }
            }
        }
        TransferRepository.persist()
        DownloadService.resume(getApplication(), _connection.value)
    }

    fun toggleAllDownloads() {
        val anyActive = TransferRepository.transfers.value.any {
            it.state == TransferState.ACTIVE || it.state == TransferState.QUEUED
        }
        if (anyActive) pauseAllDownloads() else resumeAllDownloads()
    }

    fun clearTransfers() { DownloadService.clear(getApplication()) }
    fun clearDoneTransfers() { DownloadService.clearDone(getApplication()) }

    /** Tear down the SMB connection and return to the disconnected (connect-screen) state. */
    fun disconnectToConnect() {
        manager?.disconnect()
        pool?.close()
        manager = null
        pool = null
        _connection.value = null
        _currentShare.value = null
        _currentPath.value = ""
        _items.value = emptyList()
        _manualShareNeeded.value = false
        _dialect.value = ""
        _diskSpace.value = null
        _searchMode.value = SearchMode.FILTER
        _searchLabel.value = ""
        clearSelection()
    }

    // ---- file-manager write operations over the same authenticated SMB2/3 pool ----------

    /** Borrow a pooled DiskShare, run a write, then refresh the listing on the UI thread. */
    private fun runWrite(done: (Throwable?) -> Unit, block: (DiskShare, String) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val p = pool
            val share = _currentShare.value
            if (p == null || share == null) {
                withContext(Dispatchers.Main) { done(IllegalStateException("尚未进入共享目录")) }
                return@launch
            }
            try {
                p.withShare(share) { ds -> block(ds, _currentPath.value) }
                withContext(Dispatchers.Main) {
                    done(null)
                    reload()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { done(e) }
            }
        }
    }

    fun createFolder(name: String, done: (Throwable?) -> Unit) =
        runWrite(done) { ds, path -> SmbWriteOps.mkdir(ds, path, name) }

    fun renameEntry(entry: SmbEntry, newName: String, done: (Throwable?) -> Unit) =
        runWrite(done) { ds, _ -> SmbWriteOps.rename(ds, entry, newName) }

    /** Soft delete: rename in place to a hidden name and record it in the recycle bin. */
    fun deleteEntry(entry: SmbEntry, done: (Throwable?) -> Unit) {
        val host = _connection.value?.host ?: ""
        val share = _currentShare.value ?: ""
        val wrapped: (Throwable?) -> Unit = { err ->
            if (err == null) refreshRecycle()
            done(err)
        }
        runWrite(wrapped) { ds, _ ->
            val (_, hiddenPath) = SmbWriteOps.moveToTrash(ds, entry)
            RecycleStore.add(
                getApplication(),
                RecycleItem(
                    id = UUID.randomUUID().toString(),
                    host = host,
                    share = share,
                    path = entry.path,
                    trashPath = hiddenPath,
                    name = entry.name,
                    isDirectory = entry.isDirectory,
                    size = entry.size,
                    deletedAt = System.currentTimeMillis()
                )
            )
        }
    }

    fun uploadLocal(uri: Uri, name: String, done: (Throwable?) -> Unit) =
        runWrite(done) { ds, path ->
            val input = getApplication<Application>().contentResolver.openInputStream(uri)
                ?: throw IllegalStateException("无法读取所选文件")
            SmbWriteOps.upload(ds, path, name, input)
        }

    override fun onCleared() {
        super.onCleared()
        manager?.disconnect()
        pool?.close()
        // The download engine lives in DownloadService (its own process-scoped lifecycle), so we
        // do NOT tear it down here — that would cancel background downloads when the UI closes.
    }
}
