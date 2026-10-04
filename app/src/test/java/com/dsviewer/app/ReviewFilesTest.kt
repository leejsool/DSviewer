package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** 복습 풀이 파일 이름과 머리줄 글 */
class ReviewFilesTest {
    private val seoul = ZoneId.of("Asia/Seoul")
    private fun millis(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int) =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, seoul).toInstant().toEpochMilli()

    @Test fun nameHasDateAndTime() {
        assertEquals("시험지_복습_2026-10-05 19.45.12.pdf", ReviewFiles.fileName("시험지.pdf", millis(2026, 10, 5, 19, 45, 12), seoul))
    }

    @Test fun timeUsesZoneAndZeroPads() {
        assertEquals("a_복습_2026-01-02 03.04.05.pdf", ReviewFiles.fileName("a.pdf", millis(2026, 1, 2, 3, 4, 5), seoul))
        // 같은 순간도 시간대가 다르면 다르게 적힌다
        val utc = ReviewFiles.fileName("a.pdf", millis(2026, 1, 2, 3, 4, 5), ZoneId.of("UTC"))
        assertEquals("a_복습_2026-01-01 18.04.05.pdf", utc)
    }

    @Test fun nameDropsForbiddenCharactersAndExtension() {
        val n = ReviewFiles.fileName("수학: 1/2*문제?.pdf", millis(2026, 10, 5, 0, 0, 0), seoul)
        assertTrue(n, n.startsWith("수학_ 1_2_문제__복습_"))
        assertTrue(n.none { it in "\\/:*?\"<>|" })
        assertTrue(n.endsWith(".pdf"))
        assertEquals(1, Regex("\\.pdf").findAll(n).count())
    }

    @Test fun veryLongOrEmptyNamesAreHandled() {
        val long = ReviewFiles.fileName("가".repeat(200) + ".pdf", millis(2026, 10, 5, 0, 0, 0), seoul)
        assertTrue(long.length < 100)
        assertTrue(long.startsWith("가".repeat(60) + "_복습_"))
        assertTrue(ReviewFiles.fileName("", millis(2026, 10, 5, 0, 0, 0), seoul).startsWith("문서_복습_"))
    }

    @Test fun uniqueSkipsExistingNames() {
        val taken = setOf("a.pdf", "a (2).pdf")
        assertEquals("a (3).pdf", ReviewFiles.unique("a.pdf") { it in taken })
        assertEquals("b.pdf", ReviewFiles.unique("b.pdf") { it in taken })
    }

    @Test fun headerTextShowsStageNextDateAndAccuracy() {
        val due = LocalDate.of(2026, 10, 8).toEpochDay()
        assertEquals("복습 2단계 · 다음 10.08 · 정답률 67%", ReviewSchedule.headerText(2, due, "OOX"))
        assertEquals("복습 3단계 · 다음 12.25", ReviewSchedule.headerText(3, LocalDate.of(2026, 12, 25).toEpochDay(), ""))
    }

    @Test fun headerTextIsEmptyBeforeFirstReview() {
        assertNull(ReviewSchedule.headerText(0, 0, ""))
        assertNull(ReviewSchedule.headerText(0, 12345, "OX"))
    }
}
