package com.dsviewer.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 새 노트 방향의 기본값: 기기를 세운 방향을 따른다 */
class NoteDefaultsTest {

    @Test fun portraitDeviceDefaultsToPortraitNote() {
        assertTrue(NoteDefaults.portrait(1440, 2304))
        assertTrue(NoteDefaults.portrait(1080, 2400))
    }

    @Test fun landscapeDeviceDefaultsToLandscapeNote() {
        assertFalse(NoteDefaults.portrait(2304, 1440))
        assertFalse(NoteDefaults.portrait(2400, 1080))
    }

    @Test fun squareScreenCountsAsPortrait() {
        assertTrue(NoteDefaults.portrait(1000, 1000))
    }
}
