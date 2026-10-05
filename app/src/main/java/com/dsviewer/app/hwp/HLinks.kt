package com.dsviewer.app.hwp

/**
 * 한글 문서의 하이퍼링크 (HWP·HWPX 공통). 순수 계산이라 JVM 단위 시험으로 확인한다.
 * 한글은 링크를 '필드'로 둔다: 필드 시작([HCtrl.FieldBegin]) ~ 필드 끝([HCtrl.FieldEnd]) 사이 글자가 링크 글자.
 */
object HLinks {

    private val SCHEME = Regex("^(https?|ftp|mailto|tel):", RegexOption.IGNORE_CASE)

    /**
     * 한글 하이퍼링크 명령 ("http\://example.com;1;0;0;")에서 웹 주소를 꺼낸다.
     * 명령 안의 '\'는 다음 글자를 그대로 쓰는 표시이고 ';'가 칸을 나눈다. 웹·메일·전화 주소만 돌려주고
     * (www로 시작하면 http://를 붙인다), 책갈피·내 컴퓨터의 파일처럼 PDF 밖에서 쓸 수 없는 것은 null
     */
    fun parseCommand(command: String?): String? {
        if (command == null) return null
        val sb = StringBuilder()
        var i = 0
        while (i < command.length) {
            val c = command[i]
            if (c == '\\' && i + 1 < command.length) {
                sb.append(command[i + 1])
                i += 2
                continue
            }
            if (c == ';') break
            sb.append(c)
            i++
        }
        val url = sb.toString().trim()
        return when {
            url.isEmpty() -> null
            SCHEME.containsMatchIn(url) -> url
            url.startsWith("www.", ignoreCase = true) -> "http://$url"
            else -> null
        }
    }

    /** 링크 글자의 위치 범위 [start, end) (문단 글자 위치) */
    class Span(val start: Int, val end: Int, val url: String)

    /**
     * 문단의 링크 글자 범위들. 필드 시작/끝은 8칸을 차지하므로 링크 글자는 시작 위치 + 8부터 끝 위치 앞까지.
     * 끝이 없는 필드는 문단 끝까지로 본다. 주소가 없는 필드(누름틀 등)는 짝만 맞춘다
     */
    fun spans(items: List<PItem>, paraEnd: Int = items.lastOrNull()?.let { it.pos + it.len } ?: 0): List<Span> {
        val out = ArrayList<Span>()
        val open = ArrayList<Pair<Int, String?>>()
        for (item in items) {
            val c = (item as? PItem.Ctrl)?.ctrl ?: continue
            when (c) {
                is HCtrl.FieldBegin -> open.add((item.pos + item.len) to c.url)
                is HCtrl.FieldEnd -> if (open.isNotEmpty()) {
                    val (from, url) = open.removeAt(open.size - 1)
                    if (url != null && item.pos > from) out.add(Span(from, item.pos, url))
                }
                else -> {}
            }
        }
        for ((from, url) in open) if (url != null && paraEnd > from) out.add(Span(from, paraEnd, url))
        return out
    }

    /** [pos]의 글자가 속한 링크 (없으면 null) */
    fun spanAt(spans: List<Span>, pos: Int): Span? {
        for (s in spans) if (pos >= s.start && pos < s.end) return s
        return null
    }
}
