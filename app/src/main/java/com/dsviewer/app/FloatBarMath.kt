package com.dsviewer.app

/**
 * 플로팅 툴바의 계산 (화면과 상관없는 부분만 모아 단위 테스트한다).
 *
 * 기본 칸: 실행 취소 · 펜 · 형광펜 · 직전 도구. 사용자가 옵션에서 고른 단추(extra)가 그 뒤에 붙고,
 * 단추 수에 따라 칸 수가 저절로 정해진다 (사용자가 크기를 정하지 않는다).
 */
object FloatBarMath {
    const val UNDO = "undo"
    const val PEN = "PEN"
    const val HIGHLIGHTER = "HIGHLIGHTER"

    /** 늘 있는 앞의 세 칸 */
    val FIXED = listOf(UNDO, PEN, HIGHLIGHTER)

    /** 직전 도구 칸에 올 수 있는 도구 (실행 취소 · 펜 · 형광펜이 아닌 그리기 도구) */
    val RECENT_CANDIDATES = listOf("LASSO", "ERASER", "SHAPE", "TEXT", "LASER", "TAPE", "FILL")

    /** 투명도(%) 범위와 처음 값. 100이면 바탕 색이 전혀 없는 맑은 유리 */
    const val MIN_TRANSPARENCY = 40
    const val MAX_TRANSPARENCY = 100
    const val DEFAULT_TRANSPARENCY = 88

    /** 단추 [n]개를 늘어놓을 칸 수: 6개까지 2칸(2×2, 2×3), 12개까지 3칸, 그보다 많으면 4칸 */
    fun columns(n: Int): Int = when {
        n <= 6 -> 2
        n <= 12 -> 3
        else -> 4
    }

    fun rows(n: Int): Int = if (n <= 0) 0 else (n + columns(n) - 1) / columns(n)

    /** 고른 도구를 최근 기록 맨 앞에 넣는다 (같은 것은 빼고, [max]개까지). 펜·형광펜은 기록하지 않는다 */
    fun pushHistory(history: List<String>, key: String, max: Int = 8): List<String> =
        if (key !in RECENT_CANDIDATES) history
        else (listOf(key) + history.filter { it != key }).take(max)

    /**
     * 직전 도구 칸에 올 도구: 최근 기록 가운데 툴바에 이미 올라 있지 않은 가장 최근 것.
     * 기록이 없으면 후보 차례에서 처음으로 비어 있는 것 (처음엔 선택 도구)
     */
    fun recentSlot(history: List<String>, extra: Collection<String>): String? {
        val taken = FIXED.toSet() + extra
        return history.firstOrNull { it in RECENT_CANDIDATES && it !in taken }
            ?: RECENT_CANDIDATES.firstOrNull { it !in taken }
    }

    /** 툴바에 보일 단추 차례: 실행 취소 · 펜 · 형광펜 · 직전 도구 · 사용자가 더한 단추 */
    fun slots(history: List<String>, extra: List<String>): List<String> {
        val extras = extra.filter { it !in FIXED }.distinct()
        val recent = recentSlot(history, extras)
        return FIXED + listOfNotNull(recent) + extras
    }

    /** 저장한 쉼표 목록을 읽는다. [valid]에 없는 이름(옛 버전·지운 기능)은 버린다 */
    fun parseKeys(saved: String?, valid: Collection<String>): List<String> =
        saved?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() && it in valid }?.distinct().orEmpty()

    /** 바탕 색의 진하기(0..1). 투명도가 높을수록 옅다 */
    fun tintAlpha(transparency: Int): Float =
        (100 - transparency.coerceIn(MIN_TRANSPARENCY, MAX_TRANSPARENCY)) / 100f

    /**
     * 툴바 자리를 화면 크기에 대한 비율(0..1)로 바꾼다. 화면을 돌리거나 나눠 보기로 크기가 바뀌어도
     * 같은 쪽 같은 비율에 남는다. [pos]는 왼쪽(위쪽) 끝, [size]는 툴바 크기, [avail]은 화면 크기
     */
    fun toFraction(pos: Float, size: Float, avail: Float): Float {
        val room = avail - size
        return if (room <= 0f) 0f else (pos / room).coerceIn(0f, 1f)
    }

    fun fromFraction(fraction: Float, size: Float, avail: Float): Float =
        ((avail - size).coerceAtLeast(0f) * fraction.coerceIn(0f, 1f))

    /** 끌어 옮긴 자리를 화면 안으로 (넘치면 가장자리에 붙인다) */
    fun clamp(pos: Float, size: Float, avail: Float): Float =
        pos.coerceIn(0f, (avail - size).coerceAtLeast(0f))

    /**
     * 작게 줄인 그림을 흐리게 한다 (상자 흐림을 가로·세로로 [passes]번: 가우스 흐림에 가깝다).
     * [px]는 ARGB 값 [w]×[h]개. 가장자리 밖은 가장자리 색이 이어진다고 본다
     */
    fun boxBlur(px: IntArray, w: Int, h: Int, radius: Int, passes: Int = 2) {
        if (radius <= 0 || w <= 0 || h <= 0) return
        val tmp = IntArray(px.size)
        repeat(passes) {
            blurLine(px, tmp, w, h, radius, horizontal = true)
            blurLine(tmp, px, w, h, radius, horizontal = false)
        }
    }

    private fun blurLine(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
        val len = if (horizontal) w else h
        val lines = if (horizontal) h else w
        val span = 2 * r + 1
        for (line in 0 until lines) {
            fun at(i: Int): Int {
                val k = i.coerceIn(0, len - 1)
                return if (horizontal) src[line * w + k] else src[k * w + line]
            }
            var a = 0; var rr = 0; var g = 0; var b = 0
            for (i in -r..r) {
                val c = at(i)
                a += c ushr 24; rr += (c shr 16) and 0xFF; g += (c shr 8) and 0xFF; b += c and 0xFF
            }
            for (i in 0 until len) {
                val out = ((a / span) shl 24) or ((rr / span) shl 16) or ((g / span) shl 8) or (b / span)
                if (horizontal) dst[line * w + i] = out else dst[i * w + line] = out
                val add = at(i + r + 1)
                val sub = at(i - r)
                a += (add ushr 24) - (sub ushr 24)
                rr += ((add shr 16) and 0xFF) - ((sub shr 16) and 0xFF)
                g += ((add shr 8) and 0xFF) - ((sub shr 8) and 0xFF)
                b += (add and 0xFF) - (sub and 0xFF)
            }
        }
    }
}
