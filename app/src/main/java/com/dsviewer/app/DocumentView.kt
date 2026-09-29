package com.dsviewer.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
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
    var hlColor = 0xFFFFEB3B.toInt()
    var hlWidth = 12f
    var eraserRadiusDp = 12f
    /** 획 지우개 / 영역 지우개 */
    var eraserMode = EraserMode.STROKE
    /** true면 형광펜 획만 지운다 */
    var eraseHlOnly = false
    /** true면 손가락 한 개로 필기, 두 손가락으로 이동/확대 */
    var fingerDrawing = false
    /** 보정 펜으로 그릴 도형 */
    var shapeKind = ShapeKind.LINE
    /** 보정 펜: 그리는 중에 맞춰 본 도형 (흐리게 미리 보여 줌) */
    private var shapePreview: List<Stroke>? = null
    /** 보정 펜: 보조선(지수·로그·탄젠트·쌍곡선의 점근선, 사인·코사인의 축)을 그리는 방식 */
    var shapeGuide = GuideStyle.NONE
    private var lastShapeFit = 0L

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
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
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
    /** true면 네모 선택, false면 자유 선택(올가미) */
    var lassoRect = false
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
        cancelRendering()
        doc = d
        ink = inkDoc
        sizes = d.sizes
        val n = sizes.size
        tops = FloatArray(n)
        lefts = FloatArray(n)
        docW = (sizes.maxOfOrNull { it.width } ?: 600f) + gap * 2
        var y = gap
        for (i in 0 until n) {
            lefts[i] = (docW - sizes[i].width) / 2f
            tops[i] = y
            y += sizes[i].height + gap
        }
        docH = y
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
    }

    // ================= 레이아웃 / 변환 =================

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (doc == null || w == 0) return
        // 화면 가운데에 있던 문서 위치를 유지
        val anchorY = if (oldw > 0) (offY + oldh / 2f) / scale else 0f
        baseScale = w / docW
        offY = if (oldw > 0) anchorY * scale - h / 2f else 0f
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

    /** 스크롤할 수 있는 전체 높이 (화면 px) */
    private fun contentH() = docH * scale + bottomInset

    private fun clamp() {
        val cw = docW * scale
        val ch = contentH()
        offX = if (cw <= width) -(width - cw) / 2f else offX.coerceIn(0f, cw - width)
        offY = if (ch <= height) -(height - ch) / 2f else offY.coerceIn(0f, ch - height)
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
            } else if (first >= 0) break
        }
        return if (first < 0) IntRange.EMPTY else first..last
    }

    /** 화면 좌표 → (페이지, 페이지 x, 페이지 y). 페이지 사이 여백은 가까운 페이지로 */
    private fun hitPage(sx: Float, sy: Float): Triple<Int, Float, Float>? {
        val s = scale
        val dx = (sx + offX) / s
        val dy = (sy + offY) / s
        for (i in sizes.indices) {
            val t = tops[i] - gap / 2
            val b = tops[i] + sizes[i].height + gap / 2
            if (dy in t..b) {
                val px = dx - lefts[i]
                if (px < -gap || px > sizes[i].width + gap) return null
                return Triple(i, px, dy - tops[i])
            }
        }
        return null
    }

    /** 화면 가운데에 걸친 쪽 (문서가 없으면 -1) */
    fun currentPage(): Int {
        if (sizes.isEmpty()) return -1
        val centerDoc = (offY + height / 2f) / scale
        return sizes.indices.firstOrNull { tops[it] + sizes[it].height + gap / 2 >= centerDoc } ?: sizes.lastIndex
    }

    /** [page]쪽의 위쪽 끝이 화면 맨 위에 오도록 옮긴다 */
    fun scrollToPage(page: Int) {
        if (page !in sizes.indices || width == 0) return
        scroller.forceFinished(true)
        zoomAnimator?.cancel()
        offY = (tops[page] - gap / 2) * scale
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
            val dragging = (moving || resizing) && selPage == i
            for (st in inkDoc.pages[i]) if (!dragging || st !in selSet) drawStroke(canvas, st)
            if (dragging) {
                // 옮기거나 크기를 바꾸는 중인 획은 손을 뗄 때까지 그림만 바꿔 그린다
                canvas.save()
                canvas.translate(moveDx, moveDy)
                if (resizing) canvas.scale(scaleK, scaleK, anchorX, anchorY)
                for (st in selection) drawStroke(canvas, st)
                canvas.restore()
            }
            if (curPage == i) curStroke?.let {
                if (tool == Tool.SHAPE) {
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
        if (rect != null) {
            canvas.drawRect(rect, selFill)
            canvas.drawRect(rect, selLine)
            // 크기 조절 손잡이
            val hr = HANDLE_DP * density
            for (cx in floatArrayOf(rect.left, rect.right)) for (cy in floatArrayOf(rect.top, rect.bottom)) {
                canvas.drawCircle(cx, cy, hr, handleFill)
                canvas.drawCircle(cx, cy, hr, handleLine)
            }
        }
        // 옮기거나 크기를 바꾸는 동안에는 막대를 숨긴다
        val report = if (moving || resizing) null else rect
        if (report != reportedSel) {
            reportedSel = report?.let { RectF(it) }
            listener?.onSelectionChanged(reportedSel, selection.size)
        }
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

    private fun drawStroke(c: Canvas, st: Stroke, alphaMul: Float = 1f) {
        strokePaint.color = st.color
        if (st.tool == Tool.HIGHLIGHTER) {
            strokePaint.alpha = (PdfInk.HL_ALPHA * 255).roundToInt()
            strokePaint.blendMode = BlendMode.MULTIPLY
        } else {
            strokePaint.blendMode = null
        }
        if (alphaMul < 1f) strokePaint.alpha = (strokePaint.alpha * alphaMul).roundToInt()
        strokePaint.pathEffect = if (st.dashed) DashPathEffect(st.dashIntervals(), 0f) else null
        for ((w, path) in st.paths()) {
            strokePaint.strokeWidth = w
            c.drawPath(path, strokePaint)
        }
    }

    private fun reportPage(count: Int) {
        if (sizes.isEmpty()) return
        val centerDoc = (offY + height / 2f) / scale
        var page = sizes.size - 1
        for (i in sizes.indices) {
            if (tops[i] + sizes[i].height + gap / 2 >= centerDoc) { page = i; break }
        }
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
            val maxY = max(0f, ch - height).toInt()
            val minX = if (cw <= width) offX.toInt() else 0
            val minY = if (ch <= height) offY.toInt() else 0
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

    // ================= 스크롤 막대 =================
    // 평소에는 숨겨져 있다가, 화면 높이의 절반 이상 움직이면 오른쪽에 나타난다.
    // 손잡이를 끌면 문서 위치로 바로 이동하고, 1.5초 동안 움직임이 없으면 사라진다.

    private var barAlpha = 0f
    private var barAnimator: ValueAnimator? = null
    private var barDragging = false
    private var barGrabOffset = 0f
    private var scrollAccum = 0f
    private val barTouchWidth = 40f * density
    private val barMargin = 10f * density
    private val barMinThumb = 48f * density
    private val barThumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val barTrackPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val hideBarRunnable = Runnable { if (!barDragging) fadeBar(0f) }
    private val resetAccumRunnable = Runnable { scrollAccum = 0f }

    private fun barVisible() = barAlpha > 0.05f

    private fun noteScrolled(dy: Float) {
        if (dy == 0f || contentH() <= height * 1.05f) return
        if (barVisible() && (barAnimator?.isRunning != true || barAlpha > 0.5f)) {
            fadeBar(1f)
        } else {
            scrollAccum += abs(dy)
            removeCallbacks(resetAccumRunnable)
            postDelayed(resetAccumRunnable, 800)
            if (scrollAccum >= height / 2f) {
                scrollAccum = 0f
                fadeBar(1f)
            }
        }
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

    /** 스크롤 막대의 (트랙 위, 트랙 길이, 손잡이 위, 손잡이 높이). 문서가 화면보다 짧으면 null */
    private fun barGeometry(): FloatArray? {
        val ch = contentH()
        if (ch <= height * 1.05f) return null
        val trackTop = barMargin
        val trackLen = height - barMargin * 2
        val thumbH = max(barMinThumb, trackLen * height / ch).coerceAtMost(trackLen)
        val frac = (offY / (ch - height)).coerceIn(0f, 1f)
        return floatArrayOf(trackTop, trackLen, trackTop + frac * (trackLen - thumbH), thumbH)
    }

    private fun drawScrollBar(canvas: Canvas) {
        if (!barVisible()) return
        val g = barGeometry() ?: return
        val w = (if (barDragging) 10f else 6f) * density
        val right = width - 5f * density
        val a = barAlpha
        barTrackPaint.color = Color.argb((60 * a).toInt(), 0, 0, 0)
        canvas.drawRoundRect(right - w, g[0], right, g[0] + g[1], w / 2, w / 2, barTrackPaint)
        barThumbPaint.color = if (barDragging) Color.argb((230 * a).toInt(), 0x1E, 0x5A, 0xA8)
        else Color.argb((170 * a).toInt(), 0x55, 0x55, 0x55)
        canvas.drawRoundRect(right - w, g[2], right, g[2] + g[3], w / 2, w / 2, barThumbPaint)
    }

    /** 스크롤 막대를 잡고 끄는 입력을 처리했으면 true */
    private fun handleBarTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val g = barGeometry() ?: return false
                if (!barVisible() || ev.x < width - barTouchWidth) return false
                // 손잡이 밖의 트랙을 누르면 그 위치로 손잡이 가운데를 옮긴다
                val onThumb = ev.y >= g[2] - 12f * density && ev.y <= g[2] + g[3] + 12f * density
                barGrabOffset = if (onThumb) ev.y - g[2] else g[3] / 2f
                barDragging = true
                scroller.forceFinished(true)
                zoomAnimator?.cancel()
                removeCallbacks(hideBarRunnable)
                fadeBar(1f)
                moveThumbTo(ev.y, g)
                return true
            }
            MotionEvent.ACTION_MOVE -> if (barDragging) {
                barGeometry()?.let { moveThumbTo(ev.y, it) }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (barDragging) {
                barDragging = false
                postDelayed(hideBarRunnable, 1500)
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
        offY = frac * (ch - height)
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

        // 펜 시작
        if (penPointerId == -1 && (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN)) {
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
                penErasing = tool == Tool.ERASER || (stylus && isEraserInput(ev, idx, samsungButton))
                startPen(ev.getX(idx), ev.getY(idx), pressure(ev, idx))
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
                            movePen(ev.getHistoricalX(idx, h), ev.getHistoricalY(idx, h), pressure(ev, idx, h))
                        }
                        movePen(ev.getX(idx), ev.getY(idx), pressure(ev, idx))
                    }
                }
                MotionEvent.ACTION_POINTER_UP -> {
                    if (ev.getPointerId(ev.actionIndex) == penPointerId) endPen(commit = true)
                }
                MotionEvent.ACTION_UP -> {
                    // 뗀 자리까지 획에 넣는다 (보정 펜에서 끝점이 잘리지 않게)
                    val idx = ev.findPointerIndex(penPointerId)
                    if (idx >= 0) movePen(ev.getX(idx), ev.getY(idx), pressure(ev, idx))
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

    private fun startPen(sx: Float, sy: Float, p: Float) {
        listener?.onPenDown()
        scroller.forceFinished(true)
        zoomAnimator?.cancel()
        lastSx = sx
        lastSy = sy
        erased.clear()
        pieces.clear()
        curStroke = null
        curPage = -1
        if (tool == Tool.LASSO && !penErasing) {
            startLasso(sx, sy)
            return
        }
        if (penErasing) clearSelection()
        val hit = hitPage(sx, sy) ?: run { invalidate(); return }
        if (penErasing) {
            eraseAt(hit.first, hit.second, hit.third)
        } else {
            curPage = hit.first
            val st = if (tool == Tool.HIGHLIGHTER) Stroke(Tool.HIGHLIGHTER, hlColor, hlWidth)
            else Stroke(Tool.PEN, penColor, penWidth)
            lastPressure = p
            st.add(hit.second, hit.third, p)
            curStroke = st
        }
        invalidate()
    }

    private fun movePen(sx: Float, sy: Float, p: Float) {
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
        val last = st.count - 1
        val minDist = 0.8f / scale
        if (hypot(px - st.x(last), py - st.y(last)) < minDist) return
        lastPressure = lastPressure * 0.5f + p * 0.5f
        st.add(px, py, lastPressure)
        lastSx = sx
        lastSy = sy
        if (tool == Tool.SHAPE) {
            // 미리 보기는 너무 자주 계산하지 않는다
            val now = System.currentTimeMillis()
            if (now - lastShapeFit > 40) {
                lastShapeFit = now
                shapePreview = fitShape(st)
            }
        }
        invalidate()
    }

    /**
     * 보정 펜 획을 고른 도형으로 맞춘 새 펜 획들 (굵기는 그린 획의 평균 필압).
     * 쌍곡선은 두 가지, 보조선(점근선·축)을 켜 두었으면 그 획이 더 붙는다
     */
    private fun fitShape(raw: Stroke): List<Stroke>? {
        val fitted = ShapeFit.fit(shapeKind, raw) ?: return null
        var pSum = 0f
        for (i in 0 until raw.count) pSum += raw.p(i)
        val pAvg = pSum / raw.count
        fun toStroke(pts: FloatArray, dashed: Boolean) = Stroke(Tool.PEN, raw.color, raw.width, dashed).apply {
            for (i in 0 until pts.size / 2) add(pts[i * 2], pts[i * 2 + 1], pAvg)
        }
        val out = fitted.curves.map { toStroke(it, false) }.toMutableList()
        if (shapeGuide != GuideStyle.NONE) fitted.guides.mapTo(out) { toStroke(it, shapeGuide == GuideStyle.DASHED) }
        return out
    }

    private fun eraseAt(page: Int, px: Float, py: Float) {
        val inkDoc = ink ?: return
        val r = eraserRadiusDp * density / scale
        val list = inkDoc.pages[page]
        var removed = false
        for (k in list.indices.reversed()) {
            val st = list[k]
            if (eraseHlOnly && st.tool != Tool.HIGHLIGHTER) continue
            val rest = if (eraserMode == EraserMode.AREA) st.cut(px, py, r) ?: continue
            else if (st.hitTest(px, py, r)) emptyList() else continue
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
        if (moving) {
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
                    } else inkDoc.add(curPage, st)
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
        invalidate()
    }

    // ================= 올가미 선택 =================

    /** 화면 좌표가 선택 상자나 손잡이 위인지 */
    private fun onSelection(sx: Float, sy: Float) =
        selection.isNotEmpty() && (selectionScreenRect(RectF()).contains(sx, sy) || handleAt(sx, sy) >= 0)

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
        if (handle >= 0) {
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
        selBounds.setEmpty()
        var first = true
        for (st in picked) {
            val r = st.width / 2
            for (k in 0 until st.count) {
                val l = st.x(k) - r; val t = st.y(k) - r; val rt = st.x(k) + r; val b = st.y(k) + r
                if (first) { selBounds.set(l, t, rt, b); first = false } else selBounds.union(l, t, rt, b)
            }
        }
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
        if (selection.isEmpty() && !lassoing && !moving && !resizing) return
        selection = emptyList()
        selSet = emptySet()
        selPage = -1
        lassoing = false
        moving = false
        resizing = false
        moveDx = 0f
        moveDy = 0f
        scaleK = 1f
        invalidate()
    }

    /** 보고 있는 쪽의 필기(hlOnly면 형광펜만)를 모두 지운다 (실행 취소 가능). 지운 획 수 */
    fun clearPage(hlOnly: Boolean): Int {
        val inkDoc = ink ?: return 0
        val page = currentPage().takeIf { it >= 0 } ?: return 0
        val targets = inkDoc.pages[page].filter { !hlOnly || it.tool == Tool.HIGHLIGHTER }
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

    /** 선택한 획의 대표 색 (색 고르기 창의 처음 값) */
    val selectionColor: Int? get() = selection.firstOrNull()?.color

    /** 선택한 획의 색 바꾸기 (실행 취소 가능) */
    fun recolorSelection(c: Int) {
        val inkDoc = ink ?: return
        inkDoc.edit(selection) { selection.forEach { it.recolor(c) } }
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
        removeCallbacks(resetAccumRunnable)
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
        private const val SPEN_DOWN = 211
        private const val SPEN_UP = 212
        private const val SPEN_MOVE = 213
    }
}
