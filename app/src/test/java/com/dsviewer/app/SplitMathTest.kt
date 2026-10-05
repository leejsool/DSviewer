package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 분할 보기 계산 */
class SplitMathTest {

    @Test fun landscapeSplitsSideBySidePortraitStacks() {
        assertTrue(SplitMath.sideBySide(2800, 1752))
        assertFalse(SplitMath.sideBySide(1752, 2800))
        assertFalse(SplitMath.sideBySide(1000, 1000))   // 정사각형은 위아래
    }

    @Test fun ratioIsClampedToTheAllowedRange() {
        assertEquals(0.2f, SplitMath.clamp(0f), 0f)
        assertEquals(0.8f, SplitMath.clamp(1f), 0f)
        assertEquals(0.35f, SplitMath.clamp(0.35f), 0f)
        assertEquals(0.5f, SplitMath.clamp(Float.NaN), 0f)
    }

    @Test fun firstLengthLeavesRoomForTheDivider() {
        assertEquals(990, SplitMath.firstLength(2000, 20, 0.5f))
        // 첫 칸 + 구분선 + 둘째 칸 = 전체
        for (r in listOf(0.2f, 0.33f, 0.5f, 0.71f, 0.8f)) {
            val a = SplitMath.firstLength(2800, 24, r)
            assertEquals(2800, a + 24 + (2800 - 24 - a))
            assertTrue(a in 1 until 2800 - 24)
        }
        assertEquals(0, SplitMath.firstLength(10, 24, 0.5f))   // 구분선보다 작은 화면
    }

    @Test fun ratioAtMatchesFirstLength() {
        // 구분선 가운데가 첫 칸 끝 + 절반 두께에 오면 그 첫 칸 비율이 나온다
        val total = 2000
        val d = 20
        val first = SplitMath.firstLength(total, d, 0.3f)
        assertEquals(0.3f, SplitMath.ratioAt(first + d / 2f, total, d), 1e-3f)
    }

    @Test fun draggingNearTheMiddleSnapsToHalf() {
        assertEquals(0.5f, SplitMath.ratioAt(1000f, 2000, 20), 0f)
        assertEquals(0.5f, SplitMath.ratioAt(1000f + 40f, 2000, 20), 0f)    // 2% 안
        assertTrue(SplitMath.ratioAt(1000f + 150f, 2000, 20) > 0.55f)         // 7.5% 밖은 그대로
        assertTrue(SplitMath.ratioAt(1000f - 150f, 2000, 20) < 0.45f)
    }

    @Test fun draggingPastTheEdgesStopsAtTheLimits() {
        assertEquals(0.2f, SplitMath.ratioAt(-500f, 2000, 20), 0f)
        assertEquals(0.8f, SplitMath.ratioAt(9999f, 2000, 20), 0f)
        assertEquals(0.2f, SplitMath.ratioAt(5f, 0, 20), 0f)   // 길이 0이어도 터지지 않고 한계 안
    }
}
