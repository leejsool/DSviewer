package com.dsviewer.app.hwp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HLinksTest {

    @Test fun commandUnescapesColonAndCutsAtSemicolon() {
        assertEquals("https://cafe.naver.com/pnmath", HLinks.parseCommand("""https\://cafe.naver.com/pnmath;1;0;0;"""))
        assertEquals("http://a.com/x?y=1", HLinks.parseCommand("http://a.com/x?y=1"))
    }

    @Test fun commandKeepsEscapedSemicolon() {
        assertEquals("http://a.com/a;b", HLinks.parseCommand("""http\://a.com/a\;b;1;0;0;"""))
    }

    @Test fun commandAcceptsMailTelAndWww() {
        assertEquals("mailto:me@x.com", HLinks.parseCommand("""mailto\:me@x.com;1;0;0;"""))
        assertEquals("tel:0212345678", HLinks.parseCommand("tel:0212345678;1;0;0;"))
        assertEquals("http://www.x.com", HLinks.parseCommand("www.x.com;1;0;0;"))
        assertEquals("HTTPS://X.COM", HLinks.parseCommand("""HTTPS\://X.COM;1;0;0;"""))
    }

    @Test fun commandRejectsWhatPdfCannotOpen() {
        assertNull(HLinks.parseCommand(null))
        assertNull(HLinks.parseCommand(""))
        assertNull(HLinks.parseCommand(";1;0;0;"))
        assertNull(HLinks.parseCommand("""\#bookmark;1;0;0;"""))
        assertNull(HLinks.parseCommand("""C\:\\docs\\a.hwp;1;0;0;"""))
        assertNull(HLinks.parseCommand("javascript:alert(1);1;0;0;"))
    }

    private fun ctrl(pos: Int, c: HCtrl) = PItem.Ctrl(pos, c, 0)
    private fun text(pos: Int, s: String) = PItem.Text(pos, s, 0)

    @Test fun spanCoversTextBetweenBeginAndEnd() {
        // 앞글(0~2) 필드시작(3~10) 링크글(11~14) 필드끝(15~22) 뒷글(23~)
        val items = listOf(
            text(0, "ab "), ctrl(3, HCtrl.FieldBegin("http://x.com")), text(11, "link"), ctrl(15, HCtrl.FieldEnd), text(23, " cd"),
        )
        val spans = HLinks.spans(items)
        assertEquals(1, spans.size)
        assertEquals(11, spans[0].start)
        assertEquals(15, spans[0].end)
        assertEquals("http://x.com", HLinks.spanAt(spans, 11)?.url)
        assertEquals("http://x.com", HLinks.spanAt(spans, 14)?.url)
        assertNull(HLinks.spanAt(spans, 10))
        assertNull(HLinks.spanAt(spans, 15))
        assertNull(HLinks.spanAt(spans, 0))
    }

    @Test fun twoLinksInOneParagraph() {
        val items = listOf(
            ctrl(0, HCtrl.FieldBegin("http://a.com")), text(8, "AA"), ctrl(10, HCtrl.FieldEnd),
            text(18, " "), ctrl(19, HCtrl.FieldBegin("http://b.com")), text(27, "BB"), ctrl(29, HCtrl.FieldEnd),
        )
        val spans = HLinks.spans(items)
        assertEquals(2, spans.size)
        assertEquals("http://a.com", HLinks.spanAt(spans, 9)?.url)
        assertEquals("http://b.com", HLinks.spanAt(spans, 28)?.url)
        assertNull(HLinks.spanAt(spans, 18))
    }

    @Test fun fieldWithoutUrlIsNotALinkButKeepsPairing() {
        // 누름틀(주소 없음) 안에 링크가 들어 있어도 끝은 안쪽부터 짝을 맞춘다
        val items = listOf(
            ctrl(0, HCtrl.FieldBegin(null)), ctrl(8, HCtrl.FieldBegin("http://a.com")), text(16, "AB"),
            ctrl(18, HCtrl.FieldEnd), text(26, "C"), ctrl(27, HCtrl.FieldEnd),
        )
        val spans = HLinks.spans(items)
        assertEquals(1, spans.size)
        assertEquals(16, spans[0].start)
        assertEquals(18, spans[0].end)
    }

    @Test fun missingEndRunsToParagraphEnd() {
        val items = listOf(ctrl(0, HCtrl.FieldBegin("http://a.com")), text(8, "tail"))
        val spans = HLinks.spans(items)
        assertEquals(1, spans.size)
        assertEquals(12, spans[0].end)
    }

    @Test fun emptyOrStrayEnd() {
        assertTrue(HLinks.spans(emptyList()).isEmpty())
        assertTrue(HLinks.spans(listOf(ctrl(0, HCtrl.FieldEnd), text(8, "x"))).isEmpty())
        // 링크 글자가 하나도 없으면 범위를 만들지 않는다
        assertTrue(HLinks.spans(listOf(ctrl(0, HCtrl.FieldBegin("http://a.com")), ctrl(8, HCtrl.FieldEnd))).isEmpty())
    }

    @Test fun officeTargetKeepsSemicolonAndBackslash() {
        assertEquals("https://x.com/a;b?c=1", HLinks.fromTarget("https://x.com/a;b?c=1"))
        assertEquals("mailto:me@x.com", HLinks.fromTarget(" mailto:me@x.com "))
        assertEquals("http://www.x.com", HLinks.fromTarget("www.x.com"))
        assertNull(HLinks.fromTarget(null))
        assertNull(HLinks.fromTarget(""))
        assertNull(HLinks.fromTarget("#slide2"))
        assertNull(HLinks.fromTarget("file:///C:/a.docx"))
        assertNull(HLinks.fromTarget("javascript:alert(1)"))
    }

    @Test fun fieldCodeReadsQuotedAndBareUrl() {
        assertEquals("https://a.com/x", HLinks.fromFieldCode(""" HYPERLINK "https://a.com/x" \o "설명" """))
        assertEquals("https://a.com/x", HLinks.fromFieldCode("hyperlink https://a.com/x"))
        assertEquals("mailto:me@x.com", HLinks.fromFieldCode("""HYPERLINK "mailto:me@x.com" """))
    }

    @Test fun fieldCodeRejectsBookmarkAndOtherFields() {
        assertNull(HLinks.fromFieldCode("""HYPERLINK \l "bookmark" """))
        assertNull(HLinks.fromFieldCode("""HYPERLINK "C:\docs\a.docx" """))
        assertNull(HLinks.fromFieldCode("PAGE"))
        assertNull(HLinks.fromFieldCode(null))
    }
}
