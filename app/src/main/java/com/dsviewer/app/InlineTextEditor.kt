package com.dsviewer.app

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.Layout
import android.text.Spanned
import android.text.TextWatcher
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import com.google.android.material.color.MaterialColors
import kotlin.math.abs
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
 *
 * 서식: 글자 서식은 고른 글자에(고른 게 없으면 이어서 칠 글자에), 문단 서식은 고른 문단에 붙는다.
 * 문단 서식은 [paras]가 원본이고 ParaSpan은 그리기용으로 바뀔 때마다 새로 붙인다.
 */
class InlineTextEditor(private val host: TextEditHost, private val docView: DocumentView) {
    /** 서식 줄에 보일 지금 서식 (고른 글자, 없으면 이어서 칠 글자 기준) */
    data class FormatState(val style: CharStyle, val para: ParaAttrs, val sizePt: Float)

    /** 글 상자가 열리고 닫힐 때 (서식 줄을 보이고 숨긴다) */
    var onEditingChanged: ((Boolean) -> Unit)? = null
    var onFormatChanged: ((FormatState) -> Unit)? = null

    private val density = host.resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    /** 체크 상자 누르기와 커서 움직임을 알려 주는 EditText */
    @SuppressLint("ViewConstructor")
    private inner class RichEditText(ctx: Context) : EditText(ctx) {
        private var swallow = false

        override fun onSelectionChanged(selStart: Int, selEnd: Int) {
            super.onSelectionChanged(selStart, selEnd)
            // 만들어지는 중(부모 생성자)에도 불리므로 바깥 객체가 준비됐는지 본다
            @Suppress("SENSELESS_COMPARISON")
            if (watcher != null) selectionMoved(selStart, selEnd)
        }

        /**
         * 상자는 늘 내용만큼 커지므로 스크롤할 일이 없다. 가운데·오른쪽 맞춤에 들여쓰기가 있으면
         * EditText가 커서를 보이려고 옆으로 잘못 밀어 버리므로 늘 제자리에 둔다
         */
        override fun scrollTo(x: Int, y: Int) = super.scrollTo(0, 0)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(ev: MotionEvent): Boolean {
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && toggleCheckAt(ev.x, ev.y)) {
                swallow = true
                return true
            }
            if (swallow) {
                if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) swallow = false
                return true
            }
            return super.onTouchEvent(ev)
        }
    }

    private val edit: EditText = RichEditText(host.context).apply {
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
        // 안드로이드 15부터 TextView는 한국어 글꼴 줄 높이를 최소로 써서 줄 간격이 벌어진다. 쪽에 그릴 때(StaticLayout)와 같게 끈다
        if (android.os.Build.VERSION.SDK_INT >= 35) isLocalePreferredLineHeightForMinimumUsed = false
        minHeight = 0
        minimumHeight = 0
        pivotX = 0f
        pivotY = 0f
        visibility = View.GONE
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

    /** 문단마다 서식 (글의 문단 수와 늘 같게 맞춘다) */
    private var paras: MutableList<ParaAttrs> = mutableListOf(ParaAttrs.DEFAULT)
    /** 이어서 칠 글자의 서식 (커서만 있을 때 서식 줄로 바꾼다) */
    private var typing = CharStyle()
    /** 코드가 글·서식을 고치는 중 (감시자·커서 알림을 무시) */
    private var internal = false
    /** 키보드가 글을 바꾸는 중 (커서 알림을 무시하고 바뀐 뒤에 한 번에 처리) */
    private var changing = false
    private var chStart = 0
    private var chCount = 0
    private var chPara = 0
    private var chRemovedLines = 0

    private val watcher: TextWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence, start: Int, count: Int, after: Int) {
            if (internal) return
            changing = true
            chStart = start
            chPara = Rich.paragraphIndex(s, start)
            chRemovedLines = (start until start + count).count { s[it] == '\n' }
        }

        override fun onTextChanged(s: CharSequence, start: Int, before: Int, count: Int) {
            if (!internal) chCount = count
        }

        override fun afterTextChanged(e: Editable) {
            if (internal) return
            internal = true
            try {
                textChanged(e)
            } finally {
                internal = false
                changing = false
            }
            notifyFormat()
        }
    }

    init {
        host.addView(edit, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        edit.addTextChangedListener(watcher)
    }

    /** 글 상자의 기본 글자 크기 (pt). 툴바 '가' 칸으로 바꾸면 상자 전체가 같은 비율로 커진다 */
    var size = 20f
        set(v) {
            field = v
            if (isEditing) {
                applyMetrics()
                notifyFormat()
            }
        }

    /** 글 상자의 기본 글자색 (글자색 서식이 없는 글자) */
    var color = Color.BLACK
        set(v) {
            field = v
            edit.setTextColor(v)
        }

    /**
     * 쪽 (x, y)에서 글 상자를 연다. [existing]이 있으면 그 글을 고친다 (누른 자리에 커서, 체크 상자면 체크).
     * 새 글은 첫 줄이 누른 높이에 오고, 줄은 쪽 오른쪽 끝에서 바뀐다
     */
    fun start(page: Int, x: Float, y: Float, existing: Stroke?, size: Float, color: Int) {
        commit()
        val ps = docView.pageSize(page) ?: return
        this.page = page
        this.existing = existing
        val t = existing?.text
        internal = true
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
            paras = t.rich.paras.toMutableList()
            appliedScale = docView.viewScale
            edit.setText(t.rich.toSpannable(em()))
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
            paras = mutableListOf(ParaAttrs.DEFAULT)
            edit.setText("")
        }
        typing = CharStyle()
        internal = false
        isEditing = true
        edit.visibility = View.VISIBLE
        applyMetrics()
        reposition()
        edit.requestFocus()
        if (existing != null) {
            // 누른 자리에 커서 (상자가 자리를 잡은 뒤). 체크 상자를 눌렀으면 체크만 바꾼다
            edit.post {
                val p = docView.pageToScreen(page, x, y) ?: return@post
                val r = Math.toRadians(-deg.toDouble())
                val dx = p.x - edit.translationX
                val dy = p.y - edit.translationY
                val lx = (dx * cos(r) - dy * sin(r)).toFloat()
                val ly = (dx * sin(r) + dy * cos(r)).toFloat()
                if (!toggleCheckAt(lx, ly)) edit.setSelection(edit.getOffsetForPosition(lx, ly).coerceIn(0, edit.text.length))
            }
        } else edit.setSelection(0)
        onEditingChanged?.invoke(true)
        notifyFormat()
        showKeyboard()
    }

    /** 친 글을 쪽에 넣고 상자를 닫는다. 비어 있으면 (고치던 글이면 그 글을 지우고) 아무것도 넣지 않는다 */
    fun commit() {
        if (!isEditing) return
        isEditing = false
        val rich = RichDoc.from(edit.text, paras)
        hideKeyboard()
        edit.clearFocus()
        edit.visibility = View.GONE
        docView.hiddenStroke = null
        onEditingChanged?.invoke(false)
        val ex = existing
        existing = null
        val t = if (rich.text.isBlank()) null else InkText(rich, size, wrap)
        if (ex != null) {
            val old = ex.text
            val same = t != null && old != null && old.text == t.text && old.size == t.size &&
                ex.color == color && old.rich.toJson() == t.rich.toJson()
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

    // ================= 서식 =================

    /** 굵게·기울임·밑줄·취소선 켜고 끄기 (고른 글자가 모두 그 서식이면 끄고, 아니면 켠다) */
    fun toggle(t: CharToggle) {
        if (!isEditing) return
        val (a, b) = selection()
        if (a == b) typing = Rich.with(typing, t, !Rich.has(typing, t))
        else {
            val on = !Rich.has(rangeStyle(a, b), t)
            val e = edit.text
            Rich.clear(e, a, b, Rich.codeOf(t))
            if (on) e.setSpan(Rich.toggleSpan(t), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        notifyFormat()
    }

    /** 글자색 (null이면 상자 기본색으로) */
    fun setTextColor(c: Int?) = setValue('c', c?.let { if (it == color) null else it }) { typing.copy(color = it) }

    /** 글자 배경색 (null이면 없앰) */
    fun setBgColor(c: Int?) = setValue('g', c) { typing.copy(bg = it) }

    /** 글자 크기 (pt). 상자 기본 크기에 대한 비율로 붙는다 */
    fun setSizePt(pt: Float) {
        if (!isEditing) return
        val ratio = pt / size
        val (a, b) = selection()
        if (a == b) typing = typing.copy(size = ratio)
        else {
            val e = edit.text
            Rich.clear(e, a, b, 'z')
            if (abs(ratio - 1f) > 0.001f) e.setSpan(RSize(ratio), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        notifyFormat()
    }

    private fun setValue(code: Char, v: Int?, typed: (Int?) -> CharStyle) {
        if (!isEditing) return
        val (a, b) = selection()
        if (a == b) typing = typed(v)
        else {
            val e = edit.text
            Rich.clear(e, a, b, code)
            if (v != null) e.setSpan(if (code == 'c') RColor(v) else RBg(v), a, b, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        notifyFormat()
    }

    /** 기본 서식(제목·소제목·본문·작은 글)을 고른 문단 전체에 */
    fun applyPreset(p: TextPreset) {
        if (!isEditing) return
        val e = edit.text
        val ranges = Rich.paragraphs(e)
        for (i in selectedParas()) {
            val r = ranges[i]
            Rich.clear(e, r[0], r[1], 'z')
            Rich.clear(e, r[0], r[1], 'b')
            if (r[1] > r[0]) {
                if (p.ratio != 1f) e.setSpan(RSize(p.ratio), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                if (p.bold) e.setSpan(RBold(), r[0], r[1], Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        typing = typing.copy(size = p.ratio, bold = p.bold)
        notifyFormat()
    }

    /** 목록(점·번호·체크). 고른 문단이 모두 그 목록이면 목록을 끈다 */
    fun setList(kind: ListKind) = editParas { idx ->
        val off = idx.all { paras[it].list == kind }
        for (i in idx) paras[i] = paras[i].copy(list = if (off) ListKind.NONE else kind, checked = false)
    }

    fun setAlign(a: TextAlign) = editParas { idx -> for (i in idx) paras[i] = paras[i].copy(align = a) }

    /** 들여쓰기(+1)·내어쓰기(-1) */
    fun indent(delta: Int) = editParas { idx ->
        for (i in idx) paras[i] = paras[i].copy(indent = (paras[i].indent + delta).coerceIn(0, ParaAttrs.MAX_INDENT))
    }

    private fun editParas(change: (List<Int>) -> Unit) {
        if (!isEditing) return
        change(selectedParas())
        writeParas()
        notifyFormat()
    }

    /** 고른 범위에 걸친 문단 번호들 (커서만 있으면 그 문단) */
    private fun selectedParas(): List<Int> {
        val (a, b) = selection()
        val t = edit.text
        val first = Rich.paragraphIndex(t, a)
        val last = Rich.paragraphIndex(t, if (b > a) b - 1 else a)
        return (first..last).filter { it in paras.indices }
    }

    private fun selection(): Pair<Int, Int> {
        val a = edit.selectionStart.coerceAtLeast(0)
        val b = edit.selectionEnd.coerceAtLeast(0)
        return minOf(a, b) to maxOf(a, b)
    }

    // ================= 글이 바뀔 때 =================

    /** 키보드로 글이 바뀐 뒤: 문단 서식 목록을 맞추고, 새로 친 글자에 지금 서식을 입힌다 */
    private fun textChanged(e: Editable) {
        val start = chStart
        val count = chCount
        val added = (start until (start + count).coerceAtMost(e.length)).count { e[it] == '\n' }
        // 문단 서식: 줄을 넣으면 그 문단 서식을 이어받고(체크는 풀고), 줄을 지우면 앞 문단 서식으로 합친다
        val pi = chPara.coerceIn(0, paras.lastIndex)
        val base = paras[pi]
        val next = ArrayList<ParaAttrs>(paras.subList(0, pi + 1))
        repeat(added) { next.add(base.copy(checked = false)) }
        next.addAll(paras.subList((pi + 1 + chRemovedLines).coerceAtMost(paras.size), paras.size))
        paras = next
        if (count > 0) Rich.apply(e, start, start + count, typing)
        // 빈 목록 문단에서 Enter: 줄을 넣지 않고 목록을 끝낸다
        if (count == 1 && added == 1 && e[start] == '\n' && base.list != ListKind.NONE) {
            val lineStart = lastNewline(e, start - 1) + 1
            val nextEnd = nextNewline(e, start + 1)
            val empty = (lineStart until start).all { e[it] == ZWSP } && (start + 1 until nextEnd).all { e[it] == ZWSP }
            if (empty) {
                e.delete(start, start + 1)
                paras.removeAt(pi + 1)
                paras[pi] = base.copy(list = ListKind.NONE, checked = false)
            }
        }
        // 지웠으면 커서 앞 글자의 서식을 이어서 쓴다
        if (count == 0) typing = typingAt(edit.selectionStart)
        writeParas()
    }

    private fun lastNewline(t: CharSequence, from: Int): Int {
        var i = from
        while (i >= 0 && t[i] != '\n') i--
        return i
    }

    private fun nextNewline(t: CharSequence, from: Int): Int {
        var i = from
        while (i < t.length && t[i] != '\n') i++
        return i
    }

    /** 문단 서식을 그리기용 span으로 다시 붙인다 (마지막 빈 목록 문단엔 [ZWSP]) */
    private fun writeParas() {
        val wasInternal = internal
        internal = true
        val e = edit.text
        val caret = edit.selectionStart
        if (Rich.ensureTail(e, paras) && caret == e.length - 1) edit.setSelection(caret)
        Rich.writeParas(e, paras, em())
        internal = wasInternal
        // 들여쓰기·목록 폭이 바뀌면 상자 폭도 다시 잰다
        edit.requestLayout()
    }

    private fun selectionMoved(a: Int, b: Int) {
        if (internal || changing || !isEditing) return
        val t = edit.text
        // 커서는 끝에 붙인 보이지 않는 글자 앞에 둔다 (그 뒤에서 지우면 다시 붙어 지워지지 않는 것처럼 보이므로)
        if (a == b && a == t.length && a > 0 && t[a - 1] == ZWSP) {
            edit.setSelection(a - 1)
            return
        }
        if (a == b) typing = typingAt(a)
        notifyFormat()
    }

    /** 커서 자리에서 이어서 칠 서식: 앞 글자, 없으면 뒤 글자, 둘 다 없으면 그대로 */
    private fun typingAt(p: Int): CharStyle {
        val t = edit.text
        fun ok(i: Int) = i in 0 until t.length && t[i] != '\n' && t[i] != ZWSP
        return when {
            ok(p - 1) -> Rich.styleAt(t, p - 1)
            ok(p) -> Rich.styleAt(t, p)
            else -> typing
        }
    }

    /** 고른 범위의 서식: 켜고 끄는 서식은 모든 글자가 그럴 때만 켜짐, 색·크기는 첫 글자 */
    private fun rangeStyle(a: Int, b: Int): CharStyle {
        val t = edit.text
        val chars = (a until b).filter { t[it] != '\n' && t[it] != ZWSP }
        if (chars.isEmpty()) return typing
        val styles = chars.map { Rich.styleAt(t, it) }
        val f = styles.first()
        return CharStyle(
            bold = styles.all { it.bold }, italic = styles.all { it.italic },
            underline = styles.all { it.underline }, strike = styles.all { it.strike },
            color = f.color, bg = f.bg, size = f.size,
        )
    }

    private fun notifyFormat() {
        if (!isEditing) return
        val (a, b) = selection()
        val st = if (a == b) typing else rangeStyle(a, b)
        val para = paras.getOrElse(Rich.paragraphIndex(edit.text, a)) { ParaAttrs.DEFAULT }
        onFormatChanged?.invoke(FormatState(st, para, size * st.size))
    }

    /** 글 상자 안 (lx, ly)가 체크 목록의 체크 상자면 체크를 바꾸고 true */
    private fun toggleCheckAt(x: Float, y: Float): Boolean {
        if (!isEditing) return false
        val layout = edit.layout ?: return false
        val lx = x - edit.totalPaddingLeft + edit.scrollX
        val ly = y - edit.totalPaddingTop + edit.scrollY
        if (ly < 0 || ly > layout.height) return false
        val line = layout.getLineForVertical(ly.toInt())
        val ls = layout.getLineStart(line)
        val t = edit.text
        if (ls > 0 && t[ls - 1] != '\n') return false  // 문단 첫 줄만
        val i = Rich.paragraphIndex(t, ls)
        val a = paras.getOrNull(i) ?: return false
        if (a.list != ListKind.CHECK) return false
        val left = a.indent * ParaSpan.INDENT_EM * em()
        if (lx < left - em() * 0.3f || lx > left + ParaSpan.MARKER_EM * em()) return false
        paras[i] = a.copy(checked = !a.checked)
        writeParas()
        notifyFormat()
        return true
    }

    /** 기본 글자 크기의 화면 px */
    private fun em() = size * docView.viewScale * k

    /** 글자 크기·여백·줄 바꾸는 폭을 지금 화면 배율에 맞춘다 */
    private fun applyMetrics() {
        val sc = docView.viewScale
        appliedScale = sc
        val s = sc * k
        edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, size * s)
        // 테마의 글자 모양이 준 줄 높이(줄 간격 더하기)를 없애 쪽에 그릴 때(StaticLayout 기본)와 같게
        edit.setLineSpacing(0f, 1f)
        val pad = (InkText.PAD * s).roundToInt()
        edit.setPadding(pad, pad, pad, pad)
        edit.maxWidth = if (wrap >= InkText.NO_WRAP) Int.MAX_VALUE else ((wrap + InkText.PAD * 2) * s).roundToInt()
        // 빈 상자도 글자 하나 들어갈 만큼은 보이게 (쪽에 넣은 글 상자도 폭이 적어도 글자 하나)
        edit.minWidth = (size * s + pad * 2).roundToInt()
        // 목록 표시·들여쓰기 폭도 새 배율로
        if (isEditing) writeParas()
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
