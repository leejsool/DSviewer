package com.dsviewer.app

/**
 * 필기 파일의 점 글 "x,y,p;x,y,p;..."을 읽는다. 점마다 글을 쪼개 만들던 것을 글자를 한 번 훑으며 바로 읽어
 * (획이 수만 개인 문서를 열 때 걸리는 시간의 큰 몫이었다) 새 글·목록을 만들지 않는다.
 * 안드로이드 클래스를 쓰지 않아 JVM 단위 시험으로 확인한다.
 */
internal object PointText {
    private val POW10 = doubleArrayOf(1.0, 10.0, 100.0, 1e3, 1e4, 1e5, 1e6, 1e7, 1e8, 1e9, 1e10, 1e11, 1e12, 1e13, 1e14, 1e15, 1e16, 1e17, 1e18)

    /**
     * (x, y, 필압) 세 값씩 늘어놓은 배열. 모양이 다르면(값이 셋이 아닌 점, 읽을 수 없는 글자) null을 돌려주니
     * 부른 쪽이 느리지만 너그러운 옛 방식으로 읽는다
     */
    fun parse(src: String): FloatArray? {
        val n = src.length
        if (n == 0) return null
        var points = 1
        for (ch in src) if (ch == ';') points++
        val out = FloatArray(points * 3)
        var o = 0
        var i = 0
        while (i < n) {
            for (k in 0 until 3) {
                // 부호
                var neg = false
                if (i < n && src[i] == '-') { neg = true; i++ } else if (i < n && src[i] == '+') i++
                var mant = 0L
                var digits = 0
                var frac = 0
                var seenDot = false
                var any = false
                while (i < n) {
                    val c = src[i]
                    if (c in '0'..'9') {
                        if (digits >= 18) return null  // 너무 길다: 옛 방식으로
                        mant = mant * 10 + (c - '0')
                        digits++
                        if (seenDot) frac++
                        any = true
                    } else if (c == '.' && !seenDot) {
                        seenDot = true
                    } else break
                    i++
                }
                if (!any) return null
                val v = (mant.toDouble() / POW10[frac]).toFloat()
                out[o++] = if (neg) -v else v
                if (k < 2) {
                    if (i >= n || src[i] != ',') return null
                    i++
                }
            }
            if (i < n) {
                if (src[i] != ';') return null
                i++
                if (i >= n) return null  // 끝의 빈 점
            }
        }
        return if (o == out.size) out else null
    }
}
