package com.dsviewer.app

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 올가미 ▸ 대칭: 고른 획에 선대칭을 적용하기 전에 접는 선(대칭축)을 손으로 옮겨 가며 결과를 미리 보는 편집기.
 * 파란 점선(접는 선)의 몸통을 끌면 선이 움직이고, 양 끝 동그라미를 끌면 각도가 바뀐다 (가로·세로·45°에 가까우면 붙는다).
 * 움직일 때마다 결과(복사·이동·접기, [SymmetryOps])를 흐리게 미리 보이고, 확정하면 [Host.onConfirm]으로 넘긴다.
 * 두 손가락은 그대로 이동·확대. 문서 화면([view])의 스크롤·쪽 배치는 [Host]로 읽는다.
 */
internal class SymmetryController(private val view: View, private val host: Host) {

    interface Host {
        val scale: Float
        fun pageWidth(page: Int): Float
        fun pageHeight(page: Int): Float
        /** 쪽 좌표 → 화면 좌표 */
        fun screenX(page: Int, x: Float): Float
        fun screenY(page: Int, y: Float): Float
        /** 화면 좌표 → 쪽 좌표 */
        fun pageX(page: Int, sx: Float): Float
        fun pageY(page: Int, sy: Float): Float
        /** 굴러가던 스크롤을 멈춘다 */
        fun stopFling()
        /** 두 번째 손가락이 닿음: 끌기를 접고 이동·확대로 넘긴다 */
        fun switchToPinch(ev: MotionEvent)
        /** 획 하나를 [alpha] 배로 흐리게 그린다 (쪽 좌표 캔버스) */
        fun drawStroke(c: Canvas, st: Stroke, alpha: Float)
        /** 방식·옵션·결과 유무가 바뀜: 막대를 맞춘다 */
        fun onStateChanged()
        /** 끝남 (확정했거나 취소) */
        fun onEnded()
        /** 확정: [page]쪽에 [result]를 적용한다 */
        fun onConfirm(page: Int, result: SymResult)
    }

    private val density = view.resources.displayMetrics.density

    var active = false
        private set
    var mode = SymMode.COPY
        private set
    var orig = OrigStyle.DASHED
        private set
    var resultDashed = false
        private set
    /** 접을 때 남길 쪽을 자동(점이 많은 쪽)의 반대로 바꿨는지 */
    private var flipped = false

    private var page = -1
    private var src: List<Stroke> = emptyList()
    /** 접는 선의 두 점 A·B (쪽 좌표). 선은 끝없이 이어지고 두 점은 손잡이 자리 */
    private val axis = FloatArray(4)
    var result: SymResult = SymResult.EMPTY
        private set

    private enum class Drag { NONE, A, B, BODY }
    private var drag = Drag.NONE
    private var lastX = 0f
    private var lastY = 0f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ACCENT
        strokeCap = Paint.Cap.ROUND
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val handleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = ACCENT
    }

    /**
     * [onPage]쪽에서 [strokes]의 대칭 편집을 시작한다. 접는 선은 세로선으로 [bounds](쪽 좌표)에서 시작한다:
     * 접기면 한가운데(바로 접힌 모습이 보이게), 복사·이동이면 도형 오른쪽 옆(사본이 겹치지 않고 바로 보이게).
     * [startMode]·[startOrig]·[startDashed]는 지난번에 고른 값
     */
    fun start(onPage: Int, strokes: List<Stroke>, bounds: RectF, startMode: SymMode, startOrig: OrigStyle, startDashed: Boolean) {
        page = onPage
        src = strokes
        mode = startMode
        orig = startOrig
        resultDashed = startDashed
        flipped = false
        val half = maxOf(bounds.height() / 2f + 30f, 60f)
        val x = if (startMode == SymMode.FOLD) bounds.centerX() else (bounds.right + SIDE_GAP).coerceAtMost(host.pageWidth(onPage))
        axis[0] = x
        axis[1] = bounds.centerY() - half
        axis[2] = x
        axis[3] = bounds.centerY() + half
        drag = Drag.NONE
        active = true
        rebuild()
        host.onStateChanged()
        view.invalidate()
    }

    fun setMode(m: SymMode) {
        if (!active || mode == m) return
        mode = m
        flipped = false
        rebuild()
        host.onStateChanged()
        view.invalidate()
    }

    fun setOrig(o: OrigStyle) {
        if (!active || orig == o) return
        orig = o
        rebuild()
        host.onStateChanged()
        view.invalidate()
    }

    fun setResultDashed(on: Boolean) {
        if (!active || resultDashed == on) return
        resultDashed = on
        rebuild()
        host.onStateChanged()
        view.invalidate()
    }

    /** 접을 때 남길 쪽을 반대로 */
    fun flipKeep() {
        if (!active) return
        flipped = !flipped
        rebuild()
        host.onStateChanged()
        view.invalidate()
    }

    fun cancel() {
        if (!active) return
        reset()
        host.onEnded()
        view.invalidate()
    }

    /** 확정: 지금 미리 보이는 결과를 적용한다. 적용할 것이 없으면(접을 부분이 없는 등) 아무것도 하지 않는다 */
    fun confirm() {
        if (!active || result.isEmpty) return
        val r = result
        val p = page
        reset()
        host.onEnded()
        host.onConfirm(p, r)
        view.invalidate()
    }

    private fun reset() {
        active = false
        drag = Drag.NONE
        src = emptyList()
        result = SymResult.EMPTY
        page = -1
    }

    private fun rebuild() {
        val keep = SymmetryOps.keepSide(src, axis[0], axis[1], axis[2], axis[3]) * (if (flipped) -1 else 1)
        result = SymmetryOps.build(src, mode, orig, resultDashed, keep, axis[0], axis[1], axis[2], axis[3])
    }

    /** 접는 선과 손잡이, 그리고 흐린 미리 보기를 [pageIndex]쪽에 그린다 (쪽 좌표 캔버스) */
    fun draw(c: Canvas, pageIndex: Int) {
        if (!active || page != pageIndex) return
        val s = host.scale
        // 결과 미리 보기: 흐리게
        for (st in result.added) host.drawStroke(c, st, PREVIEW_ALPHA)
        val dx = axis[2] - axis[0]
        val dy = axis[3] - axis[1]
        val len = hypot(dx, dy)
        if (len == 0f) return
        val ux = dx / len
        val uy = dy / len
        val pw = host.pageWidth(page)
        val ph = host.pageHeight(page)
        val far = hypot(pw, ph) * 2
        val w = 2.5f * density / s
        line.strokeWidth = w
        line.pathEffect = DashPathEffect(floatArrayOf(w * 4, w * 3), 0f)
        c.save()
        c.clipRect(0f, 0f, pw, ph)
        c.drawLine(axis[0] - ux * far, axis[1] - uy * far, axis[0] + ux * far, axis[1] + uy * far, line)
        c.restore()
        // 양 끝 손잡이 (각도를 바꾸는 동그라미)
        handleLine.strokeWidth = 2.5f * density / s
        val r = HANDLE_DP * density / s
        for (i in 0..1) {
            c.drawCircle(axis[i * 2], axis[i * 2 + 1], r, handleFill)
            c.drawCircle(axis[i * 2], axis[i * 2 + 1], r, handleLine)
        }
    }

    /** 화면 점 (sx, sy)에서 접는 선 몸통(끝없는 직선)까지의 거리 (화면 px) */
    private fun distToLine(sx: Float, sy: Float): Float {
        val ax = host.screenX(page, axis[0]); val ay = host.screenY(page, axis[1])
        val bx = host.screenX(page, axis[2]); val by = host.screenY(page, axis[3])
        val len = hypot(bx - ax, by - ay)
        if (len == 0f) return hypot(sx - ax, sy - ay)
        return abs((bx - ax) * (ay - sy) - (ax - sx) * (by - ay)) / len
    }

    private fun distTo(i: Int, sx: Float, sy: Float): Float =
        hypot(sx - host.screenX(page, axis[i * 2]), sy - host.screenY(page, axis[i * 2 + 1]))

    /** 접는 선을 잡고 끄는 동안의 터치: 끝 동그라미는 각도, 몸통은 이동. 손가락이 더 닿으면 이동·확대로 */
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                host.stopFling()
                val reach = HANDLE_REACH_DP * density
                drag = when {
                    distTo(0, ev.x, ev.y) <= reach -> Drag.A
                    distTo(1, ev.x, ev.y) <= reach -> Drag.B
                    distToLine(ev.x, ev.y) <= LINE_REACH_DP * density -> Drag.BODY
                    else -> Drag.NONE
                }
                lastX = host.pageX(page, ev.x)
                lastY = host.pageY(page, ev.y)
            }
            MotionEvent.ACTION_MOVE -> if (drag != Drag.NONE) {
                val px = host.pageX(page, ev.x)
                val py = host.pageY(page, ev.y)
                when (drag) {
                    Drag.BODY -> moveBody(px - lastX, py - lastY)
                    Drag.A -> moveEnd(0, px, py)
                    Drag.B -> moveEnd(1, px, py)
                    Drag.NONE -> {}
                }
                lastX = px
                lastY = py
                changed()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                drag = Drag.NONE
                host.switchToPinch(ev)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag = Drag.NONE
        }
        return true
    }

    /** 선 전체를 (dx, dy)만큼 옮긴다. 선의 가운데는 쪽 밖으로 나가지 않게 */
    private fun moveBody(dx: Float, dy: Float) {
        val pw = host.pageWidth(page)
        val ph = host.pageHeight(page)
        val mx = (axis[0] + axis[2]) / 2
        val my = (axis[1] + axis[3]) / 2
        val sx = mx + dx
        val sy = my + dy
        val cx = sx.coerceIn(0f, pw)
        val cy = sy.coerceIn(0f, ph)
        val ox = cx - mx
        val oy = cy - my
        axis[0] += ox; axis[2] += ox
        axis[1] += oy; axis[3] += oy
    }

    /** [i]번째 끝을 (px, py) 쪽으로 끌어 각도를 바꾼다: 반대쪽 끝은 그대로, 45° 단위에 가까우면 그 방향으로 붙는다 */
    private fun moveEnd(i: Int, px: Float, py: Float) {
        val o = 1 - i
        val fx = axis[o * 2]
        val fy = axis[o * 2 + 1]
        val tx = px.coerceIn(0f, host.pageWidth(page))
        val ty = py.coerceIn(0f, host.pageHeight(page))
        if (hypot(tx - fx, ty - fy) < SymmetryMath.MIN_AXIS) return
        val snapped = SymmetryMath.snapAxis(fx, fy, tx, ty)
        axis[i * 2] = snapped[2]
        axis[i * 2 + 1] = snapped[3]
    }

    private fun changed() {
        val had = !result.isEmpty
        rebuild()
        if (had != !result.isEmpty) host.onStateChanged()
        view.invalidate()
    }

    companion object {
        private const val ACCENT = 0xFF2979FF.toInt()
        /** 흐리게 보이는 미리 보기의 진하기 */
        private const val PREVIEW_ALPHA = 0.45f
        /** 복사·이동의 처음 접는 선이 도형 오른쪽 끝에서 떨어지는 거리 (pt) */
        private const val SIDE_GAP = 24f
        /** 끝 동그라미의 그림 반지름과 손가락이 닿는 반지름 (dp) */
        private const val HANDLE_DP = 10f
        private const val HANDLE_REACH_DP = 30f
        /** 선 몸통을 잡을 수 있는 폭 (dp) */
        private const val LINE_REACH_DP = 24f
    }
}
