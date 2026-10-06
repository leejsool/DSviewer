package com.dsviewer.app

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 표의 제목 줄: 없음 · 가로(첫 줄) · 세로(첫 칸) · 가로와 세로 모두.
 * 제목 칸은 옅게 칠하고, 제목과 나머지 사이의 선은 굵게 그린다.
 * [code]는 PDF에 저장하는 글자
 */
enum class TableHeader(val code: Char, val label: String) {
    NONE('N', "없음"),
    ROW('R', "가로 (첫 줄)"),
    COL('C', "세로 (첫 칸)"),
    BOTH('B', "가로·세로 모두");

    /** 첫 줄이 제목인가 */
    val row get() = this == ROW || this == BOTH
    /** 첫 칸이 제목인가 */
    val col get() = this == COL || this == BOTH

    /** 줄과 칸을 바꿔 놓았을 때의 제목 줄 */
    fun transposed() = when (this) {
        ROW -> COL
        COL -> ROW
        else -> this
    }

    companion object {
        fun of(code: Char?) = entries.firstOrNull { it.code == code } ?: NONE
    }
}

/** 칸 범위: 줄 [r0]~[r1], 칸 [c0]~[c1] (모두 0부터, 끝 포함) */
data class CellRange(val r0: Int, val c0: Int, val r1: Int, val c1: Int) {
    val rows get() = r1 - r0 + 1
    val cols get() = c1 - c0 + 1
    fun contains(r: Int, c: Int) = r in r0..r1 && c in c0..c1
}

/** 눈에 보이는 선 한 줄 (표 좌표). [heavy]는 제목 아래·옆의 굵은 선 */
class TableLine(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val heavy: Boolean)

/** 제목 칸을 칠할 네모 (표 좌표) */
class TableShade(val l: Float, val t: Float, val r: Float, val b: Float)

/** 그릴 것: 바깥 테두리 안쪽의 보이는 선들과 제목 칸 */
class TableGeometry(val lines: List<TableLine>, val shades: List<TableShade>)

/** 누른 자리의 선: [horizontal]이면 가로선(줄 사이), [index]는 그 선이 줄(칸) 몇 번째 앞에 있는지 (1부터) */
data class TableLineHit(val horizontal: Boolean, val index: Int)

/**
 * 표 모양. 줄 높이 [rowH]와 칸 너비 [colW] (표 좌표 pt)에, 칸마다 어느 셀에 속하는지 [ids]가 있다.
 * 같은 번호의 칸들은 한 셀로 합쳐진 것이고 늘 직사각형이다. 한글의 셀 합치기·나누기, 표 그리기(줄 긋기)·표 지우개를
 * 이 격자 + 합침으로 나타낸다: 줄을 그으면 격자에 선이 하나 더 생기고 닿지 않은 셀은 그 선을 가로질러 합쳐진 채로 남는다.
 *
 * 바꿀 수 없는 값이다. 고치는 연산은 모두 새 표(고칠 것이 없으면 null)를 돌려준다.
 * 안드로이드를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다
 */
class InkTable private constructor(
    val colW: FloatArray,
    val rowH: FloatArray,
    private val ids: IntArray,
    val header: TableHeader,
) {
    val rows get() = rowH.size
    val cols get() = colW.size

    /** 칸·줄 경계의 위치 (왼쪽·위에서부터, 길이 cols+1 · rows+1) */
    val colX = FloatArray(colW.size + 1).also { for (i in colW.indices) it[i + 1] = it[i] + colW[i] }
    val rowY = FloatArray(rowH.size + 1).also { for (i in rowH.indices) it[i + 1] = it[i] + rowH[i] }

    val width get() = colX[cols]
    val height get() = rowY[rows]

    /** (r, c)칸이 속한 셀 번호 */
    fun id(r: Int, c: Int) = ids[r * cols + c]

    /** 셀 번호 → 그 셀이 차지한 칸 범위 */
    private val bounds: Map<Int, CellRange> by lazy {
        val m = HashMap<Int, CellRange>()
        for (r in 0 until rows) for (c in 0 until cols) {
            val id = id(r, c)
            val b = m[id]
            m[id] = if (b == null) CellRange(r, c, r, c) else CellRange(min(b.r0, r), min(b.c0, c), max(b.r1, r), max(b.c1, c))
        }
        m
    }

    /** (r, c)칸이 속한 셀의 칸 범위 */
    fun cellAt(r: Int, c: Int): CellRange = bounds.getValue(id(r, c))

    /** 셀이 모두 몇 개인가 */
    val cellCount get() = bounds.size

    /** 합쳐진 셀(칸 둘 이상)이 [range] 안에 있는가 */
    fun hasMerged(range: CellRange): Boolean {
        for (r in range.r0..range.r1) for (c in range.c0..range.c1) {
            val b = cellAt(r, c)
            if (b.rows * b.cols > 1) return true
        }
        return false
    }

    /** 칸 범위 [range]를 감싸는 네모 (표 좌표 [왼쪽, 위, 오른쪽, 아래]) */
    fun rectOf(range: CellRange) = floatArrayOf(colX[range.c0], rowY[range.r0], colX[range.c1 + 1], rowY[range.r1 + 1])

    /** 표 좌표 y가 있는 줄 (표 밖이면 가장 가까운 줄) */
    fun rowAt(y: Float): Int {
        for (r in 0 until rows) if (y < rowY[r + 1]) return r
        return rows - 1
    }

    /** 표 좌표 x가 있는 칸 (표 밖이면 가장 가까운 칸) */
    fun colAt(x: Float): Int {
        for (c in 0 until cols) if (x < colX[c + 1]) return c
        return cols - 1
    }

    /**
     * 칸 범위를 셀 경계까지 넓힌다: 합쳐진 셀이 범위에 조금이라도 걸치면 그 셀 전체를 넣고,
     * 그 때문에 범위가 커져 다른 셀이 걸치면 또 넓힌다 (직사각형이 될 때까지)
     */
    fun expand(range: CellRange): CellRange {
        var cur = CellRange(
            range.r0.coerceIn(0, rows - 1), range.c0.coerceIn(0, cols - 1),
            range.r1.coerceIn(0, rows - 1), range.c1.coerceIn(0, cols - 1),
        )
        while (true) {
            var r0 = cur.r0; var c0 = cur.c0; var r1 = cur.r1; var c1 = cur.c1
            for (r in cur.r0..cur.r1) for (c in cur.c0..cur.c1) {
                val b = cellAt(r, c)
                r0 = min(r0, b.r0); c0 = min(c0, b.c0); r1 = max(r1, b.r1); c1 = max(c1, b.c1)
            }
            val next = CellRange(r0, c0, r1, c1)
            if (next == cur) return cur
            cur = next
        }
    }

    // ================= 그리기 =================

    /** 보이는 선과 제목 칸 (화면과 PDF가 같이 쓴다). 칸 사이의 선은 이어진 것끼리 한 줄로 합친다 */
    fun geometry(): TableGeometry {
        val lines = ArrayList<TableLine>()
        for (g in 1 until rows) {
            val heavy = header.row && g == 1
            var c = 0
            while (c < cols) {
                if (id(g - 1, c) != id(g, c)) {
                    val start = c
                    while (c < cols && id(g - 1, c) != id(g, c)) c++
                    lines.add(TableLine(colX[start], rowY[g], colX[c], rowY[g], heavy))
                } else c++
            }
        }
        for (g in 1 until cols) {
            val heavy = header.col && g == 1
            var r = 0
            while (r < rows) {
                if (id(r, g - 1) != id(r, g)) {
                    val start = r
                    while (r < rows && id(r, g - 1) != id(r, g)) r++
                    lines.add(TableLine(colX[g], rowY[start], colX[g], rowY[r], heavy))
                } else r++
            }
        }
        val shades = ArrayList<TableShade>()
        val seen = HashSet<Int>()
        fun shade(r: Int, c: Int) {
            if (!seen.add(id(r, c))) return
            val b = cellAt(r, c)
            shades.add(TableShade(colX[b.c0], rowY[b.r0], colX[b.c1 + 1], rowY[b.r1 + 1]))
        }
        if (header.row) for (c in 0 until cols) shade(0, c)
        if (header.col) for (r in 0 until rows) shade(r, 0)
        return TableGeometry(lines, shades)
    }

    // ================= 누른 자리 =================

    /**
     * (x, y) 가까이([eps] 안)에 있는 보이는 안쪽 선 (합쳐진 셀 속에 숨은 선은 아님). 여럿이면 가장 가까운 것.
     * 선을 끌어 옮기거나 표 지우개로 지울 때 쓴다
     */
    fun lineAt(x: Float, y: Float, eps: Float): TableLineHit? {
        if (x < -eps || y < -eps || x > width + eps || y > height + eps) return null
        var best: TableLineHit? = null
        var bestD = Float.MAX_VALUE
        val c = colAt(x)
        for (g in 1 until rows) {
            val d = abs(y - rowY[g])
            if (d <= eps && d < bestD && id(g - 1, c) != id(g, c)) { best = TableLineHit(true, g); bestD = d }
        }
        val r = rowAt(y)
        for (g in 1 until cols) {
            val d = abs(x - colX[g])
            if (d <= eps && d < bestD && id(r, g - 1) != id(r, g)) { best = TableLineHit(false, g); bestD = d }
        }
        return best
    }

    // ================= 고치기 =================

    /** 안쪽 선 하나를 끌어 옮긴다 (이웃 선과 [minSize] 이상 떨어지게). 가로선이면 [at]은 y, 세로선이면 x (표 좌표) */
    fun moveLine(hit: TableLineHit, at: Float, minSize: Float): InkTable? {
        if (!hit.horizontal) return transposed().moveLine(TableLineHit(true, hit.index), at, minSize)?.transposed()
        val g = hit.index
        if (g !in 1 until rows) return null
        val lo = rowY[g - 1] + minSize
        val hi = rowY[g + 1] - minSize
        if (lo > hi) return null
        val p = at.coerceIn(lo, hi)
        val h = rowH.copyOf()
        h[g - 1] = p - rowY[g - 1]
        h[g] = rowY[g + 1] - p
        if (h[g - 1] == rowH[g - 1]) return null
        return InkTable(colW, h, ids, header)
    }

    /**
     * 표 그리기: 가로선 하나를 y=[at]에, x가 [from]~[to]인 구간으로 긋는다 (세로선은 [splitVertical]).
     * 그은 줄이 지나는 셀(그 셀 너비의 [COVER] 이상 덮인 셀)은 선 위아래로 둘로 나뉜다. 선은 셀 가장자리까지 이어진다.
     * 이미 있는 선에서 [eps] 안이면 새 선을 만들지 않고 그 선을 쓴다. 나뉜 셀이 없으면 null
     */
    fun splitHorizontal(at: Float, from: Float, to: Float, eps: Float): InkTable? {
        if (at <= 0f || at >= height) return null
        val lo = min(from, to)
        val hi = max(from, to)
        val i = rowAt(at)
        // 가까운 경계에 붙이거나, 줄 하나를 둘로 가른다
        var g = -1
        if (at - rowY[i] <= eps) g = i
        else if (rowY[i + 1] - at <= eps) g = i + 1
        val raw: InkTable
        if (g >= 0) {
            if (g == 0 || g == rows) return null
            raw = this
        } else {
            val h = FloatArray(rows + 1)
            val map = IntArray(rows + 1)
            for (r in 0..rows) {
                val src = if (r <= i) r else r - 1
                map[r] = src
                h[r] = when (r) { i -> at - rowY[i]; i + 1 -> rowY[i + 1] - at; else -> rowH[src] }
            }
            raw = InkTable(colW, h, remap(map, IntArray(cols) { it }), header)
            g = i + 1
        }
        val a = raw.ids.copyOf()
        var next = (a.maxOrNull() ?: 0) + 1
        var split = false
        val done = HashSet<Int>()
        for (c in 0 until raw.cols) {
            val id = raw.id(g - 1, c)
            if (id != raw.id(g, c) || !done.add(id)) continue
            val b = raw.cellAt(g - 1, c)
            val x0 = raw.colX[b.c0]
            val x1 = raw.colX[b.c1 + 1]
            val overlap = min(hi, x1) - max(lo, x0)
            if (overlap < COVER * (x1 - x0)) continue
            // 선 아래쪽 칸들만 새 셀로
            for (r in g..b.r1) for (cc in b.c0..b.c1) a[r * raw.cols + cc] = next
            next++
            split = true
        }
        if (!split) return null
        return make(raw.colW, raw.rowH, a, header, compact = false)
    }

    /** 표 그리기: 세로선 하나를 x=[at]에, y가 [from]~[to]인 구간으로 긋는다 */
    fun splitVertical(at: Float, from: Float, to: Float, eps: Float): InkTable? =
        transposed().splitHorizontal(at, from, to, eps)?.transposed()

    /**
     * 표 지우개: 가로선을 y=[at] 근처([eps] 안)에서 x가 [from]~[to]인 구간만큼 지운다 (세로선은 [eraseVertical]).
     * 그은 길이가 그 칸 너비의 [COVER] 이상 덮은 칸의 선 토막만 지우고, 지운 토막의 위아래 셀은 하나로 합쳐진다.
     * 가까운 가로선만 지우므로 지나가는 세로선은 그대로다. 지울 선이 없으면 null
     */
    fun eraseHorizontal(at: Float, from: Float, to: Float, eps: Float): InkTable? {
        val lo = min(from, to)
        val hi = max(from, to)
        // 가까운 안쪽 가로선 중 그은 구간 아래에 보이는 토막이 있는 가장 가까운 것
        var g = -1
        var bestD = Float.MAX_VALUE
        for (k in 1 until rows) {
            val d = abs(rowY[k] - at)
            if (d <= eps && d < bestD && coveredCols(k, lo, hi).isNotEmpty()) { g = k; bestD = d }
        }
        if (g < 0) return null
        var cur = this
        for (c in coveredCols(g, lo, hi)) {
            if (cur.id(g - 1, c) == cur.id(g, c)) continue
            val a = cur.cellAt(g - 1, c)
            val b = cur.cellAt(g, c)
            cur = cur.merge(CellRange(min(a.r0, b.r0), min(a.c0, b.c0), max(a.r1, b.r1), max(a.c1, b.c1))) ?: continue
        }
        if (cur === this) return null
        // 줄을 지웠으니 어디에도 보이지 않게 된 선은 격자에서도 뺀다
        return make(cur.colW, cur.rowH, cur.ids, header)
    }

    /** 표 지우개: 세로선을 x=[at] 근처에서 y가 [from]~[to]인 구간만큼 지운다 */
    fun eraseVertical(at: Float, from: Float, to: Float, eps: Float): InkTable? =
        transposed().eraseHorizontal(at, from, to, eps)?.transposed()

    /** 가로선 [g](줄 g-1과 g 사이)에서 x가 [lo]~[hi]에 [COVER] 이상 덮이고 위아래가 다른 셀인 칸들 */
    private fun coveredCols(g: Int, lo: Float, hi: Float): List<Int> = (0 until cols).filter { c ->
        id(g - 1, c) != id(g, c) && min(hi, colX[c + 1]) - max(lo, colX[c]) >= COVER * colW[c]
    }

    /** 셀 합치기: [range]를 셀 경계까지 넓힌 범위를 한 셀로. 이미 한 셀이면 null */
    fun merge(range: CellRange): InkTable? {
        val e = expand(range)
        if (e.rows * e.cols == 1) return null
        val first = id(e.r0, e.c0)
        var same = true
        val a = ids.copyOf()
        for (r in e.r0..e.r1) for (c in e.c0..e.c1) {
            if (a[r * cols + c] != first) same = false
            a[r * cols + c] = first
        }
        if (same) return null
        return make(colW, rowH, a, header, compact = false)
    }

    /** 셀 나누기(합친 셀 풀기): [range] 안의 합쳐진 셀을 칸마다 따로 나눈다. 합쳐진 셀이 없으면 null */
    fun unmerge(range: CellRange): InkTable? {
        val e = expand(range)
        if (!hasMerged(e)) return null
        val a = ids.copyOf()
        var next = (a.maxOrNull() ?: 0) + 1
        for (r in e.r0..e.r1) for (c in e.c0..e.c1) a[r * cols + c] = next++
        return make(colW, rowH, a, header, compact = false)
    }

    /**
     * 셀 나누기(한 칸을 [nr]줄 × [nc]칸으로): 합쳐지지 않은 칸 (r, c)를 같은 크기로 나눈다.
     * 옆 칸들은 새 선을 가로질러 합쳐진 채로 남는다. 나뉜 조각이 [minSize]보다 작아지면 null
     */
    fun subdivide(r: Int, c: Int, nr: Int, nc: Int, minSize: Float): InkTable? {
        if (nr < 1 || nc < 1 || nr * nc < 2) return null
        val b = cellAt(r, c)
        if (b.rows * b.cols != 1) return null
        if (rowH[r] / nr < minSize || colW[c] / nc < minSize) return null
        val newRows = rows + nr - 1
        val newCols = cols + nc - 1
        val rowMap = IntArray(newRows) { i -> if (i < r) i else if (i < r + nr) r else i - nr + 1 }
        val colMap = IntArray(newCols) { j -> if (j < c) j else if (j < c + nc) c else j - nc + 1 }
        val h = FloatArray(newRows) { i -> if (rowMap[i] == r) rowH[r] / nr else rowH[rowMap[i]] }
        val w = FloatArray(newCols) { j -> if (colMap[j] == c) colW[c] / nc else colW[colMap[j]] }
        val a = remap(rowMap, colMap)
        var next = (ids.maxOrNull() ?: 0) + 1
        for (i in 0 until nr) for (j in 0 until nc) a[(r + i) * newCols + (c + j)] = next++
        return make(w, h, a, header, compact = false)
    }

    /**
     * 줄 추가: [ref]줄의 위([below]가 false) 또는 아래에 새 줄을 [height] 높이로 넣는다. 칸 합침 모양은 [ref]줄을 따르고,
     * 위아래로 합쳐진 셀 한가운데에 넣으면 그 셀이 늘어난다
     */
    fun insertRow(ref: Int, below: Boolean, height: Float = rowH.getOrElse(ref) { 0f }): InkTable? {
        if (ref !in 0 until rows || height <= 0f) return null
        val at = if (below) ref + 1 else ref
        val newRows = rows + 1
        val h = FloatArray(newRows) { i -> if (i < at) rowH[i] else if (i == at) height else rowH[i - 1] }
        val a = IntArray(newRows * cols)
        var next = (ids.maxOrNull() ?: 0) + 1
        val fresh = HashMap<Int, Int>()
        for (r in 0 until newRows) for (c in 0 until cols) {
            a[r * cols + c] = when {
                r < at -> id(r, c)
                r > at -> id(r - 1, c)
                // 새 줄: 위아래로 합쳐진 셀을 가로지르면 그 셀 번호, 아니면 [ref]줄의 합침 모양을 본뜬 새 셀
                at in 1 until rows && id(at - 1, c) == id(at, c) -> id(at, c)
                else -> fresh.getOrPut(id(ref, c)) { next++ }
            }
        }
        return make(colW, h, a, header, compact = false)
    }

    /** 칸 추가: [ref]칸의 왼쪽([right]가 false) 또는 오른쪽에 새 칸을 넣는다 ([insertRow]와 같은 규칙) */
    fun insertCol(ref: Int, right: Boolean, width: Float = colW.getOrElse(ref) { 0f }): InkTable? =
        transposed().insertRow(ref, right, width)?.transposed()

    /** 줄 지우기: [r0]~[r1]줄을 없앤다. 모든 줄이 사라지면 null */
    fun deleteRows(r0: Int, r1: Int): InkTable? {
        val keep = (0 until rows).filter { it !in r0..r1 }
        if (keep.isEmpty() || keep.size == rows) return null
        val a = IntArray(keep.size * cols)
        for ((nr, r) in keep.withIndex()) for (c in 0 until cols) a[nr * cols + c] = id(r, c)
        return make(colW, FloatArray(keep.size) { rowH[keep[it]] }, a, header)
    }

    /** 칸 지우기: [c0]~[c1]칸을 없앤다. 모든 칸이 사라지면 null */
    fun deleteCols(c0: Int, c1: Int): InkTable? = transposed().deleteRows(c0, c1)?.transposed()

    /** [r0]~[r1]줄의 높이를 같게 */
    fun equalizeRows(r0: Int, r1: Int): InkTable? {
        if (r1 <= r0) return null
        val avg = (rowY[r1 + 1] - rowY[r0]) / (r1 - r0 + 1)
        val h = rowH.copyOf()
        for (r in r0..r1) h[r] = avg
        if (h.contentEquals(rowH)) return null
        return InkTable(colW, h, ids, header)
    }

    /** [c0]~[c1]칸의 너비를 같게 */
    fun equalizeCols(c0: Int, c1: Int): InkTable? = transposed().equalizeRows(c0, c1)?.transposed()

    /** 제목 줄 바꾸기 */
    fun withHeader(h: TableHeader): InkTable? = if (h == header) null else InkTable(colW, rowH, ids, h)

    // ================= 저장 =================

    /** PDF에 저장하는 글: 제목 줄 글자:칸 너비들:줄 높이들:칸마다 셀 번호들 (쉼표로 이은 숫자) */
    fun encode(): String = buildString {
        append(header.code).append(':')
        colW.forEachIndexed { i, v -> if (i > 0) append(','); append(r2(v)) }
        append(':')
        rowH.forEachIndexed { i, v -> if (i > 0) append(','); append(r2(v)) }
        append(':')
        ids.forEachIndexed { i, v -> if (i > 0) append(','); append(v) }
    }

    // ---- 내부 ----

    /** 줄과 칸을 맞바꾼 표 (세로 연산을 가로 연산으로 풀 때) */
    private fun transposed(): InkTable {
        val a = IntArray(ids.size)
        for (r in 0 until rows) for (c in 0 until cols) a[c * rows + r] = ids[r * cols + c]
        return InkTable(rowH, colW, a, header.transposed())
    }

    /** 새 격자의 칸마다 옛 격자의 어느 칸을 따르는지 ([rowMap], [colMap])로 셀 번호를 옮긴다 */
    private fun remap(rowMap: IntArray, colMap: IntArray): IntArray {
        val nc = colMap.size
        val a = IntArray(rowMap.size * nc)
        for (r in rowMap.indices) for (c in 0 until nc) a[r * nc + c] = ids[rowMap[r] * cols + colMap[c]]
        return a
    }

    override fun equals(other: Any?) = other is InkTable && header == other.header &&
        colW.contentEquals(other.colW) && rowH.contentEquals(other.rowH) && ids.contentEquals(other.ids)

    override fun hashCode() = (header.hashCode() * 31 + colW.contentHashCode()) * 31 + rowH.contentHashCode() + ids.contentHashCode()

    companion object {
        /** 줄을 긋거나 지울 때 셀 너비의 이만큼 이상 지나야 그 칸에 해당한다 */
        private const val COVER = 0.4f

        /** 줄 높이가 모두 같은 [rows]줄 × [cols]칸 표 (크기는 표 좌표 pt) */
        fun create(rows: Int, cols: Int, width: Float, height: Float, header: TableHeader): InkTable {
            val r = rows.coerceAtLeast(1)
            val c = cols.coerceAtLeast(1)
            return InkTable(FloatArray(c) { width / c }, FloatArray(r) { height / r }, IntArray(r * c) { it }, header)
        }

        /** [encode]한 글에서 표를 읽는다 (읽지 못하면 null). 셀이 직사각형이 아니면 칸마다 따로 나눈 셀로 */
        fun decode(code: String): InkTable? = try {
            val p = code.split(':')
            if (p.size != 4) null
            else {
                val w = p[1].split(',').map { it.toFloat() }.toFloatArray()
                val h = p[2].split(',').map { it.toFloat() }.toFloatArray()
                val a = p[3].split(',').map { it.toInt() }.toIntArray()
                if (w.isEmpty() || h.isEmpty() || a.size != w.size * h.size || w.any { it <= 0f } || h.any { it <= 0f }) null
                else {
                    val t = InkTable(w, h, a, TableHeader.of(p[0].firstOrNull()))
                    if (t.isValid()) make(w, h, a, t.header) else InkTable(w, h, IntArray(a.size) { it }, t.header)
                }
            }
        } catch (e: Exception) {
            null
        }

        /**
         * 셀 번호를 0부터 차례로 다시 매긴다. [compact]면 합쳐진 셀 속에 숨은 안쪽 선(양쪽이 늘 같은 셀)도 없앤다
         * (줄을 지웠을 때만: 셀을 합치고 다시 나누면 처음 모양으로 돌아와야 하므로 그때는 숨은 선을 남긴다)
         */
        private fun make(colW: FloatArray, rowH: FloatArray, ids: IntArray, header: TableHeader, compact: Boolean = true): InkTable {
            var w = colW
            var h = rowH
            var a = ids
            var changed = compact
            while (changed) {
                changed = false
                var r = 1
                while (r < h.size) {
                    val nc = w.size
                    var same = true
                    for (c in 0 until nc) if (a[(r - 1) * nc + c] != a[r * nc + c]) { same = false; break }
                    if (!same) { r++; continue }
                    // r줄을 윗줄에 합친다
                    val oldH = h
                    val oldA = a
                    val k = r
                    h = FloatArray(oldH.size - 1) { i -> if (i < k - 1) oldH[i] else if (i == k - 1) oldH[k - 1] + oldH[k] else oldH[i + 1] }
                    a = IntArray(h.size * nc) { i ->
                        val rr = i / nc
                        oldA[(if (rr < k) rr else rr + 1) * nc + i % nc]
                    }
                    changed = true
                }
                var c = 1
                while (c < w.size) {
                    val nr = h.size
                    val nc = w.size
                    var same = true
                    for (rr in 0 until nr) if (a[rr * nc + c - 1] != a[rr * nc + c]) { same = false; break }
                    if (!same) { c++; continue }
                    val oldW = w
                    val oldA = a
                    val k = c
                    w = FloatArray(nc - 1) { i -> if (i < k - 1) oldW[i] else if (i == k - 1) oldW[k - 1] + oldW[k] else oldW[i + 1] }
                    a = IntArray(nr * w.size) { i ->
                        val rr = i / w.size
                        val cc = i % w.size
                        oldA[rr * nc + (if (cc < k) cc else cc + 1)]
                    }
                    changed = true
                }
            }
            val renumber = HashMap<Int, Int>()
            val out = IntArray(a.size) { i -> renumber.getOrPut(a[i]) { renumber.size } }
            return InkTable(w, h, out, header)
        }

        private fun r2(v: Float): String = ((v * 100f).roundToInt() / 100f).toString()
    }

    /** 셀마다 모두 직사각형인가 (저장된 글을 믿어도 되는지) */
    private fun isValid(): Boolean {
        for ((id, b) in bounds) {
            for (r in b.r0..b.r1) for (c in b.c0..b.c1) if (id(r, c) != id) return false
        }
        return true
    }
}

/**
 * 표 상자(쪽 좌표의 네 모서리: 왼쪽 위·오른쪽 위·오른쪽 아래·왼쪽 아래)와 표 좌표 ([InkTable]의 pt) 사이의 변환.
 * 상자는 돌리거나 한쪽으로 늘여 평행사변형이 될 수 있으므로 일반 아핀 변환으로 푼다
 */
class TableFrame(corners: FloatArray, private val tw: Float, private val th: Float) {
    private val ox = corners[0]
    private val oy = corners[1]
    // 표 좌표 x(y)가 한 단위 늘 때 쪽 좌표가 움직이는 방향과 크기
    private val ux = (corners[2] - corners[0]) / tw
    private val uy = (corners[3] - corners[1]) / tw
    private val vx = (corners[6] - corners[0]) / th
    private val vy = (corners[7] - corners[1]) / th
    private val det = ux * vy - vx * uy

    /** 표 좌표 한 단위가 쪽에서 몇 pt인지 (가로·세로) */
    val scaleX = kotlin.math.hypot(ux, uy)
    val scaleY = kotlin.math.hypot(vx, vy)

    fun pageX(x: Float, y: Float) = ox + ux * x + vx * y
    fun pageY(x: Float, y: Float) = oy + uy * x + vy * y

    /** 표 크기가 [w]x[h](표 좌표)로 바뀔 때의 상자 네 모서리 (왼쪽 위와 한 단위의 크기·방향은 그대로) */
    fun corners(w: Float, h: Float) = floatArrayOf(
        pageX(0f, 0f), pageY(0f, 0f), pageX(w, 0f), pageY(w, 0f), pageX(w, h), pageY(w, h), pageX(0f, h), pageY(0f, h),
    )

    /** 쪽 좌표 → 표 좌표 (상자가 찌그러져 풀 수 없으면 null) */
    fun toLocal(px: Float, py: Float): FloatArray? {
        if (kotlin.math.abs(det) < 1e-9f) return null
        val dx = px - ox
        val dy = py - oy
        return floatArrayOf((dx * vy - vx * dy) / det, (ux * dy - uy * dx) / det)
    }
}
