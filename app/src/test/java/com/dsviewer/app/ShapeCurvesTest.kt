package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan
import kotlin.random.Random

/**
 * 손으로 그린 것처럼 흔들린 곡선을 보정해서 원래 곡선과 얼마나 가까운지 본다.
 * 좌표는 페이지 좌표(포인트, y는 아래로).
 */
class ShapeCurvesTest {

    /** 참 곡선을 촘촘히 뽑은 뒤 길이 기준 고르게 다시 뽑고 흔든다 (ShapeFit.resample과 같은 모양의 입력) */
    private fun drawn(n: Int = 300, noise: Double = 1.5, seed: Int = 1, f: (Double) -> Pair<Double, Double>): Pair<DoubleArray, DoubleArray> {
        val dense = (0..4000).map { f(it / 4000.0) }
        val cum = DoubleArray(dense.size)
        for (i in 1 until dense.size) cum[i] = cum[i - 1] + hypot(dense[i].first - dense[i - 1].first, dense[i].second - dense[i - 1].second)
        val rnd = Random(seed)
        val xs = DoubleArray(n); val ys = DoubleArray(n)
        var j = 0
        for (k in 0 until n) {
            val target = cum.last() * k / (n - 1)
            while (j < dense.size - 1 && cum[j + 1] < target) j++
            xs[k] = dense[j].first + (rnd.nextDouble() - 0.5) * 2 * noise
            ys[k] = dense[j].second + (rnd.nextDouble() - 0.5) * 2 * noise
        }
        return xs to ys
    }

    /** 보정한 곡선의 점들이 참 곡선에서 가장 멀리 떨어진 거리 */
    private fun maxDist(curve: FloatArray, truth: (Double) -> Pair<Double, Double>): Double {
        val dense = (0..8000).map { truth(it / 8000.0) }
        var worst = 0.0
        for (i in 0 until curve.size / 2) {
            val x = curve[i * 2].toDouble(); val y = curve[i * 2 + 1].toDouble()
            var best = Double.MAX_VALUE
            for ((tx, ty) in dense) best = min(best, hypot(x - tx, y - ty))
            worst = max(worst, best)
        }
        return worst
    }

    private fun check(name: String, fitted: Fitted?, tol: Double, truth: (Double) -> Pair<Double, Double>): Fitted {
        assertNotNull("$name: 맞추지 못함", fitted)
        val d = maxDist(fitted!!.curves[0], truth)
        println("$name: 최대 거리 %.2f pt, 곡선 ${fitted.curves.size}개, 보조선 ${fitted.guides.size}개".format(d))
        assertTrue("$name: 너무 멂 ($d)", d < tol)
        return fitted
    }

    @Test fun exponential() {
        // y = 20·e^((x−250)/60) + 50 (위쪽이 +), x 100~400. 점근선 y = 50 → 페이지 y = 450
        val truth = { s: Double -> val x = 100 + 300 * s; x to 500 - (20 * exp((x - 250) / 60) + 50) }
        val f = check("지수", ShapeCurves.expOrLog(drawn(f = truth)), 6.0, truth)
        val a = f.guides.single()
        assertEquals(450.0, a[1].toDouble(), 6.0)
        assertEquals(450.0, a[3].toDouble(), 6.0)
    }

    @Test fun exponentialDecreasingDrawnRightToLeft() {
        val truth = { s: Double -> val x = 420 - 300 * s; x to 300 - 150 * exp(-(x - 120) / 70) }
        check("감소 지수(오른쪽→왼쪽)", ShapeCurves.expOrLog(drawn(f = truth, seed = 2)), 6.0, truth)
    }

    @Test fun log() {
        // y = 40·ln(x − 100), x 103~400. 점근선 x = 100
        val truth = { s: Double -> val x = 103 + 297 * s * s; x to 400 - 40 * ln(x - 100) }
        val f = check("로그", ShapeCurves.expOrLog(drawn(f = truth)), 8.0, truth)
        println("로그 점근선 x = ${f.guides.map { it[0] }} (참값 100)")
        val a = f.guides.single()
        assertEquals(100.0, a[0].toDouble(), 6.0)
        assertEquals(100.0, a[2].toDouble(), 6.0)
    }

    /** 여러 모양의 지수·로그를 그려서 알맞은 쪽(점근선이 가로/세로)으로 고르는지 */
    @Test fun expOrLogPicksRightKind() {
        val cases = listOf(
            // 이름, 가로 점근선이어야 하나, 곡선
            Triple("증가 지수", true) { s: Double -> val x = 100 + 300 * s; x to 500 - (15 * exp((x - 100) / 100) + 40) },
            Triple("감소 지수", true) { s: Double -> val x = 100 + 300 * s; x to 500 - (200 * exp(-(x - 100) / 60) + 30) },
            Triple("밑이 작은 지수", true) { s: Double -> val x = 100 + 300 * s; x to 500 - (40 * exp((x - 100) / 150) + 20) },
            Triple("증가 로그", false) { s: Double -> val x = 104 + 296 * s * s; x to 400 - 40 * ln(x - 100) },
            Triple("감소 로그", false) { s: Double -> val x = 104 + 296 * s * s; x to 200 + 40 * ln(x - 100) },
            Triple("왼쪽 로그", false) { s: Double -> val x = 396 - 296 * s * s; x to 400 - 50 * ln(400 - x) },
        )
        for ((i, c) in cases.withIndex()) {
            val (name, horizontal, truth) = c
            val f = ShapeCurves.expOrLog(drawn(f = truth, seed = 10 + i))
            assertNotNull("$name: 맞추지 못함", f)
            val a = f!!.guides.single()
            val isHorizontal = abs(a[1] - a[3]) < abs(a[0] - a[2])
            println("$name: ${if (isHorizontal) "지수(가로 점근선)" else "로그(세로 점근선)"}, 최대 거리 %.2f".format(maxDist(f.curves[0], truth)))
            assertEquals("$name: 종류를 잘못 고름", horizontal, isHorizontal)
            assertTrue(maxDist(f.curves[0], truth) < 8)
        }
    }

    @Test fun sine() {
        // 두 주기: y = 60·sin(2π(x−100)/200)
        val truth = { s: Double -> val x = 100 + 400 * s; x to 300 - 60 * sin(2 * PI * (x - 100) / 200) }
        val f = check("사인", ShapeCurves.sine(drawn(f = truth)), 6.0, truth)
        // 축은 가운데 가로선 y = 300, 그린 폭(100~500)보다 조금 길게
        val axis = f.guides.single()
        assertEquals(300.0, axis[1].toDouble(), 3.0)
        assertEquals(300.0, axis[3].toDouble(), 3.0)
        assertTrue(axis[0] < 100 && axis[2] > 500)
    }

    @Test fun sineHalfPeriod() {
        // 반 주기만 (코사인 꼭대기 하나)
        val truth = { s: Double -> val x = 100 + 300 * s; x to 300 - 80 * sin(PI * (x - 100) / 300) }
        check("사인 반 주기", ShapeCurves.sine(drawn(f = truth, seed = 3)), 6.0, truth)
    }

    @Test fun tangent() {
        // y = 30·tan(π(x−300)/440), x 205~395. 점근선 x = 80, 520
        val truth = { s: Double -> val x = 205 + 190 * s; x to 300 - 30 * tan(PI * (x - 300) / 440) }
        val f = check("탄젠트", ShapeCurves.tangent(drawn(f = truth)), 8.0, truth)
        val xs = f.guides.map { it[0].toDouble() }.sorted()
        println("탄젠트 점근선 x = $xs (참값 80, 520)")
    }

    @Test fun tangentSteep() {
        // 점근선 가까이까지 그린 가파른 가지: y = 20·tan(π(x−300)/240), x 190~410, 점근선 x = 180, 420
        val truth = { s: Double -> val x = 190 + 220 * s; x to 300 - 20 * tan(PI * (x - 300) / 240) }
        val f = check("가파른 탄젠트", ShapeCurves.tangent(drawn(f = truth, seed = 4)), 8.0, truth)
        val xs = f.guides.map { it[0].toDouble() }.sorted()
        println("가파른 탄젠트 점근선 x = $xs (참값 180, 420)")
        assertEquals(180.0, xs[0], 10.0)
        assertEquals(420.0, xs[1], 10.0)
    }

    @Test fun hyperbolaLeftRight() {
        // (x−300)²/60² − (y−300)²/40² = 1 의 오른쪽 가지
        val truth = { s: Double -> val t = -1.6 + 3.2 * s; (300 + 60 * cosh(t)) to (300 + 40 * sinh(t)) }
        val f = check("쌍곡선 좌우", ShapeCurves.hyperbola(drawn(f = truth)), 6.0, truth)
        assertEquals(2, f.curves.size)
        // 반대쪽 가지는 왼쪽
        val other = f.curves[1]
        val mid = other.size / 4 * 2
        assertTrue(other[mid] < 300)
        val mirror = { s: Double -> val t = -1.6 + 3.2 * s; (300 - 60 * cosh(t)) to (300 + 40 * sinh(t)) }
        println("반대쪽 가지 거리 %.2f".format(maxDist(other, mirror)))
        assertTrue(maxDist(other, mirror) < 8)
        // 점근선 기울기 ±40/60
        for (a in f.guides) {
            val slope = abs((a[3] - a[1]) / (a[2] - a[0]).toDouble())
            assertEquals(40.0 / 60, slope, 0.12)
        }
    }

    @Test fun hyperbolaUpDown() {
        // 위아래로 열린 아래쪽 가지 (페이지에서 아래 = y 큼)
        val truth = { s: Double -> val t = -1.4 + 2.8 * s; (300 + 50 * sinh(t)) to (300 + 45 * cosh(t)) }
        val f = check("쌍곡선 위아래", ShapeCurves.hyperbola(drawn(f = truth, seed = 5)), 6.0, truth)
        assertEquals(2, f.curves.size)
    }

    // ----- 이차 × 지수 -----

    @Test fun quadExp() {
        // y = 50·t²·eᵗ, t = (x − 300)/40, x 60~340 (t −6~1). 점근선 y = 0 → 페이지 y = 400
        val truth = { s: Double -> val x = 60 + 280 * s; val t = (x - 300) / 40; x to 400 - 50 * t * t * exp(t) }
        val f = check("이차×지수", ShapeCurves.quadExp(drawn(f = truth, seed = 21)), 6.0, truth)
        val a = f.guides.single()
        println("이차×지수 점근선 y = ${a[1]} (참값 400)")
        assertEquals(400.0, a[1].toDouble(), 6.0)
    }

    @Test fun quadExpMirroredDrawnRightToLeft() {
        // 왼쪽이 발산, 오른쪽이 점근선: y = −30·(t² + 0.5t − 1)·e^(−t) + 50, 오른쪽에서 왼쪽으로
        val truth = { s: Double -> val x = 420 - 300 * s; val t = (x - 200) / 50; x to 250 + 30 * (t * t + 0.5 * t - 1) * exp(-t) }
        check("거꾸로 이차×지수", ShapeCurves.quadExp(drawn(f = truth, seed = 22)), 6.0, truth)
    }

    @Test fun quadExpExaggerated() {
        // 칠판식: 극대를 크게 부풀리고 극소는 점근선에 닿게, 오른쪽은 가파르게 (진짜 함수가 아닌 그림)
        val truth = { s: Double ->
            val x = 80 + 300 * s
            val hump = 70 * exp(-((x - 200) / 40).let { it * it })
            val rise = 8 * exp((x - 300) / 22)
            x to 400 - (hump + rise)
        }
        val f = ShapeCurves.quadExp(drawn(f = truth, seed = 23))
        assertNotNull(f)
        val d = maxDist(f!!.curves[0], truth)
        val back = coverDist(f.curves[0], truth)
        println("부풀린 이차×지수: 보정 → 그린 것 %.2f, 그린 것 → 보정 %.2f".format(d, back))
        assertTrue("부풀린 이차×지수: 너무 멂 ($d, $back)", d < 15 && back < 15)
    }

    // ----- 부채꼴 -----

    /** 중심 (cx, cy), 반지름 r, 각 a1 → a2 (라디안, 페이지 좌표). fromCenter면 중심 → 호 → 중심, 아니면 호 끝 → 중심 → 호 끝 → 호 */
    private fun sectorPath(cx: Double, cy: Double, r: Double, a1: Double, a2: Double, fromCenter: Boolean): (Double) -> Pair<Double, Double> {
        val arcLen = r * abs(a2 - a1)
        val total = 2 * r + arcLen
        return { s ->
            val d = s * total
            if (fromCenter) when {
                d < r -> (cx + d * cos(a1)) to (cy + d * sin(a1))
                d < r + arcLen -> { val a = a1 + (a2 - a1) * (d - r) / arcLen; (cx + r * cos(a)) to (cy + r * sin(a)) }
                else -> { val t = r - (d - r - arcLen); (cx + t * cos(a2)) to (cy + t * sin(a2)) }
            } else when {
                d < r -> { val t = r - d; (cx + t * cos(a1)) to (cy + t * sin(a1)) }
                d < 2 * r -> { val t = d - r; (cx + t * cos(a2)) to (cy + t * sin(a2)) }
                else -> { val a = a2 + (a1 - a2) * (d - 2 * r) / arcLen; (cx + r * cos(a)) to (cy + r * sin(a)) }
            }
        }
    }

    @Test fun sectors() {
        val cases = listOf(
            "60° 중심부터" to sectorPath(300.0, 300.0, 150.0, -0.3, -0.3 - PI / 3, true),
            "90° 호 끝부터" to sectorPath(250.0, 350.0, 180.0, 0.0, -PI / 2, false),
            "130° 중심부터" to sectorPath(300.0, 300.0, 120.0, 0.4, 0.4 + 130 * PI / 180, true),
            "반원" to sectorPath(300.0, 300.0, 140.0, 0.0, -PI, true),
            "270° 호 끝부터" to sectorPath(300.0, 300.0, 100.0, 0.0, -1.5 * PI, false),
            "30° 뾰족" to sectorPath(150.0, 400.0, 200.0, -0.2, -0.2 - PI / 6, true),
        )
        for ((i, c) in cases.withIndex()) {
            val (name, truth) = c
            val f = ShapeCurves.sector(drawn(f = truth, seed = 30 + i))
            assertNotNull("$name: 맞추지 못함", f)
            val d = maxDist(densify(f!!.curves[0]), truth)
            val back = coverDist(f.curves[0], truth)
            println("부채꼴 $name: 보정 → 그린 것 %.2f, 그린 것 → 보정 %.2f".format(d, back))
            assertTrue("$name: 너무 멂 ($d, $back)", d < 6 && back < 6)
        }
    }

    /** 반원: 지름 끝 a에서 시작. arcFirst면 호 → 지름, 아니면 지름 → 호 */
    private fun semiPath(cx: Double, cy: Double, r: Double, a: Double, arcFirst: Boolean): (Double) -> Pair<Double, Double> {
        val arcLen = PI * r
        val total = arcLen + 2 * r
        return { s ->
            val d = s * total
            if (arcFirst) {
                if (d < arcLen) { val t = a + PI * d / arcLen; (cx + r * cos(t)) to (cy + r * sin(t)) }
                else { val u = (d - arcLen) / (2 * r); val bx = cx - r * cos(a); val by = cy - r * sin(a)
                    (bx + (cx + r * cos(a) - bx) * u) to (by + (cy + r * sin(a) - by) * u) }
            } else {
                if (d < 2 * r) { val u = d / (2 * r); val ax = cx + r * cos(a); val ay = cy + r * sin(a)
                    (ax + (cx - r * cos(a) - ax) * u) to (ay + (cy - r * sin(a) - ay) * u) }
                else { val t = a + PI - PI * (d - 2 * r) / arcLen; (cx + r * cos(t)) to (cy + r * sin(t)) }
            }
        }
    }

    @Test fun semicircles() {
        val cases = listOf(
            "위로 볼록, 호부터" to semiPath(300.0, 300.0, 150.0, PI, true),
            "아래로 볼록, 지름부터" to semiPath(300.0, 300.0, 120.0, 0.0, false),
            "기운 반원" to semiPath(250.0, 350.0, 100.0, 0.7, true),
            "왼쪽으로 볼록" to semiPath(300.0, 300.0, 130.0, PI / 2, false),
        )
        for ((i, c) in cases.withIndex()) {
            val (name, truth) = c
            val f = ShapeCurves.semicircle(drawn(f = truth, seed = 60 + i))
            assertNotNull("$name: 맞추지 못함", f)
            val d = maxDist(densify(f!!.curves[0]), truth)
            val back = coverDist(f.curves[0], truth)
            println("반원 $name: 보정 → 그린 것 %.2f, 그린 것 → 보정 %.2f".format(d, back))
            assertTrue("$name: 너무 멂 ($d, $back)", d < 6 && back < 6)
        }
    }

    /** 꺾은선 점들 사이를 채운다 (꼭짓점만 보면 긴 선분 가운데가 어긋난 것을 놓침) */
    private fun densify(a: FloatArray): FloatArray {
        val out = ArrayList<Float>()
        for (i in 0 until a.size / 2 - 1) for (j in 0 until 10) {
            val t = j / 10f
            out += a[i * 2] + (a[i * 2 + 2] - a[i * 2]) * t
            out += a[i * 2 + 1] + (a[i * 2 + 3] - a[i * 2 + 1]) * t
        }
        return out.toFloatArray()
    }

    /** 참 곡선의 점들이 보정한 꺾은선에서 가장 멀리 떨어진 거리 */
    private fun coverDist(c: FloatArray, truth: (Double) -> Pair<Double, Double>): Double {
        var worst = 0.0
        for (k in 0..400) {
            val (x, y) = truth(k / 400.0)
            var best = Double.MAX_VALUE
            for (i in 0 until c.size / 2 - 1) {
                val ax = c[i * 2].toDouble(); val ay = c[i * 2 + 1].toDouble()
                val ex = c[i * 2 + 2] - ax; val ey = c[i * 2 + 3] - ay
                val l2 = ex * ex + ey * ey
                val t = if (l2 < 1e-12) 0.0 else (((x - ax) * ex + (y - ay) * ey) / l2).coerceIn(0.0, 1.0)
                best = min(best, hypot(ax + ex * t - x, ay + ey * t - y))
            }
            worst = max(worst, best)
        }
        return worst
    }

    // ----- 이등변삼각형 -----

    /** 꼭짓점을 잇되 모서리를 둥글게 깎은 닫힌 획 (손으로 그린 것처럼) */
    private fun roundedPolygon(vs: List<Pair<Double, Double>>, cut: Double): (Double) -> Pair<Double, Double> {
        val pts = ArrayList<Pair<Double, Double>>()
        val k = vs.size
        for (i in 0..k) {
            val p = vs[i % k]; val prev = vs[(i - 1 + k) % k]; val next = vs[(i + 1) % k]
            fun toward(q: Pair<Double, Double>, t: Double) = (p.first + (q.first - p.first) * t) to (p.second + (q.second - p.second) * t)
            val a = toward(prev, cut); val b = toward(next, cut)
            for (j in 0..8) {
                val t = j / 8.0
                // 2차 베지어 a → p → b
                val x = (1 - t) * (1 - t) * a.first + 2 * (1 - t) * t * p.first + t * t * b.first
                val y = (1 - t) * (1 - t) * a.second + 2 * (1 - t) * t * p.second + t * t * b.second
                pts += x to y
            }
        }
        val cum = DoubleArray(pts.size)
        for (i in 1 until pts.size) cum[i] = cum[i - 1] + hypot(pts[i].first - pts[i - 1].first, pts[i].second - pts[i - 1].second)
        return { s ->
            val d = s * cum.last()
            var i = 1
            while (i < pts.size - 1 && cum[i] < d) i++
            val t = if (cum[i] > cum[i - 1]) (d - cum[i - 1]) / (cum[i] - cum[i - 1]) else 0.0
            (pts[i - 1].first + (pts[i].first - pts[i - 1].first) * t) to (pts[i - 1].second + (pts[i].second - pts[i - 1].second) * t)
        }
    }

    @Test fun isoscelesFollowsDrawing() {
        val cases = listOf(
            // 이름, 대충 그린 꼭짓점 (꼭대기, 밑변 둘)
            "꼭대기가 오른쪽으로 치우침" to listOf(330.0 to 100.0, 200.0 to 320.0, 410.0 to 316.0),
            // 두 변(237, 232)이 만나는 왼쪽 아래가 꼭대기인, 기울어진 이등변
            "기울어진 이등변" to listOf(190.0 to 300.0, 420.0 to 330.0, 300.0 to 90.0),
            "납작한 이등변" to listOf(300.0 to 240.0, 120.0 to 310.0, 470.0 to 305.0),
            "뾰족한 이등변" to listOf(290.0 to 60.0, 250.0 to 360.0, 345.0 to 362.0),
            "옆으로 누운 이등변" to listOf(100.0 to 300.0, 380.0 to 220.0, 370.0 to 390.0),
        )
        for ((i, c) in cases.withIndex()) {
            val (name, vs) = c
            val truth = roundedPolygon(vs, 0.12)
            val p = drawn(f = truth, seed = 40 + i)
            val v = ShapeFit.polygon(p, 3)
            assertNotNull("$name: 꼭짓점을 못 찾음", v)
            val out = Polygons.triangle(ShapeKind.TRI_ISOSCELES, v!!, p)
            // 두 변이 같은지
            val q = List(3) { out[it * 2].toDouble() to out[it * 2 + 1].toDouble() }
            val l = List(3) { hypot(q[(it + 1) % 3].first - q[it].first, q[(it + 1) % 3].second - q[it].second) }.sorted()
            val equal = min(abs(l[0] - l[1]), abs(l[1] - l[2]))
            val d = maxDist(densify(out), truth)
            val back = coverDist(out, truth)
            println("이등변 $name: 보정 → 그린 것 %.1f, 그린 것 → 보정 %.1f, 변 ${l.map { "%.0f".format(it) }}".format(d, back))
            assertTrue("$name: 두 변이 같지 않음", equal < 0.5)
            assertTrue("$name: 그린 것과 너무 다름 ($d, $back)", d < 25 && back < 25)
            // 꼭대기를 제대로 골랐는지: 꼭대기(그린 첫 꼭짓점)에 가장 가까운 보정 꼭짓점이 같은 두 변 사이에 있어야
            val apex = (0..2).minBy { hypot(q[it].first - vs[0].first, q[it].second - vs[0].second) }
            val la = hypot(q[(apex + 1) % 3].first - q[apex].first, q[(apex + 1) % 3].second - q[apex].second)
            val lb = hypot(q[(apex + 2) % 3].first - q[apex].first, q[(apex + 2) % 3].second - q[apex].second)
            assertTrue("$name: 꼭대기를 잘못 고름", abs(la - lb) < 0.5)
        }
    }

    /** 정삼각형에 가깝게 대충 그린 이등변은 밑변이 수평이 되는 꼭대기를 고른다 */
    @Test fun isoscelesNearEquilateralPrefersLevelBase() {
        val vs = listOf(300.0 to 110.0, 200.0 to 285.0, 402.0 to 283.0)
        val p = drawn(f = roundedPolygon(vs, 0.12), seed = 50)
        val out = Polygons.triangle(ShapeKind.TRI_ISOSCELES, ShapeFit.polygon(p, 3)!!, p)
        val ys = List(3) { out[it * 2 + 1].toDouble() }.sorted()
        println("정삼각형 같은 이등변: y = $ys")
        assertEquals("밑변이 수평이 아님", ys[1], ys[2], 0.5)
    }
}
