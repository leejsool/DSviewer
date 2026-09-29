package com.dsviewer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import androidx.appcompat.content.res.AppCompatResources

/**
 * 보정 펜 아이콘: 펜(ic_pen_body)을 오른쪽 위에 조금 작게, 왼쪽 아래에 f(x).
 * f(x) 글자는 지금 고른 펜 색 (펜 아이콘의 S자 곡선과 같은 방식).
 */
class ShapePenDrawable(ctx: Context) : Drawable() {

    private val body = AppCompatResources.getDrawable(ctx, R.drawable.ic_pen_body)!!.mutate()
    private val size = (24 * ctx.resources.displayMetrics.density).toInt()
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD_ITALIC)
    }

    var markColor: Int = 0xFF000000.toInt()
        set(v) { field = v; invalidateSelf() }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val s = b.width() / 24f
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        // 펜: 오른쪽 위 모서리를 기준으로 75%
        canvas.save()
        canvas.scale(0.75f, 0.75f, 24 * s, 0f)
        body.setBounds(0, 0, (24 * s).toInt(), (24 * s).toInt())
        body.draw(canvas)
        canvas.restore()
        // f(x)
        text.color = markColor
        text.textSize = 10f * s
        canvas.drawText("f(x)", 0.5f * s, 22.5f * s, text)
        canvas.restore()
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun setAlpha(alpha: Int) {
        body.alpha = alpha
        text.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        body.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
