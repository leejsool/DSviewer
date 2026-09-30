package com.dsviewer.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 보정 펜 삼각형·사각형: ShapeFit.polygon이 찾은 꼭짓점에 종류별 조건(같은 변, 직각, 평행 …)을 맞춘다.
 * 입력·출력 좌표는 페이지 좌표 [x0,y0,x1,y1,...]. 출력은 첫 꼭짓점을 끝에 한 번 더 넣은 닫힌 꺾은선.
 * 변이 수평·수직에 가까우면(6° 안) 도형을 살짝 돌려 딱 맞춘다.
 */
object Polygons {

    private class P(var x: Double, var y: Double) {
        operator fun plus(o: P) = P(x + o.x, y + o.y)
        operator fun minus(o: P) = P(x - o.x, y - o.y)
        operator fun times(k: Double) = P(x * k, y * k)
        val len get() = hypot(x, y)
        val angle get() = atan2(y, x)
        fun unit() = P(x / len, y / len)
    }

    private fun dir(a: Double) = P(cos(a), sin(a))
    private const val SNAP = 6.0 * PI / 180

    private fun toPoints(v: DoubleArray) = List(v.size / 2) { P(v[it * 2], v[it * 2 + 1]) }

    private fun centroid(ps: List<P>) = P(ps.sumOf { it.x } / ps.size, ps.sumOf { it.y } / ps.size)

    /** 각도를 가장 가까운 90°의 배수로 옮기는 데 필요한 회전 (6° 밖이면 0) */
    private fun snapDelta(angle: Double): Double {
        val unit = PI / 2
        val d = (angle / unit).roundToInt() * unit - angle
        return if (abs(d) <= SNAP) d else 0.0
    }

    private fun rotate(ps: List<P>, c: P, d: Double): List<P> {
        if (d == 0.0) return ps
        val cs = cos(d); val sn = sin(d)
        return ps.map { val q = it - c; P(c.x + q.x * cs - q.y * sn, c.y + q.x * sn + q.y * cs) }
    }

    /** 수평·수직에 가장 가까운 변을 딱 맞추도록 c를 중심으로 돌린다 */
    private fun snapEdges(ps: List<P>, c: P): List<P> {
        var best = 0.0
        var bestAbs = Double.MAX_VALUE
        for (i in ps.indices) {
            val d = snapDelta((ps[(i + 1) % ps.size] - ps[i]).angle)
            if (d != 0.0 && abs(d) < bestAbs) { bestAbs = abs(d); best = d }
        }
        return rotate(ps, c, best)
    }

    private fun closed(ps: List<P>): FloatArray {
        val out = FloatArray((ps.size + 1) * 2)
        for (i in 0..ps.size) {
            val q = ps[i % ps.size]
            out[i * 2] = q.x.toFloat(); out[i * 2 + 1] = q.y.toFloat()
        }
        return out
    }

    /** 둘레 방향 (+1/−1) */
    private fun orientation(ps: List<P>): Double {
        var a = 0.0
        for (i in ps.indices) { val j = (i + 1) % ps.size; a += ps[i].x * ps[j].y - ps[j].x * ps[i].y }
        return if (a >= 0) 1.0 else -1.0
    }

    /** 정n각형: 중심과 크기·돌림각을 꼭짓점에 최소제곱으로 */
    private fun regular(ps: List<P>): List<P> {
        val n = ps.size
        val c = centroid(ps)
        val s = orientation(ps)
        var wr = 0.0; var wi = 0.0
        for (i in 0 until n) {
            val z = ps[i] - c
            val a = -s * 2 * PI * i / n
            wr += z.x * cos(a) - z.y * sin(a)
            wi += z.x * sin(a) + z.y * cos(a)
        }
        val r = hypot(wr, wi) / n
        val th = atan2(wi, wr)
        return List(n) { i -> c + dir(th + s * 2 * PI * i / n) * r }
    }

    /** 꼭짓점 i의 내각 */
    private fun angleAt(ps: List<P>, i: Int): Double {
        val a = ps[(i - 1 + ps.size) % ps.size] - ps[i]
        val b = ps[(i + 1) % ps.size] - ps[i]
        return acos(((a.x * b.x + a.y * b.y) / (a.len * b.len)).coerceIn(-1.0, 1.0))
    }

    /** pts는 그린 획의 점들 (이등변삼각형은 획 전체에 맞춘다). quick이면 덜 다듬는다 (그리는 동안의 미리 보기) */
    fun triangle(kind: ShapeKind, v: DoubleArray, pts: Pair<DoubleArray, DoubleArray>? = null, quick: Boolean = false): FloatArray {
        val ps = toPoints(v)
        val out = when (kind) {
            ShapeKind.TRI_EQUILATERAL -> regular(ps).let { snapEdges(it, centroid(it)) }
            ShapeKind.TRI_RIGHT -> rightTriangle(ps, isosceles = false)
            ShapeKind.TRI_RIGHT_ISOSCELES -> rightTriangle(ps, isosceles = true)
            ShapeKind.TRI_ISOSCELES -> isosceles(ps, pts, quick)
            else -> snapEdges(ps, centroid(ps))
        }
        return closed(out)
    }

    /** 직각에 가장 가까운 꼭짓점을 정확히 90°로 (두 변 방향은 원래의 가운데를 기준으로 ±45°) */
    private fun rightTriangle(ps: List<P>, isosceles: Boolean): List<P> {
        val ia = (0..2).minBy { abs(angleAt(ps, it) - PI / 2) }
        val a = ps[ia]; val b = ps[(ia + 1) % 3]; val c = ps[(ia + 2) % 3]
        val ub = (b - a).unit(); val uc = (c - a).unit()
        val mid = (ub + uc).angle
        var db = mid + PI / 4; var dc = mid - PI / 4
        // 원래 방향에 가까운 쪽으로 짝짓기
        if (abs(angleDiff(db, ub.angle)) > abs(angleDiff(dc, ub.angle))) { val t = db; db = dc; dc = t }
        var lb = (b - a).len; var lc = (c - a).len
        if (isosceles) { lb = (lb + lc) / 2; lc = lb }
        // 한 변이 수평·수직에 가까우면 직각 꼭짓점을 중심으로 돌려 맞춘다
        val d = listOf(snapDelta(db), snapDelta(dc)).filter { it != 0.0 }.minByOrNull { abs(it) } ?: 0.0
        val out = MutableList(3) { a }
        out[(ia + 1) % 3] = a + dir(db + d) * lb
        out[(ia + 2) % 3] = a + dir(dc + d) * lc
        return out
    }

    /**
     * 이등변삼각형: 꼭대기(3가지)마다 꼭짓점으로 먼저 대칭을 맞추고, 그린 획 전체에 가장 가깝도록
     * 꼭대기 위치·축 방향·높이·밑변 반폭을 다듬는다 (획 → 변, 변 → 획 두 방향 거리).
     * 꼭짓점만 보면 모서리를 둥글게 그린 곳이나 치우친 꼭대기에 끌려 그린 것과 어긋나 보인다.
     * 대충 그리면 정삼각형에 가까워 어느 꼭대기든 비슷하게 맞으므로, 밑변이 수평·수직에 가까운 쪽을 조금 더 친다.
     */
    private fun isosceles(ps: List<P>, pts: Pair<DoubleArray, DoubleArray>?, quick: Boolean): List<P> {
        val size = (0..2).sumOf { (ps[(it + 1) % 3] - ps[it]).len } / 3
        if (pts == null || size < 1) return isoscelesInit(ps, 0).let { fromParams(it, 0, ps) }
        val (xs, ys) = thin(pts, if (quick) 60 else 100)
        val o = centroid(ps)
        val nx = DoubleArray(xs.size) { (xs[it] - o.x) / size }
        val ny = DoubleArray(ys.size) { (ys[it] - o.y) / size }
        fun norm(q: DoubleArray) = doubleArrayOf((q[0] - o.x) / size, (q[1] - o.y) / size, q[2], q[3] / size, q[4] / size)
        fun denorm(q: DoubleArray) = doubleArrayOf(o.x + q[0] * size, o.y + q[1] * size, q[2], q[3] * size, q[4] * size)
        fun score(q: DoubleArray) = outlineDistance(isoVertices(q), nx, ny)

        var best: DoubleArray? = null
        var bestApex = 0
        var bestCost = Double.MAX_VALUE
        for (ia in 0..2) {
            val q = ShapeCurves.nelderMead(norm(isoscelesInit(ps, ia)), 0.05, ::score, if (quick) 120 else 300)
            val base = q[2] + PI / 2
            val unit = PI / 2
            val dev = abs(base - (base / unit).roundToInt() * unit)  // 0 ~ 45°
            val cost = score(q) * (1 + 1.5 * dev / (PI / 4))
            if (cost < bestCost) { bestCost = cost; best = q; bestApex = ia }
        }
        var q = best!!
        // 축이 수평·수직에 가까우면 딱 맞추고 나머지를 다시 다듬는다.
        // 일부러 조금 기울여 그린 것이면(맞추면 그린 것과 눈에 띄게 멀어지면) 그대로 둔다
        val d = snapDelta(q[2])
        if (d != 0.0) {
            val phi = q[2] + d
            val r = ShapeCurves.nelderMead(doubleArrayOf(q[0], q[1], q[3], q[4]), 0.03, { t ->
                score(doubleArrayOf(t[0], t[1], phi, t[2], t[3]))
            }, if (quick) 80 else 200)
            val snapped = doubleArrayOf(r[0], r[1], phi, r[2], r[3])
            if (sqrt(score(snapped)) - sqrt(score(q)) < 0.006) q = snapped
        }
        return fromParams(denorm(q), bestApex, ps)
    }

    /** 모수 [꼭대기 x, y, 축 방향, 높이, 밑변 반폭(부호 있음)] → 꼭대기, 밑변 두 끝 */
    private fun isoVertices(q: DoubleArray): List<P> {
        val a = P(q[0], q[1]); val u = dir(q[2]); val perp = P(-u.y, u.x)
        return listOf(a, a + u * q[3] + perp * q[4], a + u * q[3] - perp * q[4])
    }

    /** 원래 꼭짓점 순서대로 (꼭대기가 ia번) */
    private fun fromParams(q: DoubleArray, ia: Int, ps: List<P>): List<P> {
        val v = isoVertices(q)
        val out = MutableList(3) { v[0] }
        out[(ia + 1) % 3] = v[1]
        out[(ia + 2) % 3] = v[2]
        return out
    }

    /**
     * 꼭짓점에 최소제곱으로 맞춘 이등변삼각형의 모수 (꼭대기가 ia번, 축은 꼭대기 → 밑변 가운데).
     * 축 방향 좌표 t, 옆 방향 좌표 s에서 꼭대기와 밑변은 따로, 옆 위치는 세 점의 평균으로 맞춘다.
     */
    private fun isoscelesInit(ps: List<P>, ia: Int): DoubleArray {
        val a = ps[ia]; val b = ps[(ia + 1) % 3]; val c = ps[(ia + 2) % 3]
        val m = (b + c) * 0.5
        val phi = (m - a).angle
        val u = dir(phi); val perp = P(-u.y, u.x)
        fun t(p: P) = (p - a).x * u.x + (p - a).y * u.y
        fun s(p: P) = (p - a).x * perp.x + (p - a).y * perp.y
        val sc = (s(b) + s(c)) / 3
        val apex = a + perp * sc
        return doubleArrayOf(apex.x, apex.y, phi, (t(b) + t(c)) / 2, (s(b) - s(c)) / 2)
    }

    private fun thin(p: Pair<DoubleArray, DoubleArray>, max: Int): Pair<DoubleArray, DoubleArray> {
        val (xs, ys) = p
        if (xs.size <= max) return p
        return DoubleArray(max) { xs[it * (xs.size - 1) / (max - 1)] } to DoubleArray(max) { ys[it * (ys.size - 1) / (max - 1)] }
    }

    /**
     * 닫힌 다각형과 그린 획이 얼마나 떨어져 있나: 획의 점에서 변까지 거리 제곱의 평균 +
     * 변 위에 고르게 찍은 점에서 획까지 거리 제곱의 평균 (그리지 않은 곳으로 변이 뻗지 않도록)
     */
    private fun outlineDistance(vs: List<P>, xs: DoubleArray, ys: DoubleArray): Double {
        val k = vs.size
        var fwd = 0.0
        for (i in xs.indices) {
            var best = Double.MAX_VALUE
            for (e in 0 until k) best = minOf(best, segDist2(xs[i], ys[i], vs[e], vs[(e + 1) % k]))
            fwd += best
        }
        var back = 0.0
        val per = 8
        for (e in 0 until k) {
            val a = vs[e]; val b = vs[(e + 1) % k]
            for (j in 0 until per) {
                val t = (j + 0.5) / per
                val x = a.x + (b.x - a.x) * t; val y = a.y + (b.y - a.y) * t
                var best = Double.MAX_VALUE
                for (i in xs.indices) {
                    val dx = xs[i] - x; val dy = ys[i] - y
                    best = minOf(best, dx * dx + dy * dy)
                }
                back += best
            }
        }
        return fwd / xs.size + back / (k * per)
    }

    private fun segDist2(x: Double, y: Double, a: P, b: P): Double {
        val ex = b.x - a.x; val ey = b.y - a.y
        val l2 = ex * ex + ey * ey
        val t = if (l2 < 1e-12) 0.0 else (((x - a.x) * ex + (y - a.y) * ey) / l2).coerceIn(0.0, 1.0)
        val dx = a.x + ex * t - x; val dy = a.y + ey * t - y
        return dx * dx + dy * dy
    }

    private fun angleDiff(a: Double, b: Double): Double {
        var d = a - b
        while (d > PI) d -= 2 * PI
        while (d < -PI) d += 2 * PI
        return d
    }

    fun quad(kind: ShapeKind, v: DoubleArray): FloatArray {
        val ps = toPoints(v)
        val out = when (kind) {
            ShapeKind.SQUARE -> regular(ps).let { snapEdges(it, centroid(it)) }
            ShapeKind.RECTANGLE -> rectangle(ps)
            ShapeKind.RHOMBUS -> rhombus(ps)
            ShapeKind.PARALLELOGRAM -> parallelogram(ps)
            else -> snapEdges(ps, centroid(ps))
        }
        return closed(out)
    }

    /** 변 방향의 평균(90° 주기)을 축으로, 꼭짓점들의 평균 거리를 가로·세로 반폭으로 */
    private fun rectangle(ps: List<P>): List<P> {
        val c = centroid(ps)
        var zr = 0.0; var zi = 0.0
        for (i in 0 until 4) {
            val e = ps[(i + 1) % 4] - ps[i]
            val a = 4 * e.angle
            zr += e.len * cos(a); zi += e.len * sin(a)
        }
        var phi = atan2(zi, zr) / 4
        phi += snapDelta(phi)
        val e1 = dir(phi); val e2 = P(-e1.y, e1.x)
        val a = ps.sumOf { abs((it - c).x * e1.x + (it - c).y * e1.y) } / 4
        val b = ps.sumOf { abs((it - c).x * e2.x + (it - c).y * e2.y) } / 4
        return listOf(c - e1 * a - e2 * b, c + e1 * a - e2 * b, c + e1 * a + e2 * b, c - e1 * a + e2 * b)
    }

    /** 두 대각선을 서로 수직이등분으로 */
    private fun rhombus(ps: List<P>): List<P> {
        val c = centroid(ps)
        val d1 = ps[2] - ps[0]; val d2 = ps[3] - ps[1]
        // 두 대각선 방향(180° 주기)의 평균: d1과 (d2 − 90°)
        val a1 = 2 * d1.angle; val a2 = 2 * (d2.angle - PI / 2)
        var phi = atan2(sin(a1) + sin(a2), cos(a1) + cos(a2)) / 2
        phi += snapDelta(phi)
        val e1 = dir(phi); val e2 = P(-e1.y, e1.x)
        val p = d1.len / 2; val q = d2.len / 2
        // 원래 대각선 방향과 같은 쪽으로
        val s1 = if (d1.x * e1.x + d1.y * e1.y >= 0) 1.0 else -1.0
        val s2 = if (d2.x * e2.x + d2.y * e2.y >= 0) 1.0 else -1.0
        return listOf(c - e1 * (p * s1), c - e2 * (q * s2), c + e1 * (p * s1), c + e2 * (q * s2))
    }

    /** 마주 보는 변을 평균 내어 평행하게. 밑변이 수평·수직에 가까우면 딱 맞춘다 */
    private fun parallelogram(ps: List<P>): List<P> {
        val c = centroid(ps)
        var p = ((ps[1] - ps[0]) + (ps[2] - ps[3])) * 0.5
        var q = ((ps[3] - ps[0]) + (ps[2] - ps[1])) * 0.5
        // 수평·수직에 더 가까운 변 하나만 딱 맞춘다 (다른 변의 기울기는 그대로)
        val dp = snapDelta(p.angle); val dq = snapDelta(q.angle)
        if (dp != 0.0 && (dq == 0.0 || abs(dp) <= abs(dq))) p = dir(p.angle + dp) * p.len
        else if (dq != 0.0) q = dir(q.angle + dq) * q.len
        return listOf(c - (p + q) * 0.5, c + (p - q) * 0.5, c + (p + q) * 0.5, c + (q - p) * 0.5)
    }
}
