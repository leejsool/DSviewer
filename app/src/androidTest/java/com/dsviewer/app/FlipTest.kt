package com.dsviewer.app

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 쪽 좌우·상하 반전: 왼쪽 위에 그린 검은 네모가 뒤집은 뒤 오른쪽 위(좌우)·왼쪽 아래(상하)로 가는지
 * (90° 돌린 쪽도), 글 상자는 자리만 옮기고 글자가 뒤집히지 않게 모서리 차례가 맞는지.
 * 실행: adb shell am instrument -w -e class com.dsviewer.app.FlipTest com.dsviewer.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class FlipTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "flipTest").apply { deleteRecursively(); mkdirs() }

    /** 쪽을 그려 (왼위, 오른위, 왼아래, 오른아래) 네 귀퉁이 중 검은 곳 */
    private fun corners(file: File): List<Boolean> = runBlocking {
        val d = PdfDoc.open(file)
        try {
            val s = d.sizes[0]
            val w = s.width.toInt()
            val h = s.height.toInt()
            val bmp = withContext(d.dispatcher) { d.render(0, 1f, 0f, 0f, w, h) }
            fun dark(x: Int, y: Int) = Color.red(bmp.getPixel(x, y)) < 100
            listOf(dark(30, 30), dark(w - 30, 30), dark(30, h - 30), dark(w - 30, h - 30))
        } finally {
            d.close()
        }
    }

    private fun makePdf(rotation: Int): File {
        val f = File(dir, "src$rotation.pdf")
        PDDocument().use { doc ->
            val page = PDPage(PDRectangle(400f, 600f))
            doc.addPage(page)
            // PDF 좌표는 아래가 0: 왼쪽 위 = (0, 600)
            PDPageContentStream(doc, page).use { cs ->
                cs.setNonStrokingColor(0f, 0f, 0f)
                cs.addRect(0f, 540f, 60f, 60f)
                cs.fill()
            }
            page.rotation = rotation
            doc.save(f)
        }
        return f
    }

    @Test
    fun flipPage() {
        PDFBoxResourceLoader.init(ctx)
        for (rot in listOf(0, 90)) {
            val src = makePdf(rot)
            val before = corners(src)
            // 돌린 쪽에서는 검은 네모가 다른 귀퉁이에 보인다. 그 귀퉁이 기준으로 뒤집힌 자리를 본다
            val k = before.indexOf(true)
            assertTrue("rot=$rot 처음 검은 귀퉁이 없음 $before", k >= 0)
            val h = File(dir, "h$rot.pdf").also { PdfPages.flip(src, it, listOf(0), horizontal = true) }
            val v = File(dir, "v$rot.pdf").also { PdfPages.flip(src, it, listOf(0), horizontal = false) }
            // 좌우: 0↔1, 2↔3 / 상하: 0↔2, 1↔3
            assertEquals("rot=$rot 좌우", k xor 1, corners(h).indexOf(true))
            assertEquals("rot=$rot 상하", k xor 2, corners(v).indexOf(true))
        }
    }

    @Test
    fun mirrorTextBox() {
        // 400×600 쪽의 (10,20)-(110,60) 글 상자
        val st = Stroke(Tool.PEN, Color.BLACK, 0f).apply {
            text = InkText("가", 20f)
            add(10f, 20f, 1f); add(110f, 20f, 1f); add(110f, 60f, 1f); add(10f, 60f, 1f)
        }
        val h = st.copy().apply { mirror(true, 400f) }
        // 자리는 오른쪽으로 옮겨 가고, 왼위 모서리가 여전히 왼쪽 위
        assertEquals(290f, h.x(0), 0.01f); assertEquals(20f, h.y(0), 0.01f)
        assertEquals(390f, h.x(1), 0.01f); assertEquals(60f, h.y(2), 0.01f)
        val v = st.copy().apply { mirror(false, 600f) }
        assertEquals(10f, v.x(0), 0.01f); assertEquals(540f, v.y(0), 0.01f)
        assertEquals(110f, v.x(1), 0.01f); assertEquals(580f, v.y(2), 0.01f)
    }
}
