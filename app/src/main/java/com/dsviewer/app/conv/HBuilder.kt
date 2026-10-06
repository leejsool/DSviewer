package com.dsviewer.app.conv

import com.dsviewer.app.hwp.Bullet
import com.dsviewer.app.hwp.BorderFill
import com.dsviewer.app.hwp.CharShape
import com.dsviewer.app.hwp.HCtrl
import com.dsviewer.app.hwp.HDoc
import com.dsviewer.app.hwp.HFonts
import com.dsviewer.app.hwp.HObject
import com.dsviewer.app.hwp.HPara
import com.dsviewer.app.hwp.HSection
import com.dsviewer.app.hwp.Lang
import com.dsviewer.app.hwp.Numbering
import com.dsviewer.app.hwp.PItem
import com.dsviewer.app.hwp.ParaShape

/**
 * 다른 형식(PPTX·DOCX·DOC·PPT·TXT)을 한글 문서 모델([HDoc])로 옮길 때 쓰는 도우미.
 * 글자·문단 모양을 같은 것끼리 모아 번호를 매기고, 문단의 글자 위치를 센다.
 * 그렇게 만든 HDoc은 한글 파일과 똑같이 HRenderer가 PDF로 그린다 (줄 정보가 없으니 직접 줄을 나눈다).
 */
class HBuilder {

    /** 글자 모양. 크기는 HWPUNIT (1000 = 10pt), 글자 간격은 글자 크기에 대한 % */
    data class Run(
        val latin: String = "",
        val ea: String = "",
        val size: Int = 1000,
        val color: Int = 0xFF000000.toInt(),
        val bold: Boolean = false,
        val italic: Boolean = false,
        val underline: Boolean = false,
        val strike: Boolean = false,
        val sup: Boolean = false,
        val sub: Boolean = false,
        val shade: Int? = null,
        val spacing: Int = 0,
        /** 한글·한자에 더할 글자 간격 (%) — 기기 글꼴(한글 폭 0.92)을 문서 글꼴(맑은 고딕 등, 폭 1)에 맞출 때 */
        val eaSpacing: Int = 0,
        /** 빈칸 폭 (글자 크기 비율, 0이면 글꼴 그대로) */
        val spaceEm: Float = 0f,
    )

    private val faces = ArrayList<String>()
    private val faceIds = HashMap<String, Int>()
    private val charShapes = HashMap<Int, CharShape>()
    private val csIds = HashMap<Run, Int>()
    private val paraShapes = HashMap<Int, ParaShape>()
    private val psIds = HashMap<String, Int>()
    private val borderFills = HashMap<Int, BorderFill>()
    private val bfIds = HashMap<String, Int>()
    private val numberings = HashMap<Int, Numbering>()
    private val bullets = HashMap<Int, Bullet>()
    private val bulletIds = HashMap<String, Int>()

    init {
        // 0번은 기본 모양
        charShape(Run())
        paraShape(ParaShape())
    }

    private fun face(name: String): Int = faceIds.getOrPut(name) { faces.add(name); faces.size - 1 }

    fun charShape(r: Run): Int = csIds.getOrPut(r) {
        val id = csIds.size
        val latin = face(fontName(r.latin))
        val ea = face(fontName(r.ea.ifBlank { r.latin }))
        val cs = CharShape()
        for (lang in 0 until Lang.COUNT) {
            cs.faceIds[lang] = if (lang == Lang.HANGUL || lang == Lang.HANJA || lang == Lang.JAPANESE) ea else latin
            cs.spacings[lang] = r.spacing
        }
        cs.spaceEm = r.spaceEm
        cs.eaExtraEm = r.eaSpacing / 100f
        cs.height = r.size.coerceIn(100, 100_000)
        cs.color = r.color
        cs.bold = r.bold
        cs.italic = r.italic
        if (r.underline) {
            cs.underline = "BOTTOM"
            cs.underlineColor = r.color
        }
        cs.strike = r.strike
        cs.strikeColor = r.color
        cs.supscript = r.sup
        cs.subscript = r.sub
        cs.shadeColor = r.shade
        charShapes[id] = cs
        id
    }

    fun paraShape(p: ParaShape): Int {
        val key = listOf(
            p.align, p.left, p.right, p.indent, p.prev, p.next, p.lineSpacingType, p.lineSpacing,
            p.headingType, p.headingIdRef, p.headingLevel, p.borderFillId, p.leadAbove, p.prefixTab, p.keepNext,
        ).joinToString("|")
        return psIds.getOrPut(key) {
            val id = psIds.size
            paraShapes[id] = p
            id
        }
    }

    fun paraShapeOf(id: Int): ParaShape? = paraShapes[id]

    fun borderFill(bf: BorderFill): Int {
        fun l(b: com.dsviewer.app.hwp.BorderLine) = "${b.type},${b.width},${b.color}"
        val key = listOf(l(bf.left), l(bf.right), l(bf.top), l(bf.bottom), bf.fillColor).joinToString("|")
        return bfIds.getOrPut(key) {
            val id = bfIds.size + 1
            borderFills[id] = bf
            id
        }
    }

    fun bullet(char: String): Int = bulletIds.getOrPut(char) {
        val id = bulletIds.size + 1
        bullets[id] = Bullet(char)
        id
    }

    /** 새 번호 매기기 (번호는 HRenderer가 같은 id끼리 이어서 센다) */
    fun numbering(n: Numbering): Int {
        val id = numberings.size + 1
        numberings[id] = n
        return id
    }

    fun build(sections: List<HSection>, binLoader: (String) -> ByteArray?): HDoc = HDoc(
        sections = sections,
        fontFaces = Array(Lang.COUNT) { faces.toList() },
        charShapes = charShapes,
        paraShapes = paraShapes,
        borderFills = borderFills,
        numberings = numberings,
        bullets = bullets,
        binLoader = binLoader,
    )

    /** 문단 하나 만들기: 글자 위치(한글 방식: 글자 1칸, 개체·탭 8칸)를 세며 요소를 붙인다 */
    class Para(var paraShapeId: Int = 0) {
        val items = ArrayList<PItem>()
        var pos = 0
            private set
        var pageBreak = false
        /** 개요(제목) 수준. 제목이 아니면 -1 */
        var outlineLevel = -1
        /** 글이 없는 문단의 높이를 정할 글자 모양 (문단 끝 표시의 모양) */
        var endCharShape = -1

        val isEmpty get() = items.isEmpty()
        val hasText get() = items.any { it is PItem.Text && it.text.isNotBlank() || it is PItem.Obj }

        fun text(s: String, cs: Int) {
            if (s.isEmpty()) return
            val last = items.lastOrNull()
            // 같은 모양이 이어지면 합친다
            if (last is PItem.Text && last.charShapeId == cs && last.pos + last.len == pos) {
                items[items.size - 1] = PItem.Text(last.pos, last.text + s, cs)
            } else items.add(PItem.Text(pos, s, cs))
            pos += s.length
        }

        fun tab(width: Int, cs: Int) {
            items.add(PItem.Tab(pos, width, "NONE", cs))
            pos += 8
        }

        fun obj(o: HObject, cs: Int) {
            items.add(PItem.Obj(pos, o, cs))
            pos += 8
        }

        fun ctrl(c: HCtrl, cs: Int) {
            items.add(PItem.Ctrl(pos, c, cs))
            pos += 8
        }

        fun build(): HPara {
            val list = ArrayList(items)
            // 빈 문단: 빈 글 조각으로 글자 모양을 남겨 줄 높이를 맞춘다
            if (list.none { it is PItem.Text } && endCharShape >= 0) list.add(0, PItem.Text(0, "", endCharShape))
            return HPara(paraShapeId, 0, pageBreak, false, list, emptyList()).also { it.outlineLevel = outlineLevel }
        }
    }

    companion object {
        /** EMU(1/914400 인치) → HWPUNIT(1/7200 인치) */
        fun emu(v: Long): Int = (v / 127).toInt()
        fun emu(v: Int): Int = v / 127

        /** twip(1/20 pt) → HWPUNIT */
        fun twip(v: Int): Int = v * 5

        private val SERIF_KEYS = listOf(
            "명조", "바탕", "궁서", "serif", "times", "batang", "myeongjo", "mincho", "georgia", "garamond",
            "cambria", "book", "palatino", "century", "gungsuh", "songti", "simsun", "ming",
        )

        /**
         * 기기에 있는 글꼴(고딕/명조 두 가지)로 대응시킬 이름. 한글 파일과 달리 오피스 문서는 모르는 글꼴이
         * 대개 고딕 계열(맑은 고딕, 나눔스퀘어, 프리텐다드…)이므로, 명조 쪽으로 보이는 이름이 아니면 고딕으로 한다.
         */
        fun fontName(face: String): String {
            val f = face.lowercase()
            return when {
                f.isBlank() -> "sans"
                HFonts.isSans(face) -> face
                SERIF_KEYS.any { f.contains(it) } && !f.contains("sans") -> face
                else -> "sans ($face)"
            }
        }
    }
}
