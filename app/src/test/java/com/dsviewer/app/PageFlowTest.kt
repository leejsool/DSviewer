package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 가로 넘김 쪽 배치(쪽을 왼쪽에서 오른쪽으로 한 줄에)와 한 쪽씩 맞추는 계산 */
class PageFlowTest {

    private fun horizontal(
        widths: FloatArray, heights: FloatArray, rightMargin: Float = 0f, twoPage: Boolean = false,
        viewW: Int = 800, viewH: Int = 1200,
    ) = PageLayout(10f).also { it.layout(widths, heights, rightMargin, twoPage, viewW, viewH, horizontal = true) }

    private fun fa(vararg v: Float) = v

    // ================= 배치 =================

    @Test fun pagesLieInOneRowCenteredVertically() {
        val l = horizontal(fa(600f, 300f), fa(800f, 400f))
        assertTrue(l.horizontal)
        assertEquals(listOf(10f, 620f), l.lefts.toList())
        assertEquals(listOf(10f, 210f), l.tops.toList())      // 낮은 쪽은 위아래 가운데
        assertEquals(930f, l.docW, 1e-3f)                       // 쪽 + 쪽 사이·양 끝 여백
        assertEquals(820f, l.docH, 1e-3f)
        assertEquals(2, l.unitCount)
        assertEquals(310f, l.unitCenter(0), 1e-3f)
        assertEquals(770f, l.unitCenter(1), 1e-3f)
    }

    @Test fun rightMarginIsPartOfThePageSlot() {
        val l = horizontal(fa(100f, 100f), fa(100f, 100f), rightMargin = 40f)
        assertEquals(listOf(10f, 160f), l.lefts.toList())
        assertEquals(80f, l.unitCenter(0), 1e-3f)           // 10..150 (바깥 여백 40 포함)
        assertEquals(310f, l.docW, 1e-3f)           // 10 + (100+40+10) x 2
    }

    @Test fun verticalLayoutHasNoUnits() {
        val l = PageLayout(10f).also { it.layout(fa(600f, 300f), fa(800f, 400f), 0f, false, 800, 1200) }
        assertFalse(l.horizontal)
        assertEquals(0, l.unitCount)
        assertEquals(-1, l.nearestUnit(0f, 1f, 400f))
        assertEquals(800f / 620f, l.fitScale(800, 1200), 1e-4f)   // 세로 스크롤은 너비에 맞춘다
    }

    @Test fun twoPageSpreadMakesTwoPageUnits() {
        val l = horizontal(fa(100f, 100f, 100f, 100f), fa(100f, 100f, 100f, 100f), twoPage = true, viewW = 1600, viewH = 1000)
        assertTrue(l.spread)
        assertEquals(2, l.unitCount)
        assertEquals(listOf(10f, 120f, 230f, 340f), l.lefts.toList())
        assertEquals(115f, l.unitCenter(0), 1e-3f)
        assertEquals(335f, l.unitCenter(1), 1e-3f)
        assertEquals(listOf(0, 0, 1, 1), (0..3).map { l.unitOf(it) })
    }

    @Test fun twoPageIgnoredOnPortraitScreen() {
        val l = horizontal(fa(100f, 100f, 100f), fa(100f, 100f, 100f), twoPage = true, viewW = 800, viewH = 1200)
        assertFalse(l.spread)
        assertEquals(3, l.unitCount)
    }

    @Test fun emptyDocumentDoesNotCrash() {
        val l = horizontal(FloatArray(0), FloatArray(0))
        assertEquals(0, l.count)
        assertTrue(l.visibleRange(0f, 1f, 100f, 0f, 100f).isEmpty())
        assertEquals(-1, l.rowFirstPage(0f, 1f, 100f, 0f, 100f))
        assertNull(l.topSpot(0f, 0f, 1f, 0f, 100f))
    }

    // ================= 화면에 맞추기 =================

    @Test fun fitScaleShowsWholeUnit() {
        val l = horizontal(fa(600f, 300f), fa(800f, 400f))
        // 세로 화면: 너비가 빡빡 (800 / 620)
        assertEquals(800f / 620f, l.fitScale(800, 1200), 1e-4f)
        // 가로 화면: 높이가 빡빡 (1000 / 820)
        assertEquals(1000f / 820f, l.fitScale(2000, 1000), 1e-4f)
    }

    @Test fun fitScaleUsesWidestUnit() {
        val l = horizontal(fa(100f, 100f, 100f, 100f), fa(100f, 100f, 100f, 100f), twoPage = true, viewW = 1600, viewH = 1000)
        assertEquals(1600f / (210f + 20f), l.fitScale(1600, 5000), 1e-4f)   // 두 쪽 한 칸 210
    }

    // ================= 보이는 쪽·가운데 칸 =================

    @Test fun visibleRangeByHorizontalPosition() {
        val l = horizontal(fa(600f, 300f), fa(800f, 400f))
        assertEquals(0..0, l.visibleRange(0f, 1f, 1000f, 0f, 500f))
        assertEquals(0..1, l.visibleRange(0f, 1f, 1000f, 400f, 400f))
        assertEquals(1..1, l.visibleRange(0f, 1f, 1000f, 700f, 400f))
        assertTrue(l.visibleRange(0f, 1f, 1000f, 5000f, 400f).isEmpty())
    }

    @Test fun nearestUnitFollowsViewCenter() {
        val l = horizontal(fa(600f, 300f), fa(800f, 400f))
        assertEquals(0, l.nearestUnit(100f, 1f, 400f))
        assertEquals(1, l.nearestUnit(500f, 1f, 400f))
        assertEquals(570f, l.offsetForUnit(1, 1f, 400f), 1e-3f)
    }

    @Test fun currentPageIsPageUnderViewCenter() {
        val l = horizontal(fa(600f, 300f), fa(800f, 400f))
        assertEquals(0, l.currentPage(100f, 0f, 1f, 400f, 1000f))
        assertEquals(1, l.currentPage(500f, 0f, 1f, 400f, 1000f))
        assertEquals(1, l.rowFirstPage(0f, 1f, 1000f, 500f, 400f))
    }

    @Test fun currentPageInSpreadPicksSideOfCenter() {
        val l = horizontal(fa(100f, 100f, 100f, 100f), fa(100f, 100f, 100f, 100f), twoPage = true, viewW = 1600, viewH = 1000)
        assertEquals(0, l.currentPage(0f, 0f, 1f, 200f, 1000f))    // 가운데 100 → 첫 쪽
        assertEquals(1, l.currentPage(30f, 0f, 1f, 200f, 1000f))   // 가운데 130 → 둘째 쪽
        assertEquals(3, l.currentPage(240f, 0f, 1f, 200f, 1000f))  // 다음 칸, 가운데 340 → 그 칸의 둘째 쪽
    }

    @Test fun hitPageWorksInRow() {
        val l = horizontal(fa(600f, 300f), fa(800f, 400f))
        val a = l.hitPage(0f, 0f, 1f, 50f, 100f)!!
        assertEquals(0, a.first)
        assertEquals(40f, a.second, 1e-3f)
        assertEquals(90f, a.third, 1e-3f)
        val b = l.hitPage(0f, 0f, 1f, 700f, 300f)!!
        assertEquals(1, b.first)
        assertEquals(80f, b.second, 1e-3f)
        assertEquals(90f, b.third, 1e-3f)
        assertNotNull(l.hitPage(0f, 0f, 1f, 615f, 300f))            // 쪽 사이 여백은 가까운 쪽으로
        assertNull(l.hitPage(0f, 0f, 1f, 700f, 5000f))
    }

    @Test fun topSpotUsesCurrentPageInRow() {
        val l = horizontal(fa(600f, 300f), fa(800f, 400f))
        val spot = l.topSpot(50f, 0f, 1f, 500f, 400f)!!
        assertEquals(1, spot.first)
        assertEquals(50f - 210f, spot.second, 1e-3f)
    }

    // ================= 손 뗐을 때 갈 칸 =================

    @Test fun slowReleaseStaysOnNearest() {
        assertEquals(1, PageLayout.flipTarget(1, 770f, 700f, -100f, 3))
        assertEquals(1, PageLayout.flipTarget(1, 770f, 800f, 100f, 3))
        assertEquals(1, PageLayout.flipTarget(1, 770f, 770f, 0f, 3))
    }

    @Test fun quickFlickGoesToNeighbour() {
        assertEquals(2, PageLayout.flipTarget(1, 770f, 770f, -1000f, 3))    // 왼쪽으로 쓸면 다음
        assertEquals(0, PageLayout.flipTarget(1, 770f, 770f, 1000f, 3))     // 오른쪽으로 쓸면 앞
        assertEquals(2, PageLayout.flipTarget(1, 770f, 790f, -600f, 3))     // 속도 경계
    }

    @Test fun flickThatAlreadyCrossedHalfwayDoesNotSkipAPage() {
        // 다음 칸 쪽으로 반 넘게 끌어 가장 가까운 칸이 이미 다음 칸이면, 같은 방향으로 쓸어도 거기서 멈춘다
        assertEquals(2, PageLayout.flipTarget(2, 1000f, 900f, -1000f, 3))
        assertEquals(0, PageLayout.flipTarget(0, 300f, 400f, 1000f, 3))
    }

    @Test fun flipTargetStaysInRange() {
        assertEquals(0, PageLayout.flipTarget(0, 300f, 300f, 1000f, 3))
        assertEquals(2, PageLayout.flipTarget(2, 1000f, 1000f, -1000f, 3))
        assertEquals(0, PageLayout.flipTarget(0, 300f, 300f, -1000f, 1))
        assertEquals(-1, PageLayout.flipTarget(0, 0f, 0f, -1000f, 0))
    }

    // ================= 세로 위치 =================

    @Test fun flipKeepsPageOnTopWhenItAlmostFillsTheScreen() {
        // 높이에 맞춘 쪽: 남는 자리 40px(화면의 4%)는 가운데로 맞추지 않고 위에 붙인다 (툴바가 나타나고 사라질 때 안 밀리게)
        assertEquals(0f, ViewportMath.clampYFlip(77f, 960f, 1000f, 0f), 0f)
        assertEquals(0f, ViewportMath.clampYFlip(-20f, 1000f, 1000f, 0f), 0f)
        assertEquals(-50f, ViewportMath.clampYFlip(0f, 950f, 1000f, 50f), 0f)   // 위를 가린 줄만큼 내려 둔다
    }

    @Test fun flipCentersPageWhenThereIsALotOfRoom() {
        // 너비에 맞춘 가로로 넓은 쪽: 위아래로 남는 자리가 크면 가운데
        assertEquals(-300f, ViewportMath.clampYFlip(0f, 400f, 1000f, 0f), 0f)
    }

    @Test fun flipScrollsNormallyWhenZoomedIn() {
        assertEquals(500f, ViewportMath.clampYFlip(500f, 3000f, 1000f, 0f), 0f)
        assertEquals(2000f, ViewportMath.clampYFlip(99999f, 3000f, 1000f, 0f), 0f)
        assertEquals(0f, ViewportMath.clampYFlip(-99f, 3000f, 1000f, 0f), 0f)
    }
}
