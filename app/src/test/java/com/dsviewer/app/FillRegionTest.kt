package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Font
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

/** 채우기 영역 찾기 (칠하기·자유 영역·다각형 보정). 결과 그림은 build/fillTest 폴더의 PNG */
class FillRegionTest {

    private val k = 2f
    private val w = 600
    private val h = 800

    /** 흰 바탕 쪽 그림 (1pt = k px) */
    private fun page(draw: (java.awt.Graphics2D) -> Unit): BufferedImage {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.color = Color.WHITE
        g.fillRect(0, 0, w, h)
        g.color = Color.BLACK
        g.scale(k.toDouble(), k.toDouble())
        draw(g)
        g.dispose()
        return img
    }

    private fun pixels(img: BufferedImage) = img.getRGB(0, 0, w, h, null, 0, w)

    private fun poly(vararg p: Double) = Path2D.Double().apply {
        moveTo(p[0], p[1])
        for (i in 1 until p.size / 2) lineTo(p[i * 2], p[i * 2 + 1])
        closePath()
    }

    /** 바탕 그림 위에 찾은 윤곽들을 반투명 파랑으로 칠해 저장 */
    private fun save(name: String, bg: BufferedImage?, contours: List<FloatArray>, drawn: Pair<FloatArray, FloatArray>? = null) {
        val img = BufferedImage(w, h, BufferedImage.TYPE_INT_RGB)
        val g = img.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        if (bg != null) g.drawImage(bg, 0, 0, null) else { g.color = Color.WHITE; g.fillRect(0, 0, w, h) }
        g.scale(k.toDouble(), k.toDouble())
        drawn?.let { (xs, ys) ->
            g.color = Color(170, 170, 170)
            g.stroke = BasicStroke(0.8f)
            val p = Path2D.Double()
            p.moveTo(xs[0].toDouble(), ys[0].toDouble())
            for (i in 1 until xs.size) p.lineTo(xs[i].toDouble(), ys[i].toDouble())
            g.draw(p)
        }
        val path = Path2D.Double(Path2D.WIND_EVEN_ODD)
        for (c in contours) {
            path.moveTo(c[0].toDouble(), c[1].toDouble())
            for (i in 1 until c.size / 2) path.lineTo(c[i * 2].toDouble(), c[i * 2 + 1].toDouble())
            path.closePath()
        }
        g.color = Color(40, 120, 255, 110)
        g.fill(path)
        g.color = Color(20, 60, 200)
        g.stroke = BasicStroke(0.4f)
        g.draw(path)
        for (c in contours) for (i in 0 until c.size / 2) g.fillOval((c[i * 2] - 0.8).toInt(), (c[i * 2 + 1] - 0.8).toInt(), 2, 2)
        g.dispose()
        val dir = File("build/fillTest")
        dir.mkdirs()
        ImageIO.write(img, "png", File(dir, "$name.png"))
    }

    private fun area(c: FloatArray) = FillRegion.signedArea(c)

    @Test
    fun bucketTriangleKeepsInnerCircleFillsLetter() {
        val img = page { g ->
            g.stroke = BasicStroke(0.6f)
            g.draw(poly(50.0, 300.0, 250.0, 300.0, 150.0, 80.0))
            g.draw(java.awt.geom.Ellipse2D.Double(130.0, 220.0, 40.0, 40.0))
            g.font = Font("SansSerif", Font.PLAIN, 10)
            g.drawString("A", 100f, 280f)
        }
        val r = FillRegion.bucket(pixels(img), w, h, k, (110 * k).toInt(), (200 * k).toInt())
        assertNotNull(r)
        save("bucket_triangle", img, r!!.contours)
        val outer = r.contours.filter { area(it) > 0 }
        val holes = r.contours.filter { area(it) < 0 }
        assertEquals(1, outer.size)
        // 글자 구멍은 메우고, 안쪽 원은 남긴다
        assertEquals(1, holes.size)
        val tri = 0.5 * 200 * 220
        assertTrue("삼각형 넓이 ${area(outer[0])} vs $tri", abs(area(outer[0]) - tri) < tri * 0.03)
        val circle = PI * 20 * 20
        assertTrue("원 넓이 ${-area(holes[0])} vs $circle", abs(-area(holes[0]) - circle) < circle * 0.1)

        // 원 안을 누르면 원 안만
        val c = FillRegion.bucket(pixels(img), w, h, k, (150 * k).toInt(), (240 * k).toInt())!!
        save("bucket_circle", img, c.contours)
        assertEquals(1, c.contours.size)
        assertTrue(abs(area(c.contours[0]) - circle) < circle * 0.1)
    }

    @Test
    fun bucketClosesSmallGap() {
        val img = page { g ->
            g.stroke = BasicStroke(0.8f)
            // 오른쪽 아래 꼭짓점 근처가 2pt 끊긴 사각형
            val p = Path2D.Double()
            p.moveTo(250.0, 278.0); p.lineTo(250.0, 100.0); p.lineTo(60.0, 100.0); p.lineTo(60.0, 280.0); p.lineTo(248.0, 280.0)
            g.draw(p)
        }
        val r = FillRegion.bucket(pixels(img), w, h, k, (150 * k).toInt(), (200 * k).toInt())
        assertNotNull(r)
        save("bucket_gap", img, r!!.contours)
        val outer = r.contours.filter { area(it) > 0 }
        assertEquals(1, outer.size)
        val rect = 190.0 * 180.0
        assertTrue("넓이 ${area(outer[0])} vs $rect", abs(area(outer[0]) - rect) < rect * 0.03)
    }

    @Test
    fun bucketOpenShapeFails() {
        val img = page { g ->
            g.stroke = BasicStroke(0.8f)
            val p = Path2D.Double()
            p.moveTo(60.0, 100.0); p.lineTo(150.0, 300.0); p.lineTo(240.0, 100.0)
            g.draw(p)
        }
        assertNull(FillRegion.bucket(pixels(img), w, h, k, (150 * k).toInt(), (200 * k).toInt()))
    }

    @Test
    fun bucketIgnoresLightGridLines() {
        val img = page { g ->
            // 모눈 노트의 옅은 선
            g.color = Color(0xB8, 0xC6, 0xD6)
            g.stroke = BasicStroke(0.6f)
            var x = 0.0
            while (x < 300) { g.draw(java.awt.geom.Line2D.Double(x, 0.0, x, 400.0)); g.draw(java.awt.geom.Line2D.Double(0.0, x, 300.0, x)); x += 14.0 }
            g.color = Color.BLACK
            g.stroke = BasicStroke(0.6f)
            g.draw(java.awt.geom.Ellipse2D.Double(80.0, 120.0, 140.0, 140.0))
        }
        val r = FillRegion.bucket(pixels(img), w, h, k, (150 * k).toInt(), (190 * k).toInt())!!
        save("bucket_grid", img, r.contours)
        val circle = PI * 70 * 70
        assertTrue(abs(area(r.contours.maxBy { area(it) }) - circle) < circle * 0.05)
    }

    /** 참 곡선 f(t), t ∈ [0, 1]을 n개로 뽑아 흔든다 */
    private fun drawn(n: Int, noise: Float, seed: Int = 1, f: (Double) -> Pair<Double, Double>): Pair<FloatArray, FloatArray> {
        val rnd = Random(seed)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        for (i in 0 until n) {
            val (x, y) = f(i / (n - 1.0))
            xs[i] = (x + (rnd.nextDouble() - 0.5) * 2 * noise).toFloat()
            ys[i] = (y + (rnd.nextDouble() - 0.5) * 2 * noise).toFloat()
        }
        return xs to ys
    }

    @Test
    fun freeformSpiralFillsOutermost() {
        // 골뱅이: 바깥 고리 반지름 80에서 안으로 감겨 들어가는 나선 (끝은 안쪽에서 멈춤)
        val d = drawn(600, 0.6f) { t ->
            val a = t * 2.6 * 2 * PI
            val r = 80 - 45 * t
            150 + r * cos(a) to 200 + r * sin(a)
        }
        val out = FillRegion.freeform(d.first, d.second)
        assertNotNull(out)
        save("free_spiral", null, listOf(out!!), d)
        val a = area(out)
        val outerDisk = PI * 80 * 80
        assertTrue("넓이 $a vs $outerDisk", a > outerDisk * 0.8 && a < outerDisk * 1.05)
    }

    @Test
    fun straightenSquareStartedMidEdge() {
        // 한 변 가운데에서 시작해 손으로 그린 네모 (끝은 조금 지나침)
        val corners = listOf(60.0 to 100.0, 240.0 to 100.0, 240.0 to 280.0, 60.0 to 280.0)
        val d = drawn(400, 1.2f, seed = 3) { t ->
            val s = (t * 4.08 + 0.5) % 4.0
            val i = s.toInt()
            val f = s - i
            val a = corners[i]; val b = corners[(i + 1) % 4]
            a.first + (b.first - a.first) * f to a.second + (b.second - a.second) * f
        }
        val outline = FillRegion.freeform(d.first, d.second)!!
        val sq = FillRegion.straighten(outline)
        save("free_square", null, listOf(sq), d)
        assertEquals("꼭짓점 수 ${sq.size / 2}", 4, sq.size / 2)
        for ((cx, cy) in corners) {
            val near = (0 until 4).minOf { hypot(sq[it * 2] - cx, sq[it * 2 + 1] - cy) }
            assertTrue("꼭짓점 ($cx, $cy)에서 $near", near < 3.0)
        }
    }

    @Test
    fun straightenTriangleAndPentagon() {
        for (nSides in listOf(3, 5)) {
            val vs = (0 until nSides).map { i ->
                val a = -PI / 2 + 2 * PI * i / nSides
                150 + 100 * cos(a) to 200 + 100 * sin(a)
            }
            val d = drawn(400, 1.0f, seed = nSides) { t ->
                val s = (t * nSides) % nSides.toDouble()
                val i = s.toInt().coerceAtMost(nSides - 1)
                val f = s - i
                val a = vs[i]; val b = vs[(i + 1) % nSides]
                a.first + (b.first - a.first) * f to a.second + (b.second - a.second) * f
            }
            val p = FillRegion.straighten(FillRegion.freeform(d.first, d.second)!!)
            save("free_poly$nSides", null, listOf(p), d)
            assertEquals(nSides, p.size / 2)
        }
    }

    @Test
    fun straightenKeepsCurvedSide() {
        // 반원 (지름은 곧게, 호는 그대로)
        val d = drawn(400, 0.8f, seed = 5) { t ->
            val total = 200 + PI * 100
            val s = t * total
            if (s < 200) 50 + s to 250.0
            else {
                val a = (s - 200) / 100
                150 + 100 * cos(a) to 250 - 100 * sin(a)
            }
        }
        val p = FillRegion.straighten(FillRegion.freeform(d.first, d.second)!!)
        save("free_semicircle", null, listOf(p), d)
        assertTrue("호가 남아야 함: 점 ${p.size / 2}개", p.size / 2 > 20)
        val half = PI * 100 * 100 / 2
        assertTrue(abs(area(p) - half) < half * 0.04)
        // 지름 위의 점들은 y = 250 위에
        val onBase = (0 until p.size / 2).filter { abs(p[it * 2 + 1] - 250f) < 4f && p[it * 2] in 70f..230f }
        for (i in onBase) assertTrue(abs(p[i * 2 + 1] - 250f) < 1.5f)
    }

    @Test
    fun straightenLeavesCircle() {
        val d = drawn(400, 0.8f, seed = 7) { t -> 150 + 90 * cos(t * 2 * PI) to 200 + 90 * sin(t * 2 * PI) }
        val p = FillRegion.straighten(FillRegion.freeform(d.first, d.second)!!)
        save("free_circle", null, listOf(p), d)
        assertTrue(p.size / 2 > 30)
        for (i in 0 until p.size / 2) assertTrue(abs(hypot(p[i * 2] - 150f, p[i * 2 + 1] - 200f) - 90f) < 3f)
    }
}
