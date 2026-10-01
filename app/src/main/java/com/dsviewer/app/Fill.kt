package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.drawable.Drawable
import androidx.core.graphics.ColorUtils
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 채우기 무늬. 색은 영역 전체를 칠하고, 나머지는 바탕은 비워 두고 무늬 선·점만 그 색으로 칠한다
 * (수학 도형의 '빗금 친 부분'처럼)
 */
enum class FillPattern(val label: String) {
    SOLID("색"), HATCH("빗금"), BACK_HATCH("반대 빗금"), CROSS("엇빗금"),
    HORIZONTAL("가로줄"), VERTICAL("세로줄"), GRID("격자"), DOT("점");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: SOLID
    }
}

/** 채우기 획의 무늬 */
class FillStyle(val pattern: FillPattern)

/** 칠하기: 누른 자리를 둘러싼 닫힌 영역, 자유 영역: 그린 폐곡선의 가장 바깥 안쪽 */
enum class FillMode { BUCKET, FREE }

object FillTile {
    /** 무늬 한 칸의 크기 (pt) */
    const val PERIOD = 5f
    /** 무늬 선 굵기 (pt) */
    private const val LINE = 0.6f

    /** 칸 (0, 0) ~ (PERIOD, PERIOD) 안의 무늬 모양 (테이프 무늬와 같은 모양 종류) */
    fun shapes(p: FillPattern): List<TapeTile.Shape> {
        val s = PERIOD
        // 45° 띠의 축 방향 반폭 (수직 굵기가 LINE이 되게)
        val a = LINE / sqrt(2f)
        val t = LINE / 2
        val slash = listOf(
            TapeTile.Poly(floatArrayOf(0f, s - a, s - a, 0f, s, 0f, s, a, a, s, 0f, s)),
            TapeTile.Poly(floatArrayOf(0f, 0f, a, 0f, 0f, a)),
            TapeTile.Poly(floatArrayOf(s, s, s - a, s, s, s - a)),
        )
        val back = listOf(
            TapeTile.Poly(floatArrayOf(0f, 0f, a, 0f, s, s - a, s, s, s - a, s, 0f, a)),
            TapeTile.Poly(floatArrayOf(s - a, 0f, s, 0f, s, a)),
            TapeTile.Poly(floatArrayOf(0f, s - a, a, s, 0f, s)),
        )
        val h = listOf(TapeTile.Box(0f, s / 2 - t, s, s / 2 + t))
        val v = listOf(TapeTile.Box(s / 2 - t, 0f, s / 2 + t, s))
        return when (p) {
            FillPattern.SOLID -> emptyList()
            FillPattern.HATCH -> slash
            FillPattern.BACK_HATCH -> back
            FillPattern.CROSS -> slash + back
            FillPattern.HORIZONTAL -> h
            FillPattern.VERTICAL -> v
            FillPattern.GRID -> h + v
            FillPattern.DOT -> {
                val r = 0.6f
                listOf(
                    TapeTile.Dot(s / 2, s / 2, r), TapeTile.Dot(0f, 0f, r), TapeTile.Dot(s, 0f, r),
                    TapeTile.Dot(0f, s, r), TapeTile.Dot(s, s, r),
                )
            }
        }
    }

    /** 무늬 선의 색: 아주 옅은 색이면 조금 진하게 (흰 종이 위에서 무늬가 보이게) */
    fun inkColor(base: Int): Int {
        val opaque = base or (0xFF shl 24)
        return if (Color.luminance(opaque) > 0.6f) ColorUtils.blendARGB(opaque, Color.BLACK, 0.3f) else opaque
    }

    // ---- 화면: 칸 하나를 비트맵으로 그려 되풀이 ----

    const val TILE_PX = 12f
    private val shaders = HashMap<Long, BitmapShader>()

    /** 무늬 셰이더 (색 채우기면 null). 바탕은 투명. 셰이더 좌표 1px = 1/TILE_PX pt */
    fun shader(p: FillPattern, base: Int): BitmapShader? = synchronized(shaders) {
        if (p == FillPattern.SOLID) return null
        val key = (p.ordinal.toLong() shl 32) or (base.toLong() and 0xFFFFFFFFL)
        shaders[key]?.let { return it }
        val n = (PERIOD * TILE_PX).roundToInt()
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(TILE_PX, TILE_PX)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = inkColor(base) }
        val path = Path()
        for (sh in shapes(p)) when (sh) {
            is TapeTile.Box -> c.drawRect(sh.l, sh.t, sh.r, sh.b, paint)
            is TapeTile.Dot -> c.drawCircle(sh.cx, sh.cy, sh.r, paint)
            is TapeTile.Poly -> {
                path.reset()
                path.moveTo(sh.pts[0], sh.pts[1])
                for (i in 1 until sh.pts.size / 2) path.lineTo(sh.pts[i * 2], sh.pts[i * 2 + 1])
                path.close()
                c.drawPath(path, paint)
            }
        }
        if (shaders.size > 64) shaders.clear()
        return BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).also { shaders[key] = it }
    }
}

private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
private val fillMatrix = Matrix()

/**
 * 채운 영역: 곱하기로 칠해 아래의 선·글자가 그대로 보이게 한다 (형광펜처럼).
 * 무늬는 영역의 첫 점에 붙인다 (옮겨도 무늬가 영역 위에서 미끄러지지 않게)
 */
fun drawInkFill(c: Canvas, st: Stroke, alphaMul: Float) = synchronized(fillPaint) {
    val style = st.fill ?: return
    val shape = st.fillShape()
    fillPaint.blendMode = BlendMode.MULTIPLY
    val sh = FillTile.shader(style.pattern, st.color)
    if (sh == null) {
        fillPaint.shader = null
        fillPaint.color = st.color or (0xFF shl 24)
    } else {
        fillMatrix.setScale(1f / FillTile.TILE_PX, 1f / FillTile.TILE_PX)
        fillMatrix.postTranslate(st.x(0), st.y(0))
        sh.setLocalMatrix(fillMatrix)
        fillPaint.shader = sh
        fillPaint.color = Color.BLACK
    }
    fillPaint.alpha = (255 * alphaMul).roundToInt()
    c.drawPath(shape, fillPaint)
    fillPaint.shader = null
}

/** 겹쳐 그리는 차례: 그림 → 채우기 → 나머지 필기 (채우기가 선 아래에 깔리게) */
fun inkLayer(st: Stroke) = when {
    st.image != null -> 0
    st.fill != null -> 1
    else -> 2
}

/** 옵션 줄의 무늬 칸: 흰 둥근 네모 안을 지금 색의 그 무늬로 칠한 것 */
class FillPatternDrawable(private val pattern: FillPattern, private val color: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        this.color = Color.argb(90, 0, 0, 0)
    }
    private val m = Matrix()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = b.width() * 0.1f
        val l = b.left + inset; val t = b.top + inset; val r = b.right - inset; val bt = b.bottom - inset
        val rad = b.width() * 0.16f
        paint.shader = null
        paint.color = Color.WHITE
        canvas.drawRoundRect(l, t, r, bt, rad, rad, paint)
        val sh = FillTile.shader(pattern, color)
        if (sh == null) {
            paint.color = color or (0xFF shl 24)
            canvas.drawRoundRect(l, t, r, bt, rad, rad, paint)
        } else {
            // 무늬 한 칸이 6dp쯤 되게
            val k = 6f * density / (FillTile.PERIOD * FillTile.TILE_PX)
            m.setScale(k, k)
            m.postTranslate(l, t)
            sh.setLocalMatrix(m)
            paint.shader = sh
            canvas.drawRoundRect(l, t, r, bt, rad, rad, paint)
            paint.shader = null
        }
        canvas.drawRoundRect(l, t, r, bt, rad, rad, edge)
    }

    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * 채울 영역 찾기. 결과는 쪽 좌표 윤곽들 [x0,y0,x1,y1,...] (닫힌 다각형, 처음 점을 끝에 되풀이하지 않음).
 * 바깥 윤곽과 구멍 윤곽이 섞여 있으며 짝홀 규칙으로 채운다
 */
object FillRegion {

    /** 칠하기 결과: 윤곽들, 또는 닫힌 영역을 못 찾음 (null) */
    class Bucket(val contours: List<FloatArray>)

    /**
     * 칠하기 (그림판의 페인트 통). [px]는 쪽 그림 (PDF + 필기, 흰 바탕) 픽셀, 1pt = [k]px.
     * (sx, sy)는 누른 자리 (픽셀). 어두운 점을 벽으로 보고, 선이 조금 끊긴 곳은 벽을 두껍게 해서 막아 본다
     * (0.5pt → 1.5pt → 3pt). 쪽 가장자리까지 새어 나가면 다음 단계, 모두 새면 null
     */
    fun bucket(px: IntArray, w: Int, h: Int, k: Float, sx: Int, sy: Int): Bucket? {
        val n = w * h
        val wall = BooleanArray(n)
        for (i in 0 until n) {
            val c = px[i]
            val lum = ((c shr 16 and 0xFF) * 299 + (c shr 8 and 0xFF) * 587 + (c and 0xFF) * 114) / 1000
            wall[i] = lum < WALL_LUM
        }
        val queue = IntArray(n)
        val region = ByteArray(n)
        for (gapPt in floatArrayOf(0.5f, 1.5f, 3f)) {
            val r = max(1, (gapPt * k).roundToInt())
            val thick = dilate(wall, w, h, r)
            // 선 위를 눌렀으면 가까운 빈 곳에서 시작
            val seed = nearestFree(thick, w, h, sx, sy, r + (2.5f * k).roundToInt()) ?: continue
            java.util.Arrays.fill(region, 0)
            val count = flood(thick, w, h, seed, region, queue)
            if (count < 0 || count > n * 0.92f) continue  // 쪽 가장자리로 샘
            // 두껍게 한 벽만큼 깎인 영역을 원래 벽까지 되살리고 (끊긴 곳 밖으로는 조금만 나감), 선 안쪽으로 조금 더
            grow(region, w, h, r + 1, queue) { !wall[it] }
            grow(region, w, h, max(1, (0.6f * k).roundToInt()), queue) { wall[it] }
            val contours = trace(region, w, h)
            // 영역 안의 작은 구멍 (글자·점 따위)은 메운다. 큰 구멍 (안쪽 도형)은 남긴다
            val minHole = (14f * k) * (14f * k)
            val kept = contours.filter { c ->
                val a = signedArea(c)
                val outer = a > 0f
                outer || abs(a) >= minHole
            }
            if (kept.none { signedArea(it) > 0f }) return null
            val inv = 1f / k
            return Bucket(kept.map { c ->
                val s = simplify(c, 0.7f)
                for (i in s.indices) s[i] = s[i] * inv
                s
            })
        }
        return null
    }

    /**
     * 자유 영역: 그린 선([xs], [ys], 쪽 좌표)을 처음과 끝을 이어 닫고, 가장 바깥 윤곽 안을 모두 채운다
     * (골뱅이처럼 안에 다른 고리가 생겨도). 결과는 바깥 윤곽 하나
     */
    fun freeform(xs: FloatArray, ys: FloatArray): FloatArray? {
        val n = xs.size
        if (n < 3) return null
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until n) {
            l = min(l, xs[i]); r = max(r, xs[i]); t = min(t, ys[i]); b = max(b, ys[i])
        }
        val size = max(r - l, b - t)
        if (size < 2f) return null
        val k = (1200f / size).coerceIn(2f, 8f)
        val pad = 3
        val w = ceil((r - l) * k).toInt() + pad * 2 + 1
        val h = ceil((b - t) * k).toInt() + pad * 2 + 1
        // 그린 선(처음과 끝을 이은 닫힌 선)을 0.5px 걸음으로 찍는다: 대각선으로만 이어져도 4방향 채우기는 못 지나간다
        val wall = BooleanArray(w * h)
        for (i in 0 until n) {
            val j = (i + 1) % n
            val x0 = (xs[i] - l) * k + pad; val y0 = (ys[i] - t) * k + pad
            val x1 = (xs[j] - l) * k + pad; val y1 = (ys[j] - t) * k + pad
            val steps = max(1, ceil(hypot(x1 - x0, y1 - y0) * 2f).toInt())
            for (s in 0..steps) {
                val f = s.toFloat() / steps
                val px = (x0 + (x1 - x0) * f).toInt().coerceIn(0, w - 1)
                val py = (y0 + (y1 - y0) * f).toInt().coerceIn(0, h - 1)
                wall[py * w + px] = true
            }
        }
        // 바깥(가장자리)에서 채워 닿지 않는 곳이 모두 안쪽
        val outside = ByteArray(w * h)
        val queue = IntArray(w * h)
        flood(wall, w, h, 0, outside, queue, allowEdge = true)
        val inside = ByteArray(w * h) { if (outside[it].toInt() == 0) 1 else 0 }
        val contours = trace(inside, w, h)
        val outer = contours.maxByOrNull { signedArea(it) } ?: return null
        if (signedArea(outer) <= 0f) return null
        val s = simplify(outer, 0.6f)
        val inv = 1f / k
        for (i in 0 until s.size / 2) {
            s[i * 2] = (s[i * 2] - pad) * inv + l
            s[i * 2 + 1] = (s[i * 2 + 1] - pad) * inv + t
        }
        return s
    }

    /** 이보다 어두우면 벽 (모눈·줄 노트의 옅은 선은 벽이 아님) */
    private const val WALL_LUM = 165

    /** 정사각 [r]px만큼 벽을 두껍게 (가로 한 번, 세로 한 번) */
    private fun dilate(src: BooleanArray, w: Int, h: Int, r: Int): BooleanArray {
        val tmp = BooleanArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            var last = -r - 1
            for (x in 0 until w) {
                if (src[row + x]) last = x
                if (x - last <= r) tmp[row + x] = true
            }
            last = w + r + 1
            for (x in w - 1 downTo 0) {
                if (src[row + x]) last = x
                if (last - x <= r) tmp[row + x] = true
            }
        }
        val out = BooleanArray(w * h)
        for (x in 0 until w) {
            var last = -r - 1
            for (y in 0 until h) {
                if (tmp[y * w + x]) last = y
                if (y - last <= r) out[y * w + x] = true
            }
            last = h + r + 1
            for (y in h - 1 downTo 0) {
                if (tmp[y * w + x]) last = y
                if (last - y <= r) out[y * w + x] = true
            }
        }
        return out
    }

    /** (sx, sy)에서 [reach]px 안의 가장 가까운 벽 아닌 점 */
    private fun nearestFree(wall: BooleanArray, w: Int, h: Int, sx: Int, sy: Int, reach: Int): Int? {
        if (sx !in 0 until w || sy !in 0 until h) return null
        if (!wall[sy * w + sx]) return sy * w + sx
        var best = -1
        var bestD = Int.MAX_VALUE
        for (dy in -reach..reach) {
            val y = sy + dy
            if (y !in 0 until h) continue
            for (dx in -reach..reach) {
                val x = sx + dx
                if (x !in 0 until w) continue
                val d = dx * dx + dy * dy
                if (d < bestD && d <= reach * reach && !wall[y * w + x]) { bestD = d; best = y * w + x }
            }
        }
        return if (best >= 0) best else null
    }

    /**
     * [seed]에서 벽이 아닌 곳을 4방향으로 채워 [mark]에 1. 채운 점 수, 가장자리에 닿으면 -1
     * ([allowEdge]면 가장자리에 닿아도 계속)
     */
    private fun flood(
        wall: BooleanArray, w: Int, h: Int, seed: Int, mark: ByteArray, queue: IntArray, allowEdge: Boolean = false,
    ): Int {
        if (wall[seed]) return 0
        var head = 0
        var tail = 0
        queue[tail++] = seed
        mark[seed] = 1
        while (head < tail) {
            val i = queue[head++]
            val x = i % w
            val y = i / w
            if (!allowEdge && (x == 0 || y == 0 || x == w - 1 || y == h - 1)) return -1
            if (x > 0) { val j = i - 1; if (mark[j].toInt() == 0 && !wall[j]) { mark[j] = 1; queue[tail++] = j } }
            if (x < w - 1) { val j = i + 1; if (mark[j].toInt() == 0 && !wall[j]) { mark[j] = 1; queue[tail++] = j } }
            if (y > 0) { val j = i - w; if (mark[j].toInt() == 0 && !wall[j]) { mark[j] = 1; queue[tail++] = j } }
            if (y < h - 1) { val j = i + w; if (mark[j].toInt() == 0 && !wall[j]) { mark[j] = 1; queue[tail++] = j } }
        }
        return tail
    }

    /** 영역을 [ok]인 점으로만 8방향 [steps]걸음 넓힌다 */
    private inline fun grow(region: ByteArray, w: Int, h: Int, steps: Int, queue: IntArray, ok: (Int) -> Boolean) {
        var tail = 0
        // 가장자리 점만 처음 줄에 (안쪽 점은 넓혀도 새 점이 없다)
        for (i in region.indices) {
            if (region[i].toInt() == 0) continue
            val x = i % w
            val y = i / w
            if ((x > 0 && region[i - 1].toInt() == 0) || (x < w - 1 && region[i + 1].toInt() == 0) ||
                (y > 0 && region[i - w].toInt() == 0) || (y < h - 1 && region[i + w].toInt() == 0)
            ) queue[tail++] = i
        }
        var head = 0
        repeat(steps) {
            val end = tail
            while (head < end) {
                val i = queue[head++]
                val x = i % w
                val y = i / w
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy !in 0 until h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx !in 0 until w) continue
                        val j = yy * w + xx
                        if (region[j].toInt() == 0 && ok(j)) {
                            region[j] = 1
                            queue[tail++] = j
                        }
                    }
                }
            }
        }
    }

    /**
     * 마칭 스퀘어로 [m] (1 = 안)의 윤곽들. 좌표는 픽셀 단위 (픽셀 (i, j)가 [i, i+1]×[j, j+1]).
     * 바깥 윤곽은 넓이 부호가 +, 구멍은 − ([signedArea])
     */
    internal fun trace(m: ByteArray, w: Int, h: Int): List<FloatArray> {
        val gw = w + 2
        fun at(i: Int, j: Int) = if (i < 0 || j < 0 || i >= w || j >= h) 0 else m[j * w + i].toInt()
        fun hid(i: Int, j: Int) = ((j + 1) * gw + (i + 1)) * 2
        fun vid(i: Int, j: Int) = ((j + 1) * gw + (i + 1)) * 2 + 1
        val next = IntIntMap()
        val c = IntArray(4)
        val ids = IntArray(4)
        for (j in -1 until h) {
            for (i in -1 until w) {
                c[0] = at(i, j); c[1] = at(i + 1, j); c[2] = at(i + 1, j + 1); c[3] = at(i, j + 1)
                val sum = c[0] + c[1] + c[2] + c[3]
                if (sum == 0 || sum == 4) continue
                // 시계 방향 차례의 변: 위, 오른쪽, 아래, 왼쪽
                ids[0] = hid(i, j); ids[1] = vid(i + 1, j); ids[2] = hid(i, j + 1); ids[3] = vid(i, j)
                for (e in 0 until 4) {
                    // 밖 → 안으로 드는 변과 시계 방향으로 다음에 만나는 (안 → 밖) 변을 잇는다.
                    // 나가는 변 → 드는 변 차례로 이어 바깥 윤곽이 (화면에서) 시계 방향이 되게
                    if (c[e] != 0 || c[(e + 1) % 4] == 0) continue
                    for (f in 1..3) {
                        val g = (e + f) % 4
                        if (c[g] != 0 && c[(g + 1) % 4] == 0) { next.put(ids[g], ids[e]); break }
                    }
                }
            }
        }
        val out = ArrayList<FloatArray>()
        val keys = next.keys()
        val pts = FloatArrayList()
        for (start in keys) {
            if (next.get(start) < 0) continue
            pts.clear()
            var id = start
            while (true) {
                val nx = next.get(id)
                if (nx < 0) break
                next.put(id, -1)
                val base = id / 2
                val gx = base % gw - 1
                val gy = base / gw - 1
                // 픽셀 가운데 (i + 0.5, j + 0.5) 사이의 변 가운데
                if (id % 2 == 0) pts.add(gx + 1f, gy + 0.5f) else pts.add(gx + 0.5f, gy + 1f)
                id = nx
            }
            if (pts.size >= 6) out.add(pts.toArray())
        }
        return out
    }

    /** 다각형 넓이 (화면 좌표 y 아래로: 바깥 윤곽이 +) */
    fun signedArea(p: FloatArray): Float {
        val n = p.size / 2
        var a = 0.0
        for (i in 0 until n) {
            val j = (i + 1) % n
            a += p[i * 2].toDouble() * p[j * 2 + 1] - p[j * 2].toDouble() * p[i * 2 + 1]
        }
        return (a / 2).toFloat()
    }

    /** 닫힌 꺾은선을 [eps] 안에서 줄인다 (더글러스–포이커, 가장 먼 두 점에서 둘로 나눠) */
    fun simplify(p: FloatArray, eps: Float): FloatArray {
        val n = p.size / 2
        if (n < 4) return p.copyOf()
        var far = 0
        var farD = -1f
        for (i in 1 until n) {
            val d = hypot(p[i * 2] - p[0], p[i * 2 + 1] - p[1])
            if (d > farD) { farD = d; far = i }
        }
        val keep = BooleanArray(n)
        keep[0] = true
        keep[far] = true
        val stack = ArrayDeque<IntArray>()
        stack.addLast(intArrayOf(0, far))
        stack.addLast(intArrayOf(far, n))
        while (stack.isNotEmpty()) {
            val (a, b) = stack.removeLast().let { it[0] to it[1] }
            val bi = b % n
            val ax = p[a * 2]; val ay = p[a * 2 + 1]
            val bx = p[bi * 2]; val by = p[bi * 2 + 1]
            var best = -1
            var bestD = eps
            for (i in a + 1 until b) {
                val d = segDist(p[i * 2], p[i * 2 + 1], ax, ay, bx, by)
                if (d > bestD) { bestD = d; best = i }
            }
            if (best >= 0) {
                keep[best] = true
                stack.addLast(intArrayOf(a, best))
                stack.addLast(intArrayOf(best, b))
            }
        }
        val out = FloatArrayList()
        for (i in 0 until n) if (keep[i]) out.add(p[i * 2], p[i * 2 + 1])
        return out.toArray()
    }

    private fun segDist(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        if (len2 == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
        return hypot(ax + t * vx - px, ay + t * vy - py)
    }

    // ================= 다각형 보정 =================

    /**
     * 닫힌 윤곽 [p] (쪽 좌표)에서 꺾인 곳을 찾아, 꺾인 곳 사이가 거의 곧으면 곧은 변으로 편다.
     * 모든 변이 곧으면 다각형으로 보고, 가로·세로에 가까운 변(5° 안)이 있으면 살짝 돌려 딱 맞춘다.
     * 꺾인 곳이 없거나 (원 따위) 곧은 변이 없으면 그대로
     */
    fun straighten(p: FloatArray): FloatArray {
        val n0 = p.size / 2
        if (n0 < 3) return p
        var perim = 0f
        for (i in 0 until n0) {
            val j = (i + 1) % n0
            perim += hypot(p[j * 2] - p[i * 2], p[j * 2 + 1] - p[i * 2 + 1])
        }
        if (perim < 10f) return p
        // 둘레를 고르게 다시 뽑는다
        val n = min(480, max(60, (perim / 1.2f).roundToInt()))
        val step = perim / n
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        run {
            var k = 0
            var acc = 0f
            var target = 0f
            for (i in 0 until n0) {
                val j = (i + 1) % n0
                val x0 = p[i * 2]; val y0 = p[i * 2 + 1]
                val seg = hypot(p[j * 2] - x0, p[j * 2 + 1] - y0)
                while (seg > 0f && acc + seg >= target && k < n) {
                    val t = (target - acc) / seg
                    xs[k] = x0 + (p[j * 2] - x0) * t
                    ys[k] = y0 + (p[j * 2 + 1] - y0) * t
                    k++
                    target += step
                }
                acc += seg
            }
            while (k < n) { xs[k] = p[0]; ys[k] = p[1]; k++ }
        }
        fun ix(i: Int) = ((i % n) + n) % n
        // 꺾인 각: 앞뒤 [win]점 떨어진 방향의 차이
        val win = max(2, min(n / 12, (max(3f, perim * 0.025f) / step).roundToInt()))
        val turn = FloatArray(n)
        for (i in 0 until n) {
            val a = ix(i - win); val b = ix(i + win)
            val a1 = atan2(ys[i] - ys[a], xs[i] - xs[a])
            val a2 = atan2(ys[b] - ys[i], xs[b] - xs[i])
            var d = abs(a2 - a1)
            if (d > PI) d = (2 * PI - d).toFloat()
            turn[i] = d
        }
        val th = (38.0 * PI / 180).toFloat()
        val corners = ArrayList<Int>()
        for (i in 0 until n) {
            if (turn[i] < th) continue
            var isMax = true
            for (d in -win..win) {
                if (d == 0) continue
                val j = ix(i + d)
                if (turn[j] > turn[i] || (turn[j] == turn[i] && d < 0)) { isMax = false; break }
            }
            if (isMax) corners.add(i)
        }
        if (corners.size < 2) return p
        val m = corners.size
        // 꺾인 곳 사이 구간마다 곧은지 보고, 곧으면 (꺾인 곳 근처는 빼고) 직선을 맞춘다
        class Line(val cx: Float, val cy: Float, val ux: Float, val uy: Float)
        val lines = arrayOfNulls<Line>(m)
        for (s in 0 until m) {
            val a = corners[s]
            val b = corners[(s + 1) % m]
            val len = ix(b - a).let { if (it == 0) n else it }
            val ax = xs[a]; val ay = ys[a]; val bx = xs[b]; val by = ys[b]
            val chord = hypot(bx - ax, by - ay)
            if (chord < 3f) continue
            var dev = 0f
            for (t in 1 until len) {
                val i = ix(a + t)
                dev = max(dev, segDist(xs[i], ys[i], ax, ay, bx, by))
            }
            if (dev > max(1.5f, chord * 0.055f)) continue
            // 주성분 직선
            val skip = min(win, len / 4)
            var mx = 0f; var my = 0f; var cnt = 0
            for (t in skip..len - skip) { val i = ix(a + t); mx += xs[i]; my += ys[i]; cnt++ }
            if (cnt < 2) continue
            mx /= cnt; my /= cnt
            var sxx = 0f; var sxy = 0f; var syy = 0f
            for (t in skip..len - skip) {
                val i = ix(a + t)
                val dx = xs[i] - mx; val dy = ys[i] - my
                sxx += dx * dx; sxy += dx * dy; syy += dy * dy
            }
            val ang = 0.5 * atan2(2.0 * sxy, (sxx - syy).toDouble())
            lines[s] = Line(mx, my, cos(ang).toFloat(), sin(ang).toFloat())
        }
        if (lines.all { it == null }) return p
        // 꼭짓점: 양쪽 변이 곧으면 두 직선이 만나는 곳, 한쪽만 곧으면 그 직선에 내린 곳
        val vx = FloatArray(m)
        val vy = FloatArray(m)
        for (c in 0 until m) {
            val i = corners[c]
            val before = lines[(c - 1 + m) % m]
            val after = lines[c]
            vx[c] = xs[i]; vy[c] = ys[i]
            if (before != null && after != null) {
                val det = before.ux * after.uy - before.uy * after.ux
                if (abs(det) > 0.15f) {
                    val t = ((after.cx - before.cx) * after.uy - (after.cy - before.cy) * after.ux) / det
                    val qx = before.cx + before.ux * t
                    val qy = before.cy + before.uy * t
                    if (hypot(qx - xs[i], qy - ys[i]) < max(6f, step * win * 2.5f)) { vx[c] = qx; vy[c] = qy }
                }
            } else (before ?: after)?.let { l ->
                val t = (xs[i] - l.cx) * l.ux + (ys[i] - l.cy) * l.uy
                vx[c] = l.cx + l.ux * t; vy[c] = l.cy + l.uy * t
            }
        }
        if (lines.all { it != null }) snapToAxes(vx, vy)
        val out = FloatArrayList()
        for (c in 0 until m) {
            out.add(vx[c], vy[c])
            if (lines[c] != null) continue
            // 굽은 변은 그린 그대로 (꺾인 곳 바로 옆은 새 꼭짓점과 잇기 위해 빼고)
            val a = corners[c]
            val b = corners[(c + 1) % m]
            val len = ix(b - a).let { if (it == 0) n else it }
            for (t in 1 until len) {
                val i = ix(a + t)
                out.add(xs[i], ys[i])
            }
        }
        return out.toArray()
    }

    /** 가로·세로에 가장 가까운 변이 5° 안이면 다각형을 무게중심 둘레로 돌려 딱 맞춘다 */
    private fun snapToAxes(vx: FloatArray, vy: FloatArray) {
        val m = vx.size
        var best = 0.0
        var bestAbs = Double.MAX_VALUE
        for (i in 0 until m) {
            val j = (i + 1) % m
            val a = atan2((vy[j] - vy[i]).toDouble(), (vx[j] - vx[i]).toDouble())
            val unit = PI / 2
            val d = (a / unit).roundToInt() * unit - a
            if (abs(d) <= 5.0 * PI / 180 && abs(d) < bestAbs) { bestAbs = abs(d); best = d }
        }
        if (best == 0.0) return
        val cx = vx.average().toFloat()
        val cy = vy.average().toFloat()
        val cs = cos(best).toFloat()
        val sn = sin(best).toFloat()
        for (i in 0 until m) {
            val dx = vx[i] - cx; val dy = vy[i] - cy
            vx[i] = cx + dx * cs - dy * sn
            vy[i] = cy + dx * sn + dy * cs
        }
    }
}

/** 늘어나는 Float 목록 (상자 없이) */
internal class FloatArrayList {
    private var a = FloatArray(256)
    var size = 0
        private set

    fun add(x: Float, y: Float) {
        if (size + 2 > a.size) a = a.copyOf(a.size * 2)
        a[size++] = x
        a[size++] = y
    }

    fun clear() { size = 0 }
    fun toArray(): FloatArray = a.copyOf(size)
}

/** Int → Int 사전 (열린 주소, 상자 없이). 없는 열쇠는 -1 */
internal class IntIntMap {
    private var keys = IntArray(1 shl 12) { EMPTY }
    private var vals = IntArray(1 shl 12)
    private var order = IntArray(1 shl 10)
    private var n = 0

    private fun slot(k: Int, ks: IntArray): Int {
        val mask = ks.size - 1
        var i = (k * -0x61c88647) ushr 7 and mask
        while (ks[i] != EMPTY && ks[i] != k) i = (i + 1) and mask
        return i
    }

    fun put(k: Int, v: Int) {
        var i = slot(k, keys)
        if (keys[i] == EMPTY) {
            if ((n + 1) * 2 > keys.size) {
                grow()
                i = slot(k, keys)
            }
            keys[i] = k
            if (n == order.size) order = order.copyOf(n * 2)
            order[n++] = k
        }
        vals[i] = v
    }

    fun get(k: Int): Int {
        val i = slot(k, keys)
        return if (keys[i] == EMPTY) -1 else vals[i]
    }

    /** 넣은 차례대로의 열쇠들 */
    fun keys(): IntArray = order.copyOf(n)

    private fun grow() {
        val ok = keys
        val ov = vals
        keys = IntArray(ok.size * 2) { EMPTY }
        vals = IntArray(ok.size * 2)
        for (i in ok.indices) if (ok[i] != EMPTY) {
            val j = slot(ok[i], keys)
            keys[j] = ok[i]
            vals[j] = ov[i]
        }
    }

    private companion object {
        const val EMPTY = Int.MIN_VALUE
    }
}
