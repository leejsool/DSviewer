package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 필기가 쌓여도 메모리를 아끼는 장치 (남는 칸 돌려주기, 실행 취소 기록 상한) */
class InkMemoryTest {

    private fun stroke(points: Int) = Stroke(Tool.PEN, 0xFF000000.toInt(), 2f).also {
        for (i in 0 until points) it.add(i.toFloat(), i * 2f, 0.5f)
    }

    @Test fun trimKeepsEveryPointAndShrinksTheArray() {
        val s = stroke(70)  // 64점짜리 배열이 한 번 두 배로 자라 128점 크기
        assertTrue(s.data.size > 70 * 3)
        s.trim()
        assertEquals(70 * 3, s.data.size)
        assertEquals(70, s.count)
        assertEquals(138f, s.y(69), 0f)
        assertEquals(0.5f, s.p(69), 0f)
    }

    @Test fun trimmedStrokeCanStillGrow() {
        val s = stroke(5)
        s.trim()
        s.add(100f, 200f, 0.7f)
        s.add(101f, 201f, 0.7f)
        assertEquals(7, s.count)
        assertEquals(201f, s.y(6), 0f)
    }

    @Test fun emptyStrokeCanGrowAfterTrim() {
        val s = Stroke(Tool.PEN, 0, 1f)
        s.trim()
        s.add(1f, 2f, 0.5f)
        assertEquals(1, s.count)
        assertEquals(2f, s.y(0), 0f)
    }

    @Test fun releasingCachesKeepsThePoints() {
        val s = stroke(10)
        s.releaseCaches()
        assertEquals(10, s.count)
        assertEquals(18f, s.y(9), 0f)
        assertTrue(s.mayTouch(0f, 0f, 50f, 50f))
    }

    @Test fun documentReleaseCachesTouchesEveryPage() {
        val doc = InkDocument(3)
        for (p in 0 until 3) doc.add(p, stroke(4))
        doc.releaseCaches()
        assertEquals(1, doc.pages[2].size)
    }

    @Test fun undoHistoryIsCappedButRecentOnesStillUndo() {
        val doc = InkDocument(1)
        val n = InkDocument.MAX_UNDO + 20
        for (i in 0 until n) doc.add(0, stroke(2))
        var undone = 0
        while (doc.canUndo) { doc.undo(); undone++ }
        assertEquals(InkDocument.MAX_UNDO, undone)
        // 오래된 20획은 기록에서 빠져 되돌려지지 않고 남는다
        assertEquals(20, doc.pages[0].size)
        assertFalse(doc.canUndo)
        assertTrue(doc.canRedo)
    }
}
