package com.dsviewer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** 최근 연 파일 목록 (다시 열 권한이 유지되는 경우). 즐겨찾기(star)한 파일은 개수 제한 없이 남는다. */
object Recents {
    data class Item(val uri: String, val name: String, val time: Long, val star: Boolean = false)

    private const val PREFS = "recents"
    private const val KEY = "items"
    private const val MAX = 30

    fun list(ctx: Context): List<Item> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Item(o.getString("uri"), o.getString("name"), o.getLong("time"), o.optBoolean("star"))
            }
        }.getOrDefault(emptyList())
    }

    fun add(ctx: Context, uri: String, name: String) {
        val old = list(ctx)
        val star = old.any { it.uri == uri && it.star }
        val items = old.filter { it.uri != uri }.toMutableList()
        items.add(0, Item(uri, name, System.currentTimeMillis(), star))
        save(ctx, items)
    }

    fun setStar(ctx: Context, uri: String, star: Boolean) =
        save(ctx, list(ctx).map { if (it.uri == uri) it.copy(star = star) else it })

    fun remove(ctx: Context, uri: String) = save(ctx, list(ctx).filter { it.uri != uri })

    private fun save(ctx: Context, items: List<Item>) {
        // 즐겨찾기가 아닌 파일만 MAX개까지 남긴다
        var plain = 0
        val kept = items.filter { it.star || plain++ < MAX }
        val arr = JSONArray()
        kept.forEach {
            arr.put(JSONObject().put("uri", it.uri).put("name", it.name).put("time", it.time).put("star", it.star))
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
