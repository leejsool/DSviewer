package com.dsviewer.app

import kotlin.math.abs
import kotlin.math.roundToInt

/** 분할 보기(두 문서 나란히)의 계산: 가로면 좌우, 세로면 위아래, 첫 칸이 차지하는 비율 */
internal object SplitMath {
    const val MIN_RATIO = 0.2f
    const val MAX_RATIO = 0.8f
    const val DEFAULT_RATIO = 0.5f
    /** 끌다가 반반에서 이만큼 안이면 반반으로 붙는다 */
    private const val SNAP = 0.03f

    /** 화면이 가로로 길면 좌우로, 아니면 위아래로 나눈다 */
    fun sideBySide(width: Int, height: Int) = width > height

    fun clamp(ratio: Float) = if (ratio.isNaN()) DEFAULT_RATIO else ratio.coerceIn(MIN_RATIO, MAX_RATIO)

    /** 나누는 방향의 전체 길이 [total]에서 구분선 [divider]를 뺀 나머지를 비율로 나눈 첫 칸 길이 (px) */
    fun firstLength(total: Int, divider: Int, ratio: Float): Int =
        ((total - divider).coerceAtLeast(0) * clamp(ratio)).roundToInt()

    /** 구분선 가운데가 [center](px, 나누는 방향의 시작에서)에 오도록 하는 비율. 반반 가까이면 반반으로 붙인다 */
    fun ratioAt(center: Float, total: Int, divider: Int): Float {
        val free = (total - divider).coerceAtLeast(1)
        val r = clamp((center - divider / 2f) / free)
        return if (abs(r - DEFAULT_RATIO) < SNAP) DEFAULT_RATIO else r
    }
}
