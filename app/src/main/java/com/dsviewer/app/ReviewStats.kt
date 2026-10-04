package com.dsviewer.app

/** 통계에 쓰는 오답 한 문제의 정보 */
internal class StatEntry(val symbol: Int, val tags: List<String>, val stage: Int, val dueDay: Long, val history: String)

/** 묶음(기호 하나·해시태그 하나)의 문제 수, 복습한 문제 수, 최근 채점으로 본 정답률 (채점 기록이 없으면 null) */
internal class StatGroup(val key: String, val count: Int, val reviewed: Int, val accuracy: Float?)

/** 전체 요약. [stages]는 0~5단계별 문제 수 (0단계 = 복습 전) */
internal class StatSummary(
    val total: Int,
    val reviewed: Int,
    val dueToday: Int,
    val dueTomorrow: Int,
    /** 내일부터 7일 안에 복습일이 오는 문제 수 (내일 것 포함, 오늘 복습할 것 제외) */
    val dueWithinWeek: Int,
    val accuracy: Float?,
    val stages: IntArray,
)

/**
 * 오답 복습 통계. 정답률은 문제마다 낸 평균들의 평균이 아니라 묶음 안의 모든 채점 기록을 한데 모아 (맞음 1, 애매 0.5, 틀림 0) 낸다.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 */
internal object ReviewStats {

    private fun accuracyOf(entries: List<StatEntry>): Float? {
        var twice = 0
        var n = 0
        for (e in entries) {
            val (t, c) = ReviewSchedule.points(e.history)
            twice += t
            n += c
        }
        return if (n == 0) null else twice / (2f * n)
    }

    fun summary(list: List<StatEntry>, today: Long): StatSummary {
        val stages = IntArray(ReviewSchedule.MAX_STAGE + 1)
        for (e in list) stages[e.stage.coerceIn(0, ReviewSchedule.MAX_STAGE)]++
        return StatSummary(
            total = list.size,
            reviewed = list.count { it.stage > 0 },
            dueToday = list.count { ReviewSchedule.isDue(it.stage, it.dueDay, today) },
            dueTomorrow = list.count { it.stage > 0 && it.dueDay == today + 1 },
            dueWithinWeek = list.count { it.stage > 0 && it.dueDay in (today + 1)..(today + 7) },
            accuracy = accuracyOf(list),
            stages = stages,
        )
    }

    private fun group(key: String, entries: List<StatEntry>) =
        StatGroup(key, entries.size, entries.count { it.stage > 0 }, accuracyOf(entries))

    /** 기호별 ([WrongSymbol.order] 차례). key는 기호 번호 글 */
    fun bySymbol(list: List<StatEntry>): List<StatGroup> =
        list.groupBy { it.symbol }.toSortedMap(compareBy { WrongSymbol.order(it) }).map { (s, l) -> group(s.toString(), l) }

    /**
     * 해시태그별, 약한 것부터: 정답률이 낮은 것이 먼저, 아직 채점 기록이 없는 것은 뒤에서 문제 수가 많은 차례.
     * 한 문제에 태그가 여럿이면 각 태그에 한 번씩 센다
     */
    fun byTag(list: List<StatEntry>): List<StatGroup> {
        val map = LinkedHashMap<String, MutableList<StatEntry>>()
        for (e in list) for (t in e.tags.distinct()) map.getOrPut(t) { ArrayList() }.add(e)
        return map.map { (t, l) -> group(t, l) }.sortedWith(
            compareBy<StatGroup> { it.accuracy == null }.thenBy { it.accuracy ?: 0f }.thenByDescending { it.count }.thenBy { it.key }
        )
    }
}
