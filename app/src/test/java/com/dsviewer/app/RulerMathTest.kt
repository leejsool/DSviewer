package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.random.Random

/** 자·눈금자·각도기 기하 */
class RulerMathTest {

    private val eps = 1e-3f

    // ================= 좌표 바꾸기 =================

    @Test fun localAndPageAreInverses() {
        val rnd = Random(1)
        repeat(5_000) {
            val cx = rnd.nextFloat() * 600; val cy = rnd.nextFloat() * 800
            val th = (rnd.nextFloat() - 0.5f) * 7f
            val px = rnd.nextFloat() * 600; val py = rnd.nextFloat() * 800
            val l = RulerMath.toLocal(px, py, cx, cy, th)
            val back = RulerMath.toPage(l[0], l[1], cx, cy, th)
            assertEquals(px, back[0], 0.01f); assertEquals(py, back[1], 0.01f)
        }
    }

    @Test fun flatRulerLocalAxesMatchThePage() {
        val l = RulerMath.toLocal(150f, 70f, 100f, 50f, 0f)
        assertEquals(50f, l[0], eps); assertEquals(20f, l[1], eps)
    }

    @Test fun quarterTurnPointsTheRulerAxisDownThePage() {
        // 시계 방향 90°: 자의 +u가 화면 아래쪽
        val p = RulerMath.toPage(10f, 0f, 100f, 100f, (PI / 2).toFloat())
        assertEquals(100f, p[0], eps); assertEquals(110f, p[1], eps)
        // +v는 화면 왼쪽
        val q = RulerMath.toPage(0f, 10f, 100f, 100f, (PI / 2).toFloat())
        assertEquals(90f, q[0], eps); assertEquals(100f, q[1], eps)
    }

    // ================= 막대 자 =================

    @Test fun barContainsItsBodyOnly() {
        assertTrue(RulerMath.barContains(0f, 0f, 400f, 60f))
        assertTrue(RulerMath.barContains(200f, 30f, 400f, 60f))
        assertFalse(RulerMath.barContains(201f, 0f, 400f, 60f))
        assertFalse(RulerMath.barContains(0f, 31f, 400f, 60f))
        assertTrue(RulerMath.barContains(205f, 0f, 400f, 60f, margin = 6f))
    }

    @Test fun barEdgePicksTheNearerSideWithinTolerance() {
        assertEquals(-1, RulerMath.barEdge(0f, -30f, 400f, 60f, 10f))
        assertEquals(-1, RulerMath.barEdge(0f, -38f, 400f, 60f, 10f))   // 바깥쪽 8
        assertEquals(1, RulerMath.barEdge(0f, 25f, 400f, 60f, 10f))    // 안쪽 5
        assertEquals(0, RulerMath.barEdge(0f, 0f, 400f, 60f, 10f))     // 한가운데
        assertEquals(0, RulerMath.barEdge(0f, -45f, 400f, 60f, 10f))   // 너무 멀다
    }

    @Test fun barEdgeStillCatchesJustPastTheEnds() {
        assertEquals(-1, RulerMath.barEdge(205f, -30f, 400f, 60f, 10f))
        assertEquals(0, RulerMath.barEdge(215f, -30f, 400f, 60f, 10f))
    }

    @Test fun clampUKeepsTheLineOnTheRuler() {
        assertEquals(200f, RulerMath.clampU(500f, 400f), 0f)
        assertEquals(-200f, RulerMath.clampU(-500f, 400f), 0f)
        assertEquals(12f, RulerMath.clampU(12f, 400f), 0f)
    }

    @Test fun narrowRulerStillPicksOneEdgeWhenBothAreWithinTolerance() {
        // 너비 12, 허용 10: 가운데는 두 가장자리 모두 6 → 위쪽을 고른다 (같으면 위)
        assertEquals(-1, RulerMath.barEdge(0f, 0f, 400f, 12f, 10f))
        assertEquals(1, RulerMath.barEdge(0f, 1f, 400f, 12f, 10f))
    }

    // ================= 각도기 =================

    @Test fun protractorEdgeFindsBaselineAndArc() {
        assertEquals(RulerMath.BASELINE, RulerMath.protractorEdge(50f, 3f, 100f, 10f))
        assertEquals(RulerMath.BASELINE, RulerMath.protractorEdge(-50f, -4f, 100f, 10f))
        assertEquals(RulerMath.ARC, RulerMath.protractorEdge(0f, -98f, 100f, 10f))
        assertEquals(RulerMath.ARC, RulerMath.protractorEdge(0f, -104f, 100f, 10f))
        assertEquals(RulerMath.NONE, RulerMath.protractorEdge(0f, -50f, 100f, 10f))
        assertEquals(RulerMath.NONE, RulerMath.protractorEdge(0f, 40f, 100f, 10f))
    }

    @Test fun protractorBaselineReachesJustPastTheEnds() {
        assertEquals(RulerMath.BASELINE, RulerMath.protractorEdge(105f, 0f, 100f, 10f))
        assertEquals(RulerMath.NONE, RulerMath.protractorEdge(115f, 0f, 100f, 10f))
    }

    @Test fun arcOnlyCoversTheUpperHalf() {
        // 아래쪽 반원 둘레 (v > 허용)는 붙지 않는다
        assertEquals(RulerMath.NONE, RulerMath.protractorEdge(0f, 100f, 100f, 10f))
    }

    @Test fun angleIsMeasuredFromTheBaselineCounterclockwiseUp() {
        assertEquals(0f, RulerMath.angleDeg(50f, 0f), eps)
        assertEquals(90f, RulerMath.angleDeg(0f, -50f), eps)
        assertEquals(180f, RulerMath.angleDeg(-50f, 0f), eps)
        assertEquals(45f, RulerMath.angleDeg(30f, -30f), eps)
        assertEquals(135f, RulerMath.angleDeg(-30f, -30f), eps)
    }

    @Test fun anglePastTheBaselineSticksToTheNearerEnd() {
        assertEquals(0f, RulerMath.angleDeg(20f, 10f), 0f)
        assertEquals(180f, RulerMath.angleDeg(-20f, 10f), 0f)
    }

    @Test fun arcPointAndAngleAreInverses() {
        val rnd = Random(2)
        repeat(2_000) {
            val deg = rnd.nextFloat() * 180f
            val p = RulerMath.arcPoint(100f, deg)
            assertEquals(100f, hypot(p[0], p[1]), 1e-3f)
            assertEquals(deg, RulerMath.angleDeg(p[0], p[1]), 0.01f)
            assertTrue("위쪽 반원", p[1] <= 1e-4f)
        }
    }

    @Test fun arcAnglesIncludeBothEndsAndStaySmall() {
        val a = RulerMath.arcAngles(10f, 100f, 2f)
        assertEquals(10f, a.first(), 0f)
        assertEquals(100f, a.last(), 1e-4f)
        for (i in 1 until a.size) assertTrue(abs(a[i] - a[i - 1]) <= 2f + 1e-4f)
        // 거꾸로 가도 같다
        val b = RulerMath.arcAngles(100f, 10f, 2f)
        assertEquals(100f, b.first(), 0f)
        assertEquals(10f, b.last(), 1e-4f)
        // 같은 각이면 점 하나가 아니라 두 점 (선이 비지 않게)
        assertEquals(2, RulerMath.arcAngles(30f, 30f).size)
    }

    // ================= 두 손가락 =================

    @Test fun twoFingersThatDoNotMoveLeaveTheRulerAlone() {
        val r = RulerMath.twoFinger(0f, 0f, 100f, 0f, 0f, 0f, 100f, 0f, 40f, 30f)
        assertEquals(0f, r[0], 1e-6f); assertEquals(40f, r[1], eps); assertEquals(30f, r[2], eps)
    }

    @Test fun twoFingersSlidingTogetherMoveTheRulerWithoutTurningIt() {
        val r = RulerMath.twoFinger(0f, 0f, 100f, 0f, 20f, 15f, 120f, 15f, 40f, 30f)
        assertEquals(0f, r[0], 1e-6f); assertEquals(60f, r[1], eps); assertEquals(45f, r[2], eps)
    }

    @Test fun twoFingersTurningAQuarterSwingTheRulerAroundTheirMiddle() {
        // 가운데 (50, 0)을 축으로 시계 방향 90°: 오른쪽 손가락이 아래로
        val r = RulerMath.twoFinger(0f, 0f, 100f, 0f, 50f, -50f, 50f, 50f, 50f, 0f)
        assertEquals((PI / 2).toFloat(), r[0], 1e-4f)
        // 자의 가운데가 두 손가락의 가운데와 겹쳐 있었으므로 그대로
        assertEquals(50f, r[1], eps); assertEquals(0f, r[2], eps)
        // 가운데에서 오른쪽으로 20 떨어져 있던 점은 돌아서 아래쪽 20으로
        val r2 = RulerMath.twoFinger(0f, 0f, 100f, 0f, 50f, -50f, 50f, 50f, 70f, 0f)
        assertEquals(50f, r2[1], eps); assertEquals(20f, r2[2], eps)
    }

    @Test fun rotationIsTheShortWayAroundAcrossTheSeam() {
        // 170° → -170°는 +20°
        val a0 = Math.toRadians(170.0); val a1 = Math.toRadians(-170.0)
        val r = RulerMath.twoFinger(
            0f, 0f, Math.cos(a0).toFloat() * 100, Math.sin(a0).toFloat() * 100,
            0f, 0f, Math.cos(a1).toFloat() * 100, Math.sin(a1).toFloat() * 100, 0f, 0f,
        )
        assertEquals(Math.toRadians(20.0).toFloat(), r[0], 1e-4f)
    }

    @Test fun rotatingThenRotatingBackRestoresTheCenter() {
        val rnd = Random(9)
        repeat(1_000) {
            val p = floatArrayOf(rnd.nextFloat() * 300, rnd.nextFloat() * 300, rnd.nextFloat() * 300 + 301, rnd.nextFloat() * 300)
            val q = floatArrayOf(rnd.nextFloat() * 300, rnd.nextFloat() * 300, rnd.nextFloat() * 300 + 301, rnd.nextFloat() * 300)
            val cx = rnd.nextFloat() * 400; val cy = rnd.nextFloat() * 400
            val fwd = RulerMath.twoFinger(p[0], p[1], p[2], p[3], q[0], q[1], q[2], q[3], cx, cy)
            val back = RulerMath.twoFinger(q[0], q[1], q[2], q[3], p[0], p[1], p[2], p[3], fwd[1], fwd[2])
            assertEquals(cx, back[1], 0.05f); assertEquals(cy, back[2], 0.05f)
            assertEquals(0f, fwd[0] + back[0], 1e-4f)
        }
    }

    // ================= 눈금·읽기 =================

    @Test fun tickKindsRepeatEveryMillimetreFiveAndTen() {
        assertEquals(10, RulerMath.tickKind(0))
        assertEquals(1, RulerMath.tickKind(1))
        assertEquals(5, RulerMath.tickKind(5))
        assertEquals(1, RulerMath.tickKind(9))
        assertEquals(10, RulerMath.tickKind(10))
        assertEquals(5, RulerMath.tickKind(15))
        assertEquals(10, RulerMath.tickKind(150))
    }

    @Test fun lengthsReadInMillimetresBelowOneCentimetre() {
        // 72pt = 1인치 = 25.4mm
        assertEquals("2.5 cm", RulerMath.formatLength(72f))
        assertEquals("1.8 mm", RulerMath.formatLength(5f))
        assertEquals("0.0 mm", RulerMath.formatLength(0f))
        assertEquals("15.0 cm", RulerMath.formatLength(RulerMath.BAR_LENGTH))
    }

    @Test fun anglesReadAsWholeDegrees() {
        assertEquals("63°", RulerMath.formatAngle(62.6f))
        assertEquals("0°", RulerMath.formatAngle(0.3f))
        assertEquals("180°", RulerMath.formatAngle(179.7f))
    }

    // ================= 크기 =================

    @Test fun defaultSizesAreRealCentimetres() {
        assertEquals(150f, RulerMath.BAR_LENGTH * RulerMath.MM_PER_PT, 1e-2f)
        assertEquals(22f, RulerMath.BAR_WIDTH * RulerMath.MM_PER_PT, 1e-2f)
        assertEquals(60f, RulerMath.PROTRACTOR_RADIUS * RulerMath.MM_PER_PT, 1e-2f)
    }

    @Test fun sizesShrinkOnNarrowPagesOnly() {
        assertEquals(RulerMath.BAR_LENGTH, RulerMath.barLength(842f), 0f)
        assertEquals(200f * 0.9f, RulerMath.barLength(200f), 1e-3f)
        assertEquals(RulerMath.PROTRACTOR_RADIUS, RulerMath.protractorRadius(595f), 0f)
        assertEquals(100f * 0.45f, RulerMath.protractorRadius(100f), 1e-3f)
    }

    // ================= 각도기 가운데에서 긋기 =================

    @Test fun nearTheVertexTheCenterWinsOverTheBaseline() {
        assertEquals(RulerMath.CENTER, RulerMath.protractorEdge(0f, 0f, 100f, 10f))
        assertEquals(RulerMath.CENTER, RulerMath.protractorEdge(8f, -6f, 100f, 10f))   // 거리 10 ≤ 12
        assertEquals(RulerMath.CENTER, RulerMath.protractorEdge(-11f, 0f, 100f, 10f))  // 밑변 위지만 가운데 쪽
        assertEquals(RulerMath.BASELINE, RulerMath.protractorEdge(20f, 0f, 100f, 10f)) // 거리 20 > 12
    }

    @Test fun rayAngleGoesAroundTheFullCircle() {
        assertEquals(0f, RulerMath.rayAngleDeg(50f, 0f), eps)
        assertEquals(90f, RulerMath.rayAngleDeg(0f, -50f), eps)
        assertEquals(180f, RulerMath.rayAngleDeg(-50f, 0f), eps)
        assertEquals(270f, RulerMath.rayAngleDeg(0f, 50f), eps)
        assertEquals(315f, RulerMath.rayAngleDeg(30f, 30f), eps)
    }

    @Test fun rayAngleIsAlwaysInRange() {
        val rnd = Random(12)
        repeat(5_000) {
            val d = RulerMath.rayAngleDeg(rnd.nextFloat() * 200 - 100, rnd.nextFloat() * 200 - 100)
            assertTrue("각 $d", d >= 0f && d < 360f)
        }
        // 위쪽 반원에서는 밑변 각과 같다
        repeat(2_000) {
            val u = rnd.nextFloat() * 200 - 100
            val v = -rnd.nextFloat() * 100 - 1f
            assertEquals(RulerMath.angleDeg(u, v), RulerMath.rayAngleDeg(u, v), 1e-3f)
        }
    }

    @Test fun snapDegRoundsToWholeDegreesAndWraps() {
        assertEquals(63f, RulerMath.snapDeg(62.6f), 0f)
        assertEquals(62f, RulerMath.snapDeg(62.4f), 0f)
        assertEquals(0f, RulerMath.snapDeg(359.6f), 0f)
        assertEquals(0f, RulerMath.snapDeg(0.2f), 0f)
        assertEquals(359f, RulerMath.snapDeg(359.4f), 0f)
    }

    @Test fun aRayDrawnFromTheVertexEndsExactlyOnTheSnappedAngle() {
        // 가운데에서 (u,v)를 향해 그린 선 끝은 정수 도 위에 놓이고 길이는 그대로
        val rnd = Random(13)
        repeat(3_000) {
            val u = rnd.nextFloat() * 300 - 150
            val v = rnd.nextFloat() * 300 - 150
            val d = hypot(u, v)
            val snapped = RulerMath.snapDeg(RulerMath.rayAngleDeg(u, v))
            val e = RulerMath.arcPoint(d, snapped)
            assertEquals(d, hypot(e[0], e[1]), 1e-2f)
            assertEquals(snapped, RulerMath.rayAngleDeg(e[0], e[1]), 0.05f + (if (d < 1f) 5f else 0f))
        }
    }

    // ================= 두 손가락으로 키우고 줄이기 =================

    @Test fun spreadingTheFingersScalesTheRulerAboutTheirMiddle() {
        // 두 손가락 거리 100 → 200 (가운데 (50, 0) 고정): 가운데에서 20 떨어진 점은 40 떨어진다
        val r = RulerMath.twoFinger(0f, 0f, 100f, 0f, -50f, 0f, 150f, 0f, 70f, 0f)
        assertEquals(0f, r[0], 1e-6f)
        assertEquals(2f, r[3], 1e-5f)
        assertEquals(90f, r[1], eps); assertEquals(0f, r[2], eps)
    }

    @Test fun pinchingInHalvesTheRuler() {
        val r = RulerMath.twoFinger(0f, 0f, 100f, 0f, 25f, 0f, 75f, 0f, 70f, 0f)
        assertEquals(0.5f, r[3], 1e-5f)
        // 가운데 (50, 0)에서 20 → 10
        assertEquals(60f, r[1], eps)
    }

    @Test fun fingersThatStartOnTopOfEachOtherDoNotScale() {
        val r = RulerMath.twoFinger(10f, 10f, 10.2f, 10f, 0f, 0f, 100f, 0f, 5f, 5f)
        assertEquals(1f, r[3], 0f)
        assertTrue(r[1].isFinite() && r[2].isFinite())
    }

    @Test fun scaleOfTheStepAlwaysMatchesTheFingerDistanceRatio() {
        val rnd = Random(21)
        repeat(2_000) {
            val p0 = floatArrayOf(rnd.nextFloat() * 300, rnd.nextFloat() * 300, 400 + rnd.nextFloat() * 300, rnd.nextFloat() * 300)
            val p1 = floatArrayOf(rnd.nextFloat() * 300, rnd.nextFloat() * 300, 400 + rnd.nextFloat() * 300, rnd.nextFloat() * 300)
            val r = RulerMath.twoFinger(p0[0], p0[1], p0[2], p0[3], p1[0], p1[1], p1[2], p1[3], 100f, 100f)
            val want = hypot(p1[2] - p1[0], p1[3] - p1[1]) / hypot(p0[2] - p0[0], p0[3] - p0[1])
            assertEquals(want, r[3], 1e-4f)
        }
    }

    @Test fun sizesStayWithinTheLimits() {
        assertEquals(RulerMath.MIN_LENGTH, RulerMath.clampLength(1f), 0f)
        assertEquals(RulerMath.MAX_LENGTH, RulerMath.clampLength(99999f), 0f)
        assertEquals(300f, RulerMath.clampLength(300f), 0f)
        assertEquals(RulerMath.MIN_RADIUS, RulerMath.clampRadius(1f), 0f)
        assertEquals(RulerMath.MAX_RADIUS, RulerMath.clampRadius(99999f), 0f)
        assertEquals(150f, RulerMath.clampRadius(150f), 0f)
        // 처음 크기는 범위 안
        assertTrue(RulerMath.BAR_LENGTH in RulerMath.MIN_LENGTH..RulerMath.MAX_LENGTH)
        assertTrue(RulerMath.PROTRACTOR_RADIUS in RulerMath.MIN_RADIUS..RulerMath.MAX_RADIUS)
    }
}
