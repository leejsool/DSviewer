package com.dsviewer.app

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 올가미 선택의 판정 계산: 누른 곳에 무엇이 있는지(대상 선택), 올가미가 무엇을 고르는지, 선택 상자의 크기,
 * 크기 조절 손잡이가 눌렸는지. 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다
 * (문서 화면은 좌표를 넘기고 결과만 쓴다). 좌표는 별말이 없으면 쪽 좌표(pt).
 */
internal object SelectionHit {

    /** 획의 처음 네 점(그림·글 상자의 네 모서리)이 이룬 사각형 안에 (x, y)가 있는지 */
    fun quadContains(st: Stroke, x: Float, y: Float): Boolean {
        var inside = false
        var j = 3
        for (i in 0 until 4) {
            val xi = st.x(i); val yi = st.y(i); val xj = st.x(j); val yj = st.y(j)
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** 획의 선에서 굵기 절반 + [tol] 안인지 */
    fun strokeNear(st: Stroke, x: Float, y: Float, tol: Float): Boolean {
        if (st.count == 0) return false
        val r = st.halfWidth + tol
        if (st.count == 1) return hypot(st.x(0) - x, st.y(0) - y) <= r
        for (k in 1 until st.count) {
            val ax = st.x(k - 1); val ay = st.y(k - 1)
            val dx = st.x(k) - ax; val dy = st.y(k) - ay
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0f) 0f else (((x - ax) * dx + (y - ay) * dy) / len2).coerceIn(0f, 1f)
            if (hypot(ax + dx * t - x, ay + dy * t - y) <= r) return true
        }
        return false
    }

    /** (x, y)를 덮고 있는 맨 위 그림·글 ([textOnly]면 글만). [list]는 아래에 깔린 것부터의 순서 */
    fun boxAt(list: List<Stroke>, x: Float, y: Float, textOnly: Boolean = false): Stroke? {
        for (k in list.indices.reversed()) {
            val st = list[k]
            if (!st.isBox || st.count < 4 || (textOnly && st.text == null)) continue
            if (quadContains(st, x, y)) return st
        }
        return null
    }

    /**
     * 대상 선택: (x, y)에 닿는 맨 위의 것 (획·글·테이프, 없으면 그림).
     * 필기와 글은 그림 위에 그려지므로 먼저 본다. 가는 획도 고르기 쉽게 [tol](손가락 폭)만큼 여유를 둔다
     */
    fun objectAt(list: List<Stroke>, x: Float, y: Float, tol: Float): Stroke? {
        list.lastOrNull { st ->
            when {
                st.image != null || st.fill != null || st.note != null -> false
                st.isBox -> st.count >= 4 && quadContains(st, x, y)
                st.tool == Tool.TAPE && st.tapeContains(x, y) -> true
                else -> strokeNear(st, x, y, tol)
            }
        }?.let { return it }
        // 채우기는 선 아래에 깔리므로 선 다음에 본다
        list.lastOrNull { it.fillContains(x, y) }?.let { return it }
        return list.lastOrNull { it.image != null && it.count >= 4 && quadContains(it, x, y) }
    }

    /** 다각형([poly]의 앞 [count]개 점, [x0, y0, x1, y1 …]) 안에 (x, y)가 있는지 */
    fun pointInPolygon(poly: FloatArray, count: Int, x: Float, y: Float): Boolean {
        var inside = false
        var j = count - 1
        for (i in 0 until count) {
            val xi = poly[i * 2]; val yi = poly[i * 2 + 1]
            val xj = poly[j * 2]; val yj = poly[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** 올가미 안에 점이 절반 이상 들어간 획들. 메모는 끌어 옮기고 메모의 단추로 지우므로 고르지 않는다 */
    fun pickByLasso(list: List<Stroke>, poly: FloatArray, count: Int): List<Stroke> = list.filter { st ->
        if (st.note != null) return@filter false
        var inside = 0
        for (k in 0 until st.count) if (pointInPolygon(poly, count, st.x(k), st.y(k))) inside++
        inside * 2 >= st.count && inside > 0
    }

    /**
     * 고른 획들을 감싸는 상자 [왼쪽, 위, 오른쪽, 아래] (굵기 절반만큼 여유). 점이 하나도 없으면 null.
     * RectF.union은 넓이 0인 사각형을 무시하므로(굵기 0인 그림의 모서리) 직접 최소·최대를 잰다
     */
    fun boundsOf(picked: List<Stroke>): FloatArray? {
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (st in picked) {
            val half = st.halfWidth
            for (k in 0 until st.count) {
                l = min(l, st.x(k) - half); r = max(r, st.x(k) + half)
                t = min(t, st.y(k) - half); b = max(b, st.y(k) + half)
            }
        }
        return if (l <= r) floatArrayOf(l, t, r, b) else null
    }

    /** 변 가운데 손잡이를 둘 만큼 상자가 넓은지 (위·아래 변, 왼쪽·오른쪽 변). 작으면 모서리 손잡이와 겹친다. 길이는 화면 px */
    fun sideHandles(width: Float, height: Float, reach: Float): Pair<Boolean, Boolean> {
        val need = reach * 2.2f
        return (width >= need) to (height >= need)
    }

    /**
     * 선택 상자(화면 좌표 [l, t, r, b])에서 (sx, sy)에 있는 손잡이: 0 왼쪽 위, 1 오른쪽 위, 2 왼쪽 아래, 3 오른쪽 아래,
     * 4 위 변, 5 아래 변, 6 왼쪽 변, 7 오른쪽 변, 없으면 -1 (모서리가 먼저). [reach]는 손가락이 닿는 반지름(px)
     */
    fun handleAt(l: Float, t: Float, r: Float, b: Float, sx: Float, sy: Float, reach: Float): Int {
        val corners = arrayOf(l to t, r to t, l to b, r to b)
        corners.indices.firstOrNull { hypot(sx - corners[it].first, sy - corners[it].second) <= reach }?.let { return it }
        val (topBottom, leftRight) = sideHandles(r - l, b - t, reach)
        val cx = (l + r) * 0.5f
        val cy = (t + b) * 0.5f
        val sides = arrayOf(cx to t, cx to b, l to cy, r to cy)
        val side = (if (topBottom) listOf(0, 1) else emptyList()) + (if (leftRight) listOf(2, 3) else emptyList())
        return side.firstOrNull { hypot(sx - sides[it].first, sy - sides[it].second) <= reach * 0.8f }?.let { it + 4 } ?: -1
    }
}
