package com.dsviewer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.HorizontalScrollView
import android.widget.ScrollView
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min

/**
 * 스크롤이 생길 때만 양 끝에 « » (세로면 ︽ ︾) 단추를 그리고, 누르면 한 쪽 분량 옮긴다.
 * 더 갈 곳이 없는 끝에는 그리지 않아서, 스크롤이 있다는 표시와 이동 단추를 겸한다.
 * 단추 자리를 누르면 그 밑의 칸은 눌리지 않는다.
 */
internal class EdgeArrows(private val view: View, private val vertical: Boolean, private val scrollBy: (Int) -> Unit) {
    private val d = view.resources.displayMetrics.density
    /** 스크롤 방향으로 단추가 차지하는 길이 */
    private val zone = 24f * d
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2.4f * d
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val chevron = Path().apply {
        // 왼쪽을 가리키는 « 한 쌍 (원점이 가운데)
        for (dx in floatArrayOf(-3f, 3f)) {
            moveTo((dx + 2.5f) * d, -5f * d)
            lineTo((dx - 2.5f) * d, 0f)
            lineTo((dx + 2.5f) * d, 5f * d)
        }
    }

    /** 누른 단추: -1 앞쪽, 1 뒤쪽, 0 없음 */
    private var pressed = 0

    private fun can(dir: Int) = if (vertical) view.canScrollVertically(dir) else view.canScrollHorizontally(dir)

    private fun size() = if (vertical) view.height else view.width

    private fun hit(x: Float, y: Float): Int {
        val p = if (vertical) y else x
        return when {
            p < zone && can(-1) -> -1
            p > size() - zone && can(1) -> 1
            else -> 0
        }
    }

    fun draw(c: Canvas) {
        val w = view.width.toFloat()
        val h = view.height.toFloat()
        if (w <= 0 || h <= 0) return
        bg.color = (MaterialColors.getColor(view, com.google.android.material.R.attr.colorSurfaceContainerHighest) and 0x00FFFFFF) or (0xEE shl 24)
        fg.color = MaterialColors.getColor(view, com.google.android.material.R.attr.colorOnSurface)
        c.save()
        // 스크롤한 만큼 되돌려 보이는 자리에 고정해서 그린다
        c.translate(view.scrollX.toFloat(), view.scrollY.toFloat())
        for (dir in intArrayOf(-1, 1)) {
            if (!can(dir)) continue
            val inset = 3f * d
            if (vertical) {
                val top = if (dir < 0) 0f else h - zone
                rect.set(inset, top, w - inset, top + zone)
            } else {
                val left = if (dir < 0) 0f else w - zone
                rect.set(left, inset, left + zone, h - inset)
            }
            val r = 8f * d
            bg.alpha = if (pressed == dir) 255 else 0xEE
            c.drawRoundRect(rect, r, r, bg)
            c.save()
            c.translate(rect.centerX(), rect.centerY())
            // 왼쪽 « 을 돌려서: 위 ︽ 90°, 오른쪽 » 180°, 아래 ︾ 270°
            c.rotate(if (vertical) (if (dir < 0) 90f else 270f) else (if (dir < 0) 0f else 180f))
            c.drawPath(chevron, fg)
            c.restore()
        }
        c.restore()
    }

    /** 가로채야 하면(단추 자리를 누름) true */
    fun intercept(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) pressed = hit(ev.x, ev.y)
        return pressed != 0
    }

    fun touch(ev: MotionEvent): Boolean {
        if (pressed == 0) return false
        when (ev.actionMasked) {
            MotionEvent.ACTION_UP -> {
                val p = pressed
                pressed = 0
                if (hit(ev.x, ev.y) == p) {
                    view.playSoundEffect(android.view.SoundEffectConstants.CLICK)
                    // 한 쪽 분량보다 조금 덜 (앞 칸이 이어서 보이게)
                    scrollBy(p * max(1, (size() * 0.7f).toInt()))
                }
                view.invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pressed = 0
                view.invalidate()
            }
            MotionEvent.ACTION_DOWN -> view.invalidate()
        }
        return true
    }
}

/** 스크롤이 생기면 양 끝에 « » 단추가 나타나는 가로 스크롤 ([EdgeArrows]) */
class EdgeArrowScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : HorizontalScrollView(ctx, attrs) {
    private val arrows = EdgeArrows(this, false) { smoothScrollBy(it, 0) }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        arrows.draw(canvas)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = arrows.intercept(ev) || super.onInterceptTouchEvent(ev)

    override fun onTouchEvent(ev: MotionEvent) = arrows.touch(ev) || super.onTouchEvent(ev)
}

/** 스크롤이 생기면 위·아래 끝에 ︽ ︾ 단추가 나타나는 세로 스크롤 ([EdgeArrows]) */
class EdgeArrowVerticalScrollView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : ScrollView(ctx, attrs) {
    private val arrows = EdgeArrows(this, true) { smoothScrollBy(0, it) }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        arrows.draw(canvas)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent) = arrows.intercept(ev) || super.onInterceptTouchEvent(ev)

    override fun onTouchEvent(ev: MotionEvent) = arrows.touch(ev) || super.onTouchEvent(ev)
}
