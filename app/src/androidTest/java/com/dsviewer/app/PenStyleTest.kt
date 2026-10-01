package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 펜 종류마다 글씨 같은 획(고리·지그재그·물결·점)을 쓰는 동안처럼 PenInput에 흘려 넣어 그리고,
 * PDF에 저장했다가 다시 읽어 펜 종류·점이 그대로인지 본다. 앱 캐시 폴더(penTest)에 PNG·PDF를 남긴다.
 * 실행: gradlew installDebug installDebugAndroidTest 후
 *   adb shell am instrument -w -e class com.dsviewer.app.PenStyleTest com.dsviewer.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class PenStyleTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "penTest").apply { deleteRecursively(); mkdirs() }

    /** 매개변수 곡선을 4ms마다 받은 점처럼 넣는다. 필압은 가운데가 세고 양 끝이 약하다 */
    private fun write(style: PenStyle, width: Float, smoothing: Int, steps: Int, ms: Long, f: (Float) -> Pair<Float, Float>): Stroke {
        val st = Stroke(Tool.PEN, Color.BLACK, width, pen = style)
        val input = PenInput()
        val (x0, y0) = f(0f)
        input.begin(style, width, smoothing, x0, y0, 0.3f, 0L)
        st.add(input.x, input.y, input.p)
        for (k in 1..steps) {
            val t = k / steps.toFloat()
            val (x, y) = f(t)
            val pr = 0.3f + 0.6f * sin(PI.toFloat() * t)
            if (input.move(x, y, pr, k * ms, 2f, 0.3f, st.x(st.count - 1), st.y(st.count - 1))) st.add(input.x, input.y, input.p)
        }
        input.finish(st)
        return st
    }

    private fun row(style: PenStyle, width: Float, top: Float, smoothing: Int = 0): List<Stroke> {
        val out = ArrayList<Stroke>()
        // 고리 (필기체 l 여러 개)
        out.add(write(style, width, smoothing, 220, 4) { t ->
            val a = t * 4 * 2 * PI.toFloat()
            (40f + t * 150f + 12f * sin(a)) to (top + 30f - 20f * cos(a) + 0f)
        })
        // 지그재그 (급히 꺾이는 곳)
        out.add(write(style, width, smoothing, 120, 4) { t ->
            val seg = t * 6
            val i = seg.toInt().coerceAtMost(5)
            val u = seg - i
            val y0 = if (i % 2 == 0) top + 50f else top + 10f
            val y1 = if (i % 2 == 0) top + 10f else top + 50f
            (220f + t * 120f) to (y0 + (y1 - y0) * u)
        })
        // 빠른 물결 (속도에 따라 가늘어지는지)
        out.add(write(style, width, smoothing, 60, 4) { t ->
            (370f + t * 130f) to (top + 30f + 18f * sin(t * 3 * 2 * PI.toFloat()))
        })
        // 짧게 찍은 점 + 가로 획 (끝 가늘기)
        out.add(write(style, width, smoothing, 2, 4) { t -> (520f + t * 0.2f) to (top + 30f) })
        out.add(write(style, width, smoothing, 40, 6) { t -> (535f + t * 40f) to (top + 30f) })
        return out
    }

    @Test
    fun drawAndSave() {
        PDFBoxResourceLoader.init(ctx)
        val rows = ArrayList<Stroke>()
        val widths = mapOf(
            PenStyle.FELT to 1.2f, PenStyle.BALL to 0.9f, PenStyle.FOUNTAIN to 1.6f,
            PenStyle.BRUSH to 3f, PenStyle.PENCIL to 1.4f, PenStyle.CALLIGRAPHY to 4f,
        )
        PenStyle.entries.forEachIndexed { i, s -> rows += row(s, widths.getValue(s), 20f + i * 80f) }
        // 손떨림 보정: 떨리는 획을 끔·강하게로
        for ((j, lv) in listOf(0, 3).withIndex()) {
            rows += write(PenStyle.FOUNTAIN, 1.6f, lv, 200, 4) { t ->
                val jitter = if ((t * 200).toInt() % 2 == 0) 1.2f else -1.2f
                (40f + t * 250f) to (520f + j * 40f + 10f * sin(t * 2 * PI.toFloat()) + jitter)
            }
        }

        // 화면처럼 그리기 (2배)
        val bmp = Bitmap.createBitmap(1240, 1200, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.scale(2f, 2f)
        val label = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9f; color = Color.GRAY }
        PenStyle.entries.forEachIndexed { i, s -> c.drawText(s.label, 2f, 20f + i * 80f + 34f, label) }
        val paint = inkPaint()
        rows.forEach { drawInkStroke(c, paint, it) }
        File(dir, "pens.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // 확대해서 붓펜·캘리그래피 끝
        val zoom = Bitmap.createBitmap(1200, 600, Bitmap.Config.ARGB_8888)
        Canvas(zoom).apply {
            drawColor(Color.WHITE)
            scale(6f, 6f)
            translate(-20f, -250f)
            rows.forEach { drawInkStroke(this, paint, it) }
        }
        File(dir, "zoom.png").outputStream().use { zoom.compress(Bitmap.CompressFormat.PNG, 100, it) }

        // PDF 저장 → 다시 읽기
        val src = File(dir, "src.pdf")
        PDDocument().use { d -> d.addPage(PDPage(PDRectangle(620f, 600f))); d.save(src) }
        val out = File(dir, "pens.pdf")
        PdfInk.save(src, out, listOf(rows))
        val back = PdfInk.extract(out, File(dir, "clean.pdf"))[0]
        assertEquals(rows.size, back.size)
        for ((a, b) in rows.zip(back)) {
            assertEquals(a.pen, b.pen)
            assertEquals(a.count, b.count)
            assertEquals(a.width, b.width, 0.01f)
            for (k in 0 until a.count) assertEquals(a.p(k), b.p(k), 0.01f)
        }
        // 다시 읽은 획을 그린 것 (처음 그린 것과 같아야 한다)
        val bmp2 = Bitmap.createBitmap(1240, 1200, Bitmap.Config.ARGB_8888)
        Canvas(bmp2).apply {
            drawColor(Color.WHITE)
            scale(2f, 2f)
            back.forEach { drawInkStroke(this, paint, it) }
        }
        File(dir, "pens_back.png").outputStream().use { bmp2.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
