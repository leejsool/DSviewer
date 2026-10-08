package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 스크랩(예전 오답노트)의 '복습으로 정함' 여부: 저장 형식과 복습 목록에 오르는 조건 */
class ScrapReviewFlagTest {

    private fun entry(review: Boolean, stage: Int = 0, dueDay: Long = 0L) =
        WrongEntry(1, WrongSymbol.NONE, listOf("극한"), "제목", "2026-10-08", 0, null, null, stage, 0L, dueDay, "", review)

    @Test fun savedFormatKeepsTheReviewFlag() {
        for (flag in listOf(true, false)) {
            val text = WrongNote.encode(listOf(entry(flag, stage = 2, dueDay = 20000L))) { -1 }
            val back = WrongNote.decode(text).single().first
            assertEquals(flag, back.review)
            assertEquals(2, back.stage)
            assertEquals(20000L, back.dueDay)
        }
    }

    @Test fun oldFilesWithoutTheFlagAreAllReviewTargets() {
        // 복습 칸 네 개(단계·마지막·다음·기록)까지만 있는 옛 저장 글
        val old = "3|0|0|2026-10-01|-1||%EC%A0%9C%EB%AA%A9||2|19990|20001|OX"
        val e = WrongNote.decode(old).single().first
        assertTrue(e.review)
        assertEquals(2, e.stage)
    }

    @Test fun entriesNotChosenForReviewNeverComeUpDue() {
        val today = 20000L
        // 한 번도 복습하지 않은 새 스크랩은 복습으로 정했을 때만 오늘 복습 목록에 오른다
        assertTrue(entry(true).isDue(today))
        assertFalse(entry(false).isDue(today))
        // 복습하던 것도 복습에서 빼면 일정이 지나도 오르지 않는다
        assertFalse(entry(false, stage = 2, dueDay = today - 5).isDue(today))
        assertTrue(entry(true, stage = 2, dueDay = today - 5).isDue(today))
    }
}
