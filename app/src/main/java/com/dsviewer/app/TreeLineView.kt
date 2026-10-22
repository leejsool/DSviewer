package com.dsviewer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import com.google.android.material.color.MaterialColors

/**
 * 폴더 머리글 아래 문서 줄 왼쪽의 가지선: 위에서 내려오는 세로선과 문서로 꺾이는 가로선.
 * [last]면 이 문서가 그 폴더의 마지막이라 세로선이 가운데에서 끝난다 (├ / └ 모양)
 */
class TreeLineView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var last = false
        set(v) {
            if (v == field) return
            field = v
            invalidate()
        }

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * d
        strokeCap = Paint.Cap.ROUND
        color = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutline, 0xFF74777F.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val x = 24 * d
        val cy = height / 2f
        canvas.drawLine(x, 0f, x, if (last) cy else height.toFloat(), paint)
        canvas.drawLine(x, cy, width - 2 * d, cy, paint)
    }
}
