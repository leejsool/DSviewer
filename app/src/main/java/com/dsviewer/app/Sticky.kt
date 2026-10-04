package com.dsviewer.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import androidx.core.graphics.ColorUtils
import kotlin.math.max
import kotlin.math.min

/**
 * 포스트잇 메모 (삽입 ▸ 포스트잇 메모). [Stroke.note]가 있는 PEN 획으로 쪽에 넣는다.
 * 획의 점 0은 접힌 메모(작은 네모)의 왼쪽 위, 점 1은 펼친 메모의 왼쪽 위 (쪽 좌표). 색은 [Stroke.color].
 * 펼치면 맨 위 띠에 단추(색 · 지우기 · 접기)가 있고, 아래 몸통에 친 글이 보인다. 몸통을 누르면 글을 고친다.
 * PDF에는 표준 메모 주석(/Text)으로 저장해 다른 앱에서도 아이콘을 누르면 글이 보인다
 */
class StickyNote(val text: String, val w: Float = DEFAULT_W, val h: Float = DEFAULT_H) {
    /** 접혀 있는지 (저장한다: 다시 열어도 접힌 채로, 원문을 가리지 않게) */
    var collapsed = false

    fun copy(text: String = this.text) = StickyNote(text, w, h).also { it.collapsed = collapsed }

    // 글 배치는 한 번만 (글이 바뀌면 메모를 새로 만든다). 쪽 미리보기가 다른 스레드에서 같이 그릴 수 있어 잠근다
    private var layout: StaticLayout? = null
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
        color = TEXT_COLOR
        typeface = Typeface.DEFAULT
    }

    fun drawText(c: Canvas, left: Float, top: Float) {
        if (text.isEmpty()) return
        synchronized(this) { drawTextLocked(c, left, top) }
    }

    private fun drawTextLocked(c: Canvas, left: Float, top: Float) {
        // 배치는 Q배 크게 (pt 단위 그대로면 글꼴 높이 반올림 오차가 크다)
        val l = layout ?: run {
            textPaint.textSize = TEXT_SIZE * Q
            val width = ((w - PAD * 2) * Q).toInt().coerceAtLeast(1)
            StaticLayout.Builder.obtain(text, 0, text.length, textPaint, width)
                .setIncludePad(false)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .build()
        }.also { layout = it }
        c.save()
        c.clipRect(left, top, left + w - PAD, top + h - HEADER - PAD)
        c.translate(left + PAD, top + PAD)
        c.scale(1f / Q, 1f / Q)
        l.draw(c)
        c.restore()
    }

    companion object {
        /** 펼친 메모 크기 (pt) */
        const val DEFAULT_W = 170f
        const val DEFAULT_H = 150f
        /** 접힌 메모 한 변 (pt) */
        const val ICON = 26f
        /** 펼친 메모 맨 위 단추 띠 높이 (pt) */
        const val HEADER = 26f
        const val PAD = 7f
        const val TEXT_SIZE = 12f
        private const val Q = 8f
        private const val TEXT_COLOR = 0xFF33302A.toInt()

        /** 파스텔 포스트잇 색: 노랑 · 분홍 · 연두 · 하늘 · 보라 · 살구 */
        val COLORS = intArrayOf(
            0xFFFFF59D.toInt(), 0xFFFFCDD8.toInt(), 0xFFCDEFC4.toInt(),
            0xFFC6E3FA.toInt(), 0xFFE2D3F5.toInt(), 0xFFFFDDB8.toInt(),
        )

        /** 쪽 (px, py) 자리에 새 메모 획 (펼친 채). 쪽 크기 [pw]×[ph] 안에 들게 */
        fun create(px: Float, py: Float, color: Int, pw: Float, ph: Float): Stroke {
            val note = StickyNote("")
            val x = px.coerceIn(0f, stickyMaxLeft(pw, note.w))
            val y = py.coerceIn(0f, max(0f, ph - note.h))
            return Stroke(Tool.PEN, color, 0f).apply {
                this.note = note
                add(x, y, 1f)
                add(x, y, 1f)
            }
        }

        /** 글만 바꾼 새 메모 획 (자리·색·접힘은 그대로). 실행 취소할 수 있게 획을 바꿔 넣는다 */
        fun withText(st: Stroke, text: String): Stroke = Stroke(Tool.PEN, st.color, 0f).apply {
            note = st.note!!.copy(text)
            add(st.x(0), st.y(0), 1f)
            add(st.x(1), st.y(1), 1f)
        }
    }
}

/**
 * 쪽 오른쪽에 붙는 바깥 여백 너비 (pt). 필기는 할 수 없고, 오답 배지와 펼친 포스트잇이 쪽 밖으로 벗어나 놓인다.
 * 문서에 배지나 포스트잇이 있으면 모든 쪽에 붙는다 ([DocumentView.refreshMargin])
 */
const val PAGE_SIDE_MARGIN = 100f

/** 펼친 메모 띠의 단추 */
enum class NoteButton { COLOR, DELETE, COLLAPSE }

/** 펼친 메모의 왼쪽 위가 놓일 수 있는 가장 오른쪽 (쪽 너비 [pw]): 접힌 네모는 쪽 안에, 펼친 몸통은 바깥 여백까지 */
fun stickyMaxLeft(pw: Float, w: Float) = max(0f, min(pw - StickyNote.ICON, pw + PAGE_SIDE_MARGIN - w))

/** 접힌 메모 자리 (쪽 좌표) */
fun Stroke.noteIconRect(out: RectF): RectF = out.apply { set(x(0), y(0), x(0) + StickyNote.ICON, y(0) + StickyNote.ICON) }

/** 펼친 메모 자리 (쪽 좌표) */
fun Stroke.noteRect(out: RectF): RectF {
    val n = note!!
    return out.apply { set(x(1), y(1), x(1) + n.w, y(1) + n.h) }
}

/** 펼친 메모 띠의 단추 자리: 왼쪽에 색 · 지우기, 오른쪽 끝에 접기 */
fun Stroke.noteButtonRect(b: NoteButton, out: RectF): RectF {
    val n = note!!
    val s = StickyNote.HEADER
    val left = when (b) {
        NoteButton.COLOR -> x(1)
        NoteButton.DELETE -> x(1) + s
        NoteButton.COLLAPSE -> x(1) + n.w - s
    }
    return out.apply { set(left, y(1), left + s, y(1) + s) }
}

/** 펼친 메모에서 (x, y)에 있는 단추 */
fun Stroke.noteButtonAt(x: Float, y: Float): NoteButton? {
    val r = RectF()
    return NoteButton.entries.firstOrNull { noteButtonRect(it, r).contains(x, y) }
}

/** 메모를 펼칠 때: 펼친 메모가 쪽(과 바깥 여백) 밖으로 나가면 안으로 들인다 */
fun Stroke.fitNoteInPage(pw: Float, ph: Float) {
    val n = note ?: return
    val x = x(1).coerceIn(0f, stickyMaxLeft(pw, n.w))
    val y = y(1).coerceIn(0f, max(0f, ph - n.h))
    if (x != x(1) || y != y(1)) offsetPoint(1, x - x(1), y - y(1))
}

private val notePaint = Paint(Paint.ANTI_ALIAS_FLAG)
private val noteLine = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    style = Paint.Style.STROKE
    strokeCap = Paint.Cap.ROUND
    strokeJoin = Paint.Join.ROUND
}
private val noteRectTmp = RectF()
private val notePath = Path()

/** 메모 그리기 (쪽 좌표 캔버스). 접혔으면 작은 포스트잇, 펼쳤으면 띠·단추·글이 있는 큰 포스트잇 */
fun drawStickyNote(c: Canvas, st: Stroke, alphaMul: Float = 1f) {
    synchronized(notePaint) { drawNoteLocked(c, st, alphaMul) }
}

private fun drawNoteLocked(c: Canvas, st: Stroke, alphaMul: Float) {
    val n = st.note ?: return
    if (st.count < 2) return
    val a = (255 * alphaMul).toInt().coerceIn(0, 255)
    val base = st.color or 0xFF000000.toInt()
    val edge = ColorUtils.blendARGB(base, Color.BLACK, 0.28f)
    val ink = ColorUtils.blendARGB(base, Color.BLACK, 0.6f)
    if (n.collapsed) {
        val r = st.noteIconRect(noteRectTmp)
        val fold = r.width() * 0.32f
        // 그림자
        notePaint.color = Color.argb(a * 45 / 255, 0, 0, 0)
        c.drawRect(r.left + 1.2f, r.top + 1.6f, r.right + 1.2f, r.bottom + 1.6f, notePaint)
        // 오른쪽 아래가 접힌 종이
        notePath.reset()
        notePath.moveTo(r.left, r.top)
        notePath.lineTo(r.right, r.top)
        notePath.lineTo(r.right, r.bottom - fold)
        notePath.lineTo(r.right - fold, r.bottom)
        notePath.lineTo(r.left, r.bottom)
        notePath.close()
        notePaint.color = ColorUtils.setAlphaComponent(base, a)
        c.drawPath(notePath, notePaint)
        notePath.reset()
        notePath.moveTo(r.right, r.bottom - fold)
        notePath.lineTo(r.right - fold, r.bottom - fold)
        notePath.lineTo(r.right - fold, r.bottom)
        notePath.close()
        notePaint.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(base, Color.BLACK, 0.15f), a)
        c.drawPath(notePath, notePaint)
        noteLine.color = ColorUtils.setAlphaComponent(edge, a)
        noteLine.strokeWidth = 0.8f
        c.drawRect(r.left, r.top, r.right, r.bottom - fold, noteLine)
        // 글이 있으면 줄 세 개
        if (n.text.isNotEmpty()) {
            noteLine.color = ColorUtils.setAlphaComponent(ink, a)
            noteLine.strokeWidth = 1.4f
            val x0 = r.left + r.width() * 0.2f
            for (k in 0 until 3) {
                val y = r.top + r.height() * (0.28f + 0.18f * k)
                val x1 = r.right - r.width() * (if (k == 2) 0.45f else 0.2f)
                c.drawLine(x0, y, x1, y, noteLine)
            }
        }
        return
    }
    val r = st.noteRect(noteRectTmp)
    val hdr = StickyNote.HEADER
    // 그림자 · 종이 · 띠
    notePaint.color = Color.argb(a * 40 / 255, 0, 0, 0)
    c.drawRect(r.left + 2f, r.top + 3f, r.right + 2f, r.bottom + 3f, notePaint)
    notePaint.color = ColorUtils.setAlphaComponent(base, a)
    c.drawRect(r, notePaint)
    notePaint.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(base, Color.BLACK, 0.08f), a)
    c.drawRect(r.left, r.top, r.right, r.top + hdr, notePaint)
    noteLine.color = ColorUtils.setAlphaComponent(edge, a)
    noteLine.strokeWidth = 0.8f
    c.drawRect(r, noteLine)
    // 단추 아이콘 (선 그림)
    noteLine.color = ColorUtils.setAlphaComponent(ink, a)
    noteLine.strokeWidth = 1.5f
    val b = RectF()
    st.noteButtonRect(NoteButton.COLOR, b)
    run {
        // 색: 작은 동그라미 세 개 (팔레트)
        val cx = b.centerX(); val cy = b.centerY(); val rr = hdr * 0.13f
        for ((k, col) in listOf(StickyNote.COLORS[1], StickyNote.COLORS[2], StickyNote.COLORS[3]).withIndex()) {
            val ang = Math.toRadians(-90.0 + k * 120.0)
            val px = cx + (kotlin.math.cos(ang) * rr * 1.25).toFloat()
            val py = cy + (kotlin.math.sin(ang) * rr * 1.25).toFloat()
            notePaint.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(col, Color.BLACK, 0.12f), a)
            c.drawCircle(px, py, rr, notePaint)
            c.drawCircle(px, py, rr, noteLine.apply { strokeWidth = 0.7f })
        }
        noteLine.strokeWidth = 1.5f
    }
    st.noteButtonRect(NoteButton.DELETE, b)
    run {
        // 지우기: 휴지통
        val cx = b.centerX(); val cy = b.centerY(); val s = hdr * 0.17f
        c.drawLine(cx - s * 1.3f, cy - s, cx + s * 1.3f, cy - s, noteLine)
        c.drawLine(cx - s * 0.4f, cy - s * 1.45f, cx + s * 0.4f, cy - s * 1.45f, noteLine)
        notePath.reset()
        notePath.moveTo(cx - s, cy - s)
        notePath.lineTo(cx - s * 0.75f, cy + s * 1.4f)
        notePath.lineTo(cx + s * 0.75f, cy + s * 1.4f)
        notePath.lineTo(cx + s, cy - s)
        c.drawPath(notePath, noteLine)
    }
    st.noteButtonRect(NoteButton.COLLAPSE, b)
    run {
        // 접기: 오른쪽 위로 모이는 화살표 둘 (줄이기)
        val cx = b.centerX(); val cy = b.centerY(); val s = hdr * 0.2f
        c.drawLine(cx - s, cy + s, cx + s * 0.1f, cy - s * 0.1f, noteLine)
        c.drawLine(cx + s * 0.1f, cy - s * 0.1f, cx - s * 0.75f, cy - s * 0.1f, noteLine)
        c.drawLine(cx + s * 0.1f, cy - s * 0.1f, cx + s * 0.1f, cy + s * 0.75f, noteLine)
        c.drawLine(cx + s * 0.5f, cy - s * 0.5f, cx + s, cy - s, noteLine)
    }
    if (n.text.isEmpty()) {
        // 빈 메모: 누르면 글을 칠 수 있다는 안내
        notePaint.color = ColorUtils.setAlphaComponent(ColorUtils.blendARGB(base, Color.BLACK, 0.4f), a)
        notePaint.textSize = 11f
        notePaint.textAlign = Paint.Align.CENTER
        c.drawText("눌러서 메모 입력", r.centerX(), r.top + hdr + (r.height() - hdr) / 2f + 4f, notePaint)
        notePaint.textAlign = Paint.Align.LEFT
    } else n.drawText(c, r.left, r.top + hdr)
}
