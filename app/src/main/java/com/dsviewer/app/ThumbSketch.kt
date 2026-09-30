package com.dsviewer.app

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import kotlin.math.hypot
import kotlin.math.min

/**
 * 썸네일을 펜·손가락으로 그리는 창. [background]가 있으면 '지금 썸네일' 위에 그릴 수 있다.
 * 완료하면 흰 바탕(또는 썸네일)과 그림을 합친 그림을 [onDone]으로 넘긴다.
 */
class ThumbSketch(
    private val activity: AppCompatActivity,
    private val background: Bitmap?,
    private val onDone: (Bitmap) -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private fun px(v: Float) = (v * density).toInt()

    fun show() {
        val dialog = Dialog(activity, R.style.Theme_DSViewer)
        val surface = MaterialColors.getColor(activity.window.decorView, com.google.android.material.R.attr.colorSurfaceContainer)
        val sketch = SketchView(activity)
        sketch.background0 = background

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(surface)
            fitsSystemWindows = true
        }
        val bar = MaterialToolbar(activity).apply {
            title = "썸네일 그리기"
            setNavigationIcon(R.drawable.ic_close)
            navigationContentDescription = "취소"
            setNavigationOnClickListener { dialog.dismiss() }
            menu.add("완료").setShowAsAction(android.view.MenuItem.SHOW_AS_ACTION_ALWAYS)
            setOnMenuItemClickListener {
                onDone(sketch.result())
                dialog.dismiss()
                true
            }
        }
        root.addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val holder = FrameLayout(activity).apply { setPadding(px(16f), px(8f), px(16f), px(8f)) }
        holder.addView(sketch, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        root.addView(holder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        // ---- 도구 두 줄: (바탕 · 색) / (굵기 · 지우개 · 되돌리기 · 모두 지우기). 좁으면 옆으로 밀어 본다 ----
        fun toolRow(bottom: Float) = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(px(12f), px(4f), px(12f), px(bottom))
        }.also { row ->
            val scroll = HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(row)
            }
            root.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            })
        }
        val tools = toolRow(2f)
        val tools2 = toolRow(10f)

        if (background != null) {
            val group = MaterialButtonToggleGroup(activity).apply {
                isSingleSelection = true
                isSelectionRequired = true
            }
            val plain = outlined("흰 종이").apply { id = View.generateViewId() }
            val thumb = outlined("지금 썸네일 위에").apply { id = View.generateViewId() }
            group.addView(plain)
            group.addView(thumb)
            group.check(thumb.id)
            group.addOnButtonCheckedListener { _, id, checked ->
                if (checked) sketch.useBackground = id == thumb.id
            }
            tools.addView(group)
            tools.addView(gap())
        }

        val colors = listOf(Color.BLACK, Color.parseColor("#D93025"), Color.parseColor("#1E5AA8"), Color.parseColor("#188038"),
            Color.parseColor("#F29900"), Color.parseColor("#9334E6"), Color.parseColor("#FF6D9E"), Color.WHITE)
        val colorViews = ArrayList<View>()
        val outline = MaterialColors.getColor(root, com.google.android.material.R.attr.colorOutline)
        val accent = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary)
        val eraser = ImageButton(activity)
        fun markColors() = colorViews.forEachIndexed { i, v ->
            val on = !sketch.erasing && colors[i] == sketch.color
            (v.background as GradientDrawable).setStroke(px(if (on) 3f else 1f), if (on) accent else outline)
        }
        fun markEraser() {
            eraser.background = GradientDrawable().apply {
                cornerRadius = px(8f).toFloat()
                setColor(if (sketch.erasing) (accent and 0x00FFFFFF) or 0x33000000 else Color.TRANSPARENT)
            }
        }
        for (c in colors) {
            val v = View(activity).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                }
                contentDescription = "색"
                setOnClickListener {
                    sketch.color = c
                    sketch.erasing = false
                    markColors(); markEraser()
                }
            }
            colorViews += v
            tools.addView(v, LinearLayout.LayoutParams(px(30f), px(30f)).apply { marginStart = px(4f); marginEnd = px(4f) })
        }
        val widthGroup = MaterialButtonToggleGroup(activity).apply {
            isSingleSelection = true
            isSelectionRequired = true
        }
        val widths = listOf("가늘게" to 4f, "보통" to 8f, "굵게" to 16f)
        for ((label, w) in widths) {
            val b = outlined(label).apply { id = View.generateViewId() }
            widthGroup.addView(b)
            if (w == sketch.width) widthGroup.check(b.id)
            b.setOnClickListener { sketch.width = w }
        }
        tools2.addView(widthGroup)
        tools2.addView(gap())
        eraser.apply {
            setImageResource(R.drawable.ic_eraser_stroke)
            contentDescription = "지우개"
            setOnClickListener {
                sketch.erasing = !sketch.erasing
                markColors(); markEraser()
            }
        }
        tools2.addView(eraser, LinearLayout.LayoutParams(px(44f), px(44f)))
        tools2.addView(ImageButton(activity).apply {
            setImageResource(R.drawable.ic_undo)
            contentDescription = "되돌리기"
            setBackgroundResource(android.R.color.transparent)
            setOnClickListener { sketch.undo() }
        }, LinearLayout.LayoutParams(px(44f), px(44f)))
        tools2.addView(MaterialButton(activity, null, androidx.appcompat.R.attr.borderlessButtonStyle).apply {
            text = "모두 지우기"
            setOnClickListener { sketch.clear() }
        })
        markColors(); markEraser()

        dialog.setContentView(root)
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        dialog.show()
    }

    private fun outlined(text: String) =
        MaterialButton(activity, null, com.google.android.material.R.attr.materialButtonOutlinedStyle).apply { this.text = text }

    private fun gap() = View(activity).apply { layoutParams = LinearLayout.LayoutParams(px(16f), 1) }
}

/** 썸네일 그리기 판: 카드 비율(가로 4 : 세로 5)의 흰 종이. 펜을 한 번 쓰면 손가락 터치는 무시한다 */
private class SketchView(ctx: Context) : View(ctx) {
    var color = Color.BLACK
    var width = 8f
    var erasing = false
    var background0: Bitmap? = null
    var useBackground = true
        set(v) { field = v; invalidate() }

    /** 한 획: 점마다 (x, y, 필압). [drawn]은 판에 이미 그린 조각 수 */
    private class Line(val color: Int, val erase: Boolean, val width: Float, val pts: ArrayList<Float> = ArrayList()) {
        var drawn = 0
        val count get() = pts.size / 3
        fun x(i: Int) = pts[i * 3]
        fun y(i: Int) = pts[i * 3 + 1]
        fun p(i: Int) = pts[i * 3 + 2]
    }

    private val lines = ArrayList<Line>()
    private var cur: Line? = null
    private var penSeen = false
    private val ink = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
    private val inkCanvas = Canvas(ink)
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = Color.argb(40, 0, 0, 0) }
    private val board = RectF()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val aw = w - paddingLeft - paddingRight
        val ah = h - paddingTop - paddingBottom
        val k = min(aw.toFloat() / W, ah.toFloat() / H)
        val bw = W * k; val bh = H * k
        board.set(paddingLeft + (aw - bw) / 2, paddingTop + (ah - bh) / 2, paddingLeft + (aw + bw) / 2, paddingTop + (ah + bh) / 2)
    }

    override fun onDraw(c: Canvas) {
        val d = resources.displayMetrics.density
        c.drawRect(board.left + d, board.top + 3 * d, board.right + d, board.bottom + 3 * d, shadow)
        c.save()
        c.translate(board.left, board.top)
        c.scale(board.width() / W, board.height() / H)
        drawPaper(c)
        c.drawBitmap(ink, 0f, 0f, bmpPaint)
        c.restore()
    }

    private fun drawPaper(c: Canvas) {
        c.drawColor(Color.WHITE)
        val bg = background0
        if (bg != null && useBackground) {
            val k = min(W.toFloat() / bg.width, H.toFloat() / bg.height)
            val bw = bg.width * k; val bh = bg.height * k
            c.drawBitmap(bg, null, RectF((W - bw) / 2, (H - bh) / 2, (W + bw) / 2, (H + bh) / 2), bmpPaint)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        val pen = e.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS || e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
        if (pen) penSeen = true else if (penSeen) return true
        val k = W / board.width()
        /** 판 좌표로 바꿔 더한다. 바로 앞 점과 거의 같은 자리면 건너뛰고, 필압은 부드럽게 */
        fun add(x: Float, y: Float, pressure: Float, force: Boolean = false) {
            val l = cur ?: return
            val bx = (x - board.left) * k
            val by = (y - board.top) * k
            val n = l.count
            if (n > 0 && !force && hypot(bx - l.x(n - 1), by - l.y(n - 1)) < 1.5f) return
            val raw = if (pen) pressure.coerceIn(0.05f, 1f) else 0.6f
            val pr = if (n > 0) l.p(n - 1) * 0.6f + raw * 0.4f else raw
            l.pts.add(bx); l.pts.add(by); l.pts.add(pr)
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val erase = erasing || e.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER
                cur = Line(color, erase, strokeWidth).also { add(e.x, e.y, e.pressure); drawNew(it) }
            }
            MotionEvent.ACTION_MOVE -> {
                val l = cur ?: return true
                // 빠르게 그으면 한 번에 여러 점이 온다: 모두 이어 그린다
                for (h in 0 until e.historySize) add(e.getHistoricalX(h), e.getHistoricalY(h), e.getHistoricalPressure(h))
                add(e.x, e.y, e.pressure)
                drawNew(l)
            }
            MotionEvent.ACTION_UP -> {
                cur?.let { l ->
                    add(e.x, e.y, e.pressure)
                    drawNew(l)
                    drawTail(l)
                    lines += l
                }
                cur = null
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> {
                cur = null
                redraw()
            }
        }
        invalidate()
        return true
    }

    override fun performClick() = super.performClick()

    private val strokeWidth get() = width * (W / 300f)
    private val piece = Path()

    private fun setup(l: Line) {
        paint.color = l.color
        paint.xfermode = if (l.erase) PorterDuffXfermode(PorterDuff.Mode.CLEAR) else null
    }

    private fun widthAt(l: Line, i: Int) = if (l.erase) l.width * 3f else l.width * (0.35f + l.p(i))

    /**
     * [i]번째 조각: 앞 두 점의 가운데에서 다음 가운데까지 곡선 (점 i-1을 조절점으로).
     * 첫 조각은 첫 점에서 시작한다. 조각끼리 이어져 매끈한 선이 된다
     */
    private fun drawPiece(l: Line, i: Int) {
        val sx = if (i == 1) l.x(0) else (l.x(i - 2) + l.x(i - 1)) / 2
        val sy = if (i == 1) l.y(0) else (l.y(i - 2) + l.y(i - 1)) / 2
        val ex = (l.x(i - 1) + l.x(i)) / 2
        val ey = (l.y(i - 1) + l.y(i)) / 2
        piece.reset()
        piece.moveTo(sx, sy)
        piece.quadTo(l.x(i - 1), l.y(i - 1), ex, ey)
        paint.strokeWidth = (widthAt(l, i - 1) + widthAt(l, i)) / 2
        inkCanvas.drawPath(piece, paint)
    }

    /** 아직 안 그린 조각들 (점 하나뿐이면 점) */
    private fun drawNew(l: Line) {
        setup(l)
        if (l.count == 1 && l.drawn == 0) {
            paint.strokeWidth = widthAt(l, 0)
            inkCanvas.drawPoint(l.x(0), l.y(0), paint)
        }
        for (i in maxOf(1, l.drawn + 1) until l.count) drawPiece(l, i)
        l.drawn = maxOf(l.drawn, l.count - 1)
    }

    /** 끝: 마지막 가운데 점에서 마지막 점까지 */
    private fun drawTail(l: Line) {
        val n = l.count
        if (n < 2) return
        setup(l)
        paint.strokeWidth = widthAt(l, n - 1)
        inkCanvas.drawLine((l.x(n - 2) + l.x(n - 1)) / 2, (l.y(n - 2) + l.y(n - 1)) / 2, l.x(n - 1), l.y(n - 1), paint)
    }

    private fun redraw() {
        ink.eraseColor(Color.TRANSPARENT)
        for (l in lines) {
            l.drawn = 0
            drawNew(l)
            drawTail(l)
        }
        invalidate()
    }

    fun undo() {
        if (lines.isEmpty()) return
        lines.removeAt(lines.size - 1)
        redraw()
    }

    fun clear() {
        lines.clear()
        redraw()
    }

    fun result(): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        drawPaper(c)
        c.drawBitmap(ink, 0f, 0f, bmpPaint)
        return out
    }

    companion object {
        const val W = 1200
        const val H = 1500
    }
}
