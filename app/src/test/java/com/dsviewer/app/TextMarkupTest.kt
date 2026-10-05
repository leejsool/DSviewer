package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TextMarkupTest {

    /**
     * 글줄들을 PageText로: 한 줄은 [text]의 글자를 폭 10·높이 10인 상자로 왼쪽부터 늘어놓는다 (줄 위 y = line * 20).
     * 글자 사이에 '_'가 있으면 틈 (띄어쓰기) 10을 두고 글자로는 넣지 않는다
     */
    private fun page(vararg lines: String): PageText {
        val chars = StringBuilder()
        val boxes = ArrayList<Float>()
        lines.forEachIndexed { li, line ->
            var x = 0f
            for (ch in line) {
                if (ch == '_') { x += 10f; continue }
                boxes += listOf(x, li * 20f, x + 10f, li * 20f + 10f)
                chars.append(ch)
                x += 10f
            }
        }
        return PageText(chars.toString().lowercase(), boxes.toFloatArray(), chars.toString())
    }

    @Test fun nearestInsideBox() {
        val p = page("abc")
        assertEquals(1, TextMarkup.nearest(p, 15f, 5f))
    }

    @Test fun nearestOutsideWithinSlop() {
        val p = page("abc")
        assertEquals(2, TextMarkup.nearest(p, 33f, 5f, maxDist = 4f))
        assertEquals(-1, TextMarkup.nearest(p, 50f, 5f, maxDist = 4f))
    }

    @Test fun nearestBelowLineStaysOnLine() {
        val p = page("ab", "cd")
        assertEquals(0, TextMarkup.nearest(p, 2f, 12f)) // 첫 줄 아래 틈: 가까운 쪽(첫 줄) 글자
        assertEquals(2, TextMarkup.nearest(p, 2f, 18f)) // 둘째 줄 위 틈
    }

    @Test fun nearestWithoutTextIsMinusOne() {
        assertEquals(-1, TextMarkup.nearest(PageText("", FloatArray(0)), 1f, 1f))
    }

    @Test fun lineRectsOneLine() {
        val r = TextMarkup.lineRects(page("abcd"), 1, 2)
        assertEquals(1, r.size)
        assertEquals(listOf(10f, 0f, 30f, 10f), r[0].toList())
    }

    @Test fun lineRectsAnyOrderAndTwoLines() {
        val p = page("abc", "def")
        val r = TextMarkup.lineRects(p, 4, 1) // b부터 e까지, 거꾸로 줘도 같다
        assertEquals(2, r.size)
        assertEquals(listOf(10f, 0f, 30f, 10f), r[0].toList())
        assertEquals(listOf(0f, 20f, 20f, 30f), r[1].toList())
    }

    @Test fun lineRectsClampsRange() {
        val r = TextMarkup.lineRects(page("ab"), -5, 99)
        assertEquals(1, r.size)
        assertEquals(listOf(0f, 0f, 20f, 10f), r[0].toList())
    }

    @Test fun copyKeepsCaseAndAddsSpaceAndNewline() {
        val p = page("Hi_Yo", "Ok")
        assertEquals("Hi Yo\nOk", TextMarkup.copyText(p, 0, 5))
        assertEquals("i Yo", TextMarkup.copyText(p, 1, 3))
    }

    @Test fun copyTightGlyphsHaveNoSpace() {
        assertEquals("abcd", TextMarkup.copyText(page("abcd"), 0, 3))
    }

    @Test fun copyReversedRangeSame() {
        val p = page("Hi_Yo")
        assertEquals(TextMarkup.copyText(p, 0, 3), TextMarkup.copyText(p, 3, 0))
    }

    @Test fun copyEmptyPage() {
        assertEquals("", TextMarkup.copyText(PageText("", FloatArray(0)), 0, 3))
    }

    @Test fun highlightSegmentFitsBox() {
        val s = TextMarkup.segment(TextMark.HIGHLIGHT, floatArrayOf(10f, 0f, 60f, 10f), 1f)!!
        // 굵기는 상자 높이, 둥근 끝(반 굵기)까지 더하면 상자 폭과 딱 맞는다
        assertEquals(10f, s.width, 0f)
        assertEquals(5f, s.y0, 0f)
        assertEquals(15f, s.x0, 0f)
        assertEquals(55f, s.x1, 0f)
    }

    @Test fun highlightSegmentNarrowBoxIsDot() {
        val s = TextMarkup.segment(TextMark.HIGHLIGHT, floatArrayOf(10f, 0f, 16f, 10f), 1f)!!
        assertEquals(13f, s.x0, 0f)
        assertEquals(s.x0, s.x1, 0f)
    }

    @Test fun underlineIsBelowAndThin() {
        val s = TextMarkup.segment(TextMark.UNDERLINE, floatArrayOf(10f, 0f, 60f, 10f), 1f)!!
        assertEquals(10f, s.x0, 0f)
        assertEquals(60f, s.x1, 0f)
        assertEquals(s.y0, s.y1, 0f)
        assertTrue(s.y0 > 5f && s.y0 < 10f)
        assertEquals(1f, s.width, 0f)
    }

    @Test fun strikeThroughMiddleAndWidthClamped() {
        val s = TextMarkup.segment(TextMark.STRIKE, floatArrayOf(0f, 0f, 40f, 10f), 5f)!!
        assertTrue(s.y0 > 4f && s.y0 < 7f)
        assertEquals(1.2f, s.width, 1e-4f) // 높이의 12%로 줄인다
    }

    @Test fun segmentRejectsEmptyAndCopy() {
        assertNull(TextMarkup.segment(TextMark.HIGHLIGHT, floatArrayOf(0f, 0f, 0f, 10f), 1f))
        assertNull(TextMarkup.segment(TextMark.UNDERLINE, floatArrayOf(0f, 5f, 10f, 5f), 1f))
        assertNull(TextMarkup.segment(TextMark.COPY, floatArrayOf(0f, 0f, 10f, 10f), 1f))
        assertNotNull(TextMarkup.segment(TextMark.STRIKE, floatArrayOf(0f, 0f, 10f, 10f), 1f))
    }

    @Test fun textMarkNamedFallsBack() {
        assertEquals(TextMark.UNDERLINE, TextMark.named("UNDERLINE"))
        assertEquals(TextMark.NONE, TextMark.named("없는이름"))
        assertEquals(TextMark.NONE, TextMark.named(null))
    }
}
