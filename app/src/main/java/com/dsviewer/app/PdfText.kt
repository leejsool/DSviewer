package com.dsviewer.app

import android.graphics.RectF
import com.tom_roush.pdfbox.contentstream.PDFStreamEngine
import com.tom_roush.pdfbox.contentstream.operator.DrawObject
import com.tom_roush.pdfbox.contentstream.operator.state.Concatenate
import com.tom_roush.pdfbox.contentstream.operator.state.Restore
import com.tom_roush.pdfbox.contentstream.operator.state.Save
import com.tom_roush.pdfbox.contentstream.operator.state.SetGraphicsStateParameters
import com.tom_roush.pdfbox.contentstream.operator.state.SetMatrix
import com.tom_roush.pdfbox.contentstream.operator.text.BeginText
import com.tom_roush.pdfbox.contentstream.operator.text.EndText
import com.tom_roush.pdfbox.contentstream.operator.text.MoveText
import com.tom_roush.pdfbox.contentstream.operator.text.MoveTextSetLeading
import com.tom_roush.pdfbox.contentstream.operator.text.NextLine
import com.tom_roush.pdfbox.contentstream.operator.text.SetCharSpacing
import com.tom_roush.pdfbox.contentstream.operator.text.SetFontAndSize
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextHorizontalScaling
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextLeading
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextRenderingMode
import com.tom_roush.pdfbox.contentstream.operator.text.SetTextRise
import com.tom_roush.pdfbox.contentstream.operator.text.SetWordSpacing
import com.tom_roush.pdfbox.contentstream.operator.text.ShowText
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextAdjusted
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextLine
import com.tom_roush.pdfbox.contentstream.operator.text.ShowTextLineAndSpace
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.font.PDFont
import com.tom_roush.pdfbox.util.Matrix
import com.tom_roush.pdfbox.util.Vector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min

/**
 * 한 쪽의 글자: 띄어쓰기·줄바꿈을 뺀 소문자 글자들과, 글자마다 쪽 좌표의 상자 (왼, 위, 오른, 아래).
 * [raw]는 같은 글자들의 원래 대소문자 (글자 복사용). 띄어쓰기를 빼고 찾으므로 'f(x) 의 값'으로 찾아도 'f(x)의 값'이 나온다 (시험지 PDF는 띄어쓰기가 들쭉날쭉해서)
 */
class PageText(val chars: String, val boxes: FloatArray, val raw: String = chars)

/** PDF에서 글자와 자리를 꺼내고 찾는다 (PDFBox) */
object PdfText {

    /** PDF 사용자 좌표 → 화면에 그린 쪽 좌표 (왼쪽 위가 원점, 쪽 돌림 [rot] 반영). [out]의 [at], [at]+1에 넣는다 */
    fun toPage(crop: PDRectangle, rot: Int, x: Float, y: Float, out: FloatArray, at: Int) {
        val (px, py) = when (rot) {
            90 -> (y - crop.lowerLeftY) to (x - crop.lowerLeftX)
            180 -> (crop.upperRightX - x) to (y - crop.lowerLeftY)
            270 -> (crop.upperRightY - y) to (crop.upperRightX - x)
            else -> (x - crop.lowerLeftX) to (crop.upperRightY - y)
        }
        out[at] = px
        out[at + 1] = py
    }

    /** 찾을 말을 글자 목록과 같은 모양으로 (띄어쓰기 빼고 소문자) */
    fun normalize(s: String) = buildString { for (c in s) if (!c.isWhitespace()) append(c.lowercaseChar()) }

    /**
     * [file]의 쪽마다 글자를 꺼낸다. 한 쪽을 끝낼 때마다 [onPage]. [cancelled]가 true면 그만둔다.
     * [first]쪽부터 끝까지 읽고 나서 앞쪽을 읽는다 (지금 보는 쪽의 글자를 먼저 쓸 수 있게)
     */
    fun extract(file: File, cancelled: () -> Boolean, first: Int = 0, onPage: (page: Int, text: PageText) -> Unit) {
        PDDocument.load(file).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val c = GlyphCollector()
            val n = doc.numberOfPages
            val start = first.coerceIn(0, (n - 1).coerceAtLeast(0))
            for (k in 0 until n) {
                if (cancelled()) throw CancellationException()
                val i = (start + k) % n
                onPage(i, c.collect(doc.getPage(i)))
            }
        }
    }

    /**
     * 쪽의 글자를 내용에 적힌 차례대로 모은다 (PDFBox PDFStreamEngine에 글자 연산자만 붙여서).
     * PDFTextStripper를 쓰지 않는 까닭: 글자 번호를 유니코드로 못 바꾼 글자를 버리는데,
     * 한글에서 만든 PDF는 ToUnicode 표를 PDFBox가 잘못 읽어 '대'·'점' 같은 글자가 빠진다 ([toUnicodeMap]).
     * 또 위치로 다시 줄 세우면 두 단으로 된 시험지의 왼쪽·오른쪽 단 글자가 섞인다
     */
    private class GlyphCollector : PDFStreamEngine() {
        private val sb = StringBuilder()
        private val rawSb = StringBuilder()
        private var boxes = FloatArray(1024)
        private var crop = PDRectangle()
        private var rot = 0
        private val maps = java.util.IdentityHashMap<PDFont, Map<Int, String>>()
        private val pts = FloatArray(4)

        init {
            for (op in listOf(
                BeginText(), EndText(), Concatenate(), DrawObject(), Save(), Restore(), SetGraphicsStateParameters(),
                NextLine(), SetCharSpacing(), MoveText(), MoveTextSetLeading(), SetFontAndSize(), ShowText(),
                ShowTextAdjusted(), SetTextLeading(), SetMatrix(), SetTextRenderingMode(), SetTextRise(),
                SetWordSpacing(), SetTextHorizontalScaling(), ShowTextLine(), ShowTextLineAndSpace(),
            )) addOperator(op)
        }

        fun collect(page: PDPage): PageText {
            sb.setLength(0)
            rawSb.setLength(0)
            crop = page.cropBox
            rot = ((page.rotation % 360) + 360) % 360
            processPage(page)
            return PageText(sb.toString(), boxes.copyOf(sb.length * 4), rawSb.toString())
        }

        private fun toPage(x: Float, y: Float, out: FloatArray, at: Int) = PdfText.toPage(crop, rot, x, y, out, at)

        override fun showGlyph(m: Matrix?, font: PDFont?, code: Int, unicode: String?, displacement: Vector?) {
            if (m == null || font == null) return
            val u = maps.getOrPut(font) { toUnicodeMap(font) }[code] ?: unicode ?: return
            val n = u.count { !it.isWhitespace() }
            if (n == 0) return
            // 글자 상자: 기준선에서 글자 크기의 0.85만큼 위, 0.25만큼 아래 (강조가 글자를 덮게)
            val x = m.translateX
            val y = m.translateY
            val fs = kotlin.math.abs(m.scalingFactorY).coerceAtLeast(1f)
            val w = (displacement?.x ?: 0.5f) * m.scalingFactorX
            toPage(x, y - fs * 0.25f, pts, 0)
            toPage(x + w, y + fs * 0.85f, pts, 2)
            val l = min(pts[0], pts[2]); val r = max(pts[0], pts[2])
            val t = min(pts[1], pts[3]); val b = max(pts[1], pts[3])
            var k = 0
            for (ch in u) {
                if (ch.isWhitespace()) continue
                val i = sb.length
                if (boxes.size < (i + 1) * 4) boxes = boxes.copyOf(boxes.size * 2)
                // 한 글자 번호가 여러 글자(합자)면 너비를 나눈다
                boxes[i * 4] = l + (r - l) * k / n
                boxes[i * 4 + 1] = t
                boxes[i * 4 + 2] = l + (r - l) * (k + 1) / n
                boxes[i * 4 + 3] = b
                sb.append(ch.lowercaseChar())
                rawSb.append(ch)
                k++
            }
        }
    }

    /**
     * 글꼴의 ToUnicode 표를 직접 읽는다 (글자 번호 → 글자).
     * 한글(HWP)에서 만든 PDF는 bfrange의 끝 바이트가 넘치게(예: <6000> <60FF> <B213>) 적는데,
     * PDFBox는 끝 바이트만 올려 넘치는 글자(대·점·학 …)를 잃는다. 여기서는 받아올림을 해서 읽는다
     */
    private fun toUnicodeMap(font: PDFont): Map<Int, String> {
        val stream = font.cosObject.getDictionaryObject(com.tom_roush.pdfbox.cos.COSName.TO_UNICODE)
            as? com.tom_roush.pdfbox.cos.COSStream ?: return emptyMap()
        val text = try {
            stream.createInputStream().use { String(it.readBytes(), Charsets.ISO_8859_1) }
        } catch (e: Exception) {
            return emptyMap()
        }
        val map = HashMap<Int, String>()
        fun utf16(hex: String) = buildString {
            for (k in 0 until hex.length / 4) append(hex.substring(k * 4, k * 4 + 4).toInt(16).toChar())
        }
        val hexTok = Regex("<([0-9A-Fa-f]*)>")
        for (sec in Regex("beginbfchar(.*?)endbfchar", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            val t = hexTok.findAll(sec.groupValues[1]).map { it.groupValues[1] }.toList()
            for (k in 0 until t.size / 2) {
                val src = t[k * 2].toIntOrNull(16) ?: continue
                map[src] = utf16(t[k * 2 + 1])
            }
        }
        val rangeLine = Regex("<([0-9A-Fa-f]+)>\\s*<([0-9A-Fa-f]+)>\\s*(<[0-9A-Fa-f]*>|\\[[^\\]]*])")
        for (sec in Regex("beginbfrange(.*?)endbfrange", RegexOption.DOT_MATCHES_ALL).findAll(text)) {
            for (m in rangeLine.findAll(sec.groupValues[1])) {
                val lo = m.groupValues[1].toIntOrNull(16) ?: continue
                val hi = m.groupValues[2].toIntOrNull(16) ?: continue
                if (hi < lo || hi - lo > 0xFFFF) continue
                val dst = m.groupValues[3]
                if (dst.startsWith("[")) {
                    hexTok.findAll(dst).forEachIndexed { k, h -> if (lo + k <= hi) map[lo + k] = utf16(h.groupValues[1]) }
                } else {
                    val s = utf16(dst.trim('<', '>'))
                    if (s.isEmpty()) continue
                    // 마지막 글자를 받아올림 하며 하나씩 올린다
                    val head = s.dropLast(1)
                    val last = s.last().code
                    for (c in lo..hi) map[c] = head + (last + (c - lo)).toChar()
                }
            }
        }
        return map
    }

    /** [pt]에서 [query](normalize한 것)가 나오는 자리들. 한 군데가 여러 줄에 걸치면 줄마다 상자 하나 */
    fun find(pt: PageText, query: String): List<RectF> {
        if (query.isEmpty()) return emptyList()
        val out = ArrayList<RectF>()
        var from = 0
        while (true) {
            val i = pt.chars.indexOf(query, from)
            if (i < 0) break
            var cur: RectF? = null
            for (k in i until i + query.length) {
                val l = pt.boxes[k * 4]; val t = pt.boxes[k * 4 + 1]
                val r = pt.boxes[k * 4 + 2]; val b = pt.boxes[k * 4 + 3]
                val c = cur
                // 앞 글자와 높이가 겹치면 같은 줄
                if (c != null && min(c.bottom, b) - max(c.top, t) > (b - t) * 0.3f) c.union(l, t, r, b)
                else RectF(l, t, r, b).also { out.add(it); cur = it }
            }
            from = i + max(1, query.length)
        }
        return out
    }
}

/**
 * 한 문서의 글자 찾기. 처음 찾을 때 뒤에서 쪽마다 글자를 꺼내 두고(쪽을 넣고 빼면 새로),
 * 꺼내는 동안에도 꺼낸 쪽까지의 결과를 바로바로 알려 준다. 이 앱으로 넣은 글 상자도 함께 찾는다
 */
class DocSearch(val file: File, private val scope: CoroutineScope) {
    private val texts = HashMap<Int, PageText>()
    private var job: Job? = null
    private var cancelled = false
    var pageCount = 0
        private set
    /** 글자를 다 꺼냈는지 */
    var ready = false
        private set
    /** 꺼내다 실패함 (글자를 꺼낼 수 없는 PDF) */
    var failed = false
        private set
    /** 쪽을 꺼낼 때마다 (메인 스레드) */
    var onProgress: (() -> Unit)? = null
    /** 필기를 글로 읽은 색인 (탭이 쥐고 있다). 있으면 필기 속 글도 함께 찾는다 */
    internal var handwriting: HandwritingIndex? = null

    val indexed get() = texts.size

    /** [page]쪽의 글자 (아직 꺼내지 못했으면 null). 글자 기반 주석이 쓴다 */
    fun pageText(page: Int): PageText? = texts[page]

    fun start(pages: Int) {
        // 필기 읽기는 찾기를 열 때마다 (그 사이 필기가 바뀌었을 수 있으니) 다시 훑는다. 바뀐 쪽만 읽는다
        handwriting?.let {
            it.onProgress = { onProgress?.invoke() }
            it.start()
        }
        startText(pages)
    }

    /** 글자만 꺼내기 시작한다 (필기 읽기는 건드리지 않고). [firstPage]쪽부터 읽는다. 이미 시작했으면 그대로 */
    fun startText(pages: Int, firstPage: Int = 0) {
        if (job != null) return
        pageCount = pages
        job = scope.launch {
            try {
                withContext(Dispatchers.IO) {
                    PdfText.extract(file, { cancelled }, firstPage) { p, t ->
                        scope.launch { texts[p] = t; onProgress?.invoke() }
                    }
                }
                ready = true
            } catch (e: CancellationException) {
                // 문서가 바뀜
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                failed = true
                ready = true
            }
            onProgress?.invoke()
        }
    }

    fun cancel() {
        cancelled = true
        job?.cancel()
        onProgress = null
        handwriting?.onProgress = null
    }

    /** 찾기를 닫음: 필기 읽기는 더 하지 않는다 (글자 색인은 그대로 둔다) */
    fun pause() {
        onProgress = null
        handwriting?.onProgress = null
    }

    /**
     * 쪽마다 찾은 자리 (쪽 좌표). PDF 글자는 꺼낸 쪽까지만. [ink]의 글 상자는 상자 전체를 강조한다
     */
    fun find(query: String, ink: InkDocument?): Map<Int, List<RectF>> {
        val q = PdfText.normalize(query)
        if (q.isEmpty()) return emptyMap()
        val hits = sortedMapOf<Int, MutableList<RectF>>()
        for ((p, t) in texts) {
            val r = PdfText.find(t, q)
            if (r.isNotEmpty()) hits.getOrPut(p) { ArrayList() }.addAll(r)
        }
        ink?.pages?.forEachIndexed { p, strokes ->
            for (s in strokes) {
                val txt = s.text ?: continue
                if (q !in PdfText.normalize(txt.text) || s.count == 0) continue
                // 글 상자는 네 모서리 획이라 그 점들을 감싸는 상자
                val box = RectF(s.x(0), s.y(0), s.x(0), s.y(0))
                for (i in 1 until s.count) box.union(s.x(i), s.y(i))
                hits.getOrPut(p) { ArrayList() }.add(box)
            }
        }
        // 손글씨로 쓴 것을 읽은 글
        handwriting?.find(query)?.forEach { (p, rects) -> hits.getOrPut(p) { ArrayList() }.addAll(rects) }
        return hits
    }
}
