package com.dsviewer.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 끌어 올려 붙이는 빈 쪽이 마지막 쪽의 바탕(흰 바탕·모눈·줄)을 알아내는지.
 * 필기·북마크를 저장한 뒤에도, 가로 쪽에서도.
 * 실행: adb shell am instrument -w -e class com.dsviewer.app.PaperDetectTest com.dsviewer.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PaperDetectTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "paperTest").apply { deleteRecursively(); mkdirs() }

    @Test
    fun detectPaper() {
        PDFBoxResourceLoader.init(ctx)
        for (paper in Paper.entries) for ((w, h) in listOf(PdfPages.A4_SHORT to PdfPages.A4_LONG, PdfPages.A4_LONG to PdfPages.A4_SHORT)) {
            val src = File(dir, "${paper}_$w.pdf")
            PdfPages.create(src, paper, w, h, pages = 2)
            assertEquals(paper, PdfPages.paperOf(src, 1))

            // 필기를 저장한 파일 (필기는 주석으로 들어가 쪽 내용은 그대로)
            val inked = File(dir, "${paper}_${w}_ink.pdf")
            val st = Stroke(Tool.PEN, 0xFF000000.toInt(), 2f).apply { add(10f, 10f, 0.5f); add(100f, 120f, 0.5f) }
            PdfInk.save(src, inked, listOf(emptyList(), listOf(st)), setOf(1))
            assertEquals(paper, PdfPages.paperOf(inked, 1))

            // 맨 뒤에 붙인 쪽도 같은 바탕
            val out = File(dir, "${paper}_${w}_add.pdf")
            PdfPages.insert(inked, out, 2, PdfPages.paperOf(inked, 1), w, h)
            PDDocument.load(out).use { assertEquals(3, it.numberOfPages) }
            assertEquals(paper, PdfPages.paperOf(out, 2))
        }
    }
}
