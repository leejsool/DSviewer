package com.dsviewer.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * 최근 연 파일 목록 (다시 열 권한이 유지되는 경우). 즐겨찾기(star)한 파일은 개수 제한 없이 남는다.
 * 즐겨찾기한 문서는 앱 안에서만 쓰는 폴더([FavFolder])에 넣어 나눌 수 있고, 썸네일을 바꿀 수 있다.
 */
object Recents {
    data class Item(
        val uri: String,
        val name: String,
        val time: Long,
        val star: Boolean = false,
        /** 넣어 둔 즐겨찾기 폴더 id (null = 즐겨찾기 맨 위) */
        val folder: String? = null,
        /** 썸네일로 쓸 쪽 (0부터). 0이면 첫 쪽 */
        val thumbPage: Int = 0,
        /** 불러온 그림·손으로 그린 썸네일 (앱 저장소 thumbs 폴더의 파일 이름). 있으면 쪽보다 먼저 */
        val thumbFile: String? = null,
        /** 마지막으로 보던 쪽 (0부터). 0이면 처음부터. 다시 열면 이 쪽에서 이어 본다 */
        val lastPage: Int = 0,
    )

    private const val PREFS = "recents"
    private const val KEY = "items"
    private const val MAX = 30

    fun list(ctx: Context): List<Item> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                Item(
                    o.getString("uri"), o.getString("name"), o.getLong("time"), o.optBoolean("star"),
                    o.optString("folder").ifEmpty { null }, o.optInt("thumbPage"), o.optString("thumbFile").ifEmpty { null },
                    o.optInt("lastPage"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun get(ctx: Context, uri: String) = list(ctx).firstOrNull { it.uri == uri }

    fun add(ctx: Context, uri: String, name: String) {
        val old = list(ctx)
        val prev = old.firstOrNull { it.uri == uri }
        val items = old.filter { it.uri != uri }.toMutableList()
        items.add(0, prev?.copy(name = name, time = System.currentTimeMillis()) ?: Item(uri, name, System.currentTimeMillis()))
        save(ctx, items)
    }

    /** 즐겨찾기를 끄면 폴더와 바꾼 썸네일도 버린다 */
    fun setStar(ctx: Context, uri: String, star: Boolean) {
        if (!star) get(ctx, uri)?.thumbFile?.let { Thumbs.deleteCustom(ctx, it) }
        update(ctx, uri) { if (star) it.copy(star = true) else it.copy(star = false, folder = null, thumbPage = 0, thumbFile = null) }
    }

    /** 문서를 닫거나 떠날 때 보던 [page]쪽(0부터)을 적어 둔다. 최근 목록에 없는 문서면 아무것도 하지 않는다 */
    fun setLastPage(ctx: Context, uri: String, page: Int) {
        if (get(ctx, uri)?.lastPage == page) return
        update(ctx, uri) { it.copy(lastPage = page.coerceAtLeast(0)) }
    }

    fun setFolder(ctx: Context, uri: String, folder: String?) = update(ctx, uri) { it.copy(folder = folder) }

    /** 썸네일을 [page]쪽으로 (그림 썸네일은 버린다) */
    fun setThumbPage(ctx: Context, uri: String, page: Int) {
        get(ctx, uri)?.thumbFile?.let { Thumbs.deleteCustom(ctx, it) }
        update(ctx, uri) { it.copy(thumbPage = page, thumbFile = null) }
    }

    fun setThumbFile(ctx: Context, uri: String, file: String) {
        get(ctx, uri)?.thumbFile?.takeIf { it != file }?.let { Thumbs.deleteCustom(ctx, it) }
        update(ctx, uri) { it.copy(thumbFile = file) }
    }

    fun remove(ctx: Context, uri: String) {
        get(ctx, uri)?.thumbFile?.let { Thumbs.deleteCustom(ctx, it) }
        save(ctx, list(ctx).filter { it.uri != uri })
    }

    /** 파일을 옮겼을 때: 기록(즐겨찾기·폴더·썸네일)을 새 자리로 */
    fun relink(ctx: Context, old: String, new: String, name: String) =
        save(ctx, list(ctx).filter { it.uri != new }.map { if (it.uri == old) it.copy(uri = new, name = name) else it })

    /** 폴더를 지울 때: 그 폴더의 문서를 [to] 폴더로 */
    fun moveAll(ctx: Context, from: String, to: String?) =
        save(ctx, list(ctx).map { if (it.folder == from) it.copy(folder = to) else it })

    private fun update(ctx: Context, uri: String, f: (Item) -> Item) =
        save(ctx, list(ctx).map { if (it.uri == uri) f(it) else it })

    private fun save(ctx: Context, items: List<Item>) {
        // 즐겨찾기가 아닌 파일만 MAX개까지 남긴다
        var plain = 0
        val kept = items.filter { it.star || plain++ < MAX }
        val arr = JSONArray()
        kept.forEach {
            val o = JSONObject().put("uri", it.uri).put("name", it.name).put("time", it.time).put("star", it.star)
            it.folder?.let { f -> o.put("folder", f) }
            if (it.thumbPage != 0) o.put("thumbPage", it.thumbPage)
            if (it.lastPage != 0) o.put("lastPage", it.lastPage)
            it.thumbFile?.let { f -> o.put("thumbFile", f) }
            arr.put(o)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

/**
 * 즐겨찾기를 '직접 정한 순서'로 볼 때의 순서: 문서의 uri와 폴더의 "vf:<id>" 열쇠를 위에서부터 늘어놓은 하나의 목록.
 * 폴더 안팎은 따로 보이므로 서로의 차례에는 영향이 없다 ([ListSort.mergeOrder])
 */
object FavOrder {
    private const val PREFS = "recents"
    private const val KEY = "favOrder"
    private const val MAX = 1000

    fun load(ctx: Context): List<String> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getString(it) }
        }.getOrDefault(emptyList())
    }

    fun save(ctx: Context, keys: List<String>) {
        val arr = JSONArray()
        keys.takeLast(MAX).forEach { arr.put(it) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}

/** 즐겨찾기 안에서만 쓰는 폴더 (기기 저장소에는 만들지 않는다). [parent]가 있으면 그 폴더 안의 폴더 */
data class FavFolder(
    val id: String,
    val name: String,
    val color: Int,
    val pattern: TapePattern,
    val parent: String? = null,
    val time: Long = System.currentTimeMillis(),
)

object FavFolders {
    private const val PREFS = "recents"
    private const val KEY = "folders"

    /** 폴더 색: 파랑이 먼저 (새 폴더의 기본) */
    val COLORS = listOf(
        "#4A86E8", "#E8A317", "#E06666", "#F6B26B", "#FFD966", "#93C47D",
        "#45B39D", "#76A5AF", "#8E7CC3", "#C27BA0", "#A1887F", "#8C8C8C",
    ).map { android.graphics.Color.parseColor(it) }

    fun list(ctx: Context): List<FavFolder> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                FavFolder(
                    o.getString("id"), o.getString("name"), o.getInt("color"), TapePattern.of(o.optString("pattern")),
                    o.optString("parent").ifEmpty { null }, o.optLong("time"),
                )
            }
        }.getOrDefault(emptyList())
    }

    fun get(ctx: Context, id: String?) = id?.let { i -> list(ctx).firstOrNull { it.id == i } }

    fun add(ctx: Context, name: String, color: Int, pattern: TapePattern, parent: String?): FavFolder {
        val all = list(ctx)
        val f = FavFolder("f${System.currentTimeMillis()}", uniqueName(all, name, parent, null), color, pattern, parent)
        save(ctx, all + f)
        return f
    }

    /** 이름을 바꾸거나 옮긴 폴더: 같은 곳에 같은 이름이 있으면 번호를 붙인다 */
    fun update(ctx: Context, f: FavFolder) {
        val all = list(ctx)
        val fixed = f.copy(name = uniqueName(all, f.name, f.parent, f.id))
        save(ctx, all.map { if (it.id == f.id) fixed else it })
    }

    /** [parent] 안에서 겹치지 않는 이름 ("새 폴더" → "새 폴더 (2)") */
    fun uniqueName(all: List<FavFolder>, name: String, parent: String?, self: String?): String {
        val taken = all.filter { it.parent == parent && it.id != self }.map { it.name }.toSet()
        return FileOps.uniqueName(name, withExt = false) { it in taken }
    }

    /** 폴더를 지우고, 안의 폴더와 문서는 한 단계 위로 올린다 (즐겨찾기는 그대로) */
    fun delete(ctx: Context, id: String) {
        val all = list(ctx)
        val f = all.firstOrNull { it.id == id } ?: return
        save(ctx, all.filter { it.id != id }.map { if (it.parent == id) it.copy(parent = f.parent) else it })
        Recents.moveAll(ctx, id, f.parent)
    }

    /** [id] 폴더와 그 안의 모든 폴더 */
    fun subtree(all: List<FavFolder>, id: String): Set<String> {
        val out = linkedSetOf(id)
        var grew = true
        while (grew) {
            grew = false
            for (f in all) if (f.parent in out && out.add(f.id)) grew = true
        }
        return out
    }

    /** 맨 위부터 [id]까지의 폴더들 */
    fun path(all: List<FavFolder>, id: String?): List<FavFolder> {
        val out = ArrayList<FavFolder>()
        var cur = id
        while (cur != null && out.size < 50) {
            val f = all.firstOrNull { it.id == cur } ?: break
            out.add(0, f)
            cur = f.parent
        }
        return out
    }

    private fun save(ctx: Context, items: List<FavFolder>) {
        val arr = JSONArray()
        items.forEach {
            val o = JSONObject().put("id", it.id).put("name", it.name).put("color", it.color)
                .put("pattern", it.pattern.name).put("time", it.time)
            it.parent?.let { p -> o.put("parent", p) }
            arr.put(o)
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, arr.toString()).apply()
    }
}
