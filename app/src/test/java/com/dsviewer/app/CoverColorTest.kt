package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 네모 덮기의 덮는 색 따기 */
class CoverColorTest {
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val cream = 0xFFFFF3C4.toInt()

    private fun image(w: Int, h: Int, fill: Int) = IntArray(w * h) { fill }

    @Test fun uniformBackgroundGivesThatColor() {
        val px = image(40, 40, cream)
        assertEquals(cream, CoverColor.dominant(px, 40, 40, 10, 10, 30, 30, gap = 1, thick = 4))
    }

    @Test fun textInsideTheRectIsIgnored() {
        val px = image(40, 40, white)
        for (y in 12 until 28) for (x in 12 until 28) px[y * 40 + x] = black   // 네모 안은 글자투성이
        assertEquals(white, CoverColor.dominant(px, 40, 40, 10, 10, 30, 30, gap = 1, thick = 4))
    }

    @Test fun fewDarkPixelsInTheRingDoNotWin() {
        val px = image(40, 40, white)
        // 띠에 글자 몇 획이 걸려 있다
        for (x in 5 until 12) px[7 * 40 + x] = black
        assertEquals(white, CoverColor.dominant(px, 40, 40, 10, 10, 30, 30, gap = 1, thick = 4))
    }

    @Test fun ringIsClippedAtTheImageEdge() {
        // 네모가 그림 왼쪽 위 모서리에 붙어 있어도 오른쪽·아래 띠로 색을 딴다
        val px = image(30, 30, cream)
        assertEquals(cream, CoverColor.dominant(px, 30, 30, 0, 0, 15, 15, gap = 1, thick = 4))
    }

    @Test fun rectCoveringEverythingHasNoRing() {
        val px = image(20, 20, white)
        assertNull(CoverColor.dominant(px, 20, 20, 0, 0, 20, 20, gap = 1, thick = 4))
    }

    @Test fun averagesWithinTheWinningBin() {
        val px = image(40, 40, 0xFF808080.toInt())
        px[3 * 40 + 3] = 0xFF828282.toInt()   // 같은 덩어리 안의 조금 다른 회색
        val c = CoverColor.dominant(px, 40, 40, 15, 15, 25, 25, gap = 1, thick = 20)!!
        assertEquals(0xFF, c ushr 24)
        assertEquals(0x80, (c shr 16) and 0xFF)
    }
}
