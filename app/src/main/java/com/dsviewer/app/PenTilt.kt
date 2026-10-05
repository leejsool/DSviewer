package com.dsviewer.app

/**
 * 펜 기울기 → 굵기. 연필은 눕혀 쥐면 심의 옆면으로 넓게 칠해지고, 붓펜은 붓이 눕혀지며 넓게 번진다.
 * 기울기는 필압 자리에 미리 넣어 저장한다 (만년필·붓펜의 속도처럼): 필압 값이 1을 넘는 만큼이 '더 넓음'이라
 * 저장 형식은 그대로이고 옛 필기는 달라지지 않는다. 기울기를 모르는 펜(0)이면 값이 필압 그대로다.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 */
internal object PenTilt {

    /** 이보다 덜 눕혀 쥐면 평소 필기와 같다 (평소 글씨 쓰는 기울기, 라디안. 약 35°) */
    const val START = 0.61f
    /** 이만큼 눕히면 가장 넓다 (약 75°) */
    const val FULL = 1.31f
    /** 연필이 가장 눕혔을 때 평소의 몇 배까지 넓어지는가 */
    const val PENCIL_MAX = 3f
    /** 붓펜이 가장 눕혔을 때 평소의 몇 배까지 넓어지는가 */
    const val BRUSH_MAX = 1.6f

    /** 연필 굵기 배율: 평소 필압 구간은 [PENCIL_LOW]~[PENCIL_TOP], 그 위로 눕혀서 넓어지는 구간은 필압값 1~2 */
    private const val PENCIL_LOW = 0.65f
    private const val PENCIL_SPAN = 0.5f
    private const val PENCIL_TOP = PENCIL_LOW + PENCIL_SPAN
    /** 필압값이 1에서 2까지 갈 때 늘어나는 굵기 배율 (PenStyle.factor도 이 값을 쓴다) */
    const val PENCIL_EXTRA_SLOPE = PENCIL_TOP * (PENCIL_MAX - 1f)

    private const val BRUSH_LOW = 0.08f
    private const val BRUSH_SPAN = 1.8f
    private const val BRUSH_TOP = BRUSH_LOW + BRUSH_SPAN
    const val BRUSH_EXTRA_SLOPE = BRUSH_TOP * (BRUSH_MAX - 1f)

    /** 가장 넓을 때의 굵기 배율 (지우개·선택이 닿는 범위를 정할 때) */
    const val PENCIL_REACH = PENCIL_TOP * PENCIL_MAX
    const val BRUSH_REACH = BRUSH_TOP * BRUSH_MAX

    /** 기울기 [tilt](라디안, 0이 수직, π/2가 완전히 눕힘)를 0~1의 '눕힘 정도'로: [START] 아래는 0, [FULL] 위는 1, 사이는 부드럽게 */
    fun shade(tilt: Float): Float {
        val t = ((tilt - START) / (FULL - START)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    /** 연필: 평소 필압 [pr](0~1)과 눕힘 [shade](0~1)로 저장할 값 (0~2) */
    fun pencil(pr: Float, shade: Float): Float {
        val f = (PENCIL_LOW + PENCIL_SPAN * pr.coerceIn(0f, 1f)) * (1f + (PENCIL_MAX - 1f) * shade.coerceIn(0f, 1f))
        return if (f <= PENCIL_TOP) (f - PENCIL_LOW) / PENCIL_SPAN else 1f + (f - PENCIL_TOP) / PENCIL_EXTRA_SLOPE
    }

    /** 붓펜: 평소 값 [v](0~1, 속도·필압을 넣은 값)과 눕힘 [shade]로 저장할 값 (0~2) */
    fun brush(v: Float, shade: Float): Float {
        val f = (BRUSH_LOW + BRUSH_SPAN * v.coerceIn(0f, 1f)) * (1f + (BRUSH_MAX - 1f) * shade.coerceIn(0f, 1f))
        return if (f <= BRUSH_TOP) (f - BRUSH_LOW) / BRUSH_SPAN else 1f + (f - BRUSH_TOP) / BRUSH_EXTRA_SLOPE
    }
}
