package com.dsviewer.app

import android.graphics.BlendMode
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.Log
import android.util.SizeF
import android.view.View
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * 채우기 도구의 비동기 일: 칠하기(누른 곳을 둘러싼 닫힌 영역 찾기)와 자유 영역 마무리, 그리는 중인 영역 보여 주기.
 * 판단·모양은 [FillRules], 영역 찾기는 [FillRegion]. 문서 화면([view])의 상태는 [Host]로 읽는다.
 */
internal class FillController(
    private val view: View,
    private val scope: CoroutineScope,
    private val host: Host,
) {
    interface Host {
        val doc: PdfDoc?
        val ink: InkDocument?
        val sizes: List<SizeF>
        val scale: Float
        val fillColor: Int
        val fillPattern: FillPattern
        val fillPolygon: Boolean
        /** 쪽 그림 위에 [strokes]를 겹 순서대로 그린다 (칠하기 영역을 찾을 때 필기도 경계로 치려고) */
        fun drawInk(c: Canvas, strokes: List<Stroke>)
        fun onFillFailed()
    }

    private val density = view.resources.displayMetrics.density

    /** 영역을 찾는 중인 자유 영역들 (쪽, 그린 획): 끝날 때까지 흐리게 보여 준다 */
    private val pending = ArrayList<Pair<Int, Stroke>>()
    /** 칠하기 영역을 찾는 중 (끝날 때까지 다음 칠하기는 받지 않는다) */
    private var bucketJob: Job? = null
    private val draftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { blendMode = BlendMode.MULTIPLY }
    private val draftLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val draftPath = Path()

    /** [page]쪽에서 영역을 찾는 중인 자유 영역들을 그린다 */
    fun drawPending(c: Canvas, page: Int) {
        for ((p, st) in pending) if (p == page) drawDraft(c, st)
    }

    /** 그리는 중인 (또는 영역을 찾는 중인) 자유 영역: 그린 선을 닫아 옅게 채우고 선을 그 색으로 */
    fun drawDraft(c: Canvas, st: Stroke) {
        if (st.count < 2) return
        draftPath.rewind()
        draftPath.moveTo(st.x(0), st.y(0))
        for (i in 1 until st.count) draftPath.lineTo(st.x(i), st.y(i))
        draftPath.close()
        draftPath.fillType = Path.FillType.WINDING
        draftPaint.color = st.color or (0xFF shl 24)
        draftPaint.alpha = 110
        c.drawPath(draftPath, draftPaint)
        draftLine.color = FillTile.inkColor(st.color)
        draftLine.strokeWidth = 1.5f * density / host.scale
        c.drawPath(draftPath, draftLine)
    }

    /**
     * 칠하기: 쪽 그림(PDF + 필기, 채우기·형광펜은 빼고)에서 (x, y)를 둘러싼 닫힌 영역을 찾아 채운다.
     * 같은 영역이 이미 채워져 있으면 그 채우기를 새 색·무늬로 바꾼다 (그림판처럼). 실행 취소 가능
     */
    fun bucketFill(page: Int, x: Float, y: Float) {
        val d = host.doc ?: return
        val inkDoc = host.ink ?: return
        val sizes = host.sizes
        if (bucketJob?.isActive == true || page !in sizes.indices) return
        val list = inkDoc.pages[page]
        val strokes = FillRules.bucketSources(list)
        val size = FillRules.bucketSize(sizes[page].width, sizes[page].height, FILL_MAX_K, FILL_MAX_PX)
        val k = size.k
        val w = size.w
        val h = size.h
        val color = host.fillColor
        val pattern = host.fillPattern
        bucketJob = scope.launch {
            val result = try {
                val bmp = withContext(d.dispatcher) { d.render(page, k, 0f, 0f, w, h) }
                withContext(Dispatchers.Default) {
                    try {
                        val c = Canvas(bmp)
                        c.scale(k, k)
                        host.drawInk(c, strokes)
                        val px = IntArray(w * h)
                        bmp.getPixels(px, 0, w, 0, 0, w, h)
                        FillRegion.bucket(px, w, h, k, (x * k).toInt(), (y * k).toInt())
                    } finally {
                        bmp.recycle()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "칠하기 실패", e)
                null
            }
            // 찾는 사이 문서·쪽이 바뀌었으면 버린다
            if (host.ink !== inkDoc || inkDoc.pages.getOrNull(page) !== list) return@launch
            if (result == null) {
                host.onFillFailed()
                return@launch
            }
            val st = FillRules.fillStroke(result.contours, color, pattern) ?: return@launch
            val old = FillRules.sameRegionFill(list, x, y, FillRules.strokeBounds(st))
            if (old != null) inkDoc.replace(page, old, st) else inkDoc.add(page, st)
            view.invalidate()
        }
    }

    /**
     * 자유 영역을 다 그림: 처음과 끝을 이어 닫고 가장 바깥 윤곽 안을 모두 채운다.
     * 다각형 보정을 켜 두었으면 거의 곧은 변을 곧게 편다. 아주 작게 그렸으면 버린다
     */
    fun finishFreeFill(page: Int, st: Stroke) {
        val inkDoc = host.ink ?: return
        if (st.count < 3) return
        if (FillRules.isTooSmall(FillRules.strokeBounds(st), host.scale, density)) return
        val xs = FloatArray(st.count) { st.x(it) }
        val ys = FloatArray(st.count) { st.y(it) }
        val color = host.fillColor
        val pattern = host.fillPattern
        val polygon = host.fillPolygon
        val list = inkDoc.pages[page]
        val entry = page to st
        pending.add(entry)
        scope.launch {
            val outline = withContext(Dispatchers.Default) {
                try {
                    FillRegion.freeform(xs, ys)?.let { if (polygon) FillRegion.straighten(it) else it }
                } catch (e: Throwable) {
                    Log.w(TAG, "자유 영역 실패", e)
                    null
                }
            }
            pending.remove(entry)
            view.invalidate()
            if (outline == null || host.ink !== inkDoc || inkDoc.pages.getOrNull(page) !== list) return@launch
            FillRules.fillStroke(listOf(outline), color, pattern)?.let { inkDoc.add(page, it) }
        }
    }

    /**
     * 네모 덮기를 다 끌음: 끈 네모를 한 색으로 채운 획을 남긴다 (필기 아래, PDF 글 위에 깔려 안의 내용을 가린다).
     * [auto]면 네모 바로 바깥 둘레에서 가장 많은 색을 따온다 ([CoverColor]), 못 따오거나 [auto]가 아니면 [color].
     * 아주 작게 끌었으면 버린다. 실행 취소 가능
     */
    fun finishCover(page: Int, st: Stroke, auto: Boolean, color: Int) {
        val inkDoc = host.ink ?: return
        if (st.count < 4) return
        val b = FillRules.strokeBounds(st)
        if (FillRules.isTooSmall(b, host.scale, density)) return
        val outline = floatArrayOf(b[0], b[1], b[2], b[1], b[2], b[3], b[0], b[3])
        val list = inkDoc.pages[page]
        val d = host.doc
        val sizes = host.sizes
        fun commit(c: Int) {
            if (host.ink !== inkDoc || inkDoc.pages.getOrNull(page) !== list) return
            FillRules.fillStroke(listOf(outline), c or (0xFF shl 24), FillPattern.SOLID, opaque = true)?.let {
                inkDoc.add(page, it)
                view.invalidate()
            }
        }
        if (!auto || d == null || page !in sizes.indices) {
            commit(color)
            return
        }
        val entry = page to st
        pending.add(entry)
        // 둘레(네모 밖 띠)가 들어오도록 조금 넓게 그려, 그 띠에서 색을 센다
        val pageW = sizes[page].width
        val pageH = sizes[page].height
        val l0 = max(0f, b[0] - COVER_PAD)
        val t0 = max(0f, b[1] - COVER_PAD)
        val r0 = min(pageW, b[2] + COVER_PAD)
        val b0 = min(pageH, b[3] + COVER_PAD)
        val k = (1600f / max(r0 - l0, b0 - t0)).coerceIn(1f, 4f)
        val w = max(1, ceil((r0 - l0) * k).toInt())
        val h = max(1, ceil((b0 - t0) * k).toInt())
        val sources = list.filter { it.note == null && it.tape == null && it.tool != Tool.HIGHLIGHTER && it.tool != Tool.LASER }
        scope.launch {
            val sampled = try {
                val bmp = withContext(d.dispatcher) { d.render(page, k, l0, t0, w, h) }
                withContext(Dispatchers.Default) {
                    try {
                        val c = Canvas(bmp)
                        c.scale(k, k)
                        c.translate(-l0, -t0)
                        host.drawInk(c, sources)
                        val px = IntArray(w * h)
                        bmp.getPixels(px, 0, w, 0, 0, w, h)
                        CoverColor.dominant(
                            px, w, h,
                            ((b[0] - l0) * k).toInt(), ((b[1] - t0) * k).toInt(), ((b[2] - l0) * k).toInt(), ((b[3] - t0) * k).toInt(),
                            gap = max(1, (COVER_GAP * k).toInt()), thick = max(2, (COVER_PAD * k).toInt()),
                        )
                    } finally {
                        bmp.recycle()
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.w(TAG, "덮을 색 따기 실패", e)
                null
            }
            pending.remove(entry)
            view.invalidate()
            commit(sampled ?: color)
        }
    }

    private companion object {
        const val TAG = "FillController"
        /** 네모 덮기: 둘레 색을 셀 띠의 폭과 네모에서 띄우는 간격 (pt) */
        const val COVER_PAD = 4f
        const val COVER_GAP = 1.5f
        /** 칠하기용 쪽 그림의 화소 수·배율 한도 */
        const val FILL_MAX_PX = 4_500_000f
        const val FILL_MAX_K = 4f
    }
}
