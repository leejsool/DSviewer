package com.dsviewer.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.roundToInt

/** 제목 줄과 나머지 사이의 선은 이만큼 굵게 */
const val TABLE_HEAVY = 2.2f

/** 제목 칸을 칠하는 선 색의 진하기 (0~1) */
const val TABLE_SHADE_ALPHA = 0.16f

/** 표 상자의 네 모서리 (획의 처음 네 점) */
fun tableCorners(st: Stroke) = FloatArray(8) { if (it % 2 == 0) st.x(it / 2) else st.y(it / 2) }

/**
 * 표: 제목 칸을 옅게 칠하고, 바깥 테두리와 보이는 안쪽 선을 그린다. 선 굵기는 쪽 위에서 늘 [Stroke.width]
 * (표를 돌리거나 늘여도 선 굵기는 같고 모서리 네 점에 맞춰 칸이 따라간다). 쪽 미리보기가 다른 스레드에서 같이 그릴 수 있다
 */
fun drawInkTable(c: Canvas, st: Stroke, alphaMul: Float) {
    val t = st.table ?: return
    if (st.count < 4) return
    val f = TableFrame(tableCorners(st), t.width, t.height)
    val geo = t.geometry()
    val rgb = st.color and 0x00FFFFFF
    val alpha = Color.alpha(st.color) * alphaMul
    val p = Paint(Paint.ANTI_ALIAS_FLAG)
    // 제목 칸
    if (geo.shades.isNotEmpty()) {
        p.style = Paint.Style.FILL
        p.color = rgb or ((alpha * TABLE_SHADE_ALPHA).roundToInt() shl 24)
        val path = Path()
        for (s in geo.shades) {
            path.reset()
            path.moveTo(f.pageX(s.l, s.t), f.pageY(s.l, s.t))
            path.lineTo(f.pageX(s.r, s.t), f.pageY(s.r, s.t))
            path.lineTo(f.pageX(s.r, s.b), f.pageY(s.r, s.b))
            path.lineTo(f.pageX(s.l, s.b), f.pageY(s.l, s.b))
            path.close()
            c.drawPath(path, p)
        }
    }
    // 선
    p.style = Paint.Style.STROKE
    p.color = rgb or (alpha.roundToInt() shl 24)
    p.strokeCap = Paint.Cap.SQUARE
    p.strokeJoin = Paint.Join.MITER
    val w = st.width.coerceAtLeast(0.3f)
    for (heavy in booleanArrayOf(false, true)) {
        p.strokeWidth = if (heavy) w * TABLE_HEAVY else w
        val path = Path()
        for (l in geo.lines) {
            if (l.heavy != heavy) continue
            path.moveTo(f.pageX(l.x0, l.y0), f.pageY(l.x0, l.y0))
            path.lineTo(f.pageX(l.x1, l.y1), f.pageY(l.x1, l.y1))
        }
        c.drawPath(path, p)
    }
    // 바깥 테두리
    p.strokeWidth = w
    val outer = Path()
    outer.moveTo(f.pageX(0f, 0f), f.pageY(0f, 0f))
    outer.lineTo(f.pageX(t.width, 0f), f.pageY(t.width, 0f))
    outer.lineTo(f.pageX(t.width, t.height), f.pageY(t.width, t.height))
    outer.lineTo(f.pageX(0f, t.height), f.pageY(0f, t.height))
    outer.close()
    c.drawPath(outer, p)
}
