package com.dsviewer.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 다 그린 펜 테이프를 다듬는 계산: 곧게 펴기, 아래 글자 줄에 맞추기.
 * 화면·비트맵을 모르는 순수 계산이라 JVM 단위 시험으로 확인한다 (그리는 일은 DocumentView).
 */
internal object TapeFit {

    /** 글자 줄을 찾는 범위: 테이프 가운데에서 위·아래로 이만큼 (pt) */
    const val FIT_RANGE = 40f

    /** 이보다 어두우면(0~255) 글자 점으로 본다 */
    const val DARK_LUMINANCE = 160

    /** 글자 줄을 찾으려고 그리는 흑백 그림의 화소 수 한도 */
    const val MAX_FIT_PIXELS = 8_000_000L

    /** 네모 테이프를 남길 만큼 큰가: 짧은 쪽이 화면에서 6dp 이상 */
    fun keepRect(w: Float, h: Float, scale: Float, density: Float): Boolean = min(w, h) * scale >= 6 * density

    /** 펜 테이프(선)를 남길 만큼 긴가: 점 2개 이상이고 길이가 화면에서 8dp 이상 */
    fun keepLine(pointCount: Int, length: Float, scale: Float, density: Float): Boolean =
        pointCount >= 2 && length * scale >= 8 * density

    /** 색 [rgb]가 글자 점인가: 밝기(0.299R+0.587G+0.114B)가 [DARK_LUMINANCE] 미만 */
    fun isDark(r: Int, g: Int, b: Int): Boolean = (r * 299 + g * 587 + b * 114) / 1000 < DARK_LUMINANCE

    /**
     * 글자 줄을 찾을 쪽 영역: 테이프를 감싸는 상자 ([bl], [bt], [br], [bb])를 사방으로 [FIT_RANGE]만큼 넓히고 쪽 [pw]×[ph] 안으로.
     * 돌려주는 값은 [왼쪽, 위, 오른쪽, 아래] (쪽 단위)
     */
    fun fitRegion(bl: Float, bt: Float, br: Float, bb: Float, pw: Float, ph: Float): FloatArray = floatArrayOf(
        max(0f, bl - FIT_RANGE), max(0f, bt - FIT_RANGE), min(pw, br + FIT_RANGE), min(ph, bb + FIT_RANGE),
    )

    /** 흑백 그림의 크기가 쓸 만한가: 폭·높이가 0보다 크고 화소 수가 [MAX_FIT_PIXELS] 이하 */
    fun fitBitmapOk(bw: Int, bh: Int): Boolean = bw > 0 && bh > 0 && bw.toLong() * bh <= MAX_FIT_PIXELS

    /**
     * 거의 곧게 그은 펜 테이프를 곧은 선 하나로 편다. 점들에 가장 잘 맞는 직선(주성분)에 첫 점과 끝 점을 내리고,
     * 가로·세로에 가까우면(5° 안) 딱 맞춘다. 많이 휘었으면 그대로 둔다
     */
    fun straighten(st: Stroke) {
        val n = st.count
        var mx = 0f; var my = 0f
        for (i in 0 until n) { mx += st.x(i); my += st.y(i) }
        mx /= n; my /= n
        var sxx = 0f; var sxy = 0f; var syy = 0f
        for (i in 0 until n) {
            val dx = st.x(i) - mx; val dy = st.y(i) - my
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        val th = 0.5 * atan2(2.0 * sxy, (sxx - syy).toDouble())
        val ux = cos(th).toFloat()
        val uy = sin(th).toFloat()
        var tMin = Float.MAX_VALUE; var tMax = -Float.MAX_VALUE; var dev = 0f
        for (i in 0 until n) {
            val dx = st.x(i) - mx; val dy = st.y(i) - my
            val t = dx * ux + dy * uy
            tMin = min(tMin, t); tMax = max(tMax, t)
            dev = max(dev, abs(dx * uy - dy * ux))
        }
        val len = tMax - tMin
        if (len <= 0f || dev > max(st.width * 0.6f, len * 0.12f)) return
        // 그은 방향(첫 점 → 끝 점)을 지킨다
        val forward = (st.x(n - 1) - st.x(0)) * ux + (st.y(n - 1) - st.y(0)) * uy >= 0f
        val (ta, tb) = if (forward) tMin to tMax else tMax to tMin
        var x0 = mx + ux * ta; var y0 = my + uy * ta
        var x1 = mx + ux * tb; var y1 = my + uy * tb
        val deg = Math.toDegrees(atan2(abs(y1 - y0).toDouble(), abs(x1 - x0).toDouble()))
        if (deg <= 5.0) { val y = (y0 + y1) / 2; y0 = y; y1 = y }
        else if (deg >= 85.0) { val x = (x0 + x1) / 2; x0 = x; x1 = x }
        val p = st.p(0)
        st.clearPoints()
        st.add(x0, y0, p)
        st.add(x1, y1, p)
    }

    /**
     * 펜 테이프를 아래 글자 줄에 맞춘다: 테이프를 따라 가며 수직 방향으로 [dark](쪽 좌표의 점이 어두운가)를 세어,
     * 테이프 가운데에서 가장 가까운 글자 줄의 위·아래 끝을 찾고 그 높이(+ 조금 여유)로 굵기를, 그 가운데로 자리를 옮긴다.
     * 글자가 없으면(아래가 비었으면) 그대로 둔다
     */
    fun fitToText(st: Stroke, dark: (x: Float, y: Float) -> Boolean) {
        // 테이프를 따라 1pt마다, 수직 방향 (-FIT_RANGE ~ +FIT_RANGE)을 0.5pt 간격으로 본다
        val step = 0.5f
        val m = (FIT_RANGE * 2 / step).toInt() + 1
        val hits = IntArray(m)
        var samples = 0
        for (i in 1 until st.count) {
            val dx = st.x(i) - st.x(i - 1)
            val dy = st.y(i) - st.y(i - 1)
            val len = hypot(dx, dy)
            if (len < 0.01f) continue
            val nx = -dy / len
            val ny = dx / len
            var d = 0f
            while (d < len) {
                val sx = st.x(i - 1) + dx * d / len
                val sy = st.y(i - 1) + dy * d / len
                for (j in 0 until m) {
                    val off = -FIT_RANGE + j * step
                    if (dark(sx + nx * off, sy + ny * off)) hits[j]++
                }
                samples++
                d += 1f
            }
        }
        if (samples == 0) return
        val need = max(1, (samples * 0.03f).toInt())
        val on = BooleanArray(m) { hits[it] >= need }
        // 가운데에서 가장 가까운 글자 줄 (테이프 굵기의 절반, 적어도 6pt 안)
        val center = m / 2
        val reach = (max(st.width / 2, 6f) / step).toInt()
        var start = -1
        for (dd in 0..reach) {
            if (center - dd >= 0 && on[center - dd]) { start = center - dd; break }
            if (center + dd < m && on[center + dd]) { start = center + dd; break }
        }
        if (start < 0) return
        var lo = start
        var hi = start
        // 위·아래로 넓혀 간다. 글자 안의 작은 틈(받침 사이 등)은 건너뛰고, 줄 사이 빈칸에서 멈춘다
        fun gapLimit() = max(1.5f, (hi - lo + 1) * step * 0.25f) / step
        repeat(2) {
            var gap = 0
            var j = lo - 1
            while (j >= 0) {
                if (on[j]) { lo = j; gap = 0 } else if (++gap > gapLimit()) break
                j--
            }
            gap = 0
            j = hi + 1
            while (j < m) {
                if (on[j]) { hi = j; gap = 0 } else if (++gap > gapLimit()) break
                j++
            }
        }
        // 찾는 범위 끝까지 이어지면 글자 줄이 아니라 그림·표 같은 것이므로 그대로 둔다
        if (lo == 0 || hi == m - 1) return
        val d0 = -FIT_RANGE + lo * step
        val d1 = -FIT_RANGE + (hi + 1) * step
        val h = d1 - d0
        val pad = max(1f, h * 0.15f)
        st.resize(h + pad * 2)
        // 글자 줄 가운데로 옮긴다 (점마다 앞뒤 선분의 수직 방향 평균으로)
        val shift = (d0 + d1) / 2
        val n = st.count
        val ox = FloatArray(n)
        val oy = FloatArray(n)
        for (i in 0 until n) {
            var nx = 0f; var ny = 0f
            for (j in intArrayOf(i - 1, i)) {
                if (j < 0 || j + 1 >= n) continue
                val dx = st.x(j + 1) - st.x(j)
                val dy = st.y(j + 1) - st.y(j)
                val len = hypot(dx, dy)
                if (len < 0.01f) continue
                nx += -dy / len; ny += dx / len
            }
            val len = hypot(nx, ny)
            if (len > 0f) { ox[i] = nx / len * shift; oy[i] = ny / len * shift }
        }
        for (i in 0 until n) st.offsetPoint(i, ox[i], oy[i])
    }
}
