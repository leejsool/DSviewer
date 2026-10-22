package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 겹치는 순서 바꾸기 계산 (목록 뒤쪽이 위) */
class ZOrderTest {
    private val all = listOf(1, 2, 3, 4, 5)

    private fun move(mode: ZMove, sel: Set<Int>, over: Set<Int> = all.toSet()) =
        ZOrder.move(all, mode, { it in sel }, { it in over })

    @Test fun frontAndBackKeepTheOrderAmongPicked() {
        assertEquals(listOf(1, 3, 4, 5, 2), move(ZMove.FRONT, setOf(2)))
        assertEquals(listOf(1, 3, 5, 2, 4), move(ZMove.FRONT, setOf(2, 4)))
        assertEquals(listOf(4, 1, 2, 3, 5), move(ZMove.BACK, setOf(4)))
        assertEquals(listOf(2, 4, 1, 3, 5), move(ZMove.BACK, setOf(2, 4)))
    }

    @Test fun alreadyAtTheEndChangesNothing() {
        assertNull(move(ZMove.FRONT, setOf(5)))
        assertNull(move(ZMove.BACK, setOf(1)))
        assertNull(move(ZMove.FRONT, setOf(4, 5)))
    }

    @Test fun oneStepJumpsOverTheNearestOverlappingOne() {
        assertEquals(listOf(1, 3, 2, 4, 5), move(ZMove.FORWARD, setOf(2)))
        assertEquals(listOf(2, 1, 3, 4, 5), move(ZMove.BACKWARD, setOf(2)))
    }

    @Test fun oneStepSkipsThingsThatDoNotOverlap() {
        // 2와 겹치는 것은 5뿐: 한 칸 앞으로는 5 위로
        assertEquals(listOf(1, 3, 4, 5, 2), move(ZMove.FORWARD, setOf(2), over = setOf(5)))
        // 4와 겹치는 것은 1뿐: 한 칸 뒤로는 1 아래로
        assertEquals(listOf(4, 1, 2, 3, 5), move(ZMove.BACKWARD, setOf(4), over = setOf(1)))
    }

    @Test fun oneStepWithNothingToPassChangesNothing() {
        assertNull(move(ZMove.FORWARD, setOf(5)))
        assertNull(move(ZMove.BACKWARD, setOf(1)))
        assertNull(move(ZMove.FORWARD, setOf(2), over = setOf(1)))   // 위에는 겹치는 것이 없다
    }

    @Test fun groupMovesTogetherPastOne() {
        assertEquals(listOf(1, 4, 2, 3, 5), move(ZMove.FORWARD, setOf(2, 3)))
    }

    @Test fun nothingPickedChangesNothing() {
        assertNull(move(ZMove.FRONT, emptySet()))
    }
}
