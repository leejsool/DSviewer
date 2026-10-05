package com.dsviewer.app

import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View

/**
 * 글자 기반 주석: 형광펜 도구로 PDF 본문의 글자를 끌면 글줄에 맞춰 칠하거나(밑줄·취소선) 복사한다.
 * 글자 위치는 쪽마다 [Host.textOf]에서 받고, 고르는 규칙은 [TextMarkup] (순수 계산).
 * 끝나면 보통 형광펜·펜 획으로 남기므로 지우기·실행 취소·PDF 저장은 보통 필기와 같다.
 */
internal class TextMarkController(private val view: View, private val host: Host) {
    interface Host {
        val ink: InkDocument?
        val scale: Float
        val textMark: TextMark
        val hlColor: Int
        val penColor: Int
        val penWidth: Float
        /** [page]쪽의 글자. 아직 못 읽었으면 null (읽기 시작해 달라고 한다) */
        fun textOf(page: Int): PageText?
        /** 짧은 안내 */
        fun say(msg: String)
        fun copyText(text: String)
    }

    private val density = view.resources.displayMetrics.density
    private val selPaint = Paint().apply { color = 0x551E6FD9 }

    /** 글자를 끌고 있는 중 */
    var active = false
        private set
    var page = -1
        private set
    private var text: PageText? = null
    private var anchor = -1
    private var head = -1
    private var rects: List<FloatArray> = emptyList()
    private var lastHintAt = 0L

    /**
     * 누른 자리 ([x], [y]는 [page]의 쪽 좌표)에서 글자 끌기를 시작한다. 시작했으면 true (그 누름은 이 컨트롤러가 받는다).
     * 글자가 없는 자리나 글자가 없는 쪽이면 false: 보통 형광펜으로 그린다 (복사는 그냥 아무 일 없음)
     */
    fun begin(page: Int, x: Float, y: Float): Boolean {
        val mark = host.textMark
        if (mark == TextMark.NONE) return false
        val consume = mark == TextMark.COPY
        val t = host.textOf(page)
        if (t == null) {
            val now = SystemClock.uptimeMillis()
            if (now - lastHintAt > 4000L) {
                lastHintAt = now
                host.say("이 쪽의 글자를 읽는 중입니다. 잠시 뒤에 다시 해 보세요.")
            }
            return consume
        }
        val i = TextMarkup.nearest(t, x, y, GRAB_DP * density / host.scale)
        if (i < 0) return consume
        active = true
        this.page = page
        text = t
        anchor = i
        head = i
        rects = TextMarkup.lineRects(t, i, i)
        view.invalidate()
        return true
    }

    /** 끌어서 지금 닿은 자리 ([x], [y]는 시작한 쪽의 쪽 좌표). 쪽 밖으로 나가도 가장 가까운 글자 */
    fun move(x: Float, y: Float) {
        val t = text ?: return
        val i = TextMarkup.nearest(t, x, y)
        if (i < 0 || i == head) return
        head = i
        rects = TextMarkup.lineRects(t, anchor, i)
        view.invalidate()
    }

    /** 손을 뗌 ([commit]) 또는 취소. 고른 글자로 주석 획을 남기거나 복사한다 */
    fun finish(commit: Boolean) {
        val t = text
        val p = page
        val a = anchor
        val h = head
        val mark = host.textMark
        val lines = rects
        active = false
        page = -1
        text = null
        rects = emptyList()
        view.invalidate()
        if (!commit || t == null || a < 0) return
        if (mark == TextMark.COPY) {
            val s = TextMarkup.copyText(t, a, h)
            if (s.isNotEmpty()) {
                host.copyText(s)
                host.say("${s.count { !it.isWhitespace() }}자를 복사했습니다.")
            }
            return
        }
        val ink = host.ink ?: return
        val strokes = ArrayList<Stroke>()
        for (r in lines) {
            val seg = TextMarkup.segment(mark, r, host.penWidth) ?: continue
            val st = if (mark == TextMark.HIGHLIGHT) Stroke(Tool.HIGHLIGHTER, host.hlColor, seg.width)
            else Stroke(Tool.PEN, host.penColor, seg.width)
            st.add(seg.x0, seg.y0, 1f)
            st.add(seg.x1, seg.y1, 1f)
            strokes.add(st)
        }
        if (strokes.isNotEmpty()) ink.addAll(p, strokes)
    }

    /** 끌어 고르는 중인 글자를 [page]쪽 위에 파랗게 비춘다 (캔버스는 쪽 좌표) */
    fun draw(c: Canvas, page: Int) {
        if (!active || this.page != page) return
        for (r in rects) c.drawRect(r[0], r[1], r[2], r[3], selPaint)
    }

    private companion object {
        /** 글자에서 이만큼(dp) 안을 눌러야 글자 끌기가 시작된다 */
        const val GRAB_DP = 14f
    }
}
