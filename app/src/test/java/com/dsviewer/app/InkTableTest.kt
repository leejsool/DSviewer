package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 표 모양 계산: 만들기, 줄·칸 추가·삭제, 셀 합치기·나누기, 줄 긋기·지우기, 선 옮기기, 저장 글 */
class InkTableTest {

    private fun table(rows: Int, cols: Int, w: Float = 300f, h: Float = 200f, header: TableHeader = TableHeader.NONE) =
        InkTable.create(rows, cols, w, h, header)

    /** 셀마다 직사각형이고 크기가 맞는지 */
    private fun check(t: InkTable, compact: Boolean = false) {
        for (r in 0 until t.rows) for (c in 0 until t.cols) {
            val b = t.cellAt(r, c)
            for (rr in b.r0..b.r1) for (cc in b.c0..b.c1) assertEquals("셀이 직사각형이 아님 ($r,$c)", t.id(r, c), t.id(rr, cc))
        }
        assertEquals(t.colW.sum(), t.width, 0.01f)
        assertEquals(t.rowH.sum(), t.height, 0.01f)
        assertTrue(t.colW.all { it > 0f } && t.rowH.all { it > 0f })
        if (!compact) return
        // 숨은 선이 없다: 안쪽 선은 어디선가는 보인다
        for (g in 1 until t.rows) assertTrue("가로선 $g 가 숨음", (0 until t.cols).any { t.id(g - 1, it) != t.id(g, it) })
        for (g in 1 until t.cols) assertTrue("세로선 $g 가 숨음", (0 until t.rows).any { t.id(it, g - 1) != t.id(it, g) })
    }

    @Test fun createGivesEqualCells() {
        val t = table(3, 4)
        check(t)
        assertEquals(3, t.rows); assertEquals(4, t.cols)
        assertEquals(12, t.cellCount)
        assertEquals(75f, t.colW[0], 0.001f)
        assertEquals(200f / 3, t.rowH[2], 0.001f)
    }

    @Test fun positionsFindRowsAndCols() {
        val t = table(2, 3)
        assertEquals(0, t.rowAt(10f)); assertEquals(1, t.rowAt(150f)); assertEquals(1, t.rowAt(9999f))
        assertEquals(0, t.colAt(50f)); assertEquals(2, t.colAt(250f)); assertEquals(0, t.colAt(-5f))
    }

    // ---- 합치기·나누기 ----

    @Test fun mergeMakesOneCell() {
        val t = table(3, 3).merge(CellRange(0, 0, 1, 1))!!
        check(t, false)
        assertEquals(6, t.cellCount)
        assertEquals(CellRange(0, 0, 1, 1), t.cellAt(1, 1))
    }

    @Test fun mergeExpandsToWholeCells() {
        // 가운데 두 칸을 합친 셀이 있을 때 한 칸만 걸쳐 합쳐도 그 셀 전체가 들어간다
        val a = table(3, 3).merge(CellRange(1, 0, 1, 1))!!
        val b = a.merge(CellRange(0, 1, 1, 1))!!
        check(b)
        assertEquals(CellRange(0, 0, 1, 1), b.cellAt(0, 0))
    }

    @Test fun mergeSingleCellDoesNothing() {
        assertNull(table(2, 2).merge(CellRange(0, 0, 0, 0)))
        val m = table(2, 2).merge(CellRange(0, 0, 1, 1))!!
        assertNull(m.merge(CellRange(0, 0, 1, 1)))
    }

    @Test fun mergingEverythingLeavesOneCell() {
        val t = table(3, 3).merge(CellRange(0, 0, 2, 2))!!
        check(t)
        assertEquals(1, t.cellCount)
        assertEquals(3, t.rows)       // 숨은 격자선은 남겨 둔다 (다시 나누면 처음 모양)
        assertEquals(9, t.unmerge(CellRange(0, 0, 2, 2))!!.cellCount)
        assertTrue(t.geometry().lines.isEmpty())
        assertEquals(300f, t.width, 0.01f); assertEquals(200f, t.height, 0.01f)
    }

    @Test fun unmergeRestoresCells() {
        val m = table(3, 3).merge(CellRange(0, 0, 1, 2))!!
        val u = m.unmerge(CellRange(0, 0, 0, 0))!!
        check(u)
        assertEquals(9, u.cellCount)
        assertEquals(table(3, 3), u)    // 합쳤다 풀면 처음과 같다
        assertNull(u.unmerge(CellRange(0, 0, 2, 2)))
    }

    @Test fun unmergeKeepsOtherMergesOutOfRange() {
        val m = table(4, 4).merge(CellRange(0, 0, 1, 1))!!.merge(CellRange(2, 2, 3, 3))!!
        val u = m.unmerge(CellRange(0, 0, 1, 1))!!
        check(u)
        assertEquals(CellRange(2, 2, 3, 3), u.cellAt(3, 3))
        assertEquals(CellRange(0, 0, 0, 0), u.cellAt(0, 0))
    }

    @Test fun subdivideSplitsOneCell() {
        val t = table(2, 2, 200f, 100f).subdivide(0, 0, 2, 3, 5f)!!
        check(t)
        assertEquals(3, t.rows); assertEquals(4, t.cols)
        // 나눈 셀 속 6칸은 모두 따로, 옆 칸은 새 선을 가로질러 합쳐진 채
        assertEquals(CellRange(0, 3, 1, 3), t.cellAt(0, 3))
        assertEquals(CellRange(2, 0, 2, 2), t.cellAt(2, 0))       // 아래 칸은 새 세로선을 가로질러 합쳐진 채
        assertEquals(CellRange(0, 0, 0, 0), t.cellAt(0, 0))
        assertEquals(25f, t.rowH[0], 0.001f)
        assertEquals(100f / 3, t.colW[0], 0.001f)
        assertEquals(100f, t.colW[3], 0.001f)
    }

    @Test fun subdivideRefusesMergedAndTinyCells() {
        val m = table(2, 2).merge(CellRange(0, 0, 0, 1))!!
        assertNull(m.subdivide(0, 0, 1, 2, 5f))
        assertNull(table(2, 2, 20f, 20f).subdivide(0, 0, 1, 3, 5f))
        assertNull(table(2, 2).subdivide(0, 0, 1, 1, 5f))
    }

    // ---- 줄·칸 추가 ----

    @Test fun insertRowBelowAndAbove() {
        val t = table(2, 2, 100f, 100f)
        val below = t.insertRow(0, below = true)!!
        check(below)
        assertEquals(3, below.rows)
        assertEquals(150f, below.height, 0.01f)
        val above = t.insertRow(0, below = false)!!
        check(above)
        assertEquals(3, above.rows)
        assertEquals(50f, above.rowH[0], 0.001f)
    }

    @Test fun insertRowInsideVerticalMergeExtendsIt() {
        val t = table(3, 2).merge(CellRange(0, 0, 1, 0))!!   // 0~1줄에 걸친 왼쪽 셀
        val i = t.insertRow(0, below = true)!!               // 0줄 아래 = 합친 셀 한가운데
        check(i)
        assertEquals(CellRange(0, 0, 2, 0), i.cellAt(1, 0))
        assertEquals(4, i.rows)
    }

    @Test fun insertRowCopiesHorizontalMerge() {
        val t = table(2, 3).merge(CellRange(0, 0, 0, 1))!!
        val i = t.insertRow(0, below = true)!!
        check(i)
        assertEquals(CellRange(0, 0, 0, 1), i.cellAt(0, 0))
        assertEquals(CellRange(1, 0, 1, 1), i.cellAt(1, 1))
    }

    @Test fun insertColLeftAndRight() {
        val t = table(2, 2, 100f, 100f)
        val r = t.insertCol(1, right = true)!!
        check(r)
        assertEquals(3, r.cols); assertEquals(150f, r.width, 0.01f)
        val l = t.insertCol(0, right = false)!!
        check(l)
        assertEquals(50f, l.colW[0], 0.001f)
    }

    // ---- 줄·칸 삭제 ----

    @Test fun deleteRowsAndCols() {
        val t = table(4, 3)
        val d = t.deleteRows(1, 2)!!
        check(d, true)
        assertEquals(2, d.rows)
        assertEquals(100f, d.height, 0.01f)
        val e = t.deleteCols(0, 0)!!
        check(e, true)
        assertEquals(2, e.cols)
        assertNull(t.deleteRows(0, 3))
        assertNull(t.deleteCols(0, 2))
    }

    @Test fun deleteRowShrinksMergedCell() {
        val t = table(3, 2).merge(CellRange(0, 0, 2, 0))!!
        val d = t.deleteRows(1, 1)!!
        check(d)
        assertEquals(CellRange(0, 0, 1, 0), d.cellAt(1, 0))
    }

    @Test fun deleteRowRemovingWholeMergedCellDropsIt() {
        val t = table(3, 2).merge(CellRange(1, 0, 1, 1))!!
        val d = t.deleteRows(1, 1)!!
        check(d)
        assertEquals(4, d.cellCount)
    }

    // ---- 줄 긋기 ----

    @Test fun drawLineAcrossOneCellSplitsIt() {
        // 3×3 표 가운데 칸 한가운데에 가로줄: 그 칸만 위아래로 나뉘고 옆 칸들은 합쳐진 채
        val t = table(3, 3, 300f, 300f)
        val s = t.splitHorizontal(150f, 105f, 195f, 6f)!!
        check(s)
        assertEquals(4, s.rows)
        assertEquals(10, s.cellCount)
        assertEquals(CellRange(1, 0, 2, 0), s.cellAt(1, 0))
        assertEquals(CellRange(1, 1, 1, 1), s.cellAt(1, 1))
        assertEquals(CellRange(2, 1, 2, 1), s.cellAt(2, 1))
        assertEquals(50f, s.rowH[1], 0.001f)
    }

    @Test fun drawLineAcrossWholeTableSplitsAllColumns() {
        val t = table(2, 3, 300f, 200f)
        val s = t.splitHorizontal(50f, -10f, 310f, 6f)!!
        check(s)
        assertEquals(3, s.rows)
        assertEquals(9, s.cellCount)
    }

    @Test fun drawLineSnapsToExistingLine() {
        val t = table(2, 2, 200f, 200f)
        // 이미 있는 가로 경계 근처에 그어도 새 줄은 생기지 않고, 이미 나뉜 셀이라 바뀐 것이 없다
        assertNull(t.splitHorizontal(102f, 0f, 200f, 6f))
        // 합쳐진 셀을 가로지르는 경계에 맞춰 그으면 그 셀이 나뉜다
        val m = t.merge(CellRange(0, 0, 1, 0))!!
        val s = m.splitHorizontal(100f, 0f, 100f, 6f)!!
        check(s)
        assertEquals(CellRange(0, 0, 0, 0), s.cellAt(0, 0))
        assertEquals(CellRange(1, 0, 1, 0), s.cellAt(1, 0))
        assertEquals(2, s.rows)
    }

    @Test fun drawShortLineDoesNothing() {
        val t = table(2, 2, 200f, 200f)
        assertNull(t.splitHorizontal(50f, 10f, 30f, 6f))   // 셀 너비(100)의 40% 미만
        assertNull(t.splitHorizontal(0f, 0f, 200f, 6f))    // 바깥 테두리
        assertNull(t.splitHorizontal(200f, 0f, 200f, 6f))
    }

    @Test fun drawVerticalLine() {
        val t = table(2, 2, 200f, 200f)
        val s = t.splitVertical(50f, 0f, 90f, 6f)!!
        check(s)
        assertEquals(3, s.cols)
        // 위 칸만 나뉘고 아래 칸(0.5 → 1)은 새 선을 가로질러 합쳐진 채
        assertEquals(CellRange(1, 0, 1, 1), s.cellAt(1, 0))
        assertEquals(CellRange(0, 0, 0, 0), s.cellAt(0, 0))
        assertEquals(50f, s.colW[0], 0.001f)
    }

    // ---- 표 지우개 ----

    @Test fun eraseMergesAcrossLine() {
        val t = table(2, 2, 200f, 200f)
        val e = t.eraseAt(50f, 100f, 6f)!!   // 가로선(위쪽 칸 아래) 한 토막
        check(e, true)
        assertEquals(CellRange(0, 0, 1, 0), e.cellAt(0, 0))
        assertEquals(3, e.cellCount)
        val v = t.eraseAt(100f, 150f, 6f)!!  // 세로선 한 토막
        assertEquals(CellRange(1, 0, 1, 1), v.cellAt(1, 0))
    }

    @Test fun eraseMissesWhenNoLineNear() {
        val t = table(2, 2, 200f, 200f)
        assertNull(t.eraseAt(50f, 50f, 6f))
        val m = t.merge(CellRange(0, 0, 1, 0))!!
        assertNull(m.eraseAt(50f, 100f, 6f))   // 합쳐진 셀 속 선은 이미 없다
    }

    @Test fun eraseLastLinesRemovesLine() {
        var t = table(2, 2, 200f, 200f)
        t = t.eraseAt(50f, 100f, 6f)!!
        t = t.eraseAt(150f, 100f, 6f)!!
        check(t, true)
        assertEquals(1, t.rows)   // 가로선이 모두 지워지면 줄이 하나로
        assertEquals(2, t.cols)
    }

    // ---- 선 옮기기 ----

    @Test fun moveLineChangesSpacingOnly() {
        val t = table(3, 3, 300f, 300f)
        val m = t.moveLine(TableLineHit(true, 1), 130f, 20f)!!
        check(m)
        assertEquals(130f, m.rowY[1], 0.001f)
        assertEquals(300f, m.height, 0.001f)
        val v = t.moveLine(TableLineHit(false, 2), 150f, 20f)!!
        assertEquals(150f, v.colX[2], 0.001f)
    }

    @Test fun moveLineStaysBetweenNeighbours() {
        val t = table(3, 3, 300f, 300f)
        assertEquals(190f, t.moveLine(TableLineHit(true, 1), 9999f, 10f)!!.rowY[1], 0.001f)
        assertEquals(10f, t.moveLine(TableLineHit(true, 1), -50f, 10f)!!.rowY[1], 0.001f)
        assertNull(t.moveLine(TableLineHit(true, 0), 50f, 10f))
        assertNull(t.moveLine(TableLineHit(true, 3), 50f, 10f))
    }

    @Test fun lineAtFindsVisibleLinesOnly() {
        val t = table(2, 2, 200f, 200f).merge(CellRange(0, 0, 1, 0))!!
        assertNull(t.lineAt(50f, 100f, 8f))                              // 합쳐진 셀 속
        assertEquals(TableLineHit(true, 1), t.lineAt(150f, 98f, 8f))     // 오른쪽 칸 사이 가로선
        assertEquals(TableLineHit(false, 1), t.lineAt(103f, 40f, 8f))
        assertNull(t.lineAt(150f, 50f, 8f))
        assertNull(t.lineAt(500f, 100f, 8f))
    }

    // ---- 같게·제목 ----

    @Test fun equalizeRows() {
        val t = table(3, 2, 100f, 300f).moveLine(TableLineHit(true, 1), 50f, 10f)!!
        val e = t.equalizeRows(0, 2)!!
        assertEquals(100f, e.rowH[0], 0.001f)
        assertNull(e.equalizeRows(0, 2))
        assertNull(e.equalizeRows(1, 1))
    }

    @Test fun equalizeCols() {
        val t = table(2, 3, 300f, 100f).moveLine(TableLineHit(false, 1), 30f, 10f)!!
        val e = t.equalizeCols(0, 1)!!
        assertEquals(e.colW[0], e.colW[1], 0.001f)
        assertEquals(t.width, e.width, 0.001f)
    }

    @Test fun headerGeometry() {
        val none = table(3, 3).geometry()
        assertTrue(none.shades.isEmpty())
        assertTrue(none.lines.none { it.heavy })
        val row = table(3, 3, header = TableHeader.ROW).geometry()
        assertEquals(3, row.shades.size)
        assertEquals(1, row.lines.count { it.heavy })
        val both = table(3, 3, header = TableHeader.BOTH).geometry()
        assertEquals(5, both.shades.size)           // 첫 줄 3칸 + 첫 칸 나머지 2칸 (맨 위 왼쪽은 한 번만)
        assertEquals(2, both.lines.count { it.heavy })
        val col = table(3, 3, header = TableHeader.COL).geometry()
        assertEquals(3, col.shades.size)
    }

    @Test fun geometryJoinsRunsAndHidesMergedLines() {
        val g = table(2, 3, 300f, 200f).geometry()
        assertEquals(1 + 2, g.lines.size)           // 가로선 한 줄(세 칸을 이은 것) + 세로선 둘
        val m = table(2, 3, 300f, 200f).merge(CellRange(0, 0, 1, 0))!!.geometry()
        assertEquals(1 + 2, m.lines.size)           // 가로선은 오른쪽 두 칸만 (왼쪽 합친 셀 속은 숨음), 세로선은 둘
        assertEquals(100f, m.lines.first { it.y0 == it.y1 }.x0, 0.001f)
    }

    @Test fun withHeaderChanges() {
        val t = table(2, 2)
        assertNull(t.withHeader(TableHeader.NONE))
        assertEquals(TableHeader.COL, t.withHeader(TableHeader.COL)!!.header)
    }

    // ---- 저장 글 ----

    @Test fun encodeDecodeRoundTrip() {
        val t = table(4, 3, 301f, 203.5f, TableHeader.BOTH)
            .merge(CellRange(0, 1, 1, 2))!!
            .splitHorizontal(160f, 0f, 90f, 6f)!!
        val back = InkTable.decode(t.encode())
        assertNotNull(back)
        check(back!!)
        assertEquals(t.rows, back.rows); assertEquals(t.cols, back.cols)
        assertEquals(t.header, back.header)
        assertEquals(t.cellCount, back.cellCount)
        for (r in 0 until t.rows) for (c in 0 until t.cols) assertEquals(t.cellAt(r, c), back.cellAt(r, c))
        assertEquals(t.width, back.width, 0.1f)
    }

    @Test fun decodeRejectsGarbageAndRepairsBrokenCells() {
        assertNull(InkTable.decode(""))
        assertNull(InkTable.decode("R:10,10:10:0"))            // 칸 수가 안 맞음
        assertNull(InkTable.decode("N:10,-5:10:0,1"))          // 음수 크기
        // 직사각형이 아닌 셀 번호는 칸마다 따로
        val t = InkTable.decode("N:10,10,10:10,10,10:0,0,1,1,0,2,3,4,5")
        assertNotNull(t)
        assertEquals(9, t!!.cellCount)
    }

    // ---- 상자와 표 좌표 ----

    @Test fun frameMapsAxisAlignedBox() {
        val f = TableFrame(floatArrayOf(10f, 20f, 110f, 20f, 110f, 70f, 10f, 70f), 200f, 100f)
        assertEquals(0.5f, f.scaleX, 0.0001f)
        assertEquals(0.5f, f.scaleY, 0.0001f)
        val loc = f.toLocal(60f, 45f)!!
        assertEquals(100f, loc[0], 0.001f); assertEquals(50f, loc[1], 0.001f)
        assertEquals(60f, f.pageX(100f, 50f), 0.001f); assertEquals(45f, f.pageY(100f, 50f), 0.001f)
    }

    @Test fun frameMapsRotatedBox() {
        // 90도 돌린 표: 가로 방향이 쪽의 아래쪽, 세로 방향이 쪽의 왼쪽
        val f = TableFrame(floatArrayOf(50f, 0f, 50f, 100f, 0f, 100f, 0f, 0f), 100f, 50f)
        val loc = f.toLocal(25f, 50f)!!
        assertEquals(50f, loc[0], 0.001f); assertEquals(25f, loc[1], 0.001f)
    }

    @Test fun frameCornersFollowNewSize() {
        val f = TableFrame(floatArrayOf(10f, 20f, 110f, 20f, 110f, 70f, 10f, 70f), 200f, 100f)
        val c = f.corners(200f, 140f)           // 줄이 늘어 표가 40 커짐 → 상자는 쪽에서 20 커진다
        assertEquals(90f, c[5], 0.001f); assertEquals(110f, c[4], 0.001f)
    }

    @Test fun frameOfDegenerateBoxDoesNotSolve() {
        val f = TableFrame(floatArrayOf(0f, 0f, 10f, 0f, 10f, 0f, 0f, 0f), 10f, 10f)
        assertNull(f.toLocal(1f, 1f))
    }

    @Test fun neverEditsInPlace() {
        val t = table(3, 3)
        val code = t.encode()
        t.merge(CellRange(0, 0, 1, 1)); t.insertRow(0, true); t.deleteRows(0, 0); t.splitHorizontal(50f, 0f, 300f, 6f)
        assertEquals(code, t.encode())
        assertFalse(t.merge(CellRange(0, 0, 0, 0)) != null)
    }
}
