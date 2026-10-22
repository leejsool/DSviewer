package com.dsviewer.app

/** 겹친 순서를 바꾸는 방법 */
internal enum class ZMove { FRONT, FORWARD, BACKWARD, BACK }

/**
 * 쪽의 획 목록에서 고른 것들의 겹치는 순서(목록 뒤쪽일수록 위)를 바꾸는 계산.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다
 */
internal object ZOrder {
    /**
     * [list]에서 [selected]인 것들을 [mode]대로 옮긴 새 목록. 고른 것끼리의 앞뒤는 그대로이고, 바뀐 것이 없으면 null.
     * FORWARD·BACKWARD(한 칸)는 고른 것과 [overlaps]인 가장 가까운 다른 것 하나를 넘어간다 (겹치지 않는 것은 건너뛴다)
     */
    fun <T> move(list: List<T>, mode: ZMove, selected: (T) -> Boolean, overlaps: (T) -> Boolean): List<T>? {
        val picked = list.filter(selected)
        if (picked.isEmpty()) return null
        val rest = list.filterNot(selected)
        val out = when (mode) {
            ZMove.FRONT -> rest + picked
            ZMove.BACK -> picked + rest
            ZMove.FORWARD -> {
                val top = list.indexOfLast(selected)
                val over = (top + 1 until list.size).firstOrNull { !selected(list[it]) && overlaps(list[it]) } ?: return null
                val at = rest.indexOf(list[over]) + 1
                rest.take(at) + picked + rest.drop(at)
            }
            ZMove.BACKWARD -> {
                val bottom = list.indexOfFirst(selected)
                val under = (bottom - 1 downTo 0).firstOrNull { !selected(list[it]) && overlaps(list[it]) } ?: return null
                val at = rest.indexOf(list[under])
                rest.take(at) + picked + rest.drop(at)
            }
        }
        return out.takeIf { it != list }
    }
}
