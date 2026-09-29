package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
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
}
