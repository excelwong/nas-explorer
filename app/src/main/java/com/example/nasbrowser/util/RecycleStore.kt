package com.example.nasbrowser.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Deleting a remote file renames it in place to a hidden ".trash_<timestamp>_<name>" entry and
 * records the mapping here. Restoring is therefore an in-place rename (no data copy), which is
 * both instant and safe on SMB2/3 where a rename cannot move an entry across directories.
 */
data class RecycleItem(
    val id: String,
    val host: String,
    val share: String,
    val path: String,        // original path inside the share
    val trashPath: String,   // current hidden path inside the share
    val name: String,        // original display name
    val isDirectory: Boolean,
    val size: Long,
    val deletedAt: Long
)

/**
 * Thread-safe recycle-bin record store.
 *
 * Every mutation is an atomic read-modify-write under [lock]. The previous version exposed
 * remove() as a separate operation per item; callers launched one IO coroutine per selected
 * item, so concurrent snapshots overwrote each other and already-deleted rows came back.
 * [removeAll] now performs the whole batch in one critical section.
 */
object RecycleStore {
    private const val PREF = "nas_recycle"
    private const val KEY = "items"
    private val lock = Any()

    private fun readLocked(ctx: Context): MutableList<RecycleItem> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<RecycleItem>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    RecycleItem(
                        o.optString("id"),
                        o.optString("host"),
                        o.optString("share"),
                        o.optString("path"),
                        o.optString("trashPath"),
                        o.optString("name"),
                        o.optBoolean("isDirectory"),
                        o.optLong("size"),
                        o.optLong("deletedAt")
                    )
                )
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun writeLocked(ctx: Context, items: List<RecycleItem>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("id", it.id)
                    .put("host", it.host)
                    .put("share", it.share)
                    .put("path", it.path)
                    .put("trashPath", it.trashPath)
                    .put("name", it.name)
                    .put("isDirectory", it.isDirectory)
                    .put("size", it.size)
                    .put("deletedAt", it.deletedAt)
            )
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun list(ctx: Context): List<RecycleItem> = synchronized(lock) { readLocked(ctx) }

    fun add(ctx: Context, item: RecycleItem) {
        synchronized(lock) {
            val items = readLocked(ctx)
            items.add(0, item)
            writeLocked(ctx, items)
        }
    }

    fun remove(ctx: Context, id: String) = removeAll(ctx, setOf(id))

    /** Atomically drop a whole batch of records in one read-modify-write. */
    fun removeAll(ctx: Context, ids: Set<String>) {
        if (ids.isEmpty()) return
        synchronized(lock) {
            writeLocked(ctx, readLocked(ctx).filterNot { it.id in ids })
        }
    }

    fun clear(ctx: Context) {
        synchronized(lock) { writeLocked(ctx, emptyList()) }
    }
}
