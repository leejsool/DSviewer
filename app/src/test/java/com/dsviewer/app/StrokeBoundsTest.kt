package com.dsviewer.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 화면 밖 획을 그리지 않으려고 쓰는 획의 겹침 판정 */
class StrokeBoundsTest {

    private fun line(width: Float, vararg xy: Float) = Stroke(Tool.PEN, 0xFF000000.toInt(), width).also {
        for (i in 0 until xy.size / 2) it.add(xy[i * 2], xy[i * 2 + 1], 0.5f)
    }

    @Test fun emptyStrokeNeverTouches() {
        assertFalse(Stroke(Tool.PEN, 0, 2f).mayTouch(-1e6f, -1e6f, 1e6f, 1e6f))
    }

    @Test fun strokeInsideOrCrossingTheBoxTouches() {
        val s = line(2f, 10f, 10f, 50f, 50f)
        assertTrue(s.mayTouch(0f, 0f, 100f, 100f))
        assertTrue(s.mayTouch(40f, 40f, 200f, 200f))
    }

    @Test fun strokeFarFromTheBoxDoesNot() {
        val s = line(2f, 10f, 10f, 50f, 50f)
        assertFalse(s.mayTouch(200f, 0f, 300f, 100f))
        assertFalse(s.mayTouch(0f, 200f, 100f, 300f))
    }

    @Test fun thickLineReachesBeyondItsPoints() {
        // 점은 상자 밖(x=50)이지만 굵기 40의 선은 상자 안 x=35까지 닿는다
        val s = line(40f, 50f, 0f, 50f, 100f)
        assertTrue(s.mayTouch(0f, 0f, 35f, 100f))
    }

    @Test fun boundsFollowTheStrokeWhenItMoves() {
        val s = line(2f, 10f, 10f, 20f, 20f)
        assertFalse(s.mayTouch(500f, 500f, 600f, 600f))
        s.translate(500f, 500f)
        assertTrue(s.mayTouch(500f, 500f, 600f, 600f))
        assertFalse(s.mayTouch(0f, 0f, 100f, 100f))
    }

    @Test fun boundsFollowNewPoints() {
        val s = line(2f, 10f, 10f)
        assertFalse(s.mayTouch(300f, 300f, 400f, 400f))
        s.add(350f, 350f, 0.5f)
        assertTrue(s.mayTouch(300f, 300f, 400f, 400f))
    }
}
