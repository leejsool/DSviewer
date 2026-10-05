package com.dsviewer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 찍어 온 화면 한 장을 보여 주고 손가락·펜으로 끌어서 네모로 부분을 고르는 화면.
 * 빈 곳을 끌면 새 네모, 모서리·변을 끌면 크기, 안쪽을 끌면 옮긴다.
 */
class RegionPickView(ctx: Context, private val bmp: Bitmap) : View(ctx) {

    /** 고른 네모가 쓸 만한 크기가 되었는지(또는 없어졌는지) 알린다 */
    var onSelectionChanged: ((Boolean) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val shown = RectF()          // 화면에서 그림이 그려지는 자리
    private val sel = RectF()            // 화면 좌표의 고른 네모
    private var has = false

    private val imgPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = Color.argb(150, 0, 0, 0) }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 2f * d; color = Color.WHITE
    }
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val slop = 28f * d
    /** 이보다 작게 끌다 만 것은 실수로 치고, 이보다 작게 줄일 수는 없다 (작은 글자 하나도 고를 수 있게 작게) */
    private val minSize = 12f * d

    private var mode = NONE
    private var ax = 0f
    private var ay = 0f
    private var gx = 0f
    private var gy = 0f
    private var el = false
    private var et = false
    private var er = false
    private var eb = false

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
        val s = min(w.toFloat() / bmp.width, h.toFloat() / bmp.height)
        val iw = bmp.width * s
        val ih = bmp.height * s
        val l = (w - iw) / 2
        val t = (h - ih) / 2
        // 돌리거나 크기가 바뀌면 고른 네모도 같은 자리에 오게 옮긴다
        if (has && shown.width() > 0) {
            val k = iw / shown.width()
            sel.set(l + (sel.left - shown.left) * k, t + (sel.top - shown.top) * k,
                l + (sel.right - shown.left) * k, t + (sel.bottom - shown.top) * k)
        }
        shown.set(l, t, l + iw, t + ih)
    }

    /** 고른 네모 (그림 픽셀 좌표). 고른 것이 없으면 null */
    fun selection(): Rect? {
        if (!has) return null
        val k = bmp.width / shown.width()
        val r = Rect(
            ((sel.left - shown.left) * k).toInt().coerceIn(0, bmp.width - 1),
            ((sel.top - shown.top) * k).toInt().coerceIn(0, bmp.height - 1),
            ((sel.right - shown.left) * k).toInt().coerceIn(1, bmp.width),
            ((sel.bottom - shown.top) * k).toInt().coerceIn(1, bmp.height),
        )
        return if (r.width() >= 8 && r.height() >= 8) r else null
    }

    override fun onDraw(c: Canvas) {
        c.drawBitmap(bmp, null, shown, imgPaint)
        if (!has) return
        // 고른 네모 밖은 어둡게
        c.drawRect(shown.left, shown.top, shown.right, sel.top, dimPaint)
        c.drawRect(shown.left, sel.bottom, shown.right, shown.bottom, dimPaint)
        c.drawRect(shown.left, sel.top, sel.left, sel.bottom, dimPaint)
        c.drawRect(sel.right, sel.top, shown.right, sel.bottom, dimPaint)
        c.drawRect(sel, linePaint)
        val r = 6f * d
        val mx = (sel.left + sel.right) / 2
        val my = (sel.top + sel.bottom) / 2
        for ((x, y) in listOf(
            sel.left to sel.top, sel.right to sel.top, sel.left to sel.bottom, sel.right to sel.bottom,
            mx to sel.top, mx to sel.bottom, sel.left to my, sel.right to my,
        )) c.drawCircle(x, y, r, handlePaint)
    }

    private fun cx(x: Float) = x.coerceIn(shown.left, shown.right)
    private fun cy(y: Float) = y.coerceIn(shown.top, shown.bottom)

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val x = ev.x
        val y = ev.y
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                mode = NONE
                if (has) {
                    // 작은 네모에서는 손잡이 잡는 범위도 줄인다 (안 그러면 둘레가 다 잡혀 새로 고를 수 없다)
                    val slop = min(slop, max(10f * d, min(sel.width(), sel.height()) * 0.5f))
                    el = abs(x - sel.left) < slop && y > sel.top - slop && y < sel.bottom + slop
                    er = abs(x - sel.right) < slop && y > sel.top - slop && y < sel.bottom + slop
                    et = abs(y - sel.top) < slop && x > sel.left - slop && x < sel.right + slop
                    eb = abs(y - sel.bottom) < slop && x > sel.left - slop && x < sel.right + slop
                    // 좁은 네모에서 왼쪽·오른쪽(위·아래)이 한꺼번에 잡히면 가까운 쪽만
                    if (el && er) { if (abs(x - sel.left) <= abs(x - sel.right)) er = false else el = false }
                    if (et && eb) { if (abs(y - sel.top) <= abs(y - sel.bottom)) eb = false else et = false }
                    if (el || er || et || eb) mode = RESIZE
                    else if (sel.contains(x, y)) { mode = MOVE; gx = x; gy = y }
                }
                if (mode == NONE) {
                    mode = NEW
                    ax = cx(x); ay = cy(y)
                    sel.set(ax, ay, ax, ay)
                    has = false
                }
            }
            MotionEvent.ACTION_MOVE -> {
                when (mode) {
                    NEW -> {
                        val px = cx(x); val py = cy(y)
                        sel.set(min(ax, px), min(ay, py), max(ax, px), max(ay, py))
                        has = sel.width() > 4 * d && sel.height() > 4 * d
                    }
                    MOVE -> {
                        val dx = (x - gx).coerceIn(shown.left - sel.left, shown.right - sel.right)
                        val dy = (y - gy).coerceIn(shown.top - sel.top, shown.bottom - sel.bottom)
                        sel.offset(dx, dy)
                        gx += dx; gy += dy
                    }
                    RESIZE -> {
                        if (el) sel.left = cx(x).coerceAtMost(sel.right - minSize)
                        if (er) sel.right = cx(x).coerceAtLeast(sel.left + minSize)
                        if (et) sel.top = cy(y).coerceAtMost(sel.bottom - minSize)
                        if (eb) sel.bottom = cy(y).coerceAtLeast(sel.top + minSize)
                    }
                }
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // 너무 작게 끌다 만 것은 고른 것으로 치지 않는다
                if (mode == NEW && (sel.width() < minSize || sel.height() < minSize)) has = false
                mode = NONE
                invalidate()
                onSelectionChanged?.invoke(selection() != null)
            }
        }
        return true
    }

    private companion object {
        const val NONE = 0
        const val NEW = 1
        const val MOVE = 2
        const val RESIZE = 3
    }
}
