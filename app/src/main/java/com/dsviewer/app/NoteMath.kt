package com.dsviewer.app

import kotlin.math.max
import kotlin.math.min

/**
 * 포스트잇 메모·오답 배지를 끌어 옮길 때의 한계와 색 고르기 칸 자리 계산.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다. 한계 배열은 [최소 dx, 최대 dx, 최소 dy, 최대 dy] (쪽 좌표).
 */
internal object NoteMath {

    /**
     * 오답 배지의 끌기 한계: 상자 ([left], [top], [right], [bottom])가 쪽 [pw]×[ph]과 오른쪽 바깥 여백 [rightMargin] 안에서만.
     * 이미 한계를 넘어 있어도(예전 문서) 더 벗어나게만 막는다
     */
    fun badgeLimits(left: Float, top: Float, right: Float, bottom: Float, pw: Float, ph: Float, rightMargin: Float): FloatArray =
        relax(-left, pw + rightMargin - right, -top, ph - bottom)

    /**
     * 포스트잇의 끌기 한계. 접힌 자리(점 0: [x0], [y0])는 늘 쪽 안에 (접힌 네모 크기 [icon]),
     * 펼친 자리(점 1: [x1], [y1])는 펼쳤을 때 몸통이 쪽 아래([ph] - [noteH])와 오른쪽([maxLeft])으로 나가지 않게.
     * 접힌 메모는 펼친 자리를 따지지 않는다
     */
    fun noteLimits(
        x0: Float, y0: Float, x1: Float, y1: Float, collapsed: Boolean,
        noteH: Float, pw: Float, ph: Float, icon: Float, maxLeft: Float,
    ): FloatArray = relax(
        -min(x0, x1),
        min(pw - icon - x0, if (collapsed) Float.MAX_VALUE else maxLeft - x1),
        -min(y0, y1),
        min(ph - icon - y0, if (collapsed) Float.MAX_VALUE else ph - noteH - y1),
    )

    private fun relax(minDx: Float, maxDx: Float, minDy: Float, maxDy: Float): FloatArray =
        floatArrayOf(min(minDx, 0f), max(maxDx, 0f), min(minDy, 0f), max(maxDy, 0f))

    /** 화면에서 끈 거리 ([dxPx], [dyPx])를 쪽 좌표로 바꿔 한계 안으로: [dx, dy] */
    fun clampDelta(limits: FloatArray, dxPx: Float, dyPx: Float, scale: Float): FloatArray = floatArrayOf(
        (dxPx / scale).coerceIn(limits[0], limits[1]),
        (dyPx / scale).coerceIn(limits[2], limits[3]),
    )

    /**
     * 색 고르기 칸 [k]번째 자리 (쪽 좌표): 메모 띠([header] 높이) 바로 아래에 [count]칸을 한 줄로, 칸은 정사각형.
     * 메모 상자 ([left], [top], [width])에서. 돌려주는 값은 [왼쪽, 위, 오른쪽, 아래]
     */
    fun swatchRect(left: Float, top: Float, width: Float, header: Float, k: Int, count: Int): FloatArray {
        val cell = width / count
        val t = top + header
        return floatArrayOf(left + cell * k, t, left + cell * (k + 1), t + cell)
    }
}
