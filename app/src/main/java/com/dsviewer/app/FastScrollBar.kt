package com.dsviewer.app

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 삼성 노트처럼 오른쪽 가장자리에 위아래 화살표가 그려진 손잡이.
 * 스크롤하면 나타나고 2초 동안 움직임이 없으면 사라진다 (문서를 열 때도 잠깐 보여 준다).
 * 손잡이를 끌면 문서 위치로 바로 이동하고, 끄는 동안 옆에 쪽 번호를 띄운다.
 * 문서 화면([view])이 쥔 스크롤 위치는 [Host]로 읽고 쓴다.
 */
internal class FastScrollBar(private val view: View, private val host: Host) {

    interface Host {
        /** 문서 전체 높이 (화면 좌표 단위) */
        val contentHeight: Float
        /** 위·아래에 겹쳐 뜬 줄이 가리는 높이 */
        val topInset: Float
        val bottomInset: Float
        /** 지금 스크롤 위치 */
        var offsetY: Float
        /** 끄는 동안 말풍선에 보일 글 ('3 / 20'). 쪽이 없으면 null */
        fun pageBubbleText(): String?
        /** 끌기를 시작할 때: 굴러가던 스크롤과 확대 애니메이션을 멈춘다 */
        fun stopMotion()
        /** 스크롤 위치를 문서 안으로 */
        fun clampOffset()
        /** 끌기를 마쳤을 때 (확대해 둔 곳의 세밀한 그림을 다시 요청) */
        fun onDragEnd()
    }

    private val density = view.resources.displayMetrics.density

    private var barAlpha = 0f
    private var barAnimator: ValueAnimator? = null
    private var barDragging = false
    private var barGrabOffset = 0f
    private val handleW = 32f * density
    private val handleH = 56f * density
    /** 손잡이가 오가는 범위의 위아래 여백과 오른쪽 여백 */
    private val barMargin = 10f * density
    private val barRight = 6f * density
    private val colorSurface = MaterialColors.getColor(view.context, com.google.android.material.R.attr.colorSurfaceContainerHighest, Color.WHITE)
    private val colorOnSurface = MaterialColors.getColor(view.context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
    private val colorAccent = MaterialColors.getColor(view.context, androidx.appcompat.R.attr.colorPrimary, 0xFF1E5AA8.toInt())
    private val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowPath = Path()
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * density
        color = Color.WHITE
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val barRect = RectF()
    private val hideBarRunnable = Runnable { if (!barDragging) fade(0f) }

    private fun barVisible() = barAlpha > 0.05f

    /** 손잡이를 보인다: 문서가 화면보다 짧으면 안 보이고, [ms] 동안 움직임이 없으면 사라진다 */
    fun show(ms: Long) {
        if (host.contentHeight <= view.height * 1.05f) return
        fade(1f)
        view.removeCallbacks(hideBarRunnable)
        view.postDelayed(hideBarRunnable, ms)
    }

    /** 창이 닫힐 때: 예약한 숨기기와 움직이는 중인 애니메이션을 멈춘다 */
    fun cancel() {
        view.removeCallbacks(hideBarRunnable)
        barAnimator?.cancel()
    }

    private fun fade(target: Float) {
        if (barAlpha == target && barAnimator?.isRunning != true) return
        barAnimator?.cancel()
        barAnimator = ValueAnimator.ofFloat(barAlpha, target).apply {
            duration = if (target > barAlpha) 150 else 400
            addUpdateListener {
                barAlpha = it.animatedValue as Float
                view.invalidate()
            }
            start()
        }
    }

    /**
     * (손잡이가 오가는 범위의 위, 길이, 손잡이 위, 손잡이 높이). 문서가 화면보다 짧으면 null.
     * 위·아래에 겹쳐 뜬 줄(host.topInset, host.bottomInset)에 가리지 않도록 그 사이에서만
     */
    private fun barGeometry(): FloatArray? {
        val ch = host.contentHeight
        if (ch <= view.height * 1.05f) return null
        val trackTop = barMargin + host.topInset
        val trackLen = view.height - host.bottomInset - host.topInset - barMargin * 2
        if (trackLen < handleH * 1.5f) return null
        val frac = ((host.offsetY + host.topInset) / (ch - view.height)).coerceIn(0f, 1f)
        return floatArrayOf(trackTop, trackLen, trackTop + frac * (trackLen - handleH), handleH)
    }

    private fun Paint.withAlpha(c: Int, a: Float) = apply { color = c; alpha = (Color.alpha(c) * a).roundToInt() }

    fun draw(canvas: Canvas) {
        if (!barVisible()) return
        val g = barGeometry() ?: return
        val a = barAlpha
        val right = view.width - barRight
        val left = right - handleW
        val cx = (left + right) / 2
        // 손잡이가 오가는 길: 가는 선
        val tw = 1.5f * density
        barTrackPaint.withAlpha(Color.argb(50, 0, 0, 0), a)
        canvas.drawRoundRect(cx - tw, g[0], cx + tw, g[0] + g[1], tw, tw, barTrackPaint)
        // 손잡이: 알약 모양, 끄는 동안은 강조색
        barRect.set(left, g[2], right, g[2] + g[3])
        val r = handleW / 2
        if (barDragging) {
            handlePaint.withAlpha(colorAccent, a)
            canvas.drawRoundRect(barRect, r, r, handlePaint)
        } else {
            handlePaint.withAlpha(colorSurface, a)
            handlePaint.setShadowLayer(4f * density, 0f, 1f * density, Color.argb((70 * a).toInt(), 0, 0, 0))
            canvas.drawRoundRect(barRect, r, r, handlePaint)
            handlePaint.clearShadowLayer()
            handleEdgePaint.withAlpha(Color.argb(40, 0, 0, 0), a)
            canvas.drawRoundRect(barRect, r, r, handleEdgePaint)
        }
        // 위아래 화살표
        val cy = barRect.centerY()
        val s = 5f * density
        val gap = 4.5f * density
        arrowPath.reset()
        arrowPath.moveTo(cx - s, cy - gap); arrowPath.lineTo(cx, cy - gap - s); arrowPath.lineTo(cx + s, cy - gap)
        arrowPath.moveTo(cx - s, cy + gap); arrowPath.lineTo(cx, cy + gap + s); arrowPath.lineTo(cx + s, cy + gap)
        arrowPaint.withAlpha(if (barDragging) Color.WHITE else colorOnSurface, a)
        canvas.drawPath(arrowPath, arrowPaint)
        // 끄는 동안: 손잡이 왼쪽에 쪽 번호 말풍선
        val text = if (barDragging) host.pageBubbleText() else null
        if (text != null) {
            val padH = 12f * density
            val bw = bubbleText.measureText(text) + padH * 2
            val bh = 34f * density
            val bRight = left - 10f * density
            val top = (cy - bh / 2).coerceIn(0f, max(0f, view.height - bh))
            bubblePaint.withAlpha(Color.argb(225, 0x30, 0x30, 0x30), a)
            canvas.drawRoundRect(bRight - bw, top, bRight, top + bh, bh / 2, bh / 2, bubblePaint)
            val fm = bubbleText.fontMetrics
            canvas.drawText(text, bRight - bw + padH, top + bh / 2 - (fm.ascent + fm.descent) / 2, bubbleText)
        }
    }

    /** 손잡이를 잡고 끄는 입력을 처리했으면 true. 손잡이 자체만 잡는다 (가장자리 필기를 가로채지 않게) */
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val g = barGeometry() ?: return false
                if (!barVisible()) return false
                val slop = 10f * density
                val left = view.width - barRight - handleW
                val onHandle = ev.x >= left - slop && ev.y >= g[2] - slop && ev.y <= g[2] + g[3] + slop
                if (!onHandle) return false
                barGrabOffset = ev.y - g[2]
                barDragging = true
                host.stopMotion()
                view.removeCallbacks(hideBarRunnable)
                fade(1f)
                view.invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (barDragging) {
                barGeometry()?.let { moveThumbTo(ev.y, it) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (barDragging) {
                barDragging = false
                view.postDelayed(hideBarRunnable, 2000)
                host.onDragEnd()
                view.invalidate()
                return true
            }
        }
        return barDragging
    }

    private fun moveThumbTo(y: Float, g: FloatArray) {
        val ch = host.contentHeight
        val movable = g[1] - g[3]
        if (movable <= 0f) return
        val frac = ((y - barGrabOffset - g[0]) / movable).coerceIn(0f, 1f)
        host.offsetY = frac * (ch - view.height) - host.topInset
        host.clampOffset()
        view.invalidate()
    }
}
