package com.dsviewer.app

/** 새 노트를 만들 때 처음 골라 두는 값들 (화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다) */
internal object NoteDefaults {

    /** 방향의 기본: 지금 기기 화면이 세로로 길면(세로 모드) 세로 문서, 가로로 길면(가로 모드) 가로 문서 */
    fun portrait(screenWidthPx: Int, screenHeightPx: Int): Boolean = screenHeightPx >= screenWidthPx
}
