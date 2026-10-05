package com.dsviewer.app

import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 자·눈금자·각도기의 기하 계산. 자는 쪽 위의 한 점(가운데)과 기울기로 놓이고, 자 기준 좌표계는
 * u가 자의 길이 방향, v가 그에 수직(화면 아래쪽이 +v)이다. 막대 자는 |u| ≤ 길이/2, |v| ≤ 너비/2의 직사각형이고,
 * 각도기는 가운데(0, 0)를 중심으로 v ≤ 0인 쪽(자를 눕혔을 때 위쪽)에 반지름 R의 반원이 놓인다.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다. 거리 단위는 쪽 좌표(PDF 포인트).
 */
internal object RulerMath {

    /** PDF 1포인트가 몇 mm인가 (1pt = 1/72 inch) */
    const val MM_PER_PT = 25.4f / 72f
    const val PT_PER_MM = 72f / 25.4f

    /** 막대 자의 처음 길이 15cm · 너비 2.2cm, 각도기 반지름 6cm (쪽 포인트) */
    const val BAR_LENGTH = 15f * 10f * PT_PER_MM
    const val BAR_WIDTH = 22f * PT_PER_MM
    const val PROTRACTOR_RADIUS = 60f * PT_PER_MM

    /** 두 손가락으로 키우고 줄일 수 있는 범위: 막대 자 길이 3cm~45cm, 각도기 반지름 2cm~25cm */
    const val MIN_LENGTH = 30f * PT_PER_MM
    const val MAX_LENGTH = 450f * PT_PER_MM
    const val MIN_RADIUS = 20f * PT_PER_MM
    const val MAX_RADIUS = 250f * PT_PER_MM

    fun clampLength(l: Float): Float = l.coerceIn(MIN_LENGTH, MAX_LENGTH)
    fun clampRadius(r: Float): Float = r.coerceIn(MIN_RADIUS, MAX_RADIUS)

    /** 쪽 너비 [pageW]에 맞춰 줄인 막대 자 길이 (쪽의 90%를 넘지 않게) */
    fun barLength(pageW: Float): Float = min(BAR_LENGTH, pageW * 0.9f)

    /** 쪽 너비 [pageW]에 맞춰 줄인 각도기 반지름 (지름이 쪽의 90%를 넘지 않게) */
    fun protractorRadius(pageW: Float): Float = min(PROTRACTOR_RADIUS, pageW * 0.45f)

    // ================= 좌표 바꾸기 =================

    /** 쪽 좌표 ([px], [py])를 자 기준 좌표 [u, v]로. 자는 가운데 ([cx], [cy]), 기울기 [theta](라디안, 화면에서 시계 방향이 +) */
    fun toLocal(px: Float, py: Float, cx: Float, cy: Float, theta: Float): FloatArray {
        val dx = px - cx
        val dy = py - cy
        val c = cos(theta)
        val s = sin(theta)
        return floatArrayOf(dx * c + dy * s, -dx * s + dy * c)
    }

    /** 자 기준 좌표 ([u], [v])를 쪽 좌표 [x, y]로 */
    fun toPage(u: Float, v: Float, cx: Float, cy: Float, theta: Float): FloatArray {
        val c = cos(theta)
        val s = sin(theta)
        return floatArrayOf(cx + u * c - v * s, cy + u * s + v * c)
    }

    // ================= 막대 자 =================

    /** 점 (u, v)가 길이 [len] 너비 [wid]인 막대 안인가 ([margin]만큼 넉넉하게) */
    fun barContains(u: Float, v: Float, len: Float, wid: Float, margin: Float = 0f): Boolean =
        abs(u) <= len / 2 + margin && abs(v) <= wid / 2 + margin

    /**
     * 막대 자의 어느 가장자리에 붙일까: 위쪽(v = -너비/2)이면 -1, 아래쪽(v = +너비/2)이면 +1, 어느 쪽도 [tol] 안이 아니면 0.
     * 길이 방향으로는 자의 끝에서 [tol]까지 벗어나도 붙는다 (끝에서 시작한 선이 놓치지 않게)
     */
    fun barEdge(u: Float, v: Float, len: Float, wid: Float, tol: Float): Int {
        if (abs(u) > len / 2 + tol) return 0
        val top = abs(v + wid / 2)
        val bottom = abs(v - wid / 2)
        return when {
            top > tol && bottom > tol -> 0
            top <= bottom -> -1
            else -> 1
        }
    }

    /** 막대 자 가장자리 위에서 길이 방향 위치 [u]를 자 안으로 가둔다 */
    fun clampU(u: Float, len: Float): Float = u.coerceIn(-len / 2, len / 2)

    // ================= 각도기 =================

    const val NONE = 0
    const val BASELINE = 1
    const val ARC = 2
    /** 각도기 가운데(꼭짓점): 여기서 시작해 끌면 끈 쪽으로 곧은 선이 그어진다 */
    const val CENTER = 3

    /** 가운데로 보는 거리는 붙는 거리의 이 배 */
    const val CENTER_FACTOR = 1.2f

    /**
     * 각도기의 어디에 붙일까: 가운데(꼭짓점)면 [CENTER], 밑변(지름, v = 0)이면 [BASELINE], 둥근 가장자리(반지름 [r])면 [ARC],
     * 어디도 [tol] 안이 아니면 [NONE]. 가운데는 밑변 위에 있지만 먼저 본다 (꼭짓점에서 시작해 밑변 쪽으로 그은 선도 같은 선이다).
     * 둥근 가장자리는 위쪽 반원(v ≤ 0)만이고, 밑변은 지름 끝에서 [tol]까지 벗어나도 붙는다
     */
    fun protractorEdge(u: Float, v: Float, r: Float, tol: Float): Int {
        if (hypot(u, v) <= tol * CENTER_FACTOR) return CENTER
        val inf = Float.MAX_VALUE
        val base = if (abs(u) <= r + tol) abs(v) else inf
        val arc = if (v <= tol) abs(hypot(u, v) - r) else inf
        return when {
            base > tol && arc > tol -> NONE
            base <= arc -> BASELINE
            else -> ARC
        }
    }

    /** 각도기 위의 점 (u, v)가 밑변에서 이루는 각 (도, 0~180). 아래쪽(v > 0)이면 가까운 밑변 끝(0 또는 180)으로 */
    fun angleDeg(u: Float, v: Float): Float {
        if (v > 0f) return if (u >= 0f) 0f else 180f
        // v가 -0.0이면 atan2가 음수 쪽(-180°)으로 돌아가므로 0은 늘 +0으로 만든다
        val up = if (v < 0f) -v else 0f
        return Math.toDegrees(atan2(up, u).toDouble()).toFloat().coerceIn(0f, 180f)
    }

    /**
     * 가운데에서 점 (u, v)를 본 방향의 각 (도, 0 이상 360 미만): 밑변 오른쪽이 0°, 위쪽이 90°, 왼쪽이 180°, 아래쪽이 270°.
     * 밑변 위쪽 반원 밖(아래)으로 끌어도 선을 그을 수 있도록 한 바퀴 모두 쓴다
     */
    fun rayAngleDeg(u: Float, v: Float): Float {
        // v가 -0.0이면 atan2가 음수 쪽으로 돌아가므로 0은 늘 +0으로 만든다
        val up = if (v == 0f) 0f else -v
        var d = Math.toDegrees(atan2(up, u).toDouble()).toFloat()
        if (d < 0f) d += 360f
        if (d >= 360f) d -= 360f
        return d
    }

    /** 각을 가까운 정수 도로 맞춘다 (0 이상 360 미만) */
    fun snapDeg(deg: Float): Float {
        val r = Math.round(deg).toFloat()
        return if (r >= 360f) r - 360f else r
    }

    /** 반지름 [r]인 각도기 둘레에서 각 [deg]인 점의 자 기준 좌표 [u, v] */
    fun arcPoint(r: Float, deg: Float): FloatArray {
        val a = Math.toRadians(deg.toDouble())
        return floatArrayOf((r * cos(a)).toFloat(), (-r * sin(a)).toFloat())
    }

    /** 각 [from]에서 [to]까지 (둘 다 포함) [stepDeg]도 간격의 각들. 부드러운 둘레를 점들로 그을 때 */
    fun arcAngles(from: Float, to: Float, stepDeg: Float = 2f): FloatArray {
        val n = maxOf(1, (abs(to - from) / stepDeg).toInt() + 1)
        return FloatArray(n + 1) { from + (to - from) * it / n }
    }

    // ================= 두 손가락으로 옮기고 돌리기 =================

    /**
     * 두 손가락이 (p0, q0)에서 (p1, q1)으로 움직였을 때 자의 새 가운데·기울기 변화·크기 배율: [dTheta, 새 cx, 새 cy, k].
     * 두 손가락의 가운데를 축으로 돌아간 만큼 자도 같이 돌고, 벌어진 만큼(k배) 같이 커지고, 가운데가 움직인 만큼 따라 움직인다
     */
    fun twoFinger(
        p0x: Float, p0y: Float, q0x: Float, q0y: Float,
        p1x: Float, p1y: Float, q1x: Float, q1y: Float,
        cx: Float, cy: Float,
    ): FloatArray {
        val a0 = atan2(q0y - p0y, q0x - p0x)
        val a1 = atan2(q1y - p1y, q1x - p1x)
        var d = a1 - a0
        while (d > PI) d -= (2 * PI).toFloat()
        while (d <= -PI) d += (2 * PI).toFloat()
        val m0x = (p0x + q0x) / 2
        val m0y = (p0y + q0y) / 2
        val m1x = (p1x + q1x) / 2
        val m1y = (p1y + q1y) / 2
        val d0 = hypot(q0x - p0x, q0y - p0y)
        val d1 = hypot(q1x - p1x, q1y - p1y)
        // 처음 두 손가락이 거의 겹쳐 있으면 배율을 믿을 수 없으니 키우지 않는다
        val k = if (d0 < 1f) 1f else d1 / d0
        val c = cos(d)
        val s = sin(d)
        val rx = cx - m0x
        val ry = cy - m0y
        return floatArrayOf(d, m1x + k * (rx * c - ry * s), m1y + k * (rx * s + ry * c), k)
    }

    // ================= 눈금·읽기 =================

    /** 눈금 [i](자 왼쪽 끝에서 i mm)의 종류: 10 = 센티미터(숫자), 5 = 반 센티미터, 1 = 밀리미터 */
    fun tickKind(i: Int): Int = when {
        i % 10 == 0 -> 10
        i % 5 == 0 -> 5
        else -> 1
    }

    /** 길이 [pt](쪽 포인트)를 읽기 좋게: 1cm 미만은 mm, 아니면 cm (소수 한 자리) */
    fun formatLength(pt: Float): String {
        val mm = pt * MM_PER_PT
        return if (mm < 10f) String.format(Locale.US, "%.1f mm", mm) else String.format(Locale.US, "%.1f cm", mm / 10f)
    }

    /** 각도 [deg]를 정수 도로 */
    fun formatAngle(deg: Float): String = "${deg.roundToInt()}°"
}
