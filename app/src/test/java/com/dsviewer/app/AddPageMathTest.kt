package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 마지막 쪽 아래 '빈 쪽 추가'(끌어 올리기·단추)의 계산 */
class AddPageMathTest {

    private val goal = 90f

    // ================= 끌어 올린 거리 =================

    @Test fun pullIsClampedBetweenZeroAndOnePointSixGoals() {
        assertEquals(0f, AddPageMath.clampPull(-5f, goal), 0f)
        assertEquals(50f, AddPageMath.clampPull(50f, goal), 0f)
        assertEquals(144f, AddPageMath.clampPull(500f, goal), 1e-4f)
        assertEquals(144f, AddPageMath.clampPull(144f, goal), 1e-4f)
    }

    @Test fun crossingTheGoalIsReportedOnlyOnce() {
        assertTrue(AddPageMath.crossedGoal(89f, 90f, goal))      // 딱 90에 닿을 때 (>=)
        assertTrue(AddPageMath.crossedGoal(10f, 120f, goal))
        assertFalse(AddPageMath.crossedGoal(90f, 100f, goal))    // 이미 넘은 뒤
        assertFalse(AddPageMath.crossedGoal(10f, 89.9f, goal))   // 아직
        assertFalse(AddPageMath.crossedGoal(100f, 20f, goal))    // 줄어드는 중
    }

    @Test fun readyAndProgress() {
        assertFalse(AddPageMath.isReady(89.9f, goal))
        assertTrue(AddPageMath.isReady(90f, goal))
        assertEquals(0f, AddPageMath.progress(0f, goal), 0f)
        assertEquals(0.5f, AddPageMath.progress(45f, goal), 1e-6f)
        assertEquals(1f, AddPageMath.progress(144f, goal), 0f)   // 한도 안에서도 1을 넘지 않는다
    }

    @Test fun foldingBackTakesTheDragFirst() {
        // 90 끌어 올려 둔 채 30 내리면 → 60만 남고 움직임은 다 쓴다
        var r = AddPageMath.foldBack(90f, -30f)
        assertEquals(60f, r[0], 1e-6f); assertEquals(0f, r[1], 1e-6f)
        // 20만 끌어 올려 둔 채 50 내리면 → 다 접고 30이 남아 문서가 내려간다
        r = AddPageMath.foldBack(20f, -50f)
        assertEquals(0f, r[0], 1e-6f); assertEquals(-30f, r[1], 1e-6f)
    }

    @Test fun foldingBackLeavesOtherMovesAlone() {
        // 올리는 중(dy > 0)이거나 끌어 올린 게 없으면 그대로
        var r = AddPageMath.foldBack(40f, 25f)
        assertEquals(40f, r[0], 0f); assertEquals(25f, r[1], 0f)
        r = AddPageMath.foldBack(0f, -25f)
        assertEquals(0f, r[0], 0f); assertEquals(-25f, r[1], 0f)
    }

    @Test fun stretchAddsSeventyPercentOfTheBlockedMove() {
        assertEquals(70f, AddPageMath.stretch(0f, 100f, 0f), 1e-4f)
        assertEquals(45f, AddPageMath.stretch(10f, 150f, 100f), 1e-4f)
    }

    // ================= 가운데 맞추기 =================

    @Test fun centerIsMiddleOfVisiblePartWhenItFits() {
        assertEquals(500f, AddPageMath.centerX(0f, 1000f, 1000f, 100f), 1e-4f)
        // 쪽이 오른쪽으로 치우쳐 보여도 보이는 부분의 가운데
        assertEquals(700f, AddPageMath.centerX(400f, 1000f, 1000f, 100f), 1e-4f)
    }

    @Test fun centerIsKeptInsideTheScreenEdges() {
        // 글 절반(100)이 화면 밖으로 나가지 않게
        assertEquals(100f, AddPageMath.centerX(-300f, 50f, 1000f, 100f), 1e-4f)
        assertEquals(900f, AddPageMath.centerX(950f, 1500f, 1000f, 100f), 1e-4f)
    }

    @Test fun centerFallsBackToScreenMiddleWhenTheLabelIsWiderThanTheScreen() {
        assertEquals(150f, AddPageMath.centerX(0f, 300f, 300f, 200f), 0f)
    }

    // ================= 단추 자리 =================

    @Test fun buttonSitsInTheMiddleOfTheFooterBelowTheDocument() {
        val d = 2f
        val r = AddPageMath.buttonRect(
            docW = 600f, docH = 1000f, scale = 1.5f, offX = 0f, offY = 400f,
            viewW = 900f, footer = 72f * d, labelW = 100f, density = d,
        )
        val h = 40f * d
        val w = 100f + h + 28f * d
        assertEquals(h, r[3] - r[1], 1e-3f)
        assertEquals(w, r[2] - r[0], 1e-3f)
        // 세로: 문서 아래(1000*1.5-400 = 1100)에서 자리 높이의 절반만큼
        assertEquals(1100f + 72f * d / 2f, (r[1] + r[3]) / 2f, 1e-3f)
        // 가로: 화면 가운데
        assertEquals(450f, (r[0] + r[2]) / 2f, 1e-3f)
    }

    @Test fun buttonFollowsTheVisibleDocumentWhenZoomedIn() {
        val d = 2f
        // 쪽이 화면(900)보다 넓게 확대: 문서 폭 1800, 오른쪽으로 600 밀려 있다 → 보이는 부분은 0~900 전체
        var r = AddPageMath.buttonRect(600f, 1000f, 3f, 600f, 0f, 900f, 144f, 100f, d)
        assertEquals(450f, (r[0] + r[2]) / 2f, 1e-3f)
        // 오른쪽 끝까지 밀어 문서가 화면 왼쪽 600까지만 보이면 그 가운데(300)
        r = AddPageMath.buttonRect(600f, 1000f, 1.5f, 300f, 0f, 900f, 144f, 100f, d)
        // 문서 폭 900, offX 300 → 보이는 부분 0 ~ 600, 가운데 300. 단추 절반(약 114)보다 안쪽
        assertEquals(300f, (r[0] + r[2]) / 2f, 1e-3f)
    }

    @Test fun buttonIsKeptOnScreenWhenTheVisiblePartIsNarrow() {
        val d = 2f
        // 문서가 거의 다 왼쪽으로 밀려 보이는 부분이 20px뿐이어도 단추는 화면 안에
        val r = AddPageMath.buttonRect(600f, 1000f, 1f, 580f, 0f, 900f, 144f, 100f, d)
        assertTrue(r[0] >= 0f)
        assertTrue(r[2] <= 900f)
    }

    @Test fun hitTestIncludesTheSlopOnAllSides() {
        val r = floatArrayOf(100f, 200f, 300f, 240f)
        assertTrue(AddPageMath.hitsButton(r, 200f, 220f, 6f))
        assertTrue(AddPageMath.hitsButton(r, 94f, 220f, 6f))      // 왼쪽 경계 (포함)
        assertTrue(AddPageMath.hitsButton(r, 306f, 220f, 6f))
        assertTrue(AddPageMath.hitsButton(r, 200f, 194f, 6f))
        assertTrue(AddPageMath.hitsButton(r, 200f, 246f, 6f))
        assertFalse(AddPageMath.hitsButton(r, 93.9f, 220f, 6f))
        assertFalse(AddPageMath.hitsButton(r, 306.1f, 220f, 6f))
        assertFalse(AddPageMath.hitsButton(r, 200f, 193.9f, 6f))
        assertFalse(AddPageMath.hitsButton(r, 200f, 246.1f, 6f))
    }

    // ================= 새 쪽 자리 =================

    @Test fun pullPageStartsBelowTheLastPageAndRisesWithThePull() {
        // 마지막 쪽 아래 끝 문서 좌표 1000, 배율 1, 스크롤 300, 간격 10, 단추 자리 144
        val b0 = AddPageMath.pullPageBox(600f, 1f, 0f, 300f, 0f, 1000f, 600f, 800f, 10f, 0f, 144f)
        assertEquals(1000f - 300f + 10f + 144f, b0[1], 1e-3f)
        assertEquals(600f, b0[2], 0f); assertEquals(800f, b0[3], 0f)
        val b1 = AddPageMath.pullPageBox(600f, 1f, 0f, 300f, 60f, 1000f, 600f, 800f, 10f, 0f, 144f)
        assertEquals(b0[1] - 60f, b1[1], 1e-3f)       // 끌어 올린 만큼 위로
    }

    @Test fun pullPageIsCenteredAmongPageAndRightMargin() {
        // 문서 폭 700 (쪽 600 + 오른쪽 여백 100), 배율 1: 쪽은 문서 왼쪽 끝
        val b = AddPageMath.pullPageBox(700f, 1f, 0f, 0f, 0f, 500f, 600f, 800f, 10f, 100f, 0f)
        assertEquals(0f, b[0], 1e-3f)
        // 스크롤로 오른쪽 50 밀리면 쪽도 50 왼쪽으로
        val c = AddPageMath.pullPageBox(700f, 1f, 50f, 0f, 0f, 500f, 600f, 800f, 10f, 100f, 0f)
        assertEquals(-50f, c[0], 1e-3f)
    }

    @Test fun pullPageScalesWithZoom() {
        val b = AddPageMath.pullPageBox(600f, 2f, 0f, 0f, 0f, 500f, 600f, 800f, 10f, 0f, 0f)
        assertEquals(1200f, b[2], 1e-3f)
        assertEquals(1600f, b[3], 1e-3f)
        assertEquals(500f * 2f + 10f * 2f, b[1], 1e-3f)
    }

    // ================= 옛 계산과 대조 =================

    /** 옛 DocumentView.addButtonRect의 계산을 그대로 옮긴 기준 */
    private fun referenceButton(
        docW: Float, docH: Float, s: Float, offX: Float, offY: Float, viewW: Float,
        footer: Float, labelW: Float, d: Float,
    ): FloatArray {
        val top = docH * s - offY
        val cy = top + footer / 2f
        val h = 40f * d
        val w = labelW + h + 28f * d
        val visL = maxOf(-offX, 0f)
        val visR = minOf(docW * s - offX, viewW)
        val cx = ((visL + visR) / 2f).let { if (viewW > w) it.coerceIn(w / 2f, viewW - w / 2f) else viewW / 2f }
        return floatArrayOf(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    @Test fun buttonMatchesTheOldCodeOnRandomViews() {
        val rnd = Random(21)
        repeat(20_000) {
            val docW = 300f + rnd.nextFloat() * 700
            val docH = 500f + rnd.nextFloat() * 5000
            val s = 0.5f + rnd.nextFloat() * 4
            val offX = (rnd.nextFloat() - 0.2f) * docW * s
            val offY = rnd.nextFloat() * docH * s
            val viewW = 300f + rnd.nextFloat() * 1500
            val labelW = 60f + rnd.nextFloat() * 120
            val ref = referenceButton(docW, docH, s, offX, offY, viewW, 144f, labelW, 2f)
            val got = AddPageMath.buttonRect(docW, docH, s, offX, offY, viewW, 144f, labelW, 2f)
            for (k in 0..3) assertEquals("칸 $k", ref[k], got[k], 0f)
        }
    }
}
