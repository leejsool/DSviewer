package com.dsviewer.app

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 필기 검색의 순수 계산: 획을 글줄로 묶기, 읽은 글에서 찾은 자리 계산, 쪽 내용 서명, 읽은 결과의 저장 형식.
 * 획은 상자 [왼, 위, 오른, 아래] 배열로만 다루므로 Android 없이 JVM 단위 시험으로 확인한다.
 */
internal object LineGrouper {
    /** 이보다 낮은 획(점·쉼표)도 이만큼은 높다고 본다 (pt) */
    private const val MIN_H = 8f
    /** 글줄 높이의 이 배수보다 멀리 떨어진 획은 같은 줄로 보지 않는다 (단·열이 다른 글) */
    private const val GAP_FACTOR = 2.5f
    /** 이보다 획이 많은 줄은 글이 아니라 그림으로 보고 읽지 않는다 */
    const val MAX_STROKES = 150
    /** 이보다 높은 줄은 글이 아니라 그림으로 보고 읽지 않는다 (pt) */
    const val MAX_HEIGHT = 200f

    /**
     * 쓴 차례대로 놓인 획 상자들 [boxes]를 글줄로 묶는다. 돌려주는 것은 줄마다 획 번호들 (쓴 차례, 줄은 위에서 아래로).
     * 높이가 겹치고(점·쉼표처럼 작은 획은 줄 안에 들어오면) 가로로 멀지 않은 획은 같은 줄이다
     */
    fun group(boxes: List<FloatArray>): List<List<Int>> {
        class Line(var l: Float, var t: Float, var r: Float, var b: Float, val idx: MutableList<Int>)
        val lines = ArrayList<Line>()
        for ((i, bx) in boxes.withIndex()) {
            val h = max(bx[3] - bx[1], 0f)
            var target: Line? = null
            // 가장 최근에 쓴 줄부터 본다 (쓰던 줄에 이어 쓰는 경우가 대부분)
            for (k in lines.indices.reversed()) {
                val ln = lines[k]
                val lh = ln.b - ln.t
                val overlap = min(ln.b, bx[3]) - max(ln.t, bx[1])
                if (overlap < 0.5f * min(lh, h)) continue
                // 높이가 0인 획(가로선)은 줄 안에 들어와야 한다
                if (overlap < 0f) continue
                val gap = max(0f, max(bx[0] - ln.r, ln.l - bx[2]))
                if (gap <= GAP_FACTOR * max(max(lh, h), MIN_H * 1.5f)) {
                    target = ln
                    break
                }
            }
            if (target == null) {
                lines.add(Line(bx[0], bx[1], bx[2], bx[3], mutableListOf(i)))
            } else {
                target.l = min(target.l, bx[0]); target.t = min(target.t, bx[1])
                target.r = max(target.r, bx[2]); target.b = max(target.b, bx[3])
                target.idx.add(i)
            }
        }
        return lines.sortedBy { it.t }.map { it.idx }
    }

    /** 읽어 볼 만한 줄인가: 획이 너무 많거나 너무 높으면 그림이다 */
    fun worthReading(box: FloatArray, strokeCount: Int): Boolean =
        strokeCount in 1..MAX_STROKES && box[3] - box[1] <= MAX_HEIGHT

    /** 상자들을 모두 감싸는 상자 */
    fun union(boxes: List<FloatArray>, idx: List<Int>): FloatArray {
        val u = boxes[idx[0]].copyOf()
        for (i in idx) {
            val b = boxes[i]
            u[0] = min(u[0], b[0]); u[1] = min(u[1], b[1]); u[2] = max(u[2], b[2]); u[3] = max(u[3], b[3])
        }
        return u
    }
}

internal object InkLineMath {
    /**
     * 줄 상자 [box] 안에서 읽은 글([total]글자)의 [start] 이상 [end] 미만 글자가 놓인 자리를 글자 수에 비례해 짐작한다.
     * 글자 폭이 달라 어긋날 수 있어 양옆을 글자 하나의 3할씩 넓히고 줄 상자 밖으로는 나가지 않는다 (위아래는 조금 넓힌다).
     * 돌려주는 것은 [왼, 위, 오른, 아래]
     */
    fun hitBox(box: FloatArray, total: Int, start: Int, end: Int): FloatArray {
        val n = max(total, 1)
        val w = box[2] - box[0]
        val cw = w / n
        val x0 = (box[0] + cw * start - cw * 0.3f).coerceAtLeast(box[0])
        val x1 = (box[0] + cw * end + cw * 0.3f).coerceAtMost(box[2])
        val padY = (box[3] - box[1]) * 0.08f
        return floatArrayOf(x0, box[1] - padY, max(x1, x0), box[3] + padY)
    }
}

internal object InkHash {
    private const val PRIME = 1099511628211L

    /**
     * 쪽의 필기 내용 서명 (FNV-1a). 획마다 점 수와 좌표(0.1 단위)를 쓴다: 같은 필기면 파일을 다시 열어도 같고,
     * 획을 더하거나 지우거나 옮기면 달라진다. [xy]는 획마다 x, y를 번갈아 담은 배열
     */
    fun of(xy: List<FloatArray>): Long {
        var h = -3750763034362895579L
        fun mix(v: Int) {
            for (s in intArrayOf(0, 8, 16, 24)) h = (h xor ((v ushr s) and 0xFF).toLong()) * PRIME
        }
        for (s in xy) {
            mix(s.size)
            for (v in s) mix((v * 10f).roundToInt())
        }
        return h
    }
}

/** 필기에서 읽은 글줄 하나: 줄 상자 [왼, 위, 오른, 아래]와, 읽은 글 후보들(가능성이 높은 것부터) */
internal class InkLineData(val box: FloatArray, val texts: List<String>)

/** 읽은 결과를 파일에 적고 읽는 형식 (쪽 서명마다 줄들). 쪽을 옮겨도 서명이 같으면 다시 읽지 않는다 */
internal object HandwritingCodec {
    fun encode(pages: Map<Long, List<InkLineData>>): String = buildString {
        for ((hash, lines) in pages) {
            append("P\t").append(hash).append('\n')
            for (ln in lines) {
                append("L\t").append(ln.box.joinToString(" "))
                for (t in ln.texts) append('\t').append(t.replace('\t', ' ').replace('\n', ' ').replace('\r', ' '))
                append('\n')
            }
        }
    }

    fun decode(s: String): Map<Long, List<InkLineData>> {
        val out = LinkedHashMap<Long, MutableList<InkLineData>>()
        var cur: MutableList<InkLineData>? = null
        for (line in s.lineSequence()) {
            val parts = line.split('\t')
            when (parts[0]) {
                "P" -> cur = parts.getOrNull(1)?.toLongOrNull()?.let { out.getOrPut(it) { mutableListOf() } }
                "L" -> {
                    val list = cur ?: continue
                    val box = parts.getOrNull(1)?.split(' ')?.mapNotNull { it.toFloatOrNull() }
                    if (box == null || box.size != 4) continue
                    val texts = parts.drop(2).filter { it.isNotEmpty() }
                    if (texts.isNotEmpty()) list.add(InkLineData(box.toFloatArray(), texts))
                }
            }
        }
        return out
    }
}
