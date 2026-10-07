package com.dsviewer.app

/** 대칭 방식: 반사한 사본을 더함 / 반사한 모양으로 옮김 / 축 한쪽을 반대쪽으로 접음 */
enum class SymMode { COPY, MOVE, FOLD }

/** 이동·접기에서 원래 모양을 어떻게 둘지: 없앰 / 점선으로 남김 / 실선(그대로)으로 남김 */
enum class OrigStyle { NONE, DASHED, SOLID }

/**
 * 대칭을 적용한 결과: 목록에서 뺄 획 [removed], 새로 넣을 획 [added] (남기는 점선 원본과 결과),
 * 적용한 뒤 골라 둘 획 [selectAfter] (결과 도형)
 */
class SymResult(val removed: List<Stroke>, val added: List<Stroke>, val selectAfter: List<Stroke>) {
    val isEmpty get() = removed.isEmpty() && added.isEmpty()

    companion object {
        val EMPTY = SymResult(emptyList(), emptyList(), emptyList())
    }
}

/**
 * 올가미로 고른 획들에 선대칭을 적용한 결과를 만든다 (복사·이동·접기, 원본과 결과의 실선·점선).
 * 화면을 건드리지 않는 계산이라 JVM 단위 시험으로 확인한다. 미리 보기와 확정이 같은 결과를 쓴다.
 * 좌표는 쪽 좌표, 축은 A=([ax],[ay]) → B=([bx],[by])
 */
internal object SymmetryOps {

    /** 점선으로 바꿀 수 있는 획인가: 사인펜 계열 펜 획 (글·그림·표·테이프·채우기·형광펜은 아니다) */
    fun dashable(s: Stroke): Boolean = s.tool == Tool.PEN && !s.isBox && s.tape == null && s.fill == null && s.note == null

    /** 같은 점들의 점선 획 (사인펜). 점선으로 바꿀 수 없는 획이면 null */
    fun dashedCopy(s: Stroke): Stroke? {
        if (!dashable(s)) return null
        return Stroke(Tool.PEN, s.color, s.width, true, PenStyle.FELT).also { n ->
            for (i in 0 until s.count) n.add(s.x(i), s.y(i), s.p(i))
        }
    }

    /** 접을 수 있는 획인가: 점으로 그린 선 (글·그림·표·메모·테이프·채우기는 접지 않는다) */
    private fun foldable(s: Stroke) = !s.isBox && s.note == null && s.tape == null && s.fill == null && s.image == null

    /**
     * 접을 때 남길 쪽의 부호 ([SymmetryMath.sideSign]과 같은 약속): 고른 획의 점이 더 많은 쪽.
     * (작은 부분이 큰 부분 위로 접히는 것이 자연스럽다. 반대로 하려면 부호를 뒤집는다)
     */
    fun keepSide(src: List<Stroke>, ax: Float, ay: Float, bx: Float, by: Float): Int {
        var pos = 0
        var neg = 0
        for (s in src) {
            if (!foldable(s)) continue
            for (i in 0 until s.count) {
                val k = SymmetryMath.sideSign(s.x(i), s.y(i), ax, ay, bx, by)
                if (k > 0) pos++ else if (k < 0) neg++
            }
        }
        return if (pos >= neg) 1 else -1
    }

    /**
     * 결과를 만든다. [keep]은 접을 때 남길 쪽 (+1·-1).
     * 복사는 사본만 더하고, 이동·접기는 원본을 [orig]대로 처리한다. 결과 획은 [dashed]면 점선
     */
    fun build(
        src: List<Stroke>, mode: SymMode, orig: OrigStyle, dashed: Boolean, keep: Int,
        ax: Float, ay: Float, bx: Float, by: Float,
    ): SymResult {
        val removed = ArrayList<Stroke>()
        val added = ArrayList<Stroke>()
        val after = ArrayList<Stroke>()

        /** 결과 획: 점선이면 점선 사인펜으로 (바꿀 수 없는 획은 그대로) */
        fun styled(s: Stroke): Stroke = if (dashed) dashedCopy(s) ?: s else s

        /** [s]를 [made]로 바꾼다: 원본은 [orig]대로 (복사는 그대로 둠) */
        fun place(s: Stroke, made: Stroke) {
            if (mode != SymMode.COPY && orig != OrigStyle.SOLID) {
                val ghost = if (orig == OrigStyle.DASHED) dashedCopy(s) else null
                removed += s
                if (ghost != null) added += ghost
            }
            val result = styled(made)
            added += result
            after += result
        }

        when (mode) {
            SymMode.COPY, SymMode.MOVE -> for (s in src) {
                if (s.note != null) continue
                place(s, s.copy().apply { role = null; reflect(ax, ay, bx, by) })
            }
            SymMode.FOLD -> for (s in src) {
                if (!foldable(s)) continue
                val pts = SymmetryMath.fold(s.data, s.count, ax, ay, bx, by, keep) ?: continue
                place(s, s.copy().apply { role = null; setPoints(pts) })
            }
        }
        return SymResult(removed, added, after)
    }
}
