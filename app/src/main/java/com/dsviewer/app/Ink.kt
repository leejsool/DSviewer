package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 쪽에 넣은 그림. [bytes]는 PDF에 그대로 넣을 JPEG (null이면 저장할 때 [bitmap]을 무손실로 넣는다)
 */
class InkImage(val bitmap: Bitmap, val bytes: ByteArray?)

private val imageMatrix = Matrix()
private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
private val imageSrc = FloatArray(8)
private val imageDst = FloatArray(8)

/** 그림: 획의 네 점(왼쪽 위, 오른쪽 위, 오른쪽 아래, 왼쪽 아래)에 맞춰 그린다 (돌리거나 늘여도 따라감) */
private fun drawInkImage(c: Canvas, st: Stroke, alphaMul: Float) {
    val img = st.image ?: return
    if (st.count < 4) return
    val w = img.bitmap.width.toFloat()
    val h = img.bitmap.height.toFloat()
    imageSrc[0] = 0f; imageSrc[1] = 0f; imageSrc[2] = w; imageSrc[3] = 0f
    imageSrc[4] = w; imageSrc[5] = h; imageSrc[6] = 0f; imageSrc[7] = h
    for (k in 0 until 4) {
        imageDst[k * 2] = st.x(k)
        imageDst[k * 2 + 1] = st.y(k)
    }
    imageMatrix.setPolyToPoly(imageSrc, 0, imageDst, 0, 4)
    imagePaint.alpha = (255 * alphaMul).roundToInt()
    c.drawBitmap(img.bitmap, imageMatrix, imagePaint)
}

/** 필기 그리기용 붓 ([drawInkStroke]와 같이 쓴다) */
fun inkPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

/**
 * 획 하나를 그린다 (캔버스는 페이지 좌표로 맞춰 둔 상태). 문서 화면과 쪽 미리보기가 같이 쓴다.
 * 형광펜은 반투명 곱하기, 점선 획은 점선으로. alphaMul < 1이면 흐리게
 */
fun drawInkStroke(c: Canvas, paint: Paint, st: Stroke, alphaMul: Float = 1f) {
    if (st.image != null) {
        drawInkImage(c, st, alphaMul)
        return
    }
    paint.color = st.color
    if (st.tool == Tool.HIGHLIGHTER) {
        paint.alpha = (PdfInk.HL_ALPHA * 255).roundToInt()
        paint.blendMode = BlendMode.MULTIPLY
    } else {
        paint.blendMode = null
    }
    if (alphaMul < 1f) paint.alpha = (paint.alpha * alphaMul).roundToInt()
    paint.pathEffect = if (st.dashed) DashPathEffect(st.dashIntervals(), 0f) else null
    for ((w, path) in st.paths()) {
        paint.strokeWidth = w
        c.drawPath(path, paint)
    }
}

/** SHAPE = 보정 펜 (그린 결과는 PEN 획으로 저장), LASER = 잠깐 보였다 사라지는 레이저 (저장하지 않음) */
enum class Tool { PEN, SHAPE, HIGHLIGHTER, ERASER, LASSO, LASER }

/** STROKE = 닿은 획을 통째로, AREA = 지우개가 지나간 부분만 */
enum class EraserMode { STROKE, AREA }

/**
 * 한 획. 좌표는 페이지 기준(단위: PDF 포인트, 원점은 화면에 보이는 페이지의 왼쪽 위).
 * 점마다 (x, y, 필압) 3개 값을 저장한다.
 */
class Stroke(val tool: Tool, color: Int, width: Float, val dashed: Boolean = false) {
    /** 선택 도구로 색·크기를 바꿀 수 있다 (바꿀 때는 InkDocument.edit으로 기록) */
    var color = color
        private set
    var width = width
        private set
    var data = FloatArray(3 * 64)
        private set
    var count = 0
        private set
    var version = 0
        private set
    /** 그림이면 그 그림 (점 네 개가 그림의 네 모서리). 지우개로는 지우지 않는다 */
    var image: InkImage? = null

    private var cachedPaths: List<Pair<Float, Path>>? = null
    private var cachedVersion = -1

    fun add(x: Float, y: Float, p: Float) {
        if (count * 3 + 3 > data.size) data = data.copyOf(data.size * 2)
        data[count * 3] = x
        data[count * 3 + 1] = y
        data[count * 3 + 2] = p
        count++
        version++
    }

    /** 첫 점만 남긴다 (직선 형광펜: 끝점을 새로 정할 때) */
    fun keepFirst() {
        if (count > 1) count = 1
        version++
    }

    /** 획 전체를 (dx, dy)만큼 옮긴다 */
    fun translate(dx: Float, dy: Float) {
        for (i in 0 until count) {
            data[i * 3] += dx
            data[i * 3 + 1] += dy
        }
        version++
    }

    /** (ax, ay)를 기준으로 k배 키운다. 굵기도 같이 */
    fun scale(k: Float, ax: Float, ay: Float) {
        for (i in 0 until count) {
            data[i * 3] = ax + (data[i * 3] - ax) * k
            data[i * 3 + 1] = ay + (data[i * 3 + 1] - ay) * k
        }
        width *= k
        version++
    }

    /** (cx, cy)를 중심으로 deg도 돌린다 (화면 기준 시계 방향이 +) */
    fun rotate(deg: Float, cx: Float, cy: Float) {
        val r = Math.toRadians(deg.toDouble())
        val c = kotlin.math.cos(r).toFloat()
        val s = kotlin.math.sin(r).toFloat()
        for (i in 0 until count) {
            val dx = data[i * 3] - cx
            val dy = data[i * 3 + 1] - cy
            data[i * 3] = cx + dx * c - dy * s
            data[i * 3 + 1] = cy + dx * s + dy * c
        }
        version++
    }

    fun recolor(c: Int) {
        color = c
        version++
    }

    fun copy(): Stroke {
        val s = Stroke(tool, color, width, dashed)
        s.image = image
        s.data = data.copyOf(count * 3)
        s.count = count
        return s
    }

    /** 실행 취소용으로 지금 모양을 떠 둔다 */
    class State(val data: FloatArray, val count: Int, val color: Int, val width: Float)

    fun state() = State(data.copyOf(count * 3), count, color, width)

    fun restore(s: State) {
        data = s.data.copyOf()
        count = s.count
        color = s.color
        width = s.width
        version++
    }

    fun x(i: Int) = data[i * 3]
    fun y(i: Int) = data[i * 3 + 1]
    fun p(i: Int) = data[i * 3 + 2]

    /** 필압에 따라 굵기가 달라지는 구간들로 나눠 콜백한다. 화면 그리기와 PDF 저장이 같은 규칙을 쓴다. */
    inline fun forEachGroup(block: (w: Float, from: Int, to: Int) -> Unit) {
        if (count == 0) return
        if (tool == Tool.HIGHLIGHTER) {
            block(width, 0, count - 1)
            return
        }
        if (count == 1) {
            block(width * quantize(p(0)), 0, 0)
            return
        }
        var start = 0
        var cur = quantize((p(0) + p(1)) / 2f)
        for (i in 2 until count) {
            val q = quantize((p(i - 1) + p(i)) / 2f)
            if (q != cur) {
                block(width * cur, start, i - 1)
                start = i - 1
                cur = q
            }
        }
        block(width * cur, start, count - 1)
    }

    /** 화면용 Path 목록 (굵기, 경로). 점이 추가되면 다시 만든다. */
    fun paths(): List<Pair<Float, Path>> {
        cachedPaths?.let { if (cachedVersion == version) return it }
        val list = ArrayList<Pair<Float, Path>>()
        forEachGroup { w, from, to ->
            val path = Path()
            path.moveTo(x(from), y(from))
            if (from == to) path.lineTo(x(from) + 0.01f, y(from))
            else for (i in from + 1..to) path.lineTo(x(i), y(i))
            list.add(w to path)
        }
        cachedPaths = list
        cachedVersion = version
        return list
    }

    /** (px, py)에서 반지름 r 안에 이 획이 지나가는지 */
    fun hitTest(px: Float, py: Float, r: Float): Boolean {
        val rr = r + width / 2f
        val rr2 = rr * rr
        if (count == 1) return dist2(px, py, x(0), y(0)) <= rr2
        for (i in 1 until count) {
            if (segDist2(px, py, x(i - 1), y(i - 1), x(i), y(i)) <= rr2) return true
        }
        return false
    }

    /**
     * 영역 지우개: (cx, cy) 중심 반지름 r인 원에 닿은 부분을 잘라 낸 나머지 조각들.
     * 원이 이 획에 닿지 않으면 null, 획 전체가 원 안이면 빈 목록.
     * 선 끝의 둥근 마개가 원 밖으로 삐져나오지 않도록 굵기의 절반만큼 넓게 자른다.
     */
    fun cut(cx: Float, cy: Float, r: Float): List<Stroke>? {
        if (!hitTest(cx, cy, r)) return null
        val rr = r + width / 2f
        val rr2 = rr * rr
        val pieces = ArrayList<Stroke>()
        var cur: Stroke? = null
        fun inside(i: Int) = dist2(x(i), y(i), cx, cy) <= rr2
        fun addPoint(i: Int, t: Float) {
            val piece = cur ?: Stroke(tool, color, width, dashed).also { cur = it }
            if (t == 1f) piece.add(x(i), y(i), p(i))
            else piece.add(
                x(i - 1) + (x(i) - x(i - 1)) * t, y(i - 1) + (y(i) - y(i - 1)) * t, p(i - 1) + (p(i) - p(i - 1)) * t
            )
        }
        fun finish() {
            // 자른 자리에 남는 아주 짧은 부스러기는 버린다
            cur?.let { if (it.count >= 2 && it.length() >= max(0.5f, width * 0.3f)) pieces.add(it) }
            cur = null
        }
        if (!inside(0)) addPoint(0, 1f)
        for (i in 1 until count) {
            // 선분 A→B 위의 점 A + t(B−A)가 원 안에 드는 t 구간 (t0, t1)
            val ax = x(i - 1) - cx; val ay = y(i - 1) - cy
            val dx = x(i) - x(i - 1); val dy = y(i) - y(i - 1)
            val a = dx * dx + dy * dy
            val b = 2 * (ax * dx + ay * dy)
            val c = ax * ax + ay * ay - rr2
            val disc = b * b - 4 * a * c
            if (a == 0f || disc <= 0f) {
                if (!inside(i)) addPoint(i, 1f)
                continue
            }
            val sq = sqrt(disc)
            val t0 = (-b - sq) / (2 * a)
            val t1 = (-b + sq) / (2 * a)
            if (t1 <= 0f || t0 >= 1f) {
                // 이 선분은 원에 닿지 않는다
                addPoint(i, 1f)
                continue
            }
            if (t0 > 0f) {
                addPoint(i, t0)
                finish()
            }
            if (t1 < 1f) {
                finish()
                addPoint(i, t1)
                addPoint(i, 1f)
            }
        }
        finish()
        return pieces
    }

    /** 획의 길이 (페이지 좌표) */
    fun length(): Float {
        var len = 0f
        for (i in 1 until count) len += hypot(x(i) - x(i - 1), y(i) - y(i - 1))
        return len
    }

    /** 점선 획(보정 펜의 점근선)의 [선 길이, 빈칸 길이]. 굵기에 비례, 화면과 PDF가 같이 쓴다 */
    fun dashIntervals() = floatArrayOf(4f + width * 2.5f, 3f + width * 2f)

    companion object {
        /** 필압(0~1) → 굵기 비율(0.3~1.0), 1/12 단위로 양자화 */
        fun quantize(p: Float): Float {
            val f = 0.3f + 0.7f * p.coerceIn(0f, 1f)
            return (f * 12f).roundToInt() / 12f
        }

        private fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = ax - bx
            val dy = ay - by
            return dx * dx + dy * dy
        }

        private fun segDist2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val vx = bx - ax
            val vy = by - ay
            val len2 = vx * vx + vy * vy
            if (len2 == 0f) return dist2(px, py, ax, ay)
            val t = (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
            return dist2(px, py, ax + t * vx, ay + t * vy)
        }
    }
}

/** 문서 전체의 필기와 실행 취소/다시 실행 기록 */
class InkDocument(pageCount: Int) {
    /** 쪽마다 획 목록. 빈 쪽을 넣거나 쪽을 지우면 순서가 바뀐다 */
    val pages: MutableList<MutableList<Stroke>> = MutableList(pageCount) { mutableListOf() }

    // 기록은 쪽 번호 대신 그 쪽의 획 목록을 가리킨다 (쪽을 넣고 빼도 기록이 어긋나지 않게)
    private sealed class Action {
        class Add(val page: MutableList<Stroke>, val stroke: Stroke) : Action()
        /** 지운 획들과, 영역 지우개가 획을 잘라 남긴 조각들 */
        class Erase(
            val items: List<Pair<MutableList<Stroke>, Stroke>>,
            val pieces: List<Pair<MutableList<Stroke>, Stroke>> = emptyList(),
        ) : Action()
        class Move(val strokes: List<Stroke>, val dx: Float, val dy: Float) : Action()
        class AddAll(val page: MutableList<Stroke>, val strokes: List<Stroke>) : Action()
        class Edit(val strokes: List<Stroke>, val before: List<Stroke.State>, val after: List<Stroke.State>) : Action()
    }

    private val undoStack = ArrayDeque<Action>()
    private val redoStack = ArrayDeque<Action>()

    var dirty = false
        private set
    var onChanged: (() -> Unit)? = null

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /** 파일에서 읽어 온 필기 (기록에 남기지 않음) */
    fun load(strokes: List<List<Stroke>>) {
        for (i in strokes.indices) if (i < pages.size) pages[i].addAll(strokes[i])
    }

    fun add(page: Int, stroke: Stroke) {
        pages[page].add(stroke)
        push(Action.Add(pages[page], stroke))
    }

    /** 이미 목록에서 지운 획들(과 영역 지우개가 대신 넣은 조각들)을 기록한다 */
    fun erased(items: List<Pair<Int, Stroke>>, pieces: List<Pair<Int, Stroke>> = emptyList()) {
        if (items.isEmpty() && pieces.isEmpty()) return
        push(Action.Erase(items.map { (p, s) -> pages[p] to s }, pieces.map { (p, s) -> pages[p] to s }))
    }

    /** 선택한 획들을 지운다 */
    fun remove(page: Int, strokes: List<Stroke>) {
        val set = strokes.toSet()
        pages[page].removeAll { it in set }
        erased(strokes.map { page to it })
    }

    /** 선택한 획들을 (dx, dy)만큼 옮긴다 */
    fun move(strokes: List<Stroke>, dx: Float, dy: Float) {
        if (strokes.isEmpty() || (dx == 0f && dy == 0f)) return
        strokes.forEach { it.translate(dx, dy) }
        push(Action.Move(strokes, dx, dy))
    }

    /** 붙여넣기 */
    fun addAll(page: Int, strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        pages[page].addAll(strokes)
        push(Action.AddAll(pages[page], strokes))
    }

    /** 획들의 색·크기 바꾸기를 한 번의 실행 취소 단위로 기록한다 */
    fun edit(strokes: List<Stroke>, block: () -> Unit) {
        if (strokes.isEmpty()) return
        val before = strokes.map { it.state() }
        block()
        push(Action.Edit(strokes, before, strokes.map { it.state() }))
    }

    /** [index] 자리에 빈 쪽을 넣는다 (실행 취소 기록에는 남기지 않음) */
    fun insertPage(index: Int) {
        pages.add(index, mutableListOf())
        changed()
    }

    /** [index] 자리에 쪽들을 넣는다 (다른 PDF를 넣을 때, 그 PDF에 있던 필기와 함께) */
    fun insertPages(index: Int, strokes: List<List<Stroke>>) {
        pages.addAll(index, strokes.map { it.toMutableList() })
        changed()
    }

    /** [index]번째 쪽을 지운다. 그 쪽의 필기에 대한 실행 취소 기록도 함께 버린다 */
    fun removePage(index: Int) {
        val gone = pages.removeAt(index)
        val goneStrokes = gone.toHashSet()
        fun keep(a: Action): Action? = when (a) {
            is Action.Add -> a.takeIf { a.page !== gone }
            is Action.AddAll -> a.takeIf { a.page !== gone }
            is Action.Erase -> {
                val items = a.items.filter { it.first !== gone }
                val pieces = a.pieces.filter { it.first !== gone }
                if (items.isEmpty() && pieces.isEmpty()) null else Action.Erase(items, pieces)
            }
            is Action.Move -> a.takeIf { a.strokes.none { it in goneStrokes } }
            is Action.Edit -> a.takeIf { a.strokes.none { it in goneStrokes } }
        }
        for (stack in listOf(undoStack, redoStack)) {
            val kept = stack.mapNotNull(::keep)
            stack.clear()
            stack.addAll(kept)
        }
        changed()
    }

    fun undo() {
        val a = undoStack.removeLastOrNull() ?: return
        when (a) {
            is Action.Add -> a.page.remove(a.stroke)
            is Action.Erase -> {
                a.pieces.forEach { (p, s) -> p.remove(s) }
                a.items.forEach { (p, s) -> p.add(s) }
            }
            is Action.Move -> a.strokes.forEach { it.translate(-a.dx, -a.dy) }
            is Action.AddAll -> { val set = a.strokes.toSet(); a.page.removeAll { it in set } }
            is Action.Edit -> a.strokes.forEachIndexed { i, s -> s.restore(a.before[i]) }
        }
        redoStack.addLast(a)
        changed()
    }

    fun redo() {
        val a = redoStack.removeLastOrNull() ?: return
        when (a) {
            is Action.Add -> a.page.add(a.stroke)
            is Action.Erase -> {
                a.items.forEach { (p, s) -> p.remove(s) }
                a.pieces.forEach { (p, s) -> p.add(s) }
            }
            is Action.Move -> a.strokes.forEach { it.translate(a.dx, a.dy) }
            is Action.AddAll -> a.page.addAll(a.strokes)
            is Action.Edit -> a.strokes.forEachIndexed { i, s -> s.restore(a.after[i]) }
        }
        undoStack.addLast(a)
        changed()
    }

    fun snapshot(): List<List<Stroke>> = pages.map { it.toList() }

    fun markSaved() {
        dirty = false
        onChanged?.invoke()
    }

    private fun push(a: Action) {
        undoStack.addLast(a)
        redoStack.clear()
        changed()
    }

    private fun changed() {
        dirty = true
        onChanged?.invoke()
    }
}
