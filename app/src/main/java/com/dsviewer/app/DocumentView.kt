package com.dsviewer.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.util.Log
import android.util.LruCache
import android.util.SizeF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.OverScroller
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * PDF 페이지를 세로로 이어서 보여주고, 손가락으로 이동/확대, 펜으로 필기하는 뷰.
 *
 * 좌표계
 *  - 문서 좌표: PDF 포인트 단위. 페이지들이 세로로 쌓인 하나의 큰 평면.
 *  - 화면 좌표: screen = doc * scale - off
 *  - 페이지 좌표: 각 페이지의 왼쪽 위가 원점 (필기 저장 단위)
 */
class DocumentView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    interface Listener {
        fun onPageChanged(page: Int, count: Int) {}
        /** 올가미로 고른 영역(화면 좌표)과 획 수. 선택이 풀리면 rect = null */
        fun onSelectionChanged(rect: RectF?, count: Int) {}
        /** 보정 펜으로 그린 획을 고른 도형으로 맞추지 못함 */
        fun onShapeFailed(kind: ShapeKind) {}
        /** 펜(또는 손가락 필기)이 문서에 닿음 */
        fun onPenDown() {}
        /** 글 도구로 쪽의 (x, y)를 톡 누름. 이미 있는 글 위면 [existing]이 그 글 */
        fun onTextTap(page: Int, x: Float, y: Float, existing: Stroke?) {}
        /** 화면을 다시 그림 (스크롤·확대가 바뀌었을 수 있다. 문서 위에 띄운 글 상자를 따라 옮길 때) */
        fun onViewportChanged() {}
    }

    var listener: Listener? = null

    // ---- 도구 설정 ----
    var tool = Tool.PEN
        set(v) {
            field = v
            if (v != Tool.LASSO) clearSelection()
        }
    var penColor = Color.BLACK
    var penWidth = 1.0f
    /** 펜 종류 (사인펜·볼펜·만년필·붓펜·연필·캘리그래피) */
    var penStyle = PenStyle.FELT
    /** 손떨림 보정 단계 (0 끔 ~ 3 강하게) */
    var penSmoothing = 0
    private val penInput = PenInput()
    var hlColor = 0xFFFFEB3B.toInt()
    var hlWidth = 12f
    /** 줄자: 형광펜을 시작점에서 지금 점까지 곧은 선으로만 긋는다 */
    var hlStraight = false
    var eraserRadiusDp = 12f
    var laserColor = 0xFFFF1744.toInt()
    /** 레이저 굵기 (화면 dp, 확대해도 그대로) */
    var laserWidthDp = 7f
    /** 레이저를 마지막으로 뗀 뒤 사라지기 시작할 때까지 (ms) */
    var laserFadeMs = 2000L
    /** 획 지우개 / 영역 지우개 */
    var eraserMode = EraserMode.STROKE
    /** true면 형광펜 획만 지운다 */
    var eraseHlOnly = false
    /** true면 손가락 한 개로 필기, 두 손가락으로 이동/확대 */
    var fingerDrawing = false
    /** 읽기 모드: 펜도 손가락처럼 넘기고 확대만 한다 (필기·지우기·선택 없음) */
    var readOnly = false
        set(v) {
            if (field == v) return
            if (v && penPointerId != -1) endPen(commit = true)
            field = v
            if (v) clearSelection()
        }
    /**
     * 양쪽 보기: 화면이 가로로 길면 쪽을 두 개씩 나란히 (1·2쪽, 3·4쪽 …).
     * 세로 화면에서는 켜 두어도 한 쪽씩
     */
    var twoPage = false
        set(v) {
            if (field == v) return
            field = v
            relayout()
        }
    /** 지금 두 쪽씩 놓여 있는지 */
    private var spread = false
    /** 글자 찾기 결과: 쪽마다 강조할 상자 (쪽 좌표) */
    var searchHits: Map<Int, List<RectF>> = emptyMap()
        set(v) {
            field = v
            invalidate()
        }
    private val hitPaint = Paint().apply {
        color = 0xFFFFC94D.toInt()
        blendMode = BlendMode.MULTIPLY
    }
    /** 보정 펜으로 그릴 도형 */
    var shapeKind = ShapeKind.LINE
    /** 보정 펜: 그리는 중에 맞춰 본 도형 (흐리게 미리 보여 줌) */
    private var shapePreview: List<Stroke>? = null
    /** 보정 펜: 보조선(지수·로그·탄젠트·쌍곡선의 점근선, 사인·코사인의 축)을 그리는 방식 */
    var shapeGuide = GuideStyle.NONE
    /**
     * 미리 보기는 따로 도는 스레드에서 맞춘다 (무거운 도형도 필기가 멈추지 않도록).
     * 하나가 도는 동안 들어온 요청은 끝난 뒤 마지막 획으로 한 번만. 획이 바뀌면 shapeGen이 늘어 늦게 온 결과를 버린다
     */
    private val shapeWorker = java.util.concurrent.Executors.newSingleThreadExecutor()
    private var shapeFitting = false
    private var shapePending = false
    private var shapeGen = 0
    // ---- 테이프 ----
    var tapeColor = 0xFFF6C744.toInt()
    /** 펜 테이프 굵기 (pt) */
    var tapeWidth = 16f
    var tapePattern = TapePattern.STRIPE
    /** true면 네모 테이프 (끌어서 네모), false면 펜 테이프 */
    var tapeRect = false
    /** 펜 테이프: 거의 곧게 그으면 곧은 선으로 편다 */
    var tapeStraight = true
    /** 펜 테이프: 아래 글자 줄의 높이에 맞춰 굵기와 자리를 맞춘다 */
    var tapeFitText = true
    /** true면 테이프 도구가 테이프만 지우는 지우개 */
    var tapeErasing = false
    /** 테이프 지우개: 획(테이프를 통째로) / 영역(지나간 부분만) */
    var tapeEraseMode = EraserMode.STROKE
    /** 펜(또는 손가락 필기)이 닿은 뒤 거의 움직이지 않았는지: 테이프를 톡 누른 것인지 보려고 */
    private var tapCandidate = false
    private var tapDownTime = 0L
    private var tapDownSx = 0f
    private var tapDownSy = 0f

    /** 글 도구: 누른 자리 (쪽, x, y). 떼기 전에 많이 움직이면 취소 */
    private var textTap: Triple<Int, Float, Float>? = null
    private var textPressing = false
    private var textTapSx = 0f
    private var textTapSy = 0f

    private val density = resources.displayMetrics.density

    // ---- 문서 ----
    private var doc: PdfDoc? = null
    private var ink: InkDocument? = null
    private var sizes: List<SizeF> = emptyList()
    private var tops = FloatArray(0)
    private var lefts = FloatArray(0)
    private var docW = 0f
    private var docH = 0f
    private val gap = 10f

    // ---- 화면 변환 ----
    private var baseScale = 1f
    private var zoom = 1f
    private val scale get() = baseScale * zoom
    private var offX = 0f
    private var offY = 0f
    private var lastReportedPage = -1

    // ---- 렌더링 ----
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val baseCache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 4).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val baseJobs = HashMap<Int, Job>()

    private class Detail(val page: Int, val region: RectF, val bmp: Bitmap)

    private var details: List<Detail> = emptyList()
    private var detailJob: Job? = null
    private val detailRunnable = Runnable { renderDetails() }

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val pagePaint = Paint().apply { color = Color.WHITE }
    private val borderPaint = Paint().apply {
        color = 0x22000000
        style = Paint.Style.STROKE
        strokeWidth = 1f
    }
    private val strokePaint = inkPaint()
    private val cursorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = 0xAA555555.toInt()
    }
    private val tmpRect = RectF()

    // ---- 입력 상태 ----
    private val scroller = OverScroller(context)
    private var fingerActive = false
    private var scaling = false
    private var fingersBlocked = false
    private var penPointerId = -1
    private var penIsFinger = false
    private var penErasing = false
    private var curStroke: Stroke? = null
    private var curPage = -1
    private var lastPressure = 0.5f
    private var lastSx = 0f
    private var lastSy = 0f
    private val erased = ArrayList<Pair<Int, Stroke>>()
    /** 영역 지우개가 이번 획에서 잘라 남긴 조각들 */
    private val pieces = ArrayList<Pair<Int, Stroke>>()
    private var zoomAnimator: ValueAnimator? = null

    // ---- 올가미 선택 (좌표는 모두 해당 페이지 기준) ----
    private var selPage = -1
    private var selection: List<Stroke> = emptyList()
    private var selSet: Set<Stroke> = emptySet()
    private val selBounds = RectF()
    private var lassoing = false
    private var lassoPage = -1
    private var lasso = FloatArray(512)
    private var lassoCount = 0
    private var moving = false
    private var moveStartX = 0f
    private var moveStartY = 0f
    private var moveDx = 0f
    private var moveDy = 0f
    /** 모서리 손잡이로 크기 조절 중: 반대쪽 모서리(anchor)를 기준으로 scaleK배 */
    private var resizing = false
    private var anchorX = 0f
    private var anchorY = 0f
    private var startDist = 1f
    private var scaleK = 1f
    /** 회전 손잡이로 돌리는 중: 선택 영역 가운데(rotCx, rotCy)를 중심으로 rotDeg도 */
    private var rotating = false
    private var rotCx = 0f
    private var rotCy = 0f
    private var rotStart = 0f
    private var rotDeg = 0f
    private val rotIconPath = Path()
    private val rotBubblePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE0303030.toInt() }
    private val rotBubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 14f * resources.displayMetrics.density
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    /** true면 네모 선택, false면 자유 선택(올가미) */
    var lassoRect = false
    // ---- 레이저 (쪽 좌표. 저장하지 않고, 마지막 획을 떼고 laserFadeMs 뒤 한꺼번에 사라진다) ----
    private val laserStrokes = ArrayList<Pair<Int, Stroke>>()
    private var laserAlpha = 1f
    private var laserFadeAnim: ValueAnimator? = null
    private val laserFadeRunnable = Runnable { startLaserFade() }
    private val laserPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val laserPath = Path()
    private var laserBlur: BlurMaskFilter? = null
    private var laserBlurRadius = -1f

    /** 복사해 둔 획 (복사한 영역의 왼쪽 위가 원점) */
    private var clipboard: List<Stroke> = emptyList()
    private val clipSize = RectF()
    val hasClipboard get() = clipboard.isNotEmpty()
    private val previewRect = RectF()
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val handleLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        color = SEL_COLOR
    }
    private var reportedSel: RectF? = null
    private val lassoPath = Path()
    private val selRect = RectF()
    private val selLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * resources.displayMetrics.density
        color = SEL_COLOR
        val d = resources.displayMetrics.density
        pathEffect = DashPathEffect(floatArrayOf(6 * d, 4 * d), 0f)
    }
    private val selFill = Paint().apply { color = SEL_COLOR and 0x00FFFFFF or 0x18000000 }

    /** 탭을 바꿀 때 되살릴 스크롤·확대 위치 */
    class ViewState(val zoom: Float, val offX: Float, val offY: Float)

    fun viewState(): ViewState? = if (doc == null) null else ViewState(zoom, offX, offY)

    /** 문서 없이 빈 화면 (탭의 문서를 읽는 중) */
    fun clearDocument() {
        clearSelection()
        clearLaser()
        cancelRendering()
        doc = null
        ink = null
        sizes = emptyList()
        baseCache.evictAll()
        details = emptyList()
        invalidate()
    }

    private fun cancelRendering() {
        baseJobs.values.forEach { it.cancel() }
        baseJobs.clear()
        detailJob?.cancel()
        removeCallbacks(detailRunnable)
        scroller.forceFinished(true)
        zoomAnimator?.cancel()
    }

    fun setDocument(d: PdfDoc, inkDoc: InkDocument, state: ViewState? = null) {
        clearSelection()
        clearLaser()
        cancelRendering()
        doc = d
        ink = inkDoc
        sizes = d.sizes
        layoutPages()
        zoom = 1f
        baseCache.evictAll()
        details = emptyList()
        if (width > 0) {
            baseScale = width / docW
            offY = 0f
            if (state != null) {
                zoom = state.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
                offX = state.offX
                offY = state.offY
            }
            clamp()
            scheduleDetail()
        }
        lastReportedPage = -1
        invalidate()
        post { flashBar() }
    }

    // ================= 레이아웃 / 변환 =================

    /** 쪽을 한 줄에 하나(양쪽 보기면 둘)씩 쌓아 [tops]·[lefts]·[docW]·[docH]를 정한다 */
    private fun layoutPages() {
        val n = sizes.size
        spread = twoPage && width > height && n > 1
        tops = FloatArray(n)
        lefts = FloatArray(n)
        val per = if (spread) 2 else 1
        val rows = (0 until n step per).map { it until min(n, it + per) }
        // 줄 너비 = 쪽 너비의 합 + 쪽 사이 여백. 문서 너비는 가장 넓은 줄에 맞춘다
        val rowW = rows.map { r -> r.sumOf { sizes[it].width.toDouble() }.toFloat() + gap * (r.count() - 1) }
        docW = (rowW.maxOrNull() ?: 600f) + gap * 2
        var y = gap
        for ((k, r) in rows.withIndex()) {
            var x = (docW - rowW[k]) / 2f
            var rowH = 0f
            for (i in r) {
                lefts[i] = x
                tops[i] = y
                x += sizes[i].width + gap
                rowH = max(rowH, sizes[i].height)
            }
            y += rowH + gap
        }
        docH = y
    }

    /** 쪽 배치를 다시 하고, 보던 쪽이 화면 위에 오도록 */
    private fun relayout() {
        if (doc == null || width == 0) return
        val page = currentPage()
        layoutPages()
        baseScale = width / docW
        zoom = 1f
        baseCache.evictAll()
        details = emptyList()
        clearSelection()
        lastReportedPage = -1
        scrollToPage(page)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (doc == null || w == 0) return
        // 양쪽 보기에서 화면이 가로↔세로로 바뀌면 쪽 배치를 새로
        if (twoPage && sizes.size > 1 && (w > h) != spread) {
            relayout()
            return
        }
        // 화면 가운데에 있던 문서 위치를 유지. 높이만 바뀌면(화면 키보드) 위쪽을 그대로 둔다
        val anchorY = if (oldw > 0) (offY + oldh / 2f) / scale else 0f
        baseScale = w / docW
        if (oldw != w) offY = if (oldw > 0) anchorY * scale - h / 2f else 0f
        clamp()
        baseCache.evictAll()
        details = emptyList()
        invalidate()
    }

    /**
     * 화면 아래쪽을 가리는 줄(보정 펜 도형 줄, 지우개 옵션 줄)의 높이(px).
     * 그만큼 더 스크롤할 수 있게 해서 마지막 쪽 아래 끝도 줄 위로 올려 쓸 수 있다.
     */
    var bottomInset = 0f
        set(v) {
            if (field == v) return
            field = v
            if (doc != null) {
                clamp()
                invalidate()
            }
        }

    /**
     * 화면 위쪽을 가리는 줄의 높이(px). 툴바를 위에 붙이면 도형 줄·옵션 줄이 위에 뜬다.
     * 그만큼 문서를 위로 더 내려 볼 수 있게 한다 (offY가 -topInset까지)
     */
    var topInset = 0f
        set(v) {
            if (field == v) return
            field = v
            if (doc != null) {
                clamp()
                invalidate()
            }
        }

    /** 문서를 [dy](화면 px)만큼 위로 올린다 (음수면 내린다). 글 상자 커서를 키보드 위로 보일 때 */
    fun scrollByPx(dy: Float) {
        if (doc == null || dy == 0f) return
        offY += dy
        clamp()
        invalidate()
    }

    /** 스크롤할 수 있는 전체 높이 (화면 px) */
    private fun contentH() = docH * scale + bottomInset + topInset

    private fun clamp() {
        val cw = docW * scale
        val ch = contentH()
        offX = if (cw <= width) -(width - cw) / 2f else offX.coerceIn(0f, cw - width)
        offY = if (ch <= height) -(height - ch) / 2f - topInset else offY.coerceIn(-topInset, ch - height - topInset)
    }

    private fun pageRect(i: Int, out: RectF): RectF {
        val s = scale
        out.left = lefts[i] * s - offX
        out.top = tops[i] * s - offY
        out.right = out.left + sizes[i].width * s
        out.bottom = out.top + sizes[i].height * s
        return out
    }

    private fun visibleRange(): IntRange {
        if (sizes.isEmpty()) return IntRange.EMPTY
        val s = scale
        val topDoc = offY / s
        val bottomDoc = (offY + height) / s
        var first = -1
        var last = -1
        for (i in sizes.indices) {
            val t = tops[i]
            val b = t + sizes[i].height
            if (b >= topDoc && t <= bottomDoc) {
                if (first < 0) first = i
                last = i
            } else if (first >= 0 && t > bottomDoc) break
        }
        return if (first < 0) IntRange.EMPTY else first..last
    }

    /** 화면 좌표 → (페이지, 페이지 x, 페이지 y). 페이지 사이 여백은 가까운 페이지로 */
    private fun hitPage(sx: Float, sy: Float): Triple<Int, Float, Float>? {
        val s = scale
        val dx = (sx + offX) / s
        val dy = (sy + offY) / s
        // 높이가 맞는 쪽 가운데 가로로 가장 가까운 쪽 (양쪽 보기면 한 줄에 둘)
        var best = -1
        var bestDist = Float.MAX_VALUE
        for (i in sizes.indices) {
            val t = tops[i] - gap / 2
            val b = tops[i] + sizes[i].height + gap / 2
            if (dy !in t..b) {
                if (best >= 0 && t > dy) break
                continue
            }
            val px = dx - lefts[i]
            val dist = if (px < 0) -px else max(0f, px - sizes[i].width)
            if (dist < bestDist) { best = i; bestDist = dist }
        }
        if (best < 0 || bestDist > gap) return null
        return Triple(best, dx - lefts[best], dy - tops[best])
    }

    /** 화면 가운데 줄의 첫 쪽 (문서가 없으면 -1) */
    private fun rowFirstPage(): Int {
        if (sizes.isEmpty()) return -1
        val centerDoc = (offY + height / 2f) / scale
        return sizes.indices.firstOrNull { tops[it] + sizes[it].height + gap / 2 >= centerDoc } ?: sizes.lastIndex
    }

    /** 화면 가운데에 걸친 쪽 (문서가 없으면 -1). 양쪽 보기면 그 줄에서 화면 가운데에 걸친 쪽 */
    fun currentPage(): Int {
        val first = rowFirstPage()
        if (!spread || first < 0) return first
        val cx = (offX + width / 2f) / scale
        var page = first
        for (i in first + 1 until sizes.size) {
            if (tops[i] != tops[first]) break
            if (cx >= lefts[i]) page = i
        }
        return page
    }

    /** 양쪽 보기로 두 쪽씩 놓여 있는지 */
    val isSpread get() = spread

    /** [page]쪽의 위쪽 끝이 화면 맨 위에 오도록 옮긴다 */
    fun scrollToPage(page: Int) {
        if (page !in sizes.indices || width == 0) return
        scroller.forceFinished(true)
        zoomAnimator?.cancel()
        offY = (tops[page] - gap / 2) * scale - topInset
        clamp()
        scheduleDetail()
        invalidate()
    }

    /** [page]쪽의 (x, y)가 화면에 들어오도록 옮긴다 (찾은 글자로 갈 때). 위에서 1/3쯤에 오게 */
    fun scrollToPoint(page: Int, x: Float, y: Float) {
        if (page !in sizes.indices || width == 0) return
        scroller.forceFinished(true)
        zoomAnimator?.cancel()
        offY = (tops[page] + y) * scale - height / 3f
        val sx = (lefts[page] + x) * scale - offX
        if (sx < 0 || sx > width) offX = (lefts[page] + x) * scale - width / 2f
        clamp()
        scheduleDetail()
        invalidate()
    }

    private fun toPageX(page: Int, sx: Float) = (sx + offX) / scale - lefts[page]
    private fun toPageY(page: Int, sy: Float) = (sy + offY) / scale - tops[page]

    // ================= 그리기 =================

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val d = doc ?: return
        val inkDoc = ink ?: return
        val range = visibleRange()
        val s = scale
        for (i in range) {
            val r = pageRect(i, tmpRect)
            canvas.drawRect(r, pagePaint)
            val bmp = baseCache.get(i)
            if (bmp != null) canvas.drawBitmap(bmp, null, r, bmpPaint) else requestBase(i)
            for (det in details) {
                if (det.page != i) continue
                val dst = RectF(
                    r.left + det.region.left * s, r.top + det.region.top * s,
                    r.left + det.region.right * s, r.top + det.region.bottom * s
                )
                canvas.drawBitmap(det.bmp, null, dst, bmpPaint)
            }
            canvas.drawRect(r, borderPaint)

            canvas.save()
            canvas.clipRect(r)
            canvas.translate(r.left, r.top)
            canvas.scale(s, s)
            // 찾은 글자: 형광펜처럼 글자 아래에 비치게
            searchHits[i]?.forEach { canvas.drawRect(it, hitPaint) }
            val dragging = (moving || resizing || rotating) && selPage == i
            // 그림을 먼저, 필기를 그 위에
            for (st in inkDoc.pages[i]) if (st.image != null && (!dragging || st !in selSet)) drawStroke(canvas, st)
            for (st in inkDoc.pages[i]) if (st.image == null && st !== hiddenStroke && (!dragging || st !in selSet)) drawStroke(canvas, st)
            if (dragging) {
                // 옮기거나 크기를 바꾸는 중인 획은 손을 뗄 때까지 그림만 바꿔 그린다
                canvas.save()
                canvas.translate(moveDx, moveDy)
                if (resizing) canvas.scale(scaleK, scaleK, anchorX, anchorY)
                if (rotating) canvas.rotate(rotDeg, rotCx, rotCy)
                for (st in selection) drawStroke(canvas, st)
                canvas.restore()
            }
            for ((p, st) in laserStrokes) if (p == i) drawLaser(canvas, st, laserAlpha)
            if (curPage == i) curStroke?.let {
                if (it.tool == Tool.LASER) drawLaser(canvas, it, 1f)
                else if (tool == Tool.SHAPE) {
                    // 보정 펜: 내 획은 흐리게, 맞춘 도형은 조금 더 진하게 미리 보기
                    drawStroke(canvas, it, 0.3f)
                    shapePreview?.forEach { sp -> drawStroke(canvas, sp, 0.6f) }
                } else drawStroke(canvas, it)
            }
            canvas.restore()
        }
        drawSelection(canvas)
        if (!range.isEmpty()) {
            // 앞뒤 한 페이지 미리 그리기
            if (range.first > 0 && baseCache.get(range.first - 1) == null) requestBase(range.first - 1)
            if (range.last < sizes.size - 1 && baseCache.get(range.last + 1) == null) requestBase(range.last + 1)
            cancelUnneededJobs(range.first - 1, range.last + 1)
        }
        if (penPointerId != -1 && penErasing) {
            canvas.drawCircle(lastSx, lastSy, eraserRadiusDp * density, cursorPaint)
        }
        drawScrollBar(canvas)
        listener?.onViewportChanged()
        reportPage(d.pageCount)
    }

    /** 올가미 선과 선택 상자 (화면 좌표) */
    private fun drawSelection(canvas: Canvas) {
        val s = scale
        if (lassoing && lassoCount > 1) {
            val ox = lefts[lassoPage] * s - offX
            val oy = tops[lassoPage] * s - offY
            if (lassoRect) {
                // 네모 선택: 시작점과 지금 점이 마주 보는 모서리
                canvas.drawRect(
                    ox + min(lasso[0], lasso[2]) * s, oy + min(lasso[1], lasso[3]) * s,
                    ox + max(lasso[0], lasso[2]) * s, oy + max(lasso[1], lasso[3]) * s, selLine
                )
            } else {
                lassoPath.reset()
                lassoPath.moveTo(ox + lasso[0] * s, oy + lasso[1] * s)
                for (k in 1 until lassoCount) lassoPath.lineTo(ox + lasso[k * 2] * s, oy + lasso[k * 2 + 1] * s)
                canvas.drawPath(lassoPath, selLine)
            }
        }
        val rect = if (selection.isEmpty()) null else selectionScreenRect(selRect)
        if (rect != null && rotating) {
            // 돌리는 중: 상자를 같이 돌려 그리고, 손잡이 옆에 각도를 띄운다
            canvas.save()
            canvas.rotate(rotDeg, rect.centerX(), rect.centerY())
            canvas.drawRect(rect, selFill)
            canvas.drawRect(rect, selLine)
            canvas.restore()
            val (hx, hy) = rotateHandlePos(rect)
            // 수학에서처럼 시계 반대 방향을 +로 보여 준다
            val text = "${(-rotDeg).roundToInt()}°"
            val pad = 10f * density
            val bw = rotBubbleText.measureText(text) + pad * 2
            val bh = 30f * density
            val bx = hx + ROT_HANDLE_DP * density + 8f * density
            val by = hy - bh / 2
            canvas.drawRoundRect(bx, by, bx + bw, by + bh, bh / 2, bh / 2, rotBubblePaint)
            val fm = rotBubbleText.fontMetrics
            canvas.drawText(text, bx + pad, by + bh / 2 - (fm.ascent + fm.descent) / 2, rotBubbleText)
        } else if (rect != null) {
            canvas.drawRect(rect, selFill)
            canvas.drawRect(rect, selLine)
            // 크기 조절 손잡이
            val hr = HANDLE_DP * density
            for (cx in floatArrayOf(rect.left, rect.right)) for (cy in floatArrayOf(rect.top, rect.bottom)) {
                canvas.drawCircle(cx, cy, hr, handleFill)
                canvas.drawCircle(cx, cy, hr, handleLine)
            }
            if (!moving && !resizing) drawRotateHandle(canvas, rect)
        }
        // 옮기거나 크기를 바꾸거나 돌리는 동안에는 막대를 숨긴다.
        // 알리는 영역에는 회전 손잡이도 넣어, 막대가 손잡이를 가리지 않게 한다
        val report = if (moving || resizing || rotating || rect == null) null else {
            val (hx, hy) = rotateHandlePos(rect)
            val hr = ROT_HANDLE_DP * density
            RectF(rect).apply { union(hx - hr, hy - hr, hx + hr, hy + hr) }
        }
        if (report != reportedSel) {
            reportedSel = report?.let { RectF(it) }
            listener?.onSelectionChanged(reportedSel, selection.size)
        }
    }

    /**
     * 회전 손잡이의 가운데 (화면 좌표): 선택 상자 아래 가운데. 아래에 자리가 없으면 위
     * (선택 막대는 보통 상자 위에 뜨므로 겹치지 않게 아래를 먼저)
     */
    private fun rotateHandlePos(rect: RectF): Pair<Float, Float> {
        val off = ROT_OFFSET_DP * density
        val hr = ROT_HANDLE_DP * density
        val below = rect.bottom + off
        val y = if (below + hr <= height - bottomInset - 4f * density || rect.top - off - hr < 0f) below else rect.top - off
        return rect.centerX() to y
    }

    /** 상자에서 뻗은 줄 끝의 동그란 회전 손잡이 (둥근 화살표 그림) */
    private fun drawRotateHandle(canvas: Canvas, rect: RectF) {
        val (hx, hy) = rotateHandlePos(rect)
        val hr = ROT_HANDLE_DP * density
        val edgeY = if (hy > rect.bottom) rect.bottom else rect.top
        canvas.drawLine(hx, edgeY, hx, if (hy > rect.bottom) hy - hr else hy + hr, handleLine)
        canvas.drawCircle(hx, hy, hr, handleFill)
        canvas.drawCircle(hx, hy, hr, handleLine)
        // ↻: 위쪽이 트인 원호(시계 방향으로 그림)와, 원호 끝에서 진행 방향을 가리키는 화살촉
        val ar = hr * 0.5f
        val a0 = -45f
        val sweep = 290f
        rotIconPath.reset()
        rotIconPath.addArc(hx - ar, hy - ar, hx + ar, hy + ar, a0, sweep)
        val end = Math.toRadians((a0 + sweep).toDouble())
        val ex = hx + ar * kotlin.math.cos(end).toFloat()
        val ey = hy + ar * kotlin.math.sin(end).toFloat()
        // 시계 방향 접선 (tx, ty)의 반대쪽으로 ±35° 벌린 두 날개.
        // 촉 전체를 끝점 기준으로 반시계 방향 20° 돌려야 ↻처럼 보인다
        val tx = -kotlin.math.sin(end)
        val ty = kotlin.math.cos(end)
        val len = hr * 0.4f
        val turn = Math.toRadians(-20.0)
        for (sg in intArrayOf(1, -1)) {
            val w = Math.toRadians(35.0 * sg) + turn
            val bx = -(tx * kotlin.math.cos(w) - ty * kotlin.math.sin(w))
            val by = -(tx * kotlin.math.sin(w) + ty * kotlin.math.cos(w))
            rotIconPath.moveTo(ex + (bx * len).toFloat(), ey + (by * len).toFloat())
            rotIconPath.lineTo(ex, ey)
        }
        canvas.drawPath(rotIconPath, handleLine)
    }

    private fun rotateHandleHit(sx: Float, sy: Float): Boolean {
        if (selection.isEmpty()) return false
        val (hx, hy) = rotateHandlePos(selectionScreenRect(RectF()))
        return hypot(sx - hx, sy - hy) <= HANDLE_TOUCH_DP * density
    }

    /** 선택 영역(페이지 좌표)에 옮기기·크기 조절 중인 변화를 반영한다 */
    private fun previewBounds(out: RectF): RectF {
        out.set(selBounds)
        if (resizing) {
            out.set(
                anchorX + (out.left - anchorX) * scaleK, anchorY + (out.top - anchorY) * scaleK,
                anchorX + (out.right - anchorX) * scaleK, anchorY + (out.bottom - anchorY) * scaleK,
            )
            out.sort()
        }
        out.offset(moveDx, moveDy)
        return out
    }

    /** 선택 상자(여백 포함)를 화면 좌표로 */
    private fun selectionScreenRect(out: RectF): RectF {
        val s = scale
        val pad = 6f * density
        val b = previewBounds(previewRect)
        out.set(
            (lefts[selPage] + b.left) * s - offX - pad,
            (tops[selPage] + b.top) * s - offY - pad,
            (lefts[selPage] + b.right) * s - offX + pad,
            (tops[selPage] + b.bottom) * s - offY + pad,
        )
        return out
    }

    private fun drawStroke(c: Canvas, st: Stroke, alphaMul: Float = 1f) = drawInkStroke(c, strokePaint, st, alphaMul)

    /**
     * 레이저 획: 번진 빛 + 색 선 + 가운데 흰 심. 캔버스는 쪽 좌표라서
     * 굵기(dp)를 지금 배율로 나눠 화면에서 늘 같은 굵기로 보이게 한다
     */
    private fun drawLaser(c: Canvas, st: Stroke, a: Float) {
        if (st.count == 0 || a <= 0f) return
        laserPath.reset()
        laserPath.moveTo(st.x(0), st.y(0))
        if (st.count == 1) laserPath.lineTo(st.x(0) + 0.01f, st.y(0))
        else for (k in 1 until st.count) laserPath.lineTo(st.x(k), st.y(k))
        val w = st.width * density / scale
        val blur = w * 0.9f
        if (blur != laserBlurRadius) {
            laserBlur = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            laserBlurRadius = blur
        }
        laserPaint.maskFilter = laserBlur
        laserPaint.color = st.color
        laserPaint.alpha = (140 * a).roundToInt()
        laserPaint.strokeWidth = w * 2.6f
        c.drawPath(laserPath, laserPaint)
        laserPaint.maskFilter = null
        laserPaint.color = st.color
        laserPaint.alpha = (255 * a).roundToInt()
        laserPaint.strokeWidth = w
        c.drawPath(laserPath, laserPaint)
        laserPaint.color = Color.WHITE
        laserPaint.alpha = (210 * a).roundToInt()
        laserPaint.strokeWidth = w * 0.35f
        c.drawPath(laserPath, laserPaint)
    }

    /** 레이저를 새로 긋기 시작하면 사라지던 것도 다시 또렷하게 */
    private fun holdLaser() {
        removeCallbacks(laserFadeRunnable)
        laserFadeAnim?.cancel()
        laserFadeAnim = null
        laserAlpha = 1f
    }

    private fun startLaserFade() {
        laserFadeAnim?.cancel()
        laserFadeAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 400
            addUpdateListener {
                laserAlpha = it.animatedValue as Float
                invalidate()
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: android.animation.Animator) { canceled = true }
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (canceled) return
                    laserStrokes.clear()
                    laserAlpha = 1f
                    laserFadeAnim = null
                    invalidate()
                }
            })
            start()
        }
    }

    private fun clearLaser() {
        holdLaser()
        laserStrokes.clear()
    }

    private fun reportPage(count: Int) {
        if (sizes.isEmpty()) return
        val page = rowFirstPage()
        if (page != lastReportedPage) {
            lastReportedPage = page
            listener?.onPageChanged(page, count)
        }
    }

    // ================= 비트맵 렌더링 =================

    private fun baseRenderScale(i: Int): Float {
        val sz = sizes[i]
        val maxPixels = 8_000_000f
        return min(baseScale, sqrt(maxPixels / (sz.width * sz.height)))
    }

    private fun requestBase(i: Int) {
        if (baseJobs.containsKey(i)) return
        val d = doc ?: return
        val s = baseRenderScale(i)
        val w = max(1, (sizes[i].width * s).roundToInt())
        val h = max(1, (sizes[i].height * s).roundToInt())
        val job = scope.launch {
            val me = currentCoroutineContext()[Job]
            try {
                val bmp = withContext(d.dispatcher) { d.render(i, s, 0f, 0f, w, h) }
                if (doc !== d) return@launch  // 그사이 다른 탭으로 바뀜
                baseCache.put(i, bmp)
                invalidate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "page $i render failed", e)
            } finally {
                if (baseJobs[i] === me) baseJobs.remove(i)
            }
        }
        if (!job.isCompleted) baseJobs[i] = job
    }

    private fun cancelUnneededJobs(from: Int, to: Int) {
        val it = baseJobs.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (e.key < from || e.key > to) {
                e.value.cancel()
                it.remove()
            }
        }
    }

    private fun scheduleDetail() {
        removeCallbacks(detailRunnable)
        postDelayed(detailRunnable, 180)
    }

    /** 확대 상태에서 보이는 부분만 현재 배율로 선명하게 다시 그린다 */
    private fun renderDetails() {
        detailJob?.cancel()
        val d = doc ?: return
        if (zoom <= 1.1f) {
            if (details.isNotEmpty()) { details = emptyList(); invalidate() }
            return
        }
        if (fingerActive || !scroller.isFinished || zoomAnimator?.isRunning == true) {
            scheduleDetail()
            return
        }
        val s = scale
        val reqs = ArrayList<Pair<Int, RectF>>()
        for (i in visibleRange()) {
            val r = pageRect(i, RectF())
            val vis = RectF(max(r.left, 0f), max(r.top, 0f), min(r.right, width.toFloat()), min(r.bottom, height.toFloat()))
            if (vis.width() <= 1 || vis.height() <= 1) continue
            reqs.add(i to RectF((vis.left - r.left) / s, (vis.top - r.top) / s, (vis.right - r.left) / s, (vis.bottom - r.top) / s))
        }
        detailJob = scope.launch {
            val list = ArrayList<Detail>()
            try {
                for ((page, region) in reqs) {
                    val w = (region.width() * s).roundToInt()
                    val h = (region.height() * s).roundToInt()
                    if (w <= 0 || h <= 0) continue
                    val bmp = withContext(d.dispatcher) { d.render(page, s, region.left, region.top, w, h) }
                    list.add(Detail(page, region, bmp))
                }
                if (doc !== d) return@launch  // 그사이 다른 탭으로 바뀜
                details = list
                invalidate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "detail render failed", e)
            }
        }
    }

    // ================= 손가락 제스처 =================

    private val scaleDetector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        private var lastFx = 0f
        private var lastFy = 0f

        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            lastFx = detector.focusX
            lastFy = detector.focusY
            scaling = true
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val docX = (lastFx + offX) / scale
            val docY = (lastFy + offY) / scale
            zoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            offX = docX * scale - detector.focusX
            offY = docY * scale - detector.focusY
            lastFx = detector.focusX
            lastFy = detector.focusY
            clamp()
            invalidate()
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            scaling = false
            scheduleDetail()
        }
    }).apply { isQuickScaleEnabled = false }

    private val gestureDetector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean {
            scroller.forceFinished(true)
            zoomAnimator?.cancel()
            return true
        }

        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (scaling) return true
            val before = offY
            offX += distanceX
            offY += distanceY
            clamp()
            noteScrolled(offY - before)
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (scaling) return false
            val cw = docW * scale
            val ch = contentH()
            val maxX = max(0f, cw - width).toInt()
            val maxY = (max(0f, ch - height) - topInset).toInt()
            val minX = if (cw <= width) offX.toInt() else 0
            val minY = if (ch <= height) offY.toInt() else -topInset.toInt()
            scroller.fling(
                offX.toInt(), offY.toInt(), -velocityX.toInt(), -velocityY.toInt(),
                minX, max(minX, if (cw <= width) minX else maxX),
                minY, max(minY, if (ch <= height) minY else maxY)
            )
            postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = if (zoom < 1.5f) 2.5f else 1f
            animateZoom(target, e.x, e.y)
            return true
        }

        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // 글 도구: 손가락으로 톡 눌러도 글을 넣거나 고친다 (손가락 필기를 꺼 두었을 때)
            if (tool == Tool.TEXT && !readOnly) hitPage(e.x, e.y)?.let { tapText(it.first, it.second, it.third) }
            // 다른 도구에서는 손가락으로 테이프를 톡 누르면 보였다 가려졌다
            else toggleTapeAt(e.x, e.y)
            return true
        }
    })

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        zoomAnimator?.cancel()
        val docX = (fx + offX) / scale
        val docY = (fy + offY) / scale
        zoomAnimator = ValueAnimator.ofFloat(zoom, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                zoom = it.animatedValue as Float
                offX = docX * scale - fx
                offY = docY * scale - fy
                clamp()
                invalidate()
            }
            start()
        }
        scheduleDetail()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val before = offY
            offX = scroller.currX.toFloat()
            offY = scroller.currY.toFloat()
            clamp()
            noteScrolled(offY - before)
            postInvalidateOnAnimation()
            scheduleDetail()
        }
    }

    // ================= 빠른 스크롤 손잡이 =================
    // 삼성 노트처럼 오른쪽 가장자리에 위아래 화살표가 그려진 손잡이.
    // 스크롤하면 나타나고 2초 동안 움직임이 없으면 사라진다 (문서를 열 때도 잠깐 보여 준다).
    // 손잡이를 끌면 문서 위치로 바로 이동하고, 끄는 동안 옆에 쪽 번호를 띄운다.

    private var barAlpha = 0f
    private var barAnimator: ValueAnimator? = null
    private var barDragging = false
    private var barGrabOffset = 0f
    private val handleW = 32f * density
    private val handleH = 56f * density
    /** 손잡이가 오가는 범위의 위아래 여백과 오른쪽 여백 */
    private val barMargin = 10f * density
    private val barRight = 6f * density
    private val colorSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceContainerHighest, Color.WHITE)
    private val colorOnSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
    private val colorAccent = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0xFF1E5AA8.toInt())
    private val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handleEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val arrowPath = Path()
    private val bubblePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = 15f * density
        color = Color.WHITE
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val barRect = RectF()
    private val hideBarRunnable = Runnable { if (!barDragging) fadeBar(0f) }

    private fun barVisible() = barAlpha > 0.05f

    private fun noteScrolled(dy: Float) {
        if (dy == 0f || contentH() <= height * 1.05f) return
        fadeBar(1f)
        removeCallbacks(hideBarRunnable)
        postDelayed(hideBarRunnable, 2000)
    }

    /** 문서를 열 때 손잡이가 있다는 걸 잠깐 보여 준다 */
    private fun flashBar() {
        if (contentH() <= height * 1.05f) return
        fadeBar(1f)
        removeCallbacks(hideBarRunnable)
        postDelayed(hideBarRunnable, 1500)
    }

    private fun fadeBar(target: Float) {
        if (barAlpha == target && barAnimator?.isRunning != true) return
        barAnimator?.cancel()
        barAnimator = ValueAnimator.ofFloat(barAlpha, target).apply {
            duration = if (target > barAlpha) 150 else 400
            addUpdateListener {
                barAlpha = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    /**
     * (손잡이가 오가는 범위의 위, 길이, 손잡이 위, 손잡이 높이). 문서가 화면보다 짧으면 null.
     * 위·아래에 겹쳐 뜬 줄(topInset, bottomInset)에 가리지 않도록 그 사이에서만
     */
    private fun barGeometry(): FloatArray? {
        val ch = contentH()
        if (ch <= height * 1.05f) return null
        val trackTop = barMargin + topInset
        val trackLen = height - bottomInset - topInset - barMargin * 2
        if (trackLen < handleH * 1.5f) return null
        val frac = ((offY + topInset) / (ch - height)).coerceIn(0f, 1f)
        return floatArrayOf(trackTop, trackLen, trackTop + frac * (trackLen - handleH), handleH)
    }

    private fun Paint.withAlpha(c: Int, a: Float) = apply { color = c; alpha = (Color.alpha(c) * a).roundToInt() }

    private fun drawScrollBar(canvas: Canvas) {
        if (!barVisible()) return
        val g = barGeometry() ?: return
        val a = barAlpha
        val right = width - barRight
        val left = right - handleW
        val cx = (left + right) / 2
        // 손잡이가 오가는 길: 가는 선
        val tw = 1.5f * density
        barTrackPaint.withAlpha(Color.argb(50, 0, 0, 0), a)
        canvas.drawRoundRect(cx - tw, g[0], cx + tw, g[0] + g[1], tw, tw, barTrackPaint)
        // 손잡이: 알약 모양, 끄는 동안은 강조색
        barRect.set(left, g[2], right, g[2] + g[3])
        val r = handleW / 2
        if (barDragging) {
            handlePaint.withAlpha(colorAccent, a)
            canvas.drawRoundRect(barRect, r, r, handlePaint)
        } else {
            handlePaint.withAlpha(colorSurface, a)
            handlePaint.setShadowLayer(4f * density, 0f, 1f * density, Color.argb((70 * a).toInt(), 0, 0, 0))
            canvas.drawRoundRect(barRect, r, r, handlePaint)
            handlePaint.clearShadowLayer()
            handleEdgePaint.withAlpha(Color.argb(40, 0, 0, 0), a)
            canvas.drawRoundRect(barRect, r, r, handleEdgePaint)
        }
        // 위아래 화살표
        val cy = barRect.centerY()
        val s = 5f * density
        val gap = 4.5f * density
        arrowPath.reset()
        arrowPath.moveTo(cx - s, cy - gap); arrowPath.lineTo(cx, cy - gap - s); arrowPath.lineTo(cx + s, cy - gap)
        arrowPath.moveTo(cx - s, cy + gap); arrowPath.lineTo(cx, cy + gap + s); arrowPath.lineTo(cx + s, cy + gap)
        arrowPaint.withAlpha(if (barDragging) Color.WHITE else colorOnSurface, a)
        canvas.drawPath(arrowPath, arrowPaint)
        // 끄는 동안: 손잡이 왼쪽에 쪽 번호 말풍선
        if (barDragging && sizes.isNotEmpty()) {
            val text = "${currentPage() + 1} / ${sizes.size}"
            val padH = 12f * density
            val bw = bubbleText.measureText(text) + padH * 2
            val bh = 34f * density
            val bRight = left - 10f * density
            val top = (cy - bh / 2).coerceIn(0f, max(0f, height - bh))
            bubblePaint.withAlpha(Color.argb(225, 0x30, 0x30, 0x30), a)
            canvas.drawRoundRect(bRight - bw, top, bRight, top + bh, bh / 2, bh / 2, bubblePaint)
            val fm = bubbleText.fontMetrics
            canvas.drawText(text, bRight - bw + padH, top + bh / 2 - (fm.ascent + fm.descent) / 2, bubbleText)
        }
    }

    /** 손잡이를 잡고 끄는 입력을 처리했으면 true. 손잡이 자체만 잡는다 (가장자리 필기를 가로채지 않게) */
    private fun handleBarTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val g = barGeometry() ?: return false
                if (!barVisible()) return false
                val slop = 10f * density
                val left = width - barRight - handleW
                val onHandle = ev.x >= left - slop && ev.y >= g[2] - slop && ev.y <= g[2] + g[3] + slop
                if (!onHandle) return false
                barGrabOffset = ev.y - g[2]
                barDragging = true
                scroller.forceFinished(true)
                zoomAnimator?.cancel()
                removeCallbacks(hideBarRunnable)
                fadeBar(1f)
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> if (barDragging) {
                barGeometry()?.let { moveThumbTo(ev.y, it) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (barDragging) {
                barDragging = false
                postDelayed(hideBarRunnable, 2000)
                scheduleDetail()
                invalidate()
                return true
            }
        }
        return barDragging
    }

    private fun moveThumbTo(y: Float, g: FloatArray) {
        val ch = contentH()
        val movable = g[1] - g[3]
        if (movable <= 0f) return
        val frac = ((y - barGrabOffset - g[0]) / movable).coerceIn(0f, 1f)
        offY = frac * (ch - height) - topInset
        clamp()
        invalidate()
    }

    // ================= 터치 / 펜 입력 =================

    private fun isStylus(ev: MotionEvent, idx: Int): Boolean {
        val t = ev.getToolType(idx)
        return t == MotionEvent.TOOL_TYPE_STYLUS || t == MotionEvent.TOOL_TYPE_ERASER
    }

    private fun isEraserInput(ev: MotionEvent, idx: Int, samsungButton: Boolean): Boolean {
        if (samsungButton) return true
        if (ev.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER) return true
        val mask = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_SECONDARY
        return ev.buttonState and mask != 0
    }

    private fun pressure(ev: MotionEvent, idx: Int, hist: Int = -1): Float {
        if (!isStylus(ev, idx)) return 0.6f
        val p = if (hist >= 0) ev.getHistoricalPressure(idx, hist) else ev.getPressure(idx)
        return p.coerceIn(0f, 1f)
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (doc == null) return false
        if (penPointerId == -1 && handleBarTouch(ev)) return true
        var action = ev.actionMasked
        // 구형 삼성 S펜: 버튼을 누른 채 그리면 별도 액션 코드(211~213)로 들어온다
        var samsungButton = false
        when (action) {
            SPEN_DOWN -> { action = MotionEvent.ACTION_DOWN; samsungButton = true }
            SPEN_UP -> { action = MotionEvent.ACTION_UP; samsungButton = true }
            SPEN_MOVE -> { action = MotionEvent.ACTION_MOVE; samsungButton = true }
        }

        // 펜 시작 (읽기 모드에서는 펜도 손가락처럼 넘기기·확대만)
        if (!readOnly && penPointerId == -1 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)) {
            val idx = if (samsungButton) 0 else ev.actionIndex
            val stylus = isStylus(ev, idx) || samsungButton
            // 선택한 부분은 손가락 필기를 꺼 두어도 손가락으로 끌어 옮길 수 있다
            val fingerPen = !stylus && action == MotionEvent.ACTION_DOWN &&
                ev.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER &&
                (fingerDrawing || onSelection(ev.getX(idx), ev.getY(idx)))
            if (stylus || fingerPen) {
                if (fingerActive) cancelFingerGesture(ev)
                fingersBlocked = stylus
                penPointerId = ev.getPointerId(idx)
                penIsFinger = fingerPen
                penErasing = tool == Tool.ERASER || (tool == Tool.TAPE && tapeErasing) ||
                    (stylus && isEraserInput(ev, idx, samsungButton))
                startPen(ev.getX(idx), ev.getY(idx), pressure(ev, idx), ev.eventTime)
                return true
            }
        }

        if (penPointerId != -1) {
            // 손가락 필기 중 두 번째 손가락이 닿으면 필기를 취소하고 이동/확대로 전환
            if (penIsFinger && action == MotionEvent.ACTION_POINTER_DOWN) {
                endPen(commit = false)
                fingerActive = true
                scaleDetector.onTouchEvent(ev)
                return true
            }
            when (action) {
                MotionEvent.ACTION_MOVE -> {
                    val idx = ev.findPointerIndex(penPointerId)
                    if (idx >= 0) {
                        for (h in 0 until ev.historySize) {
                            movePen(ev.getHistoricalX(idx, h), ev.getHistoricalY(idx, h), pressure(ev, idx, h), ev.getHistoricalEventTime(h))
                        }
                        movePen(ev.getX(idx), ev.getY(idx), pressure(ev, idx), ev.eventTime)
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (ev.getPointerId(ev.actionIndex) == penPointerId) endPen(commit = true)
                }
                MotionEvent.ACTION_UP -> {
                    // 뗀 자리까지 획에 넣는다 (보정 펜에서 끝점이 잘리지 않게)
                    val idx = ev.findPointerIndex(penPointerId)
                    if (idx >= 0) movePen(ev.getX(idx), ev.getY(idx), pressure(ev, idx), ev.eventTime)
                    endPen(commit = true)
                    fingersBlocked = false
                }
                MotionEvent.ACTION_CANCEL -> {
                    endPen(commit = true)
                    fingersBlocked = false
                }
            }
            return true
        }

        // 펜이 닿아 있었던 동안은 손바닥 등 손가락 입력 무시
        if (fingersBlocked) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) fingersBlocked = false
            return true
        }

        fingerActive = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
        scaleDetector.onTouchEvent(ev)
        if (!penIsFinger || ev.pointerCount == 1 || !scaling) gestureDetector.onTouchEvent(ev)
        if (!fingerActive) {
            penIsFinger = false
            scheduleDetail()
        }
        return true
    }

    private fun cancelFingerGesture(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        scaleDetector.onTouchEvent(cancel)
        gestureDetector.onTouchEvent(cancel)
        cancel.recycle()
        fingerActive = false
        scaling = false
    }

    private fun startPen(sx: Float, sy: Float, p: Float, t: Long) {
        listener?.onPenDown()
        scroller.forceFinished(true)
        zoomAnimator?.cancel()
        shapeGen++
        shapePending = false
        lastSx = sx
        lastSy = sy
        erased.clear()
        pieces.clear()
        curStroke = null
        curPage = -1
        tapCandidate = true
        tapDownTime = System.currentTimeMillis()
        tapDownSx = sx
        tapDownSy = sy
        if (tool == Tool.LASSO && !penErasing) {
            startLasso(sx, sy)
            return
        }
        if (tool == Tool.TEXT && !penErasing) {
            textPressing = true
            textTap = hitPage(sx, sy)
            textTapSx = sx
            textTapSy = sy
            return
        }
        if (penErasing) clearSelection()
        val hit = hitPage(sx, sy) ?: run { invalidate(); return }
        if (penErasing) {
            eraseAt(hit.first, hit.second, hit.third)
        } else {
            curPage = hit.first
            val st = when (tool) {
                Tool.HIGHLIGHTER -> Stroke(Tool.HIGHLIGHTER, hlColor, hlWidth)
                Tool.LASER -> Stroke(Tool.LASER, laserColor, laserWidthDp).also { holdLaser() }
                Tool.TAPE -> Stroke(Tool.TAPE, tapeColor, if (tapeRect) 0f else tapeWidth).also {
                    it.tape = TapeStyle(tapePattern, tapeRect)
                }
                // 보정 펜은 그린 획을 맞춘 도형으로 바꾸므로 그리는 동안은 사인펜
                Tool.PEN -> Stroke(Tool.PEN, penColor, penWidth, pen = penStyle)
                else -> Stroke(Tool.PEN, penColor, penWidth)
            }
            lastPressure = p
            if (tool == Tool.PEN) {
                penInput.begin(penStyle, penWidth, penSmoothing, hit.second, hit.third, p, t)
                st.add(penInput.x, penInput.y, penInput.p)
            } else st.add(hit.second, hit.third, p)
            curStroke = st
        }
        invalidate()
    }

    /** 화면 좌표 점이 회전 중심에서 이루는 각 (도) */
    private fun angleAt(sx: Float, sy: Float): Float {
        val px = toPageX(selPage, sx)
        val py = toPageY(selPage, sy)
        return Math.toDegrees(kotlin.math.atan2((py - rotCy).toDouble(), (px - rotCx).toDouble())).toFloat()
    }

    private fun movePen(sx: Float, sy: Float, p: Float, t: Long = 0L) {
        if (tapCandidate && hypot(sx - tapDownSx, sy - tapDownSy) > TAP_SLOP_DP * density) tapCandidate = false
        if (textPressing) {
            if (hypot(sx - textTapSx, sy - textTapSy) > TAP_SLOP_DP * density) textTap = null
            return
        }
        if (rotating) {
            var d = angleAt(sx, sy) - rotStart
            while (d > 180f) d -= 360f
            while (d <= -180f) d += 360f
            // 90° 배수(5° 안), 45° 배수(3° 안)에 딱 맞춘다
            val r90 = (d / 90f).roundToInt() * 90f
            val r45 = (d / 45f).roundToInt() * 45f
            d = when {
                abs(d - r90) <= 5f -> r90
                abs(d - r45) <= 3f -> r45
                else -> d
            }
            rotDeg = d
            invalidate()
            return
        }
        if (moving) {
            moveDx = toPageX(selPage, sx) - moveStartX
            moveDy = toPageY(selPage, sy) - moveStartY
            invalidate()
            return
        }
        if (resizing) {
            val d = hypot(toPageX(selPage, sx) - anchorX, toPageY(selPage, sy) - anchorY)
            scaleK = (d / startDist).coerceIn(0.1f, 20f)
            invalidate()
            return
        }
        if (lassoing) {
            val px = toPageX(lassoPage, sx)
            val py = toPageY(lassoPage, sy)
            if (lassoRect) {
                lasso[2] = px
                lasso[3] = py
            } else {
                val n = lassoCount - 1
                if (hypot(px - lasso[n * 2], py - lasso[n * 2 + 1]) * scale >= 2f * density) addLassoPoint(px, py)
            }
            invalidate()
            return
        }
        if (penErasing) {
            // 지우개는 움직인 경로 사이도 촘촘히 검사
            val dist = hypot(sx - lastSx, sy - lastSy)
            val step = eraserRadiusDp * density / 2f
            val n = max(1, (dist / step).toInt())
            for (k in 1..n) {
                val x = lastSx + (sx - lastSx) * k / n
                val y = lastSy + (sy - lastSy) * k / n
                hitPage(x, y)?.let { eraseAt(it.first, it.second, it.third) }
            }
            lastSx = sx
            lastSy = sy
            invalidate()
            return
        }
        val st = curStroke ?: return
        val px = toPageX(curPage, sx)
        val py = toPageY(curPage, sy)
        if (st.tool == Tool.HIGHLIGHTER && hlStraight) {
            // 직선 형광펜: 첫 점에서 지금 점까지. 가로·세로 근처(5° 안)면 딱 맞춘다
            var ex = px
            var ey = py
            val dx = ex - st.x(0)
            val dy = ey - st.y(0)
            if (dx != 0f || dy != 0f) {
                val deg = Math.toDegrees(kotlin.math.atan2(abs(dy).toDouble(), abs(dx).toDouble()))
                if (deg <= 5.0) ey = st.y(0)
                else if (deg >= 85.0) ex = st.x(0)
            }
            st.keepFirst()
            st.add(ex, ey, st.p(0))
            lastSx = sx
            lastSy = sy
            invalidate()
            return
        }
        if (st.tape?.rect == true) {
            // 네모 테이프: 첫 점과 지금 점이 마주 보는 모서리
            val x0 = st.x(0)
            val y0 = st.y(0)
            st.keepFirst()
            st.add(px, y0, 1f)
            st.add(px, py, 1f)
            st.add(x0, py, 1f)
            lastSx = sx
            lastSy = sy
            invalidate()
            return
        }
        val last = st.count - 1
        val minDist = 0.8f / scale
        if (tool == Tool.PEN && st.tool == Tool.PEN) {
            // 펜: 손떨림 보정·펜 종류에 맞춰 다듬은 점
            if (!penInput.move(px, py, p, t, scale / density, minDist, st.x(last), st.y(last))) return
            st.add(penInput.x, penInput.y, penInput.p)
            lastSx = sx
            lastSy = sy
            invalidate()
            return
        }
        if (hypot(px - st.x(last), py - st.y(last)) < minDist) return
        lastPressure = lastPressure * 0.5f + p * 0.5f
        st.add(px, py, lastPressure)
        lastSx = sx
        lastSy = sy
        if (tool == Tool.SHAPE) requestShapePreview()
        invalidate()
    }

    /** 지금까지 그린 획으로 미리 보기를 맞춰 달라고 한다 (이미 맞추는 중이면 끝난 뒤에 한 번 더) */
    private fun requestShapePreview() {
        if (shapeFitting) { shapePending = true; return }
        val st = curStroke ?: return
        val copy = Stroke(st.tool, st.color, st.width).apply { for (i in 0 until st.count) add(st.x(i), st.y(i), st.p(i)) }
        val gen = shapeGen
        val kind = shapeKind
        val guide = shapeGuide
        shapeFitting = true
        shapePending = false
        shapeWorker.execute {
            val r = try { fitShape(copy, kind, guide, quick = true) } catch (e: Exception) { null }
            post {
                shapeFitting = false
                if (gen == shapeGen) {
                    shapePreview = r
                    invalidate()
                }
                if (shapePending) requestShapePreview()
            }
        }
    }

    /**
     * 보정 펜 획을 고른 도형으로 맞춘 새 펜 획들 (굵기는 그린 획의 평균 필압).
     * 쌍곡선은 두 가지, 보조선(점근선·축)을 켜 두었으면 그 획이 더 붙는다. quick은 그리는 동안의 미리 보기 (덜 다듬음)
     */
    private fun fitShape(
        raw: Stroke, kind: ShapeKind = shapeKind, guide: GuideStyle = shapeGuide, quick: Boolean = false,
    ): List<Stroke>? {
        val fitted = ShapeFit.fit(kind, raw, quick) ?: return null
        var pSum = 0f
        for (i in 0 until raw.count) pSum += raw.p(i)
        val pAvg = pSum / raw.count
        // 맞춘 도형은 지금 펜 종류로 (점선 보조선은 사인펜)
        fun toStroke(pts: FloatArray, dashed: Boolean) =
            Stroke(Tool.PEN, raw.color, raw.width, dashed, if (dashed) PenStyle.FELT else penStyle).apply {
            for (i in 0 until pts.size / 2) add(pts[i * 2], pts[i * 2 + 1], pAvg)
        }
        val out = fitted.curves.map { toStroke(it, false) }.toMutableList()
        if (guide != GuideStyle.NONE) fitted.guides.mapTo(out) { toStroke(it, guide == GuideStyle.DASHED) }
        return out
    }

    private fun eraseAt(page: Int, px: Float, py: Float) {
        val inkDoc = ink ?: return
        val r = eraserRadiusDp * density / scale
        val list = inkDoc.pages[page]
        var removed = false
        // 테이프 도구의 지우개는 테이프만 (보이게 한 테이프도) 지운다
        val tapesOnly = tool == Tool.TAPE && tapeErasing
        val mode = if (tapesOnly) tapeEraseMode else eraserMode
        // 가린 테이프 아래(먼저 그린 획)는 보이지 않으므로 지우지 않는다
        var covered = false
        for (k in list.indices.reversed()) {
            val st = list[k]
            if (st.isBox) continue  // 그림·글은 선택해서 삭제
            val isTape = st.tape != null
            if (tapesOnly) {
                if (!isTape) continue
            } else if (isTape && st.revealed) continue  // 보이게 한 테이프는 투명한 셈이라 지우개가 그대로 지나간다
            if (covered) break
            val coversHere = isTape && !st.revealed && st.tapeContains(px, py)
            if (!tapesOnly && eraseHlOnly && st.tool != Tool.HIGHLIGHTER) {
                if (coversHere) covered = true
                continue
            }
            if (!st.hitTest(px, py, r)) continue
            val rest = when {
                mode == EraserMode.STROKE -> emptyList()
                // 테이프는 지우개가 지나간 동그라미만큼 구멍을 뚫는다
                isTape -> st.withHole(px, py, r)?.let { if (it.tapeGone()) emptyList() else listOf(it) } ?: continue
                else -> st.cut(px, py, r) ?: continue
            }
            if (coversHere) covered = true
            list.removeAt(k)
            list.addAll(k, rest)
            // 이번에 잘라 넣은 조각을 다시 자르면 조각 목록에서만 뺀다 (원래 획이 아니므로)
            if (!pieces.removeAll { it.second === st }) erased.add(page to st)
            rest.forEach { pieces.add(page to it) }
            removed = true
        }
        if (removed) invalidate()
    }

    private fun endPen(commit: Boolean) {
        val inkDoc = ink
        // 아직 맞추는 중인 미리 보기는 버린다
        shapeGen++
        shapePending = false
        if (textPressing) {
            textPressing = false
            val hit = textTap
            textTap = null
            penPointerId = -1
            penErasing = false
            if (commit && hit != null) tapText(hit.first, hit.second, hit.third)
            return
        }
        if (rotating) {
            if (commit && inkDoc != null && rotDeg != 0f) {
                val deg = rotDeg
                inkDoc.edit(selection) { selection.forEach { it.rotate(deg, rotCx, rotCy) } }
                select(selPage, selection)
            }
            rotating = false
            rotDeg = 0f
        } else if (moving) {
            if (commit && inkDoc != null) {
                inkDoc.move(selection, moveDx, moveDy)
                selBounds.offset(moveDx, moveDy)
            }
            moving = false
            moveDx = 0f
            moveDy = 0f
        } else if (resizing) {
            if (commit && inkDoc != null && scaleK != 1f) {
                val k = scaleK
                inkDoc.edit(selection) { selection.forEach { it.scale(k, anchorX, anchorY) } }
                select(selPage, selection)
            }
            resizing = false
            scaleK = 1f
        } else if (lassoing) {
            lassoing = false
            if (commit) finishLasso()
        } else if (commit && tapCandidate && !penErasing && curStroke != null && (tool == Tool.TAPE || penIsFinger) &&
            System.currentTimeMillis() - tapDownTime < TAP_MS && toggleTapeAt(tapDownSx, tapDownSy)
        ) {
            // 테이프를 톡 누름 (테이프 도구, 또는 손가락 필기): 획을 남기지 않고 테이프만 보였다 가려졌다
            shapePreview = null
        } else if (inkDoc != null) {
            if (penErasing) {
                inkDoc.erased(ArrayList(erased), ArrayList(pieces))
            } else {
                val st = curStroke
                if (st != null && commit) {
                    if (tool == Tool.SHAPE) {
                        // 보정 펜: 흐린 획 대신 맞춘 도형을 남긴다
                        val fitted = fitShape(st)
                        if (fitted != null) inkDoc.addAll(curPage, fitted)
                        else if (st.count > 2) listener?.onShapeFailed(shapeKind)
                    } else if (st.tool == Tool.TAPE) {
                        if (finishTape(curPage, st)) inkDoc.add(curPage, st)
                    } else if (st.tool == Tool.LASER) {
                        // 레이저: 필기에 넣지 않고 잠깐 보였다가 사라진다
                        laserStrokes.add(curPage to st)
                        removeCallbacks(laserFadeRunnable)
                        postDelayed(laserFadeRunnable, laserFadeMs)
                    } else {
                        if (st.tool == Tool.PEN) penInput.finish(st)
                        inkDoc.add(curPage, st)
                    }
                }
            }
            shapePreview = null
        }
        erased.clear()
        pieces.clear()
        curStroke = null
        curPage = -1
        penPointerId = -1
        penErasing = false
        tapCandidate = false
        invalidate()
    }

    // ================= 올가미 선택 =================

    /** 화면 좌표가 선택 상자나 손잡이 위인지 */
    private fun onSelection(sx: Float, sy: Float) =
        selection.isNotEmpty() && (selectionScreenRect(RectF()).contains(sx, sy) || handleAt(sx, sy) >= 0 || rotateHandleHit(sx, sy))

    /** 누른 곳에 있는 손잡이: 0 왼쪽 위, 1 오른쪽 위, 2 왼쪽 아래, 3 오른쪽 아래, 없으면 -1 */
    private fun handleAt(sx: Float, sy: Float): Int {
        if (selection.isEmpty()) return -1
        val r = selectionScreenRect(RectF())
        val reach = HANDLE_TOUCH_DP * density
        val corners = arrayOf(r.left to r.top, r.right to r.top, r.left to r.bottom, r.right to r.bottom)
        return corners.indices.firstOrNull { hypot(sx - corners[it].first, sy - corners[it].second) <= reach } ?: -1
    }

    private fun startLasso(sx: Float, sy: Float) {
        val handle = handleAt(sx, sy)
        if (rotateHandleHit(sx, sy)) {
            // 회전 손잡이: 선택 영역 가운데를 중심으로 돌리기
            rotCx = selBounds.centerX()
            rotCy = selBounds.centerY()
            rotStart = angleAt(sx, sy)
            rotDeg = 0f
            rotating = true
        } else if (handle >= 0) {
            // 모서리 손잡이: 반대쪽 모서리를 기준으로 크기 조절
            val b = selBounds
            val left = handle == 0 || handle == 2
            val top = handle == 0 || handle == 1
            anchorX = if (left) b.right else b.left
            anchorY = if (top) b.bottom else b.top
            startDist = max(hypot((if (left) b.left else b.right) - anchorX, (if (top) b.top else b.bottom) - anchorY), 1f)
            scaleK = 1f
            resizing = true
        } else if (onSelection(sx, sy)) {
            // 선택 상자 안을 누르면 옮기기
            moving = true
            moveStartX = toPageX(selPage, sx)
            moveStartY = toPageY(selPage, sy)
            moveDx = 0f
            moveDy = 0f
        } else {
            clearSelection()
            val hit = hitPage(sx, sy)
            if (hit != null) {
                lassoing = true
                lassoPage = hit.first
                lassoCount = 0
                addLassoPoint(hit.second, hit.third)
                // 네모 선택은 [시작점, 지금 점] 두 개만 쓴다
                if (lassoRect) addLassoPoint(hit.second, hit.third)
            }
        }
        invalidate()
    }

    private fun addLassoPoint(x: Float, y: Float) {
        if (lassoCount * 2 + 2 > lasso.size) lasso = lasso.copyOf(lasso.size * 2)
        lasso[lassoCount * 2] = x
        lasso[lassoCount * 2 + 1] = y
        lassoCount++
    }

    /** 올가미 안에 점이 절반 이상 들어간 획을 고른다 */
    private fun finishLasso() {
        val inkDoc = ink ?: return
        // 그림 위를 톡 누르면(올가미가 아주 작으면) 그 그림을 고른다
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (k in 0 until lassoCount) {
            minX = min(minX, lasso[k * 2]); maxX = max(maxX, lasso[k * 2])
            minY = min(minY, lasso[k * 2 + 1]); maxY = max(maxY, lasso[k * 2 + 1])
        }
        if (max(maxX - minX, maxY - minY) * scale < 10 * density) {
            (boxAt(lassoPage, lasso[0], lasso[1]) ?: tapeAt(lassoPage, lasso[0], lasso[1]))?.let { select(lassoPage, listOf(it)) }
            return
        }
        if (lassoRect) {
            // 두 점을 네 모서리로 바꿔 자유 선택과 같은 규칙으로 고른다
            val x0 = min(lasso[0], lasso[2]); val y0 = min(lasso[1], lasso[3])
            val x1 = max(lasso[0], lasso[2]); val y1 = max(lasso[1], lasso[3])
            if ((x1 - x0) * scale < 4 * density || (y1 - y0) * scale < 4 * density) return
            lassoCount = 0
            addLassoPoint(x0, y0); addLassoPoint(x1, y0); addLassoPoint(x1, y1); addLassoPoint(x0, y1)
        }
        if (lassoCount < 3) return
        val picked = inkDoc.pages[lassoPage].filter { st ->
            var inside = 0
            for (k in 0 until st.count) if (inLasso(st.x(k), st.y(k))) inside++
            inside * 2 >= st.count && inside > 0
        }
        if (picked.isEmpty()) return
        select(lassoPage, picked)
    }

    /** 획들을 선택하고 선택 상자를 계산한다 */
    private fun select(page: Int, picked: List<Stroke>) {
        selPage = page
        selection = picked
        selSet = picked.toHashSet()
        // RectF.union은 넓이 0인 사각형을 무시하므로(굵기 0인 그림의 모서리) 직접 최소·최대를 잰다
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (st in picked) {
            val half = st.halfWidth
            for (k in 0 until st.count) {
                l = min(l, st.x(k) - half); r = max(r, st.x(k) + half)
                t = min(t, st.y(k) - half); b = max(b, st.y(k) + half)
            }
        }
        if (l <= r) selBounds.set(l, t, r, b) else selBounds.setEmpty()
    }

    /** 쪽 좌표 (x, y)를 덮고 있는 맨 위 그림·글 ([textOnly]면 글만) */
    private fun boxAt(page: Int, x: Float, y: Float, textOnly: Boolean = false): Stroke? {
        val list = ink?.pages?.getOrNull(page) ?: return null
        for (k in list.indices.reversed()) {
            val st = list[k]
            if (!st.isBox || st.count < 4 || (textOnly && st.text == null)) continue
            var inside = false
            var j = 3
            for (i in 0 until 4) {
                val xi = st.x(i); val yi = st.y(i); val xj = st.x(j); val yj = st.y(j)
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
            }
            if (inside) return st
        }
        return null
    }

    /**
     * 그림을 보고 있는 쪽 가운데에 넣고 선택한다 (실행 취소 가능).
     * 선택을 바로 쓰도록 부르는 쪽에서 도구를 선택 도구로 바꿔 둘 것
     */
    fun insertImage(img: InkImage): Boolean {
        val inkDoc = ink ?: return false
        if (sizes.isEmpty()) return false
        val page = currentPage().coerceIn(0, sizes.lastIndex)
        val pw = sizes[page].width
        val ph = sizes[page].height
        // 쪽과 지금 보이는 화면 둘 다의 60% 안에 들어가게 (손잡이·선택 막대 자리가 남도록)
        val aspect = img.bitmap.height.toFloat() / img.bitmap.width
        val maxW = min(pw, width / scale) * 0.6f
        val maxH = min(ph, (height - bottomInset - topInset) / scale) * 0.6f
        var w = maxW
        var h = w * aspect
        if (h > maxH) { h = maxH; w = h / aspect }
        val cx = ((offX + width / 2f) / scale - lefts[page]).coerceIn(w / 2, pw - w / 2)
        val cy = ((offY + height / 2f) / scale - tops[page]).coerceIn(h / 2, ph - h / 2)
        val st = Stroke(Tool.PEN, Color.BLACK, 0f).apply {
            image = img
            add(cx - w / 2, cy - h / 2, 1f)
            add(cx + w / 2, cy - h / 2, 1f)
            add(cx + w / 2, cy + h / 2, 1f)
            add(cx - w / 2, cy + h / 2, 1f)
        }
        clearSelection()
        inkDoc.addAll(page, listOf(st))
        select(page, listOf(st))
        invalidate()
        return true
    }

    // ================= 테이프 =================

    /** 쪽 좌표 (x, y)를 덮고 있는 맨 위 테이프 */
    private fun tapeAt(page: Int, x: Float, y: Float): Stroke? =
        ink?.pages?.getOrNull(page)?.lastOrNull { it.tapeContains(x, y) }

    /** 화면 좌표의 테이프를 보이게 하거나 다시 가린다 (실행 취소 기록에 남기지 않음). 테이프가 없으면 false */
    private fun toggleTapeAt(sx: Float, sy: Float): Boolean {
        val hit = hitPage(sx, sy) ?: return false
        val st = tapeAt(hit.first, hit.second, hit.third) ?: return false
        st.revealed = !st.revealed
        invalidate()
        return true
    }

    /** 다 그린 테이프를 다듬는다 (곧게 펴기, 글자 크기에 맞추기). 너무 작아 남길 것이 없으면 false */
    private fun finishTape(page: Int, st: Stroke): Boolean {
        if (st.tape?.rect == true) {
            if (st.count < 4) return false
            val w = abs(st.x(2) - st.x(0))
            val h = abs(st.y(2) - st.y(0))
            return min(w, h) * scale >= 6 * density
        }
        if (st.count < 2 || st.length() * scale < 8 * density) return false
        if (tapeStraight) straightenTape(st)
        if (tapeFitText) fitTapeToText(page, st)
        return true
    }

    /**
     * 거의 곧게 그은 펜 테이프를 곧은 선 하나로 편다. 점들에 가장 잘 맞는 직선(주성분)에 첫 점과 끝 점을 내리고,
     * 가로·세로에 가까우면(5° 안) 딱 맞춘다. 많이 휘었으면 그대로 둔다
     */
    private fun straightenTape(st: Stroke) {
        val n = st.count
        var mx = 0f; var my = 0f
        for (i in 0 until n) { mx += st.x(i); my += st.y(i) }
        mx /= n; my /= n
        var sxx = 0f; var sxy = 0f; var syy = 0f
        for (i in 0 until n) {
            val dx = st.x(i) - mx; val dy = st.y(i) - my
            sxx += dx * dx; sxy += dx * dy; syy += dy * dy
        }
        val th = 0.5 * kotlin.math.atan2(2.0 * sxy, (sxx - syy).toDouble())
        val ux = kotlin.math.cos(th).toFloat()
        val uy = kotlin.math.sin(th).toFloat()
        var tMin = Float.MAX_VALUE; var tMax = -Float.MAX_VALUE; var dev = 0f
        for (i in 0 until n) {
            val dx = st.x(i) - mx; val dy = st.y(i) - my
            val t = dx * ux + dy * uy
            tMin = min(tMin, t); tMax = max(tMax, t)
            dev = max(dev, abs(dx * uy - dy * ux))
        }
        val len = tMax - tMin
        if (len <= 0f || dev > max(st.width * 0.6f, len * 0.12f)) return
        // 그은 방향(첫 점 → 끝 점)을 지킨다
        val forward = (st.x(n - 1) - st.x(0)) * ux + (st.y(n - 1) - st.y(0)) * uy >= 0f
        val (ta, tb) = if (forward) tMin to tMax else tMax to tMin
        var x0 = mx + ux * ta; var y0 = my + uy * ta
        var x1 = mx + ux * tb; var y1 = my + uy * tb
        val deg = Math.toDegrees(kotlin.math.atan2(abs(y1 - y0).toDouble(), abs(x1 - x0).toDouble()))
        if (deg <= 5.0) { val y = (y0 + y1) / 2; y0 = y; y1 = y }
        else if (deg >= 85.0) { val x = (x0 + x1) / 2; x0 = x; x1 = x }
        val p = st.p(0)
        st.clearPoints()
        st.add(x0, y0, p)
        st.add(x1, y1, p)
    }

    /**
     * 펜 테이프를 아래 글자 줄에 맞춘다: 테이프를 따라 가며 수직 방향으로 쪽 그림(PDF + 필기)의 어두운 점을 세어,
     * 테이프 가운데에서 가장 가까운 글자 줄의 위·아래 끝을 찾고 그 높이(+ 조금 여유)로 굵기를, 그 가운데로 자리를 옮긴다.
     * 글자가 없으면(아래가 비었으면) 그대로 둔다
     */
    private fun fitTapeToText(page: Int, st: Stroke) {
        val inkDoc = ink ?: return
        val base = baseCache.get(page) ?: return
        val pw = sizes[page].width
        val ph = sizes[page].height
        val k = base.width / pw
        // 찾는 범위: 테이프 가운데에서 위·아래로 FIT_RANGE pt
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until st.count) {
            l = min(l, st.x(i)); r = max(r, st.x(i)); t = min(t, st.y(i)); b = max(b, st.y(i))
        }
        l = max(0f, l - FIT_RANGE); t = max(0f, t - FIT_RANGE)
        r = min(pw, r + FIT_RANGE); b = min(ph, b + FIT_RANGE)
        val bw = ((r - l) * k).toInt()
        val bh = ((b - t) * k).toInt()
        if (bw <= 0 || bh <= 0 || bw.toLong() * bh > 8_000_000L) return
        val bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888)
        val px = IntArray(bw * bh)
        try {
            val c = Canvas(bmp)
            c.drawColor(Color.WHITE)
            c.scale(k, k)
            c.translate(-l, -t)
            c.drawBitmap(base, null, RectF(0f, 0f, pw, ph), bmpPaint)
            // 필기·그림·글도 (다른 테이프는 빼고)
            val paint = inkPaint()
            for (s in inkDoc.pages[page]) if (s.image != null) drawInkStroke(c, paint, s)
            for (s in inkDoc.pages[page]) if (s.image == null && s.tape == null) drawInkStroke(c, paint, s)
            bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
        } finally {
            bmp.recycle()
        }
        fun dark(x: Float, y: Float): Boolean {
            val ix = ((x - l) * k).toInt()
            val iy = ((y - t) * k).toInt()
            if (ix !in 0 until bw || iy !in 0 until bh) return false
            val c = px[iy * bw + ix]
            val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            return lum < 160
        }
        // 테이프를 따라 1pt마다, 수직 방향 (-FIT_RANGE ~ +FIT_RANGE)을 0.5pt 간격으로 본다
        val step = 0.5f
        val m = (FIT_RANGE * 2 / step).toInt() + 1
        val hits = IntArray(m)
        var samples = 0
        for (i in 1 until st.count) {
            val dx = st.x(i) - st.x(i - 1)
            val dy = st.y(i) - st.y(i - 1)
            val len = hypot(dx, dy)
            if (len < 0.01f) continue
            val nx = -dy / len
            val ny = dx / len
            var d = 0f
            while (d < len) {
                val sx = st.x(i - 1) + dx * d / len
                val sy = st.y(i - 1) + dy * d / len
                for (j in 0 until m) {
                    val off = -FIT_RANGE + j * step
                    if (dark(sx + nx * off, sy + ny * off)) hits[j]++
                }
                samples++
                d += 1f
            }
        }
        if (samples == 0) return
        val need = max(1, (samples * 0.03f).toInt())
        val on = BooleanArray(m) { hits[it] >= need }
        // 가운데에서 가장 가까운 글자 줄 (테이프 굵기의 절반, 적어도 6pt 안)
        val center = m / 2
        val reach = (max(st.width / 2, 6f) / step).toInt()
        var start = -1
        for (dd in 0..reach) {
            if (center - dd >= 0 && on[center - dd]) { start = center - dd; break }
            if (center + dd < m && on[center + dd]) { start = center + dd; break }
        }
        if (start < 0) return
        var lo = start
        var hi = start
        // 위·아래로 넓혀 간다. 글자 안의 작은 틈(받침 사이 등)은 건너뛰고, 줄 사이 빈칸에서 멈춘다
        fun gapLimit() = max(1.5f, (hi - lo + 1) * step * 0.25f) / step
        repeat(2) {
            var gap = 0
            var j = lo - 1
            while (j >= 0) {
                if (on[j]) { lo = j; gap = 0 } else if (++gap > gapLimit()) break
                j--
            }
            gap = 0
            j = hi + 1
            while (j < m) {
                if (on[j]) { hi = j; gap = 0 } else if (++gap > gapLimit()) break
                j++
            }
        }
        // 찾는 범위 끝까지 이어지면 글자 줄이 아니라 그림·표 같은 것이므로 그대로 둔다
        if (lo == 0 || hi == m - 1) return
        val d0 = -FIT_RANGE + lo * step
        val d1 = -FIT_RANGE + (hi + 1) * step
        val h = d1 - d0
        val pad = max(1f, h * 0.15f)
        st.resize(h + pad * 2)
        // 글자 줄 가운데로 옮긴다 (점마다 앞뒤 선분의 수직 방향 평균으로)
        val shift = (d0 + d1) / 2
        val n = st.count
        val ox = FloatArray(n)
        val oy = FloatArray(n)
        for (i in 0 until n) {
            var nx = 0f; var ny = 0f
            for (j in intArrayOf(i - 1, i)) {
                if (j < 0 || j + 1 >= n) continue
                val dx = st.x(j + 1) - st.x(j)
                val dy = st.y(j + 1) - st.y(j)
                val len = hypot(dx, dy)
                if (len < 0.01f) continue
                nx += -dy / len; ny += dx / len
            }
            val len = hypot(nx, ny)
            if (len > 0f) { ox[i] = nx / len * shift; oy[i] = ny / len * shift }
        }
        for (i in 0 until n) st.offsetPoint(i, ox[i], oy[i])
    }

    /** 보고 있는 쪽의 테이프 수 */
    fun pageTapeCount(): Int {
        val page = currentPage().takeIf { it >= 0 } ?: return 0
        return ink?.pages?.getOrNull(page)?.count { it.tape != null } ?: 0
    }

    /** 보고 있는 쪽의 테이프를 모두 보이게(reveal) 하거나 모두 가린다. 테이프 수 */
    fun revealPageTapes(reveal: Boolean): Int {
        val page = currentPage().takeIf { it >= 0 } ?: return 0
        val tapes = ink?.pages?.getOrNull(page)?.filter { it.tape != null } ?: return 0
        tapes.forEach { it.revealed = reveal }
        invalidate()
        return tapes.size
    }

    /** 보고 있는 쪽의 테이프를 모두 지운다 (실행 취소 가능). 지운 수 */
    fun clearPageTapes(): Int {
        val inkDoc = ink ?: return 0
        val page = currentPage().takeIf { it >= 0 } ?: return 0
        val targets = inkDoc.pages[page].filter { it.tape != null }
        if (targets.isEmpty()) return 0
        clearSelection()
        inkDoc.remove(page, targets)
        return targets.size
    }

    // ================= 글 =================

    /** 문서 위 글 상자로 고치는 중이라 그리지 않는 글 */
    var hiddenStroke: Stroke? = null
        set(v) {
            field = v
            invalidate()
        }

    /** 화면 배율 (쪽 좌표 1pt가 화면 몇 px인지) */
    val viewScale get() = scale

    fun pageSize(page: Int): SizeF? = sizes.getOrNull(page)

    /** 쪽 좌표 → 이 뷰의 화면 좌표 */
    fun pageToScreen(page: Int, x: Float, y: Float): PointF? {
        if (page !in sizes.indices) return null
        return PointF((lefts[page] + x) * scale - offX, (tops[page] + y) * scale - offY)
    }

    private fun tapText(page: Int, x: Float, y: Float) {
        listener?.onTextTap(page, x, y, boxAt(page, x, y, textOnly = true)?.takeIf { it !== hiddenStroke })
    }

    /** 쪽의 (left, top)을 왼쪽 위 모서리로 글을 넣는다. 실행 취소 가능 */
    fun addText(page: Int, left: Float, top: Float, text: InkText, color: Int) {
        val inkDoc = ink ?: return
        if (page !in sizes.indices) return
        val w = text.boxW.toFloat()
        val h = text.boxH.toFloat()
        val st = Stroke(Tool.PEN, color, 0f).apply {
            this.text = text
            add(left, top, 1f)
            add(left + w, top, 1f)
            add(left + w, top + h, 1f)
            add(left, top + h, 1f)
        }
        clearSelection()
        inkDoc.add(page, st)
        invalidate()
    }

    /**
     * 글을 고친다: 왼쪽 위 모서리와 지금 배율·기울기는 그대로 두고 새 글 크기에 맞춰 상자를 다시 잡는다.
     * [text]가 null이면 그 글을 지운다. 실행 취소 가능
     */
    fun replaceText(page: Int, old: Stroke, text: InkText?, color: Int) {
        val inkDoc = ink ?: return
        clearSelection()
        if (text == null) {
            inkDoc.remove(page, listOf(old))
            invalidate()
            return
        }
        val oldText = old.text ?: return
        val x0 = old.x(0); val y0 = old.y(0)
        // 가로 방향(0→1), 세로 방향(0→3) 단위 벡터와 배율
        val ax = old.x(1) - x0; val ay = old.y(1) - y0
        val bx = old.x(3) - x0; val by = old.y(3) - y0
        val aLen = max(hypot(ax, ay), 0.01f)
        val bLen = max(hypot(bx, by), 0.01f)
        val kx = aLen / oldText.boxW
        val ky = bLen / oldText.boxH
        val w = text.boxW * kx
        val h = text.boxH * ky
        val ux = ax / aLen * w; val uy = ay / aLen * w
        val vx = bx / bLen * h; val vy = by / bLen * h
        val st = Stroke(Tool.PEN, color, 0f).apply {
            this.text = text
            add(x0, y0, 1f)
            add(x0 + ux, y0 + uy, 1f)
            add(x0 + ux + vx, y0 + uy + vy, 1f)
            add(x0 + vx, y0 + vy, 1f)
        }
        inkDoc.replace(page, old, st)
        invalidate()
    }

    private fun inLasso(x: Float, y: Float): Boolean {
        var inside = false
        var j = lassoCount - 1
        for (i in 0 until lassoCount) {
            val xi = lasso[i * 2]; val yi = lasso[i * 2 + 1]
            val xj = lasso[j * 2]; val yj = lasso[j * 2 + 1]
            if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
            j = i
        }
        return inside
    }

    fun clearSelection() {
        if (selection.isEmpty() && !lassoing && !moving && !resizing && !rotating) return
        selection = emptyList()
        selSet = emptySet()
        selPage = -1
        lassoing = false
        moving = false
        resizing = false
        rotating = false
        rotDeg = 0f
        moveDx = 0f
        moveDy = 0f
        scaleK = 1f
        invalidate()
    }

    /** 보고 있는 쪽의 필기(hlOnly면 형광펜만)를 모두 지운다 (실행 취소 가능). 지운 획 수 */
    fun clearPage(hlOnly: Boolean): Int {
        val inkDoc = ink ?: return 0
        val page = currentPage().takeIf { it >= 0 } ?: return 0
        val targets = inkDoc.pages[page].filter { !it.isBox && (!hlOnly || it.tool == Tool.HIGHLIGHTER) }
        if (targets.isEmpty()) return 0
        clearSelection()
        inkDoc.remove(page, targets)
        return targets.size
    }

    /** 선택한 획 지우기 (실행 취소 가능) */
    fun deleteSelection() {
        val inkDoc = ink ?: return
        if (selection.isEmpty()) return
        inkDoc.remove(selPage, selection)
        clearSelection()
    }

    /** 선택한 획의 대표 색 (색 고르기 창의 처음 값). 그림만 골랐으면 null (색 버튼을 숨긴다) */
    val selectionColor: Int? get() = selection.firstOrNull { it.image == null }?.color

    /** 선택한 획의 색 바꾸기 (실행 취소 가능). 그림은 그대로 둔다 */
    fun recolorSelection(c: Int) {
        val inkDoc = ink ?: return
        val strokes = selection.filter { it.image == null }
        inkDoc.edit(strokes) { strokes.forEach { it.recolor(c) } }
        invalidate()
    }

    /** 선택한 획을 복사해 둔다 */
    fun copySelection() {
        if (selection.isEmpty()) return
        val b = selBounds
        clipboard = selection.map { it.copy().apply { translate(-b.left, -b.top) } }
        clipSize.set(0f, 0f, b.width(), b.height())
    }

    /** 복사해 둔 획을 지금 보이는 페이지 가운데에 붙이고 선택한다 (실행 취소 가능) */
    fun pasteClipboard() {
        val inkDoc = ink ?: return
        if (clipboard.isEmpty() || sizes.isEmpty()) return
        val centerDoc = (offY + height / 2f) / scale
        val page = currentPage()
        val cx = (offX + width / 2f) / scale - lefts[page]
        val cy = (centerDoc - tops[page]).coerceIn(0f, sizes[page].height)
        val w = clipSize.width(); val h = clipSize.height()
        val x0 = (cx - w / 2).coerceIn(0f, max(0f, sizes[page].width - w))
        val y0 = (cy - h / 2).coerceIn(0f, max(0f, sizes[page].height - h))
        val copies = clipboard.map { it.copy().apply { translate(x0, y0) } }
        clearSelection()
        inkDoc.addAll(page, copies)
        select(page, copies)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(detailRunnable)
        removeCallbacks(hideBarRunnable)
        removeCallbacks(laserFadeRunnable)
        laserFadeAnim?.cancel()
        barAnimator?.cancel()
        zoomAnimator?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "DocumentView"
        private const val MIN_ZOOM = 0.4f
        private const val MAX_ZOOM = 6f
        private const val SEL_COLOR = 0xFF1E6FD9.toInt()
        /** 크기 조절 손잡이 반지름(그리기)과 누르는 범위 */
        private const val HANDLE_DP = 7f
        private const val HANDLE_TOUCH_DP = 24f
        /** 글 도구: 이보다 많이 움직이면 톡 누르기가 아니다 (dp) */
        private const val TAP_SLOP_DP = 12f
        /** 테이프를 톡 누른 것으로 보는 시간 (ms) */
        private const val TAP_MS = 500L
        /** 테이프를 글자 크기에 맞출 때 가운데에서 위·아래로 찾는 범위 (pt) */
        private const val FIT_RANGE = 40f
        /** 회전 손잡이 반지름과 상자에서 떨어진 거리 */
        private const val ROT_HANDLE_DP = 14f
        private const val ROT_OFFSET_DP = 34f
        private const val SPEN_DOWN = 211
        private const val SPEN_UP = 212
        private const val SPEN_MOVE = 213
    }
}
