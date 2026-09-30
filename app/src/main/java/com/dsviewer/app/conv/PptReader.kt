package com.dsviewer.app.conv

import com.dsviewer.app.hwp.Align
import com.dsviewer.app.hwp.Cfb
import com.dsviewer.app.hwp.HDoc
import com.dsviewer.app.hwp.HObject
import com.dsviewer.app.hwp.HPara
import com.dsviewer.app.hwp.HPicture
import com.dsviewer.app.hwp.HSection
import com.dsviewer.app.hwp.HShape
import com.dsviewer.app.hwp.LineStyle
import com.dsviewer.app.hwp.PageDef
import com.dsviewer.app.hwp.ParaShape
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PPT(파워포인트 97~2003 바이너리) → [HDoc]. 슬라이드마다 도형의 위치·채우기·선, 글(크기·굵게·색·맞춤),
 * 그림(JPEG·PNG·BMP), 마스터의 배경·그림을 옮긴다. 메타파일 그림(EMF·WMF), 차트, 표(도형 묶음으로 그려진 것은 보임)는 빠질 수 있다.
 */
class PptReader private constructor(file: File) {

    private val cfb = Cfb(file)
    private val doc: ByteArray = cfb.read("PowerPoint Document") ?: error("파워포인트 문서가 아닙니다.")
    private val d = ByteBuffer.wrap(doc).order(ByteOrder.LITTLE_ENDIAN)
    private val pics: ByteArray? = cfb.read("Pictures")
    private val b = HBuilder()

    private val persist = HashMap<Int, Int>()
    private var slideW = 5760
    private var slideH = 4320
    private val fonts = ArrayList<String>()
    /** 그림 창고 (1부터): Pictures 스트림 위치 또는 FBSE 안 */
    private val blipOffsets = ArrayList<Int>()
    private val bins = HashMap<String, ByteArray>()
    private var docRef = -1
    /** 마스터 슬라이드 id → 문서 안 위치 */
    private val masterIds = HashMap<Int, Int>()

    companion object {
        fun read(file: File, maxSlides: Int = 0): HDoc = PptReader(file).build(maxSlides)

        /** 마스터 단위(1/576 인치) → HWPUNIT(1/7200 인치) */
        private fun mu(v: Int): Int = v * 25 / 2
        private fun mu(v: Double): Int = (v * 12.5).roundToInt()
        private const val EMU_TO_HWP = 1.0 / 127
    }

    init {
        // 마지막 편집 → 영속 목록 (새 편집이 앞선다)
        val cu = cfb.read("Current User")
        var edit = if (cu != null && cu.size >= 20) ByteBuffer.wrap(cu).order(ByteOrder.LITTLE_ENDIAN).getInt(16) else -1
        var docRef = -1
        val seen = HashSet<Int>()
        while (edit in 0 until doc.size - 8 && seen.add(edit)) {
            val r = OfficeArt.rec(d, edit) ?: break
            if (r.type != 0x0FF5) break
            if (docRef < 0) docRef = d.getInt(r.body + 16)
            val dir = d.getInt(r.body + 12)
            OfficeArt.rec(d, dir)?.let { pr ->
                var p = pr.body
                while (p + 4 <= pr.end) {
                    val v = d.getInt(p)
                    val id = v and 0xFFFFF
                    val n = (v ushr 20) and 0xFFF
                    for (k in 0 until n) {
                        val off = p + 4 + k * 4
                        if (off + 4 > pr.end) break
                        persist.putIfAbsent(id + k, d.getInt(off))
                    }
                    p += 4 + n * 4
                }
            }
            edit = d.getInt(r.body + 8)
            if (edit == 0) break
        }
        if (persist.isEmpty() || docRef < 0) error("파워포인트 문서 구조를 읽지 못했습니다.")
        this.docRef = docRef
    }


    private class Slide(val offset: Int, val texts: List<Pair<Int, String>>)

    private fun build(maxSlides: Int): HDoc {
        val docOff = persist[docRef] ?: error("문서 내용이 없습니다.")
        val docRec = OfficeArt.rec(d, docOff) ?: error("문서 내용이 없습니다.")
        val slides = ArrayList<Slide>()
        OfficeArt.children(d, docRec) { c ->
            when (c.type) {
                0x03E9 -> { slideW = d.getInt(c.body); slideH = d.getInt(c.body + 4) }
                0x03F2 -> readFonts(c)
                0x040B -> readBStore(c)
                0x0FF0 -> if (c.inst == 1) {
                    // 마스터 목록: 슬라이드 id → 영속 id
                    OfficeArt.children(d, c) { a -> if (a.type == 0x03F3 && a.len >= 16) persist[d.getInt(a.body)]?.let { masterIds[d.getInt(a.body + 12)] = it } }
                } else if (c.inst == 0) {
                    // 슬라이드 목록: 슬라이드마다 자리 표시자 글
                    var cur = -1
                    var texts = ArrayList<Pair<Int, String>>()
                    var type = 4
                    OfficeArt.children(d, c) { a ->
                        when (a.type) {
                            0x03F3 -> {
                                if (cur >= 0) slides.add(Slide(cur, texts))
                                cur = persist[d.getInt(a.body)] ?: -1
                                texts = ArrayList()
                            }
                            0x0F9F -> type = d.getInt(a.body)
                            0x0FA0 -> texts.add(type to utf16(a))
                            0x0FA8 -> texts.add(type to latin1(a))
                        }
                    }
                    if (cur >= 0) slides.add(Slide(cur, texts))
                }
            }
        }
        val sections = ArrayList<HSection>()
        for ((i, s) in slides.withIndex()) {
            if (maxSlides > 0 && i >= maxSlides) break
            sections.add(slide(s))
        }
        if (sections.isEmpty()) error("슬라이드가 없습니다.")
        return b.build(sections) { id -> bins[id] }
    }

    private fun utf16(r: OfficeArt.Rec): String {
        val sb = StringBuilder(r.len / 2)
        var p = r.body
        while (p + 1 < r.end) { sb.append(d.getChar(p)); p += 2 }
        return sb.toString()
    }

    private fun latin1(r: OfficeArt.Rec): String = String(doc, r.body, r.len, Charsets.ISO_8859_1)

    private fun readFonts(env: OfficeArt.Rec) {
        OfficeArt.children(d, env) { c ->
            if (c.type == 0x07D5) OfficeArt.children(d, c) { f ->
                if (f.type == 0x0FB7) {
                    val sb = StringBuilder()
                    for (k in 0 until 32) {
                        val ch = d.getChar(f.body + k * 2)
                        if (ch == '\u0000') break
                        sb.append(ch)
                    }
                    fonts.add(sb.toString())
                }
            }
        }
    }

    private fun readBStore(group: OfficeArt.Rec) {
        fun walk(r: OfficeArt.Rec) {
            OfficeArt.children(d, r) { c ->
                when {
                    c.type == 0xF007 -> {
                        // 그림이 FBSE 안에 있으면 그 자리(음수로 표시), 아니면 Pictures 스트림 위치
                        val cbName = if (c.len > 33) doc[c.body + 33].toInt() and 0xFF else 0
                        blipOffsets.add(
                            if (c.len >= 36 + cbName + 8) -(c.body + 36 + cbName) - 1
                            else if (c.len >= 32) d.getInt(c.body + 28) else Int.MIN_VALUE
                        )
                    }
                    c.isContainer -> walk(c)
                }
            }
        }
        walk(group)
    }

    /** 그림 창고 [pib](1부터) → 그림 id */
    private fun blip(pib: Int): String? {
        val key = "ppt_pic_$pib"
        if (bins.containsKey(key)) return key
        val off = blipOffsets.getOrNull(pib - 1)?.takeIf { it != Int.MIN_VALUE } ?: return null
        val bytes = if (off < 0) {
            val at = -off - 1
            OfficeArt.rec(d, at)?.let { OfficeArt.blip(doc, it) }
        } else {
            val p = pics ?: return null
            val pb = ByteBuffer.wrap(p).order(ByteOrder.LITTLE_ENDIAN)
            OfficeArt.rec(pb, off)?.let { OfficeArt.blip(p, it) }
        } ?: return null
        bins[key] = bytes
        return key
    }

    // ================= 슬라이드 =================

    /** 도형 속성표 */
    private class Opt(val props: Map<Int, Int>, val complex: Map<Int, ByteArray>) {
        operator fun get(pid: Int) = props[pid]
        fun bool(pid: Int, bit: Int, useBit: Int): Boolean? {
            val v = props[pid] ?: return null
            return if (v and useBit != 0) v and bit != 0 else null
        }
    }

    private fun opt(r: OfficeArt.Rec): Opt {
        val props = HashMap<Int, Int>()
        val complex = HashMap<Int, ByteArray>()
        val n = r.inst
        var cp = r.body + n * 6
        for (k in 0 until n) {
            val at = r.body + k * 6
            if (at + 6 > r.end) break
            val id = d.getShort(at).toInt() and 0xFFFF
            val v = d.getInt(at + 2)
            val pid = id and 0x3FFF
            props[pid] = v
            if (id and 0x8000 != 0 && v > 0 && cp + v <= r.end) {
                complex[pid] = doc.copyOfRange(cp, cp + v)
                cp += v
            }
        }
        return Opt(props, complex)
    }

    private class Scheme(val colors: IntArray)

    private fun scheme(container: OfficeArt.Rec): Scheme? {
        var out: Scheme? = null
        OfficeArt.children(d, container) { c ->
            if (c.type == 0x07F0 && out == null && c.len >= 32) {
                out = Scheme(IntArray(8) { k ->
                    val o = c.body + k * 4
                    0xFF000000.toInt() or ((doc[o].toInt() and 0xFF) shl 16) or ((doc[o + 1].toInt() and 0xFF) shl 8) or (doc[o + 2].toInt() and 0xFF)
                })
            }
        }
        return out
    }

    /** OfficeArtCOLORREF → 색 (배색 번호면 배색에서) */
    private fun color(v: Int?, sc: Scheme?): Int? {
        v ?: return null
        val r = v and 0xFF
        val g = (v shr 8) and 0xFF
        val bl = (v shr 16) and 0xFF
        val flags = (v ushr 24) and 0xFF
        if (flags and 0x08 != 0) return sc?.colors?.getOrNull(r) ?: 0xFF000000.toInt()
        if (flags and 0x10 != 0) return null
        return 0xFF000000.toInt() or (r shl 16) or (g shl 8) or bl
    }

    private fun slide(s: Slide): HSection {
        val rec = OfficeArt.rec(d, s.offset)
        val objs = ArrayList<HObject>()
        if (rec != null) {
            var masterRef = 0
            var flags = 0x7
            OfficeArt.children(d, rec) { c -> if (c.type == 0x03EF && c.len >= 24) { masterRef = d.getInt(c.body + 12); flags = d.getShort(c.body + 20).toInt() } }
            val master = (masterIds[masterRef] ?: persist[masterRef])?.let { OfficeArt.rec(d, it) }?.takeIf { it.type == 0x03F8 || it.type == 0x03EE }
            val masterScheme = master?.let { scheme(it) }
            val sc = if (flags and 0x2 != 0) masterScheme ?: scheme(rec) else scheme(rec) ?: masterScheme
            // 배경: 슬라이드 것 또는 마스터 것
            val bgFrom = if (flags and 0x4 != 0 && master != null) master else rec
            drawing(bgFrom)?.let { dg -> background(dg, sc)?.let { objs.add(it) } }
            // 마스터의 그림·도형 (자리 표시자 빼고)
            if (flags and 0x1 != 0 && master != null) drawing(master)?.let { shapes(it, sc, null, objs, master = true) }
            drawing(rec)?.let { shapes(it, sc, s, objs, master = false) }
        }
        val para = HBuilder.Para()
        for (o in objs) para.obj(o, 0)
        return HSection(listOf(para.build())).apply {
            page = PageDef().apply {
                width = mu(slideW); height = mu(slideH)
                left = 0; right = 0; top = 0; bottom = 0; header = 0; footer = 0
            }
        }
    }

    private fun drawing(container: OfficeArt.Rec): OfficeArt.Rec? {
        var dg: OfficeArt.Rec? = null
        OfficeArt.children(d, container) { c -> if (c.type == 0x040C) OfficeArt.children(d, c) { x -> if (x.type == 0xF002) dg = x } }
        return dg
    }

    private fun background(dg: OfficeArt.Rec, sc: Scheme?): HObject? {
        var out: HObject? = null
        OfficeArt.children(d, dg) { c ->
            if (c.type == 0xF004) {
                var bg = false
                var o: Opt? = null
                OfficeArt.children(d, c) { x ->
                    if (x.type == 0xF00A) bg = d.getInt(x.body + 4) and 0x400 != 0
                    if (x.type == 0xF00B) o = opt(x)
                }
                val op = o
                if (bg && op != null) {
                    val fillType = op[0x0180] ?: 0
                    val pib = op[0x0186]
                    out = if (fillType in 2..3 && pib != null && pib > 0) blip(pib)?.let { id ->
                        HPicture().apply { binId = id; place(this, 0, 0, mu(slideW), mu(slideH)); orgWidth = width; orgHeight = height }
                    } else color(op[0x0181], sc)?.let { col ->
                        HShape("rect").apply { place(this, 0, 0, mu(slideW), mu(slideH)); orgWidth = width; orgHeight = height; fillColor = col }
                    }
                }
            }
        }
        return out
    }

    private fun place(o: HObject, x: Int, y: Int, w: Int, h: Int) {
        o.treatAsChar = false
        o.textWrap = "IN_FRONT_OF_TEXT"
        o.horzRelTo = "PAPER"
        o.vertRelTo = "PAPER"
        o.horzOffset = x
        o.vertOffset = y
        o.width = max(0, w)
        o.height = max(0, h)
    }

    /** 묶음 좌표 → 슬라이드 좌표 (마스터 단위) */
    private class Xf(val ax: Double, val kx: Double, val ay: Double, val ky: Double)

    private fun shapes(dg: OfficeArt.Rec, sc: Scheme?, s: Slide?, out: MutableList<HObject>, master: Boolean) {
        var textIndex = 0
        fun group(g: OfficeArt.Rec, xf: Xf?) {
            var inner = xf
            var first = true
            OfficeArt.children(d, g) { c ->
                when (c.type) {
                    0xF003 -> group(c, inner)
                    0xF004 -> {
                        if (first) {
                            // 묶음의 첫 도형: 묶음 좌표계
                            first = false
                            var grp: IntArray? = null
                            var anchor: IntArray? = null
                            OfficeArt.children(d, c) { x ->
                                if (x.type == 0xF009 && x.len >= 16) grp = IntArray(4) { d.getInt(x.body + it * 4) }
                                if (x.type == 0xF010 || x.type == 0xF00F) anchor = anchorOf(x)
                            }
                            val gr = grp
                            val an = anchor
                            if (gr != null && an != null) {
                                val base = inner
                                val (ax, ay, bx, by) = if (base == null) an.map { it.toDouble() } else listOf(
                                    base.ax + an[0] * base.kx, base.ay + an[1] * base.ky, base.ax + an[2] * base.kx, base.ay + an[3] * base.ky,
                                )
                                val kx = if (gr[2] != gr[0]) (bx - ax) / (gr[2] - gr[0]) else 1.0
                                val ky = if (gr[3] != gr[1]) (by - ay) / (gr[3] - gr[1]) else 1.0
                                inner = Xf(ax - gr[0] * kx, kx, ay - gr[1] * ky, ky)
                            }
                            return@children
                        }
                        shape(c, sc, s, inner, out, master) { textIndex++ }
                    }
                }
            }
        }
        OfficeArt.children(d, dg) { c -> if (c.type == 0xF003) group(c, null) }
    }

    /** 앵커: (left, top, right, bottom) */
    private fun anchorOf(x: OfficeArt.Rec): IntArray = when {
        x.type == 0xF010 && x.len == 8 -> intArrayOf(d.getShort(x.body + 2).toInt(), d.getShort(x.body).toInt(), d.getShort(x.body + 4).toInt(), d.getShort(x.body + 6).toInt())
        x.type == 0xF010 -> intArrayOf(d.getInt(x.body + 4), d.getInt(x.body), d.getInt(x.body + 8), d.getInt(x.body + 12))
        else -> intArrayOf(d.getInt(x.body), d.getInt(x.body + 4), d.getInt(x.body + 8), d.getInt(x.body + 12))
    }

    private fun shape(sp: OfficeArt.Rec, sc: Scheme?, s: Slide?, xf: Xf?, out: MutableList<HObject>, master: Boolean, nextText: () -> Int) {
        var spt = 0
        var fspFlags = 0
        var o: Opt? = null
        var anchor: IntArray? = null
        var placeholder = false
        var textType = 4
        var text: String? = null
        var style: OfficeArt.Rec? = null
        var outlineRef = -1
        OfficeArt.children(d, sp) { x ->
            when (x.type) {
                0xF00A -> { spt = x.inst; fspFlags = d.getInt(x.body + 4) }
                0xF00B -> o = opt(x)
                0xF010, 0xF00F -> anchor = anchorOf(x)
                0xF011 -> OfficeArt.children(d, x) { y -> if (y.type == 0x0BC3) placeholder = true }
                0xF00D -> OfficeArt.children(d, x) { y ->
                    when (y.type) {
                        0x0F9F -> textType = d.getInt(y.body)
                        0x0FA0 -> text = utf16(y)
                        0x0FA8 -> text = latin1(y)
                        0x0FA1 -> style = y
                        0x0F9E -> outlineRef = d.getInt(y.body)
                    }
                }
            }
        }
        if (fspFlags and 0x400 != 0 || fspFlags and 0x8 != 0) return // 배경·지운 도형
        if (master && placeholder) return
        val an = anchor ?: return
        val op = o ?: Opt(emptyMap(), emptyMap())
        if (op.bool(0x03BF, 0x02, 0x020000) == true) return // 숨김(fHidden)

        // 자리 표시자 글이 슬라이드 목록에 있는 경우
        if (text == null && s != null) {
            val idx = if (outlineRef >= 0) outlineRef else if (placeholder) nextText() else -1
            s.texts.getOrNull(idx)?.let { (tt, str) -> textType = tt; text = str }
        }
        val (l, t, r, bt) = if (xf == null) an.map { it.toDouble() } else listOf(
            xf.ax + an[0] * xf.kx, xf.ay + an[1] * xf.ky, xf.ax + an[2] * xf.kx, xf.ay + an[3] * xf.ky,
        )
        val x = mu(l)
        val y = mu(t)
        val w = mu(r - l)
        val h = mu(bt - t)
        val rot = (op[0x0004] ?: 0) / 65536f
        val flipH = fspFlags and 0x40 != 0
        val flipV = fspFlags and 0x80 != 0

        // 그림
        val pib = op[0x0104]
        if (pib != null && pib > 0) {
            blip(pib)?.let { id ->
                out.add(HPicture().apply {
                    binId = id; place(this, x, y, w, h); orgWidth = width; orgHeight = height
                    rotation = rot; this.flipH = flipH; this.flipV = flipV
                })
            }
        }

        val filled = op.bool(0x01BF, 0x10, 0x100000) ?: (op[0x0181] != null && spt != 202)
        // 채우기 색을 따로 주지 않은 도형은 흰색 (파워포인트 기본값)
        val fill = if (filled) color(op[0x0181], sc) ?: 0xFFFFFFFF.toInt() else null
        val lined = op.bool(0x01FF, 0x08, 0x080000) ?: (op[0x01C0] != null)
        val lineColor = if (lined) color(op[0x01C0], sc) ?: 0xFF000000.toInt() else null
        val line = lineColor?.let { LineStyle(it, max(1, ((op[0x01CB] ?: 9525) * EMU_TO_HWP).roundToInt()), if ((op[0x01CE] ?: 0) != 0) "DASH" else "SOLID") }

        if (spt == 20 || spt == 32 || spt in 33..40) {
            // 선·연결선
            line ?: return
            out.add(HShape("line").apply {
                place(this, x, y, w, h); orgWidth = width; orgHeight = height
                x0 = if (flipH) width else 0; x1 = if (flipH) 0 else width
                y0 = if (flipV) height else 0; y1 = if (flipV) 0 else height
                this.line = line
                headArrow = (op[0x01D0] ?: 0) != 0
                tailArrow = (op[0x01D1] ?: 0) != 0
            })
            return
        }

        val paras = text?.takeIf { it.isNotBlank() }?.let { textParas(it, textType, style, sc, w) }
        if (fill == null && line == null && paras == null) return
        val shp = when (spt) {
            3 -> HShape("ellipse")
            2 -> HShape("rect").apply { roundRatio = 33 }
            1, 0, 202, 75 -> HShape("rect")
            else -> Dml.presetPath(presetName(spt), w.toFloat(), h.toFloat(), emptyMap())?.let { HShape("path").apply { path = it } } ?: HShape("rect")
        }
        place(shp, x, y, w, h)
        shp.orgWidth = shp.width; shp.orgHeight = shp.height; shp.curWidth = shp.width; shp.curHeight = shp.height
        shp.rotation = rot
        if (paras == null) { shp.flipH = flipH; shp.flipV = flipV }
        shp.fillColor = fill
        shp.line = line
        if (paras != null) {
            shp.drawText = paras
            shp.textMarginLeft = ((op[0x0081] ?: 91440) * EMU_TO_HWP).roundToInt()
            shp.textMarginTop = ((op[0x0082] ?: 45720) * EMU_TO_HWP).roundToInt()
            shp.textMarginRight = ((op[0x0083] ?: 91440) * EMU_TO_HWP).roundToInt()
            shp.textMarginBottom = ((op[0x0084] ?: 45720) * EMU_TO_HWP).roundToInt()
            shp.textVertAlign = when (op[0x0087] ?: if (textType == 0 || textType == 6) 1 else 0) {
                1, 4, 6, 9 -> "CENTER"
                2, 5, 7 -> "BOTTOM"
                else -> "TOP"
            }
        }
        out.add(shp)
    }

    private fun presetName(spt: Int): String = when (spt) {
        4 -> "diamond"; 5 -> "triangle"; 6 -> "rtTriangle"; 7 -> "parallelogram"; 8 -> "trapezoid"
        9 -> "hexagon"; 10 -> "octagon"; 11 -> "plus"; 12 -> "star5"; 13 -> "rightArrow"; 15 -> "homePlate"
        55 -> "chevron"; 56 -> "pentagon"; 66 -> "leftArrow"; 67 -> "downArrow"; 68 -> "upArrow"; 69 -> "leftRightArrow"
        70 -> "upDownArrow"; 74 -> "heart"; 23 -> "donut"; 22 -> "can"; 109 -> "flowChartProcess"; 110 -> "flowChartDecision"
        116 -> "flowChartTerminator"; 187 -> "star4"; 58 -> "star8"; 59 -> "star10"
        else -> "rect"
    }

    // ================= 글 =================

    /** 문단 모양 조각 (글자 수, 수준, 맞춤, 글머리표 여부) */
    private class PRun(val count: Int, val level: Int, val align: Int?, val bullet: Boolean?, val bulletChar: Char?, val lineSpacing: Int?, val before: Int?, val after: Int?)
    private class CRun(val count: Int, val bold: Boolean?, val italic: Boolean?, val underline: Boolean?, val font: Int?, val size: Int?, val color: Int?)

    private fun textParas(text: String, textType: Int, style: OfficeArt.Rec?, sc: Scheme?, boxW: Int): List<HPara> {
        val (pruns, cruns) = style?.let { runCatching { styleRuns(it, text.length + 1) }.getOrNull() } ?: (emptyList<PRun>() to emptyList())
        val title = textType == 0 || textType == 6
        val body = textType == 1 || textType == 5 || textType == 7 || textType == 8
        val defColor = if (title) sc?.colors?.getOrNull(3) else sc?.colors?.getOrNull(1)
        // 문단 나누기
        val out = ArrayList<HPara>()
        var pos = 0
        var pi = 0
        var pLeft = pruns.firstOrNull()?.count ?: Int.MAX_VALUE
        var ci = 0
        var cLeft = cruns.firstOrNull()?.count ?: Int.MAX_VALUE
        for (line in text.split('\r')) {
            val pr = pruns.getOrNull(pi)
            val level = (pr?.level ?: 0).coerceIn(0, 4)
            val baseSize = when {
                title -> 4400
                body -> intArrayOf(3200, 2800, 2400, 2000, 2000)[level]
                else -> 1800
            }
            val para = HBuilder.Para()
            var firstSize = 0
            for (ch in line) {
                val cr = cruns.getOrNull(ci)
                val run = HBuilder.Run(
                    latin = cr?.font?.let { fonts.getOrNull(it) } ?: fonts.firstOrNull().orEmpty(),
                    ea = cr?.font?.let { fonts.getOrNull(it) } ?: fonts.firstOrNull().orEmpty(),
                    size = (cr?.size?.takeIf { it in 1..400 }?.times(100)) ?: baseSize,
                    color = cr?.color?.let { color(it, sc) } ?: defColor ?: 0xFF000000.toInt(),
                    bold = cr?.bold ?: false,
                    italic = cr?.italic ?: false,
                    underline = cr?.underline ?: false,
                )
                if (firstSize == 0) firstSize = run.size
                para.text(if (ch == '\u000B') "\n" else ch.toString(), b.charShape(run))
                cLeft--
                if (cLeft <= 0 && ci < cruns.size) { ci++; cLeft = cruns.getOrNull(ci)?.count ?: Int.MAX_VALUE }
                pos++
            }
            // 문단 끝(\r)도 한 글자
            cLeft--
            if (cLeft <= 0 && ci < cruns.size) { ci++; cLeft = cruns.getOrNull(ci)?.count ?: Int.MAX_VALUE }
            val ps = ParaShape()
            ps.align = when (pr?.align ?: if (title) 1 else 0) {
                1 -> Align.CENTER
                2 -> Align.RIGHT
                3 -> Align.JUSTIFY
                else -> Align.LEFT
            }
            ps.lineSpacingType = "PERCENT"
            val lsp = pr?.lineSpacing
            ps.lineSpacing = if (lsp != null && lsp in 1..1000) (lsp * 1.2f).roundToInt() else 120
            ps.prev = pr?.before?.takeIf { it < 0 }?.let { -it * 100 / 8 } ?: pr?.before?.let { (firstSize * it / 100f).roundToInt() } ?: if (body) firstSize / 5 else 0
            ps.next = 0
            ps.left = level * 5400
            ps.leadAbove = 0.5f
            ps.prefixTab = true
            val bullet = pr?.bullet ?: body
            if (bullet && line.isNotBlank()) {
                ps.headingType = "BULLET"
                ps.headingIdRef = b.bullet(pr?.bulletChar?.let { WordFonts.bullet(it) } ?: if (level % 2 == 0) "•" else "–")
                ps.left += 2700
                ps.indent = -2700
            }
            para.paraShapeId = b.paraShape(ps)
            para.endCharShape = b.charShape(HBuilder.Run(size = firstSize.takeIf { it > 0 } ?: baseSize))
            out.add(para.build())
            pos++
            pLeft -= line.length + 1
            while (pLeft <= 0 && pi < pruns.size) { pi++; pLeft += pruns.getOrNull(pi)?.count ?: Int.MAX_VALUE }
        }
        return out
    }

    /** StyleTextPropAtom: 문단 조각들, 글자 조각들 */
    private fun styleRuns(r: OfficeArt.Rec, total: Int): Pair<List<PRun>, List<CRun>> {
        var p = r.body
        val pr = ArrayList<PRun>()
        var covered = 0
        while (covered < total && p + 10 <= r.end) {
            val count = d.getInt(p)
            val level = d.getShort(p + 4).toInt()
            val masks = d.getInt(p + 6)
            p += 10
            var bullet: Boolean? = null
            var bulletChar: Char? = null
            var align: Int? = null
            var lsp: Int? = null
            var before: Int? = null
            var after: Int? = null
            fun s16(): Int { val v = d.getShort(p).toInt(); p += 2; return v }
            if (masks and 0xF != 0) { val f = s16(); if (masks and 0x1 != 0) bullet = f and 1 != 0 }
            if (masks and (1 shl 7) != 0) bulletChar = s16().toChar()
            if (masks and (1 shl 4) != 0) s16()
            if (masks and (1 shl 6) != 0) s16()
            if (masks and (1 shl 5) != 0) p += 4
            if (masks and (1 shl 11) != 0) align = s16()
            if (masks and (1 shl 12) != 0) lsp = s16()
            if (masks and (1 shl 13) != 0) before = s16()
            if (masks and (1 shl 14) != 0) after = s16()
            if (masks and (1 shl 8) != 0) s16()
            if (masks and (1 shl 10) != 0) s16()
            if (masks and (1 shl 15) != 0) s16()
            if (masks and (1 shl 20) != 0) { val n = s16(); p += n * 4 }
            if (masks and (1 shl 16) != 0) s16()
            if (masks and (0x7 shl 17) != 0) s16()
            if (masks and (1 shl 21) != 0) s16()
            pr.add(PRun(count, level, align, bullet, bulletChar, lsp, before, after))
            covered += count
        }
        val cr = ArrayList<CRun>()
        covered = 0
        while (covered < total && p + 8 <= r.end) {
            val count = d.getInt(p)
            val masks = d.getInt(p + 4)
            p += 8
            var bold: Boolean? = null
            var italic: Boolean? = null
            var underline: Boolean? = null
            var font: Int? = null
            var size: Int? = null
            var color: Int? = null
            fun s16(): Int { val v = d.getShort(p).toInt() and 0xFFFF; p += 2; return v }
            if (masks and 0x3EB7 != 0) {
                val st = s16()
                if (masks and 1 != 0) bold = st and 1 != 0
                if (masks and 2 != 0) italic = st and 2 != 0
                if (masks and 4 != 0) underline = st and 4 != 0
            }
            if (masks and (1 shl 16) != 0) font = s16()
            if (masks and (1 shl 21) != 0) s16()
            if (masks and (1 shl 22) != 0) s16()
            if (masks and (1 shl 23) != 0) s16()
            if (masks and (1 shl 17) != 0) size = s16()
            if (masks and (1 shl 18) != 0) {
                val rr = doc[p].toInt() and 0xFF; val gg = doc[p + 1].toInt() and 0xFF; val bb = doc[p + 2].toInt() and 0xFF; val idx = doc[p + 3].toInt() and 0xFF
                p += 4
                // OfficeArtCOLORREF 모양으로 (배색 번호면 0x08 표시)
                color = if (idx == 0xFE) (rr or (gg shl 8) or (bb shl 16)) else (idx and 0x07) or (0x08 shl 24)
            }
            if (masks and (1 shl 19) != 0) s16()
            if (masks and (1 shl 20) != 0) p += 4
            if (masks and (1 shl 24) != 0) s16()
            if (masks and (1 shl 25) != 0) s16()
            if (masks and (1 shl 26) != 0) p += 4
            if (p > r.end) break
            cr.add(CRun(count, bold, italic, underline, font, size, color))
            covered += count
        }
        return pr to cr
    }
}
