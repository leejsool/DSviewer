package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.text.Collator
import java.util.Locale
import kotlin.random.Random

/** 문서 목록 정렬·형식·검색·칸 수 계산 */
class ListSortTest {

    private class Item(
        val title: String, val folder: Boolean = false, val drive: Boolean = false,
        val opened: Long = 0, val modified: Long = 0, val size: Long = 0,
    )

    private val collator: Collator = Collator.getInstance(Locale.KOREAN)

    private val fields = object : ListSort.Fields<Item> {
        override fun title(r: Item) = r.title
        override fun isFolder(r: Item) = r.folder
        override fun isDrive(r: Item) = r.drive
        override fun opened(r: Item) = r.opened
        override fun modified(r: Item) = r.modified
        override fun size(r: Item) = r.size
        override fun typeRank(r: Item) = DocGroup.of(DocType.ofName(r.title))?.ordinal ?: DocGroup.entries.size
    }

    private fun sorted(rows: List<Item>, key: SortKey, desc: Boolean) =
        ListSort.sort(rows, key, desc, collator, fields).map { it.title }

    // ================= 직접 정한 순서 =================

    private fun manual(rows: List<Item>, order: List<String>) =
        ListSort.sortManual(rows, order, { it.title }, fields).map { it.title }

    @Test fun manualOrderFollowsSavedListAndKeepsFoldersFirst() {
        val rows = listOf(Item("a.pdf"), Item("b.pdf"), Item("보관", folder = true), Item("c.pdf"))
        assertEquals(listOf("보관", "c.pdf", "a.pdf", "b.pdf"), manual(rows, listOf("c.pdf", "a.pdf", "b.pdf")))
    }

    @Test fun manualOrderPutsUnknownRowsLastInTheirOwnOrder() {
        val rows = listOf(Item("new1.pdf"), Item("a.pdf"), Item("new2.pdf"), Item("b.pdf"))
        assertEquals(listOf("b.pdf", "a.pdf", "new1.pdf", "new2.pdf"), manual(rows, listOf("b.pdf", "a.pdf")))
    }

    @Test fun manualOrderWithNothingSavedKeepsTheGivenOrder() {
        val rows = listOf(Item("z.pdf"), Item("보관", folder = true), Item("a.pdf"))
        assertEquals(listOf("보관", "z.pdf", "a.pdf"), manual(rows, emptyList()))
    }

    @Test fun mergeOrderReplacesShownRowsAndKeepsTheOthers() {
        val saved = listOf("a", "b", "c", "d")
        // 폴더 안에서 c, a만 보이다가 a, c로 바꿨다: b·d(다른 곳 것)는 그대로
        assertEquals(listOf("b", "d", "a", "c"), ListSort.mergeOrder(saved, listOf("a", "c")))
        assertEquals(listOf("x", "y"), ListSort.mergeOrder(emptyList(), listOf("x", "y")))
        assertEquals(listOf("a"), ListSort.mergeOrder(listOf("a", "a"), listOf("a", "a")))
    }

    // ================= 정렬 =================

    @Test fun sortByNameAscendingAndDescending() {
        val rows = listOf(Item("다.pdf"), Item("가.pdf"), Item("나.pdf"))
        assertEquals(listOf("가.pdf", "나.pdf", "다.pdf"), sorted(rows, SortKey.NAME, false))
        assertEquals(listOf("다.pdf", "나.pdf", "가.pdf"), sorted(rows, SortKey.NAME, true))
    }

    @Test fun nameSortUsesKoreanCollation() {
        // 자음 순서(ㄱㄴㄷ)를 따르고, 영문과 섞여도 안정적으로 한 순서를 낸다
        val rows = listOf(Item("하.pdf"), Item("가.pdf"), Item("카.pdf"), Item("나.pdf"))
        assertEquals(listOf("가.pdf", "나.pdf", "카.pdf", "하.pdf"), sorted(rows, SortKey.NAME, false))
    }

    @Test fun sortByDatesAndSize() {
        val rows = listOf(
            Item("a.pdf", opened = 3, modified = 20, size = 500),
            Item("b.pdf", opened = 1, modified = 30, size = 100),
            Item("c.pdf", opened = 2, modified = 10, size = 900),
        )
        assertEquals(listOf("b.pdf", "c.pdf", "a.pdf"), sorted(rows, SortKey.OPENED, false))
        assertEquals(listOf("a.pdf", "c.pdf", "b.pdf"), sorted(rows, SortKey.OPENED, true))
        assertEquals(listOf("c.pdf", "a.pdf", "b.pdf"), sorted(rows, SortKey.MODIFIED, false))
        assertEquals(listOf("b.pdf", "a.pdf", "c.pdf"), sorted(rows, SortKey.SIZE, false))
        assertEquals(listOf("c.pdf", "a.pdf", "b.pdf"), sorted(rows, SortKey.SIZE, true))
    }

    @Test fun equalValuesFallBackToNameOrderEvenWhenDescending() {
        val rows = listOf(Item("나.pdf", size = 5), Item("가.pdf", size = 5), Item("다.pdf", size = 5))
        // 값이 같으면 내림차순이어도 이름은 가나다순 (이름 비교는 뒤집지 않는다)
        assertEquals(listOf("가.pdf", "나.pdf", "다.pdf"), sorted(rows, SortKey.SIZE, true))
        assertEquals(listOf("가.pdf", "나.pdf", "다.pdf"), sorted(rows, SortKey.SIZE, false))
    }

    @Test fun typeSortFollowsTheGroupOrderPdfFirst() {
        val rows = listOf(Item("a.png"), Item("b.docx"), Item("c.pdf"), Item("d.hwp"), Item("e.pptx"), Item("f.txt"))
        assertEquals(listOf("c.pdf", "d.hwp", "b.docx", "e.pptx", "f.txt", "a.png"), sorted(rows, SortKey.TYPE, false))
        assertEquals(listOf("a.png", "f.txt", "e.pptx", "b.docx", "d.hwp", "c.pdf"), sorted(rows, SortKey.TYPE, true))
    }

    @Test fun unknownTypesGoAfterAllGroups() {
        val rows = listOf(Item("x.zzz"), Item("a.png"), Item("b.pdf"))
        assertEquals(listOf("b.pdf", "a.png", "x.zzz"), sorted(rows, SortKey.TYPE, false))
    }

    // ================= 폴더 =================

    @Test fun foldersComeBeforeDocumentsWhateverTheKey() {
        val rows = listOf(Item("가.pdf", size = 1), Item("폴더", folder = true), Item("나.pdf", size = 2))
        for (k in SortKey.entries) for (desc in listOf(false, true)) {
            assertEquals("$k $desc", "폴더", sorted(rows, k, desc).first())
        }
    }

    @Test fun foldersAreSortedByNameWhenTheKeyIsSizeOrType() {
        val rows = listOf(
            Item("다폴더", folder = true, size = 1), Item("가폴더", folder = true, size = 9), Item("나폴더", folder = true, size = 5),
            Item("문서.pdf"),
        )
        assertEquals(listOf("가폴더", "나폴더", "다폴더", "문서.pdf"), sorted(rows, SortKey.SIZE, true))
        assertEquals(listOf("가폴더", "나폴더", "다폴더", "문서.pdf"), sorted(rows, SortKey.TYPE, true))
    }

    @Test fun foldersAreByNameUnderTheTypeKeyEvenIfTheirFolderNamesLookLikeFiles() {
        // 폴더 이름에 확장자 비슷한 것이 붙어 있어도 '종류'로 줄 세우지 않고 이름순 (종류 순위가 이름 순서와 어긋나게 만든 경우)
        val rows = listOf(
            Item("가.png", folder = true), Item("나.pdf", folder = true), Item("다.hwp", folder = true),
        )
        assertEquals(listOf("가.png", "나.pdf", "다.hwp"), sorted(rows, SortKey.TYPE, false))
        assertEquals(listOf("가.png", "나.pdf", "다.hwp"), sorted(rows, SortKey.TYPE, true))
    }

    @Test fun foldersFollowDateKeysLikeDocuments() {
        val rows = listOf(
            Item("옛폴더", folder = true, modified = 1), Item("새폴더", folder = true, modified = 9),
        )
        assertEquals(listOf("새폴더", "옛폴더"), sorted(rows, SortKey.MODIFIED, true))
        assertEquals(listOf("옛폴더", "새폴더"), sorted(rows, SortKey.MODIFIED, false))
    }

    @Test fun listWithAStorageRowIsLeftAsIs() {
        val rows = listOf(Item("나", drive = true), Item("가", drive = true), Item("다.pdf"))
        assertSame(rows, ListSort.sort(rows, SortKey.NAME, false, collator, fields))
    }

    // ================= 기준·형식 고르기 =================

    @Test fun openedDateKeyOnlyWhereItMatters() {
        assertTrue(SortKey.OPENED in ListSort.keysFor(true))
        assertFalse(SortKey.OPENED in ListSort.keysFor(false))
        assertEquals(SortKey.entries.size, ListSort.keysFor(true).size)
        assertEquals(SortKey.entries.size - 1, ListSort.keysFor(false).size)
    }

    @Test fun savedKeyIsUsedOnlyIfKnownAndAllowed() {
        val allowed = ListSort.keysFor(false)
        assertEquals(SortKey.SIZE, ListSort.resolveKey("SIZE", allowed, SortKey.NAME))
        assertEquals(SortKey.NAME, ListSort.resolveKey(null, allowed, SortKey.NAME))
        assertEquals(SortKey.NAME, ListSort.resolveKey("모르는이름", allowed, SortKey.NAME))
        assertEquals(SortKey.MODIFIED, ListSort.resolveKey("OPENED", allowed, SortKey.MODIFIED))   // 이 탭에서 못 쓰는 기준
        assertEquals(SortKey.OPENED, ListSort.resolveKey("OPENED", ListSort.keysFor(true), SortKey.NAME))
    }

    @Test fun imagesAreLeftOutOfTheAllDocumentsTab() {
        assertFalse(DocGroup.IMAGE in ListSort.groupsFor(true))
        assertTrue(DocGroup.IMAGE in ListSort.groupsFor(false))
        assertEquals(DocGroup.entries.size - 1, ListSort.groupsFor(true).size)
    }

    @Test fun shownGroupsAreAllUnlessSavedAndOverlapping() {
        val all = DocGroup.entries.toList()
        assertEquals(all, ListSort.shownGroups(all, null))
        assertEquals(listOf(DocGroup.PDF, DocGroup.WORD), ListSort.shownGroups(all, setOf("WORD", "PDF")))
        // 저장해 둔 것이 이 탭에 없으면(예: 그림만 골라 둔 뒤 '모든 문서' 탭) 모두 보여 준다
        assertEquals(ListSort.groupsFor(true), ListSort.shownGroups(ListSort.groupsFor(true), setOf("IMAGE")))
        assertEquals(all, ListSort.shownGroups(all, emptySet()))
    }

    // ================= 이름 검색 =================

    @Test fun searchIgnoresCaseAndSurroundingSpaces() {
        val rows = listOf(Item("Report Final.pdf"), Item("보고서.hwp"), Item("memo.txt"))
        assertEquals(listOf("Report Final.pdf"), ListSort.filterByTitle(rows, "  report ", fields).map { it.title })
        assertEquals(listOf("보고서.hwp"), ListSort.filterByTitle(rows, "보고", fields).map { it.title })
        assertEquals(3, ListSort.filterByTitle(rows, "", fields).size)
        assertEquals(3, ListSort.filterByTitle(rows, "   ", fields).size)
        assertEquals(0, ListSort.filterByTitle(rows, "없음", fields).size)
    }

    // ================= 칸 수 =================

    @Test fun listViewHasOneColumnOrTwoInLandscape() {
        assertEquals(1, ListSort.spanCount(false, false, 1000, 2f))
        assertEquals(2, ListSort.spanCount(false, true, 1000, 2f))
        assertEquals(1, ListSort.spanCount(false, false, 0, 2f))       // 너비를 몰라도
    }

    @Test fun gridColumnsFitOneHundredFortyDpCells() {
        assertEquals(3, ListSort.spanCount(true, false, 0, 2f))          // 너비를 모르면 3
        assertEquals(3, ListSort.spanCount(true, false, 900, 2f))        // 900 / 280 = 3.2
        assertEquals(2, ListSort.spanCount(true, false, 500, 2f))        // 500 / 280 = 1.7 → 최소 2
        assertEquals(6, ListSort.spanCount(true, true, 1680, 2f))        // 정확히 6칸
        assertEquals(5, ListSort.spanCount(true, true, 1679, 2f))
    }

    // ================= 옛 계산과 대조 =================

    /** 옛 MainActivity.sortRows를 그대로 옮긴 기준 */
    private fun referenceSort(rows: List<Item>, key: SortKey, desc: Boolean): List<Item> {
        if (rows.any { it.drive }) return rows
        val byName = Comparator<Item> { a, b -> collator.compare(a.title, b.title) }
        fun primary(k: SortKey): Comparator<Item> = when (k) {
            SortKey.NAME -> byName
            SortKey.OPENED -> compareBy { it.opened }
            SortKey.MODIFIED -> compareBy { it.modified }
            SortKey.SIZE -> compareBy { it.size }
            SortKey.TYPE -> compareBy { DocGroup.of(DocType.ofName(it.title))?.ordinal ?: DocGroup.entries.size }
        }
        fun ordered(k: SortKey) = (if (desc) primary(k).reversed() else primary(k)).then(byName)
        val (folders, docs) = rows.partition { it.folder }
        val folderKey = if (key == SortKey.SIZE || key == SortKey.TYPE) null else key
        val sortedFolders = if (folderKey == null) folders.sortedWith(byName) else folders.sortedWith(ordered(folderKey))
        return sortedFolders + docs.sortedWith(ordered(key))
    }

    @Test fun sortMatchesTheOldCodeOnRandomLists() {
        val rnd = Random(61)
        val exts = listOf("pdf", "hwp", "docx", "pptx", "txt", "png", "zzz")
        val names = listOf("가", "나", "다", "라", "마", "ab", "Ab", "cd", "10", "9")
        repeat(3_000) {
            val n = rnd.nextInt(0, 14)
            val rows = List(n) {
                val folder = rnd.nextInt(4) == 0
                Item(
                    names[rnd.nextInt(names.size)] + rnd.nextInt(5) + (if (folder) "" else "." + exts[rnd.nextInt(exts.size)]),
                    folder = folder, opened = rnd.nextLong(0, 6), modified = rnd.nextLong(0, 6), size = rnd.nextLong(0, 6),
                )
            }
            for (k in SortKey.entries) for (desc in listOf(false, true)) {
                val ref = referenceSort(rows, k, desc)
                val got = ListSort.sort(rows, k, desc, collator, fields)
                assertEquals(ref.size, got.size)
                for (i in ref.indices) assertSame("$k $desc 칸 $i", ref[i], got[i])
            }
        }
    }
}
