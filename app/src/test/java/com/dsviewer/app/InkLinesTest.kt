package com.dsviewer.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 필기 검색의 순수 계산 */
class InkLinesTest {

    private fun box(l: Float, t: Float, r: Float, b: Float) = floatArrayOf(l, t, r, b)

    // ================= 글줄 묶기 =================

    @Test fun emptyHasNoLines() {
        assertEquals(emptyList<List<Int>>(), LineGrouper.group(emptyList()))
    }

    @Test fun strokesOfOneWordFormOneLine() {
        // 한 줄(높이 20) 위의 글자 획 4개
        val strokes = listOf(box(10f, 100f, 25f, 120f), box(28f, 100f, 40f, 120f), box(42f, 102f, 55f, 120f), box(58f, 100f, 70f, 118f))
        assertEquals(listOf(listOf(0, 1, 2, 3)), LineGrouper.group(strokes))
    }

    @Test fun twoLinesAreSeparatedTopToBottom() {
        // 아랫줄을 먼저 쓰고 윗줄을 나중에 써도 위에서 아래로 돌려준다
        val strokes = listOf(
            box(10f, 200f, 50f, 220f), box(55f, 200f, 90f, 220f),   // 아랫줄 (0, 1)
            box(10f, 100f, 50f, 120f), box(55f, 100f, 90f, 120f),   // 윗줄 (2, 3)
        )
        assertEquals(listOf(listOf(2, 3), listOf(0, 1)), LineGrouper.group(strokes))
    }

    @Test fun smallDotInsideLineJoinsIt() {
        // 쉼표·점은 키 큰 글자 줄 안에 들어오면 그 줄
        val strokes = listOf(box(10f, 100f, 30f, 130f), box(34f, 123f, 37f, 128f))
        assertEquals(listOf(listOf(0, 1)), LineGrouper.group(strokes))
    }

    @Test fun flatStrokeInsideLineJoinsIt() {
        // 높이 0인 가로 획(마이너스·분수선)도 줄 높이 안이면 같은 줄
        val strokes = listOf(box(10f, 100f, 30f, 130f), box(34f, 115f, 50f, 115f))
        assertEquals(listOf(listOf(0, 1)), LineGrouper.group(strokes))
    }

    @Test fun farApartStrokesOnSameHeightAreDifferentBlocks() {
        // 같은 높이라도 두 단으로 멀리 떨어진 글은 따로 읽는다
        val strokes = listOf(box(10f, 100f, 40f, 120f), box(400f, 100f, 430f, 120f))
        assertEquals(listOf(listOf(0), listOf(1)), LineGrouper.group(strokes))
    }

    @Test fun nonOverlappingHeightIsNewLine() {
        val strokes = listOf(box(10f, 100f, 40f, 120f), box(42f, 140f, 70f, 160f))
        assertEquals(2, LineGrouper.group(strokes).size)
    }

    @Test fun slightlyOverlappingStackedLinesStaySeparate() {
        // 빽빽하게 써서 윗줄 아래와 아랫줄 위가 조금(높이의 4분의 1) 겹쳐도 두 줄이다
        val strokes = listOf(box(10f, 100f, 40f, 140f), box(44f, 130f, 70f, 170f))
        assertEquals(listOf(listOf(0), listOf(1)), LineGrouper.group(strokes))
        // 절반 넘게 겹치면 같은 줄
        assertEquals(listOf(listOf(0, 1)), LineGrouper.group(listOf(box(10f, 100f, 40f, 140f), box(44f, 115f, 70f, 155f))))
    }

    @Test fun continuesLineAfterGoingElsewhere() {
        // 아랫줄을 쓰다 위로 가서 한 줄 쓰고 아랫줄에 이어 쓰면 아랫줄에 다시 붙는다
        val strokes = listOf(
            box(10f, 200f, 40f, 220f),   // 0 아랫줄
            box(10f, 100f, 40f, 120f),   // 1 윗줄
            box(44f, 200f, 70f, 220f),   // 2 아랫줄에 이어
        )
        assertEquals(listOf(listOf(1), listOf(0, 2)), LineGrouper.group(strokes))
    }

    @Test fun drawingsAreNotWorthReading() {
        assertTrue(LineGrouper.worthReading(box(0f, 0f, 100f, 30f), 5))
        assertFalse(LineGrouper.worthReading(box(0f, 0f, 100f, 30f), 0))
        assertFalse(LineGrouper.worthReading(box(0f, 0f, 100f, 30f), LineGrouper.MAX_STROKES + 1))
        assertTrue(LineGrouper.worthReading(box(0f, 0f, 100f, 30f), LineGrouper.MAX_STROKES))
        assertFalse(LineGrouper.worthReading(box(0f, 0f, 100f, LineGrouper.MAX_HEIGHT + 1), 5))
    }

    @Test fun unionCoversChosenBoxesOnly() {
        val boxes = listOf(box(10f, 10f, 20f, 20f), box(100f, 100f, 200f, 200f), box(5f, 15f, 30f, 25f))
        assertArrayEquals(box(5f, 10f, 30f, 25f), LineGrouper.union(boxes, listOf(0, 2)), 0f)
    }

    // ================= 찾은 자리 =================

    @Test fun hitBoxCoversTheMatchedCharacters() {
        // 폭 100, 열 글자: 4~6번째 글자는 40~60 (양옆 3할 = 3씩 넓힘)
        val r = InkLineMath.hitBox(box(0f, 10f, 100f, 30f), total = 10, start = 4, end = 6)
        assertEquals(37f, r[0], 0.001f)
        assertEquals(63f, r[2], 0.001f)
        assertEquals(10f - 1.6f, r[1], 0.001f)
        assertEquals(30f + 1.6f, r[3], 0.001f)
    }

    @Test fun hitBoxStaysInsideLine() {
        val first = InkLineMath.hitBox(box(20f, 0f, 120f, 10f), total = 5, start = 0, end = 1)
        assertEquals(20f, first[0], 0.001f)
        val last = InkLineMath.hitBox(box(20f, 0f, 120f, 10f), total = 5, start = 4, end = 5)
        assertEquals(120f, last[2], 0.001f)
    }

    @Test fun hitBoxOfWholeLineIsTheLine() {
        val r = InkLineMath.hitBox(box(20f, 0f, 120f, 10f), total = 7, start = 0, end = 7)
        assertEquals(20f, r[0], 0.001f)
        assertEquals(120f, r[2], 0.001f)
    }

    // ================= 쪽 서명 =================

    @Test fun sameInkSameHash() {
        val a = listOf(floatArrayOf(1f, 2f, 3f, 4f), floatArrayOf(5f, 6f))
        val b = listOf(floatArrayOf(1f, 2f, 3f, 4f), floatArrayOf(5f, 6f))
        assertEquals(InkHash.of(a), InkHash.of(b))
    }

    @Test fun changingAPointChangesHash() {
        val base = listOf(floatArrayOf(1f, 2f, 3f, 4f))
        assertNotEquals(InkHash.of(base), InkHash.of(listOf(floatArrayOf(1f, 2f, 3f, 4.5f))))
        // 0.1보다 작은 흔들림은 같은 필기로 본다
        assertEquals(InkHash.of(base), InkHash.of(listOf(floatArrayOf(1.01f, 2f, 3f, 4f))))
    }

    @Test fun strokeOrderAndSplitMatter() {
        val a = floatArrayOf(1f, 2f)
        val b = floatArrayOf(3f, 4f)
        assertNotEquals(InkHash.of(listOf(a, b)), InkHash.of(listOf(b, a)))
        // 한 획 (1,2,3,4)과 두 획 (1,2),(3,4)는 다른 필기
        assertNotEquals(InkHash.of(listOf(floatArrayOf(1f, 2f, 3f, 4f))), InkHash.of(listOf(a, b)))
        assertNotEquals(InkHash.of(emptyList()), InkHash.of(listOf(a)))
    }

    // ================= 저장 형식 =================

    @Test fun codecRoundTripsKoreanLinesAndEmptyPages() {
        val pages = linkedMapOf(
            123456789012345L to listOf(
                InkLineData(floatArrayOf(10.5f, 20f, 300.25f, 45f), listOf("삼각형의 넓이", "삼각형의 넒이", "삼각형 의 넓이")),
                InkLineData(floatArrayOf(10f, 60f, 100f, 80f), listOf("f(x)=2x+1")),
            ),
            -42L to emptyList(),   // 읽을 줄이 없던 쪽도 '읽었음'으로 남는다
        )
        val back = HandwritingCodec.decode(HandwritingCodec.encode(pages))
        assertEquals(setOf(123456789012345L, -42L), back.keys)
        val lines = back.getValue(123456789012345L)
        assertEquals(2, lines.size)
        assertArrayEquals(floatArrayOf(10.5f, 20f, 300.25f, 45f), lines[0].box, 0f)
        assertEquals(listOf("삼각형의 넓이", "삼각형의 넒이", "삼각형 의 넓이"), lines[0].texts)
        assertEquals(listOf("f(x)=2x+1"), lines[1].texts)
        assertTrue(back.getValue(-42L).isEmpty())
    }

    @Test fun codecKeepsTabsAndNewlinesOutOfTheFormat() {
        val pages = mapOf(1L to listOf(InkLineData(floatArrayOf(0f, 0f, 1f, 1f), listOf("가\t나\n다"))))
        val back = HandwritingCodec.decode(HandwritingCodec.encode(pages))
        assertEquals(listOf("가 나 다"), back.getValue(1L)[0].texts)
    }

    @Test fun codecSkipsBrokenLines() {
        val s = "쓰레기\nP\t7\nL\t1 2 3\t가\nL\t1 2 3 4\t나\nL\tx y z w\t다\nL\t1 2 3 4\n"
        val back = HandwritingCodec.decode(s)
        // 7번 쪽에는 상자가 올바르고 글이 있는 '나' 한 줄만
        assertEquals(listOf("나"), back.getValue(7L).map { it.texts.single() })
        assertEquals(setOf(7L), back.keys)
    }

    @Test fun codecDecodesEmptyText() {
        assertTrue(HandwritingCodec.decode("").isEmpty())
    }
}
