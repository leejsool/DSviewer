package com.dsviewer.app

import android.content.Context

/** 한 문서의 복습 일정 요약: 복습 전인 오답 수와, 복습한 오답들의 다음 복습일 */
internal class ReviewIndexItem(val uri: String, val name: String, val unreviewed: Int, val dueDays: List<Long>) {
    /** [today]에 복습할 오답 수 (복습 전인 것 + 복습일이 됐거나 지난 것) */
    fun dueCount(today: Long) = unreviewed + dueDays.count { it <= today }
}

/**
 * 여러 문서의 오늘 복습할 오답을 탐색기 첫 화면에서 한꺼번에 보이기 위한 색인.
 * 문서를 열거나 저장할 때 그 문서의 일정만 요약해 두고, 날짜가 바뀌면 복습일과 오늘을 견줘 다시 센다.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다 (저장 위치는 [ReviewIndexStore])
 */
internal object ReviewIndex {

    /** 문서의 오답들로 요약을 만든다. 오답이 없으면 null */
    fun summarize(uri: String, name: String, entries: List<StatEntry>): ReviewIndexItem? {
        if (entries.isEmpty()) return null
        return ReviewIndexItem(
            uri, name,
            entries.count { it.stage <= 0 },
            entries.filter { it.stage > 0 }.map { it.dueDay }.sorted(),
        )
    }

    /** [items]에서 [uri]의 요약을 [item]으로 바꾼다 (null이면 뺀다) */
    fun upsert(items: List<ReviewIndexItem>, uri: String, item: ReviewIndexItem?): List<ReviewIndexItem> {
        val rest = items.filter { it.uri != uri }
        return if (item == null) rest else listOf(item) + rest
    }

    /** 오늘 복습할 것이 있는 문서들, 많은 것부터 */
    fun dueItems(items: List<ReviewIndexItem>, today: Long): List<Pair<ReviewIndexItem, Int>> =
        items.map { it to it.dueCount(today) }.filter { it.second > 0 }.sortedByDescending { it.second }

    fun encode(items: List<ReviewIndexItem>): String = buildString {
        for (it in items) {
            append(it.uri.clean()).append('\t').append(it.name.clean()).append('\t').append(it.unreviewed).append('\t')
            append(it.dueDays.joinToString(",")).append('\n')
        }
    }

    private fun String.clean() = replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    fun decode(s: String): List<ReviewIndexItem> = s.lineSequence().mapNotNull { line ->
        val f = line.split('\t')
        if (f.size < 4 || f[0].isEmpty()) return@mapNotNull null
        val unreviewed = f[2].toIntOrNull() ?: return@mapNotNull null
        ReviewIndexItem(f[0], f[1], unreviewed.coerceAtLeast(0), f[3].split(',').mapNotNull { it.toLongOrNull() })
    }.toList()
}

/** 복습 색인을 앱 설정에 적어 두고 읽는다 */
internal object ReviewIndexStore {
    private const val PREFS = "review_index"
    private const val KEY = "items"

    fun load(ctx: Context): List<ReviewIndexItem> =
        ReviewIndex.decode(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null).orEmpty())

    private fun save(ctx: Context, items: List<ReviewIndexItem>) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, ReviewIndex.encode(items)).apply()
    }

    /** [uri] 문서의 오답 일정을 새로 요약해 둔다 (오답이 없으면 색인에서 뺀다) */
    fun update(ctx: Context, uri: String, name: String, entries: List<WrongEntry>) {
        // 복습으로 정한 스크랩만 복습 일정에 센다
        val stats = entries.filter { it.review }.map { StatEntry(it.symbol, it.tags, it.stage, it.dueDay, it.history) }
        save(ctx, ReviewIndex.upsert(load(ctx), uri, ReviewIndex.summarize(uri, name, stats)))
    }

    fun remove(ctx: Context, uri: String) = save(ctx, ReviewIndex.upsert(load(ctx), uri, null))

    /** 파일을 옮기거나 이름을 바꿨을 때 */
    fun relink(ctx: Context, old: String, new: String, name: String) {
        val items = load(ctx)
        val it = items.firstOrNull { x -> x.uri == old } ?: return
        save(ctx, ReviewIndex.upsert(items, old, null).let { rest ->
            ReviewIndex.upsert(rest, new, ReviewIndexItem(new, name, it.unreviewed, it.dueDays))
        })
    }
}
