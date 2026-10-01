package com.dsviewer.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.ColorDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import com.google.android.material.slider.Slider
import kotlin.math.roundToInt

/**
 * 도구별 굵기 범위. 펜·형광펜 굵기는 PDF 단위(pt, 1pt = 0.3528mm), 지우개는 화면 반지름(dp).
 * key는 설정 저장 이름 (칸 이전 버전의 한 개 값 이름과 같음).
 */
enum class WidthKind(val min: Float, val max: Float, val step: Float, val defaults: FloatArray, val key: String) {
    PEN(0.3f, 4f, 0.05f, floatArrayOf(0.6f, 1.2f, 2.2f), "penWidth"),
    HIGHLIGHTER(4f, 30f, 0.5f, floatArrayOf(8f, 14f, 22f), "hlWidth"),
    ERASER(6f, 40f, 1f, floatArrayOf(8f, 14f, 24f), "eraserRadius"),
    /** 레이저는 확대와 상관없이 화면에서 늘 같은 굵기 (dp) */
    LASER(2f, 16f, 0.5f, floatArrayOf(4f, 7f, 11f), "laserWidth"),
    /** 펜 테이프 굵기 (pt) */
    TAPE(4f, 40f, 0.5f, floatArrayOf(10f, 16f, 24f), "tapeWidth"),
    /** 붓펜 굵기 (pt, 세게 누르면 이보다 굵어진다) */
    BRUSH(0.5f, 12f, 0.1f, floatArrayOf(1.5f, 3f, 5f), "penWidth_BRUSH"),
    /** 캘리그래피 펜촉 너비 (pt) */
    NIB(1f, 14f, 0.1f, floatArrayOf(2f, 3.5f, 6f), "penWidth_CALLIGRAPHY");

    /** 범위 안에서의 위치 0..1 */
    fun t(v: Float) = ((v - min) / (max - min)).coerceIn(0f, 1f)

    fun label(v: Float) =
        when (this) {
            ERASER -> "크기 ${v.roundToInt()}"
            LASER -> "굵기 ${"%.1f".format(v)}"
            else -> String.format("%.2f mm", v * 0.3528f)
        }

    /** 굵기 칸에 적는 짧은 수치 (단위 없이): 펜·형광펜·테이프는 mm, 지우개는 크기, 레이저는 굵기 */
    fun short(v: Float): String {
        fun trim(x: Float, digits: Int) = String.format("%.${digits}f", x).trimEnd('0').trimEnd('.')
        return when (this) {
            ERASER -> "${v.roundToInt()}"
            LASER -> trim(v, 1)
            else -> (v * 0.3528f).let { mm -> trim(mm, if (mm < 1f) 2 else 1) }
        }
    }

    companion object {
        fun of(t: Tool) = when (t) {
            Tool.PEN, Tool.SHAPE -> PEN
            Tool.HIGHLIGHTER -> HIGHLIGHTER
            Tool.ERASER -> ERASER
            Tool.LASER -> LASER
            Tool.TAPE -> TAPE
            Tool.LASSO, Tool.TEXT, Tool.FILL -> error("선택·글·채우기 도구에는 굵기가 없습니다")
        }
    }
}

/**
 * 굵기 칸 하나: 펜·형광펜은 그 굵기의 짧은 선, 지우개는 그 크기의 원. 아래에 지금 수치를 작게 적는다
 * (펜·형광펜·테이프는 mm, 지우개는 크기, 레이저는 굵기)
 */
class WidthSwatchView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var kind = WidthKind.PEN
    var value = 1f
        set(v) { field = v; invalidate() }
    var color = Color.BLACK
        set(v) { field = v; invalidate() }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.argb(90, 0, 0, 0)
    }
    private val fg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = dp(9.5f)
        color = MaterialColors.getColor(this@WidthSwatchView, com.google.android.material.R.attr.colorOnSurfaceVariant)
    }

    override fun onDraw(canvas: Canvas) {
        // 아래 한 줄은 수치, 그 위 남은 자리 가운데에 모양
        val numH = dp(11f)
        val cx = width / 2f
        val cy = (height - numH) / 2f + dp(1f)
        canvas.drawText(kind.short(value), cx, height - dp(3f), number)
        val t = kind.t(value)
        if (kind == WidthKind.ERASER) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(1.5f)
            paint.color = fg
            canvas.drawCircle(cx, cy, dp(2.5f + t * 6.5f), paint)
            return
        }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = dp(if (kind == WidthKind.PEN) 1.5f + t * 6f else 2.5f + t * 7.5f)
        paint.color = if (kind == WidthKind.HIGHLIGHTER) ColorUtils.setAlphaComponent(color, 170) else color
        paint.strokeCap = if (kind == WidthKind.HIGHLIGHTER || kind == WidthKind.TAPE) Paint.Cap.SQUARE else Paint.Cap.ROUND
        // 칸이 세로로 길면(가로 툴바) 세로선, 가로로 길면(세로 툴바) 가로선
        val upright = height > width
        val half = dp(if (upright) 8f else 9f)
        val (hx, hy) = if (upright) 0f to half else half to 0f
        canvas.drawLine(cx - hx, cy - hy, cx + hx, cy + hy, paint)
        // 흰색 계열은 배경에 묻히지 않게 테두리
        if (Color.luminance(color) > 0.85f) {
            val r = paint.strokeWidth / 2
            canvas.drawRoundRect(cx - hx - r, cy - hy - r, cx + hx + r, cy + hy + r, r, r, outline)
        }
    }
}

/** 굵기 조절 창의 미리보기: 실제 굵기에 비례한 S자 곡선 (지우개는 원) */
class WidthPreviewView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var kind = WidthKind.PEN
    var value = 1f
        set(v) { field = v; invalidate() }
    var color = Color.BLACK

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private val fg = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        val t = kind.t(value)
        if (kind == WidthKind.ERASER) {
            paint.strokeWidth = dp(1.5f)
            paint.color = fg
            canvas.drawCircle(w / 2, h / 2, dp(4f + t * 24f).coerceAtMost(h / 2 - dp(2f)), paint)
            return
        }
        paint.strokeWidth = dp(if (kind == WidthKind.PEN) 1f + t * 12f else 4f + t * 22f)
        paint.color = if (kind == WidthKind.HIGHLIGHTER) ColorUtils.setAlphaComponent(color, 150) else color
        paint.strokeCap = if (kind == WidthKind.HIGHLIGHTER || kind == WidthKind.TAPE) Paint.Cap.SQUARE else Paint.Cap.ROUND
        val pad = dp(24f); val a = h * 0.22f
        path.reset()
        path.moveTo(pad, h / 2 + a)
        path.cubicTo(w * 0.3f, h / 2 - 2 * a, w * 0.4f, h / 2 - a, w * 0.5f, h / 2)
        path.cubicTo(w * 0.6f, h / 2 + a, w * 0.7f, h / 2 + 2 * a, w - pad, h / 2 - a)
        canvas.drawPath(path, paint)
    }
}

/**
 * 도구막대 위에 뜨는 굵기 조절 창.
 * onChange(value, done): 슬라이더를 끄는 동안은 done=false, 손을 떼거나 −/+를 누르면 done=true.
 */
class WidthPopup(
    activity: Activity,
    private val kind: WidthKind,
    initial: Float,
    color: Int,
    private val onChange: (value: Float, done: Boolean) -> Unit,
) {
    private val act = activity
    private val view: View = LayoutInflater.from(activity).inflate(R.layout.popup_width, null)
    private val valueText: TextView = view.findViewById(R.id.widthValue)
    private val preview: WidthPreviewView = view.findViewById(R.id.widthPreview)
    private val slider: Slider = view.findViewById(R.id.widthSlider)
    private val popup = PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)

    init {
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true
        view.findViewById<TextView>(R.id.widthTitle).text = if (kind == WidthKind.ERASER) "지우개 크기" else "굵기"
        preview.kind = kind
        preview.color = color
        slider.valueFrom = kind.min
        slider.valueTo = kind.max
        slider.stepSize = 0f
        set(initial)
        slider.addOnChangeListener { _, v, fromUser ->
            if (fromUser) { show(v); onChange(v, false) }
        }
        slider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(s: Slider) {}
            override fun onStopTrackingTouch(s: Slider) = onChange(s.value, true)
        })
        view.findViewById<View>(R.id.widthMinus).setOnClickListener { nudge(-1) }
        view.findViewById<View>(R.id.widthPlus).setOnClickListener { nudge(+1) }
    }

    private fun nudge(dir: Int) {
        // 눈금에 맞춰 한 칸씩
        val v = ((slider.value / kind.step).roundToInt() + dir) * kind.step
        set(v)
        onChange(slider.value, true)
    }

    private fun set(v: Float) {
        slider.value = v.coerceIn(kind.min, kind.max)
        show(slider.value)
    }

    private fun show(v: Float) {
        valueText.text = kind.label(v)
        preview.value = v
    }

    fun show(anchor: View, side: ToolbarSide = ToolbarSide.BOTTOM) {
        val (x, y, w) = placeNear(act, view, anchor, 400f, side)
        popup.width = w
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }
}
