package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 쓰기 / 읽기(필기 보임) / 읽기(필기 숨김) */
class ViewModeTest {
    @Test fun buttonCyclesThroughThreeModesAndBack() {
        assertEquals(ViewMode.READ, ViewMode.WRITE.next)
        assertEquals(ViewMode.READ_HIDDEN, ViewMode.READ.next)
        assertEquals(ViewMode.WRITE, ViewMode.READ_HIDDEN.next)
        assertEquals(ViewMode.WRITE, ViewMode.WRITE.next.next.next)
    }

    @Test fun writeModeNeverHidesInk() {
        // 안 보이는 채로 쓰는 일이 없게
        assertFalse(ViewMode.WRITE.readOnly)
        assertFalse(ViewMode.WRITE.inkHidden)
    }

    @Test fun hiddenInkOnlyInReadModes() {
        for (m in ViewMode.values()) if (m.inkHidden) assertTrue(m.readOnly)
        assertTrue(ViewMode.READ.readOnly && !ViewMode.READ.inkHidden)
        assertTrue(ViewMode.READ_HIDDEN.readOnly && ViewMode.READ_HIDDEN.inkHidden)
    }

    @Test fun openModeDefaultsToWriteAndKeepsRememberedReadMode() {
        assertEquals(OpenMode.WRITE, OpenMode.of(null))
        assertEquals(ViewMode.WRITE, ViewMode.startMode(null, OpenMode.WRITE))
        assertEquals(ViewMode.WRITE, ViewMode.startMode(ViewMode.WRITE, OpenMode.WRITE))
        assertEquals(ViewMode.READ, ViewMode.startMode(ViewMode.READ, OpenMode.WRITE))           // 지난번 읽기 모드는 그대로
        assertEquals(ViewMode.READ_HIDDEN, ViewMode.startMode(ViewMode.READ_HIDDEN, OpenMode.READ))
    }

    @Test fun presentOpenModeStartsInWriteMode() {
        assertEquals(ViewMode.WRITE, ViewMode.startMode(null, OpenMode.PRESENT))
        assertEquals(ViewMode.WRITE, ViewMode.startMode(ViewMode.READ_HIDDEN, OpenMode.PRESENT))
    }

    @Test fun readOpenModeStartsReadingEvenIfLastClosedWriting() {
        assertEquals(ViewMode.READ, ViewMode.startMode(null, OpenMode.READ))
        assertEquals(ViewMode.READ, ViewMode.startMode(ViewMode.WRITE, OpenMode.READ))
    }
}
