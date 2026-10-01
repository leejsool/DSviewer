package com.dsviewer.app

import com.tom_roush.pdfbox.multipdf.PDFMergerUtility
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import java.io.File

/** 빈 쪽의 바탕 */
enum class Paper(val label: String) { PLAIN("흰 바탕"), GRID("모눈"), LINED("줄") }

/**
 * 쪽 다루기: 필기용 빈 쪽 만들기 · 문서에 끼워 넣기 · 다른 PDF의 쪽 넣기 · 쪽 지우기.
 * 모눈·줄은 PDF 내용으로 그려 넣으므로 저장한 파일을 다른 앱에서 열어도 그대로 보인다.
 */
object PdfPages {
    /** A4 (포인트) */
    const val A4_SHORT = 595.28f
    const val A4_LONG = 841.89f

    private const val MM = 72f / 25.4f
    /** 모눈 한 칸 5mm, 다섯 칸마다 조금 진한 선 (좌표평면 그리기 좋게) */
    private const val GRID_STEP = 5 * MM
    private const val LINE_STEP = 8 * MM
    private const val LINE_TOP = 25 * MM

    /** 빈 쪽 [pages]장짜리 새 문서 */
    fun create(out: File, paper: Paper, w: Float, h: Float, pages: Int = 1) {
        PDDocument().use { doc ->
            repeat(pages) { doc.addPage(blankPage(doc, paper, w, h)) }
            doc.save(out)
        }
    }

    /** [src]의 [index]번째 자리에 빈 쪽을 넣어 [out]에 저장 (index = 쪽 수면 맨 뒤) */
    fun insert(src: File, out: File, index: Int, paper: Paper, w: Float, h: Float) {
        PDDocument.load(src).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val page = blankPage(doc, paper, w, h)
            val pages = doc.pages
            if (index >= pages.count) pages.add(page) else pages.insertBefore(page, pages.get(index))
            doc.save(out)
        }
    }

    /** [src]의 [index]번째 자리에 [add] PDF의 모든 쪽을 넣어 [out]에 저장 (index = 쪽 수면 맨 뒤) */
    fun insertPdf(src: File, out: File, index: Int, add: File) {
        PDDocument.load(src).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            PDDocument.load(add).use { other ->
                if (other.isEncrypted) other.isAllSecurityToBeRemoved = true
                val before = doc.numberOfPages
                // 맨 뒤에 붙인 다음, 넣을 자리 앞으로 차례대로 옮긴다
                PDFMergerUtility().appendDocument(doc, other)
                if (index < before) {
                    val added = (before until doc.numberOfPages).map { doc.getPage(it) }
                    val anchor = doc.getPage(index)
                    for (p in added) {
                        doc.pages.remove(p)
                        doc.pages.insertBefore(p, anchor)
                    }
                }
                doc.save(out)
            }
        }
    }

    /** [file]의 쪽 수 (암호가 걸려 열 수 없으면 예외) */
    fun pageCount(file: File): Int = PDDocument.load(file).use { it.numberOfPages }

    /** [src]에서 [from]~[to]번째 쪽(0부터, 끝 포함)을 빼고 [out]에 저장 */
    fun remove(src: File, out: File, from: Int, to: Int = from) {
        PDDocument.load(src).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            for (i in to downTo from) doc.removePage(i)
            doc.save(out)
        }
    }

    /**
     * 쪽을 [order] 차례로 다시 늘어놓아 [out]에 저장: 새 문서의 k번째 쪽 = 원래 order[k]번째 쪽 (0부터).
     * order에 없는 쪽은 빠진다 (쪽 순서 바꾸기 · 여러 쪽 지우기 · 고른 쪽만 저장)
     */
    fun reorder(src: File, out: File, order: List<Int>) {
        PDDocument.load(src).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            val all = (0 until doc.numberOfPages).map { doc.getPage(it) }
            for (p in all) doc.pages.remove(p)
            for (i in order) doc.pages.add(all[i])
            doc.save(out)
        }
    }

    /** [pages]번째 쪽들을 시계 방향으로 [deg]도(90의 배수) 돌려 [out]에 저장 */
    fun rotate(src: File, out: File, pages: Collection<Int>, deg: Int = 90) {
        PDDocument.load(src).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            for (i in pages) {
                val p = doc.getPage(i)
                p.rotation = ((p.rotation + deg) % 360 + 360) % 360
            }
            doc.save(out)
        }
    }

    /**
     * [pages]번째 쪽들을 화면에서 보이는 대로 좌우([horizontal]) 또는 상하로 뒤집어 [out]에 저장.
     * 쪽 내용 앞뒤를 'q 거울 행렬 cm … Q'로 감싼다. 쪽이 90°·270° 돌아가 있으면 PDF 안에서는 반대 축으로 뒤집는다
     */
    fun flip(src: File, out: File, pages: Collection<Int>, horizontal: Boolean) {
        PDDocument.load(src).use { doc ->
            if (doc.isEncrypted) doc.isAllSecurityToBeRemoved = true
            for (i in pages) {
                val p = doc.getPage(i)
                val box = p.cropBox
                val rot = ((p.rotation % 360) + 360) % 360
                val flipX = horizontal == (rot == 0 || rot == 180)
                val m = if (flipX) com.tom_roush.pdfbox.util.Matrix(-1f, 0f, 0f, 1f, box.lowerLeftX + box.upperRightX, 0f)
                    else com.tom_roush.pdfbox.util.Matrix(1f, 0f, 0f, -1f, 0f, box.lowerLeftY + box.upperRightY)
                PDPageContentStream(doc, p, PDPageContentStream.AppendMode.PREPEND, true, false).use { cs ->
                    cs.saveGraphicsState()
                    cs.transform(m)
                }
                PDPageContentStream(doc, p, PDPageContentStream.AppendMode.APPEND, true, false).use { cs ->
                    cs.restoreGraphicsState()
                }
            }
            doc.save(out)
        }
    }

    /**
     * [src]의 [index]번째 쪽이 이 앱이 만든 모눈·줄 쪽이면 그 바탕, 아니면 흰 바탕.
     * 필기는 주석으로 저장되어 쪽 내용은 그대로이므로, 같은 크기로 새로 그린 바탕과 내용이 똑같은지 본다
     */
    fun paperOf(src: File, index: Int): Paper {
        PDDocument.load(src).use { doc ->
            if (index !in 0 until doc.numberOfPages) return Paper.PLAIN
            val page = doc.getPage(index)
            val streams = page.contentStreams.asSequence().map { s -> s.createInputStream().use { it.readBytes() } }.toList()
            if (streams.isEmpty()) return Paper.PLAIN
            val box = page.mediaBox
            for (paper in listOf(Paper.GRID, Paper.LINED)) {
                val ref = PDDocument().use { tmp ->
                    blankPage(tmp, paper, box.width, box.height).contentStreams.next().createInputStream().use { it.readBytes() }
                }
                if (streams.any { it.contentEquals(ref) }) return paper
            }
            return Paper.PLAIN
        }
    }

    private fun blankPage(doc: PDDocument, paper: Paper, w: Float, h: Float): PDPage {
        val page = PDPage(PDRectangle(w, h))
        if (paper == Paper.PLAIN) return page
        PDPageContentStream(doc, page).use { cs ->
            when (paper) {
                Paper.GRID -> drawGrid(cs, w, h)
                Paper.LINED -> drawLines(cs, w, h)
                Paper.PLAIN -> {}
            }
        }
        return page
    }

    private fun drawGrid(cs: PDPageContentStream, w: Float, h: Float) {
        // 굵은 선이 쪽 가운데를 지나도록 맞춘다 (가운데에 좌표축을 그리기 쉽게)
        val cx = w / 2f
        val cy = h / 2f
        val nx = (cx / GRID_STEP).toInt()
        val ny = (cy / GRID_STEP).toInt()
        for (major in listOf(false, true)) {
            if (major) setColor(cs, 0xB8C6D6, 0.6f) else setColor(cs, 0xDCE3EC, 0.35f)
            for (k in -nx..nx) {
                if ((k % 5 == 0) != major) continue
                val x = cx + k * GRID_STEP
                cs.moveTo(x, 0f); cs.lineTo(x, h)
            }
            for (k in -ny..ny) {
                if ((k % 5 == 0) != major) continue
                val y = cy + k * GRID_STEP
                cs.moveTo(0f, y); cs.lineTo(w, y)
            }
            cs.stroke()
        }
    }

    private fun drawLines(cs: PDPageContentStream, w: Float, h: Float) {
        setColor(cs, 0xC5D3E3, 0.5f)
        val margin = 12 * MM
        var y = h - LINE_TOP  // PDF 좌표는 아래가 0
        while (y > margin) {
            cs.moveTo(margin, y); cs.lineTo(w - margin, y)
            y -= LINE_STEP
        }
        cs.stroke()
    }

    private fun setColor(cs: PDPageContentStream, rgb: Int, width: Float) {
        cs.setStrokingColor((rgb shr 16 and 0xFF) / 255f, (rgb shr 8 and 0xFF) / 255f, (rgb and 0xFF) / 255f)
        cs.setLineWidth(width)
    }
}
