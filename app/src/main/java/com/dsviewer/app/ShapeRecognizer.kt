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
    class Result(val label: String, val fitted: Fitted, val drawn: DoubleArray? = null)

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
            gap <= diag * 0.3 -> anchored(closed(trimOverlap(p, diag), diag), xs[0], ys[0], diag)
            else -> null
        }
    }

    /**
     * 삼각형·사각형은 시작점을 움직이지 않는다: 시작점에 가장 가까운 꼭짓점을 시작점에 정확히 놓는다
     * (시작점이 그 꼭짓점에 붙은 짧은 변의 4분의 1 안이면. 모서리를 둥글게 시작해도 그 모서리로 본다).
     * 도형 전체를 그냥 옮기면 정확히 그린 다른 꼭짓점들이 같이 밀리므로, 시작점을 기준으로 도형을 돌리고 키우는 것(닮음 변환)으로
     * 나머지 꼭짓점이 그린 자리([Result.drawn])에 최소제곱으로 가장 가깝게 오도록 한다. 닮음 변환이라 직각·같은 변·평행 같은
     * 도형의 조건은 그대로다. 닮음 변환으로 맞출 수 없거나 시작점이 변 가운데쯤이면 도형을 옮겨서라도 시작점이 도형 위에 오게 한다.
     */
    private fun anchored(r: Result?, sx: Double, sy: Double, diag: Double): Result? {
        val drawn = r?.drawn ?: return r  // 별은 꼭지를 위로 세워 맞춘 자리를 지킨다 (그린 꼭짓점을 따로 두지 않는다)
        val c = r.fitted.curves.single()
        val m = c.size / 2 - 1  // 마지막 점은 첫 점의 되풀이
        if (drawn.size / 2 != m) return r
        fun shifted(dx: Double, dy: Double) = Result(r.label, Fitted(listOf(FloatArray(c.size) { (c[it] + if (it % 2 == 0) dx else dy).toFloat() })), drawn)
        var pin = -1
        var pinD = Double.MAX_VALUE
        for (i in 0 until m) {
            val d = hypot(c[i * 2] - sx, c[i * 2 + 1] - sy)
            if (d < pinD) { pinD = d; pin = i }
        }
        if (pin < 0) return r
        fun edge(a: Int, b: Int) = hypot((c[b * 2] - c[a * 2]).toDouble(), (c[b * 2 + 1] - c[a * 2 + 1]).toDouble())
        val shortEdge = minOf(edge(pin, (pin + 1) % m), edge(pin, (pin + m - 1) % m))
        if (pinD > shortEdge * 0.25) {
            // 변 가운데쯤에서 시작: 시작점에 가장 가까운 변 위의 점이 시작점이 되도록 도형을 옮긴다
            var bx = 0.0; var by = 0.0; var bd = Double.MAX_VALUE
            for (i in 0 until m) {
                val j = i + 1
                val ex = (c[j * 2] - c[i * 2]).toDouble(); val ey = (c[j * 2 + 1] - c[i * 2 + 1]).toDouble()
                val l2 = ex * ex + ey * ey
                val t = if (l2 < 1e-12) 0.0 else (((sx - c[i * 2]) * ex + (sy - c[i * 2 + 1]) * ey) / l2).coerceIn(0.0, 1.0)
                val qx = c[i * 2] + ex * t; val qy = c[i * 2 + 1] + ey * t
                val d = hypot(qx - sx, qy - sy)
                if (d < bd) { bd = d; bx = qx; by = qy }
            }
            return shifted(sx - bx, sy - by)
        }
        // 도형의 꼭짓점이 그린 꼭짓점 중 어느 것인지: 돌아가는 방향·시작 번호를 바꿔 가며 거리 합이 가장 작은 짝
        var match = IntArray(m) { it }
        var matchCost = Double.MAX_VALUE
        for (dir in intArrayOf(1, -1)) for (sh in 0 until m) {
            val map = IntArray(m) { ((dir * it + sh) % m + m) % m }
            var cost = 0.0
            for (i in 0 until m) cost += (c[i * 2] - drawn[map[i] * 2]).let { it * it } + (c[i * 2 + 1] - drawn[map[i] * 2 + 1]).let { it * it }
            if (cost < matchCost) { matchCost = cost; match = map }
        }
        // S 기준 w = Σ conj(a)·b / Σ|a|² (a: 도형의 꼭짓점, b: 그린 꼭짓점의 시작점 기준 위치) → S + w·a
        var wr = 0.0; var wi = 0.0; var den = 0.0
        for (i in 0 until m) {
            if (i == pin) continue
            val ax = c[i * 2] - c[pin * 2].toDouble(); val ay = c[i * 2 + 1] - c[pin * 2 + 1].toDouble()
            val bx = drawn[match[i] * 2] - sx; val by = drawn[match[i] * 2 + 1] - sy
            wr += ax * bx + ay * by; wi += ax * by - ay * bx; den += ax * ax + ay * ay
        }
        // 맞출 수 없으면 (꼭짓점이 겹쳐 있거나, 너무 많이 키우고 줄여야 하면) 꼭짓점만 시작점으로 옮긴다
        if (den < 1e-9) return shifted(sx - c[pin * 2], sy - c[pin * 2 + 1])
        wr /= den; wi /= den
        val scale = hypot(wr, wi)
        if (scale < 0.75 || scale > 1.33) return shifted(sx - c[pin * 2], sy - c[pin * 2 + 1])
        val out = FloatArray(c.size)
        for (i in 0..m) {
            val ax = c[i * 2] - c[pin * 2].toDouble(); val ay = c[i * 2 + 1] - c[pin * 2 + 1].toDouble()
            out[i * 2] = (sx + wr * ax - wi * ay).toFloat()
            out[i * 2 + 1] = (sy + wi * ax + wr * ay).toFloat()
        }
        return Result(r.label, Fitted(listOf(out)), drawn)
    }

    /**
     * 닫힌 도형을 그리다 시작점을 조금 지나쳐 그은 획: 끝점이 앞쪽 획 위에 얹혀 있으면 그 앞부분을 잘라
     * 끝점에서 시작해 끝점으로 돌아오는 한 바퀴로 만든다 (지나친 부분이 가짜 모서리로 잡히지 않게).
     */
    private fun trimOverlap(p: Pair<DoubleArray, DoubleArray>, diag: Double): Pair<DoubleArray, DoubleArray> {
        val (xs, ys) = p
        val n = xs.size
        val ex = xs[n - 1]; val ey = ys[n - 1]
        var bestJ = 0
        var bestD = Double.MAX_VALUE
        for (j in 0 until n / 4) {
            val d = hypot(xs[j] - ex, ys[j] - ey)
            if (d < bestD) { bestD = d; bestJ = j }
        }
        // 끝점이 앞쪽 획에 3% 안으로 붙어 있고 잘라 낼 만큼(점 3개 넘게) 지나쳤을 때만
        if (bestJ <= 2 || bestD > diag * 0.03) return p
        return xs.copyOfRange(bestJ, n) to ys.copyOfRange(bestJ, n)
    }

    // ---------------- 직선 ----------------

    /**
     * 직선: 그은 시작점과 끝점(펜을 멈춘 자리)을 그대로 잇는다. 선을 그을 때 양 끝은 겨냥한 자리라 거의 정확하기 때문이다.
     * 수평·수직·45°에 3° 안으로 가까우면 가운데를 축으로 살짝 돌려 딱 맞춘다.
     */
    private fun straight(p: Pair<DoubleArray, DoubleArray>): Result? {
        val (xs, ys) = p
        val n = xs.size
        val x0 = xs[0]; val y0 = ys[0]
        val dx = xs[n - 1] - x0; val dy = ys[n - 1] - y0
        val len = hypot(dx, dy)
        if (len < 3.0) return null
        var worst = 0.0
        for (i in xs.indices) worst = max(worst, abs((xs[i] - x0) * dy - (ys[i] - y0) * dx) / len)
        if (worst > len * 0.06) return null
        val th = atan2(dy, dx)
        val k = (th / (PI / 4)).roundToInt()
        val snapped = if (abs(th - k * (PI / 4)) <= 3 * DEG) k * (PI / 4) else th
        val mx = (x0 + xs[n - 1]) / 2; val my = (y0 + ys[n - 1]) / 2
        val hx = cos(snapped) * len / 2; val hy = sin(snapped) * len / 2
        val l = floatArrayOf((mx - hx).toFloat(), (my - hy).toFloat(), (mx + hx).toFloat(), (my + hy).toFloat())
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
            ang.any { abs(it - 90 * DEG) < 10 * DEG } -> {
                val ia = (0..2).minBy { abs(ang[it] - 90 * DEG) }
                // 직각 꼭짓점에 붙은 두 변이 거의 같으면 직각이등변
                val s1 = side(v, ia); val s2 = side(v, (ia + 2) % 3)
                if (max(s1, s2) / min(s1, s2) < 1.12) ShapeKind.TRI_RIGHT_ISOSCELES else ShapeKind.TRI_RIGHT
            }
            else -> ShapeKind.TRIANGLE
        }
        val curve = Polygons.triangle(kind, v, p, quick = true)
        return if (fits(p.first, p.second, curve, diag)) Result("삼각형", Fitted(listOf(curve)), v) else null
    }

    private fun quad(p: Pair<DoubleArray, DoubleArray>, diag: Double): Result? {
        val v = ShapeFit.polygon(p, 4) ?: return null
        val right = (0..3).all { abs(angleAt(v, it) - 90 * DEG) < 12 * DEG }
        val kind = if (!right) ShapeKind.QUADRILATERAL else {
            val s = (0..3).map { side(v, it) }
            if (s.max() / s.min() < 1.15) ShapeKind.SQUARE else ShapeKind.RECTANGLE
        }
        val curve = Polygons.quad(kind, v)
        return if (fits(p.first, p.second, curve, diag)) Result("사각형", Fitted(listOf(curve)), v) else null
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
