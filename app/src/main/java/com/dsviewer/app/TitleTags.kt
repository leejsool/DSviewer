package com.dsviewer.app

/**
 * 문서 제목들에서 자주 같이 나오는 낱말을 뽑아 '# 태그'로 보여 주기 위한 계산.
 * 최근 파일·즐겨찾기 첫 화면에서 태그 하나를 누르면 그 낱말이 제목에 든 문서만 모아 본다.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 */
internal object TitleTags {
    /** 태그로 보여 줄 낱말의 최대 수 */
    const val MAX_TAGS = 10
    /** 문서 몇 개 이상의 제목에 나와야 '공통' 낱말로 치는지 */
    const val MIN_DOCS = 2

    /** 뜻이 없는 낱말: 확장자·복사본 표시·'학년도' 같은 흔한 말 */
    private val STOP = setOf(
        "pdf", "hwp", "hwpx", "doc", "docx", "ppt", "pptx", "txt", "png", "jpg", "jpeg", "copy", "final",
        "복사본", "최종", "학년도", "학년", "학기",
    )

    private enum class Kind { HANGUL, LATIN, DIGIT, OTHER }

    private fun kindOf(c: Char) = when (c) {
        in '가'..'힣', in 'ㄱ'..'ㅣ' -> Kind.HANGUL
        in 'a'..'z', in 'A'..'Z' -> Kind.LATIN
        in '0'..'9' -> Kind.DIGIT
        else -> Kind.OTHER
    }

    /** 제목 끝의 확장자(점 뒤 영문·숫자 5자 이하)를 뗀다 */
    private fun withoutExtension(title: String): String {
        val dot = title.lastIndexOf('.')
        if (dot <= 0) return title
        val ext = title.substring(dot + 1)
        return if (ext.length in 1..5 && ext.all { kindOf(it) == Kind.LATIN || kindOf(it) == Kind.DIGIT }) title.substring(0, dot) else title
    }

    /**
     * 제목 하나의 낱말들 (겹치지 않게). 글자 종류가 바뀌는 곳과 기호·공백에서 끊는다: "2026학년도 부장회의10-12" → 2026, 부장회의.
     * 두 글자 이상의 한글·영문, 그리고 연도처럼 보이는 네 자리 숫자만 남긴다 (영문은 소문자로)
     */
    fun words(title: String): Set<String> {
        val out = LinkedHashSet<String>()
        val cur = StringBuilder()
        var curKind = Kind.OTHER
        fun flush() {
            if (cur.isNotEmpty()) {
                val w = cur.toString()
                val ok = when (curKind) {
                    Kind.DIGIT -> w.length == 4 && (w.startsWith("19") || w.startsWith("20"))
                    Kind.HANGUL, Kind.LATIN -> w.length >= 2
                    Kind.OTHER -> false
                }
                if (ok && w !in STOP) out += w
            }
            cur.clear()
        }
        for (c in withoutExtension(title).lowercase()) {
            val k = kindOf(c)
            if (k != curKind) flush()
            curKind = k
            if (k != Kind.OTHER) cur.append(c)
        }
        flush()
        return out
    }

    /**
     * [titles](가까운 때에 연 것부터)에서 둘 이상의 제목에 나오는 낱말을 많이 나온 순으로 [max]개까지.
     * 같은 횟수면 더 최근 문서에 나온 것, 그다음은 긴 낱말이 앞선다. 제목이 하나뿐이면 비어 있다
     */
    fun extract(titles: List<String>, max: Int = MAX_TAGS): List<String> {
        val count = HashMap<String, Int>()
        val firstSeen = HashMap<String, Int>()
        titles.forEachIndexed { i, t ->
            for (w in words(t)) {
                count[w] = (count[w] ?: 0) + 1
                firstSeen.putIfAbsent(w, i)
            }
        }
        return count.filter { it.value >= MIN_DOCS }.keys
            .sortedWith(
                compareByDescending<String> { count.getValue(it) }
                    .thenBy { firstSeen.getValue(it) }
                    .thenByDescending { it.length }
                    .thenBy { it }
            )
            .take(max)
    }

    /** [title]에 태그 [tag]가 들어 있는가 (영문 대소문자는 가리지 않는다) */
    fun matches(title: String, tag: String) = title.contains(tag, ignoreCase = true)

    /**
     * 태그를 골랐을 때의 차례: 골라 둔 태그([tags])가 제목에 하나라도 든 문서([isDoc])를 맨 위로, **맞는 태그가 많은 문서부터**
     * (맞는 개수가 같으면 원래 차례대로). 나머지(안 맞는 문서와 폴더 같은 문서 아닌 줄)는 그 아래에 원래 차례대로.
     * 돌려주는 것은 (맞는 문서들, 나머지)
     */
    fun <T> rank(rows: List<T>, tags: Collection<String>, title: (T) -> String, isDoc: (T) -> Boolean): Pair<List<T>, List<T>> {
        val counts = rows.map { r -> if (isDoc(r)) tags.count { matches(title(r), it) } else 0 }
        // sortedByDescending은 안정 정렬이라 같은 개수끼리는 원래 차례가 남는다
        val hit = rows.indices.filter { counts[it] > 0 }.sortedByDescending { counts[it] }.map { rows[it] }
        val rest = rows.indices.filter { counts[it] == 0 }.map { rows[it] }
        return hit to rest
    }
}
