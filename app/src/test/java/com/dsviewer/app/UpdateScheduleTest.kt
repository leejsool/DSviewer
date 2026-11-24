package com.dsviewer.app

import com.dsviewer.app.UpdateSchedule.DAY
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 저절로 하는 업데이트 확인의 때 */
class UpdateScheduleTest {
    private val t0 = 1_800_000_000_000L

    @Test fun firstLaunchChecksRightAway() {
        assertTrue(UpdateSchedule.shouldCheck(t0, lastCheck = 0L, snoozeUntil = 0L))
    }

    @Test fun checksOnceAWeek() {
        assertFalse(UpdateSchedule.shouldCheck(t0 + 6 * DAY, lastCheck = t0, snoozeUntil = 0L))
        assertTrue(UpdateSchedule.shouldCheck(t0 + 7 * DAY, lastCheck = t0, snoozeUntil = 0L))
    }

    @Test fun declinedUpdateIsQuietForTwoWeeks() {
        // 확인해서 알렸고, 사용자가 '나중에'로 닫았다
        val snooze = UpdateSchedule.snoozeUntil(t0)
        assertFalse(UpdateSchedule.shouldCheck(t0 + 7 * DAY, lastCheck = t0, snoozeUntil = snooze))
        assertFalse(UpdateSchedule.shouldCheck(t0 + 13 * DAY, lastCheck = t0, snoozeUntil = snooze))
        assertTrue(UpdateSchedule.shouldCheck(t0 + 14 * DAY, lastCheck = t0, snoozeUntil = snooze))
    }

    @Test fun failedCheckRetriesNextDay() {
        val last = UpdateSchedule.lastCheckAfterFailure(t0)
        assertFalse(UpdateSchedule.shouldCheck(t0 + DAY / 2, lastCheck = last, snoozeUntil = 0L))
        assertTrue(UpdateSchedule.shouldCheck(t0 + DAY, lastCheck = last, snoozeUntil = 0L))
    }

    @Test fun clockMovedBackDoesNotBlockForever() {
        // 지난 확인이 앞날로 보이면 바로 확인
        assertTrue(UpdateSchedule.shouldCheck(t0, lastCheck = t0 + 30 * DAY, snoozeUntil = 0L))
        // 미룬 기간이 2주보다 길게 남아 보이면 미룬 것을 버린다
        assertTrue(UpdateSchedule.shouldCheck(t0, lastCheck = 0L, snoozeUntil = t0 + 100 * DAY))
    }
}
