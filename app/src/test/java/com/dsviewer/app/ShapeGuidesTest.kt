package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.RenderingHints
import java.awt.geom.Path2D
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** 보조선(화살표·길이 표시) 맞추기. 결과 그림은 build/guideTest 폴더의 PNG (그린 획은 회색) */
class ShapeGuidesTest {

    /** 참 곡선을 길이 기준 고르게 n개로 뽑고 흔든다 */
    private fun drawn(n: Int = 200, noise: Double = 1.2, seed: Int = 1, f: (Double) -> Pair<Double, Double>): Pair<DoubleArray, DoubleArray> {
        val dense = (0..4000).map { f(it / 4000.0) }
        val cum = DoubleArray(dense.size)
        for (i in 1 until dense.size) cum[i] = cum[i - 1] + hypot(dense[i].first - dense[i - 1].first, dense[i].second - dense[i - 1].second)
        val rnd = Random(seed)
        val xs = DoubleArray(n); val ys = DoubleArray(n)
        var j = 0
        for (k in 0 until n) {
            val target = cum.last() * k / (n - 1)
            while (j < dense.size - 1 && cum[j + 1] < target) j++
            val e = if (k == 0 || k == n - 1) 0.0 else noise
            xs[k] = dense[j].first + (rnd.nextDouble() - 0.5) * 2 * e
            ys[k] = dense[j].second + (rnd.nextDouble() - 0.5) * 2 * e
        }
        return xs to ys
    }

    private fun save(name: String, raw: Pair<DoubleArray, DoubleArray>, f: Fitted) {
        val img = BufferedImage(500, 400, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, 500, 400)
        fun path(xs: List<Double>, ys: List<Double>) = Path2D.Double().apply {
            moveTo(xs[0], ys[0]); for (i in 1 until xs.size) lineTo(xs[i], ys[i])
        }
        g.color = Color(190, 190, 190)
        g.stroke = BasicStroke(2f)
        g.draw(path(raw.first.toList(), raw.second.toList()))
        g.color = Color.BLACK
        g.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        for (c in f.curves + f.heads) {
            g.draw(path((0 until c.size / 2).map { c[it * 2].toDouble() }, (0 until c.size / 2).map { c[it * 2 + 1].toDouble() }))
        }
        g.dispose()
        val dir = File("build/guideTest").apply { mkdirs() }
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    @Test
    fun straightArrowKeepsDirectionAndSnaps() {
        val raw = drawn { t -> (60 + 380 * t) to (200 + 6 * t) }
        val f = ShapeGuides.arrow(ShapeKind.ARROW, raw, 1.2f)
        assertNotNull(f)
        save("arrow_straight", raw, f!!)
        val c = f.curves[0]
        // 가로로 딱 맞춤, 그린 방향 그대로 (끝이 오른쪽)
        assertEquals(c[1], c[3], 0.01f)
        assertTrue(c[2] > c[0])
        // 화살촉 끝 = 몸통 끝
        val h = f.heads[0]
        assertEquals(c[2], h[2], 0.01f)
        assertEquals(c[3], h[3], 0.01f)
        assertTrue(h[0] < h[2] && h[4] < h[2])
    }

    @Test
    fun curvedArrowIsParabola() {
        val raw = drawn { t -> (50 + 400 * t) to (300 - 900 * t * (1 - t) * 0.9) }
        val f = ShapeGuides.arrow(ShapeKind.ARROW_CURVE, raw, 1.2f)!!
        save("arrow_curve", raw, f)
        val c = f.curves[0]
        // 가운데가 위로 (원래 꼭대기 y ≈ 300 − 202)
        val mid = c.size / 4
        assertEquals(98.0, c[mid * 2 + 1].toDouble(), 6.0)
    }

    @Test
    fun sCurveArrowUsesCubic() {
        val raw = drawn { t -> (50 + 400 * t) to (200 - 90 * sin(2 * PI * t)) }
        val f = ShapeGuides.arrow(ShapeKind.ARROW_CURVE, raw, 1.2f)!!
        save("arrow_scurve", raw, f)
        val c = f.curves[0]
        val n = c.size / 2
        // 앞쪽은 위로, 뒤쪽은 아래로
        assertTrue(c[(n / 4) * 2 + 1] < 160)
        assertTrue(c[(3 * n / 4) * 2 + 1] > 240)
    }

    /** 고리 있는 선: 가운데 쯤 고리 (반지름 r, 위쪽 = y 작은 쪽) */
    private fun loopStroke(cx: Double, r: Double, up: Boolean) = drawn(300) { t ->
        val x0 = 50.0; val x1 = 450.0
        val w = 0.18
        val s = ((t - (cx - w)) / (2 * w)).coerceIn(0.0, 1.0)
        val phi = 2 * PI * s
        val x = x0 + (x1 - x0) * t + r * 1.4 * sin(phi)
        val y = 220.0 + (if (up) -1 else 1) * r * (1 - cos(phi))
        x to y
    }

    @Test
    fun pigtailFollowsDrawnLoop() {
        for (up in listOf(true, false)) {
            val raw = loopStroke(0.4, 30.0, up)
            val f = ShapeGuides.arrow(ShapeKind.ARROW_PIGTAIL, raw, 1.2f)!!
            save("arrow_pigtail_${if (up) "up" else "down"}", raw, f)
            val c = f.curves[0]
            val n = c.size / 2
            val ys = (0 until n).map { c[it * 2 + 1] }
            // 고리가 그린 쪽에, 그린 고리(높이 약 45)만 하게
            if (up) assertTrue(ys.min() < 220 - 45) else assertTrue(ys.max() > 220 + 45)
            // 고리가 있다: x가 뒤로 가는 구간
            assertTrue((1 until n).any { c[it * 2] < c[it * 2 - 2] - 0.5f })
            // 처음·끝은 그린 자리
            assertEquals(50f, c[0], 0.5f)
            assertEquals(450f, c[(n - 1) * 2], 0.5f)
        }
    }

    @Test
    fun pigtailWithoutLoopStillCurls() {
        val raw = drawn { t -> (60 + 380 * t) to (220 - 40 * sin(PI * t)) }
        val f = ShapeGuides.arrow(ShapeKind.ARROW_PIGTAIL, raw, 1.2f)!!
        save("arrow_pigtail_noloop", raw, f)
        val c = f.curves[0]
        val n = c.size / 2
        assertTrue((1 until n).any { c[it * 2] < c[it * 2 - 2] - 0.5f })
        assertTrue((0 until n).minOf { c[it * 2 + 1] } < 210)
    }

    @Test
    fun straightPigtailCurlsUpward() {
        for (rightward in listOf(true, false)) {
            val raw = drawn { t -> (if (rightward) 60 + 380 * t else 440 - 380 * t) to 220.0 }
            val c = ShapeGuides.arrow(ShapeKind.ARROW_PIGTAIL, raw, 1.2f)!!.curves[0]
            assertTrue((0 until c.size / 2).minOf { c[it * 2 + 1] } < 205)
        }
    }

    @Test
    fun lengthMarkFitsArcWithGap() {
        // 반지름 250, 중심 (250, 380)인 원의 위쪽 호: (70, 206.6) ~ (430, 206.6)
        val r = 250.0
        val a0 = PI + 0.8; val a1 = 2 * PI - 0.8
        val raw = drawn { t -> val a = a0 + (a1 - a0) * t; (250 + r * cos(a)) to (380 + r * sin(a)) }
        val f = ShapeGuides.lengthMark(raw)!!
        save("length_mark", raw, f)
        assertEquals(2, f.curves.size)
        for (c in f.curves) for (i in 0 until c.size / 2) {
            val d = hypot(c[i * 2] - 250.0, c[i * 2 + 1] - 380.0)
            assertEquals(r, d, 3.0)
        }
        // 두 조각 사이가 비어 있다 (가운데 위쪽)
        val left = f.curves[0]; val right = f.curves[1]
        val gap = hypot(right[0] - left[left.size - 2].toDouble(), right[1] - left[left.size - 1].toDouble())
        assertTrue(gap in 14.0..60.0)
        assertTrue(abs((left[left.size - 2] + right[0]) / 2 - 250f) < 3f)
    }

    /** 여러 크기·기울기·방향의 호: 맞춘 호가 늘 그린 쪽에 (반대쪽 큰 호가 아니라) */
    @Test
    fun lengthMarkStaysOnDrawnSide() {
        var case = 0
        for (sag in listOf(0.08, 0.15, 0.25, 0.4, 0.5, 0.7)) for (side in listOf(1.0, -1.0)) for (ang in listOf(0.0, 0.5, 1.6, 3.0, -2.2)) {
            for (seed in 1..3) {
                val cx = 250.0; val cy = 200.0; val half = 150.0
                val ux = cos(ang); val uy = sin(ang)
                val h = sag * 2 * half * side
                val r = (half * half + h * h) / (2 * abs(h))
                val k = h - Math.signum(h) * r
                val theta = 4 * kotlin.math.atan(abs(h) / half)
                val a0 = kotlin.math.atan2(-k, -half)
                val apex = kotlin.math.atan2(h - k, 0.0)
                fun diff(x: Double) = abs(kotlin.math.atan2(sin(x - apex), cos(x - apex)))
                val dir = if (diff(a0 + theta / 2) < diff(a0 - theta / 2)) 1.0 else -1.0
                val raw = drawn(noise = 2.0, seed = seed) { t ->
                    val an = a0 + dir * theta * t
                    val la = r * cos(an); val lq = k + r * sin(an)
                    (cx + la * ux - lq * uy) to (cy + la * uy + lq * ux)
                }
                val f = ShapeGuides.lengthMark(raw)!!
                if (case++ == 0) save("length_sides", raw, f)
                // 맞춘 점들의 옆쪽 거리 부호가 그린 쪽과 같고, 높이도 비슷하다
                for (c in f.curves) for (i in 0 until c.size / 2) {
                    val q = -(c[i * 2] - cx) * uy + (c[i * 2 + 1] - cy) * ux
                    assertTrue("sag=$sag side=$side ang=$ang q=$q", q * side > -3)
                }
                val far = f.curves.flatMap { c -> (0 until c.size / 2).map { i -> abs(-(c[i * 2] - cx) * uy + (c[i * 2 + 1] - cy) * ux) } }.max()
                assertEquals("sag=$sag side=$side ang=$ang", abs(h), far, abs(h) * 0.15 + 4)
            }
        }
    }

    @Test
    fun lengthMarkBelow() {
        val raw = drawn { t -> (100 + 300 * t) to (150 + 70 * sin(PI * t)) }
        val f = ShapeGuides.lengthMark(raw)!!
        save("length_mark_below", raw, f)
        // 아래로 볼록
        assertTrue(f.curves[0].let { c -> c[c.size - 1] } > 190)
    }
}
