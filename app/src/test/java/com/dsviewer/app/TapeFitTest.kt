package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** 펜 테이프 다듬기: 곧게 펴기, 아래 글자 줄에 맞추기 */
class TapeFitTest {

    private val rnd = Random(3)

    private fun tape(vararg pts: Pair<Float, Float>, width: Float = 4f, step: Float = 1f, noise: Float = 0f): Stroke {
        val st = Stroke(Tool.TAPE, 0xFFF6C744.toInt(), width)
        fun j() = (rnd.nextFloat() - 0.5f) * 2 * noise
        st.add(pts[0].first, pts[0].second, 0.5f)
        for (k in 1 until pts.size) {
            val (ax, ay) = pts[k - 1]
            val (bx, by) = pts[k]
            val n = maxOf(1, (hypot(bx - ax, by - ay) / step).toInt())
            for (i in 1..n) st.add(ax + (bx - ax) * i / n + j(), ay + (by - ay) * i / n + j(), 0.5f)
        }
        return st
    }

    private fun last(st: Stroke) = st.x(st.count - 1) to st.y(st.count - 1)

    // ================= 곧게 펴기 =================

    @Test fun straightenKeepsTwoEndsAlongTheLine() {
        // 30° 기울어 흔들리게 그은 선: 점 둘로 줄고, 양 끝이 그은 만큼
        val a = 30 * PI.toFloat() / 180
        val st = tape(100f to 100f, 100f + 200 * cos(a) to 100f + 200 * sin(a), noise = 0.6f)
        TapeFit.straighten(st)
        assertEquals(2, st.count)
        assertEquals(100f, st.x(0), 3f); assertEquals(100f, st.y(0), 3f)
        assertEquals(30.0, Math.toDegrees(Math.atan2((st.y(1) - st.y(0)).toDouble(), (st.x(1) - st.x(0)).toDouble())), 1.5)
        assertEquals(200.0, hypot((st.x(1) - st.x(0)).toDouble(), (st.y(1) - st.y(0)).toDouble()), 4.0)
    }

    @Test fun straightenSnapsNearHorizontalAndVertical() {
        // 5° 안이면 딱 수평
        val h = tape(50f to 100f, 250f to 108f, noise = 0.2f)   // 약 2.3°
        TapeFit.straighten(h)
        assertEquals(h.y(0), h.y(1), 1e-3f)
        // 5° 안이면 딱 수직
        val v = tape(100f to 50f, 107f to 250f, noise = 0.2f)
        TapeFit.straighten(v)
        assertEquals(v.x(0), v.x(1), 1e-3f)
        // 그 이상 기울면 그대로 기울어 있다
        val d = tape(50f to 100f, 250f to 140f, noise = 0.2f)   // 약 11°
        TapeFit.straighten(d)
        assertTrue(abs(d.y(1) - d.y(0)) > 20f)
    }

    @Test fun straightenKeepsDrawingDirection() {
        // 오른쪽에서 왼쪽으로 그은 선은 오른쪽 끝에서 시작
        val st = tape(300f to 100f, 100f to 103f, noise = 0.2f)
        TapeFit.straighten(st)
        assertTrue(st.x(0) > st.x(1))
        // 아래에서 위로 그은 선도
        val up = tape(100f to 300f, 104f to 100f, noise = 0.2f)
        TapeFit.straighten(up)
        assertTrue(up.y(0) > up.y(1))
    }

    @Test fun straightenLeavesCurvesAlone() {
        // 많이 휜 호(반원)는 곧게 펴지 않는다
        val pts = (0..40).map { i -> val t = PI.toFloat() * i / 40; 150f + 80 * cos(t) to 150f + 80 * sin(t) }
        val st = tape(*pts.toTypedArray())
        val before = st.count
        TapeFit.straighten(st)
        assertEquals(before, st.count)
    }

    // ================= 글자 줄에 맞추기 =================

    /** y가 [top, bottom] 안이면 어두운 글자 줄 (좌우는 어디나) */
    private fun band(top: Float, bottom: Float): (Float, Float) -> Boolean = { _, y -> y in top..bottom }

    @Test fun fitCentersAndWidensToTheTextLine() {
        // 글자 줄 y 100~112 위에 가는 테이프(굵기 4)를 y 106에 그었다 → 줄 높이 12에 여유를 더한 굵기, 가운데 106
        val st = tape(50f to 106f, 250f to 106f, width = 4f)
        TapeFit.fitToText(st, band(100f, 112f))
        assertEquals(12f + 2 * 1.8f, st.width, 0.8f)
        for (i in 0 until st.count) assertEquals(106f, st.y(i), 0.6f)
    }

    @Test fun fitMovesTapeOntoAnOffCenterLine() {
        // 테이프는 줄 위쪽 가장자리(y 104)에 있고, 줄은 y 103~115 → 줄 가운데(109)로 옮긴다
        val st = tape(50f to 104f, 250f to 104f, width = 4f)
        TapeFit.fitToText(st, band(103f, 115f))
        for (i in 0 until st.count) assertEquals(109f, st.y(i), 0.8f)
        // 가로 위치는 그대로
        assertEquals(50f, st.x(0), 1e-3f)
        assertEquals(250f, st.x(st.count - 1), 1e-3f)
    }

    @Test fun fitWorksAlongASlantedTape() {
        // 기울어진 테이프: 수직 방향(법선)으로 옮긴다. 줄 가운데가 테이프보다 법선 쪽으로 +3pt
        val a = 20 * PI.toFloat() / 180
        val ux = cos(a); val uy = sin(a)
        val nx = -uy; val ny = ux
        // 테이프 선 위의 점에서 법선 방향 거리가 [-3+0, +9] (가운데 +3)인 곳을 어두운 줄로
        fun dist(x: Float, y: Float) = (x - 50f) * nx + (y - 100f) * ny
        val st = tape(50f to 100f, 50f + 200 * ux to 100f + 200 * uy, width = 4f)
        TapeFit.fitToText(st) { x, y -> dist(x, y) in -3f..9f }
        val moved = (st.x(5) - 50f - 5 * ux) * nx + (st.y(5) - 100f - 5 * uy) * ny
        assertEquals(3f, moved, 0.8f)
    }

    @Test fun fitDoesNothingWhenThereIsNoText() {
        val st = tape(50f to 106f, 250f to 106f, width = 4f)
        TapeFit.fitToText(st) { _, _ -> false }
        assertEquals(4f, st.width, 1e-6f)
        assertEquals(106f, st.y(0), 1e-6f)
    }

    @Test fun fitIgnoresASpeckOfInk() {
        // 테이프를 따라 3%에도 못 미치는 얼룩은 글자 줄로 보지 않는다
        val st = tape(50f to 106f, 250f to 106f, width = 4f)
        TapeFit.fitToText(st) { x, y -> x in 100f..104f && y in 100f..112f }
        assertEquals(4f, st.width, 1e-6f)
    }

    @Test fun fitLeavesFiguresAndTablesAlone() {
        // 찾는 범위 끝까지 이어지는 짙은 영역(그림·표)은 글자 줄이 아니다
        val st = tape(50f to 106f, 250f to 106f, width = 4f)
        TapeFit.fitToText(st) { _, _ -> true }
        assertEquals(4f, st.width, 1e-6f)
        assertEquals(106f, st.y(0), 1e-6f)
    }

    @Test fun fitSkipsTextTooFarFromTheTape() {
        // 테이프에서 6pt(굵기 절반) 넘게 떨어진 줄은 찾지 않는다
        val st = tape(50f to 106f, 250f to 106f, width = 4f)
        TapeFit.fitToText(st, band(125f, 137f))
        assertEquals(4f, st.width, 1e-6f)
    }

    @Test fun fitBridgesSmallGapsInsideALine() {
        // 글자 안의 작은 틈(받침 사이 등)은 건너뛰고 한 줄로
        val st = tape(50f to 106f, 250f to 106f, width = 4f)
        TapeFit.fitToText(st) { _, y -> y in 100f..105f || y in 106.5f..112f }
        assertEquals(12f + 2 * 1.8f, st.width, 1.2f)
    }
}
