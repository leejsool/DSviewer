package com.dsviewer.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt

/**
 * 펜 입력 중 점을 어떻게 놓을지 정하는 작은 계산들 (직선 형광펜, 네모 테이프, 회전 각 맞춤, 보정 펜 결과를 펜 획으로).
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 */
internal object StrokeGeometry {

    /**
     * 직선 형광펜의 끝점: 첫 점 ([x0], [y0])에서 지금 점 ([x], [y])까지. 가로·세로 근처(5° 안)면 딱 맞춘다.
     * 돌려주는 값은 [끝 x, 끝 y]
     */
    fun straightHighlighterEnd(x0: Float, y0: Float, x: Float, y: Float): FloatArray {
        var ex = x
        var ey = y
        val dx = ex - x0
        val dy = ey - y0
        if (dx != 0f || dy != 0f) {
            val deg = Math.toDegrees(atan2(abs(dy).toDouble(), abs(dx).toDouble()))
            if (deg <= 5.0) ey = y0
            else if (deg >= 85.0) ex = x0
        }
        return floatArrayOf(ex, ey)
    }

    /** 네모 테이프의 네 점 (첫 점과 지금 점이 마주 보는 모서리): [x0,y0, x,y0, x,y, x0,y] 순서 */
    fun rectTapeCorners(x0: Float, y0: Float, x: Float, y: Float): FloatArray =
        floatArrayOf(x, y0, x, y, x0, y)

    /**
     * 선택을 돌리는 각(도)을 맞춘다: -180 초과 180 이하로 접은 뒤 90° 배수(5° 안), 45° 배수(3° 안)에 딱 맞춘다.
     * 90°가 45°보다 우선한다 (두 허용이 겹치는 곳은 없지만 순서가 의미를 정한다)
     */
    fun snapRotation(deg: Float): Float {
        var d = deg
        while (d > 180f) d -= 360f
        while (d <= -180f) d += 360f
        val r90 = (d / 90f).roundToInt() * 90f
        val r45 = (d / 45f).roundToInt() * 45f
        return when {
            abs(d - r90) <= 5f -> r90
            abs(d - r45) <= 3f -> r45
            else -> d
        }
    }
}
