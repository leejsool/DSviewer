package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** 꾹 눌러 도형 보정: 손으로 대충 그린 모양을 직선·원·타원·삼각형·사각형·별로 알아보는지 (그리고 글씨 같은 것은 건드리지 않는지) */
class ShapeRecognizerTest {

    private val rnd = Random(11)

    /** 점들을 이은 획. 점 사이는 1 간격으로 촘촘히, 모든 점에 [noise] 안의 흔들림 */
    private fun stroke(pts: List<Pair<Double, Double>>, noise: Double = 1.0): Stroke {
        val st = Stroke(Tool.PEN, 0xFF000000.toInt(), 2f)
        fun jitter() = (rnd.nextDouble() - 0.5) * 2 * noise
        st.add(pts[0].first.toFloat(), pts[0].second.toFloat(), 0.5f)
        for (k in 1 until pts.size) {
            val (ax, ay) = pts[k - 1]
            val (bx, by) = pts[k]
            val n = maxOf(1, hypot(bx - ax, by - ay).toInt())
            for (i in 1..n) st.add((ax + (bx - ax) * i / n + jitter()).toFloat(), (ay + (by - ay) * i / n + jitter()).toFloat(), 0.5f)
        }
        return st
    }

    private fun recognize(st: Stroke) = ShapeRecognizer.recognize(st, 40f)

    /** 꼭짓점을 돌아 처음으로 닫는다. [overshoot]면 처음 변을 그만큼 더 지나쳐 긋는다 */
    private fun polygon(vs: List<Pair<Double, Double>>, noise: Double = 1.0, overshoot: Double = 0.0): Stroke {
        val closed = vs + vs[0]
        if (overshoot == 0.0) return stroke(closed, noise)
        val (ax, ay) = vs[0]; val (bx, by) = vs[1]
        val d = hypot(bx - ax, by - ay)
        return stroke(closed + ((ax + (bx - ax) / d * overshoot) to (ay + (by - ay) / d * overshoot)), noise)
    }

    private fun ellipsePts(cx: Double, cy: Double, a: Double, b: Double, tilt: Double = 0.0, turns: Double = 1.0, start: Double = 0.0): List<Pair<Double, Double>> =
        (0..120).map { i ->
            val t = start + 2 * PI * turns * i / 120
            val u = a * cos(t); val v = b * sin(t)
            (cx + u * cos(tilt) - v * sin(tilt)) to (cy + u * sin(tilt) + v * cos(tilt))
        }

    @Test fun 직선() {
        val r = recognize(stroke(listOf(100.0 to 100.0, 300.0 to 108.0), 1.2))
        assertEquals("직선", r?.label)
    }

    @Test fun 수평에_가까운_직선은_딱_수평() {
        val c = recognize(stroke(listOf(100.0 to 100.0, 300.0 to 104.0), 0.5))!!.fitted.curves.single()
        assertEquals(c[1], c[3], 1e-3f)
    }

    @Test fun 휜_선은_직선이_아니다() {
        val arc = (0..40).map { 100.0 + it * 5 to 100.0 + 40 * sin(PI * it / 40) }
        assertNull(recognize(stroke(arc)))
    }

    @Test fun 원() {
        assertEquals("원", recognize(stroke(ellipsePts(200.0, 200.0, 80.0, 78.0, start = 1.0), 2.0))?.label)
    }

    @Test fun 끝이_조금_벌어진_원() {
        assertEquals("원", recognize(stroke(ellipsePts(200.0, 200.0, 80.0, 80.0, turns = 0.93), 1.5))?.label)
    }

    @Test fun 타원() {
        assertEquals("타원", recognize(stroke(ellipsePts(200.0, 200.0, 110.0, 55.0, tilt = 0.3), 1.5))?.label)
    }

    @Test fun 길쭉한_타원() {
        assertEquals("타원", recognize(stroke(ellipsePts(200.0, 200.0, 140.0, 40.0), 1.0))?.label)
    }

    @Test fun 삼각형() {
        val r = recognize(polygon(listOf(200.0 to 100.0, 320.0 to 300.0, 90.0 to 280.0)))
        assertEquals("삼각형", r?.label)
        assertEquals(4, r!!.fitted.curves.single().size / 2)
    }

    @Test fun 직각삼각형은_90도로() {
        val r = recognize(polygon(listOf(100.0 to 100.0, 100.0 to 300.0, 320.0 to 306.0)))!!
        assertEquals("삼각형", r.label)
        val c = r.fitted.curves.single()
        // 세 꼭짓점 중 하나는 두 변이 정확히 수직
        val cosines = (0..2).map { i ->
            val o = (i + 1) % 3; val a = i; val b = (i + 2) % 3
            val ax = c[a * 2] - c[o * 2]; val ay = c[a * 2 + 1] - c[o * 2 + 1]
            val bx = c[b * 2] - c[o * 2]; val by = c[b * 2 + 1] - c[o * 2 + 1]
            abs((ax * bx + ay * by) / (hypot(ax, ay) * hypot(bx, by))).toDouble()
        }
        assertEquals(0.0, cosines.min(), 0.02)
    }

    @Test fun 사각형() {
        assertEquals("사각형", recognize(polygon(listOf(100.0 to 100.0, 330.0 to 108.0, 322.0 to 260.0, 96.0 to 252.0), 1.5))?.label)
    }

    @Test fun 정사각형_모서리를_조금_넘겨_그어도() {
        val r = recognize(polygon(listOf(100.0 to 100.0, 260.0 to 98.0, 262.0 to 262.0, 98.0 to 260.0), 1.0, overshoot = 14.0))
        assertEquals("사각형", r?.label)
    }

    @Test fun 기울어진_사각형도() {
        val vs = listOf(0.0 to 0.0, 200.0 to 0.0, 200.0 to 100.0, 0.0 to 100.0).map { (x, y) ->
            (220 + x * cos(0.5) - y * sin(0.5)) to (120 + x * sin(0.5) + y * cos(0.5))
        }
        assertEquals("사각형", recognize(polygon(vs))?.label)
    }

    private fun pentagramPts(cx: Double, cy: Double, r: Double, rot: Double): List<Pair<Double, Double>> =
        (0..5).map { i ->
            val a = rot + 4 * PI * (i % 5) / 5
            (cx + r * cos(a)) to (cy + r * sin(a))
        }

    @Test fun 한붓_오각별() {
        val r = recognize(stroke(pentagramPts(250.0, 250.0, 120.0, -PI / 2 + 0.08), 1.5))
        assertEquals("별", r?.label)
        // 꼭지 하나가 정확히 위를 향하도록 맞춰진다
        val c = r!!.fitted.curves.single()
        val top = (0 until 5).minBy { c[it * 2 + 1] }
        assertEquals(250f, c[top * 2], 0.5f)
        assertEquals(130f, c[top * 2 + 1], 3f)
    }

    @Test fun 거꾸로_그린_별도() {
        assertEquals("별", recognize(stroke(pentagramPts(250.0, 250.0, 120.0, 0.7).reversed(), 1.5))?.label)
    }

    @Test fun 둘레를_따라_그린_별() {
        val vs = (0 until 10).map { i ->
            val a = -PI / 2 + PI * i / 5
            val r = if (i % 2 == 0) 120.0 else 48.0
            (250 + r * cos(a)) to (250 + r * sin(a))
        }
        assertEquals("별", recognize(polygon(vs, 1.0))?.label)
    }

    @Test fun 지그재그는_도형이_아니다() {
        val zig = (0..8).map { 100.0 + it * 30 to if (it % 2 == 0) 100.0 else 160.0 }
        assertNull(recognize(stroke(zig)))
    }

    @Test fun 숫자_8은_도형이_아니다() {
        val eight = (0..160).map { i ->
            val t = 2 * PI * i / 160
            (200 + 50 * sin(t)) to (200 - 90 * cos(t) * (if (cos(t) > 0) 1.0 else 1.0) + 0.0)
        }.mapIndexed { i, (x, y) -> if (i < 80) x to y else (400 - x) to y }
        assertNull(recognize(stroke(eight)))
    }

    @Test fun 너무_작으면_도형이_아니다() {
        assertNull(recognize(stroke(ellipsePts(200.0, 200.0, 8.0, 8.0), 0.2)))
        assertNull(recognize(stroke(listOf(100.0 to 100.0, 120.0 to 100.0), 0.0)))
    }

    @Test fun 반원은_도형이_아니다() {
        assertNull(recognize(stroke(ellipsePts(200.0, 200.0, 80.0, 80.0, turns = 0.5), 1.0)))
    }

    @Test fun 결과는_닫힌_도형이면_첫_점으로_돌아온다() {
        for (vs in listOf(
            listOf(200.0 to 100.0, 320.0 to 300.0, 90.0 to 280.0),
            listOf(100.0 to 100.0, 330.0 to 108.0, 322.0 to 260.0, 96.0 to 252.0),
        )) {
            val c = recognize(polygon(vs))!!.fitted.curves.single()
            val n = c.size / 2
            assertNotNull(c)
            assertEquals(c[0], c[(n - 1) * 2], 1e-3f)
            assertEquals(c[1], c[(n - 1) * 2 + 1], 1e-3f)
            assertEquals(true, abs(c[0] - c[2]) + abs(c[1] - c[3]) > 1f)
        }
    }
}
