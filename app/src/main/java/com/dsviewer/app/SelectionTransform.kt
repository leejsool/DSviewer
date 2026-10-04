package com.dsviewer.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 선택한 획을 옮기고 크기를 바꾸고 돌릴 때의 계산: 손잡이를 잡았을 때의 기준점, 끄는 만큼의 배율·각,
 * 미리보기 상자, 화면 좌표 변환, 회전 손잡이 자리, 올가미 판정.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다. 상자는 [왼쪽, 위, 오른쪽, 아래] 배열.
 */
internal object SelectionTransform {

    /**
     * 크기 조절을 시작할 때 정해지는 값. [axis] 0 = 모서리(같은 비율), 1 = 옆으로만, 2 = 위아래로만.
     * [dir]은 변 손잡이가 기준선의 어느 쪽에 있는지 (+1 오른쪽·아래, -1 왼쪽·위)
     */
    class ResizeSetup(val anchorX: Float, val anchorY: Float, val startDist: Float, val axis: Int, val dir: Float)

    /**
     * 손잡이 [handle] (0 왼쪽 위, 1 오른쪽 위, 2 왼쪽 아래, 3 오른쪽 아래, 4 위 변, 5 아래 변, 6 왼쪽 변, 7 오른쪽 변)을 잡았을 때
     * 맞은편을 기준점(anchor)으로 삼는다. 선택 상자는 [b] (쪽 좌표)
     */
    fun resizeSetup(handle: Int, b: FloatArray): ResizeSetup {
        val l = b[0]; val t = b[1]; val r = b[2]; val bt = b[3]
        if (handle in 0..3) {
            val left = handle == 0 || handle == 2
            val top = handle == 0 || handle == 1
            val ax = if (left) r else l
            val ay = if (top) bt else t
            val d = max(hypot((if (left) l else r) - ax, (if (top) t else bt) - ay), 1f)
            return ResizeSetup(ax, ay, d, 0, 1f)
        }
        val axis = if (handle <= 5) 2 else 1
        val ax = when (handle) { 6 -> r; 7 -> l; else -> (l + r) / 2f }
        val ay = when (handle) { 4 -> bt; 5 -> t; else -> (t + bt) / 2f }
        val edge = when (handle) { 4 -> t - ay; 5 -> bt - ay; 6 -> l - ax; else -> r - ax }
        return ResizeSetup(ax, ay, max(abs(edge), 1f), axis, if (edge < 0f) -1f else 1f)
    }

    /**
     * 끄는 점 ([px], [py], 쪽 좌표)에 따른 배율 [가로, 세로]. 변 손잡이는 그 방향만 바꾸고 다른 쪽은 [kx], [ky] 그대로.
     * 모서리는 같은 비율
     */
    fun resizeScale(s: ResizeSetup, px: Float, py: Float, kx: Float, ky: Float): FloatArray = when (s.axis) {
        1 -> floatArrayOf(((px - s.anchorX) * s.dir / s.startDist).coerceIn(0.05f, 20f), ky)
        2 -> floatArrayOf(kx, ((py - s.anchorY) * s.dir / s.startDist).coerceIn(0.05f, 20f))
        else -> {
            val k = (hypot(px - s.anchorX, py - s.anchorY) / s.startDist).coerceIn(0.1f, 20f)
            floatArrayOf(k, k)
        }
    }

    /** 점 ([px], [py])가 회전 중심 ([cx], [cy])에서 이루는 각 (도) */
    fun angleDeg(px: Float, py: Float, cx: Float, cy: Float): Float =
        Math.toDegrees(atan2((py - cy).toDouble(), (px - cx).toDouble())).toFloat()

    /**
     * 옮기기·크기 조절 중인 변화를 반영한 선택 상자 (쪽 좌표). 크기를 바꾸는 중이면 기준점(anchor)에서 가로 [kx], 세로 [ky]배로 늘이고
     * (뒤집히면 다시 정렬), 그 뒤 ([dx], [dy])만큼 옮긴다
     */
    fun previewBounds(
        b: FloatArray, resizing: Boolean, anchorX: Float, anchorY: Float, kx: Float, ky: Float, dx: Float, dy: Float,
    ): FloatArray {
        var l = b[0]; var t = b[1]; var r = b[2]; var bt = b[3]
        if (resizing) {
            val l2 = anchorX + (l - anchorX) * kx; val t2 = anchorY + (t - anchorY) * ky
            val r2 = anchorX + (r - anchorX) * kx; val b2 = anchorY + (bt - anchorY) * ky
            l = min(l2, r2); r = max(l2, r2); t = min(t2, b2); bt = max(t2, b2)
        }
        return floatArrayOf(l + dx, t + dy, r + dx, bt + dy)
    }

    /**
     * 쪽 좌표 상자를 화면 좌표로 ([pageLeft], [pageTop]: 쪽 위치, [scale], 스크롤 [offX], [offY]) 하고 사방으로 [pad]만큼 넓힌다
     */
    fun toScreen(
        b: FloatArray, pageLeft: Float, pageTop: Float, scale: Float, offX: Float, offY: Float, pad: Float,
    ): FloatArray = floatArrayOf(
        (pageLeft + b[0]) * scale - offX - pad,
        (pageTop + b[1]) * scale - offY - pad,
        (pageLeft + b[2]) * scale - offX + pad,
        (pageTop + b[3]) * scale - offY + pad,
    )

    /**
     * 회전 손잡이의 가운데 (화면 좌표) [x, y]: 선택 상자 아래 가운데. 아래에 자리가 없으면 위
     * (선택 막대는 보통 상자 위에 뜨므로 겹치지 않게 아래를 먼저). [offset]은 상자에서 떨어진 거리, [radius]는 손잡이 반지름,
     * [limitBottom]은 화면에서 쓸 수 있는 맨 아래, [margin]은 그 위로 남길 여백
     */
    fun rotateHandle(rect: FloatArray, offset: Float, radius: Float, limitBottom: Float, margin: Float): FloatArray {
        val below = rect[3] + offset
        val y = if (below + radius <= limitBottom - margin || rect[1] - offset - radius < 0f) below else rect[1] - offset
        return floatArrayOf((rect[0] + rect[2]) / 2f, y)
    }

    /** 올가미(점 [count]개, [x, y] 쌍으로 [pts])를 감싸는 상자 [왼쪽, 위, 오른쪽, 아래] */
    fun lassoBounds(pts: FloatArray, count: Int): FloatArray {
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (k in 0 until count) {
            minX = min(minX, pts[k * 2]); maxX = max(maxX, pts[k * 2])
            minY = min(minY, pts[k * 2 + 1]); maxY = max(maxY, pts[k * 2 + 1])
        }
        return floatArrayOf(minX, minY, maxX, maxY)
    }

    /** 올가미가 톡 누른 것으로 볼 만큼 작은가: 긴 쪽이 화면에서 10dp 미만 */
    fun isTap(bounds: FloatArray, scale: Float, density: Float): Boolean =
        max(bounds[2] - bounds[0], bounds[3] - bounds[1]) * scale < 10 * density

    /**
     * 네모 선택의 두 점 ([x0], [y0], [x1], [y1])을 정렬된 상자로 고친다. 가로나 세로가 화면에서 4dp 미만으로 얇으면 null.
     * 돌려주는 값은 [왼쪽, 위, 오른쪽, 아래]
     */
    fun rectSelection(x0: Float, y0: Float, x1: Float, y1: Float, scale: Float, density: Float): FloatArray? {
        val l = min(x0, x1); val t = min(y0, y1); val r = max(x0, x1); val b = max(y0, y1)
        if ((r - l) * scale < 4 * density || (b - t) * scale < 4 * density) return null
        return floatArrayOf(l, t, r, b)
    }
}
