package com.dsviewer.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 글 상자를 PDF에 저장할 때 글꼴이 글 상자마다가 아니라 한 번만 담기는지, 글씨체가 저장·다시 읽기로 이어지는지.
 * 앱 캐시 폴더에서만 만들고 지운다.
 * 실행 (앱을 지우지 않음): gradlew installDebug installDebugAndroidTest 후
 *   adb shell am instrument -w com.dsviewer.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class TextSaveSizeTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "textSaveTest").apply { mkdirs() }
    private val blank = File(dir, "blank.pdf")

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ctx)
        TextFont.init(ctx)
        PDDocument().use { d ->
            repeat(2) { d.addPage(PDPage(PDRectangle.A4)) }
            d.save(blank)
        }
    }

    private fun box(text: String, font: TextFont, x: Float, y: Float): Stroke {
        val doc = RichDoc(text, listOf(RichDoc.Run(0, text.length, 'f', font.ordinal.toDouble())), emptyList())
        val t = InkText(doc, 20f)
        return Stroke(Tool.PEN, 0xFF000000.toInt(), 0f).apply {
            this.text = t
            add(x, y, 1f)
            add(x + t.boxW, y, 1f)
            add(x + t.boxW, y + t.boxH, 1f)
            add(x, y + t.boxH, 1f)
        }
    }

    private fun saveSize(name: String, pages: List<List<Stroke>>): Long {
        val out = File(dir, "$name.pdf")
        PdfInk.save(blank, out, pages)
        return out.length()
    }

    @Test
    fun fontEmbeddedOnceAndKept() {
        val text = "안녕하세요 오늘은 수학 시간입니다 삼각형의 넓이"
        val none = saveSize("none", listOf(emptyList(), emptyList()))
        val one = saveSize("one", listOf(listOf(box(text, TextFont.PEN, 50f, 50f)), emptyList()))
        // 두 쪽에 걸쳐 같은 글 상자 10개
        val ten = saveSize("ten", listOf(
            (0 until 5).map { box(text, TextFont.PEN, 50f, 50f + it * 60f) },
            (0 until 5).map { box(text, TextFont.PEN, 50f, 50f + it * 60f) },
        ))
        val perBox = one - none
        val tenExtra = ten - none
        Log.i("TextSaveSizeTest", "none=$none one=$one ten=$ten perBox=$perBox tenExtra=$tenExtra")
        // 글꼴을 글 상자마다 담았다면 10개는 한 개의 거의 10배가 된다
        assertTrue("10 boxes added $tenExtra bytes, 1 box added $perBox", tenExtra < perBox * 3)

        // 다시 읽으면 글씨체가 남아 있다
        val back = PdfInk.extract(File(dir, "ten.pdf"), File(dir, "clean.pdf"))
        val run = back[0][0].text!!.rich.runs.single { it.code == 'f' }
        assertEquals(TextFont.PEN.ordinal, run.value.toInt())
        dir.deleteRecursively()
    }
}
