package com.dsviewer.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import kotlin.math.abs
import kotlin.math.hypot

/**
 * 자 · 눈금자 · 각도기: 쪽 위에 얹는 도구 (필기에 넣지 않고 저장하지도 않는다).
 * 손가락으로 몸통을 끌면 옮기고, 두 손가락으로 돌린다. 펜(또는 손가락 필기)으로 자의 가장자리에서 시작해 그으면
 * 가장자리를 따라 곧게 (각도기 둘레는 둥글게) 그어진다. 눈금자·각도기는 긋는 동안 길이·각도를 보여 준다.
 * 계산은 [RulerMath]. 문서 화면([view])의 스크롤·쪽 배치는 [Host]로 읽는다.
 */
internal class RulerController(private val view: View, private val host: Host) {

    interface Host {
        val scale: Float
        val pageCount: Int
        fun pageWidth(page: Int): Float
        fun pageHeight(page: Int): Float
        fun currentPage(): Int
        /** 지금 화면 가운데에 [w]×[h] 상자를 놓을 때 쪽 [page] 안의 왼쪽 위 [x, y] */
        fun centerOrigin(page: Int, w: Float, h: Float): FloatArray
        fun pageX(page: Int, sx: Float): Float
        fun pageY(page: Int, sy: Float): Float
        fun stopFling()
        /** 자를 켜거나 껐다 (툴바 표시를 맞춘다) */
        fun onRulerChanged()
    }

    enum class Mode(val label: String) {
        MEASURE("눈금자"), PROTRACTOR("각도기");

        companion object {
            /** 저장된 이름에서 (예전의 '직선자'는 눈금자에 합쳐졌다) */
            fun named(n: String?) = entries.firstOrNull { it.name == n } ?: MEASURE
        }
    }

    private val density = view.resources.displayMetrics.density

    private companion object {
        const val LINE = 0
        const val ARC = 1
        const val RAY = 2
    }

    var mode = Mode.MEASURE
        private set
    var active = false
        private set

    /** 자가 놓인 쪽과 그 쪽 좌표의 가운데·기울기(라디안) */
    private var page = 0
    private var cx = 0f
    private var cy = 0f
    private var theta = 0f
    /** 막대 자의 길이와 각도기 반지름 (쪽 포인트) */
    private var len = RulerMath.BAR_LENGTH
    private var radius = RulerMath.PROTRACTOR_RADIUS
    private val wid = RulerMath.BAR_WIDTH

    // ---- 켜고 끄기 ----

    /** [m] 모양의 자를 켠다. 이미 켜져 있으면 모양만 바꾸고 놓인 자리·기울기는 그대로 */
    fun show(m: Mode) {
        val wasActive = active
        mode = m
        active = true
        // 놓인 자리와 (두 손가락으로 바꾼) 크기는 모양을 바꿔도 그대로
        if (!wasActive || page !in 0 until host.pageCount) place()
        host.onRulerChanged()
        view.invalidate()
    }

    fun hide() {
        if (!active) return
        active = false
        grabbing = false
        readout = null
        host.onRulerChanged()
        view.invalidate()
    }

    /** 지금 보는 쪽의 화면 가운데에 가로로 놓는다 */
    private fun place() {
        page = host.currentPage().coerceIn(0, (host.pageCount - 1).coerceAtLeast(0))
        theta = 0f
        resize()
        val w = if (mode == Mode.PROTRACTOR) radius * 2 else len
        val h = if (mode == Mode.PROTRACTOR) radius else wid
        val o = host.centerOrigin(page, w, h)
        cx = o[0] + w / 2
        cy = o[1] + (if (mode == Mode.PROTRACTOR) radius else wid / 2)
    }

    /** 쪽 너비에 맞춰 크기를 정한다 */
    private fun resize() {
        val pw = host.pageWidth(page)
        len = RulerMath.barLength(pw)
        radius = RulerMath.protractorRadius(pw)
    }

    // ---- 그리기 ----

    private val bodyFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x3D90CAF9 }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xCC1565C0.toInt()
    }
    private val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xDD0D2A5C.toInt()
    }
    private val guide = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x990D2A5C.toInt()
    }
    private val label = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xDD0D2A5C.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6102A43.toInt() }
    private val pillText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        isFakeBoldText = true
    }
    private val path = Path()
    private val box = RectF()

    /**
     * 쪽 [pageIndex]에 자의 몸통을 그린다 (쪽 좌표 캔버스). 필기 *아래*에 그려야 자 가장자리를 따라 그은 선이 자에 가려지지 않는다
     */
    fun draw(c: Canvas, pageIndex: Int) {
        if (!active || pageIndex != page) return
        val px = 1f / host.scale
        edge.strokeWidth = 1.4f * density * px
        tick.strokeWidth = 0.8f * density * px
        c.save()
        c.translate(cx, cy)
        c.rotate(Math.toDegrees(theta.toDouble()).toFloat())
        if (mode == Mode.PROTRACTOR) drawProtractor(c, px) else drawBar(c, px)
        c.restore()
    }

    /** 긋는 동안의 길이·각도 읽기를 필기 위에 그린다 */
    fun drawOverlay(c: Canvas, pageIndex: Int) {
        if (!active || pageIndex != page) return
        readout?.let { drawReadout(c, it, 1f / host.scale) }
    }

    private fun drawBar(c: Canvas, px: Float) {
        box.set(-len / 2, -wid / 2, len / 2, wid / 2)
        c.drawRect(box, bodyFill)
        c.drawRect(box, edge)
        // 눈금: 위쪽 가장자리를 따라 1mm마다, 5mm·1cm는 길게, 1cm마다 숫자. 왼쪽 끝이 0
        label.textSize = 8.5f * density * px * 0.9f
        val mmCount = (len * RulerMath.MM_PER_PT).toInt()
        for (i in 0..mmCount) {
            val x = -len / 2 + i * RulerMath.PT_PER_MM
            val kind = RulerMath.tickKind(i)
            val l = when (kind) { 10 -> 12f; 5 -> 8f; else -> 5f } * density * px * 0.9f
            c.drawLine(x, -wid / 2, x, -wid / 2 + l, tick)
            if (kind == 10 && i > 0) c.drawText((i / 10).toString(), x, -wid / 2 + l + label.textSize + 1f * density * px, label)
        }
    }

    private fun drawProtractor(c: Canvas, px: Float) {
        box.set(-radius, -radius, radius, radius)
        path.reset()
        path.moveTo(-radius, 0f)
        path.arcTo(box, 180f, 180f)
        path.close()
        c.drawPath(path, bodyFill)
        c.drawPath(path, edge)
        // 가운데(꼭짓점): 여기서 펜을 대고 끌면 끈 쪽으로 정수 도 선이 그어진다 (손잡이처럼 보이게 고리로)
        val m = 7f * density * px
        c.drawLine(-m, 0f, m, 0f, tick)
        c.drawLine(0f, -m, 0f, m, tick)
        c.drawCircle(0f, 0f, 11f * density * px, edge)
        // 눈금: 1°마다, 5°·10°는 길게, 10°마다 숫자. 오른쪽 밑변이 0°, 왼쪽이 180°
        label.textSize = 10f * density * px
        // 가운데로 뻗는 안내선: 90°는 가운데까지 가장 길게, 30° 단위(30·60·120·150°)는 그 절반쯤. 숫자 바깥 둘레 눈금 안쪽에서 시작
        val inner = radius - (11f * density * px * 0.9f + label.textSize * 1.9f)
        guide.strokeWidth = 0.9f * density * px
        for (deg in intArrayOf(30, 60, 90, 120, 150)) {
            val to = if (deg == 90) 0f else radius * 0.5f
            if (inner <= to) continue
            val a = Math.toRadians(deg.toDouble())
            val cs = Math.cos(a).toFloat()
            val sn = Math.sin(a).toFloat()
            c.drawLine(inner * cs, -inner * sn, to * cs, -to * sn, guide)
        }
        for (deg in 0..180) {
            val kind = if (deg % 10 == 0) 10 else if (deg % 5 == 0) 5 else 1
            val l = when (kind) { 10 -> 11f; 5 -> 8f; else -> 5f } * density * px * 0.9f
            val a = Math.toRadians(deg.toDouble())
            val cs = Math.cos(a).toFloat()
            val sn = Math.sin(a).toFloat()
            c.drawLine(radius * cs, -radius * sn, (radius - l) * cs, -(radius - l) * sn, tick)
            if (kind == 10) {
                val r = radius - l - label.textSize * 0.8f
                c.drawText(deg.toString(), r * cs, -r * sn + label.textSize * 0.35f, label)
            }
        }
    }

    // ---- 긋는 동안의 읽기 ----

    private class Readout(val text: String, val x: Float, val y: Float)
    private var readout: Readout? = null

    fun clearReadout() {
        if (readout != null) {
            readout = null
            view.invalidate()
        }
    }

    private fun drawReadout(c: Canvas, r: Readout, px: Float) {
        pillText.textSize = 13f * density * px
        val w = pillText.measureText(r.text) + 14f * density * px
        val h = 22f * density * px
        // 긋는 점 위쪽에, 쪽 안으로
        val pw = host.pageWidth(page)
        val x = r.x.coerceIn(w / 2, (pw - w / 2).coerceAtLeast(w / 2))
        val y = (r.y - 28f * density * px).coerceAtLeast(h)
        box.set(x - w / 2, y - h / 2 - 2f * density * px, x + w / 2, y + h / 2 - 2f * density * px)
        c.drawRoundRect(box, h / 2, h / 2, pill)
        c.drawText(r.text, x, y + pillText.textSize * 0.35f - 2f * density * px, pillText)
    }

    // ---- 펜으로 가장자리를 따라 긋기 ----

    /**
     * 자를 따라 긋고 있는 한 획. 시작할 때 정한 시작점 ([startX], [startY], 쪽 좌표)에서 지금 점까지의 점들을 [points]로 돌려준다.
     * [kind]: [LINE] 막대 가장자리·각도기 밑변을 따라 곧게, [ARC] 각도기 둘레를 따라 둥글게,
     * [RAY] 각도기 가운데에서 끈 쪽으로 (정수 도로 맞춘) 곧은 선
     */
    inner class Snap internal constructor(
        private val kind: Int,
        /** 막대·밑변 위에서의 시작 위치 u와 가장자리 v, 각도기 둘레에서는 시작 각 */
        private val u0: Float,
        private val v0: Float,
        private val phi0: Float,
        /** 각도기 둘레를 따라 그을 때의 반지름 (자 가장자리 바로 바깥) */
        private val arcR: Float = radius,
    ) {
        val startX: Float
        val startY: Float

        init {
            val p = if (kind == ARC) {
                val q = RulerMath.arcPoint(arcR, phi0)
                RulerMath.toPage(q[0], q[1], cx, cy, theta)
            } else RulerMath.toPage(u0, v0, cx, cy, theta)
            startX = p[0]
            startY = p[1]
        }

        private fun reach() = if (mode == Mode.PROTRACTOR) radius else len / 2

        /** 지금 점 ([x], [y])까지 이어지는 점들 [x0, y0, x1, y1, …] (첫 점은 시작점) */
        fun points(x: Float, y: Float): FloatArray {
            val l = RulerMath.toLocal(x, y, cx, cy, theta)
            when (kind) {
                LINE -> {
                    val u = l[0].coerceIn(-reach(), reach())
                    val a = RulerMath.toPage(u0, v0, cx, cy, theta)
                    val b = RulerMath.toPage(u, v0, cx, cy, theta)
                    return floatArrayOf(a[0], a[1], b[0], b[1])
                }
                RAY -> {
                    val e = rayEnd(l)
                    val b = RulerMath.toPage(e[0], e[1], cx, cy, theta)
                    return floatArrayOf(startX, startY, b[0], b[1])
                }
            }
            val phi = RulerMath.angleDeg(l[0], l[1])
            val angles = RulerMath.arcAngles(phi0, phi)
            val out = FloatArray(angles.size * 2)
            for (i in angles.indices) {
                val q = RulerMath.arcPoint(arcR, angles[i])
                val p = RulerMath.toPage(q[0], q[1], cx, cy, theta)
                out[i * 2] = p[0]
                out[i * 2 + 1] = p[1]
            }
            return out
        }

        /** 가운데에서 점 [l](자 기준 좌표)을 향해 정수 도로 맞춘 선의 끝 (거리는 그대로) */
        private fun rayEnd(l: FloatArray): FloatArray {
            val d = hypot(l[0], l[1])
            return RulerMath.arcPoint(d, RulerMath.snapDeg(RulerMath.rayAngleDeg(l[0], l[1])))
        }

        /** 긋는 중 보여 줄 읽기를 정한다: 눈금자·각도기 밑변은 길이, 각도기 둘레와 가운데에서 그은 선은 각도 */
        fun updateReadout(x: Float, y: Float) {
            val l = RulerMath.toLocal(x, y, cx, cy, theta)
            val text = when {
                kind == ARC -> RulerMath.formatAngle(RulerMath.angleDeg(l[0], l[1]))
                kind == RAY -> RulerMath.formatAngle(RulerMath.snapDeg(RulerMath.rayAngleDeg(l[0], l[1]))) +
                    " · " + RulerMath.formatLength(hypot(l[0], l[1]))
                else -> RulerMath.formatLength(abs(l[0].coerceIn(-reach(), reach()) - u0))
            }
            if (text == null) {
                readout = null
            } else {
                val p = points(x, y)
                readout = Readout(text, p[p.size - 2], p[p.size - 1])
            }
        }
    }

    /**
     * 쪽 [pageIndex]의 ([px], [py])에서 시작한 획이 자에 붙는가. 붙으면 그 자를 따라 긋는 [Snap], 아니면 null.
     * [tol]은 가장자리에서 이만큼(쪽 포인트) 안이면 붙는 거리, [off]는 자가 선을 가리지 않도록 가장자리에서 바깥으로 띄우는 거리
     * (펜 굵기의 반 + 가장자리 선의 반). 자는 맨 위에 그려지므로 선은 자 가장자리 바로 바깥에 놓인다
     */
    fun beginSnap(pageIndex: Int, px: Float, py: Float, tol: Float, off: Float): Snap? {
        if (!active || pageIndex != page) return null
        val l = RulerMath.toLocal(px, py, cx, cy, theta)
        if (mode == Mode.PROTRACTOR) {
            return when (RulerMath.protractorEdge(l[0], l[1], radius, tol)) {
                RulerMath.CENTER -> Snap(RAY, 0f, 0f, 0f)
                RulerMath.BASELINE -> Snap(LINE, l[0].coerceIn(-radius, radius), off, 0f)
                RulerMath.ARC -> Snap(ARC, 0f, 0f, RulerMath.angleDeg(l[0], l[1]), radius + off)
                else -> null
            }
        }
        val e = RulerMath.barEdge(l[0], l[1], len, wid, tol)
        if (e == 0) return null
        return Snap(LINE, RulerMath.clampU(l[0], len), e * (wid / 2 + off), 0f)
    }

    // ---- 손가락으로 옮기고 돌리기 ----

    private var grabbing = false
    private var id1 = -1
    private var id2 = -1
    private var x1 = 0f
    private var y1 = 0f
    private var x2 = 0f
    private var y2 = 0f

    /** 손가락이 자의 몸통을 눌렀는가 (쪽 좌표 점) */
    private fun hitBody(px: Float, py: Float): Boolean {
        val l = RulerMath.toLocal(px, py, cx, cy, theta)
        return if (mode == Mode.PROTRACTOR) hypot(l[0], l[1]) <= radius && l[1] <= 0.15f * radius
        else RulerMath.barContains(l[0], l[1], len, wid)
    }

    private fun pagePoint(ev: MotionEvent, i: Int) = floatArrayOf(host.pageX(page, ev.getX(i)), host.pageY(page, ev.getY(i)))

    /**
     * 손가락 터치: 자의 몸통을 누르고 시작한 손가락들은 자를 옮기고 돌린다 (한 손가락 옮기기, 두 손가락 옮기며 돌리기).
     * 이 동작이 이어지는 동안은 true를 돌려주어 문서 화면이 따로 움직이지 않게 한다.
     * 펜이 닿으면(두 번째 포인터) 놓아 주고 false를 돌려주어 펜이 쓰이게 한다 (한 손으로 자를 잡고 다른 손으로 긋는 것)
     */
    fun onFingerTouch(ev: MotionEvent, penActive: Boolean): Boolean {
        if (!active) return false
        if (!grabbing) {
            if (ev.actionMasked != MotionEvent.ACTION_DOWN || penActive) return false
            if (ev.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER) return false
            val p = pagePoint(ev, 0)
            if (!hitBody(p[0], p[1])) return false
            host.stopFling()
            grabbing = true
            id1 = ev.getPointerId(0)
            id2 = -1
            x1 = p[0]
            y1 = p[1]
            return true
        }
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                val i = ev.actionIndex
                if (ev.getToolType(i) != MotionEvent.TOOL_TYPE_FINGER) {
                    grabbing = false
                    return false
                }
                if (id2 == -1) {
                    val p = pagePoint(ev, i)
                    id2 = ev.getPointerId(i)
                    x2 = p[0]
                    y2 = p[1]
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val lifted = ev.getPointerId(ev.actionIndex)
                if (lifted == id2) {
                    id2 = -1
                } else if (lifted == id1) {
                    id1 = id2
                    x1 = x2
                    y1 = y2
                    id2 = -1
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val i1 = ev.findPointerIndex(id1)
                if (i1 < 0) return true
                val a = pagePoint(ev, i1)
                if (id2 == -1) {
                    cx += a[0] - x1
                    cy += a[1] - y1
                } else {
                    val i2 = ev.findPointerIndex(id2)
                    if (i2 >= 0) {
                        val b = pagePoint(ev, i2)
                        val r = RulerMath.twoFinger(x1, y1, x2, y2, a[0], a[1], b[0], b[1], cx, cy)
                        theta += r[0]
                        cx = r[1]
                        cy = r[2]
                        // 두 손가락을 벌리고 오므리면 자도 커지고 작아진다 (눈금은 그대로 실제 mm)
                        len = RulerMath.clampLength(len * r[3])
                        radius = RulerMath.clampRadius(radius * r[3])
                        x2 = b[0]
                        y2 = b[1]
                    }
                }
                x1 = a[0]
                y1 = a[1]
                cx = cx.coerceIn(0f, host.pageWidth(page))
                cy = cy.coerceIn(0f, host.pageHeight(page))
                view.invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> grabbing = false
        }
        return true
    }
}
