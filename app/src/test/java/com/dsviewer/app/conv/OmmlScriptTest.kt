package com.dsviewer.app.conv

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 워드 수식(OMML) → 한글 수식 스크립트. XML을 읽는 대신 나무를 손으로 만들어 변환 규칙만 본다.
 * 빈칸은 한 칸으로 줄여 비교한다 (스크립트에서 빈칸 수는 뜻이 없다).
 */
class OmmlScriptTest {

    // ---- 나무 만들기 ----
    private fun n(name: String, vararg kids: XNode, attrs: Map<String, String> = emptyMap()) =
        XNode(name, attrs).also { it.children.addAll(kids) }

    /** 수식 안의 글: m:r > m:t */
    private fun r(text: String, vararg props: XNode) = n("r", *props, XNode("t", emptyMap()).also { it.addText(text) })
    private fun e(vararg kids: XNode) = n("e", *kids)
    private fun val_(name: String, v: String) = n(name, attrs = mapOf("val" to v))

    private fun script(vararg kids: XNode): String =
        OmmlScript.convert(n("oMath", *kids)).replace(Regex("\\s+"), " ").trim()

    // ---- 분수·첨자·근호 ----
    @Test fun fraction() {
        assertEquals("{a} over {b}", script(n("f", n("num", r("a")), n("den", r("b")))))
    }

    @Test fun fractionKinds() {
        val lin = n("f", n("fPr", val_("type", "lin")), n("num", r("a")), n("den", r("b")))
        assertEquals("{a} / {b}", script(lin))
        val noBar = n("f", n("fPr", val_("type", "noBar")), n("num", r("a")), n("den", r("b")))
        assertEquals("{a} atop {b}", script(noBar))
    }

    @Test fun nestedFraction() {
        val inner = n("f", n("num", r("1")), n("den", r("2")))
        assertEquals("{{1} over {2}} over {x}", script(n("f", n("num", inner), n("den", r("x")))))
    }

    @Test fun superAndSubscripts() {
        assertEquals("{x} ^{2}", script(n("sSup", e(r("x")), n("sup", r("2")))))
        assertEquals("{a} _{n}", script(n("sSub", e(r("a")), n("sub", r("n")))))
        assertEquals("{x} _{i} ^{2}", script(n("sSubSup", e(r("x")), n("sub", r("i")), n("sup", r("2")))))
    }

    @Test fun squareAndNthRoots() {
        assertEquals("sqrt {x}", script(n("rad", e(r("x")))))
        assertEquals("root {3} of {x}", script(n("rad", n("deg", r("3")), e(r("x")))))
        // 차수를 숨기면 제곱근
        assertEquals("sqrt {x}", script(n("rad", n("radPr", val_("degHide", "1")), n("deg", r("3")), e(r("x")))))
    }

    // ---- 큰 연산자·괄호 ----
    @Test fun summationUsesFromTo() {
        val sum = n("nary", n("naryPr", val_("chr", "∑")), n("sub", r("i=1")), n("sup", r("n")), e(r("a")))
        assertEquals("sum from {i=1} to {n} {a}", script(sum))
    }

    @Test fun integralUsesSubSuperscripts() {
        val int = n("nary", n("naryPr", val_("chr", "∫")), n("sub", r("0")), n("sup", r("1")), e(r("x")))
        assertEquals("int _{0} ^{1} {x}", script(int))
        // 기호를 안 정하면 적분
        assertEquals("int {x}", script(n("nary", n("naryPr", val_("subHide", "1"), val_("supHide", "1")), e(r("x")))))
    }

    @Test fun hiddenLimitsAreDropped() {
        val sum = n("nary", n("naryPr", val_("chr", "∑"), val_("supHide", "1")), n("sub", r("k")), n("sup", r("n")), e(r("a")))
        assertEquals("sum from {k} {a}", script(sum))
    }

    @Test fun delimiters() {
        assertEquals("left ( x right )", script(n("d", e(r("x")))))
        val brace = n("d", n("dPr", val_("begChr", "{"), val_("endChr", "}")), e(r("x")))
        assertEquals("left lbrace x right rbrace", script(brace))
        val abs = n("d", n("dPr", val_("begChr", "|"), val_("endChr", "|")), e(r("x")))
        assertEquals("left | x right |", script(abs))
    }

    @Test fun casesFromOpenBraceAndEquationArray() {
        val cases = n("d", n("dPr", val_("begChr", "{"), val_("endChr", "")), e(n("eqArr", e(r("a")), e(r("b")))))
        assertEquals("cases {a # b}", script(cases))
    }

    @Test fun matrixRowsAndColumns() {
        val m = n("m", n("mr", e(r("a")), e(r("b"))), n("mr", e(r("c")), e(r("d"))))
        assertEquals("matrix {a & b # c & d}", script(m))
    }

    // ---- 꾸밈 ----
    @Test fun accentsAndBars() {
        assertEquals("vec {v}", script(n("acc", n("accPr", val_("chr", "→")), e(r("v")))))
        assertEquals("bar {x}", script(n("acc", n("accPr", val_("chr", "¯")), e(r("x")))))
        assertEquals("hat {x}", script(n("acc", e(r("x")))))
        assertEquals("overline {AB}", script(n("bar", e(r("AB")))))
        assertEquals("underline {AB}", script(n("bar", n("barPr", val_("pos", "bot")), e(r("AB")))))
    }

    @Test fun functionAndLimit() {
        assertEquals("sin {x}", script(n("func", n("fName", r("sin")), e(r("x")))))
        assertEquals("{lim} _{x -> 0}", script(n("limLow", e(r("lim")), n("lim", r("x→0")))))
    }

    // ---- 글 ----
    @Test fun koreanTextIsQuoted() {
        assertEquals("\"속도\" =v", script(r("속도=v")))
        // 한글 덩어리 하나가 따옴표 하나: 영어·기호와 섞여도 한글 부분만
        assertEquals("v= \"거리\" /t", script(r("v=거리/t")))
    }

    @Test fun symbolsBecomeCommands() {
        assertEquals("a le b", script(r("a≤b")))
        assertEquals("x times y", script(r("x×y")))
        assertEquals("inf", script(r("∞")))
        assertEquals("A cup B", script(r("A∪B")))
        assertEquals("angle ABC", script(r("∠ABC")))
    }

    @Test fun reservedCharactersAreNeutralized() {
        // 한글 수식에서 뜻이 있는 문자(^ _ # & ~ ` " \)는 빈칸으로 바꿔 수식이 깨지지 않게
        assertEquals("a b", script(r("a^b")))
        assertEquals("a b", script(r("a#b")))
        assertEquals("lbrace x rbrace", script(r("{x}")))
    }

    @Test fun spacesBecomeTilde() {
        assertEquals("a ~ b", script(r("a b")))
    }

    @Test fun normalTextRunIsRoman() {
        assertEquals("rm {cm}", script(r("cm", n("rPr", n("nor")))))
        assertEquals("rm {sin}", script(r("sin", n("rPr", val_("sty", "p")))))
    }

    // ---- 그 밖 ----
    @Test fun hiddenPhantomAndPropertiesProduceNothing() {
        assertEquals("a b", script(r("a"), n("phant", n("phantPr", val_("show", "0")), e(r("zzz"))), r("b")))
        assertEquals("a", script(r("a"), n("ctrlPr", r("junk"))))
    }

    @Test fun mathParagraphJoinsLinesWithPile() {
        // oMathPara는 수식 안의 노드: 줄들을 #으로 잇는다
        assertEquals("a # b", script(n("oMathPara", n("oMath", r("a")), n("oMath", r("b")))))
    }

    @Test fun emptyRunAndEmptyMath() {
        assertEquals("", script())
        assertEquals("", script(n("r")))
    }
}
