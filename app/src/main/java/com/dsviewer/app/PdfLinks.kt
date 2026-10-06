package com.dsviewer.app

import android.graphics.RectF
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionGoTo
import com.tom_roush.pdfbox.pdmodel.interactive.action.PDActionURI
import com.tom_roush.pdfbox.pdmodel.interactive.annotation.PDAnnotationLink
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDNamedDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageFitWidthDestination
import com.tom_roush.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.cancellation.CancellationException

/**
 * PDF 쪽의 링크 하나. [rect]는 쪽 좌표 (왼쪽 위가 원점).
 * 웹 주소면 [uri], 같은 문서의 다른 쪽이면 [page] (그 쪽에서 갈 높이를 알면 [y])
 */
class PdfLink(val rect: RectF, val uri: String?, val page: Int, val y: Float?)

/** PDF에서 링크를 꺼낸다 (PDFBox) */
object PdfLinks {

    /** [file]의 쪽마다 링크 */
    fun extract(file: File): List<List<PdfLink>> = PDDocument.load(file).use { doc ->
        if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
        val pts = FloatArray(4)
        doc.pages.map { page ->
            val crop = page.cropBox
            val rot = ((page.rotation % 360) + 360) % 360
            page.annotations.mapNotNull { a ->
                val link = a as? PDAnnotationLink ?: return@mapNotNull null
                val r = link.rectangle ?: return@mapNotNull null
                PdfText.toPage(crop, rot, r.lowerLeftX, r.lowerLeftY, pts, 0)
                PdfText.toPage(crop, rot, r.upperRightX, r.upperRightY, pts, 2)
                val rect = RectF(minOf(pts[0], pts[2]), minOf(pts[1], pts[3]), maxOf(pts[0], pts[2]), maxOf(pts[1], pts[3]))
                try {
                    when (val act = link.action) {
                        is PDActionURI -> act.uri?.trim()?.takeIf { it.isNotEmpty() }?.let { PdfLink(rect, it, -1, null) }
                        is PDActionGoTo -> goTo(doc, rect, act.destination)
                        null -> goTo(doc, rect, link.destination)
                        else -> null
                    }
                } catch (e: Exception) {
                    null
                }
            }
        }
    }

    /** 같은 문서 안의 [dest]로 가는 링크 (못 찾으면 null) */
    internal fun goTo(doc: PDDocument, rect: RectF, dest: PDDestination?): PdfLink? {
        val pd = when (dest) {
            is PDPageDestination -> dest
            is PDNamedDestination -> doc.documentCatalog.findNamedDestinationPage(dest)
            else -> null
        } ?: return null
        val idx = pd.retrievePageNumber()
        if (idx !in 0 until doc.numberOfPages) return null
        val top = when (pd) {
            is PDPageXYZDestination -> pd.top
            is PDPageFitWidthDestination -> pd.top
            else -> -1
        }
        var y: Float? = null
        if (top >= 0) {
            val target = doc.getPage(idx)
            val pts = FloatArray(2)
            val rot = ((target.rotation % 360) + 360) % 360
            PdfText.toPage(target.cropBox, rot, target.cropBox.lowerLeftX, top.toFloat(), pts, 0)
            // 돌린 쪽에서는 높이가 가로로 가므로 쪽 맨 위로
            y = if (rot == 0 || rot == 180) pts[1].coerceAtLeast(0f) else null
        }
        return PdfLink(rect, null, idx, y)
    }
}

/**
 * 한 문서의 링크. 처음 쓸 때 뒤에서 꺼낸다 (쪽을 넣고 빼면 화면용 PDF가 바뀌어 새로)
 */
class DocLinks(val file: File, scope: CoroutineScope) {
    private var pages: List<List<PdfLink>>? = null
    /** 다 꺼내면 할 일 (꺼내는 동안 여러 번 누르면 마지막 것만) */
    private var waiting: ((DocLinks) -> Unit)? = null

    init {
        scope.launch {
            pages = try {
                withContext(Dispatchers.IO) { PdfLinks.extract(file) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                emptyList()
            }
            waiting?.invoke(this@DocLinks)
            waiting = null
        }
    }

    fun whenReady(f: (DocLinks) -> Unit) {
        if (pages != null) f(this) else waiting = f
    }

    /** [page]쪽 (x, y)에 있는 링크. 겹치면 가장 작은 것 */
    fun at(page: Int, x: Float, y: Float): PdfLink? {
        val slop = 2f
        return pages?.getOrNull(page)
            ?.filter { x >= it.rect.left - slop && x <= it.rect.right + slop && y >= it.rect.top - slop && y <= it.rect.bottom + slop }
            ?.minByOrNull { it.rect.width() * it.rect.height() }
    }
}
