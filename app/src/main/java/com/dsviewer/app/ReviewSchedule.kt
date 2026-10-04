package com.dsviewer.app

import java.time.LocalDate
import java.util.Locale
import kotlin.math.roundToInt

/** 복습 채점: 맞음 · 애매 · 틀림 */
internal enum class ReviewResult(val code: Char) {
    RIGHT('O'), UNSURE('H'), WRONG('X');

    companion object {
        fun of(code: Char) = entries.firstOrNull { it.code == code }
    }
}

/**
 * 오답 복습 일정 (라이트너 방식). 오답마다 단계 0~5와 다음 복습일을 둔다.
 * 단계 0은 한 번도 복습하지 않은 것. 날짜는 1970-01-01부터 센 날수로 다룬다 (시험하기 쉽고 날짜 글 형식에 기대지 않게).
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 */
internal object ReviewSchedule {
    /** 단계 1~5에서 다음 복습까지의 간격 (일) */
    private val INTERVALS = intArrayOf(1, 3, 7, 14, 30)
    const val MAX_STAGE = 5
    /** 기억해 둘 최근 채점 수 */
    const val HISTORY_MAX = 8

    fun interval(stage: Int): Int = INTERVALS[stage.coerceIn(1, MAX_STAGE) - 1]

    /** 채점한 뒤의 단계와 다음 복습일 */
    class Next(val stage: Int, val dueDay: Long)

    /**
     * 맞으면 한 단계 올라 간격이 늘고 (처음 복습해서 맞히면 2단계), 틀리면 1단계로 돌아가 내일 다시.
     * 애매하면 단계는 그대로이고 간격만 절반으로 줄여 (적어도 하루) 더 일찍 다시 본다
     */
    fun next(stage: Int, today: Long, result: ReviewResult): Next = when (result) {
        ReviewResult.RIGHT -> {
            val s = if (stage <= 0) 2 else minOf(stage + 1, MAX_STAGE)
            Next(s, today + interval(s))
        }
        ReviewResult.UNSURE -> {
            val s = stage.coerceIn(1, MAX_STAGE)
            Next(s, today + maxOf(1, interval(s) / 2))
        }
        ReviewResult.WRONG -> Next(1, today + interval(1))
    }

    /** 복습 순서: 한 번도 안 본 것이 먼저, 그다음 복습일이 이른(많이 밀린) 것부터 */
    fun order(stage: Int, dueDay: Long): Long = if (stage <= 0) Long.MIN_VALUE else dueDay

    /** 오늘 복습할 차례인가: 한 번도 안 본 것이거나 다음 복습일이 됐거나 지났다 */
    fun isDue(stage: Int, dueDay: Long, today: Long) = stage <= 0 || dueDay <= today

    /** 채점 기록 글([history])에 [result]를 붙이고 최근 [HISTORY_MAX]개만 남긴다 */
    fun appendHistory(history: String, result: ReviewResult): String = (history + result.code).takeLast(HISTORY_MAX)

    /** 최근 채점의 정답률 (맞음 1, 애매 0.5, 틀림 0). 기록이 없으면 null */
    fun accuracy(history: String): Float? {
        val rs = history.mapNotNull { ReviewResult.of(it) }
        if (rs.isEmpty()) return null
        return rs.sumOf { if (it == ReviewResult.RIGHT) 2 else if (it == ReviewResult.UNSURE) 1 else 0 } / (2f * rs.size)
    }

    /** 채점 기록 글의 점수: (두 배한 점수 합 (맞음 2, 애매 1, 틀림 0), 채점 수). 묶음 정답률을 기록 전체로 내려고 쓴다 */
    fun points(history: String): Pair<Int, Int> {
        var twice = 0
        var n = 0
        for (c in history) {
            val r = ReviewResult.of(c) ?: continue
            twice += when (r) {
                ReviewResult.RIGHT -> 2
                ReviewResult.UNSURE -> 1
                ReviewResult.WRONG -> 0
            }
            n++
        }
        return twice to n
    }

    /** 목록에 보일 복습 상태 글 */
    fun label(stage: Int, dueDay: Long, today: Long): String {
        if (stage <= 0) return "복습 전"
        val d = dueDay - today
        return "복습 ${stage}단계 · " + when {
            d < 0 -> "${-d}일 지남"
            d == 0L -> "오늘"
            d == 1L -> "내일"
            else -> "${d}일 뒤"
        }
    }

    /**
     * 오답 쪽 머리줄에 그릴 복습 글: '복습 2단계 · 다음 10.08 · 정답률 67%'. 그림이라 '오늘·내일'처럼 날마다 변하는 말은 못 쓰고
     * 날짜로 적는다. 한 번도 복습하지 않았으면 null (머리줄에 아무것도 넣지 않는다)
     */
    fun headerText(stage: Int, dueDay: Long, history: String): String? {
        if (stage <= 0) return null
        val d = LocalDate.ofEpochDay(dueDay)
        val acc = accuracy(history)?.let { " · 정답률 ${(it * 100).roundToInt()}%" } ?: ""
        return "복습 ${stage}단계 · 다음 " + String.format(Locale.US, "%d.%02d", d.monthValue, d.dayOfMonth) + acc
    }

    // ---- 날짜 ----

    fun today(): Long = LocalDate.now().toEpochDay()

    // ---- 저장 글의 복습 필드 (WrongNote.encode 한 항목 뒤에 붙는 네 칸: 단계 · 마지막 복습일 · 다음 복습일 · 채점 기록) ----

    class Fields(val stage: Int, val lastDay: Long, val dueDay: Long, val history: String)

    fun encodeFields(f: Fields): List<String> = listOf(f.stage.toString(), f.lastDay.toString(), f.dueDay.toString(), f.history)

    /** [parts]는 저장 글의 칸들 중 복습 칸들. 모자라거나 알아볼 수 없으면 '복습 전'으로 */
    fun decodeFields(parts: List<String>): Fields {
        val stage = parts.getOrNull(0)?.toIntOrNull()?.coerceIn(0, MAX_STAGE) ?: return Fields(0, 0, 0, "")
        val history = parts.getOrNull(3).orEmpty().filter { ReviewResult.of(it) != null }.takeLast(HISTORY_MAX)
        return Fields(stage, parts.getOrNull(1)?.toLongOrNull() ?: 0L, parts.getOrNull(2)?.toLongOrNull() ?: 0L, history)
    }
}
