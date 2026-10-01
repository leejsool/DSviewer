package com.dsviewer.app

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 보정 펜의 보조선: 화살표(직선·곡선·돼지꼬리)와 길이 표시(호, 가운데를 비움).
 * 입력은 ShapeFit.resample로 길이 기준 고르게 다시 뽑은 점 (페이지 좌표, y는 아래로).
 * 결과의 curves는 몸통 (실선·점선을 고를 수 있음), heads는 화살촉 (늘 실선)
 */
object ShapeGuides {
    /** 화살촉 날개가 몸통과 이루는 각 */
    private const val HEAD_ANGLE = 27.0 * PI / 180.0
    /** 이보다 짧게 그으면 맞추지 않는다 (pt) */
    private const val MIN_LEN = 6.0

    fun arrow(kind: ShapeKind, p: Pair<DoubleArray, DoubleArray>, width: Float): Fitted? {
        val body = when (kind) {
            ShapeKind.ARROW -> ShapeFit.line(p)?.let { l -> DoubleArray(4) { l[it].toDouble() } }
            ShapeKind.ARROW_CURVE -> curve(p)
            ShapeKind.ARROW_PIGTAIL -> pigtail(p, width)
            else -> null
        } ?: return null
        if (pathLength(body) < MIN_LEN) return null
        return Fitted(listOf(toFloats(body)), heads = listOf(head(body, width)))
    }

    // ---------- 곡선 화살표: 부드러운 포물선 ----------

    /**
     * 처음·끝 점을 지나는 2차 베지어(포물선 조각)로 맞춘다.
     * S자처럼 포물선으로는 많이 어긋나면 3차 베지어로 (그래도 부드럽게)
     */
    private fun curve(p: Pair<DoubleArray, DoubleArray>): DoubleArray? {
        val (xs, ys) = p
        val n = xs.size
        val len = hypot(xs[n - 1] - xs[0], ys[n - 1] - ys[0])
        if (len < MIN_LEN) return null
        return smoothCurve(xs, ys)
    }

    /** 처음·끝 점을 지나는 포물선 조각 (많이 어긋나면 3차 베지어)을 64칸으로 */
    private fun smoothCurve(xs: DoubleArray, ys: DoubleArray): DoubleArray {
        val n = xs.size
        val len = hypot(xs[n - 1] - xs[0], ys[n - 1] - ys[0])
        var best = fitBezier(xs, ys, 2)
        if (best.err > len * 0.03) {
            val cubic = fitBezier(xs, ys, 3)
            if (cubic.err < best.err * 0.7) best = cubic
        }
        return sampleBezier(best.ctrl, 64)
    }

    private class Bezier(val ctrl: DoubleArray, val err: Double)

    /** 끝점을 고정한 [deg]차 베지어 최소제곱 (매개변수는 곡선 위 가장 가까운 자리로 몇 번 다시 잡는다) */
    private fun fitBezier(xs: DoubleArray, ys: DoubleArray, deg: Int): Bezier {
        val n = xs.size
        val t = DoubleArray(n) { it / (n - 1.0) }
        val c = DoubleArray((deg + 1) * 2)
        c[0] = xs[0]; c[1] = ys[0]
        c[deg * 2] = xs[n - 1]; c[deg * 2 + 1] = ys[n - 1]
        val dense = 200
        var err = 0.0
        repeat(6) {
            if (deg == 2) {
                var nx = 0.0; var ny = 0.0; var d = 0.0
                for (i in 0 until n) {
                    val u = t[i]; val v = 1 - u
                    val b1 = 2 * u * v
                    val rx = xs[i] - v * v * c[0] - u * u * c[4]
                    val ry = ys[i] - v * v * c[1] - u * u * c[5]
                    nx += b1 * rx; ny += b1 * ry; d += b1 * b1
                }
                if (d > 1e-12) { c[2] = nx / d; c[3] = ny / d }
            } else {
                var a11 = 0.0; var a12 = 0.0; var a22 = 0.0
                var r1x = 0.0; var r1y = 0.0; var r2x = 0.0; var r2y = 0.0
                for (i in 0 until n) {
                    val u = t[i]; val v = 1 - u
                    val b0 = v * v * v; val b1 = 3 * u * v * v; val b2 = 3 * u * u * v; val b3 = u * u * u
                    val rx = xs[i] - b0 * c[0] - b3 * c[6]
                    val ry = ys[i] - b0 * c[1] - b3 * c[7]
                    a11 += b1 * b1; a12 += b1 * b2; a22 += b2 * b2
                    r1x += b1 * rx; r1y += b1 * ry; r2x += b2 * rx; r2y += b2 * ry
                }
                val det = a11 * a22 - a12 * a12
                if (abs(det) > 1e-12) {
                    c[2] = (a22 * r1x - a12 * r2x) / det; c[3] = (a22 * r1y - a12 * r2y) / det
                    c[4] = (a11 * r2x - a12 * r1x) / det; c[5] = (a11 * r2y - a12 * r1y) / det
                }
            }
            // 점마다 곡선 위 가장 가까운 자리 (앞으로만, 가까운 범위에서 찾아 순서가 뒤엉키지 않게)
            val s = sampleBezier(c, dense)
            var j = 0
            err = 0.0
            for (i in 0 until n) {
                var bestK = j
                var bestD = Double.MAX_VALUE
                for (k in j..min(dense, j + dense / 4)) {
                    val d = hypot(s[k * 2] - xs[i], s[k * 2 + 1] - ys[i])
                    if (d < bestD) { bestD = d; bestK = k }
                }
                j = bestK
                t[i] = bestK / dense.toDouble()
                err += bestD * bestD
            }
            err = sqrt(err / n)
        }
        return Bezier(c, err)
    }

    /** 베지어를 [m]칸으로 나눈 점 m+1개 */
    private fun sampleBezier(c: DoubleArray, m: Int): DoubleArray {
        val deg = c.size / 2 - 1
        val out = DoubleArray((m + 1) * 2)
        val tmp = DoubleArray(c.size)
        for (k in 0..m) {
            val u = k / m.toDouble()
            c.copyInto(tmp)
            // 드 카스텔조
            for (r in deg downTo 1) for (i in 0 until r) {
                tmp[i * 2] = tmp[i * 2] * (1 - u) + tmp[i * 2 + 2] * u
                tmp[i * 2 + 1] = tmp[i * 2 + 1] * (1 - u) + tmp[i * 2 + 3] * u
            }
            out[k * 2] = tmp[0]
            out[k * 2 + 1] = tmp[1]
        }
        return out
    }

    // ---------- 돼지꼬리 화살표 ----------

    /** 저절로 정하는 고리 크기 b (pt): 그린 고리가 없을 때. 닫힌 고리는 b의 약 [LOOP_SIZE]배 */
    private const val PIG_B = 11.0
    /** 고리 하나가 차지하는 길이 (b의 배수, 이웃 고리와 사이 포함) */
    private const val PIG_PITCH = 4.6
    /** 고리에서 원을 도는 가장 빠른 빠르기 (앞으로 가는 빠르기의 배수) */
    private const val PIG_SPIN = 3.6
    /** 고리 쪽에서 원이 커지는 비율 (0이면 바깥 휨과 고리 반지름이 같다) */
    private const val PIG_GROW = 0.5
    /** 고리들이 차지할 수 있는 선 길이의 비율 */
    private const val PIG_COVER = 0.62

    /**
     * 그린 획을 따라 부드럽게 휘며 고리를 감는 선. 바탕은 그린 획에 맞춘 부드러운 곡선(곡선 화살표와 같은 맞춤)이고,
     * 그 위에서 반지름 b인 원을 돌린다: 고리 앞뒤로는 바깥쪽으로 휘어 나갔다가 고리에서 안쪽으로 감는다.
     * 고리를 그렸으면 그 수·크기·감은 방향·자리를 따른다. 안 그렸으면 선이 길수록 고리가 많아지고 (크기는 그대로)
     * 가운데에서 휜 획의 안쪽으로 (곧으면 화면 위쪽으로) 감는다
     */
    private fun pigtail(p: Pair<DoubleArray, DoubleArray>, width: Float): DoubleArray? {
        val (xs, ys) = p
        val n = xs.size
        val sx = xs[0]; val sy = ys[0]
        val len = hypot(xs[n - 1] - sx, ys[n - 1] - sy)
        if (len < MIN_LEN * 2) return null
        val ux = (xs[n - 1] - sx) / len; val uy = (ys[n - 1] - sy) / len
        val along = DoubleArray(n) { (xs[it] - sx) * ux + (ys[it] - sy) * uy }
        val across = DoubleArray(n) { -(xs[it] - sx) * uy + (ys[it] - sy) * ux }

        val drawn = findLoops(along, across)
        var t0 = 0.5
        var b: Double
        val side: Double
        val centers = drawn.map { (i, j) -> (i + 1..j).sumOf { along[it] } / (j - i) / len }
        // 바탕 곡선: 그린 고리 부분을 뺀 점들로 맞춘다
        val inLoop = BooleanArray(n)
        for ((i, j) in drawn) for (k in i + 1..j) inLoop[k] = true
        val keep = (0 until n).filter { !inLoop[it] }
        val base = if (keep.size >= 6) smoothCurve(DoubleArray(keep.size) { xs[keep[it]] }, DoubleArray(keep.size) { ys[keep[it]] })
        else doubleArrayOf(sx, sy, xs[n - 1], ys[n - 1])
        if (drawn.isNotEmpty()) {
            var areaSum = 0.0
            var size = 0.0
            for ((i, j) in drawn) {
                var area = 0.0
                for (k in i + 1..j) {
                    val k2 = if (k == j) i + 1 else k + 1
                    area += along[k] * across[k2] - along[k2] * across[k]
                }
                areaSum += area
                size += loopExtent(along, across, i, j)
            }
            // 위로 갔다 뒤로 넘어오며 감은 방향 (넓이의 부호)이 고리가 놓일 쪽
            side = if (areaSum >= 0) 1.0 else -1.0
            t0 = (centers.first() + centers.last()) / 2
            // 맞춘 선의 닫힌 고리가 그린 고리만 하게
            b = size / drawn.size / LOOP_SIZE
        } else {
            // 바탕 곡선이 휜 쪽의 반대(안쪽)로 감는다. 거의 곧으면 화면 위쪽으로 (옆쪽 축 (−uy, ux)의 y가 ux)
            var sum = 0.0
            for (k in 0 until base.size / 2) sum += -(base[k * 2] - sx) * uy + (base[k * 2 + 1] - sy) * ux
            val mean = sum / (base.size / 2)
            side = if (abs(mean) > len * 0.02) -Math.signum(mean) else if (ux >= 0) -1.0 else 1.0
            b = PIG_B + width * 1.5
        }
        b = b.coerceIn(3.0, len * 0.2)
        // 고리를 그렸으면 그린 수만큼, 안 그렸으면 길이에 따라
        val loops = (if (drawn.isNotEmpty()) drawn.size else (len * PIG_COVER / (PIG_PITCH * b)).toInt()).coerceIn(1, 40)
        // 그린 만큼 감으면 그린 고리 사이 간격대로
        val pitch = if (loops == drawn.size && loops >= 2) (centers.last() - centers.first()) / (loops - 1) else 0.0
        return pigtailAlong(base, t0, b, side, loops, pitch)
    }

    /** 곧은 바탕 위의 돼지꼬리 선 (아이콘·크기 재기용) */
    fun pigtailPoints(
        sx: Double, sy: Double, ex: Double, ey: Double, t0: Double, b0: Double, side: Double, loops: Int = 1,
        pitch: Double = 0.0,
    ) = pigtailAlong(doubleArrayOf(sx, sy, ex, ey), t0, b0, side, loops, pitch)

    /**
     * 돼지꼬리 선의 점들: 바탕 곡선 [base]를 따라가며, 그 자리의 앞 방향 T·옆 방향 N으로
     * 반지름 b인 원 b·(sin φ·T − side·cos φ·N)을 더한다. 원의 크기는 처음과 끝에서 0으로 줄어
     * 처음·끝 점은 바탕 곡선 그대로다. φ는 고리 자리에서만 한 바퀴씩 (부드럽게 빨라졌다 느려지며) 돌고,
     * 그 밖에서는 0이라 선이 옆(바깥)으로 b만큼 휘어 나가 있다. 원의 안쪽 끝에서는 뒤로 가므로 고리가 생긴다.
     * t0은 고리들 가운데 자리(0..1), side는 고리가 놓일 쪽(±1), [pitch]는 고리 가운데 사이 간격 (0이면 고리 폭 + 조금)
     */
    private fun pigtailAlong(
        base: DoubleArray, t0: Double, b0: Double, side: Double, loops: Int, pitch: Double,
    ): DoubleArray {
        val m = max(240, loops * 90)
        val pts = resampleEven(base, m)
        val len = pathLength(base).coerceAtLeast(1e-6)
        var b = b0
        // 고리 하나의 반폭 w (매개변수): 가장 빠를 때 원을 도는 빠르기가 앞으로 가는 빠르기의 [PIG_SPIN]배
        // (클수록 고리가 동그랗게 크다). 고리 사이는 적어도 반폭의 0.4배
        var w = 1.5 * PI * b / (PIG_SPIN * len)
        var step = max(pitch, w * 2.4)
        val room = 0.7
        val span = (loops - 1) * step + 2 * w
        if (span > room) {
            b *= room / span; w *= room / span; step *= room / span
        }
        val total = (loops - 1) * step + 2 * w
        // 고리는 원이 거의 다 커진 곳(앞뒤 14% 안쪽)에
        val lo = total / 2 + 0.14
        val hi = 1 - total / 2 - 0.14
        val start = (if (lo < hi) t0.coerceIn(lo, hi) else 0.5) - total / 2
        val out = DoubleArray((m + 1) * 2)
        for (k in 0..m) {
            val t = k / m.toDouble()
            var phi = 0.0
            for (i in 0 until loops) {
                val s = ((t - (start + i * step)) / (2 * w)).coerceIn(0.0, 1.0)
                phi += 2 * PI * s * s * (3 - 2 * s)
            }
            // 앞 방향 (이웃 점으로)
            val k0 = max(0, k - 1); val k1 = min(m, k + 1)
            var tx = pts[k1 * 2] - pts[k0 * 2]; var ty = pts[k1 * 2 + 1] - pts[k0 * 2 + 1]
            val tl = hypot(tx, ty).takeIf { it > 1e-9 } ?: 1.0
            tx /= tl; ty /= tl
            // 옆 방향은 (−ty, tx)
            val nx = -ty; val ny = tx
            // 원 크기: 처음·끝에서 0, 가운데 대부분은 거의 1
            val sn = sin(PI * t)
            // 고리 쪽(φ = π)으로 갈수록 원이 조금 커진다: 바깥으로 휘는 폭보다 고리가 크게
            val r = b * (1 - (1 - sn) * (1 - sn)) * (1 + PIG_GROW * (1 - cos(phi)) / 2)
            val a = r * sin(phi)
            val q = -side * r * cos(phi)
            out[k * 2] = pts[k * 2] + a * tx + q * nx
            out[k * 2 + 1] = pts[k * 2 + 1] + a * ty + q * ny
        }
        return out
    }

    /** 꺾은선을 길이 기준 고르게 m칸 (점 m+1개)으로 */
    private fun resampleEven(p: DoubleArray, m: Int): DoubleArray {
        val n = p.size / 2
        val cum = DoubleArray(n)
        for (i in 1 until n) cum[i] = cum[i - 1] + hypot(p[i * 2] - p[i * 2 - 2], p[i * 2 + 1] - p[i * 2 - 1])
        val out = DoubleArray((m + 1) * 2)
        var j = 0
        for (k in 0..m) {
            val target = cum[n - 1] * k / m
            while (j < n - 2 && cum[j + 1] < target) j++
            val seg = cum[j + 1] - cum[j]
            val f = if (seg > 0) ((target - cum[j]) / seg).coerceIn(0.0, 1.0) else 0.0
            out[k * 2] = p[j * 2] + (p[j * 2 + 2] - p[j * 2]) * f
            out[k * 2 + 1] = p[j * 2 + 1] + (p[j * 2 + 3] - p[j * 2 + 1]) * f
        }
        return out
    }

    /**
     * 그린 고리들 (스스로 가로지르는 두 선분 i < j). 앞에서부터 가장 먼저 닫히는 고리를 찾고 그 뒤에서 다시 찾는다.
     * 손떨림으로 생긴 아주 작은 고리는 뺀다
     */
    private fun findLoops(a: DoubleArray, c: DoubleArray): List<Pair<Int, Int>> {
        val out = ArrayList<Pair<Int, Int>>()
        val n = a.size
        var i = 0
        while (i < n - 1) {
            var found = -1
            for (j in i + 2 until n - 1) if (segmentsCross(a, c, i, j)) { found = j; break }
            if (found >= 0 && found - i >= 4 && loopExtent(a, c, i, found) >= 3.0) {
                out.add(i to found)
                i = found + 1
            } else i++
        }
        return out
    }

    /** 고리 (i, j)의 크기: 가로·세로 폭 중 긴 쪽 */
    private fun loopExtent(a: DoubleArray, c: DoubleArray, i: Int, j: Int): Double {
        var lo = Double.MAX_VALUE; var hi = -Double.MAX_VALUE
        var lo2 = Double.MAX_VALUE; var hi2 = -Double.MAX_VALUE
        for (k in i + 1..j) {
            lo = min(lo, c[k]); hi = max(hi, c[k])
            lo2 = min(lo2, a[k]); hi2 = max(hi2, a[k])
        }
        return max(hi - lo, hi2 - lo2)
    }

    /** 반지름 1인 돼지꼬리 선의 닫힌 고리 크기. 고리 모양은 b에 비례한다 */
    private val LOOP_SIZE: Double by lazy {
        // 반지름 10, 길이 100으로 만들어 재고 10으로 나눈다 (고리 구간에 점이 넉넉하게)
        val pts = pigtailPoints(0.0, 0.0, 100.0, 0.0, 0.5, 10.0, 1.0)
        val m = pts.size / 2
        val a = DoubleArray(m) { pts[it * 2] }
        val c = DoubleArray(m) { pts[it * 2 + 1] }
        findLoops(a, c).firstOrNull()?.let { (i, j) -> loopExtent(a, c, i, j) / 10.0 } ?: 1.2
    }

    private fun segmentsCross(a: DoubleArray, c: DoubleArray, i: Int, j: Int): Boolean {
        fun cross(ox: Double, oy: Double, px: Double, py: Double, qx: Double, qy: Double) =
            (px - ox) * (qy - oy) - (py - oy) * (qx - ox)
        val d1 = cross(a[i], c[i], a[i + 1], c[i + 1], a[j], c[j])
        val d2 = cross(a[i], c[i], a[i + 1], c[i + 1], a[j + 1], c[j + 1])
        val d3 = cross(a[j], c[j], a[j + 1], c[j + 1], a[i], c[i])
        val d4 = cross(a[j], c[j], a[j + 1], c[j + 1], a[i + 1], c[i + 1])
        return d1 * d2 < 0 && d3 * d4 < 0
    }

    // ---------- 화살촉 ----------

    /** 화살촉 길이 (pt): 굵을수록 크게 */
    fun headLength(width: Float) = 6.0 + width * 3.2

    /** 몸통 끝의 열린 화살촉 (날개, 끝, 날개). 끝 가까운 몸통의 방향으로 */
    private fun head(body: DoubleArray, width: Float): FloatArray {
        val n = body.size / 2
        val tx = body[n * 2 - 2]; val ty = body[n * 2 - 1]
        val hl = min(headLength(width), pathLength(body) * 0.4)
        var k = n - 2
        var acc = 0.0
        while (k > 0) {
            acc += hypot(body[k * 2 + 2] - body[k * 2], body[k * 2 + 3] - body[k * 2 + 1])
            if (acc >= hl * 0.8) break
            k--
        }
        return headAt(tx, ty, tx - body[k * 2], ty - body[k * 2 + 1], hl)
    }

    /** (tx, ty)가 끝이고 (dx, dy) 방향을 가리키는 길이 hl의 화살촉 */
    fun headAt(tx: Double, ty: Double, dx: Double, dy: Double, hl: Double): FloatArray {
        val d = hypot(dx, dy).takeIf { it > 1e-9 } ?: 1.0
        val bx = -dx / d; val by = -dy / d
        val c = cos(HEAD_ANGLE); val s = sin(HEAD_ANGLE)
        return floatArrayOf(
            (tx + (bx * c - by * s) * hl).toFloat(), (ty + (bx * s + by * c) * hl).toFloat(),
            tx.toFloat(), ty.toFloat(),
            (tx + (bx * c + by * s) * hl).toFloat(), (ty + (-bx * s + by * c) * hl).toFloat(),
        )
    }

    // ---------- 길이 표시 ----------

    /** 처음·끝 점을 지나는 원호로 맞추고 가운데를 비운 두 조각 (비운 자리에 길이를 쓴다) */
    fun lengthMark(p: Pair<DoubleArray, DoubleArray>): Fitted? {
        val (xs, ys) = p
        val n = xs.size
        val sx = xs[0]; val sy = ys[0]; val ex = xs[n - 1]; val ey = ys[n - 1]
        val len = hypot(ex - sx, ey - sy)
        if (len < MIN_LEN * 2) return null
        val ux = (ex - sx) / len; val uy = (ey - sy) / len
        val mx = (sx + ex) / 2; val my = (sy + ey) / 2
        val a = DoubleArray(n) { (xs[it] - mx) * ux + (ys[it] - my) * uy }
        val q = DoubleArray(n) { -(xs[it] - mx) * uy + (ys[it] - my) * ux }
        val half = len / 2
        val minH = len * 0.02

        // 활꼴 높이 h(부호 있음)로 원이 정해진다: 반지름 R = (c² + h²)/(2|h|), 중심 (0, h ∓ R)
        fun err(h: Double): Double {
            val r = (half * half + h * h) / (2 * abs(h))
            val k = h - Math.signum(h) * r
            var e = 0.0
            for (i in 0 until n) {
                val d = hypot(a[i], q[i] - k) - r
                e += d * d
            }
            return e
        }
        val steps = 240
        val range = len * 1.2
        val step = range * 2 / steps
        var best = minH
        var bestE = Double.MAX_VALUE
        for (s in 0..steps) {
            val h = -range + s * step
            if (abs(h) < minH) continue
            val e = err(h)
            if (e < bestE) { bestE = e; best = h }
        }
        // 이웃 칸 사이를 황금 분할로 다듬는다 (부호는 그대로)
        val sign = Math.signum(best)
        var lo = max(abs(best) - step, minH)
        var hi = abs(best) + step
        val g = (sqrt(5.0) - 1) / 2
        repeat(30) {
            val m1 = hi - g * (hi - lo)
            val m2 = lo + g * (hi - lo)
            if (err(sign * m1) < err(sign * m2)) hi = m2 else lo = m1
        }
        var h = sign * (lo + hi) / 2
        // 원 하나에 활꼴 높이가 둘(작은 호 h, 큰 호 h ∓ 2R)이라 오차가 같으므로, 그린 획이 있는 쪽의 호를 고른다
        var qSum = 0.0
        for (i in 0 until n) qSum += q[i]
        if (qSum != 0.0 && Math.signum(qSum) != Math.signum(h)) {
            h -= Math.signum(h) * (half * half + h * h) / abs(h)
        }

        val r = (half * half + h * h) / (2 * abs(h))
        val k = h - Math.signum(h) * r
        val theta = 4 * atan(abs(h) / half)
        val a0 = atan2(-k, -half)
        // 꼭대기 (0, h)를 지나는 쪽으로 돈다
        val apex = atan2(h - k, 0.0)
        fun angDiff(x: Double, y: Double): Double {
            var d = (x - y) % (2 * PI)
            if (d > PI) d -= 2 * PI
            if (d < -PI) d += 2 * PI
            return abs(d)
        }
        val dir = if (angDiff(a0 + theta / 2, apex) <= angDiff(a0 - theta / 2, apex)) 1.0 else -1.0
        val arcLen = r * theta
        val gap = (arcLen * 0.22).coerceIn(14.0, 56.0).coerceAtMost(arcLen * 0.45) / arcLen
        fun piece(u0: Double, u1: Double): FloatArray {
            val m = 40
            val out = FloatArray((m + 1) * 2)
            for (i in 0..m) {
                val ang = a0 + dir * theta * (u0 + (u1 - u0) * i / m)
                val la = r * cos(ang)
                val lq = k + r * sin(ang)
                out[i * 2] = (mx + la * ux - lq * uy).toFloat()
                out[i * 2 + 1] = (my + la * uy + lq * ux).toFloat()
            }
            return out
        }
        return Fitted(listOf(piece(0.0, 0.5 - gap / 2), piece(0.5 + gap / 2, 1.0)))
    }

    // ---------- 도움 ----------

    private fun pathLength(pts: DoubleArray): Double {
        var s = 0.0
        for (i in 1 until pts.size / 2) s += hypot(pts[i * 2] - pts[i * 2 - 2], pts[i * 2 + 1] - pts[i * 2 - 1])
        return s
    }

    private fun toFloats(d: DoubleArray) = FloatArray(d.size) { d[it].toFloat() }
}
