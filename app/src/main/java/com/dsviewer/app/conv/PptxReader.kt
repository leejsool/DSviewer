package com.dsviewer.app.conv

import com.dsviewer.app.hwp.Align
import com.dsviewer.app.hwp.BorderFill
import com.dsviewer.app.hwp.BorderLine
import com.dsviewer.app.hwp.HCell
import com.dsviewer.app.hwp.HDoc
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
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * PPTX(파워포인트) → [HDoc]. 슬라이드 한 장이 한 쪽이고, 도형·그림·표를 모두 쪽에 떠 있는 개체로 놓는다.
 * 자리 표시자(제목·본문)는 레이아웃·마스터에서 위치와 글자 모양을 이어받는다.
 * 차트·수식·애니메이션·그림자·그라데이션(가운데 색으로 칠함)은 그리지 않는다.
 */
class PptxReader private constructor(private val pkg: OpcPackage) {

    private val b = HBuilder()
    private var slideW = 12192000L
    private var slideH = 6858000L
    private var defaultTextStyle: XNode? = null
    private var tableStyles: XNode? = null
    private val themes = HashMap<String, DmlTheme>()

    companion object {
        /** [maxSlides] > 0 이면 앞쪽 슬라이드만 (썸네일) */
        fun read(file: File, maxSlides: Int = 0): HDoc {
            val zip = ZipFile(file)
            return PptxReader(OpcPackage(zip)).readAll(maxSlides)
        }

        private val FILL_TAGS = setOf("noFill", "solidFill", "gradFill", "pattFill", "blipFill", "grpFill")
        private val LINE_PRESETS = setOf(
            "line", "straightConnector1", "bentConnector2", "bentConnector3", "bentConnector4", "bentConnector5",
            "curvedConnector2", "curvedConnector3", "curvedConnector4", "curvedConnector5", "lineInv",
        )
        private val ROUND_PRESETS = setOf("roundRect", "round1Rect", "round2SameRect", "round2DiagRect", "flowChartAlternateProcess")

        /** Wingdings 글머리표 → 비슷한 유니코드 글자 */
        private val WINGDINGS = mapOf(
            "§" to "■", "Ø" to "➢", "ü" to "✓", "q" to "❑", "v" to "❖", "n" to "■", "l" to "●", "Ÿ" to "•",
            "¨" to "□", "o" to "□", "p" to "■", "u" to "◆", "Ü" to "➔", "è" to "➔", "à" to "➔", "ð" to "⇨",
        )
    }

    /** 슬라이드 하나를 그리는 동안의 문맥 (테마 색, 레이아웃·마스터) */
    private inner class Ctx(
        val theme: DmlTheme,
        val scheme: Map<String, Int>,
        val layout: XNode?,
        val master: XNode?,
        val slideNo: Int,
    )

    /** 아이 좌표(EMU) → 슬라이드 좌표(EMU): x' = ax + x·kx */
    private class Xf(val ax: Double, val kx: Double, val ay: Double, val ky: Double) {
        fun x(v: Long) = ax + v * kx
        fun y(v: Long) = ay + v * ky
        companion object { val ID = Xf(0.0, 1.0, 0.0, 1.0) }
    }

    private fun readAll(maxSlides: Int): HDoc {
        val presPath = pkg.relByType("", "officeDocument") ?: "ppt/presentation.xml"
        val pres = pkg.xml(presPath) ?: error("프레젠테이션 내용을 찾을 수 없습니다.")
        pres.child("sldSz")?.let {
            slideW = it.long("cx", slideW)
            slideH = it.long("cy", slideH)
        }
        defaultTextStyle = pres.child("defaultTextStyle")
        tableStyles = pkg.relByType(presPath, "tableStyles")?.let { pkg.xml(it) }

        val slideParts = pres.child("sldIdLst")?.children("sldId").orEmpty()
            .mapNotNull { pkg.relTarget(presPath, it["id"]) }
        val sections = ArrayList<HSection>()
        for ((i, part) in slideParts.withIndex()) {
            if (maxSlides > 0 && i >= maxSlides) break
            val slide = pkg.xml(part) ?: continue
            sections.add(slideSection(part, slide, i + 1))
        }
        if (sections.isEmpty()) error("슬라이드가 없습니다.")
        return b.build(sections) { path -> pkg.bytes(path) }
    }

    private fun theme(masterPart: String?): DmlTheme {
        val tp = masterPart?.let { pkg.relByType(it, "theme") }
        return themes.getOrPut(tp ?: "") { DmlTheme(tp?.let { pkg.xml(it) }) }
    }

    private fun slideSection(part: String, slide: XNode, no: Int): HSection {
        val layoutPart = pkg.relByType(part, "slideLayout")
        val layout = layoutPart?.let { pkg.xml(it) }
        val masterPart = layoutPart?.let { pkg.relByType(it, "slideMaster") }
        val master = masterPart?.let { pkg.xml(it) }
        val theme = theme(masterPart)

        // 색 대응표: bg1 → lt1 …(마스터 clrMap, 슬라이드가 덮어쓸 수 있음)
        val map = HashMap<String, String>()
        master?.child("clrMap")?.attrs?.let { map.putAll(it) }
        (slide.path("clrMapOvr", "overrideClrMapping") ?: layout?.path("clrMapOvr", "overrideClrMapping"))?.attrs?.let { map.putAll(it) }
        val scheme = HashMap<String, Int>(theme.colors)
        for ((k, v) in map) theme.colors[v]?.let { scheme[k] = it }
        scheme.putIfAbsent("tx1", scheme["dk1"] ?: 0xFF000000.toInt())
        scheme.putIfAbsent("bg1", scheme["lt1"] ?: -1)
        scheme.putIfAbsent("tx2", scheme["dk2"] ?: 0xFF000000.toInt())
        scheme.putIfAbsent("bg2", scheme["lt2"] ?: -1)

        val ctx = Ctx(theme, scheme, layout, master, no)
        val objs = ArrayList<HObject>()

        // 배경
        val bg = listOf(slide to part, layout to layoutPart, master to masterPart)
            .firstNotNullOfOrNull { (x, p) -> x?.path("cSld", "bg")?.let { it to p } }
        background(bg?.first, bg?.second, ctx)?.let { objs.add(it) }

        // 마스터 → 레이아웃 → 슬라이드 순서로 (자리 표시자는 슬라이드 것만 그림)
        val showMaster = slide.flag("showMasterSp", true)
        if (showMaster && layout != null && master != null && layout.flag("showMasterSp", true)) {
            master.path("cSld", "spTree")?.let { tree(it, masterPart!!, Xf.ID, ctx, placeholders = false, objs) }
        }
        if (showMaster && layout != null) {
            layout.path("cSld", "spTree")?.let { tree(it, layoutPart!!, Xf.ID, ctx, placeholders = false, objs) }
        }
        slide.path("cSld", "spTree")?.let { tree(it, part, Xf.ID, ctx, placeholders = true, objs) }

        val para = HBuilder.Para()
        for (o in objs) para.obj(o, 0)
        return HSection(listOf(para.build())).apply {
            page = PageDef().apply {
                width = HBuilder.emu(slideW)
                height = HBuilder.emu(slideH)
                left = 0; right = 0; top = 0; bottom = 0; header = 0; footer = 0
            }
        }
    }

    private fun place(o: HObject, x: Double, y: Double, w: Double, h: Double) {
        o.treatAsChar = false
        o.textWrap = "IN_FRONT_OF_TEXT"
        o.horzRelTo = "PAPER"
        o.vertRelTo = "PAPER"
        o.horzAlign = "LEFT"
        o.vertAlign = "TOP"
        o.horzOffset = HBuilder.emu(x.roundToLong())
        o.vertOffset = HBuilder.emu(y.roundToLong())
        o.width = HBuilder.emu(w.roundToLong()).coerceAtLeast(0)
        o.height = HBuilder.emu(h.roundToLong()).coerceAtLeast(0)
    }

    private fun Double.roundToLong(): Long = kotlin.math.round(this).toLong()

    private fun background(bg: XNode?, part: String?, ctx: Ctx): HObject? {
        bg ?: return null
        val bgPr = bg.child("bgPr")
        val fillEl = bgPr?.children?.firstOrNull { it.name in FILL_TAGS }
        if (fillEl?.name == "blipFill" && part != null) {
            val target = pkg.relTarget(part, fillEl.child("blip")?.get("embed")) ?: return null
            return HPicture().apply {
                binId = target
                place(this, 0.0, 0.0, slideW.toDouble(), slideH.toDouble())
                orgWidth = width; orgHeight = height
            }
        }
        val color = if (bgPr != null) fillColor(fillEl, null, ctx) else bg.child("bgRef")?.let { Dml.color(it, null, ctx.scheme) }
        color ?: return null
        return HShape("rect").apply {
            place(this, 0.0, 0.0, slideW.toDouble(), slideH.toDouble())
            orgWidth = width; orgHeight = height
            fillColor = color
        }
    }

    // ================= 도형 나무 =================

    private fun tree(node: XNode, part: String, xf: Xf, ctx: Ctx, placeholders: Boolean, out: MutableList<HObject>) {
        for (c in node.children) {
            when (c.name) {
                "sp", "cxnSp" -> shape(c, part, xf, ctx, placeholders, out)
                "pic" -> picture(c, part, xf, ctx, placeholders, out)
                "grpSp" -> {
                    if (c.firstNv()?.child("cNvPr")?.flag("hidden") == true) continue
                    val x = c.path("grpSpPr", "xfrm")
                    val off = x?.child("off")
                    val ext = x?.child("ext")
                    val chOff = x?.child("chOff")
                    val chExt = x?.child("chExt")
                    val kx = if (chExt != null && chExt.long("cx") > 0) ext!!.long("cx").toDouble() / chExt.long("cx") else 1.0
                    val ky = if (chExt != null && chExt.long("cy") > 0) ext!!.long("cy").toDouble() / chExt.long("cy") else 1.0
                    val ox = (off?.long("x") ?: 0) - (chOff?.long("x") ?: 0) * kx
                    val oy = (off?.long("y") ?: 0) - (chOff?.long("y") ?: 0) * ky
                    val g = Xf(xf.ax + xf.kx * ox, xf.kx * kx, xf.ay + xf.ky * oy, xf.ky * ky)
                    tree(c, part, g, ctx, placeholders, out)
                }
                "graphicFrame" -> graphicFrame(c, part, xf, ctx, out)
                "AlternateContent" -> {
                    // 새 기능(Choice)보다 호환용(Fallback)이 보통 그림·도형으로 되어 있다
                    val pick = c.child("Fallback") ?: c.child("Choice")
                    if (pick != null) tree(pick, part, xf, ctx, placeholders, out)
                }
            }
        }
    }

    /** nvSpPr / nvPicPr / nvGrpSpPr … */
    private fun XNode.firstNv(): XNode? = children.firstOrNull { it.name.startsWith("nv") }

    private fun XNode.ph(): XNode? = firstNv()?.path("nvPr", "ph")

    /** 자리 표시자 종류 (없으면 obj) */
    private fun phType(ph: XNode) = ph["type"] ?: "obj"

    /** 레이아웃·마스터에서 같은 자리 표시자 찾기 */
    private fun findPh(root: XNode?, type: String, idx: String?, byType: Boolean): XNode? {
        val all = root?.path("cSld", "spTree")?.let { t -> ArrayList<XNode>().also { collectPh(t, it) } } ?: return null
        if (!byType && idx != null) all.firstOrNull { it.ph()?.get("idx") == idx }?.let { return it }
        val norm = normType(type)
        return all.firstOrNull { it.ph()?.let { p -> phType(p) } == type }
            ?: all.firstOrNull { it.ph()?.let { p -> normType(phType(p)) } == norm }
    }

    private fun collectPh(n: XNode, out: MutableList<XNode>) {
        for (c in n.children) {
            if ((c.name == "sp" || c.name == "pic") && c.ph() != null) out.add(c)
            if (c.name == "grpSp") collectPh(c, out)
        }
    }

    private fun normType(t: String) = when (t) {
        "ctrTitle", "title" -> "title"
        "subTitle", "obj", "body" -> "body"
        else -> t
    }

    /** 이 도형과 이어받는 레이아웃·마스터 도형 (자리 표시자가 아니면 자기만) */
    private fun chain(sp: XNode, ctx: Ctx, placeholders: Boolean): List<XNode>? {
        val ph = sp.ph() ?: return listOf(sp)
        if (!placeholders) return null
        val type = phType(ph)
        val idx = ph["idx"]
        val lay = findPh(ctx.layout, type, idx, byType = false)
        val mas = findPh(ctx.master, lay?.ph()?.let { phType(it) } ?: type, idx, byType = true)
        return listOfNotNull(sp, lay, mas)
    }

    /** 마스터의 글자 모양 묶음 (제목/본문/기타) */
    private fun txStyle(sp: XNode, ctx: Ctx): XNode? {
        val ph = sp.ph() ?: return null
        val styles = ctx.master?.child("txStyles") ?: return null
        return when (normType(phType(ph))) {
            "title" -> styles.child("titleStyle")
            "body" -> styles.child("bodyStyle")
            else -> styles.child("otherStyle")
        }
    }

    private class Box(val x: Double, val y: Double, val w: Double, val h: Double, val rot: Float, val flipH: Boolean, val flipV: Boolean)

    private fun box(chain: List<XNode>, xf: Xf): Box? {
        val x = chain.firstNotNullOfOrNull { n -> n.children.firstOrNull { it.name == "spPr" || it.name == "grpSpPr" }?.child("xfrm") ?: n.child("xfrm") }
            ?: return null
        val off = x.child("off") ?: return null
        val ext = x.child("ext")
        return Box(
            xf.x(off.long("x")), xf.y(off.long("y")),
            (ext?.long("cx") ?: 0) * xf.kx, (ext?.long("cy") ?: 0) * xf.ky,
            x.long("rot") / 60000f, x.flag("flipH"), x.flag("flipV"),
        )
    }

    private fun shape(sp: XNode, part: String, xf: Xf, ctx: Ctx, placeholders: Boolean, out: MutableList<HObject>) {
        if (sp.firstNv()?.child("cNvPr")?.flag("hidden") == true) return
        val chain = chain(sp, ctx, placeholders) ?: return
        val bx = box(chain, xf) ?: return
        val spPrs = chain.mapNotNull { it.child("spPr") }
        val style = sp.child("style")
        val geomNode = spPrs.firstNotNullOfOrNull { it.child("prstGeom") ?: it.child("custGeom") }
        val prst = if (geomNode?.name == "prstGeom") geomNode["prst"] ?: "rect" else if (geomNode == null) "rect" else "custom"
        val fillEl = spPrs.firstNotNullOfOrNull { s -> s.children.firstOrNull { it.name in FILL_TAGS } }
        val line = lineStyle(spPrs.firstNotNullOfOrNull { it.child("ln") }, style, ctx)
        val w = HBuilder.emu(bx.w.roundToLong()).toFloat()
        val h = HBuilder.emu(bx.h.roundToLong()).toFloat()

        // 그림으로 채운 도형
        if (fillEl?.name == "blipFill") {
            pkg.relTarget(part, fillEl.child("blip")?.get("embed"))?.let { target ->
                out.add(HPicture().apply {
                    binId = target
                    place(this, bx.x, bx.y, bx.w, bx.h)
                    orgWidth = width; orgHeight = height
                    rotation = bx.rot
                    this.line = line
                    crop(this, fillEl.child("srcRect"))
                })
            }
        }

        val paras = sp.child("txBody")?.let { textBody(it, chain, txStyle(sp, ctx), ctx, style) }
        val hasText = paras != null && paras.any { p -> p.items.any { it is com.dsviewer.app.hwp.PItem.Text && it.text.isNotBlank() } }

        if (prst in LINE_PRESETS) {
            line ?: return
            out.add(HShape("line").apply {
                place(this, bx.x, bx.y, bx.w, bx.h)
                orgWidth = width; orgHeight = height
                x0 = if (bx.flipH) width else 0
                x1 = if (bx.flipH) 0 else width
                y0 = if (bx.flipV) height else 0
                y1 = if (bx.flipV) 0 else height
                rotation = bx.rot
                this.line = line
                val ln = spPrs.firstNotNullOfOrNull { it.child("ln") }
                headArrow = ln?.child("headEnd")?.get("type").let { it != null && it != "none" }
                tailArrow = ln?.child("tailEnd")?.get("type").let { it != null && it != "none" }
            })
            return
        }

        val fill = if (fillEl?.name == "blipFill") null else fillColor(fillEl, style, ctx)
        if (fill == null && line == null && !hasText) return
        val s = when {
            prst == "ellipse" || prst == "flowChartConnector" -> HShape("ellipse")
            prst == "rect" || prst == "flowChartProcess" || prst == "textBox" -> HShape("rect")
            prst in ROUND_PRESETS -> HShape("rect").apply {
                roundRatio = ((Dml.adjusts(geomNode)["adj"] ?: Dml.adjusts(geomNode)["adj1"] ?: 16667) / 500).coerceIn(0, 100)
            }
            prst == "custom" -> Dml.customPath(geomNode!!, w, h)?.let { HShape("path").apply { path = it } } ?: HShape("rect")
            else -> Dml.presetPath(prst, w, h, Dml.adjusts(geomNode))?.let { HShape("path").apply { path = it } } ?: HShape("rect")
        }
        place(s, bx.x, bx.y, bx.w, bx.h)
        s.orgWidth = s.width; s.orgHeight = s.height
        s.curWidth = s.width; s.curHeight = s.height
        s.rotation = bx.rot
        // 글이 있으면 뒤집지 않는다 (파워포인트도 글은 뒤집지 않음)
        if (!hasText) {
            s.flipH = bx.flipH
            s.flipV = bx.flipV
        }
        s.fillColor = fill
        s.line = line
        if (hasText) {
            val bodyPrs = chain.mapNotNull { it.path("txBody", "bodyPr") }
            fun bp(name: String) = bodyPrs.firstNotNullOfOrNull { it[name] }
            s.drawText = paras
            s.textMarginLeft = HBuilder.emu(bp("lIns")?.toLongOrNull() ?: 91440)
            s.textMarginRight = HBuilder.emu(bp("rIns")?.toLongOrNull() ?: 91440)
            s.textMarginTop = HBuilder.emu(bp("tIns")?.toLongOrNull() ?: 45720)
            s.textMarginBottom = HBuilder.emu(bp("bIns")?.toLongOrNull() ?: 45720)
            s.textVertAlign = when (bp("anchor")) {
                "ctr" -> "CENTER"
                "b" -> "BOTTOM"
                else -> "TOP"
            }
            if (bp("wrap") == "none") {
                // 줄을 바꾸지 않는 글: 글 상자를 넓혀 준다 (맞춤 기준은 그대로)
                val wide = HBuilder.emu(slideW) * 2
                val align = paras!!.firstOrNull()?.let { b.paraShapeOf(it.paraShapeId)?.align } ?: Align.LEFT
                when (align) {
                    Align.CENTER -> { s.textMarginLeft -= wide / 2; s.textMarginRight -= wide / 2 }
                    Align.RIGHT -> s.textMarginLeft -= wide
                    else -> s.textMarginRight -= wide
                }
            }
        }
        out.add(s)
    }

    private fun picture(pic: XNode, part: String, xf: Xf, ctx: Ctx, placeholders: Boolean, out: MutableList<HObject>) {
        if (pic.firstNv()?.child("cNvPr")?.flag("hidden") == true) return
        val chain = chain(pic, ctx, placeholders) ?: return
        val bx = box(chain, xf) ?: return
        val blipFill = pic.child("blipFill") ?: return
        val target = pkg.relTarget(part, blipFill.child("blip")?.get("embed")) ?: return
        out.add(HPicture().apply {
            binId = target
            place(this, bx.x, bx.y, bx.w, bx.h)
            orgWidth = width; orgHeight = height
            rotation = bx.rot
            flipH = bx.flipH
            flipV = bx.flipV
            line = lineStyle(pic.path("spPr", "ln"), pic.child("style"), ctx)
            crop(this, blipFill.child("srcRect"))
        })
    }

    /** 그림 자르기 (srcRect: 1/1000 %) */
    private fun crop(p: HPicture, src: XNode?) {
        src ?: return
        val l = src.int("l").coerceIn(0, 99999)
        val t = src.int("t").coerceIn(0, 99999)
        val r = src.int("r").coerceIn(0, 99999)
        val bt = src.int("b").coerceIn(0, 99999)
        if (l == 0 && t == 0 && r == 0 && bt == 0) return
        p.imgWidth = 100000
        p.imgHeight = 100000
        p.clipLeft = l
        p.clipTop = t
        p.clipRight = max(l + 1, 100000 - r)
        p.clipBottom = max(t + 1, 100000 - bt)
    }

    private fun graphicFrame(gf: XNode, part: String, xf: Xf, ctx: Ctx, out: MutableList<HObject>) {
        if (gf.firstNv()?.child("cNvPr")?.flag("hidden") == true) return
        val bx = box(listOf(gf), xf) ?: return
        val data = gf.path("graphic", "graphicData") ?: return
        data.child("tbl")?.let { table(it, bx, ctx, out); return }
        val uri = data["uri"].orEmpty()
        if (uri.endsWith("/diagram")) {
            // 스마트아트: 파워포인트가 저장해 둔 그림(도형들)을 그린다
            val dm = data.child("relIds")?.get("dm")
            val dataPart = pkg.relTarget(part, dm)
            val drawRel = dataPart?.let { pkg.xml(it) }?.descendants("dataModelExt")?.firstOrNull()?.get("relId")
            val drawPart = pkg.relTarget(part, drawRel)
                ?: pkg.rels(part).values.firstOrNull { it.type.endsWith("/diagramDrawing") }?.target
            val tree = drawPart?.let { pkg.xml(it) }?.child("spTree") ?: return
            val g = Xf(bx.x, xf.kx, bx.y, xf.ky)
            tree(tree, drawPart, g, ctx, placeholders = true, out)
            return
        }
        // 그 밖(OLE 개체 등): 안에 든 대체 그림
        val pics = data.descendants("pic")
        for (p in pics) {
            val target = pkg.relTarget(part, p.path("blipFill", "blip")?.get("embed")) ?: continue
            out.add(HPicture().apply {
                binId = target
                place(this, bx.x, bx.y, bx.w, bx.h)
                orgWidth = width; orgHeight = height
            })
            break
        }
    }

    // ================= 채우기·선 =================

    private fun fillColor(el: XNode?, style: XNode?, ctx: Ctx): Int? {
        if (el == null) {
            val ref = style?.child("fillRef") ?: return null
            if (ref.int("idx") == 0) return null
            return Dml.color(ref, null, ctx.scheme)
        }
        return when (el.name) {
            "solidFill" -> Dml.color(el, null, ctx.scheme)
            "gradFill" -> gradientColor(el, ctx)
            "pattFill" -> Dml.color(el.child("fgClr"), null, ctx.scheme)
            else -> null
        }
    }

    /** 그라데이션은 가운데쯤 색 하나로 */
    private fun gradientColor(el: XNode, ctx: Ctx): Int? {
        val stops = el.child("gsLst")?.children("gs").orEmpty().mapNotNull { gs -> Dml.color(gs, null, ctx.scheme)?.let { gs.int("pos") to it } }
        if (stops.isEmpty()) return null
        if (stops.size == 1) return stops[0].second
        val a = stops.first().second
        val z = stops.last().second
        fun mix(sh: Int) = (((a ushr sh) and 0xFF) + ((z ushr sh) and 0xFF)) / 2
        return (mix(24) shl 24) or (mix(16) shl 16) or (mix(8) shl 8) or mix(0)
    }

    private fun lineStyle(ln: XNode?, style: XNode?, ctx: Ctx): LineStyle? {
        val ref = style?.child("lnRef")
        val refIdx = ref?.int("idx") ?: 0
        val fillEl = ln?.children?.firstOrNull { it.name in FILL_TAGS }
        if (fillEl?.name == "noFill") return null
        val color = when (fillEl?.name) {
            "solidFill" -> Dml.color(fillEl, null, ctx.scheme)
            "gradFill" -> gradientColor(fillEl, ctx)
            null -> if (refIdx > 0) Dml.color(ref, null, ctx.scheme) else null
            else -> null
        } ?: return null
        val wEmu = ln?.get("w")?.toIntOrNull()
            ?: ctx.theme.lineWidths.getOrNull(refIdx - 1) ?: 9525
        val dash = ln?.child("prstDash")?.get("val") ?: "solid"
        val st = when {
            dash == "solid" -> "SOLID"
            dash.contains("Dot", ignoreCase = true) && !dash.contains("Dash", ignoreCase = true) || dash == "dot" -> "DOT"
            else -> "DASH"
        }
        return LineStyle(color, HBuilder.emu(wEmu), st)
    }

    // ================= 글 =================

    /**
     * 글 상자 → 문단들. [chain]은 이 도형과 이어받는 도형들, [master]는 마스터의 글자 모양 묶음.
     * 글자 모양은 가까운 것부터 찾는다: 글자 → 문단 → 도형 lstStyle → 레이아웃·마스터 도형 → 마스터 → 프레젠테이션 기본
     */
    private fun textBody(
        body: XNode, chain: List<XNode>, master: XNode?, ctx: Ctx, style: XNode?,
        cellText: XNode? = null,
    ): List<HPara> {
        val lstStyles = chain.mapNotNull { it.path("txBody", "lstStyle") }.ifEmpty { listOfNotNull(body.child("lstStyle")) }
        val autofit = chain.firstNotNullOfOrNull { it.path("txBody", "bodyPr", "normAutofit") } ?: body.path("bodyPr", "normAutofit")
        val fontScale = (autofit?.int("fontScale", 100000) ?: 100000) / 100000f
        val lnReduce = (autofit?.int("lnSpcReduction", 0) ?: 0) / 100000f
        val fontRefColor = style?.child("fontRef")?.let { Dml.color(it, null, ctx.scheme) }
            ?: cellText?.let { Dml.color(it, null, ctx.scheme) ?: Dml.color(it.child("fontRef"), null, ctx.scheme) }
        val numIds = HashMap<String, Int>()
        val out = ArrayList<HPara>()

        for (p in body.children("p")) {
            val pPr = p.child("pPr")
            val lvl = (pPr?.int("lvl") ?: 0).coerceIn(0, 8)
            val key = "lvl${lvl + 1}pPr"
            val levels = ArrayList<XNode>()
            pPr?.let { levels.add(it) }
            for (ls in lstStyles) ls.child(key)?.let { levels.add(it) }
            master?.child(key)?.let { levels.add(it) }
            if (chain.first().ph() == null || master == null) defaultTextStyle?.child(key)?.let { levels.add(it) }
            fun pa(n: String) = levels.firstNotNullOfOrNull { it[n] }
            fun pc(n: String) = levels.firstNotNullOfOrNull { it.child(n) }
            val defRPrs = levels.mapNotNull { it.child("defRPr") }

            fun run(rPr: XNode?): HBuilder.Run {
                val list = ArrayList<XNode>(defRPrs.size + 1)
                rPr?.let { list.add(it) }
                list.addAll(defRPrs)
                fun ra(n: String) = list.firstNotNullOfOrNull { it[n] }
                fun rc(n: String) = list.firstNotNullOfOrNull { it.child(n) }
                val sz = ((ra("sz")?.toIntOrNull() ?: 1800) * fontScale).roundToInt()
                val fillHolder = list.firstNotNullOfOrNull { n -> n.children.firstOrNull { it.name == "solidFill" || it.name == "gradFill" || it.name == "noFill" } }
                val color = when (fillHolder?.name) {
                    "solidFill" -> Dml.color(fillHolder, null, ctx.scheme)
                    "gradFill" -> gradientColor(fillHolder, ctx)
                    "noFill" -> 0
                    else -> null
                } ?: fontRefColor ?: ctx.scheme["tx1"] ?: 0xFF000000.toInt()
                val baseline = ra("baseline")?.toIntOrNull() ?: 0
                return HBuilder.Run(
                    latin = ctx.theme.font(rc("latin")?.get("typeface") ?: "+mn-lt"),
                    ea = ctx.theme.font(rc("ea")?.get("typeface") ?: "+mn-ea"),
                    size = sz,
                    color = color,
                    bold = (cellText?.get("b") == "on" && ra("b") == null) || ra("b").let { it == "1" || it == "true" },
                    italic = ra("i").let { it == "1" || it == "true" },
                    underline = ra("u").let { it != null && it != "none" },
                    strike = ra("strike").let { it != null && it != "noStrike" },
                    sup = baseline > 0,
                    sub = baseline < 0,
                    shade = rc("highlight")?.let { Dml.color(it, null, ctx.scheme) },
                    spacing = ra("spc")?.toIntOrNull()?.let { if (sz > 0) it * 100 / sz else 0 } ?: 0,
                )
            }
            val caps = { rPr: XNode? -> (rPr?.get("cap") ?: defRPrs.firstNotNullOfOrNull { it["cap"] }) == "all" }

            val para = HBuilder.Para()
            var firstSize = 0
            for (c in p.children) {
                when (c.name) {
                    "r", "fld" -> {
                        val rPr = c.child("rPr")
                        val r = run(rPr)
                        if (firstSize == 0) firstSize = r.size
                        val cs = b.charShape(r)
                        var t = c.child("t")?.text ?: ""
                        if (c.name == "fld" && (c["type"] == "slidenum" || t == "‹#›")) t = ctx.slideNo.toString()
                        if (caps(rPr)) t = t.uppercase()
                        appendText(para, t, cs, r.size)
                    }
                    "br" -> para.text("\n", b.charShape(run(c.child("rPr"))))
                }
            }
            val endRun = run(p.child("endParaRPr"))
            para.endCharShape = b.charShape(endRun)
            if (firstSize == 0) firstSize = endRun.size

            val ps = ParaShape()
            ps.align = when (pa("algn")) {
                "ctr" -> Align.CENTER
                "r" -> Align.RIGHT
                "just", "justLow" -> Align.JUSTIFY
                "dist", "thaiDist" -> Align.DISTRIBUTE
                else -> Align.LEFT
            }
            val marL = HBuilder.emu(pa("marL")?.toLongOrNull() ?: 0)
            val indent = HBuilder.emu(pa("indent")?.toLongOrNull() ?: 0)
            ps.left = max(0, marL + minOf(indent, 0))
            ps.indent = indent
            ps.right = 0
            val lnSpc = pc("lnSpc")
            val pct = lnSpc?.child("spcPct")?.int("val")
            val pts = lnSpc?.child("spcPts")?.int("val")
            if (pts != null) {
                ps.lineSpacingType = "FIXED"
                ps.lineSpacing = (pts * (1 - lnReduce)).roundToInt()
            } else {
                ps.lineSpacingType = "PERCENT"
                // 파워포인트의 줄 간격 100%는 글자 크기의 약 1.2배
                ps.lineSpacing = max(50, ((pct ?: 100000) / 1000f * 1.2f * (1 - lnReduce)).roundToInt())
            }
            fun spacing(n: String): Int {
                val s = pc(n) ?: return 0
                s.child("spcPts")?.let { return it.int("val") }
                s.child("spcPct")?.let { return (firstSize * it.int("val") / 100000f * 1.2f).roundToInt() }
                return 0
            }
            ps.prev = spacing("spcBef")
            ps.next = spacing("spcAft")
            // 파워포인트는 줄 간격 여유를 글자 위아래에 나눠 두고, 글머리표 뒤 글을 내어쓰기 자리에서 시작한다
            ps.leadAbove = 0.5f
            ps.prefixTab = true

            // 글머리표·번호 (글이 있는 문단만)
            val bu = levels.firstNotNullOfOrNull { n -> n.children.firstOrNull { it.name == "buNone" || it.name == "buChar" || it.name == "buAutoNum" || it.name == "buBlip" } }
            if (para.hasText) when (bu?.name) {
                "buChar" -> {
                    var ch = bu["char"] ?: "•"
                    val font = pc("buFont")?.get("typeface").orEmpty()
                    if (font.startsWith("Wingdings", ignoreCase = true) || font.startsWith("Symbol", ignoreCase = true)) ch = WINGDINGS[ch] ?: "•"
                    ps.headingType = "BULLET"
                    ps.headingIdRef = b.bullet(ch)
                }
                "buBlip" -> {
                    ps.headingType = "BULLET"
                    ps.headingIdRef = b.bullet("•")
                }
                "buAutoNum" -> {
                    val scheme = bu["type"] ?: "arabicPeriod"
                    val start = bu.int("startAt", 1)
                    val id = numIds.getOrPut("$scheme|$start|$lvl") {
                        val n = Numbering()
                        val (fmt, type) = autoNum(scheme, lvl + 1)
                        for (k in 0 until 10) { n.formats[k] = fmt; n.numTypes[k] = type; n.starts[k] = start }
                        b.numbering(n)
                    }
                    ps.headingType = "NUMBER"
                    ps.headingIdRef = id
                    ps.headingLevel = lvl
                }
            }
            para.paraShapeId = b.paraShape(ps)
            out.add(para.build())
        }
        return out
    }

    /** 글 조각 붙이기: 탭은 탭으로, 글 안의 줄바꿈 문자는 줄바꿈으로 (파워포인트도 줄을 바꿔 보여 준다) */
    private fun appendText(para: HBuilder.Para, t: String, cs: Int, size: Int) {
        if (t.isEmpty()) return
        val clean = t.replace("\r\n", "\n").replace('\r', '\n').replace('\u000B', '\n')
        val parts = clean.split('\t')
        for ((i, s) in parts.withIndex()) {
            if (i > 0) para.tab(size * 4, cs)
            para.text(s, cs)
        }
    }

    private fun autoNum(scheme: String, level: Int): Pair<String, String> {
        val n = "^$level"
        val type = when {
            scheme.startsWith("romanUc") -> "ROMAN_CAPITAL"
            scheme.startsWith("romanLc") -> "ROMAN_SMALL"
            scheme.startsWith("alphaUc") -> "LATIN_CAPITAL"
            scheme.startsWith("alphaLc") -> "LATIN_SMALL"
            scheme.startsWith("circleNum") -> "CIRCLED_DIGIT"
            scheme.startsWith("hebrew") || scheme.startsWith("arabicAbjad") -> "DIGIT"
            else -> "DIGIT"
        }
        val fmt = when {
            scheme.endsWith("ParenBoth") -> "($n)"
            scheme.endsWith("ParenR") -> "$n)"
            scheme.endsWith("Period") -> "$n."
            scheme.endsWith("Minus") -> "- $n -"
            else -> n
        }
        return fmt to type
    }

    // ================= 표 =================

    /** 표 모양(tableStyles.xml)의 한 부분 (wholeTbl, firstRow …) */
    private class TblPart(val fill: Int?, val borders: Map<String, BorderLine>, val bold: Boolean?, val color: Int?)

    private fun tablePart(style: XNode?, name: String, ctx: Ctx): TblPart? {
        val n = style?.child(name) ?: return null
        val tc = n.child("tcStyle")
        val fillEl = tc?.child("fill")?.children?.firstOrNull()
        val fill = when (fillEl?.name) {
            "solidFill" -> Dml.color(fillEl, null, ctx.scheme)
            "noFill" -> 0
            else -> tc?.child("fillRef")?.let { Dml.color(it, null, ctx.scheme) }
        }
        val borders = HashMap<String, BorderLine>()
        tc?.child("tcBdr")?.children?.forEach { e ->
            val ln = e.child("ln")
            val bl = if (ln != null) borderLine(ln, ctx) else e.child("lnRef")?.let { r ->
                Dml.color(r, null, ctx.scheme)?.let { BorderLine("SOLID", 1f, it) }
            }
            if (bl != null) borders[e.name] = bl
        }
        val tx = n.child("tcTxStyle")
        val color = tx?.let { Dml.color(it, null, ctx.scheme) ?: Dml.color(it.child("fontRef"), null, ctx.scheme) }
        val bold = tx?.get("b")?.let { it == "on" }
        return TblPart(fill, borders, bold, color)
    }

    private fun borderLine(ln: XNode, ctx: Ctx): BorderLine {
        val f = ln.children.firstOrNull { it.name in FILL_TAGS }
        if (f == null || f.name == "noFill") return BorderLine.NONE
        val color = Dml.color(f, null, ctx.scheme) ?: return BorderLine.NONE
        val w = ln.int("w", 12700) / 12700f
        val dash = ln.child("prstDash")?.get("val") ?: "solid"
        return BorderLine(if (dash == "solid") "SOLID" else "DASH", w, color)
    }

    /** tableStyles.xml에 없는 기본 표 모양 (파워포인트 기본 '보통 스타일 2 - 강조 1'과 비슷하게) */
    private fun defaultTableStyle(ctx: Ctx): Map<String, TblPart> {
        val accent = ctx.scheme["accent1"] ?: 0xFF4472C4.toInt()
        fun tint(k: Float): Int {
            val r = (accent shr 16) and 0xFF; val g = (accent shr 8) and 0xFF; val bl = accent and 0xFF
            fun t(c: Int) = (c + (255 - c) * (1 - k)).roundToInt().coerceIn(0, 255)
            return 0xFF000000.toInt() or (t(r) shl 16) or (t(g) shl 8) or t(bl)
        }
        val white = BorderLine("SOLID", 1f, -1)
        val all = mapOf("left" to white, "right" to white, "top" to white, "bottom" to white, "insideH" to white, "insideV" to white)
        return mapOf(
            "wholeTbl" to TblPart(tint(0.2f), all, null, ctx.scheme["tx1"]),
            "band1H" to TblPart(tint(0.4f), emptyMap(), null, null),
            "firstRow" to TblPart(accent, emptyMap(), true, -1),
            "lastRow" to TblPart(accent, emptyMap(), true, -1),
        )
    }

    private fun table(tbl: XNode, bx: Box, ctx: Ctx, out: MutableList<HObject>) {
        val grid = tbl.child("tblGrid")?.children("gridCol").orEmpty().map { it.long("w") }
        if (grid.isEmpty()) return
        val rows = tbl.children("tr")
        val pr = tbl.child("tblPr")
        val styleId = pr?.child("tableStyleId")?.text?.trim()
        val styleNode = tableStyles?.children("tblStyle")?.firstOrNull { it["styleId"] == styleId }
        val parts: Map<String, TblPart> = if (styleNode != null) {
            listOf("wholeTbl", "band1H", "band2H", "band1V", "band2V", "firstRow", "lastRow", "firstCol", "lastCol")
                .mapNotNull { n -> tablePart(styleNode, n, ctx)?.let { n to it } }.toMap()
        } else if (styleId != null) defaultTableStyle(ctx) else emptyMap()
        val firstRow = pr?.flag("firstRow") == true
        val lastRow = pr?.flag("lastRow") == true
        val firstCol = pr?.flag("firstCol") == true
        val lastCol = pr?.flag("lastCol") == true
        val bandRow = pr?.flag("bandRow") == true
        val bandCol = pr?.flag("bandCol") == true
        val nRows = rows.size
        val nCols = grid.size

        val t = HTable()
        place(t, bx.x, bx.y, grid.sum() * 1.0, rows.sumOf { it.long("h") } * 1.0)
        t.rowCnt = nRows
        t.colCnt = nCols
        val rowH = rows.map { it.long("h") }
        for ((r, tr) in rows.withIndex()) {
            var c = 0
            for (tc in tr.children("tc")) {
                val col = c
                val span = tc.int("gridSpan", 1).coerceAtLeast(1)
                c += if (tc.flag("hMerge")) 1 else span
                if (tc.flag("hMerge") || tc.flag("vMerge") || col >= nCols) continue
                val rSpan = tc.int("rowSpan", 1).coerceAtLeast(1)
                val cell = HCell()
                cell.row = r
                cell.col = col
                cell.colSpan = span.coerceAtMost(nCols - col)
                cell.rowSpan = rSpan.coerceAtMost(nRows - r)
                cell.width = HBuilder.emu(grid.subList(col, col + cell.colSpan).sum())
                cell.height = HBuilder.emu(rowH.subList(r, r + cell.rowSpan).sum())
                val tcPr = tc.child("tcPr")
                cell.marginLeft = HBuilder.emu(tcPr?.long("marL", 91440) ?: 91440)
                cell.marginRight = HBuilder.emu(tcPr?.long("marR", 91440) ?: 91440)
                cell.marginTop = HBuilder.emu(tcPr?.long("marT", 45720) ?: 45720)
                cell.marginBottom = HBuilder.emu(tcPr?.long("marB", 45720) ?: 45720)
                cell.hasMargin = true
                cell.vertAlign = when (tcPr?.get("anchor")) {
                    "ctr" -> "CENTER"
                    "b" -> "BOTTOM"
                    else -> "TOP"
                }

                // 이 칸에 걸리는 표 모양 부분 (뒤에 올수록 우선)
                val applied = ArrayList<TblPart>()
                parts["wholeTbl"]?.let { applied.add(it) }
                val bandR = r - if (firstRow) 1 else 0
                if (bandRow && bandR >= 0 && !(lastRow && r == nRows - 1)) parts[if (bandR % 2 == 0) "band1H" else "band2H"]?.let { applied.add(it) }
                val bandC = col - if (firstCol) 1 else 0
                if (bandCol && bandC >= 0) parts[if (bandC % 2 == 0) "band1V" else "band2V"]?.let { applied.add(it) }
                if (lastCol && col + cell.colSpan == nCols) parts["lastCol"]?.let { applied.add(it) }
                if (firstCol && col == 0) parts["firstCol"]?.let { applied.add(it) }
                if (lastRow && r + cell.rowSpan == nRows) parts["lastRow"]?.let { applied.add(it) }
                if (firstRow && r == 0) parts["firstRow"]?.let { applied.add(it) }

                val bf = BorderFill()
                val fillEl = tcPr?.children?.firstOrNull { it.name in FILL_TAGS }
                bf.fillColor = when (fillEl?.name) {
                    "solidFill" -> Dml.color(fillEl, null, ctx.scheme)
                    "gradFill" -> gradientColor(fillEl, ctx)
                    "noFill" -> null
                    else -> applied.lastOrNull { it.fill != null }?.fill?.takeIf { it != 0 }
                }
                fun edge(own: String, outer: String, inner: String, isOuter: Boolean): BorderLine {
                    tcPr?.child(own)?.let { return borderLine(it, ctx) }
                    val key = if (isOuter) outer else inner
                    return applied.lastOrNull { it.borders.containsKey(key) }?.borders?.get(key) ?: BorderLine.NONE
                }
                bf.left = edge("lnL", "left", "insideV", col == 0)
                bf.right = edge("lnR", "right", "insideV", col + cell.colSpan == nCols)
                bf.top = edge("lnT", "top", "insideH", r == 0)
                bf.bottom = edge("lnB", "bottom", "insideH", r + cell.rowSpan == nRows)
                cell.borderFillId = b.borderFill(bf)

                val txt = applied.lastOrNull { it.color != null || it.bold != null }
                val holder = if (txt != null) XNode("tcTxStyle", if (applied.any { it.bold == true }) mapOf("b" to "on") else emptyMap()).also { n ->
                    applied.lastOrNull { it.color != null }?.color?.let { col2 ->
                        n.children.add(XNode("srgbClr", mapOf("val" to String.format("%06X", col2 and 0xFFFFFF))))
                    }
                } else null
                cell.paras = tc.child("txBody")?.let { textBody(it, listOf(tc), null, ctx, null, cellText = holder) } ?: emptyList()
                t.cells.add(cell)
            }
        }
        out.add(t)
    }
}
