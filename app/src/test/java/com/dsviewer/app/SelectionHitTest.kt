package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 올가미 선택의 판정 계산: 누른 곳의 대상, 올가미가 고르는 획, 선택 상자, 손잡이.
 * 그림(Bitmap)·글(TextPaint)·포스트잇(Paint)을 품은 획은 JVM에서 만들 수 없어, 그런 획만 따지는 규칙
 * (메모는 고르지 않기, 그림 상자 우선순위)은 여기서 시험하지 못한다. 사각형 안 판정(quadContains)은 같은 계산이라 따로 확인한다.
 */
class SelectionHitTest {

    private fun pen(vararg pts: Pair<Float, Float>, width: Float = 2f): Stroke {
        val st = Stroke(Tool.PEN, 0xFF000000.toInt(), width)
        for ((x, y) in pts) st.add(x, y, 0.5f)
        return st
    }

    /** 꼭짓점들을 이은 닫힌 영역(짝홀 규칙) 채우기. 윤곽의 첫 점만 필압 1 */
    private fun fill(vararg pts: Pair<Float, Float>): Stroke {
        val st = Stroke(Tool.FILL, 0xFFFFE082.toInt(), 0f).also { it.fill = FillStyle(FillPattern.SOLID) }
        pts.forEachIndexed { i, (x, y) -> st.add(x, y, if (i == 0) 1f else 0f) }
        return st
    }

    private fun rectTape(x0: Float, y0: Float, x1: Float, y1: Float): Stroke {
        val st = Stroke(Tool.TAPE, 0xFFF6C744.toInt(), 0f).also { it.tape = TapeStyle(TapePattern.SOLID, true) }
        st.add(x0, y0, 1f); st.add(x1, y0, 1f); st.add(x1, y1, 1f); st.add(x0, y1, 1f)
        return st
    }

    private fun square(x0: Float, y0: Float, x1: Float, y1: Float) = pen(x0 to y0, x1 to y0, x1 to y1, x0 to y1)

    // ================= 사각형 안 =================

    @Test fun quadContainsInsideAndOutside() {
        val q = square(0f, 0f, 10f, 10f)
        assertTrue(SelectionHit.quadContains(q, 5f, 5f))
        assertFalse(SelectionHit.quadContains(q, 15f, 5f))
        assertFalse(SelectionHit.quadContains(q, 5f, -1f))
    }

    @Test fun quadContainsFollowsRotatedCorners() {
        // 마름모(돌린 그림): 꼭짓점 사이 가운데는 안, 바깥 모서리 쪽은 밖
        val diamond = pen(10f to 0f, 20f to 10f, 10f to 20f, 0f to 10f)
        assertTrue(SelectionHit.quadContains(diamond, 10f, 10f))
        assertTrue(SelectionHit.quadContains(diamond, 14f, 10f))
        assertFalse(SelectionHit.quadContains(diamond, 18f, 3f))   // 마름모의 바깥 모서리 쪽
    }

    // ================= 획이 가까운지 =================

    @Test fun strokeNearUsesHalfWidthPlusTolerance() {
        val st = pen(0f to 0f, 100f to 0f, width = 4f)
        val r = st.halfWidth + 3f
        assertTrue(SelectionHit.strokeNear(st, 50f, r - 0.1f, 3f))
        assertFalse(SelectionHit.strokeNear(st, 50f, r + 0.1f, 3f))
    }

    @Test fun strokeNearMeasuresToTheNearestSegmentPoint() {
        val st = pen(0f to 0f, 100f to 0f, width = 2f)
        val r = st.halfWidth + 2f
        // 끝 너머는 끝점까지의 거리
        assertTrue(SelectionHit.strokeNear(st, 100f + r - 0.1f, 0f, 2f))
        assertFalse(SelectionHit.strokeNear(st, 100f + r + 0.1f, 0f, 2f))
        // 꺾인 선은 어느 선분이든
        val bent = pen(0f to 0f, 50f to 0f, 50f to 50f, width = 2f)
        assertTrue(SelectionHit.strokeNear(bent, 50f, 25f, 2f))
    }

    @Test fun strokeNearHandlesDotsAndEmptyStrokes() {
        val dot = pen(10f to 10f, width = 4f)
        assertTrue(SelectionHit.strokeNear(dot, 10f, 10f + dot.halfWidth + 1f, 2f))
        assertFalse(SelectionHit.strokeNear(dot, 10f, 40f, 2f))
        assertFalse(SelectionHit.strokeNear(Stroke(Tool.PEN, 0, 2f), 0f, 0f, 100f))
    }

    // ================= 대상 선택 =================

    @Test fun objectAtPicksTheTopmostLine() {
        val below = pen(0f to 0f, 100f to 0f)
        val above = pen(50f to -20f, 50f to 20f)
        assertSame(above, SelectionHit.objectAt(listOf(below, above), 50f, 0f, 3f))
        assertSame(below, SelectionHit.objectAt(listOf(above, below), 50f, 0f, 3f))
    }

    @Test fun objectAtLetsAFingerHitAThinLine() {
        val st = pen(0f to 0f, 100f to 0f, width = 1f)
        assertNull(SelectionHit.objectAt(listOf(st), 50f, 8f, 3f))
        assertSame(st, SelectionHit.objectAt(listOf(st), 50f, 8f, 10f))
    }

    @Test fun objectAtFindsRectTapeInsideAndIgnoresOutside() {
        val tape = rectTape(0f, 0f, 100f, 40f)
        assertSame(tape, SelectionHit.objectAt(listOf(tape), 50f, 20f, 3f))
        assertNull(SelectionHit.objectAt(listOf(tape), 150f, 20f, 3f))
    }

    @Test fun objectAtLooksAtFillsAfterLines() {
        val area = fill(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)
        val line = pen(0f to 50f, 100f to 50f)
        // 선이 있는 곳은 선이, 선이 없는 안쪽은 채우기가
        assertSame(line, SelectionHit.objectAt(listOf(area, line), 50f, 50f, 3f))
        assertSame(area, SelectionHit.objectAt(listOf(area, line), 50f, 10f, 3f))
        // 선이 채우기보다 아래(앞)에 있어도 선이 먼저다 (채우기는 선 아래에 깔린다)
        assertSame(line, SelectionHit.objectAt(listOf(line, area), 50f, 50f, 3f))
        assertNull(SelectionHit.objectAt(listOf(area, line), 200f, 200f, 3f))
    }

    // ================= 올가미 =================

    private fun poly(vararg pts: Pair<Float, Float>) = FloatArray(pts.size * 2) { if (it % 2 == 0) pts[it / 2].first else pts[it / 2].second }

    @Test fun pointInPolygonSquareAndConcave() {
        val sq = poly(0f to 0f, 10f to 0f, 10f to 10f, 0f to 10f)
        assertTrue(SelectionHit.pointInPolygon(sq, 4, 5f, 5f))
        assertFalse(SelectionHit.pointInPolygon(sq, 4, 12f, 5f))
        // ㄴ자 (오목): 파인 곳은 밖
        val ell = poly(0f to 0f, 10f to 0f, 10f to 4f, 4f to 4f, 4f to 10f, 0f to 10f)
        assertTrue(SelectionHit.pointInPolygon(ell, 6, 2f, 8f))
        assertTrue(SelectionHit.pointInPolygon(ell, 6, 8f, 2f))
        assertFalse(SelectionHit.pointInPolygon(ell, 6, 8f, 8f))
    }

    @Test fun pointInPolygonOnlyUsesTheFirstCountPoints() {
        // 배열이 더 길어도 앞 count개만 (올가미 점 배열은 늘려 쓰므로)
        val arr = FloatArray(40)
        val pts = poly(0f to 0f, 10f to 0f, 10f to 10f, 0f to 10f)
        pts.copyInto(arr)
        assertTrue(SelectionHit.pointInPolygon(arr, 4, 5f, 5f))
        assertFalse(SelectionHit.pointInPolygon(arr, 0, 5f, 5f))
    }

    @Test fun pickByLassoNeedsHalfOfThePointsInside() {
        val lasso = poly(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)
        val inside = pen(10f to 10f, 90f to 90f)
        val half = pen(50f to 50f, 150f to 50f)         // 점 둘 중 하나만 안 → 절반이라 고른다
        val less = pen(50f to 50f, 150f to 50f, 160f to 50f)   // 셋 중 하나 → 절반 안 됨
        val outside = pen(200f to 200f, 210f to 210f)
        val picked = SelectionHit.pickByLasso(listOf(inside, half, less, outside), lasso, 4)
        assertEquals(listOf(inside, half), picked)
    }

    @Test fun randomLassosPickWhatIsInsideTheBox() {
        val rnd = Random(2)
        repeat(200) {
            val box = poly(0f to 0f, 100f to 0f, 100f to 100f, 0f to 100f)
            val strokes = List(8) {
                val n = rnd.nextInt(1, 6)
                pen(*Array(n) { rnd.nextFloat() * 160f - 30f to rnd.nextFloat() * 160f - 30f })
            }
            val picked = SelectionHit.pickByLasso(strokes, box, 4)
            for (st in strokes) {
                var inside = 0
                for (k in 0 until st.count) if (st.x(k) in 0f..100f && st.y(k) in 0f..100f) inside++
                val expected = inside > 0 && inside * 2 >= st.count
                assertEquals(expected, st in picked)
            }
        }
    }

    // ================= 선택 상자 =================

    @Test fun boundsOfAddsHalfWidthAndUnitesStrokes() {
        val a = pen(10f to 20f, 30f to 40f, width = 4f)
        val b = pen(50f to 5f, 60f to 15f, width = 8f)
        val box = SelectionHit.boundsOf(listOf(a, b))!!
        assertEquals(10f - a.halfWidth, box[0], 1e-4f)                       // 왼쪽: a의 왼쪽
        assertEquals(5f - b.halfWidth, box[1], 1e-4f)                        // 위: b의 위
        assertEquals(60f + b.halfWidth, box[2], 1e-4f)                       // 오른쪽: b
        assertEquals(40f + a.halfWidth, box[3], 1e-4f)                       // 아래: a
    }

    @Test fun boundsOfNothingIsNull() {
        assertNull(SelectionHit.boundsOf(emptyList()))
        assertNull(SelectionHit.boundsOf(listOf(Stroke(Tool.PEN, 0, 2f))))
    }

    @Test fun boundsOfAZeroWidthBoxStillHasSize() {
        // 굵기 0인 그림 상자: 점들이 이룬 크기를 그대로 (RectF.union이 넓이 0을 무시하던 문제)
        val box = Stroke(Tool.PEN, 0, 0f)
        for ((x, y) in listOf(10f to 20f, 50f to 20f, 50f to 60f, 10f to 60f)) box.add(x, y, 1f)
        val b = SelectionHit.boundsOf(listOf(box))!!
        assertEquals(listOf(10f, 20f, 50f, 60f), b.toList())
    }

    // ================= 손잡이 =================

    private val reach = 40f

    @Test fun handleAtFindsCornersFirst() {
        // 상자 100,100 ~ 500,400
        assertEquals(0, SelectionHit.handleAt(100f, 100f, 500f, 400f, 105f, 95f, reach))
        assertEquals(1, SelectionHit.handleAt(100f, 100f, 500f, 400f, 495f, 110f, reach))
        assertEquals(2, SelectionHit.handleAt(100f, 100f, 500f, 400f, 110f, 395f, reach))
        assertEquals(3, SelectionHit.handleAt(100f, 100f, 500f, 400f, 500f, 400f, reach))
    }

    @Test fun handleAtFindsSideHandlesOnBigBoxes() {
        assertEquals(4, SelectionHit.handleAt(100f, 100f, 500f, 400f, 300f, 100f, reach))   // 위 변
        assertEquals(5, SelectionHit.handleAt(100f, 100f, 500f, 400f, 300f, 405f, reach))   // 아래 변
        assertEquals(6, SelectionHit.handleAt(100f, 100f, 500f, 400f, 100f, 250f, reach))   // 왼쪽 변
        assertEquals(7, SelectionHit.handleAt(100f, 100f, 500f, 400f, 500f, 250f, reach))   // 오른쪽 변
        assertEquals(-1, SelectionHit.handleAt(100f, 100f, 500f, 400f, 300f, 250f, reach))  // 상자 한가운데
        assertEquals(-1, SelectionHit.handleAt(100f, 100f, 500f, 400f, 900f, 900f, reach))
    }

    @Test fun sideHandleReachIsEightyPercent() {
        // 위 변 가운데에서 위로 0.8·reach 안은 잡히고, 넘으면 못 잡는다
        assertEquals(4, SelectionHit.handleAt(100f, 100f, 500f, 400f, 300f, 100f - reach * 0.79f, reach))
        assertEquals(-1, SelectionHit.handleAt(100f, 100f, 500f, 400f, 300f, 100f - reach * 0.81f, reach))
    }

    @Test fun smallBoxesHaveNoSideHandles() {
        // 모서리 손잡이와 겹치지 않도록: 폭·높이가 reach·2.2 (=88) 미만이면 그쪽 변 손잡이를 두지 않는다
        assertEquals(false to false, SelectionHit.sideHandles(80f, 80f, reach))
        assertEquals(true to false, SelectionHit.sideHandles(300f, 60f, reach))
        assertEquals(false to true, SelectionHit.sideHandles(60f, 300f, reach))
        assertEquals(true to true, SelectionHit.sideHandles(88f, 88f, reach))
        // 가로로만 넓은 상자(높이 85 < 88)는 위·아래 변 손잡이만: 왼쪽 변 가운데는 모서리에서 멀어도 잡히지 않는다
        assertEquals(-1, SelectionHit.handleAt(100f, 100f, 500f, 185f, 100f, 142.5f, reach))
        assertEquals(4, SelectionHit.handleAt(100f, 100f, 500f, 185f, 300f, 100f, reach))
    }
}
