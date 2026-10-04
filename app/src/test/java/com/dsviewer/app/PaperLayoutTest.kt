package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** 빈 쪽 바탕들의 모양 */
class PaperLayoutTest {
    private val sizes = listOf(
        PdfPages.A4_SHORT to PdfPages.A4_LONG,   // 세로
        PdfPages.A4_LONG to PdfPages.A4_SHORT,   // 가로
    )
    private val mm = PaperLayout.MM

    private fun layout(p: Paper, portrait: Boolean = true): PaperDrawing {
        val (w, h) = sizes[if (portrait) 0 else 1]
        return PaperLayout.of(p, w, h)
    }

    private fun horizontals(d: PaperDrawing) = d.segs.filter { it.y1 == it.y2 && it.x1 != it.x2 }
    private fun verticals(d: PaperDrawing) = d.segs.filter { it.x1 == it.x2 && it.y1 != it.y2 }

    @Test fun plainHasNothingAndEveryOtherPaperDraws() {
        assertTrue(layout(Paper.PLAIN).segs.isEmpty())
        for (p in Paper.entries.filter { it != Paper.PLAIN }) for (portrait in listOf(true, false)) {
            assertTrue("$p $portrait", layout(p, portrait).segs.isNotEmpty())
        }
    }

    @Test fun everythingStaysInsideThePage() {
        for (p in Paper.entries) for ((w, h) in sizes) {
            val d = PaperLayout.of(p, w, h)
            val eps = 0.5f
            for (s in d.segs) {
                for (v in listOf(s.x1, s.x2)) assertTrue("$p x=$v", v >= -eps && v <= w + eps)
                for (v in listOf(s.y1, s.y2)) assertTrue("$p y=$v", v >= -eps && v <= h + eps)
                assertTrue("$p width", s.width > 0f && s.width < 3f)
                assertFalse("$p NaN", s.x1.isNaN() || s.y1.isNaN() || s.x2.isNaN() || s.y2.isNaN())
            }
            for (f in d.fills) assertTrue("$p fill", f.x >= 0 && f.y >= 0 && f.x + f.w <= w + eps && f.y + f.h <= h + eps && f.w > 0 && f.h > 0)
        }
    }

    @Test fun pageStaysSmall() {
        // 점 격자도 점마다가 아니라 줄마다 그리므로 선 수가 수천을 넘지 않는다 (쪽이 많아도 파일이 커지지 않게)
        for (p in Paper.entries) for ((w, h) in sizes) assertTrue("$p", PaperLayout.of(p, w, h).segs.size < 1500)
    }

    @Test fun paperKindsLookDifferent() {
        fun sig(p: Paper): String {
            val d = layout(p)
            return d.segs.size.toString() + "/" + d.fills.size + "/" + d.segs.take(5).joinToString { "${it.x1},${it.y1},${it.rgb}" }
        }
        val sigs = Paper.entries.map(::sig)
        assertEquals(sigs.size, sigs.toSet().size)
    }

    // ---- 서식마다 ----

    @Test fun dotsAreRoundedTinyDashesOnFiveMillimeterSteps() {
        val d = layout(Paper.DOTS)
        assertTrue(d.segs.all { it.round && it.dash != null && it.dash!![0] < 1f })
        // 켜짐 + 꺼짐 = 5mm 간격
        assertEquals(5 * mm, d.segs[0].dash!!.sum(), 0.01f)
        // 줄 사이도 5mm
        val ys = d.segs.map { it.y1 }.sorted()
        assertEquals(5 * mm, ys[1] - ys[0], 0.01f)
        // 가운데 줄이 있다 (모눈과 같은 자리)
        assertTrue(ys.any { abs(it - PdfPages.A4_LONG / 2f) < 0.01f })
    }

    @Test fun manuscriptHasTwentyColumnsOnPortrait() {
        val d = layout(Paper.MANUSCRIPT)
        val firstRowTop = d.segs.filter { it.y1 == it.y2 }.maxOf { it.y1 }
        val columnLines = verticals(d).filter { abs(it.y2 - firstRowTop) < 0.01f || abs(it.y1 - firstRowTop) < 0.01f }
        assertEquals(21, columnLines.size)   // 20칸 = 세로선 21개
        // 칸은 정사각형
        val cell = columnLines[1].x1 - columnLines[0].x1
        val v = columnLines[0]
        assertEquals(cell, abs(v.y2 - v.y1), 0.01f)
        assertTrue(horizontals(d).size % 2 == 0)
    }

    @Test fun manuscriptRowsFitInPageWithGapBetweenRows() {
        val d = layout(Paper.MANUSCRIPT)
        val tops = horizontals(d).map { it.y1 }.distinct().sortedDescending()
        // 짝수 번째가 칸 위, 홀수 번째가 칸 아래: 줄 사이 틈이 칸 높이보다 작다
        val cell = tops[0] - tops[1]
        val gap = tops[1] - tops[2]
        assertTrue(gap in 0.1f..cell)
        assertTrue(tops.last() >= 0f)
    }

    @Test fun englishLinesComeInFours() {
        for (portrait in listOf(true, false)) {
            val d = layout(Paper.ENGLISH, portrait)
            assertEquals(0, horizontals(d).size % 4)
            assertTrue(horizontals(d).size >= 8)
            // 묶음마다 점선 가운데 줄 하나, 붉은 기준선 하나
            val dashed = d.segs.count { it.dash != null }
            val red = d.segs.count { it.rgb == 0xE79A9A }
            assertEquals(horizontals(d).size / 4, dashed)
            assertEquals(dashed, red)
        }
    }

    @Test fun staffLinesComeInFives() {
        val d = layout(Paper.STAFF)
        val lines = horizontals(d)
        assertEquals(0, lines.size % 5)
        // 한 묶음 안 줄 간격은 같다
        val ys = lines.map { it.y1 }.sortedDescending()
        assertEquals(ys[0] - ys[1], ys[1] - ys[2], 0.01f)
        assertEquals(lines.size / 5, verticals(d).size)
    }

    @Test fun axesCrossAtPageCenterWithArrowsAndTicks() {
        val (w, h) = sizes[0]
        val d = PaperLayout.of(Paper.AXES, w, h)
        val dark = d.segs.filter { it.rgb == 0x37474F }
        val xAxis = dark.first { it.y1 == it.y2 && it.x2 - it.x1 > w / 2 }
        val yAxis = dark.first { it.x1 == it.x2 && it.y2 - it.y1 > h / 2 }
        assertEquals(h / 2f, xAxis.y1, 0.01f)
        assertEquals(w / 2f, yAxis.x1, 0.01f)
        // 화살표 4개 + 눈금 여럿
        assertTrue(dark.size > 2 + 4 + 10)
        // 눈금은 축 위에 걸쳐 있다
        val ticksOnX = dark.filter { it.x1 == it.x2 && abs((it.y1 + it.y2) / 2 - h / 2f) < 0.01f && it.y2 - it.y1 < 5 * mm }
        assertTrue(ticksOnX.size >= 10)
    }

    @Test fun timetableHasSixVerticalBoundariesPlusLeftEdge() {
        for (portrait in listOf(true, false)) {
            val d = layout(Paper.TIMETABLE, portrait)
            // 왼쪽 가장자리, 시간 칸 끝, 요일 5칸 끝 = 7개
            assertEquals(7, verticals(d).size)
            assertEquals(2, d.fills.size)
            // 가로선: 맨 위, 요일 줄 아래, 교시 칸마다 하나
            assertTrue(horizontals(d).size >= 2 + 4)
        }
    }

    @Test fun cornellHasTitleCueAndSummaryAreas() {
        val (w, h) = sizes[0]
        val d = PaperLayout.of(Paper.CORNELL, w, h)
        val rule = d.segs.filter { it.rgb == 0x78909C }
        assertEquals(3, rule.size)   // 제목 칸 아래, 요약 칸 위, 큐 칸 오른쪽
        val cue = rule.first { it.x1 == it.x2 }
        assertTrue(cue.x1 > w * 0.25f && cue.x1 < w * 0.45f)
        val horiz = rule.filter { it.y1 == it.y2 }.map { it.y1 }.sortedDescending()
        assertTrue(horiz[0] > h * 0.8f)    // 제목 칸 선은 위쪽
        assertTrue(horiz[1] < h * 0.3f)    // 요약 칸 선은 아래쪽
        // 필기 칸에만 줄이 있다 (큐 칸 오른쪽에서 시작)
        val light = d.segs.filter { it.rgb == 0xDCE3EC }
        assertTrue(light.isNotEmpty() && light.all { it.x1 >= cue.x1 - 0.01f })
    }

    @Test fun gridAndLinedPreviewsKeepClassicShape() {
        val g = layout(Paper.GRID)
        // 모눈은 가운데를 지나는 굵은 선이 있다
        assertTrue(g.segs.any { it.x1 == it.x2 && abs(it.x1 - PdfPages.A4_SHORT / 2f) < 0.01f && it.width > 0.5f })
        val l = layout(Paper.LINED)
        assertTrue(horizontals(l).size > 20)
        assertNotEquals(g.segs.size, l.segs.size)
    }
}
