package com.dsviewer.app

import com.tom_roush.pdfbox.cos.COSArray
import com.tom_roush.pdfbox.cos.COSBase
import com.tom_roush.pdfbox.cos.COSBoolean
import com.tom_roush.pdfbox.cos.COSDictionary
import com.tom_roush.pdfbox.cos.COSName
import com.tom_roush.pdfbox.cos.COSNull
import com.tom_roush.pdfbox.cos.COSNumber
import com.tom_roush.pdfbox.cos.COSObject
import com.tom_roush.pdfbox.cos.COSStream
import com.tom_roush.pdfbox.cos.COSString
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 필기 저장 시험에 쓰는 정해진 획들과, 저장된 PDF의 주석을 글로 펼쳐 비교하는 도구 */
object InkSaveFixture {
    /** 매개변수 곡선을 [n]개 점으로 (필압은 가운데가 세고 양 끝이 약하다) */
    private fun curve(st: Stroke, n: Int, f: (Float) -> Pair<Float, Float>): Stroke {
        for (k in 0 until n) {
            val t = if (n == 1) 0f else k / (n - 1).toFloat()
            val (x, y) = f(t)
            st.add(x, y, 0.2f + 0.7f * sin(PI.toFloat() * t))
        }
        return st
    }

    /** 한 쪽에 들어갈 여러 가지 획: 펜 종류마다, 점선, 형광펜, 점 하나, 채우기 하나 */
    fun pageStrokes(seed: Int): MutableList<Stroke> {
        val out = ArrayList<Stroke>()
        val dy = seed * 7f
        val colors = intArrayOf(0xFF000000.toInt(), 0xFF1E88E5.toInt(), 0xFFE53935.toInt(), 0xFF2E7D32.toInt())
        for ((i, pen) in PenStyle.entries.withIndex()) {
            val top = 40f + i * 60f + dy
            out.add(curve(Stroke(Tool.PEN, colors[i % colors.size], 1f + i * 0.4f, pen = pen), 60 + i * 13) { t ->
                val a = t * 3 * 2 * PI.toFloat()
                (40f + t * 300f + 10f * sin(a)) to (top + 20f - 15f * cos(a))
            })
        }
        out.add(curve(Stroke(Tool.PEN, 0xFF6A1B9A.toInt(), 1.5f, dashed = true), 30) { t -> (60f + t * 250f) to (420f + dy + 30f * t * t) })
        out.add(curve(Stroke(Tool.HIGHLIGHTER, 0xFFFFEB3B.toInt(), 12f), 2) { t -> (50f + t * 300f) to (470f + dy) })
        out.add(curve(Stroke(Tool.PEN, 0xFF000000.toInt(), 2f), 1) { (400f + seed) to (500f + dy) })
        out.add(Stroke(Tool.FILL, 0xFF90CAF9.toInt(), 0f).apply {
            fill = FillStyle(FillPattern.SOLID)
            val c = floatArrayOf(380f, 60f + dy, 520f, 60f + dy, 520f, 180f + dy, 380f, 180f + dy)
            for (k in 0 until 4) add(c[k * 2], c[k * 2 + 1], if (k == 0) 1f else 0f)
        })
        return out
    }

    /** 쪽 상자·회전이 서로 다른 세 쪽짜리 바탕 PDF ([shift]만큼 자르기 상자를 옮긴다) */
    fun basePdf(file: File, shift: Float = 0f) {
        PDDocument().use { d ->
            d.addPage(PDPage(PDRectangle.A4))
            d.addPage(PDPage(PDRectangle.LETTER).apply { rotation = 90 })
            d.addPage(PDPage(PDRectangle(0f, 0f, 700f, 900f)).apply {
                cropBox = PDRectangle(30f + shift, 40f + shift, 600f, 800f)
                rotation = 270
            })
            d.save(file)
        }
    }

    /**
     * 저장된 PDF의 쪽마다 주석을 글로 펼친다 (사전 키는 이름 차례로, 스트림은 풀어서, 쪽 참조는 쪽 번호로).
     * 고친 날짜(/M)는 늘 빼고, [withNames]가 false면 주석 이름(/NM)도 뺀다
     */
    fun dump(file: File, withNames: Boolean = true): String {
        val sb = StringBuilder()
        PDDocument.load(file).use { doc ->
            val pageIndex = IdentityHashMap<COSBase, Int>()
            for ((i, p) in doc.pages.withIndex()) pageIndex[p.cosObject] = i
            for ((i, p) in doc.pages.withIndex()) {
                sb.append("page ").append(i).append('\n')
                val annots = p.cosObject.getDictionaryObject(COSName.ANNOTS) as? COSArray ?: continue
                for (a in annots) {
                    write(sb, a, pageIndex, withNames, IdentityHashMap(), 1)
                    sb.append('\n')
                }
            }
        }
        return sb.toString()
    }

    /** 펜 획(/Ink) 주석들의 이름(/NM), 쪽 차례대로 */
    fun inkNames(file: File): List<String> = PDDocument.load(file).use { doc ->
        doc.pages.flatMap { p ->
            p.annotations.filter { it.subtype == "Ink" }.map { it.annotationName }
        }
    }

    private fun write(sb: StringBuilder, b0: COSBase?, pages: IdentityHashMap<COSBase, Int>, names: Boolean, seen: IdentityHashMap<COSBase, Boolean>, depth: Int) {
        val b = if (b0 is COSObject) b0.`object` else b0
        when (b) {
            null, is COSNull -> sb.append("null")
            is COSBoolean -> sb.append(b.value)
            is COSNumber -> sb.append(b.floatValue())
            is COSName -> sb.append('/').append(b.name)
            is COSString -> sb.append('(').append(b.string).append(')')
            is COSArray -> {
                sb.append('[')
                for (e in b) { write(sb, e, pages, names, seen, depth + 1); sb.append(' ') }
                sb.append(']')
            }
            is COSDictionary -> {
                pages[b]?.let { sb.append("page#").append(it); return }
                if (seen.put(b, true) != null || depth > 12) { sb.append("<seen>"); return }
                sb.append("<<")
                for (k in b.keySet().sortedBy { it.name }) {
                    if (k == COSName.M || (!names && k == COSName.NM)) continue
                    if (b is COSStream && k == COSName.LENGTH) continue
                    sb.append(" /").append(k.name).append(' ')
                    write(sb, b.getItem(k), pages, names, seen, depth + 1)
                }
                sb.append(" >>")
                if (b is COSStream) b.createInputStream().use { sb.append(" stream{").append(String(it.readBytes(), Charsets.ISO_8859_1)).append('}') }
            }
            else -> sb.append(b.toString())
        }
    }
}
