package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 나눠 보기 둘째 칸 자리를 문서별로 적어 둘 때의 글자 모양 (적었다가 읽으면 그대로) */
class SplitPositionsTest {

    @Test fun 적었다가_읽으면_그대로() {
        val s = SplitPositions.parse(SplitPositions.format(DocumentView.ViewState(1.75f, -120.5f, -3456.25f), 99L))!!
        assertEquals(1.75f, s.zoom, 0f)
        assertEquals(-120.5f, s.offX, 0f)
        assertEquals(-3456.25f, s.offY, 0f)
    }

    @Test fun 깨진_줄은_버린다() {
        assertNull(SplitPositions.parse(""))
        assertNull(SplitPositions.parse("1.0,2.0"))
        assertNull(SplitPositions.parse("a,b,c,1"))
        assertNull(SplitPositions.parse("NaN,0,0,1"))
    }
}
