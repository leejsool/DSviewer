package com.dsviewer.app.hwp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.pdf.PdfDocument
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * [HDoc] → PDF.
 *
 * 한글이 저장해 둔 줄 배치 정보(lineseg: 줄마다 시작 글자 위치, 세로 위치, 높이, 기준선, 폭)를 그대로 따른다.
 * 그래서 글꼴이 달라도 줄 바꿈 위치와 쪽 나눔은 한글과 같고, 각 줄의 글자 폭만 양쪽 정렬로 맞춘다.
 * 줄 정보가 없는 문단은 직접 줄을 나눈다.
 */
class HRenderer(private val doc: HDoc) {

    private val fonts = HFonts(doc)

    // ---- 출력 상태 ----
    private var pdf: PdfDocument? = null
    private var page: PdfDocument.Page? = null
    private var canvas: Canvas? = null
    private var dry = false
    private var pageCount = 0
    private var totalPages = 0
    private var pageNumber = 1

    // ---- 쪽 설정 ----
    private var pd = PageDef()
    private var pageW = 595f
    private var pageH = 842f
    private var bodyLeft = 0f
    private var bodyTop = 0f
    private var bodyWidth = 0f
    private var bodyHeight = 0f

    // ---- 쪽 나눔 판단 ----
    private var lastVert = Int.MIN_VALUE
    private var lastVertSize = 0
    private var lastBottomVert = 0
    private var pageHasContent = false

    // ---- 머리말/꼬리말/쪽 번호 ----
    private var headers = HashMap<String, HCtrl.Header>()
    private var footers = HashMap<String, HCtrl.Footer>()
    private var pageNumCtrl: HCtrl.PageNum? = null
    private var hideHeader = false
    private var hideFooter = false
    private var hidePageNum = false

    private val numCounters = HashMap<Int, IntArray>()

    // ---- 각주/미주 ----
    private var footShape = NoteShape()
    private var endShape = NoteShape()
    private val pageFootnotes = LinkedHashSet<HCtrl.Note>()
    private val endnotes = ArrayList<HCtrl.Note>()
    private val endnoteSeen = HashSet<HCtrl.Note>()
    /** 지금 그리는 주석 (주석 안의 번호 표시용) */
    private var currentNote: HCtrl.Note? = null

    // ---- 그리기 도구 ----
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)

    fun render(out: File) {
        val needTotal = doc.sections.any { s -> s.paras.any { hasTotalPage(it) } }
        if (needTotal) {
            dry = true
            runAll()
            totalPages = pageCount
        }
        dry = false
        val doc = PdfDocument()
        pdf = doc
        try {
            runAll()
            FileOutputStream(out).use { doc.writeTo(it) }
        } finally {
            doc.close()
            pdf = null
        }
    }

    private fun hasTotalPage(p: HPara): Boolean = p.items.any { item ->
        when (item) {
            is PItem.Ctrl -> when (val c = item.ctrl) {
                is HCtrl.AutoNum -> c.numType == "TOTAL_PAGE"
                is HCtrl.Header -> c.paras.any { hasTotalPage(it) }
                is HCtrl.Footer -> c.paras.any { hasTotalPage(it) }
                else -> false
            }
            else -> false
        }
    }

    private fun runAll() {
        pageCount = 0
        pageNumber = 1
        headers.clear()
        footers.clear()
        pageNumCtrl = null
        numCounters.clear()
        pageFootnotes.clear()
        endnotes.clear()
        endnoteSeen.clear()
        currentNote = null
        for ((k, s) in doc.sections.withIndex()) renderSection(s, k == doc.sections.size - 1)
    }

    private fun u(h: Int): Float = h / 100f
    private fun h(pt: Float): Int = (pt * 100f).roundToInt()

    private fun paraShape(id: Int) = doc.paraShapes[id] ?: DEFAULT_PARA

    // ================= 쪽 =================

    private fun renderSection(s: HSection, last: Boolean) {
        pd = s.page
        footShape = s.footShape
        endShape = s.endShape
        pageW = u(pd.width)
        pageH = u(pd.height)
        bodyLeft = u(pd.left + pd.gutter)
        bodyTop = u(pd.top + pd.header)
        bodyWidth = u(pd.width - pd.left - pd.right - pd.gutter)
        bodyHeight = u(pd.height - pd.top - pd.bottom - pd.header - pd.footer)
        startPage()
        for (para in s.paras) renderBodyPara(para)
        if (last) renderEndnotes()
        finishPage()
    }

    private fun startPage() {
        pageCount++
        if (!dry) {
            val info = PdfDocument.PageInfo.Builder(pageW.roundToInt().coerceAtLeast(1), pageH.roundToInt().coerceAtLeast(1), pageCount).create()
            val pg = pdf!!.startPage(info)
            page = pg
            canvas = pg.canvas
        }
        lastVert = Int.MIN_VALUE
        lastVertSize = 0
        lastBottomVert = 0
        pageHasContent = false
        hideHeader = false
        hideFooter = false
        hidePageNum = false
    }

    private fun finishPage() {
        drawFootnotes()
        drawHeaderFooter()
        if (!dry) {
            pdf!!.finishPage(page)
            page = null
            canvas = null
        }
        pageNumber++
    }

    private fun newPage() {
        finishPage()
        startPage()
    }

    private fun applyCtrl(c: HCtrl) {
        when (c) {
            is HCtrl.Header -> headers[c.applyPage] = c
            is HCtrl.Footer -> footers[c.applyPage] = c
            is HCtrl.PageNum -> pageNumCtrl = c
            is HCtrl.NewNum -> if (c.numType == "PAGE") pageNumber = c.num
            is HCtrl.PageHide -> {
                hideHeader = hideHeader || c.header
                hideFooter = hideFooter || c.footer
                hidePageNum = hidePageNum || c.pageNum
            }
            else -> {}
        }
    }

    private fun drawHeaderFooter() {
        val odd = pageNumber % 2 == 1
        val kind = if (odd) "ODD" else "EVEN"
        if (!hideHeader) {
            (headers[kind] ?: headers["BOTH"])?.let { hd ->
                drawParaBlock(hd.paras, bodyLeft, u(pd.top), bodyWidth, "TOP", u(pd.header))
            }
        }
        if (!hideFooter) {
            (footers[kind] ?: footers["BOTH"])?.let { ft ->
                drawParaBlock(ft.paras, bodyLeft, pageH - u(pd.bottom) - u(pd.footer), bodyWidth, "TOP", u(pd.footer))
            }
        }
        val pn = pageNumCtrl
        if (pn != null && !hidePageNum && pn.pos != "NONE") {
            val num = formatNumber(pageNumber, pn.format)
            val text = if (pn.sideChar.isNotEmpty() && pn.sideChar != "\u0000") "${pn.sideChar} $num ${pn.sideChar}" else num
            val paint = fonts.paint(0, Lang.LATIN)
            val p = Paint(paint).apply { textSize = 10f; isFakeBoldText = false; textScaleX = 1f; letterSpacing = 0f; color = Color.BLACK }
            val w = p.measureText(text)
            val top = pn.pos.startsWith("TOP")
            val y = if (top) u(pd.top) + 10f else pageH - u(pd.bottom) - u(pd.footer) + 10f
            val horiz = when {
                pn.pos.endsWith("LEFT") -> "L"
                pn.pos.endsWith("RIGHT") -> "R"
                pn.pos.startsWith("OUTSIDE") -> if (odd) "R" else "L"
                pn.pos.startsWith("INSIDE") -> if (odd) "L" else "R"
                else -> "C"
            }
            val x = when (horiz) {
                "L" -> bodyLeft
                "R" -> bodyLeft + bodyWidth - w
                else -> bodyLeft + (bodyWidth - w) / 2f
            }
            canvas?.drawText(text, x, y, p)
        }
    }

    // ================= 각주 / 미주 =================

    private fun noteLineLength(shape: NoteShape): Float =
        if (shape.lineLength < 0) 5f * 72f / 2.54f else min(u(shape.lineLength), bodyWidth)

    /** 이 쪽의 각주를 본문 영역 맨 아래에 그린다 */
    private fun drawFootnotes() {
        if (pageFootnotes.isEmpty()) return
        val notes = pageFootnotes.toList()
        pageFootnotes.clear()
        val shape = footShape
        val heights = notes.map { n ->
            currentNote = n
            drawParaBlock(n.paras, bodyLeft, 0f, bodyWidth, measureOnly = true)
        }
        val block = u(shape.above) + u(shape.below) + heights.sum() + u(shape.between) * (notes.size - 1)
        var y = bodyTop + bodyHeight - block
        val lineY = y + u(shape.above)
        if (shape.lineVisible) canvas?.let { c ->
            linePaint.color = shape.lineColor
            linePaint.strokeWidth = max(0.3f, shape.lineWidth)
            linePaint.pathEffect = null
            c.drawLine(bodyLeft, lineY, bodyLeft + noteLineLength(shape), lineY, linePaint)
        }
        y = lineY + u(shape.below)
        for ((k, n) in notes.withIndex()) {
            currentNote = n
            drawParaBlock(n.paras, bodyLeft, y, bodyWidth)
            y += heights[k] + u(shape.between)
        }
        currentNote = null
    }

    /** 문서 끝에 미주를 이어서 그린다 (쪽이 넘치면 다음 쪽으로) */
    private fun renderEndnotes() {
        if (endnotes.isEmpty()) return
        val notes = endnotes.toList()
        val shape = endShape
        val bottom = bodyTop + bodyHeight
        // 본문 마지막 줄 바로 뒤에서 시작 (자리가 없으면 다음 쪽)
        var y = (if (pageHasContent) bodyTop + u(lastBottomVert) else bodyTop) + u(shape.above)
        if (y + 40f > bottom) {
            newPage()
            y = bodyTop + u(shape.above)
        }
        if (shape.lineVisible) canvas?.let { c ->
            linePaint.color = shape.lineColor
            linePaint.strokeWidth = max(0.3f, shape.lineWidth)
            linePaint.pathEffect = null
            c.drawLine(bodyLeft, y, bodyLeft + noteLineLength(shape), y, linePaint)
        }
        y += u(shape.below)
        pageHasContent = true
        for (n in notes) {
            currentNote = n
            var base = y
            var cursor = 0
            var contentBottom = y
            var prevVert = -1
            var ownBreak = false
            for (p in n.paras) {
                val ps = paraShape(p.paraShapeId)
                for (item in p.items) if (item is PItem.Ctrl) applyCtrl(item.ctrl)
                val segs = if (p.lineSegs.isNotEmpty() && segsValid(p, p.lineSegs)) p.lineSegs
                else layoutLines(p, ps, h(bodyWidth), cursor + ps.prev)
                val prefix = headingPrefix(ps, p)
                for (i in segs.indices) {
                    val seg = segs[i]
                    if (prevVert >= 0 && seg.vertPos < prevVert) {
                        // 한글이 이 줄에서 다음 쪽으로 넘겼다 (줄 위치가 0부터 다시 시작)
                        if (ownBreak) {
                            base = contentBottom - u(seg.vertPos)
                        } else {
                            newPage()
                            pageHasContent = true
                            base = bodyTop - u(seg.vertPos)
                        }
                        ownBreak = false
                    }
                    prevVert = seg.vertPos
                    var top = base + u(seg.vertPos)
                    if (top + u(seg.vertSize) > bottom + 2f && top > bodyTop + 1f) {
                        newPage()
                        pageHasContent = true
                        base = bodyTop - u(seg.vertPos)
                        top = bodyTop
                        ownBreak = true
                    }
                    if (i == 0) drawFloating(p, top, bodyLeft, bodyWidth, behind = true)
                    drawSegment(p, ps, segs, i, bodyLeft, top, if (i == 0) prefix else null, allowSplit = false)
                    if (i == 0) drawFloating(p, top, bodyLeft, bodyWidth, behind = false)
                    contentBottom = top + u(seg.vertSize + seg.spacing)
                }
                segs.lastOrNull()?.let { cursor = max(cursor, it.vertPos + it.vertSize + it.spacing + ps.next) }
            }
            y = contentBottom + u(shape.between)
        }
        currentNote = null
    }

    // ================= 본문 문단 =================

    private fun renderBodyPara(para: HPara) {
        val ps = paraShape(para.paraShapeId)
        for (item in para.items) if (item is PItem.Ctrl) applyCtrl(item.ctrl)
        if (para.pageBreak && pageHasContent) newPage()

        var segs = para.lineSegs
        val fallback = segs.isEmpty() || !segsValid(para, segs)
        if (fallback) {
            val start = if (pageHasContent) lastBottomVert else 0
            segs = layoutLines(para, ps, h(bodyWidth), start + if (pageHasContent) ps.prev else 0)
        }
        val prefix = headingPrefix(ps, para)
        var anchorTop = Float.NaN
        var shift = 0
        val bodyH = h(bodyHeight)

        for ((i, seg) in segs.withIndex()) {
            var vp = seg.vertPos + shift
            if (fallback) {
                if (pageHasContent && vp + seg.vertSize > bodyH) {
                    newPage()
                    shift = -seg.vertPos
                    vp = 0
                }
            } else if (pageHasContent && (vp < lastVert || (i == 0 && vp == lastVert && lastVertSize > 0))) {
                // 앞 줄보다 위(또는 앞 문단과 같은 위치)에서 시작하면 한글이 다음 쪽으로 넘긴 것
                newPage()
            }
            lastVertSize = seg.vertSize
            lastVert = vp
            val top = bodyTop + u(vp)
            if (i == 0) {
                anchorTop = top
                drawFloating(para, top, bodyLeft, bodyWidth, behind = true)
            }
            drawSegment(para, ps, segs, i, bodyLeft, top, if (i == 0) prefix else null, allowSplit = true)
            pageHasContent = true
            lastBottomVert = max(lastBottomVert, vp + seg.vertSize + seg.spacing)
        }
        if (fallback) lastBottomVert += ps.next
        if (!anchorTop.isNaN()) drawFloating(para, anchorTop, bodyLeft, bodyWidth, behind = false)
    }

    /** 저장된 줄 정보가 이 문단의 글자 위치와 맞는지 */
    private fun segsValid(para: HPara, segs: List<LineSeg>): Boolean {
        val len = para.textLength
        var prev = -1
        for (s in segs) {
            if (s.textPos < prev || s.textPos > len + 8) return false
            prev = s.textPos
        }
        return true
    }

    // ================= 문단 묶음 (셀, 머리말, 글상자) =================

    /** 문단들을 (x, y) 에서 폭 width 로 그린다. 반환값: 내용 높이(pt) */
    private fun drawParaBlock(
        paras: List<HPara>, x: Float, y: Float, width: Float,
        vertAlign: String = "TOP", boxHeight: Float = 0f, measureOnly: Boolean = false,
    ): Float {
        val segLists = ArrayList<List<LineSeg>>(paras.size)
        var cursor = 0
        for (p in paras) {
            val ps = paraShape(p.paraShapeId)
            val segs = if (p.lineSegs.isNotEmpty() && segsValid(p, p.lineSegs)) p.lineSegs
            else layoutLines(p, ps, h(width), cursor + ps.prev)
            segLists.add(segs)
            segs.lastOrNull()?.let { cursor = max(cursor, it.vertPos + it.vertSize + it.spacing + ps.next) }
        }
        var contentH = 0
        for (segs in segLists) for (s in segs) contentH = max(contentH, s.vertPos + s.vertSize)
        val contentPt = u(contentH)
        if (measureOnly) return contentPt
        val dy = when (vertAlign) {
            "CENTER" -> max(0f, (boxHeight - contentPt) / 2f)
            "BOTTOM" -> max(0f, boxHeight - contentPt)
            else -> 0f
        }
        for ((k, p) in paras.withIndex()) {
            val ps = paraShape(p.paraShapeId)
            for (item in p.items) if (item is PItem.Ctrl) applyCtrl(item.ctrl)
            val segs = segLists[k]
            val prefix = headingPrefix(ps, p)
            var anchor = Float.NaN
            for (i in segs.indices) {
                val top = y + dy + u(segs[i].vertPos)
                if (i == 0) {
                    anchor = top
                    drawFloating(p, top, x, width, behind = true)
                }
                drawSegment(p, ps, segs, i, x, top, if (i == 0) prefix else null, allowSplit = false)
            }
            if (!anchor.isNaN()) drawFloating(p, anchor, x, width, behind = false)
        }
        return contentPt
    }

    // ================= 줄 =================

    private class Piece(
        val pos: Int,
        val kind: Int,
        val text: String? = null,
        val paint: Paint? = null,
        val csId: Int = 0,
        val lang: Int = 0,
        val obj: HObject? = null,
        var width: Float = 0f,
        val space: Boolean = false,
        val lineBreak: Boolean = false,
        val leader: String? = null,
        /** 기준선 위로 올리는 양 (각주 번호 등) */
        val rise: Float = 0f,
        /** 이 조각이 가리키는 각주/미주 */
        val note: HCtrl.Note? = null,
    ) {
        var x = 0f

        companion object {
            const val CHAR = 0
            const val OBJ = 1
            const val TAB = 2
            const val BREAK = 3
        }
    }

    private fun charPieces(text: String, csId: Int, startPos: Int, out: MutableList<Piece>) {
        var i = 0
        while (i < text.length) {
            val c = text[i]
            val n = if (Character.isHighSurrogate(c) && i + 1 < text.length) 2 else 1
            val pos = startPos + i
            when (c) {
                '\n' -> out.add(Piece(pos, Piece.BREAK, lineBreak = true))
                '\r', '­' -> {}
                else -> {
                    val isSpace = c == ' ' || c == ' ' || c == ' ' || c == '　'
                    val lang = if (isSpace) Lang.LATIN else Lang.of(c)
                    val paint = fonts.paint(csId, lang)
                    val s = when (c) {
                        ' ' -> " "
                        ' ' -> " "
                        else -> text.substring(i, i + n)
                    }
                    out.add(Piece(pos, Piece.CHAR, s, paint, csId, lang, width = paint.measureText(s), space = isSpace))
                }
            }
            i += n
        }
    }

    /** 글자 위치 [from, to) 범위의 조각들 */
    private fun buildPieces(para: HPara, from: Int, to: Int): MutableList<Piece> {
        val out = ArrayList<Piece>()
        for (item in para.items) {
            val start = item.pos
            val end = item.pos + item.len
            if (end <= from || start >= to) continue
            when (item) {
                is PItem.Text -> {
                    val a = max(from, start) - start
                    val b = min(to, end) - start
                    charPieces(item.text.substring(a, b), item.charShapeId, start + a, out)
                }
                is PItem.Tab -> out.add(Piece(start, Piece.TAB, width = u(item.width), leader = item.leader, csId = item.charShapeId))
                is PItem.Obj -> if (item.obj.treatAsChar) {
                    val o = item.obj
                    if (o is HEquation && (o.width <= 0 || o.height <= 0)) {
                        // 크기 정보가 없는 수식은 직접 잰 크기를 쓴다
                        val (w, hh) = EqRenderer.measure(o)
                        o.width = h(w); o.height = h(hh)
                    }
                    out.add(Piece(start, Piece.OBJ, obj = o, width = u(o.width + o.outLeft + o.outRight)))
                }
                is PItem.Ctrl -> {
                    when (val c = item.ctrl) {
                        is HCtrl.AutoNum -> {
                            val text = when (c.numType) {
                                "TOTAL_PAGE" -> formatNumber(totalPages, c.format)
                                "FOOTNOTE", "ENDNOTE" -> currentNote?.let { n ->
                                    c.prefix + formatNumber(n.number, c.format) + c.suffix.ifEmpty { n.suffix }
                                } ?: ""
                                else -> formatNumber(pageNumber, c.format)
                            }
                            charPieces(text, item.charShapeId, start, out)
                        }
                        is HCtrl.Note -> {
                            // 본문의 각주/미주 표시: 작은 글자로 위첨자
                            val shape = if (c.endNote) endShape else footShape
                            val fmt = c.format.ifEmpty { shape.format }
                            val text = c.prefix.ifEmpty { shape.prefix } + formatNumber(c.number, fmt) + c.suffix.ifEmpty { shape.suffix }
                            val base = fonts.paint(item.charShapeId, Lang.LATIN)
                            val p = Paint(base).apply { textSize = base.textSize * 0.6f; letterSpacing = 0f }
                            text.forEachIndexed { k, ch ->
                                val s = ch.toString()
                                out.add(
                                    Piece(start, Piece.CHAR, s, p, item.charShapeId, Lang.LATIN, width = p.measureText(s),
                                        rise = base.textSize * 0.38f, note = if (k == 0) c else null)
                                )
                            }
                        }
                        else -> {}
                    }
                }
            }
        }
        return out
    }

    private fun firstCharShape(para: HPara): Int =
        para.items.firstOrNull { it is PItem.Text }?.let { (it as PItem.Text).charShapeId }
            ?: para.items.firstOrNull()?.let {
                when (it) {
                    is PItem.Text -> it.charShapeId
                    is PItem.Tab -> it.charShapeId
                    is PItem.Obj -> it.charShapeId
                    is PItem.Ctrl -> it.charShapeId
                }
            } ?: 0

    private fun drawSegment(
        para: HPara, ps: ParaShape, segs: List<LineSeg>, i: Int,
        containerX: Float, lineTop: Float, prefix: String?, allowSplit: Boolean,
    ) {
        val seg = segs[i]
        val to = if (i + 1 < segs.size) segs[i + 1].textPos else Int.MAX_VALUE
        val pieces = buildPieces(para, seg.textPos, to)
        if (prefix != null) {
            val pre = ArrayList<Piece>()
            charPieces(prefix, firstCharShape(para), -1, pre)
            pieces.addAll(0, pre)
        }
        if (pieces.isEmpty()) return

        // 줄 정보의 horzPos/horzSize 는 문단 왼쪽/오른쪽 여백이 이미 반영된 값. 들여쓰기만 더한다
        val firstLine = i == 0
        val indent = if (firstLine) max(ps.indent, 0) else max(-ps.indent, 0)
        val x0 = containerX + u(seg.horzPos + indent)
        var avail = u(seg.horzSize - indent)
        if (avail <= 1f) avail = u(seg.horzSize)
        val baseline = lineTop + u(seg.baseline)

        // 뒤쪽 공백/줄바꿈은 정렬 폭에서 제외
        var lastVisible = pieces.size - 1
        while (lastVisible >= 0 && (pieces[lastVisible].space || pieces[lastVisible].kind == Piece.BREAK)) lastVisible--
        var natural = 0f
        var spaces = 0
        var chars = 0
        for (k in 0..lastVisible) {
            val pc = pieces[k]
            natural += pc.width
            if (pc.space) spaces++
            if (pc.kind == Piece.CHAR || pc.kind == Piece.OBJ) chars++
        }
        val endsWithBreak = pieces.any { it.lineBreak }
        val isLast = i == segs.size - 1 || endsWithBreak
        val extra = avail - natural

        var perSpace = 0f
        var perChar = 0f
        var start = x0
        val gaps = max(1, chars - 1)
        if (extra < -0.01f) {
            // 한글보다 글꼴이 넓어 넘치면 글자 간격을 줄여 맞춘다
            perChar = extra / gaps
        } else {
            when (ps.align) {
                Align.JUSTIFY -> if (!isLast && extra < avail * 0.35f) {
                    if (spaces > 0) perSpace = extra / spaces else perChar = extra / gaps
                }
                Align.DISTRIBUTE -> perChar = extra / gaps
                Align.DISTRIBUTE_SPACE -> if (spaces > 0) perSpace = extra / spaces else perChar = extra / gaps
                Align.RIGHT -> start = x0 + extra
                Align.CENTER -> start = x0 + extra / 2f
                Align.LEFT -> {}
            }
        }

        var x = start
        for ((k, pc) in pieces.withIndex()) {
            pc.x = x
            var adv = pc.width
            if (k <= lastVisible) {
                if (pc.space) adv += perSpace
                if (pc.kind == Piece.CHAR || pc.kind == Piece.OBJ) {
                    if (k < lastVisible) adv += perChar
                }
            }
            pc.width = adv
            x += adv
        }

        val c = canvas
        // 1) 음영
        if (c != null) for (pc in pieces) {
            if (pc.kind != Piece.CHAR) continue
            val cs = fonts.charShape(pc.csId)
            val shade = cs.shadeColor ?: continue
            val size = pc.paint!!.textSize
            fillPaint.color = shade
            c.drawRect(pc.x, baseline - size * 0.88f, pc.x + pc.width, baseline + size * 0.2f, fillPaint)
        }
        // 2) 글자, 개체, 밑줄
        for (pc in pieces) {
            pc.note?.let { n ->
                if (!n.endNote) pageFootnotes.add(n)
                else if (endnoteSeen.add(n)) endnotes.add(n)
            }
            when (pc.kind) {
                Piece.CHAR -> if (c != null && !pc.space) {
                    val shift = fonts.baselineShift(pc.csId, pc.lang) + pc.rise
                    c.drawText(pc.text!!, pc.x, baseline - shift, pc.paint!!)
                }
                Piece.OBJ -> {
                    val o = pc.obj!!
                    val totalH = u(o.height + o.outTop + o.outBottom)
                    // 수식은 자기 기준선을 줄 기준선에 맞춘다
                    val objTop = if (o is HEquation) baseline - u(o.outTop) - u(o.height) * o.baseLine / 100f
                    else max(lineTop, baseline - totalH * 0.85f)
                    drawObject(o, pc.x + u(o.outLeft), objTop + u(o.outTop), allowSplit)
                }
                Piece.TAB -> if (c != null && pc.leader != null && pc.leader != "NONE" && pc.width > 4f) {
                    val p = fonts.paint(pc.csId, Lang.LATIN)
                    val dot = p.measureText(".")
                    if (dot > 0f) {
                        var dx = pc.x + dot
                        while (dx + dot < pc.x + pc.width - dot) {
                            c.drawText(".", dx, baseline, p)
                            dx += dot * 1.5f
                        }
                    }
                }
            }
        }
        if (c != null) for (pc in pieces) {
            if (pc.kind != Piece.CHAR) continue
            val cs = fonts.charShape(pc.csId)
            val size = pc.paint!!.textSize
            if (cs.underline != null) {
                linePaint.color = cs.underlineColor
                linePaint.strokeWidth = max(0.5f, size * 0.05f)
                linePaint.pathEffect = null
                val uy = when (cs.underline) {
                    "TOP" -> baseline - size * 0.9f
                    "CENTER" -> baseline - size * 0.32f
                    else -> baseline + size * 0.12f
                }
                c.drawLine(pc.x, uy, pc.x + pc.width, uy, linePaint)
            }
            if (cs.strike) {
                linePaint.color = cs.strikeColor
                linePaint.strokeWidth = max(0.5f, size * 0.05f)
                linePaint.pathEffect = null
                val sy = baseline - size * 0.32f
                c.drawLine(pc.x, sy, pc.x + pc.width, sy, linePaint)
            }
        }
    }

    // ================= 직접 줄 나누기 (줄 정보가 없을 때) =================

    private fun layoutLines(para: HPara, ps: ParaShape, widthHwp: Int, startVert: Int): List<LineSeg> {
        val pieces = buildPieces(para, 0, Int.MAX_VALUE)
        val result = ArrayList<LineSeg>()
        var vert = startVert
        val defaultH = fonts.charShape(firstCharShape(para)).height

        fun lineHeight(from: Int, until: Int): Int {
            var mh = 0
            for (k in from until until) {
                val pc = pieces[k]
                val hh = when (pc.kind) {
                    Piece.CHAR -> h(pc.paint!!.textSize)
                    Piece.OBJ -> pc.obj!!.height + pc.obj.outTop + pc.obj.outBottom
                    else -> 0
                }
                mh = max(mh, hh)
            }
            return if (mh == 0) defaultH else mh
        }

        fun addLine(from: Int, until: Int) {
            val hh = lineHeight(from, until)
            val spacing = when (ps.lineSpacingType) {
                "FIXED" -> max(0, ps.lineSpacing - hh)
                "BETWEEN_LINES" -> ps.lineSpacing
                "AT_LEAST" -> max(0, ps.lineSpacing - hh)
                else -> hh * (ps.lineSpacing - 100) / 100
            }
            val textPos = if (from < pieces.size) max(0, pieces[from].pos) else para.textLength
            result.add(LineSeg(textPos, vert, hh, hh, (hh * 0.85f).roundToInt(), spacing, ps.left, widthHwp - ps.left - ps.right, 0))
            vert += hh + spacing
        }

        if (pieces.isEmpty()) {
            addLine(0, 0)
            return result
        }
        var lineStart = 0
        var w = 0f
        var lastBreak = -1
        var k = 0
        while (k < pieces.size) {
            val pc = pieces[k]
            val indent = if (result.isEmpty()) max(ps.indent, 0) else max(-ps.indent, 0)
            val avail = u(widthHwp - ps.left - ps.right - indent)
            if (pc.kind == Piece.BREAK) {
                addLine(lineStart, k + 1)
                lineStart = k + 1; w = 0f; lastBreak = -1; k++
                continue
            }
            if (w + pc.width > avail && k > lineStart && !pc.space) {
                val cut = if (lastBreak > lineStart) lastBreak else k
                addLine(lineStart, cut)
                lineStart = cut
                w = 0f
                for (j in lineStart until k) w += pieces[j].width
                lastBreak = -1
            }
            w += pc.width
            if (pc.space) lastBreak = k + 1
            k++
        }
        if (lineStart < pieces.size || result.isEmpty()) addLine(lineStart, pieces.size)
        return result
    }

    // ================= 문단 번호 =================

    private fun headingPrefix(ps: ParaShape, para: HPara): String? {
        when (ps.headingType) {
            "BULLET" -> return (doc.bullets[ps.headingIdRef]?.char ?: "•") + " "
            "NUMBER", "OUTLINE" -> {
                val id = if (ps.headingType == "OUTLINE" && ps.headingIdRef == 0) 1 else ps.headingIdRef
                val num = doc.numberings[id] ?: return null
                val level = ps.headingLevel.coerceIn(0, 9)
                val counters = numCounters.getOrPut(id) { IntArray(10) }
                counters[level] = if (counters[level] == 0) num.starts[level] else counters[level] + 1
                for (l in level + 1 until 10) counters[l] = 0
                val fmt = num.formats[level] ?: return null
                val sb = StringBuilder()
                var j = 0
                while (j < fmt.length) {
                    val ch = fmt[j]
                    if (ch == '^' && j + 1 < fmt.length && fmt[j + 1].isDigit()) {
                        var k = j + 1
                        while (k < fmt.length && fmt[k].isDigit()) k++
                        val lv = (fmt.substring(j + 1, k).toIntOrNull() ?: 1).coerceIn(1, 10) - 1
                        val v = if (counters[lv] == 0) num.starts[lv] else counters[lv]
                        sb.append(formatNumber(v, num.numTypes[lv] ?: "DIGIT"))
                        j = k
                    } else {
                        sb.append(ch); j++
                    }
                }
                if (sb.isEmpty()) return null
                return "$sb "
            }
        }
        return null
    }

    // ================= 개체 =================

    private fun drawFloating(para: HPara, anchorTop: Float, cx: Float, cw: Float, behind: Boolean) {
        for (item in para.items) {
            if (item !is PItem.Obj) continue
            val o = item.obj
            if (o.treatAsChar) continue
            if ((o.textWrap == "BEHIND_TEXT") != behind) continue
            val w = u(o.width)
            val hh = u(o.height)
            val (bx, bw) = when (o.horzRelTo) {
                "PAPER" -> 0f to pageW
                "PAGE" -> bodyLeft to bodyWidth
                else -> cx to cw
            }
            val x = when (o.horzAlign) {
                "CENTER" -> bx + (bw - w) / 2f + u(o.horzOffset)
                "RIGHT", "OUTSIDE" -> bx + bw - w - u(o.horzOffset)
                else -> bx + u(o.horzOffset)
            }
            val y = when (o.vertRelTo) {
                "PAPER" -> when (o.vertAlign) {
                    "CENTER" -> (pageH - hh) / 2f + u(o.vertOffset)
                    "BOTTOM" -> pageH - hh - u(o.vertOffset)
                    else -> u(o.vertOffset)
                }
                "PAGE" -> when (o.vertAlign) {
                    "CENTER" -> bodyTop + (bodyHeight - hh) / 2f + u(o.vertOffset)
                    "BOTTOM" -> bodyTop + bodyHeight - hh - u(o.vertOffset)
                    else -> bodyTop + u(o.vertOffset)
                }
                else -> anchorTop + u(o.vertOffset)
            }
            drawObject(o, x, y, allowSplit = false)
        }
    }

    private fun drawObject(o: HObject, x: Float, y: Float, allowSplit: Boolean) {
        when (o) {
            is HTable -> drawTable(o, x, y, allowSplit)
            is HPicture -> drawPicture(o, RectF(x, y, x + u(o.width), y + u(o.height)))
            is HShape -> drawShape(o, RectF(x, y, x + u(o.width), y + u(o.height)))
            is HEquation -> canvas?.let { EqRenderer.draw(it, o, RectF(x, y, x + u(o.width), y + u(o.height))) }
        }
    }

    // ---- 표 ----

    private fun boundaries(n: Int, spans: List<Triple<Int, Int, Int>>): IntArray {
        // spans: (시작, 칸 수, 길이)
        val b = IntArray(n + 1) { -1 }
        b[0] = 0
        var changed = true
        var guard = 0
        while (changed && guard++ < 50) {
            changed = false
            for ((s, span, len) in spans.sortedBy { it.second }) {
                val e = (s + span).coerceAtMost(n)
                if (s > n) continue
                if (b[s] >= 0 && b[e] < 0) { b[e] = b[s] + len; changed = true }
                else if (b[e] >= 0 && b[s] < 0) { b[s] = b[e] - len; changed = true }
            }
        }
        // 비어 있는 경계는 앞뒤 값으로 보간
        for (i in 1..n) if (b[i] < 0) {
            var j = i + 1
            while (j <= n && b[j] < 0) j++
            val prev = b[i - 1]
            b[i] = if (j <= n) prev + (b[j] - prev) / (j - i + 1) else prev + 1000
        }
        for (i in 1..n) if (b[i] < b[i - 1]) b[i] = b[i - 1]
        return b
    }

    private fun drawTable(t: HTable, x: Float, y: Float, allowSplit: Boolean) {
        if (t.cells.isEmpty()) return
        val cols = max(t.colCnt, t.cells.maxOf { it.col + it.colSpan })
        val rows = max(t.rowCnt, t.cells.maxOf { it.row + it.rowSpan })
        val cb = boundaries(cols, t.cells.map { Triple(it.col, it.colSpan, it.width) })
        val rb = boundaries(rows, t.cells.map { Triple(it.row, it.rowSpan, it.height) })
        // 저장된 셀 높이는 최소 높이: 내용이 더 길면 행을 늘린다 (한글과 같은 동작)
        for (cell in t.cells.sortedBy { it.rowSpan }) {
            if (cell.paras.isEmpty()) continue
            val ce = (cell.col + cell.colSpan).coerceAtMost(cols)
            val re = (cell.row + cell.rowSpan).coerceAtMost(rows)
            val innerW = u(cb[ce] - cb[cell.col] - cell.marginLeft - cell.marginRight)
            if (innerW <= 1f) continue
            val need = h(drawParaBlock(cell.paras, 0f, 0f, innerW, measureOnly = true)) + cell.marginTop + cell.marginBottom
            val have = rb[re] - rb[cell.row]
            if (need > have + 20) {
                val diff = need - have
                for (k in re..rows) rb[k] += diff
            }
        }
        val totalH = u(rb[rows])
        val bottom = bodyTop + bodyHeight

        if (!allowSplit || y + totalH <= bottom + 1f || t.pageBreak == "NONE") {
            drawTableRows(t, cb, rb, 0, rows, x, y, null)
            return
        }
        // 쪽을 넘는 표: 행 단위로 나눠 여러 쪽에 그린다
        val headerRows = if (t.repeatHeader) {
            var hr = 0
            while (hr < rows && t.cells.any { it.row == hr && it.header }) hr++
            hr
        } else 0
        var r0 = 0
        var top = y
        var first = true
        while (r0 < rows) {
            val headerH = if (!first && headerRows > 0) u(rb[headerRows]) else 0f
            val room = bottom - top - headerH
            var r1 = r0 + 1
            while (r1 < rows && u(rb[r1 + 1] - rb[r0]) <= room + 0.5f) r1++
            if (!first && headerRows > 0 && r0 >= headerRows) {
                drawTableRows(t, cb, rb, 0, headerRows, x, top, null)
            }
            drawTableRows(t, cb, rb, r0, r1, x, top + headerH, r0)
            val usedBottom = top + headerH + u(rb[r1] - rb[r0])
            r0 = r1
            first = false
            if (r0 < rows) {
                newPage()
                top = bodyTop
            } else {
                // 표가 끝난 쪽에서, 다음 문단이 표 아래에 이어지도록 기준 위치를 조정
                lastVert = h(usedBottom - bodyTop) - 3000
                pageHasContent = true
            }
        }
    }

    /** 행 [r0, r1) 을 y 위치에 그린다. clipFrom 이 있으면 걸친 셀을 잘라서 그린다 */
    private fun drawTableRows(t: HTable, cb: IntArray, rb: IntArray, r0: Int, r1: Int, x: Float, y: Float, clipFrom: Int?) {
        val c = canvas
        val originY = y - u(rb[r0])
        val cells = t.cells.filter { it.row < r1 && it.row + it.rowSpan > r0 }
        val clip = RectF(x, y, x + u(cb[cb.size - 1]), y + u(rb[r1] - rb[r0]))
        c?.save()
        if (clipFrom != null) c?.clipRect(clip)
        // 배경
        if (c != null) for (cell in cells) {
            val bf = doc.borderFills[cell.borderFillId] ?: continue
            val color = bf.fillColor ?: continue
            fillPaint.color = color
            c.drawRect(cellRect(cell, cb, rb, x, originY), fillPaint)
        }
        // 내용
        for (cell in cells) {
            val r = cellRect(cell, cb, rb, x, originY)
            val innerW = r.width() - u(cell.marginLeft + cell.marginRight)
            val innerH = r.height() - u(cell.marginTop + cell.marginBottom)
            if (cell.paras.isNotEmpty()) {
                c?.save()
                c?.clipRect(r)
                drawParaBlock(cell.paras, r.left + u(cell.marginLeft), r.top + u(cell.marginTop), innerW, cell.vertAlign, innerH)
                c?.restore()
            }
        }
        // 테두리
        if (c != null) for (cell in cells) {
            val bf = doc.borderFills[cell.borderFillId] ?: doc.borderFills[t.borderFillId] ?: continue
            val r = cellRect(cell, cb, rb, x, originY)
            drawBorder(c, r, bf)
        }
        c?.restore()
    }

    private fun cellRect(cell: HCell, cb: IntArray, rb: IntArray, x: Float, originY: Float): RectF {
        val ce = (cell.col + cell.colSpan).coerceAtMost(cb.size - 1)
        val re = (cell.row + cell.rowSpan).coerceAtMost(rb.size - 1)
        return RectF(x + u(cb[cell.col]), originY + u(rb[cell.row]), x + u(cb[ce]), originY + u(rb[re]))
    }

    private fun drawBorder(c: Canvas, r: RectF, bf: BorderFill) {
        drawLine(c, r.left, r.top, r.right, r.top, bf.top)
        drawLine(c, r.left, r.bottom, r.right, r.bottom, bf.bottom)
        drawLine(c, r.left, r.top, r.left, r.bottom, bf.left)
        drawLine(c, r.right, r.top, r.right, r.bottom, bf.right)
        if (bf.slash) drawLine(c, r.left, r.bottom, r.right, r.top, bf.diagonal)
        if (bf.backSlash) drawLine(c, r.left, r.top, r.right, r.bottom, bf.diagonal)
    }

    private fun drawLine(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, b: BorderLine) {
        if (!b.visible) return
        linePaint.color = b.color
        linePaint.strokeCap = Paint.Cap.SQUARE
        val w = max(0.25f, b.width)
        linePaint.pathEffect = when {
            b.type.startsWith("DASH_DOT") -> DashPathEffect(floatArrayOf(w * 6, w * 2, w, w * 2), 0f)
            b.type == "DOT" || b.type == "CIRCLE" -> DashPathEffect(floatArrayOf(w, w * 2), 0f)
            b.type.contains("DASH") -> DashPathEffect(floatArrayOf(w * 6, w * 3), 0f)
            else -> null
        }
        val double = b.type.startsWith("DOUBLE") || b.type.contains("SLIM_THICK") || b.type.contains("THICK_SLIM")
        if (double) {
            linePaint.strokeWidth = max(0.25f, w / 3f)
            val horizontal = abs(y1 - y0) < abs(x1 - x0)
            val off = w / 3f
            if (horizontal) {
                c.drawLine(x0, y0 - off, x1, y1 - off, linePaint)
                c.drawLine(x0, y0 + off, x1, y1 + off, linePaint)
            } else {
                c.drawLine(x0 - off, y0, x1 - off, y1, linePaint)
                c.drawLine(x0 + off, y0, x1 + off, y1, linePaint)
            }
        } else {
            linePaint.strokeWidth = w
            c.drawLine(x0, y0, x1, y1, linePaint)
        }
        linePaint.pathEffect = null
    }

    // ---- 그림 ----

    private fun drawPicture(pic: HPicture, r: RectF) {
        val c = canvas ?: return
        val bmp = decodeImage(pic.binId, r)
        if (bmp != null) {
            val iw = if (pic.imgWidth > 0) pic.imgWidth else pic.orgWidth
            val ih = if (pic.imgHeight > 0) pic.imgHeight else pic.orgHeight
            val src = if (iw > 0 && ih > 0 && pic.clipRight > pic.clipLeft && pic.clipBottom > pic.clipTop) {
                val sx = bmp.width.toFloat() / iw
                val sy = bmp.height.toFloat() / ih
                Rect(
                    (pic.clipLeft * sx).roundToInt().coerceIn(0, bmp.width),
                    (pic.clipTop * sy).roundToInt().coerceIn(0, bmp.height),
                    (pic.clipRight * sx).roundToInt().coerceIn(1, bmp.width),
                    (pic.clipBottom * sy).roundToInt().coerceIn(1, bmp.height),
                )
            } else null
            c.drawBitmap(bmp, src, r, bmpPaint)
            bmp.recycle()
        } else {
            // 표시할 수 없는 그림 형식 (WMF/EMF 등): 빈 자리로 둔다
            android.util.Log.w("HRenderer", "image not decoded: bin=${pic.binId}")
        }
        pic.line?.let { ls -> if (ls.width > 0) strokeRect(c, r, ls) }
    }

    private fun decodeImage(binId: String, r: RectF): Bitmap? {
        val bytes = try { doc.binLoader(binId) } catch (e: Throwable) { null } ?: return null
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        // 약 220dpi 정도면 충분
        val targetW = max(64f, r.width() * 3f)
        var sample = 1
        while (opts.outWidth / (sample * 2) >= targetW) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        return try { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2) } catch (e: OutOfMemoryError) { null }
    }

    private fun strokeRect(c: Canvas, r: RectF, ls: LineStyle) {
        linePaint.color = ls.color
        linePaint.strokeWidth = max(0.25f, u(ls.width))
        linePaint.pathEffect = if (ls.style.contains("DASH") || ls.style.contains("DOT")) DashPathEffect(floatArrayOf(3f, 2f), 0f) else null
        c.drawRect(r, linePaint)
        linePaint.pathEffect = null
    }

    // ---- 도형 ----

    private fun drawShape(s: HShape, r: RectF) {
        val c = canvas
        val sx = if (s.orgWidth > 0) r.width() / u(s.orgWidth) else 1f
        val sy = if (s.orgHeight > 0) r.height() / u(s.orgHeight) else 1f
        when (s.kind) {
            "rect", "ellipse", "polygon" -> if (c != null) {
                s.fillColor?.let { fc ->
                    fillPaint.color = fc
                    drawShapePath(c, s, r, sx, sy, fillPaint)
                }
                s.line?.let { ls ->
                    linePaint.color = ls.color
                    linePaint.strokeWidth = max(0.25f, u(ls.width))
                    linePaint.pathEffect = if (ls.style.contains("DASH") || ls.style.contains("DOT")) DashPathEffect(floatArrayOf(3f, 2f), 0f) else null
                    drawShapePath(c, s, r, sx, sy, linePaint)
                    linePaint.pathEffect = null
                }
            }
            "line", "connectLine" -> if (c != null) {
                s.line?.let { ls ->
                    linePaint.color = ls.color
                    linePaint.strokeWidth = max(0.25f, u(ls.width))
                    linePaint.pathEffect = if (ls.style.contains("DASH") || ls.style.contains("DOT")) DashPathEffect(floatArrayOf(3f, 2f), 0f) else null
                    c.drawLine(r.left + u(s.x0) * sx, r.top + u(s.y0) * sy, r.left + u(s.x1) * sx, r.top + u(s.y1) * sy, linePaint)
                    linePaint.pathEffect = null
                }
            }
            "curve" -> if (c != null && s.points.size >= 2) {
                s.line?.let { ls ->
                    linePaint.color = ls.color
                    linePaint.strokeWidth = max(0.25f, u(ls.width))
                    val dotted = ls.style == "DOT" || ls.style == "CIRCLE"
                    if (dotted) linePaint.strokeWidth = max(0.7f, linePaint.strokeWidth)
                    linePaint.pathEffect = when {
                        dotted -> DashPathEffect(floatArrayOf(0.01f, max(1f, linePaint.strokeWidth * 3f)), 0f)
                        ls.style.contains("DASH") -> DashPathEffect(floatArrayOf(3f, 2f), 0f)
                        else -> null
                    }
                    // 점선은 둥근 끝으로 찍어야 점처럼 보인다
                    linePaint.strokeCap = if (dotted) Paint.Cap.ROUND else Paint.Cap.BUTT
                    c.drawPath(smoothPath(s.points, r, sx, sy), linePaint)
                    linePaint.pathEffect = null
                    linePaint.strokeCap = Paint.Cap.SQUARE
                }
            }
            "container" -> for (child in s.children) {
                // 자식의 행렬은 묶음이 실제로 보이는 크기(cur) 기준 좌표로 바로 옮겨 준다
                val csx = if (s.curWidth > 0) r.width() / u(s.curWidth) else sx
                val csy = if (s.curHeight > 0) r.height() / u(s.curHeight) else sy
                val cr = childRect(child, r, sx, sy, csx, csy)
                when (child) {
                    is HShape -> drawShape(child, cr)
                    is HPicture -> drawPicture(child, cr)
                }
            }
        }
        // 글상자 안 글
        s.drawText?.let { paras ->
            val tx = r.left + u(s.textMarginLeft)
            val ty = r.top + u(s.textMarginTop)
            val tw = r.width() - u(s.textMarginLeft + s.textMarginRight)
            val th = r.height() - u(s.textMarginTop + s.textMarginBottom)
            if (tw > 1f) drawParaBlock(paras, tx, ty, tw, s.textVertAlign, th)
        }
    }

    private fun drawShapePath(c: Canvas, s: HShape, r: RectF, sx: Float, sy: Float, paint: Paint) {
        when (s.kind) {
            "ellipse" -> c.drawOval(r, paint)
            "polygon" -> if (s.points.size >= 2) {
                val path = android.graphics.Path()
                s.points.forEachIndexed { k, (px, py) ->
                    val xx = r.left + u(px) * sx
                    val yy = r.top + u(py) * sy
                    if (k == 0) path.moveTo(xx, yy) else path.lineTo(xx, yy)
                }
                path.close()
                c.drawPath(path, paint)
            }
            else -> if (s.roundRatio > 0) {
                val rad = min(r.width(), r.height()) * s.roundRatio / 200f
                c.drawRoundRect(r, rad, rad, paint)
            } else c.drawRect(r, paint)
        }
    }

    /** 점들을 부드럽게 잇는 곡선 (Catmull-Rom → 3차 베지어) */
    private fun smoothPath(points: List<Pair<Int, Int>>, r: RectF, sx: Float, sy: Float): android.graphics.Path {
        val xs = points.map { r.left + u(it.first) * sx }
        val ys = points.map { r.top + u(it.second) * sy }
        val path = android.graphics.Path()
        path.moveTo(xs[0], ys[0])
        val n = points.size
        for (i in 0 until n - 1) {
            val x0 = xs[max(0, i - 1)]; val y0 = ys[max(0, i - 1)]
            val x1 = xs[i]; val y1 = ys[i]
            val x2 = xs[i + 1]; val y2 = ys[i + 1]
            val x3 = xs[min(n - 1, i + 2)]; val y3 = ys[min(n - 1, i + 2)]
            path.cubicTo(
                x1 + (x2 - x0) / 6f, y1 + (y2 - y0) / 6f,
                x2 - (x3 - x1) / 6f, y2 - (y3 - y1) / 6f,
                x2, y2
            )
        }
        return path
    }

    /**
     * 묶음 도형 안 자식의 실제 위치.
     * 행렬이 있으면 자식 원래 크기 → 묶음의 보이는 크기 좌표 (csx, csy 로 맞춤),
     * 없으면 묶음 원래 크기 기준 오프셋 (sx, sy 로 맞춤).
     */
    private fun childRect(child: HShapeObj, parent: RectF, sx: Float, sy: Float, csx: Float, csy: Float): RectF {
        val w = u(if (child.orgWidth > 0) child.orgWidth else child.width)
        val hh = u(if (child.orgHeight > 0) child.orgHeight else child.height)
        val m = child.matrix
        return if (m != null) {
            val pts = listOf(0f to 0f, w to 0f, 0f to hh, w to hh).map { (px, py) ->
                // 행렬 이동값은 HWPUNIT
                Matrix2D.apply(floatArrayOf(m[0], m[1], u(m[2].roundToInt()), m[3], m[4], u(m[5].roundToInt())), px, py)
            }
            val minX = pts.minOf { it.first }
            val minY = pts.minOf { it.second }
            val maxX = pts.maxOf { it.first }
            val maxY = pts.maxOf { it.second }
            RectF(parent.left + minX * csx, parent.top + minY * csy, parent.left + maxX * csx, parent.top + maxY * csy)
        } else {
            val cw = u(if (child.curWidth > 0) child.curWidth else child.width)
            val ch = u(if (child.curHeight > 0) child.curHeight else child.height)
            RectF(
                parent.left + u(child.offsetX) * sx, parent.top + u(child.offsetY) * sy,
                parent.left + (u(child.offsetX) + cw) * sx, parent.top + (u(child.offsetY) + ch) * sy
            )
        }
    }

    companion object {
        private val DEFAULT_PARA = ParaShape()

        fun formatNumber(n: Int, format: String): String {
            if (n <= 0) return n.toString()
            return when (format) {
                "CIRCLED_DIGIT" -> if (n in 1..20) ('①' + (n - 1)).toString() else n.toString()
                "ROMAN_CAPITAL" -> roman(n)
                "ROMAN_SMALL" -> roman(n).lowercase()
                "LATIN_CAPITAL" -> ('A' + (n - 1) % 26).toString()
                "LATIN_SMALL" -> ('a' + (n - 1) % 26).toString()
                "CIRCLED_LATIN_SMALL" -> if (n in 1..26) ('ⓐ' + (n - 1)).toString() else n.toString()
                "HANGUL_SYLLABLE" -> HANGUL[(n - 1) % HANGUL.length].toString()
                "CIRCLED_HANGUL_SYLLABLE" -> if (n in 1..14) ('㉮' + (n - 1)).toString() else HANGUL[(n - 1) % HANGUL.length].toString()
                "HANGUL_JAMO" -> JAMO[(n - 1) % JAMO.length].toString()
                "CIRCLED_HANGUL_JAMO" -> if (n in 1..14) ('㉠' + (n - 1)).toString() else JAMO[(n - 1) % JAMO.length].toString()
                "IDEOGRAPH", "HANGUL_PHONETIC" -> if (n in 1..10) IDEO[n - 1].toString() else n.toString()
                "CIRCLED_IDEOGRAPH" -> if (n in 1..10) ('㊀' + (n - 1)).toString() else n.toString()
                else -> n.toString()
            }
        }

        private const val HANGUL = "가나다라마바사아자차카타파하"
        private const val JAMO = "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ"
        private const val IDEO = "一二三四五六七八九十"

        private fun roman(num: Int): String {
            var n = num
            val vals = intArrayOf(1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1)
            val syms = arrayOf("M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I")
            val sb = StringBuilder()
            for (i in vals.indices) while (n >= vals[i]) { sb.append(syms[i]); n -= vals[i] }
            return sb.toString()
        }
    }
}
