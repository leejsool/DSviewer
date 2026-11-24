package com.dsviewer.app

/**
 * 저절로 하는 업데이트 확인의 때 (화면과 상관없는 계산만 모아 단위 테스트한다).
 *
 * - 앱을 켤 때 지난 확인에서 일주일이 지났으면 조용히 확인한다. 새 버전이 있을 때만 알린다.
 * - 알렸는데 업데이트하지 않고 닫으면 2주일 동안 알리지 않는다 (그동안은 확인도 하지 않는다).
 * - 인터넷이 안 돼 확인하지 못했으면 하루 뒤에 다시 해 본다.
 * - 기기 시계가 뒤로 갔으면 (지난 확인이 앞날로 보이면) 기다리지 않고 확인한다.
 */
object UpdateSchedule {
    const val DAY = 24 * 60 * 60 * 1000L
    const val CHECK_EVERY = 7 * DAY
    const val SNOOZE = 14 * DAY
    const val RETRY = DAY

    fun shouldCheck(now: Long, lastCheck: Long, snoozeUntil: Long): Boolean {
        // 미룬 기간 안이면 확인하지 않는다 (시계가 뒤로 가서 남은 기간이 2주보다 길어 보이면 미룬 것을 버린다)
        if (now < snoozeUntil && snoozeUntil - now <= SNOOZE) return false
        if (lastCheck <= 0L || now < lastCheck) return true
        return now - lastCheck >= CHECK_EVERY
    }

    /** 확인에 실패했을 때 '지난 확인' 자리에 적을 값: 하루 뒤에 다시 확인하게 된다 */
    fun lastCheckAfterFailure(now: Long): Long = now - CHECK_EVERY + RETRY

    /** 알렸는데 업데이트하지 않았을 때: 이때까지 알리지 않는다 */
    fun snoozeUntil(now: Long): Long = now + SNOOZE
}
