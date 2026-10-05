package com.dsviewer.app.hwp

/*
 * HWP/HWPX 공통 문서 모델.
 * 길이 단위는 모두 HWPUNIT (1/7200 인치). PDF 포인트 = HWPUNIT / 100.
 * HWP(바이너리)와 HWPX(XML) 리더가 모두 이 모델을 만들고, HRenderer가 PDF로 그린다.
 */

/** 언어 구분 (HWP 글자 모양의 언어별 설정 순서) */
object Lang {
    const val HANGUL = 0
    const val LATIN = 1
    const val HANJA = 2
    const val JAPANESE = 3
    const val OTHER = 4
    const val SYMBOL = 5
    const val USER = 6
    const val COUNT = 7

    fun of(c: Char): Int {
        val code = c.code
        return when {
            code in 0xAC00..0xD7A3 || code in 0x1100..0x11FF || code in 0x3130..0x318F || code in 0xA960..0xA97F || code in 0xD7B0..0xD7FF -> HANGUL
            code < 0x2000 -> LATIN
            code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF || code in 0xF900..0xFAFF -> HANJA
            code in 0x3040..0x30FF -> JAPANESE
            code in 0x2000..0x2BFF || code in 0x3000..0x303F || code in 0x3200..0x33FF || code in 0xFF00..0xFFEF -> SYMBOL
            else -> OTHER
        }
    }
}

class CharShape(
    val faceIds: IntArray = IntArray(Lang.COUNT),
    val ratios: IntArray = IntArray(Lang.COUNT) { 100 },
    val spacings: IntArray = IntArray(Lang.COUNT),
    val relSizes: IntArray = IntArray(Lang.COUNT) { 100 },
    val offsets: IntArray = IntArray(Lang.COUNT),
) {
    var height = 1000
    var color = 0xFF000000.toInt()
    var shadeColor: Int? = null
    var bold = false
    var italic = false
    /** 밑줄 위치: null(없음), "BOTTOM", "CENTER", "TOP" */
    var underline: String? = null
    var underlineShape = "SOLID"
    var underlineColor = 0xFF000000.toInt()
    var strike = false
    var strikeColor = 0xFF000000.toInt()
    var supscript = false
    var subscript = false
    var outline = false
    /** 빈칸 폭 (글자 크기에 대한 비율). 0이면 글꼴 그대로 (워드 문서를 워드와 같은 폭으로 나누려고) */
    var spaceEm = 0f
    /** 한글·한자 글자마다 더할 폭 (글자 크기 비율). 기기 글꼴 한글(0.92)을 문서 글꼴 폭(1.0)에 맞출 때 */
    var eaExtraEm = 0f
}

enum class Align { JUSTIFY, LEFT, RIGHT, CENTER, DISTRIBUTE, DISTRIBUTE_SPACE }

class ParaShape {
    var align = Align.JUSTIFY
    var left = 0
    var right = 0
    /** 첫 줄: 양수면 들여쓰기, 음수면 내어쓰기 */
    var indent = 0
    var prev = 0
    var next = 0
    var lineSpacingType = "PERCENT"
    var lineSpacing = 160
    var borderFillId = 0
    var borderOffsetLeft = 0
    var borderOffsetRight = 0
    var borderOffsetTop = 0
    var borderOffsetBottom = 0
    /** 문단 머리: NONE, OUTLINE, NUMBER, BULLET */
    var headingType = "NONE"
    var headingIdRef = 0
    var headingLevel = 0
    /** (직접 나누는 줄) 줄 간격으로 생기는 여유 중 글자 위에 둘 몫 (0 = 모두 아래, 한글 방식). 워드·파워포인트는 위에도 둔다 */
    var leadAbove = 0f
    /** 번호·글머리표 뒤를 내어쓰기 자리까지 띄운다 (워드·파워포인트처럼 둘째 줄과 글 시작을 맞춤) */
    var prefixTab = false
}

class BorderLine(val type: String, val width: Float /* pt */, val color: Int) {
    val visible get() = type != "NONE" && width > 0f
    companion object {
        val NONE = BorderLine("NONE", 0f, 0)
    }
}

class BorderFill {
    var left = BorderLine.NONE
    var right = BorderLine.NONE
    var top = BorderLine.NONE
    var bottom = BorderLine.NONE
    var diagonal = BorderLine.NONE
    var slash = false
    var backSlash = false
    var fillColor: Int? = null
}

/** 문단 번호/글머리표 정의 */
class Numbering {
    /** 수준별(1~10) 형식 문자열 (예: "^1.") 과 번호 모양 */
    val formats = arrayOfNulls<String>(10)
    val numTypes = arrayOfNulls<String>(10)
    val starts = IntArray(10) { 1 }
}

class Bullet(val char: String)

class LineSeg(
    val textPos: Int,
    val vertPos: Int,
    val vertSize: Int,
    val textHeight: Int,
    val baseline: Int,
    val spacing: Int,
    val horzPos: Int,
    val horzSize: Int,
    val flags: Int,
)

/** 문단 안의 요소. [pos]는 HWP 방식 글자 위치 (컨트롤은 8칸, 줄바꿈 등은 1칸) */
sealed class PItem(val pos: Int) {
    abstract val len: Int

    class Text(pos: Int, val text: String, val charShapeId: Int) : PItem(pos) {
        override val len get() = text.length
    }

    class Tab(pos: Int, val width: Int, val leader: String, val charShapeId: Int) : PItem(pos) {
        override val len get() = 8
    }

    class Obj(pos: Int, val obj: HObject, val charShapeId: Int) : PItem(pos) {
        override val len get() = 8
    }

    class Ctrl(pos: Int, val ctrl: HCtrl, val charShapeId: Int) : PItem(pos) {
        override val len get() = 8
    }
}

sealed class HCtrl {
    class Header(val applyPage: String, val paras: List<HPara>, val height: Int) : HCtrl()
    class Footer(val applyPage: String, val paras: List<HPara>, val height: Int) : HCtrl()
    class PageNum(val pos: String, val format: String, val sideChar: String) : HCtrl()
    /** 쪽 번호, 각주/미주 번호 등 자동 번호 (본문에 숫자로 표시) */
    class AutoNum(val numType: String, val format: String, val prefix: String = "", val suffix: String = "") : HCtrl()
    /** 각주(endNote=false) / 미주(endNote=true). format 이 비어 있으면 구역의 주석 모양을 따른다 */
    class Note(
        val endNote: Boolean, val number: Int, val format: String,
        val prefix: String, val suffix: String, val paras: List<HPara>,
    ) : HCtrl()
    class NewNum(val numType: String, val num: Int) : HCtrl()
    class PageHide(val header: Boolean, val footer: Boolean, val pageNum: Boolean) : HCtrl()
    /** 필드(누름틀·하이퍼링크 …) 시작. 하이퍼링크면 [url] (웹·메일·전화 주소), 아니면 null. 끝은 [FieldEnd] */
    class FieldBegin(val url: String?) : HCtrl()
    object FieldEnd : HCtrl()
    class SectionDef(val page: PageDef, val footShape: NoteShape = NoteShape(), val endShape: NoteShape = NoteShape()) : HCtrl()
    object Other : HCtrl()
}

class HPara(
    val paraShapeId: Int,
    val styleId: Int,
    val pageBreak: Boolean,
    val columnBreak: Boolean,
    val items: List<PItem>,
    val lineSegs: List<LineSeg>,
) {
    val textLength: Int get() = items.lastOrNull()?.let { it.pos + it.len } ?: 0
}

/** 각주/미주 모양 (구분선, 간격, 번호 모양) */
class NoteShape {
    var format = "DIGIT"
    var prefix = ""
    var suffix = ""
    /** 구분선 길이 (HWPUNIT). 음수면 5cm, 본문 폭보다 길면 본문 폭 */
    var lineLength = -1
    var lineWidth = 0.12f * 72f / 25.4f
    var lineColor = 0xFF000000.toInt()
    var lineVisible = true
    var above = 850
    var below = 567
    var between = 283
}

class PageDef {
    var width = 59528
    var height = 84186
    var landscape = false
    var left = 5669
    var right = 5669
    var top = 4251
    var bottom = 4251
    var header = 2834
    var footer = 2834
    var gutter = 0
}

/** 개체 배치 정보 (표, 그림, 도형 공통) */
open class HObject {
    var width = 0
    var height = 0
    var treatAsChar = true
    var textWrap = "TOP_AND_BOTTOM"
    var vertRelTo = "PARA"
    var horzRelTo = "PARA"
    var vertAlign = "TOP"
    var horzAlign = "LEFT"
    var vertOffset = 0
    var horzOffset = 0
    var outLeft = 0
    var outRight = 0
    var outTop = 0
    var outBottom = 0
    var zOrder = 0
}

class HCell {
    var col = 0
    var row = 0
    var colSpan = 1
    var rowSpan = 1
    var width = 0
    var height = 0
    var marginLeft = 0
    var marginRight = 0
    var marginTop = 0
    var marginBottom = 0
    var hasMargin = false
    var borderFillId = 0
    var vertAlign = "CENTER"
    var header = false
    var paras: List<HPara> = emptyList()
}

class HTable : HObject() {
    var rowCnt = 0
    var colCnt = 0
    var cellSpacing = 0
    var borderFillId = 0
    var repeatHeader = false
    var pageBreak = "CELL"
    var inLeft = 0
    var inRight = 0
    var inTop = 0
    var inBottom = 0
    val cells = ArrayList<HCell>()
    /** 높이를 내용으로 잰다 (워드처럼 저장된 높이가 최소 높이일 뿐인 표). 글자처럼 놓인 표의 줄 높이에 쓴다 */
    var measureHeight = false
}

class LineStyle(val color: Int, val width: Int /* HWPUNIT */, val style: String)

/** 그림/도형 공통 */
open class HShapeObj : HObject() {
    /** 그룹 안 위치 */
    var offsetX = 0
    var offsetY = 0
    var orgWidth = 0
    var orgHeight = 0
    var curWidth = 0
    var curHeight = 0
    /** renderingInfo 행렬들을 곱한 결과 (a b c / d e f) — orgSz 좌표 → 부모 좌표 */
    var matrix: FloatArray? = null
    var line: LineStyle? = null
    var fillColor: Int? = null
    /** 시계 방향 회전 (도), 좌우·상하 뒤집기 — 개체 가운데를 중심으로 (PPTX 등) */
    var rotation = 0f
    var flipH = false
    var flipV = false
}

class HPicture : HShapeObj() {
    var binId = ""
    var clipLeft = 0
    var clipTop = 0
    var clipRight = 0
    var clipBottom = 0
    var imgWidth = 0
    var imgHeight = 0
}

class HShape(val kind: String) : HShapeObj() {
    val children = ArrayList<HShapeObj>()
    var drawText: List<HPara>? = null
    var textMarginLeft = 283
    var textMarginRight = 283
    var textMarginTop = 283
    var textMarginBottom = 283
    var textVertAlign = "CENTER"
    var roundRatio = 0
    /** 선: 시작/끝 (orgSz 좌표) */
    var x0 = 0
    var y0 = 0
    var x1 = 0
    var y1 = 0
    val points = ArrayList<Pair<Int, Int>>()
    /** kind == "path": orgSz 좌표(HWPUNIT)의 자유 도형 (PPTX 도형 모양) */
    var path: android.graphics.Path? = null
    /** 선 끝 화살표: 시작점(head)·끝점(tail) */
    var headArrow = false
    var tailArrow = false
}

/** 수식 (한글 수식 스크립트) */
class HEquation : HObject() {
    var script = ""
    /** 글자 크기 (HWPUNIT, 1000 = 10pt) */
    var baseUnit = 1000
    var color = 0xFF000000.toInt()
    /** 개체 높이 중 기준선 위치 (%) */
    var baseLine = 85
}

class HSection(val paras: List<HPara>) {
    /** 첫 문단의 구역 정의에서 읽은 용지 설정 */
    var page = PageDef()
    var footShape = NoteShape()
    var endShape = NoteShape()
}

class HDoc(
    val sections: List<HSection>,
    val fontFaces: Array<List<String>>,
    val charShapes: Map<Int, CharShape>,
    val paraShapes: Map<Int, ParaShape>,
    val borderFills: Map<Int, BorderFill>,
    val numberings: Map<Int, Numbering>,
    val bullets: Map<Int, Bullet>,
    /** 이미지 id → 바이트를 읽는 함수 */
    val binLoader: (String) -> ByteArray?,
)
