package com.dsviewer.app

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.core.view.drawToBitmap
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 툴바를 붙이는 자리 */
enum class ToolbarSide {
    TOP, BOTTOM, LEFT, RIGHT;

    /** 왼쪽·오른쪽이면 툴바를 세로로 세운다 */
    val vertical get() = this == LEFT || this == RIGHT
}

/**
 * 툴바 붙이기. 맨 앞 손잡이를 끌면 툴바 모양이 손가락을 따라오고,
 * 위·아래·왼쪽·오른쪽 가장자리 가까이 가면 붙을 자리가 파랗게 보이며, 놓으면 그쪽에 착 붙는다.
 * 왼쪽·오른쪽에 붙이면 툴바를 세로로 세운다 (가로 스크롤 → 세로 스크롤, 안의 칸들의 가로·세로를 맞바꿈).
 *
 * @param root 바깥 세로 줄: 탭 줄, (위 툴바), [docRow], (아래 툴바)
 * @param docRow 문서 화면 줄: (왼쪽 툴바), 문서, (오른쪽 툴바)
 */
@SuppressLint("ClickableViewAccessibility")
class ToolbarDock(
    private val root: LinearLayout,
    private val docRow: LinearLayout,
    private val toolbar: LinearLayout,
    private val handle: ImageView,
    private val scrollH: HorizontalScrollView,
    private val content: LinearLayout,
    private val onDocked: (ToolbarSide) -> Unit,
) {
    var side = ToolbarSide.BOTTOM
        private set

    private val density = root.resources.displayMetrics.density
    private fun dp(v: Float) = (v * density).roundToInt()

    /** 툴바 두께 (가로일 때 높이, 세로일 때 너비) */
    private val thickness = dp(56f)

    private val scrollV = ScrollView(root.context).apply {
        isVerticalScrollBarEnabled = false
        isFillViewport = true
        overScrollMode = scrollH.overScrollMode
    }

    init {
        handle.setOnTouchListener { _, ev -> onHandleTouch(ev) }
    }

    /** [s] 자리에 툴바를 붙인다 */
    fun dock(s: ToolbarSide) {
        side = s
        val v = s.vertical
        (toolbar.parent as? ViewGroup)?.removeView(toolbar)
        toolbar.orientation = if (v) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL

        // 가로면 가로 스크롤, 세로면 세로 스크롤에 담는다
        scrollH.isFillViewport = true
        val scroll = if (v) scrollV else scrollH
        if (content.parent !== scroll) {
            (content.parent as? ViewGroup)?.removeView(content)
            toolbar.removeView(if (v) scrollH else scrollV)
            scroll.addView(content)
            toolbar.addView(scroll)
        }
        scroll.layoutParams = if (v) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        content.layoutParams = if (v) FrameLayout.LayoutParams(thickness, ViewGroup.LayoutParams.WRAP_CONTENT)
        else FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, thickness)
        content.orientation = if (v) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
        // 스크롤할 내용이 뷰포트보다 짧을 때도 아이콘 묶음을 스크롤 축 가운데에 둔다.
        content.gravity = Gravity.CENTER
        if (v) content.setPadding(0, dp(8f), 0, dp(8f))
        else content.setPadding(dp(8f), 0, dp(8f), 0)
        for (i in 0 until content.childCount) orient(content.getChildAt(i), v)

        // 손잡이: 가로 툴바는 왼쪽 끝, 세로 툴바는 위쪽 끝
        handle.layoutParams = if (v) LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(24f))
        else LinearLayout.LayoutParams(dp(28f), thickness)
        handle.setImageResource(if (v) R.drawable.ic_drag_handle_v else R.drawable.ic_drag_handle)

        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT
        when (s) {
            ToolbarSide.TOP -> root.addView(toolbar, root.indexOfChild(docRow), LinearLayout.LayoutParams(match, wrap))
            ToolbarSide.BOTTOM -> root.addView(toolbar, LinearLayout.LayoutParams(match, wrap))
            ToolbarSide.LEFT -> docRow.addView(toolbar, 0, LinearLayout.LayoutParams(wrap, match))
            ToolbarSide.RIGHT -> docRow.addView(toolbar, LinearLayout.LayoutParams(wrap, match))
        }
        onDocked(s)
    }

    /** 코드로 새로 채운 줄(색 칸·굵기 칸)을 지금 툴바 방향에 맞춘다 */
    fun fit(row: View) = orient(row, side.vertical)

    /**
     * 뷰를 가로/세로 툴바에 맞춘다: 너비↔높이, 앞 여백↔위 여백, 뒤 여백↔아래 여백을 맞바꾸고
     * 안에 든 줄(LinearLayout)도 방향을 바꾼다. 이미 맞춘 뷰는 건너뛴다 (가로가 기본)
     */
    private fun orient(view: View, vertical: Boolean) {
        val was = view.getTag(R.id.tag_vertical) == true
        if (was != vertical) {
            view.setTag(R.id.tag_vertical, vertical)
            (view.layoutParams as? ViewGroup.MarginLayoutParams)?.let { lp ->
                val w = lp.width
                lp.width = lp.height
                lp.height = w
                val start = lp.marginStart
                val end = lp.marginEnd
                val top = lp.topMargin
                val bottom = lp.bottomMargin
                lp.setMargins(top, start, bottom, end)
                lp.marginStart = top
                lp.marginEnd = bottom
                view.layoutParams = lp
            }
            if (view is LinearLayout) {
                view.orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
                view.gravity = if (vertical) Gravity.CENTER_HORIZONTAL else Gravity.CENTER_VERTICAL
            }
        }
        if (view is LinearLayout) for (i in 0 until view.childCount) orient(view.getChildAt(i), vertical)
    }

    // ================= 끌어 옮기기 =================

    private var ghost: BitmapDrawable? = null
    private val preview = GradientDrawable().apply {
        val primary = MaterialColors.getColor(root, androidx.appcompat.R.attr.colorPrimary)
        setColor((primary and 0x00FFFFFF) or 0x33000000)
        setStroke(dp(2f), primary)
        cornerRadius = dp(8f).toFloat()
    }
    /** 손가락이 툴바의 어디를 잡았는지 (고스트를 그만큼 비켜 그린다) */
    private var grabX = 0f
    private var grabY = 0f
    private var target: ToolbarSide? = null
    private val rootLoc = IntArray(2)

    private fun onHandleTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> startDrag(ev)
            MotionEvent.ACTION_MOVE -> moveDrag(ev)
            MotionEvent.ACTION_UP -> endDrag(drop = true)
            MotionEvent.ACTION_CANCEL -> endDrag(drop = false)
        }
        return true
    }

    private fun startDrag(ev: MotionEvent) {
        root.getLocationOnScreen(rootLoc)
        grabX = ev.x + handle.left
        grabY = ev.y + handle.top
        // 툴바 앞부분(손잡이 쪽)만 떼어 고스트로. 세로면 위쪽 부분
        val full = toolbar.drawToBitmap()
        val keep = dp(360f)
        val part = if (side.vertical) Bitmap.createBitmap(full, 0, 0, full.width, min(full.height, keep))
        else Bitmap.createBitmap(full, 0, 0, min(full.width, keep), full.height)
        ghost = BitmapDrawable(root.resources, part).apply { alpha = 220 }
        root.overlay.add(preview)
        root.overlay.add(ghost!!)
        preview.bounds = Rect()
        target = null
        toolbar.alpha = 0.4f
        handle.isPressed = true
        moveDrag(ev)
    }

    private fun moveDrag(ev: MotionEvent) {
        val g = ghost ?: return
        val x = ev.rawX - rootLoc[0]
        val y = ev.rawY - rootLoc[1]
        val gl = (x - grabX).roundToInt()
        val gt = (y - grabY).roundToInt()
        g.setBounds(gl, gt, gl + g.bitmap.width, gt + g.bitmap.height)

        val area = workArea()
        val snap = max(dp(96f), (min(area.width(), area.height()) * 0.18f).roundToInt())
        val near = listOf(
            ToolbarSide.TOP to y - area.top,
            ToolbarSide.BOTTOM to area.bottom - y,
            ToolbarSide.LEFT to x - area.left,
            ToolbarSide.RIGHT to area.right - x,
        ).minBy { it.second }
        val t = near.first.takeIf { near.second < snap }
        if (t != target) {
            target = t
            if (t != null) root.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        }
        preview.bounds = t?.let { dockRect(it, area) } ?: Rect()
        root.invalidate()
    }

    private fun endDrag(drop: Boolean) {
        ghost?.let { root.overlay.remove(it) }
        ghost = null
        root.overlay.remove(preview)
        handle.isPressed = false
        toolbar.alpha = 1f
        val t = target
        target = null
        if (!drop || t == null || t == side) return
        dock(t)
        root.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
        // 새 자리에서 살짝 나타나며 붙는다
        toolbar.alpha = 0f
        toolbar.animate().alpha(1f).setDuration(150).start()
    }

    /** 툴바가 붙을 수 있는 영역 (탭 줄 아래, 시스템 막대 안쪽). root 좌표 */
    private fun workArea(): Rect {
        val tabBar = root.getChildAt(0)
        return Rect(root.paddingLeft, tabBar.bottom, root.width - root.paddingRight, root.height - root.paddingBottom)
    }

    /** [s]에 붙였을 때 툴바가 차지할 자리 (미리 보기) */
    private fun dockRect(s: ToolbarSide, a: Rect) = when (s) {
        ToolbarSide.TOP -> Rect(a.left, a.top, a.right, a.top + thickness)
        ToolbarSide.BOTTOM -> Rect(a.left, a.bottom - thickness, a.right, a.bottom)
        ToolbarSide.LEFT -> Rect(a.left, a.top, a.left + thickness, a.bottom)
        ToolbarSide.RIGHT -> Rect(a.right - thickness, a.top, a.right, a.bottom)
    }
}
