package com.dsviewer.app

/**
 * 네모 덮기의 덮는 색: 네모 바로 바깥 띠에서 가장 많이 나온 색을 고른다.
 * 둘레에 글자나 그림이 조금 걸려 있어도 가장 넓게 깔린 바탕색이 이긴다.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다
 */
internal object CoverColor {
    private const val BITS = 3

    /**
     * [px](가로 [w], 세로 [h]의 ARGB 화소)에서 네모 ([l], [t])~([r], [b]) 둘레의 띠
     * (네모에서 [gap]만큼 띄우고 [thick] 두께, 그림 밖은 뺀다)의 대표 색을 불투명으로 돌려준다.
     * 색은 채널마다 5단계 덩어리로 묶어 가장 큰 덩어리의 평균. 띠에 화소가 없으면 null
     */
    fun dominant(px: IntArray, w: Int, h: Int, l: Int, t: Int, r: Int, b: Int, gap: Int, thick: Int): Int? {
        if (w <= 0 || h <= 0 || px.size < w * h) return null
        val il = l - gap
        val iTop = t - gap
        val ir = r + gap
        val ib = b + gap
        val ol = (il - thick).coerceAtLeast(0)
        val ot = (iTop - thick).coerceAtLeast(0)
        val oRight = (ir + thick).coerceAtMost(w)
        val oBottom = (ib + thick).coerceAtMost(h)
        val bins = 1 shl (3 * (8 - BITS))
        val count = IntArray(bins)
        val sumR = LongArray(bins)
        val sumG = LongArray(bins)
        val sumB = LongArray(bins)
        val shift = BITS
        fun add(x: Int, y: Int) {
            val c = px[y * w + x]
            val rr = (c shr 16) and 0xFF
            val gg = (c shr 8) and 0xFF
            val bb = c and 0xFF
            val key = ((rr shr shift) shl (2 * (8 - BITS))) or ((gg shr shift) shl (8 - BITS)) or (bb shr shift)
            count[key]++
            sumR[key] += rr.toLong()
            sumG[key] += gg.toLong()
            sumB[key] += bb.toLong()
        }
        for (y in ot until oBottom) {
            if (y in iTop until ib) {
                // 네모 높이 구간: 왼쪽·오른쪽 띠만 (안쪽 화소는 건너뛴다)
                for (x in ol until il.coerceIn(ol, oRight)) add(x, y)
                for (x in ir.coerceIn(ol, oRight) until oRight) add(x, y)
            } else {
                for (x in ol until oRight) add(x, y)
            }
        }
        var best = -1
        for (k in 0 until bins) if (count[k] > 0 && (best < 0 || count[k] > count[best])) best = k
        if (best < 0) return null
        val n = count[best]
        val rr = (sumR[best] / n).toInt()
        val gg = (sumG[best] / n).toInt()
        val bb = (sumB[best] / n).toInt()
        return (0xFF shl 24) or (rr shl 16) or (gg shl 8) or bb
    }
}
