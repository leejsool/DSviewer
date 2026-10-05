package com.dsviewer.app.hwp

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

/**
 * HWPX (한글 XML 형식, zip 압축) → [HDoc]
 */
class HwpxReader private constructor(private val zip: ZipFile) {

    private val fontFaces = Array<MutableList<String>>(Lang.COUNT) { mutableListOf() }
    private val fontFaceIds = Array(Lang.COUNT) { HashMap<Int, Int>() }
    private val charShapes = HashMap<Int, CharShape>()
    private val paraShapes = HashMap<Int, ParaShape>()
    private val borderFills = HashMap<Int, BorderFill>()
    private val numberings = HashMap<Int, Numbering>()
    private val bullets = HashMap<Int, Bullet>()
    private val binHrefs = HashMap<String, String>()

    companion object {
        fun read(file: File): HDoc {
            val zip = ZipFile(file)
            return HwpxReader(zip).readAll()
        }

        private val LANG_ATTRS = arrayOf("hangul", "latin", "hanja", "japanese", "other", "symbol", "user")
        private val LANG_NAMES = arrayOf("HANGUL", "LATIN", "HANJA", "JAPANESE", "OTHER", "SYMBOL", "USER")
    }

    private fun readAll(): HDoc {
        readManifest()
        zip.getEntry("Contents/header.xml")?.let { e -> zip.getInputStream(e).use { parseHeader(it) } }

        val sectionEntries = zip.entries().toList()
            .filter { it.name.matches(Regex("Contents/section\\d+\\.xml")) }
            .sortedBy { it.name.removePrefix("Contents/section").removeSuffix(".xml").toInt() }
        val sections = sectionEntries.map { e -> zip.getInputStream(e).use { parseSection(it) } }
        if (sections.isEmpty()) error("HWPX 본문을 찾을 수 없습니다.")

        return HDoc(
            sections = sections,
            fontFaces = Array(Lang.COUNT) { fontFaces[it].toList() },
            charShapes = charShapes,
            paraShapes = paraShapes,
            borderFills = borderFills,
            numberings = numberings,
            bullets = bullets,
            binLoader = { id -> loadBin(id) },
        )
    }

    private fun loadBin(id: String): ByteArray? {
        val href = binHrefs[id]
        val entry = (href?.let { zip.getEntry(it) ?: zip.getEntry("Contents/$it") })
            ?: zip.entries().toList().firstOrNull { it.name.startsWith("BinData/$id.") || it.name == "BinData/$id" }
            ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    // ================= 공통 =================

    private fun newParser(input: InputStream): XmlPullParser {
        val p = Xml.newPullParser()
        p.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        p.setInput(input, "UTF-8")
        return p
    }

    private fun XmlPullParser.attr(name: String): String? = getAttributeValue(null, name)
    private fun XmlPullParser.int(name: String, def: Int = 0): Int =
        attr(name)?.let { it.toIntOrNull() ?: it.toDoubleOrNull()?.toInt() } ?: def
    private fun XmlPullParser.bool(name: String): Boolean = attr(name).let { it == "1" || it == "true" }

    /** 현재 START_TAG 의 하위 요소를 모두 건너뛴다 */
    private fun XmlPullParser.skipTree() {
        var depth = 1
        while (depth > 0) {
            when (next()) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.END_TAG -> depth--
                XmlPullParser.END_DOCUMENT -> return
            }
        }
    }

    /** 현재 START_TAG 의 자식 START_TAG 마다 [block] 호출. block 은 해당 요소를 끝까지 소비해야 한다 */
    private inline fun XmlPullParser.children(block: (String) -> Unit) {
        val depth = depth
        while (true) {
            val ev = next()
            if (ev == XmlPullParser.END_TAG && this.depth == depth) return
            if (ev == XmlPullParser.END_DOCUMENT) return
            if (ev == XmlPullParser.START_TAG) block(name)
        }
    }

    private fun parseColor(s: String?): Int? {
        if (s == null || s == "none" || s.isEmpty()) return null
        val hex = s.removePrefix("#")
        return when (hex.length) {
            6 -> (0xFF000000 or hex.toLong(16)).toInt()
            8 -> {
                // #AARRGGBB (AA=투명도: 0 불투명)
                val v = hex.toLong(16)
                val a = 255 - ((v shr 24) and 0xFF).toInt()
                (a shl 24) or (v and 0xFFFFFF).toInt()
            }
            else -> null
        }
    }

    /** "0.12 mm" → pt */
    private fun parseWidth(s: String?): Float {
        if (s == null) return 0.1f * 72f / 25.4f
        val num = s.trim().split(' ')[0].toFloatOrNull() ?: 0.1f
        return num * 72f / 25.4f
    }

    // ================= content.hpf =================

    private fun readManifest() {
        val entry = zip.getEntry("Contents/content.hpf") ?: return
        zip.getInputStream(entry).use { input ->
            val p = newParser(input)
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType == XmlPullParser.START_TAG && p.name == "item") {
                    val id = p.attr("id")
                    val href = p.attr("href")
                    if (id != null && href != null) binHrefs[id] = href
                }
            }
        }
    }

    // ================= header.xml =================

    private fun parseHeader(input: InputStream) {
        val p = newParser(input)
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType != XmlPullParser.START_TAG) continue
            when (p.name) {
                "fontface" -> parseFontface(p)
                "charPr" -> parseCharPr(p)
                "paraPr" -> parseParaPr(p)
                "borderFill" -> parseBorderFill(p)
                "numbering" -> parseNumbering(p)
                "bullet" -> {
                    bullets[p.int("id")] = Bullet(p.attr("char") ?: "•")
                    p.skipTree()
                }
            }
        }
    }

    private fun parseFontface(p: XmlPullParser) {
        val lang = LANG_NAMES.indexOf(p.attr("lang")).takeIf { it >= 0 } ?: run { p.skipTree(); return }
        p.children { name ->
            if (name == "font") {
                fontFaceIds[lang][p.int("id")] = fontFaces[lang].size
                fontFaces[lang].add(p.attr("face") ?: "")
            }
            p.skipTree()
        }
    }

    private fun parseCharPr(p: XmlPullParser) {
        val cs = CharShape()
        val id = p.int("id")
        cs.height = p.int("height", 1000)
        cs.color = parseColor(p.attr("textColor")) ?: 0xFF000000.toInt()
        cs.shadeColor = parseColor(p.attr("shadeColor"))?.takeIf { it != 0xFFFFFFFF.toInt() }
        p.children { name ->
            when (name) {
                "fontRef" -> for (i in 0 until Lang.COUNT) {
                    val ref = p.int(LANG_ATTRS[i])
                    cs.faceIds[i] = fontFaceIds[i][ref] ?: ref
                }
                "ratio" -> for (i in 0 until Lang.COUNT) cs.ratios[i] = p.int(LANG_ATTRS[i], 100)
                "spacing" -> for (i in 0 until Lang.COUNT) cs.spacings[i] = p.int(LANG_ATTRS[i], 0)
                "relSz" -> for (i in 0 until Lang.COUNT) cs.relSizes[i] = p.int(LANG_ATTRS[i], 100)
                "offset" -> for (i in 0 until Lang.COUNT) cs.offsets[i] = p.int(LANG_ATTRS[i], 0)
                "bold" -> cs.bold = true
                "italic" -> cs.italic = true
                "underline" -> {
                    val t = p.attr("type")
                    if (t != null && t != "NONE") {
                        cs.underline = t
                        cs.underlineShape = p.attr("shape") ?: "SOLID"
                        cs.underlineColor = parseColor(p.attr("color")) ?: cs.color
                    }
                }
                "strikeout" -> {
                    val s = p.attr("shape")
                    if (s != null && s != "NONE" && s != "3D") {
                        cs.strike = true
                        cs.strikeColor = parseColor(p.attr("color")) ?: cs.color
                    }
                }
                "outline" -> cs.outline = p.attr("type").let { it != null && it != "NONE" }
                "supscript" -> cs.supscript = true
                "subscript" -> cs.subscript = true
            }
            p.skipTree()
        }
        charShapes[id] = cs
    }

    private fun parseParaPr(p: XmlPullParser) {
        val ps = ParaShape()
        val id = p.int("id")
        // hp:switch 안의 hp:case(신형 단위)를 우선, 없으면 hp:default, 그것도 없으면 바로 아래 값
        val direct = IntArray(5) { Int.MIN_VALUE }
        val caseVals = IntArray(5) { Int.MIN_VALUE }
        val defVals = IntArray(5) { Int.MIN_VALUE }
        var lsDirect: Pair<String, Int>? = null
        var lsCase: Pair<String, Int>? = null
        var lsDef: Pair<String, Int>? = null

        fun readMargin(target: IntArray) {
            p.children { n ->
                val v = p.int("value")
                when (n) {
                    "intent" -> target[0] = v
                    "left" -> target[1] = v
                    "right" -> target[2] = v
                    "prev" -> target[3] = v
                    "next" -> target[4] = v
                }
                p.skipTree()
            }
        }

        fun readSwitchBlock(target: IntArray): Pair<String, Int>? {
            var ls: Pair<String, Int>? = null
            p.children { n ->
                when (n) {
                    "margin" -> readMargin(target)
                    "lineSpacing" -> { ls = (p.attr("type") ?: "PERCENT") to p.int("value", 160); p.skipTree() }
                    else -> p.skipTree()
                }
            }
            return ls
        }

        p.children { name ->
            when (name) {
                "align" -> {
                    ps.align = when (p.attr("horizontal")) {
                        "LEFT" -> Align.LEFT
                        "RIGHT" -> Align.RIGHT
                        "CENTER" -> Align.CENTER
                        "DISTRIBUTE" -> Align.DISTRIBUTE
                        "DISTRIBUTE_SPACE" -> Align.DISTRIBUTE_SPACE
                        else -> Align.JUSTIFY
                    }
                    p.skipTree()
                }
                "heading" -> {
                    ps.headingType = p.attr("type") ?: "NONE"
                    ps.headingIdRef = p.int("idRef")
                    ps.headingLevel = p.int("level")
                    p.skipTree()
                }
                "margin" -> readMargin(direct)
                "lineSpacing" -> { lsDirect = (p.attr("type") ?: "PERCENT") to p.int("value", 160); p.skipTree() }
                "switch" -> p.children { n ->
                    when (n) {
                        "case" -> lsCase = readSwitchBlock(caseVals) ?: lsCase
                        "default" -> lsDef = readSwitchBlock(defVals) ?: lsDef
                        else -> p.skipTree()
                    }
                }
                "border" -> {
                    ps.borderFillId = p.int("borderFillIDRef")
                    ps.borderOffsetLeft = p.int("offsetLeft")
                    ps.borderOffsetRight = p.int("offsetRight")
                    ps.borderOffsetTop = p.int("offsetTop")
                    ps.borderOffsetBottom = p.int("offsetBottom")
                    p.skipTree()
                }
                else -> p.skipTree()
            }
        }
        val m = if (caseVals.any { it != Int.MIN_VALUE }) caseVals
        else if (defVals.any { it != Int.MIN_VALUE }) defVals else direct
        fun v(i: Int) = if (m[i] == Int.MIN_VALUE) 0 else m[i]
        ps.indent = v(0); ps.left = v(1); ps.right = v(2); ps.prev = v(3); ps.next = v(4)
        (lsCase ?: lsDef ?: lsDirect)?.let { ps.lineSpacingType = it.first; ps.lineSpacing = it.second }
        paraShapes[id] = ps
    }

    private fun parseBorderFill(p: XmlPullParser) {
        val bf = BorderFill()
        val id = p.int("id")
        fun line() = BorderLine(p.attr("type") ?: "NONE", parseWidth(p.attr("width")), parseColor(p.attr("color")) ?: 0xFF000000.toInt())
        p.children { name ->
            when (name) {
                "leftBorder" -> { bf.left = line(); p.skipTree() }
                "rightBorder" -> { bf.right = line(); p.skipTree() }
                "topBorder" -> { bf.top = line(); p.skipTree() }
                "bottomBorder" -> { bf.bottom = line(); p.skipTree() }
                "diagonal" -> { bf.diagonal = line(); p.skipTree() }
                "slash" -> { bf.slash = p.attr("type").let { it != null && it != "NONE" }; p.skipTree() }
                "backSlash" -> { bf.backSlash = p.attr("type").let { it != null && it != "NONE" }; p.skipTree() }
                "fillBrush" -> p.children { n ->
                    if (n == "winBrush") {
                        val c = parseColor(p.attr("faceColor"))
                        val alpha = p.int("alpha")
                        if (c != null && alpha < 255) bf.fillColor = c
                    }
                    p.skipTree()
                }
                else -> p.skipTree()
            }
        }
        borderFills[id] = bf
    }

    private fun parseNumbering(p: XmlPullParser) {
        val num = Numbering()
        val id = p.int("id")
        p.children { name ->
            if (name == "paraHead") {
                val level = p.int("level", 1).coerceIn(1, 10) - 1
                num.starts[level] = p.int("start", 1)
                num.numTypes[level] = p.attr("numFormat") ?: "DIGIT"
                val sb = StringBuilder()
                while (true) {
                    val ev = p.next()
                    if (ev == XmlPullParser.TEXT) sb.append(p.text)
                    if (ev == XmlPullParser.END_TAG && p.name == "paraHead") break
                    if (ev == XmlPullParser.END_DOCUMENT) break
                }
                num.formats[level] = sb.toString()
            } else p.skipTree()
        }
        numberings[id] = num
    }

    // ================= section*.xml =================

    private fun parseSection(input: InputStream): HSection {
        val p = newParser(input)
        val paras = ArrayList<HPara>()
        var def: HCtrl.SectionDef? = null
        while (p.next() != XmlPullParser.END_DOCUMENT) {
            if (p.eventType == XmlPullParser.START_TAG && p.name == "p") {
                val para = parsePara(p)
                if (def == null) {
                    def = para.items.firstNotNullOfOrNull { (it as? PItem.Ctrl)?.ctrl as? HCtrl.SectionDef }
                }
                paras.add(para)
            }
        }
        return HSection(paras).also { s -> def?.let { s.page = it.page; s.footShape = it.footShape; s.endShape = it.endShape } }
    }

    private fun parseParas(p: XmlPullParser): List<HPara> {
        val list = ArrayList<HPara>()
        p.children { name -> if (name == "p") list.add(parsePara(p)) else p.skipTree() }
        return list
    }

    private class ParaBuilder {
        val items = ArrayList<PItem>()
        var pos = 0
        val sb = StringBuilder()
        var textStart = 0
        var textCs = 0

        fun flush() {
            if (sb.isNotEmpty()) {
                items.add(PItem.Text(textStart, sb.toString(), textCs))
                sb.setLength(0)
            }
        }

        fun appendText(s: String, cs: Int) {
            if (s.isEmpty()) return
            if (sb.isEmpty() || textCs != cs) {
                flush()
                textStart = pos
                textCs = cs
            }
            sb.append(s)
            pos += s.length
        }

        fun add(item: PItem) {
            flush()
            items.add(item)
            pos += item.len
        }
    }

    private fun parsePara(p: XmlPullParser): HPara {
        val paraShapeId = p.int("paraPrIDRef")
        val styleId = p.int("styleIDRef")
        val pageBreak = p.bool("pageBreak")
        val columnBreak = p.bool("columnBreak")
        val b = ParaBuilder()
        val segs = ArrayList<LineSeg>()
        p.children { name ->
            when (name) {
                "run" -> parseRun(p, b)
                "linesegarray" -> p.children { n ->
                    if (n == "lineseg") {
                        segs.add(
                            LineSeg(
                                p.int("textpos"), p.int("vertpos"), p.int("vertsize"), p.int("textheight"),
                                p.int("baseline"), p.int("spacing"), p.int("horzpos"), p.int("horzsize"), p.int("flags")
                            )
                        )
                    }
                    p.skipTree()
                }
                else -> p.skipTree()
            }
        }
        b.flush()
        return HPara(paraShapeId, styleId, pageBreak, columnBreak, b.items, segs)
    }

    private fun parseRun(p: XmlPullParser, b: ParaBuilder) {
        val cs = p.int("charPrIDRef")
        p.children { name ->
            when (name) {
                "t" -> parseT(p, b, cs)
                "secPr" -> b.add(PItem.Ctrl(b.pos, parseSecPr(p), cs))
                "ctrl" -> p.children { n -> b.add(PItem.Ctrl(b.pos, parseCtrl(p, n), cs)) }
                "tbl" -> b.add(PItem.Obj(b.pos, parseTable(p), cs))
                "pic" -> b.add(PItem.Obj(b.pos, parsePicture(p), cs))
                "rect", "ellipse", "line", "container", "polygon", "arc", "curve", "connectLine" ->
                    b.add(PItem.Obj(b.pos, parseShape(p, name), cs))
                "equation" -> b.add(PItem.Obj(b.pos, parseEquation(p), cs))
                "markpenBegin", "markpenEnd", "titleMark", "insertBegin", "insertEnd", "deleteBegin", "deleteEnd" -> p.skipTree()
                else -> {
                    // 수식, OLE, 글자 겹침 등: 위치만 차지
                    p.skipTree()
                    b.add(PItem.Ctrl(b.pos, HCtrl.Other, cs))
                }
            }
        }
    }

    private fun parseT(p: XmlPullParser, b: ParaBuilder, cs: Int) {
        val depth = p.depth
        while (true) {
            val ev = p.next()
            if (ev == XmlPullParser.END_TAG && p.depth == depth) return
            if (ev == XmlPullParser.END_DOCUMENT) return
            when (ev) {
                XmlPullParser.TEXT -> b.appendText(p.text, cs)
                XmlPullParser.START_TAG -> {
                    when (p.name) {
                        "tab" -> b.add(PItem.Tab(b.pos, p.int("width", 4000), p.attr("leader") ?: "NONE", cs))
                        "lineBreak" -> b.appendText("\n", cs)
                        "hyphen" -> b.appendText("­", cs)
                        "nbSpace" -> b.appendText(" ", cs)
                        "fwSpace" -> b.appendText(" ", cs)
                    }
                    p.skipTree()
                }
            }
        }
    }

    private fun parseNotePr(p: XmlPullParser): NoteShape {
        val ns = NoteShape()
        p.children { n ->
            when (n) {
                "autoNumFormat" -> {
                    ns.format = p.attr("type") ?: "DIGIT"
                    ns.prefix = p.attr("prefixChar") ?: ""
                    ns.suffix = p.attr("suffixChar") ?: ""
                }
                "noteLine" -> {
                    ns.lineLength = p.int("length", -1)
                    ns.lineVisible = p.attr("type") != "NONE"
                    ns.lineWidth = parseWidth(p.attr("width"))
                    ns.lineColor = parseColor(p.attr("color")) ?: 0xFF000000.toInt()
                }
                "noteSpacing" -> {
                    ns.between = p.int("betweenNotes", 283)
                    ns.below = p.int("belowLine", 567)
                    ns.above = p.int("aboveLine", 850)
                }
            }
            p.skipTree()
        }
        return ns
    }

    private fun parseSecPr(p: XmlPullParser): HCtrl.SectionDef {
        val pd = PageDef()
        var foot = NoteShape()
        var end = NoteShape()
        p.children { name ->
            if (name == "footNotePr") { foot = parseNotePr(p); return@children }
            if (name == "endNotePr") { end = parseNotePr(p); return@children }
            if (name == "pagePr") {
                pd.width = p.int("width", 59528)
                pd.height = p.int("height", 84186)
                pd.landscape = p.attr("landscape") == "NARROWLY"
                p.children { n ->
                    if (n == "margin") {
                        pd.left = p.int("left", 5669); pd.right = p.int("right", 5669)
                        pd.top = p.int("top", 4251); pd.bottom = p.int("bottom", 4251)
                        pd.header = p.int("header", 2834); pd.footer = p.int("footer", 2834)
                        pd.gutter = p.int("gutter", 0)
                    }
                    p.skipTree()
                }
            } else p.skipTree()
        }
        // landscape="NARROWLY" 이면 가로 방향: 너비/높이 교환
        if (pd.landscape && pd.width < pd.height) {
            val t = pd.width; pd.width = pd.height; pd.height = t
        }
        return HCtrl.SectionDef(pd, foot, end)
    }

    private fun parseCtrl(p: XmlPullParser, name: String): HCtrl {
        return when (name) {
            "header", "footer" -> {
                val apply = p.attr("applyPageType") ?: "BOTH"
                var paras: List<HPara> = emptyList()
                var h = 0
                p.children { n ->
                    if (n == "subList") {
                        h = p.int("textHeight")
                        paras = parseParas(p)
                    } else p.skipTree()
                }
                if (name == "header") HCtrl.Header(apply, paras, h) else HCtrl.Footer(apply, paras, h)
            }
            "pageNum" -> {
                val c = HCtrl.PageNum(p.attr("pos") ?: "BOTTOM_CENTER", p.attr("formatType") ?: "DIGIT", p.attr("sideChar") ?: "")
                p.skipTree(); c
            }
            "autoNum" -> {
                val type = p.attr("numType") ?: "PAGE"
                var fmt = "DIGIT"
                var prefix = ""
                var suffix = ""
                p.children { n ->
                    if (n == "autoNumFormat") {
                        fmt = p.attr("type") ?: "DIGIT"
                        prefix = p.attr("prefixChar") ?: ""
                        suffix = p.attr("suffixChar") ?: ""
                    }
                    p.skipTree()
                }
                HCtrl.AutoNum(type, fmt, prefix, suffix)
            }
            "footNote", "endNote" -> {
                val number = p.int("number", 1)
                val suffix = p.attr("suffixChar") ?: ""
                var paras: List<HPara> = emptyList()
                p.children { n -> if (n == "subList") paras = parseParas(p) else p.skipTree() }
                HCtrl.Note(name == "endNote", number, "", "", suffix, paras)
            }
            // 필드: 하이퍼링크면 명령(Command)에서 주소를 꺼낸다. 끝(fieldEnd)과 짝을 이룬다
            "fieldBegin" -> {
                val link = p.attr("type") == "HYPERLINK"
                var command: String? = null
                p.children { n ->
                    if (n == "parameters") {
                        p.children { m ->
                            if (m == "stringParam" && p.attr("name") == "Command") command = p.nextText() else p.skipTree()
                        }
                    } else p.skipTree()
                }
                HCtrl.FieldBegin(if (link) HLinks.parseCommand(command) else null)
            }
            "fieldEnd" -> { p.skipTree(); HCtrl.FieldEnd }
            "newNum" -> {
                val c = HCtrl.NewNum(p.attr("numType") ?: "PAGE", p.int("num", 1))
                p.skipTree(); c
            }
            "pageHiding" -> {
                val c = HCtrl.PageHide(p.bool("hideHeader"), p.bool("hideFooter"), p.bool("hidePageNum"))
                p.skipTree(); c
            }
            else -> { p.skipTree(); HCtrl.Other }
        }
    }

    // ---- 개체 ----

    /** 개체 공통 하위 요소 처리. 처리했으면 true */
    private fun commonChild(p: XmlPullParser, name: String, o: HObject): Boolean {
        when (name) {
            "sz" -> { o.width = p.int("width"); o.height = p.int("height") }
            "pos" -> {
                o.treatAsChar = p.bool("treatAsChar")
                o.vertRelTo = p.attr("vertRelTo") ?: "PARA"
                o.horzRelTo = p.attr("horzRelTo") ?: "PARA"
                o.vertAlign = p.attr("vertAlign") ?: "TOP"
                o.horzAlign = p.attr("horzAlign") ?: "LEFT"
                o.vertOffset = p.int("vertOffset")
                o.horzOffset = p.int("horzOffset")
            }
            "outMargin" -> {
                o.outLeft = p.int("left"); o.outRight = p.int("right")
                o.outTop = p.int("top"); o.outBottom = p.int("bottom")
            }
            else -> return false
        }
        p.skipTree()
        return true
    }

    private fun objAttrs(p: XmlPullParser, o: HObject) {
        o.textWrap = p.attr("textWrap") ?: "TOP_AND_BOTTOM"
        o.zOrder = p.int("zOrder")
    }

    private fun parseTable(p: XmlPullParser): HTable {
        val t = HTable()
        objAttrs(p, t)
        t.rowCnt = p.int("rowCnt")
        t.colCnt = p.int("colCnt")
        t.cellSpacing = p.int("cellSpacing")
        t.borderFillId = p.int("borderFillIDRef")
        t.repeatHeader = p.bool("repeatHeader")
        t.pageBreak = p.attr("pageBreak") ?: "CELL"
        p.children { name ->
            if (commonChild(p, name, t)) return@children
            when (name) {
                "inMargin" -> {
                    t.inLeft = p.int("left"); t.inRight = p.int("right")
                    t.inTop = p.int("top"); t.inBottom = p.int("bottom")
                    p.skipTree()
                }
                "tr" -> p.children { n -> if (n == "tc") t.cells.add(parseCell(p)) else p.skipTree() }
                else -> p.skipTree()
            }
        }
        for (c in t.cells) if (!c.hasMargin) {
            c.marginLeft = t.inLeft; c.marginRight = t.inRight
            c.marginTop = t.inTop; c.marginBottom = t.inBottom
        }
        return t
    }

    private fun parseCell(p: XmlPullParser): HCell {
        val c = HCell()
        c.header = p.bool("header")
        c.hasMargin = p.bool("hasMargin")
        c.borderFillId = p.int("borderFillIDRef")
        p.children { name ->
            when (name) {
                "subList" -> {
                    c.vertAlign = p.attr("vertAlign") ?: "CENTER"
                    c.paras = parseParas(p)
                }
                "cellAddr" -> { c.col = p.int("colAddr"); c.row = p.int("rowAddr"); p.skipTree() }
                "cellSpan" -> { c.colSpan = p.int("colSpan", 1).coerceAtLeast(1); c.rowSpan = p.int("rowSpan", 1).coerceAtLeast(1); p.skipTree() }
                "cellSz" -> { c.width = p.int("width"); c.height = p.int("height"); p.skipTree() }
                "cellMargin" -> {
                    c.marginLeft = p.int("left"); c.marginRight = p.int("right")
                    c.marginTop = p.int("top"); c.marginBottom = p.int("bottom")
                    p.skipTree()
                }
                else -> p.skipTree()
            }
        }
        return c
    }

    /** 그림/도형 공통 하위 요소. 처리했으면 true */
    private fun shapeChild(p: XmlPullParser, name: String, s: HShapeObj): Boolean {
        if (commonChild(p, name, s)) return true
        when (name) {
            "offset" -> { s.offsetX = p.int("x"); s.offsetY = p.int("y"); p.skipTree() }
            "orgSz" -> { s.orgWidth = p.int("width"); s.orgHeight = p.int("height"); p.skipTree() }
            "curSz" -> { s.curWidth = p.int("width"); s.curHeight = p.int("height"); p.skipTree() }
            "renderingInfo" -> {
                // trans × (sca × rot)... 순서대로 곱한다
                var m = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f)
                p.children { n ->
                    val e = FloatArray(6) { i -> p.attr("e${i + 1}")?.toFloatOrNull() ?: if (i == 0 || i == 4) 1f else 0f }
                    m = Matrix2D.multiply(m, e)
                    p.skipTree()
                }
                s.matrix = m
            }
            "lineShape" -> {
                val w = p.int("width")
                val style = p.attr("style") ?: "SOLID"
                s.line = if (w <= 0 && style == "SOLID" || style == "NONE") null
                else LineStyle(parseColor(p.attr("color")) ?: 0xFF000000.toInt(), w, style)
                p.skipTree()
            }
            "fillBrush" -> p.children { n ->
                if (n == "winBrush") {
                    val c = parseColor(p.attr("faceColor"))
                    if (c != null && p.int("alpha") < 255) s.fillColor = c
                }
                p.skipTree()
            }
            else -> return false
        }
        return true
    }

    private fun parsePicture(p: XmlPullParser): HPicture {
        val pic = HPicture()
        objAttrs(p, pic)
        p.children { name ->
            if (shapeChild(p, name, pic)) return@children
            when (name) {
                "img" -> { pic.binId = p.attr("binaryItemIDRef") ?: ""; p.skipTree() }
                "imgClip" -> {
                    pic.clipLeft = p.int("left"); pic.clipRight = p.int("right")
                    pic.clipTop = p.int("top"); pic.clipBottom = p.int("bottom")
                    p.skipTree()
                }
                "imgDim" -> { pic.imgWidth = p.int("dimwidth"); pic.imgHeight = p.int("dimheight"); p.skipTree() }
                else -> p.skipTree()
            }
        }
        return pic
    }

    private fun parseEquation(p: XmlPullParser): HEquation {
        val eq = HEquation()
        objAttrs(p, eq)
        eq.baseUnit = p.int("baseUnit", 1000)
        eq.baseLine = p.int("baseLine", 85)
        eq.color = parseColor(p.attr("textColor")) ?: 0xFF000000.toInt()
        p.children { name ->
            if (commonChild(p, name, eq)) return@children
            if (name == "script") {
                val sb = StringBuilder()
                val depth = p.depth
                while (true) {
                    val ev = p.next()
                    if (ev == XmlPullParser.TEXT) sb.append(p.text)
                    if (ev == XmlPullParser.END_TAG && p.depth == depth) break
                    if (ev == XmlPullParser.END_DOCUMENT) break
                }
                eq.script = sb.toString()
            } else p.skipTree()
        }
        return eq
    }

    private fun parseShape(p: XmlPullParser, kind: String): HShape {
        val s = HShape(kind)
        objAttrs(p, s)
        s.roundRatio = p.int("ratio")
        p.children { name ->
            if (shapeChild(p, name, s)) return@children
            when (name) {
                "drawText" -> p.children { n ->
                    when (n) {
                        "textMargin" -> {
                            s.textMarginLeft = p.int("left"); s.textMarginRight = p.int("right")
                            s.textMarginTop = p.int("top"); s.textMarginBottom = p.int("bottom")
                            p.skipTree()
                        }
                        "subList" -> {
                            s.textVertAlign = p.attr("vertAlign") ?: "CENTER"
                            s.drawText = parseParas(p)
                        }
                        else -> p.skipTree()
                    }
                }
                "startPt" -> { s.x0 = p.int("x"); s.y0 = p.int("y"); p.skipTree() }
                "endPt" -> { s.x1 = p.int("x"); s.y1 = p.int("y"); p.skipTree() }
                "pt" -> { s.points.add(p.int("x") to p.int("y")); p.skipTree() }
                "rect", "ellipse", "line", "container", "polygon", "arc", "curve", "connectLine" -> s.children.add(parseShape(p, name))
                "pic" -> s.children.add(parsePicture(p))
                else -> p.skipTree()
            }
        }
        return s
    }
}

/** 2x3 아핀 행렬 [a b c; d e f] (x' = a x + b y + c, y' = d x + e y + f) */
object Matrix2D {
    fun multiply(m: FloatArray, n: FloatArray): FloatArray = floatArrayOf(
        m[0] * n[0] + m[1] * n[3], m[0] * n[1] + m[1] * n[4], m[0] * n[2] + m[1] * n[5] + m[2],
        m[3] * n[0] + m[4] * n[3], m[3] * n[1] + m[4] * n[4], m[3] * n[2] + m[4] * n[5] + m[5],
    )

    fun apply(m: FloatArray, x: Float, y: Float): Pair<Float, Float> =
        (m[0] * x + m[1] * y + m[2]) to (m[3] * x + m[4] * y + m[5])
}
