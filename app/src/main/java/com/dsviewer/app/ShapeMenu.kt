package com.dsviewer.app

/**
 * 보정 펜 도형 줄이 어떻게 묶여 보이는지.
 * 칸 하나(무리)를 누르면 도형 줄 위에 줄이 한 줄 더 생겨 그 무리의 종류들이 뜬다 (예: 일차 · 이차 · 삼차 · 사차. 세로 툴바에서는 펼침 창).
 * 하는 일이 비슷한 종류들(일차 셋, 원 셋, 삼각형 둘, 직사각형 둘)은 한 묶음([Group])으로 합쳐 그 줄에 항목 하나로만 보이고,
 * 그 항목을 고르면 그 위에 줄이 한 줄 더 생겨 묶음의 옵션 가운데 하나를 고른다.
 * 화면·Android 클래스를 모르는 순수한 정의라 JVM 단위 시험으로 확인한다
 */
internal object ShapeMenu {

    /** 한 칸으로 합친 비슷한 기능들: [label]은 칸에 보이는 이름, [options]는 옵션 (첫째가 기본) */
    class Group(val label: String, val options: List<ShapeKind>)

    /**
     * 무리 줄의 항목 하나: 합친 묶음이면 [group]이 있고 이름은 묶음 이름(일차·원 …), 아니면 종류 하나 ([kind]).
     * [label]은 줄에 보이는 이름
     */
    class Entry(val label: String, val kind: ShapeKind?, val group: Group?)

    /** 칸 하나가 펼치는 종류들: 합친 묶음이 있으면 그 옵션이 맨 앞이고, 나머지가 그 뒤 */
    class Family(val key: ShapeKind, val group: Group?, val others: List<ShapeKind>, val default: ShapeKind) {
        /** 펼침 창에 늘어놓는 차례 */
        val kinds: List<ShapeKind> get() = (group?.options ?: emptyList()) + others

        /** 무리 줄에 늘어놓는 항목: 합친 묶음이 하나로 맨 앞에, 그 뒤에 나머지 종류들 */
        val entries: List<Entry>
            get() = listOfNotNull(group?.let { Entry(it.label, null, it) }) + others.map { Entry(it.label, it, null) }
    }

    private val LINE_GROUP = Group("일차", listOf(ShapeKind.LINE_ENDS, ShapeKind.LINE_2PT, ShapeKind.LINE))
    private val CIRCLE_GROUP = Group("원", listOf(ShapeKind.CIRCLE, ShapeKind.CIRCLE_CR, ShapeKind.CIRCLE_3PT))
    private val TRIANGLE_GROUP = Group("일반삼각형", listOf(ShapeKind.TRIANGLE, ShapeKind.TRI_3PT))
    private val RECT_GROUP = Group("직사각형", listOf(ShapeKind.RECTANGLE, ShapeKind.RECT_DIAG))

    /** 펼쳐 고르는 칸들 (키는 줄에서 그 칸을 가리키는 종류) */
    val families: Map<ShapeKind, Family> = listOf(
        // 보조선 화살표: 직선·곡선·돼지꼬리
        Family(ShapeKind.ARROW, null, listOf(ShapeKind.ARROW, ShapeKind.ARROW_CURVE, ShapeKind.ARROW_PIGTAIL), ShapeKind.ARROW),
        // 다항: 일차(처음·끝점 연결 / 두 점 선분 / 가장 유사한 선분), 이차, 삼차, 사차
        Family(ShapeKind.LINE, LINE_GROUP, listOf(ShapeKind.QUADRATIC, ShapeKind.CUBIC, ShapeKind.QUARTIC), ShapeKind.LINE_ENDS),
        // 원(원 / 중심·반지름 원 / 세 점 원), 타원, 부채꼴, 반원
        Family(ShapeKind.CIRCLE, CIRCLE_GROUP, listOf(ShapeKind.ELLIPSE, ShapeKind.SECTOR, ShapeKind.SEMICIRCLE), ShapeKind.CIRCLE),
        // 일반삼각형(그린 대로 / 세 점 삼각형), 정삼각형 …
        Family(
            ShapeKind.TRIANGLE, TRIANGLE_GROUP,
            listOf(ShapeKind.TRI_EQUILATERAL, ShapeKind.TRI_RIGHT, ShapeKind.TRI_ISOSCELES, ShapeKind.TRI_RIGHT_ISOSCELES),
            ShapeKind.TRIANGLE,
        ),
        // 직사각형(그린 대로 / 대각선), 일반사각형, 정사각형 …. 처음 보이는 것은 일반사각형
        Family(
            ShapeKind.QUADRILATERAL, RECT_GROUP,
            listOf(ShapeKind.QUADRILATERAL, ShapeKind.SQUARE, ShapeKind.RHOMBUS, ShapeKind.PARALLELOGRAM, ShapeKind.QUAD_4PT),
            ShapeKind.QUADRILATERAL,
        ),
    ).associateBy { it.key }

    /** [kind]가 속한 칸 (없으면 null) */
    fun familyOf(kind: ShapeKind): Family? = families.values.firstOrNull { kind in it.kinds }

    /** [kind]가 합친 묶음의 옵션이면 그 묶음 */
    fun groupOf(kind: ShapeKind): Group? = families.values.firstNotNullOfOrNull { f -> f.group?.takeIf { kind in it.options } }

    /** 줄의 칸에 쓰는 이름: 합친 묶음의 옵션이면 묶음 이름 (일차·원·일반삼각형·직사각형), 아니면 그 종류의 이름 */
    fun barLabel(kind: ShapeKind): String = groupOf(kind)?.label ?: kind.label

    /** 펼침 창에서 합친 묶음의 옵션이 끝나고 다른 종류가 시작하는 자리 (구분 막대를 그릴 곳). 나머지가 없으면 비어 있다 */
    fun separatorIndex(family: Family): Set<Int> {
        val g = family.group ?: return emptySet()
        return if (family.others.isEmpty()) emptySet() else setOf(g.options.size)
    }

    /** 펼침 창의 옵션 이름: 합친 묶음의 옵션은 무엇이 다른지 드러나게 */
    fun optionLabel(kind: ShapeKind): String = when (kind) {
        ShapeKind.LINE_ENDS -> "처음·끝점 연결"
        ShapeKind.LINE_2PT -> "두 점 선분"
        ShapeKind.LINE -> "가장 유사한 선분"
        ShapeKind.CIRCLE -> "원"
        ShapeKind.CIRCLE_CR -> "중심·반지름 원"
        ShapeKind.CIRCLE_3PT -> "세 점 원"
        ShapeKind.TRIANGLE -> "일반삼각형"
        ShapeKind.TRI_3PT -> "세 점 삼각형"
        ShapeKind.RECTANGLE -> "직사각형"
        ShapeKind.RECT_DIAG -> "대각선 직사각형"
        else -> kind.label
    }
}
