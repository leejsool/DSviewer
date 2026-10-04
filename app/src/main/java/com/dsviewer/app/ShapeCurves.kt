package com.dsviewer.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asinh
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.round
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

    // ================= 이차 × 지수 =================

    /**
     * y = (a·x² + b·x + c)·e^(kx) + d: 한쪽은 점근선 y = d로 다가가고 다른 쪽은 발산하며, 극값이 둘 (y = x²eˣ 꼴).
     * 극값이 둘 보이면 삼차·사차처럼 칠판식으로 맞추고(quadExpSchematic), 아니면 최소제곱(quadExpLsq).
     * 가파른 쪽 끝은 그린 높이에서 자른다 (펜을 뗀 곳보다 치솟지 않도록).
     */
    fun quadExp(p: Pair<DoubleArray, DoubleArray>, quick: Boolean = false): Fitted? {
        val (xs, ys) = p
        val d = norm(xs, DoubleArray(ys.size) { -ys[it] }) ?: return null
        val (g, e) = quadExpSchematic(d, quick) ?: quadExpLsq(d) ?: return null
        val span = d.vmax - d.vmin
        val lo = d.vmin - span * 0.02; val hi = d.vmax + span * 0.02
        fun inside(u: Double) = g(u) in lo..hi
        // 끝에서 안쪽으로 들어오며 처음 그린 높이 안에 드는 곳 (이분법으로 다듬음)
        fun edge(from: Double, to: Double): Double {
            if (inside(from)) return from
            var prev = from
            for (i in 1..200) {
                val u = from + (to - from) * i / 200
                if (inside(u)) {
                    var a = prev; var b = u
                    repeat(30) { val m = (a + b) / 2; if (inside(m)) b = m else a = m }
                    return b
                }
                prev = u
            }
            return from
        }
        val ua = edge(-1.0, 1.0); val ub = edge(1.0, -1.0)
        if (ub - ua < 0.2) return null
        // 가파른 곳은 촘촘히: 한 칸에서 세로로 많이 변하면 더 나눈다
        val base = 200
        val du = (ub - ua) / base
        val us = ArrayList<Double>()
        for (i in 0 until base) {
            val u0 = ua + du * i
            val parts = (abs(g(u0 + du) - g(u0)) / du).toInt().coerceIn(1, 24)
            for (j in 0 until parts) us += u0 + du * j / parts
        }
        us += ub
        val curve = FloatArray(us.size * 2)
        us.forEachIndexed { i, u -> toPage(d, false, u, g(u), curve, i) }
        val asym = if (e > d.vmin - 2 && e < d.vmax + 2) listOf(segment(d, false, -1.15, e, 1.15, e)) else emptyList()
        return Fitted(listOf(inDrawnOrder(curve, xs)), asym)
    }

    /**
     * 칠판식 이차×지수: 그림에서 극값 둘의 자리·높이를 찾아, 그 자리에서 정확히 극값을 갖는
     * f = A·P(x)·e^(kx) + d (P는 극값 자리와 k로 정해지는 이차식, A·d는 두 극값 높이로)를 만든다.
     * k는 점근선 쪽 꼬리와 두 극값 사이가 그린 것에 가장 가깝게 고르고,
     * 두 팔(바깥 극값 너머)은 가로·세로로 늘이고 줄여 그린 팔에 맞춘다 (발산하는 팔은 그린 끝 높이까지).
     * 결과는 정규화 좌표의 함수와 점근선 높이. 극값이 둘이 아니면 null.
     */
    private fun quadExpSchematic(d: Norm, quick: Boolean): Pair<(Double) -> Double, Double>? {
        val idx = d.u.indices.sortedBy { d.u[it] }
        val u = DoubleArray(idx.size) { d.u[idx[it]] }
        val v = DoubleArray(idx.size) { d.v[idx[it]] }
        val ext = ShapeFit.findExtrema(u, v)
        if (ext.size != 2) return null
        val leftEnd = ShapeFit.smooth(v, 0, 1); val rightEnd = ShapeFit.smooth(v, v.size - 1, 1)
        val e0 = ShapeFit.smooth(v, ext[0]); val e1 = ShapeFit.smooth(v, ext[1])
        // 끝과 가까운 극값의 높이 차가 큰 쪽이 발산하는 쪽. 왼쪽이면 좌우를 뒤집어(w = −u) 늘 오른쪽이 발산하게 푼다
        val flip = abs(leftEnd - e0) > abs(rightEnd - e1)
        val s = if (flip) -1.0 else 1.0
        val w1 = if (flip) -u[ext[1]] else u[ext[0]]
        val w2 = if (flip) -u[ext[0]] else u[ext[1]]
        val h1 = if (flip) e1 else e0
        val h2 = if (flip) e0 else e1
        if (w2 - w1 < 0.05) return null
        // 점근선 쪽 꼬리(w < w1), 두 극값 사이, 발산하는 팔(w > w2)의 점들
        val lw = ArrayList<Double>(); val lv = ArrayList<Double>()
        val mw = ArrayList<Double>(); val mv = ArrayList<Double>()
        val rw = ArrayList<Double>(); val rv = ArrayList<Double>()
        for (i in u.indices step if (quick) 5 else 3) {
            val w = s * u[i]
            when {
                w < w1 -> { lw += w; lv += v[i] }
                w <= w2 -> { mw += w; mv += v[i] }
                else -> { rw += w; rv += v[i] }
            }
        }
        val lwa = lw.toDoubleArray(); val lva = lv.toDoubleArray()
        val mwa = mw.toDoubleArray(); val mva = mv.toDoubleArray()
        val rwa = rw.toDoubleArray(); val rva = rv.toDoubleArray()

        // P(w) = w² + bw + c 로 두면 (P·e^(kw))' = k·e^(kw)·(w − w1)(w − w2)
        class F(val k: Double, val a: Double, val off: Double) {
            val b = -(w1 + w2) - 2 / k
            val c = w1 * w2 - b / k
            fun q(w: Double) = (w * w + b * w + c) * exp(k * w)
            operator fun invoke(w: Double) = a * q(w) + off
        }
        fun make(k: Double): F? {
            val t = F(k, 1.0, 0.0)
            val dq = t.q(w1) - t.q(w2)
            if (abs(dq) < 1e-12) return null
            val a = (h1 - h2) / dq
            return F(k, a, h1 - a * t.q(w1))
        }
        fun middleScore(f: F): Double {
            var sum = 0.0
            for (i in mwa.indices) {
                val w = mwa[i]
                val r = f(w) - mva[i]
                val sl = (f(w + 1e-4) - f(w - 1e-4)) / 2e-4
                sum += r * r / (1 + sl * sl)
            }
            return sum
        }
        /**
         * 팔: 극값 w0에서 이어지는 top + sv·(f(w0 + sh·(w − w0)) − top).
         * 칠판에는 꼬리를 점근선에 더 바짝, 발산하는 쪽을 더 가파르게 그리곤 하므로 가로(sh)도 줄이고 늘린다.
         * sh는 훑고, sv는 최소제곱 (endH가 있으면 w = 1에서 그 높이가 되도록: 펜을 뗀 끝까지 닿게).
         * 극값에서 기울기 0으로 이어진다
         */
        class Arm(val sh: Double, val sv: Double, val score: Double)
        fun arm(f: F, w0: Double, wa: DoubleArray, vs: DoubleArray, grid: DoubleArray, endH: Double? = null): Arm? {
            val top = f(w0)
            if (wa.size < 4) return Arm(1.0, 1.0, 0.0)
            val dv = DoubleArray(vs.size) { vs[it] - top }
            var best: Arm? = null
            for (sh in grid) {
                val h = { w: Double -> f(w0 + sh * (w - w0)) - top }
                val sv: Double
                val score: Double
                if (endH != null) {
                    val he = h(1.0)
                    if (abs(he) < 1e-9) continue
                    sv = (endH - top) / he
                    var sum = 0.0
                    for (i in wa.indices) {
                        val r = sv * h(wa[i]) - dv[i]
                        val sl = sv * (h(wa[i] + 1e-4) - h(wa[i] - 1e-4)) / 2e-4
                        sum += r * r / (1 + sl * sl)
                    }
                    score = sum
                } else {
                    val lin = linFit(wa, dv, 1, { w, r -> r[0] = h(w) }, { w, r -> r[0] = (h(w + 1e-4) - h(w - 1e-4)) / 2e-4 }) ?: continue
                    sv = lin.c[0]; score = lin.score
                }
                if (sv < 0.2 || sv > 60 || score.isNaN()) continue
                if (best == null || score < best.score) best = Arm(sh, sv, score)
            }
            return best
        }
        val coarse = logGrid(0.3, 5.0, if (quick) 8 else 10)
        fun score(k: Double): Double {
            val f = make(k) ?: return Double.MAX_VALUE
            val left = arm(f, w1, lwa, lva, coarse) ?: return Double.MAX_VALUE
            val sum = middleScore(f) + left.score
            return if (sum.isNaN()) Double.MAX_VALUE else sum
        }
        val kLo = 0.3; val kHi = 40.0; val nk = if (quick) 24 else 36
        val bk = logGrid(kLo, kHi, nk).minBy(::score)
        val step = (kHi / kLo).pow(1.0 / (nk - 1))
        val f = make(golden(bk / step, bk * step, ::score)) ?: return null
        val fine = logGrid(0.3, 5.0, if (quick) 20 else 40)
        val la = arm(f, w1, lwa, lva, fine) ?: Arm(1.0, 1.0, 0.0)
        val ra = arm(f, w2, rwa, rva, fine, if (flip) leftEnd else rightEnd) ?: Arm(1.0, 1.0, 0.0)
        val top1 = f(w1); val top2 = f(w2)
        val g = { uu: Double ->
            val w = s * uu
            when {
                w < w1 -> top1 + la.sv * (f(w1 + la.sh * (w - w1)) - top1)
                w > w2 -> top2 + ra.sv * (f(w2 + ra.sh * (w - w2)) - top2)
                else -> f(w)
            }
        }
        return g to top1 + la.sv * (f.off - top1)
    }

    /** k를 훑어서 고르고 a, b, c, d는 최소제곱 */
    private fun quadExpLsq(d: Norm): Pair<(Double) -> Double, Double>? {
        fun fitK(k: Double, u: DoubleArray, v: DoubleArray) = linFit(u, v, 4,
            { x, r -> val e = exp(k * x); r[0] = x * x * e; r[1] = x * e; r[2] = e; r[3] = 1.0 },
            { x, r -> val e = exp(k * x); r[0] = (2 * x + k * x * x) * e; r[1] = (1 + k * x) * e; r[2] = k * e; r[3] = 0.0 })

        val tu = thin(d.u); val tv = thin(d.v)
        val n = 48
        val lo = 0.5; val hi = 16.0
        val mags = logGrid(lo, hi, n)
        var bestK = 0.0
        var bestScore = Double.MAX_VALUE
        for (sg in doubleArrayOf(1.0, -1.0)) for (m in mags) {
            val f = fitK(sg * m, tu, tv) ?: continue
            if (f.score < bestScore) { bestScore = f.score; bestK = sg * m }
        }
        if (bestK == 0.0) return null
        val step = (hi / lo).pow(1.0 / (n - 1))
        val sg = if (bestK > 0) 1.0 else -1.0
        val mag = golden(abs(bestK) / step, abs(bestK) * step) { m -> fitK(sg * m, d.u, d.v)?.score ?: Double.MAX_VALUE }
        val k = sg * mag
        val f = fitK(k, d.u, d.v) ?: return null
        val a = f.c[0]; val b = f.c[1]; val c = f.c[2]; val e = f.c[3]
        return { u: Double -> (a * u * u + b * u + c) * exp(k * u) + e } to e
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
     * 쌍곡선 한 가지를 그린 획 → 두 가지와 두 점근선. 축이 기울어도 된다 (y = 1/x 꼴의 가지도).
     * 중심·축 각도·a·b를 '곡선까지의 거리'가 가장 작게 맞추되 대충 그린 획에 너그럽게:
     * 시작 각도를 여러 개(꼭짓점 쪽 방향, 45° 배수) 두고 처음 점수가 좋은 셋만 다듬는다.
     * 두 점근선이 가로·세로에 12° 안이면(y = 1/x 꼴) 딱 맞추고, 아니면 축이 수평·수직·45°에서 10° 안일 때 축을 맞춘다.
     */
    fun hyperbola(p: Pair<DoubleArray, DoubleArray>): Fitted? {
        val (x0, y0) = p
        val mx = x0.average(); val my = y0.average()
        val sc = max(x0.max() - x0.min(), y0.max() - y0.min()) / 2
        if (sc < 2) return null
        val xs = thin(DoubleArray(x0.size) { (x0[it] - mx) / sc }, 120)
        val ys = thin(DoubleArray(y0.size) { (y0[it] - my) / sc }, 120)
        val n = xs.size
        val lnMax = ln(12.0)

        // q = [cx, cy, θ, ln a, ln b]: u = (cosθ, sinθ) 쪽으로 열린 가지 (X − Xc)²/a² − (Y − Yc)²/b² = 1, X > Xc
        fun score(q: DoubleArray): Double {
            val cu = cos(q[2]); val su = sin(q[2])
            val ra = exp(q[3]); val rb = exp(q[4])
            var sum = 0.0
            for (i in 0 until n) {
                val dx = xs[i] - q[0]; val dy = ys[i] - q[1]
                val gx = dx * cu + dy * su
                val gy = -dx * su + dy * cu
                val z = gy / rb
                val root = sqrt(1 + z * z)
                val r = gx - ra * root
                val g = ra * z / (rb * root)
                sum += r * r / (1 + g * g)
            }
            // a:b가 12배보다 납작·길쭉해지는 것은 막는다
            val over = abs(q[3] - q[4]) - lnMax
            return if (over > 0) sum + over * over else sum
        }

        // 열린 방향 θ에서 꼭짓점(축 방향 가장 안쪽)과 세로 폭으로 중심·a·b를 잡은 시작 모수
        fun init(theta: Double, aFrac: Double): DoubleArray? {
            val cu = cos(theta); val su = sin(theta)
            var xMin = Double.MAX_VALUE; var xMax = -Double.MAX_VALUE
            var yMin = Double.MAX_VALUE; var yMax = -Double.MAX_VALUE
            for (i in 0 until n) {
                val gx = xs[i] * cu + ys[i] * su
                val gy = -xs[i] * su + ys[i] * cu
                xMin = min(xMin, gx); xMax = max(xMax, gx)
                yMin = min(yMin, gy); yMax = max(yMax, gy)
            }
            val half = max((yMax - yMin) / 2, 0.05)
            val depth = xMax - xMin
            if (depth < 0.02) return null
            val ra = aFrac * half
            val coshT = (ra + depth) / ra
            val rb = (half / sqrt(coshT * coshT - 1)).coerceIn(ra / 10, ra * 10)
            val gxc = xMin - ra; val gyc = (yMin + yMax) / 2
            return doubleArrayOf(gxc * cu - gyc * su, gxc * su + gyc * cu, theta, ln(ra), ln(rb))
        }

        // 후보 각도: 꼭짓점(현에서 가장 먼 점) → 현 가운데 방향, 그리고 45° 배수 여덟 개
        val ex = xs[n - 1] - xs[0]; val ey = ys[n - 1] - ys[0]
        val clen = hypot(ex, ey)
        val thetas = ArrayList<Double>()
        if (clen > 0.2) {
            var vi = 0; var vd = -1.0
            for (i in 0 until n) {
                val d = abs((xs[i] - xs[0]) * ey - (ys[i] - ys[0]) * ex) / clen
                if (d > vd) { vd = d; vi = i }
            }
            thetas += atan2((ys[0] + ys[n - 1]) / 2 - ys[vi], (xs[0] + xs[n - 1]) / 2 - xs[vi])
        }
        for (k in 0 until 8) thetas += k * PI / 4
        val starts = thetas.flatMap { th -> listOf(0.25, 0.6).mapNotNull { init(th, it) } }
            .sortedBy(::score).take(3)
        var best: DoubleArray? = null
        var bestScore = Double.MAX_VALUE
        for (st in starts) {
            val q = nelderMead(st, 0.1, ::score, 500)
            val sq = score(q)
            if (sq < bestScore) { bestScore = sq; best = q }
        }
        var q = best ?: return null

        // y = 1/x 꼴 (점근선이 가로·세로, 축 45°)은 가지 일부만 그리면 다른 모양과 구별이 안 되므로,
        // 그렇게 맞춘 것이 크게 나쁘지 않으면 그쪽을 고른다
        var rect: DoubleArray? = null
        var rectScore = Double.MAX_VALUE
        for (k in intArrayOf(1, 3, 5, 7)) {
            val th = k * PI / 4
            val st = init(th, 0.4) ?: continue
            val r = nelderMead(doubleArrayOf(st[0], st[1], (st[3] + st[4]) / 2), 0.1, { v -> score(doubleArrayOf(v[0], v[1], th, v[2], v[2])) }, 300)
            val cand = doubleArrayOf(r[0], r[1], th, r[2], r[2])
            val cs = score(cand)
            if (cs < rectScore) { rectScore = cs; rect = cand }
        }
        if (rect != null && rectScore <= bestScore * 3 + n * 0.0004) q = rect

        // 두 점근선이 모두 가로·세로에 12° 안이면 딱 맞추고 중심·크기를 다시 다듬는다.
        // 아니면 축이 수평·수직·45°에서 10° 안일 때 축만 맞춘다
        val half = PI / 2
        fun snapTo(a: Double, tol: Double): Double? {
            val t = round(a / half) * half
            return if (abs(a - t) <= tol * PI / 180) t else null
        }
        val phi = atan2(exp(q[4]), exp(q[3]))
        val s1 = snapTo(q[2] + phi, 12.0)
        val s2 = snapTo(q[2] - phi, 12.0)
        if (s1 != null && s2 != null && abs(s1 - s2) > 1e-6) {
            val th = (s1 + s2) / 2
            val ph = abs(s1 - s2) / 2
            val ratio = ln(tan(ph))
            val r = nelderMead(doubleArrayOf(q[0], q[1], q[3]), 0.05, { v -> score(doubleArrayOf(v[0], v[1], th, v[2], v[2] + ratio)) }, 300)
            q = doubleArrayOf(r[0], r[1], th, r[2], r[2] + ratio)
        } else {
            val quarter = PI / 4
            val th = round(q[2] / quarter) * quarter
            if (abs(q[2] - th) <= 10 * PI / 180) {
                val r = nelderMead(doubleArrayOf(q[0], q[1], q[3], q[4]), 0.05, { v -> score(doubleArrayOf(v[0], v[1], th, v[2], v[3])) }, 300)
                q = doubleArrayOf(r[0], r[1], th, r[2], r[3])
            }
        }

        val cx = q[0]; val cy = q[1]; val th = q[2]
        val ra = exp(q[3]); val rb = exp(q[4])
        if (ra.isNaN() || rb.isNaN() || cx.isNaN() || cy.isNaN() || ra < 1e-3 || rb < 1e-3 || rb / ra > 12.5 || ra / rb > 12.5) return null
        val cu = cos(th); val su = sin(th)
        // 그린 가지의 매개변수 범위 (Y = b·sinh t 는 가지를 따라 한 방향으로 변한다)
        fun localY(i: Int) = -(xs[i] - cx) * su + (ys[i] - cy) * cu
        val tStart = asinh(localY(0) / rb)
        val tEnd = asinh(localY(n - 1) / rb)
        if (abs(tEnd - tStart) < 0.15) return null
        // 지역 좌표(X: 축 방향, Y: 수직) → 페이지 좌표
        fun page(gx: Double, gy: Double, out: FloatArray, i: Int) {
            out[i * 2] = (mx + (cx + gx * cu - gy * su) * sc).toFloat()
            out[i * 2 + 1] = (my + (cy + gx * su + gy * cu) * sc).toFloat()
        }
        val m = 120
        fun branchPts(sd: Double) = FloatArray(m * 2).also { out ->
            for (i in 0 until m) {
                val t = tStart + (tEnd - tStart) * i / (m - 1)
                page(sd * ra * cosh(t), rb * sinh(t), out, i)
            }
        }
        // 점근선 Y = ±(b/a)X: 가지들이 닿는 범위보다 조금 길게
        val len = ra * cosh(max(abs(tStart), abs(tEnd))) * 1.1
        val slope = rb / ra
        fun line(sg: Double) = FloatArray(4).also {
            page(-len, -sg * slope * len, it, 0)
            page(len, sg * slope * len, it, 1)
        }
        return Fitted(listOf(branchPts(1.0), branchPts(-1.0)), listOf(line(1.0), line(-1.0)))
    }

    /** 넬더-미드 (기울기 없이 최솟값 찾기). step은 처음 심플렉스 크기 */
    internal fun nelderMead(start: DoubleArray, step: Double, f: (DoubleArray) -> Double, iters: Int = 400): DoubleArray {
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

    // ================= 부채꼴 =================

    /**
     * 부채꼴: 중심·반지름·시작 각·중심각을 그린 획 전체에 가장 가깝게 (획 → 테두리, 테두리 → 획 두 방향 거리).
     * 중심에서 시작해 한 바퀴 돌아오든, 호 끝에서 시작하든 되도록: 획의 시작·끝과 크게 꺾인 곳들을
     * 중심 후보로 두고 각각 다듬어 가장 가까운 것을 고른다. 반지름이 수평·수직에 가까우면(5° 안) 딱 맞춘다.
     * 결과는 중심 → 반지름 → 호 → 반지름 → 중심의 닫힌 꺾은선.
     */
    fun sector(p: Pair<DoubleArray, DoubleArray>, quick: Boolean = false): Fitted? {
        val (x0, y0) = p
        val mx = x0.average(); val my = y0.average()
        val sc = max(x0.max() - x0.min(), y0.max() - y0.min()) / 2
        if (sc < 3) return null
        val np = if (quick) 70 else 160
        val xs = thin(DoubleArray(x0.size) { (x0[it] - mx) / sc }, np)
        val ys = thin(DoubleArray(y0.size) { (y0[it] - my) / sc }, np)
        val n = xs.size

        // 중심 후보: 시작점, 끝점, 두 점의 가운데, 많이 꺾인 곳 (서로 떨어진 것 셋)
        val cands = ArrayList<Pair<Double, Double>>()
        cands += xs[0] to ys[0]
        cands += xs[n - 1] to ys[n - 1]
        cands += (xs[0] + xs[n - 1]) / 2 to (ys[0] + ys[n - 1]) / 2
        val w = max(2, n / 20)
        val turn = DoubleArray(n) { i ->
            if (i < w || i >= n - w) 0.0
            else abs(wrap(atan2(ys[i + w] - ys[i], xs[i + w] - xs[i]) - atan2(ys[i] - ys[i - w], xs[i] - xs[i - w])))
        }
        val used = BooleanArray(n)
        repeat(3) {
            var bi = -1
            for (i in 0 until n) if (!used[i] && turn[i] > 0.6 && (bi < 0 || turn[i] > turn[bi])) bi = i
            if (bi >= 0) {
                cands += xs[bi] to ys[bi]
                for (j in max(0, bi - 2 * w)..min(n - 1, bi + 2 * w)) used[j] = true
            }
        }

        fun score(q: DoubleArray) = sectorDistance(q, xs, ys)
        // 처음 모수로 가장 가까운 후보 둘만 다듬는다
        val inits = cands.mapNotNull { (cx, cy) -> sectorInit(cx, cy, xs, ys) }.sortedBy(::score).take(2)
        var best: DoubleArray? = null
        var bestScore = Double.MAX_VALUE
        for (init in inits) {
            val q = nelderMead(init, 0.06, ::score, if (quick) 120 else 300)
            val s = score(q)
            if (s < bestScore) { bestScore = s; best = q }
        }
        val q = best ?: return null
        val cx = q[0]; val cy = q[1]; val r = abs(q[2])
        if (r < 0.05) return null
        val sweep = q[4].coerceIn(0.05, 2 * PI - 0.02)
        // 두 반지름을 따로따로 수평·수직에 맞춘다
        fun snap(a: Double): Double {
            val t = round(a / (PI / 2)) * (PI / 2)
            return if (abs(a - t) <= 5 * PI / 180) t else a
        }
        val a1 = snap(q[3])
        var a2 = snap(q[3] + sweep)
        if (a2 <= a1) a2 += 2 * PI
        val m = max(12, ((a2 - a1) / (2 * PI) * 120).toInt())
        val out = FloatArray((m + 3) * 2)
        fun put(i: Int, x: Double, y: Double) { out[i * 2] = (mx + x * sc).toFloat(); out[i * 2 + 1] = (my + y * sc).toFloat() }
        put(0, cx, cy)
        for (i in 0..m) {
            val a = a1 + (a2 - a1) * i / m
            put(i + 1, cx + r * cos(a), cy + r * sin(a))
        }
        put(m + 2, cx, cy)
        return Fitted(listOf(out))
    }

    /**
     * 반원: 중심각이 180°로 정해진 부채꼴 (두 반지름이 지름이 된다). 호부터 그리든 지름부터 그리든 한 획으로.
     * 획에서 가장 먼 두 점을 지름의 두 끝으로 보고 호가 어느 쪽인지 둘 다 해 본 뒤,
     * 중심·반지름·방향을 부채꼴과 같은 거리로 다듬는다. 지름이 수평·수직에 가까우면(5° 안) 딱 맞춘다.
     * 결과는 지름 한 끝 → 호 → 다른 끝 → 지름을 따라 처음 끝의 닫힌 꺾은선.
     */
    fun semicircle(p: Pair<DoubleArray, DoubleArray>, quick: Boolean = false): Fitted? {
        val (x0, y0) = p
        val mx = x0.average(); val my = y0.average()
        val sc = max(x0.max() - x0.min(), y0.max() - y0.min()) / 2
        if (sc < 3) return null
        val np = if (quick) 70 else 160
        val xs = thin(DoubleArray(x0.size) { (x0[it] - mx) / sc }, np)
        val ys = thin(DoubleArray(y0.size) { (y0[it] - my) / sc }, np)
        // 가장 먼 두 점
        var ia = 0; var ib = 0; var far = -1.0
        for (i in xs.indices) for (j in i + 1 until xs.size) {
            val d = (xs[i] - xs[j]) * (xs[i] - xs[j]) + (ys[i] - ys[j]) * (ys[i] - ys[j])
            if (d > far) { far = d; ia = i; ib = j }
        }
        if (far < 0.01) return null
        val cx0 = (xs[ia] + xs[ib]) / 2; val cy0 = (ys[ia] + ys[ib]) / 2
        val r0 = sqrt(far) / 2
        fun score(q: DoubleArray) = sectorDistance(doubleArrayOf(q[0], q[1], q[2], q[3], PI), xs, ys)
        // 호는 a1에서 각이 커지는 쪽으로 180°: 두 끝 중 어느 쪽에서 시작하느냐가 호가 놓인 쪽
        val inits = listOf(ia, ib).map { k -> doubleArrayOf(cx0, cy0, r0, atan2(ys[k] - cy0, xs[k] - cx0)) }.sortedBy(::score)
        val q = nelderMead(inits[0], 0.05, ::score, if (quick) 100 else 250)
        val cx = q[0]; val cy = q[1]; val r = abs(q[2])
        if (r < 0.05 || cx.isNaN() || cy.isNaN() || q[3].isNaN()) return null
        val t = round(q[3] / (PI / 2)) * (PI / 2)
        val a1 = if (abs(q[3] - t) <= 5 * PI / 180) t else q[3]
        val m = 60
        val out = FloatArray((m + 2) * 2)
        fun put(i: Int, x: Double, y: Double) { out[i * 2] = (mx + x * sc).toFloat(); out[i * 2 + 1] = (my + y * sc).toFloat() }
        for (i in 0..m) {
            val a = a1 + PI * i / m
            put(i, cx + r * cos(a), cy + r * sin(a))
        }
        put(m + 1, cx + r * cos(a1), cy + r * sin(a1))
        return Fitted(listOf(out))
    }

    private fun wrap(a: Double): Double {
        var d = a
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        return d
    }

    /**
     * 중심을 (cx, cy)로 볼 때의 처음 모수 [cx, cy, r, 시작 각, 중심각]:
     * 중심에서 먼 점들(호)의 거리 가운데값이 반지름, 그 점들의 각도 중 가장 크게 빈 곳의 반대쪽이 중심각.
     */
    private fun sectorInit(cx: Double, cy: Double, xs: DoubleArray, ys: DoubleArray): DoubleArray? {
        val dist = DoubleArray(xs.size) { hypot(xs[it] - cx, ys[it] - cy) }
        val dmax = dist.max()
        if (dmax < 0.1) return null
        val arc = xs.indices.filter { dist[it] > 0.8 * dmax }
        if (arc.size < 3) return null
        val r = arc.map { dist[it] }.sorted()[arc.size / 2]
        val angs = arc.map { atan2(ys[it] - cy, xs[it] - cx) }.sorted()
        var gap = angs[0] + 2 * PI - angs.last()
        var start = angs[0]
        for (i in 1 until angs.size) {
            val g = angs[i] - angs[i - 1]
            if (g > gap) { gap = g; start = angs[i] }
        }
        return doubleArrayOf(cx, cy, r, start, 2 * PI - gap)
    }

    /** 부채꼴 테두리와 획의 두 방향 거리 (획의 점 → 테두리, 테두리에 고르게 찍은 점 → 획) */
    private fun sectorDistance(q: DoubleArray, xs: DoubleArray, ys: DoubleArray): Double {
        val cx = q[0]; val cy = q[1]; val r = abs(q[2]); val a1 = q[3]
        val sweep = q[4].coerceIn(0.05, 2 * PI - 0.02)
        val e1x = cx + r * cos(a1); val e1y = cy + r * sin(a1)
        val e2x = cx + r * cos(a1 + sweep); val e2y = cy + r * sin(a1 + sweep)
        fun seg2(x: Double, y: Double, bx: Double, by: Double): Double {
            val ex = bx - cx; val ey = by - cy
            val t = (((x - cx) * ex + (y - cy) * ey) / max(1e-12, ex * ex + ey * ey)).coerceIn(0.0, 1.0)
            val dx = cx + ex * t - x; val dy = cy + ey * t - y
            return dx * dx + dy * dy
        }
        var fwd = 0.0
        for (i in xs.indices) {
            val x = xs[i]; val y = ys[i]
            var best = min(seg2(x, y, e1x, e1y), seg2(x, y, e2x, e2y))
            val rel = ((atan2(y - cy, x - cx) - a1) % (2 * PI) + 2 * PI) % (2 * PI)
            if (rel <= sweep) {
                val dd = hypot(x - cx, y - cy) - r
                best = min(best, dd * dd)
            }
            fwd += best
        }
        // 테두리 위의 점: 두 반지름에 8개씩, 호에 중심각만큼 (기기에서 느리지 않도록 상자에 넣지 않은 배열로)
        val arcN = max(6, (sweep / (2 * PI) * 32).toInt())
        val nb = 16 + arcN
        val bx = DoubleArray(nb); val by = DoubleArray(nb)
        for (j in 0 until 8) {
            val t = (j + 0.5) / 8
            bx[j * 2] = cx + (e1x - cx) * t; by[j * 2] = cy + (e1y - cy) * t
            bx[j * 2 + 1] = cx + (e2x - cx) * t; by[j * 2 + 1] = cy + (e2y - cy) * t
        }
        for (j in 0 until arcN) {
            val a = a1 + sweep * (j + 0.5) / arcN
            bx[16 + j] = cx + r * cos(a); by[16 + j] = cy + r * sin(a)
        }
        var back = 0.0
        for (j in 0 until nb) {
            val px = bx[j]; val py = by[j]
            var best = Double.MAX_VALUE
            for (i in xs.indices) {
                val dx = xs[i] - px; val dy = ys[i] - py
                val d2 = dx * dx + dy * dy
                if (d2 < best) best = d2
            }
            back += best
        }
        val s = fwd / xs.size + back / nb
        return if (s.isNaN()) Double.MAX_VALUE else s
    }
}
