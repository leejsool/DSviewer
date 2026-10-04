package com.dsviewer.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 칠하기·자유 영역의 판단과 모양 만들기. 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 * 영역을 실제로 찾는 일은 [FillRegion], 비동기로 돌리는 일은 [FillController]가 한다.
 */
internal object FillRules {

    /** 칠하기용 쪽 그림의 크기: 배율 [k]와 픽셀 가로·세로 */
    class BucketSize(val k: Float, val w: Int, val h: Int)

    /** 쪽 (가로 [pw], 세로 [ph] pt)을 [maxPx] 화소를 넘지 않게, 배율은 [maxK]까지로 그린다 */
    fun bucketSize(pw: Float, ph: Float, maxK: Float, maxPx: Float): BucketSize {
        val k = min(maxK, sqrt(maxPx / (pw * ph)))
        return BucketSize(k, max(1, (pw * k).toInt()), max(1, (ph * k).toInt()))
    }

    /** 칠하기 영역을 찾을 때 쪽 그림에 그릴 획: 채우기·메모·형광펜·레이저는 뺀다 */
    fun bucketSources(list: List<Stroke>): List<Stroke> =
        list.filter { it.fill == null && it.note == null && it.tool != Tool.HIGHLIGHTER && it.tool != Tool.LASER }

    /** 획 점들을 감싸는 상자: [왼쪽, 위, 오른쪽, 아래] */
    fun strokeBounds(st: Stroke): FloatArray {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until st.count) {
            l = min(l, st.x(i)); r = max(r, st.x(i))
            t = min(t, st.y(i)); b = max(b, st.y(i))
        }
        return floatArrayOf(l, t, r, b)
    }

    /** 두 상자의 네 변이 모두 [tol] 안으로 같은가 (같은 영역을 다시 칠했는지 가르는 데) */
    fun sameBounds(a: FloatArray, b: FloatArray, tol: Float = 3f): Boolean =
        abs(a[0] - b[0]) < tol && abs(a[1] - b[1]) < tol && abs(a[2] - b[2]) < tol && abs(a[3] - b[3]) < tol

    /**
     * 같은 영역에 이미 있는 채우기: 쪽 좌표 ([x], [y])를 덮고 상자가 [newBounds]와 같은 맨 위 채우기.
     * 있으면 새로 쌓지 않고 그 채우기를 새 색·무늬로 바꾼다 (그림판처럼)
     */
    fun sameRegionFill(list: List<Stroke>, x: Float, y: Float, newBounds: FloatArray): Stroke? =
        list.lastOrNull { it.fill != null && it.fillContains(x, y) && sameBounds(strokeBounds(it), newBounds) }

    /** 자유 영역을 너무 작게 그려서 버릴지: 긴 쪽이 화면에서 12dp 미만 */
    fun isTooSmall(bounds: FloatArray, scale: Float, density: Float): Boolean =
        max(bounds[2] - bounds[0], bounds[3] - bounds[1]) * scale < 12 * density

    /** 찾은 윤곽들로 채우기 획을 만든다 (윤곽마다 첫 점의 필압 자리가 1, 바깥 윤곽이 먼저). 점이 3개 미만이면 null */
    fun fillStroke(contours: List<FloatArray>, color: Int, pattern: FillPattern): Stroke? {
        val st = Stroke(Tool.FILL, color, 0f).also { it.fill = FillStyle(pattern) }
        // 바깥 윤곽을 먼저 (무늬가 첫 점에 붙는다)
        for (c in contours.sortedByDescending { FillRegion.signedArea(it) }) {
            if (c.size < 6) continue
            for (i in 0 until c.size / 2) st.add(c[i * 2], c[i * 2 + 1], if (i == 0) 1f else 0f)
        }
        return if (st.count >= 3) st else null
    }
}
