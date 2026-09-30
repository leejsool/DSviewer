package com.dsviewer.app.conv

import com.dsviewer.app.hwp.Align
import com.dsviewer.app.hwp.BorderFill
import com.dsviewer.app.hwp.BorderLine
import com.dsviewer.app.hwp.HCell
import com.dsviewer.app.hwp.HCtrl
import com.dsviewer.app.hwp.HDoc
import com.dsviewer.app.hwp.HEquation
import com.dsviewer.app.hwp.HObject
import com.dsviewer.app.hwp.HPara
import com.dsviewer.app.hwp.HPicture
import com.dsviewer.app.hwp.HSection
import com.dsviewer.app.hwp.HShape
import com.dsviewer.app.hwp.HTable
import com.dsviewer.app.hwp.LineStyle
import com.dsviewer.app.hwp.Numbering
import com.dsviewer.app.hwp.PageDef
import com.dsviewer.app.hwp.ParaShape
import java.io.File
import java.util.zip.ZipFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * DOCX(워드) → [HDoc]. 문단·글자 모양(스타일 이어받기 포함), 번호·글머리표, 표(셀 합치기·테두리·배경),
 * 그림, 글상자, 머리말·꼬리말·쪽 번호, 각주, 수식(한글 수식으로 옮겨 그림)을 옮긴다.
 * 여러 단, 글 둘레로 흐르는 그림 배치, 변경 내용 추적 표시는 하지 않는다.
 */
class DocxReader private constructor(private val pkg: OpcPackage) {

    private val b = HBuilder()
    private val styles = HashMap<String, XNode>()
    private var defaultParaStyle: String? = null
    private var defaultTableStyle: String? = null
    private var defRPr: XNode? = null
    private var defPPr: XNode? = null
    private var theme = DmlTheme(null)
    private val absNums = HashMap<String, XNode>()
    private val nums = HashMap<String, XNode>()
    private val numIds = HashMap<String, Int>()
    private val footnotes = HashMap<String, XNode>()
    private var footCount = 0
    private var tabWidth = 3600
    /** 짝수·홀수 쪽 머리말을 따로 쓰는지 (설정에 없으면 짝수 쪽 머리말은 쓰지 않는다) */
    private var evenOdd = false
    private var docPart = "word/document.xml"

    /** 복합 필드(PAGE 등)를 읽는 중: 결과 부분을 건너뛸지 */
    private var fieldSkip = false
    private var fieldInstr = StringBuilder()
    private var inFieldCode = false

    companion object {
        fun read(file: File, maxPages: Int = 0): HDoc {
            val zip = ZipFile(file)
            return DocxReader(OpcPackage(zip)).readAll(maxPages)
        }

        private val HIGHLIGHT = mapOf(
            "yellow" to 0xFFFFFF00, "green" to 0xFF00FF00, "cyan" to 0xFF00FFFF, "magenta" to 0xFFFF00FF,
            "blue" to 0xFF0000FF, "red" to 0xFFFF0000, "darkBlue" to 0xFF000080, "darkCyan" to 0xFF008080,
            "darkGreen" to 0xFF008000, "darkMagenta" to 0xFF800080, "darkRed" to 0xFF800000, "darkYellow" to 0xFF808000,
            "darkGray" to 0xFF808080, "lightGray" to 0xFFC0C0C0, "black" to 0xFF000000, "white" to 0xFFFFFFFF,
        ).mapValues { it.value.toInt() }

        /** 기호 글꼴(Symbol·Wingdings)의 글머리표 → 유니코드 */
        private val SYMBOL_BULLETS = mapOf(
            '\uF0B7' to "•", '\uF0A7' to "▪", '\uF0D8' to "➢", '\uF0FC' to "✓", '\uF06E' to "■", '\uF076' to "❖",
            '\uF0A8' to "□", '\uF06C' to "•", '\uF071' to "❑", '\uF075' to "◆", '\uF0E0' to "➔", '\uF0E8' to "➔",
            '\uF0F0' to "⇨", '\uF06F' to "□", '\uF0A1' to "○", '\uF09F' to "•", '\uF02D' to "–",
        )

        fun bulletOf(ch: Char): String = SYMBOL_BULLETS[ch] ?: if (ch.code in 0xF000..0xF0FF) "•" else if (ch == 'o') "◦" else ch.toString()

        private fun on(n: XNode?): Boolean = n != null && n["val"].let { it == null || it == "1" || it == "true" || it == "on" }
    }

    private fun readAll(maxPages: Int): HDoc {
        docPart = pkg.relByType("", "officeDocument") ?: "word/document.xml"
        val doc = pkg.xml(docPart) ?: error("워드 본문을 찾을 수 없습니다.")
        pkg.relByType(docPart, "theme")?.let { theme = DmlTheme(pkg.xml(it)) }
        pkg.relByType(docPart, "styles")?.let { pkg.xml(it) }?.let { readStyles(it) }
        pkg.relByType(docPart, "numbering")?.let { pkg.xml(it) }?.let { readNumbering(it) }
        pkg.relByType(docPart, "settings")?.let { pkg.xml(it) }?.let { st ->
            st.child("defaultTabStop")?.int("val")?.let { if (it > 0) tabWidth = it * 5 }
            evenOdd = st.child("evenAndOddHeaders")?.let { on(it) } == true
        }
        pkg.relByType(docPart, "footnotes")?.let { pkg.xml(it) }?.children("footnote")?.forEach { fn ->
            if (fn["type"] == null || fn["type"] == "normal") fn["id"]?.let { footnotes[it] = fn }
        }

        val body = doc.child("body") ?: error("워드 본문을 찾을 수 없습니다.")
        // 구역마다 문단을 모은다. 구역 설정(sectPr)은 구역의 마지막 문단(또는 본문 끝)에 있다
        val groups = ArrayList<Pair<MutableList<XNode>, XNode?>>()
        var cur = ArrayList<XNode>()
        fun collect(container: XNode) {
            for (c in container.children) when (c.name) {
                "p" -> {
                    cur.add(c)
                    c.path("pPr", "sectPr")?.let { sp -> groups.add(cur to sp); cur = ArrayList() }
                }
                "tbl" -> cur.add(c)
                "sdt" -> c.child("sdtContent")?.let { collect(it) }
                "customXml" -> collect(c)
                "AlternateContent" -> (c.child("Choice") ?: c.child("Fallback"))?.let { collect(it) }
            }
        }
        collect(body)
        groups.add(cur to body.child("sectPr"))

        val sections = ArrayList<HSection>()
        // 썸네일처럼 앞쪽만 필요하면 앞부분만 읽는다
        val limit = if (maxPages > 0) 80 * maxPages else Int.MAX_VALUE
        var count = 0
        for ((k, g) in groups.withIndex()) {
            val (nodes, sectPr) = g
            if (nodes.isEmpty() && k > 0 && k == groups.size - 1) continue
            val paras = ArrayList<HPara>()
            // 구역 머리말·꼬리말은 첫 문단의 조판 부호로
            val first = HBuilder.Para()
            sectPr?.let { headerFooter(it, first) }
            if (first.items.isNotEmpty()) paras.add(first.build())
            for (n in nodes) {
                if (n.name == "p") paragraph(n, docPart, emptyList(), paras) else table(n, docPart, paras)
                if (++count >= limit) break
            }
            val page = pageDef(sectPr)
            // 이어지는 구역(continuous)은 앞 구역에 붙인다 (새 쪽으로 넘기지 않음)
            val type = sectPr?.child("type")?.get("val")
            if (type == "continuous" && sections.isNotEmpty()) {
                val prev = sections.removeAt(sections.size - 1)
                sections.add(HSection(prev.paras + paras).apply { this.page = prev.page })
            } else sections.add(HSection(paras).apply { this.page = page })
            if (count >= limit) break
        }
        if (sections.isEmpty()) sections.add(HSection(listOf(HBuilder.Para().build())).apply { page = pageDef(null) })
        return b.build(sections) { path -> pkg.bytes(path) }
    }

    // ================= 스타일·번호 =================

    private fun readStyles(root: XNode) {
        root.path("docDefaults", "rPrDefault", "rPr")?.let { defRPr = it }
        root.path("docDefaults", "pPrDefault", "pPr")?.let { defPPr = it }
        for (st in root.children("style")) {
            val id = st["styleId"] ?: continue
            styles[id] = st
            if (st.flag("default")) when (st["type"]) {
                "paragraph" -> defaultParaStyle = id
                "table" -> defaultTableStyle = id
            }
        }
    }

    /** 스타일과 그 바탕 스타일들 (가까운 것부터) */
    private fun styleChain(id: String?): List<XNode> {
        val out = ArrayList<XNode>()
        var cur = id
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) {
            val st = styles[cur] ?: break
            out.add(st)
            cur = st.child("basedOn")?.get("val")
        }
        return out
    }

    private fun readNumbering(root: XNode) {
        for (a in root.children("abstractNum")) a["abstractNumId"]?.let { absNums[it] = a }
        for (n in root.children("num")) n["numId"]?.let { nums[it] = n }
    }

    /** 번호 정의의 수준 [ilvl] (num의 덮어쓰기 먼저) */
    private fun numLevel(numId: String, ilvl: Int): XNode? {
        val num = nums[numId] ?: return null
        num.children("lvlOverride").firstOrNull { it.int("ilvl") == ilvl }?.child("lvl")?.let { return it }
        val abs = absNums[num.child("abstractNumId")?.get("val")] ?: return null
        abs.children("lvl").firstOrNull { it.int("ilvl") == ilvl }?.let { return it }
        // 다른 번호 정의를 따르는 경우
        val link = abs.child("numStyleLink")?.get("val") ?: return null
        val sid = styles[link]?.path("pPr", "numPr", "numId")?.get("val") ?: return null
        return if (sid != numId) numLevel(sid, ilvl) else null
    }

    private fun numberingId(numId: String): Int = numIds.getOrPut(numId) {
        val n = Numbering()
        for (lv in 0 until 9) {
            val lvl = numLevel(numId, lv) ?: continue
            val fmt = lvl.child("numFmt")?.get("val") ?: "decimal"
            val text = lvl.child("lvlText")?.get("val") ?: "%${lv + 1}."
            n.formats[lv] = text.replace(Regex("%(\\d)")) { "^" + it.groupValues[1] }
            n.numTypes[lv] = when (fmt) {
                "lowerLetter" -> "LATIN_SMALL"
                "upperLetter" -> "LATIN_CAPITAL"
                "lowerRoman" -> "ROMAN_SMALL"
                "upperRoman" -> "ROMAN_CAPITAL"
                "ganada", "koreanDigital" -> "HANGUL_SYLLABLE"
                "chosung" -> "HANGUL_JAMO"
                "decimalEnclosedCircle", "decimalEnclosedCircleChinese" -> "CIRCLED_DIGIT"
                "ideographTraditional", "koreanCounting", "chineseCounting" -> "IDEOGRAPH"
                else -> "DIGIT"
            }
            n.starts[lv] = lvl.child("start")?.int("val", 1) ?: 1
        }
        // 덮어쓴 시작 번호
        nums[numId]?.children("lvlOverride")?.forEach { o ->
            o.child("startOverride")?.let { n.starts[o.int("ilvl").coerceIn(0, 9)] = it.int("val", 1) }
        }
        b.numbering(n)
    }

    // ================= 쪽 설정 =================

    private fun pageDef(sectPr: XNode?): PageDef {
        val pd = PageDef()
        val sz = sectPr?.child("pgSz")
        val w = sz?.int("w", 11906) ?: 11906
        val h = sz?.int("h", 16838) ?: 16838
        pd.width = HBuilder.twip(w)
        pd.height = HBuilder.twip(h)
        val m = sectPr?.child("pgMar")
        val top = abs(m?.int("top", 1440) ?: 1440)
        val bottom = abs(m?.int("bottom", 1440) ?: 1440)
        val hd = m?.int("header", 720) ?: 720
        val ft = m?.int("footer", 720) ?: 720
        pd.left = HBuilder.twip(m?.int("left", 1440) ?: 1440)
        pd.right = HBuilder.twip(m?.int("right", 1440) ?: 1440)
        pd.gutter = HBuilder.twip(m?.int("gutter") ?: 0)
        pd.top = HBuilder.twip(minOf(hd, top))
        pd.header = HBuilder.twip(max(0, top - hd))
        pd.bottom = HBuilder.twip(minOf(ft, bottom))
        pd.footer = HBuilder.twip(max(0, bottom - ft))
        return pd
    }

    private fun headerFooter(sectPr: XNode, into: HBuilder.Para) {
        val titlePg = sectPr.child("titlePg")?.let { on(it) } == true
        for (kind in listOf("headerReference", "footerReference")) {
            for (ref in sectPr.children(kind)) {
                val type = ref["type"] ?: "default"
                if (type == "first" || (type == "even" && !evenOdd)) continue
                val part = pkg.relTarget(docPart, ref["id"]) ?: continue
                val root = pkg.xml(part) ?: continue
                val paras = ArrayList<HPara>()
                for (c in root.children) when (c.name) {
                    "p" -> paragraph(c, part, emptyList(), paras)
                    "tbl" -> table(c, part, paras)
                    "sdt" -> c.child("sdtContent")?.children?.forEach { x -> if (x.name == "p") paragraph(x, part, emptyList(), paras) }
                }
                val apply = if (type == "even") "EVEN" else "BOTH"
                into.ctrl(if (kind == "headerReference") HCtrl.Header(apply, paras, 0) else HCtrl.Footer(apply, paras, 0), 0)
            }
        }
        // 첫 쪽 머리말·꼬리말을 따로 두는 문서(표지 등): 첫 쪽에는 기본 머리말·꼬리말을 숨긴다
        if (titlePg) into.ctrl(HCtrl.PageHide(header = true, footer = true, pageNum = false), 0)
    }

    // ================= 문단 =================

    /** 표 셀 안이면 표 스타일의 글자·문단 모양 */
    private class Extra(val pPr: List<XNode>, val rPr: List<XNode>)

    private fun pProps(pPr: XNode?, extra: List<XNode>, numLvl: XNode?): List<XNode> {
        val list = ArrayList<XNode>()
        pPr?.let { list.add(it) }
        numLvl?.child("pPr")?.let { list.add(it) }
        styleChain(pPr?.child("pStyle")?.get("val") ?: defaultParaStyle).forEach { st -> st.child("pPr")?.let { list.add(it) } }
        list.addAll(extra)
        defPPr?.let { list.add(it) }
        return list
    }

    private fun rProps(rPr: XNode?, pStyle: String?, extra: List<XNode>): List<XNode> {
        val list = ArrayList<XNode>()
        rPr?.let { list.add(it) }
        styleChain(rPr?.child("rStyle")?.get("val")).forEach { st -> st.child("rPr")?.let { list.add(it) } }
        styleChain(pStyle ?: defaultParaStyle).forEach { st -> st.child("rPr")?.let { list.add(it) } }
        list.addAll(extra)
        defRPr?.let { list.add(it) }
        return list
    }

    private fun first(props: List<XNode>, name: String): XNode? = props.firstNotNullOfOrNull { it.child(name) }

    private fun themeColor(name: String?): Int? = when (name) {
        null -> null
        "text1", "dark1" -> theme.colors["dk1"]
        "background1", "light1" -> theme.colors["lt1"]
        "text2", "dark2" -> theme.colors["dk2"]
        "background2", "light2" -> theme.colors["lt2"]
        "hyperlink" -> theme.colors["hlink"]
        "followedHyperlink" -> theme.colors["folHlink"]
        else -> theme.colors[name]
    }

    private fun wordColor(n: XNode?): Int? {
        n ?: return null
        themeColor(n["themeColor"])?.let { base ->
            var c = base
            n["themeShade"]?.toIntOrNull(16)?.let { s -> c = scale(c, s / 255f, false) }
            n["themeTint"]?.toIntOrNull(16)?.let { t -> c = scale(c, t / 255f, true) }
            return c
        }
        val v = n["val"] ?: return null
        if (v == "auto") return null
        return Dml.parseHex(v)
    }

    private fun scale(c: Int, k: Float, tint: Boolean): Int {
        fun ch(v: Int) = if (tint) (v * k + 255 * (1 - k)).roundToInt() else (v * k).roundToInt()
        return 0xFF000000.toInt() or (ch((c shr 16) and 0xFF).coerceIn(0, 255) shl 16) or (ch((c shr 8) and 0xFF).coerceIn(0, 255) shl 8) or ch(c and 0xFF).coerceIn(0, 255)
    }

    private fun fontOf(rf: XNode?, ea: Boolean): String? {
        rf ?: return null
        if (ea) {
            rf["eastAsia"]?.let { return it }
            rf["eastAsiaTheme"]?.let { return if (it.startsWith("major")) theme.majorEa.ifBlank { theme.majorLatin } else theme.minorEa.ifBlank { theme.minorLatin } }
        } else {
            rf["ascii"]?.let { return it }
            rf["hAnsi"]?.let { return it }
            (rf["asciiTheme"] ?: rf["hAnsiTheme"])?.let { return if (it.startsWith("major")) theme.majorLatin else theme.minorLatin }
        }
        return null
    }

    /** 문단의 한 줄 높이 비율: 글자들이 쓰는 글꼴 중 가장 큰 것 */
    private fun lineFactor(p: XNode, pStyle: String?, extraR: List<XNode>, mark: HBuilder.Run): Float {
        var best = 0f
        for (r in p.descendants("r")) {
            val text = r.children("t").joinToString("") { it.text }
            if (text.isEmpty()) continue
            val run = runOf(rProps(r.child("rPr"), pStyle, extraR))
            val cjk = text.any { Character.UnicodeScript.of(it.code).let { sc -> sc == Character.UnicodeScript.HANGUL || sc == Character.UnicodeScript.HAN } }
            val latin = text.any { it.code < 0x2000 && !it.isWhitespace() }
            if (cjk) best = max(best, WordFonts.lineFactor(run.ea))
            if (latin) best = max(best, WordFonts.lineFactor(run.latin))
        }
        return if (best > 0f) best else WordFonts.lineFactor(mark.ea)
    }

    private fun runOf(props: List<XNode>): HBuilder.Run {
        val fonts = props.mapNotNull { it.child("rFonts") }
        val latin = fonts.firstNotNullOfOrNull { fontOf(it, ea = false) } ?: theme.minorLatin
        val ea = fonts.firstNotNullOfOrNull { fontOf(it, ea = true) } ?: theme.minorEa.ifBlank { latin }
        val sz = first(props, "sz")?.int("val", 20) ?: 20
        val size = sz * 50
        val va = first(props, "vertAlign")?.get("val")
        val shade = first(props, "highlight")?.get("val")?.let { HIGHLIGHT[it] }
            ?: first(props, "shd")?.get("fill")?.let { Dml.parseHex(it) }
        val spacing = first(props, "spacing")?.int("val")?.let { tw -> if (size > 0) tw * 5 * 100 / size else 0 } ?: 0
        return HBuilder.Run(
            latin = latin,
            ea = ea,
            size = size,
            color = wordColor(first(props, "color")) ?: 0xFF000000.toInt(),
            bold = on(first(props, "b")),
            italic = on(first(props, "i")),
            underline = first(props, "u")?.get("val").let { it != null && it != "none" },
            strike = on(first(props, "strike")) || on(first(props, "dstrike")),
            sup = va == "superscript",
            sub = va == "subscript",
            shade = shade,
            spacing = spacing,
            eaSpacing = if (WordFonts.fullWidthHangul(ea)) 8 else 0,
            spaceEm = if (WordFonts.fullWidthHangul(latin)) 0.5f else 0f,
        )
    }

    /** 문단 하나 (쪽 나누기가 있으면 여러 문단이 된다) → [out] */
    private fun paragraph(p: XNode, part: String, extra: List<XNode>, out: MutableList<HPara>, extraR: List<XNode> = emptyList()) {
        val pPr = p.child("pPr")
        val pStyle = pPr?.child("pStyle")?.get("val") ?: defaultParaStyle
        // 번호: 문단에 없으면 스타일에서
        val numPr = pPr?.child("numPr") ?: styleChain(pStyle).firstNotNullOfOrNull { it.path("pPr", "numPr") }
        val numId = numPr?.child("numId")?.get("val")?.takeIf { it != "0" }
        val ilvl = (numPr?.child("ilvl")?.int("val") ?: 0).coerceIn(0, 8)
        val lvl = numId?.let { numLevel(it, ilvl) }
        val props = pProps(pPr, extra, lvl)
        val markRun = runOf(rProps(pPr?.child("rPr"), pStyle, extraR))
        val baseSize = markRun.size

        val ps = ParaShape()
        ps.align = when (props.firstNotNullOfOrNull { it.child("jc")?.get("val") }) {
            "center" -> Align.CENTER
            "right", "end" -> Align.RIGHT
            "both" -> Align.JUSTIFY
            "distribute" -> Align.DISTRIBUTE
            else -> Align.LEFT
        }
        // 틀(frame)로 가운데·오른쪽에 둔 문단 (쪽 번호 등)
        when (pPr?.child("framePr")?.get("xAlign")) {
            "center" -> ps.align = Align.CENTER
            "right", "outside" -> ps.align = Align.RIGHT
        }
        val ind = props.mapNotNull { it.child("ind") }
        // 들여쓰기 값: 가까운 설정부터. 글자 단위(…Chars, 1/100 글자)가 0이 아니면 그것이 우선
        fun indentOf(tw: List<String>, ch: List<String>): Int? {
            for (n in ind) {
                val c = ch.firstNotNullOfOrNull { n[it]?.toIntOrNull() }
                if (c != null && c != 0) return c * baseSize / 100
                val t = tw.firstNotNullOfOrNull { n[it]?.toIntOrNull() }
                if (t != null) return HBuilder.twip(t)
            }
            return null
        }
        val left = indentOf(listOf("left", "start"), listOf("leftChars", "startChars")) ?: 0
        val right = indentOf(listOf("right", "end"), listOf("rightChars", "endChars")) ?: 0
        // 첫 줄: 가까운 설정이 내어쓰기(hanging)인지 들여쓰기(firstLine)인지
        val firstNode = ind.firstOrNull { n -> listOf("hanging", "firstLine", "hangingChars", "firstLineChars").any { n[it] != null } }
        fun num(n: XNode, a: String) = n[a]?.toIntOrNull()?.takeIf { it != 0 }
        val indent = when {
            firstNode == null -> 0
            num(firstNode, "hangingChars") != null -> -num(firstNode, "hangingChars")!! * baseSize / 100
            firstNode["hanging"] != null -> -HBuilder.twip(firstNode["hanging"]!!.toIntOrNull() ?: 0)
            num(firstNode, "firstLineChars") != null -> num(firstNode, "firstLineChars")!! * baseSize / 100
            else -> HBuilder.twip(firstNode["firstLine"]?.toIntOrNull() ?: 0)
        }
        ps.left = max(0, left + minOf(indent, 0))
        ps.right = max(0, right)
        ps.indent = indent

        val sp = props.mapNotNull { it.child("spacing") }
        fun spA(name: String) = sp.firstNotNullOfOrNull { it[name] }
        val line = spA("line")?.toIntOrNull()
        when (spA("lineRule") ?: "auto") {
            "exact" -> { ps.lineSpacingType = "FIXED"; ps.lineSpacing = HBuilder.twip(line ?: 240) }
            "atLeast" -> { ps.lineSpacingType = "AT_LEAST"; ps.lineSpacing = HBuilder.twip(line ?: 240) }
            else -> {
                ps.lineSpacingType = "PERCENT"
                // 워드의 '줄 간격 1'은 글꼴마다 다르다 (맑은 고딕은 글자 크기의 약 1.7배)
                val factor = lineFactor(p, pStyle, extraR, markRun)
                ps.lineSpacing = ((line ?: 240) / 240f * factor * 100f).roundToInt().coerceIn(60, 800)
            }
        }
        ps.leadAbove = 0.6f
        fun space(tw: String, lines: String, auto: String): Int {
            if (spA(auto).let { it == "1" || it == "true" }) return 1400
            spA(lines)?.toIntOrNull()?.let { return it * baseSize * 12 / 1000 }
            return HBuilder.twip(spA(tw)?.toIntOrNull() ?: 0)
        }
        ps.prev = space("before", "beforeLines", "beforeAutospacing")
        ps.next = space("after", "afterLines", "afterAutospacing")

        // 번호·글머리표 (번호 뒤 글은 내어쓰기 자리에서 시작)
        ps.prefixTab = true
        if (lvl != null) {
            val fmt = lvl.child("numFmt")?.get("val")
            if (fmt == "bullet") {
                val text = lvl.child("lvlText")?.get("val").orEmpty()
                val ch = text.firstOrNull()?.let { SYMBOL_BULLETS[it] ?: if (it.code in 0xF000..0xF0FF) "•" else if (it == 'o') "◦" else text } ?: ""
                if (ch.isNotBlank()) {
                    ps.headingType = "BULLET"
                    ps.headingIdRef = b.bullet(ch)
                }
            } else if (fmt != "none") {
                ps.headingType = "NUMBER"
                ps.headingIdRef = numberingId(numId)
                ps.headingLevel = ilvl
            }
        }
        val psId = b.paraShape(ps)
        // 쪽 나누기로 이어지는 문단은 번호 없이
        val contId = if (ps.headingType == "NONE") psId else b.paraShape(ParaShape().also { c ->
            c.align = ps.align; c.left = ps.left; c.right = ps.right; c.indent = 0; c.lineSpacingType = ps.lineSpacingType
            c.lineSpacing = ps.lineSpacing; c.next = ps.next
        })

        val state = PState(psId, contId, out, b.charShape(markRun))
        state.cur.pageBreak = on(first(props, "pageBreakBefore"))
        inline(p, part, pStyle, extraR, state)
        state.flush()
    }

    /** 쪽 나누기로 문단이 나뉘는 것까지 챙기며 문단을 만든다 */
    private inner class PState(val psId: Int, val contId: Int, val out: MutableList<HPara>, val endCs: Int) {
        var cur = HBuilder.Para(psId)
        fun pageBreak() {
            flush()
            cur = HBuilder.Para(contId).apply { pageBreak = true }
        }
        fun flush() {
            cur.endCharShape = endCs
            out.add(cur.build())
        }
    }

    /** 문단 안 요소들 (글자, 탭, 그림, 수식, 필드 …) */
    private fun inline(container: XNode, part: String, pStyle: String?, extraR: List<XNode>, st: PState) {
        for (c in container.children) {
            when (c.name) {
                "r" -> run(c, part, pStyle, extraR, st)
                "hyperlink", "ins", "smartTag", "customXml", "dir", "bdo", "moveTo" -> inline(c, part, pStyle, extraR, st)
                "sdt" -> c.child("sdtContent")?.let { inline(it, part, pStyle, extraR, st) }
                "fldSimple" -> {
                    val instr = c["instr"].orEmpty().trim().uppercase()
                    val cs = b.charShape(runOf(rProps(c.descendants("rPr").firstOrNull(), pStyle, extraR)))
                    when {
                        instr.startsWith("PAGE") -> st.cur.ctrl(HCtrl.AutoNum("PAGE", "DIGIT"), cs)
                        instr.startsWith("NUMPAGES") -> st.cur.ctrl(HCtrl.AutoNum("TOTAL_PAGE", "DIGIT"), cs)
                        else -> inline(c, part, pStyle, extraR, st)
                    }
                }
                "oMath" -> math(c, pStyle, extraR, st)
                "oMathPara" -> c.children("oMath").forEach { math(it, pStyle, extraR, st) }
                "AlternateContent" -> (c.child("Choice") ?: c.child("Fallback"))?.let { inline(it, part, pStyle, extraR, st) }
            }
        }
    }

    private fun math(m: XNode, pStyle: String?, extraR: List<XNode>, st: PState) {
        val script = OmmlScript.convert(m)
        if (script.isBlank()) return
        val r = runOf(rProps(m.descendants("rPr").firstOrNull { it.child("sz") != null }, pStyle, extraR))
        st.cur.obj(HEquation().apply {
            this.script = script
            baseUnit = r.size
            color = r.color
            treatAsChar = true
        }, b.charShape(r))
    }

    private fun run(r: XNode, part: String, pStyle: String?, extraR: List<XNode>, st: PState) {
        val rPr = r.child("rPr")
        val props = rProps(rPr, pStyle, extraR)
        if (on(first(props, "vanish")) && rPr?.child("vanish") != null) return
        var run: HBuilder.Run? = null
        fun cs(): Int = b.charShape(run ?: runOf(props).also { run = it })
        val caps = on(first(props, "caps"))
        for (c in r.children) {
            when (c.name) {
                "fldChar" -> when (c["fldCharType"]) {
                    "begin" -> { inFieldCode = true; fieldInstr = StringBuilder(); fieldSkip = false }
                    "separate" -> {
                        inFieldCode = false
                        val instr = fieldInstr.toString().trim().uppercase()
                        when {
                            instr.startsWith("PAGE") -> { st.cur.ctrl(HCtrl.AutoNum("PAGE", "DIGIT"), cs()); fieldSkip = true }
                            instr.startsWith("NUMPAGES") || instr.startsWith("SECTIONPAGES") -> {
                                st.cur.ctrl(HCtrl.AutoNum("TOTAL_PAGE", "DIGIT"), cs()); fieldSkip = true
                            }
                        }
                    }
                    "end" -> { inFieldCode = false; fieldSkip = false }
                }
                "instrText" -> if (inFieldCode) fieldInstr.append(c.text)
            }
            if (inFieldCode || fieldSkip) continue
            when (c.name) {
                "t" -> st.cur.text(if (caps) c.text.uppercase() else c.text, cs())
                "tab", "ptab" -> st.cur.tab(tabWidth, cs())
                "br", "cr" -> when (c["type"]) {
                    "page" -> st.pageBreak()
                    else -> st.cur.text("\n", cs())
                }
                "noBreakHyphen" -> st.cur.text("-", cs())
                "sym" -> {
                    val code = c["char"]?.toIntOrNull(16) ?: continue
                    val ch = code.toChar()
                    st.cur.text(SYMBOL_BULLETS[ch] ?: SYMBOL_BULLETS[(0xF000 + (code and 0xFF)).toChar()] ?: if (code in 0xF000..0xF0FF) "•" else ch.toString(), cs())
                }
                "footnoteReference" -> {
                    val fn = footnotes[c["id"]] ?: continue
                    footCount++
                    val paras = ArrayList<HPara>()
                    for (x in fn.children) if (x.name == "p") paragraph(x, pkg.relByType(docPart, "footnotes") ?: part, emptyList(), paras)
                    st.cur.ctrl(HCtrl.Note(false, footCount, "", "", "", paras), cs())
                }
                "footnoteRef" -> st.cur.ctrl(HCtrl.AutoNum("FOOTNOTE", "DIGIT"), cs())
                "drawing" -> drawing(c, part, st, cs())
                "pict", "object" -> vml(c, part, st, cs())
                "AlternateContent" -> {
                    val pick = c.child("Choice")?.takeIf { it.child("drawing") != null } ?: c.child("Fallback") ?: c.child("Choice")
                    pick?.children?.forEach { x ->
                        when (x.name) {
                            "drawing" -> drawing(x, part, st, cs())
                            "pict", "object" -> vml(x, part, st, cs())
                        }
                    }
                }
            }
        }
    }

    // ================= 그림·글상자 =================

    private fun drawing(d: XNode, part: String, st: PState, cs: Int) {
        val holder = d.child("inline") ?: d.child("anchor") ?: return
        val anchor = holder.name == "anchor"
        val ext = holder.child("extent") ?: return
        val w = HBuilder.emu(ext.long("cx"))
        val h = HBuilder.emu(ext.long("cy"))
        val data = holder.path("graphic", "graphicData") ?: return
        val obj: HObject = data.child("pic")?.let { pic ->
            val target = pkg.relTarget(part, pic.path("blipFill", "blip")?.get("embed")) ?: return
            HPicture().apply {
                binId = target
                orgWidth = w; orgHeight = h
                pic.path("spPr", "xfrm")?.let { x -> rotation = x.long("rot") / 60000f; flipH = x.flag("flipH"); flipV = x.flag("flipV") }
                pic.path("blipFill", "srcRect")?.let { r ->
                    val l = r.int("l").coerceIn(0, 99999); val t = r.int("t").coerceIn(0, 99999)
                    val rr = r.int("r").coerceIn(0, 99999); val bb = r.int("b").coerceIn(0, 99999)
                    if (l or t or rr or bb != 0) {
                        imgWidth = 100000; imgHeight = 100000
                        clipLeft = l; clipTop = t; clipRight = max(l + 1, 100000 - rr); clipBottom = max(t + 1, 100000 - bb)
                    }
                }
            }
        } ?: data.child("wsp")?.let { wsp -> textShape(wsp, part, w, h) } ?: return
        obj.width = w
        obj.height = h
        val wrapNone = holder.child("wrapNone") != null
        if (anchor && (wrapNone || holder.flag("behindDoc"))) {
            // 글 위·뒤에 떠 있는 개체: 쪽·여백·문단 기준 위치
            obj.treatAsChar = false
            obj.textWrap = if (holder.flag("behindDoc")) "BEHIND_TEXT" else "IN_FRONT_OF_TEXT"
            val ph = holder.child("positionH")
            val pv = holder.child("positionV")
            obj.horzRelTo = when (ph?.get("relativeFrom")) { "page" -> "PAPER"; "margin", "leftMargin", "rightMargin", "insideMargin", "outsideMargin" -> "PAGE"; else -> "PARA" }
            obj.vertRelTo = when (pv?.get("relativeFrom")) { "page" -> "PAPER"; "margin", "topMargin", "bottomMargin" -> "PAGE"; else -> "PARA" }
            obj.horzAlign = when (ph?.child("align")?.text?.trim()) { "center" -> "CENTER"; "right", "outside" -> "RIGHT"; else -> "LEFT" }
            obj.vertAlign = when (pv?.child("align")?.text?.trim()) { "center" -> "CENTER"; "bottom" -> "BOTTOM"; else -> "TOP" }
            obj.horzOffset = HBuilder.emu(ph?.child("posOffset")?.text?.trim()?.toLongOrNull() ?: 0)
            obj.vertOffset = HBuilder.emu(pv?.child("posOffset")?.text?.trim()?.toLongOrNull() ?: 0)
        } else {
            obj.treatAsChar = true
        }
        st.cur.obj(obj, cs)
    }

    /** 워드 도형(wps): 채우기·선·글 */
    private fun textShape(wsp: XNode, part: String, w: Int, h: Int): HShape {
        val spPr = wsp.child("spPr")
        val prst = spPr?.child("prstGeom")?.get("prst") ?: "rect"
        val s = when (prst) {
            "ellipse" -> HShape("ellipse")
            "rect" -> HShape("rect")
            "roundRect" -> HShape("rect").apply { roundRatio = ((Dml.adjusts(spPr?.child("prstGeom"))["adj"] ?: 16667) / 500).coerceIn(0, 100) }
            else -> Dml.presetPath(prst, w.toFloat(), h.toFloat(), Dml.adjusts(spPr?.child("prstGeom")))?.let { HShape("path").apply { path = it } } ?: HShape("rect")
        }
        s.orgWidth = w; s.orgHeight = h; s.curWidth = w; s.curHeight = h
        val style = wsp.child("style")
        val scheme = theme.colors
        val fillEl = spPr?.children?.firstOrNull { it.name == "noFill" || it.name == "solidFill" || it.name == "gradFill" }
        s.fillColor = when (fillEl?.name) {
            "solidFill" -> Dml.color(fillEl, null, scheme)
            "gradFill" -> fillEl.child("gsLst")?.children("gs")?.firstOrNull()?.let { Dml.color(it, null, scheme) }
            "noFill" -> null
            else -> style?.child("fillRef")?.takeIf { it.int("idx") > 0 }?.let { Dml.color(it, null, scheme) }
        }
        val ln = spPr?.child("ln")
        val lnFill = ln?.children?.firstOrNull { it.name == "noFill" || it.name == "solidFill" }
        val lnColor = when (lnFill?.name) {
            "solidFill" -> Dml.color(lnFill, null, scheme)
            "noFill" -> null
            else -> style?.child("lnRef")?.takeIf { it.int("idx") > 0 }?.let { Dml.color(it, null, scheme) }
        }
        if (lnColor != null) s.line = LineStyle(lnColor, HBuilder.emu(ln?.int("w", 9525) ?: 9525), if (ln?.child("prstDash")?.get("val").let { it == null || it == "solid" }) "SOLID" else "DASH")
        val txbx = wsp.path("txbx", "txbxContent")
        if (txbx != null) {
            val paras = ArrayList<HPara>()
            for (c in txbx.children) when (c.name) {
                "p" -> paragraph(c, part, emptyList(), paras)
                "tbl" -> table(c, part, paras)
            }
            s.drawText = paras
            val bp = wsp.child("bodyPr")
            s.textMarginLeft = HBuilder.emu(bp?.long("lIns", 91440) ?: 91440)
            s.textMarginRight = HBuilder.emu(bp?.long("rIns", 91440) ?: 91440)
            s.textMarginTop = HBuilder.emu(bp?.long("tIns", 45720) ?: 45720)
            s.textMarginBottom = HBuilder.emu(bp?.long("bIns", 45720) ?: 45720)
            s.textVertAlign = when (bp?.get("anchor")) { "ctr" -> "CENTER"; "b" -> "BOTTOM"; else -> "TOP" }
        }
        return s
    }

    /** 옛 방식(VML) 그림: v:imagedata + style의 width/height */
    private fun vml(n: XNode, part: String, st: PState, cs: Int) {
        val shape = n.descendants("shape").firstOrNull() ?: return
        val img = shape.descendants("imagedata").firstOrNull()
        val style = shape["style"].orEmpty()
        fun len(key: String): Int? {
            val m = Regex("$key:\\s*([0-9.]+)(pt|in|cm|mm|px)?").find(style) ?: return null
            val v = m.groupValues[1].toFloatOrNull() ?: return null
            val pt = when (m.groupValues[2]) { "in" -> v * 72; "cm" -> v * 72 / 2.54f; "mm" -> v * 72 / 25.4f; "px" -> v * 0.75f; else -> v }
            return (pt * 100).roundToInt()
        }
        val w = len("width") ?: return
        val h = len("height") ?: return
        if (img != null) {
            val target = pkg.relTarget(part, img["id"]) ?: return
            st.cur.obj(HPicture().apply { binId = target; width = w; height = h; orgWidth = w; orgHeight = h; treatAsChar = true }, cs)
            return
        }
        shape.descendants("txbxContent").firstOrNull()?.let { tb ->
            val paras = ArrayList<HPara>()
            for (c in tb.children) if (c.name == "p") paragraph(c, part, emptyList(), paras)
            st.cur.obj(HShape("rect").apply {
                width = w; height = h; orgWidth = w; orgHeight = h; treatAsChar = true
                drawText = paras
                textVertAlign = "TOP"
            }, cs)
        }
    }

    // ================= 표 =================

    private fun border(n: XNode?): BorderLine? {
        n ?: return null
        val v = n["val"] ?: return null
        if (v == "nil" || v == "none") return BorderLine.NONE
        val w = (n.int("sz", 4) / 8f).coerceAtLeast(0.25f)
        val color = n["color"]?.let { if (it == "auto") 0xFF000000.toInt() else Dml.parseHex(it) } ?: 0xFF000000.toInt()
        val type = when {
            v.startsWith("double") -> "DOUBLE"
            v.contains("dash", true) -> "DASH"
            v.contains("dot", true) -> "DOT"
            else -> "SOLID"
        }
        return BorderLine(type, if (type == "DOUBLE") w * 3 else w, color)
    }

    private fun table(tbl: XNode, part: String, out: MutableList<HPara>) {
        val tblPr = tbl.child("tblPr")
        val styleId = tblPr?.child("tblStyle")?.get("val") ?: defaultTableStyle
        val chain = styleChain(styleId)
        val tprs = listOfNotNull(tblPr) + chain.mapNotNull { it.child("tblPr") }
        val stylePPr = chain.mapNotNull { it.child("pPr") }
        val styleRPr = chain.mapNotNull { it.child("rPr") }
        val grid = tbl.child("tblGrid")?.children("gridCol").orEmpty().map { HBuilder.twip(it.int("w")) }
        val rows = tbl.children("tr").toMutableList()
        // 표 안의 sdt 행
        tbl.children("sdt").forEach { s -> s.child("sdtContent")?.children("tr")?.let { rows.addAll(it) } }
        if (rows.isEmpty()) return

        val tblBorders = tprs.mapNotNull { it.child("tblBorders") }
        fun tb(name: String, alt: String? = null) = tblBorders.firstNotNullOfOrNull { n -> border(n.child(name)) ?: alt?.let { border(n.child(it)) } }
        val mar = tprs.mapNotNull { it.child("tblCellMar") }
        fun m(name: String, alt: String, def: Int) = mar.firstNotNullOfOrNull { (it.child(name) ?: it.child(alt))?.int("w") }?.let { HBuilder.twip(it) } ?: def

        // 칸 배치: 행마다 (열 위치, 칸) + 세로 합치기
        class Slot(val tc: XNode, val row: Int, val col: Int, val span: Int)
        val slots = ArrayList<Slot>()
        val vContinue = HashSet<Long>()
        var nCols = grid.size
        for ((r, tr) in rows.withIndex()) {
            var c = tr.path("trPr", "gridBefore")?.int("val") ?: 0
            for (tc in tr.children.filter { it.name == "tc" || it.name == "sdt" }.flatMap { if (it.name == "sdt") it.child("sdtContent")?.children("tc").orEmpty() else listOf(it) }) {
                val tcPr = tc.child("tcPr")
                val span = (tcPr?.child("gridSpan")?.int("val") ?: 1).coerceAtLeast(1)
                val vm = tcPr?.child("vMerge")
                if (vm != null && vm["val"] != "restart") vContinue.add(r.toLong() shl 20 or c.toLong())
                else slots.add(Slot(tc, r, c, span))
                c += span
            }
            nCols = max(nCols, c)
        }
        val widths = if (grid.size >= nCols) grid else grid + List(nCols - grid.size) { 1000 }

        val t = HTable()
        t.rowCnt = rows.size
        t.colCnt = nCols
        t.treatAsChar = true
        t.measureHeight = true
        t.width = widths.sum()
        var total = 0
        val rowH = rows.map { tr -> HBuilder.twip(tr.path("trPr", "trHeight")?.int("val") ?: 0).coerceAtLeast(0) }
        t.repeatHeader = rows.firstOrNull()?.path("trPr", "tblHeader")?.let { on(it) } == true
        for (s in slots) {
            val tcPr = s.tc.child("tcPr")
            var rSpan = 1
            while (s.row + rSpan < rows.size && vContinue.contains((s.row + rSpan).toLong() shl 20 or s.col.toLong())) rSpan++
            val cell = HCell()
            cell.row = s.row
            cell.col = s.col.coerceAtMost(nCols - 1)
            cell.colSpan = s.span.coerceAtMost(nCols - cell.col)
            cell.rowSpan = rSpan
            cell.width = widths.subList(cell.col, cell.col + cell.colSpan).sum()
            cell.height = rowH.subList(s.row, s.row + rSpan).sum().coerceAtLeast(400)
            cell.header = rows[s.row].path("trPr", "tblHeader")?.let { on(it) } == true
            val cm = tcPr?.child("tcMar")
            cell.marginLeft = cm?.let { (it.child("left") ?: it.child("start"))?.int("w") }?.let { HBuilder.twip(it) } ?: m("left", "start", 540)
            cell.marginRight = cm?.let { (it.child("right") ?: it.child("end"))?.int("w") }?.let { HBuilder.twip(it) } ?: m("right", "end", 540)
            cell.marginTop = cm?.child("top")?.int("w")?.let { HBuilder.twip(it) } ?: m("top", "top", 0)
            cell.marginBottom = cm?.child("bottom")?.int("w")?.let { HBuilder.twip(it) } ?: m("bottom", "bottom", 0)
            cell.hasMargin = true
            cell.vertAlign = when (tcPr?.child("vAlign")?.get("val")) { "center" -> "CENTER"; "bottom" -> "BOTTOM"; else -> "TOP" }

            val bf = BorderFill()
            val tcb = tcPr?.child("tcBorders")
            val lastRow = s.row + rSpan >= rows.size
            val lastCol = cell.col + cell.colSpan >= nCols
            bf.left = border(tcb?.child("left") ?: tcb?.child("start")) ?: (if (cell.col == 0) tb("left", "start") else tb("insideV")) ?: BorderLine.NONE
            bf.right = border(tcb?.child("right") ?: tcb?.child("end")) ?: (if (lastCol) tb("right", "end") else tb("insideV")) ?: BorderLine.NONE
            bf.top = border(tcb?.child("top")) ?: (if (s.row == 0) tb("top") else tb("insideH")) ?: BorderLine.NONE
            bf.bottom = border(tcb?.child("bottom")) ?: (if (lastRow) tb("bottom") else tb("insideH")) ?: BorderLine.NONE
            bf.fillColor = tcPr?.child("shd")?.let { sh -> sh["fill"]?.takeIf { it != "auto" }?.let { Dml.parseHex(it) } ?: themeColor(sh["themeFill"]) }
            cell.borderFillId = b.borderFill(bf)

            val paras = ArrayList<HPara>()
            for (c in s.tc.children) when (c.name) {
                "p" -> paragraph(c, part, stylePPr, paras, styleRPr)
                "tbl" -> table(c, part, paras)
                "sdt" -> c.child("sdtContent")?.children?.forEach { x -> if (x.name == "p") paragraph(x, part, stylePPr, paras, styleRPr) }
            }
            cell.paras = paras
            t.cells.add(cell)
        }
        for (hh in rowH) total += hh.coerceAtLeast(400)
        t.height = total

        // 표를 담는 문단: 표 맞춤과 들여쓰기
        val ps = ParaShape()
        ps.align = when (tprs.firstNotNullOfOrNull { it.child("jc")?.get("val") }) {
            "center" -> Align.CENTER
            "right", "end" -> Align.RIGHT
            else -> Align.LEFT
        }
        ps.lineSpacingType = "PERCENT"
        ps.lineSpacing = 100
        ps.left = max(0, tprs.firstNotNullOfOrNull { it.child("tblInd")?.int("w") }?.let { HBuilder.twip(it) } ?: 0)
        val para = HBuilder.Para(b.paraShape(ps))
        para.obj(t, 0)
        out.add(para.build())
    }
}

/** 워드 글꼴의 줄 높이·글자 폭 (워드와 같은 곳에서 줄·쪽이 나뉘도록). DOCX·DOC 공통 */
internal object WordFonts {
    /** 목록 글머리표 글자(기호 글꼴이면 대응하는 유니코드로) */
    fun bullet(ch: Char): String = DocxReader.bulletOf(ch)

    /** 워드가 쓰는 한 줄 높이 (글자 크기에 대한 비율). 동아시아 글꼴은 워드가 30% 더 띄운다 */
    fun lineFactor(face: String): Float {
        val f = face.lowercase()
        return when {
            f.contains("맑은") || f.contains("malgun") -> 1.73f
            f.contains("바탕") || f.contains("batang") || f.contains("굴림") || f.contains("gulim") || f.contains("돋움") ||
                f.contains("dotum") || f.contains("궁서") || f.contains("gungsuh") -> 1.49f
            f.contains("함초롬") || f.contains("hcr") || f.contains("한컴") || f.contains("나눔") || f.contains("nanum") ||
                f.contains("고딕") || f.contains("명조") || f.contains("gothic") -> 1.5f
            f.contains("calibri") -> 1.22f
            f.contains("segoe") -> 1.33f
            f.contains("cambria") -> 1.17f
            f.contains("consolas") -> 1.17f
            f.contains("arial") || f.contains("times") || f.contains("helvetica") -> 1.15f
            else -> 1.2f
        }
    }

    /** 한글 폭이 글자 크기와 같은 글꼴 (맑은 고딕·바탕·굴림 …). 기기 글꼴의 한글은 조금 좁아 간격을 더한다 */
    fun fullWidthHangul(face: String): Boolean {
        val f = face.lowercase()
        return listOf("맑은", "malgun", "바탕", "batang", "굴림", "gulim", "돋움", "dotum", "궁서", "gungsuh", "함초롬", "hcr", "한컴", "나눔", "nanum", "고딕", "명조", "gothic")
            .any { f.contains(it) }
    }
}
