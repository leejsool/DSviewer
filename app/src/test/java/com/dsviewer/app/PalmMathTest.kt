package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.max
import kotlin.random.Random

/** 손바닥 지우기의 판단과 계산 */
class PalmMathTest {

    private val xdpi = 254f      // 1mm = 10px

    // ================= 크기 =================

    @Test fun millimetresConvertThroughDpi() {
        assertEquals(90f, PalmMath.mm(254f, 9f), 1e-4f)
        assertEquals(30f, PalmMath.mm(254f, 3f), 1e-4f)
    }

    @Test fun medianNeedsThreeSamplesOtherwiseFallback() {
        assertEquals(77f, PalmMath.medianSize(emptyList(), 77f), 0f)
        assertEquals(77f, PalmMath.medianSize(listOf(50f, 60f), 77f), 0f)
        assertEquals(60f, PalmMath.medianSize(listOf(80f, 50f, 60f), 77f), 0f)
        // 짝수 개면 위쪽 가운데 (size/2 번째)
        assertEquals(70f, PalmMath.medianSize(listOf(50f, 60f, 70f, 80f), 77f), 0f)
    }

    @Test fun medianIgnoresOneHugeOutlier() {
        assertEquals(60f, PalmMath.medianSize(listOf(55f, 60f, 65f, 900f, 58f), 77f), 0f)
    }

    @Test fun palmLimitIsTwoAndAHalfFingersButAtLeastThreeMillimetres() {
        assertEquals(225f, PalmMath.palmLimit(90f, xdpi), 1e-3f)
        // 손가락 크기가 아주 작게 익혀져도 3mm(30px) 밑으로는 안 내려간다
        assertEquals(30f, PalmMath.palmLimit(4f, xdpi), 1e-3f)
    }

    // ================= 손바닥 가르기 =================

    @Test fun onlyFingersWideEnoughCountAsPalm() {
        val finger = booleanArrayOf(true, true)
        assertTrue(PalmMath.anyPalm(finger, floatArrayOf(80f, 230f), 2, 225f))
        assertFalse(PalmMath.anyPalm(finger, floatArrayOf(80f, 200f), 2, 225f))
        // 정확히 한도면 손바닥
        assertTrue(PalmMath.anyPalm(finger, floatArrayOf(225f), 1, 225f))
    }

    @Test fun aWideNonFingerIsNotPalm() {
        // 펜(스타일러스) 같은 손가락이 아닌 입력은 넓어 보여도 손바닥이 아니다
        assertFalse(PalmMath.anyPalm(booleanArrayOf(false), floatArrayOf(500f), 1, 225f))
        assertTrue(PalmMath.anyPalm(booleanArrayOf(false, true), floatArrayOf(10f, 500f), 2, 225f))
    }

    @Test fun anyPalmOnlyLooksAtTheFirstCountPoints() {
        assertFalse(PalmMath.anyPalm(booleanArrayOf(true, true), floatArrayOf(10f, 500f), 1, 225f))
    }

    @Test fun threeOrMoreFingersSoonAfterTheFirstTouchMeanPalm() {
        assertTrue(PalmMath.gathered(3, 0L))
        assertTrue(PalmMath.gathered(5, 249L))
        assertFalse(PalmMath.gathered(3, 250L))      // 경계: 250ms부터는 아님
        assertFalse(PalmMath.gathered(2, 100L))
    }

    // ================= 손가락 크기 익히기 =================

    @Test fun rememberKeepsOnlyTheLatestFifteen() {
        val h = ArrayDeque<Float>()
        for (i in 1..20) PalmMath.remember(h, i.toFloat())
        assertEquals(15, h.size)
        assertEquals(6f, h.first(), 0f)
        assertEquals(20f, h.last(), 0f)
    }

    // ================= 닿은 범위를 덮는 원 =================

    @Test fun singleTouchCircleIsCenteredOnItWithHalfItsSize() {
        val c = PalmMath.cover(floatArrayOf(100f), floatArrayOf(200f), floatArrayOf(60f), 1, -1)!!
        assertEquals(100f, c[0], 0f); assertEquals(200f, c[1], 0f); assertEquals(30f, c[2], 0f)
    }

    @Test fun circleCoversAllTouchesAroundTheirAverage() {
        // (0,0)과 (100,0): 가운데 (50,0), 각 50 떨어짐 + 닿은 크기 20의 절반 10 → 60
        val c = PalmMath.cover(floatArrayOf(0f, 100f), floatArrayOf(0f, 0f), floatArrayOf(20f, 20f), 2, -1)!!
        assertEquals(50f, c[0], 1e-4f); assertEquals(0f, c[1], 1e-4f); assertEquals(60f, c[2], 1e-4f)
    }

    @Test fun leavingFingerIsLeftOut() {
        val c = PalmMath.cover(floatArrayOf(0f, 100f), floatArrayOf(0f, 0f), floatArrayOf(20f, 20f), 2, 1)!!
        assertEquals(0f, c[0], 0f); assertEquals(10f, c[2], 0f)
        assertNull(PalmMath.cover(floatArrayOf(5f), floatArrayOf(5f), floatArrayOf(5f), 1, 0))
        assertNull(PalmMath.cover(FloatArray(0), FloatArray(0), FloatArray(0), 0, -1))
    }

    @Test fun radiusIsClamped() {
        assertEquals(48f, PalmMath.clampRadius(10f, 48f, 440f), 0f)
        assertEquals(440f, PalmMath.clampRadius(900f, 48f, 440f), 0f)
        assertEquals(100f, PalmMath.clampRadius(100f, 48f, 440f), 0f)
    }

    // ================= 보폭 =================

    @Test fun stepsAreAtLeastOneAndRoundDown() {
        assertEquals(1, PalmMath.steps(0f, 10f))
        assertEquals(1, PalmMath.steps(9.9f, 10f))
        assertEquals(1, PalmMath.steps(10f, 10f))
        assertEquals(2, PalmMath.steps(25f, 10f))
        assertEquals(100, PalmMath.steps(1000f, 10f))
    }

    @Test fun noGapInTheSweptPathBiggerThanTheStep() {
        // 어떤 길이든 칸 수 × 보폭이 길이를 다 덮거나(칸 간격 ≤ 보폭 이내 올림 없음) 마지막 칸이 보폭보다 짧다
        val rnd = Random(1)
        repeat(5_000) {
            val d = rnd.nextFloat() * 2000
            val step = 1f + rnd.nextFloat() * 50
            val n = PalmMath.steps(d, step)
            // 칸 하나의 길이 d/n 은 보폭의 2배를 넘지 않는다 (내림하므로 1.0~2.0배 안)
            assertTrue("$d/$step", d / n <= step * 2f + 1e-3f)
        }
    }

    // ================= 옛 계산과 대조 =================

    @Test fun coverMatchesTheOldCodeOnRandomHands() {
        val rnd = Random(77)
        repeat(10_000) {
            val count = 1 + rnd.nextInt(5)
            val xs = FloatArray(count) { rnd.nextFloat() * 1500 }
            val ys = FloatArray(count) { rnd.nextFloat() * 2500 }
            val ms = FloatArray(count) { 20f + rnd.nextFloat() * 300 }
            val leaving = if (rnd.nextBoolean()) rnd.nextInt(count) else -1
            // 옛 DocumentView.palmMove 계산
            var n = 0; var cx = 0f; var cy = 0f
            for (i in 0 until count) { if (i == leaving) continue; cx += xs[i]; cy += ys[i]; n++ }
            val got = PalmMath.cover(xs, ys, ms, count, leaving)
            if (n == 0) { assertNull(got); return@repeat }
            cx /= n; cy /= n
            var r = 0f
            for (i in 0 until count) { if (i == leaving) continue; r = max(r, hypot(xs[i] - cx, ys[i] - cy) + ms[i] / 2f) }
            assertEquals(cx, got!![0], 0f); assertEquals(cy, got[1], 0f); assertEquals(r, got[2], 0f)
        }
    }
}
