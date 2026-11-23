package com.officesuite.app.core

import android.content.Context
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

data class RecentFile(val uri: String, val name: String, val kind: DocKind, val time: Long)

object Recents {
    private const val PREF = "recents"
    private const val KEY = "items"
    private const val MAX = 60

    fun load(ctx: Context): List<RecentFile> {
        val raw = ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.getJSONObject(i)
                val kind = runCatching { DocKind.valueOf(o.getString("kind")) }.getOrNull() ?: return@mapNotNull null
                RecentFile(o.getString("uri"), o.getString("name"), kind, o.getLong("time"))
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<RecentFile>) {
        val arr = JSONArray()
        list.take(MAX).forEach {
            arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("kind", it.kind.name).put("time", it.time))
        }
        ctx.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }

    fun add(ctx: Context, uri: Uri, name: String, kind: DocKind) {
        val s = uri.toString()
        val list = load(ctx).filterNot { it.uri == s }.toMutableList()
        list.add(0, RecentFile(s, name, kind, System.currentTimeMillis()))
        save(ctx, list)
    }

    fun remove(ctx: Context, uri: String) {
        save(ctx, load(ctx).filterNot { it.uri == uri })
    }
}
