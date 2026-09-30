package com.dsviewer.app.conv

/**
 * 워드 수식(OMML, m:oMath) → 한글 수식 스크립트. 그리기는 한글 수식과 같은 EqRenderer가 한다.
 * 예: 분수 m:f → `{a} over {b}`, 근호 m:rad → `sqrt {x}`, 위첨자 m:sSup → `{x} ^{2}`
 */
object OmmlScript {

    fun convert(math: XNode): String = children(math).trim()

    private fun children(n: XNode): String = n.children.joinToString(" ") { node(it) }.trim()

    private fun arg(n: XNode?, name: String): String = n?.child(name)?.let { children(it) } ?: ""

    private fun group(s: String) = "{$s}"

    private fun node(n: XNode): String = when (n.name) {
        "r" -> run(n)
        "e", "num", "den", "sub", "sup", "deg", "fName", "lim", "oMath", "box", "borderBox", "phant" ->
            if (n.name == "phant" && n.path("phantPr", "show")?.get("val") == "0") "" else children(n)
        "f" -> {
            val num = group(arg(n, "num"))
            val den = group(arg(n, "den"))
            when (n.path("fPr", "type")?.get("val")) {
                "lin" -> "$num / $den"
                "noBar" -> "$num atop $den"
                else -> "$num over $den"
            }
        }
        "sSup" -> "${group(arg(n, "e"))} ^${group(arg(n, "sup"))}"
        "sSub" -> "${group(arg(n, "e"))} _${group(arg(n, "sub"))}"
        "sSubSup" -> "${group(arg(n, "e"))} _${group(arg(n, "sub"))} ^${group(arg(n, "sup"))}"
        "sPre" -> "{} _${group(arg(n, "sub"))} ^${group(arg(n, "sup"))} ${group(arg(n, "e"))}"
        "rad" -> {
            val deg = arg(n, "deg")
            val hide = n.path("radPr", "degHide")?.get("val").let { it == "1" || it == "on" || it == "true" }
            if (deg.isBlank() || hide) "sqrt ${group(arg(n, "e"))}" else "root ${group(deg)} of ${group(arg(n, "e"))}"
        }
        "d" -> delim(n)
        "nary" -> nary(n)
        "func" -> "${arg(n, "fName")} ${group(arg(n, "e"))}"
        "limLow" -> "${group(arg(n, "e"))} _${group(arg(n, "lim"))}"
        "limUpp" -> "${group(arg(n, "e"))} ^${group(arg(n, "lim"))}"
        "acc" -> {
            val chr = n.path("accPr", "chr")?.get("val") ?: "\u0302"
            val cmd = when (chr) {
                "\u0303", "~" -> "tilde"
                "\u0307", "˙" -> "dot"
                "\u0308", "¨" -> "ddot"
                "\u20D7", "\u20D1", "→" -> "vec"
                "\u0305", "¯", "‾" -> "bar"
                "\u030C" -> "check"
                "\u0301" -> "acute"
                "\u0300" -> "grave"
                "\u0306" -> "arch"
                else -> "hat"
            }
            "$cmd ${group(arg(n, "e"))}"
        }
        "bar" -> if (n.path("barPr", "pos")?.get("val") == "bot") "underline ${group(arg(n, "e"))}"
        else "overline ${group(arg(n, "e"))}"
        // 묶음 기호(⏟ 등)는 그리지 않고 안의 식만
        "groupChr" -> group(arg(n, "e"))
        "m" -> "matrix {" + n.children("mr").joinToString(" # ") { mr -> mr.children("e").joinToString(" & ") { children(it) } } + "}"
        "eqArr" -> "pile {" + n.children("e").joinToString(" # ") { children(it) } + "}"
        "oMathPara" -> n.children("oMath").joinToString(" # ") { children(it) }
        // 속성들 (…Pr), 워드 글자 모양 등은 건너뛴다
        else -> if (n.name.endsWith("Pr")) "" else children(n)
    }

    private fun delim(n: XNode): String {
        val pr = n.child("dPr")
        val beg = pr?.child("begChr")?.get("val") ?: "("
        val end = pr?.child("endChr")?.get("val") ?: ")"
        val sep = pr?.child("sepChr")?.get("val") ?: "|"
        val es = n.children("e")
        // 경우 나누기: { 만 있고 안에 여러 줄
        if (beg == "{" && end.isEmpty() && es.size == 1) {
            val arr = es[0].child("eqArr")
            if (arr != null) return "cases {" + arr.children("e").joinToString(" # ") { children(it) } + "}"
        }
        val body = es.joinToString(" ${sym(sep)} ") { children(it) }
        val l = delimName(beg)
        val r = delimName(end)
        return if (l != null && r != null) "left $l $body right $r" else "${sym(beg)} $body ${sym(end)}"
    }

    private fun delimName(c: String): String? = when (c) {
        "(", ")", "[", "]", "|" -> c
        "{" -> "lbrace"
        "}" -> "rbrace"
        "⟨", "〈" -> "langle"
        "⟩", "〉" -> "rangle"
        "‖" -> "dline"
        "⌊" -> "lfloor"
        "⌋" -> "rfloor"
        "⌈" -> "lceil"
        "⌉" -> "rceil"
        "" -> null
        else -> null
    }

    private fun nary(n: XNode): String {
        val pr = n.child("naryPr")
        val chr = pr?.child("chr")?.get("val") ?: "∫"
        val cmd = when (chr) {
            "∑" -> "sum"
            "∏" -> "prod"
            "∐" -> "coprod"
            "∬" -> "dint"
            "∭" -> "tint"
            "∮" -> "oint"
            "⋃" -> "bigcup"
            "⋂" -> "bigcap"
            else -> "int"
        }
        fun hidden(name: String) = pr?.child(name)?.get("val").let { it == "1" || it == "on" || it == "true" }
        val sub = if (hidden("subHide")) "" else arg(n, "sub")
        val sup = if (hidden("supHide")) "" else arg(n, "sup")
        val sb = StringBuilder(cmd)
        if (cmd == "int" || cmd == "dint" || cmd == "tint" || cmd == "oint") {
            if (sub.isNotBlank()) sb.append(" _").append(group(sub))
            if (sup.isNotBlank()) sb.append(" ^").append(group(sup))
        } else {
            if (sub.isNotBlank()) sb.append(" from ").append(group(sub))
            if (sup.isNotBlank()) sb.append(" to ").append(group(sup))
        }
        sb.append(' ').append(group(arg(n, "e")))
        return sb.toString()
    }

    /** 수식 안의 글: 기호는 한글 수식 명령으로, 한글은 따옴표로 */
    private fun run(r: XNode): String {
        val t = r.children("t").joinToString("") { it.text }
        if (t.isEmpty()) return ""
        val plain = r.path("rPr", "nor") != null || r.path("rPr", "sty")?.get("val") == "p"
        val sb = StringBuilder()
        var i = 0
        while (i < t.length) {
            val c = t[i]
            when {
                c in '가'..'힣' || c in 'ㄱ'..'ㆎ' -> {
                    var j = i
                    while (j < t.length && (t[j] in '가'..'힣' || t[j] in 'ㄱ'..'ㆎ' || t[j] == ' ')) j++
                    sb.append(" \"").append(t, i, j).append("\" ")
                    i = j
                    continue
                }
                c == ' ' -> sb.append(" ~ ")
                else -> sb.append(sym(c.toString()))
            }
            i++
        }
        val s = sb.toString()
        return if (plain && s.any { it.isLetter() && it.code < 0x80 }) "rm {$s}" else s
    }

    private fun sym(c: String): String = when (c) {
        "{" -> " lbrace "
        "}" -> " rbrace "
        "^", "_", "#", "&", "~", "`", "\"", "\\" -> " "
        "≤" -> " le "
        "≥" -> " ge "
        "≠" -> " != "
        "±" -> " +- "
        "∓" -> " -+ "
        "×" -> " times "
        "÷" -> " div "
        "⋅", "·" -> " cdot "
        "∞" -> " inf "
        "→" -> " -> "
        "←" -> " larrow "
        "⇒" -> " Rarrow "
        "⇔" -> " LRarrow "
        "∈" -> " in "
        "∉" -> " notin "
        "⊂" -> " subset "
        "⊆" -> " subseteq "
        "∪" -> " cup "
        "∩" -> " cap "
        "∅" -> " emptyset "
        "∴" -> " therefore "
        "∵" -> " because "
        "∠" -> " angle "
        "⊥" -> " perp "
        "∥" -> " parallel "
        "≈" -> " approx "
        "≡" -> " equiv "
        "′" -> " prime "
        "°" -> " deg "
        "∂" -> " partial "
        "∇" -> " nabla "
        "…" -> " cdots "
        "⋯" -> " cdots "
        else -> c
    }
}
