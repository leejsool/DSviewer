package com.dsviewer.app.hwp

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import kotlin.math.max
import kotlin.math.min

/**
 * 한글 수식 스크립트 (예: `x = {-b +- sqrt {b^2 - 4ac}} over {2a}`) 를 조판해서 그린다.
 * 상자(Box) 모델: 각 상자는 폭, 기준선 위 높이(asc), 기준선 아래 깊이(desc)를 가진다.
 */
object EqRenderer {

    /** 수식 개체를 사각형 r 안에 그린다 */
    fun draw(c: Canvas, eq: HEquation, r: RectF) {
        val size = eq.baseUnit / 100f
        val box = layout(eq.script, size, eq.color)
        if (box.w <= 0f) return
        // 폭이 넘칠 때만 줄인다 (한글은 괄호 높이를 개체 높이에 넣지 않아 높이는 기준이 못 된다)
        var s = 1f
        if (r.width() > 0f && box.w > r.width()) s = r.width() / box.w
        val x = r.left + max(0f, (r.width() - box.w * s) / 2f)
        // 한 줄 수식은 한글이 알려준 기준선 위치에, 여러 줄(#) 수식은 상자 위쪽에 맞춘다
        val multiLine = box is MatrixBox && box.topLevel
        val baseline = when {
            multiLine -> r.top + box.asc * s
            r.height() > 0f -> r.top + r.height() * eq.baseLine / 100f
            else -> r.top + box.asc
        }
        c.save()
        c.translate(x, baseline)
        c.scale(s, s)
        box.draw(c, 0f, 0f)
        c.restore()
    }

    /** 자연 크기 (pt) */
    fun measure(eq: HEquation): Pair<Float, Float> {
        val box = layout(eq.script, eq.baseUnit / 100f, eq.color)
        val h = box.asc + box.desc
        if (h > 0f) eq.baseLine = (box.asc / h * 100f).toInt().coerceIn(1, 100)
        return box.w to h
    }

    fun layout(script: String, size: Float, color: Int): Box = try {
        Parser(tokenize(script), color).parseAll(size)
    } catch (e: Exception) {
        TextBox(script, paint(size, Style.ROMAN, color), size)
    }

    // ================= 글꼴 =================

    enum class Style { ROMAN, ITALIC, BOLD }

    // 수식 글꼴: STIX Two Text (앱에 포함, OFL). 한글 수식처럼 Times 계열의 수학용 기울임 글자
    private var serif: Typeface = Typeface.SERIF
    private var serifItalic: Typeface = Typeface.create(Typeface.SERIF, Typeface.ITALIC)
    private var serifBold: Typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD)

    /** 앱 시작 때 한 번 호출: 포함된 수학 글꼴을 읽는다 */
    fun init(assets: android.content.res.AssetManager) {
        runCatching {
            serif = Typeface.createFromAsset(assets, "fonts/STIXTwoText-Regular.ttf")
            serifItalic = Typeface.createFromAsset(assets, "fonts/STIXTwoText-Italic.ttf")
            serifBold = Typeface.createFromAsset(assets, "fonts/STIXTwoText-Bold.ttf")
        }
    }

    fun paint(size: Float, style: Style, color: Int): Paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = size
        this.color = color
        textLocale = HFonts.NEUTRAL_LOCALE
        typeface = when (style) {
            Style.ITALIC -> serifItalic
            Style.BOLD -> serifBold
            Style.ROMAN -> serif
        }
    }

    fun linePaint(size: Float, color: Int, width: Float = max(0.4f, size * 0.05f)) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        style = Paint.Style.STROKE
        strokeWidth = width
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    /** 수학 축 높이 (분수선, 괄호 중심) */
    fun axis(size: Float) = size * 0.27f

    // ================= 상자 =================

    abstract class Box {
        var w = 0f
        var asc = 0f
        var desc = 0f
        /** 연산자 간격용 분류: 0 일반, 1 이항 연산자, 2 관계, 3 여는 괄호, 4 쉼표 */
        var cls = 0
        abstract fun draw(c: Canvas, x: Float, y: Float)
    }

    class TextBox(val text: String, val paint: Paint, size: Float) : Box() {
        init {
            w = paint.measureText(text)
            // 기울임 글자는 오른쪽으로 삐져나오므로 약간의 여유
            if (paint.typeface?.isItalic == true) w += size * 0.07f
            asc = size * 0.68f
            desc = size * 0.2f
        }
        override fun draw(c: Canvas, x: Float, y: Float) {
            c.drawText(text, x, y, paint)
        }
    }

    class SpaceBox(width: Float) : Box() {
        init { w = width }
        override fun draw(c: Canvas, x: Float, y: Float) {}
    }

    class RowBox(val items: List<Box>, size: Float) : Box() {
        private val xs = FloatArray(items.size)
        init {
            var x = 0f
            var prev: Box? = null
            for ((i, b) in items.withIndex()) {
                val pad = when (b.cls) {
                    1 -> if (prev == null || prev.cls in 1..3) 0f else size * 0.22f
                    2 -> if (prev == null) 0f else size * 0.28f
                    else -> 0f
                }
                x += pad
                xs[i] = x
                x += b.w
                x += when (b.cls) {
                    1 -> if (prev == null || prev.cls in 1..3) 0f else size * 0.22f
                    2 -> size * 0.28f
                    4 -> size * 0.17f
                    else -> 0f
                }
                prev = b
            }
            w = x
            asc = items.maxOfOrNull { it.asc } ?: size * 0.7f
            desc = items.maxOfOrNull { it.desc } ?: size * 0.2f
        }
        override fun draw(c: Canvas, x: Float, y: Float) {
            for ((i, b) in items.withIndex()) b.draw(c, x + xs[i], y)
        }
    }

    /** 안쪽 상자를 k 배로 줄여 그린다 */
    class ScaledBox(val inner: Box, val k: Float) : Box() {
        init {
            w = inner.w * k; asc = inner.asc * k; desc = inner.desc * k; cls = inner.cls
        }
        override fun draw(c: Canvas, x: Float, y: Float) {
            c.save()
            c.translate(x, y)
            c.scale(k, k)
            inner.draw(c, 0f, 0f)
            c.restore()
        }
    }

    fun containsFrac(b: Box): Boolean = when (b) {
        is FracBox -> true
        is RowBox -> b.items.any { containsFrac(it) }
        is ScriptBox -> containsFrac(b.base)
        is DelimBox -> containsFrac(b.body)
        else -> false
    }

    class FracBox(num0: Box, den0: Box, val size: Float, val color: Int, val line: Boolean = true) : Box() {
        // 분수 안의 분수는 작게 (한글과 같은 모양)
        val num: Box = if (containsFrac(num0)) ScaledBox(num0, 0.8f) else num0
        val den: Box = if (containsFrac(den0)) ScaledBox(den0, 0.8f) else den0
        private val ax = axis(size)
        private val gap = size * 0.14f
        private val thick = max(0.4f, size * 0.05f)
        private val numShift: Float
        private val denShift: Float
        init {
            w = max(num.w, den.w) + size * 0.24f
            numShift = ax + thick / 2 + gap + num.desc
            denShift = -ax + thick / 2 + gap + den.asc
            asc = numShift + num.asc
            desc = denShift + den.desc
        }
        override fun draw(c: Canvas, x: Float, y: Float) {
            num.draw(c, x + (w - num.w) / 2f, y - numShift)
            den.draw(c, x + (w - den.w) / 2f, y + denShift)
            if (line) c.drawLine(x + size * 0.06f, y - ax, x + w - size * 0.06f, y - ax, linePaint(size, color, thick))
        }
    }

    class SqrtBox(val body: Box, val index: Box?, val size: Float, val color: Int) : Box() {
        private val gap = size * 0.12f
        private val thick = max(0.4f, size * 0.055f)
        private val radW = size * 0.6f
        private val top: Float
        private val off: Float
        init {
            top = body.asc + gap + thick
            off = if (index != null) max(0f, index.w - radW * 0.45f) else 0f
            w = off + radW + body.w + size * 0.12f
            asc = top + thick
            if (index != null) asc = max(asc, body.asc * 0.55f + index.asc + index.desc + size * 0.1f)
            desc = body.desc + size * 0.06f
        }
        override fun draw(c: Canvas, x: Float, y: Float) {
            val x0 = x + off
            val p = Path()
            p.moveTo(x0, y - body.asc * 0.35f)
            p.lineTo(x0 + radW * 0.22f, y - body.asc * 0.45f)
            p.lineTo(x0 + radW * 0.52f, y + desc - size * 0.04f)
            p.lineTo(x0 + radW, y - top)
            p.lineTo(x0 + radW + body.w + size * 0.1f, y - top)
            c.drawPath(p, linePaint(size, color, thick))
            body.draw(c, x0 + radW + size * 0.04f, y)
            index?.draw(c, x0 + radW * 0.4f - index.w, y - body.asc * 0.55f - index.desc - size * 0.05f)
        }
    }

    class ScriptBox(val base: Box, val sup: Box?, val sub: Box?, size: Float) : Box() {
        private val supShift: Float
        private val subShift: Float
        init {
            supShift = if (sup != null) max(size * 0.42f, base.asc - sup.asc * 0.55f) else 0f
            subShift = if (sub != null) max(size * 0.22f, base.desc + sub.asc * 0.35f - size * 0.1f) else 0f
            w = base.w + max(sup?.w ?: 0f, sub?.w ?: 0f) + size * 0.05f
            asc = max(base.asc, if (sup != null) supShift + sup.asc else 0f)
            desc = max(base.desc, if (sub != null) subShift + sub.desc else 0f)
            cls = base.cls
        }
        override fun draw(c: Canvas, x: Float, y: Float) {
            base.draw(c, x, y)
            sup?.draw(c, x + base.w + 1f, y - supShift)
            sub?.draw(c, x + base.w, y + subShift)
        }
    }

    /** 합, 곱, 적분 등 큰 연산자 */
    class BigOpBox(val sym: String, val lower: Box?, val upper: Box?, val size: Float, val color: Int, val sideLimits: Boolean, val bigSym: Boolean) : Box() {
        // 적분 기호는 글자 높이의 약 2배, 합/곱 기호는 1.55배 (한글과 비슷하게)
        private val symPaint = paint(if (!bigSym) size else if (sideLimits) size * 2.0f else size * 1.55f, Style.ROMAN, color)
        private val symW = symPaint.measureText(sym)
        private val symAsc: Float
        private val symDesc: Float
        private val symShift: Float
        private val gap = size * 0.12f
        init {
            val symSize = symPaint.textSize
            if (bigSym) {
                // 기호를 수학 축 중심에 둔다
                val half = symSize * 0.5f
                // 기호 글자의 시각적 중심(기준선 위 약 0.35em)을 수학 축에 맞춘다
                symShift = symSize * 0.35f - axis(size)
                symAsc = axis(size) + half
                symDesc = half - axis(size)
            } else {
                symShift = 0f
                symAsc = size * 0.72f
                symDesc = size * 0.22f
            }
            if (sideLimits) {
                w = symW + max((lower?.w ?: 0f) - size * 0.1f, (upper?.w ?: 0f) + size * 0.08f) + size * 0.1f
                asc = max(symAsc, (upper?.let { symAsc - it.asc * 0.3f + it.asc } ?: 0f))
                desc = max(symDesc, (lower?.let { symDesc + it.desc } ?: 0f))
            } else {
                w = max(symW, max(lower?.w ?: 0f, upper?.w ?: 0f))
                asc = symAsc + (upper?.let { gap + it.asc + it.desc } ?: 0f)
                desc = symDesc + (lower?.let { gap + it.asc + it.desc } ?: 0f)
            }
            w += size * 0.12f
        }
        override fun draw(c: Canvas, x: Float, y: Float) {
            if (sideLimits) {
                c.drawText(sym, x, y + symShift, symPaint)
                // 위 끝은 기호 오른쪽 위, 아래 끝은 기호 오른쪽 아래
                upper?.draw(c, x + symW + size * 0.08f, y - symAsc + upper.asc)
                lower?.draw(c, x + symW - size * 0.1f, y + symDesc - lower.desc)
            } else {
                val cx = x + (w - size * 0.12f) / 2f
                c.drawText(sym, cx - symW / 2f, y + symShift, symPaint)
                upper?.draw(c, cx - upper.w / 2f, y - symAsc - gap - upper.desc)
                lower?.draw(c, cx - lower.w / 2f, y + symDesc + gap + lower.asc)
            }
        }
    }

    /** 늘어나는 괄호 */
    class DelimBox(val left: String, val body: Box, val right: String, val size: Float, val color: Int) : Box() {
        private val ax = axis(size)
        // 내용이 보통 글자 높이면 괄호도 글자 높이 (한글과 같게)
        private val extent = max(max(body.asc - ax, body.desc + ax) + size * 0.06f, size * 0.5f)
        private val lw = delimWidth(left)
        private val rw = delimWidth(right)
        init {
            w = lw + body.w + rw
            asc = ax + extent
            desc = extent - ax
        }
        private fun delimWidth(d: String) = when (d) {
            "", "." -> 0f
            "|" -> size * 0.28f
            "||" -> size * 0.42f
            "{", "}" -> size * 0.45f
            else -> size * 0.38f
        }
        override fun draw(c: Canvas, x: Float, y: Float) {
            val top = y - ax - extent
            val bottom = y - ax + extent
            drawDelim(c, left, x, lw, top, bottom, true)
            body.draw(c, x + lw, y)
            drawDelim(c, right, x + lw + body.w, rw, top, bottom, false)
        }
        private fun drawDelim(c: Canvas, d: String, x: Float, dw: Float, top: Float, bottom: Float, isLeft: Boolean) {
            if (dw <= 0f) return
            val p = linePaint(size, color, max(0.45f, size * 0.06f))
            val mid = (top + bottom) / 2f
            val inner = if (isLeft) x + dw * 0.8f else x + dw * 0.2f
            val outer = if (isLeft) x + dw * 0.25f else x + dw * 0.75f
            val path = Path()
            when (d) {
                "(", ")" -> {
                    path.moveTo(inner, top)
                    path.quadTo(outer - (inner - outer) * 0.25f, mid, inner, bottom)
                }
                "[", "]" -> {
                    path.moveTo(inner, top); path.lineTo(outer, top); path.lineTo(outer, bottom); path.lineTo(inner, bottom)
                }
                "{", "}" -> {
                    val m = (inner + outer) / 2f
                    val q = (bottom - top) / 4f
                    path.moveTo(inner, top)
                    path.quadTo(m, top, m, top + q * 0.6f)
                    path.lineTo(m, mid - q * 0.5f)
                    path.quadTo(m, mid, outer, mid)
                    path.quadTo(m, mid, m, mid + q * 0.5f)
                    path.lineTo(m, bottom - q * 0.6f)
                    path.quadTo(m, bottom, inner, bottom)
                }
                "<", ">", "langle", "rangle" -> {
                    path.moveTo(inner, top); path.lineTo(outer, mid); path.lineTo(inner, bottom)
                }
                "|" -> { val m = x + dw / 2f; path.moveTo(m, top); path.lineTo(m, bottom) }
                "||" -> {
                    path.moveTo(x + dw * 0.3f, top); path.lineTo(x + dw * 0.3f, bottom)
                    path.moveTo(x + dw * 0.7f, top); path.lineTo(x + dw * 0.7f, bottom)
                }
                "lceil", "rceil" -> { path.moveTo(inner, top); path.lineTo(outer, top); path.lineTo(outer, bottom) }
                "lfloor", "rfloor" -> { path.moveTo(outer, top); path.lineTo(outer, bottom); path.lineTo(inner, bottom) }
                else -> {
                    val tp = paint(size, Style.ROMAN, color)
                    c.drawText(d, x, mid + size * 0.3f, tp)
                    return
                }
            }
            c.drawPath(path, p)
        }
    }

    /** 행렬, 쌓기(pile), 경우(cases) */
    class MatrixBox(val rows: List<List<Box>>, val aligns: (Int) -> Int, val size: Float, val topLevel: Boolean = false) : Box() {
        private val cols = rows.maxOfOrNull { it.size } ?: 0
        private val colW = FloatArray(cols)
        private val rowAsc = FloatArray(rows.size)
        private val rowDesc = FloatArray(rows.size)
        private val colGap = size * 0.9f
        private val rowGap = size * 0.25f
        private val total: Float
        init {
            for ((ri, row) in rows.withIndex()) {
                rowAsc[ri] = size * 0.7f
                rowDesc[ri] = size * 0.2f
                for ((ci, cell) in row.withIndex()) {
                    colW[ci] = max(colW[ci], cell.w)
                    rowAsc[ri] = max(rowAsc[ri], cell.asc)
                    rowDesc[ri] = max(rowDesc[ri], cell.desc)
                }
            }
            w = colW.sum() + colGap * max(0, cols - 1)
            var h = 0f
            for (ri in rows.indices) h += rowAsc[ri] + rowDesc[ri]
            h += rowGap * max(0, rows.size - 1)
            total = h
            asc = axis(size) + h / 2f
            desc = h / 2f - axis(size)
        }
        override fun draw(c: Canvas, x: Float, y: Float) {
            var top = y - asc
            for ((ri, row) in rows.withIndex()) {
                val base = top + rowAsc[ri]
                var cx = x
                for (ci in 0 until cols) {
                    val cell = row.getOrNull(ci)
                    if (cell != null) {
                        val dx = when (aligns(ci)) {
                            -1 -> 0f
                            1 -> colW[ci] - cell.w
                            else -> (colW[ci] - cell.w) / 2f
                        }
                        cell.draw(c, cx + dx, base)
                    }
                    cx += colW[ci] + colGap
                }
                top += rowAsc[ri] + rowDesc[ri] + rowGap
            }
        }
    }

    /** 사각형 테두리 상자 */
    class FrameBox(val body: Box, val size: Float, val color: Int) : Box() {
        private val pad = size * 0.12f
        init {
            w = body.w + pad * 2
            asc = max(body.asc, size * 0.7f) + pad
            desc = max(body.desc, size * 0.2f) + pad
        }
        override fun draw(c: Canvas, x: Float, y: Float) {
            body.draw(c, x + pad, y)
            c.drawRect(x + pad * 0.3f, y - asc + pad * 0.3f, x + w - pad * 0.3f, y + desc - pad * 0.3f, linePaint(size, color, max(0.4f, size * 0.04f)))
        }
    }

    class AccentBox(val body: Box, val kind: String, val size: Float, val color: Int) : Box() {
        private val under = kind == "under" || kind == "underline"
        private val extra = size * 0.28f
        init {
            w = body.w
            asc = body.asc + if (under) 0f else extra
            desc = body.desc + if (under) extra else 0f
            cls = body.cls
        }
        override fun draw(c: Canvas, x: Float, y: Float) {
            body.draw(c, x, y)
            val p = linePaint(size, color, max(0.4f, size * 0.045f))
            val ay = y - body.asc - size * 0.1f
            val cx = x + w / 2f
            when (kind) {
                "bar", "overline" -> c.drawLine(x, ay, x + w, ay, p)
                "under", "underline" -> { val uy = y + body.desc + size * 0.1f; c.drawLine(x, uy, x + w, uy, p) }
                "vec", "dyad" -> {
                    c.drawLine(x, ay, x + w, ay, p)
                    val hs = size * 0.12f
                    c.drawLine(x + w, ay, x + w - hs, ay - hs * 0.7f, p)
                    c.drawLine(x + w, ay, x + w - hs, ay + hs * 0.7f, p)
                    if (kind == "dyad") {
                        c.drawLine(x, ay, x + hs, ay - hs * 0.7f, p)
                        c.drawLine(x, ay, x + hs, ay + hs * 0.7f, p)
                    }
                }
                "hat", "check" -> {
                    val hw = min(w / 2f, size * 0.25f)
                    val up = if (kind == "hat") -size * 0.14f else size * 0.14f
                    val path = Path()
                    path.moveTo(cx - hw, ay)
                    path.lineTo(cx, ay + up)
                    path.lineTo(cx + hw, ay)
                    c.drawPath(path, p)
                }
                "tilde" -> {
                    val hw = min(w / 2f, size * 0.25f)
                    val path = Path()
                    path.moveTo(cx - hw, ay)
                    path.quadTo(cx - hw / 2f, ay - size * 0.12f, cx, ay)
                    path.quadTo(cx + hw / 2f, ay + size * 0.12f, cx + hw, ay)
                    c.drawPath(path, p)
                }
                "dot", "ddot" -> {
                    val f = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }
                    val rad = size * 0.05f
                    if (kind == "dot") c.drawCircle(cx, ay, rad, f)
                    else { c.drawCircle(cx - size * 0.1f, ay, rad, f); c.drawCircle(cx + size * 0.1f, ay, rad, f) }
                }
                "acute" -> c.drawLine(cx - size * 0.05f, ay, cx + size * 0.08f, ay - size * 0.12f, p)
                "grave" -> c.drawLine(cx + size * 0.05f, ay, cx - size * 0.08f, ay - size * 0.12f, p)
                "arch" -> {
                    val path = Path()
                    path.moveTo(x, ay)
                    path.quadTo(cx, ay - size * 0.2f, x + w, ay)
                    c.drawPath(path, p)
                }
            }
        }
    }

    // ================= 토큰 =================

    private sealed class Tok {
        class Word(val s: String) : Tok()
        class Num(val s: String) : Tok()
        class Sym(val s: String) : Tok()
        class Text(val s: String) : Tok()
        class Space(val em: Float) : Tok()
        object LBrace : Tok()
        object RBrace : Tok()
        object Sup : Tok()
        object Sub : Tok()
        object Hash : Tok()
        object Amp : Tok()
    }

    private val MULTI_OPS = listOf("<=>", "<->", "<=", ">=", "!=", "==", "->", "<-", "=>", "+-", "-+", "<<", ">>")

    private fun tokenize(s: String): List<Tok> {
        val out = ArrayList<Tok>()
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            when {
                ch.isWhitespace() -> i++
                ch == '{' -> { out.add(Tok.LBrace); i++ }
                ch == '}' -> { out.add(Tok.RBrace); i++ }
                ch == '^' -> { out.add(Tok.Sup); i++ }
                ch == '_' -> { out.add(Tok.Sub); i++ }
                ch == '#' -> { out.add(Tok.Hash); i++ }
                ch == '&' -> { out.add(Tok.Amp); i++ }
                ch == '`' -> { out.add(Tok.Space(0.17f)); i++ }
                ch == '~' -> { out.add(Tok.Space(0.33f)); i++ }
                ch == '\\' -> i++
                ch == '"' -> {
                    val end = s.indexOf('"', i + 1).let { if (it < 0) s.length else it }
                    out.add(Tok.Text(s.substring(i + 1, end)))
                    i = end + 1
                }
                ch in 'a'..'z' || ch in 'A'..'Z' -> {
                    var j = i
                    while (j < s.length && (s[j] in 'a'..'z' || s[j] in 'A'..'Z')) j++
                    splitWord(s.substring(i, j), out)
                    i = j
                }
                ch.isDigit() || (ch == '.' && i + 1 < s.length && s[i + 1].isDigit()) -> {
                    var j = i
                    while (j < s.length && (s[j].isDigit() || (s[j] == '.' && j + 1 < s.length && s[j + 1].isDigit()))) j++
                    out.add(Tok.Num(s.substring(i, j)))
                    i = j
                }
                ch.code >= 0x80 && Character.isLetter(ch) -> {
                    // 한글 등은 한 덩어리 글자로
                    var j = i
                    while (j < s.length && s[j].code >= 0x80 && Character.isLetter(s[j])) j++
                    out.add(Tok.Text(s.substring(i, j)))
                    i = j
                }
                else -> {
                    val op = MULTI_OPS.firstOrNull { s.startsWith(it, i) }
                    if (op != null) { out.add(Tok.Sym(op)); i += op.length }
                    else { out.add(Tok.Sym(ch.toString())); i++ }
                }
            }
        }
        return out
    }

    /**
     * 한글 수식은 명령어 뒤에 글자를 붙여 쓸 수 있다 (sinx → sin x, rmABC → rm ABC).
     * 단어 앞부분이 명령어면 가장 긴 명령어를 떼어 낸다.
     */
    private fun splitWord(word: String, out: MutableList<Tok>) {
        var w = word
        while (w.isNotEmpty()) {
            var best = ""
            for (k in KEYWORDS) {
                if (k.length > best.length && k.length <= w.length && matchesKeyword(w, k)) best = w.substring(0, k.length)
            }
            if (best.isEmpty() || best.length == w.length) {
                out.add(Tok.Word(w))
                return
            }
            out.add(Tok.Word(best))
            w = w.substring(best.length)
        }
    }

    /** w 가 명령어 k 로 시작하는가 */
    private fun matchesKeyword(w: String, k: String): Boolean = keywordForm(w.substring(0, k.length)) == k

    /**
     * 단어가 명령어 형태면 소문자 명령어 이름, 아니면 null.
     * 소문자는 그대로 인정하고, 대문자(LEFT, TIMES)나 첫 글자 대문자(Theta)는 3글자 이상만 인정한다.
     * (IN, NE 같은 점 이름이 기호로 바뀌지 않도록)
     */
    fun keywordForm(word: String): String? {
        val lw = word.lowercase()
        if (word == lw) return lw
        if (word.length < 3) return null
        if (word == word.uppercase()) return lw
        if (word[0].isUpperCase() && word.substring(1) == lw.substring(1)) return lw
        return null
    }

    private val KEYWORDS: Set<String> by lazy {
        val s = HashSet<String>()
        s.addAll(GREEK.keys); s.addAll(SYMBOLS.keys); s.addAll(FUNCS.map { it.lowercase() }); s.addAll(BIG_OPS.keys.map { it.lowercase() })
        s.addAll(ACCENTS)
        s.addAll(listOf(
            "sqrt", "root", "of", "over", "atop", "choose", "left", "right", "matrix", "pmatrix", "bmatrix", "dmatrix",
            "cases", "pile", "cpile", "lpile", "rpile", "eqalign", "rm", "it", "bold", "from", "to", "sup", "sub",
            "lbrace", "rbrace", "langle", "rangle", "lceil", "rceil", "lfloor", "rfloor",
        ))
        s.addAll(SYMBOLS_EXACT.keys.map { it.lowercase() })
        s
    }

    // ================= 기호표 =================

    private val GREEK = mapOf(
        "alpha" to "α", "beta" to "β", "gamma" to "γ", "delta" to "δ", "epsilon" to "ε", "zeta" to "ζ",
        "eta" to "η", "theta" to "θ", "iota" to "ι", "kappa" to "κ", "lambda" to "λ", "mu" to "μ",
        "nu" to "ν", "xi" to "ξ", "omicron" to "ο", "pi" to "π", "rho" to "ρ", "sigma" to "σ",
        "tau" to "τ", "upsilon" to "υ", "phi" to "φ", "chi" to "χ", "psi" to "ψ", "omega" to "ω",
        "varepsilon" to "ε", "vartheta" to "ϑ", "varphi" to "ϕ", "varsigma" to "ς", "varrho" to "ϱ",
    )
    private val GREEK_UPPER = mapOf(
        "alpha" to "Α", "beta" to "Β", "gamma" to "Γ", "delta" to "Δ", "epsilon" to "Ε", "zeta" to "Ζ",
        "eta" to "Η", "theta" to "Θ", "iota" to "Ι", "kappa" to "Κ", "lambda" to "Λ", "mu" to "Μ",
        "nu" to "Ν", "xi" to "Ξ", "omicron" to "Ο", "pi" to "Π", "rho" to "Ρ", "sigma" to "Σ",
        "tau" to "Τ", "upsilon" to "Υ", "phi" to "Φ", "chi" to "Χ", "psi" to "Ψ", "omega" to "Ω",
    )

    /** 기호: 이름 → (글자, 분류) */
    private val SYMBOLS_EXACT = mapOf(
        "Rarrow" to ("⇒" to 2), "Larrow" to ("⇐" to 2), "LRarrow" to ("⇔" to 2), "Lrarrow" to ("⇔" to 2),
        "Uparrow" to ("⇑" to 2), "Downarrow" to ("⇓" to 2),
    )
    private val SYMBOLS = mapOf(
        "times" to ("×" to 1), "div" to ("÷" to 1), "cdot" to ("·" to 1), "pm" to ("±" to 1), "mp" to ("∓" to 1),
        "le" to ("≤" to 2), "leq" to ("≤" to 2), "ge" to ("≥" to 2), "geq" to ("≥" to 2), "ne" to ("≠" to 2), "neq" to ("≠" to 2),
        "approx" to ("≈" to 2), "sim" to ("∼" to 2), "simeq" to ("≃" to 2), "cong" to ("≅" to 2), "equiv" to ("≡" to 2),
        "propto" to ("∝" to 2), "inf" to ("∞" to 0), "infinity" to ("∞" to 0), "infty" to ("∞" to 0),
        "partial" to ("∂" to 0), "nabla" to ("∇" to 0), "forall" to ("∀" to 0), "exist" to ("∃" to 0), "exists" to ("∃" to 0),
        "in" to ("∈" to 2), "notin" to ("∉" to 2), "ni" to ("∋" to 2), "owns" to ("∋" to 2),
        "subset" to ("⊂" to 2), "supset" to ("⊃" to 2), "subseteq" to ("⊆" to 2), "supseteq" to ("⊇" to 2),
        "cup" to ("∪" to 1), "cap" to ("∩" to 1), "emptyset" to ("∅" to 0), "therefore" to ("∴" to 2), "because" to ("∵" to 2),
        "angle" to ("∠" to 0), "triangle" to ("△" to 0), "perp" to ("⊥" to 2), "bot" to ("⊥" to 0), "prime" to ("′" to 0),
        "deg" to ("°" to 0), "cdots" to ("⋯" to 0), "ldots" to ("…" to 0), "vdots" to ("⋮" to 0), "ddots" to ("⋱" to 0),
        "rarrow" to ("→" to 2), "rightarrow" to ("→" to 2), "larrow" to ("←" to 2), "leftarrow" to ("←" to 2),
        "lrarrow" to ("↔" to 2), "uparrow" to ("↑" to 2), "downarrow" to ("↓" to 2), "to" to ("→" to 2),
        "star" to ("⋆" to 1), "circ" to ("∘" to 1), "bullet" to ("∙" to 1), "oplus" to ("⊕" to 1), "otimes" to ("⊗" to 1),
        "odot" to ("⊙" to 1), "ominus" to ("⊖" to 1), "neg" to ("¬" to 0), "wedge" to ("∧" to 1), "land" to ("∧" to 1),
        "vee" to ("∨" to 1), "lor" to ("∨" to 1), "aleph" to ("ℵ" to 0), "hbar" to ("ℏ" to 0), "ell" to ("ℓ" to 0),
        "wp" to ("℘" to 0), "dagger" to ("†" to 0), "ohm" to ("Ω" to 0), "angstrom" to ("Å" to 0), "centigrade" to ("℃" to 0),
        "celsius" to ("℃" to 0), "prec" to ("≺" to 2), "succ" to ("≻" to 2), "ll" to ("≪" to 2), "gg" to ("≫" to 2),
        "parallel" to ("∥" to 2), "vert" to ("|" to 0), "diamond" to ("◇" to 0), "box" to ("□" to 0), "square" to ("□" to 0),
        "blacksquare" to ("■" to 0), "percent" to ("%" to 0), "smallint" to ("∫" to 0), "doteq" to ("≐" to 2),
        "xor" to ("⊻" to 1), "image" to ("≒" to 2), "sharp" to ("♯" to 0), "flat" to ("♭" to 0), "lnot" to ("¬" to 0),
    )
    private val OPS = mapOf(
        "+" to ("+" to 1), "-" to ("−" to 1), "*" to ("∗" to 1), "/" to ("/" to 0),
        "=" to ("=" to 2), "<" to ("<" to 2), ">" to (">" to 2), "<=" to ("≤" to 2), ">=" to ("≥" to 2),
        "!=" to ("≠" to 2), "==" to ("≡" to 2), "->" to ("→" to 2), "<-" to ("←" to 2), "<->" to ("↔" to 2),
        "=>" to ("⇒" to 2), "<=>" to ("⇔" to 2), "+-" to ("±" to 1), "-+" to ("∓" to 1), "<<" to ("≪" to 2), ">>" to ("≫" to 2),
        ":" to (":" to 2), "," to ("," to 4), ";" to (";" to 4), "'" to ("′" to 0),
        "(" to ("(" to 3), "[" to ("[" to 3),
    )
    private val FUNCS = setOf(
        "sin", "cos", "tan", "cot", "sec", "csc", "cosec", "arcsin", "arccos", "arctan", "sinh", "cosh", "tanh", "coth",
        "log", "ln", "lg", "exp", "det", "mod", "gcd", "lcm", "max", "min", "sup", "inf", "arg", "dim", "ker", "hom", "deg",
        "Pr", "if", "for", "and", "or", "Re", "Im",
    )
    /** 큰 연산자: 이름 → (기호, 한계 위치가 옆인가, 큰 기호인가) */
    private val BIG_OPS = mapOf(
        "sum" to Triple("∑", false, true), "prod" to Triple("∏", false, true), "coprod" to Triple("∐", false, true),
        "int" to Triple("∫", true, true), "integral" to Triple("∫", true, true), "oint" to Triple("∮", true, true),
        "dint" to Triple("∬", true, true), "tint" to Triple("∭", true, true), "odint" to Triple("∯", true, true),
        "otint" to Triple("∰", true, true), "bigcup" to Triple("⋃", false, true), "union" to Triple("⋃", false, true),
        "bigcap" to Triple("⋂", false, true), "inter" to Triple("⋂", false, true), "bigoplus" to Triple("⨁", false, true),
        "bigotimes" to Triple("⨂", false, true), "lim" to Triple("lim", false, false), "Lim" to Triple("Lim", false, false),
    )
    private val ACCENTS = setOf("bar", "overline", "under", "underline", "vec", "hat", "dot", "ddot", "tilde", "acute", "grave", "check", "arch", "dyad")

    // ================= 파서 =================

    private class Parser(val toks: List<Tok>, val color: Int) {
        var i = 0
        var style: Style? = null

        fun peek(): Tok? = toks.getOrNull(i)
        fun peekWord(): String? = (peek() as? Tok.Word)?.s

        fun parseAll(size: Float): Box {
            val rows = ArrayList<Box>()
            // 최상위의 # 은 줄바꿈 (쌓기)
            while (true) {
                rows.add(parseExpr(size))
                when (peek()) {
                    Tok.Hash, Tok.Amp -> i++
                    Tok.RBrace -> i++
                    null -> break
                    else -> {}
                }
                if (peek() == null) break
            }
            return if (rows.size == 1) rows[0] else MatrixBox(rows.map { listOf(it) }, { -1 }, size, topLevel = true)
        }

        fun isStop(t: Tok?): Boolean = t == null || t == Tok.RBrace || t == Tok.Hash || t == Tok.Amp ||
            (t is Tok.Word && t.s.lowercase() == "right")

        fun parseExpr(size: Float): Box {
            val items = ArrayList<Box>()
            while (!isStop(peek())) {
                val w = peekWord()?.lowercase()
                if (w == "over" || w == "atop" || w == "choose") {
                    i++
                    val num = if (items.isNotEmpty()) items.removeAt(items.size - 1) else RowBox(emptyList(), size)
                    val den = parseTerm(size)
                    val frac = FracBox(num, den, size, color, line = w == "over")
                    items.add(if (w == "choose") DelimBox("(", frac, ")", size, color) else frac)
                    continue
                }
                val before = i
                val t = parseTerm(size)
                items.add(t)
                if (i == before) i++ // 안전장치
            }
            return if (items.size == 1) items[0] else RowBox(items, size)
        }

        fun scriptSize(size: Float) = max(size * 0.7f, 4f)

        /** 기본 요소 + 위/아래 첨자 */
        fun parseTerm(size: Float): Box {
            var base = parsePrimary(size)
            var sup: Box? = null
            var sub: Box? = null
            while (true) {
                val t = peek()
                val w = (t as? Tok.Word)?.s?.lowercase()
                when {
                    t == Tok.Sup || w == "sup" -> { i++; sup = parsePrimary(scriptSize(size)) }
                    t == Tok.Sub || w == "sub" -> { i++; sub = parsePrimary(scriptSize(size)) }
                    else -> break
                }
            }
            if (base is BigOpBox) return base
            if (sup != null || sub != null) base = ScriptBox(base, sup, sub, size)
            return base
        }

        /** 기본이 기울임인 글자(변수)만 rm/it 전환의 영향을 받고, 굵게는 모두에 적용 */
        fun text(s: String, size: Float, st: Style, cls: Int = 0): Box {
            val eff = when {
                style == Style.BOLD -> Style.BOLD
                st == Style.ITALIC && style != null -> style!!
                else -> st
            }
            return TextBox(s, paint(size, eff, color), size).also { it.cls = cls }
        }

        /** { ... } 묶음: 안에서 바꾼 글자 모양은 묶음이 끝나면 되돌린다 */
        fun braced(size: Float): Box {
            i++
            val saved = style
            val e = parseExpr(size)
            if (peek() == Tok.RBrace) i++
            style = saved
            return e
        }

        fun group(size: Float): Box = if (peek() == Tok.LBrace) braced(size) else parseTerm(size)

        fun parsePrimary(size: Float): Box {
            val t = peek() ?: return RowBox(emptyList(), size)
            when (t) {
                Tok.LBrace -> return braced(size)
                Tok.RBrace, Tok.Hash, Tok.Amp -> return RowBox(emptyList(), size)
                Tok.Sup, Tok.Sub -> return RowBox(emptyList(), size)
                is Tok.Num -> { i++; return text(t.s, size, Style.ROMAN) }
                is Tok.Text -> { i++; return text(t.s, size, Style.ROMAN) }
                is Tok.Space -> { i++; return SpaceBox(size * t.em) }
                is Tok.Sym -> {
                    i++
                    val op = OPS[t.s]
                    if (op != null) return text(op.first, size, Style.ROMAN, op.second)
                    return text(t.s, size, Style.ROMAN)
                }
                is Tok.Word -> return parseWord(t.s, size)
            }
            @Suppress("UNREACHABLE_CODE")
            return RowBox(emptyList(), size)
        }

        fun readDelim(): String {
            return when (val t = peek()) {
                is Tok.Sym -> { i++; if (t.s == "|" && (peek() as? Tok.Sym)?.s == "|") { i++; "||" } else t.s }
                Tok.LBrace -> { i++; "{" }
                Tok.RBrace -> { i++; "}" }
                is Tok.Word -> {
                    i++
                    when (t.s.lowercase()) {
                        "lbrace" -> "{"; "rbrace" -> "}"; "langle" -> "<"; "rangle" -> ">"
                        "lceil" -> "lceil"; "rceil" -> "rceil"; "lfloor" -> "lfloor"; "rfloor" -> "rfloor"
                        "vert" -> "|"; "dline" -> "||"
                        else -> ""
                    }
                }
                else -> ""
            }
        }

        fun parseRows(size: Float): List<List<Box>> {
            val rows = ArrayList<List<Box>>()
            if (peek() != Tok.LBrace) return listOf(listOf(parseTerm(size)))
            i++
            var row = ArrayList<Box>()
            while (true) {
                row.add(parseExpr(size))
                when (peek()) {
                    Tok.Amp -> i++
                    Tok.Hash -> { i++; rows.add(row); row = ArrayList() }
                    Tok.RBrace -> { i++; rows.add(row); break }
                    null -> { rows.add(row); break }
                    else -> i++
                }
            }
            return rows
        }

        fun parseWord(word: String, size: Float): Box {
            i++
            SYMBOLS_EXACT[word]?.let { return text(it.first, size, Style.ROMAN, it.second) }
            // 명령어 형태가 아니면 일반 문자(변수)
            val lw = keywordForm(word) ?: return text(word, size, Style.ITALIC)
            // 구조 키워드 (대소문자 무시)
            when (lw) {
                "sqrt" -> return SqrtBox(group(size), null, size, color)
                "root" -> {
                    val idx = group(scriptSize(size))
                    if (peekWord()?.lowercase() == "of") i++
                    return SqrtBox(group(size), idx, size, color)
                }
                "left" -> {
                    val l = readDelim()
                    val body = parseExpr(size)
                    var r = ""
                    if (peekWord()?.lowercase() == "right") { i++; r = readDelim() }
                    return DelimBox(l, body, r, size, color)
                }
                "matrix", "pmatrix", "bmatrix", "dmatrix" -> {
                    val m = MatrixBox(parseRows(size), { 0 }, size)
                    return when (lw) {
                        "pmatrix" -> DelimBox("(", m, ")", size, color)
                        "bmatrix" -> DelimBox("[", m, "]", size, color)
                        "dmatrix" -> DelimBox("|", m, "|", size, color)
                        else -> m
                    }
                }
                "cases" -> return DelimBox("{", MatrixBox(parseRows(size), { -1 }, size), "", size, color)
                "pile", "cpile" -> return MatrixBox(parseRows(size), { 0 }, size)
                "lpile" -> return MatrixBox(parseRows(size), { -1 }, size)
                "rpile" -> return MatrixBox(parseRows(size), { 1 }, size)
                "eqalign" -> return MatrixBox(parseRows(size), { if (it % 2 == 0) 1 else -1 }, size)
                "rm", "it", "bold" -> {
                    // 글자 모양 전환: 현재 { } 묶음이 끝날 때까지 유지
                    style = when (lw) { "rm" -> Style.ROMAN; "it" -> Style.ITALIC; else -> Style.BOLD }
                    return SpaceBox(0f)
                }
                "of", "from", "to" -> if (lw != "to") return RowBox(emptyList(), size)
            }
            if (lw in ACCENTS) return AccentBox(group(size), lw, size, color)
            // box{…}: 내용을 사각형으로 둘러싼다
            if (lw == "box" && peek() == Tok.LBrace) return FrameBox(braced(size), size, color)
            BIG_OPS[word]?.let { return bigOp(it, size) } ?: BIG_OPS[lw]?.let { return bigOp(it, size) }

            SYMBOLS_EXACT[word]?.let { return text(it.first, size, Style.ROMAN, it.second) }
            // 그리스 문자: 소문자 이름 → 소문자, 대문자로 시작 → 대문자
            GREEK[lw]?.let { g ->
                if (word[0].isUpperCase()) return text(GREEK_UPPER[lw] ?: g, size, Style.ROMAN)
                // 소문자 그리스 문자는 rm 안에서도 기울임 (한글과 같음). 굵게만 적용
                val st = if (style == Style.BOLD) Style.BOLD else Style.ITALIC
                return TextBox(g, paint(size, st, color), size)
            }
            SYMBOLS[lw]?.let { return text(it.first, size, Style.ROMAN, it.second) }
            if (word in FUNCS || lw in FUNCS) {
                return RowBox(listOf(text(word, size, Style.ROMAN), SpaceBox(size * 0.12f)), size)
            }
            // 일반 문자(변수): 기울임
            return text(word, size, Style.ITALIC)
        }

        fun bigOp(def: Triple<String, Boolean, Boolean>, size: Float): Box {
            var lower: Box? = null
            var upper: Box? = null
            val ss = scriptSize(size)
            while (true) {
                val t = peek()
                val w = (t as? Tok.Word)?.s?.lowercase()
                when {
                    w == "from" || t == Tok.Sub -> { i++; lower = parsePrimary(ss) }
                    (w == "to" && lower != null) || t == Tok.Sup -> { i++; upper = parsePrimary(ss) }
                    else -> break
                }
            }
            return BigOpBox(def.first, lower, upper, size, color, def.second, def.third)
        }
    }
}
