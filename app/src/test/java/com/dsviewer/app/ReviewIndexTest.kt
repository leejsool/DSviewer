package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 여러 문서의 오늘 복습할 오답 색인 */
class ReviewIndexTest {
    private val today = 20000L
    private fun e(stage: Int, due: Long = 0) = StatEntry(0, emptyList(), stage, due, "")

    @Test fun summarizeSplitsUnreviewedAndSortsDueDays() {
        val item = ReviewIndex.summarize("u", "시험지.pdf", listOf(e(0), e(2, today + 5), e(1, today - 1), e(0), e(3, today)))!!
        assertEquals(2, item.unreviewed)
        assertEquals(listOf(today - 1, today, today + 5), item.dueDays)
    }

    @Test fun noWrongsMeansNoItem() {
        assertNull(ReviewIndex.summarize("u", "a.pdf", emptyList()))
    }

    @Test fun dueCountIncludesUnreviewedAndPastOrToday() {
        val item = ReviewIndexItem("u", "n", unreviewed = 2, dueDays = listOf(today - 1, today, today + 1, today + 30))
        assertEquals(2 + 2, item.dueCount(today))
        // 날짜가 지나면 다시 센다: 하루 뒤엔 내일 것도 오늘 것
        assertEquals(2 + 3, item.dueCount(today + 1))
        assertEquals(2 + 4, item.dueCount(today + 30))
    }

    @Test fun upsertReplacesRemovesAndPutsNewestFirst() {
        val a = ReviewIndexItem("a", "A", 1, emptyList())
        val b = ReviewIndexItem("b", "B", 1, emptyList())
        val a2 = ReviewIndexItem("a", "A2", 5, emptyList())
        val list = ReviewIndex.upsert(ReviewIndex.upsert(emptyList(), "a", a), "b", b)
        assertEquals(listOf("b", "a"), list.map { it.uri })
        val replaced = ReviewIndex.upsert(list, "a", a2)
        assertEquals(listOf("a", "b"), replaced.map { it.uri })
        assertEquals("A2", replaced[0].name)
        assertEquals(listOf("a"), ReviewIndex.upsert(list, "b", null).map { it.uri })
    }

    @Test fun dueItemsSkipsEmptyAndSortsByCount() {
        val items = listOf(
            ReviewIndexItem("x", "none", 0, listOf(today + 3)),
            ReviewIndexItem("y", "two", 0, listOf(today, today - 2)),
            ReviewIndexItem("z", "five", 5, emptyList()),
        )
        val due = ReviewIndex.dueItems(items, today)
        assertEquals(listOf("z", "y"), due.map { it.first.uri })
        assertEquals(listOf(5, 2), due.map { it.second })
    }

    @Test fun codecRoundTrips() {
        val items = listOf(
            ReviewIndexItem("file:///sdcard/Download/%EC%8B%9C%ED%97%98.pdf", "시험 문제집.pdf", 3, listOf(19999L, 20005L)),
            ReviewIndexItem("content://docs/7", "이름\t탭\n줄바꿈", 0, emptyList()),
        )
        val back = ReviewIndex.decode(ReviewIndex.encode(items))
        assertEquals(2, back.size)
        assertEquals("시험 문제집.pdf", back[0].name)
        assertEquals(3, back[0].unreviewed)
        assertEquals(listOf(19999L, 20005L), back[0].dueDays)
        assertEquals("이름 탭 줄바꿈", back[1].name)   // 형식을 깨는 글자는 공백으로
        assertTrue(back[1].dueDays.isEmpty())
    }

    @Test fun decodeSkipsBrokenLines() {
        val back = ReviewIndex.decode("\n쓰레기\nu1\tn\tx\t1,2\nu2\tn\t4\t7,oops,9\n\tn\t1\t\n")
        assertEquals(1, back.size)
        assertEquals("u2", back[0].uri)
        assertEquals(listOf(7L, 9L), back[0].dueDays)
    }
}
