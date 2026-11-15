package com.dsviewer.app

/**
 * '고른 것'을 나타내는 파랑은 이 두 가지만 쓴다 (곳마다 조금씩 다른 파랑을 직접 적지 않는다).
 * 한글 파일 표시색·펜 색 목록·자 몸통·스크랩 문제지 PDF의 파랑은 성격이 달라 여기에 넣지 않는다.
 */
object UiColors {
    /** 화면 단추·목록에서 켜진/고른 것 (res/values/colors.xml 의 active_stroke 와 같은 값) */
    const val ACCENT = 0xFF2F6DB5.toInt()

    /** 문서 위에서 고른 것 (선택 테두리·표 칸·글 선택·대칭 축·스크랩 영역). 흰 종이 위에서 또렷하게 더 선명한 파랑 */
    const val DOC_SELECT = 0xFF1E6FD9.toInt()

    /** [color]의 투명도만 [a](0..255)로 바꾼 색 */
    fun alpha(color: Int, a: Int) = (color and 0x00FFFFFF) or (a shl 24)
}
