package com.dsviewer.app

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File

/** PDF 목차(워드의 탐색 창) 항목 하나: [level] 0이 가장 위, [page]는 0부터, [y]는 그 쪽 안 높이 (쪽 맨 위가 0, 모르면 null) */
class PdfOutlineItem(val level: Int, val title: String, val page: Int, val y: Float?)

/** PDF에서 목차(책갈피 트리)를 꺼낸다 (PDFBox). 워드 문서를 바꾼 PDF의 제목들도 여기로 나온다 */
object PdfOutline {

    /** [file]의 목차를 차례대로 (트리를 펼친 순서). 목차가 없으면 빈 목록 */
    fun extract(file: File): List<PdfOutlineItem> = PDDocument.load(file).use { doc ->
        if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        val root = doc.documentCatalog.documentOutline ?: return emptyList()
        val out = ArrayList<PdfOutlineItem>()
        fun walk(node: PDOutlineNode, level: Int) {
            var item: PDOutlineItem? = node.firstChild
            var guard = 0
            while (item != null && guard++ < 5000 && out.size < 5000) {
                val title = item.title?.trim().orEmpty()
                val link = try {
                    val dest = item.destination ?: (item.action as? PDActionGoTo)?.destination
                    PdfLinks.goTo(doc, RectF(), dest)
                } catch (e: Exception) {
                    null
                }
                // 갈 곳을 모르는 항목도 자식은 살린다
                if (title.isNotEmpty() && link != null) out.add(PdfOutlineItem(level, title, link.page, link.y))
                if (item.hasChildren() && level < 8) walk(item, if (title.isNotEmpty() && link != null) level + 1 else level)
                item = item.nextSibling
            }
        }
        walk(root, 0)
        out
    }
}
