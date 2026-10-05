package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 시험·풀이 타이머 */
class StudyTimerTest {
    private val sec = 1000L
    private val min = 60_000L
    private fun countdown(minutes: Int, laps: Boolean = true) =
        StudyTimer(TimerMode.COUNTDOWN, minutes * min, laps).also { it.start(1000L) }

    private val t0 = 1000L

    // ================= 시간 흐름 =================

    @Test fun countdownShrinksAndStopwatchGrows() {
        val c = countdown(60)
        assertEquals(60 * min - 5 * sec, c.remaining(t0 + 5 * sec))
        val s = StudyTimer(TimerMode.STOPWATCH).also { it.start(t0) }
        assertEquals(5 * sec, s.elapsed(t0 + 5 * sec))
        assertEquals(5 * sec, s.shown(t0 + 5 * sec))
    }

    @Test fun notStartedDoesNotRun() {
        val s = StudyTimer(TimerMode.STOPWATCH)
        assertFalse(s.isRunning)
        assertEquals(0L, s.elapsed(99_999))
        s.resume(5)  // 시작 전에는 이어 가기도 안 된다
        assertFalse(s.isRunning)
    }

    @Test fun pauseFreezesAndResumeContinues() {
        val s = StudyTimer(TimerMode.STOPWATCH).also { it.start(t0) }
        s.pause(t0 + 10 * sec)
        assertEquals(10 * sec, s.elapsed(t0 + 500 * sec))  // 멈춰 있는 동안은 안 흐른다
        s.resume(t0 + 100 * sec)
        assertEquals(15 * sec, s.elapsed(t0 + 105 * sec))
    }

    @Test fun toggleFlips() {
        val s = StudyTimer(TimerMode.STOPWATCH).also { it.start(t0) }
        s.toggle(t0 + sec)
        assertFalse(s.isRunning)
        s.toggle(t0 + 2 * sec)
        assertTrue(s.isRunning)
    }

    @Test fun clockGoingBackwardsDoesNotShrinkTime() {
        val s = StudyTimer(TimerMode.STOPWATCH).also { it.start(t0) }
        assertEquals(0L, s.elapsed(t0 - 5 * sec))
        s.pause(t0 - 5 * sec)
        assertEquals(0L, s.elapsed(t0))
    }

    @Test fun countdownTargetHasFloor() {
        assertEquals(StudyTimer.MIN_TARGET_MS, StudyTimer(TimerMode.COUNTDOWN, 0).targetMs)
        assertEquals(0L, StudyTimer(TimerMode.STOPWATCH, 99 * min).targetMs)
    }

    // ================= 문제별 기록 =================

    @Test fun lapsRecordTimePerProblem() {
        val s = StudyTimer(TimerMode.STOPWATCH).also { it.start(t0) }
        assertEquals(1, s.currentProblem)
        assertEquals(30 * sec, s.lap(t0 + 30 * sec))
        assertEquals(2, s.currentProblem)
        assertEquals(45 * sec, s.lap(t0 + 75 * sec))
        assertEquals(listOf(30 * sec, 45 * sec), s.laps)
        assertEquals(10 * sec, s.currentLap(t0 + 85 * sec))
    }

    @Test fun pausedTimeIsNotCountedInLaps() {
        val s = StudyTimer(TimerMode.STOPWATCH).also { it.start(t0) }
        s.pause(t0 + 20 * sec)
        s.resume(t0 + 120 * sec)
        assertEquals(30 * sec, s.lap(t0 + 130 * sec))
    }

    @Test fun lapNeedsRunningAndTracking() {
        val s = StudyTimer(TimerMode.STOPWATCH).also { it.start(t0) }
        s.pause(t0 + sec)
        assertNull(s.lap(t0 + 2 * sec))
        val off = StudyTimer(TimerMode.STOPWATCH, trackLaps = false).also { it.start(t0) }
        assertNull(off.lap(t0 + 2 * sec))
        assertTrue(off.laps.isEmpty())
    }

    @Test fun undoLapMergesTimeBackIntoCurrentProblem() {
        val s = StudyTimer(TimerMode.STOPWATCH).also { it.start(t0) }
        s.lap(t0 + 30 * sec)
        s.lap(t0 + 40 * sec)
        assertTrue(s.undoLap())
        assertEquals(listOf(30 * sec), s.laps)
        assertEquals(20 * sec, s.currentLap(t0 + 50 * sec))
        assertTrue(s.undoLap())
        assertFalse(s.undoLap())
        assertEquals(50 * sec, s.currentLap(t0 + 50 * sec))
    }

    // ================= 알림 =================

    @Test fun noticesComeOnceInOrder() {
        val c = countdown(60)
        assertNull(c.pollNotice(t0 + 49 * min))
        assertEquals(TimerNotice.TEN_MINUTES, c.pollNotice(t0 + 50 * min))
        assertNull(c.pollNotice(t0 + 51 * min))
        assertEquals(TimerNotice.ONE_MINUTE, c.pollNotice(t0 + 59 * min))
        assertNull(c.pollNotice(t0 + 59 * min + 30 * sec))
        assertEquals(TimerNotice.TIME_UP, c.pollNotice(t0 + 60 * min))
        assertNull(c.pollNotice(t0 + 61 * min))
    }

    @Test fun skippedNoticesCollapseToMostUrgent() {
        // 앱이 뒤에 가 있는 사이 시간이 다 지났다 → 시간 종료 하나만, 지난 알림은 다시 안 나온다
        val c = countdown(60)
        assertEquals(TimerNotice.TIME_UP, c.pollNotice(t0 + 70 * min))
        assertNull(c.pollNotice(t0 + 71 * min))
    }

    @Test fun shortExamsSkipTenMinuteNotice() {
        val c = countdown(15)
        assertNull(c.pollNotice(t0 + 6 * min))
        assertEquals(TimerNotice.ONE_MINUTE, c.pollNotice(t0 + 14 * min))
        val tiny = countdown(3)
        assertNull(tiny.pollNotice(t0 + 2 * min + 30 * sec))
        assertEquals(TimerNotice.TIME_UP, tiny.pollNotice(t0 + 3 * min))
    }

    @Test fun noNoticesWhenPausedOrStopwatch() {
        val c = countdown(60)
        c.pause(t0 + sec)
        assertNull(c.pollNotice(t0 + 500 * min))
        val s = StudyTimer(TimerMode.STOPWATCH).also { it.start(t0) }
        assertNull(s.pollNotice(t0 + 500 * min))
    }

    @Test fun extendReArmsNotices() {
        val c = countdown(60)
        assertEquals(TimerNotice.TIME_UP, c.pollNotice(t0 + 60 * min))
        c.extend(15 * min, t0 + 60 * min)
        assertEquals(15 * min, c.remaining(t0 + 60 * min))
        assertEquals(TimerNotice.ONE_MINUTE, c.pollNotice(t0 + 74 * min))
        assertEquals(TimerNotice.TIME_UP, c.pollNotice(t0 + 75 * min))
    }

    @Test fun extendIgnoredForStopwatch() {
        val s = StudyTimer(TimerMode.STOPWATCH)
        s.extend(5 * min, 0)
        assertEquals(0L, s.targetMs)
    }

    // ================= 색 =================

    @Test fun urgencyByRemainingTime() {
        val c = countdown(60)  // 주황은 남은 10분부터
        assertEquals(TimerUrgency.NORMAL, c.urgency(t0 + 49 * min))
        assertEquals(TimerUrgency.WARN, c.urgency(t0 + 50 * min))
        assertEquals(TimerUrgency.DANGER, c.urgency(t0 + 59 * min))
        assertEquals(TimerUrgency.OVER, c.urgency(t0 + 60 * min))
        val short = countdown(10)  // 짧은 시험은 전체의 5분의 1 (2분)부터
        assertEquals(TimerUrgency.NORMAL, short.urgency(t0 + 7 * min))
        assertEquals(TimerUrgency.WARN, short.urgency(t0 + 8 * min))
        assertEquals(TimerUrgency.NORMAL, StudyTimer(TimerMode.STOPWATCH).urgency(0))
    }

    // ================= 글자 =================

    @Test fun clockFormats() {
        assertEquals("00:00", TimerFormat.clock(0, true))
        assertEquals("05:09", TimerFormat.clock(5 * min + 9 * sec, false))
        assertEquals("1:05:09", TimerFormat.clock(65 * min + 9 * sec, false))
        assertEquals("59:59", TimerFormat.clock(59 * min + 59 * sec + 999, false))
    }

    @Test fun countdownRoundsUpSoLastSecondShowsOne() {
        assertEquals("00:01", TimerFormat.clock(1, true))
        assertEquals("00:01", TimerFormat.clock(1000, true))
        assertEquals("00:02", TimerFormat.clock(1001, true))
        assertEquals("1:00:00", TimerFormat.clock(60 * min, true))
    }

    @Test fun overtimeShowsPlusSign() {
        assertEquals("00:00", TimerFormat.clock(-500, true))
        assertEquals("+00:01", TimerFormat.clock(-1500, true))
        assertEquals("+1:00:05", TimerFormat.clock(-(60 * min + 5 * sec), true))
    }

    @Test fun lapSummary() {
        assertNull(TimerFormat.lapSummary(emptyList()))
        val s = TimerFormat.lapSummary(listOf(60 * sec, 180 * sec, 30 * sec))!!
        assertEquals(270 * sec, s.totalMs)
        assertEquals(90 * sec, s.averageMs)
        assertEquals(2, s.longestProblem)
        assertEquals(3, s.shortestProblem)
    }

    @Test fun reportListsLaps() {
        val text = TimerFormat.report(TimerMode.COUNTDOWN, 5 * min, 60 * min, listOf(60 * sec, 240 * sec))
        assertTrue(text.contains("걸린 시간 05:00 / 주어진 시간 1:00:00"))
        assertTrue(text.contains("1번  01:00"))
        assertTrue(text.contains("2번  04:00"))
        assertTrue(text.contains("평균 02:30 · 가장 오래 걸린 2번 04:00"))
        assertFalse(TimerFormat.report(TimerMode.STOPWATCH, 5 * min, 0, emptyList()).contains("평균"))
    }

    // ================= 입력 =================

    @Test fun parseMinutes() {
        assertEquals(90, StudyTimer.parseMinutes(" 90 "))
        assertEquals(1, StudyTimer.parseMinutes("0"))
        assertEquals(StudyTimer.MAX_MINUTES, StudyTimer.parseMinutes("99999"))
        assertNull(StudyTimer.parseMinutes(""))
        assertNull(StudyTimer.parseMinutes("1시간"))
    }

    // ================= 저장·복원 =================

    @Test fun serializeRoundTrip() {
        val c = countdown(60)
        c.lap(t0 + 40 * sec)
        c.lap(t0 + 100 * sec)
        c.pollNotice(t0 + 55 * min)  // 10분 알림 소진
        c.pause(t0 + 56 * min)
        val back = StudyTimer.parse(c.serialize())!!
        assertEquals(TimerMode.COUNTDOWN, back.mode)
        assertEquals(c.targetMs, back.targetMs)
        assertEquals(c.laps, back.laps)
        assertEquals(c.elapsed(t0 + 99 * min), back.elapsed(t0 + 99 * min))
        assertFalse(back.isRunning)
        assertEquals(c.currentLap(t0 + 99 * min), back.currentLap(t0 + 99 * min))
        // 이미 알린 10분 알림은 복원해도 다시 안 나온다
        back.resume(t0 + 99 * min)
        assertEquals(TimerNotice.ONE_MINUTE, back.pollNotice(t0 + 99 * min + 3 * min + 30 * sec))
    }

    @Test fun runningTimerKeepsRunningAfterRestore() {
        val s = StudyTimer(TimerMode.STOPWATCH).also { it.start(t0) }
        val back = StudyTimer.parse(s.serialize())!!
        assertTrue(back.isRunning)
        assertEquals(60 * sec, back.elapsed(t0 + 60 * sec))
    }

    @Test fun parseRejectsGarbage() {
        assertNull(StudyTimer.parse(null))
        assertNull(StudyTimer.parse(""))
        assertNull(StudyTimer.parse("v2;STOPWATCH;0;0;0;1;1;0;0;"))
        assertNull(StudyTimer.parse("v1;NOPE;0;0;0;1;1;0;0;"))
        assertNull(StudyTimer.parse("v1;STOPWATCH;x;0;0;1;1;0;0;"))
        assertNull(StudyTimer.parse("v1;STOPWATCH;0;0;0;1;1;0;0;1,x"))
        assertNotNull(StudyTimer.parse("v1;STOPWATCH;0;0;0;1;1;0;0;"))
    }
}
