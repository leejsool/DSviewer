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
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
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
        /** 손가락으로 넘기거나 튕겨서 문서가 위아래로 움직임 (쪽 번호를 잠깐 보일 때) */
        fun onScrolled() {}
        /** 마지막 쪽 아래로 끝까지 끌어 올렸다가 놓음, 또는 마지막 쪽 아래 '빈 쪽 추가' 단추 → 맨 뒤에 빈 쪽 붙이기 */
        fun onPullAddPage() {}
        /** 칠하기: 누른 자리를 둘러싼 닫힌 영역을 찾지 못함 */
        fun onFillFailed() {}
        /** 읽기 모드에서 쪽의 (x, y)를 톡 누름 (테이프가 아닌 곳). 링크를 따라갈 때 */
        fun onReadTap(page: Int, x: Float, y: Float) {}
        /** 필기 모드에서 손가락으로 쪽의 (x, y)를 톡 누름 (테이프가 아닌 곳). 오답 배지·'원문 보기'를 따라갈 때 */
        fun onWrongTap(page: Int, x: Float, y: Float) {}
        /** 화면을 새로 누르기 시작함 (손가락·펜). 문서 위에서 치던 포스트잇 글을 넣을 때 */
        fun onTouchDown() {}
        /** 펼친 포스트잇 메모의 몸통을 누름 → 글 고치기 ([setNoteText]) */
        fun onNoteEdit(page: Int, note: Stroke) {}
        /** 포스트잇 메모 색을 고름 (다음에 넣는 메모도 이 색으로) */
        fun onNoteColorPicked(color: Int) {}
        /** 포스트잇 붙일 곳 고르기가 끝남 (붙였거나 취소). 안내를 닫을 때 */
        fun onNotePlacementEnded() {}
        /** 오답으로 담을 영역을 골랐다 (쪽 번호, 그 쪽 좌표의 영역). 고른 영역 표시는 [clearWrongRect]로 지운다 */
        fun onWrongPicked(page: Int, rect: RectF) {}
        /** 오답 영역 고르기가 끝남 (골랐거나 취소). 안내를 닫을 때 */
        fun onWrongPickEnded() {}
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
    /** 펜으로 (좌우든 위아래든) 마구 긁으면 긁은 자리를 영역 지우개로 지운다 */
    var scribbleErase = true
    /** 손가락 필기 중 손바닥처럼 넓게 닿아 문지르면 영역 지우개 */
    var palmErase = true
    /** 읽기 모드: 펜도 손가락처럼 넘기고 확대만 한다 (필기·지우기·선택 없음) */
    var readOnly = false
        set(v) {
            if (field == v) return
            if (v && penPointerId != -1) endPen(commit = true)
            field = v
            if (v) {
                clearSelection()
                cancelNotePlacement()
                cancelWrongPick()
                clearWrongRect()
            }
            // 마지막 쪽 아래 '빈 쪽 추가' 단추는 읽기 모드에서 숨긴다 (그만큼 스크롤 길이가 바뀐다)
            if (doc != null) {
                clamp()
                invalidate()
            }
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
    private val spread get() = pageLayout.spread
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
    /** 보정 펜: 화살표·길이 표시의 몸통을 점선으로 (화살촉은 늘 실선) */
    var shapeDashed = false
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

    // ---- 채우기 ----
    var fillColor = 0xFFFFE082.toInt()
    var fillPattern = FillPattern.SOLID
    var fillMode = FillMode.BUCKET
    /** 자유 영역: 손떨림 보정 단계 (0 끔 ~ 3 강하게) */
    var fillSmoothing = 2
    /** 자유 영역: 거의 곧게 그린 변을 곧게 펴 다각형으로 */
    var fillPolygon = true
    /** true면 채우기 도구가 채우기만 지우는 지우개 */
    var fillErasing = false
    var fillEraseMode = EraserMode.STROKE
    /** 칠하기: 누른 자리 (쪽, x, y). 떼기 전에 많이 움직이면 취소 */
    private var fillTap: Triple<Int, Float, Float>? = null
    private var fillPressing = false
    /** 일반 지우개가 이번 획에서 채우기도 지울지: 처음 닿은 것이 채우기뿐이면 true, 다른 필기면 false (null = 아직) */
    private var eraseFills: Boolean? = null

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
    private val gap = 10f
    /** 쪽 배치와 '지금 보이는 쪽' 계산 (순수 계산이라 PageLayout에서) */
    private val pageLayout = PageLayout(gap)
    private val tops get() = pageLayout.tops
    private val lefts get() = pageLayout.lefts
    private val docW get() = pageLayout.docW
    private val docH get() = pageLayout.docH
    /** 쪽마다 오른쪽에 붙는 바깥 여백 (pt). 배지·포스트잇이 있는 문서만 [PAGE_SIDE_MARGIN] */
    private var rightMargin = 0f

    // ---- 화면 변환 ----
    private var baseScale = 1f
    private var zoom = 1f
    private val scale get() = baseScale * zoom
    private var offX = 0f
    private var offY = 0f
    private var lastReportedPage = -1

    // ---- 렌더링 ----
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** 칠하기·자유 영역을 찾는 비동기 일과 그리는 중인 영역 보여 주기는 FillController가 한다 */
    private val fillCtl: FillController = FillController(this, scope, object : FillController.Host {
        override val doc get() = this@DocumentView.doc
        override val ink get() = this@DocumentView.ink
        override val sizes get() = this@DocumentView.sizes
        override val scale get() = this@DocumentView.scale
        override val fillColor get() = this@DocumentView.fillColor
        override val fillPattern get() = this@DocumentView.fillPattern
        override val fillPolygon get() = this@DocumentView.fillPolygon
        override fun drawInk(c: Canvas, strokes: List<Stroke>) {
            val paint = inkPaint()
            for (s in strokes.sortedBy { inkLayer(it) }) drawInkStroke(c, paint, s)
        }
        override fun onFillFailed() { listener?.onFillFailed() }
    })
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
    private val tmpRect2 = RectF()
    /** 쪽 오른쪽 바깥 여백의 바탕 */
    private val marginPaint = Paint().apply { color = 0xFFF1F1F1.toInt() }
    /** 배지와 원문을 잇는 점선 */
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF78909C.toInt()
    }
    private val linkDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF78909C.toInt() }
    private val linkPath = Path()

    // ---- 입력 상태 ----
    private val scroller = OverScroller(context)
    private var fingerActive = false
    private var scaling = false
    private var fingersBlocked = false
    private var penPointerId = -1
    private var penIsFinger = false
    private var penErasing = false
    /** 손바닥 지우기 중 (닿은 모든 손가락·손바닥의 가운데를 지운다) */
    private var palmErasing = false
    /** 손바닥 지우개 반지름 (화면 px) */
    private var palmRadius = 0f
    /** 이번 손가락 획에서 가장 넓게 닿은 크기 (손가락 크기를 익히는 데 씀) */
    private var penMaxMajor = 0f
    /** 최근 손가락 획들의 닿은 크기 (손바닥과 견줄 보통 손가락 크기) */
    private val fingerSizes = ArrayDeque<Float>()
    private var curStroke: Stroke? = null
    private var curPage = -1
    private var lastPressure = 0.5f
    private var lastSx = 0f
    private var lastSy = 0f
    private val erased = ArrayList<Pair<Int, Stroke>>()
    /** 영역 지우개가 이번 획에서 잘라 남긴 조각들 */
    private val pieces = ArrayList<Pair<Int, Stroke>>()
    private var zoomAnimator: ValueAnimator? = null

    // ---- 마지막 쪽 아래 '빈 쪽 추가' (끌어 올리기·단추): 그리기·터치는 AddPageFooter가 한다 ----
    private val addFooter: AddPageFooter = AddPageFooter(this, object : AddPageFooter.Host {
        override val readOnly get() = this@DocumentView.readOnly
        override val scale get() = this@DocumentView.scale
        override val offsetX get() = offX
        override val offsetY get() = offY
        override val docWidth get() = docW
        override val docHeight get() = docH
        override val bottomInset get() = this@DocumentView.bottomInset
        override val pageCount get() = sizes.size
        override val lastPageBottom get() = tops[sizes.lastIndex] + sizes[sizes.lastIndex].height
        override val lastPageWidth get() = sizes[sizes.lastIndex].width
        override val lastPageHeight get() = sizes[sizes.lastIndex].height
        override val pageGap get() = gap
        override val rightMargin get() = this@DocumentView.rightMargin
        override fun stopFling() = scroller.forceFinished(true)
        override fun forwardToGestures(ev: MotionEvent) { gestureDetector.onTouchEvent(ev) }
        override fun onAddPage() { listener?.onPullAddPage() }
    }, SEL_COLOR)
    /** 포스트잇·메모를 누르고 움직였는지 가르는 거리 */
    private val touchSlop = android.view.ViewConfiguration.get(context).scaledTouchSlop

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
    /**
     * 손잡이로 크기 조절 중: 반대쪽 모서리·변(anchor)을 기준으로 가로 scaleK배, 세로 scaleKy배.
     * 모서리는 같은 비율로, 변 가운데 손잡이는 위아래로만(resizeAxis 2)·옆으로만(1)
     */
    private var resizing = false
    private var anchorX = 0f
    private var anchorY = 0f
    private var startDist = 1f
    private var scaleK = 1f
    private var scaleKy = 1f
    private var resizeAxis = 0
    /** 변 손잡이가 기준선의 어느 쪽에 있는지 (+1 오른쪽·아래, -1 왼쪽·위) */
    private var resizeDir = 1f
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
    /** 대상 선택: 누른 획·그림·글을 바로 고른다 (빈 곳을 끌면 네모 선택) */
    var lassoTap = false
    // ---- 레이저 (쪽 좌표. 저장하지 않고, 마지막 획을 떼고 laserFadeMs 뒤 한꺼번에 사라진다) ----
    private val laser = LaserTrails(this, density, { scale }, { laserFadeMs })

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
        resetNoteTouch()
        paletteNote = null
        cancelNotePlacement()
        addFooter.reset()
        rightMargin = if (inkDoc.hasMarginItems()) PAGE_SIDE_MARGIN else 0f
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
        post { scrollBar.show(1500) }
    }

    // ================= 레이아웃 / 변환 =================

    /** 쪽을 한 줄에 하나(양쪽 보기면 둘)씩 쌓아 [tops]·[lefts]·[docW]·[docH]를 정한다 */
    private fun layoutPages() {
        pageLayout.layout(
            FloatArray(sizes.size) { sizes[it].width }, FloatArray(sizes.size) { sizes[it].height },
            rightMargin, twoPage, width, height,
        )
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
    private fun contentH() = docH * scale + addFooter.footerPx + bottomInset + topInset

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

    private fun visibleRange(): IntRange =
        if (sizes.isEmpty()) IntRange.EMPTY else pageLayout.visibleRange(offY, scale, height.toFloat())

    /**
     * 배지·포스트잇이 처음 생기면 모든 쪽에 바깥 여백을 붙인다 (보던 자리는 그대로).
     * 다 지워도 문서를 다시 열 때까지는 둔다
     */
    fun refreshMargin() {
        val inkDoc = ink ?: return
        if (rightMargin > 0f || doc == null || width == 0 || !inkDoc.hasMarginItems()) return
        val spot = topSpot()
        clearSelection()
        rightMargin = PAGE_SIDE_MARGIN
        layoutPages()
        baseScale = width / docW
        baseCache.evictAll()
        details = emptyList()
        if (spot != null) scrollToPageY(spot.first, spot.second) else clamp()
        scheduleDetail()
        invalidate()
    }

    /**
     * 화면 좌표 → (페이지, 페이지 x, 페이지 y). 페이지 사이 여백은 가까운 페이지로.
     * [wide]면 쪽 오른쪽 바깥 여백도 그 쪽으로 (배지·포스트잇을 누를 때). 아니면 필기할 수 없는 곳이라 null
     */
    private fun hitPage(sx: Float, sy: Float, wide: Boolean = false): Triple<Int, Float, Float>? =
        if (sizes.isEmpty()) null else pageLayout.hitPage(offX, offY, scale, sx, sy, wide)

    /** 화면 가운데 줄의 첫 쪽 (문서가 없으면 -1) */
    private fun rowFirstPage(): Int = if (sizes.isEmpty()) -1 else pageLayout.rowFirstPage(offY, scale, height.toFloat())

    /** 화면 가운데에 걸친 쪽 (문서가 없으면 -1). 양쪽 보기면 그 줄에서 화면 가운데에 걸친 쪽 */
    fun currentPage(): Int =
        if (sizes.isEmpty()) -1 else pageLayout.currentPage(offX, offY, scale, width.toFloat(), height.toFloat())

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

    /** 화면 맨 위에 걸친 (쪽, 그 쪽 안 높이). 링크로 옮기기 전 자리를 기억해 둘 때 ([scrollToPageY]로 돌아온다) */
    fun topSpot(): Pair<Int, Float>? = if (sizes.isEmpty()) null else pageLayout.topSpot(offY, topInset, scale)

    /** 쪽 좌표 (x, y)에 있는 링크 단 글의 주소 (맨 위 것) */
    fun inkLinkAt(page: Int, x: Float, y: Float): String? = boxAt(page, x, y, textOnly = true)?.link

    /** [page]쪽의 높이 [y]가 화면 맨 위에 오도록 옮긴다 (목차 링크로 갈 때) */
    fun scrollToPageY(page: Int, y: Float) {
        if (page !in sizes.indices || width == 0) return
        scroller.forceFinished(true)
        zoomAnimator?.cancel()
        offY = (tops[page] + y.coerceIn(-gap / 2, sizes[page].height)) * scale - topInset
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
        canvas.save()
        canvas.translate(0f, -addFooter.pullPx)
        for (i in range) {
            val r = pageRect(i, tmpRect)
            canvas.drawRect(r, pagePaint)
            val bmp = baseCache.get(i)
            if (bmp != null) {
                canvas.drawBitmap(bmp, null, r, bmpPaint)
                // 배율 단계가 바뀌었으면 있는 그림을 늘려 보여 주는 동안 맞는 크기로 다시 그린다
                val want = sizes[i].width * baseRenderScale(i)
                if (bmp.width < want * 0.9f || bmp.width > want * 1.5f) requestBase(i)
            } else requestBase(i)
            for (det in details) {
                if (det.page != i) continue
                val dst = RectF(
                    r.left + det.region.left * s, r.top + det.region.top * s,
                    r.left + det.region.right * s, r.top + det.region.bottom * s
                )
                canvas.drawBitmap(det.bmp, null, dst, bmpPaint)
            }
            canvas.drawRect(r, borderPaint)
            // 쪽 오른쪽 바깥 여백: 필기는 못 하고 배지·포스트잇만 놓인다
            if (rightMargin > 0f) {
                val mr = tmpRect2.apply { set(r.right, r.top, r.right + rightMargin * s, r.bottom) }
                canvas.drawRect(mr, marginPaint)
                canvas.drawLine(mr.left, mr.top, mr.left, mr.bottom, borderPaint)
            }

            canvas.save()
            canvas.clipRect(r)
            canvas.translate(r.left, r.top)
            canvas.scale(s, s)
            // 찾은 글자: 형광펜처럼 글자 아래에 비치게
            searchHits[i]?.forEach { canvas.drawRect(it, hitPaint) }
            val dragging = (moving || resizing || rotating) && selPage == i
            // 그림을 먼저, 채우기를 그 위에, 나머지 필기는 맨 위에 (배지·포스트잇은 바깥 여백까지 그려야 해서 따로)
            for (layer in 0..2) for (st in inkDoc.pages[i]) {
                if (inkLayer(st) != layer || st === hiddenStroke || (dragging && st in selSet) || st.isMarginItem()) continue
                drawStroke(canvas, st)
            }
            fillCtl.drawPending(canvas, i)
            pickRect?.let { if (pickPage == i) drawPickRect(canvas, it) }
            if (dragging) {
                // 옮기거나 크기를 바꾸는 중인 획은 손을 뗄 때까지 그림만 바꿔 그린다
                canvas.save()
                canvas.translate(moveDx, moveDy)
                if (resizing) canvas.scale(scaleK, scaleKy, anchorX, anchorY)
                if (rotating) canvas.rotate(rotDeg, rotCx, rotCy)
                for (st in selection) drawStroke(canvas, st)
                canvas.restore()
            }
            laser.drawFaded(canvas, i)
            if (curPage == i) curStroke?.let {
                if (it.tool == Tool.LASER) laser.draw(canvas, it, 1f)
                else if (it.tool == Tool.FILL) fillCtl.drawDraft(canvas, it)
                else if (tool == Tool.SHAPE) {
                    // 보정 펜: 내 획은 흐리게, 맞춘 도형은 조금 더 진하게 미리 보기
                    drawStroke(canvas, it, 0.3f)
                    shapePreview?.forEach { sp -> drawStroke(canvas, sp, 0.6f) }
                } else drawStroke(canvas, it)
            }
            canvas.restore()

            // 배지(점선으로 원문과 이어서)와 포스트잇: 쪽 오른쪽 바깥 여백까지 그린다. 포스트잇은 맨 위에
            canvas.save()
            canvas.clipRect(r.left, r.top, r.right + rightMargin * s, r.bottom)
            canvas.translate(r.left, r.top)
            canvas.scale(s, s)
            drawMarginItems(canvas, inkDoc, i, (moving || resizing || rotating) && selPage == i)
            canvas.restore()
        }
        addFooter.drawButton(canvas)
        canvas.restore()
        if (addFooter.pullPx > 0f) addFooter.drawPullPage(canvas)
        drawSelection(canvas)
        if (!range.isEmpty()) {
            // 앞뒤 한 페이지 미리 그리기
            if (range.first > 0 && baseCache.get(range.first - 1) == null) requestBase(range.first - 1)
            if (range.last < sizes.size - 1 && baseCache.get(range.last + 1) == null) requestBase(range.last + 1)
            cancelUnneededJobs(range.first - 1, range.last + 1)
        }
        if (penPointerId != -1 && penErasing) {
            canvas.drawCircle(lastSx, lastSy, if (palmErasing) palmRadius else eraserRadiusDp * density, cursorPaint)
        }
        scrollBar.draw(canvas)
        listener?.onViewportChanged()
        reportPage(d.pageCount)
    }

    // ================= 포스트잇 메모 =================
    // 접힌 메모: 톡 누르면 펼치고, 꾹 누른 채 끌면 옮긴다. 펼친 메모: 띠의 단추(색 · 지우기 · 접기),
    // 띠를 끌면 옮기고, 몸통을 누르면 글 고치기. 손가락으로 메모에서 시작해 움직이면 그대로 문서 넘기기

    /** 새 메모 색 (마지막으로 고른 색) */
    var noteColor = StickyNote.COLORS[0]

    /** 누르고 있는 메모와 그 쪽 */
    private var noteTrack: Stroke? = null
    private var notePage = -1
    private var noteMode = NoteTouch.NONE
    private var noteButton: NoteButton? = null
    private var noteSwatch = -1
    private var noteFinger = false
    private var noteDownX = 0f
    private var noteDownY = 0f
    private var noteMoved = false
    /** 메모를 끌어 옮기는 중 (손가락이 움직인 거리, 화면 px) */
    private var noteDragging = false
    private var noteDx = 0f
    private var noteDy = 0f
    private val noteDelta = FloatArray(2)
    /** 색 고르기 칸을 띄운 메모 */
    private var paletteNote: Stroke? = null
    private val noteBox = RectF()
    private val swatchFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swatchLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    private enum class NoteTouch { NONE, ICON, HEADER, BUTTON, BODY, SWATCH, SCROLL, BADGE }

    /** 배지를 누른 쪽 좌표 (톡 눌렀을 때 오답 쪽으로 가는 데 쓴다) */
    private var noteDownPx = 0f
    private var noteDownPy = 0f

    /**
     * 쪽 좌표 (x, y)의 오답 배지. 손가락이거나 읽기 모드거나 바깥 여백에 있는 배지만
     * (펜으로 쪽 안에 놓인 배지 위에 쓸 수 있게)
     */
    private fun badgeAt(page: Int, x: Float, y: Float, ev: MotionEvent): Stroke? {
        val finger = ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        val slop = 4f * density / scale
        val pw = sizes.getOrNull(page)?.width ?: return null
        return ink?.pages?.getOrNull(page)?.lastOrNull { st ->
            st.isWrongBadge() && wrongBounds(st).apply { inset(-slop, -slop) }.contains(x, y) &&
                (finger || readOnly || x > pw)
        }
    }

    /** 접힌 메모·배지를 꾹 누름: 끌어 옮기기 시작 */
    private val noteLongPress = Runnable {
        if (noteTrack != null && (noteMode == NoteTouch.ICON || noteMode == NoteTouch.BADGE) && !noteMoved && !readOnly) {
            noteDragging = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            invalidate()
        }
    }

    /** 포스트잇 붙일 곳을 고르는 중: 다음에 톡 누른 자리에 메모를 넣는다 (손가락으로 밀면 그동안 문서를 넘겨 볼 수 있다) */
    var notePlacing = false
        private set
    private var placeTracking = false
    private var placeFinger = false
    private var placeMoved = false
    private var placeDownX = 0f
    private var placeDownY = 0f

    /** 포스트잇 붙일 곳 고르기를 시작한다 (끝나면 [Listener.onNotePlacementEnded]) */
    fun startNotePlacement(): Boolean {
        if (ink == null || readOnly) return false
        clearSelection()
        paletteNote = null
        notePlacing = true
        invalidate()
        return true
    }

    fun cancelNotePlacement() {
        if (!notePlacing) return
        notePlacing = false
        placeTracking = false
        listener?.onNotePlacementEnded()
    }

    /** 쪽의 (x, y)를 왼쪽 위로 새 메모를 넣고(펼친 채, 쪽 안에 들게) 바로 글을 치게 한다. 실행 취소 가능 */
    private fun placeNote(page: Int, x: Float, y: Float) {
        val inkDoc = ink ?: return
        val st = StickyNote.create(x, y, noteColor, sizes[page].width, sizes[page].height)
        inkDoc.add(page, st)
        notePlacing = false
        listener?.onNotePlacementEnded()
        invalidate()
        listener?.onNoteEdit(page, st)
    }

    /** 붙일 곳 고르는 동안의 터치: 톡 누르면 그 자리에, 손가락으로 밀면 문서 넘기기 */
    private fun handlePlaceTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                placeTracking = true
                placeMoved = false
                placeFinger = ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                placeDownX = ev.x
                placeDownY = ev.y
                scroller.forceFinished(true)
            }
            MotionEvent.ACTION_MOVE -> if (placeTracking) {
                if (!placeMoved && hypot(ev.x - placeDownX, ev.y - placeDownY) > touchSlop) {
                    placeMoved = true
                    if (placeFinger) {
                        val down = MotionEvent.obtain(ev)
                        down.action = MotionEvent.ACTION_DOWN
                        gestureDetector.onTouchEvent(down)
                        down.recycle()
                    }
                } else if (placeMoved && placeFinger) gestureDetector.onTouchEvent(ev)
            }
            MotionEvent.ACTION_UP -> if (placeTracking) {
                placeTracking = false
                if (placeMoved) {
                    if (placeFinger) gestureDetector.onTouchEvent(ev)
                } else hitPage(ev.x, ev.y)?.let { (page, x, y) ->
                    // 쪽 바깥(쪽 사이 여백)을 누르면 다시 고르게 둔다
                    if (page in sizes.indices && x in 0f..sizes[page].width && y in 0f..sizes[page].height) placeNote(page, x, y)
                }
            }
            MotionEvent.ACTION_CANCEL -> if (placeTracking) {
                placeTracking = false
                if (placeMoved && placeFinger) gestureDetector.onTouchEvent(ev)
            }
        }
        return true
    }

    // ================= 오답 영역 고르기 =================
    // 한 손가락이나 펜으로 문제 영역을 네모로 끌어 고른다. 두 손가락은 그대로 이동·확대

    /** 오답 영역을 고르는 중 */
    var wrongPicking = false
        private set
    private var pickTracking = false
    private var pickPage = -1
    private var pickX0 = 0f
    private var pickY0 = 0f
    /** 끄는 중이거나 골라 둔 영역 (쪽 [pickPage]의 좌표) */
    private var pickRect: RectF? = null
    private val pickFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x302979FF }
    private val pickLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0xFF2979FF.toInt()
    }

    /** 오답 영역 고르기를 시작한다 (끝나면 [Listener.onWrongPickEnded]) */
    fun startWrongPick(): Boolean {
        if (ink == null || readOnly) return false
        clearSelection()
        paletteNote = null
        cancelNotePlacement()
        clearWrongRect()
        wrongPicking = true
        invalidate()
        return true
    }

    fun cancelWrongPick() {
        if (!wrongPicking) return
        wrongPicking = false
        pickTracking = false
        pickRect = null
        listener?.onWrongPickEnded()
        invalidate()
    }

    /** 골라 둔 영역 표시를 지운다 */
    fun clearWrongRect() {
        if (pickRect == null) return
        pickRect = null
        pickTracking = false
        invalidate()
    }

    private fun drawPickRect(c: Canvas, r: RectF) {
        pickLine.strokeWidth = 2f * density / scale
        c.drawRect(r, pickFill)
        c.drawRect(r, pickLine)
    }

    /** 쪽 [page] 안의 점으로 (화면 좌표 → 쪽 좌표, 쪽 밖은 가장자리로) */
    private fun clampToPage(page: Int, sx: Float, sy: Float, out: FloatArray) {
        out[0] = ((sx + offX) / scale - lefts[page]).coerceIn(0f, sizes[page].width)
        out[1] = ((sy + offY) / scale - tops[page]).coerceIn(0f, sizes[page].height)
    }

    private val pickPoint = FloatArray(2)

    /** 오답 영역 고르는 동안의 터치: 한 손가락·펜으로 끌면 네모, 손가락이 더 닿으면 이동·확대로 */
    private fun handlePickTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                scroller.forceFinished(true)
                pickRect = null
                val hit = hitPage(ev.x, ev.y)
                pickTracking = hit != null && hit.first in sizes.indices
                if (pickTracking) {
                    pickPage = hit!!.first
                    clampToPage(pickPage, ev.x, ev.y, pickPoint)
                    pickX0 = pickPoint[0]
                    pickY0 = pickPoint[1]
                }
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> if (pickTracking) {
                clampToPage(pickPage, ev.x, ev.y, pickPoint)
                pickRect = RectF(
                    min(pickX0, pickPoint[0]), min(pickY0, pickPoint[1]),
                    max(pickX0, pickPoint[0]), max(pickY0, pickPoint[1]),
                )
                invalidate()
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // 두 번째 손가락: 네모 고르기를 그만두고 이동·확대. 제스처 감지기는 첫 손가락의 DOWN을 못 받았으므로 지금 자리에서 새로 시작
                pickTracking = false
                pickRect = null
                fingerActive = true
                val down = MotionEvent.obtain(ev)
                down.action = MotionEvent.ACTION_DOWN
                scaleDetector.onTouchEvent(down)
                gestureDetector.onTouchEvent(down)
                down.recycle()
                scaleDetector.onTouchEvent(ev)
                gestureDetector.onTouchEvent(ev)
                invalidate()
            }
            MotionEvent.ACTION_UP -> if (pickTracking) {
                pickTracking = false
                val r = pickRect
                val minSide = 16f
                if (r != null && r.width() >= minSide && r.height() >= minSide) {
                    wrongPicking = false
                    listener?.onWrongPickEnded()
                    listener?.onWrongPicked(pickPage, RectF(r))
                } else pickRect = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                pickTracking = false
                pickRect = null
                invalidate()
            }
        }
        return true
    }

    /** 메모 글 고치기 (실행 취소 가능) */
    fun setNoteText(page: Int, old: Stroke, text: String) {
        val inkDoc = ink ?: return
        if (old.note == null || old.note?.text == text || page !in inkDoc.pages.indices) return
        inkDoc.replace(page, old, StickyNote.withText(old, text))
        invalidate()
    }

    /** 쪽 좌표 (x, y)에 닿는 맨 위 메모. 접힌 메모는 [slop]만큼 넉넉히 */
    private fun noteAt(page: Int, x: Float, y: Float, slop: Float): Stroke? =
        ink?.pages?.getOrNull(page)?.lastOrNull { st ->
            val n = st.note ?: return@lastOrNull false
            if (n.collapsed) st.noteIconRect(noteBox).apply { inset(-slop, -slop) }.contains(x, y)
            else st.noteRect(noteBox).contains(x, y)
        }

    private fun expandedNoteAt(page: Int, x: Float, y: Float): Stroke? =
        ink?.pages?.getOrNull(page)?.lastOrNull { st -> st.note?.collapsed == false && st.noteRect(noteBox).contains(x, y) }

    /**
     * 끌어 옮기는 거리 (쪽 좌표). 메모는 접힌 네모가 쪽 안에, 펼친 몸통은 바깥 여백까지만.
     * 배지는 쪽과 바깥 여백 안에서
     */
    private fun computeNoteDelta(st: Stroke, page: Int, out: FloatArray) {
        val pw = sizes[page].width
        val ph = sizes[page].height
        val n = st.note
        var minDx: Float
        var maxDx: Float
        var minDy: Float
        var maxDy: Float
        if (n == null) {
            val b = wrongBounds(st)
            minDx = -b.left
            maxDx = pw + rightMargin - b.right
            minDy = -b.top
            maxDy = ph - b.bottom
        } else {
            // 접힌 자리(점 0)는 늘 쪽 안에, 펼친 자리(점 1)는 펼쳤을 때 몸통이 쪽 아래로 나가지 않게
            val icon = StickyNote.ICON
            minDx = -min(st.x(0), st.x(1))
            minDy = -min(st.y(0), st.y(1))
            maxDx = min(pw - icon - st.x(0), if (n.collapsed) Float.MAX_VALUE else stickyMaxLeft(pw, n.w) - st.x(1))
            maxDy = min(ph - icon - st.y(0), if (n.collapsed) Float.MAX_VALUE else ph - n.h - st.y(1))
        }
        // 이미 한계를 넘어 있어도(예전 문서) 더 벗어나게만 막는다
        maxDx = max(maxDx, 0f); minDx = min(minDx, 0f)
        maxDy = max(maxDy, 0f); minDy = min(minDy, 0f)
        out[0] = (noteDx / scale).coerceIn(minDx, maxDx)
        out[1] = (noteDy / scale).coerceIn(minDy, maxDy)
    }

    /** 배지와 포스트잇 그리기 (쪽 좌표 캔버스, 바깥 여백까지). 끌어 옮기는 중이면 손가락을 따라 */
    private fun drawMarginItems(c: Canvas, inkDoc: InkDocument, page: Int, selDragging: Boolean) {
        val list = inkDoc.pages[page]
        val pw = sizes[page].width
        fun shown(st: Stroke) = st !== hiddenStroke && !(selDragging && st in selSet)
        fun dragged(st: Stroke): Boolean {
            val drag = st === noteTrack && noteDragging
            if (drag) computeNoteDelta(st, page, noteDelta) else { noteDelta[0] = 0f; noteDelta[1] = 0f }
            return drag
        }
        for (st in list) if (st.isWrongBadge() && shown(st)) {
            dragged(st)
            drawBadgeLink(c, inkDoc, st, page, pw, noteDelta[0], noteDelta[1])
        }
        for (st in list) if (st.isWrongBadge() && shown(st)) {
            c.save()
            dragged(st)
            c.translate(noteDelta[0], noteDelta[1])
            drawStroke(c, st)
            c.restore()
        }
        for (st in list) if (st.note != null && shown(st)) {
            c.save()
            dragged(st)
            c.translate(noteDelta[0], noteDelta[1])
            drawStroke(c, st)
            c.restore()
        }
        paletteNote?.let { if (it.note?.collapsed == false && list.contains(it)) drawNotePalette(c, it) }
    }

    /** 배지에서 원문 자리까지 점선: 문제 영역 오른쪽 위에서 쪽 끝까지 가로로 가다가 배지로 비스듬히 */
    private fun drawBadgeLink(c: Canvas, inkDoc: InkDocument, badge: Stroke, page: Int, pw: Float, dx: Float, dy: Float) {
        val n = badge.role?.substring(2)?.toIntOrNull() ?: return
        val e = inkDoc.wrongByNumber(n) ?: return
        val src = e.srcRect ?: return
        if (e.srcList !== inkDoc.pages[page]) return
        val b = wrongBounds(badge)
        val px = density / scale  // 화면 1dp에 해당하는 쪽 좌표 길이
        val ax = src.right
        val ay = src.top + min(8f, src.height() / 2f)
        val bx = b.left + dx
        val by = b.centerY() + dy
        linkPath.reset()
        linkPath.moveTo(ax, ay)
        if (bx > pw) {
            linkPath.lineTo(pw, ay)
            linkPath.lineTo(bx, by)
        } else linkPath.lineTo(bx, by)
        linkPaint.strokeWidth = 1f * px
        linkPaint.pathEffect = DashPathEffect(floatArrayOf(2f * px, 3f * px), 0f)
        c.drawPath(linkPath, linkPaint)
        c.drawCircle(ax, ay, 1.8f * px, linkDot)
    }

    /** 색 고르기 칸 k번째 자리 (쪽 좌표): 메모 띠 바로 아래에 한 줄 */
    private fun swatchRect(st: Stroke, k: Int, out: RectF): RectF {
        val r = st.noteRect(RectF())
        val n = StickyNote.COLORS.size
        val cell = r.width() / n
        val top = r.top + StickyNote.HEADER
        return out.apply { set(r.left + cell * k, top, r.left + cell * (k + 1), top + cell) }
    }

    /** 펼친 메모 띠 아래의 색 고르기 칸 (쪽 좌표 캔버스) */
    private fun drawNotePalette(c: Canvas, st: Stroke) {
        val b = RectF()
        val r = st.noteRect(RectF())
        swatchRect(st, 0, b)
        swatchFill.color = Color.argb(235, 255, 255, 255)
        c.drawRect(r.left, b.top, r.right, b.bottom, swatchFill)
        for (k in StickyNote.COLORS.indices) {
            swatchRect(st, k, b)
            val rad = b.width() * 0.32f
            swatchFill.color = StickyNote.COLORS[k]
            c.drawCircle(b.centerX(), b.centerY(), rad, swatchFill)
            val on = (StickyNote.COLORS[k] or 0xFF000000.toInt()) == (st.color or 0xFF000000.toInt())
            swatchLine.color = if (on) SEL_COLOR else Color.argb(90, 0, 0, 0)
            swatchLine.strokeWidth = if (on) 2f else 0.8f
            c.drawCircle(b.centerX(), b.centerY(), rad, swatchLine)
        }
    }

    private fun resetNoteTouch() {
        removeCallbacks(noteLongPress)
        noteTrack = null
        notePage = -1
        noteMode = NoteTouch.NONE
        noteButton = null
        noteSwatch = -1
        noteMoved = false
        noteDragging = false
        noteDx = 0f
        noteDy = 0f
    }

    /** 메모를 누른 동작 (손가락·펜 모두). 메모가 아닌 곳에서 시작했으면 false */
    private fun handleNoteTouch(ev: MotionEvent): Boolean {
        if (wrongPicking) return handlePickTouch(ev)
        if (notePlacing || placeTracking) return handlePlaceTouch(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetNoteTouch()
                val hit = hitPage(ev.x, ev.y, wide = true)
                // 색 고르기 칸이 떠 있으면 먼저 그 칸을 본다. 다른 곳을 누르면 닫는다
                paletteNote?.let { pn ->
                    if (hit != null && ink?.pages?.getOrNull(hit.first)?.contains(pn) == true) {
                        val b = RectF()
                        for (k in StickyNote.COLORS.indices) if (swatchRect(pn, k, b).contains(hit.second, hit.third)) {
                            beginNoteTouch(ev, pn, hit.first, NoteTouch.SWATCH)
                            noteSwatch = k
                            return true
                        }
                    }
                    paletteNote = null
                    invalidate()
                }
                hit ?: return false
                val st = noteAt(hit.first, hit.second, hit.third, 6f * density / scale) ?: run {
                    // 오답 배지: 톡 누르면 오답 쪽으로, 꾹 누르면 끌어 옮긴다
                    val badge = badgeAt(hit.first, hit.second, hit.third, ev) ?: return false
                    beginNoteTouch(ev, badge, hit.first, NoteTouch.BADGE)
                    noteDownPx = hit.second
                    noteDownPy = hit.third
                    if (!readOnly) postDelayed(noteLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                    invalidate()
                    return true
                }
                val n = st.note!!
                val mode = when {
                    n.collapsed -> NoteTouch.ICON
                    hit.third < st.y(1) + StickyNote.HEADER -> {
                        noteButton = st.noteButtonAt(hit.second, hit.third)
                        if (noteButton != null) NoteTouch.BUTTON else NoteTouch.HEADER
                    }
                    else -> NoteTouch.BODY
                }
                beginNoteTouch(ev, st, hit.first, mode)
                if (mode == NoteTouch.ICON && !readOnly) postDelayed(noteLongPress, ViewConfiguration.getLongPressTimeout().toLong())
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                noteTrack ?: return false
                if (!noteMoved && hypot(ev.x - noteDownX, ev.y - noteDownY) > touchSlop) {
                    noteMoved = true
                    removeCallbacks(noteLongPress)
                    when {
                        noteDragging -> {}
                        // 펼친 메모는 띠를 끌면 바로 옮긴다
                        noteMode == NoteTouch.HEADER && !readOnly -> noteDragging = true
                        // 손가락이면 문서 넘기기로 (제스처 감지기는 DOWN을 못 받았으므로 지금 자리에서 새로 시작)
                        noteFinger -> {
                            noteMode = NoteTouch.SCROLL
                            val down = MotionEvent.obtain(ev)
                            down.action = MotionEvent.ACTION_DOWN
                            gestureDetector.onTouchEvent(down)
                            down.recycle()
                        }
                        else -> noteMode = NoteTouch.NONE
                    }
                }
                if (noteDragging) {
                    noteDx = ev.x - noteDownX
                    noteDy = ev.y - noteDownY
                    invalidate()
                } else if (noteMode == NoteTouch.SCROLL) gestureDetector.onTouchEvent(ev)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val st = noteTrack ?: return false
                removeCallbacks(noteLongPress)
                when {
                    noteDragging -> {
                        computeNoteDelta(st, notePage, noteDelta)
                        if (noteDelta[0] != 0f || noteDelta[1] != 0f) ink?.move(listOf(st), noteDelta[0], noteDelta[1])
                    }
                    noteMode == NoteTouch.SCROLL -> gestureDetector.onTouchEvent(ev)
                    !noteMoved -> noteTapped(st, notePage)
                }
                resetNoteTouch()
                invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                noteTrack ?: return false
                if (noteMode == NoteTouch.SCROLL) gestureDetector.onTouchEvent(ev)
                resetNoteTouch()
                invalidate()
                return true
            }
        }
        return noteTrack != null
    }

    private fun beginNoteTouch(ev: MotionEvent, st: Stroke, page: Int, mode: NoteTouch) {
        noteTrack = st
        notePage = page
        noteMode = mode
        noteFinger = ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        noteDownX = ev.x
        noteDownY = ev.y
        scroller.forceFinished(true)
        zoomAnimator?.cancel()
        clearSelection()
    }

    /** 메모를 톡 누름. 읽기 모드에서는 펼치고 접기만 */
    private fun noteTapped(st: Stroke, page: Int) {
        val inkDoc = ink ?: return
        if (noteMode == NoteTouch.BADGE) {
            listener?.onWrongTap(page, noteDownPx, noteDownPy)
            return
        }
        val n = st.note ?: return
        if (page !in sizes.indices) return
        playSoundEffect(android.view.SoundEffectConstants.CLICK)
        // 접고 펼친 상태는 저장할 때 같이 남지만, 그것만으로 '저장 안 한 변경'이 생기지는 않는다 (읽으려고 펼쳐 볼 때)
        when (noteMode) {
            NoteTouch.ICON -> {
                n.collapsed = false
                st.fitNoteInPage(sizes[page].width, sizes[page].height)
            }
            NoteTouch.BUTTON -> when (noteButton) {
                NoteButton.COLLAPSE -> {
                    n.collapsed = true
                    paletteNote = null
                }
                NoteButton.COLOR -> if (!readOnly) paletteNote = if (paletteNote === st) null else st
                NoteButton.DELETE -> if (!readOnly) {
                    paletteNote = null
                    inkDoc.remove(page, listOf(st))
                }
                null -> {}
            }
            NoteTouch.BODY -> if (!readOnly) listener?.onNoteEdit(page, st)
            NoteTouch.SWATCH -> {
                val c = StickyNote.COLORS.getOrNull(noteSwatch) ?: return
                if (c != st.color) inkDoc.edit(listOf(st)) { st.recolor(c) }
                noteColor = c
                listener?.onNoteColorPicked(c)
                paletteNote = null
            }
            else -> {}
        }
        invalidate()
    }

    /** 올가미 선과 선택 상자 (화면 좌표) */
    private fun drawSelection(canvas: Canvas) {
        val s = scale
        if (lassoing && lassoCount > 1) {
            val ox = lefts[lassoPage] * s - offX
            val oy = tops[lassoPage] * s - offY
            if (lassoRect || lassoTap) {
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
            // 변 가운데: 위아래로만·옆으로만 늘이는 납작한 손잡이
            val (topBottom, leftRight) = sideHandles(rect)
            val long = hr * 1.6f
            val short = hr * 0.8f
            if (topBottom) for (cy in floatArrayOf(rect.top, rect.bottom)) {
                val cx = rect.centerX()
                canvas.drawRoundRect(cx - long, cy - short, cx + long, cy + short, short, short, handleFill)
                canvas.drawRoundRect(cx - long, cy - short, cx + long, cy + short, short, short, handleLine)
            }
            if (leftRight) for (cx in floatArrayOf(rect.left, rect.right)) {
                val cy = rect.centerY()
                canvas.drawRoundRect(cx - short, cy - long, cx + short, cy + long, short, short, handleFill)
                canvas.drawRoundRect(cx - short, cy - long, cx + short, cy + long, short, short, handleLine)
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
                anchorX + (out.left - anchorX) * scaleK, anchorY + (out.top - anchorY) * scaleKy,
                anchorX + (out.right - anchorX) * scaleK, anchorY + (out.bottom - anchorY) * scaleKy,
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

    fun clearLaser() = laser.clear()

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
        // 많이 줄여 여러 쪽이 한꺼번에 보일 때는 작게 그려 둔다 (큰 화면에서 메모리가 모자라 계속 다시 그리지 않게)
        val k = when {
            zoom >= 0.5f -> 1f
            zoom >= 0.25f -> 0.5f
            else -> 0.25f
        }
        return min(baseScale * k, sqrt(maxPixels / (sz.width * sz.height)))
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
            addFooter.release(add = false)
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
            var dy = distanceY
            // 끌어 올려 둔 새 쪽 자리가 있으면 내릴 때 그것부터 접는다
            dy = addFooter.foldBack(dy)
            offX += distanceX
            offY += dy
            val want = offY
            clamp()
            // 마지막 쪽 끝에서 더 올리면 (뻑뻑하게) 새 쪽 자리를 끌어낸다
            if (dy > 0f && want > offY + 0.5f) addFooter.stretchPull(want, offY)
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
            // 칠하기: 손가락으로 톡 눌러도 칠한다
            else if (tool == Tool.FILL && fillMode == FillMode.BUCKET && !fillErasing && !readOnly) {
                hitPage(e.x, e.y)?.let { fillCtl.bucketFill(it.first, it.second, it.third) }
            }
            // 다른 도구에서는 손가락으로 테이프를 톡 누르면 보였다 가려졌다.
            // 읽기 모드에서 테이프가 아닌 곳을 누르면 (펜도) 링크를 따라간다
            // 필기 모드에서는 손가락으로 누를 때만 오답 배지·'원문 보기'를 따라간다 (펜은 필기)
            else if (!toggleTapeAt(e.x, e.y)) {
                hitPage(e.x, e.y)?.let {
                    if (readOnly) listener?.onReadTap(it.first, it.second, it.third)
                    else if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) listener?.onWrongTap(it.first, it.second, it.third)
                }
            }
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
    // 그리기·끌기는 FastScrollBar가 한다. 여기서는 스크롤했다는 것만 알려 준다.

    private val scrollBar = FastScrollBar(this, object : FastScrollBar.Host {
        override val contentHeight get() = contentH()
        override val topInset get() = this@DocumentView.topInset
        override val bottomInset get() = this@DocumentView.bottomInset
        override var offsetY: Float
            get() = offY
            set(v) { offY = v }
        override fun pageBubbleText() = if (sizes.isNotEmpty()) "${currentPage() + 1} / ${sizes.size}" else null
        override fun stopMotion() {
            scroller.forceFinished(true)
            zoomAnimator?.cancel()
        }
        override fun clampOffset() = clamp()
        override fun onDragEnd() = scheduleDetail()
    })

    private fun noteScrolled(dy: Float) {
        if (dy == 0f) return
        listener?.onScrolled()
        clearLaser()
        scrollBar.show(2000)
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
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) listener?.onTouchDown()
        if (penPointerId == -1 && scrollBar.onTouch(ev)) return true
        if (penPointerId == -1 && !fingerActive && addFooter.onTouch(ev)) return true
        if (penPointerId == -1 && !fingerActive && handleNoteTouch(ev)) return true
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
                penErasing = tool == Tool.ERASER || (tool == Tool.TAPE && tapeErasing) || (tool == Tool.FILL && fillErasing) ||
                    (stylus && isEraserInput(ev, idx, samsungButton))
                penMaxMajor = if (fingerPen) ev.getTouchMajor(idx) else 0f
                startPen(ev.getX(idx), ev.getY(idx), pressure(ev, idx), ev.eventTime)
                if (palmReady() && anyPalm(ev)) switchToPalm(ev)
                return true
            }
        }

        if (penPointerId != -1 && palmErasing) {
            when (action) {
                MotionEvent.ACTION_MOVE, MotionEvent.ACTION_POINTER_DOWN -> palmMove(ev, -1)
                MotionEvent.ACTION_POINTER_UP -> palmMove(ev, ev.actionIndex)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> endPen(commit = true)
            }
            return true
        }

        if (penPointerId != -1) {
            // 손가락 필기 중 손바닥처럼 넓게 닿거나, 여러 손가락이 한꺼번에 닿으면 손바닥 지우개로 바꾼다
            if (palmReady() && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_POINTER_DOWN)) {
                if (anyPalm(ev) || (ev.pointerCount >= 3 && ev.eventTime - ev.downTime < PALM_GATHER_MS)) {
                    switchToPalm(ev)
                    return true
                }
                if (action == MotionEvent.ACTION_MOVE) {
                    val idx = ev.findPointerIndex(penPointerId)
                    if (idx >= 0) penMaxMajor = max(penMaxMajor, ev.getTouchMajor(idx))
                }
            }
            // 손가락 필기 중 두 번째 손가락이 닿으면 필기를 취소하고 이동/확대로 전환
            if (penIsFinger && action == MotionEvent.ACTION_POINTER_DOWN) {
                endPen(commit = false)
                fingerActive = true
                // 제스처 감지기는 첫 손가락의 DOWN을 못 받았으므로 지금 두 손가락 자리에서 새로 시작시킨다.
                // 안 그러면 지난 제스처가 끝난 자리를 기준으로 첫 움직임을 계산해 문서가 확 튄다
                val down = MotionEvent.obtain(ev)
                down.action = MotionEvent.ACTION_DOWN
                scaleDetector.onTouchEvent(down)
                gestureDetector.onTouchEvent(down)
                down.recycle()
                scaleDetector.onTouchEvent(ev)
                gestureDetector.onTouchEvent(ev)
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

        // 두 손가락으로 넘기기를 막 시작했는데 곧바로 손가락이 더 닿거나 손바닥이면 손바닥 지우기.
        // 이미 넘기기·확대 중이어도 손바닥만큼 넓은 것이 닿으면 (손이 닿는 면이 점점 넓어지며 시작하는 경우) 확대를 멈추고 지우기로
        if (fingerDrawing && penPointerId == -1 && palmToolOk() &&
            ((action == MotionEvent.ACTION_POINTER_DOWN && ev.eventTime - ev.downTime < PALM_GATHER_MS && ev.pointerCount >= 3) ||
                ((action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_MOVE) && ev.pointerCount >= 2 && anyPalm(ev)))
        ) {
            cancelFingerGesture(ev)
            listener?.onPenDown()
            scroller.forceFinished(true)
            erased.clear()
            pieces.clear()
            penPointerId = ev.getPointerId(0)
            penIsFinger = true
            switchToPalm(ev)
            return true
        }

        fingerActive = action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL
        scaleDetector.onTouchEvent(ev)
        // 확대 중에도 넘겨 주어야 움직임 기준점이 따라와서 확대가 끝난 뒤 튀지 않는다 (onScroll은 확대 중이면 무시)
        gestureDetector.onTouchEvent(ev)
        if (!fingerActive) {
            penIsFinger = false
            addFooter.release(add = action == MotionEvent.ACTION_UP)
            scheduleDetail()
        }
        return true
    }

    // ================= 손바닥 지우기 =================

    /** 손바닥 지우기를 쓰는 도구 (펜·형광펜·보정 펜·지우개) */
    private fun palmToolOk() = palmErase && !readOnly &&
        (tool == Tool.PEN || tool == Tool.HIGHLIGHTER || tool == Tool.SHAPE || tool == Tool.ERASER)

    /** 지금 손가락 필기 중이라 손바닥으로 바꿀 수 있는지 */
    private fun palmReady() = fingerDrawing && penIsFinger && !palmErasing && palmToolOk()

    /** 보통 손가락이 닿는 크기 (최근 손가락 획들의 가운뎃값, 아직 모르면 9mm) */
    private fun fingerSize(): Float {
        if (fingerSizes.size >= 3) return fingerSizes.sorted()[fingerSizes.size / 2]
        return 9f * resources.displayMetrics.xdpi / 25.4f
    }

    /** 닿은 것 중에 손바닥만큼 넓은 것이 있는지 (보통 손가락의 2.5배 넘게) */
    private fun anyPalm(ev: MotionEvent): Boolean {
        val limit = max(fingerSize() * PALM_RATIO, 3f * resources.displayMetrics.xdpi / 25.4f)
        for (i in 0 until ev.pointerCount) {
            if (ev.getToolType(i) == MotionEvent.TOOL_TYPE_FINGER && ev.getTouchMajor(i) >= limit) return true
        }
        return false
    }

    /** 그리던 손가락 획을 버리고 손바닥 지우개로 */
    private fun switchToPalm(ev: MotionEvent) {
        shapeGen++
        shapePending = false
        shapePreview = null
        curStroke = null
        curPage = -1
        tapCandidate = false
        clearSelection()
        penErasing = true
        palmErasing = true
        palmMove(ev, -1, first = true)
    }

    /**
     * 손바닥 지우개를 닿은 손(손가락·손바닥 모두)의 가운데로 옮기며 지나간 길을 지운다.
     * 반지름은 닿은 범위를 다 덮도록. [leaving]은 막 떨어지는 손가락 (빼고 셈)
     */
    private fun palmMove(ev: MotionEvent, leaving: Int, first: Boolean = false) {
        var n = 0
        var cx = 0f
        var cy = 0f
        for (i in 0 until ev.pointerCount) {
            if (i == leaving) continue
            cx += ev.getX(i); cy += ev.getY(i); n++
        }
        if (n == 0) return
        cx /= n
        cy /= n
        var r = 0f
        for (i in 0 until ev.pointerCount) {
            if (i == leaving) continue
            r = max(r, hypot(ev.getX(i) - cx, ev.getY(i) - cy) + ev.getTouchMajor(i) / 2f)
        }
        palmRadius = r.coerceIn(PALM_MIN_DP * density, PALM_MAX_DP * density)
        if (first) {
            lastSx = cx
            lastSy = cy
        }
        val dist = hypot(cx - lastSx, cy - lastSy)
        val steps = max(1, (dist / (palmRadius / 2f)).toInt())
        for (k in (if (first) 0 else 1)..steps) {
            val x = lastSx + (cx - lastSx) * k / steps
            val y = lastSy + (cy - lastSy) * k / steps
            hitPage(x, y)?.let { eraseAt(it.first, it.second, it.third, palmRadius) }
        }
        lastSx = cx
        lastSy = cy
        invalidate()
    }

    private fun cancelFingerGesture(ev: MotionEvent) {
        val cancel = MotionEvent.obtain(ev)
        cancel.action = MotionEvent.ACTION_CANCEL
        scaleDetector.onTouchEvent(cancel)
        gestureDetector.onTouchEvent(cancel)
        cancel.recycle()
        fingerActive = false
        scaling = false
        addFooter.release(add = false)
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
        eraseFills = null
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
        if (tool == Tool.FILL && !penErasing && fillMode == FillMode.BUCKET) {
            fillPressing = true
            fillTap = hitPage(sx, sy)
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
                Tool.LASER -> Stroke(Tool.LASER, laserColor, laserWidthDp).also { laser.hold() }
                Tool.TAPE -> Stroke(Tool.TAPE, tapeColor, if (tapeRect) 0f else tapeWidth).also {
                    it.tape = TapeStyle(tapePattern, tapeRect)
                }
                // 자유 영역: 그리는 동안은 선만 (다 그리면 바깥 윤곽 안을 채운 획으로 바꾼다)
                Tool.FILL -> Stroke(Tool.FILL, fillColor, 0f)
                // 보정 펜은 그린 획을 맞춘 도형으로 바꾸므로 그리는 동안은 사인펜
                Tool.PEN -> Stroke(Tool.PEN, penColor, penWidth, pen = penStyle)
                else -> Stroke(Tool.PEN, penColor, penWidth)
            }
            lastPressure = p
            if (tool == Tool.PEN) {
                penInput.begin(penStyle, penWidth, penSmoothing, hit.second, hit.third, p, t)
                st.add(penInput.x, penInput.y, penInput.p)
            } else if (tool == Tool.FILL) {
                penInput.begin(PenStyle.FELT, 1f, fillSmoothing, hit.second, hit.third, p, t)
                st.add(penInput.x, penInput.y, 0f)
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
        if (fillPressing) {
            if (hypot(sx - tapDownSx, sy - tapDownSy) > TAP_SLOP_DP * density) fillTap = null
            return
        }
        if (rotating) {
            // 90° 배수(5° 안), 45° 배수(3° 안)에 딱 맞춘다
            rotDeg = StrokeGeometry.snapRotation(angleAt(sx, sy) - rotStart)
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
            val px = toPageX(selPage, sx)
            val py = toPageY(selPage, sy)
            when (resizeAxis) {
                1 -> scaleK = ((px - anchorX) * resizeDir / startDist).coerceIn(0.05f, 20f)
                2 -> scaleKy = ((py - anchorY) * resizeDir / startDist).coerceIn(0.05f, 20f)
                else -> {
                    scaleK = (hypot(px - anchorX, py - anchorY) / startDist).coerceIn(0.1f, 20f)
                    scaleKy = scaleK
                }
            }
            invalidate()
            return
        }
        if (lassoing) {
            val px = toPageX(lassoPage, sx)
            val py = toPageY(lassoPage, sy)
            if (lassoRect || lassoTap) {
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
            val end = StrokeGeometry.straightHighlighterEnd(st.x(0), st.y(0), px, py)
            st.keepFirst()
            st.add(end[0], end[1], st.p(0))
            lastSx = sx
            lastSy = sy
            invalidate()
            return
        }
        if (st.tape?.rect == true) {
            // 네모 테이프: 첫 점과 지금 점이 마주 보는 모서리
            val c = StrokeGeometry.rectTapeCorners(st.x(0), st.y(0), px, py)
            st.keepFirst()
            for (k in 0 until 3) st.add(c[k * 2], c[k * 2 + 1], 1f)
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
        if (st.tool == Tool.FILL) {
            // 자유 영역: 손떨림 보정한 점
            if (!penInput.move(px, py, p, t, scale / density, minDist, st.x(last), st.y(last))) return
            st.add(penInput.x, penInput.y, 0f)
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
        val dashed = shapeDashed
        shapeFitting = true
        shapePending = false
        shapeWorker.execute {
            val r = try { fitShape(copy, kind, guide, dashed, quick = true) } catch (e: Exception) { null }
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
        raw: Stroke, kind: ShapeKind = shapeKind, guide: GuideStyle = shapeGuide, dashed: Boolean = shapeDashed,
        quick: Boolean = false,
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
        // 화살표·길이 표시는 몸통을 고른 대로 실선·점선, 화살촉은 실선
        val out = fitted.curves.map { toStroke(it, dashed && kind.isGuideLine) }.toMutableList()
        fitted.heads.mapTo(out) { toStroke(it, false) }
        if (guide != GuideStyle.NONE) fitted.guides.mapTo(out) { toStroke(it, guide == GuideStyle.DASHED) }
        return out
    }

    private fun eraseAt(page: Int, px: Float, py: Float, radiusPx: Float = eraserRadiusDp * density) {
        val inkDoc = ink ?: return
        // 펼친 포스트잇 메모 아래는 보이지 않으므로 지우지 않는다
        if (expandedNoteAt(page, px, py) != null) return
        val r = radiusPx / scale
        val list = inkDoc.pages[page]
        var removed = false
        // 테이프 도구의 지우개는 테이프만 (보이게 한 테이프도), 채우기 도구의 지우개는 채우기만 지운다
        val tapesOnly = tool == Tool.TAPE && tapeErasing && !palmErasing
        val fillsOnly = tool == Tool.FILL && fillErasing && !palmErasing
        // 손바닥은 늘 닿은 부분만 지운다
        val mode = if (palmErasing) EraserMode.AREA else if (tapesOnly) tapeEraseMode else if (fillsOnly) fillEraseMode else eraserMode
        val hlOnly = eraseHlOnly && !palmErasing
        /** k번째 획을 지우거나 (영역 지우개면) 닿은 부분만 잘라 낸다. 바뀐 것이 없으면 false */
        fun eraseOne(k: Int, st: Stroke): Boolean {
            val rest = when {
                mode == EraserMode.STROKE -> emptyList()
                // 테이프·채우기는 지우개가 지나간 동그라미만큼 구멍을 뚫는다
                st.tape != null || st.fill != null ->
                    st.withHole(px, py, r)?.let { if (it.tapeGone()) emptyList() else listOf(it) } ?: return false
                else -> st.cut(px, py, r) ?: return false
            }
            list.removeAt(k)
            list.addAll(k, rest)
            // 이번에 잘라 넣은 조각을 다시 자르면 조각 목록에서만 뺀다 (원래 획이 아니므로)
            if (!pieces.removeAll { it.second === st }) erased.add(page to st)
            rest.forEach { pieces.add(page to it) }
            return true
        }
        val filter = EraseRules.Filter(tapesOnly, fillsOnly, hlOnly)
        // 획마다 닿았는지·가렸는지는 규칙이 필요로 할 때만 잰다 (람다)
        val targets = list.map { st ->
            EraseRules.Target(
                id = st, isBox = st.isBox, isNote = st.note != null, isTape = st.tape != null, isFill = st.fill != null,
                isHighlighter = st.tool == Tool.HIGHLIGHTER, revealed = st.revealed,
                coversPoint = { st.tapeContains(px, py) }, hit = { st.hitTest(px, py, r) },
            )
        }
        val result = EraseRules.apply(
            targets, filter,
            when (eraseFills) { null -> EraseRules.FillDecision.UNDECIDED; true -> EraseRules.FillDecision.ERASE; else -> EraseRules.FillDecision.KEEP },
            object : EraseRules.Eraser {
                // 앞서 자른 획이 번호를 밀 수 있어 (채우기는 나중에 지우므로) 지금 위치를 다시 찾는다
                override fun erase(id: Any): Boolean {
                    val st = id as Stroke
                    val k = list.indexOf(st)
                    return k >= 0 && eraseOne(k, st)
                }
            },
        )
        eraseFills = when (result.decision) {
            EraseRules.FillDecision.UNDECIDED -> null
            EraseRules.FillDecision.ERASE -> true
            EraseRules.FillDecision.KEEP -> false
        }
        if (result.removed) removed = true
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
        if (fillPressing) {
            fillPressing = false
            val hit = fillTap
            fillTap = null
            penPointerId = -1
            penErasing = false
            tapCandidate = false
            if (commit && hit != null) fillCtl.bucketFill(hit.first, hit.second, hit.third)
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
            if (commit && inkDoc != null && (scaleK != 1f || scaleKy != 1f)) {
                val kx = scaleK
                val ky = scaleKy
                inkDoc.edit(selection) {
                    selection.forEach { if (kx == ky) it.scale(kx, anchorX, anchorY) else it.scaleXY(kx, ky, anchorX, anchorY) }
                }
                select(selPage, selection)
            }
            resizing = false
            scaleK = 1f
            scaleKy = 1f
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
                    } else if (st.tool == Tool.FILL) {
                        fillCtl.finishFreeFill(curPage, st)
                    } else if (st.tool == Tool.LASER) {
                        // 레이저: 필기에 넣지 않고 잠깐 보였다가 사라진다
                        laser.release(curPage, st)
                    } else if (st.tool == Tool.PEN && scribbleErase && eraseScribbled(curPage, st)) {
                        // 긁어 지우기: 긁은 획은 남기지 않고 그 아래를 지웠다
                    } else {
                        if (st.tool == Tool.PEN) penInput.finish(st)
                        inkDoc.add(curPage, st)
                    }
                }
            }
            shapePreview = null
        }
        // 손바닥이 아니었던 손가락 획으로 보통 손가락 크기를 익힌다
        if (penIsFinger && !palmErasing && penMaxMajor > 0f) {
            fingerSizes.addLast(penMaxMajor)
            while (fingerSizes.size > 15) fingerSizes.removeFirst()
        }
        penMaxMajor = 0f
        erased.clear()
        pieces.clear()
        curStroke = null
        curPage = -1
        penPointerId = -1
        penErasing = false
        palmErasing = false
        tapCandidate = false
        invalidate()
    }

    /**
     * 긁어 지우기: [st]가 좌우로 마구 긁은 획이면 그 영역에 닿은 필기(펜·형광펜)를 지우고 true.
     * 긁은 모양이 아니거나 아래에 지울 필기가 없으면 false (보통 획으로 남긴다). 그림·글·테이프는 그대로
     */
    private fun eraseScribbled(page: Int, st: Stroke): Boolean {
        val inkDoc = ink ?: return false
        val region = ScribbleRegion.detect(st, density / scale) ?: return false
        val list = inkDoc.pages[page]
        val gone = ArrayList<Pair<Int, Stroke>>()
        val added = ArrayList<Pair<Int, Stroke>>()
        val step = 0.75f * density / scale
        // 먼저 잘라 보기만 한다 (뒤에서부터라 고칠 때 앞 번호가 안 밀림)
        val cuts = ArrayList<Triple<Int, Stroke, List<Stroke>>>()
        var crossed = 0
        for (k in list.indices.reversed()) {
            val s = list[k]
            if (s.isBox || s.tape != null || s.fill != null || s.note != null) continue
            val hw = s.halfWidth
            val rest = s.cutWhere(step) { x, y -> region.contains(x, y, hw) } ?: continue
            cuts.add(Triple(k, s, rest))
            if (crossed < SCRIBBLE_CROSSINGS) crossed += crossings(st, s, SCRIBBLE_CROSSINGS - crossed)
        }
        // 정말 긁었으면 아래 글씨를 여러 번 가로지른다. 옆 글자에 살짝 닿은 것 ('ㅗㅇ'을 이어 쓰다 'ㅇ'에 닿음)은 아님
        if (crossed < SCRIBBLE_CROSSINGS) return false
        for ((k, s, rest) in cuts) {
            list.removeAt(k)
            list.addAll(k, rest)
            gone.add(page to s)
            rest.forEach { added.add(page to it) }
        }
        inkDoc.erased(gone, added)
        performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        return true
    }

    // ================= 올가미 선택 =================

    /** 화면 좌표가 선택 상자나 손잡이 위인지 */
    private fun onSelection(sx: Float, sy: Float) =
        selection.isNotEmpty() && (selectionScreenRect(RectF()).contains(sx, sy) || handleAt(sx, sy) >= 0 || rotateHandleHit(sx, sy))

    /**
     * 누른 곳에 있는 손잡이: 0 왼쪽 위, 1 오른쪽 위, 2 왼쪽 아래, 3 오른쪽 아래,
     * 4 위 변, 5 아래 변, 6 왼쪽 변, 7 오른쪽 변, 없으면 -1 (모서리가 먼저)
     */
    private fun handleAt(sx: Float, sy: Float): Int {
        if (selection.isEmpty()) return -1
        val r = selectionScreenRect(RectF())
        return SelectionHit.handleAt(r.left, r.top, r.right, r.bottom, sx, sy, HANDLE_TOUCH_DP * density)
    }

    /** 변 가운데 손잡이를 둘 만큼 상자가 넓은지 (위·아래 변, 왼쪽·오른쪽 변). 작으면 모서리 손잡이와 겹친다 */
    private fun sideHandles(r: RectF): Pair<Boolean, Boolean> = SelectionHit.sideHandles(r.width(), r.height(), HANDLE_TOUCH_DP * density)

    private fun startLasso(sx: Float, sy: Float) {
        val handle = handleAt(sx, sy)
        if (lassoTap && handle < 0 && !rotateHandleHit(sx, sy)) {
            // 대상 선택: 누른 것을 고르고 그대로 끌면 옮긴다
            val hit = hitPage(sx, sy)
            val obj = hit?.let { objectAt(it.first, it.second, it.third) }
            if (hit != null && obj != null) {
                if (obj !in selSet || selPage != hit.first) select(hit.first, listOf(obj))
                moving = true
                moveStartX = toPageX(selPage, sx)
                moveStartY = toPageY(selPage, sy)
                moveDx = 0f
                moveDy = 0f
                invalidate()
                return
            }
        }
        if (rotateHandleHit(sx, sy)) {
            // 회전 손잡이: 선택 영역 가운데를 중심으로 돌리기
            rotCx = selBounds.centerX()
            rotCy = selBounds.centerY()
            rotStart = angleAt(sx, sy)
            rotDeg = 0f
            rotating = true
        } else if (handle in 0..3) {
            // 모서리 손잡이: 반대쪽 모서리를 기준으로 크기 조절
            val b = selBounds
            val left = handle == 0 || handle == 2
            val top = handle == 0 || handle == 1
            anchorX = if (left) b.right else b.left
            anchorY = if (top) b.bottom else b.top
            startDist = max(hypot((if (left) b.left else b.right) - anchorX, (if (top) b.top else b.bottom) - anchorY), 1f)
            scaleK = 1f
            scaleKy = 1f
            resizeAxis = 0
            resizing = true
        } else if (handle >= 4) {
            // 변 가운데 손잡이: 맞은편 변을 기준으로 위아래로만(4·5) 또는 옆으로만(6·7)
            val b = selBounds
            resizeAxis = if (handle <= 5) 2 else 1
            anchorX = when (handle) { 6 -> b.right; 7 -> b.left; else -> b.centerX() }
            anchorY = when (handle) { 4 -> b.bottom; 5 -> b.top; else -> b.centerY() }
            val edge = when (handle) { 4 -> b.top - anchorY; 5 -> b.bottom - anchorY; 6 -> b.left - anchorX; else -> b.right - anchorX }
            resizeDir = if (edge < 0f) -1f else 1f
            startDist = max(abs(edge), 1f)
            scaleK = 1f
            scaleKy = 1f
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
                // 네모 선택(대상 선택의 빈 곳 끌기도)은 [시작점, 지금 점] 두 개만 쓴다
                if (lassoRect || lassoTap) addLassoPoint(hit.second, hit.third)
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
            (boxAt(lassoPage, lasso[0], lasso[1]) ?: tapeAt(lassoPage, lasso[0], lasso[1]) ?: fillAt(lassoPage, lasso[0], lasso[1]))
                ?.let { select(lassoPage, listOf(it)) }
            return
        }
        if (lassoRect || lassoTap) {
            // 두 점을 네 모서리로 바꿔 자유 선택과 같은 규칙으로 고른다
            val x0 = min(lasso[0], lasso[2]); val y0 = min(lasso[1], lasso[3])
            val x1 = max(lasso[0], lasso[2]); val y1 = max(lasso[1], lasso[3])
            if ((x1 - x0) * scale < 4 * density || (y1 - y0) * scale < 4 * density) return
            lassoCount = 0
            addLassoPoint(x0, y0); addLassoPoint(x1, y0); addLassoPoint(x1, y1); addLassoPoint(x0, y1)
        }
        if (lassoCount < 3) return
        val picked = SelectionHit.pickByLasso(inkDoc.pages[lassoPage], lasso, lassoCount)
        if (picked.isEmpty()) return
        select(lassoPage, picked)
    }

    /** 획들을 선택하고 선택 상자를 계산한다 */
    private fun select(page: Int, picked: List<Stroke>) {
        selPage = page
        selection = picked
        selSet = picked.toHashSet()
        val box = SelectionHit.boundsOf(picked)
        if (box != null) selBounds.set(box[0], box[1], box[2], box[3]) else selBounds.setEmpty()
    }

    /**
     * 대상 선택: 쪽 좌표 (x, y)에 닿는 맨 위의 것 (획·글·테이프, 없으면 그림).
     * 필기와 글은 그림 위에 그려지므로 먼저 본다. 가는 획도 고르기 쉽게 손가락 폭만큼 여유를 둔다
     */
    private fun objectAt(page: Int, x: Float, y: Float): Stroke? {
        val list = ink?.pages?.getOrNull(page) ?: return null
        return SelectionHit.objectAt(list, x, y, TAP_SELECT_DP * density / scale)
    }

    private fun boxContains(st: Stroke, x: Float, y: Float) = SelectionHit.quadContains(st, x, y)

    /** 쪽 좌표 (x, y)를 덮고 있는 맨 위 그림·글 ([textOnly]면 글만) */
    private fun boxAt(page: Int, x: Float, y: Float, textOnly: Boolean = false): Stroke? {
        val list = ink?.pages?.getOrNull(page) ?: return null
        return SelectionHit.boxAt(list, x, y, textOnly)
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

    /** 링크 단 글을 지금 보는 쪽의 화면 가운데에 넣고 고른다 (바로 옮길 수 있게) */
    fun insertLink(text: InkText, color: Int, url: String): Boolean {
        val inkDoc = ink ?: return false
        if (sizes.isEmpty()) return false
        val page = currentPage().coerceIn(0, sizes.lastIndex)
        val pw = sizes[page].width
        val ph = sizes[page].height
        val w = min(text.boxW.toFloat(), pw)
        val h = text.boxH * (w / text.boxW)
        val cx = ((offX + width / 2f) / scale - lefts[page]).coerceIn(w / 2, pw - w / 2)
        val cy = ((offY + height / 2f) / scale - tops[page]).coerceIn(h / 2, max(h / 2, ph - h / 2))
        val st = Stroke(Tool.PEN, color, 0f).apply {
            this.text = text
            link = url
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
        if (tapeStraight) TapeFit.straighten(st)
        if (tapeFitText) fitTapeToText(page, st)
        return true
    }

    /**
     * 펜 테이프를 아래 글자 줄에 맞춘다. 쪽 그림(PDF + 필기)을 테이프 둘레만 흑백으로 그려 두고
     * 어두운 점이 어디인지를 [TapeFit]에 넘긴다 (줄을 찾는 계산은 거기서). 글자가 없으면 그대로 둔다
     */
    private fun fitTapeToText(page: Int, st: Stroke) {
        val inkDoc = ink ?: return
        val base = baseCache.get(page) ?: return
        val pw = sizes[page].width
        val ph = sizes[page].height
        val k = base.width / pw
        // 찾는 범위: 테이프 가운데에서 위·아래로 FIT_RANGE pt
        val range = TapeFit.FIT_RANGE
        var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
        for (i in 0 until st.count) {
            l = min(l, st.x(i)); r = max(r, st.x(i)); t = min(t, st.y(i)); b = max(b, st.y(i))
        }
        l = max(0f, l - range); t = max(0f, t - range)
        r = min(pw, r + range); b = min(ph, b + range)
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
            for (s in inkDoc.pages[page]) if (s.image == null && s.tape == null && s.fill == null && s.note == null) drawInkStroke(c, paint, s)
            bmp.getPixels(px, 0, bw, 0, 0, bw, bh)
        } finally {
            bmp.recycle()
        }
        TapeFit.fitToText(st) { x, y ->
            val ix = ((x - l) * k).toInt()
            val iy = ((y - t) * k).toInt()
            if (ix !in 0 until bw || iy !in 0 until bh) return@fitToText false
            val c = px[iy * bw + ix]
            val lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000
            lum < 160
        }
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

    // ================= 채우기 =================

    /** 쪽 좌표 (x, y)를 덮고 있는 맨 위 채우기 */
    private fun fillAt(page: Int, x: Float, y: Float): Stroke? =
        ink?.pages?.getOrNull(page)?.lastOrNull { it.fillContains(x, y) }

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
        val c = PlacementMath.upright(left, top, text.boxW.toFloat(), text.boxH.toFloat())
        val st = Stroke(Tool.PEN, color, 0f).apply {
            this.text = text
            for (i in 0 until 4) add(c[i * 2], c[i * 2 + 1], 1f)
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
        val oldCorners = FloatArray(8) { if (it % 2 == 0) old.x(it / 2) else old.y(it / 2) }
        val c = PlacementMath.retargetTextBox(
            oldCorners, oldText.boxW.toFloat(), oldText.boxH.toFloat(), text.boxW.toFloat(), text.boxH.toFloat(),
        )
        val st = Stroke(Tool.PEN, color, 0f).apply {
            this.text = text
            for (i in 0 until 4) add(c[i * 2], c[i * 2 + 1], 1f)
            link = old.link
        }
        inkDoc.replace(page, old, st)
        invalidate()
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
        scaleKy = 1f
        invalidate()
    }

    /** 보고 있는 쪽의 필기(hlOnly면 형광펜만)를 모두 지운다 (실행 취소 가능). 지운 획 수 */
    fun clearPage(hlOnly: Boolean): Int {
        val inkDoc = ink ?: return 0
        val page = currentPage().takeIf { it >= 0 } ?: return 0
        val targets = inkDoc.pages[page].filter { !it.isBox && it.note == null && (!hlOnly || it.tool == Tool.HIGHLIGHTER) }
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
        clipboard = selection.map { it.copy().apply { role = null; translate(-b.left, -b.top) } }
        clipSize.set(0f, 0f, b.width(), b.height())
    }

    /** 복사해 둔 획을 지금 보이는 페이지 가운데에 붙이고 선택한다 (실행 취소 가능) */
    fun pasteClipboard() {
        val inkDoc = ink ?: return
        if (clipboard.isEmpty() || sizes.isEmpty()) return
        val page = currentPage()
        val at = PlacementMath.pasteOrigin(
            offX, offY, width.toFloat(), height.toFloat(), scale,
            lefts[page], tops[page], sizes[page].width, sizes[page].height, clipSize.width(), clipSize.height(),
        )
        val copies = clipboard.map { it.copy().apply { role = null; translate(at[0], at[1]) } }
        clearSelection()
        inkDoc.addAll(page, copies)
        select(page, copies)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        removeCallbacks(detailRunnable)
        scrollBar.cancel()
        laser.cancel()
        zoomAnimator?.cancel()
        scope.cancel()
    }

    companion object {
        private const val TAG = "DocumentView"
        private const val MIN_ZOOM = 0.1f
        private const val MAX_ZOOM = 6f
        private const val SEL_COLOR = 0xFF1E6FD9.toInt()
        /** 크기 조절 손잡이 반지름(그리기)과 누르는 범위 */
        private const val HANDLE_DP = 7f
        private const val HANDLE_TOUCH_DP = 24f
        /** 대상 선택: 획에서 이만큼(dp) 떨어져 눌러도 고른다 */
        private const val TAP_SELECT_DP = 12f
        /** 글 도구: 이보다 많이 움직이면 톡 누르기가 아니다 (dp) */
        private const val TAP_SLOP_DP = 12f
        /** 테이프를 톡 누른 것으로 보는 시간 (ms) */
        private const val TAP_MS = 500L
        /** 손바닥: 보통 손가락보다 이만큼 넓게 닿으면 */
        private const val PALM_RATIO = 2.5f
        /** 긁어서 지우기: 긁은 선이 아래 필기를 적어도 이만큼 가로질러야 지운다 */
        private const val SCRIBBLE_CROSSINGS = 3
        /** 손바닥: 처음 닿고 이 안에 손가락 셋 넘게 닿으면 (ms) */
        private const val PALM_GATHER_MS = 250L
        private const val PALM_MIN_DP = 24f
        private const val PALM_MAX_DP = 220f
        /** 회전 손잡이 반지름과 상자에서 떨어진 거리 */
        private const val ROT_HANDLE_DP = 14f
        private const val ROT_OFFSET_DP = 34f
        private const val SPEN_DOWN = 211
        private const val SPEN_UP = 212
        private const val SPEN_MOVE = 213
    }
}
