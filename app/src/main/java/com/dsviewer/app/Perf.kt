package com.dsviewer.app

import android.util.Log

/** 지금 시각(ms). 어느 시각부터인지는 상관없고 차이만 쓴다 */
internal fun perfNowMs(): Long = System.nanoTime() / 1_000_000

/**
 * 열기·저장이 단계별로 얼마나 걸리는지 logcat(태그 `DSperf`)에 남긴다.
 * 기기에서 `adb logcat -s DSperf`로 보면 어디서 시간이 가는지 숫자로 알 수 있다.
 * 단계마다 `step("이름")`을 부르면 직전 단계 이후 걸린 시간(ms)이 쌓이고, `done()`에서 합계와 함께 한 줄로 찍는다
 */
internal class Perf(
    private val what: String,
    private val clock: () -> Long = ::perfNowMs,
    private val sink: (String) -> Unit = { Log.d(TAG, it) },
) {
    private val start = clock()
    private var last = start
    private val steps = StringBuilder()

    /** 직전 단계 이후 걸린 시간을 [name] 단계로 적는다 */
    fun step(name: String) {
        val now = clock()
        steps.append(' ').append(name).append('=').append(now - last).append("ms")
        last = now
    }

    /** 합계와 단계별 시간을 한 줄로 찍는다 */
    fun done(extra: String = "") {
        sink("$what 합계=${clock() - start}ms$steps${if (extra.isEmpty()) "" else " $extra"}")
    }

    companion object {
        const val TAG = "DSperf"
    }
}
