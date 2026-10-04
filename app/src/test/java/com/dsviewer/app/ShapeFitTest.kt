package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 보정 펜 기본 도형 맞추기: 직선 · 원 · 타원 · 중심·반지름 원 · 이차 · 삼각형 · 사각형,
 * 그리고 맞추기가 기대는 작은 계산(연립방정식, 극점 찾기, 뒤집기).
 * 도형마다 '그린 것과 가까운가'가 아니라 '그 도형의 조건(같은 변·직각·평행 …)을 정확히 지키는가'를 본다.
 */
class ShapeFitTest {

    private val rnd = Random(7)

    /** 점들을 이은 획. 점 사이는 1 간격으로 촘촘히, 모든 점에 [noise] 안의 흔들림 */
    private fun stroke(pts: List<Pair<Double, Double>>, noise: Double = 0.0, width: Float = 2f): Stroke {
        val st = Stroke(Tool.PEN, 0xFF000000.toInt(), width)
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

    /** 꼭짓점을 차례로 돌며 닫힌 도형을 한 획으로 */
    private fun closedStroke(vs: List<Pair<Double, Double>>, noise: Double = 0.8) = stroke(vs + vs[0], noise)

    private fun fit(kind: ShapeKind, st: Stroke) = ShapeFit.fit(kind, st)?.curves?.single()

    private fun pt(a: FloatArray, i: Int) = a[i * 2].toDouble() to a[i * 2 + 1].toDouble()
    private fun dist(a: Pair<Double, Double>, b: Pair<Double, Double>) = hypot(a.first - b.first, a.second - b.second)

    /** 닫힌 꺾은선(끝점이 처음과 같음)의 꼭짓점들 */
    private fun vertices(a: FloatArray): List<Pair<Double, Double>> {
        val n = a.size / 2
        assertEquals("닫힌 도형은 첫 점으로 되돌아와야 한다", pt(a, 0), pt(a, n - 1))
        return (0 until n - 1).map { pt(a, it) }
    }

    private fun sides(vs: List<Pair<Double, Double>>) = vs.indices.map { dist(vs[it], vs[(it + 1) % vs.size]) }

    /** 꼭짓점 i의 내각 (도) */
    private fun angle(vs: List<Pair<Double, Double>>, i: Int): Double {
        val a = vs[(i - 1 + vs.size) % vs.size]; val o = vs[i]; val b = vs[(i + 1) % vs.size]
        val ux = a.first - o.first; val uy = a.second - o.second
        val vx = b.first - o.first; val vy = b.second - o.second
        return acos(((ux * vx + uy * vy) / (hypot(ux, uy) * hypot(vx, vy))).coerceIn(-1.0, 1.0)) * 180 / PI
    }

    // ================= 직선 =================

    @Test fun lineKeepsDrawnDirectionAndEnds() {
        // 30° 기울어진 선 (수평·수직·45°에 붙지 않는 각): 그린 각을 그대로 두고 양 끝을 그린 만큼
        val a = 30 * PI / 180
        val l = fit(ShapeKind.LINE, stroke(listOf(100.0 to 100.0, 100 + 200 * cos(a) to 100 + 200 * sin(a)), noise = 1.0))!!
        assertEquals(4, l.size)
        assertEquals(100.0, l[0].toDouble(), 3.0); assertEquals(100.0, l[1].toDouble(), 3.0)
        assertEquals(30.0, Math.toDegrees(Math.atan2((l[3] - l[1]).toDouble(), (l[2] - l[0]).toDouble())), 1.5)
        assertEquals(200.0, hypot((l[2] - l[0]).toDouble(), (l[3] - l[1]).toDouble()), 4.0)
    }

    @Test fun lineStartsAtThePointDrawnFirst() {
        // 오른쪽에서 왼쪽으로 그리면 결과도 오른쪽 끝에서 시작
        val l = fit(ShapeKind.LINE, stroke(listOf(300.0 to 100.0, 100.0 to 160.0), noise = 0.5))!!
        assertTrue(l[0] > l[2])
    }

    @Test fun lineSnapsToHorizontalVerticalAndDiagonal() {
        // 4° 안이면 딱 맞춘다
        val h = fit(ShapeKind.LINE, stroke(listOf(50.0 to 100.0, 250.0 to 107.0)))!!   // 약 2°
        assertEquals(h[1], h[3], 1e-3f)
        val v = fit(ShapeKind.LINE, stroke(listOf(100.0 to 50.0, 106.0 to 250.0)))!!   // 약 1.7°
        assertEquals(v[0], v[2], 1e-3f)
        val d = fit(ShapeKind.LINE, stroke(listOf(50.0 to 50.0, 250.0 to 258.0)))!!    // 45°에서 약 1.5°
        assertEquals(abs(d[2] - d[0]), abs(d[3] - d[1]), 1e-2f)
        // 4°보다 벗어나면 그대로
        val tilted = fit(ShapeKind.LINE, stroke(listOf(50.0 to 100.0, 250.0 to 130.0)))!!   // 약 8.5°
        assertTrue(abs(tilted[3] - tilted[1]) > 20f)
    }

    @Test fun tinyOrTooFewPointsAreNotShapes() {
        assertNull(ShapeFit.fit(ShapeKind.LINE, stroke(listOf(10.0 to 10.0, 11.0 to 11.0))))
        val two = Stroke(Tool.PEN, 0xFF000000.toInt(), 2f).apply { add(0f, 0f, 1f); add(50f, 50f, 1f) }
        assertNull("점이 3개 미만이면 맞출 수 없다", ShapeFit.fit(ShapeKind.LINE, two))
    }

    // ================= 원 · 타원 =================

    @Test fun circleFitsCenterAndRadius() {
        val pts = (0..360 step 5).map { d -> 200 + 80 * cos(Math.toRadians(d.toDouble())) to 150 + 80 * sin(Math.toRadians(d.toDouble())) }
        val c = fit(ShapeKind.CIRCLE, stroke(pts, noise = 1.5))!!
        for (i in 0 until c.size / 2) {
            val p = pt(c, i)
            assertEquals(80.0, dist(p, 200.0 to 150.0), 2.0)
        }
    }

    @Test fun circleDrawnLoosely() {
        // 시작과 끝이 어긋나고(열린 원), 반지름이 조금씩 흔들려도 원으로
        val pts = (0..340 step 5).map { d ->
            val r = 60 + 4 * sin(Math.toRadians(d * 3.0))
            100 + r * cos(Math.toRadians(d.toDouble())) to 100 + r * sin(Math.toRadians(d.toDouble()))
        }
        val c = fit(ShapeKind.CIRCLE, stroke(pts, noise = 1.0))!!
        val radii = (0 until c.size / 2).map { dist(pt(c, it), 100.0 to 100.0) }
        assertEquals(60.0, radii.average(), 3.0)
        assertTrue("고른 원이어야 한다", radii.max() - radii.min() < 1.0)
    }

    @Test fun ellipseFitsBothAxes() {
        val pts = (0..360 step 5).map { d -> 250 + 100 * cos(Math.toRadians(d.toDouble())) to 200 + 50 * sin(Math.toRadians(d.toDouble())) }
        val e = fit(ShapeKind.ELLIPSE, stroke(pts, noise = 1.0))!!
        for (i in 0 until e.size / 2) {
            val (x, y) = pt(e, i)
            val v = ((x - 250) / 100).let { it * it } + ((y - 200) / 50).let { it * it }
            assertEquals("타원 위의 점", 1.0, v, 0.08)
        }
    }

    @Test fun centerRadiusCircleUsesFirstAndLastPoint() {
        // 처음 점이 중심, 끝 점까지의 거리가 반지름: 그린 가운데 부분은 상관없다
        val st = stroke(listOf(120.0 to 120.0, 150.0 to 200.0, 120.0 + 60 to 120.0))
        val c = fit(ShapeKind.CIRCLE_CR, st)!!
        for (i in 0 until c.size / 2) assertEquals(60.0, dist(pt(c, i), 120.0 to 120.0), 0.01)
        // 반지름을 그은 쪽에서 시작한다
        assertEquals(180.0, c[0].toDouble(), 0.01); assertEquals(120.0, c[1].toDouble(), 0.01)
        // 반지름이 2보다 작으면 원이 아니다
        val dot = Stroke(Tool.PEN, 0xFF000000.toInt(), 2f).apply { add(10f, 10f, 1f); add(11f, 10f, 1f) }
        assertNull(ShapeFit.fit(ShapeKind.CIRCLE_CR, dot))
    }

    // ================= 함수 =================

    @Test fun quadraticFollowsParabola() {
        // 위로 볼록한 포물선 (쪽 좌표는 y가 아래로): y = 150 − 0.01 (x−200)²
        val pts = (50..350 step 5).map { x -> x.toDouble() to 150 - 0.01 * (x - 200.0) * (x - 200.0) }
        val q = fit(ShapeKind.QUADRATIC, stroke(pts, noise = 1.0))!!
        for (i in 0 until q.size / 2 step 7) {
            val (x, y) = pt(q, i)
            assertEquals(150 - 0.01 * (x - 200) * (x - 200), y, 3.0)
        }
        // 그린 방향 그대로: 왼쪽에서 오른쪽
        assertTrue(q[0] < q[q.size - 2])
    }

    @Test fun quadraticDrawnRightToLeftKeepsDirection() {
        val pts = (350 downTo 50 step 5).map { x -> x.toDouble() to 150 - 0.01 * (x - 200.0) * (x - 200.0) }
        val q = fit(ShapeKind.QUADRATIC, stroke(pts, noise = 1.0))!!
        assertTrue(q[0] > q[q.size - 2])
    }

    @Test fun quadraticNeedsWidth() {
        // 폭이 4 미만이면 함수로 보지 않는다
        assertNull(ShapeFit.fit(ShapeKind.QUADRATIC, stroke(listOf(100.0 to 10.0, 101.0 to 60.0, 102.0 to 110.0))))
    }

    // ================= 삼각형 =================

    private val sloppyTriangle = listOf(300.0 to 100.0, 195.0 to 290.0, 415.0 to 295.0)

    @Test fun equilateralHasEqualSides() {
        val v = vertices(fit(ShapeKind.TRI_EQUILATERAL, closedStroke(sloppyTriangle))!!)
        assertEquals(3, v.size)
        val s = sides(v)
        assertEquals(s[0], s[1], s[0] * 0.01)
        assertEquals(s[1], s[2], s[1] * 0.01)
        for (i in 0..2) assertEquals(60.0, angle(v, i), 0.5)
    }

    @Test fun equilateralSnapsABaseToHorizontal() {
        // 밑변이 거의 수평이면 딱 수평이 되도록 돌린다
        val v = vertices(fit(ShapeKind.TRI_EQUILATERAL, closedStroke(sloppyTriangle))!!)
        val horizontal = v.indices.any { abs(v[it].second - v[(it + 1) % 3].second) < 0.01 }
        assertTrue(horizontal)
    }

    @Test fun rightTriangleHasExactRightAngle() {
        val drawn = listOf(100.0 to 100.0, 98.0 to 300.0, 320.0 to 303.0)   // 직각 근처
        val v = vertices(fit(ShapeKind.TRI_RIGHT, closedStroke(drawn))!!)
        assertTrue((0..2).any { abs(angle(v, it) - 90.0) < 0.01 })
    }

    @Test fun rightIsoscelesHasRightAngleAndEqualLegs() {
        val drawn = listOf(100.0 to 100.0, 95.0 to 300.0, 310.0 to 310.0)
        val v = vertices(fit(ShapeKind.TRI_RIGHT_ISOSCELES, closedStroke(drawn))!!)
        val corner = (0..2).first { abs(angle(v, it) - 90.0) < 0.01 }
        val l1 = dist(v[corner], v[(corner + 1) % 3]); val l2 = dist(v[corner], v[(corner + 2) % 3])
        assertEquals(l1, l2, 0.01)
        assertEquals(45.0, angle(v, (corner + 1) % 3), 0.01)
    }

    @Test fun plainTriangleStaysCloseToTheDrawnOne() {
        val v = vertices(fit(ShapeKind.TRIANGLE, closedStroke(sloppyTriangle))!!)
        for (d in sloppyTriangle) assertTrue("꼭짓점이 그린 곳 가까이", v.any { dist(it, d) < 12.0 })
    }

    // ================= 사각형 =================

    private val sloppyQuad = listOf(100.0 to 100.0, 305.0 to 108.0, 298.0 to 295.0, 96.0 to 288.0)

    @Test fun squareHasEqualSidesAndRightAngles() {
        val v = vertices(fit(ShapeKind.SQUARE, closedStroke(sloppyQuad))!!)
        assertEquals(4, v.size)
        val s = sides(v)
        for (i in 1..3) assertEquals(s[0], s[i], s[0] * 0.01)
        for (i in 0..3) assertEquals(90.0, angle(v, i), 0.5)
    }

    @Test fun rectangleHasRightAnglesAndPairedSides() {
        val drawn = listOf(100.0 to 100.0, 400.0 to 106.0, 396.0 to 240.0, 97.0 to 232.0)
        val v = vertices(fit(ShapeKind.RECTANGLE, closedStroke(drawn))!!)
        val s = sides(v)
        assertEquals(s[0], s[2], 0.5)
        assertEquals(s[1], s[3], 0.5)
        for (i in 0..3) assertEquals(90.0, angle(v, i), 0.5)
        // 정사각형이 아니다 (긴 변과 짧은 변)
        assertTrue(abs(s[0] - s[1]) > 50)
    }

    @Test fun rhombusHasEqualSidesButNotRightAngles() {
        val drawn = listOf(200.0 to 80.0, 320.0 to 200.0, 200.0 to 320.0, 80.0 to 200.0).let {
            // 납작한 마름모
            listOf(200.0 to 120.0, 350.0 to 200.0, 200.0 to 280.0, 50.0 to 200.0)
        }
        val v = vertices(fit(ShapeKind.RHOMBUS, closedStroke(drawn))!!)
        val s = sides(v)
        for (i in 1..3) assertEquals(s[0], s[i], s[0] * 0.01)
        assertTrue(abs(angle(v, 0) - 90.0) > 10)
    }

    @Test fun parallelogramHasParallelOppositeSides() {
        val drawn = listOf(150.0 to 100.0, 400.0 to 104.0, 330.0 to 250.0, 78.0 to 247.0)
        val v = vertices(fit(ShapeKind.PARALLELOGRAM, closedStroke(drawn))!!)
        // 마주 보는 두 변이 평행하고 길이가 같다: 대각선이 서로의 가운데에서 만난다
        val mid02 = (v[0].first + v[2].first) / 2 to (v[0].second + v[2].second) / 2
        val mid13 = (v[1].first + v[3].first) / 2 to (v[1].second + v[3].second) / 2
        assertEquals(0.0, dist(mid02, mid13), 0.01)
        val s = sides(v)
        assertEquals(s[0], s[2], 0.01)
        assertEquals(s[1], s[3], 0.01)
    }

    @Test fun flatScribbleIsNotAPolygon() {
        // 거의 한 줄을 오간 획은 삼각형도 사각형도 아니다
        val pts = listOf(100.0 to 100.0, 300.0 to 104.0, 100.0 to 108.0)
        assertNull(ShapeFit.fit(ShapeKind.TRIANGLE, stroke(pts, noise = 0.3)))
        assertNull(ShapeFit.fit(ShapeKind.QUADRILATERAL, stroke(pts, noise = 0.3)))
    }

    // ================= 계산 도우미 =================

    @Test fun resampleSpacesPointsEvenly() {
        // 천천히 그린 곳(점이 몰린 곳)이 있어도 길이 기준 고르게
        val st = Stroke(Tool.PEN, 0xFF000000.toInt(), 2f)
        for (i in 0..50) st.add(i * 0.2f, 0f, 1f)          // 10 길이를 점 51개로
        for (i in 1..4) st.add(10f + i * 22.5f, 0f, 1f)    // 90 길이를 점 4개로
        val (xs, ys) = ShapeFit.resample(st)!!
        val gaps = (1 until xs.size).map { hypot(xs[it] - xs[it - 1], ys[it] - ys[it - 1]) }
        assertEquals(gaps.average(), gaps.max(), gaps.average() * 0.05)
        assertEquals(0.0, xs.first(), 1e-6)
        assertEquals(100.0, xs.last(), 1e-3)
        assertNull("너무 짧으면 null", ShapeFit.resample(stroke(listOf(0.0 to 0.0, 2.0 to 0.0, 3.0 to 0.0))))
    }

    @Test fun solveGaussianAndSingular() {
        // 2x + y = 5, x + 3y = 10 → x = 1, y = 3
        val x = ShapeFit.solve(arrayOf(doubleArrayOf(2.0, 1.0), doubleArrayOf(1.0, 3.0)), doubleArrayOf(5.0, 10.0))!!
        assertEquals(1.0, x[0], 1e-9); assertEquals(3.0, x[1], 1e-9)
        // 행이 서로 비례하면 풀 수 없다
        assertNull(ShapeFit.solve(arrayOf(doubleArrayOf(1.0, 2.0), doubleArrayOf(2.0, 4.0)), doubleArrayOf(1.0, 2.0)))
        // 앞 열의 피벗이 0이어도 행을 바꿔 푼다
        val y = ShapeFit.solve(arrayOf(doubleArrayOf(0.0, 1.0), doubleArrayOf(1.0, 0.0)), doubleArrayOf(4.0, 7.0))!!
        assertEquals(7.0, y[0], 1e-9); assertEquals(4.0, y[1], 1e-9)
    }

    @Test fun reversePointsKeepsPairsTogether() {
        val r = ShapeFit.reversePoints(floatArrayOf(1f, 2f, 3f, 4f, 5f, 6f))
        assertEquals(listOf(5f, 6f, 3f, 4f, 1f, 2f), r.toList())
    }

    @Test fun findExtremaCountsOnlyClearBumps() {
        val n = 200
        val u = DoubleArray(n) { -1 + 2.0 * it / (n - 1) }
        // 삼차: 극점 둘 (x≈±0.58)
        val cubic = DoubleArray(n) { u[it] * u[it] * u[it] - 0.5 * u[it] }
        val e = ShapeFit.findExtrema(u, cubic)
        assertEquals(2, e.size)
        assertTrue(u[e[0]] < 0 && u[e[1]] > 0)
        // 포물선: 극점 하나
        assertEquals(1, ShapeFit.findExtrema(u, DoubleArray(n) { -u[it] * u[it] }).size)
        // 직선: 극점 없음, 거의 평평한 잔물결도 무시
        assertEquals(0, ShapeFit.findExtrema(u, DoubleArray(n) { u[it] }).size)
        assertEquals(0, ShapeFit.findExtrema(u, DoubleArray(n) { 0.02 * sin(u[it] * 40) + u[it] }).size)
        assertEquals(0, ShapeFit.findExtrema(u, DoubleArray(n) { 1.0 }).size)
    }

    @Test fun polygonRecoversCornersThatWereDrawnRounded() {
        // 모서리를 8씩 둥글게 깎아 그린 정사각형: 껍질의 꼭짓점은 참 모서리 안쪽에 있지만
        // 변마다 직선을 맞춰 교점을 구하면 참 모서리를 되찾는다
        val c = 8.0
        val pts = listOf(
            100.0 + c to 100.0, 300.0 - c to 100.0, 300.0 to 100.0 + c, 300.0 to 300.0 - c,
            300.0 - c to 300.0, 100.0 + c to 300.0, 100.0 to 300.0 - c, 100.0 to 100.0 + c, 100.0 + c to 100.0,
        )
        val v = ShapeFit.polygon(ShapeFit.resample(stroke(pts, noise = 0.2))!!, 4)!!.toList().chunked(2)
        for (corner in listOf(100.0 to 100.0, 300.0 to 100.0, 300.0 to 300.0, 100.0 to 300.0)) {
            assertTrue("참 모서리 $corner 가까이", v.any { hypot(it[0] - corner.first, it[1] - corner.second) < 2.5 })
        }
    }

    @Test fun polygonFindsCornersOfSloppyShapes() {
        val st = closedStroke(sloppyQuad, noise = 1.0)
        val p = ShapeFit.resample(st)!!
        val v = ShapeFit.polygon(p, 4)
        assertNotNull(v)
        for (d in sloppyQuad) assertTrue(v!!.toList().chunked(2).any { hypot(it[0] - d.first, it[1] - d.second) < 12.0 })
        // 점이 꼭짓점 수보다 적게 모이는 껍질이면 null: 직선
        assertNull(ShapeFit.polygon(ShapeFit.resample(stroke(listOf(0.0 to 0.0, 200.0 to 0.0)))!!, 3))
    }
}
