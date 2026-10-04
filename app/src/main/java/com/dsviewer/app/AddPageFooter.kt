package com.dsviewer.app

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 마지막 쪽 아래에서 빈 쪽을 붙이는 두 방법:
 * - 끌어 올리기: 끝에서 더 올리면 새 쪽 자리가 드러나고, 충분히 올렸다 놓으면 붙는다
 * - '⊕ 빈 쪽 추가' 단추: 톡 누르면 붙는다 (움직이면 누름을 풀고 문서 넘기기로)
 * 문서 화면([view])의 위치·크기는 [Host]로 읽는다. 계산은 [AddPageMath]에 있다.
 */
internal class AddPageFooter(
    private val view: View,
    private val host: Host,
    private val accent: Int,
) {

    interface Host {
        val readOnly: Boolean
        val scale: Float
        val offsetX: Float
        val offsetY: Float
        val docWidth: Float
        val docHeight: Float
        /** 아래에 겹쳐 뜬 줄이 가리는 높이 */
        val bottomInset: Float
        val pageCount: Int
        /** 마지막 쪽의 아래 끝 (문서 좌표)과 크기 (쪽 단위). 쪽이 없으면 부르지 않는다 */
        val lastPageBottom: Float
        val lastPageWidth: Float
        val lastPageHeight: Float
        val pageGap: Float
        val rightMargin: Float
        /** 굴러가던 스크롤을 멈춘다 */
        fun stopFling()
        /** 단추에서 시작한 동작을 문서 넘기기로 넘길 때: 제스처 감지기에 이벤트를 준다 */
        fun forwardToGestures(ev: MotionEvent)
        /** 빈 쪽을 붙이라고 알린다 */
        fun onAddPage()
    }

    private val density = view.resources.displayMetrics.density

    /** 마지막 쪽 아래로 더 끌어 올린 거리 (화면 px). 그만큼 쪽들을 위로 밀어 그리고 아래에 새 쪽 자리를 보여 준다 */
    var pullPx = 0f
        private set
    private var pullAnimator: ValueAnimator? = null
    private val pullGoal get() = PULL_ADD_DP * density
    private val pullFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val pullLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        pathEffect = DashPathEffect(floatArrayOf(6f * density, 4f * density), 0f)
    }
    private val pullIcon = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pullText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * density
        textAlign = Paint.Align.CENTER
    }

    /** 마지막 쪽 아래에 단추를 두는 자리의 높이 (화면 px). 읽기 모드에서는 없다 */
    val footerPx get() = if (host.readOnly) 0f else ADD_FOOTER_DP * density
    private val addRect = RectF()
    /** 단추를 누르고 있는 중 (손가락·펜 모두) */
    private var tracking = false
    private var pressed = false
    private var downX = 0f
    private var downY = 0f
    private val addFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val addLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val addText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 14f * density
        isFakeBoldText = true
    }
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop
    private val tmpRect = RectF()

    /** 새 문서를 열 때: 끌어 올려 둔 것을 버린다 */
    fun reset() {
        pullAnimator?.cancel()
        pullPx = 0f
    }

    fun setPull(v: Float) {
        val nv = AddPageMath.clampPull(v, pullGoal)
        if (nv == pullPx) return
        // 놓으면 붙는 거리를 넘을 때 손에 톡 알려 준다
        if (AddPageMath.crossedGoal(pullPx, nv, pullGoal)) view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        pullAnimator?.cancel()
        pullPx = nv
        view.invalidate()
    }

    /**
     * 위로 [dy]만큼 움직이려 할 때 끌어 올려 둔 새 쪽 자리부터 접고 남은 움직임을 돌려준다.
     * 스크롤한 뒤 마지막 쪽 끝에 막혔으면 [stretchPull]을 부른다
     */
    fun foldBack(dy: Float): Float {
        val r = AddPageMath.foldBack(pullPx, dy)
        if (r[0] != pullPx) setPull(r[0])
        return r[1]
    }

    /** 마지막 쪽 끝에서 더 올리려 한 만큼 ([want] 원하던 위치, [clamped] 실제 위치) 새 쪽 자리를 끌어낸다 */
    fun stretchPull(want: Float, clamped: Float) {
        if (host.readOnly) return
        setPull(AddPageMath.stretch(pullPx, want, clamped))
    }

    /** 손을 뗌: 충분히 끌어 올렸고 [add]면 빈 쪽을 붙이라고 알리고, 새 쪽 자리는 다시 접는다 */
    fun release(add: Boolean) {
        if (pullPx <= 0f) return
        if (add && AddPageMath.isReady(pullPx, pullGoal)) host.onAddPage()
        pullAnimator?.cancel()
        pullAnimator = ValueAnimator.ofFloat(pullPx, 0f).apply {
            duration = 200
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                pullPx = it.animatedValue as Float
                view.invalidate()
            }
            start()
        }
    }

    /** 마지막 쪽 아래에 드러난 새 쪽 자리: 점선 쪽 테두리 + 가운데 ⊕와 안내 글 */
    fun drawPullPage(canvas: Canvas) {
        if (host.pageCount <= 0) return
        val s = host.scale
        val box = AddPageMath.pullPageBox(
            host.docWidth, s, host.offsetX, host.offsetY, pullPx,
            host.lastPageBottom, host.lastPageWidth, host.lastPageHeight, host.pageGap, host.rightMargin, footerPx,
        )
        val left = box[0]
        val top = box[1]
        val pw = box[2]
        val bottom = view.height - host.bottomInset
        if (bottom - top < 8f * density) return
        val ready = AddPageMath.isReady(pullPx, pullGoal)
        val k = AddPageMath.progress(pullPx, pullGoal)
        val color = if (ready) accent else 0xFF888888.toInt()
        tmpRect.set(left, top, left + pw, top + box[3])
        pullFill.alpha = (110 + 145 * k).toInt()
        canvas.drawRect(tmpRect, pullFill)
        pullLine.color = color
        canvas.drawRect(tmpRect, pullLine)
        // ⊕와 글은 드러난 부분의 가운데에
        val r = 16f * density
        val textH = pullText.textSize * 1.6f
        if (bottom - top < r * 2 + textH + 8f * density) return
        val label = if (ready) "놓으면 빈 쪽이 추가됩니다" else "더 올리면 빈 쪽 추가"
        // 확대해서 쪽이 화면보다 넓으면 쪽 가운데가 화면 밖일 수 있다 → 화면에 보이는 부분의 가운데에.
        // 글이 화면 가장자리에 잘리지 않게도
        val half = pullText.measureText(label) / 2f + 8f * density
        val w = view.width.toFloat()
        val cx = AddPageMath.centerX(max(left, 0f), min(left + pw, w), w, half)
        val cy = (top + bottom) / 2f - textH / 2f
        pullIcon.style = if (ready) Paint.Style.FILL else Paint.Style.STROKE
        pullIcon.strokeWidth = 2f * density
        pullIcon.color = color
        canvas.drawCircle(cx, cy, r, pullIcon)
        pullIcon.color = if (ready) Color.WHITE else color
        val arm = r * 0.5f
        canvas.drawLine(cx - arm, cy, cx + arm, cy, pullIcon)
        canvas.drawLine(cx, cy - arm, cx, cy + arm, pullIcon)
        pullText.color = color
        canvas.drawText(label, cx, cy + r + textH * 0.8f, pullText)
    }

    /** '빈 쪽 추가' 단추 자리를 [addRect]에 (화면 좌표, 끌어 올린 거리는 빼고). 단추가 없으면 false */
    private fun buttonRect(): Boolean {
        val footer = footerPx
        if (footer <= 0f || host.pageCount == 0) return false
        val r = AddPageMath.buttonRect(
            host.docWidth, host.docHeight, host.scale, host.offsetX, host.offsetY,
            view.width.toFloat(), footer, addText.measureText(ADD_LABEL), density,
        )
        addRect.set(r[0], r[1], r[2], r[3])
        return true
    }

    /** 마지막 쪽 아래의 '⊕ 빈 쪽 추가' 알약 단추 (쪽들과 함께 끌어 올려진 canvas에 그린다) */
    fun drawButton(canvas: Canvas) {
        if (!buttonRect()) return
        if (addRect.bottom < 0f || addRect.top > view.height) return
        val color = if (pressed) accent else 0xFF6B7380.toInt()
        val r = addRect.height() / 2f
        addFill.color = if (pressed) 0xFFE3EEFC.toInt() else Color.WHITE
        canvas.drawRoundRect(addRect, r, r, addFill)
        addLine.color = color
        addLine.strokeWidth = 1.5f * density
        canvas.drawRoundRect(addRect, r, r, addLine)
        // ⊕ 아이콘 + 글
        val ir = 9f * density
        val icx = addRect.left + 14f * density + ir
        val cy = addRect.centerY()
        addLine.strokeWidth = 1.8f * density
        canvas.drawCircle(icx, cy, ir, addLine)
        val arm = ir * 0.55f
        canvas.drawLine(icx - arm, cy, icx + arm, cy, addLine)
        canvas.drawLine(icx, cy - arm, icx, cy + arm, addLine)
        addText.color = color
        val tx = icx + ir + 8f * density
        canvas.drawText(ADD_LABEL, tx, cy - (addText.ascent() + addText.descent()) / 2f, addText)
    }

    /**
     * '빈 쪽 추가' 단추를 누르는 동안의 터치 (손가락·펜 모두). 단추에서 시작한 동작은 여기서 받는다.
     * 움직이면 누름을 풀고 문서 넘기기로 (제스처 감지기에 그대로 넘긴다), 단추 위에서 떼면 빈 쪽 추가
     */
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (pullPx > 0f || !buttonRect()) return false
                val r = floatArrayOf(addRect.left, addRect.top, addRect.right, addRect.bottom)
                if (!AddPageMath.hitsButton(r, ev.x, ev.y, 6f * density)) return false
                tracking = true
                pressed = true
                downX = ev.x
                downY = ev.y
                host.stopFling()
                view.invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (tracking) {
                if (pressed && hypot(ev.x - downX, ev.y - downY) > touchSlop) {
                    pressed = false
                    view.invalidate()
                    // 넘기기로: 제스처 감지기는 DOWN을 못 받았으므로 지금 자리에서 새로 시작시킨다
                    val down = MotionEvent.obtain(ev)
                    down.action = MotionEvent.ACTION_DOWN
                    host.forwardToGestures(down)
                    down.recycle()
                } else if (!pressed) host.forwardToGestures(ev)
                return true
            }
            MotionEvent.ACTION_UP -> if (tracking) {
                val click = pressed
                tracking = false
                pressed = false
                if (click) {
                    view.playSoundEffect(SoundEffectConstants.CLICK)
                    view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    host.onAddPage()
                } else host.forwardToGestures(ev)
                view.invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> if (tracking) {
                tracking = false
                pressed = false
                host.forwardToGestures(ev)
                view.invalidate()
                return true
            }
        }
        return tracking
    }

    private companion object {
        /** 마지막 쪽 아래로 이만큼(dp) 끌어 올렸다 놓으면 빈 쪽을 붙인다 */
        const val PULL_ADD_DP = 90f
        /** 마지막 쪽 아래 '빈 쪽 추가' 단추 자리의 높이 (dp) */
        const val ADD_FOOTER_DP = 72f
        const val ADD_LABEL = "빈 쪽 추가"
    }
}
