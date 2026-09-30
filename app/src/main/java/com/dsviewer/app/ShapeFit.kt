package com.dsviewer.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** 보정 펜으로 그릴 수 있는 도형 */
enum class ShapeKind(val label: String) {
    LINE("직선"),
    QUADRATIC("이차"),
    CUBIC("삼차"),
    QUARTIC("사차"),
    CIRCLE("원"),
    ELLIPSE("타원"),
    /** 처음 찍은 점이 중심, 그은 선분의 길이가 반지름 */
    CIRCLE_CR("중심·반지름 원"),
    /** 중심에서 시작하든 호 끝에서 시작하든 한 획으로 */
    SECTOR("부채꼴"),
    /** 호부터 그리든 지름부터 그리든 한 획으로 */
    SEMICIRCLE("반원"),
    TRIANGLE("일반삼각형"),
    TRI_EQUILATERAL("정삼각형"),
    TRI_RIGHT("직각삼각형"),
    TRI_ISOSCELES("이등변삼각형"),
    TRI_RIGHT_ISOSCELES("직각이등변삼각형"),
    QUADRILATERAL("일반사각형"),
    SQUARE("정사각형"),
    RECTANGLE("직사각형"),
    RHOMBUS("마름모"),
    PARALLELOGRAM("평행사변형"),
    /** 이차곡선 x²/a²−y²/b²=±1 (축에 나란한). 한 가지를 그리면 반대쪽 가지도 */
    HYPERBOLA("쌍곡선"),
    /** 지수인지 로그인지는 그린 모양으로 알아서 */
    EXP_LOG("지수·로그"),
    /** y = (ax² + bx + c)·e^(kx) + d: 한쪽은 점근선으로 수렴, 다른 쪽은 발산, 극값 둘 */
    QUAD_EXP("이차×지수"),
    SINE("사인·코사인"),
    /** 한 획에 한 가지 (점근선 사이) */
    TANGENT("탄젠트"),
}

/**
 * 보정 결과: 곡선들(쌍곡선은 두 가지)과 보조선들 (좌표는 [x0, y0, x1, y1, ...]).
 * 보조선은 지수·로그·이차×지수·탄젠트·쌍곡선의 점근선, 사인·코사인의 축(가운데 가로선)
 */
class Fitted(val curves: List<FloatArray>, val guides: List<FloatArray> = emptyList())

/** 보조선(점근선·축)을 그리는 방식 */
enum class GuideStyle { NONE, DASHED, SOLID }

/**
 * 대충 그린 획을 고른 도형으로 맞춘다 (최소제곱).
 * 좌표는 페이지 좌표(PDF 포인트, y는 아래로). 결과는 [x0, y0, x1, y1, ...] 또는 맞출 수 없으면 null.
 * 함수(이차~사차)는 종이 가로를 x축, 위쪽을 y축으로 본 y = f(x).
 */
object ShapeFit {

    /** quick: 그리는 동안의 미리 보기. 오래 다듬는 도형(부채꼴·이차×지수·이등변삼각형)을 덜 다듬는다 */
    fun fit(kind: ShapeKind, st: Stroke, quick: Boolean = false): Fitted? {
        if (kind == ShapeKind.CIRCLE_CR) return centerRadius(st)?.let { Fitted(listOf(it)) }
        val p = resample(st) ?: return null
        when (kind) {
            ShapeKind.HYPERBOLA -> return ShapeCurves.hyperbola(p)
            ShapeKind.EXP_LOG -> return ShapeCurves.expOrLog(p)
            ShapeKind.QUAD_EXP -> return ShapeCurves.quadExp(p, quick)
            ShapeKind.SECTOR -> return ShapeCurves.sector(p, quick)
            ShapeKind.SEMICIRCLE -> return ShapeCurves.semicircle(p, quick)
            ShapeKind.SINE -> return ShapeCurves.sine(p)
            ShapeKind.TANGENT -> return ShapeCurves.tangent(p)
            else -> {}
        }
        val one = when (kind) {
            ShapeKind.LINE -> line(p)
            ShapeKind.QUADRATIC -> poly(p, 2)
            ShapeKind.CUBIC -> schematic(p, 3) ?: poly(p, 3)
            ShapeKind.QUARTIC -> schematic(p, 4) ?: poly(p, 4)
            ShapeKind.CIRCLE -> circle(p)
            ShapeKind.ELLIPSE -> ellipse(p)
            ShapeKind.CIRCLE_CR -> null
            ShapeKind.TRIANGLE, ShapeKind.TRI_EQUILATERAL, ShapeKind.TRI_RIGHT,
            ShapeKind.TRI_ISOSCELES, ShapeKind.TRI_RIGHT_ISOSCELES -> polygon(p, 3)?.let { Polygons.triangle(kind, it, p, quick) }
            ShapeKind.QUADRILATERAL, ShapeKind.SQUARE, ShapeKind.RECTANGLE,
            ShapeKind.RHOMBUS, ShapeKind.PARALLELOGRAM -> polygon(p, 4)?.let { Polygons.quad(kind, it) }
            else -> null
        }
        return one?.let { Fitted(listOf(it)) }
    }

    /**
     * 대충 그린 다각형의 꼭짓점 k개 ([x0,y0,x1,y1,...], 둘레 순서).
     * 1) 볼록 껍질을 구하고 넓이를 가장 적게 잃는 꼭짓점부터 빼서 k개로 줄이고 (Visvalingam)
     * 2) 각 변 가운데 부분의 점들로 직선을 맞춘 뒤, 이웃한 직선의 교점을 꼭짓점으로 삼는다.
     */
    internal fun polygon(p: Pair<DoubleArray, DoubleArray>, k: Int): DoubleArray? {
        val (xs, ys) = p
        val hull = convexHull(xs, ys)
        if (hull.size < k) return null
        val poly = hull.toMutableList()
        while (poly.size > k) {
            var best = 0
            var bestA = Double.MAX_VALUE
            for (i in poly.indices) {
                val a = poly[(i - 1 + poly.size) % poly.size]; val b = poly[i]; val c = poly[(i + 1) % poly.size]
                val area = abs((xs[b] - xs[a]) * (ys[c] - ys[a]) - (xs[c] - xs[a]) * (ys[b] - ys[a]))
                if (area < bestA) { bestA = area; best = i }
            }
            poly.removeAt(best)
        }
        val vx = DoubleArray(k) { xs[poly[it]] }
        val vy = DoubleArray(k) { ys[poly[it]] }
        // 너무 납작하면 도형이 아님
        var area2 = 0.0
        var perim = 0.0
        for (i in 0 until k) {
            val j = (i + 1) % k
            area2 += vx[i] * vy[j] - vx[j] * vy[i]
            perim += hypot(vx[j] - vx[i], vy[j] - vy[i])
        }
        if (abs(area2) / 2 < perim * perim * 0.01) return null

        // 변마다 직선 맞추기 (꼭짓점 가까운 15%와 변에서 멀리 떨어진 점은 빼고)
        val lines = Array(k) { i ->
            val j = (i + 1) % k
            val ex = vx[j] - vx[i]; val ey = vy[j] - vy[i]
            val len = hypot(ex, ey)
            val px = ArrayList<Double>(); val py = ArrayList<Double>()
            for (m in xs.indices) {
                val t = ((xs[m] - vx[i]) * ex + (ys[m] - vy[i]) * ey) / (len * len)
                if (t < 0.15 || t > 0.85) continue
                val d = abs((xs[m] - vx[i]) * ey - (ys[m] - vy[i]) * ex) / len
                if (d > len * 0.2) continue
                px += xs[m]; py += ys[m]
            }
            if (px.size < 5) doubleArrayOf(vx[i], vy[i], ex / len, ey / len)
            else {
                val mx = px.average(); val my = py.average()
                var cxx = 0.0; var cyy = 0.0; var cxy = 0.0
                for (m in px.indices) {
                    val dx = px[m] - mx; val dy = py[m] - my
                    cxx += dx * dx; cyy += dy * dy; cxy += dx * dy
                }
                val th = 0.5 * atan2(2 * cxy, cxx - cyy)
                doubleArrayOf(mx, my, cos(th), sin(th))
            }
        }
        val out = DoubleArray(k * 2)
        for (i in 0 until k) {
            val a = lines[(i - 1 + k) % k]; val b = lines[i]
            val cross = a[2] * b[3] - a[3] * b[2]
            var x = vx[i]; var y = vy[i]
            if (abs(cross) > 1e-6) {
                val t = ((b[0] - a[0]) * b[3] - (b[1] - a[1]) * b[2]) / cross
                val ix = a[0] + a[2] * t; val iy = a[1] + a[3] * t
                // 교점이 엉뚱하게 멀면 껍질의 꼭짓점을 그대로
                val near = min(
                    hypot(vx[(i + 1) % k] - vx[i], vy[(i + 1) % k] - vy[i]),
                    hypot(vx[(i - 1 + k) % k] - vx[i], vy[(i - 1 + k) % k] - vy[i])
                )
                if (hypot(ix - vx[i], iy - vy[i]) < near * 0.35) { x = ix; y = iy }
            }
            out[i * 2] = x; out[i * 2 + 1] = y
        }
        return out
    }

    /** 볼록 껍질 (점 번호, 둘레 순서) */
    private fun convexHull(xs: DoubleArray, ys: DoubleArray): List<Int> {
        val idx = xs.indices.sortedWith(compareBy<Int>({ xs[it] }, { ys[it] }))
        fun cross(o: Int, a: Int, b: Int) = (xs[a] - xs[o]) * (ys[b] - ys[o]) - (ys[a] - ys[o]) * (xs[b] - xs[o])
        val lower = ArrayList<Int>()
        for (i in idx) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], i) <= 0) lower.removeAt(lower.size - 1)
            lower += i
        }
        val upper = ArrayList<Int>()
        for (i in idx.reversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], i) <= 0) upper.removeAt(upper.size - 1)
            upper += i
        }
        lower.removeAt(lower.size - 1); upper.removeAt(upper.size - 1)
        return lower + upper
    }

    /** 처음 점이 중심, 처음 점~끝점 거리가 반지름인 원 */
    private fun centerRadius(st: Stroke): FloatArray? {
        if (st.count < 2) return null
        val cx = st.x(0).toDouble(); val cy = st.y(0).toDouble()
        val ex = st.x(st.count - 1).toDouble(); val ey = st.y(st.count - 1).toDouble()
        val r = hypot(ex - cx, ey - cy)
        if (r < 2) return null
        // 반지름을 그은 쪽에서부터 한 바퀴
        val start = atan2(ey - cy, ex - cx)
        return closedCurve(96) { t -> (cx + r * cos(start + t)) to (cy + r * sin(start + t)) }
    }

    /**
     * 교사가 칠판 폭 때문에 좁게·과장해서 그린 삼차/사차함수 보정.
     * 1) 그림에서 극점(삼차 2개, 사차 3개)의 x 위치를 찾고,
     * 2) 그 위치를 정확히 극점으로 갖는 진짜 다항식 f = k·∫∏(x−xᵢ)dx + C 를 극점 높이에 맞추고,
     * 3) 바깥 극점 밖의 두 팔은 f를 세로로 늘려 그린 팔 끝 높이까지 올린다 (극점에서 기울기 0으로 이어짐).
     * 극점 수가 맞지 않으면 null (보통 최소제곱으로 넘어감).
     */
    private fun schematic(p: Pair<DoubleArray, DoubleArray>, deg: Int): FloatArray? {
        val (xs0, ys0) = p
        val xmin = xs0.min(); val xmax = xs0.max()
        if (xmax - xmin < 4) return null
        // x 순서로 정렬한 함수 표본 (위쪽이 +y), x는 [-1, 1]
        val xm = (xmin + xmax) / 2; val xsc = (xmax - xmin) / 2
        val idx = xs0.indices.sortedBy { xs0[it] }
        val u = DoubleArray(idx.size) { (xs0[idx[it]] - xm) / xsc }
        val v = DoubleArray(idx.size) { -ys0[idx[it]] }
        val ext = findExtrema(u, v)
        if (ext.size != deg - 1) return null
        val ex = ext.map { u[it] }
        val ey = ext.map { smooth(v, it) }
        // F(x) = ∫ ∏(x − xᵢ) dx
        val F: (Double) -> Double = when (deg) {
            3 -> { x -> x * x * x / 3 - (ex[0] + ex[1]) * x * x / 2 + ex[0] * ex[1] * x }
            4 -> {
                val s1 = ex[0] + ex[1] + ex[2]
                val s2 = ex[0] * ex[1] + ex[0] * ex[2] + ex[1] * ex[2]
                val s3 = ex[0] * ex[1] * ex[2]
                ({ x -> x * x * x * x / 4 - s1 * x * x * x / 3 + s2 * x * x / 2 - s3 * x })
            }
            else -> return null
        }
        // 극점 높이에 k, C 최소제곱
        val fx = ex.map(F)
        val fm = fx.average(); val ym = ey.average()
        var sxy = 0.0; var sxx = 0.0
        for (i in fx.indices) { sxy += (fx[i] - fm) * (ey[i] - ym); sxx += (fx[i] - fm) * (fx[i] - fm) }
        if (sxx < 1e-12) return null
        val k = sxy / sxx
        val c = ym - k * fm
        // 첫 극점이 극소면 그 왼쪽 팔은 올라가야 한다: k의 부호가 그림과 맞는지
        val firstIsMin = ey[0] < ey[1]
        val f: (Double) -> Double = { x -> k * F(x) + c }
        val leftEndV = smooth(v, 0, 1); val rightEndV = smooth(v, v.size - 1, 1)
        if (firstIsMin != (f(-1.0) > f(ex[0]))) return null
        // 두 팔을 그린 끝 높이까지 세로로 늘림
        fun armScale(xe: Double, xEnd: Double, target: Double): Double {
            val base = f(xe)
            val d = f(xEnd) - base
            val t = target - base
            return if (abs(d) < 1e-9 || d * t <= 0) 1.0 else (t / d).coerceIn(0.3, 100.0)
        }
        val sL = armScale(ex.first(), -1.0, leftEndV)
        val sR = armScale(ex.last(), 1.0, rightEndV)
        val n = 200
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val x = -1.0 + 2.0 * i / (n - 1)
            val y = when {
                x < ex.first() -> f(ex.first()) + sL * (f(x) - f(ex.first()))
                x > ex.last() -> f(ex.last()) + sR * (f(x) - f(ex.last()))
                else -> f(x)
            }
            out[i * 2] = (xm + x * xsc).toFloat()
            out[i * 2 + 1] = (-y).toFloat()
        }
        return if (xs0.first() > xs0.last()) reversePoints(out) else out
    }

    /** 앞뒤 몇 점의 평균 (손떨림 줄이기) */
    internal fun smooth(v: DoubleArray, i: Int, w: Int = max(2, v.size / 40)): Double {
        var s = 0.0; var n = 0
        for (j in max(0, i - w)..min(v.size - 1, i + w)) { s += v[j]; n++ }
        return s / n
    }

    /**
     * 부드럽게 한 y에서 뚜렷한 극점(높이 차가 전체의 8% 이상)만 번갈아 찾는다.
     * 양 끝은 극점으로 치지 않는다. 결과는 표본 번호.
     */
    internal fun findExtrema(u: DoubleArray, v: DoubleArray): List<Int> {
        val n = v.size
        val sv = DoubleArray(n) { smooth(v, it) }
        val range = sv.max() - sv.min()
        if (range <= 0) return emptyList()
        val th = range * 0.08
        val out = ArrayList<Int>()
        // 지그재그: 마지막 기준점에서 th 이상 반대로 움직이면 방향이 바뀐 것
        var dir = 0
        var cand = 0
        for (i in 1 until n) {
            when (dir) {
                0 -> {
                    // 시작점에서 th 이상 움직이면 처음 방향이 정해진다 (시작점은 극점이 아님)
                    if (sv[i] - sv[0] > th) { dir = 1; cand = i }
                    else if (sv[0] - sv[i] > th) { dir = -1; cand = i }
                }
                1 -> if (sv[i] >= sv[cand]) cand = i else if (sv[cand] - sv[i] > th) { out += cand; dir = -1; cand = i }
                -1 -> if (sv[i] <= sv[cand]) cand = i else if (sv[i] - sv[cand] > th) { out += cand; dir = 1; cand = i }
            }
        }
        // 양 끝 5% 안의 것은 극점이 아니라 팔의 끝
        return out.filter { u[it] > -0.9 && u[it] < 0.9 }
    }

    /** 천천히 그린 곳에 점이 몰리지 않도록 길이 기준으로 고르게 다시 뽑는다 */
    private fun resample(st: Stroke): Pair<DoubleArray, DoubleArray>? {
        if (st.count < 3) return null
        var len = 0.0
        for (i in 1 until st.count) len += hypot((st.x(i) - st.x(i - 1)).toDouble(), (st.y(i) - st.y(i - 1)).toDouble())
        if (len < 4.0) return null
        val n = min(400, max(24, len.roundToInt()))
        val step = len / (n - 1)
        val xs = DoubleArray(n)
        val ys = DoubleArray(n)
        xs[0] = st.x(0).toDouble(); ys[0] = st.y(0).toDouble()
        var k = 1
        var acc = 0.0
        var target = step
        for (i in 1 until st.count) {
            val x0 = st.x(i - 1).toDouble(); val y0 = st.y(i - 1).toDouble()
            val x1 = st.x(i).toDouble(); val y1 = st.y(i).toDouble()
            val seg = hypot(x1 - x0, y1 - y0)
            while (seg > 0 && acc + seg >= target && k < n) {
                val t = (target - acc) / seg
                xs[k] = x0 + (x1 - x0) * t
                ys[k] = y0 + (y1 - y0) * t
                k++
                target += step
            }
            acc += seg
        }
        while (k < n) { xs[k] = st.x(st.count - 1).toDouble(); ys[k] = st.y(st.count - 1).toDouble(); k++ }
        return xs to ys
    }

    /** 주성분 방향의 직선. 수평·수직·45° 근처(4° 안)면 딱 맞춘다 */
    private fun line(p: Pair<DoubleArray, DoubleArray>): FloatArray? {
        val (xs, ys) = p
        val n = xs.size
        val mx = xs.average(); val my = ys.average()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in 0 until n) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        var th = 0.5 * atan2(2 * sxy, sxx - syy)
        th = snapAngle(th, PI / 4, 4.0)
        val c = cos(th); val s = sin(th)
        var tmin = Double.MAX_VALUE; var tmax = -Double.MAX_VALUE
        for (i in 0 until n) {
            val t = (xs[i] - mx) * c + (ys[i] - my) * s
            tmin = min(tmin, t); tmax = max(tmax, t)
        }
        if (tmax - tmin < 3) return null
        // 그린 방향 그대로 (시작점 쪽이 먼저)
        val t0 = (xs[0] - mx) * c + (ys[0] - my) * s
        val (a, b) = if (abs(t0 - tmin) <= abs(t0 - tmax)) tmin to tmax else tmax to tmin
        return floatArrayOf((mx + a * c).toFloat(), (my + a * s).toFloat(), (mx + b * c).toFloat(), (my + b * s).toFloat())
    }

    /** 각도를 unit(라디안)의 배수 근처(tolDeg 안)면 그 배수로 */
    private fun snapAngle(th: Double, unit: Double, tolDeg: Double): Double {
        val k = (th / unit).roundToInt()
        return if (abs(th - k * unit) <= tolDeg * PI / 180) k * unit else th
    }

    /** 원: x²+y²+Dx+Ey+F=0 을 최소제곱으로 (Kasa) */
    private fun circle(p: Pair<DoubleArray, DoubleArray>): FloatArray? {
        val (xs, ys) = p
        val mx = xs.average(); val my = ys.average()
        val a = Array(3) { DoubleArray(3) }
        val b = DoubleArray(3)
        for (i in xs.indices) {
            val x = xs[i] - mx; val y = ys[i] - my
            val row = doubleArrayOf(x, y, 1.0)
            val rhs = -(x * x + y * y)
            for (j in 0..2) {
                for (k in 0..2) a[j][k] += row[j] * row[k]
                b[j] += row[j] * rhs
            }
        }
        val s = solve(a, b) ?: return null
        val cx = -s[0] / 2; val cy = -s[1] / 2
        val r2 = cx * cx + cy * cy - s[2]
        if (r2 <= 1.0) return null
        val r = sqrt(r2)
        if (r > 5000) return null
        // 그리기 시작한 쪽에서부터 한 바퀴
        val start = atan2(ys[0] - my - cy, xs[0] - mx - cx)
        return closedCurve(96) { t ->
            val ang = start + t
            (mx + cx + r * cos(ang)) to (my + cy + r * sin(ang))
        }
    }

    /**
     * 타원: 길이 가중 공분산으로 기울기를 정하고, 그 방향으로 돌린 좌표에서
     * A u² + C v² + D u + E v = 1 을 최소제곱. 기울기가 0°/90° 근처(6° 안)면 딱 맞춘다.
     */
    private fun ellipse(p: Pair<DoubleArray, DoubleArray>): FloatArray? {
        val (xs, ys) = p
        val mx = xs.average(); val my = ys.average()
        var sxx = 0.0; var syy = 0.0; var sxy = 0.0
        for (i in xs.indices) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            sxx += dx * dx; syy += dy * dy; sxy += dx * dy
        }
        var th = 0.5 * atan2(2 * sxy, sxx - syy)
        th = snapAngle(th, PI / 2, 6.0)
        val c = cos(th); val s = sin(th)
        val a = Array(4) { DoubleArray(4) }
        val b = DoubleArray(4)
        for (i in xs.indices) {
            val dx = xs[i] - mx; val dy = ys[i] - my
            val u = dx * c + dy * s
            val v = -dx * s + dy * c
            val row = doubleArrayOf(u * u, v * v, u, v)
            for (j in 0..3) {
                for (k in 0..3) a[j][k] += row[j] * row[k]
                b[j] += row[j]
            }
        }
        val q = solve(a, b) ?: return null
        val aa = q[0]; val cc = q[1]
        if (aa <= 0 || cc <= 0) return null
        val u0 = -q[2] / (2 * aa); val v0 = -q[3] / (2 * cc)
        val g = 1 + aa * u0 * u0 + cc * v0 * v0
        if (g <= 0) return null
        val ra = sqrt(g / aa); val rb = sqrt(g / cc)
        if (ra > 5000 || rb > 5000 || ra < 1 || rb < 1) return null
        val cx = mx + u0 * c - v0 * s
        val cy = my + u0 * s + v0 * c
        return closedCurve(120) { t ->
            val u = ra * cos(t); val v = rb * sin(t)
            (cx + u * c - v * s) to (cy + u * s + v * c)
        }
    }

    /** y = f(x) 다항식 (위쪽이 +y). 그린 x 범위만큼만 그린다 */
    private fun poly(p: Pair<DoubleArray, DoubleArray>, deg: Int): FloatArray? {
        val (xs, ys) = p
        val xmin = xs.min(); val xmax = xs.max()
        if (xmax - xmin < 4) return null
        // 계산이 흔들리지 않도록 x를 [-1, 1]로, y를 대략 [-1, 1]로
        val xm = (xmin + xmax) / 2; val xsc = (xmax - xmin) / 2
        val yv = DoubleArray(ys.size) { -ys[it] }
        val ym = yv.average()
        val ysc = max(1.0, yv.maxOf { abs(it - ym) })
        val m = deg + 1
        val a = Array(m) { DoubleArray(m) }
        val b = DoubleArray(m)
        val pw = DoubleArray(2 * deg + 1)
        for (i in xs.indices) {
            val u = (xs[i] - xm) / xsc
            val v = (yv[i] - ym) / ysc
            pw[0] = 1.0
            for (k in 1..2 * deg) pw[k] = pw[k - 1] * u
            for (j in 0 until m) {
                for (k in 0 until m) a[j][k] += pw[j + k]
                b[j] += v * pw[j]
            }
        }
        val coef = solve(a, b) ?: return null
        val n = 160
        val out = FloatArray(n * 2)
        for (i in 0 until n) {
            val u = -1.0 + 2.0 * i / (n - 1)
            var v = 0.0
            for (k in deg downTo 0) v = v * u + coef[k]
            out[i * 2] = (xm + u * xsc).toFloat()
            out[i * 2 + 1] = (-(ym + v * ysc)).toFloat()
        }
        // 오른쪽에서 왼쪽으로 그렸으면 그 방향 그대로
        return if (xs.first() > xs.last()) reversePoints(out) else out
    }

    private inline fun closedCurve(n: Int, at: (Double) -> Pair<Double, Double>): FloatArray {
        val out = FloatArray((n + 1) * 2)
        for (i in 0..n) {
            val (x, y) = at(2 * PI * i / n)
            out[i * 2] = x.toFloat()
            out[i * 2 + 1] = y.toFloat()
        }
        return out
    }

    internal fun reversePoints(a: FloatArray): FloatArray {
        val n = a.size / 2
        val out = FloatArray(a.size)
        for (i in 0 until n) {
            out[i * 2] = a[(n - 1 - i) * 2]
            out[i * 2 + 1] = a[(n - 1 - i) * 2 + 1]
        }
        return out
    }

    /** 가우스 소거 (부분 피벗). 풀 수 없으면 null */
    internal fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val n = b.size
        for (col in 0 until n) {
            var piv = col
            for (r in col + 1 until n) if (abs(a[r][col]) > abs(a[piv][col])) piv = r
            if (abs(a[piv][col]) < 1e-12) return null
            if (piv != col) {
                val t = a[piv]; a[piv] = a[col]; a[col] = t
                val tb = b[piv]; b[piv] = b[col]; b[col] = tb
            }
            for (r in col + 1 until n) {
                val f = a[r][col] / a[col][col]
                for (k in col until n) a[r][k] -= f * a[col][k]
                b[r] -= f * b[col]
            }
        }
        val x = DoubleArray(n)
        for (r in n - 1 downTo 0) {
            var s = b[r]
            for (k in r + 1 until n) s -= a[r][k] * x[k]
            x[r] = s / a[r][r]
        }
        return x
    }
}
