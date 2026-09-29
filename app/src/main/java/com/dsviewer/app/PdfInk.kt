package com.dsviewer.app

import android.graphics.Color
import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSFloat
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.PDResources
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.blend.BlendMode
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDColor
import com.tom_roush.pdfbox.pdmodel.graphics.color.PDDeviceRGB
import com.tom_roush.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceDictionary
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAppearanceStream
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
                    if (s != null) decode(s)?.let { strokes.add(it) } else keep.add(a)
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
        PDDocument.load(src).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            for ((i, page) in doc.pages.withIndex()) {
                val list: MutableList<PDAnnotation> =
                    page.annotations.filterTo(ArrayList()) { !it.cosObject.containsKey(KEY_NAME) }
                if (i < pages.size) {
                    for (s in pages[i]) if (s.count > 0) list.add(makeAnnotation(doc, page, s))
                }
                page.annotations = list
            }
            doc.save(out)
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
        dict.setItem(COSName.BS, bs)
        if (s.tool == Tool.HIGHLIGHTER) dict.setFloat(COSName.CA, HL_ALPHA)
        dict.setString(KEY_NAME, encode(s))

        val apd = PDAppearanceDictionary()
        apd.setNormalAppearance(ap)
        annot.appearance = apd
        return annot
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

    // 형식: 1|P|ff000000|1.2|x,y,p;x,y,p;...
    private fun encode(s: Stroke): String {
        val sb = StringBuilder(s.count * 16 + 32)
        sb.append("1|").append(if (s.tool == Tool.HIGHLIGHTER) 'H' else 'P').append('|')
        sb.append(Integer.toHexString(s.color)).append('|').append(r2(s.width)).append('|')
        for (i in 0 until s.count) {
            if (i > 0) sb.append(';')
            sb.append(r2(s.x(i))).append(',').append(r2(s.y(i))).append(',').append(r2(s.p(i)))
        }
        return sb.toString()
    }

    private fun decode(str: String): Stroke? = try {
        val parts = str.split('|')
        if (parts.size < 5 || parts[0] != "1") null
        else {
            val tool = if (parts[1] == "H") Tool.HIGHLIGHTER else Tool.PEN
            val color = parts[2].toLong(16).toInt()
            val s = Stroke(tool, color, parts[3].toFloat())
            for (pt in parts[4].split(';')) {
                val v = pt.split(',')
                if (v.size == 3) s.add(v[0].toFloat(), v[1].toFloat(), v[2].toFloat())
            }
            if (s.count > 0) s else null
        }
    } catch (e: Exception) {
        null
    }

    private fun r2(v: Float): String = ((v * 100f).roundToInt() / 100f).toString()
}
