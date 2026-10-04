package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Region
import android.graphics.Typeface
import android.text.StaticLayout
import android.text.TextPaint
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 쪽에 넣은 그림. [bytes]는 PDF에 그대로 넣을 JPEG (null이면 저장할 때 [bitmap]을 무손실로 넣는다)
 */
class InkImage(val bitmap: Bitmap, val bytes: ByteArray?)

private val imageMatrix = Matrix()
private val imagePaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
private val imageSrc = FloatArray(8)
private val imageDst = FloatArray(8)

/** 그림: 획의 네 점(왼쪽 위, 오른쪽 위, 오른쪽 아래, 왼쪽 아래)에 맞춰 그린다 (돌리거나 늘여도 따라감) */
private fun drawInkImage(c: Canvas, st: Stroke, alphaMul: Float) {
    val img = st.image ?: return
    if (st.count < 4) return
    val w = img.bitmap.width.toFloat()
    val h = img.bitmap.height.toFloat()
    imageSrc[0] = 0f; imageSrc[1] = 0f; imageSrc[2] = w; imageSrc[3] = 0f
    imageSrc[4] = w; imageSrc[5] = h; imageSrc[6] = 0f; imageSrc[7] = h
    for (k in 0 until 4) {
        imageDst[k * 2] = st.x(k)
        imageDst[k * 2 + 1] = st.y(k)
    }
    imageMatrix.setPolyToPoly(imageSrc, 0, imageDst, 0, 4)
    imagePaint.alpha = (255 * alphaMul).roundToInt()
    c.drawBitmap(img.bitmap, imageMatrix, imagePaint)
}

/**
 * 쪽에 넣은 글. [size]는 글자 크기(pt), [wrap]은 줄을 바꾸는 폭(pt, 글 상자를 만든 자리에서 쪽 오른쪽 끝까지).
 * 그림처럼 획의 네 점(상자 모서리)에 맞춰 그리므로 옮기고 키우고 돌려도 따라간다.
 * 문서 위에서 치는 글 상자(InlineTextEditor의 EditText)와 줄바꿈이 같도록 같은 설정으로 배치한다.
 */
class InkText(val rich: RichDoc, val size: Float, val wrap: Float = NO_WRAP) {
    constructor(text: String, size: Float, wrap: Float = NO_WRAP) : this(RichDoc.plain(text), size, wrap)

    /** 서식을 뺀 글 */
    val text: String get() = rich.text

    // 배치는 Q배 크게 한다: 글꼴 높이가 정수로 반올림되는데 pt 단위 그대로면 줄마다 오차가 커져
    // 화면(px 단위)의 글 상자와 줄 간격이 어긋나므로
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        textSize = size * Q
        typeface = Typeface.DEFAULT
    }
    private val content = rich.toSpannable(size * Q)
    private val layout: StaticLayout

    /** 상자 크기 (pt). 저장할 때 PDF 한 쪽 크기로도 쓰므로 정수로 올린다. 폭은 적어도 글자 하나 (빈 글 상자와 같게) */
    val boxW: Int
    val boxH: Int

    init {
        var l = build(ceil(min(wrap, NO_WRAP) * Q).toInt().coerceAtLeast(1))
        val inner = ceil(max((0 until l.lineCount).maxOf { l.getLineMax(it) }, size * Q)).toInt()
        // 가운데·오른쪽 맞춤은 상자 폭 안에서 맞추므로 그 폭으로 다시 배치 (글 상자 EditText가 내용 폭으로 줄어드는 것과 같게)
        if (rich.paras.any { it.align != TextAlign.LEFT }) l = build(inner)
        layout = l
        boxW = ceil(inner / Q + PAD * 2).toInt().coerceAtLeast(1)
        boxH = ceil(l.height / Q + PAD * 2f).toInt().coerceAtLeast(1)
    }

    private fun build(width: Int): StaticLayout = layoutOf(content, paint, width)

    /** (0, 0) ~ (boxW, boxH) 상자에 글을 그린다. 쪽 미리보기가 다른 스레드에서 같이 그릴 수 있어 잠근다 */
    fun draw(c: Canvas, color: Int) = synchronized(this) {
        paint.color = color
        c.save()
        c.translate(PAD, PAD)
        c.scale(1f / Q, 1f / Q)
        layout.draw(c)
        c.restore()
    }

    companion object {
        /** 줄을 바꾸지 않음 (예전에 넣은 글) */
        const val NO_WRAP = 100_000f
        /** 글자가 상자 끝에서 잘리지 않게 두르는 여백 (pt) */
        const val PAD = 2f
        /** 배치 배율 */
        private const val Q = 8f

        private fun layoutOf(content: CharSequence, paint: TextPaint, width: Int): StaticLayout =
            StaticLayout.Builder.obtain(content, 0, content.length, paint, width)
                .setIncludePad(false)
                .setUseLineSpacingFromFallbacks(true)
                .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE)
                .build()

        /**
         * 긴 글을 쪽마다 나눈다: 조각마다 [InkText]로 만들면 상자 높이가 [maxH](pt, 여백 포함)를 넘지 않는다.
         * 줄이 바뀌는 자리에서 자르므로 문단이 두 쪽에 걸칠 수 있다. 반환값은 글 안의 자르는 자리들 (첫 조각은 0부터)
         */
        fun paginate(text: String, size: Float, wrap: Float, maxH: Float): List<IntRange> {
            if (text.isEmpty()) return listOf(0 until 0)
            val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                textSize = size * Q
                typeface = Typeface.DEFAULT
            }
            val l = layoutOf(RichDoc.plain(text).toSpannable(size * Q), paint, ceil(min(wrap, NO_WRAP) * Q).toInt().coerceAtLeast(1))
            val room = (maxH - PAD * 2f) * Q
            val out = ArrayList<IntRange>()
            var first = 0
            for (line in 0 until l.lineCount) {
                if (line > first && l.getLineBottom(line) - l.getLineTop(first) > room) {
                    out.add(l.getLineStart(first) until l.getLineStart(line))
                    first = line
                }
            }
            out.add(l.getLineStart(first) until text.length)
            return out
        }
    }
}

/** 글: 상자 (0, 0, boxW, boxH)를 획의 네 점에 맞춰 그린다 */
private fun drawInkText(c: Canvas, st: Stroke, alphaMul: Float) {
    val t = st.text ?: return
    if (st.count < 4) return
    val w = t.boxW.toFloat()
    val h = t.boxH.toFloat()
    imageSrc[0] = 0f; imageSrc[1] = 0f; imageSrc[2] = w; imageSrc[3] = 0f
    imageSrc[4] = w; imageSrc[5] = h; imageSrc[6] = 0f; imageSrc[7] = h
    for (k in 0 until 4) {
        imageDst[k * 2] = st.x(k)
        imageDst[k * 2 + 1] = st.y(k)
    }
    imageMatrix.setPolyToPoly(imageSrc, 0, imageDst, 0, 4)
    c.save()
    c.concat(imageMatrix)
    val a = (android.graphics.Color.alpha(st.color) * alphaMul).roundToInt()
    t.draw(c, (st.color and 0x00FFFFFF) or (a shl 24))
    c.restore()
}

/** 필기 그리기용 붓 ([drawInkStroke]와 같이 쓴다) */
fun inkPaint() = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}

/**
 * 획 하나를 그린다 (캔버스는 페이지 좌표로 맞춰 둔 상태). 문서 화면과 쪽 미리보기가 같이 쓴다.
 * 형광펜은 반투명 곱하기, 점선 획은 점선으로. alphaMul < 1이면 흐리게
 */
fun drawInkStroke(c: Canvas, paint: Paint, st: Stroke, alphaMul: Float = 1f) {
    if (st.note != null) {
        drawStickyNote(c, st, alphaMul)
        return
    }
    if (st.image != null) {
        drawInkImage(c, st, alphaMul)
        return
    }
    if (st.text != null) {
        drawInkText(c, st, alphaMul)
        return
    }
    if (st.tape != null) {
        drawInkTape(c, st, alphaMul)
        return
    }
    if (st.fill != null) {
        drawInkFill(c, st, alphaMul)
        return
    }
    paint.color = st.color
    if (st.tool == Tool.HIGHLIGHTER) {
        paint.alpha = (PdfInk.HL_ALPHA * 255).roundToInt()
        paint.blendMode = BlendMode.MULTIPLY
    } else {
        paint.blendMode = null
    }
    if (st.outlined) {
        // 굵기가 매끄럽게 바뀌는 펜: 외곽선을 채운다. 연필은 조금 옅게, 종이 결무늬를 입혀서
        val pencil = st.pen == PenStyle.PENCIL
        if (pencil) paint.alpha = (paint.alpha * PenStyle.PENCIL_ALPHA).roundToInt()
        if (alphaMul < 1f) paint.alpha = (paint.alpha * alphaMul).roundToInt()
        paint.pathEffect = null
        paint.style = Paint.Style.FILL
        if (pencil) paint.shader = PencilGrain.shader
        c.drawPath(st.outline(), paint)
        paint.shader = null
        paint.style = Paint.Style.STROKE
        return
    }
    if (alphaMul < 1f) paint.alpha = (paint.alpha * alphaMul).roundToInt()
    paint.pathEffect = if (st.dashed) DashPathEffect(st.dashIntervals(), 0f) else null
    for ((w, path) in st.paths()) {
        paint.strokeWidth = w
        c.drawPath(path, paint)
    }
}

/**
 * SHAPE = 보정 펜 (그린 결과는 PEN 획으로 저장), LASER = 잠깐 보였다 사라지는 레이저 (저장하지 않음),
 * TEXT = 누른 자리에 글 넣기 (글은 [Stroke.text]가 있는 PEN 획으로 저장),
 * TAPE = 내용을 가리는 테이프 (누르면 보였다 가려졌다 한다. [Stroke.tape]가 있는 TAPE 획),
 * FILL = 영역 채우기 ([Stroke.fill]이 있는 FILL 획. 점들은 윤곽들이고, 필압 자리 1이 윤곽의 첫 점)
 */
enum class Tool { PEN, SHAPE, HIGHLIGHTER, ERASER, LASSO, LASER, TEXT, TAPE, FILL }

/** STROKE = 닿은 획을 통째로, AREA = 지우개가 지나간 부분만 */
enum class EraserMode { STROKE, AREA }

/**
 * 한 획. 좌표는 페이지 기준(단위: PDF 포인트, 원점은 화면에 보이는 페이지의 왼쪽 위).
 * 점마다 (x, y, 필압) 3개 값을 저장한다. [pen]은 펜 획의 펜 종류 (만년필·붓펜은 필압 자리에 속도·끝 가늘기를 넣은 값)
 */
class Stroke(val tool: Tool, color: Int, width: Float, val dashed: Boolean = false, val pen: PenStyle = PenStyle.FELT) {
    /** 선택 도구로 색·크기를 바꿀 수 있다 (바꿀 때는 InkDocument.edit으로 기록) */
    var color = color
        private set
    var width = width
        private set
    var data = FloatArray(3 * 64)
        private set
    var count = 0
        private set
    var version = 0
        private set
    /** 그림이면 그 그림 (점 네 개가 그림의 네 모서리). 지우개로는 지우지 않는다 */
    var image: InkImage? = null
    /** 오답노트가 붙인 그림의 이름표 (머리줄 'WH번호', 원문 쪽 배지 'WS번호'). 그림 주석에 함께 저장한다 */
    var role: String? = null
    /** 글이면 그 글 (점 네 개가 글 상자의 네 모서리). 지우개로는 지우지 않는다 */
    var text: InkText? = null
    /** 글에 단 링크 (웹 주소). 읽기 모드에서 누르면 연다 */
    var link: String? = null
    /** 포스트잇 메모면 그 메모 (점 0은 접힌 메모, 점 1은 펼친 메모의 왼쪽 위). 지우개·선택으로는 건드리지 않는다 */
    var note: StickyNote? = null

    /** 테이프면 그 모양·무늬 */
    var tape: TapeStyle? = null
    /** 채우기면 그 무늬. 점들은 윤곽 여러 개 (짝홀 규칙: 바깥 윤곽과 구멍), 필압 자리가 1인 점에서 새 윤곽이 시작한다 */
    var fill: FillStyle? = null

    /** 테이프·채우기에서 영역 지우개로 뚫은 구멍들 (x, y, 반지름)을 이어 붙인 것 */
    var holes = FloatArray(0)
        private set

    /** 테이프를 눌러 가린 내용을 보이게 했는지 (테두리만 그린다). 저장하지 않고, 파일을 열면 늘 가린 상태 */
    var revealed = false

    /** 그림이나 글처럼 네 모서리로 된 상자인지 (지우개가 자르지 않고, 톡 눌러 고를 수 있다) */
    val isBox get() = image != null || text != null

    /** 사인펜이 아닌 펜 획: 채운 외곽선으로 그린다 ([outline]) */
    val outlined get() = tool == Tool.PEN && pen != PenStyle.FELT && !dashed && image == null && text == null

    /** 선이 가운데에서 가장 멀리 닿는 거리 (가장 굵은 곳의 절반) */
    val halfWidth get() = width * (if (tool == Tool.PEN && !dashed) pen.reach else 1f) / 2f

    private var cachedPaths: List<Pair<Float, Path>>? = null
    private var cachedVersion = -1
    private var cachedShape: Path? = null
    private var cachedShapeVersion = -1
    private var cachedOutline: Path? = null
    private var cachedOutlineVersion = -1
    // 펜 외곽선: 점만 더해졌으면 새 조각만 덧붙인다 (쓰는 동안 매번 처음부터 만들지 않게)
    private var appended = 0
    private var penPath: Path? = null
    private var penPathVersion = -1
    private var penPathAppended = 0
    private var penPathCount = 0
    private var penPathKept = 0

    fun add(x: Float, y: Float, p: Float) {
        if (count * 3 + 3 > data.size) data = data.copyOf(data.size * 2)
        data[count * 3] = x
        data[count * 3 + 1] = y
        data[count * 3 + 2] = p
        count++
        version++
        appended++
    }

    /** i번째 점의 필압 값만 바꾼다 (만년필·붓펜 끝 가늘기) */
    fun setPressure(i: Int, p: Float) {
        data[i * 3 + 2] = p
        version++
    }

    /** 펜 외곽선 (쪽 좌표, 채우기용). 쪽 미리보기가 다른 스레드에서 같이 그릴 수 있어 잠근다 */
    fun outline(): Path {
        synchronized(this) { return outlineLocked() }
    }

    private fun outlineLocked(): Path {
        val old = penPath
        if (old != null && penPathVersion == version) return old
        if (old != null && count > penPathCount && version - penPathVersion == appended - penPathAppended) {
            penPathKept = PenOutline.append(this, PathSink(old), penPathKept, penPathCount, forceLast = false)
        } else {
            val path = Path()
            penPathKept = PenOutline.append(this, PathSink(path), 0, 0, forceLast = true)
            penPath = path
        }
        penPathVersion = version
        penPathAppended = appended
        penPathCount = count
        return penPath!!
    }

    /** 첫 점만 남긴다 (직선 형광펜: 끝점을 새로 정할 때) */
    fun keepFirst() {
        if (count > 1) count = 1
        version++
    }

    /** 점을 모두 뺀다 (테이프를 곧게 펴서 점을 새로 넣을 때) */
    fun clearPoints() {
        count = 0
        version++
    }

    /** i번째 점만 (dx, dy)만큼 옮긴다 */
    fun offsetPoint(i: Int, dx: Float, dy: Float) {
        data[i * 3] += dx
        data[i * 3 + 1] += dy
        version++
    }

    /** 굵기만 바꾼다 (테이프를 글자 크기에 맞출 때) */
    fun resize(w: Float) {
        width = w
        version++
    }

    /** 획 전체를 (dx, dy)만큼 옮긴다 */
    fun translate(dx: Float, dy: Float) {
        for (i in 0 until count) {
            data[i * 3] += dx
            data[i * 3 + 1] += dy
        }
        for (i in holes.indices step 3) {
            holes[i] += dx
            holes[i + 1] += dy
        }
        version++
    }

    /** (ax, ay)를 기준으로 k배 키운다. 굵기도 같이 */
    fun scale(k: Float, ax: Float, ay: Float) {
        for (i in 0 until count) {
            data[i * 3] = ax + (data[i * 3] - ax) * k
            data[i * 3 + 1] = ay + (data[i * 3 + 1] - ay) * k
        }
        for (i in holes.indices step 3) {
            holes[i] = ax + (holes[i] - ax) * k
            holes[i + 1] = ay + (holes[i + 1] - ay) * k
            holes[i + 2] *= k
        }
        width *= k
        version++
    }

    /**
     * (ax, ay)를 기준으로 가로 kx배, 세로 ky배 늘인다 (위아래로만·옆으로만 키우기).
     * 펜 굵기와 테이프 구멍은 덜 늘어난 쪽을 따라 바꿔, 길게 늘여도 획이 뭉뚝해지지 않게 한다
     */
    fun scaleXY(kx: Float, ky: Float, ax: Float, ay: Float) {
        for (i in 0 until count) {
            data[i * 3] = ax + (data[i * 3] - ax) * kx
            data[i * 3 + 1] = ay + (data[i * 3 + 1] - ay) * ky
        }
        val k = min(kx, ky)
        for (i in holes.indices step 3) {
            holes[i] = ax + (holes[i] - ax) * kx
            holes[i + 1] = ay + (holes[i + 1] - ay) * ky
            holes[i + 2] *= k
        }
        width *= k
        version++
    }

    /** (cx, cy)를 중심으로 deg도 돌린다 (화면 기준 시계 방향이 +) */
    fun rotate(deg: Float, cx: Float, cy: Float) {
        val r = Math.toRadians(deg.toDouble())
        val c = kotlin.math.cos(r).toFloat()
        val s = kotlin.math.sin(r).toFloat()
        for (i in 0 until count) {
            val dx = data[i * 3] - cx
            val dy = data[i * 3 + 1] - cy
            data[i * 3] = cx + dx * c - dy * s
            data[i * 3 + 1] = cy + dx * s + dy * c
        }
        for (i in holes.indices step 3) {
            val dx = holes[i] - cx
            val dy = holes[i + 1] - cy
            holes[i] = cx + dx * c - dy * s
            holes[i + 1] = cy + dx * s + dy * c
        }
        version++
    }

    /**
     * 쪽을 좌우([horizontal]) 또는 상하로 뒤집을 때: 점을 쪽 가로(세로) 길이 [extent]를 기준으로 거울에 비춘다.
     * 글·그림 상자는 자리만 옮기고 글자·그림이 뒤집히지 않게 모서리 차례를 바꾼다
     * (네 모서리 왼위·오른위·오른아래·왼아래 → 좌우는 1·0·3·2, 상하는 3·2·1·0)
     */
    fun mirror(horizontal: Boolean, extent: Float) {
        val axis = if (horizontal) 0 else 1
        for (i in 0 until count) data[i * 3 + axis] = extent - data[i * 3 + axis]
        for (i in holes.indices step 3) holes[i + axis] = extent - holes[i + axis]
        if ((image != null || text != null) && count == 4) {
            val order = if (horizontal) intArrayOf(1, 0, 3, 2) else intArrayOf(3, 2, 1, 0)
            val old = data.copyOf(12)
            for (k in 0 until 4) for (j in 0 until 3) data[k * 3 + j] = old[order[k] * 3 + j]
        }
        // 메모: 점은 왼쪽 위 모서리이므로 거울에 비춘 뒤 크기만큼 되돌린다 (글은 뒤집지 않는다)
        note?.let { n ->
            if (count >= 2) {
                data[axis] -= StickyNote.ICON
                data[3 + axis] -= if (horizontal) n.w else n.h
            }
        }
        version++
    }

    fun recolor(c: Int) {
        color = c
        version++
    }

    fun copy(): Stroke {
        val s = Stroke(tool, color, width, dashed, pen)
        s.image = image
        s.role = role
        s.text = text
        s.link = link
        s.note = note?.copy()
        s.tape = tape
        s.fill = fill
        s.holes = holes.copyOf()
        s.data = data.copyOf(count * 3)
        s.count = count
        return s
    }

    /**
     * 테이프에 (x, y) 반지름 r인 구멍을 더 뚫은 새 테이프 (영역 지우개). 바로 앞 구멍과 거의 같은 자리면 null
     */
    fun withHole(x: Float, y: Float, r: Float): Stroke? {
        val n = holes.size
        if (n >= 3 && hypot(holes[n - 3] - x, holes[n - 2] - y) < r * 0.25f && abs(holes[n - 1] - r) < r * 0.1f) return null
        // 이미 뚫린 구멍 안에 다 들어가면 더 뚫을 것이 없다
        for (i in 0 until n step 3) if (hypot(holes[i] - x, holes[i + 1] - y) + r <= holes[i + 2]) return null
        val child = copy().also { it.holes = holes + floatArrayOf(x, y, r) }
        // 지금 영역에서 새 구멍만 빼서 넘겨준다 (구멍이 쌓여도 지울 때마다 모든 구멍을 처음부터 다시 빼지 않게)
        val base = cachedShape?.takeIf { cachedShapeVersion == version }
        if (base != null) {
            val cut = Path().apply { addCircle(x, y, r, Path.Direction.CW) }
            val next = Path()
            if (next.op(base, cut, Path.Op.DIFFERENCE)) {
                child.cachedShape = next
                child.cachedShapeVersion = child.version
            }
        }
        return child
    }

    /** 다른 테이프의 구멍들을 그대로 가져온다 (파일에서 읽을 때) */
    fun copyHolesFrom(o: Stroke) {
        holes = o.holes.copyOf()
        version++
    }

    /** 구멍을 뚫다 보니 테이프(채우기)가 거의(1pt 조각도) 남지 않았는지 */
    fun tapeGone(): Boolean {
        val shape = if (fill != null) fillShape() else tapeShape()
        val b = RectF()
        shape.computeBounds(b, true)
        val clip = Region(floor(b.left).toInt() - 1, floor(b.top).toInt() - 1, ceil(b.right).toInt() + 1, ceil(b.bottom).toInt() + 1)
        val left = Region()
        left.setPath(shape, clip)
        return left.isEmpty
    }

    /** 실행 취소용으로 지금 모양을 떠 둔다 */
    class State(val data: FloatArray, val count: Int, val color: Int, val width: Float, val holes: FloatArray)

    fun state() = State(data.copyOf(count * 3), count, color, width, holes.copyOf())

    fun restore(s: State) {
        data = s.data.copyOf()
        count = s.count
        color = s.color
        width = s.width
        holes = s.holes.copyOf()
        version++
    }

    fun x(i: Int) = data[i * 3]
    fun y(i: Int) = data[i * 3 + 1]
    fun p(i: Int) = data[i * 3 + 2]

    /** 필압에 따라 굵기가 달라지는 구간들로 나눠 콜백한다. 화면 그리기와 PDF 저장이 같은 규칙을 쓴다. */
    inline fun forEachGroup(block: (w: Float, from: Int, to: Int) -> Unit) {
        if (count == 0) return
        if (tool == Tool.HIGHLIGHTER || tool == Tool.TAPE) {
            block(width, 0, count - 1)
            return
        }
        if (count == 1) {
            block(width * quantize(p(0)), 0, 0)
            return
        }
        var start = 0
        var cur = quantize((p(0) + p(1)) / 2f)
        for (i in 2 until count) {
            val q = quantize((p(i - 1) + p(i)) / 2f)
            if (q != cur) {
                block(width * cur, start, i - 1)
                start = i - 1
                cur = q
            }
        }
        block(width * cur, start, count - 1)
    }

    /** 화면용 Path 목록 (굵기, 경로). 점이 추가되면 다시 만든다. */
    fun paths(): List<Pair<Float, Path>> {
        cachedPaths?.let { if (cachedVersion == version) return it }
        val list = ArrayList<Pair<Float, Path>>()
        forEachGroup { w, from, to ->
            val path = Path()
            path.moveTo(x(from), y(from))
            if (from == to) path.lineTo(x(from) + 0.01f, y(from))
            else for (i in from + 1..to) path.lineTo(x(i), y(i))
            list.add(w to path)
        }
        cachedPaths = list
        cachedVersion = version
        return list
    }

    /** (px, py)에서 반지름 r 안에 이 획이 지나가는지. 네모 테이프·채우기는 안쪽도 */
    fun hitTest(px: Float, py: Float, r: Float): Boolean {
        if (fill != null) return fillHit(px, py, r)
        val rr = r + halfWidth
        val rr2 = rr * rr
        if (count == 1) return dist2(px, py, x(0), y(0)) <= rr2
        val closed = tape?.rect == true && count >= 3
        if (closed && inPolygon(px, py)) return true
        for (i in 1 until count) {
            if (segDist2(px, py, x(i - 1), y(i - 1), x(i), y(i)) <= rr2) return true
        }
        return closed && segDist2(px, py, x(count - 1), y(count - 1), x(0), y(0)) <= rr2
    }

    /** 점들을 이은 다각형 안에 (px, py)가 있는지 */
    private fun inPolygon(px: Float, py: Float): Boolean {
        var inside = false
        var j = count - 1
        for (i in 0 until count) {
            val xi = x(i); val yi = y(i); val xj = x(j); val yj = y(j)
            if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    /** 채우기 윤곽마다 (첫 점 번호, 끝 점 번호) */
    inline fun forEachContour(block: (from: Int, to: Int) -> Unit) {
        var start = 0
        for (i in 1..count) {
            if (i == count || p(i) >= 0.5f) {
                if (i - start >= 3) block(start, i - 1)
                start = i
            }
        }
    }

    /** 채우기가 (px, py)를 덮는지 (짝홀 규칙, 지우개 구멍 자리는 아님) */
    fun fillContains(px: Float, py: Float): Boolean = insideOutline(px, py) && !inHole(px, py)

    /** 채우기 윤곽 안인지 (지우개 구멍은 따지지 않음) */
    private fun insideOutline(px: Float, py: Float): Boolean {
        if (fill == null) return false
        var inside = false
        forEachContour { from, to ->
            var j = to
            for (i in from..to) {
                val xi = x(i); val yi = y(i); val xj = x(j); val yj = y(j)
                if ((yi > py) != (yj > py) && px < (xj - xi) * (py - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
        }
        return inside
    }

    /**
     * 채우기의 윤곽 안이거나 윤곽에서 r 안인지. 지우개 구멍 자리도 닿은 것으로 본다
     * (영역 지우개가 앞 구멍 안에서 다음 구멍을 바로 이어 뚫도록. 더 뚫을 것이 없으면 withHole이 거른다)
     */
    private fun fillHit(px: Float, py: Float, r: Float): Boolean {
        if (insideOutline(px, py)) return true
        val r2 = r * r
        var hit = false
        forEachContour { from, to ->
            if (hit) return@forEachContour
            var j = to
            for (i in from..to) {
                if (segDist2(px, py, x(j), y(j), x(i), y(i)) <= r2) { hit = true; break }
                j = i
            }
        }
        return hit
    }

    private fun inHole(px: Float, py: Float): Boolean {
        for (i in holes.indices step 3) if (hypot(holes[i] - px, holes[i + 1] - py) <= holes[i + 2]) return true
        return false
    }

    /** 채우기 영역 (쪽 좌표, 짝홀 규칙). 지우개 구멍은 뺀다 */
    fun fillShape(): Path {
        cachedShape?.let { if (cachedShapeVersion == version) return it }
        val out = Path()
        out.fillType = Path.FillType.EVEN_ODD
        forEachContour { from, to ->
            out.moveTo(x(from), y(from))
            for (i in from + 1..to) out.lineTo(x(i), y(i))
            out.close()
        }
        if (holes.isNotEmpty()) {
            val cut = Path()
            for (i in holes.indices step 3) cut.addCircle(holes[i], holes[i + 1], holes[i + 2], Path.Direction.CW)
            val rest = Path()
            if (rest.op(out, cut, Path.Op.DIFFERENCE)) out.set(rest)
        }
        cachedShape = out
        cachedShapeVersion = version
        return out
    }

    /** 테이프가 (px, py)를 덮고 있는지 (지우개로 뚫은 구멍 자리는 아님) */
    fun tapeContains(px: Float, py: Float): Boolean {
        if (tape == null || !hitTest(px, py, 0f)) return false
        for (i in holes.indices step 3) if (hypot(holes[i] - px, holes[i + 1] - py) <= holes[i + 2]) return false
        return true
    }

    /**
     * 테이프가 덮는 영역 (쪽 좌표, 채우기용). 펜 테이프는 굵은 선의 테두리, 네모 테이프는 네 점을 이은 네모.
     * 겹친 부분이 남아 있을 수 있으니 테두리를 그릴 때는 [tapeOutline]
     */
    fun tapeShape(): Path {
        cachedShape?.let { if (cachedShapeVersion == version) return it }
        val out = Path()
        if (tape?.rect == true) {
            if (count >= 3) {
                out.moveTo(x(0), y(0))
                for (i in 1 until count) out.lineTo(x(i), y(i))
                out.close()
            }
        } else if (count > 0) {
            val line = Path()
            line.moveTo(x(0), y(0))
            if (count == 1) line.lineTo(x(0) + 0.01f, y(0))
            else for (i in 1 until count) line.lineTo(x(i), y(i))
            // 테이프 끝은 그은 처음·끝 자리에서 곧게 잘라 (굵은 테이프가 옆 글자까지 덮지 않게), 꺾인 곳은 둥글게
            Paint().apply {
                style = Paint.Style.STROKE
                strokeWidth = width
                strokeCap = Paint.Cap.BUTT
                strokeJoin = Paint.Join.ROUND
            }.getFillPath(line, out)
            out.fillType = Path.FillType.WINDING
        }
        if (holes.isNotEmpty()) {
            // 영역 지우개로 뚫은 구멍을 뺀다
            val cut = Path()
            for (i in holes.indices step 3) cut.addCircle(holes[i], holes[i + 1], holes[i + 2], Path.Direction.CW)
            val rest = Path()
            if (rest.op(out, cut, Path.Op.DIFFERENCE)) out.set(rest)
        }
        cachedShape = out
        cachedShapeVersion = version
        return out
    }

    /** 테이프 영역에서 겹친 부분을 합친 바깥 테두리 (보이게 한 테이프의 테두리, PDF 저장) */
    fun tapeOutline(): Path {
        cachedOutline?.let { if (cachedOutlineVersion == version) return it }
        val shape = tapeShape()
        val out = Path()
        if (!out.op(shape, Path(), Path.Op.UNION)) out.set(shape)
        cachedOutline = out
        cachedOutlineVersion = version
        return out
    }

    /**
     * 영역 지우개: (cx, cy) 중심 반지름 r인 원에 닿은 부분을 잘라 낸 나머지 조각들.
     * 원이 이 획에 닿지 않으면 null, 획 전체가 원 안이면 빈 목록.
     * 선 끝의 둥근 마개가 원 밖으로 삐져나오지 않도록 굵기의 절반만큼 넓게 자른다.
     */
    fun cut(cx: Float, cy: Float, r: Float): List<Stroke>? {
        if (!hitTest(cx, cy, r)) return null
        val rr = r + halfWidth
        val rr2 = rr * rr
        val pieces = ArrayList<Stroke>()
        var cur: Stroke? = null
        fun inside(i: Int) = dist2(x(i), y(i), cx, cy) <= rr2
        fun addPoint(i: Int, t: Float) {
            val piece = cur ?: Stroke(tool, color, width, dashed, pen).also { it.tape = tape; cur = it }
            if (t == 1f) piece.add(x(i), y(i), p(i))
            else piece.add(
                x(i - 1) + (x(i) - x(i - 1)) * t, y(i - 1) + (y(i) - y(i - 1)) * t, p(i - 1) + (p(i) - p(i - 1)) * t
            )
        }
        fun finish() {
            // 자른 자리에 남는 아주 짧은 부스러기는 버린다
            cur?.let { if (it.count >= 2 && it.length() >= max(0.5f, width * 0.3f)) pieces.add(it) }
            cur = null
        }
        if (!inside(0)) addPoint(0, 1f)
        for (i in 1 until count) {
            // 선분 A→B 위의 점 A + t(B−A)가 원 안에 드는 t 구간 (t0, t1)
            val ax = x(i - 1) - cx; val ay = y(i - 1) - cy
            val dx = x(i) - x(i - 1); val dy = y(i) - y(i - 1)
            val a = dx * dx + dy * dy
            val b = 2 * (ax * dx + ay * dy)
            val c = ax * ax + ay * ay - rr2
            val disc = b * b - 4 * a * c
            if (a == 0f || disc <= 0f) {
                if (!inside(i)) addPoint(i, 1f)
                continue
            }
            val sq = sqrt(disc)
            val t0 = (-b - sq) / (2 * a)
            val t1 = (-b + sq) / (2 * a)
            if (t1 <= 0f || t0 >= 1f) {
                // 이 선분은 원에 닿지 않는다
                addPoint(i, 1f)
                continue
            }
            if (t0 > 0f) {
                addPoint(i, t0)
                finish()
            }
            if (t1 < 1f) {
                finish()
                addPoint(i, t1)
                addPoint(i, 1f)
            }
        }
        finish()
        return pieces
    }

    /** 획의 길이 (페이지 좌표) */
    fun length(): Float {
        var len = 0f
        for (i in 1 until count) len += hypot(x(i) - x(i - 1), y(i) - y(i - 1))
        return len
    }

    /** 점선 획(보정 펜의 점근선)의 [선 길이, 빈칸 길이]. 굵기에 비례, 화면과 PDF가 같이 쓴다 */
    fun dashIntervals() = floatArrayOf(4f + width * 2.5f, 3f + width * 2f)

    companion object {
        /** 필압(0~1) → 굵기 비율(0.3~1.0), 1/12 단위로 양자화 */
        fun quantize(p: Float): Float {
            val f = 0.3f + 0.7f * p.coerceIn(0f, 1f)
            return (f * 12f).roundToInt() / 12f
        }

        private fun dist2(ax: Float, ay: Float, bx: Float, by: Float): Float {
            val dx = ax - bx
            val dy = ay - by
            return dx * dx + dy * dy
        }

        private fun segDist2(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
            val vx = bx - ax
            val vy = by - ay
            val len2 = vx * vx + vy * vy
            if (len2 == 0f) return dist2(px, py, ax, ay)
            val t = (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0f, 1f)
            return dist2(px, py, ax + t * vx, ay + t * vy)
        }
    }
}

/** 문서 전체의 필기와 실행 취소/다시 실행 기록 */
class InkDocument(pageCount: Int) {
    /** 쪽마다 획 목록. 쪽을 넣거나 지우면 순서가 바뀐다 */
    val pages: MutableList<MutableList<Stroke>> = MutableList(pageCount) { mutableListOf() }

    // 기록은 쪽 번호 대신 그 쪽의 획 목록을 가리킨다 (쪽을 넣고 빼도 기록이 어긋나지 않게)
    private sealed class Action {
        class Add(val page: MutableList<Stroke>, val stroke: Stroke) : Action()
        /** 지운 획들과, 영역 지우개가 획을 잘라 남긴 조각들 */
        class Erase(
            val items: List<Pair<MutableList<Stroke>, Stroke>>,
            val pieces: List<Pair<MutableList<Stroke>, Stroke>> = emptyList(),
        ) : Action()
        class Move(val strokes: List<Stroke>, val dx: Float, val dy: Float) : Action()
        class AddAll(val page: MutableList<Stroke>, val strokes: List<Stroke>) : Action()
        class Edit(val strokes: List<Stroke>, val before: List<Stroke.State>, val after: List<Stroke.State>) : Action()
        class Pages(
            val before: List<MutableList<Stroke>>, val after: List<MutableList<Stroke>>,
            val beforeFiles: Any, val afterFiles: Any,
        ) : Action()
        /** 목차 링크로 다른 자리로 감 (필기는 바뀌지 않는다) */
        class Jump(val before: Any, val after: Any) : Action()
        /** 오답 한 문제를 이미 있는 오답 쪽의 빈 칸에 담음: 획들과, [list] 쪽의 오답 항목 목록의 전후 */
        class Wrong(
            val adds: List<Pair<MutableList<Stroke>, Stroke>>,
            val list: MutableList<Stroke>,
            val before: List<WrongEntry>?,
            val after: List<WrongEntry>?,
        ) : Action()
    }

    private val undoStack = ArrayDeque<Action>()
    private val redoStack = ArrayDeque<Action>()

    var dirty = false
        private set
    var onChanged: (() -> Unit)? = null

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    /**
     * 북마크한 쪽. 쪽 번호 대신 그 쪽의 획 목록(자체)을 담아 두어, 쪽을 넣고 빼거나
     * 그 일을 실행 취소해도 북마크가 쪽을 따라간다. 북마크는 실행 취소 기록에 남기지 않는다
     */
    private val marks: MutableSet<MutableList<Stroke>> = java.util.Collections.newSetFromMap(java.util.IdentityHashMap())

    fun isBookmarked(page: Int) = pages.getOrNull(page)?.let { it in marks } == true

    fun setBookmark(page: Int, on: Boolean) {
        val list = pages.getOrNull(page) ?: return
        if (if (on) marks.add(list) else marks.remove(list)) changed()
    }

    /** 북마크한 쪽 번호 (0부터, 차례대로) */
    fun bookmarkedPages(): Set<Int> = pages.indices.filterTo(LinkedHashSet()) { pages[it] in marks }

    /** 파일에서 읽어 온 필기와 북마크 (기록에 남기지 않음) */
    fun load(strokes: List<List<Stroke>>, bookmarks: Set<Int> = emptySet()) {
        for (i in strokes.indices) if (i < pages.size) pages[i].addAll(strokes[i])
        for (i in bookmarks) pages.getOrNull(i)?.let { marks.add(it) }
    }

    /** 쪽 구성 바꾸기에서 새로 들어온 쪽 목록에 북마크를 단다 ([changePages]의 edit 안에서) */
    fun markList(list: MutableList<Stroke>) {
        marks.add(list)
    }

    // ---- 오답노트 ----

    /**
     * 오답 쪽의 항목들. 북마크처럼 쪽 번호 대신 그 쪽의 획 목록(자체)에 달아 두어
     * 쪽을 넣고 빼거나 실행 취소해도 항목이 쪽을 따라간다
     */
    private val wrongs: MutableMap<MutableList<Stroke>, MutableList<WrongEntry>> = java.util.IdentityHashMap()

    fun wrongEntries(page: Int): List<WrongEntry> = pages.getOrNull(page)?.let { wrongs[it] } ?: emptyList()

    /** 오답 전체 (쪽 번호, 항목): 쪽 차례, 한 쪽 안에서는 위 칸부터 */
    fun allWrongs(): List<Pair<Int, WrongEntry>> =
        pages.indices.flatMap { i -> wrongEntries(i).sortedBy { it.slot }.map { i to it } }

    fun nextWrongNumber() = (allWrongs().maxOfOrNull { it.second.number } ?: 0) + 1

    /** 번호가 [number]인 오답 항목 (배지의 점선이 원문 자리를 찾을 때) */
    fun wrongByNumber(number: Int): WrongEntry? = wrongs.values.firstNotNullOfOrNull { l -> l.firstOrNull { it.number == number } }

    /** 쪽 밖 여백에 놓일 것(배지·포스트잇)이 하나라도 있는가 */
    fun hasMarginItems(): Boolean = pages.any { l -> l.any { it.isMarginItem() } }

    /** 쪽 구성 바꾸기([changePages])의 edit 안에서 새로 만든 오답 쪽에 항목을 단다 (그 기록이 되돌리는 대로 따라간다) */
    fun attachWrong(list: MutableList<Stroke>, e: WrongEntry) {
        wrongs.getOrPut(list) { mutableListOf() }.add(e)
    }

    /**
     * 이미 있는 [page]쪽의 빈 칸에 오답 [e]를 담는다. [adds]는 (쪽 번호, 획): 오답 쪽의 머리줄·문제 그림과 원문 쪽의 배지.
     * 한 번의 실행 취소로 모두 되돌린다
     */
    fun addWrongToPage(page: Int, e: WrongEntry, adds: List<Pair<Int, Stroke>>) {
        val list = pages[page]
        val before = wrongs[list]?.toList()
        attachWrong(list, e)
        val pairs = adds.map { (p, st) -> pages[p] to st }
        pairs.forEach { (l, st) -> l.add(st) }
        push(Action.Wrong(pairs, list, before, wrongs[list]?.toList()))
    }

    private fun restoreWrongs(list: MutableList<Stroke>, entries: List<WrongEntry>?) {
        if (entries == null) wrongs.remove(list) else wrongs[list] = entries.toMutableList()
    }

    /** 오답의 머리줄·배지 그림을 바꿔 끼운다 (분류를 고친 뒤). 실행 취소 기록에는 남기지 않는다 */
    fun swapWrongStroke(page: Int, old: Stroke, new: Stroke) {
        val list = pages.getOrNull(page) ?: return
        val i = list.indexOf(old)
        if (i >= 0) list[i] = new else list.add(new)
        changed()
    }

    /** 오답 항목 자체를 고쳤음을 알린다 (저장할 것이 생긴다) */
    fun wrongEdited() = changed()

    /** 저장할 오답 정보 (쪽마다 한 줄 글, 목차) */
    fun wrongSave(): WrongSave {
        val metas = HashMap<Int, String>()
        val items = ArrayList<WrongOutlineItem>()
        for (i in pages.indices) {
            val es = wrongs[pages[i]]?.takeIf { it.isNotEmpty() } ?: continue
            val sorted = es.sortedBy { it.slot }
            metas[i] = WrongNote.encode(sorted) { e -> pages.indexOfFirst { it === e.srcList } }
            sorted.forEach { items.add(WrongOutlineItem(i, WrongNote.slotTop(it.slot), it)) }
        }
        return WrongSave(metas, items)
    }

    /** 파일에서 읽은 쪽별 오답 정보를 단다 ([load] 다음에) */
    fun loadWrongs(metas: Map<Int, String>) {
        for ((i, s) in metas) {
            val list = pages.getOrNull(i) ?: continue
            val es = WrongNote.decode(s).map { (e, src) -> e.also { it.srcList = pages.getOrNull(src) } }
            if (es.isNotEmpty()) wrongs[list] = es.toMutableList()
        }
    }

    fun add(page: Int, stroke: Stroke) {
        pages[page].add(stroke)
        push(Action.Add(pages[page], stroke))
    }

    /** 이미 목록에서 지운 획들(과 영역 지우개가 대신 넣은 조각들)을 기록한다 */
    fun erased(items: List<Pair<Int, Stroke>>, pieces: List<Pair<Int, Stroke>> = emptyList()) {
        if (items.isEmpty() && pieces.isEmpty()) return
        push(Action.Erase(items.map { (p, s) -> pages[p] to s }, pieces.map { (p, s) -> pages[p] to s }))
    }

    /** 선택한 획들을 지운다 */
    fun remove(page: Int, strokes: List<Stroke>) {
        val set = strokes.toSet()
        pages[page].removeAll { it in set }
        erased(strokes.map { page to it })
    }

    /** 획 하나를 새 획으로 바꾼다 (글 고치기). 같은 자리(겹친 순서)에 넣는다 */
    fun replace(page: Int, old: Stroke, new: Stroke) {
        val list = pages[page]
        val i = list.indexOf(old)
        if (i < 0) return
        list[i] = new
        erased(listOf(page to old), listOf(page to new))
    }

    /** 선택한 획들을 (dx, dy)만큼 옮긴다 */
    fun move(strokes: List<Stroke>, dx: Float, dy: Float) {
        if (strokes.isEmpty() || (dx == 0f && dy == 0f)) return
        strokes.forEach { it.translate(dx, dy) }
        push(Action.Move(strokes, dx, dy))
    }

    /** 붙여넣기 */
    fun addAll(page: Int, strokes: List<Stroke>) {
        if (strokes.isEmpty()) return
        pages[page].addAll(strokes)
        push(Action.AddAll(pages[page], strokes))
    }

    /** 획들의 색·크기 바꾸기를 한 번의 실행 취소 단위로 기록한다 */
    fun edit(strokes: List<Stroke>, block: () -> Unit) {
        if (strokes.isEmpty()) return
        val before = strokes.map { it.state() }
        block()
        push(Action.Edit(strokes, before, strokes.map { it.state() }))
    }

    /**
     * 쪽 구성 바꾸기 (빈 쪽·PDF 넣기, 쪽 지우기). [edit]가 쪽 목록을 고친다.
     * [before]/[after]는 바뀌기 전·후의 PDF 파일 묶음으로, 실행 취소·다시 실행할 때
     * [swapPages]에 넘겨 화면의 PDF도 같이 되돌리게 한다.
     * 지운 쪽의 획 목록도 기록에 남아 있으므로 그 쪽의 필기 기록은 버리지 않는다
     * (기록은 차례대로만 되돌려지니, 쪽이 돌아온 뒤에야 그 쪽의 기록에 닿는다).
     */
    fun changePages(before: Any, after: Any, edit: (MutableList<MutableList<Stroke>>) -> Unit) {
        val old = pages.toList()
        edit(pages)
        push(Action.Pages(old, pages.toList(), before, after))
    }

    /**
     * 쪽 구성을 실행 취소·다시 실행할 때 부른다. 그 상태의 PDF 파일 묶음을 받아 PDF를 연 뒤
     * apply를 부르면 그제야 필기 쪽 목록과 기록이 바뀐다 (PDF를 못 열면 부르지 않는다).
     * 여는 사이 기록이 바뀌었으면 apply는 아무것도 하지 않고 false를 돌려준다
     */
    var swapPages: ((files: Any, apply: () -> Boolean) -> Unit)? = null

    /** 링크로 옮겨 간 자리를 실행 취소·다시 실행할 때 그 자리로 화면을 옮긴다 */
    var jumpTo: ((spot: Any) -> Unit)? = null

    /** 링크로 [before]에서 [after]로 옮겨 감을 기록한다 (실행 취소하면 돌아온다. 저장할 것이 생기지는 않는다) */
    fun jumped(before: Any, after: Any) {
        undoStack.addLast(Action.Jump(before, after))
        redoStack.clear()
        onChanged?.invoke()
    }

    /** 옮겨 간 기록이면 화면만 옮기고 true */
    private fun jumpIf(a: Action, undo: Boolean): Boolean {
        if (a !is Action.Jump) return false
        if (undo) redoStack.addLast(undoStack.removeLast()) else undoStack.addLast(redoStack.removeLast())
        jumpTo?.invoke(if (undo) a.before else a.after)
        onChanged?.invoke()
        return true
    }

    /** 쪽 구성 기록이면 [swapPages]로 PDF를 먼저 바꾸게 하고 true */
    private fun swapIfPages(a: Action, undo: Boolean): Boolean {
        if (a !is Action.Pages) return false
        val (from, to) = if (undo) undoStack to redoStack else redoStack to undoStack
        val apply = apply@{
            if (from.lastOrNull() !== a) return@apply false
            from.removeLast()
            pages.clear()
            pages.addAll(if (undo) a.before else a.after)
            to.addLast(a)
            changed()
            true
        }
        swapPages?.invoke(if (undo) a.beforeFiles else a.afterFiles, apply) ?: apply()
        return true
    }

    fun undo() {
        val last = undoStack.lastOrNull() ?: return
        if (jumpIf(last, undo = true) || swapIfPages(last, undo = true)) return
        val a = undoStack.removeLast()
        when (a) {
            is Action.Add -> a.page.remove(a.stroke)
            is Action.Erase -> {
                a.pieces.forEach { (p, s) -> p.remove(s) }
                a.items.forEach { (p, s) -> p.add(s) }
            }
            is Action.Move -> a.strokes.forEach { it.translate(-a.dx, -a.dy) }
            is Action.AddAll -> { val set = a.strokes.toSet(); a.page.removeAll { it in set } }
            is Action.Edit -> a.strokes.forEachIndexed { i, s -> s.restore(a.before[i]) }
            is Action.Wrong -> {
                a.adds.forEach { (p, st) -> p.remove(st) }
                restoreWrongs(a.list, a.before)
            }
            is Action.Pages, is Action.Jump -> {}  // swapIfPages, jumpIf
        }
        redoStack.addLast(a)
        changed()
    }

    fun redo() {
        val last = redoStack.lastOrNull() ?: return
        if (jumpIf(last, undo = false) || swapIfPages(last, undo = false)) return
        val a = redoStack.removeLast()
        when (a) {
            is Action.Add -> a.page.add(a.stroke)
            is Action.Erase -> {
                a.items.forEach { (p, s) -> p.remove(s) }
                a.pieces.forEach { (p, s) -> p.add(s) }
            }
            is Action.Move -> a.strokes.forEach { it.translate(a.dx, a.dy) }
            is Action.AddAll -> a.page.addAll(a.strokes)
            is Action.Edit -> a.strokes.forEachIndexed { i, s -> s.restore(a.after[i]) }
            is Action.Wrong -> {
                a.adds.forEach { (p, st) -> p.add(st) }
                restoreWrongs(a.list, a.after)
            }
            is Action.Pages, is Action.Jump -> {}  // swapIfPages, jumpIf
        }
        undoStack.addLast(a)
        changed()
    }

    fun snapshot(): List<List<Stroke>> = pages.map { it.toList() }

    fun markSaved() {
        dirty = false
        onChanged?.invoke()
    }

    /** 자동 저장본에서 복구한 필기: 아직 저장하지 않은 상태로 둔다 (알리지는 않는다) */
    fun restoreDirty() {
        dirty = true
    }

    private fun push(a: Action) {
        undoStack.addLast(a)
        redoStack.clear()
        changed()
    }

    private fun changed() {
        dirty = true
        onChanged?.invoke()
    }
}
