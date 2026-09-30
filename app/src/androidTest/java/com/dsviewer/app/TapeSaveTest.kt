package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 테이프를 PDF에 저장했다가 다시 읽으면 모양·무늬·색이 그대로인지, 화면에 그리면 아래 내용이 가려지는지.
 * 앱 캐시 폴더(tapeTest)에 결과 PDF·PNG를 남긴다 (adb로 꺼내 다른 PDF 뷰어로 확인할 수 있게).
 * 실행: gradlew installDebug installDebugAndroidTest 후
 *   adb shell am instrument -w -e class com.dsviewer.app.TapeSaveTest com.dsviewer.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class TapeSaveTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "tapeTest").apply { deleteRecursively(); mkdirs() }

    private fun penTape(pattern: TapePattern, color: Int, w: Float, vararg pts: Float) =
        Stroke(Tool.TAPE, color, w).apply {
            tape = TapeStyle(pattern, rect = false)
            for (i in 0 until pts.size / 2) add(pts[i * 2], pts[i * 2 + 1], 0.5f)
        }

    private fun rectTape(pattern: TapePattern, color: Int, l: Float, t: Float, r: Float, b: Float) =
        Stroke(Tool.TAPE, color, 0f).apply {
            tape = TapeStyle(pattern, rect = true)
            add(l, t, 1f); add(r, t, 1f); add(r, b, 1f); add(l, b, 1f)
        }

    @Test
    fun saveLoadAndCover() {
        PDFBoxResourceLoader.init(ctx)
        // 검은 막대 세 줄이 있는 쪽 (가려야 할 내용)
        val src = File(dir, "src.pdf")
        PDDocument().use { d ->
            val page = PDPage(PDRectangle.A4)
            d.addPage(page)
            PDPageContentStream(d, page).use { cs ->
                cs.setNonStrokingColor(0f, 0f, 0f)
                for (k in 0 until 3) cs.addRect(80f, 842f - (114f + k * 60f), 300f, 14f)
                cs.fill()
            }
            d.save(src)
        }
        val tapes = listOf(
            penTape(TapePattern.STRIPE, 0xFFF6C744.toInt(), 22f, 70f, 107f, 390f, 107f),
            penTape(TapePattern.DOT, 0xFF7FC8F8.toInt(), 22f, 70f, 167f, 150f, 162f, 250f, 172f, 390f, 167f),
            rectTape(TapePattern.CHECK, 0xFFF48FB1.toInt(), 70f, 210f, 390f, 245f),
            rectTape(TapePattern.GRID, 0xFF81C784.toInt(), 70f, 300f, 200f, 400f),
            penTape(TapePattern.SOLID, 0xFF9E9E9E.toInt(), 30f, 250f, 300f, 390f, 400f),
        )
        val out = File(dir, "tapes.pdf")
        PdfInk.save(src, out, listOf(tapes))

        val back = PdfInk.extract(out, File(dir, "clean.pdf"))[0]
        assertEquals(tapes.size, back.size)
        for ((a, b) in tapes.zip(back)) {
            assertEquals(Tool.TAPE, b.tool)
            assertNotNull(b.tape)
            assertEquals(a.tape!!.pattern, b.tape!!.pattern)
            assertEquals(a.tape!!.rect, b.tape!!.rect)
            assertEquals(a.color, b.color)
            assertEquals(a.width, b.width, 0.01f)
            assertEquals(a.count, b.count)
            assertFalse(b.revealed)
        }

        // 화면처럼 그려 보기: 가린 테이프 아래 검은 막대가 보이지 않아야 한다
        val k = 2f
        fun render(reveal: Boolean): Bitmap {
            val bmp = Bitmap.createBitmap((595 * k).toInt(), (842 * k).toInt(), Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            c.scale(k, k)
            val p = android.graphics.Paint().apply { color = Color.BLACK }
            for (j in 0 until 3) c.drawRect(80f, 100f + j * 60f, 380f, 114f + j * 60f, p)
            back.forEach { it.revealed = reveal; drawInkStroke(c, inkPaint(), it) }
            File(dir, if (reveal) "revealed.png" else "hidden.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return bmp
        }
        fun darkInBars(bmp: Bitmap): Int {
            var n = 0
            for (j in 0 until 3) for (x in 90 until 370 step 5) {
                val c = bmp.getPixel((x * k).toInt(), ((107f + j * 60f) * k).toInt())
                if (Color.red(c) + Color.green(c) + Color.blue(c) < 120) n++
            }
            return n
        }
        assertEquals("가린 테이프 아래 막대가 보임", 0, darkInBars(render(reveal = false)))
        assertTrue("보이게 한 테이프 아래 막대가 안 보임", darkInBars(render(reveal = true)) > 100)
    }

    /** 영역 지우개로 뚫은 구멍: 그 자리만 아래가 보이고, 저장·다시 읽기·옮기기에 따라가고, 다 뚫으면 사라진 것으로 본다 */
    @Test
    fun holes() {
        PDFBoxResourceLoader.init(ctx)
        val src = File(dir, "blank.pdf")
        PDDocument().use { d -> d.addPage(PDPage(PDRectangle.A4)); d.save(src) }
        for (rect in listOf(false, true)) {
            val t0 = if (rect) rectTape(TapePattern.STRIPE, 0xFFF6C744.toInt(), 50f, 90f, 250f, 110f)
            else penTape(TapePattern.STRIPE, 0xFFF6C744.toInt(), 20f, 50f, 100f, 250f, 100f)
            val t = t0.withHole(150f, 100f, 15f)!!
            assertFalse(t.tapeContains(150f, 100f))
            assertTrue(t.tapeContains(80f, 100f))
            assertFalse(t.tapeGone())
            // 저장했다 읽어도 구멍이 남는다
            val out = File(dir, "holes_$rect.pdf")
            PdfInk.save(src, out, listOf(listOf(t)))
            val back = PdfInk.extract(out, File(dir, "holes_clean.pdf"))[0].single()
            assertEquals(3, back.holes.size)
            assertFalse(back.tapeContains(150f, 100f))
            // 옮기면 구멍도 같이
            back.translate(10f, 0f)
            assertFalse(back.tapeContains(160f, 100f))
            assertTrue(back.tapeContains(145f - 20f, 100f))
            // 테이프 전체를 덮는 구멍들이면 사라진 것
            var all: Stroke = t0
            var x = 40f
            while (x <= 260f) { all = all.withHole(x, 100f, 20f) ?: all; x += 10f }
            assertTrue(all.tapeGone())
        }
    }
}
