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
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.PDImageXObject
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
    const val HL_ALPHA = 0.45f

    /** 이 앱이 저장한 필기가 들어 있는지 빠르게 확인 (PDFBox는 주석 사전을 압축하지 않고 쓴다) */
    fun containsInk(file: File): Boolean {
        val pattern = "/$KEY".toByteArray()
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

    /** 이 앱의 필기를 꺼내고, 필기를 뺀 PDF를 [clean]에 저장한다 (화면 렌더링용) */
    fun extract(src: File, clean: File): List<List<Stroke>> {
        val result = ArrayList<MutableList<Stroke>>()
        PDDocument.load(src).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            for (page in doc.pages) {
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

    /** 원본 [src]에 필기를 주석으로 넣어 [out]에 저장 */
    fun save(src: File, out: File, pages: List<List<Stroke>>) {
        // 글 외형을 가져온 임시 PDF들 (다 저장한 뒤에 닫는다)
        val temps = ArrayList<PDDocument>()
        try {
            PDDocument.load(src).use { doc ->
                if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
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
                                    s.text != null -> makeTextAnnotation(doc, page, s, temps)
                                    else -> makeAnnotation(doc, page, s)
                                }
                            )
                        }
                    }
                    page.annotations = list
                }
                doc.save(out)
            }
        } finally {
            temps.forEach { it.close() }
        }
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
     * 글 주석(Stamp). 글을 안드로이드 PDF 한 쪽(상자 크기)으로 그려 글꼴째 담고,
     * 그 쪽을 폼으로 가져와 네 모서리에 맞춰 외형에 넣는다 (다른 앱에서도 글자가 그대로, 벡터로 보인다)
     */
    private fun makeTextAnnotation(doc: PDDocument, page: PDPage, s: Stroke, temps: MutableList<PDDocument>): PDAnnotation {
        val t = s.text!!
        val box = page.cropBox
        val rot = ((page.rotation % 360) + 360) % 360
        val ux = FloatArray(4)
        val uy = FloatArray(4)
        for (i in 0 until 4) toUser(s.x(i), s.y(i), box, rot, ux, uy, i)
        val rect = PDRectangle(ux.min(), uy.min(), ux.max() - ux.min(), uy.max() - uy.min())

        val bytes = ByteArrayOutputStream().use { out ->
            val pd = PdfDocument()
            try {
                val pg = pd.startPage(PdfDocument.PageInfo.Builder(t.boxW, t.boxH, 1).create())
                t.draw(pg.canvas, s.color)
                pd.finishPage(pg)
                pd.writeTo(out)
            } finally {
                pd.close()
            }
            out.toByteArray()
        }
        val tmp = PDDocument.load(bytes)
        temps.add(tmp)
        val form = LayerUtility(doc).importPageAsForm(tmp, 0)

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

    // 형식: 1|P|ff000000|1.2|x,y,p;x,y,p;...  (도구: P 펜, H 형광펜, D 점선 펜, I 그림, T 글 — 그림·글은 네 모서리)
    // 글은 굵기 자리에 글자 크기, 끝에 |글(UTF-8 Base64)을 붙인다
    private fun encode(s: Stroke): String {
        val sb = StringBuilder(s.count * 16 + 32)
        val kind = when {
            s.image != null -> 'I'
            s.text != null -> 'T'
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
        s.text?.let { sb.append('|').append(Base64.encodeToString(it.text.toByteArray(), Base64.NO_WRAP)) }
        return sb.toString()
    }

    private fun decode(str: String): Stroke? = try {
        val parts = str.split('|')
        if (parts.size < 5 || parts[0] != "1") null
        else {
            val tool = if (parts[1] == "H") Tool.HIGHLIGHTER else Tool.PEN
            val color = parts[2].toLong(16).toInt()
            val isText = parts[1] == "T"
            val s = Stroke(tool, color, if (isText) 0f else parts[3].toFloat(), dashed = parts[1] == "D")
            for (pt in parts[4].split(';')) {
                val v = pt.split(',')
                if (v.size == 3) s.add(v[0].toFloat(), v[1].toFloat(), v[2].toFloat())
            }
            if (isText) s.text = InkText(String(Base64.decode(parts[5], Base64.NO_WRAP)), parts[3].toFloat())
            if (s.count > 0 && (!isText || s.count >= 4)) s else null
        }
    } catch (e: Exception) {
        null
    }

    private fun r2(v: Float): String = ((v * 100f).roundToInt() / 100f).toString()
}
