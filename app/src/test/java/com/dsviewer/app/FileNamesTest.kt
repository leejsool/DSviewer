package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 파일 이름 다루기와 목록에서 끌어 고르기 */
class FileNamesTest {

    // ================= 못 쓰는 글자 =================

    @Test fun everyForbiddenCharacterBecomesAnUnderscore() {
        assertEquals("a_b_c_d_e_f_g_h_i", FileNames.safe("a\\b/c:d*e?f\"g<h>i").replace("|", "_"))
        for (c in listOf('\\', '/', ':', '*', '?', '"', '<', '>', '|')) {
            assertEquals("a_b", FileNames.safe("a${c}b"))
        }
    }

    @Test fun otherCharactersStayIncludingKoreanSpacesAndDots() {
        assertEquals("노트 2026-09-29 (2).필기", FileNames.safe("노트 2026-09-29 (2).필기"))
        assertEquals("a b-c_d.e", FileNames.safe("a b-c_d.e"))
    }

    @Test fun consecutiveForbiddenCharactersEachBecomeOne() {
        assertEquals("___", FileNames.safe("***"))
        assertEquals("a__b", FileNames.safe("a:/b"))
    }

    // ================= 이름·확장자 =================

    @Test fun baseNameDropsOnlyTheLastExtension() {
        assertEquals("보고서", FileNames.baseName("보고서.hwp"))
        assertEquals("a.b", FileNames.baseName("a.b.pdf"))
        assertEquals("noext", FileNames.baseName("noext"))
    }

    @Test fun aLeadingDotIsNotAnExtension() {
        assertEquals(".hidden", FileNames.baseName(".hidden"))
        assertEquals(".hidden", FileNames.baseName(".hidden"))
        assertEquals("", FileNames.extension(".hidden").let { if (it == "") "" else it })
    }

    @Test fun extensionKeepsTheDot() {
        assertEquals(".hwp", FileNames.extension("보고서.hwp"))
        assertEquals(".pdf", FileNames.extension("a.b.pdf"))
        assertEquals("", FileNames.extension("noext"))
        assertEquals("", FileNames.extension(""))
    }

    @Test fun baseAndExtensionPutBackTogetherGiveTheOriginal() {
        for (n in listOf("a.pdf", "a.b.c", ".x", "noext", "한글 이름.docx", "")) {
            assertEquals(n, FileNames.baseName(n) + FileNames.extension(n))
        }
    }

    // ================= 이름 바꾸기 입력 =================

    @Test fun renameTrimsAndCleansTheTypedName() {
        assertEquals("새 이름", FileNames.renamedBase("  새 이름  ", "옛 이름"))
        assertEquals("a_b", FileNames.renamedBase("a/b", "옛"))
    }

    @Test fun renameIgnoresEmptyOrUnchangedNames() {
        assertNull(FileNames.renamedBase("", "옛"))
        assertNull(FileNames.renamedBase("   ", "옛"))
        assertNull(FileNames.renamedBase("옛", "옛"))
        assertNull(FileNames.renamedBase(" 옛 ", "옛"))      // 공백만 다르면 같은 이름
        assertNull(FileNames.renamedBase("a/b", "a_b"))       // 정리한 뒤 같아지면
    }

    // ================= 끌어서 고르기 =================

    private val keys = listOf("k0", "k1", null, "k3", "k4", "k5")
    private fun at(i: Int): String? = keys.getOrNull(i)

    @Test fun dragSelectsFromTheAnchorToTheCurrentRowInEitherDirection() {
        assertEquals(setOf("k1", "k3", "k4"), DragSelect.select(emptySet(), 1, 4, ::at))
        assertEquals(setOf("k1", "k3", "k4"), DragSelect.select(emptySet(), 4, 1, ::at))
        assertEquals(setOf("k3"), DragSelect.select(emptySet(), 3, 3, ::at))
    }

    @Test fun dragKeepsWhatWasSelectedBeforeAndSkipsRowsWithoutAKey() {
        val r = DragSelect.select(setOf("old"), 0, 3, ::at)
        assertEquals(setOf("old", "k0", "k1", "k3"), r)        // 2번 칸(null)은 건너뛴다
    }

    @Test fun dragBackShrinksTheRangeButNotTheOldSelection() {
        val base = setOf("old")
        val wide = DragSelect.select(base, 0, 5, ::at)
        val narrow = DragSelect.select(base, 0, 1, ::at)
        assertTrue(wide.containsAll(narrow))
        assertEquals(setOf("old", "k0", "k1"), narrow)
    }

    @Test fun dragIgnoresRowsOutsideTheList() {
        assertEquals(setOf("k4", "k5"), DragSelect.select(emptySet(), 4, 9, ::at))
    }

    @Test fun selectionKeepsInsertionOrder() {
        assertEquals(listOf("z", "k0", "k1"), DragSelect.select(linkedSetOf("z"), 0, 1, ::at).toList())
    }

    // ================= 가장자리 자동 스크롤 =================

    @Test fun edgeAndSpeedConstants() {
        // 가장자리 72dp, 맨 끝 속도 28dp/프레임
        assertEquals(72f, DragSelect.EDGE_DP, 0f)
        assertEquals(28f, DragSelect.MAX_SPEED_DP, 0f)
    }

    @Test fun noAutoScrollInTheMiddle() {
        assertEquals(0f, DragSelect.autoScroll(500f, 1000, 100f, 50f), 0f)
        assertEquals(0f, DragSelect.autoScroll(100f, 1000, 100f, 50f), 0f)      // 경계는 스크롤 안 함
        assertEquals(0f, DragSelect.autoScroll(900f, 1000, 100f, 50f), 0f)
    }

    @Test fun autoScrollIsFasterTheCloserToTheEdgeAndSignedByDirection() {
        assertEquals(-25f, DragSelect.autoScroll(50f, 1000, 100f, 50f), 1e-4f)         // 위쪽 절반
        assertEquals(-50f, DragSelect.autoScroll(0f, 1000, 100f, 50f), 1e-4f)          // 맨 위: 최대
        assertEquals(25f, DragSelect.autoScroll(950f, 1000, 100f, 50f), 1e-4f)
        assertEquals(50f, DragSelect.autoScroll(1000f, 1000, 100f, 50f), 1e-4f)
        assertTrue(DragSelect.autoScroll(20f, 1000, 100f, 50f) < DragSelect.autoScroll(60f, 1000, 100f, 50f))
    }

    @Test fun edgeDetectionMatchesAutoScrollBeingNonZero() {
        for (y in listOf(-10f, 0f, 50f, 99f, 100f, 101f, 500f, 899f, 900f, 901f, 1000f, 1100f)) {
            assertEquals("y=$y", DragSelect.inEdge(y, 1000, 100f), DragSelect.autoScroll(y, 1000, 100f, 50f) != 0f)
        }
        assertTrue(DragSelect.inEdge(0f, 1000, 100f))
        assertFalse(DragSelect.inEdge(100f, 1000, 100f))
    }
}
