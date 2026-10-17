package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 긁어서 지우기 판단: 넓게 휘어 긁기·좁게 위아래로 긁기·뾰족하게 긁기는 긁기,
 * 동그라미 두 번·물결선은 긁기가 아님. 긁은 아래 가로선은 가운데만 잘림. (쪽 좌표 1 = 1dp로 봄)
 */
class ScribbleTest {
    private fun stroke(pts: List<Pair<Float, Float>>): Stroke {
        val st = Stroke(Tool.PEN, 0xFF000000.toInt(), 2f)
        // 점을 1 간격으로 촘촘히
        for (k in pts.indices) {
            if (k == 0) { st.add(pts[0].first, pts[0].second, 0.5f); continue }
            val (ax, ay) = pts[k - 1]
            val (bx, by) = pts[k]
            val n = maxOf(1, hypot(bx - ax, by - ay).toInt())
            for (i in 1..n) st.add(ax + (bx - ax) * i / n, ay + (by - ay) * i / n, 0.5f)
        }
        return st
    }

    /** 가로로 [w] 폭을 [passes]번 오가며 한 번에 [down]씩 내려감. 긋는 선은 [sag]만큼 휘고 끝은 반지름 [r]로 둥글게 돎 */
    private fun scribble(w: Float, passes: Int, down: Float, sag: Float, r: Float, vertical: Boolean = false): Stroke {
        val pts = ArrayList<Pair<Float, Float>>()
        var y = 0f
        for (p in 0 until passes) {
            val rightward = p % 2 == 0
            for (i in 0..40) {
                val t = i / 40f
                val x = if (rightward) w * t else w * (1 - t)
                pts.add(x to y + sag * sin(PI * t).toFloat() + down * t)
            }
            y += down
            // 끝에서 둥글게 돌기
            val cx = if (rightward) w else 0f
            for (i in 1..6) {
                val a = PI / 2 * (if (rightward) 1 else -1) * i / 6
                pts.add(cx + r * sin(a).toFloat() to y + r * (1 - cos(a).toFloat()) * 0.3f)
            }
        }
        return stroke(if (vertical) pts.map { it.second to it.first } else pts)
    }

    @Test
    fun wideCurvedScribble() {
        assertNotNull(ScribbleRegion.detect(scribble(160f, 8, 5f, 18f, 12f), 1f))
    }

    @Test
    fun narrowVerticalScribble() {
        assertNotNull(ScribbleRegion.detect(scribble(14f, 8, 2f, 1f, 2f, vertical = true), 1f))
    }

    @Test
    fun sharpScribble() {
        assertNotNull(ScribbleRegion.detect(scribble(200f, 5, 8f, 0f, 0f), 1f))
    }

    @Test
    fun circleTwiceIsNotScribble() {
        val pts = (0..720 step 3).map { d ->
            val a = d * PI / 180
            (100 + 30 * cos(a)).toFloat() to (100 + 30 * sin(a)).toFloat()
        }
        assertNull(ScribbleRegion.detect(stroke(pts), 1f))
    }

    @Test
    fun dotWobbleIsNotScribble() {
        // 점을 찍다 작게 오가거나 떨린 획 (6dp 안팎)은 긁기가 아니다
        assertNull(ScribbleRegion.detect(scribble(6f, 8, 1f, 0f, 0f), 1f))
        assertNull(ScribbleRegion.detect(scribble(7f, 8, 1f, 0f, 0f, vertical = true), 1f))
    }

    /** [src]와 같은 자취를 굵기 [width]로 다시 그린 획 */
    private fun withWidth(src: Stroke, width: Float): Stroke {
        val st = Stroke(Tool.PEN, 0xFF000000.toInt(), width)
        for (i in 0 until src.count) st.add(src.x(i), src.y(i), src.p(i))
        return st
    }

    @Test
    fun thickPenBlobDotIsNotScribble() {
        // 전자칠판: 굵은 펜(굵기 4)으로 점을 비벼 크게 찍은 것(굵기의 8배 안)은 dp로는 긁기만 해도 긁기가 아니다
        val blob = scribble(24f, 8, 2f, 0f, 0f)
        assertNotNull("같은 자취를 가는 펜으로 긁으면 긁기", ScribbleRegion.detect(withWidth(blob, 2f), 1f))
        assertNull(ScribbleRegion.detect(withWidth(blob, 4f), 1f))
        // 같은 굵은 펜이라도 굵기보다 훨씬 넓게 긁으면 긁기
        assertNotNull(ScribbleRegion.detect(withWidth(scribble(160f, 8, 5f, 18f, 12f), 4f), 1f))
    }

    @Test
    fun waveIsNotScribble() {
        val pts = (0..200).map { x -> x.toFloat() to (10 * sin(x / 8.0)).toFloat() }
        assertNull(ScribbleRegion.detect(stroke(pts), 1f))
    }

    @Test
    fun lineUnderScribbleIsCutInMiddle() {
        val region = ScribbleRegion.detect(scribble(160f, 8, 5f, 18f, 12f), 1f)!!
        val line = stroke(listOf(-60f to 30f, 220f to 30f))
        val rest = line.cutWhere(0.75f) { x, y -> region.contains(x, y, line.halfWidth) }!!
        assertEquals(2, rest.size)
        assertTrue(rest[0].x(rest[0].count - 1) < 5f)
        assertTrue(rest[1].x(0) > 155f)
        // 멀리 있는 선은 그대로
        val far = stroke(listOf(-60f to 300f, 220f to 300f))
        assertNull(far.cutWhere(0.75f) { x, y -> region.contains(x, y, far.halfWidth) })
    }

    @Test
    fun smallIsolatedLeftoverIsStray() {
        val tiny = stroke(listOf(0f to 0f, 6f to 0f))
        val long = stroke(listOf(0f to 40f, 60f to 40f))
        val neighbor = stroke(listOf(0f to 6f, 6f to 6f))
        val far = stroke(listOf(200f to 200f, 260f to 200f))
        // 떨어져 있고 짧다: 부스러기
        assertEquals(setOf(tiny), strayPieces(listOf(tiny, long), listOf(far), 1f))
        // 길게 남은 것은 그대로
        assertTrue(long !in strayPieces(listOf(tiny, long), listOf(far), 1f))
        // 이웃 필기가 가까이 있으면 부스러기가 아니다
        assertTrue(strayPieces(listOf(tiny), listOf(neighbor), 1f).isEmpty())
    }

    /** 'ㅗㅇ'을 한 획으로: ㅗ 세로, 가로(왼쪽으로 갔다 오른쪽으로), 아래 ㅇ 한 바퀴 */
    private fun ohIeung(): Stroke {
        val pts = arrayListOf(50f to 0f, 50f to 18f, 22f to 20f, 78f to 20f, 50f to 32f)
        for (d in 0..360 step 10) {
            val a = d * PI / 180 - PI / 2
            pts.add((50 + 15 * cos(a)).toFloat() to (47 + 15 * sin(a)).toFloat())
        }
        return stroke(pts)
    }

    @Test
    fun ohIeungIsNotScribble() {
        assertNull(ScribbleRegion.detect(ohIeung(), 1f))
    }

    @Test
    fun scribbleCrossesWordManyTimes() {
        // 긁은 선은 아래 세로획들을 여러 번 가로지른다
        val sc = scribble(160f, 8, 5f, 18f, 12f)
        val word = stroke(listOf(40f to -5f, 40f to 50f, 80f to 50f, 80f to -5f, 120f to -5f, 120f to 50f))
        assertEquals(3, crossings(sc, word, 3))
        // 옆 글자에 살짝 닿기만 하면 거의 안 가로지른다
        val beside = stroke(listOf(0f to 0f, 0f to 30f))
        assertTrue(crossings(ohIeung(), beside, 3) < 3)
    }
}
