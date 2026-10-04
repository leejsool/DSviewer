package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 오답 복습 통계 */
class ReviewStatsTest {
    private val today = 20000L
    private fun e(symbol: Int = 0, tags: List<String> = emptyList(), stage: Int = 0, due: Long = 0, history: String = "") =
        StatEntry(symbol, tags, stage, due, history)

    @Test fun pointsCountsRightUnsureWrong() {
        assertEquals(0 to 0, ReviewSchedule.points(""))
        assertEquals(3 to 3, ReviewSchedule.points("OHX"))   // 2 + 1 + 0 (두 배한 점수)
        assertEquals(4 to 2, ReviewSchedule.points("O?O"))   // 알 수 없는 글자는 세지 않는다
        assertEquals(2 to 1, ReviewSchedule.points("O"))
    }

    @Test fun summaryCountsStagesAndDueDates() {
        val list = listOf(
            e(stage = 0),                                  // 복습 전: 오늘 복습할 차례
            e(stage = 1, due = today - 3, history = "X"),  // 밀림: 오늘
            e(stage = 2, due = today, history = "O"),      // 오늘
            e(stage = 3, due = today + 1, history = "OO"), // 내일
            e(stage = 3, due = today + 7, history = "O"),  // 7일째: 7일 안
            e(stage = 5, due = today + 30, history = "OOO"),
        )
        val s = ReviewStats.summary(list, today)
        assertEquals(6, s.total)
        assertEquals(5, s.reviewed)
        assertEquals(3, s.dueToday)
        assertEquals(1, s.dueTomorrow)
        assertEquals(2, s.dueWithinWeek)
        assertEquals(listOf(1, 1, 1, 2, 0, 1), s.stages.toList())
    }

    @Test fun summaryAccuracyPoolsAllGradings() {
        // 한 문제는 3번 모두 맞음, 다른 문제는 1번 틀림 → 4번 중 3번: 75% (평균의 평균이면 50%)
        val s = ReviewStats.summary(listOf(e(stage = 3, due = today + 5, history = "OOO"), e(stage = 1, due = today + 1, history = "X")), today)
        assertEquals(0.75f, s.accuracy!!, 0.001f)
    }

    @Test fun summaryOfNothing() {
        val s = ReviewStats.summary(emptyList(), today)
        assertEquals(0, s.total)
        assertNull(s.accuracy)
        assertEquals(0, s.dueToday)
        assertEquals(6, s.stages.size)
    }

    @Test fun unreviewedHasNoAccuracy() {
        assertNull(ReviewStats.summary(listOf(e(), e()), today).accuracy)
    }

    @Test fun bySymbolFollowsSymbolOrderWithCounts() {
        val list = listOf(
            e(symbol = WrongSymbol.SQUARE, stage = 1, history = "X"),
            e(symbol = 5, stage = 2, history = "O"),
            e(symbol = WrongSymbol.CROSS, stage = 1, history = "OX"),
            e(symbol = 5, stage = 0),
        )
        val g = ReviewStats.bySymbol(list)
        // 별 5개 → 엑스 → 네모 차례
        assertEquals(listOf("5", WrongSymbol.CROSS.toString(), WrongSymbol.SQUARE.toString()), g.map { it.key })
        assertEquals(listOf(2, 1, 1), g.map { it.count })
        assertEquals(listOf(1, 1, 1), g.map { it.reviewed })
        assertEquals(1f, g[0].accuracy!!, 0.001f)
        assertEquals(0.5f, g[1].accuracy!!, 0.001f)
        assertEquals(0f, g[2].accuracy!!, 0.001f)
    }

    @Test fun byTagListsWeakestFirstAndUnreviewedLast() {
        val list = listOf(
            e(tags = listOf("이차방정식", "계산"), stage = 2, history = "OO"),
            e(tags = listOf("이차방정식"), stage = 1, history = "X"),   // 이차방정식: 2/3
            e(tags = listOf("확률"), stage = 1, history = "XX"),         // 확률: 0
            e(tags = listOf("도형")),                                    // 도형: 복습 전
            e(tags = listOf("수열", "도형")),                            // 수열·도형 복습 전: 도형이 문제 2개
        )
        val g = ReviewStats.byTag(list)
        assertEquals(listOf("확률", "이차방정식", "계산", "도형", "수열"), g.map { it.key })
        assertEquals(0f, g[0].accuracy!!, 0.001f)
        assertEquals(2 / 3f, g[1].accuracy!!, 0.001f)
        assertEquals(1f, g[2].accuracy!!, 0.001f)
        assertNull(g[3].accuracy)
        assertEquals(2, g[3].count)
        assertEquals(0, g[3].reviewed)
    }

    @Test fun repeatedTagOnOneProblemCountsOnce() {
        val g = ReviewStats.byTag(listOf(e(tags = listOf("a", "a", "b"))))
        assertEquals(2, g.size)
        assertEquals(1, g.first { it.key == "a" }.count)
    }
}
