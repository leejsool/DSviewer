package com.dsviewer.app

/**
 * 지우개가 한 번 닿았을 때 '어느 획을 건드릴지'를 정하는 규칙. 실제로 자르는 일(획 자르기, 테이프·채우기 구멍 뚫기)은
 * 넘겨받은 [Eraser]가 하고, 여기서는 순서·건너뛰기·보호 규칙만 다룬다 (Android 그래픽을 쓰지 않아 JVM 단위 시험으로 확인한다).
 *
 * 규칙 요약 (위에 그려진 것부터 아래로):
 *  - 그림·글·메모는 지우개로 지우지 않는다 (선택해서 삭제 / 메모의 지우기 단추)
 *  - 테이프 지우개는 테이프만, 채우기 지우개는 채우기만. 보이게 한 테이프는 투명한 셈이라 지우개가 그대로 지나간다
 *  - 가린 테이프(보이지 않게 덮은 것) 아래 획은 보이지 않으므로 지우지 않는다
 *  - '형광펜만' 모드면 형광펜만 건드린다 (가린 테이프는 그래도 아래를 가린다)
 *  - 닿은 채우기는, 이번에 선도 함께 닿았으면 건드리지 않는다 (채운 도형 안의 선을 지우다 채우기까지 지우지 않게).
 *    선 없이 채우기만 닿았으면 지운다. 어느 쪽인지는 한 번 정해지면 그 지우개 획 동안 유지된다 ([FillDecision])
 */
internal object EraseRules {

    /** 지우개 한 번이 어떤 종류를 건드리는지 */
    class Filter(
        /** 테이프 도구의 지우개: 테이프만 */
        val tapesOnly: Boolean,
        /** 채우기 도구의 지우개: 채우기만 */
        val fillsOnly: Boolean,
        /** '형광펜만' 지우기 */
        val highlighterOnly: Boolean,
    )

    /** 지우개가 닿은 한 획의 성질 (자르는 일은 모른 채 규칙만 보게) */
    class Target(
        /** 실행하는 쪽이 이 획을 다시 찾는 데 쓰는 이름표 (보통 Stroke 자신) */
        val id: Any,
        val isBox: Boolean,
        val isNote: Boolean,
        val isTape: Boolean,
        val isFill: Boolean,
        val isHighlighter: Boolean,
        /** 보이게 한 테이프 */
        val revealed: Boolean,
        /**
         * 지우개 한가운데 점이 (가린) 테이프에 덮여 있는지. 계산이 들어 규칙이 필요할 때만 부른다
         * (쪽에 획이 많아도 지우개를 끄는 동안 매번 전부 재지 않도록)
         */
        val coversPoint: () -> Boolean,
        /** 지우개 원에 이 획이 닿는지. 위와 같이 필요할 때만 부른다 */
        val hit: () -> Boolean,
    )

    /** 일반 지우개가 채우기에 닿았을 때의 결정: 아직 모름 / 선도 닿았으니 채우기는 두기 / 채우기만 닿았으니 지우기 */
    enum class FillDecision { UNDECIDED, KEEP, ERASE }

    /**
     * 지우개가 [Eraser]에게 시키는 일. 획을 번호가 아닌 [Target.id]로 가리킨다: 앞서 자른 획이 목록 번호를 밀어도
     * (채우기는 나중에 지우므로) 실행하는 쪽이 지금 위치를 다시 찾는다
     */
    interface Eraser {
        /** 이 획을 지우거나 (영역 지우개면) 닿은 부분만 잘라 낸다. 바뀐 것이 없으면 false */
        fun erase(id: Any): Boolean
    }

    /** 이 획을 아예 건드리지 않고 넘어가는지 */
    fun skips(t: Target, f: Filter): Boolean {
        if (t.isBox || t.isNote) return true
        if (f.fillsOnly) return !t.isFill
        if (f.tapesOnly) return !t.isTape
        return t.isTape && t.revealed
    }

    /** 테이프 도구의 지우개이고 손바닥이 아니면 테이프만 지운다 */
    fun tapesOnly(tool: Tool, tapeErasing: Boolean, palm: Boolean): Boolean = tool == Tool.TAPE && tapeErasing && !palm

    /** 채우기 도구의 지우개이고 손바닥이 아니면 채우기만 지운다 */
    fun fillsOnly(tool: Tool, fillErasing: Boolean, palm: Boolean): Boolean = tool == Tool.FILL && fillErasing && !palm

    /**
     * 지우는 방식: 손바닥은 늘 닿은 부분만(AREA), 테이프만·채우기만 지울 때는 그 도구의 방식, 아니면 일반 지우개의 방식
     */
    fun modeFor(
        palm: Boolean, tapesOnly: Boolean, fillsOnly: Boolean,
        tapeMode: EraserMode, fillMode: EraserMode, eraserMode: EraserMode,
    ): EraserMode = if (palm) EraserMode.AREA else if (tapesOnly) tapeMode else if (fillsOnly) fillMode else eraserMode

    class Result(val removed: Boolean, val decision: FillDecision)

    /**
     * 위에서 아래로 훑으며 지운다. [targets]는 아래에 깔린 것부터의 순서이고 맨 위(뒤쪽)부터 본다.
     * [decision]은 이 지우개 획에서 앞서 정한 채우기 결정, 돌려주는 값이 갱신된 결정.
     */
    fun apply(targets: List<Target>, f: Filter, decision: FillDecision, eraser: Eraser): Result {
        val fillHits = ArrayList<Any>()
        var hitOther = false
        var covered = false
        var removed = false
        var dec = decision
        for (k in targets.indices.reversed()) {
            val t = targets[k]
            if (skips(t, f)) continue
            if (covered) break
            val coversHere = t.isTape && !t.revealed && t.coversPoint()
            if (!f.tapesOnly && !f.fillsOnly && f.highlighterOnly && !t.isHighlighter) {
                if (coversHere) covered = true
                continue
            }
            if (!t.hit()) continue
            if (t.isFill && !f.fillsOnly && dec != FillDecision.ERASE) {
                fillHits.add(t.id)
                continue
            }
            if (!t.isFill) hitOther = true
            if (!eraser.erase(t.id)) continue
            if (coversHere) covered = true
            removed = true
        }
        if (!f.fillsOnly && dec == FillDecision.UNDECIDED) {
            if (hitOther) dec = FillDecision.KEEP else if (fillHits.isNotEmpty()) dec = FillDecision.ERASE
        }
        if (dec == FillDecision.ERASE) for (id in fillHits) {
            if (eraser.erase(id)) removed = true
        }
        return Result(removed, dec)
    }
}
