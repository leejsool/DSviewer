package com.dsviewer.app

/**
 * 보정 펜으로 그린 획을 고른 도형으로 맞춘 결과([Fitted])를 실제 펜 획들로 만든다.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다 (도형을 맞추는 일은 [ShapeFit]).
 */
internal object ShapeStrokes {

    /** 획 점들의 필압 평균 (굵기를 정할 때). 점이 없으면 0 */
    fun averagePressure(raw: Stroke): Float {
        if (raw.count == 0) return 0f
        var sum = 0f
        for (i in 0 until raw.count) sum += raw.p(i)
        return sum / raw.count
    }

    /** 이 종류의 도형에서 보조선(점근선·축)을 어떤 모양으로 그릴지에 따른 보조 획 점선 여부 */
    private fun guideDashed(guide: GuideStyle) = guide == GuideStyle.DASHED

    /**
     * 맞춘 도형 [fitted]를 [raw]의 색·굵기와 평균 필압의 펜 획들로.
     * 맞춘 도형은 지금 펜 종류([penStyle])로 그리고, 점선 획은 사인펜으로 한다.
     * 화살표·길이 표시는 몸통을 고른 대로 실선·점선([dashed]), 화살촉은 늘 실선, 보조선은 [guide] 설정대로 (없음이면 그리지 않는다)
     */
    fun build(raw: Stroke, fitted: Fitted, kind: ShapeKind, guide: GuideStyle, dashed: Boolean, penStyle: PenStyle): List<Stroke> {
        val pAvg = averagePressure(raw)
        fun toStroke(pts: FloatArray, dash: Boolean) =
            Stroke(Tool.PEN, raw.color, raw.width, dash, if (dash) PenStyle.FELT else penStyle).apply {
                for (i in 0 until pts.size / 2) add(pts[i * 2], pts[i * 2 + 1], pAvg)
            }
        val out = fitted.curves.map { toStroke(it, dashed && kind.isGuideLine) }.toMutableList()
        fitted.heads.mapTo(out) { toStroke(it, false) }
        if (guide != GuideStyle.NONE) fitted.guides.mapTo(out) { toStroke(it, guideDashed(guide)) }
        return out
    }
}
