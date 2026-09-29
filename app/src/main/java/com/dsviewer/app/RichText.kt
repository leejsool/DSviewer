package com.dsviewer.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.text.Layout
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.AlignmentSpan
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.text.style.LeadingMarginSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.text.style.UpdateLayout
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/*
 * 글 상자의 서식.
 *  - 글자 서식(굵게·기울임·밑줄·취소선·글자색·배경색·크기)은 글자 범위에 붙는 span
 *  - 문단 서식(정렬·들여쓰기·목록·체크)은 문단마다 하나씩 (ParaAttrs, 그리기는 ParaSpan)
 * 글자 크기는 글 상자의 기본 크기에 대한 비율이라 확대·축소해도 그대로 맞는다.
 */

/** 서식이 붙은 마지막 빈 문단도 표시(글머리표 등)가 보이도록 끝에 붙이는 보이지 않는 글자 */
const val ZWSP = '​'

private const val EE = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE

// 이 앱이 붙인 서식만 알아보도록 따로 만든 span (키보드가 조합 중인 글자에 붙이는 밑줄 등과 섞이지 않게)
class RBold : StyleSpan(Typeface.BOLD)
class RItalic : StyleSpan(Typeface.ITALIC)
class RUnderline : UnderlineSpan()
class RStrike : StrikethroughSpan()
class RColor(color: Int) : ForegroundColorSpan(color)
class RBg(color: Int) : BackgroundColorSpan(color)
class RSize(val ratio: Float) : RelativeSizeSpan(ratio)

/** 글자 하나의 서식. color·bg가 null이면 기본(글 상자 색·배경 없음), size는 기본 크기에 대한 비율 */
data class CharStyle(
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val strike: Boolean = false,
    val color: Int? = null,
    val bg: Int? = null,
    val size: Float = 1f,
)

enum class CharToggle { BOLD, ITALIC, UNDERLINE, STRIKE }

enum class ListKind { NONE, BULLET, NUMBER, CHECK }

enum class TextAlign(val layout: Layout.Alignment) {
    LEFT(Layout.Alignment.ALIGN_NORMAL), CENTER(Layout.Alignment.ALIGN_CENTER), RIGHT(Layout.Alignment.ALIGN_OPPOSITE)
}

/** 문단 서식 */
data class ParaAttrs(
    val align: TextAlign = TextAlign.LEFT,
    val indent: Int = 0,
    val list: ListKind = ListKind.NONE,
    val checked: Boolean = false,
) {
    val isDefault get() = this == DEFAULT

    companion object {
        val DEFAULT = ParaAttrs()
        const val MAX_INDENT = 6
    }
}

/** 기본 서식 (문단에 적용): 글자 크기 비율과 굵기 */
enum class TextPreset(val label: String, val ratio: Float, val bold: Boolean) {
    TITLE("제목", 1.8f, true),
    HEADING("소제목", 1.35f, true),
    BODY("본문", 1f, false),
    SMALL("작은 글", 0.8f, false),
}

/**
 * 문단 서식 그리기: 들여쓰기·목록 표시만큼 왼쪽을 비우고, 첫 줄 앞에 글머리표·번호·체크 상자를 그린다.
 * [em]은 기본 글자 크기 (화면이면 px, 쪽이면 pt).
 * UpdateLayout: 붙이거나 뗄 때 글 상자(EditText)가 줄 배치를 다시 하도록
 */
class ParaSpan(val attrs: ParaAttrs, private val em: Float) : LeadingMarginSpan, AlignmentSpan, UpdateLayout {
    /** 번호 목록의 번호 (같은 들여쓰기에서 이어지는 번호 문단끼리 센다) */
    var number = 1

    override fun getAlignment(): Layout.Alignment = attrs.align.layout

    override fun getLeadingMargin(first: Boolean): Int = (indentWidth() + markerWidth()).roundToInt()

    private fun indentWidth() = attrs.indent * INDENT_EM * em
    private fun markerWidth() = if (attrs.list == ListKind.NONE) 0f else MARKER_EM * em

    override fun drawLeadingMargin(
        c: Canvas, p: Paint, x: Int, dir: Int, top: Int, baseline: Int, bottom: Int,
        text: CharSequence?, start: Int, end: Int, first: Boolean, layout: Layout?,
    ) {
        if (!first || attrs.list == ListKind.NONE) return
        val left = x + dir * indentWidth()
        val by = baseline.toFloat()
        val oldStyle = p.style
        val oldWidth = p.strokeWidth
        val oldAlign = p.textAlign
        val oldSize = p.textSize
        p.textAlign = Paint.Align.LEFT
        p.textSize = em
        when (attrs.list) {
            ListKind.BULLET -> c.drawText(BULLETS[attrs.indent % BULLETS.size], left + em * 0.15f, by, p)
            ListKind.NUMBER -> c.drawText(numberLabel(), left, by, p)
            ListKind.CHECK -> {
                val s = em * 0.78f
                val bx = left + em * 0.08f
                val bt = by - s * 0.9f
                p.style = Paint.Style.STROKE
                p.strokeWidth = em * 0.07f
                c.drawRoundRect(bx, bt, bx + s, bt + s, s * 0.18f, s * 0.18f, p)
                if (attrs.checked) {
                    p.strokeWidth = em * 0.1f
                    val cap = p.strokeCap
                    val join = p.strokeJoin
                    p.strokeCap = Paint.Cap.ROUND
                    p.strokeJoin = Paint.Join.ROUND
                    val path = Path().apply {
                        moveTo(bx + s * 0.2f, bt + s * 0.52f)
                        lineTo(bx + s * 0.42f, bt + s * 0.74f)
                        lineTo(bx + s * 0.8f, bt + s * 0.28f)
                    }
                    c.drawPath(path, p)
                    p.strokeCap = cap
                    p.strokeJoin = join
                }
            }
            ListKind.NONE -> {}
        }
        p.style = oldStyle
        p.strokeWidth = oldWidth
        p.textAlign = oldAlign
        p.textSize = oldSize
    }

    /** 들여쓰기 단계마다 1. / 가. / 1) … */
    private fun numberLabel(): String = when (attrs.indent % 3) {
        0 -> "$number."
        1 -> "${HANGUL_ORDER[(number - 1) % HANGUL_ORDER.length]}."
        else -> "$number)"
    }

    /** 이 문단의 체크 상자 자리인지 (문단 첫 줄 기준 가로 위치 lx, 줄 왼쪽 x) */
    fun hitsMarker(lx: Float): Boolean {
        if (attrs.list != ListKind.CHECK) return false
        val left = indentWidth()
        return lx >= left - em * 0.3f && lx <= left + markerWidth()
    }

    companion object {
        const val INDENT_EM = 1.5f
        const val MARKER_EM = 1.6f
        private val BULLETS = arrayOf("•", "◦", "▪")
        private const val HANGUL_ORDER = "가나다라마바사아자차카타파하"
    }
}

/**
 * 서식 있는 글 한 덩어리 (저장·그리기용 원본). [runs]는 글자 서식, [paras]는 문단마다 서식.
 */
class RichDoc(val text: String, val runs: List<Run>, paras: List<ParaAttrs>) {
    /** 글자 서식 한 조각: [start, end) 에 [code] (b 굵게, i 기울임, u 밑줄, s 취소선, c 글자색, g 배경색, z 크기 비율) */
    data class Run(val start: Int, val end: Int, val code: Char, val value: Double = 0.0)

    /** 문단 수에 맞춘 문단 서식 (모자라면 기본, 남으면 버림) */
    val paras: List<ParaAttrs> = List(Rich.paragraphs(text).size) { paras.getOrNull(it) ?: ParaAttrs.DEFAULT }

    val isPlain get() = runs.isEmpty() && paras.all { it.isDefault }

    /** 글자 서식·문단 서식을 붙인 편집 가능한 글. 서식 있는 마지막 빈 문단에는 [ZWSP]를 붙인다 */
    fun toSpannable(em: Float): SpannableStringBuilder {
        val sb = SpannableStringBuilder(text)
        for (r in runs) {
            val span: Any = when (r.code) {
                'b' -> RBold()
                'i' -> RItalic()
                'u' -> RUnderline()
                's' -> RStrike()
                'c' -> RColor(r.value.toInt())
                'g' -> RBg(r.value.toInt())
                'z' -> RSize(r.value.toFloat())
                else -> continue
            }
            val s = r.start.coerceIn(0, text.length)
            val e = r.end.coerceIn(s, text.length)
            if (e > s) sb.setSpan(span, s, e, EE)
        }
        Rich.ensureTail(sb, paras)
        Rich.writeParas(sb, paras, em)
        return sb
    }

    fun toJson(): String {
        val o = JSONObject()
        if (runs.isNotEmpty()) o.put("r", JSONArray().apply {
            for (r in runs) put(JSONArray().apply {
                put(r.start); put(r.end); put(r.code.toString())
                when (r.code) {
                    'c', 'g' -> put(r.value.toInt())
                    'z' -> put(r.value)
                }
            })
        })
        if (paras.any { !it.isDefault }) o.put("p", JSONArray().apply {
            for (p in paras) put(JSONArray().apply {
                put(p.align.ordinal); put(p.indent); put(p.list.ordinal); put(if (p.checked) 1 else 0)
            })
        })
        return o.toString()
    }

    companion object {
        fun plain(text: String) = RichDoc(text, emptyList(), emptyList())

        fun fromJson(text: String, json: String): RichDoc = try {
            val o = JSONObject(json)
            val runs = ArrayList<Run>()
            o.optJSONArray("r")?.let { a ->
                for (i in 0 until a.length()) {
                    val r = a.getJSONArray(i)
                    val code = r.getString(2).first()
                    val v = when (code) {
                        'c', 'g' -> r.getInt(3).toDouble()
                        'z' -> r.getDouble(3)
                        else -> 0.0
                    }
                    runs.add(Run(r.getInt(0), r.getInt(1), code, v))
                }
            }
            val paras = ArrayList<ParaAttrs>()
            o.optJSONArray("p")?.let { a ->
                for (i in 0 until a.length()) {
                    val p = a.getJSONArray(i)
                    paras.add(
                        ParaAttrs(
                            TextAlign.entries.getOrElse(p.getInt(0)) { TextAlign.LEFT },
                            p.getInt(1).coerceIn(0, ParaAttrs.MAX_INDENT),
                            ListKind.entries.getOrElse(p.getInt(2)) { ListKind.NONE },
                            p.getInt(3) != 0,
                        )
                    )
                }
            }
            RichDoc(text, runs, paras)
        } catch (e: Exception) {
            plain(text)
        }

        /**
         * 편집한 글(서식 span)과 문단 서식으로 원본을 만든다. [ZWSP]는 빼고, 글 끝의 빈칸·빈 줄은 잘라 낸다
         */
        fun from(sp: Spanned, paras: List<ParaAttrs>): RichDoc {
            // ZWSP를 빼면서 위치를 옮겨 적는다
            val src = sp.toString()
            val map = IntArray(src.length + 1)
            val out = StringBuilder(src.length)
            for (i in src.indices) {
                map[i] = out.length
                if (src[i] != ZWSP) out.append(src[i])
            }
            map[src.length] = out.length
            val len = out.trimEnd().length
            val text = out.substring(0, len)
            val raw = ArrayList<Run>()
            for (o in sp.getSpans(0, sp.length, Any::class.java)) {
                val code = Rich.codeOf(o) ?: continue
                val s = map[sp.getSpanStart(o)].coerceAtMost(len)
                val e = map[sp.getSpanEnd(o)].coerceAtMost(len)
                if (e <= s) continue
                val v = when (o) {
                    is RColor -> o.foregroundColor.toDouble()
                    is RBg -> o.backgroundColor.toDouble()
                    is RSize -> o.ratio.toDouble()
                    else -> 0.0
                }
                raw.add(Run(s, e, code, v))
            }
            // 같은 서식끼리 겹치거나 맞닿으면 하나로
            val runs = ArrayList<Run>()
            for ((_, group) in raw.groupBy { it.code to it.value }) {
                var cur: Run? = null
                for (r in group.sortedBy { it.start }) {
                    val c = cur
                    cur = if (c != null && r.start <= c.end) c.copy(end = maxOf(c.end, r.end)) else {
                        if (c != null) runs.add(c)
                        r
                    }
                }
                cur?.let { runs.add(it) }
            }
            runs.sortWith(compareBy({ it.start }, { it.code }))
            return RichDoc(text, runs, paras)
        }
    }
}

/** 서식 span 다루기 (편집기와 원본이 같이 쓴다) */
object Rich {
    fun codeOf(o: Any): Char? = when (o) {
        is RBold -> 'b'
        is RItalic -> 'i'
        is RUnderline -> 'u'
        is RStrike -> 's'
        is RColor -> 'c'
        is RBg -> 'g'
        is RSize -> 'z'
        else -> null
    }

    private fun copySpan(o: Any): Any = when (o) {
        is RBold -> RBold()
        is RItalic -> RItalic()
        is RUnderline -> RUnderline()
        is RStrike -> RStrike()
        is RColor -> RColor(o.foregroundColor)
        is RBg -> RBg(o.backgroundColor)
        is RSize -> RSize(o.ratio)
        else -> error("서식 span이 아님")
    }

    fun toggleSpan(t: CharToggle): Any = when (t) {
        CharToggle.BOLD -> RBold()
        CharToggle.ITALIC -> RItalic()
        CharToggle.UNDERLINE -> RUnderline()
        CharToggle.STRIKE -> RStrike()
    }

    fun codeOf(t: CharToggle) = when (t) {
        CharToggle.BOLD -> 'b'
        CharToggle.ITALIC -> 'i'
        CharToggle.UNDERLINE -> 'u'
        CharToggle.STRIKE -> 's'
    }

    fun has(st: CharStyle, t: CharToggle) = when (t) {
        CharToggle.BOLD -> st.bold
        CharToggle.ITALIC -> st.italic
        CharToggle.UNDERLINE -> st.underline
        CharToggle.STRIKE -> st.strike
    }

    fun with(st: CharStyle, t: CharToggle, on: Boolean) = when (t) {
        CharToggle.BOLD -> st.copy(bold = on)
        CharToggle.ITALIC -> st.copy(italic = on)
        CharToggle.UNDERLINE -> st.copy(underline = on)
        CharToggle.STRIKE -> st.copy(strike = on)
    }

    /** [pos] 글자의 서식 */
    fun styleAt(sp: Spanned, pos: Int): CharStyle {
        var st = CharStyle()
        for (o in sp.getSpans(pos, pos + 1, Any::class.java)) {
            if (sp.getSpanStart(o) > pos || sp.getSpanEnd(o) <= pos) continue
            st = when (o) {
                is RBold -> st.copy(bold = true)
                is RItalic -> st.copy(italic = true)
                is RUnderline -> st.copy(underline = true)
                is RStrike -> st.copy(strike = true)
                is RColor -> st.copy(color = o.foregroundColor)
                is RBg -> st.copy(bg = o.backgroundColor)
                is RSize -> st.copy(size = o.ratio)
                else -> st
            }
        }
        return st
    }

    /** [start, end) 에서 [code] 서식을 걷어 낸다 (범위에 걸친 서식은 잘라 바깥쪽을 남긴다). code가 null이면 전부 */
    fun clear(e: Spannable, start: Int, end: Int, code: Char? = null) {
        if (start >= end) return
        for (o in e.getSpans(start, end, Any::class.java)) {
            val c = codeOf(o) ?: continue
            if (code != null && c != code) continue
            val s = e.getSpanStart(o)
            val t = e.getSpanEnd(o)
            if (t <= start || s >= end) continue
            e.removeSpan(o)
            if (s < start) e.setSpan(copySpan(o), s, start, EE)
            if (t > end) e.setSpan(copySpan(o), end, t, EE)
        }
    }

    /** [start, end) 에 서식 [st]를 입힌다 (있던 글자 서식은 걷어 낸다) */
    fun apply(e: Spannable, start: Int, end: Int, st: CharStyle) {
        if (start >= end) return
        clear(e, start, end)
        if (st.bold) e.setSpan(RBold(), start, end, EE)
        if (st.italic) e.setSpan(RItalic(), start, end, EE)
        if (st.underline) e.setSpan(RUnderline(), start, end, EE)
        if (st.strike) e.setSpan(RStrike(), start, end, EE)
        st.color?.let { e.setSpan(RColor(it), start, end, EE) }
        st.bg?.let { e.setSpan(RBg(it), start, end, EE) }
        if (st.size != 1f) e.setSpan(RSize(st.size), start, end, EE)
    }

    /** 문단마다 [시작, 내용 끝, 다음 문단 시작(줄바꿈 뒤, 마지막 문단은 글 끝)] */
    fun paragraphs(t: CharSequence): List<IntArray> {
        val out = ArrayList<IntArray>()
        var s = 0
        for (i in 0 until t.length) if (t[i] == '\n') {
            out.add(intArrayOf(s, i, i + 1))
            s = i + 1
        }
        out.add(intArrayOf(s, t.length, t.length))
        return out
    }

    /** [pos]가 들어 있는 문단 번호 */
    fun paragraphIndex(t: CharSequence, pos: Int): Int {
        var n = 0
        for (i in 0 until pos.coerceAtMost(t.length)) if (t[i] == '\n') n++
        return n
    }

    /** 서식 있는 마지막 문단이 비어 있으면 표시가 보이도록 [ZWSP]를 붙인다. 붙였으면 true */
    fun ensureTail(e: android.text.Editable, paras: List<ParaAttrs>): Boolean {
        val last = paras.lastOrNull() ?: return false
        if (last.isDefault) return false
        val t = e.toString()
        val lastStart = t.lastIndexOf('\n') + 1
        if (lastStart < t.length) return false
        e.append(ZWSP)
        return true
    }

    /** 문단 서식 span을 모두 새로 붙인다 (번호 목록의 번호도 다시 센다) */
    fun writeParas(e: Spannable, paras: List<ParaAttrs>, em: Float) {
        for (o in e.getSpans(0, e.length, ParaSpan::class.java)) e.removeSpan(o)
        val ranges = paragraphs(e)
        val counters = IntArray(ParaAttrs.MAX_INDENT + 1)
        for ((i, r) in ranges.withIndex()) {
            val a = paras.getOrNull(i) ?: ParaAttrs.DEFAULT
            if (a.list == ListKind.NUMBER) {
                counters[a.indent]++
                for (k in a.indent + 1 until counters.size) counters[k] = 0
            } else for (k in a.indent until counters.size) counters[k] = 0
            if (a.isDefault || r[2] <= r[0]) continue
            val span = ParaSpan(a, em)
            span.number = counters[a.indent].coerceAtLeast(1)
            e.setSpan(span, r[0], r[2], Spanned.SPAN_PARAGRAPH)
        }
    }
}

