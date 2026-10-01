package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.rendering.PDFRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 채우기를 PDF에 저장했다가 다시 읽으면 윤곽·무늬·색·지우개 구멍이 그대로인지, 다른 앱처럼 주석 외형으로 그리면 칠해져 보이는지.
 * 앱 캐시 폴더(fillTest)에 결과 PDF·PNG를 남긴다.
 * 실행: gradlew installDebug installDebugAndroidTest 후
 *   adb shell am instrument -w -e class com.dsviewer.app.FillSaveTest com.dsviewer.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class FillSaveTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "fillTest").apply { deleteRecursively(); mkdirs() }

    private fun fill(pattern: FillPattern, color: Int, vararg contours: FloatArray) =
        Stroke(Tool.FILL, color, 0f).apply {
            fill = FillStyle(pattern)
            for (c in contours) for (i in 0 until c.size / 2) add(c[i * 2], c[i * 2 + 1], if (i == 0) 1f else 0f)
        }

    @Test
    fun saveLoadAndAppearance() {
        PDFBoxResourceLoader.init(ctx)
        val src = File(dir, "src.pdf")
        PDDocument().use { d ->
            d.addPage(PDPage(PDRectangle.A4))
            d.save(src)
        }
        // 구멍 난 네모 (바깥 + 안쪽 네모), 빗금 삼각형, 지우개 구멍이 있는 점 무늬 네모
        val ring = fill(
            FillPattern.SOLID, 0xFF90CAF9.toInt(),
            floatArrayOf(60f, 60f, 260f, 60f, 260f, 260f, 60f, 260f),
            floatArrayOf(120f, 120f, 120f, 200f, 200f, 200f, 200f, 120f),
        )
        val tri = fill(FillPattern.HATCH, 0xFFE53935.toInt(), floatArrayOf(320f, 260f, 520f, 260f, 420f, 60f))
        val dots = fill(FillPattern.DOT, 0xFF2E9D48.toInt(), floatArrayOf(60f, 320f, 260f, 320f, 260f, 500f, 60f, 500f))
            .withHole(160f, 410f, 30f)!!
        val fills = listOf(ring, tri, dots)
        val out = File(dir, "fills.pdf")
        PdfInk.save(src, out, listOf(fills))

        val back = PdfInk.extract(out, File(dir, "clean.pdf"))[0]
        assertEquals(fills.size, back.size)
        for ((a, b) in fills.zip(back)) {
            assertEquals(Tool.FILL, b.tool)
            assertNotNull(b.fill)
            assertEquals(a.fill!!.pattern, b.fill!!.pattern)
            assertEquals(a.color, b.color)
            assertEquals(a.count, b.count)
            var n = 0
            b.forEachContour { _, _ -> n++ }
            var m = 0
            a.forEachContour { _, _ -> m++ }
            assertEquals(m, n)
            assertEquals(a.holes.size, b.holes.size)
        }
        // 안쪽 네모·지우개 구멍은 비어 있다
        assertTrue(back[0].fillContains(80f, 80f))
        assertTrue(!back[0].fillContains(160f, 160f))
        assertTrue(!back[2].fillContains(160f, 410f))
        assertTrue(back[2].fillContains(80f, 340f))

        // 다른 앱처럼: PDFBox로 주석 외형까지 그려 본다
        val bmp = PDDocument.load(out).use { PDFRenderer(it).renderImage(0, 1f) }
        File(dir, "fills.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        fun px(x: Int, y: Int) = bmp.getPixel(x, y)
        assertTrue("구멍 난 네모의 칠", Color.blue(px(80, 80)) > 200 && Color.red(px(80, 80)) < 200)
        assertEquals("안쪽 네모는 흰색", Color.WHITE, px(160, 160) or 0xFF000000.toInt())

        // 이 앱 화면 그리기도 (예외 없이)
        val screen = Bitmap.createBitmap(595, 842, Bitmap.Config.ARGB_8888)
        val c = Canvas(screen)
        c.drawColor(Color.WHITE)
        back.sortedBy { inkLayer(it) }.forEach { drawInkStroke(c, inkPaint(), it) }
        File(dir, "screen.png").outputStream().use { screen.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertEquals(Color.WHITE, screen.getPixel(160, 160) or 0xFF000000.toInt())
        assertTrue(screen.getPixel(80, 80) != Color.WHITE)
    }
}
