package com.dsviewer.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 문서를 보는 창(스크롤·확대)의 계산: 스크롤 한계, 확대 중심 유지, 플링 범위, 쪽으로 옮길 때의 위치, 선명하게 다시 그릴 영역.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 * 스크롤 위치 off는 "화면 왼쪽(위)이 문서 안에서 몇 px 떨어졌는지"(화면 px)이다.
 */
internal object ViewportMath {

    /** 두 번 톡 누르면 이 배율로 확대하고, 이미 [DOUBLE_TAP_THRESHOLD] 넘게 확대돼 있으면 원래 크기로 */
    const val DOUBLE_TAP_ZOOM = 2.5f
    const val DOUBLE_TAP_THRESHOLD = 1.5f

    /** 스크롤할 수 있는 전체 높이 (화면 px): 문서 + 아래 단추 자리 + 위아래를 가리는 줄 */
    fun contentHeight(docH: Float, scale: Float, footer: Float, bottomInset: Float, topInset: Float): Float =
        docH * scale + footer + bottomInset + topInset

    /** 가로 스크롤 위치를 문서 안으로: 문서가 화면보다 좁으면 가운데 */
    fun clampX(off: Float, contentW: Float, viewW: Float): Float =
        if (contentW <= viewW) -(viewW - contentW) / 2f else off.coerceIn(0f, contentW - viewW)

    /** 세로 스크롤 위치를 문서 안으로: 위 줄 높이만큼 더 내려 볼 수 있고, 문서가 화면보다 짧으면 가운데 */
    fun clampY(off: Float, contentH: Float, viewH: Float, topInset: Float): Float =
        if (contentH <= viewH) -(viewH - contentH) / 2f - topInset else off.coerceIn(-topInset, contentH - viewH - topInset)

    /** 확대하는 동안 손가락 가운데(초점)에 있는 문서 점이 그대로 있도록 새 스크롤 위치: ([lastFocus] + [off]) / [oldScale] 이 [newScale]에서 [focus]에 오게 */
    fun zoomOffset(off: Float, lastFocus: Float, focus: Float, oldScale: Float, newScale: Float): Float =
        (lastFocus + off) / oldScale * newScale - focus

    /** 화면 위치 [focus]에 있는 문서 점 (문서 단위) */
    fun docAt(off: Float, focus: Float, scale: Float): Float = (focus + off) / scale

    /** 문서 점 [doc]이 배율 [scale]에서 화면 위치 [focus]에 오게 하는 스크롤 위치 (확대 애니메이션의 매 걸음) */
    fun offsetKeeping(doc: Float, scale: Float, focus: Float): Float = doc * scale - focus

    /** 화면 가운데에 있는 문서 위치 (문서 단위) */
    fun centerDoc(off: Float, viewSize: Float, scale: Float): Float = (off + viewSize / 2f) / scale

    /** 문서 위치 [doc]이 화면 가운데에 오는 스크롤 위치 */
    fun offsetForCenter(doc: Float, scale: Float, viewSize: Float): Float = doc * scale - viewSize / 2f

    /** 두 번 톡 누를 때의 목표 배율 */
    fun doubleTapTarget(zoom: Float): Float = if (zoom < DOUBLE_TAP_THRESHOLD) DOUBLE_TAP_ZOOM else 1f

    /**
     * 플링 범위: [최소 x, 최대 x, 최소 y, 최대 y]. 문서가 화면보다 좁거나 짧은 쪽은 움직이지 않게 고정한다
     * ([offX], [offY]는 지금 위치, [cw]·[ch]는 스크롤할 수 있는 전체 폭·높이)
     */
    fun flingBounds(offX: Float, offY: Float, cw: Float, ch: Float, viewW: Float, viewH: Float, topInset: Float): IntArray {
        val maxX = max(0f, cw - viewW).toInt()
        val maxY = (max(0f, ch - viewH) - topInset).toInt()
        val minX = if (cw <= viewW) offX.toInt() else 0
        val minY = if (ch <= viewH) offY.toInt() else -topInset.toInt()
        return intArrayOf(minX, max(minX, if (cw <= viewW) minX else maxX), minY, max(minY, if (ch <= viewH) minY else maxY))
    }

    /** [pageTop]쪽 위 끝(쪽 사이 간격의 절반 위)이 화면 맨 위(위 줄 아래)에 오는 세로 스크롤 위치 */
    fun offsetForPageTop(pageTop: Float, gap: Float, scale: Float, topInset: Float): Float =
        (pageTop - gap / 2) * scale - topInset

    /** 쪽 안의 높이 [y]가 화면 맨 위에 오는 위치 ([y]는 쪽 위 간격 절반부터 쪽 높이 [pageH]까지로 맞춘다) */
    fun offsetForPageY(pageTop: Float, y: Float, pageH: Float, gap: Float, scale: Float, topInset: Float): Float =
        (pageTop + y.coerceIn(-gap / 2, pageH)) * scale - topInset

    /** 쪽의 한 점이 위에서 1/3쯤에 오는 세로 스크롤 위치 (찾은 글자로 갈 때) */
    fun offsetForPointY(pageTop: Float, y: Float, scale: Float, viewH: Float): Float =
        (pageTop + y) * scale - viewH / 3f

    /** 쪽의 한 점의 화면 가로 위치가 화면 밖이면 그 점이 가운데 오게 하는 가로 스크롤 위치, 이미 보이면 지금 위치 그대로 */
    fun offsetForPointX(offX: Float, pageLeft: Float, x: Float, scale: Float, viewW: Float): Float {
        val sx = (pageLeft + x) * scale - offX
        return if (sx < 0 || sx > viewW) (pageLeft + x) * scale - viewW / 2f else offX
    }

    /**
     * 쪽이 화면에 보이는 부분을 쪽 단위 영역으로: 쪽의 화면 위치 ([l], [t], [r], [b])와 화면 [viewW]×[viewH]가 겹친 부분.
     * 겹친 폭이나 높이가 1px 이하면 null (선명하게 다시 그릴 것이 없다). 돌려주는 값은 [왼쪽, 위, 오른쪽, 아래] (쪽 단위)
     */
    fun visibleRegion(l: Float, t: Float, r: Float, b: Float, viewW: Float, viewH: Float, scale: Float): FloatArray? {
        val vl = max(l, 0f); val vt = max(t, 0f); val vr = min(r, viewW); val vb = min(b, viewH)
        if (vr - vl <= 1 || vb - vt <= 1) return null
        return floatArrayOf((vl - l) / scale, (vt - t) / scale, (vr - l) / scale, (vb - t) / scale)
    }

    /** 쪽 하나를 비트맵으로 그릴 때 화소 수 한도 */
    const val MAX_PAGE_PIXELS = 8_000_000f

    /**
     * 쪽 [pw]×[ph]를 그릴 배율. 많이 줄여 여러 쪽이 한꺼번에 보일 때([zoom] 0.5 미만)는 작게(1/2, 1/4) 그려 둔다
     * (큰 화면에서 메모리가 모자라 계속 다시 그리지 않게). 화소 수가 [MAX_PAGE_PIXELS]를 넘지 않게도
     */
    fun baseRenderScale(zoom: Float, baseScale: Float, pw: Float, ph: Float): Float {
        val k = when {
            zoom >= 0.5f -> 1f
            zoom >= 0.25f -> 0.5f
            else -> 0.25f
        }
        return min(baseScale * k, sqrt(MAX_PAGE_PIXELS / (pw * ph)))
    }

    /** 배율 [s]로 그린 쪽 비트맵의 가로·세로 픽셀 (최소 1): [w, h] */
    fun bitmapSize(pw: Float, ph: Float, s: Float): IntArray =
        intArrayOf(max(1, (pw * s).roundToInt()), max(1, (ph * s).roundToInt()))

    /**
     * 미리 그려 둘 쪽: 보이는 쪽 [first]~[last] 바로 앞뒤 한 쪽씩 (문서는 [count]쪽). 이미 그렸는지는 부르는 쪽이 따진다
     */
    fun prefetchPages(first: Int, last: Int, count: Int): List<Int> {
        val r = ArrayList<Int>(2)
        if (first > 0) r.add(first - 1)
        if (last < count - 1) r.add(last + 1)
        return r
    }
}
