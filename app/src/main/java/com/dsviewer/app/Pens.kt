package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Matrix
import android.graphics.Path
import android.graphics.Shader
import java.nio.ByteBuffer
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan

/**
 * 펜 종류 (삼성노트·굿노트·플렉슬의 펜들을 본뜸). [code]는 PDF에 저장하는 획 종류 글자
 * (사인펜은 예전부터 쓰던 'P'), [reach]는 가장 굵을 때 굵기 배율 (지우개·선택이 닿는 범위).
 * 사인펜은 예전 펜 그대로 (필압 구간마다 둥근 선), 나머지는 굵기가 점마다 매끄럽게 바뀌는 채운 외곽선으로 그린다
 */
enum class PenStyle(val code: Char, val label: String, val reach: Float) {
    FELT('P', "사인펜", 1f),
    BALL('B', "볼펜", 1.1f),
    FOUNTAIN('F', "만년필", 1.3f),
    BRUSH('R', "붓펜", 1.88f),
    PENCIL('C', "연필", 1.15f),
    CALLIGRAPHY('G', "캘리그래피", 1.15f);

    /**
     * 저장된 필압 값 p(0~1) → 굵기 배율. 만년필·붓펜은 쓰는 동안 속도와 처음·끝 가늘기를 p에 미리 넣어 두므로
     * (그래서 지우개로 자른 조각도 모양이 그대로) 여기서는 p만 본다. 캘리그래피는 펜촉 길이 배율
     */
    fun factor(p: Float): Float {
        val q = p.coerceIn(0f, 1f)
        // 1을 넘는 값은 연필·붓펜을 눕혀 쥐었을 때 더 넓어지는 만큼 ([PenTilt]). 옛 필기는 1 이하라 그대로
        val extra = p.coerceIn(1f, 2f) - 1f
        return when (this) {
            FELT -> Stroke.quantize(q)
            BALL -> 0.8f + 0.3f * q
            FOUNTAIN -> 0.3f + 1.0f * q
            BRUSH -> 0.08f + 1.8f * q + PenTilt.BRUSH_EXTRA_SLOPE * extra
            PENCIL -> 0.65f + 0.5f * q + PenTilt.PENCIL_EXTRA_SLOPE * extra
            CALLIGRAPHY -> 0.55f + 0.6f * q
        }
    }

    /** 굵기 칸: 범위, 저장 이름(사인펜은 예전 펜 이름 그대로), 처음 굵기 */
    val widthKind
        get() = when (this) {
            BRUSH -> WidthKind.BRUSH
            CALLIGRAPHY -> WidthKind.NIB
            else -> WidthKind.PEN
        }
    val widthKey get() = if (this == FELT) WidthKind.PEN.key else "penWidth_$name"
    val widthDefaults
        get() = when (this) {
            BALL -> floatArrayOf(0.5f, 0.9f, 1.5f)
            FOUNTAIN -> floatArrayOf(0.6f, 1.1f, 1.9f)
            PENCIL -> floatArrayOf(0.6f, 1.1f, 2f)
            else -> widthKind.defaults
        }

    /** 툴바 펜 몸통 그림 */
    val bodyIcon
        get() = when (this) {
            FELT -> R.drawable.ic_pen_body
            BALL -> R.drawable.ic_pen_ball_body
            FOUNTAIN -> R.drawable.ic_pen_fountain_body
            BRUSH -> R.drawable.ic_pen_brush_body
            PENCIL -> R.drawable.ic_pen_pencil_body
            CALLIGRAPHY -> R.drawable.ic_pen_chisel_body
        }

    companion object {
        fun of(code: Char) = entries.firstOrNull { it.code == code }
        fun named(n: String?) = entries.firstOrNull { it.name == n } ?: FELT

        /** 연필 불투명도 */
        const val PENCIL_ALPHA = 0.88f
        /** 캘리그래피 펜촉 각도: 쪽에서 오른쪽 위로 45° */
        val NIB_X = cos(Math.toRadians(-45.0)).toFloat()
        val NIB_Y = sin(Math.toRadians(-45.0)).toFloat()
    }
}

/** 손떨림 보정 단계 (끔·약하게·보통·강하게): 새 점 쪽으로 따라가는 비율 */
object PenSmoothing {
    val labels = listOf("끔", "약하게", "보통", "강하게")
    private val follow = floatArrayOf(1f, 0.5f, 0.3f, 0.16f)
    fun follow(level: Int) = follow[level.coerceIn(0, follow.size - 1)]
}

/**
 * 펜으로 쓰는 동안 점을 다듬는다: 손떨림 보정(위치를 부드럽게), 필압 다듬기,
 * 만년필·붓펜은 빨리 그을수록 가늘게 + 시작을 가늘게. 끝을 가늘게 하는 것은 뗄 때 [finish]
 */
class PenInput {
    private var style = PenStyle.FELT
    private var follow = 1f
    private var width = 1f
    /** 보정한 위치 (쪽 좌표) */
    private var sx = 0f
    private var sy = 0f
    /** 마지막으로 받은 실제 위치·시각 */
    private var rx = 0f
    private var ry = 0f
    private var lastT = 0L
    private var pressure = 0f
    /** 이번 획에서 가장 센 필압 */
    private var peak = 0f
    /** 속도 (dp/ms, 부드럽게) */
    private var speed = 0f
    /** 지금까지 넣은 획 길이 (쪽 좌표) */
    private var dist = 0f
    /** 펜 기울기 (라디안, 부드럽게). 연필·붓펜이 눕혀 쥔 만큼 넓어진다 */
    private var tilt = 0f

    /** 이번에 넣을 점 */
    var x = 0f; private set
    var y = 0f; private set
    var p = 0f; private set

    fun begin(style: PenStyle, width: Float, smoothing: Int, px: Float, py: Float, pr: Float, t: Long, tiltRad: Float = 0f) {
        this.style = style
        this.width = width
        follow = PenSmoothing.follow(smoothing)
        sx = px; sy = py; rx = px; ry = py
        lastT = t
        pressure = pr
        peak = pr
        speed = 0f
        dist = 0f
        tilt = tiltRad
        x = px; y = py
        p = shape(pr)
    }

    /**
     * 새로 받은 점. [dpPerPt]는 쪽 1pt가 화면에서 몇 dp인지 (속도를 확대와 상관없이 재려고),
     * [minDist]보다 가까우면 넣지 않는다 (false)
     */
    fun move(
        px: Float, py: Float, pr: Float, t: Long, dpPerPt: Float, minDist: Float, lastX: Float, lastY: Float,
        tiltRad: Float = 0f,
    ): Boolean {
        val dt = (t - lastT).coerceAtLeast(1L)
        val v = hypot(px - rx, py - ry) * dpPerPt / dt
        speed = speed * 0.75f + min(v, 4f) * 0.25f
        rx = px; ry = py; lastT = t
        peak = max(peak, pr)
        sx += (px - sx) * follow
        sy += (py - sy) * follow
        val d = hypot(sx - lastX, sy - lastY)
        if (d < minDist) return false
        pressure = pressure * 0.5f + pr * 0.5f
        tilt = tilt * 0.6f + tiltRad * 0.4f
        dist += d
        x = sx; y = sy
        p = shape(pressure)
        return true
    }

    /** 필압 → 저장할 값 (속도·시작 가늘기를 넣어서) */
    private fun shape(pr: Float): Float {
        val fast = ((speed - 0.15f) / 1.2f).coerceIn(0f, 1f)
        return when (style) {
            PenStyle.FOUNTAIN -> pr * (1f - 0.35f * fast) * taper(dist, width * 1.2f, 0.55f)
            PenStyle.BRUSH -> PenTilt.brush(pr.pow(1.2f) * (1f - 0.5f * fast) * taper(dist, width * 2.5f, 0.2f), PenTilt.shade(tilt))
            PenStyle.PENCIL -> PenTilt.pencil(pr, PenTilt.shade(tilt))
            else -> pr
        }
    }

    /** 끝에서 [d]만큼 떨어진 곳의 가늘기 배율: 끝은 [low], 길이 [len]을 지나면 1 */
    private fun taper(d: Float, len: Float, low: Float): Float {
        if (len <= 0f || d >= len) return 1f
        val t = d / len
        return low + (1f - low) * (t * (2f - t))
    }

    /**
     * 뗄 때: 손떨림 보정으로 뒤처진 끝을 뗀 자리까지 잇고, 만년필·붓펜은 끝을 가늘게 한다
     * (빨리 떼며 그을수록 더 가늘게)
     */
    fun finish(st: Stroke, bridge: Boolean = true) {
        if (st.count == 0) return
        val lx = st.x(st.count - 1)
        val ly = st.y(st.count - 1)
        val gap = hypot(rx - lx, ry - ly)
        // 자를 따라 그은 획은 곧게 둔다: 뗀 자리(자 밖일 수 있음)까지 잇지 않는다
        if (bridge && follow < 1f && gap > 0.05f) {
            val n = 4
            val lp = st.p(st.count - 1)
            for (k in 1..n) st.add(lx + (rx - lx) * k / n, ly + (ry - ly) * k / n, lp)
        }
        if (style != PenStyle.FOUNTAIN && style != PenStyle.BRUSH) return
        // 톡 찍은 점: 시작 가늘기 때문에 거의 안 보이지 않게 가장 센 필압으로
        if (st.length() < width * 1.5f) {
            for (i in 0 until st.count) st.setPressure(i, max(st.p(i), peak))
            return
        }
        val (lenK, low) = when (style) {
            PenStyle.FOUNTAIN -> 1.2f to 0.55f
            PenStyle.BRUSH -> 3f to 0.15f
            else -> return
        }
        val total = st.length()
        val len = min(width * lenK, total * 0.4f)
        if (len <= 0f) return
        val fast = ((speed - 0.15f) / 1.2f).coerceIn(0f, 1f)
        // 천천히 떼면 덜 가늘게
        val end = low + (1f - low) * (1f - fast) * 0.5f
        var d = 0f
        var i = st.count - 1
        while (i >= 0 && d < len) {
            st.setPressure(i, st.p(i) * taper(d, len, end))
            if (i > 0) d += hypot(st.x(i) - st.x(i - 1), st.y(i) - st.y(i - 1))
            i--
        }
    }
}

/** 외곽선을 받아 적는 곳 (화면용 Path, PDF 내용) */
interface OutlineSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float)
    fun close()
}

class PathSink(val path: Path) : OutlineSink {
    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) = path.cubicTo(x1, y1, x2, y2, x3, y3)
    override fun close() = path.close()
}

/**
 * 굵기가 바뀌는 펜의 외곽선. 이웃한 두 점마다 볼록한 조각 하나 (둥근 펜은 두 원을 감싼 모양,
 * 캘리그래피는 납작한 펜촉 두 개를 감싼 모양)를 모두 같은 방향으로 돌아 그리므로,
 * 0이 아닌 감김(nonzero)으로 채우면 겹친 곳 없이 합쳐진다 (급히 꺾여도 구멍이 생기지 않는다)
 */
object PenOutline {
    private const val TWO_PI = (Math.PI * 2).toFloat()

    private fun radius(st: Stroke, i: Int) = max(st.width * st.pen.factor(st.p(i)) / 2f, 0.06f)

    /**
     * [from]번째 점부터 조각을 덧붙인다 ([kept]는 바로 앞에서 마지막으로 쓴 점). 너무 촘촘한 점은 건너뛰고
     * ([forceLast]면 마지막 점은 꼭 쓴다), 마지막으로 쓴 점을 돌려준다
     */
    fun append(st: Stroke, sink: OutlineSink, kept: Int, from: Int, forceLast: Boolean): Int {
        val n = st.count
        if (n == 0) return 0
        val nib = st.pen == PenStyle.CALLIGRAPHY
        var k = kept
        var start = from
        if (from == 0) {
            if (nib) nibPiece(st, sink, 0, 0) else circle(sink, st.x(0), st.y(0), radius(st, 0))
            k = 0
            start = 1
        }
        for (i in start until n) {
            val ri = radius(st, i)
            val rk = radius(st, k)
            val d = hypot(st.x(i) - st.x(k), st.y(i) - st.y(k))
            if (d < 0.35f * min(ri, rk) && !(forceLast && i == n - 1)) continue
            if (nib) nibPiece(st, sink, k, i) else capsule(sink, st.x(k), st.y(k), rk, st.x(i), st.y(i), ri)
            k = i
        }
        return k
    }

    fun build(st: Stroke, sink: OutlineSink) {
        append(st, sink, 0, 0, forceLast = true)
    }

    /**
     * PDF 외형용: 이웃한 두 점의 평균 굵기를 펜 굵기의 1/24 단위로 나눠, 같은 굵기가 이어지는 구간마다 콜백
     * (둥근 끝·둥근 꺾임으로 그으면 외곽선을 채운 것과 거의 같다)
     */
    fun forEachWidthGroup(st: Stroke, block: (w: Float, from: Int, to: Int) -> Unit) {
        val n = st.count
        if (n == 0) return
        fun q(f: Float) = max(Math.round(f * 24f), 1) / 24f
        if (n == 1) {
            block(st.width * q(st.pen.factor(st.p(0))), 0, 0)
            return
        }
        var start = 0
        var cur = q((st.pen.factor(st.p(0)) + st.pen.factor(st.p(1))) / 2f)
        for (i in 2 until n) {
            val f = q((st.pen.factor(st.p(i - 1)) + st.pen.factor(st.p(i))) / 2f)
            if (f != cur) {
                block(st.width * cur, start, i - 1)
                start = i - 1
                cur = f
            }
        }
        block(st.width * cur, start, n - 1)
    }

    /** 두 원 (x1, y1, r1), (x2, y2, r2)를 감싼 볼록한 조각 */
    private fun capsule(sink: OutlineSink, x1: Float, y1: Float, r1: Float, x2: Float, y2: Float, r2: Float) {
        val dx = x2 - x1
        val dy = y2 - y1
        val d = hypot(dx, dy)
        if (d <= abs(r1 - r2) + 1e-4f) {
            // 한 원이 다른 원 안에 든다
            if (r2 >= r1) circle(sink, x2, y2, r2) else circle(sink, x1, y1, r1)
            return
        }
        val phi = atan2(dy, dx)
        val a = acos(((r1 - r2) / d).coerceIn(-1f, 1f))
        // 앞 원의 바깥쪽 호 → 옆 접선 → 뒤 원의 바깥쪽 호 → (닫으며) 다른 옆 접선. 각이 늘어나는 쪽으로 돈다
        sink.moveTo(x2 + r2 * cos(phi - a), y2 + r2 * sin(phi - a))
        arc(sink, x2, y2, r2, phi - a, 2 * a)
        sink.lineTo(x1 + r1 * cos(phi + a), y1 + r1 * sin(phi + a))
        arc(sink, x1, y1, r1, phi + a, TWO_PI - 2 * a)
        sink.close()
    }

    private fun circle(sink: OutlineSink, cx: Float, cy: Float, r: Float) {
        sink.moveTo(cx + r, cy)
        arc(sink, cx, cy, r, 0f, TWO_PI)
        sink.close()
    }

    /** 지금 점(호의 시작)에서 이어 [start]부터 [sweep]만큼 원호 (베지어로, 90°보다 작게 나눠) */
    private fun arc(sink: OutlineSink, cx: Float, cy: Float, r: Float, start: Float, sweep: Float) {
        if (sweep <= 0f) return
        val m = ceil(sweep / (Math.PI.toFloat() / 2f) - 1e-4f).toInt().coerceAtLeast(1)
        val step = sweep / m
        val k = 4f / 3f * tan(step / 4f) * r
        var a0 = start
        var c0 = cos(a0)
        var s0 = sin(a0)
        for (j in 0 until m) {
            val a1 = a0 + step
            val c1 = cos(a1)
            val s1 = sin(a1)
            sink.cubicTo(
                cx + r * c0 - k * s0, cy + r * s0 + k * c0,
                cx + r * c1 + k * s1, cy + r * s1 - k * c1,
                cx + r * c1, cy + r * s1,
            )
            a0 = a1; c0 = c1; s0 = s1
        }
    }

    private val hullIn = FloatArray(16)
    private val hullOut = FloatArray(36)

    /** 캘리그래피: [a]번째와 [b]번째 점의 납작한 펜촉(긴 네모) 두 개를 감싼 볼록 다각형 */
    private fun nibPiece(st: Stroke, sink: OutlineSink, a: Int, b: Int) {
        synchronized(hullIn) { nibPieceLocked(st, sink, a, b) }
    }

    private fun nibPieceLocked(st: Stroke, sink: OutlineSink, a: Int, b: Int) {
        var n = 0
        val thin = max(st.width * 0.07f, 0.12f)
        // 펜촉에 수직인 방향
        val vx = -PenStyle.NIB_Y
        val vy = PenStyle.NIB_X
        for (i in if (a == b) intArrayOf(a) else intArrayOf(a, b)) {
            val h = radius(st, i)
            for (su in intArrayOf(-1, 1)) for (sv in intArrayOf(-1, 1)) {
                hullIn[n * 2] = st.x(i) + su * h * PenStyle.NIB_X + sv * thin * vx
                hullIn[n * 2 + 1] = st.y(i) + su * h * PenStyle.NIB_Y + sv * thin * vy
                n++
            }
        }
        val m = hull(hullIn, n, hullOut)
        if (m < 3) return
        sink.moveTo(hullOut[0], hullOut[1])
        for (k in 1 until m) sink.lineTo(hullOut[k * 2], hullOut[k * 2 + 1])
        sink.close()
    }

    /** 점 [n]개의 볼록 껍질 (모노톤 체인, 늘 같은 방향). 꼭짓점 수를 돌려준다 */
    private fun hull(pts: FloatArray, n: Int, out: FloatArray): Int {
        val idx = (0 until n).sortedWith(compareBy({ pts[it * 2] }, { pts[it * 2 + 1] }))
        fun cross(o: Int, a: Int, b: Int): Float {
            val ox = out[o * 2]; val oy = out[o * 2 + 1]
            return (out[a * 2] - ox) * (pts[b * 2 + 1] - oy) - (out[a * 2 + 1] - oy) * (pts[b * 2] - ox)
        }
        var k = 0
        fun push(i: Int) { out[k * 2] = pts[i * 2]; out[k * 2 + 1] = pts[i * 2 + 1]; k++ }
        for (i in idx) {
            while (k >= 2 && cross(k - 2, k - 1, i) <= 0f) k--
            push(i)
        }
        val lower = k + 1
        for (j in idx.indices.reversed()) {
            if (j == idx.size - 1) continue
            val i = idx[j]
            while (k >= lower && cross(k - 2, k - 1, i) <= 0f) k--
            push(i)
        }
        return k - 1
    }
}

/** 연필 결: 종이 결처럼 군데군데 옅은 점무늬 (쪽 좌표에 붙어 확대하면 같이 커진다) */
object PencilGrain {
    private const val SIZE = 128
    /** 결 한 칸이 쪽에서 몇 pt인지 */
    private const val CELL = 0.4f

    val shader: BitmapShader by lazy {
        val rnd = java.util.Random(20261001L)
        val raw = FloatArray(SIZE * SIZE) { rnd.nextFloat() }
        // 이웃과 섞어 알갱이를 조금 키운다 (가로로 조금 더 길게 — 연필 결). 가장자리는 반대쪽과 이어 타일이 티 나지 않게
        val bytes = ByteArray(SIZE * SIZE)
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            var s = 0f
            var w = 0f
            for (dy in -1..1) for (dx in -2..2) {
                val k = if (dy == 0) 1f else 0.5f
                s += raw[((y + dy + SIZE) % SIZE) * SIZE + (x + dx + SIZE) % SIZE] * k
                w += k
            }
            val v = 0.6f * (s / w) + 0.4f * raw[y * SIZE + x]
            val a = ((v - 0.18f) * 2.1f).coerceIn(0.22f, 1f)
            bytes[y * SIZE + x] = (a * 255f).toInt().toByte()
        }
        val bmp = Bitmap.createBitmap(SIZE, SIZE, Bitmap.Config.ALPHA_8)
        bmp.copyPixelsFromBuffer(ByteBuffer.wrap(bytes))
        BitmapShader(bmp, Shader.TileMode.REPEAT, Shader.TileMode.REPEAT).apply {
            setLocalMatrix(Matrix().apply { setScale(CELL, CELL) })
        }
    }
}
