package com.dsviewer.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dsviewer.app.conv.DocConvert
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 기기의 /sdcard/Download/convtest/memo.docx (제목 스타일이 있는 워드 문서)를 PDF로 바꾸고
 * 제목이 PDF 목차로 나오는지, 쪽 수가 맞는지 본다. 결과는 앱 캐시 outlineTest.txt
 */
@RunWith(AndroidJUnit4::class)
class OutlineTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun docxHeadingsBecomeOutline() {
        PDFBoxResourceLoader.init(ctx)
        val f = File("/sdcard/Download/convtest/memo.docx")
        assertTrue("no memo.docx", f.exists())
        val pdf = DocConvert.toPdf(ctx, f, FileUtil.detect(f.name, null, f), f.name)
        val items = PdfOutline.extract(pdf)
        val sb = StringBuilder("pages=${PdfPages.pageCount(pdf)}\n")
        for (i in items) sb.append("L${i.level} p${i.page + 1} y=${i.y} ${i.title}\n")
        File(ctx.cacheDir, "outlineTest.txt").writeText(sb.toString())
        assertEquals(11, items.size)
        assertEquals(0, items[0].level)
        assertEquals(1, items[2].level)
    }
}
