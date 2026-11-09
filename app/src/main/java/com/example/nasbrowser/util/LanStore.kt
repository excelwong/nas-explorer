package com.example.nasbrowser.util

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** A remembered SMB server on the LAN. Passwords are deliberately NOT stored here. */
data class LanDevice(
    val host: String,
    val label: String,
    val username: String,
    val domain: String
)

object LanStore {
    private const val PREF = "nas_lan"
    private const val KEY = "devices"

    fun list(ctx: Context): MutableList<LanDevice> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, "[]") ?: "[]"
        return try {
            val arr = JSONArray(raw)
            val out = mutableListOf<LanDevice>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                out.add(
                    LanDevice(
                        o.optString("host"),
                        o.optString("label"),
                        o.optString("username"),
                        o.optString("domain")
                    )
                )
            }
            out
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun save(ctx: Context, items: List<LanDevice>) {
        val arr = JSONArray()
        items.forEach {
            arr.put(
                JSONObject()
                    .put("host", it.host)
                    .put("label", it.label)
                    .put("username", it.username)
                    .put("domain", it.domain)
            )
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, d: LanDevice) {
        val items = list(ctx).filterNot { it.host == d.host }.toMutableList()
        items.add(0, d)
        save(ctx, items)
    }

    fun remove(ctx: Context, host: String) = save(ctx, list(ctx).filterNot { it.host == host })

    fun byHost(ctx: Context, host: String): LanDevice? = list(ctx).firstOrNull { it.host == host }
}
