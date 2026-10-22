package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Test

/** 저장하지 않은 필기를 한꺼번에 저장할 때: 덮어쓸지 새 PDF로 둘지, 새 PDF의 이름 */
class DraftSaverTest {

    private fun meta(
        name: String = "수학 교재.pdf", type: String = "PDF", newNote: Boolean = false, canOverwrite: Boolean = true,
    ) = DraftMeta("id1", "file:///sdcard/Documents/$name", name, type, newNote, canOverwrite, time = 0L)

    // ================= 덮어쓸까 새로 둘까 =================

    @Test fun existingPdfIsOverwritten() {
        assertEquals(DraftSaver.Plan.OVERWRITE, DraftSaver.plan(meta(), originalExists = true))
    }

    @Test fun missingOriginalGoesToNewFile() {
        assertEquals(DraftSaver.Plan.SAVE_NEW, DraftSaver.plan(meta(), originalExists = false))
    }

    @Test fun newNoteHasNothingToOverwrite() {
        assertEquals(DraftSaver.Plan.SAVE_NEW, DraftSaver.plan(meta(newNote = true), originalExists = true))
    }

    @Test fun convertedDocumentIsSavedAsNewPdf() {
        for (t in listOf("HWP", "HWPX", "DOCX", "DOC", "PPTX", "PPT", "TXT", "IMAGE")) {
            assertEquals(t, DraftSaver.Plan.SAVE_NEW, DraftSaver.plan(meta(name = "문서.x", type = t), originalExists = true))
        }
    }

    @Test fun readOnlyPdfIsSavedAsNewFile() {
        // 다른 앱에서 연 읽기 전용 PDF (덮어쓸 수 없다)
        assertEquals(DraftSaver.Plan.SAVE_NEW, DraftSaver.plan(meta(canOverwrite = false), originalExists = true))
    }

    // ================= 새 PDF의 이름 =================

    @Test fun newNoteIsNamedByDate() {
        assertEquals("노트 2026-10-22.pdf", DraftSaver.newFileName(meta(name = "아무거나", newNote = true), "2026-10-22"))
    }

    @Test fun pdfGetsInkSuffix() {
        assertEquals("수학 교재_필기.pdf", DraftSaver.newFileName(meta(), "2026-10-22"))
    }

    @Test fun otherDocumentsKeepTheirNameAsPdf() {
        assertEquals("공문.pdf", DraftSaver.newFileName(meta(name = "공문.hwp", type = "HWP"), "2026-10-22"))
        assertEquals("보고서.pdf", DraftSaver.newFileName(meta(name = "보고서.docx", type = "DOCX"), "2026-10-22"))
    }

    @Test fun forbiddenCharactersAreReplacedAndBlankNameFallsBack() {
        assertEquals("a_b_필기.pdf", DraftSaver.newFileName(meta(name = "a:b.pdf"), "2026-10-22"))
        assertEquals("문서.pdf", DraftSaver.newFileName(meta(name = "  ", type = "HWP"), "2026-10-22"))
    }
}
