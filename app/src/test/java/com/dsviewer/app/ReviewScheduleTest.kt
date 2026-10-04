package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 오답 복습 일정 */
class ReviewScheduleTest {
    private val today = 20000L
    private fun next(stage: Int, r: ReviewResult) = ReviewSchedule.next(stage, today, r)

    // ================= 채점 뒤 일정 =================

    @Test fun firstRightAnswerSkipsToStageTwo() {
        val n = next(0, ReviewResult.RIGHT)
        assertEquals(2, n.stage)
        assertEquals(today + 3, n.dueDay)
    }

    @Test fun rightAnswersClimbAndIntervalsGrow() {
        var stage = 0
        val days = ArrayList<Long>()
        repeat(5) {
            val n = next(stage, ReviewResult.RIGHT)
            stage = n.stage
            days.add(n.dueDay - today)
        }
        assertEquals(listOf(3L, 7L, 14L, 30L, 30L), days)
        assertEquals(ReviewSchedule.MAX_STAGE, stage)
    }

    @Test fun wrongAnswerGoesBackToStageOneAndTomorrow() {
        for (s in 0..5) {
            val n = next(s, ReviewResult.WRONG)
            assertEquals(1, n.stage)
            assertEquals(today + 1, n.dueDay)
        }
    }

    @Test fun unsureKeepsStageAndHalvesInterval() {
        assertEquals(1L, next(1, ReviewResult.UNSURE).dueDay - today)
        assertEquals(1L, next(2, ReviewResult.UNSURE).dueDay - today)
        assertEquals(3L, next(3, ReviewResult.UNSURE).dueDay - today)
        assertEquals(7L, next(4, ReviewResult.UNSURE).dueDay - today)
        assertEquals(15L, next(5, ReviewResult.UNSURE).dueDay - today)
        for (s in 1..5) assertEquals(s, next(s, ReviewResult.UNSURE).stage)
        // 처음 본 것이 애매하면 1단계
        assertEquals(1, next(0, ReviewResult.UNSURE).stage)
    }

    // ================= 오늘 복습할 차례 =================

    @Test fun neverReviewedIsAlwaysDue() {
        assertTrue(ReviewSchedule.isDue(0, 0, today))
        assertTrue(ReviewSchedule.isDue(0, today + 99, today))
    }

    @Test fun dueOnOrAfterDueDay() {
        assertFalse(ReviewSchedule.isDue(2, today + 1, today))
        assertTrue(ReviewSchedule.isDue(2, today, today))
        assertTrue(ReviewSchedule.isDue(2, today - 5, today))
    }

    // ================= 채점 기록 =================

    @Test fun historyKeepsLastEight() {
        var h = ""
        repeat(10) { h = ReviewSchedule.appendHistory(h, if (it % 2 == 0) ReviewResult.RIGHT else ReviewResult.WRONG) }
        assertEquals(8, h.length)
        assertEquals("OXOXOXOX", h)
        assertEquals("OXOXOXOXH".takeLast(8), ReviewSchedule.appendHistory(h, ReviewResult.UNSURE))
    }

    @Test fun accuracyCountsUnsureAsHalf() {
        assertNull(ReviewSchedule.accuracy(""))
        assertEquals(1f, ReviewSchedule.accuracy("OOO")!!, 0.001f)
        assertEquals(0f, ReviewSchedule.accuracy("XX")!!, 0.001f)
        assertEquals(0.5f, ReviewSchedule.accuracy("OX")!!, 0.001f)
        assertEquals(0.5f, ReviewSchedule.accuracy("H")!!, 0.001f)
        assertEquals(0.625f, ReviewSchedule.accuracy("OOHX")!!, 0.001f)
    }

    // ================= 목록 글 =================

    @Test fun labelDescribesState() {
        assertEquals("복습 전", ReviewSchedule.label(0, 0, today))
        assertEquals("복습 2단계 · 오늘", ReviewSchedule.label(2, today, today))
        assertEquals("복습 2단계 · 내일", ReviewSchedule.label(2, today + 1, today))
        assertEquals("복습 3단계 · 5일 뒤", ReviewSchedule.label(3, today + 5, today))
        assertEquals("복습 1단계 · 2일 지남", ReviewSchedule.label(1, today - 2, today))
    }

    // ================= 저장 글 =================

    @Test fun fieldsRoundTrip() {
        val f = ReviewSchedule.Fields(3, 19990L, 20007L, "OXH")
        val back = ReviewSchedule.decodeFields(ReviewSchedule.encodeFields(f))
        assertEquals(3, back.stage)
        assertEquals(19990L, back.lastDay)
        assertEquals(20007L, back.dueDay)
        assertEquals("OXH", back.history)
    }

    @Test fun oldFilesWithoutReviewFieldsMeanNotReviewed() {
        val f = ReviewSchedule.decodeFields(emptyList())
        assertEquals(0, f.stage)
        assertEquals("", f.history)
        assertTrue(ReviewSchedule.isDue(f.stage, f.dueDay, today))
    }

    @Test fun brokenFieldsAreForgiven() {
        assertEquals(0, ReviewSchedule.decodeFields(listOf("abc", "1", "2", "O")).stage)
        val f = ReviewSchedule.decodeFields(listOf("9", "x", "y", "O?Z X"))
        assertEquals(ReviewSchedule.MAX_STAGE, f.stage)   // 범위를 벗어난 단계는 맨 위로
        assertEquals(0L, f.lastDay)
        assertEquals("OX", f.history)                      // 알 수 없는 글자는 버린다
        assertEquals("OOOOOOOO", ReviewSchedule.decodeFields(listOf("1", "0", "0", "O".repeat(12))).history)
    }
}
