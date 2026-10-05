package com.dsviewer.app

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.view.isVisible
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/** 팔레트 탭의 기본 색: 맨 윗줄 흰색→검정, 그 아래로 12가지 색상 × 5단계 밝기 */
object ColorPalette {
    const val COLUMNS = 12
    private val HUES = floatArrayOf(0f, 22f, 40f, 55f, 90f, 135f, 170f, 195f, 215f, 240f, 275f, 320f)
    /** (채도, 명도) — 연한 색부터 진한 색까지 */
    private val SHADES = arrayOf(
        floatArrayOf(0.25f, 1f), floatArrayOf(0.5f, 1f), floatArrayOf(0.8f, 0.95f),
        floatArrayOf(0.9f, 0.72f), floatArrayOf(0.9f, 0.45f),
    )

    val colors: List<Int> by lazy {
        val out = ArrayList<Int>()
        for (i in 0 until COLUMNS) out += Color.HSVToColor(floatArrayOf(0f, 0f, 1f - i / (COLUMNS - 1f)))
        for (s in SHADES) for (h in HUES) out += Color.HSVToColor(floatArrayOf(h, s[0], s[1]))
        out
    }
}

internal fun View.dp(v: Float) = v * resources.displayMetrics.density

/**
 * 툴바의 누른 버튼 옆, 문서 쪽으로 뜨는 창의 위치 (x, y, 너비).
 * 툴바가 아래면 버튼 위, 위면 버튼 아래, 왼쪽이면 버튼 오른쪽, 오른쪽이면 버튼 왼쪽.
 * content는 이 너비로 미리 재 둔다.
 */
internal fun placeNear(
    activity: Activity, content: View, anchor: View, maxWidthDp: Float, side: ToolbarSide = ToolbarSide.BOTTOM,
): Triple<Int, Int, Int> {
    val margin = content.dp(8f).toInt()
    val screenW = activity.window.decorView.width.takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels
    val screenH = activity.window.decorView.height.takeIf { it > 0 } ?: activity.resources.displayMetrics.heightPixels
    val loc = IntArray(2)
    // 팝업 좌표(showAtLocation)는 창 기준이다: 분할 화면에서 화면 기준 좌표를 쓰면 창 위치만큼 어긋난다
    anchor.getLocationInWindow(loc)
    // 옆에 뜰 때는 버튼 옆 남은 폭 안에 들어가게
    val room = when (side) {
        ToolbarSide.LEFT -> screenW - (loc[0] + anchor.width) - 2 * margin
        ToolbarSide.RIGHT -> loc[0] - 2 * margin
        else -> screenW - 2 * margin
    }
    val w = min(room, content.dp(maxWidthDp).toInt()).coerceAtLeast(content.dp(200f).toInt())
    content.measure(
        View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
    )
    val h = content.measuredHeight
    val centerX = (loc[0] + anchor.width / 2 - w / 2).coerceIn(margin, max(margin, screenW - w - margin))
    val centerY = (loc[1] + anchor.height / 2 - h / 2).coerceIn(margin, max(margin, screenH - h - margin))
    return when (side) {
        ToolbarSide.BOTTOM -> Triple(centerX, max(margin, loc[1] - h), w)
        ToolbarSide.TOP -> Triple(centerX, min(loc[1] + anchor.height, max(margin, screenH - h - margin)), w)
        ToolbarSide.LEFT -> Triple(min(loc[0] + anchor.width + margin, max(margin, screenW - w - margin)), centerY, w)
        ToolbarSide.RIGHT -> Triple(max(margin, loc[0] - w - margin), centerY, w)
    }
}

/** 동그란 색 견본을 격자로 늘어놓은 뷰 */
class SwatchGridView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var columns = ColorPalette.COLUMNS
    var colors: List<Int> = emptyList()
        set(v) { field = v; requestLayout(); invalidate() }
    var selected: Int? = null
        set(v) { field = v; invalidate() }
    var onPick: ((Int) -> Unit)? = null

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.argb(70, 0, 0, 0)
    }
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = dp(3f)
        color = MaterialColors.getColor(this@SwatchGridView, androidx.appcompat.R.attr.colorPrimary)
    }

    private val cell get() = width.toFloat() / columns

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val rows = ceil(colors.size / columns.toFloat()).toInt()
        setMeasuredDimension(w, (rows * w.toFloat() / columns).toInt())
    }

    override fun onDraw(canvas: Canvas) {
        val c = cell
        val r = c * 0.34f
        colors.forEachIndexed { i, color ->
            val cx = (i % columns + 0.5f) * c
            val cy = (i / columns + 0.5f) * c
            fill.color = color
            canvas.drawCircle(cx, cy, r, fill)
            if (Color.luminance(color) > 0.85f) canvas.drawCircle(cx, cy, r, edge)
            if (color == selected) canvas.drawCircle(cx, cy, min(r + dp(4f), c / 2 - dp(1.5f)), ring)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) {
            val i = (e.y / cell).toInt() * columns + (e.x / cell).toInt().coerceIn(0, columns - 1)
            if (e.x in 0f..width.toFloat() && i in colors.indices) {
                performClick()
                onPick?.invoke(colors[i])
            }
        }
        return true
    }

    override fun performClick() = super.performClick()
}

/** 채도(가로) · 명도(세로) 사각형 */
class SatValView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var hue = 0f
        set(v) { field = v; shader = null; invalidate() }
    var sat = 1f
    var value = 1f
    var onChange: ((sat: Float, value: Float, done: Boolean) -> Unit)? = null

    private var shader: Shader? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbIn = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.WHITE }
    private val thumbOut = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.argb(120, 0, 0, 0) }
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { shader = null }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (shader == null) {
            val v = LinearGradient(0f, 0f, 0f, h, Color.WHITE, Color.BLACK, Shader.TileMode.CLAMP)
            val s = LinearGradient(0f, 0f, w, 0f, Color.WHITE, Color.HSVToColor(floatArrayOf(hue, 1f, 1f)), Shader.TileMode.CLAMP)
            shader = ComposeShader(v, s, PorterDuff.Mode.MULTIPLY)
        }
        paint.shader = shader
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, dp(10f), dp(10f), paint)
        val x = sat * w; val y = (1 - value) * h
        thumbFill.color = Color.HSVToColor(floatArrayOf(hue, sat, value))
        canvas.drawCircle(x, y, dp(11f), thumbFill)
        canvas.drawCircle(x, y, dp(11f), thumbIn)
        canvas.drawCircle(x, y, dp(12.5f), thumbOut)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        sat = (e.x / width).coerceIn(0f, 1f)
        value = 1f - (e.y / height).coerceIn(0f, 1f)
        invalidate()
        val done = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        onChange?.invoke(sat, value, done)
        return true
    }
}

/** 무지개 색상 막대 */
class HueBarView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var hue = 0f
        set(v) { field = v; invalidate() }
    var onChange: ((hue: Float, done: Boolean) -> Unit)? = null

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val thumbIn = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(3f); color = Color.WHITE }
    private val thumbOut = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(1f); color = Color.argb(120, 0, 0, 0) }
    private val rect = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val stops = IntArray(7) { Color.HSVToColor(floatArrayOf(it * 60f % 360f, 1f, 1f)) }
        stops[6] = stops[0]
        paint.shader = LinearGradient(0f, 0f, w.toFloat(), 0f, stops, null, Shader.TileMode.CLAMP)
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val barH = h * 0.6f
        rect.set(0f, (h - barH) / 2, width.toFloat(), (h + barH) / 2)
        canvas.drawRoundRect(rect, barH / 2, barH / 2, paint)
        val x = (hue / 360f * width).coerceIn(h / 2, width - h / 2)
        thumbFill.color = Color.HSVToColor(floatArrayOf(hue, 1f, 1f))
        canvas.drawCircle(x, h / 2, h / 2 - dp(2f), thumbFill)
        canvas.drawCircle(x, h / 2, h / 2 - dp(2f), thumbIn)
        canvas.drawCircle(x, h / 2, h / 2 - dp(0.5f), thumbOut)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        parent?.requestDisallowInterceptTouchEvent(true)
        hue = (e.x / width).coerceIn(0f, 1f) * 359.9f
        val done = e.actionMasked == MotionEvent.ACTION_UP || e.actionMasked == MotionEvent.ACTION_CANCEL
        onChange?.invoke(hue, done)
        return true
    }
}

/**
 * 도구막대 위에 뜨는 색 고르기 창.
 * onPick(color, done): 직접 선택에서 끄는 동안은 done=false, 손을 떼거나 견본을 누르면 done=true.
 */
class ColorPickerPopup(
    private val activity: Activity,
    initial: Int,
    recent: List<Int>,
    private val onPick: (color: Int, done: Boolean) -> Unit,
) {
    private val view: View = LayoutInflater.from(activity).inflate(R.layout.popup_color, null)
    private val preview: View = view.findViewById(R.id.preview)
    private val grid: SwatchGridView = view.findViewById(R.id.grid)
    private val customPanel: View = view.findViewById(R.id.customPanel)
    private val satVal: SatValView = view.findViewById(R.id.satVal)
    private val hueBar: HueBarView = view.findViewById(R.id.hueBar)
    private val hex: EditText = view.findViewById(R.id.hex)
    private val recentLabel: TextView = view.findViewById(R.id.recentLabel)
    private val recentGrid: SwatchGridView = view.findViewById(R.id.recent)
    private val popup = PopupWindow(view, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, true)

    private val hsv = FloatArray(3)
    private var anchor: View? = null

    init {
        popup.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        popup.isOutsideTouchable = true

        grid.colors = ColorPalette.colors
        grid.onPick = { pickDone(it) }
        recentGrid.colors = recent
        recentGrid.onPick = { pickDone(it) }
        recentLabel.isVisible = recent.isNotEmpty()
        recentGrid.isVisible = recent.isNotEmpty()

        satVal.onChange = { s, v, done ->
            hsv[1] = s; hsv[2] = v
            fromHsv(done)
        }
        hueBar.onChange = { h, done ->
            hsv[0] = h
            satVal.hue = h
            fromHsv(done)
        }
        hex.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_DONE) {
                parseHex()?.let { set(it); onPick(it, true) }
            }
            false
        }

        val modes: MaterialButtonToggleGroup = view.findViewById(R.id.modeGroup)
        modes.check(if (lastCustom) R.id.modeCustom else R.id.modePalette)
        showMode(lastCustom)
        modes.addOnButtonCheckedListener { _, id, checked ->
            if (checked) {
                lastCustom = id == R.id.modeCustom
                showMode(lastCustom)
                relayout()
            }
        }
        set(initial)
    }

    private fun showMode(custom: Boolean) {
        grid.isVisible = !custom
        customPanel.isVisible = custom
    }

    /** 화면 표시만 바꾼다 (onPick은 부르지 않음) */
    private fun set(c: Int) {
        Color.colorToHSV(c, hsv)
        satVal.hue = hsv[0]; satVal.sat = hsv[1]; satVal.value = hsv[2]; satVal.invalidate()
        hueBar.hue = hsv[0]
        grid.selected = c
        recentGrid.selected = c
        setHexText(c)
        preview.background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(c)
            setStroke(view.dp(1f).toInt(), Color.argb(70, 0, 0, 0))
        }
    }

    private fun fromHsv(done: Boolean) {
        val c = Color.HSVToColor(hsv)
        grid.selected = c
        setHexText(c)
        (preview.background as? GradientDrawable)?.setColor(c)
        onPick(c, done)
    }

    private fun pickDone(c: Int) {
        set(c)
        onPick(c, true)
        popup.dismiss()
    }

    private fun setHexText(c: Int) {
        if (!hex.hasFocus()) hex.setText(String.format("%06X", c and 0xFFFFFF))
    }

    private fun parseHex(): Int? {
        val s = hex.text.toString().trim().removePrefix("#")
        if (s.length != 6) return null
        return s.toIntOrNull(16)?.let { it or 0xFF000000.toInt() }
    }

    private var side = ToolbarSide.BOTTOM

    fun show(anchor: View, side: ToolbarSide = ToolbarSide.BOTTOM) {
        this.anchor = anchor
        this.side = side
        val (x, y, w) = place(anchor)
        popup.width = w
        popup.showAtLocation(anchor, Gravity.NO_GRAVITY, x, y)
    }

    private fun relayout() {
        val a = anchor ?: return
        if (!popup.isShowing) return
        val (x, y, w) = place(a)
        popup.update(x, y, w, -1)
    }

    private fun place(anchor: View) = placeNear(activity, view, anchor, 520f, side)

    companion object {
        /** 마지막으로 쓴 탭(팔레트/직접 선택)을 기억 */
        private var lastCustom = false
    }
}
