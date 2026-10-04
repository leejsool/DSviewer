package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt
import kotlin.random.Random

/** 칠하기·자유 영역의 판단과 모양 만들기 */
class FillRulesTest {

    private fun square(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray =
        floatArrayOf(x0, y0, x1, y0, x1, y1, x0, y1)

    private fun line(vararg xy: Float): Stroke {
        val st = Stroke(Tool.PEN, 0xFF000000.toInt(), 1f)
        for (i in xy.indices step 2) st.add(xy[i], xy[i + 1], 0.5f)
        return st
    }

    private fun filled(x0: Float, y0: Float, x1: Float, y1: Float): Stroke =
        FillRules.fillStroke(listOf(square(x0, y0, x1, y1)), 0xFFFFE082.toInt(), FillPattern.SOLID)!!

    // ================= 칠하기용 쪽 그림 크기 =================

    @Test fun smallPageIsDrawnAtMaxScale() {
        // A4 약 595x842 = 50만 → 450만 화소 한도면 배율 3.0 (한도 4 아래)
        val s = FillRules.bucketSize(595f, 842f, 4f, 4_500_000f)
        assertEquals(sqrt(4_500_000f / (595f * 842f)), s.k, 1e-5f)
        assertTrue(s.k < 4f)
        assertTrue(s.w.toLong() * s.h <= 4_500_000L)
    }

    @Test fun tinyPageIsCappedAtMaxK() {
        val s = FillRules.bucketSize(100f, 100f, 4f, 4_500_000f)
        assertEquals(4f, s.k, 0f)
        assertEquals(400, s.w); assertEquals(400, s.h)
    }

    @Test fun bucketSizeIsAtLeastOnePixel() {
        val s = FillRules.bucketSize(0.1f, 0.1f, 0.5f, 4_500_000f)
        assertEquals(1, s.w); assertEquals(1, s.h)
    }

    @Test fun bucketSizeNeverExceedsThePixelBudgetOnRandomPages() {
        val rnd = Random(3)
        repeat(5_000) {
            val pw = 50f + rnd.nextFloat() * 3000
            val ph = 50f + rnd.nextFloat() * 3000
            val s = FillRules.bucketSize(pw, ph, 4f, 4_500_000f)
            assertTrue(s.k <= 4f)
            // 정수로 내림하므로 한도를 넘지 않는다
            assertTrue("$pw x $ph", s.w.toLong() * s.h <= 4_500_000L)
        }
    }

    // ================= 영역을 찾을 때 그릴 획 =================

    @Test fun bucketSourcesDropFillsHighlightersAndLasers() {
        val pen = line(0f, 0f, 10f, 10f)
        val hi = Stroke(Tool.HIGHLIGHTER, 0xFFFFFF00.toInt(), 8f).also { it.add(0f, 0f, 1f); it.add(5f, 5f, 1f) }
        val laser = Stroke(Tool.LASER, 0xFFFF0000.toInt(), 3f).also { it.add(0f, 0f, 1f); it.add(5f, 5f, 1f) }
        val fill = filled(0f, 0f, 20f, 20f)
        val src = FillRules.bucketSources(listOf(pen, hi, laser, fill))
        assertEquals(listOf(pen), src)
    }

    // ================= 상자 =================

    @Test fun strokeBoundsCoverAllPoints() {
        val b = FillRules.strokeBounds(line(5f, 20f, -3f, 8f, 12f, 40f))
        assertEquals(listOf(-3f, 8f, 12f, 40f), b.toList())
    }

    @Test fun sameBoundsToleranceIsStrictlyBelowThree() {
        val a = floatArrayOf(10f, 10f, 50f, 50f)
        assertTrue(FillRules.sameBounds(a, floatArrayOf(12.9f, 7.1f, 52.9f, 47.1f)))
        assertFalse(FillRules.sameBounds(a, floatArrayOf(13f, 10f, 50f, 50f)))
        assertFalse(FillRules.sameBounds(a, floatArrayOf(10f, 7f, 50f, 50f)))
        assertFalse(FillRules.sameBounds(a, floatArrayOf(10f, 10f, 53f, 50f)))
        assertFalse(FillRules.sameBounds(a, floatArrayOf(10f, 10f, 50f, 47f)))
    }

    // ================= 채우기 획 만들기 =================

    @Test fun fillStrokeKeepsContourStartsMarkedAndOuterFirst() {
        val inner = square(40f, 40f, 60f, 60f)
        val outer = square(0f, 0f, 100f, 100f)
        // 안쪽을 먼저 줘도 바깥 윤곽이 앞에 온다
        val st = FillRules.fillStroke(listOf(inner, outer), 0xFF112233.toInt(), FillPattern.SOLID)!!
        assertEquals(8, st.count)
        assertEquals(0f, st.x(0), 0f); assertEquals(0f, st.y(0), 0f)    // 바깥 첫 점
        assertEquals(40f, st.x(4), 0f)                                  // 안쪽 시작
        assertNotNull(st.fill)
        assertEquals(FillPattern.SOLID, st.fill!!.pattern)
        assertEquals(Tool.FILL, st.tool)
    }

    @Test fun fillStrokeMarksEachContourStartForTheContourSplitter() {
        // 윤곽마다 첫 점의 필압 자리가 1, 나머지는 0이라야 forEachContour가 윤곽을 둘로 가른다
        val st = FillRules.fillStroke(listOf(square(0f, 0f, 100f, 100f), square(40f, 40f, 60f, 60f)), 0, FillPattern.SOLID)!!
        assertEquals(listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f), (0 until st.count).map { st.p(it) })
        val ranges = ArrayList<Pair<Int, Int>>()
        st.forEachContour { from, to -> ranges.add(from to to) }
        assertEquals(listOf(0 to 3, 4 to 7), ranges)
    }

    @Test fun fillStrokeSkipsDegenerateContoursAndRejectsTooFewPoints() {
        assertNull(FillRules.fillStroke(emptyList(), 0, FillPattern.SOLID))
        assertNull(FillRules.fillStroke(listOf(floatArrayOf(0f, 0f, 5f, 5f)), 0, FillPattern.SOLID))   // 점 2개
        val st = FillRules.fillStroke(listOf(floatArrayOf(0f, 0f, 5f, 5f), square(0f, 0f, 10f, 10f)), 0, FillPattern.SOLID)!!
        assertEquals(4, st.count)                                        // 점 2개짜리 윤곽은 버린다
    }

    // ================= 같은 영역 다시 칠하기 =================

    @Test fun repaintReplacesTheFillWithTheSameOutline() {
        val old = filled(10f, 10f, 100f, 100f)
        val list = listOf(old)
        // 같은 영역 (상자가 2pt 안으로 같음) 안을 누르면 바꿀 대상
        val nb = floatArrayOf(11f, 9f, 101f, 99f)
        assertSame(old, FillRules.sameRegionFill(list, 50f, 50f, nb))
    }

    @Test fun repaintStacksANewFillWhenTheRegionDiffers() {
        val old = filled(10f, 10f, 100f, 100f)
        // 상자가 다르면 (3pt 이상) 새로 쌓는다
        assertNull(FillRules.sameRegionFill(listOf(old), 50f, 50f, floatArrayOf(10f, 10f, 100f, 104f)))
        // 같은 상자라도 누른 자리가 채우기 밖이면 대상이 아니다
        assertNull(FillRules.sameRegionFill(listOf(old), 150f, 50f, floatArrayOf(10f, 10f, 100f, 100f)))
        // 채우기가 아닌 획은 대상이 아니다
        assertNull(FillRules.sameRegionFill(listOf(line(10f, 10f, 100f, 10f, 100f, 100f)), 50f, 50f, floatArrayOf(10f, 10f, 100f, 100f)))
    }

    @Test fun repaintPicksTheTopmostMatchingFill() {
        val below = filled(10f, 10f, 100f, 100f)
        val above = filled(10f, 10f, 100f, 100f)
        assertSame(above, FillRules.sameRegionFill(listOf(below, above), 50f, 50f, floatArrayOf(10f, 10f, 100f, 100f)))
    }

    // ================= 너무 작은 자유 영역 =================

    @Test fun freeFillSmallerThanTwelveDpIsDropped() {
        val d = 2f
        // 긴 쪽 × 배율이 24px(12dp) 미만이면 버린다
        assertTrue(FillRules.isTooSmall(floatArrayOf(0f, 0f, 11.9f, 5f), 2f, d))
        assertFalse(FillRules.isTooSmall(floatArrayOf(0f, 0f, 12f, 5f), 2f, d))
        // 가로·세로 중 긴 쪽 기준
        assertFalse(FillRules.isTooSmall(floatArrayOf(0f, 0f, 3f, 20f), 2f, d))
        // 확대하면 같은 크기도 통과
        assertFalse(FillRules.isTooSmall(floatArrayOf(0f, 0f, 6f, 6f), 4f, d))
    }
}
