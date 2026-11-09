package com.example.nasbrowser

import com.example.nasbrowser.model.ConnectionParams

/** The five fixed slots of the ES-style bottom toolbar. */
enum class Action { NEW, SEARCH, REFRESH, VIEW, WINDOW, MORE, HISTORY, SCAN, EMPTY_ALL }

/** Each window/tab in the top strip is one of these page kinds. */
enum class TabType { HOME, LOCAL, LAN, SMB, BOOKMARKS, RECYCLE, APPS }

/** How a network window should open: connect to a NAS and optionally jump into a share/path. */
data class SmbLaunch(
    val params: ConnectionParams,
    val share: String = "",
    val path: String = ""
)

data class TabState(
    val id: String,
    val type: TabType,
    var title: String,
    var launch: SmbLaunch? = null,
    var initialPath: String = ""
)

/**
 * Every page implements this so the single Activity-owned bottom toolbar can drive it.
 * [onBackPressed] lets a directory page consume Back to go up one level instead of exiting.
 */
interface PageActions {
    fun toolbarActions(): List<Action>
    fun onToolbarAction(a: Action): Boolean
    fun onBackPressed(): Boolean
}

object ToolbarMeta {
    fun icon(a: Action): Int = when (a) {
        Action.NEW -> R.drawable.ic_action_add
        Action.SEARCH -> R.drawable.ic_action_search
        Action.REFRESH -> R.drawable.ic_action_refresh
        Action.VIEW -> R.drawable.ic_action_view
        Action.WINDOW -> R.drawable.ic_action_window
        Action.MORE -> R.drawable.ic_action_more
        Action.HISTORY -> R.drawable.ic_action_history
        Action.SCAN -> R.drawable.ic_action_refresh
        Action.EMPTY_ALL -> R.drawable.ic_sel_delete
    }

    fun label(a: Action): Int = when (a) {
        Action.NEW -> R.string.tool_new
        Action.SEARCH -> R.string.tool_search
        Action.REFRESH -> R.string.tool_refresh
        Action.VIEW -> R.string.tool_view
        Action.WINDOW -> R.string.tool_window
        Action.MORE -> R.string.tool_more
        Action.HISTORY -> R.string.tool_history
        Action.SCAN -> R.string.tool_scan
        Action.EMPTY_ALL -> R.string.recycle_clear_all
    }
}
