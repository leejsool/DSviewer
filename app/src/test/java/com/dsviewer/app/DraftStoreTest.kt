package com.dsviewer.app

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 자동 저장 보관소와 저장 시점 계산 */
class DraftStoreTest {

    private lateinit var dir: File
    private lateinit var store: DraftStore

    @Before fun setUp() {
        dir = Files.createTempDirectory("drafts").toFile()
        store = DraftStore(File(dir, "drafts"))
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun meta(id: String, time: Long = 1000L, name: String = "한글 이름.pdf", uri: String = "content://x/$id") =
        DraftMeta(id, uri, name, "HWP", isNewNote = false, canOverwrite = true, time = time)

    /** [id]의 임시 PDF를 [text] 내용으로 쓰고 자동 저장본으로 확정 */
    private fun commit(m: DraftMeta, text: String = "pdf"): Boolean {
        val tmp = store.tmp(m.id)
        tmp.writeText(text)
        return store.commit(m, tmp)
    }

    // ================= 보관소 =================

    @Test fun commitThenReadBack() {
        val m = meta("a", time = 123L).copy(isNewNote = true, canOverwrite = false)
        assertTrue(commit(m, "내용"))
        assertEquals(m, store.meta("a"))
        assertEquals("내용", store.pdf("a").readText())
        assertFalse(store.tmp("a").exists())
    }

    @Test fun recommitReplacesOldOne() {
        commit(meta("a", time = 1), "옛것")
        commit(meta("a", time = 2), "새것")
        assertEquals("새것", store.pdf("a").readText())
        assertEquals(2L, store.meta("a")?.time)
        assertEquals(1, store.list().size)
    }

    @Test fun missingDraftHasNoMeta() {
        assertNull(store.meta("nope"))
    }

    @Test fun metaWithoutPdfIsIgnored() {
        commit(meta("a"))
        store.pdf("a").delete()
        assertNull(store.meta("a"))
        assertTrue(store.list().isEmpty())
    }

    @Test fun corruptMetaIsIgnored() {
        commit(meta("a"))
        File(dir, "drafts/b.meta").writeBytes(byteArrayOf(0, 1, 2))  // 정보가 깨진 자동 저장본
        File(dir, "drafts/b.pdf").writeText("x")
        assertNull(store.meta("b"))
        // 깨진 것 때문에 멀쩡한 것까지 목록에서 사라지지는 않는다
        assertEquals(listOf("a"), store.list().map { it.id })
    }

    @Test fun listIsNewestFirst() {
        commit(meta("a", time = 10))
        commit(meta("b", time = 30))
        commit(meta("c", time = 20))
        assertEquals(listOf("b", "c", "a"), store.list().map { it.id })
    }

    @Test fun deleteRemovesEverythingOfThatDraft() {
        commit(meta("a"))
        store.tmp("a").writeText("쓰다 만 것")
        store.delete("a")
        assertNull(store.meta("a"))
        assertFalse(store.pdf("a").exists())
        assertFalse(store.tmp("a").exists())
    }

    @Test fun idIsStableAndDistinct() {
        val a = store.idOf("content://docs/1")
        assertEquals(a, store.idOf("content://docs/1"))
        assertNotEquals(a, store.idOf("content://docs/2"))
        assertTrue(a.matches(Regex("[0-9a-f]{20}")))
        // 한글 주소도 파일 이름으로 안전하다
        assertTrue(store.idOf("file:///sdcard/문서/시험.pdf").matches(Regex("[0-9a-f]{20}")))
    }

    @Test fun failedCommitLeavesOldDraftAndCleansTemp() {
        commit(meta("a"), "옛것")
        val missing = File(dir, "없는 파일")
        assertFalse(store.commit(meta("a", time = 9), missing))
        assertEquals("옛것", store.pdf("a").readText())
        assertEquals(1000L, store.meta("a")?.time)
    }

    @Test fun cleanupRemovesOldOrphansAndLeftoverTemps() {
        commit(meta("old", time = 100))
        commit(meta("new", time = 5000))
        store.tmp("half").writeText("쓰다 만 것")        // 쓰다 죽은 임시 파일
        File(dir, "drafts/lonely.pdf").writeText("x")      // 정보 없는 PDF
        store.cleanup(oldestTime = 1000)
        assertEquals(listOf("new"), store.list().map { it.id })
        assertFalse(store.tmp("half").exists())
        assertFalse(File(dir, "drafts/lonely.pdf").exists())
        assertFalse(store.pdf("old").exists())
        assertTrue(store.pdf("new").exists())
    }

    @Test fun cleanupOnMissingFolderIsFine() {
        DraftStore(File(dir, "없음")).cleanup(0)
    }

    // ================= 저장 시점 =================

    private fun delay(now: Long, dirtySince: Long, lastChange: Long, lastSaveEnd: Long = 0, cost: Long = 0) =
        AutoSavePolicy.delay(now, dirtySince, lastChange, lastSaveEnd, cost)

    @Test fun firstSaveWaitsForIdle() {
        // 막 고쳤으면 5초 뒤
        assertEquals(AutoSavePolicy.IDLE_MS, delay(now = 1000, dirtySince = 1000, lastChange = 1000))
        // 3초 쉬었으면 2초 더
        assertEquals(2000L, delay(now = 4000, dirtySince = 1000, lastChange = 1000))
        // 6초 쉬었으면 이미 지났다
        assertTrue(delay(now = 7000, dirtySince = 1000, lastChange = 1000) <= 0)
    }

    @Test fun keepsWritingStillSavesWithinOneMinute() {
        // 1초마다 계속 고치는 중: 마지막 변경 + 5초는 계속 밀리지만 처음 변경 + 60초가 상한
        val dirtySince = 0L
        assertEquals(AutoSavePolicy.MAX_STALE_MS - 59_000L, delay(now = 59_000, dirtySince, lastChange = 59_000))
        assertEquals(0L, delay(now = AutoSavePolicy.MAX_STALE_MS, dirtySince, lastChange = AutoSavePolicy.MAX_STALE_MS))
    }

    @Test fun respectsMinimumGapAfterLastSave() {
        // 방금 저장이 끝났고(1000) 곧바로 또 고쳤어도 20초는 쉰다
        val d = delay(now = 2000, dirtySince = 2000, lastChange = 2000, lastSaveEnd = 1000, cost = 100)
        assertEquals(1000 + AutoSavePolicy.MIN_GAP_MS - 2000, d)
    }

    @Test fun slowSaveStretchesTheGap() {
        // 저장에 8초 걸린 큰 문서: 80초 쉰다
        assertEquals(80_000L, AutoSavePolicy.gap(8_000))
        assertEquals(AutoSavePolicy.MIN_GAP_MS, AutoSavePolicy.gap(100))
        // 너무 오래 걸려도 10분을 넘기지 않는다
        assertEquals(600_000L, AutoSavePolicy.gap(10 * 60_000L))
    }

    @Test fun gapNeverAppliesBeforeFirstSave() {
        assertTrue(delay(now = 10_000, dirtySince = 0, lastChange = 0, lastSaveEnd = 0) <= 0)
    }
}
