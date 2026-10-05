package com.dsviewer.app

import android.view.KeyEvent

/**
 * 키보드·블루투스 페이지 터너 페달로 쪽을 넘기는 키. 페달은 대부분 키보드처럼 ←/→ 나 PageUp/PageDown, Space 같은 키를 보낸다.
 * 키 → 넘길 방향: 다음 쪽 [NEXT], 이전 쪽 [PREV], 해당 없음 null.
 */
object PageKeys {
    const val NEXT = 1
    const val PREV = -1

    /**
     * [keyCode] 키를 눌렀을 때 넘길 방향. [shift]가 눌렸으면 Space는 이전 쪽(PDF 뷰어들과 같게).
     * Ctrl·Alt·Meta 같은 [otherModifier]가 같이 눌렸으면 단축키일 수 있어 건드리지 않는다
     */
    fun direction(keyCode: Int, shift: Boolean = false, otherModifier: Boolean = false): Int? {
        if (otherModifier) return null
        return when (keyCode) {
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_PAGE_DOWN,
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER,
            KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> NEXT
            KeyEvent.KEYCODE_SPACE -> if (shift) PREV else NEXT
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_PAGE_UP,
            KeyEvent.KEYCODE_DEL,
            KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_REWIND -> PREV
            else -> null
        }
    }
}
