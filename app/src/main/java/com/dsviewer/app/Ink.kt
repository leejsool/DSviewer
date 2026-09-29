package com.dsviewer.app

import android.graphics.Path
import kotlin.math.roundToInt

/** SHAPE = 보정 펜 (그린 결과는 PEN 획으로 저장) */
enum class Tool { PEN, SHAPE, HIGHLIGHTER, ERASER, LASSO }

/**
 * 한 획. 좌표는 페이지 기준(단위: PDF 포인트, 원점은 화면에 보이는 페이지의 왼쪽 위).
 * 점마다 (x, y, 필압) 3개 값을 저장한다.
 */
class Stroke(val tool: Tool, color: Int, width: Float) {
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

    fun recolor(c: Int) {
        color = c
        version++
    }

    fun copy(): Stroke {
        val s = Stroke(tool, color, width)
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
    val pages: Array<MutableList<Stroke>> = Array(pageCount) { mutableListOf() }

    private sealed class Action {
        class Add(val page: Int, val stroke: Stroke) : Action()
        class Erase(val items: List<Pair<Int, Stroke>>) : Action()
        class Move(val strokes: List<Stroke>, val dx: Float, val dy: Float) : Action()
        class AddAll(val page: Int, val strokes: List<Stroke>) : Action()
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
        push(Action.Add(page, stroke))
    }

    /** 이미 목록에서 지운 획들을 기록한다 */
    fun erased(items: List<Pair<Int, Stroke>>) {
        if (items.isNotEmpty()) push(Action.Erase(items))
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
        push(Action.AddAll(page, strokes))
    }

    /** 획들의 색·크기 바꾸기를 한 번의 실행 취소 단위로 기록한다 */
    fun edit(strokes: List<Stroke>, block: () -> Unit) {
        if (strokes.isEmpty()) return
        val before = strokes.map { it.state() }
        block()
        push(Action.Edit(strokes, before, strokes.map { it.state() }))
    }

    fun undo() {
        val a = undoStack.removeLastOrNull() ?: return
        when (a) {
            is Action.Add -> pages[a.page].remove(a.stroke)
            is Action.Erase -> a.items.forEach { (p, s) -> pages[p].add(s) }
            is Action.Move -> a.strokes.forEach { it.translate(-a.dx, -a.dy) }
            is Action.AddAll -> { val set = a.strokes.toSet(); pages[a.page].removeAll { it in set } }
            is Action.Edit -> a.strokes.forEachIndexed { i, s -> s.restore(a.before[i]) }
        }
        redoStack.addLast(a)
        changed()
    }

    fun redo() {
        val a = redoStack.removeLastOrNull() ?: return
        when (a) {
            is Action.Add -> pages[a.page].add(a.stroke)
            is Action.Erase -> a.items.forEach { (p, s) -> pages[p].remove(s) }
            is Action.Move -> a.strokes.forEach { it.translate(a.dx, a.dy) }
            is Action.AddAll -> pages[a.page].addAll(a.strokes)
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
