package com.dsviewer.app

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import kotlin.math.roundToInt

/**
 * 스포이트: 색 고르기 창에서 누르면 창을 접고, 지금 화면(문서·툴바 …)을 한 장 찍어 둔 뒤
 * 화면을 눌러 그 자리의 색을 집는다. 누른 채 움직이면 손가락 위에 돋보기로 확대해 색을 보여 주고 손을 떼면 확정.
 * 위쪽 ‘취소’로 그만둔다.
 */
internal object Eyedropper {

    /** 화면을 찍어 스포이트를 띄운다. 집으면 [onPick]에 색(불투명)이 온다 */
    fun start(activity: Activity, onPick: (Int) -> Unit) {
        val window = activity.window
        val decor = window.decorView
        if (decor.width <= 0 || decor.height <= 0) return
        val bmp = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        // 방금 접은 색 고르기 창이 사라진 다음 화면을 찍는다
        decor.post {
            PixelCopy.request(window, bmp, { result ->
                if (result == PixelCopy.SUCCESS) show(activity, bmp, onPick)
                else Toast.makeText(activity, "화면의 색을 읽지 못했습니다.", Toast.LENGTH_SHORT).show()
            }, Handler(Looper.getMainLooper()))
        }
    }

    private fun show(activity: Activity, bmp: Bitmap, onPick: (Int) -> Unit) {
        val decor = activity.window.decorView as? ViewGroup ?: return
        val root = FrameLayout(activity)
        fun close() { decor.removeView(root) }
        val pick = EyedropperView(activity, bmp) { color ->
            close()
            if (color != null) onPick(color)
        }
        root.addView(pick, FrameLayout.LayoutParams(-1, -1))

        // 안내 줄 + 취소
        val d = activity.resources.displayMetrics.density
        val top = ViewCompat.getRootWindowInsets(decor)?.getInsets(WindowInsetsCompat.Type.systemBars())?.top ?: 0
        val bar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((16 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
            background = GradientDrawable().apply { cornerRadius = 28 * d; setColor(Color.argb(225, 32, 33, 36)) }
        }
        bar.addView(TextView(activity).apply {
            text = "색을 집을 곳을 누르고 손을 떼세요"
            setTextColor(Color.WHITE)
            textSize = 14f
        })
        bar.addView(TextView(activity).apply {
            text = "취소"
            setTextColor(Color.rgb(138, 180, 248))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
            setPadding((16 * d).toInt(), (8 * d).toInt(), (12 * d).toInt(), (8 * d).toInt())
            setOnClickListener { close() }
        })
        root.addView(bar, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = top + (10 * d).toInt() })
        decor.addView(root, ViewGroup.LayoutParams(-1, -1))
    }
}

/** 찍어 둔 화면 [shot] 위에서 손가락 자리의 색을 돋보기로 보여 주는 뷰. 손을 떼면 [done]에 색, 취소되면 null */
private class EyedropperView(ctx: Context, private val shot: Bitmap, private val done: (Int?) -> Unit) : View(ctx) {

    private val d = resources.displayMetrics.density
    private val radius = 58 * d
    /** 돋보기에 담는 화소 수 (가로·세로, 홀수 — 가운데 한 칸이 집는 화소) */
    private val cells = 17
    private val pixels = Paint().apply { isFilterBitmap = false; isAntiAlias = false }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 5 * d }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1.5f * d; color = Color.argb(150, 0, 0, 0) }
    private val cellEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = Color.WHITE }
    private val cellEdgeOut = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 1 * d; color = Color.BLACK }
    private val chipFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 13 * d; typeface = Typeface.MONOSPACE; textAlign = Paint.Align.CENTER }
    private val clip = Path()
    private val src = Rect()
    private val dst = RectF()

    private var touching = false
    private var x = 0f
    private var y = 0f
    private var color = Color.WHITE

    private fun sample(px: Float, py: Float): Int {
        val ix = px.roundToInt().coerceIn(0, shot.width - 1)
        val iy = py.roundToInt().coerceIn(0, shot.height - 1)
        return shot.getPixel(ix, iy) or 0xFF000000.toInt()
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                touching = true
                x = e.x; y = e.y
                color = sample(x, y)
                invalidate()
            }
            MotionEvent.ACTION_UP -> done(if (touching) sample(e.x, e.y) else null)
            MotionEvent.ACTION_CANCEL -> done(null)
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!touching) return
        // 손가락 위쪽에 띄우되, 위가 모자라면 아래쪽에
        val gap = 36 * d
        var cy = y - gap - radius
        if (cy - radius < 0) cy = y + gap + radius
        val cx = x.coerceIn(radius, (width - radius).coerceAtLeast(radius))

        // 돋보기: 집는 화소 둘레를 확대
        val half = cells / 2
        val ix = x.roundToInt(); val iy = y.roundToInt()
        src.set(ix - half, iy - half, ix + half + 1, iy + half + 1)
        dst.set(cx - radius, cy - radius, cx + radius, cy + radius)
        clip.reset()
        clip.addCircle(cx, cy, radius, Path.Direction.CW)
        canvas.save()
        canvas.clipPath(clip)
        canvas.drawColor(Color.WHITE)
        // 화면 밖으로 나간 부분은 흰색 (drawBitmap은 src가 비트맵 밖이어도 잘라서 그린다)
        val sx0 = src.left.coerceAtLeast(0); val sy0 = src.top.coerceAtLeast(0)
        val sx1 = src.right.coerceAtMost(shot.width); val sy1 = src.bottom.coerceAtMost(shot.height)
        if (sx1 > sx0 && sy1 > sy0) {
            val k = 2 * radius / cells
            val vis = Rect(sx0, sy0, sx1, sy1)
            val to = RectF(
                dst.left + (sx0 - src.left) * k, dst.top + (sy0 - src.top) * k,
                dst.left + (sx1 - src.left) * k, dst.top + (sy1 - src.top) * k,
            )
            canvas.drawBitmap(shot, vis, to, pixels)
        }
        canvas.restore()

        // 가운데 칸 표시 + 고른 색 테두리
        val k = 2 * radius / cells
        val c = RectF(cx - k / 2, cy - k / 2, cx + k / 2, cy + k / 2)
        canvas.drawRect(c, cellEdge)
        canvas.drawRect(c, cellEdgeOut)
        ring.color = color
        canvas.drawCircle(cx, cy, radius, ring)
        canvas.drawCircle(cx, cy, radius + 2.5f * d, outline)

        // 아래에 색 코드
        val label = "#%06X".format(color and 0xFFFFFF)
        val w = chipText.measureText(label) + 20 * d
        val h = 24 * d
        val top = cy + radius + 8 * d
        chipFill.color = color
        canvas.drawRoundRect(RectF(cx - w / 2, top, cx + w / 2, top + h), h / 2, h / 2, chipFill)
        chipText.color = if (Color.luminance(color) > 0.5f) Color.BLACK else Color.WHITE
        canvas.drawText(label, cx, top + h / 2 + chipText.textSize * 0.35f, chipText)
    }
}
