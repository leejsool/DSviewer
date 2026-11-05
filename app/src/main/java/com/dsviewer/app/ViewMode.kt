package com.dsviewer.app

/**
 * 문서를 보는 방식. 읽기 단추를 누를 때마다 쓰기 → 읽기(필기 보임) → 읽기(필기 숨김) → 쓰기로 돈다.
 * 필기를 숨기는 것은 읽기에서만 가능하다 (안 보이는 채로 쓰는 일이 없게).
 */
enum class ViewMode(
    /** 필기·지우기 없이 넘겨 보기만 한다 (툴바도 숨김) */
    val readOnly: Boolean,
    /** 필기·그림·글 상자·포스트잇·오답 배지·테이프를 가리고 원래 문서만 보인다 */
    val inkHidden: Boolean,
) {
    WRITE(false, false),
    READ(true, false),
    READ_HIDDEN(true, true);

    companion object {
        /**
         * 문서를 열 때의 보기 방식: 지난번에 닫을 때 방식([saved], 없으면 쓰기)을 따르되,
         * 탐색기 옵션에서 '읽기 모드로 열기'([open] = [OpenMode.READ])를 골랐으면 쓰기 대신 읽기로 연다 (필기 숨김은 그대로)
         */
        fun startMode(saved: ViewMode?, open: OpenMode): ViewMode = when {
            // 발표는 쓰기 모드에서 시작한다 (발표 중에도 펜으로 쓸 수 있게)
            open == OpenMode.PRESENT -> WRITE
            saved != null && saved.readOnly -> saved
            open == OpenMode.READ -> READ
            else -> WRITE
        }
    }

    /** 읽기 단추를 한 번 눌렀을 때 */
    val next: ViewMode
        get() = when (this) {
            WRITE -> READ
            READ -> READ_HIDDEN
            READ_HIDDEN -> WRITE
        }
}

/** 탐색기 ⋮ ▸ 옵션 ▸ 문서 열기: 문서를 처음에 어떤 모드로 열지 (기본은 필기 모드) */
enum class OpenMode(val label: String, val hint: String) {
    WRITE("필기 모드로 열기", "문서를 열면 바로 펜으로 쓸 수 있습니다. 지난번에 읽기 모드로 보다 닫은 문서는 읽기 모드로 열립니다."),
    READ("읽기 모드(뷰어)로 열기", "문서를 열면 필기 없이 넘겨 보기만 합니다. 휴대폰처럼 필기가 불편할 때 좋습니다. 쓰려면 위의 읽기 모드 단추를 누르세요."),
    PRESENT("발표 모드로 열기", "문서를 열면 바로 발표를 시작합니다: 전체 화면에서 한 쪽씩 넘기고 펜은 레이저 포인터입니다. 오른쪽 위 단추나 뒤로 가기로 발표를 끝냅니다. (새 노트는 필기 모드로 엽니다)");

    companion object {
        /** 탐색기 설정에 적어 두는 이름표 (탐색기의 'browser' 설정 파일) */
        const val KEY = "openMode"
        const val PREFS = "browser"

        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: WRITE

        fun current(ctx: android.content.Context) =
            of(ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(KEY, null))
    }
}
