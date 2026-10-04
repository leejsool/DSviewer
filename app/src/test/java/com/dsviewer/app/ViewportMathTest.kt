package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** 문서를 보는 창(스크롤·확대)의 계산 */
class ViewportMathTest {

    // ================= 스크롤 한계 =================

    @Test fun contentHeightAddsFooterAndBothInsets() {
        assertEquals(2000f * 1.5f + 100f + 60f + 40f, ViewportMath.contentHeight(2000f, 1.5f, 100f, 60f, 40f), 1e-3f)
    }

    @Test fun narrowDocumentIsCenteredHorizontally() {
        assertEquals(-100f, ViewportMath.clampX(999f, 800f, 1000f), 0f)      // (1000-800)/2 만큼 왼쪽 여백
        assertEquals(0f, ViewportMath.clampX(0f, 1000f, 1000f) + 0f, 0f)    // 정확히 같으면 -0 = 0
    }

    @Test fun wideDocumentIsClampedInsideHorizontally() {
        assertEquals(0f, ViewportMath.clampX(-50f, 1500f, 1000f), 0f)
        assertEquals(500f, ViewportMath.clampX(900f, 1500f, 1000f), 0f)
        assertEquals(200f, ViewportMath.clampX(200f, 1500f, 1000f), 0f)
    }

    @Test fun shortDocumentIsCenteredVerticallyBelowTheTopInset() {
        // 문서 600, 화면 1000, 위 줄 50: -(400)/2 - 50 = -250
        assertEquals(-250f, ViewportMath.clampY(77f, 600f, 1000f, 50f), 0f)
    }

    @Test fun tallDocumentAllowsScrollingUpByTheTopInsetAndStopsAtTheEnd() {
        assertEquals(-50f, ViewportMath.clampY(-999f, 3000f, 1000f, 50f), 0f)
        assertEquals(3000f - 1000f - 50f, ViewportMath.clampY(99999f, 3000f, 1000f, 50f), 0f)
        assertEquals(700f, ViewportMath.clampY(700f, 3000f, 1000f, 50f), 0f)
    }

    // ================= 확대 중심 =================

    @Test fun zoomKeepsTheDocumentPointUnderTheFocus() {
        // 배율 1 → 2, 초점 (300), 스크롤 100: 문서 점 400 이 확대 뒤에도 화면 300에
        val off = ViewportMath.zoomOffset(100f, 300f, 300f, 1f, 2f)
        assertEquals(400f * 2f - 300f, off, 1e-3f)
        assertEquals(300f, 400f * 2f - off, 1e-3f)
    }

    @Test fun zoomFollowsAMovingFocus() {
        // 초점이 300 → 340으로 옮겨 가며 확대: 같은 문서 점이 새 초점 아래에
        val off = ViewportMath.zoomOffset(100f, 300f, 340f, 1f, 2f)
        assertEquals(340f, 400f * 2f - off, 1e-3f)
    }

    @Test fun animatedZoomKeepsThePointFixedEveryStep() {
        val doc = ViewportMath.docAt(120f, 250f, 1.25f)
        for (s in listOf(1.25f, 1.6f, 2.0f, 3.1f)) {
            assertEquals(250f, doc * s - ViewportMath.offsetKeeping(doc, s, 250f), 1e-2f)
        }
    }

    @Test fun doubleTapTogglesBetweenTwoAndAHalfAndOne() {
        assertEquals(2.5f, ViewportMath.doubleTapTarget(1f), 0f)
        assertEquals(2.5f, ViewportMath.doubleTapTarget(1.49f), 0f)
        assertEquals(1f, ViewportMath.doubleTapTarget(1.5f), 0f)     // 경계: 1.5부터는 원래 크기로
        assertEquals(1f, ViewportMath.doubleTapTarget(4f), 0f)
    }

    // ================= 화면 크기가 바뀔 때 =================

    @Test fun centerSurvivesAResizeThroughTheDocumentPosition() {
        val doc = ViewportMath.centerDoc(800f, 1000f, 2f)          // (800+500)/2 = 650
        assertEquals(650f, doc, 1e-4f)
        // 화면이 높이 600이 되어도 그 문서 점이 가운데
        val off = ViewportMath.offsetForCenter(doc, 2f, 600f)
        assertEquals(650f, ViewportMath.centerDoc(off, 600f, 2f), 1e-3f)
    }

    // ================= 플링 =================

    @Test fun flingBoundsOfALargeDocumentAreItsScrollRange() {
        val b = ViewportMath.flingBounds(100f, 400f, 1500f, 3000f, 1000f, 1000f, 50f)
        assertEquals(listOf(0, 500, -50, 1950), b.toList())
    }

    @Test fun flingIsPinnedOnAnAxisWhereTheDocumentFitsTheScreen() {
        // 폭이 화면보다 좁으면 가로는 지금 위치에 고정
        val b = ViewportMath.flingBounds(-100f, 400f, 800f, 3000f, 1000f, 1000f, 50f)
        assertEquals(-100, b[0]); assertEquals(-100, b[1])
        // 높이가 화면보다 짧으면 세로도
        val c = ViewportMath.flingBounds(0f, -250f, 1500f, 600f, 1000f, 1000f, 50f)
        assertEquals(-250, c[2]); assertEquals(-250, c[3])
    }

    // ================= 쪽으로 옮기기 =================

    @Test fun pageTopSitsJustBelowTheTopInsetMinusHalfTheGap() {
        // 쪽 위 1000, 간격 10, 배율 2, 위 줄 40: (1000-5)*2-40 = 1950
        assertEquals(1950f, ViewportMath.offsetForPageTop(1000f, 10f, 2f, 40f), 1e-3f)
    }

    @Test fun pageYIsClampedToThePage() {
        assertEquals((1000f + 300f) * 2f - 40f, ViewportMath.offsetForPageY(1000f, 300f, 800f, 10f, 2f, 40f), 1e-3f)
        assertEquals((1000f - 5f) * 2f - 40f, ViewportMath.offsetForPageY(1000f, -999f, 800f, 10f, 2f, 40f), 1e-3f)   // 위 간격의 절반까지
        assertEquals((1000f + 800f) * 2f - 40f, ViewportMath.offsetForPageY(1000f, 5000f, 800f, 10f, 2f, 40f), 1e-3f)
    }

    @Test fun pointGoesToTheUpperThirdAndOnlyScrollsSidewaysIfOffScreen() {
        assertEquals((1000f + 300f) * 2f - 1000f / 3f, ViewportMath.offsetForPointY(1000f, 300f, 2f, 1000f), 1e-3f)
        // 가로: 이미 보이면 그대로
        assertEquals(120f, ViewportMath.offsetForPointX(120f, 0f, 300f, 2f, 1000f), 0f)      // 화면 x = 480
        // 화면 밖이면 가운데로: 점 x=900, 배율 2 → 문서 1800, 화면 가운데에 오는 스크롤 = 1800 - 500
        assertEquals(1300f, ViewportMath.offsetForPointX(0f, 0f, 900f, 2f, 1000f), 0f)
        // 화면 왼쪽 밖
        assertEquals(100f * 2f - 500f, ViewportMath.offsetForPointX(900f, 0f, 100f, 2f, 1000f), 0f)
    }

    // ================= 선명하게 다시 그릴 영역 =================

    @Test fun visibleRegionIsTheOverlapInPageUnits() {
        // 쪽이 화면 (0,0)-(1000,1000) 중 (-200,300)~(800,1400) 에 놓임, 배율 2 → 겹친 부분 (0,300)-(800,1000), 쪽 단위로 (100,0)-(500,350)
        val v = ViewportMath.visibleRegion(-200f, 300f, 800f, 1400f, 1000f, 1000f, 2f)!!
        assertEquals(listOf(100f, 0f, 500f, 350f), v.toList())
    }

    @Test fun regionBarelyVisibleIsSkipped() {
        assertNull(ViewportMath.visibleRegion(999f, 0f, 1500f, 1000f, 1000f, 1000f, 1f))        // 폭 1px
        assertNull(ViewportMath.visibleRegion(0f, 1000f, 500f, 2000f, 1000f, 1000f, 1f))        // 화면 아래 밖
        assertTrue(ViewportMath.visibleRegion(998f, 0f, 1500f, 1000f, 1000f, 1000f, 1f) != null)  // 폭 2px는 그림
    }

    // ================= 옛 계산과 대조 =================

    @Test fun clampMatchesTheOldCodeOnRandomViews() {
        val rnd = Random(51)
        repeat(20_000) {
            val cw = 200f + rnd.nextFloat() * 3000; val ch = 200f + rnd.nextFloat() * 6000
            val vw = 300f + rnd.nextInt(1500); val vh = 300f + rnd.nextInt(2500)
            val top = rnd.nextFloat() * 100
            val ox = rnd.nextFloat() * 4000 - 500; val oy = rnd.nextFloat() * 8000 - 500
            val rx = if (cw <= vw) -(vw - cw) / 2f else ox.coerceIn(0f, cw - vw)
            val ry = if (ch <= vh) -(vh - ch) / 2f - top else oy.coerceIn(-top, ch - vh - top)
            assertEquals(rx, ViewportMath.clampX(ox, cw, vw), 0f)
            assertEquals(ry, ViewportMath.clampY(oy, ch, vh, top), 0f)
            // 플링 범위도 같은 규칙으로 옛 계산과 대조
            val maxX = max(0f, cw - vw).toInt(); val maxY = (max(0f, ch - vh) - top).toInt()
            val minX = if (cw <= vw) ox.toInt() else 0; val minY = if (ch <= vh) oy.toInt() else -top.toInt()
            val b = ViewportMath.flingBounds(ox, oy, cw, ch, vw, vh, top)
            assertEquals(minX, b[0]); assertEquals(max(minX, if (cw <= vw) minX else maxX), b[1])
            assertEquals(minY, b[2]); assertEquals(max(minY, if (ch <= vh) minY else maxY), b[3])
        }
    }

    @Test fun visibleRegionMatchesTheOldCodeOnRandomPages() {
        val rnd = Random(52)
        repeat(20_000) {
            val l = rnd.nextFloat() * 1600 - 800; val t = rnd.nextFloat() * 3000 - 1500
            val r = l + 100 + rnd.nextFloat() * 1500; val b = t + 100 + rnd.nextFloat() * 2000
            val vw = 600f + rnd.nextInt(1000); val vh = 800f + rnd.nextInt(1800); val s = 0.5f + rnd.nextFloat() * 4
            val vl = max(l, 0f); val vt = max(t, 0f); val vr = min(r, vw); val vb = min(b, vh)
            val got = ViewportMath.visibleRegion(l, t, r, b, vw, vh, s)
            if (vr - vl <= 1 || vb - vt <= 1) { assertNull(got); return@repeat }
            assertEquals(listOf((vl - l) / s, (vt - t) / s, (vr - l) / s, (vb - t) / s), got!!.toList())
        }
    }
}
