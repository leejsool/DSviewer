package com.dsviewer.app

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot

/**
 * 올가미 ▸ 대칭: 대칭축을 한 손가락이나 펜으로 끌어 긋는다 (가로·세로·45°에 가까우면 저절로 맞춘다, [SymmetryMath.snapAxis]).
 * 접기라면 축을 그은 뒤 남길 쪽을 한 번 더 눌러 고른다. 두 손가락은 그대로 이동·확대.
 * 계산은 [SymmetryMath]. 문서 화면([view])의 스크롤·쪽 배치는 [Host]로 읽는다 ([WrongPicker]와 같은 방식).
 */
internal class AxisPicker(private val view: View, private val host: Host) {

    interface Host {
        val scale: Float
        val offsetX: Float
        val offsetY: Float
        val pageCount: Int
        fun pageWidth(page: Int): Float
        fun pageHeight(page: Int): Float
        fun pageLeft(page: Int): Float
        fun pageTop(page: Int): Float
        /** 화면 점 아래의 (쪽, 쪽 x, 쪽 y). 없으면 null */
        fun hitPage(sx: Float, sy: Float): Triple<Int, Float, Float>?
        /** 굴러가던 스크롤을 멈춘다 */
        fun stopFling()
        /** 두 번째 손가락이 닿음: 고르기를 잠시 접고 이동·확대로 넘긴다 */
        fun switchToPinch(ev: MotionEvent)
        /** 축을 다 그었고 이제 남길 쪽을 누를 차례 (접기일 때만) */
        fun onAxisDrawn()
        /** 고르기가 끝남 (마쳤거나 취소) */
        fun onPickEnded()
        /** 축 [axis] (A→B, 쪽 좌표)와 남길 쪽 [keep] (복사면 0)을 골랐다 */
        fun onPicked(page: Int, axis: FloatArray, keep: Int)
    }

    private val density = view.resources.displayMetrics.density
    private val slop = ViewConfiguration.get(view.context).scaledTouchSlop.toFloat()

    var picking = false
        private set
    private enum class Stage { AXIS, SIDE }
    private var stage = Stage.AXIS
    private var needSide = false
    /** 축을 그어야 하는 쪽 (고른 것이 있는 쪽) */
    private var page = -1
    private var tracking = false
    private var x0 = 0f
    private var y0 = 0f
    private var downSx = 0f
    private var downSy = 0f
    /** 끄는 중이거나 그어 둔 축 [Ax, Ay, Bx, By] (쪽 [page]의 좌표) */
    private var axis: FloatArray? = null
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2979FF.toInt()
        strokeCap = Paint.Cap.ROUND
    }

    /** [onPage]쪽에서 대칭축 긋기를 시작한다. [side]면 축을 그은 뒤 남길 쪽도 고른다 */
    fun start(onPage: Int, side: Boolean) {
        picking = true
        stage = Stage.AXIS
        needSide = side
        page = onPage
        tracking = false
        axis = null
        view.invalidate()
    }

    fun cancel() {
        if (!picking) return
        picking = false
        tracking = false
        axis = null
        host.onPickEnded()
        view.invalidate()
    }

    /** [pageIndex]쪽에 긋는 중인 축을 그린다 (쪽 좌표 캔버스): 쪽을 가로지르는 파란 점선 */
    fun draw(c: Canvas, pageIndex: Int) {
        val a = axis ?: return
        if (page != pageIndex) return
        val dx = a[2] - a[0]
        val dy = a[3] - a[1]
        val len = hypot(dx, dy)
        if (len == 0f) return
        val ux = dx / len
        val uy = dy / len
        val pw = host.pageWidth(page)
        val ph = host.pageHeight(page)
        val far = hypot(pw, ph) * 2
        val w = 2.5f * density / host.scale
        line.strokeWidth = w
        line.pathEffect = DashPathEffect(floatArrayOf(w * 4, w * 3), 0f)
        c.save()
        c.clipRect(0f, 0f, pw, ph)
        c.drawLine(a[0] - ux * far, a[1] - uy * far, a[0] + ux * far, a[1] + uy * far, line)
        c.restore()
    }

    private fun point(sx: Float, sy: Float): FloatArray = PickMath.clampToPage(
        sx, sy, host.offsetX, host.offsetY, host.scale,
        host.pageLeft(page), host.pageTop(page), host.pageWidth(page), host.pageHeight(page),
    )

    private fun finish(keep: Int) {
        val a = axis ?: return
        picking = false
        tracking = false
        host.onPickEnded()
        host.onPicked(page, a.copyOf(), keep)
        axis = null
        view.invalidate()
    }

    /** 축을 긋는 동안(과 남길 쪽을 누르는 동안)의 터치 */
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                host.stopFling()
                downSx = ev.x
                downSy = ev.y
                val hit = host.hitPage(ev.x, ev.y)
                tracking = hit != null && hit.first == page
                if (tracking && stage == Stage.AXIS) {
                    axis = null
                    val p = point(ev.x, ev.y)
                    x0 = p[0]
                    y0 = p[1]
                }
                view.invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (tracking && stage == Stage.AXIS) {
                val p = point(ev.x, ev.y)
                axis = SymmetryMath.snapAxis(x0, y0, p[0], p[1])
                view.invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tracking = false
                if (stage == Stage.AXIS) axis = null
                host.switchToPinch(ev)
                view.invalidate()
            }
            MotionEvent.ACTION_UP -> if (tracking) {
                tracking = false
                if (stage == Stage.AXIS) {
                    val a = axis
                    if (a != null && SymmetryMath.longEnough(a)) {
                        if (needSide) {
                            stage = Stage.SIDE
                            host.onAxisDrawn()
                        } else finish(0)
                    } else axis = null
                } else if (hypot(ev.x - downSx, ev.y - downSy) <= slop) {
                    // 남길 쪽: 눌러 둔 점이 축의 어느 쪽인가
                    val a = axis
                    val p = point(ev.x, ev.y)
                    val keep = if (a == null) 0 else SymmetryMath.sideSign(p[0], p[1], a[0], a[1], a[2], a[3])
                    if (keep != 0) finish(keep)
                }
                view.invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                if (stage == Stage.AXIS) axis = null
                view.invalidate()
            }
        }
        return true
    }
}
