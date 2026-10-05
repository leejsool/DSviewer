package com.dsviewer.app.hwp

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotation
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDBorderStyleDictionary
import java.io.File

/**
 * 변환한 PDF에서 하이퍼링크 글자 자리. [page]는 0부터, 자리는 쪽 왼쪽 위가 원점인 pt 좌표이고
 * [pageH]는 그 쪽의 높이 (PDF 좌표는 왼쪽 아래가 원점이라 뒤집는 데 쓴다)
 */
internal class LinkBox(val page: Int, val pageH: Float, val l: Float, val t: Float, val r: Float, val b: Float, val url: String)

/** HRenderer가 그린 PDF에 하이퍼링크 주석을 달아, 눌러서 열 수 있는 링크로 만든다 (PDFBox) */
internal object LinkWriter {

    /** PDF 링크 주석의 영역 (왼쪽 아래 원점): x, y, 폭, 높이 */
    fun pdfRect(box: LinkBox): FloatArray =
        floatArrayOf(box.l, box.pageH - box.b, box.r - box.l, box.b - box.t)

    /** 같은 줄·같은 주소에서 맞닿거나 겹친 자리는 하나로 합친다 (글자 조각마다 따로 올 때) */
    fun merge(boxes: List<LinkBox>): List<LinkBox> {
        val out = ArrayList<LinkBox>()
        for (b in boxes) {
            val last = out.lastOrNull()
            if (last != null && last.page == b.page && last.url == b.url &&
                kotlin.math.abs(last.t - b.t) < 0.5f && kotlin.math.abs(last.b - b.b) < 0.5f && b.l <= last.r + 0.5f && b.l >= last.l
            ) {
                out[out.size - 1] = LinkBox(last.page, last.pageH, last.l, last.t, maxOf(last.r, b.r), last.b, last.url)
            } else out.add(b)
        }
        return out
    }

    /** [file]에 [boxes]의 링크를 단다. 실패해도 변환한 PDF는 그대로 둔다 (링크만 없다) */
    fun add(file: File, boxes: List<LinkBox>) {
        if (boxes.isEmpty()) return
        val tmp = File(file.path + ".lnk")
        try {
            PDDocument.load(file).use { doc ->
                val byPage = merge(boxes).groupBy { it.page }
                for ((idx, list) in byPage) {
                    if (idx !in 0 until doc.numberOfPages) continue
                    val page = doc.getPage(idx)
                    val annots = ArrayList<PDAnnotation>(page.annotations)
                    for (box in list) {
                        val r = pdfRect(box)
                        if (r[2] <= 0f || r[3] <= 0f) continue
                        val link = PDAnnotationLink()
                        link.rectangle = PDRectangle(r[0], r[1], r[2], r[3])
                        // 눌러서 열리되 테두리는 그리지 않는다
                        link.borderStyle = PDBorderStyleDictionary().apply { width = 0f }
                        link.action = PDActionURI().apply { uri = box.url }
                        annots.add(link)
                    }
                    page.annotations = annots
                }
                doc.save(tmp)
            }
            tmp.copyTo(file, overwrite = true)
        } catch (e: Throwable) {
            Log.w("LinkWriter", "링크를 달지 못했습니다", e)
        } finally {
            tmp.delete()
        }
    }
}
