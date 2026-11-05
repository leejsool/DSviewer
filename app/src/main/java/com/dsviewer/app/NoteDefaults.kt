package com.dsviewer.app

/** 새 노트를 만들 때 처음 골라 두는 값들 (화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다) */
internal object NoteDefaults {

    /** 탐색기 ⋮ ▸ 옵션 ▸ 새 노트 ▸ 방향 기본값 */
    enum class Orient(val label: String, val hint: String) {
        AUTO("화면 방향에 맞춤", "기기를 세로로 들고 있으면 세로 노트, 가로로 들고 있으면 가로 노트로 고릅니다."),
        PORTRAIT("세로", "늘 세로(A4 세로) 노트로 고릅니다."),
        LANDSCAPE("가로", "늘 가로(A4 가로) 노트로 고릅니다."),
    }

    /** 저장해 둔 이름으로 방향 기본값을 찾는다 (없거나 모르는 이름이면 화면 방향에 맞춤) */
    fun orientOf(name: String?): Orient = Orient.entries.firstOrNull { it.name == name } ?: Orient.AUTO

    /** 방향의 기본: 지금 기기 화면이 세로로 길면(세로 모드) 세로 문서, 가로로 길면(가로 모드) 가로 문서 */
    fun portrait(screenWidthPx: Int, screenHeightPx: Int): Boolean = screenHeightPx >= screenWidthPx

    /** 옵션에서 고른 방향 기본값 [orient]에 따라 새 노트 창에 처음 골라 둘 방향 (세로면 true) */
    fun portrait(orient: Orient, screenWidthPx: Int, screenHeightPx: Int): Boolean = when (orient) {
        Orient.AUTO -> portrait(screenWidthPx, screenHeightPx)
        Orient.PORTRAIT -> true
        Orient.LANDSCAPE -> false
    }
}
