package com.dsviewer.app

import java.io.File

/**
 * 이 앱이 저장한 필기나 북마크가 PDF 안에 들어 있는지 바이트를 흘려 보내며 알아낸다 (PDFBox는 주석·쪽 사전을 압축하지 않고 쓰므로
 * 두 키 모두 `/DSViewer`로 시작하는 글이 그대로 보인다). 덩어리째 [feed]에 넣으면 되고 덩어리 경계에 걸친 글도 찾는다.
 * 파일을 복사하는 동안 같은 덩어리를 넣으면 복사한 뒤에 파일을 다시 읽지 않아도 된다
 */
class InkSniffer {
    private var matched = 0

    /** 찾았는가 */
    var found = false
        private set

    fun feed(buf: ByteArray, n: Int) {
        if (found) return
        var m = matched
        for (k in 0 until n) {
            val b = buf[k]
            m = when {
                b == PATTERN[m] -> m + 1
                b == PATTERN[0] -> 1
                else -> 0
            }
            if (m == PATTERN.size) {
                found = true
                return
            }
        }
        matched = m
    }

    companion object {
        private val PATTERN = "/DSViewer".toByteArray()

        /** [file]에 이 앱의 필기나 북마크가 들어 있는가 */
        fun containsInk(file: File): Boolean {
            val sniffer = InkSniffer()
            val buf = ByteArray(1 shl 18)
            file.inputStream().use { input ->
                while (!sniffer.found) {
                    val n = input.read(buf)
                    if (n < 0) break
                    sniffer.feed(buf, n)
                }
            }
            return sniffer.found
        }
    }
}
