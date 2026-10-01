package com.dsviewer.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 북마크를 PDF에 저장했다가 다시 읽으면 같은 쪽에 붙어 있는지, 필기 없이 북마크만 있어도 찾는지,
 * 쪽을 넣으면 북마크가 쪽을 따라가는지.
 * 실행: adb shell am instrument -w -e class com.dsviewer.app.BookmarkSaveTest com.dsviewer.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class BookmarkSaveTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "bookmarkTest").apply { deleteRecursively(); mkdirs() }

    @Test
    fun saveLoadAndFollowPages() {
        PDFBoxResourceLoader.init(ctx)
        val src = File(dir, "src.pdf")
        PDDocument().use { d ->
            repeat(5) { d.addPage(PDPage(PDRectangle.A4)) }
            d.save(src)
        }
        assertFalse(PdfInk.containsInk(src))

        // 필기 없이 북마크만 (2·4쪽)
        val out = File(dir, "marks.pdf")
        PdfInk.save(src, out, List(5) { emptyList() }, setOf(1, 3))
        assertTrue(PdfInk.containsInk(out))
        val marks = HashSet<Int>()
        val strokes = PdfInk.extract(out, File(dir, "clean.pdf"), marks)
        assertEquals(setOf(1, 3), marks)

        // 다시 저장하면서 북마크를 바꾸면 옛 북마크는 빠진다
        val out2 = File(dir, "marks2.pdf")
        PdfInk.save(out, out2, strokes, setOf(0))
        val marks2 = HashSet<Int>()
        PdfInk.extract(out2, File(dir, "clean2.pdf"), marks2)
        assertEquals(setOf(0), marks2)

        // 앞에 쪽을 넣고 빼도 북마크는 그 쪽을 따라간다
        val ink = InkDocument(5)
        ink.load(strokes, marks)
        ink.changePages("a", "b") { pages -> pages.add(0, mutableListOf()) }
        assertEquals(setOf(2, 4), ink.bookmarkedPages())
        ink.setBookmark(2, false)
        assertEquals(setOf(4), ink.bookmarkedPages())
    }
}
