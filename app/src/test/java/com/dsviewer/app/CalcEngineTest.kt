package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 공학용 계산기의 계산 규칙 */
class CalcEngineTest {

    /** "12+3" 처럼 적은 글을 키 누름으로 바꿔 넣는다 (x=×, /=÷, ^=xʸ, n=±, (=괄호) */
    private fun CalcEngine.press(keys: String): CalcEngine {
        for (c in keys) when (c) {
            in '0'..'9' -> digit(c - '0')
            '.' -> dot()
            '+' -> binary(CalcOp.ADD)
            '-' -> binary(CalcOp.SUB)
            'x' -> binary(CalcOp.MUL)
            '/' -> binary(CalcOp.DIV)
            '^' -> binary(CalcOp.POW)
            'm' -> binary(CalcOp.MOD)
            '(' -> openParen()
            ')' -> closeParen()
            '=' -> equals()
            'n' -> negate()
            'e' -> exponent()
            'b' -> backspace()
            'C' -> clear()
        }
        return this
    }

    private fun calc(keys: String) = CalcEngine().press(keys).display

    // ================= 사칙·우선순위 =================

    @Test fun basicArithmetic() {
        assertEquals("5", calc("2+3="))
        assertEquals("14", calc("2+3x4="))
        assertEquals("0.3", calc("0.1+0.2="))
        assertEquals("2.5", calc("5/2="))
        assertEquals("1,234,567", calc("1234567"))
    }

    @Test fun precedenceAndParentheses() {
        assertEquals("20", calc("(2+3)x4="))
        assertEquals("512", calc("2^3^2="))          // 오른쪽부터
        assertEquals("14", calc("2+3x4="))
    }

    @Test fun unclosedParenthesisIsClosedByEquals() {
        assertEquals("14", calc("(2+3x4="))
    }

    @Test fun numberAfterParenthesisMultiplies() {
        assertEquals("10", calc("2(3+2)="))
    }

    @Test fun operatorCanBeReplaced() {
        assertEquals("6", calc("2+x3="))
    }

    @Test fun equalsRepeatsLastOperation() {
        assertEquals("9", calc("5+2=="))
        assertEquals("11", calc("5+2==="))
    }

    @Test fun continuingFromResult() {
        assertEquals("15", calc("5+5=+5="))
    }

    @Test fun typingAfterEqualsStartsNew() {
        assertEquals("7", calc("5+5=7"))
    }

    // ================= 오류 =================

    @Test fun divideByZero() {
        val c = CalcEngine().press("5/0=")
        assertEquals(CalcEngine.ERR_DIV0, c.display)
        c.press("C7")
        assertEquals("7", c.display)
        assertNull(c.error)
    }

    @Test fun zeroOverZeroIsUndefined() {
        assertEquals(CalcEngine.ERR_UNDEF, calc("0/0="))
    }

    @Test fun sqrtOfNegativeIsInvalid() {
        val c = CalcEngine().press("4n")
        c.unary(CalcFn.SQRT)
        assertEquals(CalcEngine.ERR_INVALID, c.display)
    }

    // ================= 입력 =================

    @Test fun decimalAndBackspaceAndNegate() {
        assertEquals("1.5", calc("1.5"))
        assertEquals("1.", calc("1.5b"))
        assertEquals("0", calc("5b"))
        assertEquals("-5", calc("5n"))
        assertEquals("5", calc("5nn"))
        assertEquals("0.5", calc(".5"))
        assertEquals("1.5", calc("1..5"))
    }

    @Test fun exponentEntry() {
        assertEquals("300", calc("3e2="))
        assertEquals("0.03", calc("3e2n="))
        assertEquals("3e+", calc("3e"))
    }

    @Test fun digitLimit() {
        assertEquals("1,234,567,890,123,456", calc("12345678901234567890"))
    }

    // ================= 함수 =================

    @Test fun unaryFunctionsApplyImmediately() {
        val c = CalcEngine().press("5")
        c.unary(CalcFn.SQR)
        assertEquals("25", c.display)
        assertEquals("", c.expression)           // 아직 식에 안 들어감
        c.unary(CalcFn.SQRT)
        assertEquals("5", c.display)
        c.press("+3=")
        assertEquals("8", c.display)
    }

    @Test fun unaryInsideExpression() {
        val c = CalcEngine().press("2+3")
        c.unary(CalcFn.SQR)
        c.press("=")
        assertEquals("11", c.display)
        assertEquals("2 + sqr(3) =", c.expression)
    }

    @Test fun factorialAndReciprocal() {
        val c = CalcEngine().press("5")
        c.unary(CalcFn.FACT)
        assertEquals("120", c.display)
        c.clear()
        c.press("8")
        c.unary(CalcFn.RECIP)
        assertEquals("0.125", c.display)
        c.clear()
        c.press("0")
        c.unary(CalcFn.RECIP)
        assertEquals(CalcEngine.ERR_DIV0, c.display)
        c.clear()
        c.press("2.5")
        c.unary(CalcFn.FACT)
        assertEquals(CalcEngine.ERR_INVALID, c.display)
    }

    @Test fun logsAndPowers() {
        val c = CalcEngine().press("1000")
        c.unary(CalcFn.LOG)
        assertEquals("3", c.display)
        c.clear(); c.press("3")
        c.unary(CalcFn.POW10)
        assertEquals("1,000", c.display)
        c.clear(); c.press("1")
        c.unary(CalcFn.EXP)
        assertEquals("2.71828182845905", c.display)
        c.clear(); c.press("1")
        c.unary(CalcFn.LN)
        assertEquals("0", c.display)
        c.clear(); c.press("27")
        c.unary(CalcFn.CBRT)
        assertEquals("3", c.display)
    }

    @Test fun trigInDegrees() {
        val c = CalcEngine()
        c.press("30"); c.unary(CalcFn.SIN)
        assertEquals("0.5", c.display)
        c.clear(); c.press("180"); c.unary(CalcFn.SIN)
        assertEquals("0", c.display)                   // 1.2e-16 가 아니라 0
        c.clear(); c.press("90"); c.unary(CalcFn.COS)
        assertEquals("0", c.display)
        c.clear(); c.press("45"); c.unary(CalcFn.TAN)
        assertEquals("1", c.display)
        c.clear(); c.press("90"); c.unary(CalcFn.TAN)
        assertEquals(CalcEngine.ERR_UNDEF, c.display)
        c.clear(); c.press("1"); c.unary(CalcFn.ASIN)
        assertEquals("90", c.display)
        c.clear(); c.press("2"); c.unary(CalcFn.ASIN)
        assertEquals(CalcEngine.ERR_INVALID, c.display)
    }

    @Test fun trigInRadiansAndGrads() {
        val c = CalcEngine()
        c.angle = AngleUnit.RAD
        c.constant(Math.PI, "π")
        c.unary(CalcFn.COS)
        assertEquals("-1", c.display)
        c.angle = AngleUnit.GRAD
        c.clear(); c.press("100"); c.unary(CalcFn.SIN)
        assertEquals("1", c.display)
        assertEquals(AngleUnit.DEG, AngleUnit.GRAD.next())
    }

    @Test fun rootsLogsAndMod() {
        val c = CalcEngine().press("27")
        c.binary(CalcOp.YROOT); c.press("3=")
        assertEquals("3", c.display)
        c.clear(); c.press("8n")
        c.binary(CalcOp.YROOT); c.press("3=")
        assertEquals("-2", c.display)
        c.clear(); c.press("8")
        c.binary(CalcOp.LOGY); c.press("2=")
        assertEquals("3", c.display)
        assertEquals("1", calc("5nm3="))               // 부호는 나누는 수를 따른다
        assertEquals("2", calc("17m5="))
    }

    @Test fun degreeMinuteSecond() {
        assertEquals(30.30, CalcEngine.toDms(30.5), 1e-9)
        assertEquals(30.5, CalcEngine.fromDms(30.30), 1e-9)
        assertEquals(12.3045, CalcEngine.toDms(12.5125), 1e-9)
    }

    // ================= 표시 =================

    @Test fun formatting() {
        assertEquals("0", CalcEngine.format(0.0, false))
        assertEquals("100", CalcEngine.format(100.0, false))
        assertEquals("0.3", CalcEngine.format(0.1 + 0.2, false))
        assertEquals("1.e+16", CalcEngine.format(1e16, false))
        assertEquals("1.e+2", CalcEngine.format(100.0, true))
        assertEquals("1.2345e-10", CalcEngine.format(1.2345e-10, false))
        assertEquals("-1.5e+3", CalcEngine.format(-1500.0, true))
        assertEquals("1,234,567.891", CalcEngine.group("1234567.891"))
        assertEquals("-1,000", CalcEngine.group("-1000"))
        assertEquals("1,000e+5", CalcEngine.group("1000e+5"))
    }

    @Test fun scientificDisplayToggle() {
        val c = CalcEngine().press("1500=")
        c.sciNotation = true
        assertEquals("1.5e+3", c.display)
    }

    @Test fun overflow() {
        val c = CalcEngine().press("10^1000=")
        assertEquals(CalcEngine.ERR_OVERFLOW, c.display)
    }

    // ================= 메모리·기록 =================

    @Test fun memoryOperations() {
        val c = CalcEngine()
        assertNull(c.memory.firstOrNull())
        c.press("5"); c.memoryStore()
        c.press("C3"); c.memoryAdd(1)
        assertEquals(8.0, c.memory[0], 0.0)
        c.memoryAdd(-1); c.memoryAdd(-1)
        assertEquals(2.0, c.memory[0], 0.0)
        c.press("C"); c.memoryRecall()
        assertEquals("2", c.display)
        c.press("+1=")
        assertEquals("3", c.display)
        c.memoryClear()
        assertEquals(0, c.memory.size)
        c.press("C4"); c.memoryAdd(-1)                  // 비었을 때 M− 는 −값을 넣는다
        assertEquals(-4.0, c.memory[0], 0.0)
    }

    @Test fun historyRecordsFinishedCalculations() {
        val c = CalcEngine().press("2+3=").press("4x5=")
        assertEquals(2, c.history.size)
        assertEquals("4 × 5 =", c.history[0].expression)
        assertEquals(20.0, c.history[0].result, 0.0)
        assertNotNull(c.history.getOrNull(1))
        c.load(c.history[1].result)
        assertEquals("5", c.display)
    }

    @Test fun expressionLine() {
        val c = CalcEngine().press("2+3x")
        assertEquals("2 + 3 ×", c.expression)
        c.press("(4")
        assertEquals("2 + 3 × (", c.expression)
        c.press("+1)")
        assertEquals("2 + 3 × (4 + 1)", c.expression)
        assertEquals("5", c.display)
        c.press("=")
        assertEquals("17", c.display)
        assertEquals("2 + 3 × (4 + 1) =", c.expression)
    }
}
