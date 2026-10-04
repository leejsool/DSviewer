package com.dsviewer.app

import kotlin.math.hypot
import kotlin.math.max

/**
 * 손바닥 지우기의 판단과 계산: 손바닥인지 가르기, 닿은 범위를 덮는 지우개 원, 지나간 길을 촘촘히 지우는 보폭 수.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 */
internal object PalmMath {

    /** 손바닥: 보통 손가락보다 이만큼 넓게 닿으면 */
    const val RATIO = 2.5f
    /** 손바닥: 처음 닿고 이 안에 손가락 셋 넘게 닿으면 (ms) */
    const val GATHER_MS = 250L
    /** 손바닥 지우개 반지름의 하한·상한 (dp) */
    const val MIN_DP = 24f
    const val MAX_DP = 220f
    /** 손가락 크기를 익힐 때 기억하는 최근 획 수 */
    const val REMEMBER = 15

    /** 화면 [mm] 밀리미터가 몇 픽셀인지 ([xdpi]는 가로 인치당 픽셀) */
    fun mm(xdpi: Float, mm: Float): Float = mm * xdpi / 25.4f

    /** 보통 손가락이 닿는 크기: 최근 손가락 획들의 가운뎃값, 3개 미만이면 모른다고 보고 [fallback] */
    fun medianSize(sizes: Collection<Float>, fallback: Float): Float =
        if (sizes.size >= 3) sizes.sorted()[sizes.size / 2] else fallback

    /** 손바닥으로 보는 닿은 크기의 하한: 보통 손가락의 [RATIO]배, 하지만 3mm보다는 크게 */
    fun palmLimit(fingerSize: Float, xdpi: Float): Float = max(fingerSize * RATIO, mm(xdpi, 3f))

    /** 닿은 것 중에 손가락이면서 [limit] 이상으로 넓은 것이 있는가. [isFinger]·[majors]는 닿은 점마다 */
    fun anyPalm(isFinger: BooleanArray, majors: FloatArray, count: Int, limit: Float): Boolean {
        for (i in 0 until count) if (isFinger[i] && majors[i] >= limit) return true
        return false
    }

    /** 처음 닿고 얼마 안 되어([elapsedMs]) 손가락이 셋 넘게 모였는가 (손바닥을 대면 손가락 여럿이 한꺼번에 닿는다) */
    fun gathered(pointerCount: Int, elapsedMs: Long): Boolean = pointerCount >= 3 && elapsedMs < GATHER_MS

    /** 손바닥이 아니었던 손가락 획의 크기 [major]를 익힌다: 최근 [REMEMBER]개만 남긴다 */
    fun remember(history: ArrayDeque<Float>, major: Float) {
        history.addLast(major)
        while (history.size > REMEMBER) history.removeFirst()
    }

    /**
     * 닿은 손(손가락·손바닥 모두)을 덮는 원: [x, y, 반지름]. 가운데는 닿은 점들의 평균, 반지름은 가장 먼 점의 거리 + 그 점의 닿은 크기의 절반.
     * [leaving]은 막 떨어지는 손가락 (빼고 센다). 셀 점이 없으면 null.
     * 돌려주는 반지름은 아직 [MIN_DP]~[MAX_DP]로 맞추기 전 값
     */
    fun cover(xs: FloatArray, ys: FloatArray, majors: FloatArray, count: Int, leaving: Int): FloatArray? {
        var n = 0
        var cx = 0f
        var cy = 0f
        for (i in 0 until count) {
            if (i == leaving) continue
            cx += xs[i]; cy += ys[i]; n++
        }
        if (n == 0) return null
        cx /= n
        cy /= n
        var r = 0f
        for (i in 0 until count) {
            if (i == leaving) continue
            r = max(r, hypot(xs[i] - cx, ys[i] - cy) + majors[i] / 2f)
        }
        return floatArrayOf(cx, cy, r)
    }

    /** 반지름 [r]을 [minPx]~[maxPx]로 맞춘다 */
    fun clampRadius(r: Float, minPx: Float, maxPx: Float): Float = r.coerceIn(minPx, maxPx)

    /** 길이 [dist]만큼 움직인 길을 [step] 간격으로 나눈 칸 수 (최소 1). 지우개는 이 칸마다 검사해 빈틈이 없게 한다 */
    fun steps(dist: Float, step: Float): Int = max(1, (dist / step).toInt())
}
