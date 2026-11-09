package com.example.nasbrowser.util

import android.content.Context
import com.example.nasbrowser.model.SortMode
import com.example.nasbrowser.model.ViewMode

/**
 * All display preferences, including per-directory view/sort overrides and the "home" location.
 * Kept in a plain SharedPreferences file: none of this is sensitive (credentials live in
 * [PrefsStore]'s encrypted store).
 */
object UiPrefs {
    private const val FILE = "nas_ui_v3"

    private fun sp(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    data class StartLocation(val host: String, val share: String, val path: String)
    data class DirPrefs(val view: ViewMode, val sort: SortMode)

    // ---- global defaults ---------------------------------------------------------------

    fun loadViewMode(ctx: Context): ViewMode {
        // ES opens every directory in the compact list view, including on unfolded foldables.
        // Large-icon / detail views stay available from the 视图 toolbar button; on a wide
        // screen the large-icon view becomes an adaptive multi-column grid.
        return ViewMode.fromKey(sp(ctx).getString("viewMode", null))
    }

    fun saveViewMode(ctx: Context, v: ViewMode) {
        sp(ctx).edit().putString("viewMode", v.key).apply()
    }

    fun loadSortMode(ctx: Context): SortMode {
        val own = sp(ctx).getString("sortMode", null)
        if (own != null) return SortMode.fromKey(own)
        // Migrate the v2 preference (stored in a different file).
        return SortMode.fromKey(PrefsStore.loadSortMode(ctx))
    }

    fun saveSortMode(ctx: Context, s: SortMode) {
        sp(ctx).edit().putString("sortMode", s.key).apply()
        PrefsStore.saveSortMode(ctx, s.key)
    }

    fun showThumbnails(ctx: Context) = sp(ctx).getBoolean("showThumbnails", true)
    fun saveShowThumbnails(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("showThumbnails", v).apply()

    fun showNewFiles(ctx: Context) = sp(ctx).getBoolean("showNewFiles", true)
    fun saveShowNewFiles(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("showNewFiles", v).apply()

    fun showDiskSpace(ctx: Context) = sp(ctx).getBoolean("showDiskSpace", true)
    fun saveShowDiskSpace(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("showDiskSpace", v).apply()

    fun showHidden(ctx: Context) = sp(ctx).getBoolean("showHidden", false)
    fun saveShowHidden(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("showHidden", v).apply()

    fun onlyNewFiles(ctx: Context) = sp(ctx).getBoolean("onlyNewFiles", false)
    fun saveOnlyNewFiles(ctx: Context, v: Boolean) =
        sp(ctx).edit().putBoolean("onlyNewFiles", v).apply()

    fun newFileDays(ctx: Context) = sp(ctx).getInt("newFileDays", 7).coerceIn(1, 90)
    fun saveNewFileDays(ctx: Context, d: Int) = sp(ctx).edit().putInt("newFileDays", d.coerceIn(1, 90)).apply()

    // ---- home / start directory --------------------------------------------------------

    fun loadStartLocation(ctx: Context): StartLocation? {
        val p = sp(ctx)
        val host = p.getString("startHost", null) ?: return null
        return StartLocation(
            host,
            p.getString("startShare", "") ?: "",
            p.getString("startPath", "") ?: ""
        )
    }

    fun saveStartLocation(ctx: Context, loc: StartLocation) {
        sp(ctx).edit()
            .putString("startHost", loc.host)
            .putString("startShare", loc.share)
            .putString("startPath", loc.path)
            .apply()
    }

    fun clearStartLocation(ctx: Context) {
        sp(ctx).edit().remove("startHost").remove("startShare").remove("startPath").apply()
    }

    // ---- per-directory view / sort overrides -------------------------------------------

    fun dirKey(host: String, share: String, path: String) = "$host|$share|$path"

    fun loadDirPrefs(ctx: Context, key: String): DirPrefs? {
        val p = sp(ctx)
        val v = p.getString("dir_${key}_view", null) ?: return null
        val s = p.getString("dir_${key}_sort", null) ?: return null
        return DirPrefs(ViewMode.fromKey(v), SortMode.fromKey(s))
    }

    fun saveDirPrefs(ctx: Context, key: String, v: ViewMode, s: SortMode) {
        sp(ctx).edit()
            .putString("dir_${key}_view", v.key)
            .putString("dir_${key}_sort", s.key)
            .apply()
    }

    fun clearDirPrefs(ctx: Context, key: String) {
        sp(ctx).edit().remove("dir_${key}_view").remove("dir_${key}_sort").apply()
    }
}
