package com.dsviewer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.roundToInt

/** 바탕을 작은 그림으로 그린다 (서식 고르기 미리보기). 선 계산은 PDF에 그릴 때와 같은 [PaperLayout] */
internal object PaperPreview {
    fun render(paper: Paper, widthPx: Int, heightPx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val w = PdfPages.A4_SHORT
        val h = PdfPages.A4_LONG
        val sx = widthPx / w
        val sy = heightPx / h
        val d = PaperLayout.of(paper, w, h)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
        for (f in d.fills) {
            paint.color = 0xFF000000.toInt() or f.rgb
            c.drawRect(f.x * sx, heightPx - (f.y + f.h) * sy, (f.x + f.w) * sx, heightPx - f.y * sy, paint)
        }
        paint.style = Paint.Style.STROKE
        for (s in d.segs) {
            paint.color = 0xFF000000.toInt() or s.rgb
            // 아주 가는 선도 작은 그림에서 보이도록 적어도 0.8px
            paint.strokeWidth = max(0.8f, s.width * sx)
            val x1 = s.x1 * sx
            val y1 = heightPx - s.y1 * sy
            val x2 = s.x2 * sx
            val y2 = heightPx - s.y2 * sy
            val dash = s.dash
            if (dash != null && dash[0] < 1f) {
                // 점선 가로줄로 그린 점들: 작은 그림에서는 동그라미로 직접
                paint.style = Paint.Style.FILL
                val step = dash.sum() * sx
                var x = x1
                while (x <= x2 + 0.01f) {
                    c.drawCircle(x, y1, max(0.7f, s.width * sx), paint)
                    x += step
                }
                paint.style = Paint.Style.STROKE
            } else {
                paint.pathEffect = dash?.let { DashPathEffect(floatArrayOf(it[0] * sx, it[1] * sx), 0f) }
                c.drawLine(x1, y1, x2, y2, paint)
                paint.pathEffect = null
            }
        }
        paint.color = 0x33000000
        paint.strokeWidth = 1f
        c.drawRect(0.5f, 0.5f, widthPx - 0.5f, heightPx - 0.5f, paint)
        return bmp
    }
}

/**
 * 서식 고르기: 바탕 미리보기 카드들이 옆으로 늘어서고 하나를 누르면 고른다.
 * 새 노트 · 빈 쪽 넣기 · 오답 쪽 바탕 고르기 창이 같이 쓴다
 */
internal class PaperPicker(private val ctx: Context, initial: Paper, private val onChange: ((Paper) -> Unit)? = null) {
    var selected: Paper = initial
        private set

    private val density = ctx.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private val accent = 0xFF3E82F7.toInt()
    private val cards = LinkedHashMap<Paper, View>()

    val view = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }

    init {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in Paper.entries) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(4), dp(4), dp(4), dp(4))
                isClickable = true
                contentDescription = p.label
                setOnClickListener { select(p) }
            }
            card.addView(ImageView(ctx).apply {
                setImageBitmap(PaperPreview.render(p, dp(56), dp(79)))
            }, LinearLayout.LayoutParams(dp(56), dp(79)))
            card.addView(TextView(ctx).apply {
                text = p.label
                textSize = 11f
                gravity = Gravity.CENTER
                setSingleLine()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(3) })
            cards[p] = card
            row.addView(card, LinearLayout.LayoutParams(dp(68), ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(4) })
        }
        view.addView(row)
        refresh()
        // 고른 카드가 처음부터 보이게
        view.post { cards[selected]?.let { view.scrollTo((it.left - dp(16)).coerceAtLeast(0), 0) } }
    }

    private fun select(p: Paper) {
        if (p == selected) return
        selected = p
        refresh()
        onChange?.invoke(p)
    }

    private fun refresh() {
        for ((p, card) in cards) {
            val on = p == selected
            card.background = GradientDrawable().apply {
                cornerRadius = dp(8).toFloat()
                setColor(if (on) 0x1A3E82F7 else Color.TRANSPARENT)
                setStroke(dp(if (on) 2 else 1), if (on) accent else 0x22000000)
            }
        }
    }
}
