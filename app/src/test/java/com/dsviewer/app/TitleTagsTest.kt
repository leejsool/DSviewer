package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 문서 제목에서 뽑는 추천 태그 */
class TitleTagsTest {

    @Test fun wordsSplitAtKindChangesAndSymbols() {
        assertEquals(setOf("2026", "부장회의", "자료방"), TitleTags.words("2026학년도 부장회의 자료방_10.16.pdf"))
        assertEquals(setOf("부장회의"), TitleTags.words("부장회의10-12.pdf"))
    }

    @Test fun shortWordsAndPlainNumbersAreDropped() {
        assertTrue(TitleTags.words("a 1 12 가.pdf").isEmpty())
        assertEquals(setOf("report"), TitleTags.words("Report_v2 (복사본).pdf"))
    }

    @Test fun extensionIsNotATag() {
        assertFalse("pdf" in TitleTags.words("회의록.pdf"))
        assertFalse("hwpx" in TitleTags.words("공문.hwpx"))
    }

    @Test fun onlyWordsInTwoOrMoreTitlesBecomeTags() {
        val titles = listOf("2026학년도 부장회의 자료방_10.16.pdf", "부장회의10-12.pdf", "수학 시험지.pdf")
        assertEquals(listOf("부장회의"), TitleTags.extract(titles))
    }

    @Test fun moreCommonThenMoreRecentComesFirst() {
        val titles = listOf("가나다 월간.pdf", "월간 보고.pdf", "월간 계획.pdf", "가나다 보고.pdf")
        // 월간 3번, 가나다·보고 2번 (가나다가 더 최근 문서에 처음 나온다)
        assertEquals(listOf("월간", "가나다", "보고"), TitleTags.extract(titles))
    }

    @Test fun singleTitleGivesNoTags() {
        assertTrue(TitleTags.extract(listOf("부장회의 자료.pdf")).isEmpty())
        assertTrue(TitleTags.extract(emptyList()).isEmpty())
    }

    @Test fun tagCountIsLimited() {
        val titles = (1..30).map { i -> "낱말${"가나다라마바사아자차카타파하"[i % 14]}${"가나다라마바사아자차카타파하"[(i + 3) % 14]} 공통어.pdf" }
        assertTrue(TitleTags.extract(titles, max = 3).size <= 3)
    }

    @Test fun pickedTagMovesMatchingDocsToTheTopKeepingOrder() {
        // 폴더(문서 아님)는 태그에 맞는 문서가 아니므로 나머지 쪽에 남는다
        val rows = listOf("보관/", "a 회의.pdf", "b 시험.pdf", "c 회의.pdf", "d 안내.pdf")
        val (hit, rest) = TitleTags.split(rows, "회의", { it }, { !it.endsWith("/") })
        assertEquals(listOf("a 회의.pdf", "c 회의.pdf"), hit)
        assertEquals(listOf("보관/", "b 시험.pdf", "d 안내.pdf"), rest)
    }

    @Test fun tagMatchingNothingKeepsEverythingInRest() {
        val (hit, rest) = TitleTags.split(listOf("a.pdf", "b.pdf"), "없음", { it }, { true })
        assertTrue(hit.isEmpty())
        assertEquals(listOf("a.pdf", "b.pdf"), rest)
    }

    @Test fun matchingIgnoresLatinCase() {
        assertTrue(TitleTags.matches("Report 2026.pdf", "report"))
        assertFalse(TitleTags.matches("회의.pdf", "부장"))
    }
}
