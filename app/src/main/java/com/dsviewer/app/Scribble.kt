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
        /** 꺾임이 이만큼 (코사인) 되돌아가면 왕복의 끝 (약 135°보다 크게 꺾임) */
        private const val REVERSE_COS = -0.7f
        /** 적어도 이만큼 되돌아가야 긁기로 본다 (네 번 넘게 오감) */
        private const val MIN_REVERSALS = 3

        /**
         * 획이 마구 긁은 모양이면 그 영역, 아니면 null. [unit]은 1dp가 쪽 좌표로 얼마인지.
         * 꺾인 점 대부분이 거의 반대로 되돌아가야 한다 (지그재그·용수철처럼 비스듬히 꺾인 선은 아님)
         */
        fun detect(st: Stroke, unit: Float): ScribbleRegion? {
            if (st.count < 8) return null
            val v = simplify(st, 2.5f * unit)
            if (v.size < 2 + MIN_REVERSALS) return null
            // 꺾인 점마다 되돌아가는지
            val turns = ArrayList<Int>()
            var corners = 0
            for (k in 1 until v.size - 1) {
                val a = v[k - 1]; val b = v[k]; val c = v[k + 1]
                val ux = st.x(b) - st.x(a); val uy = st.y(b) - st.y(a)
                val wx = st.x(c) - st.x(b); val wy = st.y(c) - st.y(b)
                val lu = hypot(ux, uy); val lw = hypot(wx, wy)
                if (lu == 0f || lw == 0f) continue
                corners++
                if ((ux * wx + uy * wy) / (lu * lw) < REVERSE_COS) turns.add(b)
            }
            if (turns.size < MIN_REVERSALS || turns.size < corners * 0.6f) return null
            val pts = ArrayList<Int>().apply { add(0); addAll(turns); add(st.count - 1) }
            // 한 번 오가는 길이가 너무 짧으면 (점을 콕콕 찍은 것) 긁기가 아니다
            val lens = (1 until pts.size).map { hypot(st.x(pts[it]) - st.x(pts[it - 1]), st.y(pts[it]) - st.y(pts[it - 1])) }.sorted()
            if (lens[lens.size / 2] < 5f * unit) return null
            val tris = FloatArray((pts.size - 2) * 6)
            for (k in 0 until pts.size - 2) {
                for (j in 0..2) {
                    tris[k * 6 + j * 2] = st.x(pts[k + j])
                    tris[k * 6 + j * 2 + 1] = st.y(pts[k + j])
                }
            }
            return ScribbleRegion(tris, st, st.halfWidth + 1.5f * unit)
        }

        /** 더글라스-포이커로 줄인 점 번호들 (처음·끝 포함) */
        private fun simplify(st: Stroke, tol: Float): List<Int> {
            val keep = BooleanArray(st.count)
            keep[0] = true
            keep[st.count - 1] = true
            val stack = ArrayDeque<Pair<Int, Int>>()
            stack.add(0 to st.count - 1)
            val tol2 = tol * tol
            while (stack.isNotEmpty()) {
                val (a, b) = stack.removeLast()
                var best = -1
                var bestD = tol2
                for (i in a + 1 until b) {
                    val d = segDist2(st.x(i), st.y(i), st.x(a), st.y(a), st.x(b), st.y(b))
                    if (d > bestD) { bestD = d; best = i }
                }
                if (best >= 0) {
                    keep[best] = true
                    stack.add(a to best)
                    stack.add(best to b)
                }
            }
            return keep.indices.filter { keep[it] }
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
