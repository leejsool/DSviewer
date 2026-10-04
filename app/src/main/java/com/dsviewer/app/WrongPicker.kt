package com.dsviewer.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 오답 영역 고르기: 한 손가락이나 펜으로 문제 영역을 네모로 끌어 고른다. 두 손가락은 그대로 이동·확대.
 * 계산은 [PickMath]. 문서 화면([view])의 스크롤·쪽 배치는 [Host]로 읽는다.
 */
internal class WrongPicker(private val view: View, private val host: Host) {

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
        /** 두 번째 손가락이 닿음: 네모 고르기를 그만두고 이동·확대로 넘긴다 */
        fun switchToPinch(ev: MotionEvent)
        fun onPickEnded()
        fun onPicked(page: Int, rect: RectF)
    }

    private val density = view.resources.displayMetrics.density

    /** 오답 영역을 고르는 중 */
    var picking = false
        private set
    private var tracking = false
    private var page = -1
    private var x0 = 0f
    private var y0 = 0f
    /** 끄는 중이거나 골라 둔 영역 (쪽 [page]의 좌표) */
    private var rect: RectF? = null
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x302979FF }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2979FF.toInt()
    }

    /** 오답 영역 고르기를 시작한다 */
    fun start() {
        clear()
        picking = true
        view.invalidate()
    }

    fun cancel() {
        if (!picking) return
        picking = false
        tracking = false
        rect = null
        host.onPickEnded()
        view.invalidate()
    }

    /** 골라 둔 영역 표시를 지운다 */
    fun clear() {
        if (rect == null) return
        rect = null
        tracking = false
        view.invalidate()
    }

    /** [pageIndex]쪽에 골라 둔·끄는 중인 네모를 그린다 (쪽 좌표 캔버스) */
    fun draw(c: Canvas, pageIndex: Int) {
        val r = rect ?: return
        if (page != pageIndex) return
        line.strokeWidth = 2f * density / host.scale
        c.drawRect(r, fill)
        c.drawRect(r, line)
    }

    private fun point(sx: Float, sy: Float): FloatArray = PickMath.clampToPage(
        sx, sy, host.offsetX, host.offsetY, host.scale,
        host.pageLeft(page), host.pageTop(page), host.pageWidth(page), host.pageHeight(page),
    )

    /** 오답 영역 고르는 동안의 터치: 한 손가락·펜으로 끌면 네모, 손가락이 더 닿으면 이동·확대로 */
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                host.stopFling()
                rect = null
                val hit = host.hitPage(ev.x, ev.y)
                tracking = hit != null && hit.first in 0 until host.pageCount
                if (tracking) {
                    page = hit!!.first
                    val p = point(ev.x, ev.y)
                    x0 = p[0]
                    y0 = p[1]
                }
                view.invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                val p = point(ev.x, ev.y)
                val r = PickMath.rectOf(x0, y0, p[0], p[1])
                rect = RectF(r[0], r[1], r[2], r[3])
                view.invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                tracking = false
                rect = null
                host.switchToPinch(ev)
                view.invalidate()
            }
            MotionEvent.ACTION_UP -> if (tracking) {
                tracking = false
                val r = rect
                if (r != null && PickMath.bigEnough(floatArrayOf(r.left, r.top, r.right, r.bottom))) {
                    picking = false
                    host.onPickEnded()
                    host.onPicked(page, RectF(r))
                } else rect = null
                view.invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                tracking = false
                rect = null
                view.invalidate()
            }
        }
        return true
    }
}
