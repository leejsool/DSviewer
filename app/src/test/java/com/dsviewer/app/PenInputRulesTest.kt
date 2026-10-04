package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 터치가 들어왔을 때 펜으로 쓸지·지울지·이동으로 볼지 가르는 규칙 */
class PenInputRulesTest {

    private val down = PenInputRules.ACTION_DOWN
    private val up = PenInputRules.ACTION_UP
    private val move = PenInputRules.ACTION_MOVE
    private val pointerDown = PenInputRules.ACTION_POINTER_DOWN

    // ================= 삼성 S펜 코드 =================

    @Test fun samsungCodesBecomeStandardActionsWithTheButton() {
        var a = PenInputRules.translate(211)
        assertEquals(down, a.action); assertTrue(a.samsungButton)
        a = PenInputRules.translate(212)
        assertEquals(up, a.action); assertTrue(a.samsungButton)
        a = PenInputRules.translate(213)
        assertEquals(move, a.action); assertTrue(a.samsungButton)
    }

    @Test fun otherActionsPassThroughWithoutTheButton() {
        for (code in listOf(0, 1, 2, 3, 5, 6, 210, 214)) {
            val a = PenInputRules.translate(code)
            assertEquals(code, a.action); assertFalse(a.samsungButton)
        }
    }

    @Test fun androidActionNumbersMatchTheConstantsHere() {
        // 시험용 상수가 실제 MotionEvent 값과 같은지 (DocumentView가 넘기는 값)
        assertEquals(android.view.MotionEvent.ACTION_DOWN, down)
        assertEquals(android.view.MotionEvent.ACTION_UP, up)
        assertEquals(android.view.MotionEvent.ACTION_MOVE, move)
        assertEquals(android.view.MotionEvent.ACTION_POINTER_DOWN, pointerDown)
    }

    // ================= 지우개 입력 =================

    @Test fun eraserInputFromSamsungButtonEraserEndOrStylusButtons() {
        val mask = 0b0110
        assertTrue(PenInputRules.isEraserInput(true, false, 0, mask))
        assertTrue(PenInputRules.isEraserInput(false, true, 0, mask))
        assertTrue(PenInputRules.isEraserInput(false, false, 0b0010, mask))
        assertTrue(PenInputRules.isEraserInput(false, false, 0b0100, mask))
        assertFalse(PenInputRules.isEraserInput(false, false, 0b0001, mask))      // 마스크 밖 버튼
        assertFalse(PenInputRules.isEraserInput(false, false, 0, mask))
    }

    // ================= 펜 시작 =================

    @Test fun penStartsOnlyWhenWritableAndNotAlreadyWriting() {
        assertTrue(PenInputRules.mayStartPen(false, false, down))
        assertTrue(PenInputRules.mayStartPen(false, false, pointerDown))
        assertFalse(PenInputRules.mayStartPen(true, false, down))        // 읽기 모드
        assertFalse(PenInputRules.mayStartPen(false, true, down))        // 이미 쓰는 중
        assertFalse(PenInputRules.mayStartPen(false, false, move))
        assertFalse(PenInputRules.mayStartPen(false, false, up))
    }

    @Test fun fingerPenNeedsFingerDrawingOrASelectionUnderTheFinger() {
        // 손가락 필기 켬
        assertTrue(PenInputRules.isFingerPen(false, down, true, true, false))
        // 꺼 두어도 선택한 부분 위면 옮기려고
        assertTrue(PenInputRules.isFingerPen(false, down, true, false, true))
        // 둘 다 아니면 이동·확대
        assertFalse(PenInputRules.isFingerPen(false, down, true, false, false))
        // 스타일러스는 손가락 필기가 아니다
        assertFalse(PenInputRules.isFingerPen(true, down, true, true, true))
        // 손가락이 아닌 것(마우스 등)
        assertFalse(PenInputRules.isFingerPen(false, down, false, true, true))
        // 두 번째 손가락(POINTER_DOWN)은 손가락 필기를 새로 시작하지 않는다
        assertFalse(PenInputRules.isFingerPen(false, pointerDown, true, true, true))
    }

    // ================= 지울지 =================

    @Test fun eraserToolAlwaysErases() {
        assertTrue(PenInputRules.penErasing(Tool.ERASER, false, false, false, false))
    }

    @Test fun tapeAndFillToolsEraseOnlyWhenSwitchedToTheirEraser() {
        assertTrue(PenInputRules.penErasing(Tool.TAPE, true, false, false, false))
        assertFalse(PenInputRules.penErasing(Tool.TAPE, false, true, false, false))
        assertTrue(PenInputRules.penErasing(Tool.FILL, false, true, false, false))
        assertFalse(PenInputRules.penErasing(Tool.FILL, true, false, false, false))
    }

    @Test fun stylusEraserInputErasesWithAnyTool() {
        assertTrue(PenInputRules.penErasing(Tool.PEN, false, false, true, true))
        assertTrue(PenInputRules.penErasing(Tool.HIGHLIGHTER, false, false, true, true))
        // 손가락(스타일러스 아님)의 '지우개 입력'은 무시
        assertFalse(PenInputRules.penErasing(Tool.PEN, false, false, false, true))
        assertFalse(PenInputRules.penErasing(Tool.PEN, false, false, true, false))
    }

    // ================= 손바닥 =================

    @Test fun palmToolsArePenHighlighterShapeAndEraser() {
        for (t in listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.SHAPE, Tool.ERASER)) assertTrue("$t", PenInputRules.palmToolOk(true, false, t))
        for (t in listOf(Tool.TAPE, Tool.FILL, Tool.TEXT, Tool.LASER)) assertFalse("$t", PenInputRules.palmToolOk(true, false, t))
    }

    @Test fun palmNeedsTheSettingAndWritableMode() {
        assertFalse(PenInputRules.palmToolOk(false, false, Tool.PEN))
        assertFalse(PenInputRules.palmToolOk(true, true, Tool.PEN))
    }

    @Test fun palmReadyWhileFingerWritingAndNotAlreadyPalm() {
        assertTrue(PenInputRules.palmReady(true, true, false, true))
        assertFalse(PenInputRules.palmReady(false, true, false, true))
        assertFalse(PenInputRules.palmReady(true, false, false, true))
        assertFalse(PenInputRules.palmReady(true, true, true, true))
        assertFalse(PenInputRules.palmReady(true, true, false, false))
    }

    // ================= 압력 =================

    @Test fun fingerPressureIsFixedAndStylusPressureIsClamped() {
        assertEquals(0.6f, PenInputRules.pressure(false, 0.1f), 0f)
        assertEquals(0.3f, PenInputRules.pressure(true, 0.3f), 0f)
        assertEquals(1f, PenInputRules.pressure(true, 1.7f), 0f)
        assertEquals(0f, PenInputRules.pressure(true, -0.2f), 0f)
    }
}
