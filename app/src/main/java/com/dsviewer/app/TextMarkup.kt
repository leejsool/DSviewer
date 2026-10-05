package com.dsviewer.app

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** 글자 기반 주석의 종류 (형광펜 펼침 창에서 고른다). [NONE]이면 보통 형광펜 */
enum class TextMark(val label: String, val hint: String) {
    NONE("자유 형광펜", ""),
    HIGHLIGHT("글자 형광펜", "글자를 끌면 글줄에 맞게 칠합니다"),
    UNDERLINE("글자 밑줄", "글자를 끌면 반듯한 밑줄이 붙습니다"),
    STRIKE("글자 취소선", "글자를 끌면 글자 가운데로 반듯한 선이 지나갑니다"),
    COPY("글자 복사", "글자를 끌면 복사합니다");

    companion object {
        fun named(name: String?) = entries.firstOrNull { it.name == name } ?: NONE
    }
}

/**
 * PDF 글자(쪽의 [PageText]) 위에서 끌어 고른 글자를 주석 획이나 복사할 글로 바꾸는 순수 계산.
 * 상자는 모두 (왼, 위, 오른, 아래) 쪽 좌표 네 수. Android 클래스를 몰라 JVM 단위 시험으로 확인한다
 */
internal object TextMarkup {

    /** 같은 줄로 칠 세로 겹침 비율 (글자 상자 높이 기준). 찾기와 같은 값 */
    private const val SAME_LINE = 0.3f

    /** 띄어쓰기로 볼 글자 사이 틈 (상자 높이 기준) */
    private const val SPACE_GAP = 0.18f

    private fun sameLine(t1: Float, b1: Float, t2: Float, b2: Float): Boolean =
        min(b1, b2) - max(t1, t2) > (b2 - t2) * SAME_LINE

    /**
     * ([x], [y])에 가장 가까운 글자 번호. 점이 글자 상자 안이면 그 글자, 아니면 상자까지 거리가 가장 짧은 글자.
     * 거리가 [maxDist]를 넘으면 -1 (글자가 없는 자리). 글자가 하나도 없어도 -1
     */
    fun nearest(pt: PageText, x: Float, y: Float, maxDist: Float = Float.MAX_VALUE): Int {
        var best = -1
        var bestD = Float.MAX_VALUE
        for (k in 0 until pt.chars.length) {
            val dx = max(max(pt.boxes[k * 4] - x, x - pt.boxes[k * 4 + 2]), 0f)
            val dy = max(max(pt.boxes[k * 4 + 1] - y, y - pt.boxes[k * 4 + 3]), 0f)
            val d = hypot(dx, dy)
            if (d < bestD) {
                bestD = d
                best = k
            }
        }
        return if (best >= 0 && bestD <= maxDist) best else -1
    }

    /** 글자 [from]부터 [to]까지(둘 다 포함, 순서 무관)를 줄마다 하나씩 묶은 상자들 */
    fun lineRects(pt: PageText, from: Int, to: Int): List<FloatArray> {
        val a = min(from, to).coerceAtLeast(0)
        val b = max(from, to).coerceAtMost(pt.chars.length - 1)
        val out = ArrayList<FloatArray>()
        for (k in a..b) {
            val l = pt.boxes[k * 4]; val t = pt.boxes[k * 4 + 1]
            val r = pt.boxes[k * 4 + 2]; val bt = pt.boxes[k * 4 + 3]
            val c = out.lastOrNull()
            if (c != null && sameLine(c[1], c[3], t, bt)) {
                c[0] = min(c[0], l); c[1] = min(c[1], t); c[2] = max(c[2], r); c[3] = max(c[3], bt)
            } else out.add(floatArrayOf(l, t, r, bt))
        }
        return out
    }

    /** 글자 [from]~[to]를 복사할 글로: 글줄이 바뀌면 줄바꿈, 같은 줄에서 틈이 벌어지면 띄어쓰기 */
    fun copyText(pt: PageText, from: Int, to: Int): String {
        val a = min(from, to).coerceAtLeast(0)
        val b = max(from, to).coerceAtMost(pt.chars.length - 1)
        if (a > b) return ""
        val sb = StringBuilder()
        for (k in a..b) {
            if (k > a) {
                val pt0 = (k - 1) * 4
                val t = pt.boxes[k * 4 + 1]; val bt = pt.boxes[k * 4 + 3]
                if (!sameLine(pt.boxes[pt0 + 1], pt.boxes[pt0 + 3], t, bt)) sb.append('\n')
                else if (pt.boxes[k * 4] - pt.boxes[pt0 + 2] > (bt - t) * SPACE_GAP) sb.append(' ')
            }
            sb.append(pt.raw[k])
        }
        return sb.toString()
    }

    /** 주석 획 하나: 두 끝점 (x0,y0)-(x1,y1)과 굵기 */
    class Seg(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val width: Float)

    /**
     * 글줄 상자 [r]에 맞는 주석 획. 형광펜은 상자 높이만큼 굵은 선 (둥근 끝이 상자 밖으로 나가지 않게 양끝을 반 굵기씩 안으로),
     * 밑줄은 글자 바로 아래, 취소선은 글자 가운데를 지나는 가는 선. 가는 선의 굵기는 [lineWidth]를 상자 높이의 12% 안으로 맞춘 값
     */
    fun segment(mark: TextMark, r: FloatArray, lineWidth: Float): Seg? {
        val h = r[3] - r[1]
        if (h <= 0f || r[2] <= r[0]) return null
        return when (mark) {
            TextMark.HIGHLIGHT -> {
                val y = (r[1] + r[3]) / 2f
                val x0 = r[0] + h / 2f
                val x1 = r[2] - h / 2f
                if (x1 >= x0) Seg(x0, y, x1, y, h) else Seg((r[0] + r[2]) / 2f, y, (r[0] + r[2]) / 2f, y, h)
            }
            TextMark.UNDERLINE -> {
                val y = r[3] - h * 0.15f
                Seg(r[0], y, r[2], y, lineWidth.coerceIn(0.4f, max(0.4f, h * 0.12f)))
            }
            TextMark.STRIKE -> {
                val y = r[1] + h * 0.55f
                Seg(r[0], y, r[2], y, lineWidth.coerceIn(0.4f, max(0.4f, h * 0.12f)))
            }
            else -> null
        }
    }
}
