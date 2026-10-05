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

    /** 읽기 단추를 한 번 눌렀을 때 */
    val next: ViewMode
        get() = when (this) {
            WRITE -> READ
            READ -> READ_HIDDEN
            READ_HIDDEN -> WRITE
        }
}
