package com.dsviewer.app.hwp

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Inflater

/**
 * HWP 5.x (한글 바이너리 형식) → [HDoc]
 * 한컴 공개 문서 "한글 문서 파일 형식 5.0" 을 따른다.
 */
class HwpReader private constructor(private val cfb: Cfb) {

    private val fontFaces = Array<MutableList<String>>(Lang.COUNT) { mutableListOf() }
    private val charShapes = HashMap<Int, CharShape>()
    private val paraShapes = HashMap<Int, ParaShape>()
    private val borderFills = HashMap<Int, BorderFill>()
    private val numberings = HashMap<Int, Numbering>()
    private val bullets = HashMap<Int, Bullet>()
    /** BIN_DATA 순번(1부터) → (스트림 이름, 압축 여부) */
    private val binStreams = HashMap<Int, Pair<String, Boolean>>()
    private var compressed = true

    companion object {
        fun read(file: File): HDoc {
            val cfb = Cfb(file)
            return HwpReader(cfb).readAll()
        }

        // 레코드 태그
        private const val T_ID_MAPPINGS = 0x11
        private const val T_BIN_DATA = 0x12
        private const val T_FACE_NAME = 0x13
        private const val T_BORDER_FILL = 0x14
        private const val T_CHAR_SHAPE = 0x15
        private const val T_NUMBERING = 0x17
        private const val T_BULLET = 0x18
        private const val T_PARA_SHAPE = 0x19
        private const val T_PARA_HEADER = 0x42
        private const val T_PARA_TEXT = 0x43
        private const val T_PARA_CHAR_SHAPE = 0x44
        private const val T_PARA_LINE_SEG = 0x45
        private const val T_CTRL_HEADER = 0x47
        private const val T_LIST_HEADER = 0x48
        private const val T_PAGE_DEF = 0x49
        private const val T_FOOTNOTE_SHAPE = 0x4A
        private const val T_SHAPE_COMPONENT = 0x4C
        private const val T_TABLE = 0x4D
        private const val T_SC_LINE = 0x4E
        private const val T_SC_RECT = 0x4F
        private const val T_SC_POLYGON = 0x52
        private const val T_SC_CURVE = 0x53
        private const val T_SC_PICTURE = 0x55
        private const val T_EQEDIT = 0x58

        private val BORDER_TYPES = arrayOf(
            "NONE", "SOLID", "DASH", "DOT", "DASH_DOT", "DASH_DOT_DOT", "LONG_DASH", "CIRCLE",
            "DOUBLE_SLIM", "SLIM_THICK", "THICK_SLIM", "SLIM_THICK_SLIM", "WAVE", "DOUBLE_WAVE",
            "THICK_3D", "THICK_3D_REVERSAL", "3D", "3D_REVERSAL"
        )
        private val BORDER_WIDTHS = floatArrayOf(0.1f, 0.12f, 0.15f, 0.2f, 0.25f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 1.0f, 1.5f, 2.0f, 3.0f, 4.0f, 5.0f)
        private val NUM_FORMATS = arrayOf(
            "DIGIT", "CIRCLED_DIGIT", "ROMAN_CAPITAL", "ROMAN_SMALL", "LATIN_CAPITAL", "LATIN_SMALL",
            "CIRCLED_LATIN_CAPITAL", "CIRCLED_LATIN_SMALL", "HANGUL_SYLLABLE", "CIRCLED_HANGUL_SYLLABLE",
            "HANGUL_JAMO", "CIRCLED_HANGUL_JAMO", "HANGUL_PHONETIC", "IDEOGRAPH", "CIRCLED_IDEOGRAPH"
        )

        fun id(s: String): Int = (s[0].code shl 24) or (s[1].code shl 16) or (s[2].code shl 8) or s[3].code
        private val ID_SECD = id("secd")
        private val ID_TBL = id("tbl ")
        private val ID_GSO = id("gso ")
        private val ID_EQED = id("eqed")
        private val ID_FN = id("fn  ")
        private val ID_EN = id("en  ")
        private val ID_HEAD = id("head")
        private val ID_FOOT = id("foot")
        private val ID_PGNP = id("pgnp")
        private val ID_ATNO = id("atno")
        private val ID_NWNO = id("nwno")
        private val ID_PGHD = id("pghd")
        /** 하이퍼링크 필드 */
        private val ID_HLK = id("%hlk")
        private val ID_PIC = id("\$pic")
        private val ID_REC = id("\$rec")
        private val ID_ELL = id("\$ell")
        private val ID_LIN = id("\$lin")
        private val ID_CON = id("\$con")
        private val ID_POL = id("\$pol")
        private val ID_CUR = id("\$cur")
    }

    // ================= 레코드 =================

    private class Rec(val tag: Int, val level: Int, val data: ByteArray) {
        val children = ArrayList<Rec>()
        private val buf: ByteBuffer get() = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        fun u8(o: Int) = if (o < data.size) data[o].toInt() and 0xFF else 0
        fun i8(o: Int) = if (o < data.size) data[o].toInt() else 0
        fun u16(o: Int) = if (o + 2 <= data.size) buf.getShort(o).toInt() and 0xFFFF else 0
        fun i16(o: Int) = if (o + 2 <= data.size) buf.getShort(o).toInt() else 0
        fun i32(o: Int) = if (o + 4 <= data.size) buf.getInt(o) else 0
        fun u32(o: Int) = if (o + 4 <= data.size) buf.getInt(o).toLong() and 0xFFFFFFFFL else 0L
        fun f64(o: Int) = if (o + 8 <= data.size) buf.getDouble(o) else 0.0
        fun wstr(o: Int, len: Int): String {
            val sb = StringBuilder()
            for (k in 0 until len) {
                if (o + k * 2 + 2 > data.size) break
                sb.append(u16(o + k * 2).toChar())
            }
            return sb.toString()
        }
        fun child(tag: Int) = children.firstOrNull { it.tag == tag }
    }

    private fun parseRecords(data: ByteArray): List<Rec> {
        val roots = ArrayList<Rec>()
        val stack = ArrayList<Rec>()
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 4 <= data.size) {
            val h = bb.getInt(i)
            i += 4
            val tag = h and 0x3FF
            val level = (h ushr 10) and 0x3FF
            var size = (h ushr 20) and 0xFFF
            if (size == 0xFFF) {
                if (i + 4 > data.size) break
                size = bb.getInt(i)
                i += 4
            }
            if (size < 0 || i + size > data.size) break
            val rec = Rec(tag, level, data.copyOfRange(i, i + size))
            i += size
            while (stack.isNotEmpty() && stack.last().level >= level) stack.removeAt(stack.size - 1)
            if (stack.isEmpty()) roots.add(rec) else stack.last().children.add(rec)
            stack.add(rec)
        }
        return roots
    }

    private fun stream(name: String, deflated: Boolean = compressed): ByteArray? {
        val raw = cfb.read(name) ?: return null
        return if (deflated) inflate(raw) else raw
    }

    private fun inflate(raw: ByteArray): ByteArray {
        val inf = Inflater(true)
        inf.setInput(raw)
        val out = ByteArrayOutputStream(raw.size * 4)
        val buf = ByteArray(65536)
        try {
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) break
                }
                out.write(buf, 0, n)
            }
        } finally {
            inf.end()
        }
        return out.toByteArray()
    }

    private fun colorRef(v: Long): Int {
        val r = (v and 0xFF).toInt()
        val g = ((v shr 8) and 0xFF).toInt()
        val b = ((v shr 16) and 0xFF).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    // ================= 전체 =================

    private fun readAll(): HDoc {
        val header = cfb.read("FileHeader") ?: error("HWP 파일 헤더가 없습니다.")
        val sig = String(header, 0, 17, Charsets.US_ASCII)
        if (!sig.startsWith("HWP Document File")) error("HWP 5.0 형식이 아닙니다.")
        val flags = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(36)
        compressed = flags and 1 != 0
        if (flags and 2 != 0) error("암호가 걸린 HWP 문서는 열 수 없습니다.")
        if (flags and 4 != 0) error("배포용 HWP 문서는 열 수 없습니다.")

        val docInfo = stream("DocInfo") ?: error("DocInfo 가 없습니다.")
        parseDocInfo(parseRecords(docInfo))

        val sectionNames = cfb.names().filter { it.matches(Regex("BodyText/Section\\d+")) }
            .sortedBy { it.removePrefix("BodyText/Section").toInt() }
        if (sectionNames.isEmpty()) error("HWP 본문을 찾을 수 없습니다.")
        val sections = sectionNames.map { name ->
            val recs = parseRecords(stream(name) ?: ByteArray(0))
            val paras = recs.filter { it.tag == T_PARA_HEADER }.map { parsePara(it) }
            val sec = HSection(paras)
            paras.firstNotNullOfOrNull { p -> p.items.firstNotNullOfOrNull { (it as? PItem.Ctrl)?.ctrl as? HCtrl.SectionDef } }
                ?.let { sec.page = it.page; sec.footShape = it.footShape; sec.endShape = it.endShape }
            sec
        }

        return HDoc(
            sections = sections,
            fontFaces = Array(Lang.COUNT) { fontFaces[it].toList() },
            charShapes = charShapes,
            paraShapes = paraShapes,
            borderFills = borderFills,
            numberings = numberings,
            bullets = bullets,
            binLoader = { idStr -> loadBin(idStr) },
        )
    }

    private fun loadBin(idStr: String): ByteArray? {
        val idx = idStr.toIntOrNull() ?: return null
        val (name, comp) = binStreams[idx] ?: return null
        return try { stream(name, comp) } catch (e: Exception) { cfb.read(name) }
    }

    // ================= DocInfo =================

    private fun parseDocInfo(roots: List<Rec>) {
        // 모든 레코드를 순서대로 (DocInfo 는 대부분 level 0/1)
        val all = ArrayList<Rec>()
        fun collect(r: Rec) { all.add(r); r.children.forEach { collect(it) } }
        roots.forEach { collect(it) }

        val faceCounts = IntArray(Lang.COUNT)
        var faceIndex = 0
        var borderId = 1
        var charId = 0
        var paraId = 0
        var numId = 1
        var bulletId = 1
        var binIndex = 1

        for (r in all) when (r.tag) {
            T_ID_MAPPINGS -> for (l in 0 until Lang.COUNT) faceCounts[l] = r.i32(4 + l * 4)
            T_FACE_NAME -> {
                // 언어별 개수만큼 차례로 들어 있다
                var lang = 0
                var acc = 0
                while (lang < Lang.COUNT - 1 && faceIndex >= acc + faceCounts[lang]) { acc += faceCounts[lang]; lang++ }
                val len = r.u16(1)
                fontFaces[lang].add(r.wstr(3, len))
                faceIndex++
            }
            T_BORDER_FILL -> borderFills[borderId++] = parseBorderFill(r)
            T_CHAR_SHAPE -> charShapes[charId++] = parseCharShape(r)
            T_PARA_SHAPE -> paraShapes[paraId++] = parseParaShape(r)
            T_NUMBERING -> numberings[numId++] = parseNumbering(r)
            T_BULLET -> bullets[bulletId++] = Bullet(r.u16(12).toChar().toString().ifBlank { "•" })
            T_BIN_DATA -> {
                val prop = r.u16(0)
                val type = prop and 0xF
                if (type == 1 || type == 2) {
                    val binId = r.u16(2)
                    val extLen = r.u16(4)
                    val ext = r.wstr(6, extLen)
                    val comp = when ((prop shr 4) and 3) {
                        1 -> true
                        2 -> false
                        else -> compressed
                    }
                    binStreams[binIndex] = "BinData/BIN%04X.%s".format(binId, ext) to comp
                }
                binIndex++
            }
        }
    }

    private fun parseBorderFill(r: Rec): BorderFill {
        val bf = BorderFill()
        fun line(o: Int): BorderLine {
            val t = r.u8(o)
            val w = BORDER_WIDTHS.getOrElse(r.u8(o + 1)) { 0.12f } * 72f / 25.4f
            return BorderLine(BORDER_TYPES.getOrElse(t) { "SOLID" }, w, colorRef(r.u32(o + 2)))
        }
        val prop = r.u16(0)
        bf.left = line(2)
        bf.right = line(8)
        bf.top = line(14)
        bf.bottom = line(20)
        bf.diagonal = line(26)
        bf.slash = (prop shr 2) and 7 != 0
        bf.backSlash = (prop shr 5) and 7 != 0
        val fillType = r.u32(32)
        if (fillType and 1L != 0L) {
            val c = r.u32(36)
            if (c != 0xFFFFFFFFL) bf.fillColor = colorRef(c)
        }
        return bf
    }

    private fun parseCharShape(r: Rec): CharShape {
        val cs = CharShape()
        for (l in 0 until Lang.COUNT) {
            cs.faceIds[l] = r.u16(l * 2)
            cs.ratios[l] = r.u8(14 + l)
            cs.spacings[l] = r.i8(21 + l)
            cs.relSizes[l] = r.u8(28 + l)
            cs.offsets[l] = r.i8(35 + l)
        }
        cs.height = r.i32(42)
        val prop = r.u32(46)
        cs.italic = prop and 1L != 0L
        cs.bold = prop and 2L != 0L
        cs.underline = when ((prop shr 2) and 3L) {
            1L -> "BOTTOM"
            2L -> "CENTER"
            3L -> "TOP"
            else -> null
        }
        cs.outline = (prop shr 8) and 7L != 0L
        cs.supscript = prop and (1L shl 15) != 0L
        cs.subscript = prop and (1L shl 16) != 0L
        cs.strike = (prop shr 18) and 7L != 0L
        cs.color = colorRef(r.u32(52))
        cs.underlineColor = colorRef(r.u32(56))
        val shade = r.u32(60)
        cs.shadeColor = if (shade == 0xFFFFFFFFL || (shade and 0xFFFFFF) == 0xFFFFFFL || shade shr 24 != 0L) null else colorRef(shade)
        cs.strikeColor = if (r.data.size >= 74) colorRef(r.u32(70)) else cs.color
        return cs
    }

    private fun parseParaShape(r: Rec): ParaShape {
        val ps = ParaShape()
        val p1 = r.u32(0)
        ps.align = when (((p1 shr 2) and 7L).toInt()) {
            1 -> Align.LEFT
            2 -> Align.RIGHT
            3 -> Align.CENTER
            4 -> Align.DISTRIBUTE
            5 -> Align.DISTRIBUTE_SPACE
            else -> Align.JUSTIFY
        }
        // 바이너리 파일의 여백 값은 실제의 2배로 저장되어 있다
        ps.left = r.i32(4) / 2
        ps.right = r.i32(8) / 2
        ps.indent = r.i32(12) / 2
        ps.prev = r.i32(16) / 2
        ps.next = r.i32(20) / 2
        ps.headingType = when (((p1 shr 23) and 3L).toInt()) {
            1 -> "OUTLINE"
            2 -> "NUMBER"
            3 -> "BULLET"
            else -> "NONE"
        }
        ps.headingLevel = ((p1 shr 25) and 7L).toInt()
        ps.headingIdRef = r.u16(30)
        ps.borderFillId = r.u16(32)
        ps.borderOffsetLeft = r.i16(34)
        ps.borderOffsetRight = r.i16(36)
        ps.borderOffsetTop = r.i16(38)
        ps.borderOffsetBottom = r.i16(40)
        val lsType: Int
        if (r.data.size >= 54) {
            lsType = (r.u32(46) and 0x1F).toInt()
            ps.lineSpacing = r.i32(50)
        } else {
            lsType = (p1 and 3L).toInt()
            ps.lineSpacing = r.i32(24)
        }
        ps.lineSpacingType = when (lsType) {
            1 -> "FIXED"
            2 -> "BETWEEN_LINES"
            3 -> "AT_LEAST"
            else -> "PERCENT"
        }
        return ps
    }

    private fun parseNumbering(r: Rec): Numbering {
        val num = Numbering()
        var o = 0
        for (level in 0 until 7) {
            if (o + 14 > r.data.size) break
            val prop = r.u32(o)
            num.numTypes[level] = NUM_FORMATS.getOrElse(((prop shr 5) and 0xF).toInt()) { "DIGIT" }
            val len = r.u16(o + 12)
            num.formats[level] = r.wstr(o + 14, len)
            o += 14 + len * 2
        }
        val start = r.u16(o)
        if (start > 0) for (l in 0 until 10) num.starts[l] = start
        return num
    }

    // ================= 본문 =================

    private fun parseParas(recs: List<Rec>): List<HPara> = recs.filter { it.tag == T_PARA_HEADER }.map { parsePara(it) }

    private fun parsePara(r: Rec): HPara {
        val paraShapeId = r.u16(8)
        val styleId = r.u8(10)
        val breakType = r.u8(11)
        val textRec = r.child(T_PARA_TEXT)
        val csRec = r.child(T_PARA_CHAR_SHAPE)
        val segRec = r.child(T_PARA_LINE_SEG)
        val ctrls = r.children.filter { it.tag == T_CTRL_HEADER }

        // 글자 모양 바뀌는 위치
        val csPos = ArrayList<Pair<Int, Int>>()
        if (csRec != null) {
            var o = 0
            while (o + 8 <= csRec.data.size) {
                csPos.add(csRec.i32(o) to csRec.i32(o + 4))
                o += 8
            }
        }
        if (csPos.isEmpty()) csPos.add(0 to 0)
        fun csAt(pos: Int): Int {
            var id = csPos[0].second
            for ((p, c) in csPos) if (p <= pos) id = c else break
            return id
        }

        val items = ArrayList<PItem>()
        if (textRec != null) {
            val n = textRec.data.size / 2
            val sb = StringBuilder()
            var sbStart = 0
            var sbCs = -1
            fun flush() {
                if (sb.isNotEmpty()) {
                    items.add(PItem.Text(sbStart, sb.toString(), sbCs))
                    sb.setLength(0)
                }
            }
            fun addChar(pos: Int, ch: Char) {
                val cs = csAt(pos)
                if (sb.isEmpty() || cs != sbCs || sbStart + sb.length != pos) {
                    flush()
                    sbStart = pos
                    sbCs = cs
                }
                sb.append(ch)
            }
            var ctrlIdx = 0
            var i = 0
            while (i < n) {
                val c = textRec.u16(i * 2)
                if (c >= 32) {
                    addChar(i, c.toChar())
                    i++
                    continue
                }
                when (c) {
                    10 -> { addChar(i, '\n'); i++ }
                    13 -> { i++ }
                    24 -> { addChar(i, '­'); i++ }
                    30 -> { addChar(i, ' '); i++ }
                    31 -> { addChar(i, ' '); i++ }
                    0 -> { i++ }
                    9 -> {
                        flush()
                        val width = textRec.i32((i + 1) * 2)
                        items.add(PItem.Tab(i, if (width in 1..200000) width else 4000, "NONE", csAt(i)))
                        i += 8
                    }
                    // 필드 끝: 짝이 되는 시작과 함께 링크 글자의 범위를 이룬다 (HLinks)
                    4 -> { flush(); items.add(PItem.Ctrl(i, HCtrl.FieldEnd, csAt(i))); i += 8 }
                    5, 6, 7, 8, 19, 20 -> { flush(); i += 8 }
                    // 필드 시작: 하이퍼링크가 아닌 필드도 끝과 짝을 맞추려고 주소 없는 시작으로 둔다
                    3 -> {
                        flush()
                        val ctrl = ctrls.getOrNull(ctrlIdx++)
                        val item = ctrl?.let { parseCtrl(it, i, csAt(i)) }
                        items.add(if (item is PItem.Ctrl && item.ctrl is HCtrl.FieldBegin) item else PItem.Ctrl(i, HCtrl.FieldBegin(null), csAt(i)))
                        i += 8
                    }
                    else -> {
                        // 확장 컨트롤: 순서대로 CTRL_HEADER 와 짝을 이룬다
                        flush()
                        val ctrl = ctrls.getOrNull(ctrlIdx++)
                        if (ctrl != null) items.add(parseCtrl(ctrl, i, csAt(i)))
                        i += 8
                    }
                }
            }
            flush()
        }

        val segs = ArrayList<LineSeg>()
        if (segRec != null) {
            var o = 0
            while (o + 36 <= segRec.data.size) {
                segs.add(
                    LineSeg(
                        segRec.i32(o), segRec.i32(o + 4), segRec.i32(o + 8), segRec.i32(o + 12),
                        segRec.i32(o + 16), segRec.i32(o + 20), segRec.i32(o + 24), segRec.i32(o + 28), segRec.i32(o + 32)
                    )
                )
                o += 36
            }
        }
        return HPara(paraShapeId, styleId, breakType and 4 != 0, breakType and 8 != 0, items, segs)
    }

    private fun parseCtrl(r: Rec, pos: Int, cs: Int): PItem {
        val cid = r.i32(0)
        return when (cid) {
            ID_SECD -> {
                val pd = PageDef()
                r.child(T_PAGE_DEF)?.let { p ->
                    pd.width = p.i32(0); pd.height = p.i32(4)
                    pd.left = p.i32(8); pd.right = p.i32(12)
                    pd.top = p.i32(16); pd.bottom = p.i32(20)
                    pd.header = p.i32(24); pd.footer = p.i32(28); pd.gutter = p.i32(32)
                    pd.landscape = p.u32(36) and 1L != 0L
                    if (pd.landscape && pd.width < pd.height) {
                        val t = pd.width; pd.width = pd.height; pd.height = t
                    }
                }
                val shapes = r.children.filter { it.tag == T_FOOTNOTE_SHAPE }.map { parseNoteShape(it) }
                PItem.Ctrl(pos, HCtrl.SectionDef(pd, shapes.getOrElse(0) { NoteShape() }, shapes.getOrElse(1) { NoteShape() }), cs)
            }
            ID_FN, ID_EN -> {
                val number = r.i32(4)
                val prefix = r.u16(8).let { if (it == 0) "" else it.toChar().toString() }
                val suffix = r.u16(10).let { if (it == 0) "" else it.toChar().toString() }
                val format = NUM_FORMATS.getOrElse(r.i32(12) and 0xFF) { "DIGIT" }
                PItem.Ctrl(pos, HCtrl.Note(cid == ID_EN, number, format, prefix, suffix, parseParas(r.children)), cs)
            }
            ID_TBL -> PItem.Obj(pos, parseTable(r), cs)
            ID_GSO -> {
                val sc = r.child(T_SHAPE_COMPONENT)
                val obj: HObject = if (sc != null) parseShapeComponent(sc, true) else HShape("rect")
                readCommon(r, obj)
                // 글상자 등 도형은 개체 머리의 크기가 옛 값으로 남아 있는 경우가 있어 현재 크기를 쓴다
                // (그림은 현재 크기에 배율이 따로 곱해지므로 개체 머리의 크기가 맞다)
                if (obj is HShape && obj.curWidth > 0 && obj.curHeight > 0) {
                    obj.width = obj.curWidth
                    obj.height = obj.curHeight
                }
                PItem.Obj(pos, obj, cs)
            }
            ID_EQED -> {
                val eq = HEquation()
                readCommon(r, eq)
                r.child(T_EQEDIT)?.let { e ->
                    val len = e.u16(4)
                    eq.script = e.wstr(6, len)
                    val o = 6 + len * 2
                    eq.baseUnit = e.i32(o).takeIf { it in 100..100000 } ?: 1000
                    eq.color = colorRef(e.u32(o + 4))
                    eq.baseLine = e.i16(o + 8).takeIf { it in 1..100 } ?: 85
                }
                PItem.Obj(pos, eq, cs)
            }
            ID_HEAD, ID_FOOT -> {
                val apply = when ((r.u32(4) and 3L).toInt()) {
                    1 -> "EVEN"
                    2 -> "ODD"
                    else -> "BOTH"
                }
                val list = r.child(T_LIST_HEADER)
                val h = list?.i32(12) ?: 0
                val paras = parseParas(r.children)
                PItem.Ctrl(pos, if (cid == ID_HEAD) HCtrl.Header(apply, paras, h) else HCtrl.Footer(apply, paras, h), cs)
            }
            ID_PGNP -> {
                val prop = r.u32(4)
                val format = NUM_FORMATS.getOrElse((prop and 0xFF).toInt()) { "DIGIT" }
                val posName = when (((prop shr 8) and 0xF).toInt()) {
                    0 -> "NONE"
                    1 -> "TOP_LEFT"
                    2 -> "TOP_CENTER"
                    3 -> "TOP_RIGHT"
                    4 -> "BOTTOM_LEFT"
                    6 -> "BOTTOM_RIGHT"
                    7 -> "OUTSIDE_TOP"
                    8 -> "OUTSIDE_BOTTOM"
                    9 -> "INSIDE_TOP"
                    10 -> "INSIDE_BOTTOM"
                    else -> "BOTTOM_CENTER"
                }
                val side = r.u16(14).let { if (it == 0) "" else it.toChar().toString() }
                PItem.Ctrl(pos, HCtrl.PageNum(posName, format, side), cs)
            }
            ID_ATNO -> {
                val prop = r.u32(4)
                val type = when ((prop and 0xF).toInt()) {
                    0 -> "PAGE"
                    1 -> "FOOTNOTE"
                    2 -> "ENDNOTE"
                    3 -> "PICTURE"
                    4 -> "TABLE"
                    5 -> "EQUATION"
                    else -> "TOTAL_PAGE"
                }
                val format = NUM_FORMATS.getOrElse(((prop shr 4) and 0xFF).toInt()) { "DIGIT" }
                val prefix = r.u16(12).let { if (it == 0) "" else it.toChar().toString() }
                val suffix = r.u16(14).let { if (it == 0) "" else it.toChar().toString() }
                PItem.Ctrl(
                    pos,
                    if (type in setOf("PAGE", "TOTAL_PAGE", "FOOTNOTE", "ENDNOTE")) HCtrl.AutoNum(type, format, prefix, suffix) else HCtrl.Other,
                    cs
                )
            }
            ID_NWNO -> {
                val type = if ((r.u32(4) and 0xF) == 0L) "PAGE" else "OTHER"
                PItem.Ctrl(pos, HCtrl.NewNum(type, r.u16(8)), cs)
            }
            ID_PGHD -> {
                val p = r.u32(4)
                PItem.Ctrl(pos, HCtrl.PageHide(p and 1L != 0L, p and 2L != 0L, p and 32L != 0L), cs)
            }
            // 하이퍼링크: 속성(4) 기타(1) 명령 길이(2) 명령(글자) 순서
            ID_HLK -> PItem.Ctrl(pos, HCtrl.FieldBegin(HLinks.parseCommand(r.wstr(11, r.u16(9)))), cs)
            else -> PItem.Ctrl(pos, HCtrl.Other, cs)
        }
    }

    private fun parseNoteShape(r: Rec): NoteShape {
        val ns = NoteShape()
        ns.format = NUM_FORMATS.getOrElse((r.u32(0) and 0xFF).toInt()) { "DIGIT" }
        ns.prefix = r.u16(6).let { if (it == 0) "" else it.toChar().toString() }
        ns.suffix = r.u16(8).let { if (it == 0) "" else it.toChar().toString() }
        ns.lineLength = r.i32(12)
        ns.above = r.u16(16)
        ns.below = r.u16(18)
        ns.between = r.u16(20)
        val type = r.u8(22)
        ns.lineVisible = type != 0
        ns.lineWidth = BORDER_WIDTHS.getOrElse(r.u8(23)) { 0.12f } * 72f / 25.4f
        ns.lineColor = colorRef(r.u32(24))
        return ns
    }

    /** 개체 공통 속성 (CTRL_HEADER 의 ctrlId 다음) */
    private fun readCommon(r: Rec, o: HObject) {
        val prop = r.u32(4)
        o.treatAsChar = prop and 1L != 0L
        o.vertRelTo = when (((prop shr 3) and 3L).toInt()) { 0 -> "PAPER"; 1 -> "PAGE"; else -> "PARA" }
        o.vertAlign = when (((prop shr 5) and 7L).toInt()) { 1 -> "CENTER"; 2 -> "BOTTOM"; 3 -> "INSIDE"; 4 -> "OUTSIDE"; else -> "TOP" }
        o.horzRelTo = when (((prop shr 8) and 3L).toInt()) { 0 -> "PAPER"; 1 -> "PAGE"; 2 -> "COLUMN"; else -> "PARA" }
        o.horzAlign = when (((prop shr 10) and 7L).toInt()) { 1 -> "CENTER"; 2 -> "RIGHT"; 3 -> "INSIDE"; 4 -> "OUTSIDE"; else -> "LEFT" }
        o.textWrap = when (((prop shr 21) and 7L).toInt()) { 0 -> "SQUARE"; 2 -> "BEHIND_TEXT"; 3 -> "IN_FRONT_OF_TEXT"; else -> "TOP_AND_BOTTOM" }
        o.vertOffset = r.i32(8)
        o.horzOffset = r.i32(12)
        o.width = r.i32(16)
        o.height = r.i32(20)
        o.zOrder = r.i32(24)
        o.outLeft = r.i16(28)
        o.outRight = r.i16(30)
        o.outTop = r.i16(32)
        o.outBottom = r.i16(34)
    }

    private fun parseTable(r: Rec): HTable {
        val t = HTable()
        readCommon(r, t)
        val tr = r.child(T_TABLE)
        if (tr != null) {
            val prop = tr.u32(0)
            t.pageBreak = when ((prop and 3L).toInt()) { 0 -> "NONE"; 1 -> "CELL"; else -> "TABLE" }
            t.repeatHeader = prop and 4L != 0L
            t.rowCnt = tr.u16(4)
            t.colCnt = tr.u16(6)
            t.cellSpacing = tr.u16(8)
            t.inLeft = tr.u16(10); t.inRight = tr.u16(12); t.inTop = tr.u16(14); t.inBottom = tr.u16(16)
            t.borderFillId = tr.u16(18 + t.rowCnt * 2)
        }
        // 셀: LIST_HEADER 뒤에 그 셀의 문단들이 이어진다
        var cell: HCell? = null
        val cellParas = ArrayList<Rec>()
        fun finish() {
            cell?.let { it.paras = parseParas(cellParas); t.cells.add(it) }
            cellParas.clear()
        }
        for (c in r.children) {
            when (c.tag) {
                T_LIST_HEADER -> {
                    finish()
                    val ce = HCell()
                    val prop = c.u32(4)
                    ce.vertAlign = when (((prop shr 5) and 3L).toInt()) { 0 -> "TOP"; 2 -> "BOTTOM"; else -> "CENTER" }
                    ce.col = c.u16(8); ce.row = c.u16(10)
                    ce.colSpan = c.u16(12).coerceAtLeast(1); ce.rowSpan = c.u16(14).coerceAtLeast(1)
                    ce.width = c.i32(16); ce.height = c.i32(20)
                    ce.marginLeft = c.u16(24); ce.marginRight = c.u16(26)
                    ce.marginTop = c.u16(28); ce.marginBottom = c.u16(30)
                    ce.borderFillId = c.u16(32)
                    ce.hasMargin = true
                    ce.header = t.repeatHeader && ce.row == 0
                    cell = ce
                }
                T_PARA_HEADER -> if (cell != null) cellParas.add(c)
            }
        }
        finish()
        return t
    }

    /** SHAPE_COMPONENT → 그림/도형 */
    private fun parseShapeComponent(sc: Rec, topLevel: Boolean): HShapeObj {
        val id1 = sc.i32(0)
        val id2 = sc.i32(4)
        val b = if (topLevel || id1 == id2) 8 else 4
        val kindId = id1
        val obj: HShapeObj = when (kindId) {
            ID_PIC -> HPicture()
            ID_REC -> HShape("rect")
            ID_ELL -> HShape("ellipse")
            ID_LIN -> HShape("line")
            ID_CON -> HShape("container")
            ID_POL -> HShape("polygon")
            ID_CUR -> HShape("curve")
            else -> HShape("rect")
        }
        obj.offsetX = sc.i32(b)
        obj.offsetY = sc.i32(b + 4)
        obj.orgWidth = sc.i32(b + 12)
        obj.orgHeight = sc.i32(b + 16)
        obj.curWidth = sc.i32(b + 20)
        obj.curHeight = sc.i32(b + 24)
        obj.width = obj.curWidth
        obj.height = obj.curHeight
        val cnt = sc.u16(b + 42)
        var o = b + 44
        fun mat(at: Int) = FloatArray(6) { k -> sc.f64(at + k * 8).toFloat() }
        var m = mat(o)
        o += 48
        for (k in 0 until cnt) {
            m = Matrix2D.multiply(m, mat(o)); o += 48
            m = Matrix2D.multiply(m, mat(o)); o += 48
        }
        obj.matrix = m

        if (obj is HShape && kindId != ID_CON) {
            // 선, 채우기 정보
            val color = sc.u32(o)
            val thick = sc.i32(o + 4)
            val lprop = sc.u32(o + 8)
            val ltype = (lprop and 0x3F).toInt()
            // 선 종류 0 = 선 없음 (한글 편집 화면에서만 빨간 점선으로 보이는 투명 테두리)
            if (thick > 0 && ltype != 0) obj.line = LineStyle(colorRef(color), thick, BORDER_TYPES.getOrElse(ltype) { "SOLID" })
            val fo = o + 13
            val ftype = sc.u32(fo)
            if (ftype and 1L != 0L) {
                val fc = sc.u32(fo + 4)
                if (fc != 0xFFFFFFFFL) obj.fillColor = colorRef(fc)
            }
        }

        when (obj) {
            is HPicture -> sc.child(T_SC_PICTURE)?.let { p ->
                val thick = p.i32(4)
                if (thick > 0) obj.line = LineStyle(colorRef(p.u32(0)), thick, "SOLID")
                // 그림 원본 크기: imgRect 의 꼭짓점
                val xs = intArrayOf(p.i32(12), p.i32(20), p.i32(28), p.i32(36))
                val ys = intArrayOf(p.i32(16), p.i32(24), p.i32(32), p.i32(40))
                obj.imgWidth = xs.max() - xs.min()
                obj.imgHeight = ys.max() - ys.min()
                // 자르기 값은 그림 원래 크기(레코드 뒤쪽) 기준
                if (p.data.size >= 90 && p.i32(82) > 0 && p.i32(86) > 0) {
                    obj.imgWidth = p.i32(82)
                    obj.imgHeight = p.i32(86)
                }
                obj.clipLeft = p.i32(44); obj.clipTop = p.i32(48)
                obj.clipRight = p.i32(52); obj.clipBottom = p.i32(56)
                obj.binId = p.u16(71).toString()
            }
            is HShape -> {
                sc.child(T_SC_RECT)?.let { obj.roundRatio = it.u8(0) }
                sc.child(T_SC_LINE)?.let { l ->
                    obj.x0 = l.i32(0); obj.y0 = l.i32(4); obj.x1 = l.i32(8); obj.y1 = l.i32(12)
                }
                sc.child(T_SC_POLYGON)?.let { pg ->
                    val n = pg.i16(0)
                    for (k in 0 until n) obj.points.add(pg.i32(2 + k * 8) to pg.i32(6 + k * 8))
                }
                // 곡선: 점 개수(4바이트) 다음에 (x, y) 쌍
                sc.child(T_SC_CURVE)?.let { cv ->
                    val n = cv.i32(0).coerceIn(0, 10000)
                    for (k in 0 until n) {
                        if (4 + k * 8 + 8 > cv.data.size) break
                        obj.points.add(cv.i32(4 + k * 8) to cv.i32(8 + k * 8))
                    }
                }
                // 글상자
                val list = sc.child(T_LIST_HEADER)
                if (list != null) {
                    val prop = list.u32(4)
                    obj.textVertAlign = when (((prop shr 5) and 3L).toInt()) { 0 -> "TOP"; 2 -> "BOTTOM"; else -> "CENTER" }
                    obj.textMarginLeft = list.i16(8); obj.textMarginRight = list.i16(10)
                    obj.textMarginTop = list.i16(12); obj.textMarginBottom = list.i16(14)
                    obj.drawText = parseParas(sc.children)
                }
                if (kindId == ID_CON) {
                    for (c in sc.children) if (c.tag == T_SHAPE_COMPONENT) obj.children.add(parseShapeComponent(c, false))
                }
            }
        }
        return obj
    }
}

/** OLE 복합 문서(Compound File Binary) 읽기 */
class Cfb(file: File) {
    private val data: ByteArray = RandomAccessFile(file, "r").use { raf ->
        val b = ByteArray(raf.length().toInt())
        raf.readFully(b)
        b
    }
    private val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
    private val sectorSize: Int
    private val miniSectorSize: Int
    private val cutoff: Int
    private val fat: IntArray
    private val miniFat: IntArray
    private val miniStream: ByteArray
    private val entries = HashMap<String, Pair<Int, Long>>()

    init {
        if (data.size < 512 || bb.getInt(0) != 0xE011CFD0.toInt()) error("OLE 형식이 아닙니다.")
        sectorSize = 1 shl (bb.getShort(0x1E).toInt() and 0xFFFF)
        miniSectorSize = 1 shl (bb.getShort(0x20).toInt() and 0xFFFF)
        val nFat = bb.getInt(0x2C)
        val dir0 = bb.getInt(0x30)
        cutoff = bb.getInt(0x38)
        val miniFat0 = bb.getInt(0x3C)
        var difatSector = bb.getInt(0x44)

        val difat = ArrayList<Int>()
        for (k in 0 until 109) difat.add(bb.getInt(0x4C + k * 4))
        var guard = 0
        while (difatSector >= 0 && guard++ < 10000) {
            val off = sectorOffset(difatSector)
            val per = sectorSize / 4
            for (k in 0 until per - 1) difat.add(bb.getInt(off + k * 4))
            difatSector = bb.getInt(off + (per - 1) * 4)
        }
        val fatList = ArrayList<Int>()
        for (k in 0 until nFat) {
            val s = difat.getOrNull(k) ?: break
            if (s < 0) continue
            val off = sectorOffset(s)
            for (j in 0 until sectorSize / 4) fatList.add(if (off + j * 4 + 4 <= data.size) bb.getInt(off + j * 4) else -1)
        }
        fat = fatList.toIntArray()

        val dir = readChain(dir0)
        val dbb = ByteBuffer.wrap(dir).order(ByteOrder.LITTLE_ENDIAN)
        val count = dir.size / 128
        val names = Array(count) { "" }
        val types = IntArray(count)
        val left = IntArray(count)
        val right = IntArray(count)
        val child = IntArray(count)
        val start = IntArray(count)
        val size = LongArray(count)
        for (i in 0 until count) {
            val o = i * 128
            val nl = (dbb.getShort(o + 64).toInt() and 0xFFFF)
            val sb = StringBuilder()
            for (k in 0 until max0((nl - 2) / 2)) sb.append(dbb.getChar(o + k * 2))
            names[i] = sb.toString()
            types[i] = dir[o + 66].toInt()
            left[i] = dbb.getInt(o + 68)
            right[i] = dbb.getInt(o + 72)
            child[i] = dbb.getInt(o + 76)
            start[i] = dbb.getInt(o + 116)
            size[i] = dbb.getLong(o + 120)
        }
        miniStream = if (count > 0) readChain(start[0]) else ByteArray(0)
        miniFat = if (miniFat0 >= 0) {
            val mf = readChain(miniFat0)
            val mbb = ByteBuffer.wrap(mf).order(ByteOrder.LITTLE_ENDIAN)
            IntArray(mf.size / 4) { mbb.getInt(it * 4) }
        } else IntArray(0)

        val visited = HashSet<Int>()
        fun walk(idx: Int, prefix: String) {
            if (idx < 0 || idx >= count || !visited.add(idx)) return
            walk(left[idx], prefix)
            val path = prefix + names[idx]
            if (types[idx] == 2) entries[path] = start[idx] to size[idx]
            if ((types[idx] == 1 || types[idx] == 5) && child[idx] >= 0) {
                walk(child[idx], if (types[idx] == 5) "" else "$path/")
            }
            walk(right[idx], prefix)
        }
        walk(0, "")
    }

    private fun max0(v: Int) = if (v < 0) 0 else v

    private fun sectorOffset(s: Int) = 512 + s * sectorSize

    private fun readChain(first: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var s = first
        var guard = 0
        while (s >= 0 && guard++ < 2_000_000) {
            val off = sectorOffset(s)
            if (off >= data.size) break
            out.write(data, off, minOf(sectorSize, data.size - off))
            s = if (s < fat.size) fat[s] else -2
        }
        return out.toByteArray()
    }

    fun names(): Set<String> = entries.keys

    fun read(name: String): ByteArray? {
        val (start, size) = entries[name] ?: return null
        val n = size.toInt()
        if (size < cutoff) {
            val out = ByteArrayOutputStream(n)
            var s = start
            var guard = 0
            while (s >= 0 && guard++ < 2_000_000) {
                val off = s * miniSectorSize
                if (off >= miniStream.size) break
                out.write(miniStream, off, minOf(miniSectorSize, miniStream.size - off))
                s = if (s < miniFat.size) miniFat[s] else -2
            }
            val b = out.toByteArray()
            return if (b.size > n) b.copyOf(n) else b
        }
        val b = readChain(start)
        return if (b.size > n) b.copyOf(n) else b
    }
}
