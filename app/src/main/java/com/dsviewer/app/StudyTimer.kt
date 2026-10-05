package com.dsviewer.app

import java.util.Locale

enum class TimerMode { COUNTDOWN, STOPWATCH }

/** 카운트다운이 끝나 가는 정도 (칩 색깔) */
enum class TimerUrgency { NORMAL, WARN, DANGER, OVER }

/** 시간이 흘러 알려 줄 때가 된 것. 한 번씩만 나온다 */
enum class TimerNotice { TEN_MINUTES, ONE_MINUTE, TIME_UP }

/**
 * 시험·풀이 타이머 하나의 상태. 시계는 안 쥐고 시각(ms)을 받는 쪽에서 넣어 준다 (시험하기 쉽게).
 * - [TimerMode.COUNTDOWN]: [targetMs]에서 줄어든다. 0이 지나도 멈추지 않고 초과 시간이 늘어난다.
 * - [TimerMode.STOPWATCH]: 0에서 늘어난다 (풀이 시간 재기).
 * - [lap]: 문제 하나가 끝날 때마다 눌러 문제별 시간을 적는다 ([trackLaps]가 켜진 때).
 */
class StudyTimer(
    val mode: TimerMode,
    targetMs: Long = 0L,
    val trackLaps: Boolean = true,
) {
    /** 시작 때 정한 시간 + 연장한 시간 (카운트다운만 뜻이 있다) */
    var targetMs: Long = if (mode == TimerMode.COUNTDOWN) targetMs.coerceAtLeast(MIN_TARGET_MS) else 0L
        private set

    /** 멈춰 있는 동안 쌓아 둔 흐른 시간 */
    private var accumulated = 0L

    /** 달리기 시작한 시각 (0 = 멈춤·아직 시작 전) */
    private var runningSince = 0L
    var started = false
        private set

    /** 문제별로 걸린 시간 (ms). 번호는 1부터 */
    val laps: List<Long> get() = lapList
    private val lapList = ArrayList<Long>()

    /** 마지막 문제 끊은 때의 흐른 시간 */
    private var lapBase = 0L

    /** 이미 알린 것 (복원할 때도 다시 울리지 않게) */
    private var tenMinSent = false
    private var oneMinSent = false
    private var timeUpSent = false

    val isRunning get() = runningSince != 0L

    fun start(now: Long) {
        if (started) return
        started = true
        runningSince = now
    }

    fun pause(now: Long) {
        if (!isRunning) return
        accumulated += (now - runningSince).coerceAtLeast(0)
        runningSince = 0L
    }

    fun resume(now: Long) {
        if (!started || isRunning) return
        runningSince = now
    }

    fun toggle(now: Long) = if (isRunning) pause(now) else resume(now)

    /** 지금까지 흐른 시간 */
    fun elapsed(now: Long): Long =
        accumulated + if (isRunning) (now - runningSince).coerceAtLeast(0) else 0L

    /** 남은 시간 (카운트다운, 시간이 지났으면 음수) */
    fun remaining(now: Long): Long = targetMs - elapsed(now)

    /** 화면에 크게 보일 시간: 카운트다운은 남은 시간, 풀이는 흐른 시간 */
    fun shown(now: Long): Long = if (mode == TimerMode.COUNTDOWN) remaining(now) else elapsed(now)

    /** 현재 문제에 쓴 시간 */
    fun currentLap(now: Long): Long = (elapsed(now) - lapBase).coerceAtLeast(0)

    /** 지금 풀고 있는 문제 번호 (1부터) */
    val currentProblem get() = lapList.size + 1

    /** 문제 하나를 끝낸다. 달리는 중에만 받는다. 걸린 시간을 돌려주고, 못 받으면 null */
    fun lap(now: Long): Long? {
        if (!trackLaps || !isRunning) return null
        val e = elapsed(now)
        val t = (e - lapBase).coerceAtLeast(0)
        lapList.add(t)
        lapBase = e
        return t
    }

    /** 마지막으로 끊은 문제를 되돌린다 (잘못 눌렀을 때). 그 시간은 다시 현재 문제에 합쳐진다 */
    fun undoLap(): Boolean {
        val last = lapList.removeLastOrNull() ?: return false
        lapBase -= last
        return true
    }

    /** 시간을 늘린다 (시험 연장). 늘린 뒤에 남은 시간이 다시 줄어들면 알림도 다시 나간다 */
    fun extend(ms: Long, now: Long) {
        if (mode != TimerMode.COUNTDOWN || ms <= 0) return
        targetMs += ms
        val rest = remaining(now)
        if (rest > TEN_MIN_MS) tenMinSent = false
        if (rest > ONE_MIN_MS) oneMinSent = false
        if (rest > 0) timeUpSent = false
    }

    fun urgency(now: Long): TimerUrgency {
        if (mode != TimerMode.COUNTDOWN) return TimerUrgency.NORMAL
        val rest = remaining(now)
        if (rest <= 0) return TimerUrgency.OVER
        if (rest <= ONE_MIN_MS) return TimerUrgency.DANGER
        return if (rest <= warnAt()) TimerUrgency.WARN else TimerUrgency.NORMAL
    }

    /** '주황'이 되는 남은 시간: 전체의 5분의 1 (최대 10분, 최소 1분) */
    private fun warnAt() = (targetMs / 5).coerceIn(ONE_MIN_MS, TEN_MIN_MS)

    /**
     * 지금 알릴 것. 달리는 중인 카운트다운에서만 나오고, 한 번 알린 것은 다시 안 나온다.
     * 앱이 뒤에 있는 동안 여러 개를 건너뛰었으면 가장 급한 것 하나만 돌려주고 나머지는 지나간 것으로 친다.
     * 10분 알림은 시간이 20분 넘게 잡힌 시험에서만, 1분 알림은 3분 넘는 시험에서만.
     */
    fun pollNotice(now: Long): TimerNotice? {
        if (mode != TimerMode.COUNTDOWN || !isRunning) return null
        val rest = remaining(now)
        var found: TimerNotice? = null
        if (!tenMinSent && targetMs > 2 * TEN_MIN_MS && rest <= TEN_MIN_MS) {
            tenMinSent = true
            found = TimerNotice.TEN_MINUTES
        }
        if (!oneMinSent && targetMs > 3 * ONE_MIN_MS && rest <= ONE_MIN_MS) {
            oneMinSent = true
            tenMinSent = true
            found = TimerNotice.ONE_MINUTE
        }
        if (!timeUpSent && rest <= 0) {
            timeUpSent = true
            oneMinSent = true
            tenMinSent = true
            found = TimerNotice.TIME_UP
        }
        return found
    }

    // ================= 저장·복원 (앱이 죽었다 살아나도 이어서) =================

    /** 한 줄 글로. 시각은 시스템 시각이라 앱을 다시 켜도 이어진다 */
    fun serialize(): String = listOf(
        "v1", mode.name, targetMs, accumulated, runningSince, if (started) 1 else 0, if (trackLaps) 1 else 0,
        lapBase, flags(), lapList.joinToString(","),
    ).joinToString(";")

    private fun flags() = (if (tenMinSent) 1 else 0) or (if (oneMinSent) 2 else 0) or (if (timeUpSent) 4 else 0)

    companion object {
        const val MIN_TARGET_MS = 60_000L
        const val TEN_MIN_MS = 10 * 60_000L
        const val ONE_MIN_MS = 60_000L

        /** 시험 시간으로 받는 분 범위 */
        const val MIN_MINUTES = 1
        const val MAX_MINUTES = 600

        /** 바로 고르는 시험 시간(분) */
        val PRESET_MINUTES = listOf(30, 45, 60, 90, 120)

        /** 직접 친 분 → 1~600분 안의 값, 숫자가 아니면 null */
        fun parseMinutes(text: String): Int? {
            val n = text.trim().toIntOrNull() ?: return null
            return n.coerceIn(MIN_MINUTES, MAX_MINUTES)
        }

        /** [serialize]로 만든 글을 되살린다. 깨졌으면 null */
        fun parse(text: String?): StudyTimer? {
            val p = text?.split(';') ?: return null
            if (p.size != 10 || p[0] != "v1") return null
            return try {
                val mode = TimerMode.valueOf(p[1])
                val target = p[2].toLong()
                val t = StudyTimer(mode, target, p[6] == "1")
                t.accumulated = p[3].toLong().coerceAtLeast(0)
                t.runningSince = p[4].toLong().coerceAtLeast(0)
                t.started = p[5] == "1"
                t.lapBase = p[7].toLong().coerceAtLeast(0)
                val f = p[8].toInt()
                t.tenMinSent = f and 1 != 0
                t.oneMinSent = f and 2 != 0
                t.timeUpSent = f and 4 != 0
                if (p[9].isNotEmpty()) p[9].split(',').forEach { t.lapList.add(it.toLong().coerceAtLeast(0)) }
                t
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

/** 타이머 화면에 쓰는 글 만들기 */
object TimerFormat {
    /**
     * 시계 글자. 한 시간 넘으면 '1:05:09', 아니면 '05:09'.
     * 남은 시간(카운트다운)이 양수면 올림해서 마지막 1초가 '00:01'로 보이다가 0이 되는 순간 '00:00'이 되고,
     * 그 뒤 초과 시간은 '+00:12'처럼 보인다. 흐른 시간(풀이)은 내림.
     */
    fun clock(ms: Long, countdown: Boolean): String {
        val secs = when {
            ms > 0 && countdown -> (ms + 999) / 1000
            ms >= 0 -> ms / 1000
            else -> (-ms) / 1000
        }
        val sign = if (ms < 0 && secs > 0) "+" else ""
        val h = secs / 3600
        val m = secs % 3600 / 60
        val s = secs % 60
        return if (h > 0) String.format(Locale.ROOT, "%s%d:%02d:%02d", sign, h, m, s) else String.format(Locale.ROOT, "%s%02d:%02d", sign, m, s)
    }

    /** 문제별 기록 요약: 평균과 가장 오래·짧게 걸린 문제 (기록 없으면 null) */
    fun lapSummary(laps: List<Long>): LapSummary? {
        if (laps.isEmpty()) return null
        var longest = 0
        var shortest = 0
        for (i in laps.indices) {
            if (laps[i] > laps[longest]) longest = i
            if (laps[i] < laps[shortest]) shortest = i
        }
        return LapSummary(laps.sum(), laps.sum() / laps.size, longest + 1, shortest + 1)
    }

    /** 복사·공유용 글 */
    fun report(mode: TimerMode, total: Long, targetMs: Long, laps: List<Long>): String {
        val sb = StringBuilder()
        sb.append(if (mode == TimerMode.COUNTDOWN) "시험 타이머" else "풀이 시간").append('\n')
        sb.append("걸린 시간 ").append(clock(total, false))
        if (mode == TimerMode.COUNTDOWN) sb.append(" / 주어진 시간 ").append(clock(targetMs, false))
        sb.append('\n')
        for ((i, t) in laps.withIndex()) sb.append("${i + 1}번  ").append(clock(t, false)).append('\n')
        lapSummary(laps)?.let {
            sb.append("평균 ").append(clock(it.averageMs, false))
                .append(" · 가장 오래 걸린 ${it.longestProblem}번 ").append(clock(laps[it.longestProblem - 1], false)).append('\n')
        }
        return sb.toString().trimEnd()
    }
}

class LapSummary(val totalMs: Long, val averageMs: Long, val longestProblem: Int, val shortestProblem: Int)
