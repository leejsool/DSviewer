package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.roundToInt
import kotlin.random.Random

/** 직선 형광펜 끝점, 네모 테이프 모서리, 회전 각 맞춤 */
class StrokeGeometryTest {

    // ================= 직선 형광펜 =================

    @Test fun highlighterSnapsToHorizontalWithinFiveDegrees() {
        val e = StrokeGeometry.straightHighlighterEnd(100f, 100f, 300f, 108f)   // 약 2.3°
        assertEquals(300f, e[0], 1e-6f); assertEquals(100f, e[1], 1e-6f)
    }

    @Test fun highlighterSnapsToVerticalWithinFiveDegrees() {
        val e = StrokeGeometry.straightHighlighterEnd(100f, 100f, 108f, 300f)
        assertEquals(100f, e[0], 1e-6f); assertEquals(300f, e[1], 1e-6f)
    }

    @Test fun highlighterKeepsOtherAngles() {
        val e = StrokeGeometry.straightHighlighterEnd(100f, 100f, 300f, 140f)   // 약 11°
        assertEquals(300f, e[0], 1e-6f); assertEquals(140f, e[1], 1e-6f)
        val d = StrokeGeometry.straightHighlighterEnd(0f, 0f, 100f, 100f)       // 45°
        assertEquals(100f, d[0], 1e-6f); assertEquals(100f, d[1], 1e-6f)
    }

    @Test fun highlighterSnapWorksInEveryDirection() {
        // 왼쪽·위쪽으로 그어도 같다 (절댓값으로 각을 잰다)
        val l = StrokeGeometry.straightHighlighterEnd(300f, 100f, 100f, 95f)
        assertEquals(100f, l[0], 1e-6f); assertEquals(100f, l[1], 1e-6f)
        val u = StrokeGeometry.straightHighlighterEnd(100f, 300f, 95f, 100f)
        assertEquals(100f, u[0], 1e-6f); assertEquals(100f, u[1], 1e-6f)
    }

    @Test fun highlighterBoundaryAtFiveAndEightyFiveDegrees() {
        // 정확히 5° (tan 5° ≈ 0.0875)는 맞춘다, 조금 넘으면 그대로
        val t5 = Math.tan(Math.toRadians(5.0)).toFloat()
        assertEquals(0f, StrokeGeometry.straightHighlighterEnd(0f, 0f, 1000f, 1000f * t5 - 0.01f)[1], 1e-6f)
        assertTrue(StrokeGeometry.straightHighlighterEnd(0f, 0f, 1000f, 1000f * t5 + 1f)[1] > 80f)
        // 85° 쪽도
        assertEquals(0f, StrokeGeometry.straightHighlighterEnd(0f, 0f, 1000f * t5 - 0.01f, 1000f)[0], 1e-6f)
        assertTrue(StrokeGeometry.straightHighlighterEnd(0f, 0f, 1000f * t5 + 1f, 1000f)[0] > 80f)
    }

    @Test fun highlighterZeroLengthStaysPut() {
        val e = StrokeGeometry.straightHighlighterEnd(50f, 50f, 50f, 50f)
        assertEquals(50f, e[0], 1e-6f); assertEquals(50f, e[1], 1e-6f)
    }

    // ================= 네모 테이프 =================

    @Test fun rectTapeCornersFollowTheDragCorner() {
        // 첫 점이 왼쪽 위, 지금 점이 오른쪽 아래: (x,y0), (x,y), (x0,y)가 이어진다
        assertEquals(listOf(300f, 100f, 300f, 200f, 100f, 200f), StrokeGeometry.rectTapeCorners(100f, 100f, 300f, 200f).toList())
        // 반대 방향으로 끌어도 같은 규칙 (마주 보는 모서리)
        assertEquals(listOf(50f, 400f, 50f, 300f, 200f, 300f), StrokeGeometry.rectTapeCorners(200f, 400f, 50f, 300f).toList())
    }

    // ================= 회전 각 =================

    @Test fun rotationSnapsToNinetyMultiplesWithinFiveDegrees() {
        assertEquals(90f, StrokeGeometry.snapRotation(86f), 1e-4f)
        assertEquals(90f, StrokeGeometry.snapRotation(94.9f), 1e-4f)
        assertEquals(0f, StrokeGeometry.snapRotation(-4.9f), 1e-4f)
        assertEquals(-90f, StrokeGeometry.snapRotation(-93f), 1e-4f)
        assertEquals(180f, StrokeGeometry.snapRotation(177f), 1e-4f)
    }

    @Test fun rotationSnapsToFortyFiveMultiplesWithinThreeDegrees() {
        assertEquals(45f, StrokeGeometry.snapRotation(47f), 1e-4f)
        assertEquals(135f, StrokeGeometry.snapRotation(133f), 1e-4f)
        assertEquals(-45f, StrokeGeometry.snapRotation(-42.5f), 1e-4f)
        // 허용 밖은 그대로
        assertEquals(40f, StrokeGeometry.snapRotation(40f), 1e-4f)
        assertEquals(60f, StrokeGeometry.snapRotation(60f), 1e-4f)
    }

    @Test fun rotationToleranceEdges() {
        // 90° 배수: 5.0° 안은 맞추고(경계 포함), 5.1°부터는 그대로 (단 45° 배수 3° 안이면 그쪽으로)
        assertEquals(90f, StrokeGeometry.snapRotation(95f), 1e-4f)
        assertEquals(95.1f, StrokeGeometry.snapRotation(95.1f), 1e-4f)
        assertEquals(84.9f, StrokeGeometry.snapRotation(84.9f), 1e-4f)
        // 45° 배수: 3.0° 안은 맞추고 3.1°부터는 그대로
        assertEquals(45f, StrokeGeometry.snapRotation(48f), 1e-4f)
        assertEquals(48.1f, StrokeGeometry.snapRotation(48.1f), 1e-4f)
        assertEquals(41.9f, StrokeGeometry.snapRotation(41.9f), 1e-4f)
        // 0° 근처도 같은 5° 허용
        assertEquals(0f, StrokeGeometry.snapRotation(5f), 1e-4f)
        assertEquals(5.1f, StrokeGeometry.snapRotation(5.1f), 1e-4f)
    }

    @Test fun rotationIsFoldedIntoHalfOpenRange() {
        // -180 초과 180 이하: 190° = -170°, 360°는 0°, -180°는 180°
        assertEquals(-170f, StrokeGeometry.snapRotation(190f), 1e-4f)
        assertEquals(0f, StrokeGeometry.snapRotation(360f), 1e-4f)
        assertEquals(180f, StrokeGeometry.snapRotation(-180f), 1e-4f)
        assertEquals(-170f, StrokeGeometry.snapRotation(-170f), 1e-4f)
        assertEquals(90f, StrokeGeometry.snapRotation(450f), 1e-4f)
        assertEquals(-90f, StrokeGeometry.snapRotation(-450f), 1e-4f)
    }

    /** 맞추기 전의 계산을 그대로 옮긴 기준 (DocumentView.movePen의 회전) */
    private fun referenceRotation(deg: Float): Float {
        var d = deg
        while (d > 180f) d -= 360f
        while (d <= -180f) d += 360f
        val r90 = (d / 90f).roundToInt() * 90f
        val r45 = (d / 45f).roundToInt() * 45f
        return when {
            abs(d - r90) <= 5f -> r90
            abs(d - r45) <= 3f -> r45
            else -> d
        }
    }

    @Test fun rotationMatchesTheOldCodeOnManyAngles() {
        val rnd = Random(4)
        repeat(50_000) {
            val d = (rnd.nextFloat() - 0.5f) * 1500f
            assertEquals("각 $d", referenceRotation(d), StrokeGeometry.snapRotation(d), 0f)
        }
    }

    /** 맞추기 전의 직선 형광펜 계산 (DocumentView.movePen) */
    private fun referenceHighlighter(x0: Float, y0: Float, px: Float, py: Float): Pair<Float, Float> {
        var ex = px
        var ey = py
        val dx = ex - x0
        val dy = ey - y0
        if (dx != 0f || dy != 0f) {
            val deg = Math.toDegrees(atan2(abs(dy).toDouble(), abs(dx).toDouble()))
            if (deg <= 5.0) ey = y0
            else if (deg >= 85.0) ex = x0
        }
        return ex to ey
    }

    @Test fun highlighterMatchesTheOldCodeOnRandomPoints() {
        val rnd = Random(8)
        repeat(50_000) {
            val x0 = rnd.nextFloat() * 600; val y0 = rnd.nextFloat() * 800
            val x = rnd.nextFloat() * 600; val y = rnd.nextFloat() * 800
            val (rx, ry) = referenceHighlighter(x0, y0, x, y)
            val e = StrokeGeometry.straightHighlighterEnd(x0, y0, x, y)
            assertEquals(rx, e[0], 0f); assertEquals(ry, e[1], 0f)
        }
    }
}
