package com.dsviewer.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
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
        /** 자를 켜거나 껐다 (툴바의 자 단추 표시를 맞춘다) */
        fun onRulerChanged() {}
        /** 펜 버튼 동작으로 도구를 바꿔야 함 (툴바 표시도 따라가도록 뷰어가 처리) */
        fun onPenButtonTool(tool: Tool) {}
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
        /** 오답 배지·'원문 보기'를 누름 (쪽의 (x, y)) */
        fun onWrongTap(page: Int, x: Float, y: Float) {}
        /** 필기 모드에서 손가락으로 톡 누름 (손가락으로 쓰기를 꺼 두었을 때): 오답 배지·링크를 따라간다 */
        fun onFingerTap(page: Int, x: Float, y: Float) {}
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
        /** 영역 스크린샷으로 찍을 영역을 골랐다 (쪽 번호, 그 쪽 좌표의 영역) */
        fun onShotPicked(page: Int, rect: RectF) {}
        /** 오답 영역 고르기가 끝남 (골랐거나 취소). 안내를 닫을 때 */
        fun onWrongPickEnded() {}
    }

    var listener: Listener? = null

    // ---- 도구 설정 ----
    var tool = Tool.PEN
        set(v) {
            if (field != v && !quietTool) previousTool = field
            field = v
            if (v != Tool.LASSO) {
                buttonLassoReturn = null
                clearSelection()
            }
        }
    /** 직전에 쓰던 도구 (펜 버튼 '직전 도구로 전환'에서 번갈아 쓴다) */
    private var previousTool = Tool.PEN
    /** 도구를 잠깐 바꿨다 돌아올 때는 직전 도구로 치지 않는다 */
    private var quietTool = false
    /** 펜을 눕혀 쥔 만큼 연필·붓펜이 넓게 칠해지게 한다 (옵션) */
    var penTilt = true
    /** 지금 들어온 점의 펜 기울기 (라디안) */
    private var curTilt = 0f
    /** 펜 버튼 동작: 버튼을 누른 채 쓸 때 (지우개·올가미 선택·레이저·직전 도구 전환) */
    internal var penButtonAction = PenInputRules.ButtonAction.ERASER
    /** 펜 버튼으로 올가미를 켰다면 선택을 마친 뒤 돌아갈 도구 */
    private var buttonLassoReturn: Tool? = null
    /** 펜 버튼으로 이번 획만 바꾼 도구 (획이 끝나면 되돌린다) */
    private var buttonTempTool: Tool? = null
    /** 지금 펜 버튼을 누른 채 톡 치는지 보는 중 (끌면 아무것도 안 한다) */
    private var penSwapping = false
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
    /** 글자 기반 주석 (옵션): 형광펜으로 PDF 글자를 끌면 글줄에 맞춰 칠하기·밑줄·취소선·복사. NONE이면 보통 형광펜 */
    var textMark = TextMark.NONE
    /** 쪽의 PDF 글자를 돌려준다 (아직 못 읽었으면 null). 뷰어가 건다 */
    var textProvider: ((Int) -> PageText?)? = null
    /** 글자 기반 주석의 짧은 안내 (토스트). 뷰어가 건다 */
    var textSay: ((String) -> Unit)? = null
    /** 지금 화면에 놓인 쪽 수 */
    val pageTotal get() = sizes.size
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
                ruler.hide()
            }
            // 마지막 쪽 아래 '빈 쪽 추가' 단추는 읽기 모드에서 숨긴다 (그만큼 스크롤 길이가 바뀐다)
            if (doc != null) {
                clamp()
                invalidate()
            }
        }
    /**
     * 필기 숨기기 (읽기 모드에서만 뜻이 있다): 원래 문서만 보인다. 필기·그림·글 상자·포스트잇·오답 배지·테이프가 다 가려지고,
     * 그 위의 터치도 문서로 넘어간다. 파일에는 아무 영향이 없다. 쓰기 모드에서는 안 보이는 채로 쓰는 일이 없게 늘 보인다
     */
    var inkHidden = false
        set(v) {
            if (field == v) return
            field = v
            invalidate()
        }
    private val hideInk get() = inkHidden && readOnly

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
    /**
     * 가로 넘김: 쪽을 왼쪽에서 오른쪽으로 한 줄에 늘어놓고, 한 쪽(양쪽 보기면 두 쪽)이 화면에 꼭 맞게 보인다.
     * 옆으로 쓸면 한 쪽씩 넘어가고, 확대하면 쪽 안을 자유롭게 움직인다. 끄면 세로로 이어 스크롤한다 (기본)
     */
    var horizontal = false
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
    private val textMarks = TextMarkController(this, object : TextMarkController.Host {
        override val ink get() = this@DocumentView.ink
        override val scale get() = this@DocumentView.scale
        override val textMark get() = this@DocumentView.textMark
        override val hlColor get() = this@DocumentView.hlColor
        override val penColor get() = this@DocumentView.penColor
        override val penWidth get() = this@DocumentView.penWidth
        override fun textOf(page: Int) = textProvider?.invoke(page)
        override fun say(msg: String) { textSay?.invoke(msg) }
        override fun copyText(text: String) {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            cm.setPrimaryClip(android.content.ClipData.newPlainText("DSnote", text))
        }
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
        // 마지막 쪽 아래로 끌어 올리는 동작은 세로 스크롤에서만 (가로 넘김에서는 쪽 관리·삽입 메뉴로)
        override val readOnly get() = this@DocumentView.readOnly || horizontal
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

    /**
     * 분할 보기: 다른 칸 문서 화면의 도구 설정(도구·색·굵기·지우개·테이프·채우기·선택 방식 …)과 복사해 둔 획을 이어받는다.
     * 칸을 바꿔 눌러도 툴바가 가리키는 설정이 그대로 이어지게 한다 (읽기 모드·양쪽 보기는 뷰어가 모든 칸에 같이 건다)
     */
    fun copyToolsFrom(o: DocumentView) {
        if (o === this) return
        if (tool != o.tool) tool = o.tool
        penColor = o.penColor; penWidth = o.penWidth; penStyle = o.penStyle; penSmoothing = o.penSmoothing
        hlColor = o.hlColor; hlWidth = o.hlWidth; hlStraight = o.hlStraight; textMark = o.textMark
        eraserRadiusDp = o.eraserRadiusDp; eraserMode = o.eraserMode; eraseHlOnly = o.eraseHlOnly
        scribbleErase = o.scribbleErase; palmErase = o.palmErase; fingerDrawing = o.fingerDrawing; horizontal = o.horizontal
        laserColor = o.laserColor; laserWidthDp = o.laserWidthDp; laserFadeMs = o.laserFadeMs
        shapeKind = o.shapeKind; shapeGuide = o.shapeGuide; shapeDashed = o.shapeDashed
        tapeColor = o.tapeColor; tapeWidth = o.tapeWidth; tapePattern = o.tapePattern; tapeRect = o.tapeRect
        tapeStraight = o.tapeStraight; tapeFitText = o.tapeFitText; tapeErasing = o.tapeErasing; tapeEraseMode = o.tapeEraseMode
        fillColor = o.fillColor; fillPattern = o.fillPattern; fillMode = o.fillMode; fillSmoothing = o.fillSmoothing
        fillPolygon = o.fillPolygon; fillErasing = o.fillErasing; fillEraseMode = o.fillEraseMode
        lassoRect = o.lassoRect; lassoTap = o.lassoTap
        penButtonAction = o.penButtonAction
        penTilt = o.penTilt
        noteColor = o.noteColor
        clipboard = o.clipboard
        clipSize.set(o.clipSize)
    }
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
        ruler.hide()
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
        ruler.hide()
        cancelRendering()
        doc = d
        ink = inkDoc
        sizes = d.sizes
        notes.reset()
        addFooter.reset()
        rightMargin = if (inkDoc.hasMarginItems()) PAGE_SIDE_MARGIN else 0f
        layoutPages()
        zoom = 1f
        baseCache.evictAll()
        details = emptyList()
        if (width > 0) {
            baseScale = fitScale()
            offY = 0f
            if (state != null) {
                zoom = state.zoom.coerceIn(MIN_ZOOM, MAX_ZOOM)
                offX = state.offX
                offY = state.offY
            } else if (pageLayout.horizontal) offX = pageLayout.offsetForUnit(0, scale, width.toFloat())
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
            rightMargin, twoPage, width, height, horizontal,
        )
    }

    /** 쪽 배치에 맞는 기본 배율 (확대 1배): 세로 스크롤은 문서 너비, 가로 넘김은 한 칸이 통째로 보이게 */
    private fun fitScale() = pageLayout.fitScale(width, height)

    /** 쪽 배치를 다시 하고, 보던 쪽이 화면 위에 오도록 */
    private fun relayout() {
        if (doc == null || width == 0) return
        val page = currentPage()
        layoutPages()
        baseScale = fitScale()
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
        if (pageLayout.horizontal) {
            // 가로 넘김은 쪽 맞춤 배율이 너비·높이에 다 달려 있어 너비가 바뀔 때(회전·분할 보기)만 다시 맞춘다.
            // 높이만 바뀌면(툴바·읽기 모드·화면 키보드) 쪽 크기는 그대로 두어 화면이 출렁이지 않게
            if (oldw != w) relayout() else {
                clamp()
                invalidate()
            }
            return
        }
        // 화면 가운데에 있던 문서 위치를 유지. 높이만 바뀌면(화면 키보드) 위쪽을 그대로 둔다
        val anchorY = if (oldw > 0) ViewportMath.centerDoc(offY, oldh.toFloat(), scale) else 0f
        baseScale = w / docW
        if (oldw != w) offY = if (oldw > 0) ViewportMath.offsetForCenter(anchorY, scale, h.toFloat()) else 0f
        clamp()
        // 너비가 같으면 배율도 같아 그려 둔 쪽 그림이 그대로 맞다. 높이만 바뀌는 때(툴바·읽기 모드·화면 키보드)에 비우면
        // 쪽이 한 순간 하얗게 비었다가 다시 그려져 화면이 깜빡인다
        if (oldw != w) {
            baseCache.evictAll()
            details = emptyList()
        }
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
    private fun contentH() = ViewportMath.contentHeight(docH, scale, addFooter.footerPx, bottomInset, topInset)

    private fun clamp() {
        offX = ViewportMath.clampX(offX, docW * scale, width.toFloat())
        offY = if (pageLayout.horizontal) ViewportMath.clampYFlip(offY, contentH(), height.toFloat(), topInset)
        else ViewportMath.clampY(offY, contentH(), height.toFloat(), topInset)
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
        if (sizes.isEmpty()) IntRange.EMPTY else pageLayout.visibleRange(offY, scale, height.toFloat(), offX, width.toFloat())

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
        baseScale = fitScale()
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
    private fun rowFirstPage(): Int =
        if (sizes.isEmpty()) -1 else pageLayout.rowFirstPage(offY, scale, height.toFloat(), offX, width.toFloat())

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
        offY = ViewportMath.offsetForPageTop(tops[page], gap, scale, topInset)
        if (pageLayout.horizontal) offX = pageLayout.offsetForUnit(pageLayout.unitOf(page), scale, width.toFloat())
        clamp()
        scheduleDetail()
        invalidate()
    }

    /** 화면 맨 위에 걸친 (쪽, 그 쪽 안 높이). 링크로 옮기기 전 자리를 기억해 둘 때 ([scrollToPageY]로 돌아온다) */
    fun topSpot(): Pair<Int, Float>? =
        if (sizes.isEmpty()) null else pageLayout.topSpot(offY, topInset, scale, offX, width.toFloat())

    /** 쪽 좌표 (x, y)에 있는 링크 단 글의 주소 (맨 위 것) */
    fun inkLinkAt(page: Int, x: Float, y: Float): String? = boxAt(page, x, y, textOnly = true)?.link

    /** [page]쪽의 높이 [y]가 화면 맨 위에 오도록 옮긴다 (목차 링크로 갈 때) */
    fun scrollToPageY(page: Int, y: Float) {
        if (page !in sizes.indices || width == 0) return
        scroller.forceFinished(true)
        zoomAnimator?.cancel()
        offY = ViewportMath.offsetForPageY(tops[page], y, sizes[page].height, gap, scale, topInset)
        if (pageLayout.horizontal) offX = pageLayout.offsetForUnit(pageLayout.unitOf(page), scale, width.toFloat())
        clamp()
        scheduleDetail()
        invalidate()
    }

    /** [page]쪽의 (x, y)가 화면에 들어오도록 옮긴다 (찾은 글자로 갈 때). 위에서 1/3쯤에 오게 */
    fun scrollToPoint(page: Int, x: Float, y: Float) {
        if (page !in sizes.indices || width == 0) return
        scroller.forceFinished(true)
        zoomAnimator?.cancel()
        offY = ViewportMath.offsetForPointY(tops[page], y, scale, height.toFloat())
        offX = ViewportMath.offsetForPointX(offX, lefts[page], x, scale, width.toFloat())
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
            if (!hideInk) for (layer in 0..2) for (st in inkDoc.pages[i]) {
                if (inkLayer(st) != layer || st === hiddenStroke || (dragging && st in selSet) || st.isMarginItem()) continue
                drawStroke(canvas, st)
            }
            fillCtl.drawPending(canvas, i)
            textMarks.draw(canvas, i)
            picker.draw(canvas, i)
            if (dragging && !hideInk) {
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
            // 자는 붙여넣은 그림·필기보다 늘 맨 위에 (그은 선은 자 가장자리 바로 바깥에 놓여 자에 가려지지 않는다)
            ruler.draw(canvas, i)
            ruler.drawOverlay(canvas, i)
            canvas.restore()

            // 배지(점선으로 원문과 이어서)와 포스트잇: 쪽 오른쪽 바깥 여백까지 그린다. 포스트잇은 맨 위에
            canvas.save()
            canvas.clipRect(r.left, r.top, r.right + rightMargin * s, r.bottom)
            canvas.translate(r.left, r.top)
            canvas.scale(s, s)
            if (!hideInk) notes.drawMarginItems(canvas, inkDoc, i, (moving || resizing || rotating) && selPage == i)
            canvas.restore()
        }
        addFooter.drawButton(canvas)
        canvas.restore()
        if (addFooter.pullPx > 0f) addFooter.drawPullPage(canvas)
        drawSelection(canvas)
        if (!range.isEmpty()) {
            // 앞뒤 한 페이지 미리 그리기
            for (p in ViewportMath.prefetchPages(range.first, range.last, sizes.size)) if (baseCache.get(p) == null) requestBase(p)
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
    // 입력·그리기는 StickyNoteController가 한다 (접고 펼치기, 끌어 옮기기, 색 고르기 칸, 붙일 곳 고르기, 오답 배지).

    private val notes: StickyNoteController = StickyNoteController(this, object : StickyNoteController.Host {
        override val ink get() = this@DocumentView.ink
        override val readOnly get() = this@DocumentView.readOnly
        override val scale get() = this@DocumentView.scale
        override val rightMargin get() = this@DocumentView.rightMargin
        override val pageCount get() = sizes.size
        override fun pageWidth(page: Int) = sizes[page].width
        override fun pageHeight(page: Int) = sizes[page].height
        override fun hitPage(sx: Float, sy: Float, wide: Boolean) = this@DocumentView.hitPage(sx, sy, wide)
        override fun isHidden(st: Stroke) = st === hiddenStroke
        override fun isSelected(st: Stroke) = st in selSet
        override fun clearSelection() = this@DocumentView.clearSelection()
        override fun stopFling() = scroller.forceFinished(true)
        override fun cancelZoomAnimation() { zoomAnimator?.cancel() }
        override fun forwardToGestures(ev: MotionEvent) { gestureDetector.onTouchEvent(ev) }
        override fun drawStroke(c: Canvas, st: Stroke) = this@DocumentView.drawStroke(c, st)
        override fun onNotePlacementEnded() { listener?.onNotePlacementEnded() }
        override fun onNoteEdit(page: Int, st: Stroke) { listener?.onNoteEdit(page, st) }
        override fun onWrongTap(page: Int, x: Float, y: Float) { listener?.onWrongTap(page, x, y) }
        override fun onNoteColorPicked(color: Int) { listener?.onNoteColorPicked(color) }
    }, SEL_COLOR)

    /** 새 메모 색 (마지막으로 고른 색) */
    var noteColor: Int
        get() = notes.color
        set(v) { notes.color = v }

    /** 포스트잇 붙일 곳 고르기를 시작한다 (끝나면 [Listener.onNotePlacementEnded]) */
    fun startNotePlacement(): Boolean = notes.startPlacement()

    fun cancelNotePlacement() = notes.cancelPlacement()

    /** 메모 글 고치기 (실행 취소 가능) */
    fun setNoteText(page: Int, old: Stroke, text: String) = notes.setText(page, old, text)

    /** 메모를 누른 동작 (손가락·펜 모두). 오답 영역을 고르는 중이면 그 입력으로. 메모가 아닌 곳에서 시작했으면 false */
    private fun handleNoteTouch(ev: MotionEvent): Boolean =
        if (hideInk) false else if (wrongPicking) picker.onTouch(ev) else notes.onTouch(ev)

    // ================= 자 · 눈금자 · 각도기 =================
    // 그리기·손가락으로 옮기기·가장자리 붙이기는 RulerController가 한다. 여기서는 쪽 배치를 읽게 해 주고 펜 입력에 이어 준다.

    internal val ruler: RulerController = RulerController(this, object : RulerController.Host {
        override val scale get() = this@DocumentView.scale
        override val pageCount get() = sizes.size
        override fun pageWidth(page: Int) = sizes[page].width
        override fun pageHeight(page: Int) = sizes[page].height
        override fun currentPage() = this@DocumentView.currentPage()
        override fun centerOrigin(page: Int, w: Float, h: Float) = PlacementMath.pasteOrigin(
            offX, offY, width.toFloat(), height.toFloat(), scale,
            lefts[page], tops[page], sizes[page].width, sizes[page].height, w, h,
        )
        override fun pageX(page: Int, sx: Float) = toPageX(page, sx)
        override fun pageY(page: Int, sy: Float) = toPageY(page, sy)
        override fun stopFling() = scroller.forceFinished(true)
        override fun onRulerChanged() { listener?.onRulerChanged() }
    })

    /** 지금 긋는 획이 자 가장자리를 따라가는 중이면 그 붙임 */
    private var rulerSnap: RulerController.Snap? = null

    // ================= 오답 영역 고르기 =================
    // 네모를 끌어 고르는 일은 WrongPicker가 한다. 여기서는 시작할 때 다른 입력 모드를 정리해 준다.

    private val picker: WrongPicker = WrongPicker(this, object : WrongPicker.Host {
        override val scale get() = this@DocumentView.scale
        override val offsetX get() = offX
        override val offsetY get() = offY
        override val pageCount get() = sizes.size
        override fun pageWidth(page: Int) = sizes[page].width
        override fun pageHeight(page: Int) = sizes[page].height
        override fun pageLeft(page: Int) = lefts[page]
        override fun pageTop(page: Int) = tops[page]
        override fun hitPage(sx: Float, sy: Float) = this@DocumentView.hitPage(sx, sy)
        override fun stopFling() = scroller.forceFinished(true)
        override fun switchToPinch(ev: MotionEvent) {
            // 제스처 감지기는 첫 손가락의 DOWN을 못 받았으므로 지금 자리에서 새로 시작
            fingerActive = true
            val down = MotionEvent.obtain(ev)
            down.action = MotionEvent.ACTION_DOWN
            scaleDetector.onTouchEvent(down)
            gestureDetector.onTouchEvent(down)
            down.recycle()
            scaleDetector.onTouchEvent(ev)
            gestureDetector.onTouchEvent(ev)
        }
        override fun onPickEnded() { listener?.onWrongPickEnded() }
        override fun onPicked(page: Int, rect: RectF) {
            if (pickForShot) listener?.onShotPicked(page, rect) else listener?.onWrongPicked(page, rect)
        }
    })

    /** 지금 고르는 네모가 오답이 아니라 영역 스크린샷용인가 */
    private var pickForShot = false

    /** 오답 영역을 고르는 중 */
    val wrongPicking get() = picker.picking

    /** 오답 영역 고르기를 시작한다 (끝나면 [Listener.onWrongPickEnded]). [forShot]이면 고른 영역은 영역 스크린샷으로 간다 */
    fun startWrongPick(forShot: Boolean = false): Boolean {
        if (ink == null || readOnly) return false
        clearSelection()
        notes.reset()
        pickForShot = forShot
        picker.start()
        return true
    }

    fun cancelWrongPick() = picker.cancel()

    /** 골라 둔 영역 표시를 지운다 */
    fun clearWrongRect() = picker.clear()

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
        val p = SelectionTransform.rotateHandle(
            floatArrayOf(rect.left, rect.top, rect.right, rect.bottom),
            ROT_OFFSET_DP * density, ROT_HANDLE_DP * density, height - bottomInset, 4f * density,
        )
        return p[0] to p[1]
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
        val b = SelectionTransform.previewBounds(
            floatArrayOf(selBounds.left, selBounds.top, selBounds.right, selBounds.bottom),
            resizing, anchorX, anchorY, scaleK, scaleKy, moveDx, moveDy,
        )
        return out.apply { set(b[0], b[1], b[2], b[3]) }
    }

    /** 선택 상자(여백 포함)를 화면 좌표로 */
    private fun selectionScreenRect(out: RectF): RectF {
        val b = previewBounds(previewRect)
        val r = SelectionTransform.toScreen(
            floatArrayOf(b.left, b.top, b.right, b.bottom), lefts[selPage], tops[selPage], scale, offX, offY, 6f * density,
        )
        return out.apply { set(r[0], r[1], r[2], r[3]) }
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
        return ViewportMath.baseRenderScale(zoom, baseScale, sz.width, sz.height)
    }

    private fun requestBase(i: Int) {
        if (baseJobs.containsKey(i)) return
        val d = doc ?: return
        val s = baseRenderScale(i)
        val wh = ViewportMath.bitmapSize(sizes[i].width, sizes[i].height, s)
        val w = wh[0]
        val h = wh[1]
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
            val v = ViewportMath.visibleRegion(r.left, r.top, r.right, r.bottom, width.toFloat(), height.toFloat(), s) ?: continue
            reqs.add(i to RectF(v[0], v[1], v[2], v[3]))
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
            val oldScale = scale
            val oldOffX = offX
            val oldOffY = offY
            zoom = (zoom * detector.scaleFactor).coerceIn(MIN_ZOOM, MAX_ZOOM)
            offX = ViewportMath.zoomOffset(oldOffX, lastFx, detector.focusX, oldScale, scale)
            offY = ViewportMath.zoomOffset(oldOffY, lastFy, detector.focusY, oldScale, scale)
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
            val beforeX = offX
            var dy = distanceY
            // 끌어 올려 둔 새 쪽 자리가 있으면 내릴 때 그것부터 접는다
            dy = addFooter.foldBack(dy)
            offX += distanceX
            offY += dy
            val want = offY
            clamp()
            // 마지막 쪽 끝에서 더 올리면 (뻑뻑하게) 새 쪽 자리를 끌어낸다
            if (dy > 0f && want > offY + 0.5f) addFooter.stretchPull(want, offY)
            noteScrolled(abs(offY - before) + abs(offX - beforeX))
            invalidate()
            return true
        }

        override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
            if (scaling) return false
            // 가로 넘김(확대 안 한 상태)에서는 굴러가지 않고 한 쪽 넘어간다
            if (snapsToPage()) {
                flipTo(PageLayout.flipTarget(nearestUnit(), nearestUnitCenter(), viewCenterDoc(), velocityX, pageLayout.unitCount))
                return true
            }
            val b = ViewportMath.flingBounds(offX, offY, docW * scale, contentH(), width.toFloat(), height.toFloat(), topInset)
            scroller.fling(offX.toInt(), offY.toInt(), -velocityX.toInt(), -velocityY.toInt(), b[0], b[1], b[2], b[3])
            postInvalidateOnAnimation()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            val target = ViewportMath.doubleTapTarget(zoom)
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
            // 필기 모드에서는 손가락으로 누를 때만 오답 배지·'원문 보기'와 링크를 따라간다 (펜은 필기)
            else if (!toggleTapeAt(e.x, e.y)) {
                hitPage(e.x, e.y)?.let {
                    if (readOnly) listener?.onReadTap(it.first, it.second, it.third)
                    else if (e.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) listener?.onFingerTap(it.first, it.second, it.third)
                }
            }
            return true
        }
    })

    // ---- 가로 넘김: 쪽 맞추기 ----

    /** 가로 넘김이고 확대하지 않은 상태라 손을 떼면 한 쪽(칸)에 맞춰 멈추는지 */
    private fun snapsToPage() = pageLayout.horizontal && doc != null && width > 0 && zoom <= SNAP_ZOOM

    private fun nearestUnit() = pageLayout.nearestUnit(offX, scale, width.toFloat())
    private fun nearestUnitCenter() = pageLayout.unitCenter(nearestUnit())
    private fun viewCenterDoc() = (offX + width / 2f) / scale

    /** 칸 [unit]이 화면 가운데에 오도록 부드럽게 옮긴다 */
    private fun flipTo(unit: Int) {
        if (unit !in 0 until pageLayout.unitCount) return
        scroller.forceFinished(true)
        val target = pageLayout.offsetForUnit(unit, scale, width.toFloat())
        val dx = ViewportMath.clampX(target, docW * scale, width.toFloat()) - offX
        scroller.startScroll(offX.toInt(), offY.toInt(), dx.roundToInt(), 0, FLIP_MS)
        postInvalidateOnAnimation()
    }

    /** 느리게 끌다 뗐을 때: 가장 가까운 칸으로 */
    private fun snapToNearest() {
        if (snapsToPage() && scroller.isFinished && zoomAnimator?.isRunning != true) flipTo(nearestUnit())
    }

    private fun animateZoom(target: Float, fx: Float, fy: Float) {
        zoomAnimator?.cancel()
        val docX = ViewportMath.docAt(offX, fx, scale)
        val docY = ViewportMath.docAt(offY, fy, scale)
        zoomAnimator = ValueAnimator.ofFloat(zoom, target).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                zoom = it.animatedValue as Float
                offX = ViewportMath.offsetKeeping(docX, scale, fx)
                offY = ViewportMath.offsetKeeping(docY, scale, fy)
                clamp()
                invalidate()
            }
            // 확대를 풀고 가로 넘김 모습으로 돌아왔으면 한 쪽에 맞춘다
            addListener(object : AnimatorListenerAdapter() {
                private var cancelled = false
                override fun onAnimationCancel(a: Animator) { cancelled = true }
                override fun onAnimationEnd(a: Animator) { if (!cancelled && target <= SNAP_ZOOM) post { snapToNearest() } }
            })
            start()
        }
        scheduleDetail()
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            val before = offY
            val beforeX = offX
            offX = scroller.currX.toFloat()
            offY = scroller.currY.toFloat()
            clamp()
            noteScrolled(abs(offY - before) + abs(offX - beforeX))
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

    /** 도구를 바꾼다: 뷰어가 있으면 툴바 표시까지 따라가게 맡기고, 없으면 그냥 바꾼다 */
    private fun switchToolFromPen(t: Tool) {
        val l = listener
        if (l != null) l.onPenButtonTool(t) else tool = t
    }

    /**
     * 펜 입력이 새로 닿을 때 옆 버튼 동작을 정한다 ([button]은 버튼을 누른 채인가, ([sx], [sy])는 닿은 화면 자리).
     * 올가미로 바꿨다면 선택을 마친 뒤, 선택 밖을 버튼 없이 쓰는 순간 원래 도구로 돌아온다
     */
    private fun applyPenButton(button: Boolean, sx: Float, sy: Float) {
        penSwapping = false
        if (button) {
            when (penButtonAction) {
                PenInputRules.ButtonAction.LASSO -> if (tool != Tool.LASSO) {
                    val back = tool
                    switchToolFromPen(Tool.LASSO)
                    buttonLassoReturn = back
                }
                PenInputRules.ButtonAction.LASER -> if (tool != Tool.LASER) {
                    buttonTempTool = tool
                    quietTool = true
                    tool = Tool.LASER
                    quietTool = false
                }
                PenInputRules.ButtonAction.SWAP -> penSwapping = true
                PenInputRules.ButtonAction.ERASER -> {}
            }
        } else {
            val back = buttonLassoReturn
            if (back != null && !onSelection(sx, sy)) {
                buttonLassoReturn = null
                switchToolFromPen(back)
            }
        }
    }

    /** 펜 기울기 (라디안, 0이 수직). 손가락이거나 기울기를 안 쓰거나 모르는 펜이면 0 */
    private fun tilt(ev: MotionEvent, idx: Int, hist: Int = -1): Float {
        if (!penTilt || !isStylus(ev, idx)) return 0f
        return if (hist >= 0) ev.getHistoricalAxisValue(MotionEvent.AXIS_TILT, idx, hist) else ev.getAxisValue(MotionEvent.AXIS_TILT, idx)
    }

    private fun pressure(ev: MotionEvent, idx: Int, hist: Int = -1): Float {
        val stylus = isStylus(ev, idx)
        if (!stylus) return PenInputRules.pressure(false, 0f)
        return PenInputRules.pressure(true, if (hist >= 0) ev.getHistoricalPressure(idx, hist) else ev.getPressure(idx))
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        if (doc == null) return false
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) listener?.onTouchDown()
        if (penPointerId == -1 && scrollBar.onTouch(ev)) return true
        if (penPointerId == -1 && !fingerActive && addFooter.onTouch(ev)) return true
        if (penPointerId == -1 && !fingerActive && handleNoteTouch(ev)) return true
        // 자: 손가락으로 자의 몸통을 잡고 옮기고 돌린다 (펜이 닿으면 놓아 주어 한 손으로 자를 잡고 다른 손으로 긋는다)
        if (!readOnly && ruler.onFingerTouch(ev, penPointerId != -1)) return true
        // 구형 삼성 S펜: 버튼을 누른 채 그리면 별도 액션 코드(211~213)로 들어온다
        val translated = PenInputRules.translate(ev.actionMasked)
        val action = translated.action
        val samsungButton = translated.samsungButton

        // 펜 시작 (읽기 모드에서는 펜도 손가락처럼 넘기기·확대만)
        if (PenInputRules.mayStartPen(readOnly, penPointerId != -1, action)) {
            val idx = if (samsungButton) 0 else ev.actionIndex
            val stylus = isStylus(ev, idx) || samsungButton
            // 선택한 부분은 손가락 필기를 꺼 두어도 손가락으로 끌어 옮길 수 있다
            val fingerPen = PenInputRules.isFingerPen(
                stylus, action, ev.getToolType(idx) == MotionEvent.TOOL_TYPE_FINGER, fingerDrawing,
                onSelection(ev.getX(idx), ev.getY(idx)),
            )
            if (stylus || fingerPen) {
                if (fingerActive) cancelFingerGesture(ev)
                fingersBlocked = stylus
                penPointerId = ev.getPointerId(idx)
                penIsFinger = fingerPen
                val tip = ev.getToolType(idx) == MotionEvent.TOOL_TYPE_ERASER
                val button = stylus && !tip && PenInputRules.buttonDown(samsungButton, ev.buttonState, STYLUS_BUTTON_MASK)
                applyPenButton(button, ev.getX(idx), ev.getY(idx))
                penErasing = PenInputRules.penErasing(
                    tool, tapeErasing, fillErasing, stylus, PenInputRules.eraserInput(tip, button, penButtonAction),
                )
                penMaxMajor = if (fingerPen) ev.getTouchMajor(idx) else 0f
                curTilt = tilt(ev, idx)
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
                if (anyPalm(ev) || PalmMath.gathered(ev.pointerCount, ev.eventTime - ev.downTime)) {
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
                            curTilt = tilt(ev, idx, h)
                            movePen(ev.getHistoricalX(idx, h), ev.getHistoricalY(idx, h), pressure(ev, idx, h), ev.getHistoricalEventTime(h))
                        }
                        curTilt = tilt(ev, idx)
                        movePen(ev.getX(idx), ev.getY(idx), pressure(ev, idx), ev.eventTime)
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (ev.getPointerId(ev.actionIndex) == penPointerId) endPen(commit = true)
                }
                MotionEvent.ACTION_UP -> {
                    // 뗀 자리까지 획에 넣는다 (보정 펜에서 끝점이 잘리지 않게)
                    val idx = ev.findPointerIndex(penPointerId)
                    if (idx >= 0) {
                        curTilt = tilt(ev, idx)
                        movePen(ev.getX(idx), ev.getY(idx), pressure(ev, idx), ev.eventTime)
                    }
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
            ((action == MotionEvent.ACTION_POINTER_DOWN && PalmMath.gathered(ev.pointerCount, ev.eventTime - ev.downTime)) ||
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
            snapToNearest()
            scheduleDetail()
        }
        return true
    }

    // ================= 손바닥 지우기 =================

    /** 손바닥 지우기를 쓰는 도구 (펜·형광펜·보정 펜·지우개) */
    private fun palmToolOk() = PenInputRules.palmToolOk(palmErase, readOnly, tool)

    /** 지금 손가락 필기 중이라 손바닥으로 바꿀 수 있는지 */
    private fun palmReady() = PenInputRules.palmReady(fingerDrawing, penIsFinger, palmErasing, palmToolOk())

    /** 보통 손가락이 닿는 크기 (최근 손가락 획들의 가운뎃값, 아직 모르면 9mm) */
    private fun fingerSize(): Float {
        return PalmMath.medianSize(fingerSizes, PalmMath.mm(resources.displayMetrics.xdpi, 9f))
    }

    /** 닿은 것 중에 손바닥만큼 넓은 것이 있는지 (보통 손가락의 2.5배 넘게) */
    private fun anyPalm(ev: MotionEvent): Boolean {
        val n = ev.pointerCount
        val limit = PalmMath.palmLimit(fingerSize(), resources.displayMetrics.xdpi)
        return PalmMath.anyPalm(
            BooleanArray(n) { ev.getToolType(it) == MotionEvent.TOOL_TYPE_FINGER },
            FloatArray(n) { ev.getTouchMajor(it) }, n, limit,
        )
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
        val n = ev.pointerCount
        val c = PalmMath.cover(
            FloatArray(n) { ev.getX(it) }, FloatArray(n) { ev.getY(it) }, FloatArray(n) { ev.getTouchMajor(it) }, n, leaving,
        ) ?: return
        val cx = c[0]
        val cy = c[1]
        palmRadius = PalmMath.clampRadius(c[2], PalmMath.MIN_DP * density, PalmMath.MAX_DP * density)
        if (first) {
            lastSx = cx
            lastSy = cy
        }
        val dist = hypot(cx - lastSx, cy - lastSy)
        val steps = PalmMath.steps(dist, palmRadius / 2f)
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
        // 펜 버튼을 누른 채 톡 쳐서 도구를 바꾸는 중: 끌어도 아무것도 그리지 않는다
        if (penSwapping) return
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
        } else if (tool == Tool.HIGHLIGHTER && textMark != TextMark.NONE && textMarks.begin(hit.first, hit.second, hit.third)) {
            // 글자 기반 주석: 글자를 끌어 고른다 (글자가 없는 자리면 위에서 false라 보통 형광펜으로 그린다)
            clearSelection()
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
            // 자 가장자리에서 시작한 펜·형광펜 획은 가장자리를 따라 곧게 (각도기 둘레는 둥글게)
            val snap = if ((tool == Tool.PEN || tool == Tool.HIGHLIGHTER) && ruler.active) {
                // 선이 자에 가려지지 않게 가장자리에서 펜 굵기의 반 + 가장자리 선의 반만큼 바깥에 긋는다
                val halfPen = (if (tool == Tool.HIGHLIGHTER) hlWidth else penWidth * penStyle.reach) / 2f
                ruler.beginSnap(hit.first, hit.second, hit.third, SNAP_DP * density / scale, halfPen + 0.7f * density / scale)
            } else null
            rulerSnap = snap
            val hx = snap?.startX ?: hit.second
            val hy = snap?.startY ?: hit.third
            if (tool == Tool.PEN) {
                penInput.begin(penStyle, penWidth, penSmoothing, hx, hy, p, t, curTilt)
                st.add(penInput.x, penInput.y, penInput.p)
            } else if (tool == Tool.FILL) {
                penInput.begin(PenStyle.FELT, 1f, fillSmoothing, hit.second, hit.third, p, t)
                st.add(penInput.x, penInput.y, 0f)
            } else st.add(hx, hy, p)
            curStroke = st
        }
        invalidate()
    }

    /** 화면 좌표 점이 회전 중심에서 이루는 각 (도) */
    private fun angleAt(sx: Float, sy: Float): Float {
        return SelectionTransform.angleDeg(toPageX(selPage, sx), toPageY(selPage, sy), rotCx, rotCy)
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
        if (textMarks.active) {
            textMarks.move(toPageX(textMarks.page, sx), toPageY(textMarks.page, sy))
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
            val k = SelectionTransform.resizeScale(
                SelectionTransform.ResizeSetup(anchorX, anchorY, startDist, resizeAxis, resizeDir),
                toPageX(selPage, sx), toPageY(selPage, sy), scaleK, scaleKy,
            )
            scaleK = k[0]
            scaleKy = k[1]
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
            val n = PalmMath.steps(dist, step)
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
        val snap = rulerSnap
        if (snap != null) {
            // 자를 따라 긋기: 시작점에서 지금 점까지 곧은 선(각도기 둘레는 둥근 호)으로 다시 만든다
            lastPressure = lastPressure * 0.5f + p * 0.5f
            val pts = snap.points(px, py)
            st.keepFirst()
            // 만년필·붓펜이 아니면 굵기가 한결같게 (처음 닿을 때의 약한 필압이 남지 않게)
            if (st.pen != PenStyle.FOUNTAIN && st.pen != PenStyle.BRUSH) st.setPressure(0, lastPressure)
            for (k in 1 until pts.size / 2) st.add(pts[k * 2], pts[k * 2 + 1], lastPressure)
            snap.updateReadout(px, py)
            lastSx = sx
            lastSy = sy
            invalidate()
            return
        }
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
            if (!penInput.move(px, py, p, t, scale / density, minDist, st.x(last), st.y(last), curTilt)) return
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
        return ShapeStrokes.build(raw, fitted, kind, guide, dashed, penStyle)
    }

    private fun eraseAt(page: Int, px: Float, py: Float, radiusPx: Float = eraserRadiusDp * density) {
        val inkDoc = ink ?: return
        // 펼친 포스트잇 메모 아래는 보이지 않으므로 지우지 않는다
        if (notes.expandedNoteAt(page, px, py) != null) return
        val r = radiusPx / scale
        val list = inkDoc.pages[page]
        var removed = false
        // 테이프 도구의 지우개는 테이프만 (보이게 한 테이프도), 채우기 도구의 지우개는 채우기만 지운다
        val tapesOnly = EraseRules.tapesOnly(tool, tapeErasing, palmErasing)
        val fillsOnly = EraseRules.fillsOnly(tool, fillErasing, palmErasing)
        // 손바닥은 늘 닿은 부분만 지운다
        val mode = EraseRules.modeFor(palmErasing, tapesOnly, fillsOnly, tapeEraseMode, fillEraseMode, eraserMode)
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
        if (penSwapping) {
            penSwapping = false
            if (PenInputRules.isSwapTap(true, commit, tapCandidate, System.currentTimeMillis() - tapDownTime, TAP_MS)) {
                switchToolFromPen(previousTool)
            }
        }
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
        } else if (textMarks.active) {
            textMarks.finish(commit)
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
                        if (st.tool == Tool.PEN) penInput.finish(st, bridge = rulerSnap == null)
                        inkDoc.add(curPage, st)
                    }
                }
            }
            shapePreview = null
        }
        // 손바닥이 아니었던 손가락 획으로 보통 손가락 크기를 익힌다
        if (penIsFinger && !palmErasing && penMaxMajor > 0f) {
            PalmMath.remember(fingerSizes, penMaxMajor)
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
        rulerSnap = null
        ruler.clearReadout()
        // 펜 버튼으로 이번 획만 바꾼 도구(레이저)를 되돌린다
        buttonTempTool?.let {
            buttonTempTool = null
            quietTool = true
            tool = it
            quietTool = false
        }
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
        } else if (handle >= 0) {
            // 모서리 손잡이(0~3)는 반대쪽 모서리를, 변 가운데 손잡이(4~7)는 맞은편 변을 기준으로 크기 조절
            val su = SelectionTransform.resizeSetup(handle, floatArrayOf(selBounds.left, selBounds.top, selBounds.right, selBounds.bottom))
            anchorX = su.anchorX
            anchorY = su.anchorY
            startDist = su.startDist
            resizeAxis = su.axis
            resizeDir = su.dir
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
        if (SelectionTransform.isTap(SelectionTransform.lassoBounds(lasso, lassoCount), scale, density)) {
            (boxAt(lassoPage, lasso[0], lasso[1]) ?: tapeAt(lassoPage, lasso[0], lasso[1]) ?: fillAt(lassoPage, lasso[0], lasso[1]))
                ?.let { select(lassoPage, listOf(it)) }
            return
        }
        if (lassoRect || lassoTap) {
            // 두 점을 네 모서리로 바꿔 자유 선택과 같은 규칙으로 고른다
            val r = SelectionTransform.rectSelection(lasso[0], lasso[1], lasso[2], lasso[3], scale, density) ?: return
            lassoCount = 0
            addLassoPoint(r[0], r[1]); addLassoPoint(r[2], r[1]); addLassoPoint(r[2], r[3]); addLassoPoint(r[0], r[3])
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
        if (hideInk) return false
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
            return TapeFit.keepRect(abs(st.x(2) - st.x(0)), abs(st.y(2) - st.y(0)), scale, density)
        }
        if (!TapeFit.keepLine(st.count, st.length(), scale, density)) return false
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
        val sb = FillRules.strokeBounds(st)
        val reg = TapeFit.fitRegion(sb[0], sb[1], sb[2], sb[3], pw, ph)
        val l = reg[0]; val t = reg[1]; val r = reg[2]; val b = reg[3]
        val bw = ((r - l) * k).toInt()
        val bh = ((b - t) * k).toInt()
        if (!TapeFit.fitBitmapOk(bw, bh)) return
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
            TapeFit.isDark(Color.red(c), Color.green(c), Color.blue(c))
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

    /**
     * 선택한 것을 선택 영역의 가운데 선을 축으로 좌우([horizontal]) 또는 상하로 뒤집는다 (실행 취소 가능).
     * 글·그림은 자리만 뒤집고 글자·그림 자체는 뒤집히지 않는다 ([Stroke.mirror])
     */
    fun flipSelection(horizontal: Boolean) {
        val inkDoc = ink ?: return
        if (selection.isEmpty() || selBounds.isEmpty) return
        val strokes = selection.toList()
        val extent = if (horizontal) selBounds.left + selBounds.right else selBounds.top + selBounds.bottom
        inkDoc.edit(strokes) { strokes.forEach { it.mirror(horizontal, extent) } }
        select(selPage, strokes)
        invalidate()
    }

    /** 선택한 획을 복사해 둔다 */
    fun copySelection() {
        if (selection.isEmpty()) return
        val b = selBounds
        clipboard = selection.map { it.copy().apply { role = null; translate(-b.left, -b.top) } }
        clipSize.set(0f, 0f, b.width(), b.height())
    }

    /** 영역 스크린샷 [img]를 복사해 둔다 ([widthPt]×[heightPt]는 찍은 영역의 쪽 크기). 붙여넣기로 어느 문서에든 넣는다 */
    fun copyImage(img: InkImage, widthPt: Float, heightPt: Float) {
        val st = Stroke(Tool.PEN, Color.BLACK, 0f).apply {
            image = img
            add(0f, 0f, 1f)
            add(widthPt, 0f, 1f)
            add(widthPt, heightPt, 1f)
            add(0f, heightPt, 1f)
        }
        clipboard = listOf(st)
        clipSize.set(0f, 0f, widthPt, heightPt)
    }

    /** 복사해 둔 획을 지금 보이는 페이지 가운데에 붙이고 선택한다 (실행 취소 가능) */
    fun pasteClipboard() {
        val inkDoc = ink ?: return
        if (clipboard.isEmpty() || sizes.isEmpty()) return
        val page = currentPage()
        // 그림 하나(영역 스크린샷 등)가 쪽보다 크면 쪽에 들어오게 줄여 붙인다
        val fit = if (clipboard.size == 1 && clipboard[0].image != null)
            PlacementMath.pasteFit(clipSize.width(), clipSize.height(), sizes[page].width, sizes[page].height) else 1f
        val at = PlacementMath.pasteOrigin(
            offX, offY, width.toFloat(), height.toFloat(), scale,
            lefts[page], tops[page], sizes[page].width, sizes[page].height, clipSize.width() * fit, clipSize.height() * fit,
        )
        val copies = clipboard.map { it.copy().apply { role = null; if (fit < 1f) scale(fit, 0f, 0f); translate(at[0], at[1]) } }
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
        /** 가로 넘김에서 이 배율 이하(확대 안 한 상태)면 손을 뗄 때 한 쪽에 맞춘다 */
        private const val SNAP_ZOOM = 1.05f
        /** 가로 넘김의 쪽 맞추기 애니메이션 시간 (ms) */
        private const val FLIP_MS = 260
        private const val SEL_COLOR = 0xFF1E6FD9.toInt()
        /** 크기 조절 손잡이 반지름(그리기)과 누르는 범위 */
        private const val HANDLE_DP = 7f
        private const val HANDLE_TOUCH_DP = 24f
        /** 대상 선택: 획에서 이만큼(dp) 떨어져 눌러도 고른다 */
        private const val TAP_SELECT_DP = 12f
        /** 글 도구: 이보다 많이 움직이면 톡 누르기가 아니다 (dp) */
        private const val TAP_SLOP_DP = 12f
        /** 펜이 자 가장자리에서 이만큼(dp) 안에서 시작하면 가장자리에 붙는다 */
        private const val SNAP_DP = 16f
        /** 펜 옆 버튼으로 보는 버튼 상태 비트 (S펜 버튼 · 보조 버튼) */
        private const val STYLUS_BUTTON_MASK =
            MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY or MotionEvent.BUTTON_SECONDARY
        /** 테이프를 톡 누른 것으로 보는 시간 (ms) */
        private const val TAP_MS = 500L
        /** 긁어서 지우기: 긁은 선이 아래 필기를 적어도 이만큼 가로질러야 지운다 */
        private const val SCRIBBLE_CROSSINGS = 3
        /** 회전 손잡이 반지름과 상자에서 떨어진 거리 */
        private const val ROT_HANDLE_DP = 14f
        private const val ROT_OFFSET_DP = 34f
    }
}
