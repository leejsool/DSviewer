package com.dsviewer.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 문서 화면의 쪽 배치: 쪽을 한 줄에 하나(양쪽 보기면 둘)씩 위에서 아래로 쌓아(세로 스크롤) 또는 왼쪽에서 오른쪽으로 늘어놓아(가로 넘김)
 * 각 쪽의 [lefts]·[tops]와 문서 크기 [docW]·[docH]를 정하고,
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
    /** 가로 넘김 배치인지 (쪽을 왼쪽에서 오른쪽으로 한 줄에 늘어놓는다) */
    var horizontal = false
        private set
    /** 가로 넘김의 한 칸(쪽 하나, 양쪽 보기면 두 쪽)의 왼쪽·오른쪽 (문서 좌표, pt) */
    private var unitLeft = FloatArray(0)
    private var unitRight = FloatArray(0)
    private var unitOfPage = IntArray(0)

    val count get() = widths.size

    /** 가로 넘김의 칸 수 (세로 스크롤이면 0) */
    val unitCount get() = unitLeft.size

    /** [page]가 든 칸 번호 (가로 넘김일 때) */
    fun unitOf(page: Int) = unitOfPage[page.coerceIn(0, max(0, count - 1))]

    /** 칸 [k]의 가운데 (문서 좌표, pt) */
    fun unitCenter(k: Int) = (unitLeft[k] + unitRight[k]) / 2f

    /** 칸 [k]가 화면 가운데에 오는 가로 스크롤 위치 (화면 px) */
    fun offsetForUnit(k: Int, scale: Float, viewW: Float) = unitCenter(k) * scale - viewW / 2f

    /** 화면 가운데에 가장 가까운 칸 (가로 넘김이 아니면 -1) */
    fun nearestUnit(offX: Float, scale: Float, viewW: Float): Int {
        if (unitCount == 0) return -1
        val c = (offX + viewW / 2f) / scale
        var best = 0
        for (k in 1 until unitCount) if (abs(unitCenter(k) - c) < abs(unitCenter(best) - c)) best = k
        return best
    }

    /**
     * 쪽 한 칸이 화면에 꼭 맞는 배율 (pt당 px). 세로 스크롤은 문서 너비를 화면 너비에 맞추고,
     * 가로 넘김은 가장 큰 칸 하나가 화면에 통째로 들어오게 (너비·높이 중 빡빡한 쪽에 맞춘다)
     */
    fun fitScale(viewW: Int, viewH: Int): Float {
        if (!horizontal) return viewW / docW
        var unitW = 0f
        for (k in 0 until unitCount) unitW = max(unitW, unitRight[k] - unitLeft[k])
        return min(viewW / (unitW + gap * 2), viewH / docH)
    }

    /**
     * 쪽 [widths]×[heights]를 배치한다. [twoPage]가 켜져 있어도 화면이 가로로 길고([viewW] > [viewH]) 쪽이 둘 이상일 때만 양쪽으로.
     * 줄 너비 = 쪽 너비의 합 + 쪽 사이 여백, 문서 너비는 가장 넓은 줄에 맞추고 각 줄은 가운데 맞춤.
     * 쪽마다 오른쪽에 바깥 여백([rightMargin])이 붙는다
     */
    fun layout(
        widths: FloatArray, heights: FloatArray, rightMargin: Float, twoPage: Boolean, viewW: Int, viewH: Int,
        horizontal: Boolean = false,
    ) {
        require(widths.size == heights.size)
        this.widths = widths
        this.heights = heights
        this.rightMargin = rightMargin
        this.horizontal = horizontal
        val n = widths.size
        spread = twoPage && viewW > viewH && n > 1
        tops = FloatArray(n)
        lefts = FloatArray(n)
        val per = if (spread) 2 else 1
        val rows = (0 until n step per).map { it until min(n, it + per) }
        if (horizontal) {
            layoutHorizontal(rows)
            return
        }
        unitLeft = FloatArray(0)
        unitRight = FloatArray(0)
        unitOfPage = IntArray(0)
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

    /** 가로 넘김: 칸([rows])을 왼쪽에서 오른쪽으로 늘어놓고, 쪽은 칸 안에서 위아래 가운데 맞춤 */
    private fun layoutHorizontal(rows: List<IntRange>) {
        val maxH = heights.maxOrNull() ?: 0f
        unitLeft = FloatArray(rows.size)
        unitRight = FloatArray(rows.size)
        unitOfPage = IntArray(count)
        var x = gap
        for ((k, r) in rows.withIndex()) {
            unitLeft[k] = x
            for (i in r) {
                unitOfPage[i] = k
                lefts[i] = x
                tops[i] = gap + (maxH - heights[i]) / 2f
                x += widths[i] + rightMargin + gap
            }
            unitRight[k] = x - gap
        }
        docW = max(x, gap * 2)
        docH = maxH + gap * 2
    }

    /**
     * 화면에 조금이라도 걸린 쪽들 ([offY]는 화면 맨 위의 문서 위치 px, [scale]은 pt당 px).
     * 가로 넘김이면 가로로 따진다 ([offX]는 화면 맨 왼쪽의 문서 위치 px, [viewW]는 화면 너비)
     */
    fun visibleRange(offY: Float, scale: Float, viewH: Float, offX: Float = 0f, viewW: Float = 0f): IntRange {
        if (count == 0) return IntRange.EMPTY
        if (horizontal) {
            val leftDoc = offX / scale
            val rightDoc = (offX + viewW) / scale
            var first = -1
            var last = -1
            for (i in 0 until count) {
                if (lefts[i] + widths[i] + rightMargin >= leftDoc && lefts[i] <= rightDoc) {
                    if (first < 0) first = i
                    last = i
                } else if (first >= 0 && lefts[i] > rightDoc) break
            }
            return if (first < 0) IntRange.EMPTY else first..last
        }
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

    /** 화면 가운데 줄의 첫 쪽 (쪽이 없으면 -1). 가로 넘김이면 화면 가운데에 가장 가까운 칸의 첫 쪽 */
    fun rowFirstPage(offY: Float, scale: Float, viewH: Float, offX: Float = 0f, viewW: Float = 0f): Int {
        if (count == 0) return -1
        if (horizontal) {
            val k = nearestUnit(offX, scale, viewW)
            return (0 until count).first { unitOfPage[it] == k }
        }
        val centerDoc = (offY + viewH / 2f) / scale
        return (0 until count).firstOrNull { tops[it] + heights[it] + gap / 2 >= centerDoc } ?: (count - 1)
    }

    /** 화면 가운데에 걸친 쪽 (쪽이 없으면 -1). 양쪽 보기면 그 줄에서 화면 가운데에 걸친 쪽 */
    fun currentPage(offX: Float, offY: Float, scale: Float, viewW: Float, viewH: Float): Int {
        val first = rowFirstPage(offY, scale, viewH, offX, viewW)
        if (!spread || first < 0) return first
        val cx = (offX + viewW / 2f) / scale
        var page = first
        for (i in first + 1 until count) {
            if (if (horizontal) unitOfPage[i] != unitOfPage[first] else tops[i] != tops[first]) break
            if (cx >= lefts[i]) page = i
        }
        return page
    }

    /** 화면 맨 위에 걸친 (쪽, 그 쪽 안 높이). [topInset]은 위에 겹쳐 뜬 줄의 높이 px. 쪽이 없으면 null */
    fun topSpot(offY: Float, topInset: Float, scale: Float, offX: Float = 0f, viewW: Float = 0f): Pair<Int, Float>? {
        if (count == 0) return null
        val docY = (offY + topInset) / scale
        if (horizontal) {
            val page = currentPage(offX, offY, scale, viewW, 0f)
            return page to docY - tops[page]
        }
        val page = (0 until count).lastOrNull { tops[it] - gap / 2 <= docY } ?: 0
        return page to docY - tops[page]
    }

    companion object {
        /** 이 속도(px/s)보다 빠르게 쓸면 넘기기로 본다 */
        const val FLICK_PX_PER_S = 600f

        /**
         * 가로 넘김에서 손을 뗐을 때 갈 칸. 느리게 끌었으면 가장 가까운 칸([nearest]).
         * 빠르게 쓸었으면 그 방향의 옆 칸: 손가락이 왼쪽으로([velocityX] < 0) 가면 다음, 오른쪽이면 앞.
         * 이미 화면 가운데가 [nearest]의 가운데를 지나 쓴 방향으로 더 와 있으면 거기서 더 넘기지 않는다.
         * [viewCenter]·[nearestCenter]는 문서 좌표(pt)
         */
        fun flipTarget(nearest: Int, nearestCenter: Float, viewCenter: Float, velocityX: Float, count: Int): Int {
            if (count == 0) return -1
            val eps = 1f
            val k = when {
                velocityX <= -FLICK_PX_PER_S && viewCenter >= nearestCenter - eps -> nearest + 1
                velocityX >= FLICK_PX_PER_S && viewCenter <= nearestCenter + eps -> nearest - 1
                else -> nearest
            }
            return k.coerceIn(0, count - 1)
        }
    }
}
