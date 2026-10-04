package com.dsviewer.app

import kotlin.math.max
import kotlin.math.min

/**
 * 문서 화면의 쪽 배치: 쪽을 한 줄에 하나(양쪽 보기면 둘)씩 쌓아 각 쪽의 [lefts]·[tops]와 문서 크기 [docW]·[docH]를 정하고,
 * 스크롤·확대 상태(offX, offY, scale)로 '지금 보이는 쪽', '눌린 곳이 어느 쪽 어디인지'를 알려 준다.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다. 길이는 모두 쪽 좌표(pt)이고,
 * 스크롤 위치·화면 크기는 화면 px이다.
 */
internal class PageLayout(
    /** 쪽 사이(와 문서 가장자리) 여백 (pt) */
    val gap: Float = 10f,
) {
    var widths = FloatArray(0)
        private set
    var heights = FloatArray(0)
        private set
    /** 각 쪽의 왼쪽·위쪽 (문서 좌표, pt) */
    var lefts = FloatArray(0)
        private set
    var tops = FloatArray(0)
        private set
    var docW = 0f
        private set
    var docH = 0f
        private set
    /** 지금 두 쪽씩 놓여 있는지 */
    var spread = false
        private set
    /** 쪽마다 오른쪽에 붙은 바깥 여백 (pt) */
    var rightMargin = 0f
        private set

    val count get() = widths.size

    /**
     * 쪽 [widths]×[heights]를 배치한다. [twoPage]가 켜져 있어도 화면이 가로로 길고([viewW] > [viewH]) 쪽이 둘 이상일 때만 양쪽으로.
     * 줄 너비 = 쪽 너비의 합 + 쪽 사이 여백, 문서 너비는 가장 넓은 줄에 맞추고 각 줄은 가운데 맞춤.
     * 쪽마다 오른쪽에 바깥 여백([rightMargin])이 붙는다
     */
    fun layout(widths: FloatArray, heights: FloatArray, rightMargin: Float, twoPage: Boolean, viewW: Int, viewH: Int) {
        require(widths.size == heights.size)
        this.widths = widths
        this.heights = heights
        this.rightMargin = rightMargin
        val n = widths.size
        spread = twoPage && viewW > viewH && n > 1
        tops = FloatArray(n)
        lefts = FloatArray(n)
        val per = if (spread) 2 else 1
        val rows = (0 until n step per).map { it until min(n, it + per) }
        val rowW = rows.map { r -> r.sumOf { (widths[it] + rightMargin).toDouble() }.toFloat() + gap * (r.count() - 1) }
        docW = (rowW.maxOrNull() ?: 600f) + gap * 2
        var y = gap
        for ((k, r) in rows.withIndex()) {
            var x = (docW - rowW[k]) / 2f
            var rowH = 0f
            for (i in r) {
                lefts[i] = x
                tops[i] = y
                x += widths[i] + rightMargin + gap
                rowH = max(rowH, heights[i])
            }
            y += rowH + gap
        }
        docH = y
    }

    /** 화면에 조금이라도 걸린 쪽들 ([offY]는 화면 맨 위의 문서 위치 px, [scale]은 pt당 px) */
    fun visibleRange(offY: Float, scale: Float, viewH: Float): IntRange {
        if (count == 0) return IntRange.EMPTY
        val topDoc = offY / scale
        val bottomDoc = (offY + viewH) / scale
        var first = -1
        var last = -1
        for (i in 0 until count) {
            val t = tops[i]
            val b = t + heights[i]
            if (b >= topDoc && t <= bottomDoc) {
                if (first < 0) first = i
                last = i
            } else if (first >= 0 && t > bottomDoc) break
        }
        return if (first < 0) IntRange.EMPTY else first..last
    }

    /**
     * 화면 좌표 → (쪽, 쪽 x, 쪽 y). 쪽 사이 여백은 가까운 쪽으로.
     * [wide]면 쪽 오른쪽 바깥 여백도 그 쪽으로 (배지·포스트잇을 누를 때). 아니면 필기할 수 없는 곳이라 null
     */
    fun hitPage(offX: Float, offY: Float, scale: Float, sx: Float, sy: Float, wide: Boolean = false): Triple<Int, Float, Float>? {
        val dx = (sx + offX) / scale
        val dy = (sy + offY) / scale
        // 높이가 맞는 쪽 가운데 가로로 가장 가까운 쪽 (양쪽 보기면 한 줄에 둘)
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in 0 until count) {
            val t = tops[i] - gap / 2
            val b = tops[i] + heights[i] + gap / 2
            if (dy !in t..b) {
                if (best >= 0 && t > dy) break
                continue
            }
            val px = dx - lefts[i]
            val dist = if (px < 0) -px else max(0f, px - widths[i] - (if (wide) rightMargin else 0f))
            if (dist < bestDist) { best = i; bestDist = dist }
        }
        if (best < 0 || bestDist > gap) return null
        return Triple(best, dx - lefts[best], dy - tops[best])
    }

    /** 화면 가운데 줄의 첫 쪽 (쪽이 없으면 -1) */
    fun rowFirstPage(offY: Float, scale: Float, viewH: Float): Int {
        if (count == 0) return -1
        val centerDoc = (offY + viewH / 2f) / scale
        return (0 until count).firstOrNull { tops[it] + heights[it] + gap / 2 >= centerDoc } ?: (count - 1)
    }

    /** 화면 가운데에 걸친 쪽 (쪽이 없으면 -1). 양쪽 보기면 그 줄에서 화면 가운데에 걸친 쪽 */
    fun currentPage(offX: Float, offY: Float, scale: Float, viewW: Float, viewH: Float): Int {
        val first = rowFirstPage(offY, scale, viewH)
        if (!spread || first < 0) return first
        val cx = (offX + viewW / 2f) / scale
        var page = first
        for (i in first + 1 until count) {
            if (tops[i] != tops[first]) break
            if (cx >= lefts[i]) page = i
        }
        return page
    }

    /** 화면 맨 위에 걸친 (쪽, 그 쪽 안 높이). [topInset]은 위에 겹쳐 뜬 줄의 높이 px. 쪽이 없으면 null */
    fun topSpot(offY: Float, topInset: Float, scale: Float): Pair<Int, Float>? {
        if (count == 0) return null
        val docY = (offY + topInset) / scale
        val page = (0 until count).lastOrNull { tops[it] - gap / 2 <= docY } ?: 0
        return page to docY - tops[page]
    }
}
