package com.dsviewer.app.hwp

import org.junit.Assert.assertEquals
import org.junit.Test

class LinkWriterTest {

    private fun box(l: Float, t: Float, r: Float, b: Float, url: String = "http://a.com", page: Int = 0) =
        LinkBox(page, 800f, l, t, r, b, url)

    @Test fun pdfRectFlipsToBottomLeftOrigin() {
        // 쪽 위에서 100~112, 왼쪽에서 50~150인 글자 → PDF는 아래에서 800-112 = 688부터 높이 12
        val r = LinkWriter.pdfRect(box(50f, 100f, 150f, 112f))
        assertEquals(listOf(50f, 688f, 100f, 12f), r.toList())
    }

    @Test fun mergeJoinsTouchingPiecesOnTheSameLine() {
        val merged = LinkWriter.merge(listOf(box(10f, 100f, 20f, 112f), box(20f, 100f, 31f, 112f), box(31.2f, 100f, 40f, 112f)))
        assertEquals(1, merged.size)
        assertEquals(10f, merged[0].l, 0f)
        assertEquals(40f, merged[0].r, 0f)
    }

    @Test fun mergeKeepsDifferentLinesUrlsAndPages() {
        val list = listOf(
            box(10f, 100f, 20f, 112f),
            box(10f, 120f, 20f, 132f), // 다른 줄
            box(20f, 120f, 30f, 132f, url = "http://b.com"), // 다른 주소
            box(30f, 120f, 40f, 132f, url = "http://b.com", page = 1), // 다른 쪽
            box(100f, 120f, 110f, 132f, url = "http://b.com", page = 1), // 떨어져 있음
        )
        assertEquals(5, LinkWriter.merge(list).size)
    }

    @Test fun mergeOfEmptyIsEmpty() {
        assertEquals(0, LinkWriter.merge(emptyList()).size)
    }
}
