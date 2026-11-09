package com.example.nasbrowser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.example.nasbrowser.smb.SmbEntry
import java.net.URLEncoder

/** Dependencies a file-list page supplies to build the trimmed overflow menu. */
data class MoreEnv(
    val ctx: Context,
    val selected: () -> List<SmbEntry>,
    val isLocal: Boolean,
    val bookmark: (SmbEntry) -> Unit,
    /** Used when nothing is selected, so 添加为收藏 bookmarks the current folder like ES does. */
    val bookmarkCurrent: (() -> Unit)? = null,
    val play: (SmbEntry) -> Unit,
    val share: (SmbEntry) -> Unit,
    val compress: (List<SmbEntry>) -> Unit,
    val moveCopy: (List<SmbEntry>, Boolean) -> Unit,
    val pin: (List<SmbEntry>) -> Unit,
    val properties: (SmbEntry) -> Unit,
    val openWith: (SmbEntry) -> Unit,
    val shortcut: (SmbEntry) -> Unit
)

/**
 * The "更多" overflow menu. Only entries with a real implementation are kept;
 * placeholders (投屏 / 播放列表 / 加解密 / 网盘备份 …) were removed in v4.1.5.
 */
object MoreActions {

    private fun Context.str(res: Int): String = getString(res)
    private fun Context.str(res: Int, arg: Any): String = getString(res, arg)

    fun build(env: MoreEnv): List<MoreItem> {
        val c = env.ctx
        val first: () -> SmbEntry? = { env.selected().firstOrNull() }
        val all: () -> List<SmbEntry> = { env.selected() }
        val needSelection: () -> Unit = {
            Toast.makeText(c, R.string.more_need_selection, Toast.LENGTH_SHORT).show()
        }
        val withFirst: (String, (SmbEntry) -> Unit) -> Unit = { _, action ->
            val e = first()
            if (e == null) needSelection() else action(e)
        }
        val withAll: (String, (List<SmbEntry>) -> Unit) -> Unit = { _, action ->
            val list = all()
            if (list.isEmpty()) needSelection() else action(list)
        }

        return listOf(
            MoreItem(c.str(R.string.more_share), R.drawable.ic_more_share) {
                withFirst("") { env.share(it) }
            },
            MoreItem(c.str(R.string.more_favorite), R.drawable.ic_more_star) {
                val e = first()
                if (e != null) env.bookmark(e)
                else {
                    val current = env.bookmarkCurrent
                    if (current != null) current() else needSelection()
                }
            },
            MoreItem(c.str(R.string.sel_properties), R.drawable.ic_sel_info) {
                withFirst("") { env.properties(it) }
            },
            MoreItem(c.str(R.string.more_move_to), R.drawable.ic_more_move) {
                withAll("") { env.moveCopy(it, true) }
            },
            MoreItem(c.str(R.string.more_copy_to), R.drawable.ic_sel_copy) {
                withAll("") { env.moveCopy(it, false) }
            },
            MoreItem(c.str(R.string.sel_web_search), R.drawable.ic_sel_web) {
                withFirst("") { e ->
                    Toast.makeText(
                        c, c.str(R.string.web_search_hint, e.name), Toast.LENGTH_SHORT
                    ).show()
                    try {
                        val url = "https://www.baidu.com/s?wd=" +
                            URLEncoder.encode(e.name, "UTF-8")
                        c.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        })
                    } catch (_: Exception) {
                        Unit
                    }
                }
            },
            MoreItem(c.str(R.string.more_pin), R.drawable.ic_more_pin) {
                withAll("") { env.pin(it) }
            }
        )
    }
}
