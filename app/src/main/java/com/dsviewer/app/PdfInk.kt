package com.dsviewer.app

import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.util.Base64
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.multipdf.LayerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDPatternContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
import com.tom_roush.pdfbox.pdmodel.graphics.pattern.PDTilingPattern
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
import com.tom_roush.pdfbox.util.Matrix
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Calendar
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 필기를 PDF 표준 잉크 주석(/Ink)으로 저장하고 다시 읽는다.
 * 다른 PDF 앱에서도 보이도록 외형(appearance stream)을 함께 넣고,
 * 이 앱에서 다시 편집할 수 있도록 원본 획 데이터를 전용 키에 보관한다.
 */
object PdfInk {
    private const val KEY = "DSViewerStroke"
    private val KEY_NAME: COSName = COSName.getPDFName(KEY)
    /** 북마크한 쪽: 쪽 사전에 이 키를 true로 */
    private val MARK_NAME: COSName = COSName.getPDFName("DSViewerMark")
    const val HL_ALPHA = 0.45f

    /**
     * 이 앱이 저장한 필기나 북마크가 들어 있는지 빠르게 확인 (PDFBox는 주석·쪽 사전을 압축하지 않고 쓴다).
     * 두 키 모두 /DSViewer로 시작한다
     */
    fun containsInk(file: File): Boolean {
        val pattern = "/DSViewer".toByteArray()
        var matched = 0
        file.inputStream().buffered(1 shl 16).use { input ->
            while (true) {
                val b = input.read()
                if (b < 0) return false
                matched = when {
                    b.toByte() == pattern[matched] -> matched + 1
                    b.toByte() == pattern[0] -> 1
                    else -> 0
                }
                if (matched == pattern.size) return true
            }
        }
    }

    /**
     * 이 앱의 필기를 꺼내고, 필기를 뺀 PDF를 [clean]에 저장한다 (화면 렌더링용).
     * [marks]를 주면 북마크한 쪽 번호(0부터)를 담는다
     */
    fun extract(src: File, clean: File, marks: MutableSet<Int>? = null): List<List<Stroke>> {
        val result = ArrayList<MutableList<Stroke>>()
        PDDocument.load(src).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            for ((pi, page) in doc.pages.withIndex()) {
                if (page.cosObject.getBoolean(MARK_NAME, false)) marks?.add(pi)
                val strokes = mutableListOf<Stroke>()
                val annots = page.annotations
                val keep = ArrayList<PDAnnotation>()
                for (a in annots) {
                    val s = a.cosObject.getString(KEY_NAME)
                    if (s == null) {
                        keep.add(a)
                        continue
                    }
                    val st = decode(s) ?: continue
                    // 그림: 외형 안의 그림을 꺼낸다 (못 꺼내면 버림)
                    if (s.startsWith("1|I|")) st.image = readImage(a) ?: continue
                    strokes.add(st)
                }
                if (keep.size != annots.size) page.annotations = keep
                result.add(strokes)
            }
            doc.save(clean)
        }
        return result
    }

    /** [page]쪽의 이 앱 필기만 꺼낸다 (썸네일용. PdfRenderer는 주석을 그리지 않으므로 원본 위에 얹으면 된다) */
    fun pageStrokes(src: File, page: Int): List<Stroke> {
        PDDocument.load(src).use { doc ->
            if (page !in 0 until doc.numberOfPages) return emptyList()
            return doc.getPage(page).annotations.mapNotNull { a ->
                val s = a.cosObject.getString(KEY_NAME) ?: return@mapNotNull null
                val st = decode(s) ?: return@mapNotNull null
                if (s.startsWith("1|I|")) st.image = readImage(a) ?: return@mapNotNull null
                st
            }
        }
    }

    /** 원본 [src]에 필기를 주석으로 넣고 [marks] 쪽(0부터)에 북마크를 달아 [out]에 저장 */
    fun save(src: File, out: File, pages: List<List<Stroke>>, marks: Set<Int> = emptySet()) {
        // 글 외형을 그린 임시 PDF (다 저장한 뒤에 닫는다)
        var texts: TextForms? = null
        try {
            PDDocument.load(src).use { doc ->
                if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
                texts = renderTexts(doc, pages)
                for ((i, page) in doc.pages.withIndex()) {
                    val list: MutableList<PDAnnotation> =
                        page.annotations.filterTo(ArrayList()) { !it.cosObject.containsKey(KEY_NAME) }
                    if (i < pages.size) {
                        // 그림을 먼저 (다른 앱에서도 필기가 그림 위에 보이게)
                        for (s in pages[i].sortedBy { if (it.image != null) 0 else 1 }) {
                            if (s.count == 0) continue
                            list.add(
                                when {
                                    s.image != null -> makeImageAnnotation(doc, page, s)
                                    s.text != null -> makeTextAnnotation(doc, page, s, texts!!)
                                    s.tape != null -> makeTapeAnnotation(doc, page, s)
                                    else -> makeAnnotation(doc, page, s)
                                }
                            )
                        }
                    }
                    page.annotations = list
                    if (i in marks) page.cosObject.setBoolean(MARK_NAME, true)
                    else page.cosObject.removeItem(MARK_NAME)
                }
                doc.save(out)
            }
        } finally {
            texts?.src?.close()
        }
    }

    /** 글 상자 외형들: 한 임시 PDF의 쪽들 ([index]는 글 획 → 쪽 번호) */
    private class TextForms(val src: PDDocument?, val index: java.util.IdentityHashMap<Stroke, Int>, val layer: LayerUtility)

    /**
     * 모든 글 상자를 안드로이드 PDF 하나에 한 쪽씩 그린다. 한 문서 안에서는 글꼴이 한 번만 담기고
     * (쓴 글자만 모아서), 같은 LayerUtility로 가져오면 그 글꼴을 모든 글 상자가 같이 쓴다.
     * 글 상자마다 PDF를 따로 만들면 같은 글꼴이 글 상자 수만큼 들어가 파일이 커진다
     */
    private fun renderTexts(doc: PDDocument, pages: List<List<Stroke>>): TextForms {
        val index = java.util.IdentityHashMap<Stroke, Int>()
        val layer = LayerUtility(doc)
        val all = pages.flatten().filter { it.text != null && it.count > 0 }
        if (all.isEmpty()) return TextForms(null, index, layer)
        val bytes = ByteArrayOutputStream().use { out ->
            val pd = PdfDocument()
            try {
                for ((n, s) in all.withIndex()) {
                    val t = s.text!!
                    val pg = pd.startPage(PdfDocument.PageInfo.Builder(t.boxW, t.boxH, n + 1).create())
                    t.draw(pg.canvas, s.color)
                    pd.finishPage(pg)
                    index[s] = n
                }
                pd.writeTo(out)
            } finally {
                pd.close()
            }
            out.toByteArray()
        }
        return TextForms(PDDocument.load(bytes), index, layer)
    }

    private fun makeAnnotation(doc: PDDocument, page: PDPage, s: Stroke): PDAnnotation {
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val n = s.count
        val ux = FloatArray(n)
        val uy = FloatArray(n)
        var minX = Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            toUser(s.x(i), s.y(i), box, rot, ux, uy, i)
            minX = min(minX, ux[i]); maxX = max(maxX, ux[i])
            minY = min(minY, uy[i]); maxY = max(maxY, uy[i])
        }
        val pad = s.width + 2f
        val rect = PDRectangle(minX - pad, minY - pad, (maxX - minX) + pad * 2, (maxY - minY) + pad * 2)
        val color = PDColor(
            floatArrayOf(Color.red(s.color) / 255f, Color.green(s.color) / 255f, Color.blue(s.color) / 255f),
            PDDeviceRGB.INSTANCE
        )

        // 외형: 필압 구간마다 선 굵기를 바꿔 그린다
        val ap = PDAppearanceStream(doc)
        ap.bBox = rect
        ap.resources = PDResources()
        PDPageContentStream(doc, ap).use { cs ->
            if (s.tool == Tool.HIGHLIGHTER) {
                val gs = PDExtendedGraphicsState()
                gs.strokingAlphaConstant = HL_ALPHA
                gs.blendMode = BlendMode.MULTIPLY
                cs.setGraphicsStateParameters(gs)
            }
            cs.setStrokingColor(color)
            if (s.dashed) cs.setLineDashPattern(s.dashIntervals(), 0f)
            cs.setLineCapStyle(1)
            cs.setLineJoinStyle(1)
            s.forEachGroup { w, from, to ->
                cs.setLineWidth(w)
                cs.moveTo(ux[from], uy[from])
                if (from == to) cs.lineTo(ux[from] + 0.01f, uy[from])
                else for (k in from + 1..to) cs.lineTo(ux[k], uy[k])
                cs.stroke()
            }
        }

        val dict = COSDictionary()
        dict.setItem(COSName.TYPE, COSName.ANNOT)
        dict.setItem(COSName.SUBTYPE, COSName.getPDFName("Ink"))
        val annot = PDAnnotation.createAnnotation(dict)
        annot.rectangle = rect
        annot.color = color
        annot.isPrinted = true
        annot.annotationName = UUID.randomUUID().toString()
        annot.setModifiedDate(Calendar.getInstance())
        annot.page = page

        val inkPath = COSArray()
        for (i in 0 until n) {
            inkPath.add(COSFloat(ux[i]))
            inkPath.add(COSFloat(uy[i]))
        }
        val inkList = COSArray()
        inkList.add(inkPath)
        dict.setItem(COSName.getPDFName("InkList"), inkList)
        val bs = COSDictionary()
        bs.setFloat(COSName.W, s.width)
        if (s.dashed) {
            bs.setName(COSName.S, "D")
            bs.setItem(COSName.D, COSArray().apply { s.dashIntervals().forEach { add(COSFloat(it)) } })
        }
        dict.setItem(COSName.BS, bs)
        if (s.tool == Tool.HIGHLIGHTER) dict.setFloat(COSName.CA, HL_ALPHA)
        dict.setString(KEY_NAME, encode(s))

        val apd = PDAppearanceDictionary()
        apd.setNormalAppearance(ap)
        annot.appearance = apd
        return annot
    }

    /** 그림 주석(Stamp): 외형에 그림을 네 모서리에 맞춰 그려 넣는다 */
    private fun makeImageAnnotation(doc: PDDocument, page: PDPage, s: Stroke): PDAnnotation {
        val img = s.image!!
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val ux = FloatArray(4)
        val uy = FloatArray(4)
        for (i in 0 until 4) toUser(s.x(i), s.y(i), box, rot, ux, uy, i)
        val rect = PDRectangle(ux.min(), uy.min(), ux.max() - ux.min(), uy.max() - uy.min())
        val x: PDImageXObject = if (img.bytes != null) JPEGFactory.createFromByteArray(doc, img.bytes)
        else LosslessFactory.createFromImage(doc, img.bitmap)

        val ap = PDAppearanceStream(doc)
        ap.bBox = rect
        ap.resources = PDResources()
        PDPageContentStream(doc, ap).use { cs ->
            // 그림 공간의 (1,0)은 오른쪽 아래, (0,1)은 왼쪽 위, 원점은 왼쪽 아래 모서리
            cs.drawImage(x, Matrix(ux[2] - ux[3], uy[2] - uy[3], ux[0] - ux[3], uy[0] - uy[3], ux[3], uy[3]))
        }
        val dict = COSDictionary()
        dict.setItem(COSName.TYPE, COSName.ANNOT)
        dict.setItem(COSName.SUBTYPE, COSName.getPDFName("Stamp"))
        val annot = PDAnnotation.createAnnotation(dict)
        annot.rectangle = rect
        annot.isPrinted = true
        annot.annotationName = UUID.randomUUID().toString()
        annot.setModifiedDate(Calendar.getInstance())
        annot.page = page
        dict.setString(KEY_NAME, encode(s))
        val apd = PDAppearanceDictionary()
        apd.setNormalAppearance(ap)
        annot.appearance = apd
        return annot
    }

    /**
     * 글 주석(Stamp). 글을 안드로이드 PDF 한 쪽(상자 크기)으로 그려 글꼴째 담고 ([renderTexts]),
     * 그 쪽을 폼으로 가져와 네 모서리에 맞춰 외형에 넣는다 (다른 앱에서도 글자가 그대로, 벡터로 보인다)
     */
    private fun makeTextAnnotation(doc: PDDocument, page: PDPage, s: Stroke, texts: TextForms): PDAnnotation {
        val t = s.text!!
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val ux = FloatArray(4)
        val uy = FloatArray(4)
        for (i in 0 until 4) toUser(s.x(i), s.y(i), box, rot, ux, uy, i)
        val rect = PDRectangle(ux.min(), uy.min(), ux.max() - ux.min(), uy.max() - uy.min())

        val form = texts.layer.importPageAsForm(texts.src!!, texts.index.getValue(s))

        val ap = PDAppearanceStream(doc)
        ap.bBox = rect
        ap.resources = PDResources()
        val w = t.boxW.toFloat()
        val h = t.boxH.toFloat()
        PDPageContentStream(doc, ap).use { cs ->
            // 폼 공간: 원점은 왼쪽 아래 모서리(3), 가로는 오른쪽 아래(2) 쪽, 세로는 왼쪽 위(0) 쪽
            cs.saveGraphicsState()
            cs.transform(Matrix((ux[2] - ux[3]) / w, (uy[2] - uy[3]) / w, (ux[0] - ux[3]) / h, (uy[0] - uy[3]) / h, ux[3], uy[3]))
            cs.drawForm(form)
            cs.restoreGraphicsState()
        }
        val dict = COSDictionary()
        dict.setItem(COSName.TYPE, COSName.ANNOT)
        dict.setItem(COSName.SUBTYPE, COSName.getPDFName("Stamp"))
        val annot = PDAnnotation.createAnnotation(dict)
        annot.rectangle = rect
        annot.isPrinted = true
        annot.annotationName = UUID.randomUUID().toString()
        annot.setModifiedDate(Calendar.getInstance())
        annot.page = page
        dict.setString(KEY_NAME, encode(s))
        val apd = PDAppearanceDictionary()
        apd.setNormalAppearance(ap)
        annot.appearance = apd
        return annot
    }

    /**
     * 테이프 주석(Stamp): 외형에 테이프 영역을 바탕색으로 칠하고 그 위를 무늬(타일 무늬)로 한 번 더 칠한다.
     * 보이게 해 둔 테이프도 늘 가린 모습으로 저장한다 (파일을 다시 열면 가린 상태로 시작)
     */
    private fun makeTapeAnnotation(doc: PDDocument, page: PDPage, s: Stroke): PDAnnotation {
        val style = s.tape!!
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        // 테이프 영역을 다각형들로 (사용자 좌표)
        val polys = ArrayList<FloatArray>()
        val tmpX = FloatArray(1)
        val tmpY = FloatArray(1)
        fun addPoly(xs: List<Float>) {
            if (xs.size < 6) return
            val out = FloatArray(xs.size)
            for (i in 0 until xs.size / 2) {
                toUser(xs[i * 2], xs[i * 2 + 1], box, rot, tmpX, tmpY, 0)
                out[i * 2] = tmpX[0]
                out[i * 2 + 1] = tmpY[0]
            }
            polys.add(out)
        }
        val outline = s.tapeOutline()
        if (style.rect && s.holes.isEmpty()) {
            addPoly((0 until s.count).flatMap { listOf(s.x(it), s.y(it)) })
        } else {
            // 곡선을 잘게 나눈 점들 (분수, x, y). 윤곽이 끊기는 곳은 같은 분수가 두 번 나온다
            val a = outline.approximate(0.2f)
            val cur = ArrayList<Float>()
            var i = 0
            while (i + 2 < a.size) {
                if (i > 0 && a[i] == a[i - 3] && (a[i + 1] != a[i - 2] || a[i + 2] != a[i - 1])) {
                    addPoly(cur)
                    cur.clear()
                }
                cur.add(a[i + 1]); cur.add(a[i + 2])
                i += 3
            }
            addPoly(cur)
        }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (pl in polys) for (k in 0 until pl.size / 2) {
            minX = min(minX, pl[k * 2]); maxX = max(maxX, pl[k * 2])
            minY = min(minY, pl[k * 2 + 1]); maxY = max(maxY, pl[k * 2 + 1])
        }
        if (polys.isEmpty()) { minX = 0f; minY = 0f; maxX = 1f; maxY = 1f }
        val rect = PDRectangle(minX - 1f, minY - 1f, maxX - minX + 2f, maxY - minY + 2f)

        val ap = PDAppearanceStream(doc)
        ap.bBox = rect
        ap.resources = PDResources()
        val evenOdd = (!style.rect || s.holes.isNotEmpty()) && (outline.fillType == android.graphics.Path.FillType.EVEN_ODD ||
            outline.fillType == android.graphics.Path.FillType.INVERSE_EVEN_ODD)
        val sb = StringBuilder()
        fun n(v: Float) = String.format(java.util.Locale.US, "%.3f", v)
        fun pathOps() {
            for (pl in polys) {
                sb.append(n(pl[0])).append(' ').append(n(pl[1])).append(" m\n")
                for (k in 1 until pl.size / 2) sb.append(n(pl[k * 2])).append(' ').append(n(pl[k * 2 + 1])).append(" l\n")
                sb.append("h\n")
            }
            sb.append(if (evenOdd) "f*\n" else "f\n")
        }
        val c = s.color
        sb.append("q\n")
        sb.append(n(Color.red(c) / 255f)).append(' ').append(n(Color.green(c) / 255f)).append(' ')
            .append(n(Color.blue(c) / 255f)).append(" rg\n")
        pathOps()
        if (style.pattern != TapePattern.SOLID) {
            val anchorX = FloatArray(1)
            val anchorY = FloatArray(1)
            toUser(s.x(0), s.y(0), box, rot, anchorX, anchorY, 0)
            val name = ap.resources.add(tilePattern(style.pattern, c, anchorX[0], anchorY[0]))
            sb.append("/Pattern cs /").append(name.name).append(" scn\n")
            pathOps()
        }
        sb.append("Q\n")
        ap.cosObject.createOutputStream().use { it.write(sb.toString().toByteArray(Charsets.US_ASCII)) }

        val dict = COSDictionary()
        dict.setItem(COSName.TYPE, COSName.ANNOT)
        dict.setItem(COSName.SUBTYPE, COSName.getPDFName("Stamp"))
        val annot = PDAnnotation.createAnnotation(dict)
        annot.rectangle = rect
        annot.isPrinted = true
        annot.annotationName = UUID.randomUUID().toString()
        annot.setModifiedDate(Calendar.getInstance())
        annot.page = page
        dict.setString(KEY_NAME, encode(s))
        val apd = PDAppearanceDictionary()
        apd.setNormalAppearance(ap)
        annot.appearance = apd
        return annot
    }

    /** 테이프 무늬 한 칸 (TapeTile.shapes)을 PDF 타일 무늬로. 무늬는 테이프의 첫 점 (x0, y0)에 붙인다 */
    private fun tilePattern(p: TapePattern, base: Int, x0: Float, y0: Float): PDTilingPattern {
        val s = TapeTile.PERIOD
        val pat = PDTilingPattern()
        pat.paintType = PDTilingPattern.PAINT_COLORED
        pat.tilingType = PDTilingPattern.TILING_CONSTANT_SPACING
        pat.bBox = PDRectangle(0f, 0f, s, s)
        pat.xStep = s
        pat.yStep = s
        pat.cosObject.setItem(COSName.MATRIX, COSArray().apply {
            floatArrayOf(1f, 0f, 0f, 1f, x0, y0).forEach { add(COSFloat(it)) }
        })
        val c = TapeTile.patternColor(base)
        PDPatternContentStream(pat).use { cs ->
            cs.setNonStrokingColor(Color.red(c) / 255f, Color.green(c) / 255f, Color.blue(c) / 255f)
            // 칸 좌표는 아래로 갈수록 y가 커지므로 위아래를 뒤집어 넣는다
            for (sh in TapeTile.shapes(p)) when (sh) {
                is TapeTile.Box -> cs.addRect(sh.l, s - sh.b, sh.r - sh.l, sh.b - sh.t)
                is TapeTile.Dot -> {
                    // 원: 베지어 네 개
                    val r = sh.r
                    val k = 0.5523f * r
                    val cx = sh.cx
                    val cy = s - sh.cy
                    cs.moveTo(cx + r, cy)
                    cs.curveTo(cx + r, cy + k, cx + k, cy + r, cx, cy + r)
                    cs.curveTo(cx - k, cy + r, cx - r, cy + k, cx - r, cy)
                    cs.curveTo(cx - r, cy - k, cx - k, cy - r, cx, cy - r)
                    cs.curveTo(cx + k, cy - r, cx + r, cy - k, cx + r, cy)
                    cs.closePath()
                }
                is TapeTile.Poly -> {
                    cs.moveTo(sh.pts[0], s - sh.pts[1])
                    for (i in 1 until sh.pts.size / 2) cs.lineTo(sh.pts[i * 2], s - sh.pts[i * 2 + 1])
                    cs.closePath()
                }
            }
            cs.fill()
        }
        return pat
    }

    /** 이 앱이 넣은 그림 주석에서 그림을 꺼낸다. JPEG면 원래 바이트도 같이 (다시 저장할 때 화질 그대로) */
    private fun readImage(a: PDAnnotation): InkImage? = try {
        val res = a.normalAppearanceStream?.resources
        val x = res?.xObjectNames?.firstNotNullOfOrNull { res.getXObject(it) as? PDImageXObject }
        if (x == null) null
        else {
            val bytes = if (x.suffix == "jpg") x.cosObject.createRawInputStream().use { it.readBytes() } else null
            InkImage(x.image, bytes)
        }
    } catch (e: Exception) {
        null
    }

    /** 화면 좌표(보이는 페이지의 왼쪽 위 원점) → PDF 사용자 좌표 (페이지 회전 고려) */
    private fun toUser(x: Float, y: Float, b: PDRectangle, rot: Int, ux: FloatArray, uy: FloatArray, i: Int) {
        val x0 = b.lowerLeftX
        val y0 = b.lowerLeftY
        val x1 = b.upperRightX
        val y1 = b.upperRightY
        when (rot) {
            90 -> { ux[i] = x0 + y; uy[i] = y0 + x }
            180 -> { ux[i] = x1 - x; uy[i] = y0 + y }
            270 -> { ux[i] = x1 - y; uy[i] = y1 - x }
            else -> { ux[i] = x0 + x; uy[i] = y1 - y }
        }
    }

    // 형식: 1|P|ff000000|1.2|x,y,p;x,y,p;...  (도구: P 펜, H 형광펜, D 점선 펜, I 그림, T 글, K 테이프 — 그림·글은 네 모서리)
    // 글은 굵기 자리에 글자 크기, 끝에 |글(UTF-8 Base64)|줄 바꾸는 폭|서식(JSON, UTF-8 Base64)을 붙인다
    // 테이프는 끝에 |R(네모) 또는 P(펜)|무늬 이름, 지우개로 뚫은 구멍이 있으면 |x,y,r;x,y,r;... 을 붙인다
    private fun encode(s: Stroke): String {
        val sb = StringBuilder(s.count * 16 + 32)
        val kind = when {
            s.image != null -> 'I'
            s.text != null -> 'T'
            s.tape != null -> 'K'
            s.tool == Tool.HIGHLIGHTER -> 'H'
            s.dashed -> 'D'
            else -> 'P'
        }
        sb.append("1|").append(kind).append('|')
        sb.append(Integer.toHexString(s.color)).append('|').append(r2(s.text?.size ?: s.width)).append('|')
        for (i in 0 until s.count) {
            if (i > 0) sb.append(';')
            sb.append(r2(s.x(i))).append(',').append(r2(s.y(i))).append(',').append(r2(s.p(i)))
        }
        s.text?.let {
            sb.append('|').append(Base64.encodeToString(it.text.toByteArray(), Base64.NO_WRAP))
            val rich = !it.rich.isPlain
            if (it.wrap < InkText.NO_WRAP || rich) sb.append('|').append(r2(it.wrap))
            if (rich) sb.append('|').append(Base64.encodeToString(it.rich.toJson().toByteArray(), Base64.NO_WRAP))
        }
        s.tape?.let {
            sb.append('|').append(if (it.rect) 'R' else 'P').append('|').append(it.pattern.name)
            if (s.holes.isNotEmpty()) {
                sb.append('|')
                for (i in s.holes.indices step 3) {
                    if (i > 0) sb.append(';')
                    sb.append(r2(s.holes[i])).append(',').append(r2(s.holes[i + 1])).append(',').append(r2(s.holes[i + 2]))
                }
            }
        }
        return sb.toString()
    }

    private fun decode(str: String): Stroke? = try {
        val parts = str.split('|')
        if (parts.size < 5 || parts[0] != "1") null
        else {
            val tool = when (parts[1]) {
                "H" -> Tool.HIGHLIGHTER
                "K" -> Tool.TAPE
                else -> Tool.PEN
            }
            val color = parts[2].toLong(16).toInt()
            val isText = parts[1] == "T"
            val s = Stroke(tool, color, if (isText) 0f else parts[3].toFloat(), dashed = parts[1] == "D")
            for (pt in parts[4].split(';')) {
                val v = pt.split(',')
                if (v.size == 3) s.add(v[0].toFloat(), v[1].toFloat(), v[2].toFloat())
            }
            if (tool == Tool.TAPE) {
                s.tape = TapeStyle(TapePattern.of(parts.getOrNull(6)), parts.getOrNull(5) == "R")
                parts.getOrNull(7)?.split(';')?.forEach { h ->
                    val v = h.split(',')
                    if (v.size == 3) s.withHole(v[0].toFloat(), v[1].toFloat(), v[2].toFloat())?.let { s.copyHolesFrom(it) }
                }
            }
            if (isText) {
                val txt = String(Base64.decode(parts[5], Base64.NO_WRAP))
                val rich = parts.getOrNull(7)?.let { RichDoc.fromJson(txt, String(Base64.decode(it, Base64.NO_WRAP))) }
                s.text = InkText(rich ?: RichDoc.plain(txt), parts[3].toFloat(), parts.getOrNull(6)?.toFloatOrNull() ?: InkText.NO_WRAP)
            }
            if (s.count > 0 && (!isText || s.count >= 4)) s else null
        }
    } catch (e: Exception) {
        null
    }

    private fun r2(v: Float): String = ((v * 100f).roundToInt() / 100f).toString()
}
