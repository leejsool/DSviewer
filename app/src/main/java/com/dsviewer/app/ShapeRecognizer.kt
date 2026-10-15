package com.dsviewer.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 일반 펜으로 도형을 그리고 꾹 누르고 있을 때, 그린 획이 어떤 도형인지 알아보고 반듯하게 맞춘다.
 * 알아보는 도형: 직선 · 원 · 타원 · 삼각형 · 사각형 · 별(오각별). 자주 쓰는 것만 보수적으로 알아보고,
 * 확실하지 않으면 null (그린 그대로 둔다). 화면을 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 *
 * 먼저 길이 기준으로 고르게 다시 뽑은 점에서 모서리(꺾이는 곳)를 세고, 그 수로 도형 후보를 정한 다음
 * 실제로 맞춘 도형이 그린 획과 충분히 가까운지(양방향) 다시 확인한다.
 */
internal object ShapeRecognizer {

    /** 알아본 도형: 이름과 맞춘 선 */
    class Result(val label: String, val fitted: Fitted)

    private const val DEG = PI / 180

    /** minSize: 그린 획이 (페이지 좌표로) 이보다 작으면 도형으로 보지 않는다 (글씨·점 보호) */
    fun recognize(st: Stroke, minSize: Float): Result? {
        val p = ShapeFit.resample(st) ?: return null
        val (xs, ys) = p
        val n = xs.size
        val diag = hypot(xs.max() - xs.min(), ys.max() - ys.min())
        if (diag < minSize) return null
        // 처음과 끝 사이가 그린 범위(대각선)만큼 벌어졌으면 곧은 선 후보, 거의 붙었으면 닫힌 도형 후보.
        // (획 길이로 재면 손떨림으로 길이가 부풀어 기준이 흔들린다)
        val gap = hypot(xs[n - 1] - xs[0], ys[n - 1] - ys[0])
        return when {
            gap >= diag * 0.9 -> straight(p)
            gap <= diag * 0.3 -> closed(p, diag)
            else -> null
        }
    }

    // ---------------- 직선 ----------------

    private fun straight(p: Pair<DoubleArray, DoubleArray>): Result? {
        val (xs, ys) = p
        val l = ShapeFit.line(p) ?: return null
        val x0 = l[0].toDouble(); val y0 = l[1].toDouble()
        val dx = l[2] - x0; val dy = l[3] - y0
        val len = hypot(dx, dy)
        if (len < 1.0) return null
        var worst = 0.0
        for (i in xs.indices) worst = max(worst, abs((xs[i] - x0) * dy - (ys[i] - y0) * dx) / len)
        if (worst > len * 0.06) return null
        return Result("직선", Fitted(listOf(l)))
    }

    // ---------------- 닫힌 도형 ----------------

    private fun closed(p: Pair<DoubleArray, DoubleArray>, diag: Double): Result? {
        val (xs, ys) = p
        val cs = corners(xs, ys)
        val k = cs.size
        val vx = DoubleArray(k) { xs[cs[it]] }
        val vy = DoubleArray(k) { ys[cs[it]] }
        // 꼭짓점마다 꺾인 각 (부호 있음). 합이 ±360°면 한 바퀴 도는 볼록 도형, ±720°면 두 바퀴 (오각별)
        val turns = DoubleArray(k) { i ->
            val a = (i - 1 + k) % k; val b = (i + 1) % k
            val ux = vx[i] - vx[a]; val uy = vy[i] - vy[a]
            val wx = vx[b] - vx[i]; val wy = vy[b] - vy[i]
            atan2(ux * wy - uy * wx, ux * wx + uy * wy)
        }
        val sum = turns.sum()
        return when (k) {
            0, 1, 2 -> round(p, diag)
            3 -> if (convex(turns, sum)) triangle(p, diag) else null
            4 -> if (convex(turns, sum)) quad(p, diag) else null
            5 -> if (abs(abs(sum) - 720 * DEG) < 100 * DEG && turns.all { it * sum > 0 && abs(abs(it) - 144 * DEG) < 40 * DEG }) {
                pentagram(xs, ys, vx, vy, sum, diag)
            } else null
            10 -> if (abs(abs(sum) - 360 * DEG) < 90 * DEG && (0 until 10).all { turns[it] * turns[(it + 1) % 10] < 0 }) {
                starOutline(xs, ys, vx, vy, diag)
            } else null
            else -> null
        }
    }

    /** 모든 꺾임이 같은 쪽이고 합이 한 바퀴 (360° ± 60°) */
    private fun convex(turns: DoubleArray, sum: Double) =
        abs(abs(sum) - 360 * DEG) < 60 * DEG && turns.all { it * sum > 0 }

    /**
     * 꺾이는 곳의 점 번호 (둘레 순서). 한 점 앞뒤로 획 길이의 3.5% 떨어진 두 점을 이은 방향이
     * 45° 넘게 바뀌는 곳 중 가장 뚜렷한 것부터 고르되, 가까이 붙은 것은 하나만.
     * 끝이 처음과 이어진 닫힌 획으로 보고 처음·끝을 넘나들며 잰다.
     */
    internal fun corners(xs: DoubleArray, ys: DoubleArray): List<Int> {
        val n = xs.size
        val w = max(3, (n * 0.035).roundToInt())
        val turn = DoubleArray(n) { i ->
            val a = (i - w + n) % n; val b = (i + w) % n
            val ux = xs[i] - xs[a]; val uy = ys[i] - ys[a]
            val vx = xs[b] - xs[i]; val vy = ys[b] - ys[i]
            atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        }
        val order = (0 until n).filter { abs(turn[it]) >= 45 * DEG }.sortedByDescending { abs(turn[it]) }
        val picked = ArrayList<Int>()
        for (i in order) {
            if (picked.all { j -> min(abs(i - j), n - abs(i - j)) >= w * 3 / 2 }) picked += i
        }
        return picked.sorted()
    }

    // ---------------- 원 · 타원 ----------------

    private fun round(p: Pair<DoubleArray, DoubleArray>, diag: Double): Result? {
        val (xs, ys) = p
        val mx = xs.average(); val my = ys.average()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        val half = (sxx + syy) / 2
        val disc = sqrt(max(0.0, half * half - (sxx * syy - sxy * sxy)))
        val big = half + disc
        val ratio = if (big <= 0) 0.0 else sqrt(max(0.0, half - disc) / big)
        // 거의 둥글면 원 (안 맞으면 타원), 아니면 타원
        if (ratio >= 0.88) {
            ShapeFit.circle(p)?.takeIf { fits(xs, ys, it, diag) }?.let { return Result("원", Fitted(listOf(it))) }
        }
        return ShapeFit.ellipse(p)?.takeIf { fits(xs, ys, it, diag) }?.let { Result("타원", Fitted(listOf(it))) }
    }

    // ---------------- 삼각형 · 사각형 ----------------

    private fun angleAt(v: DoubleArray, i: Int): Double {
        val k = v.size / 2
        val a = (i - 1 + k) % k; val b = (i + 1) % k
        val ux = v[a * 2] - v[i * 2]; val uy = v[a * 2 + 1] - v[i * 2 + 1]
        val wx = v[b * 2] - v[i * 2]; val wy = v[b * 2 + 1] - v[i * 2 + 1]
        return acos(((ux * wx + uy * wy) / (hypot(ux, uy) * hypot(wx, wy))).coerceIn(-1.0, 1.0))
    }

    private fun side(v: DoubleArray, i: Int): Double {
        val j = (i + 1) % (v.size / 2)
        return hypot(v[j * 2] - v[i * 2], v[j * 2 + 1] - v[i * 2 + 1])
    }

    private fun triangle(p: Pair<DoubleArray, DoubleArray>, diag: Double): Result? {
        val v = ShapeFit.polygon(p, 3) ?: return null
        val ang = DoubleArray(3) { angleAt(v, it) }
        val kind = when {
            ang.all { abs(it - 60 * DEG) < 10 * DEG } -> ShapeKind.TRI_EQUILATERAL
            ang.any { abs(it - 90 * DEG) < 8 * DEG } -> {
                val ia = (0..2).minBy { abs(ang[it] - 90 * DEG) }
                // 직각 꼭짓점에 붙은 두 변이 거의 같으면 직각이등변
                val s1 = side(v, ia); val s2 = side(v, (ia + 2) % 3)
                if (max(s1, s2) / min(s1, s2) < 1.12) ShapeKind.TRI_RIGHT_ISOSCELES else ShapeKind.TRI_RIGHT
            }
            else -> ShapeKind.TRIANGLE
        }
        val curve = Polygons.triangle(kind, v, p, quick = true)
        return if (fits(p.first, p.second, curve, diag)) Result("삼각형", Fitted(listOf(curve))) else null
    }

    private fun quad(p: Pair<DoubleArray, DoubleArray>, diag: Double): Result? {
        val v = ShapeFit.polygon(p, 4) ?: return null
        val right = (0..3).all { abs(angleAt(v, it) - 90 * DEG) < 12 * DEG }
        val kind = if (!right) ShapeKind.QUADRILATERAL else {
            val s = (0..3).map { side(v, it) }
            if (s.max() / s.min() < 1.15) ShapeKind.SQUARE else ShapeKind.RECTANGLE
        }
        val curve = Polygons.quad(kind, v)
        return if (fits(p.first, p.second, curve, diag)) Result("사각형", Fitted(listOf(curve))) else null
    }

    // ---------------- 별 ----------------

    /** 별의 꼭지 하나가 위를 향하도록 (8° 안이면) 딱 맞춘다. 꼭지는 72°마다 */
    private fun upright(th: Double): Double {
        val d = Math.IEEEremainder(-PI / 2 - th, 2 * PI / 5)
        return if (abs(d) <= 8 * DEG) th + d else th
    }

    /** 중심 둘레 방향 (+1/−1): 이어진 점들이 도는 쪽 */
    private fun spin(vx: DoubleArray, vy: DoubleArray, cx: Double, cy: Double): Double {
        var a = 0.0
        for (i in vx.indices) {
            val j = (i + 1) % vx.size
            a += (vx[i] - cx) * (vy[j] - cy) - (vx[j] - cx) * (vy[i] - cy)
        }
        return if (a >= 0) 1.0 else -1.0
    }

    /**
     * 정오각형 꼭짓점 5개를 [step]씩 돌려 가며 지난 것으로 보고, 중심·크기·돌림각을 최소제곱으로.
     * 반환: [중심x, 중심y, 반지름, 돌림각]
     */
    private fun regularFit(px: DoubleArray, py: DoubleArray, s: Double, step: Double): DoubleArray {
        val cx = px.average(); val cy = py.average()
        var wr = 0.0; var wi = 0.0
        for (i in px.indices) {
            val zx = px[i] - cx; val zy = py[i] - cy
            val a = -s * step * i
            wr += zx * cos(a) - zy * sin(a)
            wi += zx * sin(a) + zy * cos(a)
        }
        return doubleArrayOf(cx, cy, hypot(wr, wi) / px.size, atan2(wi, wr))
    }

    /** 한 번에 이어 그린 오각별 (꼭지 5개를 하나 건너 이음) */
    private fun pentagram(xs: DoubleArray, ys: DoubleArray, vx: DoubleArray, vy: DoubleArray, sum: Double, diag: Double): Result? {
        val cx = vx.average(); val cy = vy.average()
        val s = spin(vx, vy, cx, cy)
        val step = 4 * PI / 5
        val f = regularFit(vx, vy, s, step)
        val th = upright(f[3])
        val curve = FloatArray(12)
        for (i in 0..5) {
            val a = th + s * step * (i % 5)
            curve[i * 2] = (f[0] + f[2] * cos(a)).toFloat()
            curve[i * 2 + 1] = (f[1] + f[2] * sin(a)).toFloat()
        }
        return if (f[2] > 1 && fits(xs, ys, curve, diag)) Result("별", Fitted(listOf(curve))) else null
    }

    /** 바깥 둘레를 따라 그린 별 (꼭지 5개 + 오목한 곳 5개 = 모서리 10개) */
    private fun starOutline(xs: DoubleArray, ys: DoubleArray, vx: DoubleArray, vy: DoubleArray, diag: Double): Result? {
        val cx = vx.average(); val cy = vy.average()
        val r = DoubleArray(10) { hypot(vx[it] - cx, vy[it] - cy) }
        val evenFar = (0 until 10 step 2).sumOf { r[it] } > (1 until 10 step 2).sumOf { r[it] }
        val outer = (0 until 10).filter { (it % 2 == 0) == evenFar }
        val inner = (0 until 10).filter { (it % 2 == 0) != evenFar }
        val s = spin(vx, vy, cx, cy)
        val step = 2 * PI / 5
        val f = regularFit(DoubleArray(5) { vx[outer[it]] }, DoubleArray(5) { vy[outer[it]] }, s, step)
        if (f[2] <= 1) return null
        val ratio = (inner.sumOf { r[it] } / 5 / f[2]).coerceIn(0.35, 0.5)
        val th = upright(f[3])
        val curve = FloatArray(22)
        for (i in 0..10) {
            val j = i % 10
            val a = th + s * step * j / 2
            val rr = if (j % 2 == 0) f[2] else f[2] * ratio
            curve[i * 2] = (f[0] + rr * cos(a)).toFloat()
            curve[i * 2 + 1] = (f[1] + rr * sin(a)).toFloat()
        }
        return if (fits(xs, ys, curve, diag)) Result("별", Fitted(listOf(curve))) else null
    }

    // ---------------- 맞춘 도형이 그린 획과 가까운지 ----------------

    private fun segDist(x: Double, y: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val ex = bx - ax; val ey = by - ay
        val l2 = ex * ex + ey * ey
        val t = if (l2 < 1e-12) 0.0 else (((x - ax) * ex + (y - ay) * ey) / l2).coerceIn(0.0, 1.0)
        return hypot(ax + ex * t - x, ay + ey * t - y)
    }

    /**
     * 그린 점에서 도형까지(평균 4.5%, 가장 먼 것 16%)와 도형 위의 점에서 그린 획까지(평균 5%)의
     * 거리가 모두 도형 크기(대각선) 대비 이 안이면 그린 것과 같은 도형으로 본다.
     * 뒤쪽 검사는 그리지 않은 쪽까지 도형이 뻗은 경우(획이 도형의 일부만 그린 경우)를 걸러 낸다.
     */
    private fun fits(xs: DoubleArray, ys: DoubleArray, curve: FloatArray, diag: Double): Boolean {
        val m = curve.size / 2
        var sum = 0.0
        var worst = 0.0
        for (i in xs.indices) {
            var best = Double.MAX_VALUE
            for (e in 0 until m - 1) {
                best = min(best, segDist(xs[i], ys[i], curve[e * 2].toDouble(), curve[e * 2 + 1].toDouble(), curve[e * 2 + 2].toDouble(), curve[e * 2 + 3].toDouble()))
            }
            sum += best
            worst = max(worst, best)
        }
        if (sum / xs.size > 0.045 * diag || worst > 0.16 * diag) return false
        val step = diag / 40
        var back = 0.0
        var cnt = 0
        for (e in 0 until m - 1) {
            val ax = curve[e * 2].toDouble(); val ay = curve[e * 2 + 1].toDouble()
            val bx = curve[e * 2 + 2].toDouble(); val by = curve[e * 2 + 3].toDouble()
            val q = max(1, ceil(hypot(bx - ax, by - ay) / step).toInt())
            for (j in 0 until q) {
                val t = (j + 0.5) / q
                val x = ax + (bx - ax) * t; val y = ay + (by - ay) * t
                var best = Double.MAX_VALUE
                for (i in xs.indices) best = min(best, hypot(xs[i] - x, ys[i] - y))
                back += best
                cnt++
            }
        }
        return cnt > 0 && back / cnt <= 0.05 * diag
    }
}
