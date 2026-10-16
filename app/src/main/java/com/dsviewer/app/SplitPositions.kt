package com.dsviewer.app

import android.content.Context

/**
 * 같은 문서를 둘로 나눠 볼 때 둘째 칸이 보던 스크롤·확대 자리를 문서별로 기억한다.
 * 문서를 아예 닫았다 다시 열어도 다시 나눠 보면 둘째 칸이 그 자리에서 시작한다.
 * 문서 주소(uri)별로 한 줄 "확대,가로,세로,시각"이고, 오래된 것부터 [MAX]개까지만 남긴다.
 */
internal object SplitPositions {
    private const val PREFS = "splitPos"
    private const val MAX = 40

    fun get(ctx: Context, uri: String): DocumentView.ViewState? {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(uri, null) ?: return null
        return parse(raw)
    }

    fun put(ctx: Context, uri: String, s: DocumentView.ViewState) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val e = p.edit().putString(uri, format(s, System.currentTimeMillis()))
        val all = p.all
        if (all.size >= MAX) {
            // 가장 오래 전에 적은 것부터 버려 MAX개로
            all.entries.filter { it.key != uri }
                .sortedBy { (it.value as? String)?.let(::timeOf) ?: 0L }
                .take(all.size - MAX + 1)
                .forEach { e.remove(it.key) }
        }
        e.apply()
    }

    fun format(s: DocumentView.ViewState, time: Long) = "${s.zoom},${s.offX},${s.offY},$time"

    fun parse(raw: String): DocumentView.ViewState? {
        val f = raw.split(',')
        if (f.size < 3) return null
        val z = f[0].toFloatOrNull() ?: return null
        val x = f[1].toFloatOrNull() ?: return null
        val y = f[2].toFloatOrNull() ?: return null
        if (!z.isFinite() || !x.isFinite() || !y.isFinite()) return null
        return DocumentView.ViewState(z, x, y)
    }

    private fun timeOf(raw: String) = raw.split(',').getOrNull(3)?.toLongOrNull() ?: 0L
}
