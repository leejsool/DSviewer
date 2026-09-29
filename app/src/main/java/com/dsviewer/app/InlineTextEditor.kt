package com.dsviewer.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.Layout
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.color.MaterialColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** 글 상자를 담는 판. 글 상자가 화면 폭에 눌려 줄이 먼저 바뀌지 않도록 폭 제한 없이 잰다 (줄바꿈은 maxWidth로) */
class TextEditHost @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : FrameLayout(ctx, attrs) {
    override fun measureChildWithMargins(child: View, wSpec: Int, wUsed: Int, hSpec: Int, hUsed: Int) {
        val free = MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        child.measure(free, free)
    }
}

/**
 * 문서 위에서 바로 치는 글 상자. 글 도구로 쪽을 누르면 그 자리에 점선 상자가 생기고 커서가 깜빡인다.
 * 상자는 쪽에 그려질 글과 같은 자리·크기·기울기로 겹쳐 보이며, 스크롤·확대를 따라간다.
 * 상자 밖을 누르거나 다른 도구를 고르면 [commit]으로 쪽에 넣는다 (실행 취소 한 단계).
 * 줄은 상자를 만든 자리에서 쪽 오른쪽 끝까지 가면 바뀐다 ([InkText.wrap]).
 */
class InlineTextEditor(private val host: TextEditHost, private val docView: DocumentView) {
    private val density = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val edit = EditText(host.context).apply {
        background = GradientDrawable().apply {
            setColor(Color.TRANSPARENT)
            val primary = MaterialColors.getColor(host, androidx.appcompat.R.attr.colorPrimary)
            setStroke(dp(1.5f).roundToInt(), primary, dp(5f), dp(3f))
            cornerRadius = dp(3f)
        }
        // 쪽에 그릴 때(InkText의 StaticLayout)와 같은 배치
        includeFontPadding = false
        isFallbackLineSpacing = true
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        typeface = Typeface.DEFAULT
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        // 가로 화면에서 키보드가 전체 화면 입력창으로 바뀌지 않게
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        gravity = Gravity.TOP or Gravity.START
        setHorizontallyScrolling(false)
        minHeight = 0
        minimumHeight = 0
        pivotX = 0f
        pivotY = 0f
        visibility = View.GONE
    }

    init {
        host.addView(edit, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
    }

    var isEditing = false
        private set

    private var page = -1
    /** 상자 왼쪽 위 모서리 (쪽 좌표) */
    private var x0 = 0f
    private var y0 = 0f
    /** 고치는 글이 선택 도구로 키워진 배율과 돌아간 각도 */
    private var k = 1f
    private var deg = 0f
    private var wrap = InkText.NO_WRAP
    private var existing: Stroke? = null
    private var appliedScale = -1f

    /** 글자 크기 (pt). 치는 중에 바꾸면 바로 반영 */
    var size = 20f
        set(v) {
            field = v
            if (isEditing) applyMetrics()
        }

    var color = Color.BLACK
        set(v) {
            field = v
            edit.setTextColor(v)
        }

    /**
     * 쪽 (x, y)에서 글 상자를 연다. [existing]이 있으면 그 글을 고친다 (누른 자리에 커서).
     * 새 글은 첫 줄이 누른 높이에 오고, 줄은 쪽 오른쪽 끝에서 바뀐다
     */
    fun start(page: Int, x: Float, y: Float, existing: Stroke?, size: Float, color: Int) {
        commit()
        val ps = docView.pageSize(page) ?: return
        this.page = page
        this.existing = existing
        val t = existing?.text
        if (existing != null && t != null) {
            x0 = existing.x(0)
            y0 = existing.y(0)
            val ax = existing.x(1) - x0
            val ay = existing.y(1) - y0
            k = max(hypot(ax, ay) / t.boxW, 0.01f)
            deg = Math.toDegrees(atan2(ay, ax).toDouble()).toFloat()
            wrap = t.wrap
            this.size = t.size
            this.color = existing.color
            edit.setText(t.text)
            docView.hiddenStroke = existing
        } else {
            val pad = InkText.PAD
            x0 = (x - pad).coerceIn(0f, max(0f, ps.width - MIN_WRAP - pad * 2))
            y0 = (y - size * 0.6f - pad).coerceIn(0f, max(0f, ps.height - size * 1.5f))
            k = 1f
            deg = 0f
            wrap = max(MIN_WRAP, ps.width - x0 - pad * 2 - EDGE)
            this.size = size
            this.color = color
            edit.setText("")
        }
        isEditing = true
        edit.visibility = View.VISIBLE
        applyMetrics()
        reposition()
        edit.requestFocus()
        if (existing != null) {
            // 누른 자리에 커서 (상자가 자리를 잡은 뒤)
            edit.post {
                val p = docView.pageToScreen(page, x, y) ?: return@post
                val r = Math.toRadians(-deg.toDouble())
                val dx = p.x - edit.translationX
                val dy = p.y - edit.translationY
                val lx = (dx * cos(r) - dy * sin(r)).toFloat()
                val ly = (dx * sin(r) + dy * cos(r)).toFloat()
                edit.setSelection(edit.getOffsetForPosition(lx, ly).coerceIn(0, edit.text.length))
            }
        } else edit.setSelection(0)
        showKeyboard()
    }

    /** 친 글을 쪽에 넣고 상자를 닫는다. 비어 있으면 (고치던 글이면 그 글을 지우고) 아무것도 넣지 않는다 */
    fun commit() {
        if (!isEditing) return
        isEditing = false
        val txt = edit.text.toString().trimEnd()
        hideKeyboard()
        edit.clearFocus()
        edit.visibility = View.GONE
        docView.hiddenStroke = null
        val ex = existing
        existing = null
        val t = if (txt.isBlank()) null else InkText(txt, size, wrap)
        if (ex != null) {
            val old = ex.text
            val same = t != null && old != null && old.text == t.text && old.size == t.size && ex.color == color
            if (!same) docView.replaceText(page, ex, t, color)
        } else if (t != null) docView.addText(page, x0, y0, t, color)
    }

    /** 문서가 스크롤·확대되면 상자를 따라 옮긴다 */
    fun reposition() {
        if (!isEditing) return
        if (docView.viewScale != appliedScale) applyMetrics()
        val p = docView.pageToScreen(page, x0, y0) ?: return
        if (edit.translationX != p.x) edit.translationX = p.x
        if (edit.translationY != p.y) edit.translationY = p.y
        if (edit.rotation != deg) edit.rotation = deg
    }

    /** 글자 크기·여백·줄 바꾸는 폭을 지금 화면 배율에 맞춘다 */
    private fun applyMetrics() {
        val sc = docView.viewScale
        appliedScale = sc
        val s = sc * k
        edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s)
        val pad = (InkText.PAD * s).roundToInt()
        edit.setPadding(pad, pad, pad, pad)
        edit.maxWidth = if (wrap >= InkText.NO_WRAP) Int.MAX_VALUE else ((wrap + InkText.PAD * 2) * s).roundToInt()
        // 빈 상자도 글자 하나 들어갈 만큼은 보이게
        edit.minWidth = (size * s + pad * 2).roundToInt()
    }

    private fun showKeyboard() {
        val imm = host.context.getSystemService(InputMethodManager::class.java)
        edit.post { imm.showSoftInput(edit, 0) }
    }

    private fun hideKeyboard() {
        val imm = host.context.getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(edit.windowToken, 0)
    }

    private companion object {
        /** 줄 바꾸는 폭의 최소값 (쪽 오른쪽 끝 가까이 눌러도 이만큼은) (pt) */
        const val MIN_WRAP = 60f
        /** 쪽 오른쪽 끝에서 띄우는 여백 (pt) */
        const val EDGE = 12f
    }
}
