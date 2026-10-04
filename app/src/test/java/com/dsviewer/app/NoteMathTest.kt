package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** 포스트잇·오답 배지 끌기 한계와 색 고르기 칸 자리 */
class NoteMathTest {

    private val pw = 600f
    private val ph = 800f
    private val margin = 160f
    private val icon = 28f

    // ================= 오답 배지 =================

    @Test fun badgeMayMoveWithinPageAndRightMargin() {
        // 배지 상자 (580, 100)~(620, 130): 왼쪽으로 580까지, 오른쪽으로 (600+160) - 620 = 140까지
        val l = NoteMath.badgeLimits(580f, 100f, 620f, 130f, pw, ph, margin)
        assertEquals(listOf(-580f, 140f, -100f, 670f), l.toList())
    }

    @Test fun badgeAlreadyOutsideIsOnlyPreventedFromGoingFurther() {
        // 오른쪽 한계(760)를 넘어 있는 배지: 더 나가는 쪽(+)은 0, 돌아오는 쪽(-)은 자유
        val l = NoteMath.badgeLimits(780f, 100f, 800f, 130f, pw, ph, margin)
        assertEquals(0f, l[1], 0f)
        assertEquals(-780f, l[0], 0f)
        // 위쪽으로 나가 있어도(top < 0) 위로 더 가는 것만 막는다
        val u = NoteMath.badgeLimits(100f, -20f, 140f, 10f, pw, ph, margin)
        assertEquals(0f, u[2], 0f)
    }

    // ================= 포스트잇 =================

    @Test fun collapsedNoteOnlyNeedsItsIconInsideThePage() {
        // 접힌 메모 (점 0) = (100, 200), 펼친 자리 (점 1) = (300, 250)
        val l = NoteMath.noteLimits(100f, 200f, 300f, 250f, collapsed = true, noteH = 120f, pw, ph, icon, maxLeft = 400f)
        assertEquals(-100f, l[0], 0f)                 // 점 0·1 중 더 왼쪽 (100) 만큼
        assertEquals(600f - 28f - 100f, l[1], 0f)     // 접힌 네모가 쪽 오른쪽 안
        assertEquals(-200f, l[2], 0f)
        assertEquals(800f - 28f - 200f, l[3], 0f)
    }

    @Test fun expandedNoteAlsoKeepsItsBodyInside() {
        val l = NoteMath.noteLimits(100f, 200f, 300f, 250f, collapsed = false, noteH = 120f, pw, ph, icon, maxLeft = 400f)
        // 오른쪽: 접힌 네모 한계 472와 펼친 몸통 한계(400-300=100) 중 작은 쪽
        assertEquals(100f, l[1], 0f)
        // 아래: 접힌 한계 572와 몸통(800-120-250=430) 중 작은 쪽
        assertEquals(430f, l[3], 0f)
    }

    @Test fun noteLeftLimitUsesTheLeftmostOfCollapsedAndExpandedSpots() {
        val l = NoteMath.noteLimits(300f, 200f, 50f, 250f, collapsed = false, noteH = 120f, pw, ph, icon, maxLeft = 400f)
        assertEquals(-50f, l[0], 0f)
    }

    @Test fun noteBeyondItsLimitsCanStillComeBack() {
        // 몸통이 이미 쪽 아래로 나가 있는 옛 문서: 아래로 더 가는 건 0, 올라오는 건 자유
        val l = NoteMath.noteLimits(100f, 700f, 300f, 790f, collapsed = false, noteH = 120f, pw, ph, icon, maxLeft = 400f)
        assertEquals(0f, l[3], 0f)
        assertTrue(l[2] < 0f)
    }

    // ================= 끈 거리 =================

    @Test fun dragDistanceIsConvertedToPageUnitsAndClamped() {
        val l = floatArrayOf(-50f, 80f, -30f, 90f)
        // 화면 100px, 배율 2 → 쪽 50
        val d = NoteMath.clampDelta(l, 100f, -40f, 2f)
        assertEquals(50f, d[0], 1e-4f); assertEquals(-20f, d[1], 1e-4f)
        // 한계 밖은 한계로
        val e = NoteMath.clampDelta(l, 1000f, -1000f, 2f)
        assertEquals(80f, e[0], 0f); assertEquals(-30f, e[1], 0f)
    }

    @Test fun clampDeltaNeverLeavesTheLimitsOnRandomDrags() {
        val rnd = Random(6)
        repeat(10_000) {
            val l = NoteMath.badgeLimits(
                rnd.nextFloat() * 700, rnd.nextFloat() * 900, 0f, 0f, pw, ph, margin,
            ).also { /* 오른쪽·아래는 아래에서 다시 */ }
            val b = floatArrayOf(rnd.nextFloat() * 700 - 50, rnd.nextFloat() * 900 - 50)
            val lim = NoteMath.badgeLimits(b[0], b[1], b[0] + 40, b[1] + 30, pw, ph, margin)
            val d = NoteMath.clampDelta(lim, (rnd.nextFloat() - 0.5f) * 4000, (rnd.nextFloat() - 0.5f) * 4000, 0.5f + rnd.nextFloat() * 3)
            assertTrue(d[0] in lim[0]..lim[1]); assertTrue(d[1] in lim[2]..lim[3])
            assertTrue(l.size == 4)
        }
    }

    // ================= 색 고르기 칸 =================

    @Test fun swatchesFillOneRowRightBelowTheHeader() {
        // 메모 상자 왼쪽 100, 위 200, 폭 150, 띠 24, 5칸 → 칸 30x30, 위는 224
        val a = NoteMath.swatchRect(100f, 200f, 150f, 24f, 0, 5)
        assertEquals(listOf(100f, 224f, 130f, 254f), a.toList())
        val c = NoteMath.swatchRect(100f, 200f, 150f, 24f, 4, 5)
        assertEquals(listOf(220f, 224f, 250f, 254f), c.toList())
    }

    @Test fun swatchesTileTheWholeWidthWithoutGaps() {
        val n = 6
        for (k in 0 until n - 1) {
            val a = NoteMath.swatchRect(10f, 20f, 180f, 24f, k, n)
            val b = NoteMath.swatchRect(10f, 20f, 180f, 24f, k + 1, n)
            assertEquals(a[2], b[0], 1e-4f)
        }
        assertEquals(190f, NoteMath.swatchRect(10f, 20f, 180f, 24f, n - 1, n)[2], 1e-3f)
    }

    // ================= 옛 계산과 대조 =================

    /** 옛 DocumentView.computeNoteDelta의 한계 계산을 그대로 옮긴 기준 */
    private fun referenceLimits(
        b: FloatArray?, x0: Float, y0: Float, x1: Float, y1: Float, collapsed: Boolean, noteH: Float, maxLeft: Float,
    ): FloatArray {
        var minDx: Float; var maxDx: Float; var minDy: Float; var maxDy: Float
        if (b != null) {
            minDx = -b[0]; maxDx = pw + margin - b[2]; minDy = -b[1]; maxDy = ph - b[3]
        } else {
            minDx = -min(x0, x1)
            minDy = -min(y0, y1)
            maxDx = min(pw - icon - x0, if (collapsed) Float.MAX_VALUE else maxLeft - x1)
            maxDy = min(ph - icon - y0, if (collapsed) Float.MAX_VALUE else ph - noteH - y1)
        }
        maxDx = max(maxDx, 0f); minDx = min(minDx, 0f)
        maxDy = max(maxDy, 0f); minDy = min(minDy, 0f)
        return floatArrayOf(minDx, maxDx, minDy, maxDy)
    }

    @Test fun limitsMatchTheOldCodeOnRandomItems() {
        val rnd = Random(31)
        repeat(20_000) {
            if (rnd.nextBoolean()) {
                val l = rnd.nextFloat() * 800 - 100; val t = rnd.nextFloat() * 900 - 50
                val r = l + 10 + rnd.nextFloat() * 60; val bt = t + 10 + rnd.nextFloat() * 40
                val ref = referenceLimits(floatArrayOf(l, t, r, bt), 0f, 0f, 0f, 0f, false, 0f, 0f)
                assertEquals(ref.toList(), NoteMath.badgeLimits(l, t, r, bt, pw, ph, margin).toList())
            } else {
                val x0 = rnd.nextFloat() * 700 - 50; val y0 = rnd.nextFloat() * 900 - 50
                val x1 = rnd.nextFloat() * 700 - 50; val y1 = rnd.nextFloat() * 900 - 50
                val collapsed = rnd.nextBoolean(); val h = 40 + rnd.nextFloat() * 300; val ml = rnd.nextFloat() * 700
                val ref = referenceLimits(null, x0, y0, x1, y1, collapsed, h, ml)
                assertEquals(ref.toList(), NoteMath.noteLimits(x0, y0, x1, y1, collapsed, h, pw, ph, icon, ml).toList())
            }
        }
    }
}
