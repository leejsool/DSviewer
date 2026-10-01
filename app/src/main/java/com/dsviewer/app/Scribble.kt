package com.dsviewer.app

import android.graphics.RectF
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * 긁어 지우기: 펜으로 좌우든 위아래든 마구 긁은 획이 덮은 영역.
 * 긁은 왕복의 꺾인 점 셋씩 이은 삼각형(왕복 사이 빈틈)과 긁은 선 자체(굵기 + 여유)를 합친 것
 */
class ScribbleRegion private constructor(
    private val tris: FloatArray,
    private val line: Stroke,
    private val band: Float,
) {
    val bounds = RectF()

    init {
        bounds.set(line.x(0), line.y(0), line.x(0), line.y(0))
        for (i in 1 until line.count) bounds.union(line.x(i), line.y(i))
        bounds.inset(-band, -band)
    }

    /** (x, y)에서 반지름 m인 원이 영역에 닿는지 */
    fun contains(x: Float, y: Float, m: Float): Boolean {
        if (x < bounds.left - m || x > bounds.right + m || y < bounds.top - m || y > bounds.bottom + m) return false
        val m2 = m * m
        for (t in tris.indices step 6) {
            val ax = tris[t]; val ay = tris[t + 1]
            val bx = tris[t + 2]; val by = tris[t + 3]
            val cx = tris[t + 4]; val cy = tris[t + 5]
            if (inTriangle(x, y, ax, ay, bx, by, cx, cy)) return true
            if (m > 0f && (segDist2(x, y, ax, ay, bx, by) <= m2 || segDist2(x, y, bx, by, cx, cy) <= m2 ||
                    segDist2(x, y, cx, cy, ax, ay) <= m2)) return true
        }
        val rr = band + m
        val rr2 = rr * rr
        for (i in 1 until line.count) {
            if (segDist2(x, y, line.x(i - 1), line.y(i - 1), line.x(i), line.y(i)) <= rr2) return true
        }
        return false
    }

    companion object {
        /** 적어도 이만큼 오가야 긁기로 본다 */
        private const val MIN_PASSES = 4
        /** 이어진 두 번이 이만큼 (코사인) 정반대여야 (약 127°보다 크게 되돌아감) */
        private const val REVERSE_COS = -0.6f
        /** 이어진 두 번의 길이가 짧은 쪽이 긴 쪽의 이만큼은 되어야 */
        private const val MIN_RATIO = 0.4f
        /** 한 번 오가는 길이가 곧은 거리의 이만큼보다 길면 (동그라미처럼 돈 것) 긁기가 아니다 */
        private const val MAX_BEND = 1.4f

        /**
         * 획이 마구 긁은 모양이면 그 영역, 아니면 null. [unit]은 1dp가 쪽 좌표로 얼마인지.
         * 꺾인 각도 대신 '앞으로 간 만큼에서 얼마나 되돌아왔는지'로 왕복을 센다.
         * 넓게 긁어 한 번 긋는 선이 활처럼 휘거나 끝에서 둥글게 돌아도 한 번으로 센다
         */
        fun detect(st: Stroke, unit: Float): ScribbleRegion? {
            if (st.count < 8) return null
            val minStart = 3f * unit
            // 되돌아간 점(한 번 오간 끝)들
            val turns = ArrayList<Int>()
            var s = 0       // 이번에 긋기 시작한 점
            var e = -1      // 이번에 가장 멀리 간 점 (아직 방향이 없으면 -1)
            for (i in 1 until st.count) {
                val x = st.x(i)
                val y = st.y(i)
                if (e < 0) {
                    if (hypot(x - st.x(s), y - st.y(s)) >= minStart) e = i
                    continue
                }
                val ex = st.x(e) - st.x(s)
                val ey = st.y(e) - st.y(s)
                val far = hypot(ex, ey)
                // 시작점에서 가장 먼 점 쪽으로 지금 점이 얼마나 갔는지 (방향은 먼 점을 따라가 휜 선도 됨)
                val along = ((x - st.x(s)) * ex + (y - st.y(s)) * ey) / far
                if (along >= far) {
                    e = i
                } else if (far - along > max(3f * unit, 0.3f * far)) {
                    // 간 길의 30% 넘게 되돌아왔다: 한 번 오감
                    turns.add(e)
                    s = e
                    e = if (hypot(x - st.x(s), y - st.y(s)) >= minStart) i else -1
                }
            }
            if (turns.size < MIN_PASSES) return null
            val pts = ArrayList<Int>().apply { add(0); addAll(turns) }
            // 오간 길마다: 곧은 방향, 곧은 거리, 휨(지나간 길이 / 곧은 거리)
            val n = pts.size - 1
            val vx = FloatArray(n)
            val vy = FloatArray(n)
            val chords = FloatArray(n)
            val bends = FloatArray(n)
            for (k in 0 until n) {
                val a = pts[k]
                val b = pts[k + 1]
                vx[k] = st.x(b) - st.x(a)
                vy[k] = st.y(b) - st.y(a)
                chords[k] = hypot(vx[k], vy[k])
                var path = 0f
                for (i in a + 1..b) path += hypot(st.x(i) - st.x(i - 1), st.y(i) - st.y(i - 1))
                bends[k] = if (chords[k] > 0f) path / chords[k] else 99f
            }
            // 점을 콕콕 찍은 것은 긁기가 아니다
            if (chords.sorted()[n / 2] < 5f * unit) return null
            // 긁기는 거의 같은 자리를 비슷한 길이로 정반대로 오간다. 이어진 두 번이 그런지 센다
            // (한 획으로 이어 쓴 글자 'ㅗㅇ'·동그라미 치기는 방향·길이가 들쭉날쭉하다)
            var good = 0
            for (k in 1 until n) {
                val cos = (vx[k] * vx[k - 1] + vy[k] * vy[k - 1]) / (chords[k] * chords[k - 1])
                val ratio = minOf(chords[k], chords[k - 1]) / maxOf(chords[k], chords[k - 1])
                if (cos < REVERSE_COS && ratio >= MIN_RATIO && bends[k] <= MAX_BEND && bends[k - 1] <= MAX_BEND) good++
            }
            if (good < MIN_PASSES - 1 || good < (n - 1) * 0.75f) return null
            pts.add(st.count - 1)
            val tris = FloatArray((pts.size - 2) * 6)
            for (k in 0 until pts.size - 2) {
                for (j in 0..2) {
                    tris[k * 6 + j * 2] = st.x(pts[k + j])
                    tris[k * 6 + j * 2 + 1] = st.y(pts[k + j])
                }
            }
            return ScribbleRegion(tris, st, st.halfWidth + 1.5f * unit)
        }

        private fun inTriangle(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float): Boolean {
            val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
            val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
            val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
            val neg = d1 < 0 || d2 < 0 || d3 < 0
            val pos = d1 > 0 || d2 > 0 || d3 > 0
            return !(neg && pos)
        }

        private fun segDist2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val vx = bx - ax
            val vy = by - ay
            val len2 = vx * vx + vy * vy
            val t = if (len2 == 0f) 0f else (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
            val dx = px - (ax + t * vx)
            val dy = py - (ay + t * vy)
            return dx * dx + dy * dy
        }
    }
}

/**
 * 획에서 [inside]인 부분을 [step] 간격으로 따라가며 잘라 낸 나머지 조각들.
 * 잘린 곳이 없으면 null, 모두 잘렸으면 빈 목록 (영역 지우개의 [Stroke.cut]과 같은 규칙)
 */
fun Stroke.cutWhere(step: Float, inside: (Float, Float) -> Boolean): List<Stroke>? {
    val pieces = ArrayList<Stroke>()
    var cur: Stroke? = null
    var hit = false
    fun sample(px: Float, py: Float, pp: Float) {
        if (inside(px, py)) {
            hit = true
            cur?.let { if (it.count >= 2 && it.length() >= max(0.5f, width * 0.3f)) pieces.add(it) }
            cur = null
        } else {
            (cur ?: Stroke(tool, color, width, dashed, pen).also { it.tape = tape; cur = it }).add(px, py, pp)
        }
    }
    sample(x(0), y(0), p(0))
    for (i in 1 until count) {
        val n = max(1, ceil(hypot(x(i) - x(i - 1), y(i) - y(i - 1)) / step).toInt())
        for (k in 1..n) {
            val f = k.toFloat() / n
            sample(x(i - 1) + (x(i) - x(i - 1)) * f, y(i - 1) + (y(i) - y(i - 1)) * f, p(i - 1) + (p(i) - p(i - 1)) * f)
        }
    }
    cur?.let { if (it.count >= 2 && it.length() >= max(0.5f, width * 0.3f)) pieces.add(it) }
    return if (hit) pieces else null
}

/** 두 획이 서로 가로지르는 횟수 ([limit]에 이르면 그만 셈) */
fun crossings(a: Stroke, b: Stroke, limit: Int): Int {
    var n = 0
    for (i in 1 until a.count) {
        val ax = a.x(i - 1); val ay = a.y(i - 1); val bx = a.x(i); val by = a.y(i)
        for (j in 1 until b.count) {
            val cx = b.x(j - 1); val cy = b.y(j - 1); val dx = b.x(j); val dy = b.y(j)
            val d1 = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
            val d2 = (bx - ax) * (dy - ay) - (by - ay) * (dx - ax)
            if ((d1 > 0f) == (d2 > 0f)) continue
            val d3 = (dx - cx) * (ay - cy) - (dy - cy) * (ax - cx)
            val d4 = (dx - cx) * (by - cy) - (dy - cy) * (bx - cx)
            if ((d3 > 0f) == (d4 > 0f)) continue
            if (++n >= limit) return n
        }
    }
    return n
}
