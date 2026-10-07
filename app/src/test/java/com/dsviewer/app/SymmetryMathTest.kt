package com.dsviewer.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

/** 선대칭: 반사·축 맞추기·접기 계산 */
class SymmetryMathTest {

    private val eps = 1e-3f

    // ================= 반사 =================

    @Test fun reflectAcrossHorizontalAndVerticalAxes() {
        // 가로축 y=5: (3, 8) → (3, 2)
        assertArrayEquals(floatArrayOf(3f, 2f), SymmetryMath.reflect(3f, 8f, 0f, 5f, 10f, 5f), eps)
        // 세로축 x=4: (7, 1) → (1, 1)
        assertArrayEquals(floatArrayOf(1f, 1f), SymmetryMath.reflect(7f, 1f, 4f, 0f, 4f, 9f), eps)
    }

    @Test fun reflectAcrossDiagonalSwapsCoordinates() {
        // y=x 축(화면 좌표에서도 점 (0,0)→(1,1)): (2, 5) → (5, 2)
        assertArrayEquals(floatArrayOf(5f, 2f), SymmetryMath.reflect(2f, 5f, 0f, 0f, 1f, 1f), eps)
    }

    @Test fun pointsOnTheAxisStayAndReflectingTwiceComesBack() {
        assertArrayEquals(floatArrayOf(6f, 6f), SymmetryMath.reflect(6f, 6f, 0f, 0f, 3f, 3f), eps)
        val once = SymmetryMath.reflect(2.5f, -4f, 1f, 2f, 7f, 5f)
        val twice = SymmetryMath.reflect(once[0], once[1], 1f, 2f, 7f, 5f)
        assertArrayEquals(floatArrayOf(2.5f, -4f), twice, eps)
    }

    @Test fun reflectKeepsDistanceToTheAxis() {
        val ax = 1f; val ay = 2f; val bx = 7f; val by = 5f
        val s1 = SymmetryMath.side(3f, 9f, ax, ay, bx, by)
        val r = SymmetryMath.reflect(3f, 9f, ax, ay, bx, by)
        val s2 = SymmetryMath.side(r[0], r[1], ax, ay, bx, by)
        assertEquals(s1, -s2, 1e-3)
    }

    @Test fun degenerateAxisLeavesThePointAlone() {
        assertArrayEquals(floatArrayOf(3f, 4f), SymmetryMath.reflect(3f, 4f, 1f, 1f, 1f, 1f), eps)
    }

    // ================= 축 맞추기 =================

    @Test fun nearlyHorizontalVerticalAndDiagonalLinesSnap() {
        // 거의 가로 (약 3°)
        val h = SymmetryMath.snapAxis(0f, 0f, 100f, 5f)
        assertEquals(0f, h[3] - h[1], eps)
        assertEquals(hypot(100.0, 5.0).toFloat(), h[2] - h[0], 1e-2f)
        // 거의 세로
        val v = SymmetryMath.snapAxis(10f, 10f, 14f, 110f)
        assertEquals(0f, v[2] - v[0], eps)
        // 거의 45° (y가 아래로 커지는 쪽)
        val d = SymmetryMath.snapAxis(0f, 0f, 60f, 63f)
        assertEquals(d[2] - d[0], d[3] - d[1], eps)
        // 거의 -45°
        val u = SymmetryMath.snapAxis(0f, 0f, 60f, -58f)
        assertEquals(u[2] - u[0], -(u[3] - u[1]), eps)
    }

    @Test fun otherAnglesAreLeftAsDrawn() {
        // 약 22° 는 어느 쪽에도 가깝지 않다
        val a = SymmetryMath.snapAxis(0f, 0f, 100f, 40f)
        assertArrayEquals(floatArrayOf(0f, 0f, 100f, 40f), a, 0f)
        // 길이 0
        assertArrayEquals(floatArrayOf(5f, 5f, 5f, 5f), SymmetryMath.snapAxis(5f, 5f, 5f, 5f), 0f)
    }

    @Test fun shortAxesAreRejected() {
        assertFalse(SymmetryMath.longEnough(floatArrayOf(0f, 0f, 10f, 5f)))
        assertTrue(SymmetryMath.longEnough(floatArrayOf(0f, 0f, 20f, 0f)))
    }

    // ================= 접기 =================

    private fun pts(vararg xy: Float): FloatArray {
        val out = FloatArray(xy.size / 2 * 3)
        for (i in 0 until xy.size / 2) { out[i * 3] = xy[i * 2]; out[i * 3 + 1] = xy[i * 2 + 1]; out[i * 3 + 2] = 0.5f }
        return out
    }

    @Test fun foldingAVMakesAbsoluteValueShape() {
        // 가로축 y=0 (A→B가 오른쪽): side(y>0)=+. y가 양수인 쪽을 남기면 (0,-2)·(2,2) 선분의 음수 쪽이 접혀 올라온다
        val data = pts(0f, -2f, 2f, 2f)
        val out = SymmetryMath.fold(data, 2, 0f, 0f, 1f, 0f, keep = 1)
        assertNotNull(out)
        // (0,2) → 교점 (1,0) → (2,2): 점 3개의 V
        assertEquals(9, out!!.size)
        assertArrayEquals(floatArrayOf(0f, 2f, 0.5f, 1f, 0f, 0.5f, 2f, 2f, 0.5f), out, eps)
    }

    @Test fun foldingOnlyMovesThePartOnTheOtherSide() {
        // 위(y<0)에서 시작해 아래(y>0)로 내려가는 선: y>0 쪽을 남기면 앞부분만 반사
        val data = pts(0f, -4f, 0f, -2f, 0f, 3f)
        val out = SymmetryMath.fold(data, 3, 0f, 0f, 1f, 0f, keep = 1)!!
        // (0,4) (0,2) 교점(0,0) (0,3)
        val ys = (0 until out.size / 3).map { out[it * 3 + 1] }
        assertEquals(listOf(4f, 2f, 0f, 3f), ys)
    }

    @Test fun foldingReturnsNullWhenEverythingIsOnTheKeptSide() {
        val data = pts(0f, 1f, 5f, 3f, 9f, 2f)
        assertNull(SymmetryMath.fold(data, 3, 0f, 0f, 1f, 0f, keep = 1))
        // 축 위의 점만 있어도 접을 것이 없다
        assertNull(SymmetryMath.fold(pts(0f, 0f, 4f, 0f), 2, 0f, 0f, 1f, 0f, keep = -1))
    }

    @Test fun foldingTheOtherWayKeepsTheOtherSide() {
        val data = pts(0f, -2f, 2f, 2f)
        val out = SymmetryMath.fold(data, 2, 0f, 0f, 1f, 0f, keep = -1)!!
        // 이번에는 y<0 쪽이 남고 (2,2)가 (2,-2)로 접힌다: (0,-2) 교점(1,0) (2,-2)
        val ys = (0 until out.size / 3).map { out[it * 3 + 1] }
        assertEquals(listOf(-2f, 0f, -2f), ys)
    }

    @Test fun foldingOverTheYAxisGivesFOfAbsX() {
        // 세로축 x=0 (A=(0,0)→B=(0,1)): side = -(x)... x>0 쪽이 한쪽. 오른쪽(x>0)을 남기면 왼쪽 팔이 반사된다
        val keepRight = if (SymmetryMath.sideSign(1f, 0f, 0f, 0f, 0f, 1f) > 0) 1 else -1
        val data = pts(-3f, 9f, -1f, 1f, 1f, 1f, 3f, 9f)  // 포물선 x² 의 점들
        val out = SymmetryMath.fold(data, 4, 0f, 0f, 0f, 1f, keep = keepRight)!!
        // 왼쪽이 오른쪽으로 접혀 모든 x가 0 이상
        for (i in 0 until out.size / 3) assertTrue(out[i * 3] >= -eps)
    }

    @Test fun foldKeepsPressureAndInterpolatesAtTheCrossing() {
        val data = floatArrayOf(0f, -2f, 0.2f, 2f, 2f, 0.8f)
        val out = SymmetryMath.fold(data, 2, 0f, 0f, 1f, 0f, keep = 1)!!
        assertEquals(0.2f, out[2], eps)
        assertEquals(0.5f, out[5], eps)   // 교점은 두 점의 가운데
        assertEquals(0.8f, out[8], eps)
    }
}
