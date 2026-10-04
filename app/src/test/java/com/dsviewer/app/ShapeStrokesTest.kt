package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 맞춘 도형을 펜 획들로 만들기, 지우개가 지울 범위·방식 정하기 */
class ShapeStrokesTest {

    private val color = 0xFF2244AA.toInt()

    private fun raw(vararg ps: Float): Stroke {
        val st = Stroke(Tool.SHAPE, color, 3.5f)
        for (p in ps) st.add(0f, 0f, p)
        return st
    }

    private val line = floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f)
    private val head = floatArrayOf(18f, -2f, 20f, 0f, 18f, 2f)
    private val axis = floatArrayOf(0f, 5f, 20f, 5f)

    // ================= 평균 필압 =================

    @Test fun averagePressureIsTheMeanOfAllPoints() {
        assertEquals(0.5f, ShapeStrokes.averagePressure(raw(0.2f, 0.8f)), 1e-6f)
        assertEquals(0.4f, ShapeStrokes.averagePressure(raw(0.1f, 0.4f, 0.7f)), 1e-6f)
        assertEquals(0f, ShapeStrokes.averagePressure(Stroke(Tool.SHAPE, color, 1f)), 0f)
    }

    // ================= 획 만들기 =================

    @Test fun curvesKeepColorWidthAndAveragePressureWithTheCurrentPen() {
        val out = ShapeStrokes.build(raw(0.2f, 0.6f), Fitted(listOf(line)), ShapeKind.LINE, GuideStyle.NONE, false, PenStyle.FOUNTAIN)
        assertEquals(1, out.size)
        val st = out[0]
        assertEquals(Tool.PEN, st.tool)
        assertEquals(color, st.color)
        assertEquals(3.5f, st.width, 0f)
        assertEquals(PenStyle.FOUNTAIN, st.pen)
        assertEquals(3, st.count)
        for (i in 0 until st.count) assertEquals(0.4f, st.p(i), 1e-6f)
        assertEquals(10f, st.x(1), 0f)
    }

    @Test fun guideLineBodyFollowsTheDashedChoiceButHeadsStaySolid() {
        val fitted = Fitted(listOf(line), heads = listOf(head))
        val dashed = ShapeStrokes.build(raw(0.5f), fitted, ShapeKind.ARROW, GuideStyle.NONE, true, PenStyle.BALL)
        assertEquals(2, dashed.size)
        assertTrue(dashed[0].dashed)                       // 몸통은 점선
        assertEquals(PenStyle.FELT, dashed[0].pen)          // 점선은 사인펜
        assertFalse(dashed[1].dashed)                      // 화살촉은 실선
        assertEquals(PenStyle.BALL, dashed[1].pen)
        val solid = ShapeStrokes.build(raw(0.5f), fitted, ShapeKind.ARROW, GuideStyle.NONE, false, PenStyle.BALL)
        assertFalse(solid[0].dashed)
        assertEquals(PenStyle.BALL, solid[0].pen)
    }

    @Test fun dashedChoiceDoesNotAffectOrdinaryShapes() {
        // 선택한 점선은 화살표·길이 표시에만 쓰인다 (원 같은 도형 몸통은 실선)
        val out = ShapeStrokes.build(raw(0.5f), Fitted(listOf(line)), ShapeKind.CIRCLE, GuideStyle.NONE, true, PenStyle.BALL)
        assertFalse(out[0].dashed)
    }

    @Test fun guidesAreAddedByTheGuideStyle() {
        val fitted = Fitted(listOf(line), guides = listOf(axis))
        assertEquals(1, ShapeStrokes.build(raw(0.5f), fitted, ShapeKind.CIRCLE, GuideStyle.NONE, false, PenStyle.FELT).size)
        val dashedGuide = ShapeStrokes.build(raw(0.5f), fitted, ShapeKind.CIRCLE, GuideStyle.DASHED, false, PenStyle.BALL)
        assertEquals(2, dashedGuide.size)
        assertTrue(dashedGuide[1].dashed); assertEquals(PenStyle.FELT, dashedGuide[1].pen)
        val solidGuide = ShapeStrokes.build(raw(0.5f), fitted, ShapeKind.CIRCLE, GuideStyle.SOLID, false, PenStyle.BALL)
        assertFalse(solidGuide[1].dashed); assertEquals(PenStyle.BALL, solidGuide[1].pen)
    }

    @Test fun strokesComeInTheOrderCurvesHeadsGuides() {
        val fitted = Fitted(listOf(line, line), guides = listOf(axis), heads = listOf(head))
        val out = ShapeStrokes.build(raw(0.5f), fitted, ShapeKind.ARROW, GuideStyle.SOLID, false, PenStyle.FELT)
        assertEquals(listOf(3, 3, 3, 2), out.map { it.count })
    }

    // ================= 지우개가 지울 범위·방식 =================

    @Test fun tapeEraserOnlyErasesTapesUnlessPalm() {
        assertTrue(EraseRules.tapesOnly(Tool.TAPE, true, false))
        assertFalse(EraseRules.tapesOnly(Tool.TAPE, true, true))        // 손바닥은 늘 일반
        assertFalse(EraseRules.tapesOnly(Tool.TAPE, false, false))
        assertFalse(EraseRules.tapesOnly(Tool.PEN, true, false))
    }

    @Test fun fillEraserOnlyErasesFillsUnlessPalm() {
        assertTrue(EraseRules.fillsOnly(Tool.FILL, true, false))
        assertFalse(EraseRules.fillsOnly(Tool.FILL, true, true))
        assertFalse(EraseRules.fillsOnly(Tool.FILL, false, false))
        assertFalse(EraseRules.fillsOnly(Tool.TAPE, true, false))
    }

    @Test fun eraseModeFollowsPalmThenToolThenTheOrdinaryEraser() {
        val s = EraserMode.STROKE
        val a = EraserMode.AREA
        // 손바닥은 어떤 설정이어도 AREA
        assertEquals(a, EraseRules.modeFor(true, true, false, s, s, s))
        assertEquals(a, EraseRules.modeFor(true, false, false, s, s, s))
        // 테이프만 → 테이프 지우개의 방식
        assertEquals(a, EraseRules.modeFor(false, true, false, a, s, s))
        assertEquals(s, EraseRules.modeFor(false, true, false, s, a, a))
        // 채우기만 → 채우기 지우개의 방식
        assertEquals(a, EraseRules.modeFor(false, false, true, s, a, s))
        assertEquals(s, EraseRules.modeFor(false, false, true, a, s, a))
        // 그 밖에는 일반 지우개
        assertEquals(a, EraseRules.modeFor(false, false, false, s, s, a))
        assertEquals(s, EraseRules.modeFor(false, false, false, a, a, s))
    }
}
