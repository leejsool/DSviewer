package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/** 글 상자·붙여넣기 위치 계산 */
class PlacementMathTest {

    // ================= 곧은 상자 =================

    @Test fun uprightBoxCornersGoClockwiseFromTopLeft() {
        assertEquals(listOf(10f, 20f, 110f, 20f, 110f, 70f, 10f, 70f), PlacementMath.upright(10f, 20f, 100f, 50f).toList())
    }

    // ================= 글 상자 다시 잡기 =================

    private fun box(x0: Float, y0: Float, w: Float, h: Float, deg: Float = 0f, kx: Float = 1f, ky: Float = 1f): FloatArray {
        // 글 크기 w×h를 배율 kx, ky로 늘리고 deg만큼 돌린 상자
        val r = Math.toRadians(deg.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val ux = c * w * kx; val uy = s * w * kx
        val vx = -s * h * ky; val vy = c * h * ky
        return floatArrayOf(x0, y0, x0 + ux, y0 + uy, x0 + ux + vx, y0 + uy + vy, x0 + vx, y0 + vy)
    }

    @Test fun unchangedTextKeepsTheSameBox() {
        val old = box(50f, 60f, 120f, 40f)
        val n = PlacementMath.retargetTextBox(old, 120f, 40f, 120f, 40f)
        for (i in 0 until 8) assertEquals("모서리 값 $i", old[i], n[i], 1e-3f)
    }

    @Test fun longerTextWidensTheBoxKeepingTheTopLeft() {
        val old = box(50f, 60f, 120f, 40f)
        val n = PlacementMath.retargetTextBox(old, 120f, 40f, 240f, 40f)
        assertEquals(50f, n[0], 1e-4f); assertEquals(60f, n[1], 1e-4f)
        assertEquals(240f, hypot(n[2] - n[0], n[3] - n[1]), 1e-3f)
        assertEquals(40f, hypot(n[6] - n[0], n[7] - n[1]), 1e-3f)
    }

    @Test fun scaledBoxKeepsItsScaleAndRotation() {
        // 1.5배(가로)·2배(세로)로 키우고 30° 돌려 둔 상자에 글이 두 배 길어져도 배율·기울기는 그대로
        val old = box(100f, 100f, 80f, 30f, deg = 30f, kx = 1.5f, ky = 2f)
        val n = PlacementMath.retargetTextBox(old, 80f, 30f, 160f, 45f)
        val want = box(100f, 100f, 160f, 45f, deg = 30f, kx = 1.5f, ky = 2f)
        for (i in 0 until 8) assertEquals("모서리 값 $i", want[i], n[i], 1e-2f)
    }

    @Test fun rotatedBoxStaysARectangle() {
        val old = box(0f, 0f, 100f, 50f, deg = 77f, kx = 0.8f, ky = 1.3f)
        val n = PlacementMath.retargetTextBox(old, 100f, 50f, 130f, 90f)
        // 마주 보는 변이 같고 이웃한 변이 직각
        assertEquals(n[2] - n[0], n[4] - n[6], 1e-2f); assertEquals(n[3] - n[1], n[5] - n[7], 1e-2f)
        val dot = (n[2] - n[0]) * (n[6] - n[0]) + (n[3] - n[1]) * (n[7] - n[1])
        assertEquals(0f, dot, 0.5f)
    }

    @Test fun degenerateOldBoxDoesNotDivideByZero() {
        // 옛 상자가 점으로 눌려 있어도 유한한 값 (길이 하한 0.01)
        val n = PlacementMath.retargetTextBox(floatArrayOf(5f, 5f, 5f, 5f, 5f, 5f, 5f, 5f), 100f, 50f, 100f, 50f)
        assertTrue(n.all { it.isFinite() })
    }

    /** 옛 DocumentView.replaceText의 계산을 그대로 옮긴 기준 */
    private fun referenceRetarget(old: FloatArray, oldW: Int, oldH: Int, newW: Int, newH: Int): FloatArray {
        val x0 = old[0]; val y0 = old[1]
        val ax = old[2] - x0; val ay = old[3] - y0
        val bx = old[6] - x0; val by = old[7] - y0
        val aLen = max(hypot(ax, ay), 0.01f)
        val bLen = max(hypot(bx, by), 0.01f)
        val kx = aLen / oldW
        val ky = bLen / oldH
        val w = newW * kx
        val h = newH * ky
        val ux = ax / aLen * w; val uy = ay / aLen * w
        val vx = bx / bLen * h; val vy = by / bLen * h
        return floatArrayOf(x0, y0, x0 + ux, y0 + uy, x0 + ux + vx, y0 + uy + vy, x0 + vx, y0 + vy)
    }

    @Test fun retargetMatchesTheOldCodeOnRandomBoxes() {
        val rnd = Random(12)
        repeat(20_000) {
            val ow = 20 + rnd.nextInt(400); val oh = 10 + rnd.nextInt(200)
            val nw = 20 + rnd.nextInt(600); val nh = 10 + rnd.nextInt(300)
            val old = box(
                rnd.nextFloat() * 500, rnd.nextFloat() * 700, ow.toFloat(), oh.toFloat(),
                deg = (rnd.nextFloat() - 0.5f) * 720f, kx = 0.2f + rnd.nextFloat() * 4, ky = 0.2f + rnd.nextFloat() * 4,
            )
            val ref = referenceRetarget(old, ow, oh, nw, nh)
            val got = PlacementMath.retargetTextBox(old, ow.toFloat(), oh.toFloat(), nw.toFloat(), nh.toFloat())
            for (i in 0 until 8) assertEquals("모서리 값 $i", ref[i], got[i], 0f)
        }
    }

    // ================= 붙여넣기 위치 =================

    @Test fun pasteGoesToTheMiddleOfTheScreen() {
        // 첫 쪽(왼쪽 0, 위 0) 600x800, 배율 1, 스크롤 없음, 화면 600x1000 → 화면 가운데 (300, 500)
        val p = PlacementMath.pasteOrigin(0f, 0f, 600f, 1000f, 1f, 0f, 0f, 600f, 800f, 100f, 60f)
        assertEquals(250f, p[0], 1e-4f); assertEquals(470f, p[1], 1e-4f)
    }

    @Test fun pasteFollowsScrollAndZoom() {
        // 배율 2: 화면 가운데는 문서 좌표로 절반. 아래로 1000 스크롤하면 (offY+viewH/2)/2 = 750
        val p = PlacementMath.pasteOrigin(200f, 1000f, 800f, 1000f, 2f, 0f, 500f, 600f, 800f, 100f, 60f)
        // cx = (200+400)/2 - 0 = 300, cy = 1000+500=1500/2=750 - 500 = 250
        assertEquals(250f, p[0], 1e-4f); assertEquals(220f, p[1], 1e-4f)
    }

    @Test fun pasteIsKeptInsideThePage() {
        // 화면 가운데가 쪽 오른쪽 아래 구석 밖이어도 쪽 안으로
        val p = PlacementMath.pasteOrigin(0f, 5000f, 600f, 1000f, 1f, 0f, 0f, 600f, 800f, 100f, 60f)
        assertEquals(250f, p[0], 1e-4f); assertEquals(740f, p[1], 1e-4f)         // 800 - 60
        val q = PlacementMath.pasteOrigin(5000f, 0f, 600f, 1000f, 1f, 0f, 0f, 600f, 800f, 100f, 60f)
        assertEquals(500f, q[0], 1e-4f)                                          // 600 - 100
        // 화면 가운데가 쪽 왼쪽 위 밖이어도
        val r = PlacementMath.pasteOrigin(-3000f, -3000f, 600f, 1000f, 1f, 0f, 0f, 600f, 800f, 100f, 60f)
        assertEquals(0f, r[0], 0f); assertEquals(0f, r[1], 0f)
    }

    @Test fun pasteOfAClipLargerThanThePageStartsAtTheCorner() {
        val p = PlacementMath.pasteOrigin(0f, 0f, 600f, 1000f, 1f, 0f, 0f, 300f, 400f, 500f, 600f)
        assertEquals(0f, p[0], 0f); assertEquals(0f, p[1], 0f)
    }

    /** 옛 DocumentView.pasteClipboard의 계산을 그대로 옮긴 기준 */
    private fun referencePaste(
        offX: Float, offY: Float, vw: Float, vh: Float, scale: Float,
        left: Float, top: Float, pw: Float, ph: Float, w: Float, h: Float,
    ): FloatArray {
        val centerDoc = (offY + vh / 2f) / scale
        val cx = (offX + vw / 2f) / scale - left
        val cy = (centerDoc - top).coerceIn(0f, ph)
        val x0 = (cx - w / 2).coerceIn(0f, max(0f, pw - w))
        val y0 = (cy - h / 2).coerceIn(0f, max(0f, ph - h))
        return floatArrayOf(x0, y0)
    }

    @Test fun pasteMatchesTheOldCodeOnRandomViews() {
        val rnd = Random(5)
        repeat(20_000) {
            val args = floatArrayOf(
                rnd.nextFloat() * 2000 - 500, rnd.nextFloat() * 8000 - 500, 300f + rnd.nextFloat() * 1500, 300f + rnd.nextFloat() * 2000,
                0.3f + rnd.nextFloat() * 4, rnd.nextFloat() * 100, rnd.nextFloat() * 4000, 200f + rnd.nextFloat() * 600,
                300f + rnd.nextFloat() * 700, 10f + rnd.nextFloat() * 800, 10f + rnd.nextFloat() * 900,
            )
            val ref = referencePaste(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10])
            val got = PlacementMath.pasteOrigin(args[0], args[1], args[2], args[3], args[4], args[5], args[6], args[7], args[8], args[9], args[10])
            assertEquals(ref[0], got[0], 0f); assertEquals(ref[1], got[1], 0f)
        }
    }

    // ================= 큰 그림 붙이기 (영역 스크린샷) =================

    @Test fun smallImageIsPastedAtItsOwnSize() {
        assertEquals(1f, PlacementMath.pasteFit(200f, 100f, 600f, 800f), 0f)
    }

    @Test fun wideImageShrinksToNinetyPercentOfPageWidth() {
        // 쪽 600 → 540. 1080 너비 그림은 절반
        assertEquals(0.5f, PlacementMath.pasteFit(1080f, 200f, 600f, 800f), 1e-6f)
    }

    @Test fun tallImageShrinksToNinetyPercentOfPageHeight() {
        // 쪽 높이 800 → 720. 1440 높이 그림은 절반
        assertEquals(0.5f, PlacementMath.pasteFit(100f, 1440f, 600f, 800f), 1e-6f)
    }

    @Test fun theTighterSideDecides() {
        val k = PlacementMath.pasteFit(1080f, 1440f * 4, 600f, 800f)
        assertEquals(720f / (1440f * 4), k, 1e-6f)
    }

    @Test fun imageExactlyAtTheLimitIsNotShrunk() {
        assertEquals(1f, PlacementMath.pasteFit(540f, 720f, 600f, 800f), 0f)
    }

    @Test fun emptyImageDoesNotDivideByZero() {
        assertEquals(1f, PlacementMath.pasteFit(0f, 0f, 600f, 800f), 0f)
    }

    @Test fun shrunkImageAlwaysFitsThePage() {
        val rnd = Random(11)
        repeat(5_000) {
            val w = 1f + rnd.nextFloat() * 3000; val h = 1f + rnd.nextFloat() * 3000
            val pw = 100f + rnd.nextFloat() * 900; val ph = 100f + rnd.nextFloat() * 1200
            val k = PlacementMath.pasteFit(w, h, pw, ph)
            assertTrue(k in 0f..1f)
            assertTrue(w * k <= pw * PlacementMath.FIT_FRACTION + 1e-2f)
            assertTrue(h * k <= ph * PlacementMath.FIT_FRACTION + 1e-2f)
        }
    }
}
