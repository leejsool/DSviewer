package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.rendering.PDFRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 오답노트: 담은 문제(머리줄·문제 그림·원문 배지)와 오답 정보·PDF 목차가 저장했다 다시 읽어도 그대로인지,
 * 한 쪽 / 반 쪽 배치가 겹치지 않는지. 앱 캐시 폴더(wrongTest)에 결과 PDF·PNG를 남긴다.
 * 실행: gradlew installDebug installDebugAndroidTest 후
 *   adb shell am instrument -w -e class com.dsviewer.app.WrongNoteTest com.dsviewer.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class WrongNoteTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(ctx.cacheDir, "wrongTest").apply { deleteRecursively(); mkdirs() }

    /** 문제처럼 보이는 그림: 글줄 몇 개가 있는 흰 바탕 */
    private fun fakeProblem(w: Int, h: Int): Bitmap {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.DKGRAY; textSize = h / 8f }
        for (i in 1..4) c.drawText("3. 다음 극한값을 구하시오 ($i)", 12f, i * h / 5f, p)
        return b
    }

    private fun bounds(s: Stroke) = wrongBounds(s)

    @Test
    fun layoutDoesNotOverlap() {
        val rect = RectF(30f, 100f, 530f, 300f)
        val capture = fakeProblem(1500, 600)
        for (slot in 0..2) {
            val e = WrongEntry(1, 3, listOf("극한"), "제목", WrongNote.today(), slot, null, rect)
            val built = WrongNote.build(e, capture, rect, 595f)
            val h = bounds(built.header)
            val b = bounds(built.body)
            val slotTop = WrongNote.slotTop(slot)
            val slotBottom = slotTop + if (slot == 0) WrongNote.PAGE_H else WrongNote.PAGE_H / 2f
            assertTrue("slot $slot header inside", h.top >= slotTop && h.bottom <= slotBottom)
            assertTrue("slot $slot header above body", h.bottom <= b.top)
            assertTrue("slot $slot body inside slot", b.bottom <= slotBottom)
            // 풀이 쓸 자리: 칸의 40% 이상은 비어 있어야 한다
            val free = slotBottom - b.bottom
            assertTrue("slot $slot has room to write ($free)", free >= (slotBottom - slotTop) * 0.4f)
            assertNull("no badge without source", built.badge)
        }
    }

    @Test
    fun saveLoadAndOutline() {
        PDFBoxResourceLoader.init(ctx)
        val src = File(dir, "src.pdf")
        PdfPages.create(src, Paper.PLAIN, 595f, 842f, 3)
        // 원문 3쪽 + 맨 뒤에 오답 쪽 둘 (첫째는 반 쪽 둘, 둘째는 한 쪽)
        val withNotes = File(dir, "withNotes.pdf")
        PdfPages.insert(src, File(dir, "t1.pdf"), 3, Paper.LINED, WrongNote.PAGE_W, WrongNote.PAGE_H)
        PdfPages.insert(File(dir, "t1.pdf"), withNotes, 4, Paper.GRID, WrongNote.PAGE_W, WrongNote.PAGE_H)

        val ink = InkDocument(5)
        val rect1 = RectF(40f, 120f, 520f, 260f)
        val rect2 = RectF(60f, 400f, 300f, 520f)
        val e1 = WrongEntry(1, 4, listOf("미적분", "극한"), "3번 치환", "2026-10-04", 1, ink.pages[0], rect1)
        val e2 = WrongEntry(2, WrongSymbol.CROSS, listOf("확률"), "", "2026-10-04", 2, ink.pages[1], rect2)
        val e3 = WrongEntry(3, WrongSymbol.SQUARE, emptyList(), "", "2026-10-05", 0, ink.pages[1], rect2)
        val b1 = WrongNote.build(e1, fakeProblem(1440, 420), rect1, 595f)
        val b2 = WrongNote.build(e2, fakeProblem(720, 360), rect2, 595f)
        val b3 = WrongNote.build(e3, fakeProblem(720, 360), rect2, 595f)
        // 오답 쪽 첫째: 위 칸 + 아래 칸이 한 쪽에
        ink.pages[3].addAll(listOf(b1.header, b1.body, b2.header, b2.body))
        ink.pages[4].addAll(listOf(b3.header, b3.body))
        ink.pages[0].add(b1.badge!!)
        ink.pages[1].addAll(listOf(b2.badge!!, b3.badge!!))
        ink.attachWrong(ink.pages[3], e1)
        ink.attachWrong(ink.pages[3], e2)
        ink.attachWrong(ink.pages[4], e3)

        val out = File(dir, "notes.pdf")
        PdfInk.save(withNotes, out, ink.snapshot(), emptySet(), ink.wrongSave())

        // 목차: 오답노트 ▸ 기호 묶음 ▸ 항목
        PDDocument.load(out).use { doc ->
            val ours = doc.documentCatalog.documentOutline.children().first()
            assertEquals("오답노트 (3)", ours.title)
            val groups = ours.children().map { it.title }
            assertEquals(listOf("★★★★ (1)", "✕ (1)", "□ (1)"), groups)
            val first = ours.children().first().children().first()
            assertEquals(3, doc.pages.indexOf(first.findDestinationPage(doc)))
        }

        val wrongs = HashMap<Int, String>()
        val ink2 = InkDocument(5)
        ink2.load(PdfInk.extract(out, File(dir, "clean.pdf"), null, wrongs))
        ink2.loadWrongs(wrongs)
        val all = ink2.allWrongs()
        assertEquals(3, all.size)
        assertEquals(listOf(3, 3, 4), all.map { it.first })
        assertEquals(listOf(1, 2, 3), all.map { it.second.number })
        assertEquals(listOf("미적분", "극한"), all[0].second.tags)
        assertEquals("3번 치환", all[0].second.title)
        // 원문 쪽이 쪽 순서를 따라 다시 이어진다
        assertTrue(all[0].second.srcList === ink2.pages[0])
        assertTrue(all[1].second.srcList === ink2.pages[1])
        // 그림 획의 이름표가 살아 있다
        assertTrue(ink2.pages[3].any { it.role == "WH1" && it.image != null })
        assertTrue(ink2.pages[0].any { it.role == "WS1" && it.image != null })
        // 오답 쪽 링크: 원문의 배지 → 오답 쪽, 머리줄 오른쪽 → 원문
        val badge = bounds(ink2.pages[0].first { it.role == "WS1" })
        // 배지는 쪽 오른쪽 바깥 여백에 있어 원문을 가리지 않는다 (저장·불러오기 뒤에도 그 자리)
        assertTrue("badge in right margin (${badge.left})", badge.left >= 595f)
        assertEquals(3, ink2.wrongLinkAt(0, badge.centerX(), badge.centerY())?.first)
        val head = bounds(ink2.pages[3].first { it.role == "WH1" })
        assertEquals(0, ink2.wrongLinkAt(3, head.right - 10f, head.centerY())?.first)
        assertNull(ink2.wrongLinkAt(3, head.left + 10f, head.centerY()))

        // 다른 앱처럼 그려 본다: 오답 쪽을 PNG로
        PDDocument.load(out).use { doc ->
            val renderer = PDFRenderer(doc)
            for (i in 3..4) File(dir, "page$i.png").outputStream().use {
                renderer.renderImage(i, 1.2f).compress(Bitmap.CompressFormat.PNG, 100, it)
            }
        }
    }
}
