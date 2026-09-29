package com.dsviewer.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

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

    fun triangle(kind: ShapeKind, v: DoubleArray): FloatArray {
        val ps = toPoints(v)
        val out = when (kind) {
            ShapeKind.TRI_EQUILATERAL -> regular(ps).let { snapEdges(it, centroid(it)) }
            ShapeKind.TRI_RIGHT -> rightTriangle(ps, isosceles = false)
            ShapeKind.TRI_RIGHT_ISOSCELES -> rightTriangle(ps, isosceles = true)
            ShapeKind.TRI_ISOSCELES -> isosceles(ps)
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
     * 꼭대기를 골라 대칭축에 맞춘 이등변삼각형.
     * 대충 그리면 정삼각형에 가까워 어느 꼭짓점이든 두 변이 비슷하므로,
     * '두 변이 비슷한 정도'에 '마주 보는 밑변이 수평·수직에 가까운 정도'를 더해 고른다.
     */
    private fun isosceles(ps: List<P>): List<P> {
        val ia = (0..2).minBy {
            val l1 = (ps[(it + 1) % 3] - ps[it]).len; val l2 = (ps[(it + 2) % 3] - ps[it]).len
            val base = (ps[(it + 2) % 3] - ps[(it + 1) % 3]).angle
            val unit = PI / 2
            val dev = abs(base - (base / unit).roundToInt() * unit)  // 0 ~ 45°
            abs(l1 - l2) / maxOf(l1, l2) + 0.15 * dev / (PI / 4)
        }
        val a = ps[ia]; val b = ps[(ia + 1) % 3]; val c = ps[(ia + 2) % 3]
        val m = (b + c) * 0.5
        var axis = (m - a).angle
        axis += snapDelta(axis)
        val u = dir(axis); val perp = P(-u.y, u.x)
        val h = (m - a).len
        val w = abs((b - c).x * perp.x + (b - c).y * perp.y) / 2
        val sb = if ((b - m).x * perp.x + (b - m).y * perp.y >= 0) 1.0 else -1.0
        val out = MutableList(3) { a }
        out[(ia + 1) % 3] = a + u * h + perp * (w * sb)
        out[(ia + 2) % 3] = a + u * h - perp * (w * sb)
        return out
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
