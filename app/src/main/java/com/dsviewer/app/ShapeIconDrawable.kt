package com.dsviewer.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import com.google.android.material.color.MaterialColors
import kotlin.math.cosh
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.tan

/**
 * 보정 펜 도형 줄의 아이콘: 도형 종류마다 그 모양을 작게 그린다 (이차는 아래로 볼록한 포물선 등).
 * 24 × 24 칸 좌표로 그린다.
 */
class ShapeIconDrawable(ctx: Context, private val kind: ShapeKind) : Drawable() {

    private val size = (24 * ctx.resources.displayMetrics.density).toInt()
    private val color = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, 0xFF1C1B1F.toInt())
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.8f
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        this.color = this@ShapeIconDrawable.color
    }
    /** 점근선·축 (가늘고 옅은 점선) */
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f
        pathEffect = DashPathEffect(floatArrayOf(1.6f, 1.6f), 0f)
        this.color = this@ShapeIconDrawable.color
        alpha = 130
    }
    /** 직각 표시 */
    private val thin = Paint(line).apply { strokeWidth = 1f }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@ShapeIconDrawable.color }

    override fun draw(canvas: Canvas) {
        val b = bounds
        canvas.save()
        canvas.translate(b.left.toFloat(), b.top.toFloat())
        canvas.scale(b.width() / 24f, b.height() / 24f)
        when (kind) {
            ShapeKind.LINE -> canvas.drawLine(4f, 19f, 20f, 5f, line)
            // 아래로 볼록한 포물선
            ShapeKind.QUADRATIC -> plot(canvas, -1f, 1f) { it * it }
            // 올라갔다 내려갔다 다시 올라가는 삼차
            ShapeKind.CUBIC -> plot(canvas, -1.25f, 1.25f) { it * it * it - it }
            // W 모양 사차
            ShapeKind.QUARTIC -> plot(canvas, -1.3f, 1.3f) { it * it * it * it - 1.6f * it * it }
            ShapeKind.CIRCLE -> canvas.drawCircle(12f, 12f, 8.5f, line)
            ShapeKind.ELLIPSE -> canvas.drawOval(2.5f, 6.5f, 21.5f, 17.5f, line)
            ShapeKind.CIRCLE_CR -> {
                canvas.drawCircle(12f, 12f, 8.5f, line)
                canvas.drawCircle(12f, 12f, 1.4f, dot)
                canvas.drawLine(12f, 12f, 18f, 6f, line)
            }
            // 중심각 60°쯤의 부채꼴 (중심은 왼쪽 아래)
            ShapeKind.SECTOR -> {
                val p = Path()
                p.moveTo(4f, 19.5f)
                p.arcTo(RectF(4f - 16f, 19.5f - 16f, 4f + 16f, 19.5f + 16f), 0f, -62f)
                p.close()
                canvas.drawPath(p, line)
            }
            ShapeKind.TRIANGLE -> poly(canvas, 3f, 19f, 21f, 19f, 8f, 5f)
            ShapeKind.TRI_EQUILATERAL -> poly(canvas, 3.5f, 19f, 20.5f, 19f, 12f, 4.3f)
            ShapeKind.TRI_RIGHT -> {
                poly(canvas, 4f, 19f, 21f, 19f, 4f, 7f)
                rightMark(canvas, 4f, 19f, 1f, -1f)
            }
            ShapeKind.TRI_ISOSCELES -> poly(canvas, 6.5f, 20f, 17.5f, 20f, 12f, 3.5f)
            ShapeKind.TRI_RIGHT_ISOSCELES -> {
                // 빗변을 아래로: 꼭대기가 직각
                poly(canvas, 3f, 17f, 21f, 17f, 12f, 8f)
                canvas.save()
                canvas.rotate(45f, 12f, 8f)
                canvas.drawRect(12f, 8f, 14.8f, 10.8f, thin)
                canvas.restore()
            }
            ShapeKind.QUADRILATERAL -> poly(canvas, 4f, 18f, 19f, 20.5f, 21f, 6f, 8f, 3.5f)
            ShapeKind.SQUARE -> canvas.drawRect(5f, 5f, 19f, 19f, line)
            ShapeKind.RECTANGLE -> canvas.drawRect(2.5f, 7f, 21.5f, 17f, line)
            ShapeKind.RHOMBUS -> poly(canvas, 12f, 2.5f, 20f, 12f, 12f, 21.5f, 4f, 12f)
            ShapeKind.PARALLELOGRAM -> poly(canvas, 2.5f, 18f, 16.5f, 18f, 21.5f, 6f, 7.5f, 6f)
            ShapeKind.HYPERBOLA -> {
                // x² − y² = 1 의 두 가지와 점근선 y = ±x
                canvas.drawLine(4f, 4f, 20f, 20f, guide)
                canvas.drawLine(4f, 20f, 20f, 4f, guide)
                for (sx in floatArrayOf(-1f, 1f)) {
                    val p = Path()
                    var t = -1.6f
                    var first = true
                    while (t <= 1.6f + 1e-4f) {
                        val x = 12f + sx * 4.2f * cosh(t)
                        val y = 12f - 4.2f * sinh(t)
                        if (first) p.moveTo(x, y) else p.lineTo(x, y)
                        first = false
                        t += 0.1f
                    }
                    canvas.drawPath(p, line)
                }
            }
            ShapeKind.EXP_LOG -> {
                // y = 2^x 와 y = log₂x (y = x 에 대칭)
                canvas.drawLine(3f, 21f, 21f, 3f, guide)
                val e = Path()
                val l = Path()
                var x = -2.6f
                var first = true
                while (x <= 2.2f + 1e-4f) {
                    val y = exp(x * ln(2f))
                    // 칸 좌표: 원점 (9, 15), 한 칸 2.8
                    val px = 9f + x * 2.8f
                    val py = 15f - y * 2.8f
                    val lx = 9f + y * 2.8f
                    val ly = 15f - x * 2.8f
                    if (first) { e.moveTo(px, py); l.moveTo(lx, ly) } else { e.lineTo(px, py); l.lineTo(lx, ly) }
                    first = false
                    x += 0.1f
                }
                canvas.drawPath(e, line)
                canvas.drawPath(l, line)
            }
            // 지름이 아래인 반원
            ShapeKind.SEMICIRCLE -> {
                val p = Path()
                p.moveTo(2.5f, 16.5f)
                p.arcTo(RectF(2.5f, 7f, 21.5f, 26f), 180f, 180f)
                p.close()
                canvas.drawPath(p, line)
            }
            ShapeKind.QUAD_EXP -> {
                // y = x²eˣ: 왼쪽은 점근선 y = 0으로, 극대 하나 지나 y = 0에서 극소, 오른쪽은 치솟음
                canvas.drawLine(2f, 20f, 22f, 20f, guide)
                plot(canvas, -5.5f, 0.75f) { it * it * exp(it) }
            }
            ShapeKind.SINE -> {
                canvas.drawLine(2f, 12f, 22f, 12f, guide)
                val p = Path()
                for (i in 0..40) {
                    val u = i / 40f
                    val x = 2.5f + u * 19f
                    val y = 12f - 7f * sin(u * 2f * Math.PI.toFloat())
                    if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
                }
                canvas.drawPath(p, line)
            }
            ShapeKind.TANGENT -> {
                // 한 가지와 양쪽 점근선
                canvas.drawLine(5f, 2f, 5f, 22f, guide)
                canvas.drawLine(19f, 2f, 19f, 22f, guide)
                val p = Path()
                var first = true
                var u = -1.33f
                while (u <= 1.33f + 1e-4f) {
                    val x = 12f + u / (Math.PI.toFloat() / 2f) * 7f
                    val y = 12f - tan(u) * 2.2f
                    if (first) p.moveTo(x, y) else p.lineTo(x, y)
                    first = false
                    u += 0.05f
                }
                canvas.drawPath(p, line)
            }
        }
        canvas.restore()
    }

    /** y = f(x) (x는 [x0, x1])를 칸에 꽉 차게 그린다 (위가 +y) */
    private fun plot(canvas: Canvas, x0: Float, x1: Float, f: (Float) -> Float) {
        val n = 40
        val ys = FloatArray(n + 1) { f(x0 + (x1 - x0) * it / n) }
        val lo = ys.min()
        val hi = ys.max()
        val p = Path()
        for (i in 0..n) {
            val x = 3f + 18f * i / n
            val y = 20f - 16f * (ys[i] - lo) / (hi - lo)
            if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
        }
        canvas.drawPath(p, line)
    }

    private fun poly(canvas: Canvas, vararg xy: Float) {
        val p = Path()
        p.moveTo(xy[0], xy[1])
        for (i in 2 until xy.size step 2) p.lineTo(xy[i], xy[i + 1])
        p.close()
        canvas.drawPath(p, line)
    }

    /** 직각 표시: 꼭짓점 (x, y)에서 (dx, dy) 쪽으로 작은 네모 */
    private fun rightMark(canvas: Canvas, x: Float, y: Float, dx: Float, dy: Float) {
        val s = 3f
        val p = Path()
        p.moveTo(x + dx * s, y)
        p.lineTo(x + dx * s, y + dy * s)
        p.lineTo(x, y + dy * s)
        canvas.drawPath(p, thin)
    }

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun setAlpha(alpha: Int) {
        line.alpha = alpha
        dot.alpha = alpha
        thin.alpha = alpha
        guide.alpha = alpha * 130 / 255
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        line.colorFilter = colorFilter
        dot.colorFilter = colorFilter
        thin.colorFilter = colorFilter
        guide.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
