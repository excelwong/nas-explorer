package com.example.nasbrowser.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A saved location: either a folder/file on an SMB share, or a path on this device. */
data class Bookmark(
    val name: String,
    val share: String,
    val path: String,
    val host: String = "",
    val isDirectory: Boolean = true,
    val isLocal: Boolean = false
)

/** Lightweight persisted favourites (no database dependency). */
object BookmarkStore {
    private const val PREF = "nas_bookmarks"
    private const val KEY = "items"

    fun list(ctx: Context): MutableList<Bookmark> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<Bookmark>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val host = o.optString("host")
                val share = o.optString("share")
                val path = o.optString("path")
                // v4.1 and earlier stored local favourites with an empty host/share and an
                // absolute filesystem path. Recover them so existing bookmarks still open.
                val legacyLocal = host.isBlank() && share.isBlank() && path.startsWith("/")
                out.add(
                    Bookmark(
                        o.optString("name"),
                        share,
                        path,
                        host,
                        o.optBoolean("isDirectory", true),
                        o.optBoolean("isLocal", legacyLocal)
                    )
                )
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun save(ctx: Context, items: List<Bookmark>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("name", it.name)
                    .put("share", it.share)
                    .put("path", it.path)
                    .put("host", it.host)
                    .put("isDirectory", it.isDirectory)
                    .put("isLocal", it.isLocal)
            )
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, b: Bookmark) {
        val items = list(ctx)
        if (items.any { it.isLocal == b.isLocal && it.host == b.host && it.share == b.share && it.path == b.path }) return
        items.add(0, b)
        save(ctx, items)
    }

    fun remove(ctx: Context, b: Bookmark) {
        save(
            ctx,
            list(ctx).filterNot {
                it.isLocal == b.isLocal && it.host == b.host && it.share == b.share && it.path == b.path
            }
        )
    }

    fun rename(ctx: Context, b: Bookmark, newName: String) {
        save(
            ctx,
            list(ctx).map {
                if (it.isLocal == b.isLocal && it.host == b.host && it.share == b.share && it.path == b.path)
                    it.copy(name = newName)
                else it
            }
        )
    }
}
