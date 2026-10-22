package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** 보정 펜 점 찍기 도형(세 점 원·삼각형, 네 점 사각형)과 대각선 직사각형 */
class ShapeTapTest {

    @Test
    fun circleThroughThreePoints() {
        val f = ShapeFit.fromPoints(ShapeKind.CIRCLE_3PT, floatArrayOf(110f, 100f, 100f, 110f, 90f, 100f))
        assertNotNull(f)
        val c = f!!.curves[0]
        // 중심 (100, 100), 반지름 10
        for (i in 0 until c.size / 2) assertEquals(10.0, hypot(c[i * 2] - 100.0, c[i * 2 + 1] - 100.0), 0.01)
        // 첫 점에서 시작해 한 바퀴
        assertEquals(110f, c[0], 0.01f)
        assertEquals(100f, c[1], 0.01f)
    }

    @Test
    fun collinearPointsMakeNoShape() {
        assertNull(ShapeFit.fromPoints(ShapeKind.CIRCLE_3PT, floatArrayOf(0f, 0f, 10f, 10f, 20f, 20f)))
        assertNull(ShapeFit.fromPoints(ShapeKind.TRI_3PT, floatArrayOf(0f, 0f, 10f, 10f, 20f, 20f)))
    }

    @Test
    fun triangleKeepsTheTappedPoints() {
        val c = ShapeFit.fromPoints(ShapeKind.TRI_3PT, floatArrayOf(0f, 0f, 40f, 0f, 10f, 30f))!!.curves[0]
        assertEquals(8, c.size)
        assertEquals(10f, c[4], 0f)
        assertEquals(30f, c[5], 0f)
        // 닫힌 꺾은선
        assertEquals(c[0], c[6], 0f)
        assertEquals(c[1], c[7], 0f)
    }

    @Test
    fun crossedQuadIsUntwisted() {
        // 찍은 순서대로 이으면 나비 모양 (0,0)-(10,10)-(10,0)-(0,10)
        val c = ShapeFit.fromPoints(ShapeKind.QUAD_4PT, floatArrayOf(0f, 0f, 10f, 10f, 10f, 0f, 0f, 10f))!!.curves[0]
        assertEquals(10, c.size)
        // 반듯한 네모의 둘레 40
        var per = 0.0
        for (i in 0 until 4) per += hypot((c[i * 2 + 2] - c[i * 2]).toDouble(), (c[i * 2 + 3] - c[i * 2 + 1]).toDouble())
        assertEquals(40.0, per, 0.01)
    }

    @Test
    fun diagonalMakesAxisAlignedRectangle() {
        val st = Stroke(Tool.PEN, 0xFF000000.toInt(), 2f)
        st.add(10f, 20f, 0.5f)
        st.add(30f, 33f, 0.5f)
        st.add(60f, 50f, 0.5f)
        val c = ShapeFit.fit(ShapeKind.RECT_DIAG, st)!!.curves[0]
        assertEquals(listOf(10f, 20f, 60f, 20f, 60f, 50f, 10f, 50f, 10f, 20f), c.toList())
    }

    @Test
    fun tapKindsAreNotFittedFromAStroke() {
        val st = Stroke(Tool.PEN, 0xFF000000.toInt(), 2f)
        st.add(0f, 0f, 0.5f); st.add(50f, 50f, 0.5f)
        assertNull(ShapeFit.fit(ShapeKind.TRI_3PT, st))
        assertTrue(ShapeKind.QUAD_4PT.isTapKind)
        assertEquals(4, ShapeKind.QUAD_4PT.tapPoints)
    }
}
