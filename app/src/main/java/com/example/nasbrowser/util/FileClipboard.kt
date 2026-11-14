package com.example.nasbrowser.util

/**
 * Windows-style file clipboard shared by every window/tab of the app.
 *
 * 复制 / 剪切 only stage the current selection here; the real copy / move is deferred until the
 * user navigates into another directory and taps 粘贴. A paste job clears the clipboard when it
 * finishes (success, skip, failure or 取消), which restores the normal bottom toolbar, exactly
 * like ES. 取消 in the three-button bar also clears it.
 */
enum class ClipMode { COPY, CUT }

/** Outcome of a cross-type paste (NAS <-> device); a CUT is only cleared when nothing failed. */
data class CrossPasteResult(
    val ok: Int,
    val failed: Int,
    val firstError: String? = null
)

data class ClipItem(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val size: Long = 0L
)

/**
 * Where the staged items live.
 *  - [local] = true: [items] paths are absolute paths on the device, [fromDir] is an absolute path.
 *  - [local] = false: [items] paths are relative to [share] on [host], [fromDir] is a relative
 *    path inside that share.
 */
data class ClipSource(
    val local: Boolean,
    val host: String = "",
    val share: String = "",
    val fromDir: String = "",
    val items: List<ClipItem> = emptyList()
)

data class ClipboardState(
    val mode: ClipMode,
    val source: ClipSource
)

object FileClipboard {

    @Volatile
    var state: ClipboardState? = null
        private set

    fun put(mode: ClipMode, source: ClipSource) {
        state = ClipboardState(mode, source)
    }

    fun clear() {
        state = null
    }

    fun get(): ClipboardState? = state

    fun hasContent(): Boolean = state != null

    /** Paths of CUT items, used to grey out rows in the source directory (Windows behaviour). */
    fun cutKeys(): Set<String> =
        state?.takeIf { it.mode == ClipMode.CUT }
            ?.source?.items?.mapTo(HashSet()) { it.path }
            ?: emptySet()
}
