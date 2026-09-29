package com.dsviewer.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 보정 펜의 지수·로그·사인·탄젠트·쌍곡선 맞추기.
 * 곡선 모양을 정하는 모수(지수의 k, 사인의 ω, 탄젠트의 중심·주기)는 훑어서 고르고,
 * 나머지 계수(높이·이동)는 최소제곱으로 푼다. 가파른 곳의 세로 오차가 결과를 끌고 가지 않도록
 * 기울기로 가중해 '곡선까지의 거리'에 가깝게 잰다.
 * 좌표는 페이지 좌표(y는 아래로). 함수는 종이 가로를 x축, 위쪽을 y축으로 본다.
 */
internal object ShapeCurves {

    /** 함수 표본. 가로축 t와 세로축 s를 같은 배율 sc로 줄인 u, v (u는 [-1, 1]) */
    private class Norm(val u: DoubleArray, val v: DoubleArray, val tm: Double, val sm: Double, val sc: Double) {
        val vmin = v.min()
        val vmax = v.max()
    }

    private fun norm(t: DoubleArray, s: DoubleArray): Norm? {
        val tmin = t.min(); val tmax = t.max()
        if (tmax - tmin < 4) return null
        val tm = (tmin + tmax) / 2; val sc = (tmax - tmin) / 2
        val sm = s.average()
        return Norm(DoubleArray(t.size) { (t[it] - tm) / sc }, DoubleArray(s.size) { (s[it] - sm) / sc }, tm, sm, sc)
    }

    /** 훑을 때는 점을 줄여 빨리 */
    private fun thin(a: DoubleArray, max: Int = 120): DoubleArray =
        if (a.size <= max) a else DoubleArray(max) { a[it * (a.size - 1) / (max - 1)] }

    private fun logGrid(a: Double, b: Double, n: Int) = DoubleArray(n) { a * (b / a).pow(it / (n - 1.0)) }

    private class Lin(val c: DoubleArray, val score: Double)

    /**
     * v ≈ Σ cⱼ φⱼ(u) 를 푼다. 한 번 푼 곡선의 기울기 s로 1/(1+s²) 가중해 다시 풀고,
     * 점수는 Σ r²/(1+s²) (곡선까지 거리의 제곱에 가까움). 모수가 다른 후보끼리 비교할 수 있다.
     */
    private fun linFit(
        u: DoubleArray, v: DoubleArray, m: Int,
        phi: (Double, DoubleArray) -> Unit, dphi: (Double, DoubleArray) -> Unit,
    ): Lin? {
        val n = u.size
        val row = DoubleArray(m)
        val drow = DoubleArray(m)
        var w: DoubleArray? = null
        var c = DoubleArray(m)
        repeat(2) {
            val a = Array(m) { DoubleArray(m) }
            val b = DoubleArray(m)
            for (i in 0 until n) {
                phi(u[i], row)
                val wi = w?.get(i) ?: 1.0
                for (j in 0 until m) {
                    for (k in 0 until m) a[j][k] += wi * row[j] * row[k]
                    b[j] += wi * row[j] * v[i]
                }
            }
            val sol = ShapeFit.solve(a, b) ?: return null
            c = sol
            w = DoubleArray(n) { i ->
                dphi(u[i], drow)
                val sl = dot(sol, drow)
                1 / (1 + sl * sl)
            }
        }
        val ww = w ?: return null
        var score = 0.0
        for (i in 0 until n) {
            phi(u[i], row)
            val r = dot(c, row) - v[i]
            score += r * r * ww[i]
        }
        return if (score.isNaN() || score.isInfinite()) null else Lin(c, score)
    }

    private fun dot(a: DoubleArray, b: DoubleArray): Double {
        var s = 0.0
        for (i in a.indices) s += a[i] * b[i]
        return s
    }

    /** [lo, hi]에서 f가 가장 작은 곳 (황금분할) */
    private fun golden(lo0: Double, hi0: Double, f: (Double) -> Double): Double {
        val g = (sqrt(5.0) - 1) / 2
        var lo = lo0; var hi = hi0
        var x1 = hi - g * (hi - lo); var x2 = lo + g * (hi - lo)
        var f1 = f(x1); var f2 = f(x2)
        repeat(30) {
            if (f1 < f2) {
                hi = x2; x2 = x1; f2 = f1
                x1 = hi - g * (hi - lo); f1 = f(x1)
            } else {
                lo = x1; x1 = x2; f1 = f2
                x2 = lo + g * (hi - lo); f2 = f(x2)
            }
        }
        return (lo + hi) / 2
    }

    // ----- 정규화 좌표 → 페이지 좌표 -----

    /** swapped면 u가 세로(위쪽 +), v가 가로 (로그처럼 x를 y의 함수로 본 경우) */
    private fun toPage(d: Norm, swapped: Boolean, u: Double, v: Double, out: FloatArray, i: Int) {
        val t = d.tm + u * d.sc
        val s = d.sm + v * d.sc
        if (swapped) { out[i * 2] = s.toFloat(); out[i * 2 + 1] = (-t).toFloat() }
        else { out[i * 2] = t.toFloat(); out[i * 2 + 1] = (-s).toFloat() }
    }

    private inline fun sample(d: Norm, swapped: Boolean, n: Int, f: (Double) -> Double): FloatArray {
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val u = -1.0 + 2.0 * i / (n - 1)
            toPage(d, swapped, u, f(u), out, i)
        }
        return out
    }

    private fun segment(d: Norm, swapped: Boolean, u0: Double, v0: Double, u1: Double, v1: Double): FloatArray {
        val out = FloatArray(4)
        toPage(d, swapped, u0, v0, out, 0)
        toPage(d, swapped, u1, v1, out, 1)
        return out
    }

    /** 그린 방향 그대로 (가로축 t가 줄어드는 쪽으로 그렸으면 뒤집는다) */
    private fun inDrawnOrder(curve: FloatArray, t: DoubleArray) =
        if (t.first() > t.last()) ShapeFit.reversePoints(curve) else curve

    // ================= 지수 · 로그 =================

    /**
     * 지수·로그: 가로로 맞춘 지수 y = A·e^(kx) + C (점근선 y = C)와
     * 세로로 맞춘 x = A·e^(ky) + C (= 로그 y = a·ln(x − p) + q, 점근선 x = C)를 둘 다 해 보고
     * 그린 선에 더 가까운 쪽을 고른다. 두 점수는 페이지 단위로 바꿔 비교한다.
     */
    fun expOrLog(p: Pair<DoubleArray, DoubleArray>): Fitted? {
        val e = exponential(p, inverse = false)
        val l = exponential(p, inverse = true)
        return when {
            e == null -> l?.first
            l == null -> e.first
            else -> if (e.second <= l.second) e.first else l.first
        }
    }

    /** 결과와 점수(곡선까지 거리 제곱의 합, 페이지 단위). inverse면 가로·세로를 바꿔 맞춘다 */
    private fun exponential(p: Pair<DoubleArray, DoubleArray>, inverse: Boolean): Pair<Fitted, Double>? {
        val (xs, ys) = p
        val up = DoubleArray(ys.size) { -ys[it] }
        val t = if (inverse) up else xs
        val d = norm(t, if (inverse) xs else up) ?: return null
        fun fitK(k: Double, u: DoubleArray, v: DoubleArray) = linFit(u, v, 2,
            { x, r -> r[0] = exp(k * x); r[1] = 1.0 },
            { x, r -> r[0] = k * exp(k * x); r[1] = 0.0 })

        val tu = thin(d.u); val tv = thin(d.v)
        val n = 40
        val mags = logGrid(0.3, 14.0, n)
        var bestK = 0.0
        var bestScore = Double.MAX_VALUE
        for (sg in doubleArrayOf(1.0, -1.0)) for (m in mags) {
            val f = fitK(sg * m, tu, tv) ?: continue
            if (f.score < bestScore) { bestScore = f.score; bestK = sg * m }
        }
        if (bestK == 0.0) return null
        val step = (14.0 / 0.3).pow(1.0 / (n - 1))
        val sg = if (bestK > 0) 1.0 else -1.0
        val mag = golden(abs(bestK) / step, abs(bestK) * step) { m -> fitK(sg * m, d.u, d.v)?.score ?: Double.MAX_VALUE }
        val k = sg * mag
        val f = fitK(k, d.u, d.v) ?: return null
        val a = f.c[0]; val c = f.c[1]
        if (abs(a) < 1e-9) return null
        val curve = inDrawnOrder(sample(d, inverse, 160) { u -> a * exp(k * u) + c }, t)
        // 점근선이 그린 곡선에서 너무 멀면(곡선이 거의 직선) 생략
        val asym = if (c > d.vmin - 2 && c < d.vmax + 2) listOf(segment(d, inverse, -1.15, c, 1.15, c)) else emptyList()
        return Fitted(listOf(curve), asym) to f.score * d.sc * d.sc
    }

    // ================= 사인 · 코사인 =================

    /** y = a·sin(ωx) + b·cos(ωx) + C (= R·sin(ωx + φ) + C). 사인·코사인은 모양이 같다 */
    fun sine(p: Pair<DoubleArray, DoubleArray>): Fitted? {
        val (xs, ys) = p
        val d = norm(xs, DoubleArray(ys.size) { -ys[it] }) ?: return null
        fun fitW(w: Double, u: DoubleArray, v: DoubleArray) = linFit(u, v, 3,
            { x, r -> r[0] = sin(w * x); r[1] = cos(w * x); r[2] = 1.0 },
            { x, r -> r[0] = w * cos(w * x); r[1] = -w * sin(w * x); r[2] = 0.0 })

        // 그린 폭 안에 약 1/4 ~ 7.5 주기
        val tu = thin(d.u, 160); val tv = thin(d.v, 160)
        val n = 160
        val grid = logGrid(0.8, 24.0, n)
        var bestW = 0.0
        var bestScore = Double.MAX_VALUE
        for (w in grid) {
            val f = fitW(w, tu, tv) ?: continue
            if (f.score < bestScore) { bestScore = f.score; bestW = w }
        }
        if (bestW == 0.0) return null
        val step = (24.0 / 0.8).pow(1.0 / (n - 1))
        val w = golden(bestW / step, bestW * step) { fitW(it, d.u, d.v)?.score ?: Double.MAX_VALUE }
        val f = fitW(w, d.u, d.v) ?: return null
        if (hypot(f.c[0], f.c[1]) < 0.03) return null
        val pts = max(160, (w * 40).toInt())
        val curve = sample(d, false, pts) { u -> f.c[0] * sin(w * u) + f.c[1] * cos(w * u) + f.c[2] }
        // 축: 곡선이 오르내리는 가운데 가로선 y = C, 그린 폭보다 양쪽으로 조금 길게
        val axis = segment(d, false, -1.12, f.c[2], 1.12, f.c[2])
        return Fitted(listOf(inDrawnOrder(curve, xs)), listOf(axis))
    }

    // ================= 탄젠트 =================

    /**
     * 한 가지: y = A·tan(ω(x − x₀)) + C, 점근선 x = x₀ ± π/(2ω).
     * 가운데 x₀와 '점근선까지 거리 / 그린 반폭' 비율을 훑는다 (그린 범위는 두 점근선 사이에 있어야 하므로 비율 > 1).
     */
    fun tangent(p: Pair<DoubleArray, DoubleArray>): Fitted? {
        val (xs, ys) = p
        val d = norm(xs, DoubleArray(ys.size) { -ys[it] }) ?: return null
        fun half(u0: Double, ratio: Double) = ratio * (1 + abs(u0))
        fun fitT(u0: Double, ratio: Double, u: DoubleArray, v: DoubleArray): Lin? {
            val w = PI / (2 * half(u0, ratio))
            return linFit(u, v, 2,
                { x, r -> r[0] = tan(w * (x - u0)); r[1] = 1.0 },
                { x, r -> val c = cos(w * (x - u0)); r[0] = w / (c * c); r[1] = 0.0 })
        }

        val tu = thin(d.u); val tv = thin(d.v)
        val nr = 24
        val ratios = logGrid(1.01, 6.0, nr)
        var bu = 0.0; var br = 0.0
        var bestScore = Double.MAX_VALUE
        for (iu in 0..28) {
            val u0 = -0.7 + 1.4 * iu / 28
            for (r in ratios) {
                val f = fitT(u0, r, tu, tv) ?: continue
                if (f.score < bestScore) { bestScore = f.score; bu = u0; br = r }
            }
        }
        if (br == 0.0) return null
        // 가운데, 비율 순서로 한 번씩 다듬기
        val u0 = golden(bu - 0.05, bu + 0.05) { fitT(it, br, d.u, d.v)?.score ?: Double.MAX_VALUE }
        val step = (6.0 / 1.01).pow(1.0 / (nr - 1))
        val r = golden(max(1.002, br / step), br * step) { fitT(u0, it, d.u, d.v)?.score ?: Double.MAX_VALUE }
        val f = fitT(u0, r, d.u, d.v) ?: return null
        val a = f.c[0]; val c = f.c[1]
        if (abs(a) < 1e-9) return null
        val h = half(u0, r)
        val w = PI / (2 * h)
        val span = d.vmax - d.vmin
        // 점근선 바로 옆에서 곡선이 치솟지 않도록 그린 높이까지만.
        // 가파른 곳도 매끄럽도록 가로로 고르게 뽑은 점과 세로로 고르게 뽑은 점을 합친다
        val vLo = d.vmin; val vHi = d.vmax
        fun uAt(v: Double) = u0 + kotlin.math.atan((v - c) / a) / w
        val ua = max(-1.0, min(uAt(vLo), uAt(vHi)))
        val ub = min(1.0, max(uAt(vLo), uAt(vHi)))
        if (ub - ua < 0.2) return null
        val us = ArrayList<Double>()
        for (i in 0..160) us += ua + (ub - ua) * i / 160
        val va = a * tan(w * (ua - u0)) + c; val vb = a * tan(w * (ub - u0)) + c
        for (i in 1 until 160) us += uAt(va + (vb - va) * i / 160)
        us.sort()
        val curve = FloatArray(us.size * 2)
        us.forEachIndexed { i, u -> toPage(d, false, u, a * tan(w * (u - u0)) + c, curve, i) }
        val lo = d.vmin - span * 0.08; val hi = d.vmax + span * 0.08
        val asym = listOf(u0 - h, u0 + h).filter { abs(it) < 2.5 }.map { ua -> segment(d, false, ua, lo, ua, hi) }
        return Fitted(listOf(inDrawnOrder(curve, xs)), asym)
    }

    // ================= 쌍곡선 =================

    /**
     * 축에 나란한 쌍곡선 x²/a² − y²/b² = ±1 (중심은 옮길 수 있음).
     * 한 가지를 그리면: 꼭짓점(가장 바깥 점)이 획 가운데쪽에 있는 축으로 열린 것으로 보고 맞춘 뒤,
     * 중심에 대칭인 반대쪽 가지와 두 점근선을 만든다.
     */
    fun hyperbola(p: Pair<DoubleArray, DoubleArray>): Fitted? {
        val (xs, ys) = p
        val n = xs.size
        fun vertexCentrality(a: DoubleArray): Double {
            var iMin = 0; var iMax = 0
            for (i in a.indices) {
                if (a[i] < a[iMin]) iMin = i
                if (a[i] > a[iMax]) iMax = i
            }
            fun c(i: Int) = min(i, n - 1 - i).toDouble() / n
            return max(c(iMin), c(iMax))
        }
        val leftRight = vertexCentrality(xs) >= vertexCentrality(ys)
        if (leftRight) return branch(xs, ys)
        // 위아래로 열림: 가로·세로를 바꿔 맞추고 되돌린다
        val f = branch(ys, xs) ?: return null
        return Fitted(f.curves.map(::swapXY), f.guides.map(::swapXY))
    }

    /** 넬더-미드 (기울기 없이 최솟값 찾기). step은 처음 심플렉스 크기 */
    private fun nelderMead(start: DoubleArray, step: Double, f: (DoubleArray) -> Double, iters: Int = 400): DoubleArray {
        val n = start.size
        val pts = Array(n + 1) { i -> start.copyOf().also { if (i > 0) it[i - 1] += step } }
        val vals = DoubleArray(n + 1) { f(pts[it]) }
        fun along(c: DoubleArray, p: DoubleArray, t: Double) = DoubleArray(n) { c[it] + t * (p[it] - c[it]) }
        repeat(iters) {
            val order = (0..n).sortedBy { vals[it] }
            val best = order[0]; val worst = order[n]; val second = order[n - 1]
            val c = DoubleArray(n)
            for (i in order.dropLast(1)) for (j in 0 until n) c[j] += pts[i][j] / n
            val r = along(c, pts[worst], -1.0); val fr = f(r)
            when {
                fr < vals[best] -> {
                    val e = along(c, pts[worst], -2.0); val fe = f(e)
                    if (fe < fr) { pts[worst] = e; vals[worst] = fe } else { pts[worst] = r; vals[worst] = fr }
                }
                fr < vals[second] -> { pts[worst] = r; vals[worst] = fr }
                else -> {
                    val k = along(c, pts[worst], 0.5); val fk = f(k)
                    if (fk < vals[worst]) { pts[worst] = k; vals[worst] = fk }
                    else for (i in 0..n) if (i != best) {
                        pts[i] = along(pts[best], pts[i], 0.5); vals[i] = f(pts[i])
                    }
                }
            }
        }
        return pts[(0..n).minBy { vals[it] }]
    }

    private fun swapXY(a: FloatArray) = FloatArray(a.size) { if (it % 2 == 0) a[it + 1] else a[it - 1] }

    /** 좌우로 열린 (X−h)²/a² − (Y−k)²/b² = 1 의 한 가지 */
    private fun branch(x0: DoubleArray, y0: DoubleArray): Fitted? {
        val n = x0.size
        val mx = x0.average(); val my = y0.average()
        val sc = max(x0.max() - x0.min(), y0.max() - y0.min()) / 2
        if (sc < 2) return null
        val x = DoubleArray(n) { (x0[it] - mx) / sc }
        val y = DoubleArray(n) { (y0[it] - my) / sc }
        // X² = 2h·X + s·Y² − 2sk·Y + (a² + s·k² − h²),  s = (a/b)²  → 계수 4개를 선형 최소제곱으로
        val a = Array(4) { DoubleArray(4) }
        val b = DoubleArray(4)
        val row = DoubleArray(4)
        for (i in 0 until n) {
            row[0] = x[i]; row[1] = y[i] * y[i]; row[2] = y[i]; row[3] = 1.0
            val rhs = x[i] * x[i]
            for (j in 0..3) {
                for (k in 0..3) a[j][k] += row[j] * row[k]
                b[j] += row[j] * rhs
            }
        }
        val c = ShapeFit.solve(a, b) ?: return null
        val h = c[0] / 2
        val s = c[1]
        // s ≤ 0이면 타원·포물선 모양 (쌍곡선이 아님). 점근선 기울기 1/√s 가 너무 눕거나 서도 안 됨
        if (s < 0.02 || s > 50) return null
        val k0 = -c[2] / (2 * s)
        val a2 = c[3] - s * k0 * k0 + h * h
        if (a2 <= 1e-6) return null
        val side = if (x.average() > h) 1.0 else -1.0
        // 위 식은 대수적 오차라 한 가지만으로는 중심이 틀어지기 쉽다 (반대쪽 가지가 엉뚱한 곳에).
        // 곡선까지의 거리(가로 오차를 기울기로 나눈 것)를 직접 줄이도록 다듬는다
        fun score(q: DoubleArray): Double {
            val hh = q[0]; val kk = q[1]; val aa = exp(q[2]); val bb = exp(q[3])
            var sum = 0.0
            for (i in 0 until n) {
                val z = (y[i] - kk) / bb
                val root = sqrt(1 + z * z)
                val r = x[i] - (hh + side * aa * root)
                val g = side * aa * z / (bb * root)
                sum += r * r / (1 + g * g)
            }
            return sum
        }
        val q = nelderMead(doubleArrayOf(h, k0, ln(sqrt(a2)), ln(sqrt(a2) / sqrt(s))), 0.1, ::score)
        val hq = q[0]; val k = q[1]; val ra = exp(q[2]); val rb = exp(q[3])
        if (ra.isNaN() || rb.isNaN() || ra < 1e-3 || rb < 1e-3 || rb / ra > 8 || ra / rb > 8) return null
        // 그린 가지의 매개변수 범위 (Y = k + b·sinh t 는 가지를 따라 한 방향으로 변한다)
        val tStart = asinh((y.first() - k) / rb)
        val tEnd = asinh((y.last() - k) / rb)
        if (abs(tEnd - tStart) < 0.3) return null
        val m = 120
        fun branchPts(sd: Double) = FloatArray(m * 2).also { out ->
            for (i in 0 until m) {
                val t = tStart + (tEnd - tStart) * i / (m - 1)
                out[i * 2] = (mx + (hq + sd * ra * cosh(t)) * sc).toFloat()
                out[i * 2 + 1] = (my + (k + rb * sinh(t)) * sc).toFloat()
            }
        }
        // 점근선 Y − k = ±(b/a)(X − h): 가지들이 닿는 범위보다 조금 길게
        val len = ra * cosh(max(abs(tStart), abs(tEnd))) * 1.1
        val slope = rb / ra
        fun line(sg: Double) = floatArrayOf(
            (mx + (hq - len) * sc).toFloat(), (my + (k - sg * slope * len) * sc).toFloat(),
            (mx + (hq + len) * sc).toFloat(), (my + (k + sg * slope * len) * sc).toFloat(),
        )
        return Fitted(listOf(branchPts(side), branchPts(-side)), listOf(line(1.0), line(-1.0)))
    }
}
