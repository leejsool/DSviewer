package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
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
import kotlin.math.roundToInt

/**
 * 테이프 무늬. 무늬는 한 변이 [TapeTile.PERIOD] pt인 칸을 되풀이한 것이고,
 * 칸 안의 모양([TapeTile.shapes])을 화면(비트맵 무늬)과 PDF(타일 무늬)가 같이 쓴다
 */
enum class TapePattern(val label: String) {
    SOLID("단색"), STRIPE("빗금"), CHECK("체크"), DOT("물방울"), GRID("격자");

    companion object {
        fun of(name: String?) = entries.firstOrNull { it.name == name } ?: STRIPE
    }
}

/** 테이프 획의 모양: [rect]면 네 점이 네모의 모서리, 아니면 굵기가 있는 선 (펜으로 붙인 테이프) */
class TapeStyle(val pattern: TapePattern, val rect: Boolean)

object TapeTile {
    /** 무늬 한 칸의 크기 (pt) */
    const val PERIOD = 6f

    sealed class Shape
    class Box(val l: Float, val t: Float, val r: Float, val b: Float) : Shape()
    class Dot(val cx: Float, val cy: Float, val r: Float) : Shape()
    class Poly(val pts: FloatArray) : Shape()

    /** 칸 (0, 0) ~ (PERIOD, PERIOD) 안의 무늬 모양 */
    fun shapes(p: TapePattern): List<Shape> {
        val s = PERIOD
        return when (p) {
            TapePattern.SOLID -> emptyList()
            TapePattern.STRIPE -> {
                // 45° 빗금: x + y = s 둘레의 띠와, 이웃 칸에서 이어지는 두 모서리 조각
                val a = s * 0.25f
                listOf(
                    Poly(floatArrayOf(0f, s - a, s - a, 0f, s, a, a, s)),
                    Poly(floatArrayOf(0f, 0f, a, 0f, 0f, a)),
                    Poly(floatArrayOf(s, s, s - a, s, s, s - a)),
                )
            }
            TapePattern.CHECK -> listOf(Box(0f, 0f, s / 2, s / 2), Box(s / 2, s / 2, s, s))
            TapePattern.DOT -> {
                // 엇갈린 물방울: 가운데 하나와 네 모서리 (모서리 것은 이웃 칸과 합쳐 하나)
                val r = s * 0.17f
                listOf(Dot(s / 2, s / 2, r), Dot(0f, 0f, r), Dot(s, 0f, r), Dot(0f, s, r), Dot(s, s, r))
            }
            TapePattern.GRID -> {
                val t = s * 0.07f
                listOf(Box(0f, s / 2 - t, s, s / 2 + t), Box(s / 2 - t, 0f, s / 2 + t, s))
            }
        }
    }

    /** 바탕색 위에 그릴 무늬 색: 밝은 테이프는 조금 어둡게, 어두운 테이프는 조금 밝게 */
    fun patternColor(base: Int): Int {
        val opaque = base or (0xFF shl 24)
        return if (Color.luminance(opaque) > 0.35f) ColorUtils.blendARGB(opaque, Color.BLACK, 0.2f)
        else ColorUtils.blendARGB(opaque, Color.WHITE, 0.28f)
    }

    /** 보이게 한 테이프의 테두리 색 (흰 종이 위에서도 보이게 바탕색보다 진하게) */
    fun edgeColor(base: Int): Int = ColorUtils.blendARGB(base or (0xFF shl 24), Color.BLACK, 0.35f)

    // ---- 화면: 칸 하나를 비트맵으로 그려 되풀이 ----

    /** 비트맵 칸의 해상도 (1pt당 px). 크게 확대해도 무늬가 흐려지지 않을 만큼 */
    const val TILE_PX = 12f
    private val shaders = HashMap<Long, BitmapShader>()

    /** 무늬 셰이더 (단색이면 null). 셰이더 좌표 1px = 1/TILE_PX pt */
    fun shader(p: TapePattern, base: Int): BitmapShader? {
        if (p == TapePattern.SOLID) return null
        val key = (p.ordinal.toLong() shl 32) or (base.toLong() and 0xFFFFFFFFL)
        shaders[key]?.let { return it }
        val n = (PERIOD * TILE_PX).roundToInt()
        val bmp = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(TILE_PX, TILE_PX)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = patternColor(base) }
        val path = Path()
        for (sh in shapes(p)) when (sh) {
            is Box -> c.drawRect(sh.l, sh.t, sh.r, sh.b, paint)
            is Dot -> c.drawCircle(sh.cx, sh.cy, sh.r, paint)
            is Poly -> {
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

private val tapeFill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
private val tapeEdge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeJoin = Paint.Join.ROUND
}
private val tapeMatrix = Matrix()

/**
 * 테이프: 가린 상태면 바탕색 + 무늬로 덮고 (아래 PDF·필기가 보이지 않는다),
 * 보이게 한 상태면 테두리만 그린다
 */
fun drawInkTape(c: Canvas, st: Stroke, alphaMul: Float) {
    val style = st.tape ?: return
    val a = (255 * alphaMul).roundToInt()
    if (st.revealed) {
        tapeEdge.color = TapeTile.edgeColor(st.color)
        tapeEdge.alpha = a
        tapeEdge.strokeWidth = 1f
        c.drawPath(st.tapeOutline(), tapeEdge)
        return
    }
    val shape = st.tapeShape()
    tapeFill.shader = null
    tapeFill.color = st.color or (0xFF shl 24)
    tapeFill.alpha = a
    c.drawPath(shape, tapeFill)
    val sh = TapeTile.shader(style.pattern, st.color) ?: return
    // 무늬는 테이프의 첫 점에 붙인다 (옮겨도 무늬가 테이프 위에서 미끄러지지 않게)
    tapeMatrix.setScale(1f / TapeTile.TILE_PX, 1f / TapeTile.TILE_PX)
    tapeMatrix.postTranslate(st.x(0), st.y(0))
    sh.setLocalMatrix(tapeMatrix)
    tapeFill.shader = sh
    tapeFill.color = Color.BLACK
    tapeFill.alpha = a
    c.drawPath(shape, tapeFill)
    tapeFill.shader = null
}

/** 옵션 줄의 무늬 칸: 지금 테이프 색으로 칠한 둥근 네모에 그 무늬 */
class TapePatternDrawable(private val pattern: TapePattern, private val color: Int, private val density: Float) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        this.color = Color.argb(70, 0, 0, 0)
    }
    private val m = Matrix()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val inset = b.width() * 0.08f
        val l = b.left + inset; val t = b.top + inset; val r = b.right - inset; val bt = b.bottom - inset
        val rad = b.width() * 0.16f
        paint.shader = null
        paint.color = color or (0xFF shl 24)
        canvas.drawRoundRect(l, t, r, bt, rad, rad, paint)
        TapeTile.shader(pattern, color)?.let { sh ->
            // 무늬 한 칸이 7dp쯤 되게
            val k = 7f * density / (TapeTile.PERIOD * TapeTile.TILE_PX)
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
