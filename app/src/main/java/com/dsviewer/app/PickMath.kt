package com.dsviewer.app

import kotlin.math.max
import kotlin.math.min

/**
 * 오답 영역을 네모로 끌어 고를 때의 계산. 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 */
internal object PickMath {

    /** 이보다 작게 끌어 고른 네모는 버린다 (쪽 좌표, pt) */
    const val MIN_SIDE = 16f

    /**
     * 화면 점 ([sx], [sy])을 쪽 좌표로: 스크롤 ([offX], [offY])·배율 [scale]·쪽 위치 ([pageLeft], [pageTop])로 바꾸고,
     * 쪽 크기 [pw]×[ph] 밖이면 가장자리로 붙인다. 돌려주는 값은 [x, y]
     */
    fun clampToPage(
        sx: Float, sy: Float, offX: Float, offY: Float, scale: Float,
        pageLeft: Float, pageTop: Float, pw: Float, ph: Float,
    ): FloatArray = floatArrayOf(
        ((sx + offX) / scale - pageLeft).coerceIn(0f, pw),
        ((sy + offY) / scale - pageTop).coerceIn(0f, ph),
    )

    /** 처음 누른 점 ([x0], [y0])과 지금 점 ([x], [y])이 마주 보는 모서리인 네모: [왼쪽, 위, 오른쪽, 아래] */
    fun rectOf(x0: Float, y0: Float, x: Float, y: Float): FloatArray =
        floatArrayOf(min(x0, x), min(y0, y), max(x0, x), max(y0, y))

    /** 네모가 오답 영역으로 삼을 만큼 큰가: 가로·세로 모두 [MIN_SIDE] 이상 */
    fun bigEnough(r: FloatArray): Boolean = r[2] - r[0] >= MIN_SIDE && r[3] - r[1] >= MIN_SIDE
}
