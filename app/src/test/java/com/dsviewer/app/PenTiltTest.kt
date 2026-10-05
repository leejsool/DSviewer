package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 펜 기울기 → 연필·붓펜 굵기 */
class PenTiltTest {

    private fun oldPencil(p: Float) = 0.65f + 0.5f * p.coerceIn(0f, 1f)
    private fun oldBrush(p: Float) = 0.08f + 1.8f * p.coerceIn(0f, 1f)

    // ================= 눕힘 정도 =================

    @Test fun normalWritingAngleIsNotShaded() {
        assertEquals(0f, PenTilt.shade(0f), 0f)
        assertEquals(0f, PenTilt.shade(PenTilt.START), 0f)
        assertEquals(0f, PenTilt.shade(PenTilt.START - 0.2f), 0f)
    }

    @Test fun flatPenIsFullyShaded() {
        assertEquals(1f, PenTilt.shade(PenTilt.FULL), 1e-6f)
        assertEquals(1f, PenTilt.shade((Math.PI / 2).toFloat()), 0f)
    }

    @Test fun shadeRisesSmoothlyAndNeverDecreases() {
        var last = -1f
        var t = 0f
        while (t <= 1.6f) {
            val s = PenTilt.shade(t)
            assertTrue("t=$t", s in 0f..1f && s >= last - 1e-6f)
            last = s
            t += 0.01f
        }
        // 가운데 값은 S자 곡선의 한가운데
        assertEquals(0.5f, PenTilt.shade((PenTilt.START + PenTilt.FULL) / 2), 1e-5f)
    }

    // ================= 옛 필기는 그대로 =================

    @Test fun oldPressureRangeKeepsTheSameWidthForPencilAndBrush() {
        val rnd = Random(3)
        repeat(5_000) {
            val p = rnd.nextFloat()
            assertEquals(oldPencil(p), PenStyle.PENCIL.factor(p), 1e-6f)
            assertEquals(oldBrush(p), PenStyle.BRUSH.factor(p), 1e-6f)
        }
    }

    @Test fun otherPensIgnoreValuesAboveOne() {
        for (style in listOf(PenStyle.FELT, PenStyle.BALL, PenStyle.FOUNTAIN, PenStyle.CALLIGRAPHY)) {
            assertEquals(style.name, style.factor(1f), style.factor(1.7f), 0f)
        }
    }

    @Test fun uprightPenStoresThePressureAsIs() {
        val rnd = Random(4)
        repeat(2_000) {
            val pr = rnd.nextFloat()
            assertEquals(pr, PenTilt.pencil(pr, 0f), 1e-5f)
            assertEquals(pr, PenTilt.brush(pr, 0f), 1e-5f)
        }
    }

    // ================= 눕히면 넓어진다 =================

    @Test fun pencilWidthIsTheNormalWidthTimesTheShadeFactor() {
        val rnd = Random(5)
        repeat(5_000) {
            val pr = rnd.nextFloat()
            val s = rnd.nextFloat()
            val want = oldPencil(pr) * (1f + (PenTilt.PENCIL_MAX - 1f) * s)
            assertEquals(want, PenStyle.PENCIL.factor(PenTilt.pencil(pr, s)), 1e-4f)
        }
    }

    @Test fun brushWidthIsTheNormalWidthTimesTheShadeFactor() {
        val rnd = Random(6)
        repeat(5_000) {
            val v = rnd.nextFloat()
            val s = rnd.nextFloat()
            val want = oldBrush(v) * (1f + (PenTilt.BRUSH_MAX - 1f) * s)
            assertEquals(want, PenStyle.BRUSH.factor(PenTilt.brush(v, s)), 1e-4f)
        }
    }

    @Test fun moreTiltIsNeverNarrower() {
        for (pr in listOf(0f, 0.3f, 0.7f, 1f)) {
            var lastPencil = 0f
            var lastBrush = 0f
            for (k in 0..20) {
                val s = k / 20f
                val a = PenStyle.PENCIL.factor(PenTilt.pencil(pr, s))
                val b = PenStyle.BRUSH.factor(PenTilt.brush(pr, s))
                assertTrue("연필 pr=$pr s=$s", a >= lastPencil - 1e-5f)
                assertTrue("붓펜 pr=$pr s=$s", b >= lastBrush - 1e-5f)
                lastPencil = a
                lastBrush = b
            }
        }
    }

    @Test fun storedValueStaysWithinZeroToTwoAndWidestMatchesTheReach() {
        assertEquals(2f, PenTilt.pencil(1f, 1f), 1e-5f)
        assertEquals(2f, PenTilt.brush(1f, 1f), 1e-5f)
        assertEquals(PenTilt.PENCIL_REACH, PenStyle.PENCIL.factor(2f), 1e-4f)
        assertEquals(PenTilt.BRUSH_REACH, PenStyle.BRUSH.factor(2f), 1e-4f)
        val rnd = Random(7)
        repeat(5_000) {
            val v = PenTilt.pencil(rnd.nextFloat(), rnd.nextFloat())
            assertTrue(v in 0f..2f)
            assertTrue(PenStyle.PENCIL.factor(v) <= PenTilt.PENCIL_REACH + 1e-4f)
        }
    }

    @Test fun valuesBeyondTheRangeAreClamped() {
        assertEquals(PenStyle.PENCIL.factor(2f), PenStyle.PENCIL.factor(9f), 0f)
        assertEquals(PenStyle.BRUSH.factor(0f), PenStyle.BRUSH.factor(-3f), 0f)
    }
}
