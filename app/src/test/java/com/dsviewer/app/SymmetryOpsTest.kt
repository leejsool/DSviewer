package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 올가미 ▸ 대칭: 복사·이동·접기와 원본·결과의 실선·점선 */
class SymmetryOpsTest {

    private val eps = 1e-3f

    private fun stroke(vararg xy: Float, tool: Tool = Tool.PEN): Stroke =
        Stroke(tool, 0xFF000000.toInt(), 2f).apply { for (i in 0 until xy.size / 2) add(xy[i * 2], xy[i * 2 + 1], 0.5f) }

    // 세로축 x = 10 (A=(10,0) → B=(10,100))
    private fun build(src: List<Stroke>, mode: SymMode, orig: OrigStyle = OrigStyle.NONE, dashed: Boolean = false, keep: Int = 1) =
        SymmetryOps.build(src, mode, orig, dashed, keep, 10f, 0f, 10f, 100f)

    @Test fun copyAddsOnlyTheMirroredCopyAndLeavesTheOriginal() {
        val s = stroke(2f, 5f, 4f, 20f)
        val r = build(listOf(s), SymMode.COPY)
        assertTrue(r.removed.isEmpty())
        assertEquals(1, r.added.size)
        // x=2 → 18, x=4 → 16
        assertEquals(18f, r.added[0].x(0), eps)
        assertEquals(16f, r.added[0].x(1), eps)
        assertEquals(5f, r.added[0].y(0), eps)
        // 원본은 그대로
        assertEquals(2f, s.x(0), eps)
        assertSame(r.added[0], r.selectAfter[0])
    }

    @Test fun copyIgnoresTheOrigStyleChoice() {
        val s = stroke(2f, 5f, 4f, 20f)
        for (o in OrigStyle.values()) {
            val r = build(listOf(s), SymMode.COPY, orig = o)
            assertTrue(r.removed.isEmpty())
            assertEquals(1, r.added.size)
            assertFalse(r.added[0].dashed)
        }
    }

    @Test fun dashedResultMakesADashedFeltStroke() {
        val r = build(listOf(stroke(2f, 5f, 4f, 20f)), SymMode.COPY, dashed = true)
        assertTrue(r.added[0].dashed)
        assertEquals(PenStyle.FELT, r.added[0].pen)
        assertEquals(18f, r.added[0].x(0), eps)
    }

    @Test fun moveWithNoOriginalReplacesTheStroke() {
        val s = stroke(2f, 5f, 4f, 20f)
        val r = build(listOf(s), SymMode.MOVE, orig = OrigStyle.NONE)
        assertEquals(listOf(s), r.removed)
        assertEquals(1, r.added.size)
        assertEquals(18f, r.added[0].x(0), eps)
    }

    @Test fun moveWithDashedOriginalLeavesADashedGhostUnderTheResult() {
        val s = stroke(2f, 5f, 4f, 20f)
        val r = build(listOf(s), SymMode.MOVE, orig = OrigStyle.DASHED)
        assertEquals(listOf(s), r.removed)
        assertEquals(2, r.added.size)
        // 먼저 점선 원본, 그 위에 실선 결과
        assertTrue(r.added[0].dashed)
        assertEquals(2f, r.added[0].x(0), eps)
        assertFalse(r.added[1].dashed)
        assertEquals(18f, r.added[1].x(0), eps)
        assertSame(r.added[1], r.selectAfter[0])
    }

    @Test fun moveWithSolidOriginalKeepsTheOriginalInPlace() {
        val s = stroke(2f, 5f, 4f, 20f)
        val r = build(listOf(s), SymMode.MOVE, orig = OrigStyle.SOLID)
        assertTrue(r.removed.isEmpty())
        assertEquals(1, r.added.size)
    }

    @Test fun dashedOriginalOfANonDashableStrokeIsTreatedAsGone() {
        // 형광펜은 점선으로 바꿀 수 없다: 원본을 없애고 결과만 넣는다
        val hl = stroke(2f, 5f, 4f, 20f, tool = Tool.HIGHLIGHTER)
        val r = build(listOf(hl), SymMode.MOVE, orig = OrigStyle.DASHED)
        assertEquals(listOf(hl), r.removed)
        assertEquals(1, r.added.size)
    }

    @Test fun foldReplacesOnlyTheStrokesThatCrossTheAxis() {
        val crossing = stroke(2f, 10f, 18f, 30f)       // 축 x=10을 가로지른다
        val away = stroke(14f, 10f, 16f, 30f)          // 축 오른쪽에만 있다
        // 축의 오른쪽(x>10)을 남긴다: side=(bx-ax)*(y-ay)-(by-ay)*(x-ax) = -100*(x-10) → x>10 쪽이 음수
        val keepRight = SymmetryMath.sideSign(15f, 0f, 10f, 0f, 10f, 100f)
        val r = build(listOf(crossing, away), SymMode.FOLD, keep = keepRight)
        assertEquals(listOf(crossing), r.removed)
        assertEquals(1, r.added.size)
        // 접힌 뒤 모든 점이 x ≥ 10
        for (i in 0 until r.added[0].count) assertTrue(r.added[0].x(i) >= 10f - eps)
    }

    @Test fun foldWithDashedOriginalKeepsTheWholeOriginalAsAGhost() {
        val crossing = stroke(2f, 10f, 18f, 30f)
        val keepRight = SymmetryMath.sideSign(15f, 0f, 10f, 0f, 10f, 100f)
        val r = build(listOf(crossing), SymMode.FOLD, orig = OrigStyle.DASHED, keep = keepRight)
        assertEquals(2, r.added.size)
        assertTrue(r.added[0].dashed)                    // 접히기 전: 점선
        assertEquals(2f, r.added[0].x(0), eps)
        assertFalse(r.added[1].dashed)                   // 접힌 뒤: 실선
        assertTrue(r.added[1].x(0) >= 10f - eps)
    }

    @Test fun foldWithNothingToFoldIsEmpty() {
        val away = stroke(14f, 10f, 16f, 30f)
        val keepRight = SymmetryMath.sideSign(15f, 0f, 10f, 0f, 10f, 100f)
        assertTrue(build(listOf(away), SymMode.FOLD, keep = keepRight).isEmpty)
    }

    @Test fun keepSideIsTheSideWithMorePoints() {
        val mostlyRight = stroke(2f, 0f, 12f, 10f, 14f, 20f, 16f, 30f)
        val right = SymmetryMath.sideSign(15f, 0f, 10f, 0f, 10f, 100f)
        assertEquals(right, SymmetryOps.keepSide(listOf(mostlyRight), 10f, 0f, 10f, 100f))
        val mostlyLeft = stroke(2f, 0f, 4f, 10f, 6f, 20f, 16f, 30f)
        assertEquals(-right, SymmetryOps.keepSide(listOf(mostlyLeft), 10f, 0f, 10f, 100f))
    }
}
