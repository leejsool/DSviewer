package com.dsviewer.app

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 키보드·페달 키 → 쪽 넘기기 방향 */
class PageKeysTest {
    private fun dir(code: Int, shift: Boolean = false, mod: Boolean = false) = PageKeys.direction(code, shift, mod)

    @Test fun arrowsAndPageKeysTurnPages() {
        assertEquals(PageKeys.NEXT, dir(KeyEvent.KEYCODE_DPAD_RIGHT))
        assertEquals(PageKeys.NEXT, dir(KeyEvent.KEYCODE_DPAD_DOWN))
        assertEquals(PageKeys.NEXT, dir(KeyEvent.KEYCODE_PAGE_DOWN))
        assertEquals(PageKeys.PREV, dir(KeyEvent.KEYCODE_DPAD_LEFT))
        assertEquals(PageKeys.PREV, dir(KeyEvent.KEYCODE_DPAD_UP))
        assertEquals(PageKeys.PREV, dir(KeyEvent.KEYCODE_PAGE_UP))
    }

    @Test fun spaceGoesNextAndShiftSpaceGoesBack() {
        assertEquals(PageKeys.NEXT, dir(KeyEvent.KEYCODE_SPACE))
        assertEquals(PageKeys.PREV, dir(KeyEvent.KEYCODE_SPACE, shift = true))
    }

    @Test fun otherPedalKeys() {
        // 페달 제품마다 보내는 키가 달라서 흔한 것들을 받는다
        assertEquals(PageKeys.NEXT, dir(KeyEvent.KEYCODE_ENTER))
        assertEquals(PageKeys.NEXT, dir(KeyEvent.KEYCODE_NUMPAD_ENTER))
        assertEquals(PageKeys.PREV, dir(KeyEvent.KEYCODE_DEL))
        assertEquals(PageKeys.NEXT, dir(KeyEvent.KEYCODE_MEDIA_NEXT))
        assertEquals(PageKeys.PREV, dir(KeyEvent.KEYCODE_MEDIA_PREVIOUS))
        assertEquals(PageKeys.NEXT, dir(KeyEvent.KEYCODE_MEDIA_FAST_FORWARD))
        assertEquals(PageKeys.PREV, dir(KeyEvent.KEYCODE_MEDIA_REWIND))
    }

    @Test fun shortcutsWithModifiersAreLeftAlone() {
        assertNull(dir(KeyEvent.KEYCODE_DPAD_RIGHT, mod = true))
        assertNull(dir(KeyEvent.KEYCODE_SPACE, mod = true))
        assertNull(dir(KeyEvent.KEYCODE_PAGE_DOWN, shift = true, mod = true))
    }

    @Test fun otherKeysAreIgnored() {
        assertNull(dir(KeyEvent.KEYCODE_A))
        assertNull(dir(KeyEvent.KEYCODE_VOLUME_UP))   // 음량 키는 일부러 건드리지 않는다
        assertNull(dir(KeyEvent.KEYCODE_VOLUME_DOWN))
        assertNull(dir(KeyEvent.KEYCODE_BACK))
        assertNull(dir(KeyEvent.KEYCODE_ESCAPE))
    }
}
