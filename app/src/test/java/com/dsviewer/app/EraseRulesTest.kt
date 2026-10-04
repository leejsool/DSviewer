package com.dsviewer.app

import com.dsviewer.app.EraseRules.FillDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 지우개가 어느 획을 건드릴지 정하는 규칙.
 * 규칙을 문서 화면에서 떼어 내기 전의 반복문(아래 [reference])을 그대로 옮겨 두고, 무작위 입력에서 새 규칙과
 * 결과(지운 획·결정·불린 순서)가 같은지 맞춰 보는 시험도 한다.
 */
class EraseRulesTest {

    /** 시험용 획: 성질만 가진 가짜. [calls]에 닿음·가림을 몇 번 물었는지 센다 */
    private class Fake(
        val name: String,
        val box: Boolean = false, val note: Boolean = false, val tape: Boolean = false, val fill: Boolean = false,
        val hl: Boolean = false, val revealed: Boolean = false, val covers: Boolean = false, val hit: Boolean = true,
    ) {
        var hitAsked = 0
        var coverAsked = 0
        override fun toString() = name
    }

    private fun target(f: Fake) = EraseRules.Target(
        id = f, isBox = f.box, isNote = f.note, isTape = f.tape, isFill = f.fill, isHighlighter = f.hl, revealed = f.revealed,
        coversPoint = { f.coverAsked++; f.covers }, hit = { f.hitAsked++; f.hit },
    )

    /** 지운 획들을 순서대로 기록하는 지우개. [fails]에 든 획은 '바뀐 것이 없다(false)'로 돌려준다 */
    private class Recorder(private val fails: Set<Any> = emptySet()) : EraseRules.Eraser {
        val erased = ArrayList<Any>()
        override fun erase(id: Any): Boolean {
            if (id in fails) return false
            erased.add(id)
            return true
        }
    }

    private val normal = EraseRules.Filter(tapesOnly = false, fillsOnly = false, highlighterOnly = false)

    private fun run(list: List<Fake>, f: EraseRules.Filter = normal, dec: FillDecision = FillDecision.UNDECIDED, rec: Recorder = Recorder()) =
        EraseRules.apply(list.map(::target), f, dec, rec) to rec

    // ================= 건너뛰기 =================

    @Test fun boxesAndNotesAreNeverErased() {
        val img = Fake("그림", box = true); val note = Fake("메모", note = true); val pen = Fake("펜")
        val (r, rec) = run(listOf(img, note, pen))
        assertEquals(listOf<Any>(pen), rec.erased)
        assertTrue(r.removed)
        assertEquals(0, img.hitAsked); assertEquals(0, note.hitAsked)   // 그림·메모는 닿았는지도 묻지 않는다
    }

    @Test fun tapeEraserOnlyTouchesTapes() {
        val pen = Fake("펜"); val tape = Fake("테이프", tape = true); val fill = Fake("채우기", fill = true)
        val (_, rec) = run(listOf(pen, tape, fill), EraseRules.Filter(tapesOnly = true, fillsOnly = false, highlighterOnly = false))
        assertEquals(listOf<Any>(tape), rec.erased)
        assertEquals(0, pen.hitAsked)
    }

    @Test fun fillEraserOnlyTouchesFills() {
        val pen = Fake("펜"); val tape = Fake("테이프", tape = true); val fill = Fake("채우기", fill = true)
        val (_, rec) = run(listOf(pen, tape, fill), EraseRules.Filter(tapesOnly = false, fillsOnly = true, highlighterOnly = false))
        assertEquals(listOf<Any>(fill), rec.erased)
    }

    @Test fun revealedTapeIsTransparentToAnEverydayEraser() {
        val pen = Fake("펜"); val shown = Fake("보이는 테이프", tape = true, revealed = true, covers = true)
        val (_, rec) = run(listOf(pen, shown))
        assertEquals(listOf<Any>(pen), rec.erased)    // 보이게 한 테이프는 지우지 않고, 아래 펜도 가리지 않는다
        // 그러나 테이프 지우개는 보이게 한 테이프도 지운다
        val (_, rec2) = run(listOf(shown), EraseRules.Filter(tapesOnly = true, fillsOnly = false, highlighterOnly = false))
        assertEquals(listOf<Any>(shown), rec2.erased)
    }

    // ================= 가린 테이프 =================

    @Test fun coveringTapeShieldsWhatIsBelowIt() {
        val below = Fake("아래 펜"); val tape = Fake("가린 테이프", tape = true, covers = true); val above = Fake("위 펜")
        val (_, rec) = run(listOf(below, tape, above))
        // 위에서부터: 위 펜, 가린 테이프(지움) → 그 아래는 보이지 않으므로 멈춘다
        assertEquals(listOf<Any>(above, tape), rec.erased)
        assertEquals(0, below.hitAsked)
    }

    @Test fun aTapeNotUnderThePointDoesNotShield() {
        val below = Fake("아래 펜"); val tape = Fake("옆 테이프", tape = true, covers = false, hit = false)
        val (_, rec) = run(listOf(below, tape))
        assertEquals(listOf<Any>(below), rec.erased)
    }

    @Test fun tapeThatFailsToErodeStillShieldsOnlyIfItWasErased() {
        // 테이프를 지우지 못했으면(바뀐 것 없음) 가림 처리를 하지 않고 계속 아래를 본다
        val below = Fake("아래 펜"); val tape = Fake("테이프", tape = true, covers = true)
        val (_, rec) = run(listOf(below, tape), rec = Recorder(fails = setOf(tape)))
        assertEquals(listOf<Any>(below), rec.erased)
    }

    // ================= 형광펜만 =================

    @Test fun highlighterOnlyLeavesOtherInkAlone() {
        val pen = Fake("펜"); val hl = Fake("형광펜", hl = true)
        val (_, rec) = run(listOf(pen, hl), EraseRules.Filter(false, false, highlighterOnly = true))
        assertEquals(listOf<Any>(hl), rec.erased)
        assertEquals(0, pen.hitAsked)
    }

    @Test fun highlighterOnlyStillRespectsACoveringTape() {
        val hl = Fake("형광펜", hl = true); val tape = Fake("가린 테이프", tape = true, covers = true, hit = false)
        val (_, rec) = run(listOf(hl, tape), EraseRules.Filter(false, false, highlighterOnly = true))
        assertTrue(rec.erased.isEmpty())   // 테이프는 형광펜이 아니라 지우지 않지만 그 아래 형광펜은 가린다
        assertEquals(0, hl.hitAsked)
    }

    // ================= 채우기 =================

    @Test fun fillIsKeptWhenALineWasHitToo() {
        val fill = Fake("채우기", fill = true); val line = Fake("선")
        val (r, rec) = run(listOf(fill, line))
        assertEquals(listOf<Any>(line), rec.erased)
        assertEquals(FillDecision.KEEP, r.decision)
    }

    @Test fun fillAloneIsErasedAndRemembered() {
        val fill = Fake("채우기", fill = true)
        val (r, rec) = run(listOf(fill))
        assertEquals(listOf<Any>(fill), rec.erased)
        assertEquals(FillDecision.ERASE, r.decision)
        assertTrue(r.removed)
    }

    @Test fun anEarlierKeepDecisionKeepsFillsOnLaterTouchesAsWell() {
        // 이 지우개 획에서 이미 'KEEP'이 정해졌으면 선 없이 채우기에만 닿아도 지우지 않는다
        val fill = Fake("채우기", fill = true)
        val (r, rec) = run(listOf(fill), dec = FillDecision.KEEP)
        assertTrue(rec.erased.isEmpty())
        assertFalse(r.removed)
        assertEquals(FillDecision.KEEP, r.decision)
    }

    @Test fun anEarlierEraseDecisionErasesFillsRightAway() {
        // 이미 ERASE로 정해졌으면 채우기를 모아 두었다 지우지 않고 훑는 도중 바로 지운다 (선보다 먼저/나중 순서대로)
        val line = Fake("선"); val fill = Fake("채우기", fill = true)
        val (_, rec) = run(listOf(line, fill), dec = FillDecision.ERASE)
        assertEquals(listOf<Any>(fill, line), rec.erased)
    }

    @Test fun fillEraserErasesFillsEvenWhenLinesAreAround() {
        val fill = Fake("채우기", fill = true); val line = Fake("선")
        val (r, rec) = run(listOf(fill, line), EraseRules.Filter(false, fillsOnly = true, highlighterOnly = false))
        assertEquals(listOf<Any>(fill), rec.erased)
        assertEquals(FillDecision.UNDECIDED, r.decision)    // 채우기 지우개는 결정을 건드리지 않는다
    }

    @Test fun missedStrokesAreNotErased() {
        val a = Fake("멀리 있는 펜", hit = false); val b = Fake("닿는 펜")
        val (r, rec) = run(listOf(a, b))
        assertEquals(listOf<Any>(b), rec.erased)
        assertTrue(r.removed)
    }

    @Test fun nothingToErase() {
        val (r, rec) = run(listOf(Fake("먼 펜", hit = false)))
        assertFalse(r.removed)
        assertTrue(rec.erased.isEmpty())
        assertEquals(FillDecision.UNDECIDED, r.decision)
    }

    // ================= 닿았는지 묻는 횟수 =================

    @Test fun hitIsAskedAtMostOncePerStroke() {
        val list = List(20) { Fake("펜$it") }
        run(list)
        assertTrue(list.all { it.hitAsked == 1 })
    }

    // ================= 옛 반복문과 같은지 =================

    /** 규칙을 떼어 내기 전의 반복문 그대로 (DocumentView.eraseAt) */
    private fun reference(list: List<Fake>, f: EraseRules.Filter, start: FillDecision, fails: Set<Any>): Triple<List<Any>, FillDecision, Boolean> {
        val erased = ArrayList<Any>()
        fun eraseOne(st: Fake): Boolean {
            if (st in fails) return false
            erased.add(st)
            return true
        }
        var eraseFills: Boolean? = when (start) { FillDecision.UNDECIDED -> null; FillDecision.ERASE -> true; FillDecision.KEEP -> false }
        val fillHits = ArrayList<Fake>()
        var hitOther = false
        var covered = false
        var removed = false
        for (k in list.indices.reversed()) {
            val st = list[k]
            if (st.box || st.note) continue
            val isTape = st.tape
            val isFill = st.fill
            if (f.fillsOnly) {
                if (!isFill) continue
            } else if (f.tapesOnly) {
                if (!isTape) continue
            } else if (isTape && st.revealed) continue
            if (covered) break
            val coversHere = isTape && !st.revealed && st.covers
            if (!f.tapesOnly && !f.fillsOnly && f.highlighterOnly && !st.hl) {
                if (coversHere) covered = true
                continue
            }
            if (!st.hit) continue
            if (isFill && !f.fillsOnly && eraseFills != true) {
                fillHits.add(st)
                continue
            }
            if (!isFill) hitOther = true
            if (!eraseOne(st)) continue
            if (coversHere) covered = true
            removed = true
        }
        if (!f.fillsOnly && eraseFills == null) {
            if (hitOther) eraseFills = false else if (fillHits.isNotEmpty()) eraseFills = true
        }
        if (eraseFills == true) for (st in fillHits) {
            if (eraseOne(st)) removed = true
        }
        val dec = when (eraseFills) { null -> FillDecision.UNDECIDED; true -> FillDecision.ERASE; else -> FillDecision.KEEP }
        return Triple(erased, dec, removed)
    }

    @Test fun matchesTheOldLoopOnRandomInputs() {
        val rnd = Random(77)
        repeat(20_000) { n ->
            val size = rnd.nextInt(0, 9)
            val list = List(size) { i ->
                val kind = rnd.nextInt(6)
                Fake(
                    "s$i",
                    box = kind == 0 && rnd.nextInt(3) == 0, note = kind == 1 && rnd.nextInt(3) == 0,
                    tape = kind == 2 || kind == 3, fill = kind == 4,
                    hl = rnd.nextInt(3) == 0, revealed = rnd.nextBoolean(), covers = rnd.nextBoolean(), hit = rnd.nextInt(4) != 0,
                )
            }
            val f = EraseRules.Filter(rnd.nextInt(5) == 0, rnd.nextInt(5) == 0, rnd.nextInt(4) == 0)
            val start = FillDecision.entries[rnd.nextInt(3)]
            val fails = list.filter { rnd.nextInt(5) == 0 }.toSet<Any>()
            val (refErased, refDec, refRemoved) = reference(list, f, start, fails)
            val (r, rec) = run(list, f, start, Recorder(fails))
            assertEquals("#$n 지운 획과 순서", refErased, rec.erased)
            assertEquals("#$n 채우기 결정", refDec, r.decision)
            assertEquals("#$n 바뀐 것이 있었는지", refRemoved, r.removed)
        }
    }
}
