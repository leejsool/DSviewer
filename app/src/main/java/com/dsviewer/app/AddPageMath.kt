package com.dsviewer.app

import kotlin.math.max
import kotlin.math.min

/**
 * 마지막 쪽 아래 '빈 쪽 추가'(끌어 올리기·단추)의 계산. 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 * 좌표 배열은 [왼쪽, 위, 오른쪽, 아래] 같은 순서로 돌려준다.
 */
internal object AddPageMath {

    /** 끌어 올린 거리의 한도: 붙는 거리의 1.6배까지만 늘어난다 */
    const val MAX_PULL_FACTOR = 1.6f

    /** 끌어 올린 거리가 한도를 넘지 않게 */
    fun clampPull(v: Float, goal: Float): Float = v.coerceIn(0f, goal * MAX_PULL_FACTOR)

    /** 놓으면 붙는 거리를 이번에 처음 넘었는가 (손에 톡 알려 주는 때) */
    fun crossedGoal(old: Float, new: Float, goal: Float): Boolean = old < goal && new >= goal

    /** 놓으면 빈 쪽이 붙을 만큼 끌어 올렸는가 */
    fun isReady(pull: Float, goal: Float): Boolean = pull >= goal

    /** 0(안 끌어 올림) ~ 1(붙는 거리) */
    fun progress(pull: Float, goal: Float): Float = (pull / goal).coerceIn(0f, 1f)

    /**
     * 아래로 내리는 움직임([dy] < 0)이면 끌어 올려 둔 새 쪽 자리부터 접는다.
     * 돌려주는 값은 [새 끌어 올린 거리, 남은 dy]
     */
    fun foldBack(pull: Float, dy: Float): FloatArray {
        if (pull > 0f && dy < 0f) {
            val back = min(pull, -dy)
            return floatArrayOf(pull - back, dy + back)
        }
        return floatArrayOf(pull, dy)
    }

    /** 마지막 쪽 끝에서 더 올리려 한 만큼([want] - [clamped])의 70%를 새로 끌어 올린 거리에 보탠다 (뻑뻑하게) */
    fun stretch(pull: Float, want: Float, clamped: Float): Float = pull + (want - clamped) * 0.7f

    /**
     * 글·⊕를 둘 가로 가운데: 화면에 보이는 부분 [visL]~[visR]의 가운데. 화면이 [half]*2보다 넓으면 가장자리에 잘리지 않게 안으로 들인다
     */
    fun centerX(visL: Float, visR: Float, viewW: Float, half: Float): Float {
        val c = (visL + visR) / 2f
        return if (viewW > half * 2f) c.coerceIn(half, viewW - half) else viewW / 2f
    }

    /**
     * '빈 쪽 추가' 단추 자리 (화면 좌표, 끌어 올린 거리는 뺀다): [left, top, right, bottom].
     * 문서 아래([docH]*[scale] - [offY])에 [footer] 높이로 비워 둔 자리의 가운데. 확대해서 쪽이 화면보다 넓어도 보이는 문서 부분의 가운데에 둔다
     */
    fun buttonRect(
        docW: Float, docH: Float, scale: Float, offX: Float, offY: Float,
        viewW: Float, footer: Float, labelW: Float, density: Float,
    ): FloatArray {
        val cy = docH * scale - offY + footer / 2f
        val h = 40f * density
        val w = labelW + h + 28f * density
        val cx = centerX(max(-offX, 0f), min(docW * scale - offX, viewW), viewW, w / 2f)
        return floatArrayOf(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }

    /** ([x], [y])가 [rect]를 사방으로 [slop]만큼 넓힌 곳 안인가 */
    fun hitsButton(rect: FloatArray, x: Float, y: Float, slop: Float): Boolean =
        !(x < rect[0] - slop || x > rect[2] + slop || y < rect[1] - slop || y > rect[3] + slop)

    /**
     * 끌어 올려 드러난 새 쪽 자리의 점선 테두리: [left, top, width, height] (화면 좌표).
     * 마지막 쪽 아래([lastBottom]은 문서 좌표) 위로 [pull]만큼 밀려 올라간 곳에서 쪽 사이 간격과 단추 자리를 건너뛴 곳부터 시작한다.
     * 가로는 오른쪽 여백을 포함한 쪽 묶음의 가운데에 맞춘다
     */
    fun pullPageBox(
        docW: Float, scale: Float, offX: Float, offY: Float, pull: Float,
        lastBottom: Float, pageW: Float, pageH: Float, gap: Float, rightMargin: Float, footer: Float,
    ): FloatArray {
        val top = lastBottom * scale - offY - pull + gap * scale + footer
        val pw = pageW * scale
        val left = docW * scale / 2f - (pw + rightMargin * scale) / 2f - offX
        return floatArrayOf(left, top, pw, pageH * scale)
    }
}
