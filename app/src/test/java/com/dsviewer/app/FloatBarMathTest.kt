package com.dsviewer.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 플로팅 툴바 계산 */
class FloatBarMathTest {

    @Test fun columnsGrowWithButtonCount() {
        assertEquals(2, FloatBarMath.columns(4))   // 2×2
        assertEquals(2, FloatBarMath.columns(6))   // 2×3
        assertEquals(3, FloatBarMath.columns(7))
        assertEquals(3, FloatBarMath.columns(12))  // 3×4
        assertEquals(4, FloatBarMath.columns(13))
        assertEquals(2, FloatBarMath.rows(4))
        assertEquals(3, FloatBarMath.rows(5))
        assertEquals(3, FloatBarMath.rows(7))
        assertEquals(4, FloatBarMath.rows(16))
    }

    @Test fun defaultSlotsAreUndoPenHighlighterAndLasso() {
        assertEquals(listOf("undo", "PEN", "HIGHLIGHTER", "LASSO"), FloatBarMath.slots(emptyList(), emptyList()))
    }

    @Test fun recentSlotFollowsLastNonPenTool() {
        var h = emptyList<String>()
        h = FloatBarMath.pushHistory(h, "ERASER")
        h = FloatBarMath.pushHistory(h, "PEN")          // 펜·형광펜은 기록하지 않는다
        h = FloatBarMath.pushHistory(h, "HIGHLIGHTER")
        assertEquals(listOf("ERASER"), h)
        assertEquals("ERASER", FloatBarMath.slots(h, emptyList())[3])
        h = FloatBarMath.pushHistory(h, "TEXT")
        h = FloatBarMath.pushHistory(h, "ERASER")       // 다시 고르면 맨 앞으로
        assertEquals(listOf("ERASER", "TEXT"), h)
    }

    @Test fun recentSlotSkipsToolsAlreadyAdded() {
        val h = listOf("ERASER", "TEXT")
        // 지우개를 단추로 더했으면 직전 도구 칸에는 그다음 최근 도구 (같은 아이콘이 두 번 나오지 않는다)
        assertEquals(listOf("undo", "PEN", "HIGHLIGHTER", "TEXT", "ERASER"), FloatBarMath.slots(h, listOf("ERASER")))
        // 기록이 모두 올라가 있으면 후보 차례에서 빈 것
        assertEquals("LASSO", FloatBarMath.recentSlot(listOf("ERASER"), listOf("ERASER")))
    }

    @Test fun extrasKeepOrderAndDropFixedAndDuplicates() {
        val s = FloatBarMath.slots(emptyList(), listOf("redo", "PEN", "save", "redo"))
        assertEquals(listOf("undo", "PEN", "HIGHLIGHTER", "LASSO", "redo", "save"), s)
    }

    @Test fun defaultButtonsCanBeTurnedOff() {
        // 펜을 끄면 빠지고 나머지 차례는 그대로
        assertEquals(listOf("undo", "HIGHLIGHTER", "LASSO"), FloatBarMath.slots(emptyList(), emptyList(), listOf("PEN")))
        // 셋 다 끄면 직전 도구 하나만 (1칸)
        val one = FloatBarMath.slots(emptyList(), emptyList(), FloatBarMath.DEFAULTS)
        assertEquals(listOf("LASSO"), one)
        assertEquals(1, FloatBarMath.columns(one.size))
        assertEquals(2, FloatBarMath.columns(2))   // 1×2
        assertEquals(2, FloatBarMath.rows(3))      // 2칸 + 1
    }

    @Test fun parseKeysDropsUnknown() {
        assertEquals(listOf("redo", "ERASER"), FloatBarMath.parseKeys("redo, ERASER,,gone,redo", setOf("redo", "ERASER")))
        assertTrue(FloatBarMath.parseKeys(null, setOf("redo")).isEmpty())
    }

    @Test fun tintAlphaFollowsTransparency() {
        assertEquals(0.12f, FloatBarMath.tintAlpha(88), 1e-6f)
        assertEquals(0f, FloatBarMath.tintAlpha(100), 1e-6f)
        assertEquals(0.6f, FloatBarMath.tintAlpha(10), 1e-6f)   // 40% 아래로는 내려가지 않는다
    }

    @Test fun positionFractionSurvivesResize() {
        val f = FloatBarMath.toFraction(300f, 100f, 1100f)
        assertEquals(0.3f, f, 1e-6f)
        assertEquals(150f, FloatBarMath.fromFraction(f, 100f, 600f), 1e-4f)
        assertEquals(0f, FloatBarMath.toFraction(50f, 200f, 150f), 0f)   // 툴바가 화면보다 크면 왼쪽 끝
        assertEquals(500f, FloatBarMath.clamp(900f, 100f, 600f), 0f)
        assertEquals(0f, FloatBarMath.clamp(-20f, 100f, 600f), 0f)
    }

    @Test fun boxBlurSpreadsAndKeepsFlatAreas() {
        val w = 5
        val h = 1
        val black = 0xFF000000.toInt()
        val white = 0xFFFFFFFF.toInt()
        val px = intArrayOf(black, black, white, black, black)
        FloatBarMath.boxBlur(px, w, h, radius = 1, passes = 1)
        // 가운데 흰 점이 양옆으로 번진다
        assertEquals(0xFF555555.toInt(), px[1])
        assertEquals(0xFF555555.toInt(), px[2])
        assertEquals(0xFF555555.toInt(), px[3])
        assertEquals(black, px[0])
        val flat = IntArray(9) { 0x80336699.toInt() }
        FloatBarMath.boxBlur(flat, 3, 3, radius = 2)
        assertArrayEquals(IntArray(9) { 0x80336699.toInt() }, flat)
    }
}
