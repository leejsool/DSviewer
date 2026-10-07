package com.dsviewer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import com.google.android.material.color.MaterialColors

/**
 * 보정 펜 줄 맨 앞 '실선 ↔ 점선' 칸의 아이콘: 세로로 세운 선 하나 (가로 폭을 줄이려고). [dashed]면 점선
 */
class LineStyleIcon(ctx: Context, private val dashed: Boolean) : Drawable() {

    private val density = ctx.resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = 2.2f * density
        color = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
        if (dashed) pathEffect = DashPathEffect(floatArrayOf(3.2f * density, 3.4f * density), 0f)
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        val x = b.exactCenterX()
        val pad = b.height() * 0.1f
        canvas.drawLine(x, b.top + pad, x, b.bottom - pad, paint)
    }

    override fun setAlpha(alpha: Int) { paint.alpha = alpha }
    override fun setColorFilter(colorFilter: ColorFilter?) { paint.colorFilter = colorFilter }
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
