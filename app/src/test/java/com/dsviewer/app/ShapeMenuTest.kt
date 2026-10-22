package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 보정 펜 도형 줄: 비슷한 기능을 한 칸의 옵션으로 합친 구성과, 새 '처음·끝점 연결' 직선 */
class ShapeMenuTest {

    private fun family(key: ShapeKind) = ShapeMenu.families.getValue(key)

    // ================= 일차·원·삼각형·직사각형이 옵션으로 합쳐졌다 =================

    @Test fun lineOptionsAreEndsThenTwoPointThenBestFit() {
        val f = family(ShapeKind.LINE)
        assertEquals(listOf(ShapeKind.LINE_ENDS, ShapeKind.LINE_2PT, ShapeKind.LINE), f.group!!.options)
        assertEquals("일차", f.group!!.label)
        assertEquals("기본은 처음·끝점 연결", ShapeKind.LINE_ENDS, f.default)
        // 이차·삼차·사차는 옵션 뒤에 그대로 있다
        assertEquals(listOf(ShapeKind.QUADRATIC, ShapeKind.CUBIC, ShapeKind.QUARTIC), f.others)
    }

    @Test fun circleOptionsAreCircleCenterRadiusThreePoint() {
        val f = family(ShapeKind.CIRCLE)
        assertEquals(listOf(ShapeKind.CIRCLE, ShapeKind.CIRCLE_CR, ShapeKind.CIRCLE_3PT), f.group!!.options)
        assertEquals("원", f.group!!.label)
        assertEquals(listOf(ShapeKind.ELLIPSE, ShapeKind.SECTOR, ShapeKind.SEMICIRCLE), f.others)
    }

    @Test fun triangleOptionsAreGeneralAndThreePoint() {
        val f = family(ShapeKind.TRIANGLE)
        assertEquals(listOf(ShapeKind.TRIANGLE, ShapeKind.TRI_3PT), f.group!!.options)
        assertEquals("일반삼각형", f.group!!.label)
        assertFalse(ShapeKind.TRI_3PT in f.others)
    }

    @Test fun rectangleOptionsAreDiagonalFirstThenRectangle() {
        val f = family(ShapeKind.QUADRILATERAL)
        // 기본(맨 왼쪽)은 대각선 직사각형
        assertEquals(listOf(ShapeKind.RECT_DIAG, ShapeKind.RECTANGLE), f.group!!.options)
        assertEquals("직사각형", f.group!!.label)
        // 처음 보이는 칸은 그대로 일반사각형
        assertEquals(ShapeKind.QUADRILATERAL, f.default)
        assertTrue(ShapeKind.QUAD_4PT in f.others)
    }

    @Test fun mergedKindsAppearOnlyOnce() {
        for (f in ShapeMenu.families.values) assertEquals("${f.key} 안에 겹치는 종류가 있다", f.kinds.size, f.kinds.toSet().size)
        // 어느 종류도 두 칸에 나오지 않는다
        val all = ShapeMenu.families.values.flatMap { it.kinds }
        assertEquals(all.size, all.toSet().size)
    }

    @Test fun everyDefaultIsInItsOwnFamily() {
        for (f in ShapeMenu.families.values) {
            assertTrue(f.default in f.kinds)
            assertEquals(f, ShapeMenu.familyOf(f.default))
        }
    }

    // ================= 무리 줄: 묶음은 항목 하나, 그 위 옵션 줄 =================

    private fun labels(key: ShapeKind) = family(key).entries.map { it.label }

    @Test fun lineRowShowsLineQuadraticCubicQuartic() {
        // 일차 이차 삼차 사차: 일차 셋은 '일차' 항목 하나로만
        assertEquals(listOf("일차", "이차", "삼차", "사차"), labels(ShapeKind.LINE))
    }

    @Test fun circleRowShowsCircleEllipseSectorSemicircle() {
        assertEquals(listOf("원", "타원", "부채꼴", "반원"), labels(ShapeKind.CIRCLE))
    }

    @Test fun triangleAndRectangleRows() {
        assertEquals(listOf("일반삼각형", "정삼각형", "직각삼각형", "이등변삼각형", "직각이등변삼각형"), labels(ShapeKind.TRIANGLE))
        assertEquals(
            listOf("직사각형", "일반사각형", "정사각형", "마름모", "평행사변형", "네 점 사각형"), labels(ShapeKind.QUADRILATERAL),
        )
    }

    @Test fun groupEntryCarriesItsOptionsAndSingleEntryItsKind() {
        val line = family(ShapeKind.LINE).entries
        assertEquals(listOf(ShapeKind.LINE_ENDS, ShapeKind.LINE_2PT, ShapeKind.LINE), line[0].group!!.options)
        assertNull(line[0].kind)
        assertEquals(ShapeKind.QUADRATIC, line[1].kind)
        assertNull(line[1].group)
    }

    @Test fun arrowRowHasNoGroup() {
        assertEquals(listOf("직선 화살표", "곡선 화살표", "돼지꼬리 화살표"), labels(ShapeKind.ARROW))
        assertTrue(family(ShapeKind.ARROW).entries.all { it.group == null })
    }

    @Test fun everyKindIsReachableFromTheRows() {
        // 무리 줄의 항목과 묶음의 옵션을 합치면 칸의 모든 종류
        for (f in ShapeMenu.families.values) {
            val reach = f.entries.flatMap { e -> e.group?.options ?: listOf(e.kind!!) }
            assertEquals(f.kinds.toSet(), reach.toSet())
            assertEquals(f.kinds.size, reach.size)
        }
    }

    // ================= 칸 이름과 펼침 창 =================

    @Test fun barShowsGroupNameForAnyOption() {
        assertEquals("일차", ShapeMenu.barLabel(ShapeKind.LINE_ENDS))
        assertEquals("일차", ShapeMenu.barLabel(ShapeKind.LINE_2PT))
        assertEquals("일차", ShapeMenu.barLabel(ShapeKind.LINE))
        assertEquals("원", ShapeMenu.barLabel(ShapeKind.CIRCLE_3PT))
        assertEquals("일반삼각형", ShapeMenu.barLabel(ShapeKind.TRI_3PT))
        assertEquals("직사각형", ShapeMenu.barLabel(ShapeKind.RECT_DIAG))
        // 합치지 않은 종류는 제 이름 그대로
        assertEquals("이차", ShapeMenu.barLabel(ShapeKind.QUADRATIC))
        assertEquals("타원", ShapeMenu.barLabel(ShapeKind.ELLIPSE))
        assertEquals("정삼각형", ShapeMenu.barLabel(ShapeKind.TRI_EQUILATERAL))
    }

    @Test fun separatorSitsBetweenOptionsAndOthers() {
        assertEquals(setOf(3), ShapeMenu.separatorIndex(family(ShapeKind.LINE)))
        assertEquals(setOf(3), ShapeMenu.separatorIndex(family(ShapeKind.CIRCLE)))
        assertEquals(setOf(2), ShapeMenu.separatorIndex(family(ShapeKind.TRIANGLE)))
        assertEquals(setOf(2), ShapeMenu.separatorIndex(family(ShapeKind.QUADRILATERAL)))
        // 합친 묶음이 없는 화살표에는 막대가 없다
        assertTrue(ShapeMenu.separatorIndex(family(ShapeKind.ARROW)).isEmpty())
    }

    @Test fun optionLabelsTellTheVariantsApart() {
        val line = family(ShapeKind.LINE).group!!.options.map(ShapeMenu::optionLabel)
        assertEquals(listOf("처음·끝점 연결", "두 점 선분", "가장 유사한 선분"), line)
        assertEquals(listOf("원", "중심·반지름 원", "세 점 원"), family(ShapeKind.CIRCLE).group!!.options.map(ShapeMenu::optionLabel))
        assertEquals(listOf("일반삼각형", "세 점 삼각형"), family(ShapeKind.TRIANGLE).group!!.options.map(ShapeMenu::optionLabel))
        assertEquals(listOf("대각선 직사각형", "직사각형"), family(ShapeKind.QUADRILATERAL).group!!.options.map(ShapeMenu::optionLabel))
        // 합치지 않은 종류는 원래 이름
        assertEquals("이차", ShapeMenu.optionLabel(ShapeKind.QUADRATIC))
    }

    @Test fun groupOfFindsOnlyMergedOptions() {
        assertNotNull(ShapeMenu.groupOf(ShapeKind.CIRCLE_CR))
        assertNull(ShapeMenu.groupOf(ShapeKind.ELLIPSE))
        assertNull(ShapeMenu.groupOf(ShapeKind.HYPERBOLA))
        assertNull(ShapeMenu.familyOf(ShapeKind.HYPERBOLA))
    }

    // ================= 처음·끝점 연결 직선 =================

    private fun stroke(vararg xy: Float): Stroke {
        val st = Stroke(Tool.PEN, 0xFF000000.toInt(), 2f)
        for (i in xy.indices step 2) st.add(xy[i], xy[i + 1], 0.5f)
        return st
    }

    @Test fun endsLineJoinsFirstAndLastPointOfAWigglyStroke() {
        val st = stroke(10f, 80f, 30f, 20f, 50f, 90f, 70f, 10f, 100f, 40f)
        val c = ShapeFit.fit(ShapeKind.LINE_ENDS, st)!!.curves
        assertEquals(1, c.size)
        assertEquals(listOf(10f, 80f, 100f, 40f), c[0].toList())
    }

    @Test fun endsLineIgnoresHowTheStrokeWanders() {
        // 되돌아와서 끝점이 처음 점 근처에 있는 획: 두 점이 너무 가까우면 만들지 않는다
        val loop = stroke(10f, 10f, 80f, 10f, 80f, 80f, 10f, 80f, 10.5f, 10.5f)
        assertNull(ShapeFit.fit(ShapeKind.LINE_ENDS, loop))
    }

    @Test fun endsLineNeedsTwoPoints() {
        assertNull(ShapeFit.fit(ShapeKind.LINE_ENDS, stroke(5f, 5f)))
    }

    @Test fun bestFitLineStillFitsTheWholeStroke() {
        // 기존 '가장 유사한 직선'은 그대로: 끝점이 아니라 획 전체에 맞춘다
        val st = Stroke(Tool.PEN, 0xFF000000.toInt(), 2f)
        for (i in 0..100) st.add(i.toFloat(), (i * 0.5f) + (if (i == 100) 30f else 0f), 0.5f)
        val c = ShapeFit.fit(ShapeKind.LINE, st)!!.curves[0]
        // 끝점 (100, 80)을 지나지 않는다
        val endY = c[3]
        assertTrue("끝점이 아닌 전체에 맞춘 선이어야 한다 (끝 y=$endY)", endY < 75f)
    }

    @Test fun endsLineIsNotATapKind() {
        assertFalse(ShapeKind.LINE_ENDS.isTapKind)
        assertTrue(ShapeKind.LINE_2PT.isTapKind)
        assertTrue(ShapeKind.CIRCLE_3PT.isTapKind && ShapeKind.TRI_3PT.isTapKind)
    }
}
