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

    private companion object {
        const val TAG = "FillController"
        /** 칠하기용 쪽 그림의 화소 수·배율 한도 */
        const val FILL_MAX_PX = 4_500_000f
        const val FILL_MAX_K = 4f
    }
}
