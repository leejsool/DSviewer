package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** 오답 영역을 네모로 끌어 고를 때의 계산 */
class PickMathTest {

    // ================= 화면 점 → 쪽 점 =================

    @Test fun screenPointMapsThroughScrollScaleAndPagePosition() {
        // 스크롤 (100, 400), 배율 2, 쪽 위치 (10, 300): 쪽 x = (250+100)/2 - 10 = 165, y = (900+400)/2 - 300 = 350
        val p = PickMath.clampToPage(250f, 900f, 100f, 400f, 2f, 10f, 300f, 600f, 800f)
        assertEquals(165f, p[0], 1e-4f); assertEquals(350f, p[1], 1e-4f)
    }

    @Test fun pointOutsideThePageSticksToItsEdge() {
        val above = PickMath.clampToPage(-500f, -500f, 0f, 0f, 1f, 0f, 0f, 600f, 800f)
        assertEquals(0f, above[0], 0f); assertEquals(0f, above[1], 0f)
        val below = PickMath.clampToPage(5000f, 5000f, 0f, 0f, 1f, 0f, 0f, 600f, 800f)
        assertEquals(600f, below[0], 0f); assertEquals(800f, below[1], 0f)
        // 한쪽 축만 밖이면 그 축만
        val side = PickMath.clampToPage(5000f, 300f, 0f, 0f, 1f, 0f, 0f, 600f, 800f)
        assertEquals(600f, side[0], 0f); assertEquals(300f, side[1], 0f)
    }

    // ================= 네모 =================

    @Test fun rectIsTheSameWhicheverDirectionYouDrag() {
        val expect = listOf(10f, 20f, 110f, 90f)
        assertEquals(expect, PickMath.rectOf(10f, 20f, 110f, 90f).toList())     // 오른쪽 아래로
        assertEquals(expect, PickMath.rectOf(110f, 90f, 10f, 20f).toList())     // 왼쪽 위로
        assertEquals(expect, PickMath.rectOf(110f, 20f, 10f, 90f).toList())     // 왼쪽 아래로
        assertEquals(expect, PickMath.rectOf(10f, 90f, 110f, 20f).toList())     // 오른쪽 위로
    }

    @Test fun rectOfASinglePointIsEmpty() {
        assertEquals(listOf(5f, 5f, 5f, 5f), PickMath.rectOf(5f, 5f, 5f, 5f).toList())
    }

    @Test fun rectIsAlwaysOrdered() {
        val rnd = Random(2)
        repeat(5_000) {
            val r = PickMath.rectOf(rnd.nextFloat() * 600, rnd.nextFloat() * 800, rnd.nextFloat() * 600, rnd.nextFloat() * 800)
            assertTrue(r[0] <= r[2] && r[1] <= r[3])
        }
    }

    // ================= 크기 =================

    @Test fun smallRectIsDiscardedBothSidesNeedSixteen() {
        assertTrue(PickMath.bigEnough(floatArrayOf(0f, 0f, 16f, 16f)))          // 경계 포함
        assertFalse(PickMath.bigEnough(floatArrayOf(0f, 0f, 15.9f, 100f)))
        assertFalse(PickMath.bigEnough(floatArrayOf(0f, 0f, 100f, 15.9f)))
        assertFalse(PickMath.bigEnough(floatArrayOf(0f, 0f, 0f, 0f)))
        assertTrue(PickMath.bigEnough(floatArrayOf(50f, 60f, 300f, 400f)))
    }

    // ================= 옛 계산과 대조 =================

    @Test fun clampMatchesTheOldCodeOnRandomViews() {
        val rnd = Random(9)
        repeat(20_000) {
            val sx = rnd.nextFloat() * 2000 - 200; val sy = rnd.nextFloat() * 3000 - 200
            val offX = rnd.nextFloat() * 500; val offY = rnd.nextFloat() * 6000
            val scale = 0.3f + rnd.nextFloat() * 4
            val left = rnd.nextFloat() * 50; val top = rnd.nextFloat() * 4000
            val pw = 200f + rnd.nextFloat() * 600; val ph = 300f + rnd.nextFloat() * 800
            val rx = ((sx + offX) / scale - left).coerceIn(0f, pw)
            val ry = ((sy + offY) / scale - top).coerceIn(0f, ph)
            val p = PickMath.clampToPage(sx, sy, offX, offY, scale, left, top, pw, ph)
            assertEquals(rx, p[0], 0f); assertEquals(ry, p[1], 0f)
            val x0 = rnd.nextFloat() * pw; val y0 = rnd.nextFloat() * ph
            val r = PickMath.rectOf(x0, y0, p[0], p[1])
            assertEquals(listOf(min(x0, rx), min(y0, ry), max(x0, rx), max(y0, ry)), r.toList())
        }
    }
}
