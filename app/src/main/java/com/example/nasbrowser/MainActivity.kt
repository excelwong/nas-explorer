package com.example.nasbrowser

import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.example.nasbrowser.databinding.ActivityMainBinding
import com.example.nasbrowser.model.ConnectionParams
import com.example.nasbrowser.util.Bookmark
import com.example.nasbrowser.util.LanDevice
import com.example.nasbrowser.util.LanStore
import com.example.nasbrowser.util.PrefsStore
import com.example.nasbrowser.util.SavedWindow
import com.example.nasbrowser.util.WindowStore
import com.example.nasbrowser.viewmodel.BrowserViewModel
import java.util.UUID

/**
 * ES-style single Activity shell.
 *
 *  - The blue top bar owns a horizontally scrollable strip of closable window tabs; every
 *    directory lives in its own tab (BrowserViewModel keeps a snapshot per tab).
 *  - A fixed five-slot bottom toolbar drives whichever page is currently shown via [PageActions].
 *  - A left drawer mirrors ES's 库 / 网络 / 工具 navigation.
 *  - No right-hand preview pane, and no IP/credential screen at launch: the previous window
 *    session is restored, or 收藏夹 opens when there is none.
 */
class MainActivity : AppCompatActivity(), SelectionHost {

    private lateinit var binding: ActivityMainBinding
    private lateinit var vm: BrowserViewModel
    private val tabs = mutableListOf<TabState>()
    private val localPaths = HashMap<String, String>()
    private var activeId = ""
    private var seq = 0
    private var currentSelection: SelectionState? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        vm = ViewModelProvider(this)[BrowserViewModel::class.java]

        binding.btnMenu.setOnClickListener { openDrawer() }
        binding.btnNewTab.setOnClickListener { showNewTabDialog() }
        binding.drawerScrim.setOnClickListener { closeDrawer() }
        setupDrawer()

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (binding.drawerPanel.root.visibility == View.VISIBLE) {
                    closeDrawer()
                    return
                }
                currentSelection?.let {
                    it.onCancel()
                    return
                }
                val page = currentPage() as? PageActions
                if (page?.onBackPressed() == true) return
                finish()
            }
        })

        // Resume the previous windows/directories; with no saved session ES opens 收藏夹.
        if (!restoreWindows()) {
            addTab(TabType.BOOKMARKS, getString(R.string.tab_bookmarks))
        }
    }

    // ---- persisted window session ----------------------------------------------------------

    /** Rebuild the tab strip (and each window's folder) from the last saved session. */
    private fun restoreWindows(): Boolean {
        val (active, saved) = WindowStore.load(this)
        if (saved.isEmpty()) return false
        saved.forEach { w ->
            val type = try { TabType.valueOf(w.type) } catch (_: Exception) { return@forEach }
            val launch = if (type == TabType.SMB && w.host.isNotBlank()) {
                SmbLaunch(ConnectionParams(w.host, w.user, w.pass, w.domain), w.share, w.path)
            } else null
            tabs.add(TabState(w.id, type, w.title, launch, w.path))
            if (type == TabType.LOCAL) localPaths[w.id] = w.path
        }
        if (tabs.isEmpty()) return false
        seq = saved.size
        showTab(tabs.firstOrNull { it.id == active }?.id ?: tabs.last().id)
        return true
    }

    /** Called while leaving the app: remember every window and its current directory. */
    private fun saveWindows() {
        if (tabs.isEmpty()) return
        val page = currentPage()
        if (page is LocalFilesFragment && activeId.isNotEmpty()) {
            localPaths[activeId] = page.currentPath()
        }
        val states = vm.windowStates()
        val saved = tabs.map { t ->
            val smb = t.type == TabType.SMB
            val loc = states[t.id]
            val params = loc?.params ?: t.launch?.params
            SavedWindow(
                id = t.id,
                type = t.type.name,
                title = t.title,
                path = when (t.type) {
                    TabType.LOCAL -> localPaths[t.id] ?: t.initialPath
                    TabType.SMB -> loc?.path ?: t.launch?.path ?: ""
                    else -> ""
                },
                share = if (smb) loc?.share ?: t.launch?.share ?: "" else "",
                host = if (smb) params?.host ?: "" else "",
                user = if (smb) params?.username ?: "" else "",
                pass = if (smb) params?.password ?: "" else "",
                domain = if (smb) params?.domain ?: "" else ""
            )
        }
        WindowStore.save(this, activeId, saved)
    }

    override fun onPause() {
        saveWindows()
        super.onPause()
    }

    override fun onStop() {
        saveWindows()
        super.onStop()
    }

    // ---- tabs ---------------------------------------------------------------------------

    private fun addTab(
        type: TabType,
        title: String,
        launch: SmbLaunch? = null,
        initialPath: String = "",
        select: Boolean = true
    ): TabState {
        val id = "t${++seq}_${UUID.randomUUID().toString().take(6)}"
        val tab = TabState(id, type, title, launch, initialPath)
        tabs.add(tab)
        if (select) showTab(id) else renderTabs()
        return tab
    }

    private fun showTab(id: String) {
        val tab = tabs.firstOrNull { it.id == id } ?: return
        if (activeId.isNotEmpty() && activeId != id) {
            val old = supportFragmentManager.findFragmentById(R.id.pageContainer)
            if (old is LocalFilesFragment) localPaths[activeId] = old.currentPath()
        }
        activeId = id
        currentSelection = null
        vm.activateTab(id, tab.launch)
        supportFragmentManager.beginTransaction()
            .replace(R.id.pageContainer, fragmentFor(tab))
            .commitAllowingStateLoss()
        renderTabs()
        binding.toolBar.post { updateChrome() }
    }

    private fun fragmentFor(t: TabState): Fragment = when (t.type) {
        TabType.HOME -> HomeFragment()
        TabType.LOCAL -> LocalFilesFragment.newInstance(t.id, localPaths[t.id] ?: t.initialPath)
        TabType.LAN -> LanFragment()
        TabType.SMB -> BrowserFragment.newInstance(t.id)
        TabType.BOOKMARKS -> BookmarksFragment()
        TabType.RECYCLE -> RecycleFragment()
        TabType.APPS -> AppsFragment()
    }

    private fun closeTab(id: String) {
        if (tabs.size <= 1) {
            Toast.makeText(this, R.string.window_last, Toast.LENGTH_SHORT).show()
            return
        }
        val idx = tabs.indexOfFirst { it.id == id }
        if (idx < 0) return
        tabs.removeAt(idx)
        vm.dropTab(id)
        if (activeId == id) showTab(tabs[idx.coerceAtMost(tabs.size - 1)].id)
        else {
            renderTabs()
            updateChrome()
        }
    }

    private fun renderTabs() {
        binding.tabStrip.removeAllViews()
        var activeView: View? = null
        tabs.forEach { t ->
            val v = layoutInflater.inflate(R.layout.item_tab, binding.tabStrip, false)
            val root: View = v.findViewById(R.id.tabRoot)
            val icon: ImageView = v.findViewById(R.id.imgTabIcon)
            val title: TextView = v.findViewById(R.id.tvTabTitle)
            val close: ImageView = v.findViewById(R.id.btnTabClose)
            root.setBackgroundResource(
                if (t.id == activeId) R.drawable.bg_tab_active else R.drawable.bg_tab
            )
            title.text = t.title
            val iconRes = tabIcon(t.type)
            if (iconRes == null) {
                icon.visibility = View.GONE
            } else {
                icon.visibility = View.VISIBLE
                icon.setImageResource(iconRes)
                icon.setColorFilter(Color.WHITE)
            }
            root.setOnClickListener { if (t.id != activeId) showTab(t.id) }
            close.setOnClickListener { closeTab(t.id) }
            binding.tabStrip.addView(v)
            if (t.id == activeId) activeView = v
        }
        val target = activeView ?: return
        binding.tabScroll.post {
            val x = target.left - binding.tabScroll.width / 2 + target.width / 2
            binding.tabScroll.smoothScrollTo(x.coerceAtLeast(0), 0)
        }
    }

    /** LAN / NAS windows show no icon: the cloud glyph was a meaningless leftover. */
    private fun tabIcon(type: TabType): Int? = when (type) {
        TabType.HOME -> R.drawable.ic_home
        TabType.LOCAL -> R.drawable.ic_cat_log
        TabType.LAN, TabType.SMB -> null
        TabType.BOOKMARKS -> R.drawable.ic_action_history
        TabType.RECYCLE -> R.drawable.ic_cat_log
        TabType.APPS -> R.drawable.ic_cat_apps
    }

    private fun titleOf(type: TabType): Int = when (type) {
        TabType.HOME -> R.string.tab_home
        TabType.LOCAL -> R.string.tab_local
        TabType.LAN -> R.string.tab_lan
        TabType.SMB -> R.string.tab_nas
        TabType.BOOKMARKS -> R.string.tab_bookmarks
        TabType.RECYCLE -> R.string.tab_recycle
        TabType.APPS -> R.string.tab_apps
    }

    /** Drawer / menu entry point: reuse an existing tab of that kind, else open a new one. */
    fun openTab(type: TabType) {
        closeDrawer()
        val existing = tabs.firstOrNull { it.type == type }
        if (existing != null) showTab(existing.id) else addTab(type, getString(titleOf(type)))
    }

    /** Open a NAS window. Called by LanFragment after the user enters credentials. */
    fun openSmbTab(params: ConnectionParams, share: String = "", path: String = "") {
        addTab(TabType.SMB, params.host, SmbLaunch(params, share, path))
    }

    /** Home category tiles: open a new local window directly at that folder. */
    fun openLocalPath(path: String) {
        val f = java.io.File(path)
        val dir = if (f.isDirectory) f else f.parentFile
        if (dir == null || !dir.exists()) {
            openTab(TabType.LOCAL)
            return
        }
        addTab(
            TabType.LOCAL,
            dir.name.ifEmpty { getString(R.string.tab_local) },
            initialPath = dir.absolutePath
        )
    }

    /** Bookmark jump: local paths open a 本机 window, everything else opens an SMB window. */
    fun openBookmark(b: Bookmark) {
        if (b.isLocal) {
            openLocalBookmark(b)
            return
        }
        val targetPath = if (b.isDirectory) b.path else b.path.substringBeforeLast('/', "")
        val conn = vm.connection.value
        if (conn != null && (b.host.isBlank() || b.host == conn.host)) {
            val current = tabs.firstOrNull { it.id == activeId }
            if (current?.type == TabType.SMB) {
                vm.openLocation(b.share, targetPath)
                if (!b.isDirectory) {
                    Toast.makeText(this, R.string.bookmark_jump_file, Toast.LENGTH_SHORT).show()
                }
                return
            }
            vm.openLocation(b.share, targetPath)
            openTab(TabType.SMB)
            return
        }

        val dev = LanStore.byHost(this, b.host)
        if (dev == null) {
            Toast.makeText(this, getString(R.string.bookmark_need_host, b.host), Toast.LENGTH_LONG).show()
            openTab(TabType.LAN)
            return
        }
        val prefs = PrefsStore.load(this)
        val savedPass = if (prefs.host == b.host) prefs.pass else ""
        if (savedPass.isNotEmpty()) {
            openSmbTab(ConnectionParams(b.host, dev.username, savedPass, dev.domain), b.share, targetPath)
        } else {
            showBookmarkPasswordDialog(dev, b, targetPath)
        }
    }

    /**
     * Open a bookmark that points at this device. Local favourites hold an absolute
     * filesystem path, so they must never be routed through the SMB connection logic.
     * A file bookmark jumps to its parent folder, matching ES's behaviour.
     */
    private fun openLocalBookmark(b: Bookmark) {
        val f = java.io.File(b.path)
        val dir = if (b.isDirectory || f.isDirectory) f else f.parentFile
        if (dir == null || !dir.exists() || !dir.isDirectory) {
            Toast.makeText(
                this, getString(R.string.bookmark_local_missing, b.path), Toast.LENGTH_LONG
            ).show()
            return
        }
        addTab(
            TabType.LOCAL,
            b.name.ifBlank { dir.name.ifEmpty { getString(R.string.tab_local) } },
            initialPath = dir.absolutePath
        )
        if (!b.isDirectory) {
            Toast.makeText(this, R.string.bookmark_jump_file, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showBookmarkPasswordDialog(dev: LanDevice, b: Bookmark, targetPath: String) {
        val input = EditText(this).apply {
            hint = getString(R.string.pass_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.lan_connect, dev.host))
            .setView(input)
            .setPositiveButton(R.string.btn_connect) { _, _ ->
                val pass = input.text.toString()
                if (pass.isEmpty()) {
                    Toast.makeText(this, R.string.lan_need_cred, Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                openSmbTab(
                    ConnectionParams(dev.host, dev.username, pass, dev.domain),
                    b.share, targetPath
                )
            }
            .setNegativeButton(R.string.cancel, null)
            .show()
    }

    /** Compatibility shim for the browser's "more" menu. */
    fun navigateTo(itemId: Int) {
        when (itemId) {
            R.id.nav_lan -> openTab(TabType.LAN)
            R.id.nav_recycle -> openTab(TabType.RECYCLE)
            R.id.nav_bookmarks -> openTab(TabType.BOOKMARKS)
            R.id.nav_apps -> openTab(TabType.APPS)
            R.id.nav_local -> openTab(TabType.LOCAL)
            else -> openTab(TabType.HOME)
        }
    }

    /** LocalFilesFragment reports its current folder so tab switches restore it. */
    fun saveLocalPath(tabId: String, path: String) { localPaths[tabId] = path }

    // ---- bottom toolbar -----------------------------------------------------------------

    private fun currentPage(): Fragment? =
        supportFragmentManager.findFragmentById(R.id.pageContainer)

    private fun defaultActions(): List<Action> {
        val type = tabs.firstOrNull { it.id == activeId }?.type ?: TabType.HOME
        return when (type) {
            TabType.HOME -> listOf(Action.NEW, Action.SEARCH, Action.REFRESH, Action.WINDOW)
            TabType.LAN -> listOf(Action.NEW, Action.REFRESH, Action.VIEW, Action.WINDOW, Action.HISTORY)
            TabType.RECYCLE -> listOf(Action.EMPTY_ALL, Action.REFRESH, Action.VIEW, Action.WINDOW, Action.HISTORY)
            else -> listOf(Action.NEW, Action.SEARCH, Action.REFRESH, Action.VIEW, Action.MORE)
        }
    }

    /** Redraw top + bottom chrome for either browsing mode or multi-selection mode. */
    private fun updateChrome() {
        val sel = currentSelection
        binding.selectionTopBar.visibility = if (sel != null) View.VISIBLE else View.GONE
        binding.topBar.visibility = if (sel != null) View.INVISIBLE else View.VISIBLE
        binding.toolBar.removeAllViews()
        if (sel != null) {
            binding.tvSelCount.text = getString(R.string.sel_top_count, sel.selected, sel.total)
            binding.btnSelAll.setOnClickListener { sel.onSelectAll() }
            binding.btnSelRange.setOnClickListener { sel.onRange() }
            binding.btnSelCancel.setOnClickListener { sel.onCancel() }
            sel.actions.take(5).forEach { a ->
                addToolbarItem(a.label, a.icon) { a.onClick() }
            }
            return
        }
        val actions = (currentPage() as? PageActions)?.toolbarActions() ?: defaultActions()
        actions.forEach { a ->
            addToolbarItem(getString(ToolbarMeta.label(a)), ToolbarMeta.icon(a)) { dispatch(a) }
        }
    }

    private fun addToolbarItem(label: String, icon: Int, click: () -> Unit) {
        val v = layoutInflater.inflate(R.layout.item_toolbar, binding.toolBar, false)
        v.findViewById<ImageView>(R.id.imgTool).setImageResource(icon)
        v.findViewById<TextView>(R.id.tvTool).text = label
        v.setOnClickListener { click() }
        binding.toolBar.addView(v)
    }

    /** Pages call this whenever their selection set changes. */
    override fun publishSelection(state: SelectionState?) {
        currentSelection = state
        binding.toolBar.post { updateChrome() }
    }

    private fun dispatch(a: Action) {
        if (a == Action.WINDOW) {
            showWindowDialog()
            return
        }
        val page = currentPage() as? PageActions
        if (page != null && page.onToolbarAction(a)) return
        when (a) {
            Action.NEW -> showNewTabDialog()
            Action.REFRESH -> showTab(activeId)
            Action.MORE -> showGenericMore()
            else -> Unit
        }
    }

    private fun showGenericMore() {
        val labels = arrayOf(
            getString(R.string.window_title),
            getString(R.string.tab_new_title),
            getString(R.string.menu_display),
            getString(R.string.menu_dir_settings)
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.tool_more)
            .setItems(labels) { _, which ->
                when (which) {
                    0 -> showWindowDialog()
                    1 -> showNewTabDialog()
                    2, 3 -> (currentPage() as? PageActions)?.onToolbarAction(Action.MORE)
                }
            }
            .show()
    }

    private fun showWindowDialog() {
        val names = tabs.map { it.title }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle(R.string.window_title)
            .setItems(names) { _, i -> tabs.getOrNull(i)?.let { showTab(it.id) } }
            .setPositiveButton(R.string.window_open_new) { _, _ -> showNewTabDialog() }
            .setNeutralButton(R.string.window_close_all) { _, _ ->
                val keep = activeId
                tabs.filter { it.id != keep }.forEach { vm.dropTab(it.id) }
                tabs.removeAll { it.id != keep }
                renderTabs()
                Toast.makeText(this, R.string.op_done, Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    fun showNewTabDialog() {
        val labels = arrayOf(
            getString(R.string.tab_new_home),
            getString(R.string.tab_new_local),
            getString(R.string.tab_new_lan),
            getString(R.string.tab_new_bookmarks),
            getString(R.string.tab_new_apps),
            getString(R.string.tab_new_recycle)
        )
        val types = arrayOf(
            TabType.HOME, TabType.LOCAL, TabType.LAN,
            TabType.BOOKMARKS, TabType.APPS, TabType.RECYCLE
        )
        AlertDialog.Builder(this)
            .setTitle(R.string.tab_new_title)
            .setItems(labels) { _, i -> addTab(types[i], getString(titleOf(types[i]))) }
            .show()
    }

    // ---- drawer -------------------------------------------------------------------------

    private fun openDrawer() {
        binding.drawerPanel.root.visibility = View.VISIBLE
        binding.drawerScrim.visibility = View.VISIBLE
        highlightDrawer()
    }

    /** Highlight the drawer row belonging to the active window (收藏夹 on a cold start). */
    private fun highlightDrawer() {
        val d = binding.drawerPanel
        val type = tabs.firstOrNull { it.id == activeId }?.type
        val rows = mapOf(
            TabType.HOME to d.itemHome,
            TabType.LOCAL to d.itemLocal,
            TabType.BOOKMARKS to d.itemBookmarks,
            TabType.RECYCLE to d.itemRecycle,
            TabType.LAN to d.itemLan
        )
        rows.forEach { (t, row) ->
            row.setBackgroundColor(
                if (t == type) Color.parseColor("#DCEBFB") else Color.TRANSPARENT
            )
        }
    }

    private fun closeDrawer() {
        binding.drawerPanel.root.visibility = View.GONE
        binding.drawerScrim.visibility = View.GONE
    }

    private fun setupDrawer() {
        val d = binding.drawerPanel
        // 库 (contains 收藏夹) is the default open group, as in ES. 网络 keeps only 局域网
        // and is expanded so the single row is reachable in one tap (v4.1.5).
        bindGroup(d.headLibrary, d.boxLibrary, d.arrowLibrary, true)
        bindGroup(d.headNetwork, d.boxNetwork, d.arrowNetwork, true)

        d.itemHome.setOnClickListener { openTab(TabType.HOME) }
        d.itemLocal.setOnClickListener { openTab(TabType.LOCAL) }
        d.itemBookmarks.setOnClickListener { openTab(TabType.BOOKMARKS) }
        d.itemRecycle.setOnClickListener { openTab(TabType.RECYCLE) }
        d.itemLan.setOnClickListener { openTab(TabType.LAN) }
    }

    private fun bindGroup(head: View, box: View, arrow: ImageView, initiallyOpen: Boolean) {
        box.visibility = if (initiallyOpen) View.VISIBLE else View.GONE
        arrow.rotation = if (initiallyOpen) 180f else 0f
        head.setOnClickListener {
            val open = box.visibility != View.VISIBLE
            box.visibility = if (open) View.VISIBLE else View.GONE
            arrow.rotation = if (open) 180f else 0f
        }
    }
}
