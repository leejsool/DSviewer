package com.dsviewer.app

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.SoundEffectConstants
import android.view.View
import android.view.ViewConfiguration
import kotlin.math.hypot
import kotlin.math.min

/**
 * 포스트잇 메모와 오답 배지의 입력·그리기.
 * 접힌 메모: 톡 누르면 펼치고, 꾹 누른 채 끌면 옮긴다. 펼친 메모: 띠의 단추(색 · 지우기 · 접기),
 * 띠를 끌면 옮기고, 몸통을 누르면 글 고치기. 손가락으로 메모에서 시작해 움직이면 그대로 문서 넘기기.
 * 끌기 한계·칸 자리 계산은 [NoteMath]. 문서 화면([view])의 상태는 [Host]로 읽는다.
 */
internal class StickyNoteController(
    private val view: View,
    private val host: Host,
    private val accent: Int,
) {

    interface Host {
        val ink: InkDocument?
        val readOnly: Boolean
        val scale: Float
        val rightMargin: Float
        val pageCount: Int
        fun pageWidth(page: Int): Float
        fun pageHeight(page: Int): Float
        /** 화면 점 아래의 (쪽, 쪽 x, 쪽 y). [wide]면 바깥 여백까지. 없으면 null */
        fun hitPage(sx: Float, sy: Float, wide: Boolean): Triple<Int, Float, Float>?
        /** 문서 위 글 상자로 고치는 중이라 그리지 않는 글 */
        fun isHidden(st: Stroke): Boolean
        /** 끌어 옮기는 선택에 들어 있는지 */
        fun isSelected(st: Stroke): Boolean
        fun clearSelection()
        fun stopFling()
        fun cancelZoomAnimation()
        /** 손가락이 메모에서 시작해 움직임: 제스처 감지기에 이벤트를 준다 */
        fun forwardToGestures(ev: MotionEvent)
        /** 손가락으로도 필기하는 중인가 (손가락 필기 옵션) */
        val fingerDrawing: Boolean
        /**
         * 오답 버튼에서 시작한 손가락이 끌려 움직임: 버튼을 누른 것이 아니라 선을 긋는 것이므로, 처음 누른 자리부터
         * ([downX], [downY], [downTime]) 필기로 넘겨 이 움직임부터 이어 긋게 한다
         */
        fun startFingerPen(ev: MotionEvent, downX: Float, downY: Float, downTime: Long)
        fun drawStroke(c: Canvas, st: Stroke)
        fun onNotePlacementEnded()
        fun onNoteEdit(page: Int, st: Stroke)
        fun onWrongTap(page: Int, x: Float, y: Float)
        fun onNoteColorPicked(color: Int)
    }

    private val density = view.resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(view.context).scaledTouchSlop

    /** 새 메모 색 (마지막으로 고른 색) */
    var color = StickyNote.COLORS[0]

    /** 누르고 있는 메모와 그 쪽 */
    private var track: Stroke? = null
    private var trackPage = -1
    private var mode = Touch.NONE
    private var button: NoteButton? = null
    private var swatch = -1
    private var finger = false
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    /** 메모를 끌어 옮기는 중 (손가락이 움직인 거리, 화면 px) */
    private var dragging = false
    private var dx = 0f
    private var dy = 0f
    private val delta = FloatArray(2)
    /** 색 고르기 칸을 띄운 메모 */
    private var palette: Stroke? = null
    private val box = RectF()
    private val swatchFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val swatchLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    /** 배지와 원문을 잇는 점선 */
    private val linkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = 0xFF78909C.toInt()
    }
    private val linkDot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF78909C.toInt() }
    private val linkPath = Path()

    private enum class Touch { NONE, ICON, HEADER, BUTTON, BODY, SWATCH, SCROLL, BADGE, LINK }

    /** 배지를 누른 쪽 좌표 (톡 눌렀을 때 오답 쪽으로 가는 데 쓴다) */
    private var downPx = 0f
    private var downPy = 0f

    /** 접힌 메모·배지를 꾹 누름: 끌어 옮기기 시작 */
    private val longPress = Runnable {
        if (track != null && (mode == Touch.ICON || mode == Touch.BADGE) && !moved && !host.readOnly) {
            dragging = true
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            view.invalidate()
        }
    }

    /** 포스트잇 붙일 곳을 고르는 중: 다음에 톡 누른 자리에 메모를 넣는다 (손가락으로 밀면 그동안 문서를 넘겨 볼 수 있다) */
    var placing = false
        private set
    private var placeTracking = false
    private var placeFinger = false
    private var placeMoved = false
    private var placeDownX = 0f
    private var placeDownY = 0f

    /** 새 문서를 열 때: 누르던 것·색 칸·붙일 곳 고르기를 모두 버린다 */
    fun reset() {
        resetTouch()
        palette = null
        cancelPlacement()
    }

    /** 색 고르기 칸을 닫는다 */
    fun closePalette() {
        palette = null
    }

    /** 포스트잇 붙일 곳 고르기를 시작한다 (끝나면 [Host.onNotePlacementEnded]) */
    fun startPlacement(): Boolean {
        if (host.ink == null || host.readOnly) return false
        host.clearSelection()
        palette = null
        placing = true
        view.invalidate()
        return true
    }

    fun cancelPlacement() {
        if (!placing) return
        placing = false
        placeTracking = false
        host.onNotePlacementEnded()
    }

    /** 쪽의 (x, y)를 왼쪽 위로 새 메모를 넣고(펼친 채, 쪽 안에 들게) 바로 글을 치게 한다. 실행 취소 가능 */
    private fun place(page: Int, x: Float, y: Float) {
        val inkDoc = host.ink ?: return
        val st = StickyNote.create(x, y, color, host.pageWidth(page), host.pageHeight(page))
        inkDoc.add(page, st)
        placing = false
        host.onNotePlacementEnded()
        view.invalidate()
        host.onNoteEdit(page, st)
    }

    /** 붙일 곳 고르는 동안의 터치: 톡 누르면 그 자리에, 손가락으로 밀면 문서 넘기기 */
    private fun onPlaceTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                placeTracking = true
                placeMoved = false
                placeFinger = ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                placeDownX = ev.x
                placeDownY = ev.y
                host.stopFling()
            }
            MotionEvent.ACTION_MOVE -> if (placeTracking) {
                if (!placeMoved && hypot(ev.x - placeDownX, ev.y - placeDownY) > touchSlop) {
                    placeMoved = true
                    if (placeFinger) {
                        val down = MotionEvent.obtain(ev)
                        down.action = MotionEvent.ACTION_DOWN
                        host.forwardToGestures(down)
                        down.recycle()
                    }
                } else if (placeMoved && placeFinger) host.forwardToGestures(ev)
            }
            MotionEvent.ACTION_UP -> if (placeTracking) {
                placeTracking = false
                if (placeMoved) {
                    if (placeFinger) host.forwardToGestures(ev)
                } else host.hitPage(ev.x, ev.y, false)?.let { (page, x, y) ->
                    // 쪽 바깥(쪽 사이 여백)을 누르면 다시 고르게 둔다
                    if (page in 0 until host.pageCount && x in 0f..host.pageWidth(page) && y in 0f..host.pageHeight(page)) place(page, x, y)
                }
            }
            MotionEvent.ACTION_CANCEL -> if (placeTracking) {
                placeTracking = false
                if (placeMoved && placeFinger) host.forwardToGestures(ev)
            }
        }
        return true
    }

    /** 메모 글 고치기 (실행 취소 가능) */
    fun setText(page: Int, old: Stroke, text: String) {
        val inkDoc = host.ink ?: return
        if (old.note == null || old.note?.text == text || page !in inkDoc.pages.indices) return
        inkDoc.replace(page, old, StickyNote.withText(old, text))
        view.invalidate()
    }

    /**
     * 쪽 좌표 (x, y)의 오답 배지. 손가락이거나 읽기 모드거나 바깥 여백에 있는 배지만
     * (펜으로 쪽 안에 놓인 배지 위에 쓸 수 있게)
     */
    private fun badgeAt(page: Int, x: Float, y: Float, ev: MotionEvent): Stroke? {
        val isFinger = ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        val slop = 4f * density / host.scale
        if (page !in 0 until host.pageCount) return null
        val pw = host.pageWidth(page)
        return host.ink?.pages?.getOrNull(page)?.lastOrNull { st ->
            st.isWrongBadge() && wrongBounds(st).apply { inset(-slop, -slop) }.contains(x, y) &&
                (isFinger || host.readOnly || x > pw)
        }
    }

    /**
     * 쪽 좌표 (x, y)의 오답 쪽 머리줄 '원문 보기 ›' 자리(머리줄 오른쪽 [WrongNote.LINK_W]). 손가락이거나 읽기 모드일 때만
     * (펜은 그 위에도 쓸 수 있게). 여기를 손가락으로 톡 누르는 것은 점을 찍으려는 것이 아니라 이동하려는 것이다
     */
    private fun headerLinkAt(page: Int, x: Float, y: Float, ev: MotionEvent): Stroke? {
        val isFinger = ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        if (!isFinger && !host.readOnly) return null
        val slop = 4f * density / host.scale
        if (page !in 0 until host.pageCount) return null
        return host.ink?.pages?.getOrNull(page)?.lastOrNull { st ->
            st.image != null && st.count >= 4 && st.role?.startsWith(WrongNote.ROLE_HEADER) == true &&
                wrongBounds(st).let { b ->
                    y >= b.top - slop && y <= b.bottom + slop && x <= b.right + slop && x >= b.right - WrongNote.LINK_W - slop
                }
        }
    }

    /** 쪽 좌표 (x, y)에 닿는 맨 위 메모. 접힌 메모는 [slop]만큼 넉넉히 */
    private fun noteAt(page: Int, x: Float, y: Float, slop: Float): Stroke? =
        host.ink?.pages?.getOrNull(page)?.lastOrNull { st ->
            val n = st.note ?: return@lastOrNull false
            if (n.collapsed) st.noteIconRect(box).apply { inset(-slop, -slop) }.contains(x, y)
            else st.noteRect(box).contains(x, y)
        }

    /** 쪽 좌표 (x, y)에 닿는 맨 위 펼친 메모 (그 아래는 보이지 않으므로 지우개가 건드리지 않게) */
    fun expandedNoteAt(page: Int, x: Float, y: Float): Stroke? =
        host.ink?.pages?.getOrNull(page)?.lastOrNull { st -> st.note?.collapsed == false && st.noteRect(box).contains(x, y) }

    /**
     * 끌어 옮기는 거리 (쪽 좌표). 메모는 접힌 네모가 쪽 안에, 펼친 몸통은 바깥 여백까지만.
     * 배지는 쪽과 바깥 여백 안에서
     */
    private fun computeDelta(st: Stroke, page: Int, out: FloatArray) {
        val pw = host.pageWidth(page)
        val ph = host.pageHeight(page)
        val n = st.note
        val limits = if (n == null) {
            val b = wrongBounds(st)
            NoteMath.badgeLimits(b.left, b.top, b.right, b.bottom, pw, ph, host.rightMargin)
        } else {
            NoteMath.noteLimits(
                st.x(0), st.y(0), st.x(1), st.y(1), n.collapsed, n.h, pw, ph, StickyNote.ICON, stickyMaxLeft(pw, n.w),
            )
        }
        val d = NoteMath.clampDelta(limits, dx, dy, host.scale)
        out[0] = d[0]
        out[1] = d[1]
    }

    /** 배지와 포스트잇 그리기 (쪽 좌표 캔버스, 바깥 여백까지). 끌어 옮기는 중이면 손가락을 따라 */
    fun drawMarginItems(c: Canvas, inkDoc: InkDocument, page: Int, selDragging: Boolean) {
        val list = inkDoc.pages[page]
        val pw = host.pageWidth(page)
        fun shown(st: Stroke) = !host.isHidden(st) && !(selDragging && host.isSelected(st))
        fun dragged(st: Stroke): Boolean {
            val drag = st === track && dragging
            if (drag) computeDelta(st, page, delta) else { delta[0] = 0f; delta[1] = 0f }
            return drag
        }
        for (st in list) if (st.isWrongBadge() && shown(st)) {
            dragged(st)
            drawBadgeLink(c, inkDoc, st, page, pw, delta[0], delta[1])
        }
        for (st in list) if (st.isWrongBadge() && shown(st)) {
            c.save()
            dragged(st)
            c.translate(delta[0], delta[1])
            host.drawStroke(c, st)
            c.restore()
        }
        for (st in list) if (st.note != null && shown(st)) {
            c.save()
            dragged(st)
            c.translate(delta[0], delta[1])
            host.drawStroke(c, st)
            c.restore()
        }
        palette?.let { if (it.note?.collapsed == false && list.contains(it)) drawPalette(c, it) }
    }

    /** 배지에서 원문 자리까지 점선: 문제 영역 오른쪽 위에서 쪽 끝까지 가로로 가다가 배지로 비스듬히 */
    private fun drawBadgeLink(c: Canvas, inkDoc: InkDocument, badge: Stroke, page: Int, pw: Float, dx: Float, dy: Float) {
        val n = badge.role?.substring(2)?.toIntOrNull() ?: return
        val e = inkDoc.wrongByNumber(n) ?: return
        val src = e.srcRect ?: return
        if (e.srcList !== inkDoc.pages[page]) return
        val b = wrongBounds(badge)
        val px = density / host.scale  // 화면 1dp에 해당하는 쪽 좌표 길이
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
        val s = NoteMath.swatchRect(r.left, r.top, r.width(), StickyNote.HEADER, k, StickyNote.COLORS.size)
        return out.apply { set(s[0], s[1], s[2], s[3]) }
    }

    /** 펼친 메모 띠 아래의 색 고르기 칸 (쪽 좌표 캔버스) */
    private fun drawPalette(c: Canvas, st: Stroke) {
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
            swatchLine.color = if (on) accent else Color.argb(90, 0, 0, 0)
            swatchLine.strokeWidth = if (on) 2f else 0.8f
            c.drawCircle(b.centerX(), b.centerY(), rad, swatchLine)
        }
    }

    private fun resetTouch() {
        view.removeCallbacks(longPress)
        track = null
        trackPage = -1
        mode = Touch.NONE
        button = null
        swatch = -1
        moved = false
        dragging = false
        dx = 0f
        dy = 0f
    }

    /** 메모를 누른 동작 (손가락·펜 모두). 메모가 아닌 곳에서 시작했으면 false */
    fun onTouch(ev: MotionEvent): Boolean {
        if (placing || placeTracking) return onPlaceTouch(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                resetTouch()
                val hit = host.hitPage(ev.x, ev.y, true)
                // 색 고르기 칸이 떠 있으면 먼저 그 칸을 본다. 다른 곳을 누르면 닫는다
                palette?.let { pn ->
                    if (hit != null && host.ink?.pages?.getOrNull(hit.first)?.contains(pn) == true) {
                        val b = RectF()
                        for (k in StickyNote.COLORS.indices) if (swatchRect(pn, k, b).contains(hit.second, hit.third)) {
                            begin(ev, pn, hit.first, Touch.SWATCH)
                            swatch = k
                            return true
                        }
                    }
                    palette = null
                    view.invalidate()
                }
                hit ?: return false
                val st = noteAt(hit.first, hit.second, hit.third, 6f * density / host.scale) ?: run {
                    // 오답 쪽 머리줄의 '원문 보기 ›': 손가락으로 톡 누르면 어떤 도구에서든 바로 원문 쪽으로
                    val badge = badgeAt(hit.first, hit.second, hit.third, ev)
                    if (badge == null) {
                        val link = headerLinkAt(hit.first, hit.second, hit.third, ev) ?: return false
                        begin(ev, link, hit.first, Touch.LINK)
                        downPx = hit.second
                        downPy = hit.third
                        view.invalidate()
                        return true
                    }
                    // 오답 배지: 톡 누르면 오답 쪽으로, 꾹 누르면 끌어 옮긴다
                    begin(ev, badge, hit.first, Touch.BADGE)
                    downPx = hit.second
                    downPy = hit.third
                    if (!host.readOnly) view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    view.invalidate()
                    return true
                }
                val n = st.note!!
                val m = when {
                    n.collapsed -> Touch.ICON
                    hit.third < st.y(1) + StickyNote.HEADER -> {
                        button = st.noteButtonAt(hit.second, hit.third)
                        if (button != null) Touch.BUTTON else Touch.HEADER
                    }
                    else -> Touch.BODY
                }
                begin(ev, st, hit.first, m)
                if (m == Touch.ICON && !host.readOnly) view.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                view.invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                track ?: return false
                if (!moved && hypot(ev.x - downX, ev.y - downY) > touchSlop) {
                    // 오답 버튼(배지·'원문 보기') 위에서 손가락으로 선을 그으면 이동하지 않고 그 자리부터 그대로 그어진다
                    // (손가락 필기를 켜 두었을 때. 꺼 두었으면 아래처럼 문서 넘기기)
                    if (!dragging && finger && (mode == Touch.BADGE || mode == Touch.LINK) && host.fingerDrawing && !host.readOnly) {
                        val x0 = downX
                        val y0 = downY
                        view.removeCallbacks(longPress)
                        resetTouch()
                        host.startFingerPen(ev, x0, y0, ev.downTime)
                        view.invalidate()
                        return false
                    }
                    moved = true
                    view.removeCallbacks(longPress)
                    when {
                        dragging -> {}
                        // 펼친 메모는 띠를 끌면 바로 옮긴다
                        mode == Touch.HEADER && !host.readOnly -> dragging = true
                        // 손가락이면 문서 넘기기로 (제스처 감지기는 DOWN을 못 받았으므로 지금 자리에서 새로 시작)
                        finger -> {
                            mode = Touch.SCROLL
                            val down = MotionEvent.obtain(ev)
                            down.action = MotionEvent.ACTION_DOWN
                            host.forwardToGestures(down)
                            down.recycle()
                        }
                        else -> mode = Touch.NONE
                    }
                }
                if (dragging) {
                    dx = ev.x - downX
                    dy = ev.y - downY
                    view.invalidate()
                } else if (mode == Touch.SCROLL) host.forwardToGestures(ev)
                return true
            }
            MotionEvent.ACTION_UP -> {
                val st = track ?: return false
                view.removeCallbacks(longPress)
                when {
                    dragging -> {
                        computeDelta(st, trackPage, delta)
                        if (delta[0] != 0f || delta[1] != 0f) host.ink?.move(listOf(st), delta[0], delta[1])
                    }
                    mode == Touch.SCROLL -> host.forwardToGestures(ev)
                    !moved -> tapped(st, trackPage)
                }
                resetTouch()
                view.invalidate()
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                track ?: return false
                if (mode == Touch.SCROLL) host.forwardToGestures(ev)
                resetTouch()
                view.invalidate()
                return true
            }
        }
        return track != null
    }

    private fun begin(ev: MotionEvent, st: Stroke, page: Int, m: Touch) {
        track = st
        trackPage = page
        mode = m
        finger = ev.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        downX = ev.x
        downY = ev.y
        host.stopFling()
        host.cancelZoomAnimation()
        host.clearSelection()
    }

    /** 메모를 톡 누름. 읽기 모드에서는 펼치고 접기만 */
    private fun tapped(st: Stroke, page: Int) {
        val inkDoc = host.ink ?: return
        if (mode == Touch.BADGE || mode == Touch.LINK) {
            host.onWrongTap(page, downPx, downPy)
            return
        }
        val n = st.note ?: return
        if (page !in 0 until host.pageCount) return
        view.playSoundEffect(SoundEffectConstants.CLICK)
        // 접고 펼친 상태는 저장할 때 같이 남지만, 그것만으로 '저장 안 한 변경'이 생기지는 않는다 (읽으려고 펼쳐 볼 때)
        when (mode) {
            Touch.ICON -> {
                n.collapsed = false
                st.fitNoteInPage(host.pageWidth(page), host.pageHeight(page))
            }
            Touch.BUTTON -> when (button) {
                NoteButton.COLLAPSE -> {
                    n.collapsed = true
                    palette = null
                }
                NoteButton.COLOR -> if (!host.readOnly) palette = if (palette === st) null else st
                NoteButton.DELETE -> if (!host.readOnly) {
                    palette = null
                    inkDoc.remove(page, listOf(st))
                }
                null -> {}
            }
            Touch.BODY -> if (!host.readOnly) host.onNoteEdit(page, st)
            Touch.SWATCH -> {
                val c = StickyNote.COLORS.getOrNull(swatch) ?: return
                if (c != st.color) inkDoc.edit(listOf(st)) { st.recolor(c) }
                color = c
                host.onNoteColorPicked(c)
                palette = null
            }
            else -> {}
        }
        view.invalidate()
    }
}
