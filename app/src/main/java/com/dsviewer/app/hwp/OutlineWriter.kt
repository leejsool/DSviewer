package com.dsviewer.app.hwp

import android.util.Log
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineNode
import java.io.File

/** 제목(개요) 문단 하나: [level] 0이 가장 위, [page]는 0부터, [y]는 쪽 아래에서 잰 높이(pt, PDF 좌표) */
internal class OutlineEntry(val level: Int, val title: String, val page: Int, val y: Float)

/** HRenderer가 그린 PDF에 목차(워드의 탐색 창)를 단다 (PDFBox) */
internal object OutlineWriter {

    /** [file]에 [entries]의 목차를 단다. 실패해도 변환한 PDF는 그대로 둔다 (목차만 없다) */
    fun add(file: File, entries: List<OutlineEntry>) {
        if (entries.isEmpty()) return
        val tmp = File(file.path + ".out")
        try {
            PDDocument.load(file).use { doc ->
                val root = PDDocumentOutline()
                doc.documentCatalog.documentOutline = root
                // 수준이 건너뛰어도(1 다음 3) 가장 가까운 윗 제목 밑에 넣는다
                val stack = ArrayList<Pair<Int, PDOutlineNode>>()
                stack.add(-1 to root)
                for (e in entries) {
                    if (e.page !in 0 until doc.numberOfPages) continue
                    while (stack.size > 1 && stack.last().first >= e.level) stack.removeAt(stack.size - 1)
                    val item = PDOutlineItem()
                    item.title = e.title
                    item.destination = PDPageXYZDestination().apply {
                        page = doc.getPage(e.page)
                        left = 0
                        top = e.y.toInt()
                    }
                    stack.last().second.addLast(item)
                    stack.add(e.level to item)
                }
                // 제목이 많지 않으면 펼쳐 둔다
                for ((_, node) in stack) if (node is PDOutlineItem) node.openNode()
                root.openNode()
                doc.save(tmp)
            }
            tmp.copyTo(file, overwrite = true)
        } catch (e: Throwable) {
            Log.w("OutlineWriter", "목차를 달지 못했습니다", e)
        } finally {
            tmp.delete()
        }
    }
}
