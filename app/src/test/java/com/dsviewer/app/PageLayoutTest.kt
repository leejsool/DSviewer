package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 쪽 배치와 '지금 보이는 쪽·눌린 곳' 계산 (문서 화면에서 떼어 낸 순수 계산) */
class PageLayoutTest {

    private fun layout(
        widths: FloatArray, heights: FloatArray, rightMargin: Float = 0f, twoPage: Boolean = false,
        viewW: Int = 800, viewH: Int = 1200, gap: Float = 10f,
    ) = PageLayout(gap).also { it.layout(widths, heights, rightMargin, twoPage, viewW, viewH) }

    private fun fa(vararg v: Float) = v

    // ================= 배치 =================

    @Test fun singleColumnIsCenteredAndStacked() {
        val l = layout(fa(600f, 300f), fa(800f, 400f))
        assertEquals(620f, l.docW, 1e-3f)           // 가장 넓은 쪽 + 양쪽 여백
        assertEquals(listOf(10f, 820f), l.tops.toList())
        assertEquals(listOf(10f, 160f), l.lefts.toList())   // 좁은 쪽은 가운데
        assertEquals(1230f, l.docH, 1e-3f)
        assertFalse(l.spread)
    }

    @Test fun twoPageSpreadPutsPagesSideBySide() {
        val l = layout(fa(100f, 200f, 300f), fa(150f, 120f, 80f), twoPage = true, viewW = 1600, viewH = 1000)
        assertTrue(l.spread)
        assertEquals(330f, l.docW, 1e-3f)           // 첫 줄 100+10+200 = 310, + 여백 20
        assertEquals(listOf(10f, 10f, 170f), l.tops.toList())
        assertEquals(listOf(10f, 120f, 15f), l.lefts.toList())
        assertEquals(260f, l.docH, 1e-3f)           // 둘째 줄 80
    }

    @Test fun spreadNeedsLandscapeViewAndMoreThanOnePage() {
        val w = fa(100f, 100f); val h = fa(100f, 100f)
        assertFalse(layout(w, h, twoPage = true, viewW = 600, viewH = 1000).spread)   // 세로 화면
        assertFalse(layout(w, h, twoPage = false, viewW = 1600, viewH = 1000).spread) // 끄면 한 쪽씩
        assertFalse(layout(fa(100f), fa(100f), twoPage = true, viewW = 1600, viewH = 1000).spread)
        assertTrue(layout(w, h, twoPage = true, viewW = 1600, viewH = 1000).spread)
    }

    @Test fun rightMarginWidensEveryPage() {
        val l = layout(fa(100f, 100f), fa(100f, 100f), rightMargin = 20f)
        assertEquals(120f + 20f, l.docW, 1e-3f)
        assertEquals(10f, l.lefts[0], 1e-3f)
    }

    @Test fun emptyDocumentHasADefaultWidth() {
        val l = layout(fa(), fa())
        assertEquals(620f, l.docW, 1e-3f)
        assertEquals(10f, l.docH, 1e-3f)
        assertEquals(-1, l.rowFirstPage(0f, 1f, 800f))
        assertNull(l.topSpot(0f, 0f, 1f))
        assertTrue(l.visibleRange(0f, 1f, 800f).isEmpty())
    }

    @Test fun spreadWithRightMarginSpacesPagesByMarginAndGap() {
        // 쪽 둘, 바깥 여백 20: 둘째 쪽은 첫 쪽 왼쪽 + 너비 + 바깥 여백 + 쪽 사이 여백
        val l = layout(fa(300f, 300f), fa(400f, 400f), rightMargin = 20f, twoPage = true, viewW = 1600, viewH = 800)
        assertEquals(l.lefts[0] + 300f + 20f + 10f, l.lefts[1], 1e-3f)
        assertEquals(2 * 320f + 10f + 20f, l.docW, 1e-3f)
    }

    @Test fun hitPageOnATieGoesToTheEarlierPage() {
        // 두 쪽 사이 가로 여백(바깥 여백 없이 10pt)의 한가운데: 두 쪽에서 같은 거리면 앞 쪽
        val l = layout(fa(300f, 300f), fa(400f, 400f), twoPage = true, viewW = 1600, viewH = 800)
        val mid = l.lefts[0] + 300f + 5f
        assertEquals(0, l.hitPage(0f, 0f, 1f, mid, 100f)!!.first)
    }

    // ================= 보이는 쪽 =================

    private val three = layout(fa(600f, 600f, 600f), fa(800f, 800f, 800f))   // tops 10, 820, 1630

    @Test fun visibleRangeFollowsScrolling() {
        assertEquals(0..0, three.visibleRange(0f, 1f, 700f))
        assertEquals(0..1, three.visibleRange(0f, 1f, 1000f))
        assertEquals(1..1, three.visibleRange(850f, 1f, 700f))
        assertEquals(1..2, three.visibleRange(1500f, 1f, 800f))
        // 확대하면 같은 화면에 더 적은 문서가 보인다
        assertEquals(0..0, three.visibleRange(0f, 2f, 1000f))
    }

    @Test fun currentPageIsTheOneAtTheScreenCenter() {
        assertEquals(0, three.currentPage(0f, 0f, 1f, 600f, 800f))
        assertEquals(1, three.currentPage(0f, 820f, 1f, 600f, 800f))
        assertEquals(2, three.currentPage(0f, 5000f, 1f, 600f, 800f))   // 끝을 지나면 마지막 쪽
    }

    @Test fun currentPageInASpreadFollowsHorizontalCenter() {
        val l = layout(fa(300f, 300f), fa(400f, 400f), twoPage = true, viewW = 1600, viewH = 800)   // lefts 10, 320
        assertEquals(0, l.currentPage(0f, 0f, 1f, 600f, 800f))      // 가운데 x 300 < 320
        assertEquals(1, l.currentPage(100f, 0f, 1f, 600f, 800f))    // 가운데 x 400 ≥ 320
    }

    @Test fun topSpotRemembersPageAndHeightInIt() {
        // 맨 위에서는 첫 쪽, 쪽 위 여백만큼 음수 (쪽 위 10pt 전)
        val top = three.topSpot(0f, 0f, 1f)!!
        assertEquals(0, top.first)
        assertEquals(-10f, top.second, 1e-3f)
        val (page, y) = three.topSpot(1000f, 0f, 1f)!!
        assertEquals(1, page)
        assertEquals(1000f - 820f, y, 1e-3f)
        // 위에 겹쳐 뜬 줄(topInset)만큼 더 위를 맨 위로 본다
        val (p2, y2) = three.topSpot(1000f, 100f, 1f)!!
        assertEquals(1, p2)
        assertEquals(1100f - 820f, y2, 1e-3f)
    }

    // ================= 눌린 곳 =================

    @Test fun hitPageMapsScreenToPageCoordinates() {
        // scale 1.5, 스크롤 offX 30, offY 400: 쪽 1의 (100, 200)은 화면 ((lefts+100)*1.5-30, (tops+200)*1.5-400)
        val sx = (three.lefts[1] + 100f) * 1.5f - 30f
        val sy = (three.tops[1] + 200f) * 1.5f - 400f
        val hit = three.hitPage(30f, 400f, 1.5f, sx, sy)!!
        assertEquals(1, hit.first)
        assertEquals(100f, hit.second, 1e-3f)
        assertEquals(200f, hit.third, 1e-3f)
    }

    @Test fun hitPageInTheGapGoesToTheNearerPage() {
        // 쪽 0 아래 끝은 810, 쪽 1 위는 820: 사이 여백(810~820)은 가까운 쪽
        val near0 = three.hitPage(0f, 0f, 1f, 300f, 812f)!!
        assertEquals(0, near0.first)
        val near1 = three.hitPage(0f, 0f, 1f, 300f, 818f)!!
        assertEquals(1, near1.first)
    }

    @Test fun hitPageOutsideTheDocumentIsNull() {
        assertNull(three.hitPage(0f, 0f, 1f, -50f, 100f))        // 왼쪽 바깥 (여백 gap보다 멀리)
        assertNull(three.hitPage(0f, 0f, 1f, 900f, 100f))        // 오른쪽 바깥
        assertNull(three.hitPage(0f, 0f, 1f, 300f, 5000f))       // 아래 바깥
        assertNull(layout(fa(), fa()).hitPage(0f, 0f, 1f, 100f, 100f))
    }

    @Test fun hitPageWideReachesTheRightMarginForBadges() {
        // 쪽 오른쪽 바깥 여백 20pt: 보통은 필기할 수 없는 곳이라 null, wide면 그 쪽
        val l = layout(fa(600f), fa(800f), rightMargin = 20f)
        val x = l.lefts[0] + 600f + 15f
        assertNull(l.hitPage(0f, 0f, 1f, x, 100f))
        val hit = l.hitPage(0f, 0f, 1f, x, 100f, wide = true)
        assertNotNull(hit)
        assertEquals(0, hit!!.first)
    }

    @Test fun hitPageInASpreadFindsTheRightPageOfTheRow() {
        val l = layout(fa(300f, 300f), fa(400f, 400f), twoPage = true, viewW = 1600, viewH = 800)
        assertEquals(0, l.hitPage(0f, 0f, 1f, 100f, 100f)!!.first)
        assertEquals(1, l.hitPage(0f, 0f, 1f, 500f, 100f)!!.first)
    }

    // ================= 무작위 성질 =================

    @Test fun randomLayoutsKeepPagesInsideAndNotOverlapping() {
        val rnd = Random(11)
        repeat(300) {
            val n = rnd.nextInt(1, 12)
            val w = FloatArray(n) { rnd.nextInt(100, 700).toFloat() }
            val h = FloatArray(n) { rnd.nextInt(100, 900).toFloat() }
            val margin = if (rnd.nextBoolean()) 0f else 24f
            val two = rnd.nextBoolean()
            val l = layout(w, h, margin, two, viewW = if (rnd.nextBoolean()) 1600 else 600, viewH = 900)
            for (i in 0 until n) {
                assertTrue("쪽이 문서 안에 있다", l.lefts[i] >= -1e-3f && l.lefts[i] + w[i] + margin <= l.docW + 1e-3f)
                assertTrue(l.tops[i] >= l.gap - 1e-3f && l.tops[i] + h[i] <= l.docH + 1e-3f)
            }
            // 같은 줄이 아닌 쪽끼리는 위아래로 겹치지 않는다
            for (i in 1 until n) if (l.tops[i] != l.tops[i - 1]) assertTrue(l.tops[i] >= l.tops[i - 1] + l.gap)
        }
    }

    @Test fun randomPressesOnPageCentersHitThatPage() {
        val rnd = Random(5)
        repeat(300) {
            val n = rnd.nextInt(1, 10)
            val w = FloatArray(n) { rnd.nextInt(150, 700).toFloat() }
            val h = FloatArray(n) { rnd.nextInt(150, 900).toFloat() }
            val l = layout(w, h, twoPage = rnd.nextBoolean(), viewW = 1600, viewH = 900)
            val scale = 0.5f + rnd.nextFloat() * 2f
            val offX = rnd.nextFloat() * 200f
            val offY = rnd.nextFloat() * 500f
            for (i in 0 until n) {
                val sx = (l.lefts[i] + w[i] / 2) * scale - offX
                val sy = (l.tops[i] + h[i] / 2) * scale - offY
                val hit = l.hitPage(offX, offY, scale, sx, sy)
                assertNotNull(hit)
                assertEquals(i, hit!!.first)
                assertEquals(w[i] / 2, hit.second, 0.01f)
                assertEquals(h[i] / 2, hit.third, 0.01f)
            }
        }
    }

    @Test fun randomViewsHaveTheCurrentPageAmongTheVisibleOnes() {
        val rnd = Random(9)
        repeat(300) {
            val n = rnd.nextInt(1, 10)
            val w = FloatArray(n) { rnd.nextInt(150, 700).toFloat() }
            val h = FloatArray(n) { rnd.nextInt(150, 900).toFloat() }
            val l = layout(w, h, twoPage = rnd.nextBoolean(), viewW = 1600, viewH = 900)
            val scale = 0.5f + rnd.nextFloat() * 2f
            val viewH = 600f + rnd.nextFloat() * 600f
            val offY = rnd.nextFloat() * (l.docH * scale)
            val range = l.visibleRange(offY, scale, viewH)
            val cur = l.currentPage(rnd.nextFloat() * 300f, offY, scale, 1600f, viewH)
            // (양쪽 보기에서 같은 줄의 낮은 쪽은 화면 가운데 줄에 걸쳐도 보이는 범위에서 빠질 수 있어 한 쪽씩일 때만)
            if (!l.spread && !range.isEmpty() && offY / scale <= l.docH) {
                assertTrue("현재 쪽 $cur 이 보이는 범위 $range 안", cur in range || cur == n - 1)
            }
        }
    }
}
