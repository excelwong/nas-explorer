package com.example.nasbrowser.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persisted window session: the tab strip, each window's current directory and (for SMB
 * windows) the credentials needed to reconnect. This lets the next launch resume exactly
 * where the user left off instead of always starting on a blank home page.
 */
data class SavedWindow(
    val id: String,
    val type: String,
    val title: String,
    val path: String = "",
    val share: String = "",
    val host: String = "",
    val user: String = "",
    val pass: String = "",
    val domain: String = ""
)

object WindowStore {
    private const val PREF = "nas_windows"
    private const val KEY = "state"

    fun save(ctx: Context, activeId: String, windows: List<SavedWindow>) {
        val arr = JSONArray()
        windows.forEach { w ->
            arr.put(
                JSONObject()
                    .put("id", w.id)
                    .put("type", w.type)
                    .put("title", w.title)
                    .put("path", w.path)
                    .put("share", w.share)
                    .put("host", w.host)
                    .put("user", w.user)
                    .put("pass", w.pass)
                    .put("domain", w.domain)
            )
        }
        val root = JSONObject().put("active", activeId).put("tabs", arr)
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, root.toString()).apply()
    }

    fun load(ctx: Context): Pair<String, List<SavedWindow>> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, null) ?: return Pair("", emptyList())
        return try {
            val root = JSONObject(raw)
            val arr = root.optJSONArray("tabs") ?: return Pair("", emptyList())
            val out = ArrayList<SavedWindow>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    SavedWindow(
                        id = o.optString("id"),
                        type = o.optString("type"),
                        title = o.optString("title"),
                        path = o.optString("path"),
                        share = o.optString("share"),
                        host = o.optString("host"),
                        user = o.optString("user"),
                        pass = o.optString("pass"),
                        domain = o.optString("domain")
                    )
                )
            }
            Pair(root.optString("active"), out)
        } catch (_: Exception) {
            Pair("", emptyList())
        }
    }
}
