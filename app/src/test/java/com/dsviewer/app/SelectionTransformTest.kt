package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.random.Random

/** 선택 옮기기·크기 조절·회전·올가미 판정의 계산 */
class SelectionTransformTest {

    private val box = floatArrayOf(100f, 200f, 300f, 260f)     // 가로 200, 세로 60

    // ================= 크기 조절 기준점 =================

    @Test fun cornerHandleUsesTheOppositeCornerAsAnchor() {
        // 0 왼쪽 위 → 기준은 오른쪽 아래
        var s = SelectionTransform.resizeSetup(0, box)
        assertEquals(300f, s.anchorX, 0f); assertEquals(260f, s.anchorY, 0f)
        assertEquals(0, s.axis)
        assertEquals(hypot(200f, 60f), s.startDist, 1e-3f)
        // 3 오른쪽 아래 → 기준은 왼쪽 위
        s = SelectionTransform.resizeSetup(3, box)
        assertEquals(100f, s.anchorX, 0f); assertEquals(200f, s.anchorY, 0f)
        // 1 오른쪽 위 → 왼쪽 아래, 2 왼쪽 아래 → 오른쪽 위
        s = SelectionTransform.resizeSetup(1, box)
        assertEquals(100f, s.anchorX, 0f); assertEquals(260f, s.anchorY, 0f)
        s = SelectionTransform.resizeSetup(2, box)
        assertEquals(300f, s.anchorX, 0f); assertEquals(200f, s.anchorY, 0f)
    }

    @Test fun sideHandlesResizeOneAxisAroundTheOppositeSide() {
        // 4 위 변: 아래 변이 기준, 위아래로만, 변은 기준 위쪽(-)
        var s = SelectionTransform.resizeSetup(4, box)
        assertEquals(2, s.axis); assertEquals(260f, s.anchorY, 0f); assertEquals(200f, s.anchorX, 0f)
        assertEquals(-1f, s.dir, 0f); assertEquals(60f, s.startDist, 1e-4f)
        // 5 아래 변
        s = SelectionTransform.resizeSetup(5, box)
        assertEquals(2, s.axis); assertEquals(200f, s.anchorY, 0f); assertEquals(1f, s.dir, 0f)
        // 6 왼쪽 변: 오른쪽 변이 기준, 옆으로만
        s = SelectionTransform.resizeSetup(6, box)
        assertEquals(1, s.axis); assertEquals(300f, s.anchorX, 0f); assertEquals(230f, s.anchorY, 0f)
        assertEquals(-1f, s.dir, 0f); assertEquals(200f, s.startDist, 1e-4f)
        // 7 오른쪽 변
        s = SelectionTransform.resizeSetup(7, box)
        assertEquals(1, s.axis); assertEquals(100f, s.anchorX, 0f); assertEquals(1f, s.dir, 0f)
    }

    @Test fun startDistanceIsAtLeastOne() {
        val tiny = floatArrayOf(50f, 50f, 50.2f, 50.3f)
        assertEquals(1f, SelectionTransform.resizeSetup(3, tiny).startDist, 0f)
        assertEquals(1f, SelectionTransform.resizeSetup(4, tiny).startDist, 0f)
    }

    // ================= 끄는 만큼의 배율 =================

    @Test fun draggingTheCornerOutwardScalesBothAxesTheSame() {
        val s = SelectionTransform.resizeSetup(3, box)         // 기준 왼쪽 위 (100, 200), 시작 거리 hypot(200,60)
        val k = SelectionTransform.resizeScale(s, 100f + 400f, 200f + 120f, 1f, 1f)
        assertEquals(2f, k[0], 1e-4f); assertEquals(2f, k[1], 1e-4f)
    }

    @Test fun cornerScaleIsClampedBetweenPointOneAndTwenty() {
        val s = SelectionTransform.resizeSetup(3, box)
        assertEquals(0.1f, SelectionTransform.resizeScale(s, 100f, 200f, 1f, 1f)[0], 0f)
        assertEquals(20f, SelectionTransform.resizeScale(s, 100000f, 100000f, 1f, 1f)[0], 0f)
    }

    @Test fun sideHandleScalesOnlyItsAxisAndKeepsTheOther() {
        val right = SelectionTransform.resizeSetup(7, box)      // 옆으로만, 기준 왼쪽 변 x=100, 시작 200
        val k = SelectionTransform.resizeScale(right, 500f, 999f, 1f, 1.5f)
        assertEquals(2f, k[0], 1e-4f); assertEquals(1.5f, k[1], 1e-4f)
        val top = SelectionTransform.resizeSetup(4, box)        // 위아래로만, 기준 아래 변 y=260, 방향 -
        val m = SelectionTransform.resizeScale(top, 999f, 260f - 120f, 1.7f, 1f)
        assertEquals(1.7f, m[0], 1e-4f); assertEquals(2f, m[1], 1e-4f)
    }

    @Test fun sideHandleDragPastTheAnchorFlipsToTheMinimum() {
        // 위 변을 기준선 아래로 끌어 넘기면 배율은 음수 → 하한 0.05
        val top = SelectionTransform.resizeSetup(4, box)
        assertEquals(0.05f, SelectionTransform.resizeScale(top, 0f, 400f, 1f, 1f)[1], 0f)
    }

    // ================= 각 =================

    @Test fun angleIsMeasuredFromTheCenter() {
        assertEquals(0f, SelectionTransform.angleDeg(110f, 50f, 100f, 50f), 1e-4f)      // 오른쪽
        assertEquals(90f, SelectionTransform.angleDeg(100f, 60f, 100f, 50f), 1e-4f)     // 아래 (화면 y가 아래로 늘어남)
        assertEquals(180f, SelectionTransform.angleDeg(90f, 50f, 100f, 50f), 1e-3f)
        assertEquals(-90f, SelectionTransform.angleDeg(100f, 40f, 100f, 50f), 1e-4f)
    }

    // ================= 미리보기 상자 =================

    @Test fun previewWithoutChangeIsTheBox() {
        assertEquals(box.toList(), SelectionTransform.previewBounds(box, false, 0f, 0f, 1f, 1f, 0f, 0f).toList())
    }

    @Test fun previewMovesTheBox() {
        assertEquals(listOf(110f, 195f, 310f, 255f), SelectionTransform.previewBounds(box, false, 0f, 0f, 1f, 1f, 10f, -5f).toList())
    }

    @Test fun previewScalesAroundTheAnchor() {
        // 왼쪽 위(100,200) 기준 가로 2배, 세로 0.5배
        val p = SelectionTransform.previewBounds(box, true, 100f, 200f, 2f, 0.5f, 0f, 0f)
        assertEquals(listOf(100f, 200f, 500f, 230f), p.toList())
    }

    @Test fun previewKeepsTheAnchorFixedForEveryHandle() {
        for (h in 0..7) {
            val s = SelectionTransform.resizeSetup(h, box)
            val k = SelectionTransform.resizeScale(s, s.anchorX + 80f * s.dir, s.anchorY + 50f * s.dir, 1f, 1f)
            val p = SelectionTransform.previewBounds(box, true, s.anchorX, s.anchorY, k[0], k[1], 0f, 0f)
            // 기준점은 상자 안(또는 변 위)에서 그대로여야 한다
            assertTrue("손잡이 $h", s.anchorX in p[0] - 1e-3f..p[2] + 1e-3f)
            assertTrue("손잡이 $h", s.anchorY in p[1] - 1e-3f..p[3] + 1e-3f)
            assertTrue(p[0] <= p[2] && p[1] <= p[3])
        }
    }

    @Test fun previewIsSortedWhenTheScaleWouldFlipIt() {
        // 음수 배율(뒤집힘)이어도 왼쪽 ≤ 오른쪽
        val p = SelectionTransform.previewBounds(box, true, 100f, 200f, -1f, -1f, 0f, 0f)
        assertEquals(listOf(-100f, 140f, 100f, 200f), p.toList())
    }

    // ================= 화면 좌표 =================

    @Test fun screenRectUsesPagePositionScrollScaleAndPad() {
        // 쪽 위치 (10, 300), 배율 2, 스크롤 (40, 500), 여백 6
        val r = SelectionTransform.toScreen(box, 10f, 300f, 2f, 40f, 500f, 6f)
        assertEquals(listOf((10f + 100f) * 2 - 40 - 6, (300f + 200f) * 2 - 500 - 6, (10f + 300f) * 2 - 40 + 6, (300f + 260f) * 2 - 500 + 6), r.toList())
    }

    // ================= 회전 손잡이 =================

    @Test fun rotateHandleSitsBelowTheBoxCentered() {
        val h = SelectionTransform.rotateHandle(floatArrayOf(100f, 200f, 300f, 260f), 34f, 14f, 1000f, 4f)
        assertEquals(200f, h[0], 1e-4f); assertEquals(294f, h[1], 1e-4f)
    }

    @Test fun rotateHandleGoesAboveWhenThereIsNoRoomBelow() {
        // 아래 한계 (1000 - 4)에 닿으면 위로
        val h = SelectionTransform.rotateHandle(floatArrayOf(100f, 700f, 300f, 960f), 34f, 14f, 1000f, 4f)
        assertEquals(700f - 34f, h[1], 1e-4f)
    }

    @Test fun rotateHandleStaysBelowWhenAboveIsOffScreenToo() {
        // 위에도 자리가 없으면(상자 위가 화면 밖) 아래에 둔다
        val h = SelectionTransform.rotateHandle(floatArrayOf(100f, 20f, 300f, 990f), 34f, 14f, 1000f, 4f)
        assertEquals(990f + 34f, h[1], 1e-4f)
    }

    @Test fun rotateHandleBoundaryExactlyFitsBelow() {
        // 상자 아래 548 + 34 = 582, 손잡이 반지름 14 → 596 = 600 - 4: 딱 맞으면 아래
        val h = SelectionTransform.rotateHandle(floatArrayOf(0f, 100f, 100f, 548f), 34f, 14f, 600f, 4f)
        assertEquals(582f, h[1], 1e-4f)
        // 1px만 더 내려가면 위로
        val u = SelectionTransform.rotateHandle(floatArrayOf(0f, 100f, 100f, 549f), 34f, 14f, 600f, 4f)
        assertEquals(100f - 34f, u[1], 1e-4f)
    }

    // ================= 올가미 =================

    @Test fun lassoBoundsCoverAllPoints() {
        val pts = floatArrayOf(10f, 40f, 90f, 5f, 50f, 100f, 0f, 0f)
        val b = SelectionTransform.lassoBounds(pts, 3)            // 처음 3점만
        assertEquals(listOf(10f, 5f, 90f, 100f), b.toList())
    }

    @Test fun tinyLassoCountsAsATap() {
        val d = 2f
        assertTrue(SelectionTransform.isTap(floatArrayOf(0f, 0f, 4.9f, 3f), 2f, d))        // 긴 쪽 9.8px < 20px
        assertTrue(!SelectionTransform.isTap(floatArrayOf(0f, 0f, 10f, 3f), 2f, d))        // 20px
        assertTrue(!SelectionTransform.isTap(floatArrayOf(0f, 0f, 3f, 30f), 2f, d))        // 세로가 길어도
    }

    @Test fun rectSelectionSortsTheTwoPoints() {
        val r = SelectionTransform.rectSelection(200f, 300f, 100f, 100f, 1f, 2f)!!
        assertEquals(listOf(100f, 100f, 200f, 300f), r.toList())
    }

    @Test fun rectSelectionThinnerThanFourDpIsRejected() {
        assertNull(SelectionTransform.rectSelection(0f, 0f, 3.9f, 100f, 2f, 2f))           // 가로 7.8px < 8px
        assertNull(SelectionTransform.rectSelection(0f, 0f, 100f, 3.9f, 2f, 2f))
        assertTrue(SelectionTransform.rectSelection(0f, 0f, 4f, 4f, 2f, 2f) != null)       // 정확히 8px는 통과
    }

    // ================= 옛 계산과 대조 =================

    @Test fun resizeMatchesTheOldCodeOnRandomHandles() {
        val rnd = Random(40)
        repeat(20_000) {
            val l = rnd.nextFloat() * 500; val t = rnd.nextFloat() * 700
            val b = floatArrayOf(l, t, l + 5 + rnd.nextFloat() * 400, t + 5 + rnd.nextFloat() * 400)
            val handle = rnd.nextInt(8)
            val px = rnd.nextFloat() * 900 - 100; val py = rnd.nextFloat() * 1100 - 100
            // 옛 DocumentView.startLasso / movePen 계산
            var anchorX: Float; var anchorY: Float; var startDist: Float; var axis = 0; var dir = 1f
            if (handle in 0..3) {
                val left = handle == 0 || handle == 2
                val top = handle == 0 || handle == 1
                anchorX = if (left) b[2] else b[0]
                anchorY = if (top) b[3] else b[1]
                startDist = max(hypot((if (left) b[0] else b[2]) - anchorX, (if (top) b[1] else b[3]) - anchorY), 1f)
            } else {
                axis = if (handle <= 5) 2 else 1
                anchorX = when (handle) { 6 -> b[2]; 7 -> b[0]; else -> (b[0] + b[2]) / 2 }
                anchorY = when (handle) { 4 -> b[3]; 5 -> b[1]; else -> (b[1] + b[3]) / 2 }
                val edge = when (handle) { 4 -> b[1] - anchorY; 5 -> b[3] - anchorY; 6 -> b[0] - anchorX; else -> b[2] - anchorX }
                dir = if (edge < 0f) -1f else 1f
                startDist = max(abs(edge), 1f)
            }
            var kx = 1f; var ky = 1f
            when (axis) {
                1 -> kx = ((px - anchorX) * dir / startDist).coerceIn(0.05f, 20f)
                2 -> ky = ((py - anchorY) * dir / startDist).coerceIn(0.05f, 20f)
                else -> { kx = (hypot(px - anchorX, py - anchorY) / startDist).coerceIn(0.1f, 20f); ky = kx }
            }
            val s = SelectionTransform.resizeSetup(handle, b)
            assertEquals(anchorX, s.anchorX, 0f); assertEquals(anchorY, s.anchorY, 0f)
            assertEquals(startDist, s.startDist, 0f); assertEquals(axis, s.axis); assertEquals(dir, s.dir, 0f)
            val k = SelectionTransform.resizeScale(s, px, py, 1f, 1f)
            assertEquals(kx, k[0], 0f); assertEquals(ky, k[1], 0f)
            // 미리보기: RectF.sort와 같은 결과
            val dx = rnd.nextFloat() * 100 - 50; val dy = rnd.nextFloat() * 100 - 50
            val l2 = anchorX + (b[0] - anchorX) * kx; val t2 = anchorY + (b[1] - anchorY) * ky
            val r2 = anchorX + (b[2] - anchorX) * kx; val b2 = anchorY + (b[3] - anchorY) * ky
            val want = floatArrayOf(min(l2, r2) + dx, min(t2, b2) + dy, max(l2, r2) + dx, max(t2, b2) + dy)
            assertEquals(want.toList(), SelectionTransform.previewBounds(b, true, anchorX, anchorY, kx, ky, dx, dy).toList())
        }
    }

    @Test fun angleMatchesTheOldCode() {
        val rnd = Random(41)
        repeat(5_000) {
            val px = rnd.nextFloat() * 600; val py = rnd.nextFloat() * 800
            val cx = rnd.nextFloat() * 600; val cy = rnd.nextFloat() * 800
            val want = Math.toDegrees(atan2((py - cy).toDouble(), (px - cx).toDouble())).toFloat()
            assertEquals(want, SelectionTransform.angleDeg(px, py, cx, cy), 0f)
        }
    }
}
