package com.dsviewer.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * 기기에서 오래 다듬는 보정 펜 도형(부채꼴·반원·이차×지수·이등변삼각형)이 얼마나 걸리는지.
 * 미리 보기(quick)는 그리는 동안 따로 도는 스레드에서, 마지막 맞추기는 펜을 뗄 때 화면 스레드에서 돈다.
 * 실행: gradlew installDebug installDebugAndroidTest 후
 *   adb shell am instrument -w -e class com.dsviewer.app.ShapeTimingTest com.dsviewer.app.test/androidx.test.runner.AndroidJUnitRunner
 * 결과는 logcat 태그 ShapeTiming
 */
@RunWith(AndroidJUnit4::class)
class ShapeTimingTest {

    private fun drawn(n: Int, seed: Int, f: (Double) -> Pair<Double, Double>): Pair<DoubleArray, DoubleArray> {
        val rnd = Random(seed)
        val xs = DoubleArray(n); val ys = DoubleArray(n)
        for (k in 0 until n) {
            val (x, y) = f(k / (n - 1.0))
            xs[k] = x + (rnd.nextDouble() - 0.5) * 3; ys[k] = y + (rnd.nextDouble() - 0.5) * 3
        }
        return xs to ys
    }

    private fun time(block: () -> Unit): Double {
        val t0 = System.nanoTime(); block(); return (System.nanoTime() - t0) / 1e6
    }

    @Test fun timings() {
        val r = 150.0; val a1 = -0.3; val a2 = -0.3 - PI / 3
        val arcLen = r * abs(a2 - a1); val total = 2 * r + arcLen
        val sector = drawn(400, 1) { s ->
            val d = s * total
            when {
                d < r -> (300 + d * cos(a1)) to (300 + d * sin(a1))
                d < r + arcLen -> { val a = a1 + (a2 - a1) * (d - r) / arcLen; (300 + r * cos(a)) to (300 + r * sin(a)) }
                else -> { val t = r - (d - r - arcLen); (300 + t * cos(a2)) to (300 + t * sin(a2)) }
            }
        }
        val qe = drawn(400, 2) { s -> val x = 60 + 280 * s; val t = (x - 300) / 40; x to 400 - 50 * t * t * exp(t) }
        val tri = drawn(400, 3) { s ->
            val vs = listOf(300.0 to 100.0, 190.0 to 300.0, 410.0 to 300.0)
            val e = (s * 3).toInt().coerceAtMost(2); val t = s * 3 - e
            val a = vs[e]; val b = vs[(e + 1) % 3]
            (a.first + (b.first - a.first) * t) to (a.second + (b.second - a.second) * t)
        }
        var worstFull = 0.0
        repeat(4) { round ->
            val sf = time { ShapeCurves.sector(sector) }
            val sq = time { ShapeCurves.sector(sector, true) }
            val hf = time { ShapeCurves.semicircle(sector) }
            val hq = time { ShapeCurves.semicircle(sector, true) }
            val qf = time { ShapeCurves.quadExp(qe) }
            val qq = time { ShapeCurves.quadExp(qe, true) }
            val tf = time { Polygons.triangle(ShapeKind.TRI_ISOSCELES, ShapeFit.polygon(tri, 3)!!, tri) }
            val tq = time { Polygons.triangle(ShapeKind.TRI_ISOSCELES, ShapeFit.polygon(tri, 3)!!, tri, true) }
            Log.i("ShapeTiming", "round $round: 부채꼴 %.0f/%.0f ms, 반원 %.0f/%.0f ms, 이차×지수 %.0f/%.0f ms, 이등변 %.0f/%.0f ms (전체/미리 보기)"
                .format(sf, sq, hf, hq, qf, qq, tf, tq))
            if (round > 0) worstFull = maxOf(worstFull, maxOf(sf, hf, qf, tf))
        }
        // 펜을 뗄 때 화면 스레드에서 도는 마지막 맞추기가 눈에 띄게 멈추지 않을 것
        assertTrue("마지막 맞추기가 너무 느림 ($worstFull ms)", worstFull < 300)
        assertTrue(hypot(1.0, 1.0) > 0)
    }
}
