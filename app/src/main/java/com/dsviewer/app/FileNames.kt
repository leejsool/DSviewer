package com.dsviewer.app

/**
 * 파일 이름 다루기: 파일 이름에 못 쓰는 글자 바꾸기, 이름과 확장자 나누기. 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 */
internal object FileNames {

    /** 파일 이름에 쓸 수 없는 글자 (\ / : * ? " < > |) */
    private val FORBIDDEN = Regex("[\\\\/:*?\"<>|]")

    /** 못 쓰는 글자를 '_'로 바꾼다 */
    fun safe(name: String): String = name.replace(FORBIDDEN, "_")

    /** 확장자를 뺀 이름. 맨 앞의 점은 확장자로 보지 않는다 (".hidden"은 그대로) */
    fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }

    /** 확장자 (점 포함). 없으면 빈 글자 */
    fun extension(name: String): String = name.substring(baseName(name).length)

    /**
     * 이름 바꾸기 창에서 입력한 글 [typed]을 새 기본 이름으로: 앞뒤 공백을 빼고 못 쓰는 글자를 바꾼다.
     * 비었거나 원래 이름([oldBase])과 같으면 null (바꿀 것이 없다)
     */
    fun renamedBase(typed: String, oldBase: String): String? {
        val name = safe(typed.trim())
        return if (name.isEmpty() || name == oldBase) null else name
    }
}
