package com.dsviewer.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.google.android.material.color.MaterialColors

/** 분할 보기에서 문서 화면 하나(칸): 문서 + 글 상자 층 + 고른 칸 표시줄, 그리고 지금 그 칸에 떠 있는 탭 */
internal class ViewerPane(val frame: PaneFrame, val view: DocumentView, val host: TextEditHost, val focusBar: View) {
    var tab: DocTab? = null
    val shown get() = frame.visibility == View.VISIBLE
}

/** 칸을 새로 누르면(손가락·펜) 그 칸이 '고른 칸'이 되도록 알린다. 누름 자체는 그대로 아래로 전달된다 */
internal class PaneFrame @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    var onTouched: (() -> Unit)? = null

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) onTouched?.invoke()
        return super.dispatchTouchEvent(ev)
    }
}

/**
 * 두 칸을 나란히 놓는다: 자식은 차례로 [첫 칸, 구분선, 둘째 칸] (구분선은 코드에서 끼운다).
 * [split]이 꺼져 있으면 첫 칸이 전부 차지한다. 가로로 긴 화면은 좌우, 세로로 긴 화면은 위아래로 나누고,
 * 구분선을 끌면 [ratio](첫 칸 비율)가 바뀐다 ([SplitMath])
 */
internal class SplitLayout @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ViewGroup(ctx, attrs) {
    /** 첫 칸이 차지하는 비율 */
    var ratio = SplitMath.DEFAULT_RATIO
        set(v) {
            val c = SplitMath.clamp(v)
            if (c == field) return
            field = c
            requestLayout()
        }
    /** 구분선을 끄는 중에는 false, 놓으면 true */
    var onRatioChanged: ((ratio: Float, done: Boolean) -> Unit)? = null

    private val density = resources.displayMetrics.density
    val dividerPx = (DIVIDER_DP * density).toInt()

    /** 지금 좌우로 나눠져 있는지 (마지막으로 배치할 때 기준) */
    var sideBySide = true
        private set

    /** 두 칸으로 나눠 보이는 중인지. 켜면 구분선과 둘째 칸이 나타난다 */
    var split = false
        set(v) {
            field = v
            getChildAt(1).visibility = if (v) View.VISIBLE else View.GONE
            getChildAt(2).visibility = if (v) View.VISIBLE else View.GONE
            requestLayout()
        }
    private val splitting get() = split

    /** XML에는 [첫 칸, 둘째 칸]만 적고, 둘 사이에 구분선을 끼워 넣는다 */
    override fun onFinishInflate() {
        super.onFinishInflate()
        addView(Divider(context).apply { visibility = View.GONE }, 1)
        getChildAt(2).visibility = View.GONE
    }

    override fun onMeasure(widthSpec: Int, heightSpec: Int) {
        val w = MeasureSpec.getSize(widthSpec)
        val h = MeasureSpec.getSize(heightSpec)
        setMeasuredDimension(w, h)
        sideBySide = SplitMath.sideBySide(w, h)
        if (!splitting) {
            getChildAt(0).measure(exact(w), exact(h))
            return
        }
        val total = if (sideBySide) w else h
        val first = SplitMath.firstLength(total, dividerPx, ratio)
        val second = (total - dividerPx - first).coerceAtLeast(0)
        val cross = if (sideBySide) h else w
        measureAlong(getChildAt(0), first, cross)
        measureAlong(getChildAt(1), dividerPx, cross)
        measureAlong(getChildAt(2), second, cross)
    }

    private fun measureAlong(v: View, along: Int, cross: Int) =
        if (sideBySide) v.measure(exact(along), exact(cross)) else v.measure(exact(cross), exact(along))

    private fun exact(px: Int) = MeasureSpec.makeMeasureSpec(px, MeasureSpec.EXACTLY)

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        val h = b - t
        if (!splitting) {
            getChildAt(0).layout(0, 0, w, h)
            return
        }
        var pos = 0
        for (i in 0 until 3) {
            val v = getChildAt(i)
            val along = if (sideBySide) v.measuredWidth else v.measuredHeight
            if (sideBySide) v.layout(pos, 0, pos + along, h) else v.layout(0, pos, w, pos + along)
            pos += along
        }
    }

    /** 구분선의 가운데가 이 레이아웃 안 [center] 자리에 오도록 (구분선이 보내 준다) */
    private fun dragTo(center: Float, done: Boolean) {
        val total = if (sideBySide) width else height
        val r = SplitMath.ratioAt(center, total, dividerPx)
        ratio = r
        onRatioChanged?.invoke(ratio, done)
    }

    /** 칸 사이의 구분선: 끌면 비율이 바뀐다. 가운데에 잡는 자리 표시 */
    @SuppressLint("ViewConstructor")
    class Divider(ctx: Context) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val bg = Paint().apply { color = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutlineVariant, 0xFFC4C6D0.toInt()) }
        private val grip = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF5F6B7D.toInt() }
        private val gripRect = RectF()
        private val loc = IntArray(2)
        private var dragging = false

        private val layout get() = parent as SplitLayout

        override fun onDraw(canvas: Canvas) {
            canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bg)
            // 잡는 자리: 가운데의 가는 알약 (좌우 분할이면 세로로, 위아래 분할이면 가로로)
            val len = 44 * d
            val thick = 4 * d
            val cx = width / 2f
            val cy = height / 2f
            if (layout.sideBySide) gripRect.set(cx - thick / 2, cy - len / 2, cx + thick / 2, cy + len / 2)
            else gripRect.set(cx - len / 2, cy - thick / 2, cx + len / 2, cy + thick / 2)
            canvas.drawRoundRect(gripRect, thick / 2, thick / 2, grip)
        }

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            val lay = layout
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    lay.requestDisallowInterceptTouchEvent(true)
                    return true
                }
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP -> {
                    if (!dragging) return false
                    lay.getLocationOnScreen(loc)
                    val center = if (lay.sideBySide) ev.rawX - loc[0] else ev.rawY - loc[1]
                    val done = ev.actionMasked == MotionEvent.ACTION_UP
                    lay.dragTo(center, done)
                    if (done) dragging = false
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (dragging) lay.onRatioChanged?.invoke(lay.ratio, true)
                    dragging = false
                    return true
                }
            }
            return true
        }
    }

    private companion object {
        const val DIVIDER_DP = 18
    }
}
