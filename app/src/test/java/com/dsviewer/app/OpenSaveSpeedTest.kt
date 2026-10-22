package com.dsviewer.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/** 열기·저장을 빠르게 하려고 넣은 것들: 단계별 시간 기록, 복사 대신 파일 잇기, 뒤에서 쓰는 문서 기다리기 */
class OpenSaveSpeedTest {

    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("speed").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    // ================= 단계별 시간 기록 =================

    @Test fun perfLineHasStepsAndTotal() {
        var now = 1000L
        val lines = ArrayList<String>()
        val perf = Perf("열기", { now }, { lines += it })
        now += 30
        perf.step("복사")
        now += 120
        perf.step("필기읽기")
        now += 5
        perf.done("쪽=12")
        assertEquals(listOf("열기 합계=155ms 복사=30ms 필기읽기=120ms 쪽=12"), lines)
    }

    @Test fun perfWithoutStepsOrExtra() {
        var now = 0L
        val lines = ArrayList<String>()
        val perf = Perf("저장", { now }, { lines += it })
        now += 7
        perf.done()
        assertEquals(listOf("저장 합계=7ms"), lines)
    }

    // ================= 필기가 든 PDF인지 흘려 보내며 알아내기 =================

    private fun sniff(vararg chunks: String): Boolean {
        val s = InkSniffer()
        for (ch in chunks) {
            val b = ch.toByteArray(Charsets.ISO_8859_1)
            s.feed(b, b.size)
        }
        return s.found
    }

    @Test fun ssnifferFindsInkKeys() {
        assertTrue(sniff("<< /Type /Annot /DSViewerStroke (1|P|...) >>"))
        assertTrue(sniff("<< /Type /Page /DSViewerMark true >>"))
    }

    @Test fun snifferIgnoresPlainPdf() {
        assertFalse(sniff("%PDF-1.7 /DSView /DS /Viewer /DSViewe /Annots [] /DSViewe"))
    }

    @Test fun snifferFindsKeySplitAcrossChunks() {
        assertTrue(sniff("... /DSVi", "ewerStroke ..."))
        assertTrue(sniff("/", "D", "S", "V", "i", "e", "w", "e", "r"))
        assertTrue(sniff("//DSViewer"))  // 앞 글자와 겹쳐도 (// 뒤의 /DSViewer)
        assertTrue(sniff("/DSV/DSViewer"))
    }

    @Test fun snifferStaysFoundAndFileHelperWorks() {
        val s = InkSniffer()
        val b = "/DSViewer".toByteArray()
        s.feed(b, b.size)
        s.feed(ByteArray(10), 10)
        assertTrue(s.found)
        val ink = File(dir, "ink.pdf").apply { writeBytes(ByteArray(300_000) + b + ByteArray(5)) }
        val plain = File(dir, "plain.pdf").apply { writeBytes(ByteArray(300_000)) }
        assertTrue(InkSniffer.containsInk(ink))
        assertFalse(InkSniffer.containsInk(plain))
    }

    // ================= 보관함: 복사 없이 그대로 쓰는 화면용 PDF =================

    private fun doc(name: String, text: String = "문서") = File(dir, name).apply { writeText(text) }
    private fun clean(name: String, text: String) = File(dir, name).apply { writeText(text) }
    private fun cacheDir() = File(dir, "cache")
    private fun pdfCount() = cacheDir().listFiles { f -> f.name.endsWith(".pdf") }.orEmpty().size
    private fun store(doc: File, clean: File?) = InkCache.store(cacheDir(), doc, clean, emptyList(), emptySet(), emptyMap())

    @Test fun cacheStoresAndLoadsWithoutStrokes() {
        val d = doc("a.pdf")
        store(d, clean("a_clean.pdf", "화면용"))
        val hit = InkCache.load(cacheDir(), d)!!
        assertEquals("화면용", hit.clean.readText())
        assertEquals(cacheDir().canonicalFile, hit.clean.canonicalFile.parentFile)
        assertTrue(hit.strokes.isEmpty())
    }

    @Test fun cacheMissesWhenDocumentChanged() {
        val d = doc("b.pdf")
        store(d, clean("b_clean.pdf", "화면용"))
        d.writeText("다른 앱이 고친 문서, 더 길다")
        assertNull(InkCache.load(cacheDir(), d))
    }

    @Test fun cacheReusesItsOwnPdfWithoutCopying() {
        // 보관본으로 연 문서를 저장하면 화면용 PDF는 이미 보관함 안에 있으니 새로 복사하지 않고 그대로 가리킨다
        val d = doc("c.pdf")
        store(d, clean("c_clean.pdf", "화면용"))
        val hit = InkCache.load(cacheDir(), d)!!
        d.writeText("저장한 새 문서")
        store(d, hit.clean)
        assertEquals(1, pdfCount())
        val again = InkCache.load(cacheDir(), d)!!
        assertEquals(hit.clean.canonicalFile, again.clean.canonicalFile)
    }

    @Test fun cacheCopiesSameCleanOnlyOnce() {
        val d = doc("d.pdf")
        val cl = clean("d_clean.pdf", "화면용")
        store(d, cl)
        d.writeText("저장 두 번째")
        store(d, cl)
        assertEquals(1, pdfCount())
    }

    @Test fun cachePdfInUseSurvivesNewVersionAndTrim() {
        // 열려 있는 문서가 쓰는 화면용 PDF(붙잡힌 것)는 쪽을 바꿔 새 화면용 PDF를 보관해도 지워지지 않는다
        val d = doc("e.pdf")
        store(d, clean("e_clean.pdf", "옛 쪽 구성"))
        val open = InkCache.load(cacheDir(), d)!!
        d.writeText("쪽을 바꿔 저장한 문서")
        store(d, clean("e_clean2.pdf", "새 쪽 구성"))
        InkCache.trim(cacheDir(), now = System.currentTimeMillis() + 3_600_000L)
        assertTrue("열린 문서의 PDF는 남아야 한다", open.clean.isFile)
        assertEquals("옛 쪽 구성", open.clean.readText())
        assertEquals("새 쪽 구성", InkCache.load(cacheDir(), d)!!.clean.readText())
    }

    @Test fun trimRemovesOldOrphanPdfsButNotFreshOnes() {
        val d = doc("f.pdf")
        store(d, clean("f_clean.pdf", "화면용"))
        val orphan = File(cacheDir(), "zz-orphan-nobody.pdf").apply { writeText("짝 없음") }
        val tmp = File(cacheDir(), "zz.ink.tmp").apply { writeText("임시") }
        InkCache.trim(cacheDir())  // 방금 만든 것은 정보 파일이 곧 생길 수 있어 둔다
        assertTrue(orphan.isFile && tmp.isFile)
        InkCache.trim(cacheDir(), now = System.currentTimeMillis() + 3_600_000L)
        assertFalse(orphan.exists() || tmp.exists())
        assertNotNull(InkCache.load(cacheDir(), d))
    }

    @Test fun cacheKeepsOnlyRecentEntries() {
        val docs = (1..10).map { n -> doc("many$n.pdf", "문서$n").also { store(it, clean("many${n}_clean.pdf", "화면용$n")) ; Thread.sleep(5) } }
        InkCache.trim(cacheDir(), now = System.currentTimeMillis() + 3_600_000L)
        val hits = docs.count { InkCache.load(cacheDir(), it) != null }
        assertEquals(8, hits)
        assertNull(InkCache.load(cacheDir(), docs[0]))
    }

    @Test fun forgetDropsTheEntry() {
        val d = doc("g.pdf")
        store(d, clean("g_clean.pdf", "화면용"))
        InkCache.forget(cacheDir(), d)
        assertNull(InkCache.load(cacheDir(), d))
    }

    @Test fun missingCleanForgetsOldEntry() {
        val d = doc("h.pdf")
        store(d, clean("h_clean.pdf", "화면용"))
        store(d, null)
        assertNull(InkCache.load(cacheDir(), d))
    }

    // ================= 뒤에서 쓰는 문서 기다리기 =================

    @Test fun awaitReturnsAtOnceWhenNothingIsWritten() = runBlocking {
        val pending = PendingWrites()
        withTimeout(1000) { pending.await("file:///a.pdf") }
        assertFalse(pending.isPending("file:///a.pdf"))
    }

    @Test fun awaitWaitsUntilTheWriteEnds() = runBlocking {
        val pending = PendingWrites()
        val gate = CompletableDeferred<Unit>()
        val order = ArrayList<String>()
        val write = launch(Dispatchers.Default, start = CoroutineStart.LAZY) {
            gate.await()
            order += "쓰기끝"
        }
        pending.add("k", write)
        write.start()
        assertTrue(pending.isPending("k"))
        val opener = async(Dispatchers.Default) {
            pending.await("k")
            order += "열기"
        }
        delay(50)
        assertTrue("쓰는 동안은 열면 안 된다", order.isEmpty())
        gate.complete(Unit)
        withTimeout(2000) { opener.await() }
        assertEquals(listOf("쓰기끝", "열기"), order)
        assertFalse(pending.isPending("k"))
    }

    @Test fun otherDocumentsAreNotHeldBack() = runBlocking {
        val pending = PendingWrites()
        val never = CompletableDeferred<Unit>()
        val write = launch(Dispatchers.Default) { never.await() }
        pending.add("a", write)
        withTimeout(1000) { pending.await("b") }
        assertFalse(pending.isPending("b"))
        write.cancel()
    }

    @Test fun awaitFollowsAWriteThatStartsMeanwhile() = runBlocking {
        val pending = PendingWrites()
        val firstGate = CompletableDeferred<Unit>()
        val secondGate = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) { firstGate.await() }
        pending.add("k", first)
        val done = ArrayList<String>()
        val opener = async(Dispatchers.Default) {
            pending.await("k")
            done += "열기"
        }
        delay(30)
        // 첫 쓰기가 끝나기 전에 같은 문서를 또 쓰기 시작했다
        val second = launch(Dispatchers.Default) { secondGate.await() }
        pending.add("k", second)
        firstGate.complete(Unit)
        delay(50)
        assertTrue("둘째 쓰기가 끝나야 연다", done.isEmpty())
        secondGate.complete(Unit)
        withTimeout(2000) { opener.await() }
        assertEquals(listOf("열기"), done)
    }

    @Test fun removeKeepsALaterWrite() = runBlocking {
        val pending = PendingWrites()
        val gate = CompletableDeferred<Unit>()
        val first = launch(Dispatchers.Default) { }
        val second = launch(Dispatchers.Default) { gate.await() }
        pending.add("k", first)
        pending.add("k", second)
        pending.remove("k", first)  // 첫 일이 끝나며 자기 것을 지우려 해도 뒤에 시작한 일은 남는다
        assertTrue(pending.isPending("k"))
        gate.complete(Unit)
        second.join()
        pending.remove("k", second)
        assertFalse(pending.isPending("k"))
    }
}
