package com.dsviewer.app

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.math.truncate

/** 삼각 함수에 쓰는 각의 단위 */
enum class AngleUnit(val label: String) {
    DEG("DEG"), RAD("RAD"), GRAD("GRAD");

    fun next() = entries[(ordinal + 1) % entries.size]

    fun toRad(x: Double) = when (this) {
        DEG -> Math.toRadians(x)
        RAD -> x
        GRAD -> x * Math.PI / 200.0
    }

    fun fromRad(x: Double) = when (this) {
        DEG -> Math.toDegrees(x)
        RAD -> x
        GRAD -> x * 200.0 / Math.PI
    }
}

/** 한 항만 받는 함수 (누르면 지금 값에 바로 적용된다) */
enum class CalcFn(val label: String) {
    SQR("sqr"), CUBE("cube"), SQRT("√"), CBRT("∛"), RECIP("1/"), ABS("abs"), FACT("fact"),
    POW10("10^"), POW2("2^"), EXP("e^"), LOG("log"), LN("ln"),
    SIN("sin"), COS("cos"), TAN("tan"), SEC("sec"), CSC("csc"), COT("cot"),
    ASIN("sin⁻¹"), ACOS("cos⁻¹"), ATAN("tan⁻¹"),
    SINH("sinh"), COSH("cosh"), TANH("tanh"),
    FLOOR("floor"), CEIL("ceil"), DMS("dms"), DEGREES("deg"),
}

/** 두 항 사이에 놓는 연산. [level]이 클수록 먼저 계산한다 */
enum class CalcOp(val symbol: String, val level: Int) {
    ADD("+", 1), SUB("−", 1), MUL("×", 2), DIV("÷", 2), MOD("mod", 2),
    POW("^", 3), YROOT("yroot", 3), LOGY("logbase", 3),
}

/** 사용자에게 보여 줄 오류 (0으로 나눔, 정의되지 않음 …) */
class CalcError(message: String) : RuntimeException(message)

/**
 * 공학용 계산기의 속(계산 규칙). 화면과 떨어져 있어서 단위 시험이 된다.
 *
 * 윈도우 계산기처럼 움직인다:
 * - 숫자를 치고 + − × ÷ … 를 누르면 식에 쌓이고, = 에서 곱셈·나눗셈 먼저 같은 우선순위로 한꺼번에 계산한다.
 * - sin·x²·√ 같은 한 항 함수는 누르는 즉시 지금 보이는 값에 적용된다.
 * - 괄호는 닫는 순간 값이 하나로 줄어든다. = 를 누를 때 안 닫힌 괄호는 저절로 닫는다.
 * - 계산이 끝난 뒤 = 를 또 누르면 마지막 연산을 되풀이한다.
 */
class CalcEngine {
    private sealed class Tok {
        class Num(val v: Double, val text: String) : Tok()
        class Op(val op: CalcOp) : Tok()
        object Open : Tok()
    }

    class HistoryItem(val expression: String, val result: Double)

    var angle = AngleUnit.DEG
    var sciNotation = false

    /** 메모리 (맨 앞이 가장 최근) */
    val memory = ArrayList<Double>()

    val history = ArrayList<HistoryItem>()

    private val toks = ArrayList<Tok>()

    /** 지금 치고 있는 수의 글자 (typing일 때) */
    private var entry = "0"

    /** 치는 중이 아니면 이 값이 보인다 (함수 결과·= 결과·π 처럼) */
    private var value = 0.0
    private var typing = true

    /** 함수 결과를 식에 적을 때 쓰는 글 (예: sin(30)). 치는 수는 null */
    private var entryText: String? = null

    /** 마지막 연산자·괄호 뒤로 새 수를 넣었는가 (아니면 연산자를 또 누르면 연산자만 바뀐다) */
    private var pending = false

    /** = 를 누른 직후 식 줄에 남겨 두는 글 */
    private var finishedExpr: String? = null
    private var repeatOp: CalcOp? = null
    private var repeatArg = 0.0

    var error: String? = null
        private set

    // ================= 보이는 것 =================

    /** 큰 칸에 보일 글 (정수 부분에 천 단위 쉼표) */
    val display: String
        get() = error ?: group(if (typing) entry else format(value, sciNotation))

    /** 작은 식 줄 */
    val expression: String
        get() = if (error != null) "" else finishedExpr ?: toks.joinToString(" ") { tokText(it) }

    /** 지금 보이는 값 (복사·메모리에 쓴다). 오류면 null */
    fun currentValue(): Double? = if (error != null) null else current()

    private fun current(): Double = if (typing) parseEntry() else value

    private fun parseEntry(): Double =
        entry.trimEnd('e', '+', '-').ifEmpty { "0" }.toDoubleOrNull() ?: 0.0

    private fun tokText(t: Tok) = when (t) {
        is Tok.Num -> t.text
        is Tok.Op -> t.op.symbol
        Tok.Open -> "("
    }

    // ================= 수 넣기 =================

    /** 새 수를 넣기 시작한다: 끝난 계산은 버리고, 닫은 괄호 값 뒤에 바로 넣는 수는 그 값을 대신한다 */
    private fun startOperand() {
        if (finishedExpr != null) {
            finishedExpr = null
            toks.clear()
        } else if (toks.lastOrNull() is Tok.Num) {
            toks.removeAt(toks.size - 1)
        }
        pending = true
    }

    /** 결과로 나온 값을 지금 값으로 둔다 */
    private fun setValue(v: Double, text: String?) {
        value = v
        typing = false
        entryText = text
    }

    private fun typeFresh() {
        startOperand()
        if (!typing) {
            entry = "0"
            typing = true
            entryText = null
        }
    }

    fun digit(d: Int) {
        require(d in 0..9)
        if (error != null) clear()
        typeFresh()
        if ('e' in entry) {
            // 지수는 세 자리까지
            if (entry.substringAfter('e').trimStart('+', '-').length >= 3) return
            entry += d.toString()
            return
        }
        if (entry.count { it.isDigit() } >= MAX_DIGITS) return
        entry = when (entry) {
            "0" -> d.toString()
            "-0" -> "-$d"
            else -> entry + d
        }
    }

    fun dot() {
        if (error != null) clear()
        typeFresh()
        if ('e' in entry || '.' in entry) return
        entry += "."
    }

    /** 지수 입력 (3 exp 2 → 3×10²) */
    fun exponent() {
        if (error != null) return
        if (!typing) {
            val plain = format(value, false)
            startOperand()
            entry = if ('e' in plain) "0" else plain
            typing = true
            entryText = null
        } else startOperand()
        if ('e' in entry) return
        entry = entry.trimEnd('.') + "e+"
    }

    fun backspace() {
        if (error != null || !typing) return  // 결과는 한 자리씩 못 지운다
        entry = entry.dropLast(1)
        if (entry.isEmpty() || entry == "-") entry = "0"
        if (entry.endsWith("e")) entry = entry.dropLast(1)  // 지수 입력 취소
    }

    fun negate() {
        if (error != null) return
        if (typing) {
            val e = entry.indexOf('e')
            entry = when {
                e >= 0 -> {
                    val sign = if (entry.getOrNull(e + 1) == '-') '+' else '-'
                    entry.substring(0, e + 1) + sign + entry.substring(e + 2)
                }
                entry.startsWith("-") -> entry.drop(1)
                entry == "0" -> "0"
                else -> "-$entry"
            }
            startOperand()
        } else {
            val x = value
            val shown = entryText ?: format(x, false)
            startOperand()
            setValue(-x, "negate($shown)")
        }
    }

    fun constant(v: Double, name: String) {
        if (error != null) clear()
        startOperand()
        setValue(v, name)
    }

    /** 0~1 사이 무작위 수 */
    fun random() {
        if (error != null) clear()
        startOperand()
        setValue(Math.random(), null)
    }

    /** 기록·메모리에서 값을 불러온다 */
    fun load(v: Double) {
        if (error != null) clear()
        startOperand()
        setValue(v, null)
    }

    // ================= 한 항 함수 =================

    fun unary(fn: CalcFn) {
        if (error != null) return
        try {
            val x = current()
            val shown = entryText ?: format(x, false)
            val r = checked(applyFn(fn, x, angle))
            startOperand()
            setValue(r, if (fn == CalcFn.RECIP) "1/($shown)" else "${fn.label}($shown)")
        } catch (e: CalcError) {
            fail(e.message ?: ERR_INVALID)
        }
    }

    // ================= 연산·괄호·= =================

    fun binary(op: CalcOp) {
        if (error != null) return
        if (finishedExpr != null) {
            // 결과에서 이어 간다
            val v = current()
            toks.clear()
            finishedExpr = null
            toks.add(Tok.Num(v, format(v, false)))
        } else if (toks.lastOrNull() is Tok.Op && !pending) {
            toks[toks.size - 1] = Tok.Op(op)  // 연산자를 바로 바꿔 누름
            return
        } else {
            commit()
        }
        toks.add(Tok.Op(op))
        pending = false
        setValue((toks[toks.size - 2] as Tok.Num).v, null)
    }

    /** 지금 값을 피연산자로 식에 넣는다 (닫은 괄호 값처럼 이미 들어가 있으면 그대로) */
    private fun commit() {
        if (toks.lastOrNull() is Tok.Num) return
        val v = current()
        toks.add(Tok.Num(v, entryText ?: format(v, false)))
    }

    fun openParen() {
        if (error != null) return
        if (finishedExpr != null) {
            finishedExpr = null
            toks.clear()
            pending = false
        }
        // 수 뒤에 바로 괄호가 오면 곱셈으로 본다
        if (toks.lastOrNull() is Tok.Num) toks.add(Tok.Op(CalcOp.MUL))
        else if (pending) {
            commit()
            toks.add(Tok.Op(CalcOp.MUL))
        }
        toks.add(Tok.Open)
        pending = false
        entry = "0"
        setValue(0.0, null)
        typing = true
    }

    private fun openDepth() = toks.count { it is Tok.Open }

    fun closeParen() {
        if (error != null || openDepth() == 0) return
        try {
            commit()
            collapseOne()
        } catch (e: CalcError) {
            fail(e.message ?: ERR_INVALID)
        }
    }

    /** 가장 안쪽 괄호 하나를 값 하나로 줄인다 */
    private fun collapseOne() {
        val open = toks.indexOfLast { it === Tok.Open }
        val inner = toks.subList(open + 1, toks.size).toList()
        val v = evalFlat(inner)
        while (toks.size > open) toks.removeAt(toks.size - 1)
        val text = "(" + inner.joinToString(" ") { tokText(it) } + ")"
        toks.add(Tok.Num(v, text))
        pending = true
        setValue(v, text)
    }

    fun equals() {
        if (error != null) return
        try {
            val repeat = repeatOp
            if (finishedExpr != null) {
                if (repeat == null) return  // 되풀이할 연산이 없는 결과
                // 마지막 연산을 되풀이 (5 + 2 = = → 9)
                val a = current()
                val r = checked(applyOp(repeat, a, repeatArg))
                finish("${format(a, false)} ${repeat.symbol} ${format(repeatArg, false)} =", r)
                return
            }
            if (toks.isEmpty()) {
                // 식 없이 = : 지금 값 그대로
                val v = current()
                val text = "${entryText ?: format(v, false)} ="
                repeatOp = null
                finishedExpr = text
                setValue(v, null)
                return
            }
            commit()
            while (openDepth() > 0) collapseOne()
            val text = toks.joinToString(" ") { tokText(it) } + " ="
            // 단순한 'a op b'면 되풀이할 수 있게 적어 둔다
            repeatOp = null
            if (toks.size == 3 && toks[1] is Tok.Op) {
                repeatOp = (toks[1] as Tok.Op).op
                repeatArg = (toks[2] as Tok.Num).v
            }
            finish(text, evalFlat(toks.toList()))
        } catch (e: CalcError) {
            fail(e.message ?: ERR_INVALID)
        }
    }

    private fun finish(text: String, r: Double) {
        history.add(0, HistoryItem(text, r))
        if (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
        finishedExpr = text
        toks.clear()
        pending = false
        setValue(r, null)
    }

    /** C: 전부 지운다 */
    fun clear() {
        toks.clear()
        entry = "0"
        value = 0.0
        typing = true
        entryText = null
        pending = false
        finishedExpr = null
        repeatOp = null
        error = null
    }

    private fun fail(msg: String) {
        error = msg
        toks.clear()
        finishedExpr = null
        repeatOp = null
        pending = false
    }

    // ================= 메모리 =================

    fun memoryClear() = memory.clear()

    fun memoryStore() {
        val v = currentValue() ?: return
        memory.add(0, v)
        if (memory.size > MAX_MEMORY) memory.removeAt(memory.size - 1)
    }

    fun memoryRecall(index: Int = 0) {
        val v = memory.getOrNull(index) ?: return
        load(v)
    }

    fun memoryAdd(sign: Int) {
        val v = currentValue() ?: return
        if (memory.isEmpty()) memory.add(sign * v) else memory[0] = memory[0] + sign * v
    }

    // ================= 계산 =================

    private fun evalFlat(list: List<Tok>): Double {
        val nums = ArrayList<Double>()
        val ops = ArrayList<CalcOp>()
        for (t in list) when (t) {
            is Tok.Num -> nums.add(t.v)
            is Tok.Op -> ops.add(t.op)
            Tok.Open -> throw CalcError(ERR_INVALID)
        }
        if (nums.isEmpty()) return 0.0
        if (ops.size != nums.size - 1) throw CalcError(ERR_INVALID)
        // 거듭제곱 계열은 오른쪽부터 (2^3^2 = 2^9)
        var i = ops.size - 1
        while (i >= 0) {
            if (ops[i].level == 3) {
                nums[i] = applyOp(ops[i], nums[i], nums[i + 1])
                nums.removeAt(i + 1)
                ops.removeAt(i)
            }
            i--
        }
        for (level in 2 downTo 1) {
            i = 0
            while (i < ops.size) {
                if (ops[i].level == level) {
                    nums[i] = applyOp(ops[i], nums[i], nums[i + 1])
                    nums.removeAt(i + 1)
                    ops.removeAt(i)
                } else i++
            }
        }
        return checked(nums[0])
    }

    companion object {
        const val MAX_DIGITS = 16
        const val MAX_HISTORY = 50
        const val MAX_MEMORY = 20
        const val ERR_DIV0 = "0으로 나눌 수 없습니다"
        const val ERR_UNDEF = "정의되지 않았습니다"
        const val ERR_INVALID = "잘못된 입력입니다"
        const val ERR_OVERFLOW = "범위를 넘었습니다"

        private fun checked(v: Double): Double {
            if (v.isNaN()) throw CalcError(ERR_INVALID)
            if (v.isInfinite()) throw CalcError(ERR_OVERFLOW)
            return v
        }

        /** 계산 오차(sin 180° = 1.2e-16 같은)를 0으로 눌러 준다 */
        private fun tidy(v: Double) = if (abs(v) < 1e-14) 0.0 else v

        fun applyOp(op: CalcOp, a: Double, b: Double): Double = when (op) {
            CalcOp.ADD -> a + b
            CalcOp.SUB -> a - b
            CalcOp.MUL -> a * b
            CalcOp.DIV -> {
                if (b == 0.0) throw CalcError(if (a == 0.0) ERR_UNDEF else ERR_DIV0)
                a / b
            }
            CalcOp.MOD -> {
                if (b == 0.0) throw CalcError(ERR_DIV0)
                val r = a % b
                // 부호는 나누는 수를 따른다 (-5 mod 3 = 1)
                if (r != 0.0 && (r < 0) != (b < 0)) r + b else r
            }
            CalcOp.POW -> {
                if (a == 0.0 && b == 0.0) throw CalcError(ERR_UNDEF)
                if (a == 0.0 && b < 0) throw CalcError(ERR_DIV0)
                a.pow(b)
            }
            CalcOp.YROOT -> root(a, b)
            CalcOp.LOGY -> {
                // log_b(a)
                if (a <= 0 || b <= 0 || b == 1.0) throw CalcError(ERR_INVALID)
                ln(a) / ln(b)
            }
        }

        /** a의 b제곱근 (음수는 홀수 정수 제곱근만) */
        private fun root(a: Double, b: Double): Double {
            if (b == 0.0) throw CalcError(ERR_INVALID)
            if (a < 0) {
                val odd = b == floor(b) && abs(b % 2.0) == 1.0
                if (!odd) throw CalcError(ERR_INVALID)
                return -((-a).pow(1.0 / b))
            }
            if (a == 0.0 && b < 0) throw CalcError(ERR_DIV0)
            return a.pow(1.0 / b)
        }

        fun applyFn(fn: CalcFn, x: Double, angle: AngleUnit): Double = when (fn) {
            CalcFn.SQR -> x * x
            CalcFn.CUBE -> x * x * x
            CalcFn.SQRT -> { if (x < 0) throw CalcError(ERR_INVALID); sqrt(x) }
            CalcFn.CBRT -> Math.cbrt(x)
            CalcFn.RECIP -> { if (x == 0.0) throw CalcError(ERR_DIV0); 1.0 / x }
            CalcFn.ABS -> abs(x)
            CalcFn.FACT -> factorial(x)
            CalcFn.POW10 -> 10.0.pow(x)
            CalcFn.POW2 -> 2.0.pow(x)
            CalcFn.EXP -> kotlin.math.exp(x)
            CalcFn.LOG -> { if (x <= 0) throw CalcError(ERR_INVALID); roundLog(log10(x)) }
            CalcFn.LN -> { if (x <= 0) throw CalcError(ERR_INVALID); ln(x) }
            CalcFn.SIN -> tidy(sin(angle.toRad(x)))
            CalcFn.COS -> tidy(cos(angle.toRad(x)))
            CalcFn.TAN -> {
                val c = tidy(cos(angle.toRad(x)))
                if (c == 0.0) throw CalcError(ERR_UNDEF)
                tidy(tan(angle.toRad(x)))
            }
            CalcFn.SEC -> {
                val c = tidy(cos(angle.toRad(x)))
                if (c == 0.0) throw CalcError(ERR_UNDEF)
                1.0 / c
            }
            CalcFn.CSC -> {
                val s = tidy(sin(angle.toRad(x)))
                if (s == 0.0) throw CalcError(ERR_UNDEF)
                1.0 / s
            }
            CalcFn.COT -> {
                val s = tidy(sin(angle.toRad(x)))
                if (s == 0.0) throw CalcError(ERR_UNDEF)
                tidy(cos(angle.toRad(x))) / s
            }
            CalcFn.ASIN -> { if (x < -1 || x > 1) throw CalcError(ERR_INVALID); tidy(angle.fromRad(asin(x))) }
            CalcFn.ACOS -> { if (x < -1 || x > 1) throw CalcError(ERR_INVALID); tidy(angle.fromRad(acos(x))) }
            CalcFn.ATAN -> tidy(angle.fromRad(atan(x)))
            CalcFn.SINH -> sinh(x)
            CalcFn.COSH -> cosh(x)
            CalcFn.TANH -> tanh(x)
            CalcFn.FLOOR -> floor(x)
            CalcFn.CEIL -> ceil(x)
            CalcFn.DMS -> toDms(x)
            CalcFn.DEGREES -> fromDms(x)
        }

        /** log10(1000) = 2.9999999999999996 같은 것을 다듬는다 */
        private fun roundLog(v: Double): Double {
            val r = Math.rint(v)
            return if (abs(v - r) < 1e-13) r else v
        }

        private fun factorial(x: Double): Double {
            if (x < 0 || x != floor(x)) throw CalcError(ERR_INVALID)
            if (x > 170) throw CalcError(ERR_OVERFLOW)
            var r = 1.0
            for (i in 2..x.toInt()) r *= i
            return r
        }

        /** 도(소수) → 도.분초 (30.5 → 30.30) */
        fun toDms(x: Double): Double {
            val sign = if (x < 0) -1 else 1
            val a = abs(x)
            val d = truncate(a)
            val mFull = (a - d) * 60
            val m = truncate(mFull + 1e-9)
            val s = (mFull - m) * 60
            val r = d + m / 100.0 + s / 10000.0
            return sign * BigDecimal(r).setScale(10, RoundingMode.HALF_UP).toDouble()
        }

        /** 도.분초 → 도(소수) (30.30 → 30.5) */
        fun fromDms(x: Double): Double {
            val sign = if (x < 0) -1 else 1
            val a = abs(x)
            val d = truncate(a)
            val mFull = (a - d) * 100
            val m = truncate(mFull + 1e-9)
            val s = (mFull - m) * 100
            val r = d + m / 60.0 + s / 3600.0
            return sign * BigDecimal(r).setScale(10, RoundingMode.HALF_UP).toDouble()
        }

        // ================= 글자 꾸미기 =================

        /** 값을 보여 줄 글로 (15자리 유효숫자, 끝의 0 제거). [sci]면 늘 과학 표기 */
        fun format(v: Double, sci: Boolean): String {
            if (v == 0.0) return if (sci) "0.e+0" else "0"
            val bd = BigDecimal(v).round(MathContext(15, RoundingMode.HALF_UP)).stripTrailingZeros()
            val a = abs(v)
            if (sci || a >= 1e16 || a < 1e-9) return sciText(bd)
            return bd.toPlainString()
        }

        private fun sciText(bd: BigDecimal): String {
            val unscaled = bd.unscaledValue().abs().toString()
            val exp = unscaled.length - 1 - bd.scale()
            val mant = if (unscaled.length > 1) unscaled[0] + "." + unscaled.substring(1) else "$unscaled."
            val sign = if (bd.signum() < 0) "-" else ""
            return "$sign$mant" + "e" + (if (exp >= 0) "+" else "-") + abs(exp)
        }

        /** 보여 줄 글의 정수 부분에 쉼표를 넣는다 */
        fun group(s: String): String {
            val e = s.indexOf('e')
            if (e >= 0) return group(s.substring(0, e)) + s.substring(e)
            val neg = s.startsWith("-")
            val body = if (neg) s.drop(1) else s
            val dot = body.indexOf('.')
            val int = if (dot >= 0) body.substring(0, dot) else body
            val frac = if (dot >= 0) body.substring(dot) else ""
            val sb = StringBuilder()
            for ((i, c) in int.withIndex()) {
                if (i > 0 && (int.length - i) % 3 == 0) sb.append(',')
                sb.append(c)
            }
            return (if (neg) "-" else "") + sb + frac
        }
    }
}
