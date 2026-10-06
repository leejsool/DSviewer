package com.dsviewer.app

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * 고른 표 하나를 고치는 일 (한글의 표 편집을 본뜸): 칸 고르기(누르거나 끌어 범위), 선 끌어 간격 바꾸기,
 * 표 그리기(줄 긋기로 칸 나누기), 표 지우개(줄 지워 칸 합치기), 줄·칸 넣기와 지우기, 셀 합치기·나누기, 제목 줄, 선 굵기.
 *
 * 표 모양을 바꾸는 계산은 [InkTable]이 한다. 여기서는 터치를 표 좌표로 옮기고, 결과를 실행 취소 기록에 남기며
 * (표 모양은 바꿀 수 없는 값이라 획을 새 획으로 갈아 끼운다), 고른 칸 표시를 그린다.
 * 선을 끌거나 지우는 동안은 획의 표를 미리 보기 모양으로 잠깐 바꿔 두고, 손을 떼면 되돌린 뒤 한 번에 기록한다
 */
class TableController(private val host: Host) {

    interface Host {
        val ink: InkDocument?
        val scale: Float
        val density: Float
        /** 지금 고른 획들과 그 쪽 번호 */
        val selection: List<Stroke>
        val selectedPage: Int
        fun toPageX(page: Int, sx: Float): Float
        fun toPageY(page: Int, sy: Float): Float
        fun screenX(page: Int, px: Float): Float
        fun screenY(page: Int, py: Float): Float
        /** 선택 상자 (화면 좌표) */
        fun selectionRect(): RectF
        /** 새로 만든 표를 고르게 한다 (칸 고른 것은 그대로 둔다) */
        fun reselect(page: Int, stroke: Stroke)
        /** 고른 표를 화면 [curSx], [curSy]까지 끌어 옮기기 시작한다 (처음 누른 자리는 [startSx], [startSy]) */
        fun beginMove(startSx: Float, startSy: Float, curSx: Float, curSy: Float)
        fun edit(strokes: List<Stroke>, block: () -> Unit)
        fun invalidate()
        fun hint(message: String)
        /** 표 편집 상태(고른 표·칸·방식)가 바뀜: 편집 막대를 맞춘다 */
        fun onTableChanged()
    }

    enum class Mode { SELECT, DRAW, ERASE }

    private enum class Touch { NONE, PENDING, RANGE, LINE, DRAW, ERASE }

    /** 지금 방식: 칸 고르기·선 옮기기 / 줄 긋기 / 줄 지우기 */
    var mode = Mode.SELECT
        private set

    /** 고른 칸 범위 (셀 경계까지 넓힌 것). 없으면 null */
    var cells: CellRange? = null
        private set

    private var touch = Touch.NONE
    val touching get() = touch != Touch.NONE

    /** 표를 새 획으로 갈아 끼우는 중: 고른 것이 바뀌어도 칸·방식을 지우지 않는다 */
    private var replacing = false

    // 터치 상태
    private var startSx = 0f
    private var startSy = 0f
    private var anchorR = 0
    private var anchorC = 0
    private var prevCells: CellRange? = null
    private var moved = false
    private var orig: InkTable? = null
    private var line: TableLineHit? = null
    private var linePos0 = 0f
    private var startLoc = FloatArray(2)
    private var curLoc = FloatArray(2)
    private val trail = ArrayList<Float>()

    /** 고른 표 (표 하나만 골랐을 때) */
    fun stroke(): Stroke? = host.selection.singleOrNull()?.takeIf { it.table != null && it.count >= 4 }

    val active get() = stroke() != null

    val table: InkTable? get() = stroke()?.table

    /** 선 굵기 */
    val lineWidth: Float get() = stroke()?.width ?: 0f

    private fun frame(st: Stroke): TableFrame? {
        val t = st.table ?: return null
        return TableFrame(tableCorners(st), t.width, t.height)
    }

    // ================= 고른 것이 바뀜 =================

    /** 마지막으로 본 고른 표 (같은 표를 다시 고른 것이면 칸·방식을 그대로 둔다) */
    private var lastStroke: Stroke? = null

    /** 문서 화면에서 고른 것이 바뀌었을 때 */
    fun onSelectionChanged() {
        val cur = stroke()
        if (replacing || (cur != null && cur === lastStroke)) {
            lastStroke = cur
            host.onTableChanged()
            return
        }
        lastStroke = cur
        cells = null
        mode = Mode.SELECT
        touch = Touch.NONE
        orig = null
        trail.clear()
        host.onTableChanged()
    }

    fun setMode(m: Mode) {
        if (mode == m) return
        mode = m
        host.invalidate()
        host.onTableChanged()
    }

    private fun setCells(r: CellRange?) {
        if (cells == r) return
        cells = r
        host.invalidate()
        host.onTableChanged()
    }

    // ================= 터치 =================

    /** 표 안팎의 허용 거리 (표 좌표, 선을 집거나 줄을 긋는 자리) */
    private fun eps(f: TableFrame) = TOUCH_DP * host.density / host.scale / ((f.scaleX + f.scaleY) / 2f)

    /** 선을 끌 때 이웃 선과 이만큼(표 좌표)은 떨어져 있게 */
    private fun minSize(f: TableFrame, horizontal: Boolean) =
        MIN_CELL_DP * host.density / host.scale / (if (horizontal) f.scaleY else f.scaleX)

    private fun moveHandlePos(): FloatArray {
        val r = host.selectionRect()
        val d = host.density
        var y = r.top - 30f * d
        if (y < 18f * d) y = r.bottom + 30f * d
        return floatArrayOf(r.left + 22f * d, y)
    }

    /** 이동 손잡이(표 왼쪽 위 바깥의 십자 단추)를 눌렀는가 */
    fun moveHandleHit(sx: Float, sy: Float): Boolean {
        if (!active) return false
        val h = moveHandlePos()
        return hypot(sx - h[0], sy - h[1]) <= HANDLE_TOUCH_DP * host.density
    }

    /**
     * 화면의 (sx, sy)를 눌렀다. 표 편집으로 받았으면 true (크기 조절·회전 손잡이는 이미 걸러진 뒤)
     */
    fun startTouch(sx: Float, sy: Float): Boolean {
        val st = stroke() ?: return false
        if (moveHandleHit(sx, sy)) {
            host.beginMove(sx, sy, sx, sy)
            return true
        }
        val f = frame(st) ?: return false
        val t = st.table ?: return false
        val page = host.selectedPage
        val loc = f.toLocal(host.toPageX(page, sx), host.toPageY(page, sy)) ?: return false
        if (loc[0] < 0f || loc[1] < 0f || loc[0] > t.width || loc[1] > t.height) return false
        startSx = sx
        startSy = sy
        moved = false
        startLoc = loc
        curLoc = loc
        when (mode) {
            Mode.SELECT -> {
                val hit = t.lineAt(loc[0], loc[1], eps(f))
                if (hit != null) {
                    touch = Touch.LINE
                    line = hit
                    orig = t
                    linePos0 = if (hit.horizontal) t.rowY[hit.index] else t.colX[hit.index]
                    return true
                }
                anchorR = t.rowAt(loc[1])
                anchorC = t.colAt(loc[0])
                prevCells = cells
                if (cells == null) {
                    // 칸을 아직 안 골랐으면: 톡 누르면 그 칸을 고르고, 끌면 표를 옮긴다
                    touch = Touch.PENDING
                } else {
                    touch = Touch.RANGE
                    setCells(t.expand(CellRange(anchorR, anchorC, anchorR, anchorC)))
                }
            }
            Mode.DRAW -> touch = Touch.DRAW
            Mode.ERASE -> {
                touch = Touch.ERASE
                orig = t
                trail.clear()
                trail.add(loc[0]); trail.add(loc[1])
                eraseAlong(st, f, loc[0], loc[1], loc[0], loc[1])
            }
        }
        host.invalidate()
        return true
    }

    fun moveTouch(sx: Float, sy: Float) {
        val st = stroke() ?: return
        val f = frame(st) ?: return
        val t = st.table ?: return
        val page = host.selectedPage
        val loc = f.toLocal(host.toPageX(page, sx), host.toPageY(page, sy)) ?: return
        val prev = curLoc
        curLoc = loc
        if (!moved && hypot(sx - startSx, sy - startSy) > SLOP_DP * host.density) moved = true
        when (touch) {
            Touch.PENDING -> if (moved) {
                touch = Touch.NONE
                host.beginMove(startSx, startSy, sx, sy)
            }
            Touch.RANGE -> {
                val r = t.rowAt(loc[1])
                val c = t.colAt(loc[0])
                setCells(t.expand(CellRange(min(anchorR, r), min(anchorC, c), max(anchorR, r), max(anchorC, c))))
            }
            Touch.LINE -> {
                val h = line ?: return
                val o = orig ?: return
                val at = linePos0 + if (h.horizontal) loc[1] - startLoc[1] else loc[0] - startLoc[0]
                st.table = o.moveLine(h, at, minSize(f, h.horizontal)) ?: o
            }
            Touch.DRAW -> {}
            Touch.ERASE -> {
                trail.add(loc[0]); trail.add(loc[1])
                eraseAlong(st, f, prev[0], prev[1], loc[0], loc[1])
            }
            Touch.NONE -> {}
        }
        host.invalidate()
    }

    /** (x0, y0)에서 (x1, y1)까지 걸으며 닿은 선 토막을 지운다 (미리 보기로 획의 표를 바꿔 둔다) */
    private fun eraseAlong(st: Stroke, f: TableFrame, x0: Float, y0: Float, x1: Float, y1: Float) {
        var cur = st.table ?: return
        val e = eps(f)
        val dist = hypot(x1 - x0, y1 - y0)
        val steps = max(1, (dist / (e * 0.5f)).toInt())
        for (k in 0..steps) {
            val x = x0 + (x1 - x0) * k / steps
            val y = y0 + (y1 - y0) * k / steps
            cur = cur.eraseAt(x, y, e) ?: cur
        }
        st.table = cur
    }

    fun endTouch(commit: Boolean) {
        val st = stroke()
        val f = st?.let { frame(it) }
        val kind = touch
        touch = Touch.NONE
        if (st == null || f == null) {
            val o = orig
            if (o != null) host.selection.singleOrNull()?.table = o
            orig = null
            trail.clear()
            host.invalidate()
            return
        }
        when (kind) {
            Touch.PENDING -> if (commit) {
                val t = st.table
                if (t != null) setCells(t.expand(CellRange(anchorR, anchorC, anchorR, anchorC)))
            }
            Touch.RANGE -> {
                // 이미 하나만 골라 둔 칸을 다시 톡 누르면 고른 것을 푼다 (그러면 끌어서 표를 옮길 수 있다)
                val p = prevCells
                val cell = p?.let { st.table?.cellAt(it.r0, it.c0) }
                if (commit && !moved && p != null && p == cells && cell == p) setCells(null)
            }
            Touch.LINE, Touch.ERASE -> {
                val preview = st.table
                val o = orig
                st.table = o
                orig = null
                trail.clear()
                if (commit && preview != null && o != null && preview != o) apply(st, preview, f, null)
            }
            Touch.DRAW -> if (commit) finishDraw(st, f)
            Touch.NONE -> {}
        }
        host.invalidate()
    }

    /** 표 그리기: 그은 줄로 칸을 나눈다 */
    private fun finishDraw(st: Stroke, f: TableFrame) {
        val t = st.table ?: return
        val dx = (curLoc[0] - startLoc[0]) * f.scaleX
        val dy = (curLoc[1] - startLoc[1]) * f.scaleY
        // 너무 짧게 그은 것은 줄 긋기가 아니다
        if (hypot(dx, dy) < MIN_DRAW_DP * host.density / host.scale) return
        val e = eps(f)
        val result = if (abs(dx) >= abs(dy)) t.splitHorizontal((startLoc[1] + curLoc[1]) / 2f, startLoc[0], curLoc[0], e)
        else t.splitVertical((startLoc[0] + curLoc[0]) / 2f, startLoc[1], curLoc[1], e)
        if (result == null) host.hint("나눌 칸이 없습니다. 칸을 가로질러 끝까지 그어 주세요.")
        else apply(st, result, f, null)
    }

    // ================= 고치기 =================

    /** 고친 표로 획을 갈아 끼운다 (실행 취소 한 번). 표가 커지거나 줄면 상자도 같은 크기만큼 자란다 */
    private fun apply(st: Stroke, nt: InkTable, f: TableFrame, newCells: CellRange?) {
        val ink = host.ink ?: return
        val page = host.selectedPage
        val ns = Stroke(Tool.PEN, st.color, st.width)
        ns.table = nt
        val c = f.corners(nt.width, nt.height)
        for (i in 0 until 4) ns.add(c[i * 2], c[i * 2 + 1], 1f)
        ink.replace(page, st, ns)
        replacing = true
        try {
            host.reselect(page, ns)
        } finally {
            replacing = false
        }
        cells = newCells?.let { nt.expand(it) }
        host.invalidate()
        host.onTableChanged()
    }

    private fun needCells(): CellRange? {
        val r = cells
        if (r == null) host.hint("먼저 표에서 칸을 눌러 고르세요.")
        return r
    }

    /** 고른 줄들의 위([below]가 false) 또는 아래에 줄을 넣는다 */
    fun insertRow(below: Boolean) {
        val st = stroke() ?: return
        val t = st.table ?: return
        val f = frame(st) ?: return
        val r = needCells() ?: return
        val h = (t.rowY[r.r1 + 1] - t.rowY[r.r0]) / r.rows
        val nt = t.insertRow(if (below) r.r1 else r.r0, below, h) ?: return
        apply(st, nt, f, if (below) r else CellRange(r.r0 + 1, r.c0, r.r1 + 1, r.c1))
    }

    /** 고른 칸들의 왼쪽([right]가 false) 또는 오른쪽에 칸을 넣는다 */
    fun insertCol(right: Boolean) {
        val st = stroke() ?: return
        val t = st.table ?: return
        val f = frame(st) ?: return
        val r = needCells() ?: return
        val w = (t.colX[r.c1 + 1] - t.colX[r.c0]) / r.cols
        val nt = t.insertCol(if (right) r.c1 else r.c0, right, w) ?: return
        apply(st, nt, f, if (right) r else CellRange(r.r0, r.c0 + 1, r.r1, r.c1 + 1))
    }

    /** 고른 칸이 걸친 줄을 지운다 */
    fun deleteRows() {
        val st = stroke() ?: return
        val t = st.table ?: return
        val f = frame(st) ?: return
        val r = needCells() ?: return
        val nt = t.deleteRows(r.r0, r.r1)
        if (nt == null) host.hint("모든 줄을 지울 수는 없습니다. 표를 지우려면 '삭제'를 누르세요.") else apply(st, nt, f, null)
    }

    /** 고른 칸이 걸친 칸을 지운다 */
    fun deleteCols() {
        val st = stroke() ?: return
        val t = st.table ?: return
        val f = frame(st) ?: return
        val r = needCells() ?: return
        val nt = t.deleteCols(r.c0, r.c1)
        if (nt == null) host.hint("모든 칸을 지울 수는 없습니다. 표를 지우려면 '삭제'를 누르세요.") else apply(st, nt, f, null)
    }

    /** 셀 합치기 */
    fun merge() {
        val st = stroke() ?: return
        val t = st.table ?: return
        val f = frame(st) ?: return
        val r = needCells() ?: return
        val e = t.expand(r)
        val nt = t.merge(e)
        if (nt == null) host.hint("합칠 칸을 둘 이상 고르세요. 칸을 끌면 여러 칸을 고를 수 있습니다.") else apply(st, nt, f, e)
    }

    /** 고른 범위에 합쳐진 셀이 있는가 */
    fun canUnmerge(): Boolean {
        val t = table ?: return false
        val r = cells ?: return false
        return t.hasMerged(t.expand(r))
    }

    /** 합친 셀 풀기 */
    fun unmerge() {
        val st = stroke() ?: return
        val t = st.table ?: return
        val f = frame(st) ?: return
        val r = needCells() ?: return
        val e = t.expand(r)
        val nt = t.unmerge(e) ?: return
        apply(st, nt, f, e)
    }

    /** 한 칸만 골랐고 합쳐지지 않았는가 (그러면 몇 줄 몇 칸으로 나눌지 묻는다) */
    fun singlePlainCell(): Boolean {
        val t = table ?: return false
        val r = cells ?: return false
        val e = t.expand(r)
        return e.rows * e.cols == 1
    }

    /** 고른 한 칸을 [nr]줄 × [nc]칸으로 나눈다 */
    fun subdivide(nr: Int, nc: Int) {
        val st = stroke() ?: return
        val t = st.table ?: return
        val f = frame(st) ?: return
        val r = needCells() ?: return
        val e = t.expand(r)
        val nt = t.subdivide(e.r0, e.c0, nr, nc, max(minSize(f, true), minSize(f, false)))
        if (nt == null) host.hint("칸이 너무 작아서 더 나눌 수 없습니다.") else apply(st, nt, f, null)
    }

    /** 고른 칸들이 걸친 줄의 높이를 같게 (한 줄만 골랐으면 표 전체) */
    fun equalizeRows() {
        val st = stroke() ?: return
        val t = st.table ?: return
        val f = frame(st) ?: return
        val r = cells?.takeIf { it.rows > 1 } ?: CellRange(0, 0, t.rows - 1, 0)
        val nt = t.equalizeRows(r.r0, r.r1)
        if (nt == null) host.hint("줄 높이가 이미 같습니다.") else apply(st, nt, f, cells)
    }

    /** 고른 칸들이 걸친 칸의 너비를 같게 (한 칸만 골랐으면 표 전체) */
    fun equalizeCols() {
        val st = stroke() ?: return
        val t = st.table ?: return
        val f = frame(st) ?: return
        val r = cells?.takeIf { it.cols > 1 } ?: CellRange(0, 0, 0, t.cols - 1)
        val nt = t.equalizeCols(r.c0, r.c1)
        if (nt == null) host.hint("칸 너비가 이미 같습니다.") else apply(st, nt, f, cells)
    }

    val header: TableHeader get() = table?.header ?: TableHeader.NONE

    fun setHeader(h: TableHeader) {
        val st = stroke() ?: return
        val t = st.table ?: return
        val f = frame(st) ?: return
        apply(st, t.withHeader(h) ?: return, f, cells)
    }

    fun setLineWidth(w: Float) {
        val st = stroke() ?: return
        if (abs(st.width - w) < 0.001f) return
        host.edit(listOf(st)) { st.resize(w) }
        host.invalidate()
        host.onTableChanged()
    }

    // ================= 그리기 =================

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0x331E6FD9 }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF1E6FD9.toInt()
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFFFFFFFF.toInt() }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFFE53935.toInt()
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()

    /** 고른 칸·그은 줄·지우는 길·이동 손잡이 (화면 좌표). 옮기거나 크기를 바꾸는 중이면 [dragging]으로 그리지 않는다 */
    fun draw(canvas: Canvas, dragging: Boolean) {
        val st = stroke() ?: return
        if (dragging) return
        val f = frame(st) ?: return
        val t = st.table ?: return
        val page = host.selectedPage
        val d = host.density
        fun sx(x: Float, y: Float) = host.screenX(page, f.pageX(x, y))
        fun sy(x: Float, y: Float) = host.screenY(page, f.pageY(x, y))
        linePaint.strokeWidth = 2f * d
        cells?.let { r ->
            val b = t.rectOf(r)
            path.reset()
            path.moveTo(sx(b[0], b[1]), sy(b[0], b[1]))
            path.lineTo(sx(b[2], b[1]), sy(b[2], b[1]))
            path.lineTo(sx(b[2], b[3]), sy(b[2], b[3]))
            path.lineTo(sx(b[0], b[3]), sy(b[0], b[3]))
            path.close()
            canvas.drawPath(path, fillPaint)
            canvas.drawPath(path, linePaint)
        }
        when (touch) {
            Touch.LINE -> line?.let { h ->
                // 끌고 있는 선을 굵게 비춘다
                val pos = if (h.horizontal) t.rowY[h.index] else t.colX[h.index]
                linePaint.strokeWidth = 4f * d
                if (h.horizontal) canvas.drawLine(sx(0f, pos), sy(0f, pos), sx(t.width, pos), sy(t.width, pos), linePaint)
                else canvas.drawLine(sx(pos, 0f), sy(pos, 0f), sx(pos, t.height), sy(pos, t.height), linePaint)
            }
            Touch.DRAW -> {
                linePaint.strokeWidth = 3f * d
                canvas.drawLine(
                    sx(startLoc[0], startLoc[1]), sy(startLoc[0], startLoc[1]),
                    sx(curLoc[0], curLoc[1]), sy(curLoc[0], curLoc[1]), linePaint,
                )
            }
            Touch.ERASE -> if (trail.size >= 4) {
                trailPaint.strokeWidth = 3f * d
                path.reset()
                path.moveTo(sx(trail[0], trail[1]), sy(trail[0], trail[1]))
                var i = 2
                while (i + 1 < trail.size) {
                    path.lineTo(sx(trail[i], trail[i + 1]), sy(trail[i], trail[i + 1]))
                    i += 2
                }
                canvas.drawPath(path, trailPaint)
            }
            else -> {}
        }
        drawMoveHandle(canvas)
    }

    /** 이동 손잡이: 흰 동그라미 안에 네 방향 화살표 */
    private fun drawMoveHandle(canvas: Canvas) {
        val h = moveHandlePos()
        val d = host.density
        val r = 15f * d
        linePaint.strokeWidth = 1.6f * d
        canvas.drawCircle(h[0], h[1], r, handleFill)
        canvas.drawCircle(h[0], h[1], r, linePaint)
        val a = 8f * d
        val k = 3f * d
        path.reset()
        // 가로·세로 줄과 끝의 화살촉
        path.moveTo(h[0] - a, h[1]); path.lineTo(h[0] + a, h[1])
        path.moveTo(h[0], h[1] - a); path.lineTo(h[0], h[1] + a)
        path.moveTo(h[0] - a + k, h[1] - k); path.lineTo(h[0] - a, h[1]); path.lineTo(h[0] - a + k, h[1] + k)
        path.moveTo(h[0] + a - k, h[1] - k); path.lineTo(h[0] + a, h[1]); path.lineTo(h[0] + a - k, h[1] + k)
        path.moveTo(h[0] - k, h[1] - a + k); path.lineTo(h[0], h[1] - a); path.lineTo(h[0] + k, h[1] - a + k)
        path.moveTo(h[0] - k, h[1] + a - k); path.lineTo(h[0], h[1] + a); path.lineTo(h[0] + k, h[1] + a - k)
        canvas.drawPath(path, linePaint)
    }

    companion object {
        /** 선을 집거나 줄을 붙이는 거리 (dp) */
        private const val TOUCH_DP = 14f
        /** 선을 끌 때 이웃 선과 떨어져 있어야 할 최소 간격 (dp) */
        private const val MIN_CELL_DP = 14f
        /** 줄 긋기로 치는 최소 길이 (dp) */
        private const val MIN_DRAW_DP = 24f
        /** 톡 누르기와 끌기를 가르는 거리 (dp) */
        private const val SLOP_DP = 10f
        /** 이동 손잡이를 누르는 범위 (dp) */
        private const val HANDLE_TOUCH_DP = 26f
    }
}
