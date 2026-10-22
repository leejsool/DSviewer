package com.dsviewer.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 펜 획 주석을 다시 쓰는 저장 (PdfInk의 주석 재료 보관)이 새로 만든 저장과 같은 파일 내용을 내는지:
 * 두 번째 저장은 재료를 다시 쓰고, 획이 바뀌거나 쪽 상자·회전이 다르면 그 획만 다시 만들며,
 * 어느 쪽이든 주석을 펼친 글과 다시 읽은 필기가 보관 없이 저장한 것과 같아야 한다. 두 저장이 겹쳐 돌아도 마찬가지.
 * 실행: gradlew installDebug installDebugAndroidTest 후
 *   adb shell am instrument -w -e class com.dsviewer.app.InkSaveReuseTest com.dsviewer.app.dev.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class InkSaveReuseTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "inkReuseTest").apply { deleteRecursively(); mkdirs() }
    private val src = File(dir, "src.pdf")

    @Before
    fun setUp() {
        PDFBoxResourceLoader.init(ctx)
        InkSaveFixture.basePdf(src)
    }

    private fun pages() = listOf(InkSaveFixture.pageStrokes(0), InkSaveFixture.pageStrokes(1), InkSaveFixture.pageStrokes(2))

    /** 재료를 보관하는 획 (펜·형광펜): 그림·글·표·테이프·채우기·메모가 아닌 것 */
    private fun isPen(s: Stroke) = s.count > 0 && s.note == null && s.image == null && s.text == null &&
        s.table == null && s.tape == null && s.fill == null

    /** 다시 읽은 필기를 쪽마다 획 글로 (차례는 따지지 않는다) */
    private fun readBack(file: File): List<List<String>> =
        PdfInk.extract(file, File(dir, "clean.pdf")).map { p -> p.map { PdfInk.encodeStroke(it) }.sorted() }

    private fun expected(pages: List<List<Stroke>>) = pages.map { p -> p.filter { it.count > 0 }.map { PdfInk.encodeStroke(it) }.sorted() }

    /** 보관 없이 새로 만들어 저장한 결과 */
    private fun fresh(base: File, pages: List<List<Stroke>>, name: String): File {
        PdfInk.clearInkParts()
        return File(dir, name).also { PdfInk.save(base, it, pages) }
    }

    @Test
    fun secondSaveReusesAndMatches() {
        val pages = pages()
        val pens = pages.sumOf { p -> p.count(::isPen) }
        PdfInk.clearInkParts()
        val b0 = PdfInk.inkPartBuilds
        val cold = File(dir, "cold.pdf").also { PdfInk.save(src, it, pages) }
        assertEquals("첫 저장은 펜 획마다 새로 만든다", pens, PdfInk.inkPartBuilds - b0)
        val b1 = PdfInk.inkPartBuilds
        val warm = File(dir, "warm.pdf").also { PdfInk.save(src, it, pages) }
        assertEquals("두 번째 저장은 다시 쓴다", 0, PdfInk.inkPartBuilds - b1)

        // 펜 획 주석은 이름(/NM)까지 그대로, 쪽 참조는 새 문서의 쪽으로 (채우기 주석은 저장마다 이름을 새로 짓는다)
        assertEquals(InkSaveFixture.dump(cold, withNames = false), InkSaveFixture.dump(warm, withNames = false))
        assertEquals(InkSaveFixture.inkNames(cold), InkSaveFixture.inkNames(warm))
        assertEquals(pens, InkSaveFixture.inkNames(warm).toSet().size)
        assertEquals(expected(pages), readBack(warm))
        assertEquals(expected(pages), readBack(cold))
        // 보관 없이 새로 만든 저장과도 같다 (이름은 새로 지으니 빼고)
        assertEquals(InkSaveFixture.dump(fresh(src, pages, "fresh.pdf"), withNames = false), InkSaveFixture.dump(warm, withNames = false))
        // 세 번 저장해도 (다시 쓴 재료를 또 다시 써도) 같다
        val again = File(dir, "again.pdf").also { PdfInk.save(src, it, pages) }
        assertEquals(InkSaveFixture.dump(warm, withNames = false), InkSaveFixture.dump(again, withNames = false))
    }

    @Test
    fun changedStrokeIsRebuilt() {
        val pages = pages()
        PdfInk.clearInkParts()
        PdfInk.save(src, File(dir, "first.pdf"), pages)
        // 옮기기, 색 바꾸기, 점 더하기: 그 획만 다시 만든다
        pages[0][0].translate(5f, 7f)
        pages[1][2].recolor(0xFFFF9800.toInt())
        pages[2][1].add(300f, 300f, 0.5f)
        val b = PdfInk.inkPartBuilds
        val out = File(dir, "changed.pdf").also { PdfInk.save(src, it, pages) }
        assertEquals(3, PdfInk.inkPartBuilds - b)
        assertEquals(expected(pages), readBack(out))
        assertNotEquals(InkSaveFixture.dump(File(dir, "first.pdf"), withNames = false), InkSaveFixture.dump(out, withNames = false))
        assertEquals(InkSaveFixture.dump(fresh(src, pages, "changedFresh.pdf"), withNames = false), InkSaveFixture.dump(out, withNames = false))
        // 획을 빼고 저장해도 남은 것만 들어간다
        val fewer = pages.map { it.drop(2) }
        val out2 = File(dir, "fewer.pdf").also { PdfInk.save(src, it, fewer) }
        assertEquals(expected(fewer), readBack(out2))
    }

    @Test
    fun otherPageFrameIsRebuilt() {
        val pages = pages()
        PdfInk.clearInkParts()
        PdfInk.save(src, File(dir, "first.pdf"), pages)
        // 셋째 쪽만 자르기 상자가 다른 바탕: 그 쪽의 펜 획만 다시 만든다
        val src2 = File(dir, "src2.pdf").also { InkSaveFixture.basePdf(it, shift = 10f) }
        val b = PdfInk.inkPartBuilds
        val out = File(dir, "shifted.pdf").also { PdfInk.save(src2, it, pages) }
        assertEquals(pages[2].count(::isPen), PdfInk.inkPartBuilds - b)
        assertEquals(InkSaveFixture.dump(fresh(src2, pages, "shiftedFresh.pdf"), withNames = false), InkSaveFixture.dump(out, withNames = false))
        assertEquals(expected(pages), readBack(out))
    }

    @Test
    fun numbersWrittenLikeCosFloat() {
        val rnd = java.util.Random(7)
        val values = mutableListOf(0f, -0f, 1f, -1f, 100f, 0.5f, 1e-4f, -3.2e-5f, 1.5e7f, 123456789f, 595.2756f, Float.MIN_VALUE)
        repeat(5000) { values.add((rnd.nextFloat() - 0.5f) * 2000f) }
        repeat(500) { values.add((rnd.nextFloat() - 0.5f) * 0.002f) }
        for (v in values) {
            val want = java.io.ByteArrayOutputStream().also { com.tom_roush.pdfbox.cos.COSFloat(v).writePDF(it) }.toString("ISO-8859-1")
            assertEquals("$v", want, PdfInk.pdfNumber(v))
        }
    }

    @Test
    fun overlappingSavesDoNotInterfere() {
        val pages = pages()
        PdfInk.clearInkParts()
        val ref = File(dir, "ref.pdf").also { PdfInk.save(src, it, pages) }
        val want = InkSaveFixture.dump(ref, withNames = false)
        // 같은 재료를 쓰는 저장 여럿을 한꺼번에 (자동 덮어쓰기와 손으로 저장이 겹칠 때처럼)
        val pool = Executors.newFixedThreadPool(4)
        val jobs = (0 until 12).map { k ->
            pool.submit<File> { File(dir, "par$k.pdf").also { PdfInk.save(src, it, pages) } }
        }
        val outs = jobs.map { it.get(120, TimeUnit.SECONDS) }
        pool.shutdown()
        for (f in outs) {
            assertEquals(f.name, want, InkSaveFixture.dump(f, withNames = false))
            assertEquals(f.name, expected(pages), readBack(f))
        }
    }
}
