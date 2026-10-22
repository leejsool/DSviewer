package com.dsviewer.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.drawable.GradientDrawable
import android.animation.ValueAnimator
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.max
import kotlin.math.min

/** 긴 탭 이름 자동 흘리기: 처음·끝에서 멈추는 시간, 흐르는 속도(dp/초)와 가장 짧은 흐름 시간, 처음으로 돌아오는 시간 (ms) */
private const val MARQUEE_HOLD_MS = 1200f
private const val MARQUEE_DP_PER_SEC = 36f
private const val MARQUEE_MIN_SCROLL_MS = 800f
private const val MARQUEE_BACK_MS = 300f

/**
 * 크롬 브라우저 모양의 탭 막대.
 * 선택한 탭은 윗모서리가 둥글고 아래쪽이 바깥으로 휘어져 바로 아래 화면(colorSurface)과 이어진다.
 * 막대 바탕은 한 단계 어두운 색이어야 한다 (레이아웃에서 부모 배경으로 준다).
 * 탭이 많으면 탭 너비가 줄어들고, 최소 너비보다 좁아지면 옆으로 밀린다. ＋ 버튼은 늘 탭 바로 오른쪽.
 * 폭이 좁은 화면(휴대폰 세로 등)에서는 탭을 작게 그리고, 고른 탭에만 × 단추를 둔다 (크롬처럼).
 * 탭을 길게 눌렀다가 그대로 옆으로 끌면 순서를 바꾼다.
 * 이름이 잘린 탭은 풀네임을 말풍선으로 본다: 이미 고른 탭을 한 번 더 누르거나, 펜·마우스를 탭 위에 잠시 둔다.
 */
class ChromeTabBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {

    interface Listener {
        fun onTabSelected(index: Int) {}
        fun onTabClose(index: Int) {}
        fun onAddTab() {}
        /** 탭을 끌어 [from]번에서 [to]번 자리로 옮겼다 (끌기를 놓았을 때 한 번) */
        fun onTabMoved(from: Int, to: Int) {}
    }

    var listener: Listener? = null
    var minTabWidthDp = 110f
    var maxTabWidthDp = 220f
    /** 좁은 화면에서 쓰는 작은 탭의 최소 너비 */
    var compactMinTabWidthDp = 104f

    /** 화면 폭이 이보다 좁으면 작은 탭 (휴대폰 세로) */
    private val compactBelowDp = 600
    private var compact = isCompactScreen()
    private val minTabPx get() = px(if (compact) compactMinTabWidthDp else minTabWidthDp)
    private val addButtonSpace get() = if (showAddButton) px(46f) else 0

    private val density = resources.displayMetrics.density
    private fun px(dp: Float) = (dp * density).toInt()

    private val scroll = EdgeArrowScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
    }
    private val row = LinearLayout(ctx).apply { orientation = HORIZONTAL }
    private val addButton = ImageButton(ctx)
    private val tabs = ArrayList<TabView>()

    var selectedIndex = -1
        private set
    val tabCount get() = tabs.size

    /** 고른 탭 색 = 탭 바로 아래 화면의 바탕색 (기본은 colorSurface) */
    var activeFill = MaterialColors.getColor(this, com.google.android.material.R.attr.colorSurface)
        set(v) { field = v; tabs.forEach { it.invalidate() } }
    private val textActive = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface)
    private val textInactive = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val dividerColor = MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutline)

    init {
        orientation = HORIZONTAL
        gravity = Gravity.BOTTOM
        scroll.addView(row, ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        addView(scroll, LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT))
        addButton.setImageResource(R.drawable.ic_add)
        addButton.contentDescription = "새 탭"
        addButton.setBackgroundResource(attrRes(android.R.attr.selectableItemBackgroundBorderless))
        addButton.setOnClickListener { listener?.onAddTab() }
        addButton.visibility = View.GONE
        addView(addButton, LayoutParams(px(44f), px(44f)).apply {
            gravity = Gravity.CENTER_VERTICAL
            marginStart = px(2f)
        })
    }

    private fun attrRes(attr: Int): Int {
        val tv = TypedValue()
        context.theme.resolveAttribute(attr, tv, true)
        return tv.resourceId
    }

    var showAddButton: Boolean
        get() = addButton.visibility == View.VISIBLE
        set(v) { addButton.visibility = if (v) View.VISIBLE else View.GONE }

    fun setAddEnabled(enabled: Boolean, description: String) {
        addButton.isEnabled = enabled
        addButton.alpha = if (enabled) 1f else 0.3f
        addButton.contentDescription = description
    }

    /** 탭을 뒤에 붙이고 그 번호를 돌려준다 */
    fun addTab(title: CharSequence, icon: Int = 0, iconTint: Int? = null, closable: Boolean = false): Int {
        val tv = TabView(closable)
        tv.setTitle(title)
        tv.setIcon(icon, iconTint)
        tv.applyCompact(compact)
        tabs.add(tv)
        row.addView(tv, LayoutParams(minTabPx, ViewGroup.LayoutParams.MATCH_PARENT))
        refreshStates()
        return tabs.size - 1
    }

    fun removeTab(i: Int) {
        if (i !in tabs.indices) return
        dismissTip()
        row.removeView(tabs.removeAt(i))
        if (selectedIndex == i) selectedIndex = -1 else if (selectedIndex > i) selectedIndex--
        refreshStates()
    }

    fun setTitle(i: Int, title: CharSequence) = tabs.getOrNull(i)?.setTitle(title)

    fun setIcon(i: Int, icon: Int, tint: Int?) = tabs.getOrNull(i)?.setIcon(icon, tint)

    /** 저장하지 않은 변경이 있는 탭에 디스켓 표시 */
    fun setUnsaved(i: Int, unsaved: Boolean) = tabs.getOrNull(i)?.setUnsaved(unsaved)

    /** i번 탭을 고른다. notify면 바뀌었을 때 listener.onTabSelected를 부른다 */
    fun select(i: Int, notify: Boolean = false) {
        if (i !in tabs.indices) return
        val changed = i != selectedIndex
        if (changed) dismissTip()
        selectedIndex = i
        refreshStates()
        post { scrollToSelected(smooth = true) }
        if (notify && changed) listener?.onTabSelected(i)
    }

    /** 고른 탭을 탭 줄 가운데로 (자리가 좁아도 고른 탭은 늘 보이게) */
    private fun scrollToSelected(smooth: Boolean) {
        val t = tabs.getOrNull(selectedIndex) ?: return
        val x = t.left - (scroll.width - t.width) / 2
        if (smooth) scroll.smoothScrollTo(x, 0) else scroll.scrollTo(x, 0)
    }

    // ----- 탭 끌어 순서 바꾸기 -----

    private var dragTab: TabView? = null
    private var dragFrom = -1
    private var dragTo = -1
    /** 탭을 잡은 자리: 탭 왼쪽 끝에서 손가락까지의 거리 */
    private var dragGrab = 0f
    private var dragRawX = 0f
    private val edgeScroll = object : Runnable {
        override fun run() {
            val t = dragTab ?: return
            val loc = IntArray(2)
            scroll.getLocationOnScreen(loc)
            val edge = px(36f)
            val step = px(10f)
            val dx = when {
                dragRawX < loc[0] + edge -> -step
                dragRawX > loc[0] + scroll.width - edge -> step
                else -> 0
            }
            if (dx != 0) {
                scroll.scrollBy(dx, 0)
                moveDrag(t)
            }
            postOnAnimation(this)
        }
    }

    private fun startDrag(t: TabView, rawX: Float) {
        if (tabs.size < 2) return
        dragTab = t
        dragFrom = tabs.indexOf(t)
        dragTo = dragFrom
        dragRawX = rawX
        val loc = IntArray(2)
        row.getLocationOnScreen(loc)
        dragGrab = rawX - loc[0] - t.left
        scroll.requestDisallowInterceptTouchEvent(true)
        parent?.requestDisallowInterceptTouchEvent(true)
        dismissTip()
        t.animate().scaleX(1.04f).scaleY(1.04f).setDuration(100).start()
        t.translationZ = px(6f).toFloat()
        postOnAnimation(edgeScroll)
    }

    /**
     * 손가락 자리에 맞춰 끌고 있는 탭을 움직인다. 이웃 탭은 비켜 주기만 하고(translationX),
     * 실제 순서는 놓을 때 바꾼다 (끄는 도중에 뷰를 빼고 넣으면 터치가 끊기므로)
     */
    private fun moveDrag(t: TabView) {
        val tw = t.width.takeIf { it > 0 } ?: return
        val loc = IntArray(2)
        row.getLocationOnScreen(loc)
        val desiredLeft = (dragRawX - loc[0] - dragGrab).coerceIn(0f, (tw * (tabs.size - 1)).toFloat())
        dragTo = ((desiredLeft + tw / 2f) / tw).toInt().coerceIn(0, tabs.lastIndex)
        t.translationX = desiredLeft - dragFrom * tw
        tabs.forEachIndexed { k, o ->
            if (o === t) return@forEachIndexed
            val want = when {
                k in (dragFrom + 1)..dragTo -> -tw.toFloat()
                k in dragTo until dragFrom -> tw.toFloat()
                else -> 0f
            }
            // 목표가 바뀔 때만 애니메이션을 시작한다 (손가락이 움직일 때마다 다시 시작하면 뚝뚝 끊긴다)
            if (o.slideTarget != want) {
                o.slideTarget = want
                o.animate().translationX(want).setDuration(160).setInterpolator(android.view.animation.DecelerateInterpolator()).start()
            }
        }
    }

    private fun endDrag(cancel: Boolean) {
        val t = dragTab ?: return
        dragTab = null
        removeCallbacks(edgeScroll)
        t.isPressed = false
        val to = if (cancel) dragFrom else dragTo
        val tw = t.width
        // 이웃 탭들은 이미 비켜 있으므로, 순서를 확정하며 위치 이동을 걷고, 끌던 탭은 놓인 자리로 미끄러져 들어간다
        val startX = t.translationX - (to - dragFrom) * tw
        tabs.forEach { it.animate().cancel(); it.translationX = 0f; it.slideTarget = 0f }
        if (to != dragFrom) {
            val selTab = tabs.getOrNull(selectedIndex)
            tabs.removeAt(dragFrom)
            tabs.add(to, t)
            row.removeView(t)
            row.addView(t, to)
            selectedIndex = tabs.indexOf(selTab)
            refreshStates()
            listener?.onTabMoved(dragFrom, to)
        }
        t.scaleX = 1.04f
        t.scaleY = 1.04f
        t.translationX = startX
        t.animate().translationX(0f).scaleX(1f).scaleY(1f).setDuration(120).withEndAction { t.translationZ = 0f }.start()
    }

    // ----- 탭 이름 말풍선 -----

    private val tipHandler = Handler(Looper.getMainLooper())
    private var tip: PopupWindow? = null
    private val hideTip = Runnable { dismissTip() }

    /** [tab] 아래에 풀네임을 띄운다. [hideAfterMs]가 0이면 [dismissTip]을 부를 때까지 둔다 */
    private fun showTip(tab: TabView, hideAfterMs: Long) {
        dismissTip()
        if (!tab.isAttachedToWindow) return
        val maxW = resources.displayMetrics.widthPixels - px(24f)
        val text = TextView(context).apply {
            this.text = tab.fullTitle
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            maxWidth = maxW
            setPadding(px(12f), px(8f), px(12f), px(8f))
            background = GradientDrawable().apply {
                setColor(0xE6303030.toInt())
                cornerRadius = px(8f).toFloat()
            }
        }
        // 손·펜이 닿아도 아래 화면이 그대로 받게 (말풍선은 보기만)
        tip = PopupWindow(text, ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            isFocusable = false
            isTouchable = false
            elevation = px(4f).toFloat()
            showAsDropDown(tab, tab.width / 8, px(2f))
        }
        if (hideAfterMs > 0) tipHandler.postDelayed(hideTip, hideAfterMs)
    }

    private fun dismissTip() {
        tipHandler.removeCallbacks(hideTip)
        tip?.dismiss()
        tip = null
    }

    override fun onDetachedFromWindow() {
        dismissTip()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        // 화면을 돌리거나 옆 단추 줄이 자리를 바꿔 폭이 달라져도 고른 탭이 밀려나지 않게
        post { scrollToSelected(smooth = false) }
    }

    /**
     * 옆에 다른 것과 자리를 나눌 때 탭 줄이 적어도 차지해야 할 너비 ([TabActionRow]).
     * 고른 탭 하나는 늘 다 보이고, 탭이 여럿이면 [total]의 절반까지는 더 받는다
     */
    fun reserveWidth(total: Int): Int {
        updateCompact()
        val base = paddingLeft + paddingRight + addButtonSpace
        val one = base + minTabPx
        val all = base + minTabPx * tabs.size
        return max(one, min(all, total / 2))
    }

    private fun isCompactScreen() = resources.configuration.screenWidthDp in 1 until compactBelowDp

    /** 화면 폭에 맞춰 작은 탭을 쓸지 정한다 (바뀌었을 때만 탭들을 고친다) */
    private fun updateCompact() {
        val c = isCompactScreen()
        if (c == compact) return
        compact = c
        tabs.forEach { it.applyCompact(c) }
        refreshStates()
    }

    private fun refreshStates() {
        tabs.forEachIndexed { k, t ->
            t.active = k == selectedIndex
            // 크롬처럼 선택한 탭 양옆에는 구분선을 긋지 않는다
            t.divider = k != selectedIndex && k + 1 != selectedIndex && k != tabs.lastIndex
            t.invalidate()
            t.refreshColors()
            t.refreshClose()
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        updateCompact()
        // 탭 너비: 자리를 탭 수로 나눈 값을 최소~최대 사이로
        val avail = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - addButtonSpace).coerceAtLeast(0)
        val n = tabs.size.coerceAtLeast(1)
        val tw = (avail / n).coerceIn(minTabPx, max(minTabPx, px(maxTabWidthDp)))
        tabs.forEach { it.layoutParams.width = tw }
        (scroll.layoutParams as LayoutParams).width = min(tw * tabs.size, avail)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /**
     * 탭 이름. 평소에는 TextView 그대로(끝은 …으로 줄임)이고, [scrolled]가 0 이상이면 이름을 줄이지 않고 그만큼 왼쪽으로 민 채 그린다
     * (탭이 이름이 자리보다 길 때 저절로 흘려 보이려고)
     */
    private class MarqueeText(ctx: Context) : TextView(ctx) {
        /** 왼쪽으로 민 거리 (px). 음수면 흘리지 않는다 */
        var scrolled = -1f

        private fun avail() = width - paddingLeft - paddingRight

        /** 이름이 자리보다 넘치는 길이 (px, 넘치지 않으면 0) */
        fun overflowPx(): Float {
            if (width <= 0 || text.isNullOrEmpty()) return 0f
            return max(0f, paint.measureText(text, 0, text.length) - avail())
        }

        override fun onDraw(canvas: Canvas) {
            val l = layout
            if (scrolled < 0f || l == null) {
                super.onDraw(canvas)
                return
            }
            canvas.save()
            canvas.clipRect(paddingLeft, 0, width - paddingRight, height)
            paint.color = currentTextColor
            canvas.drawText(text, 0, text.length, paddingLeft - scrolled, compoundPaddingTop + l.getLineBaseline(0).toFloat(), paint)
            canvas.restore()
        }
    }

    /** 탭 하나: [아이콘] 이름 [저장 안 됨] [×] */
    private inner class TabView(private val closable: Boolean) : LinearLayout(context) {
        var active = false
        var divider = false
        private var small = false

        private val foot = px(6f).toFloat()      // 아래쪽 바깥으로 휘는 부분
        private val radius = px(10f).toFloat()   // 위쪽 둥근 모서리
        private val icon = ImageView(context)
        private val title = MarqueeText(context)
        private val unsaved = ImageView(context)
        private val close = ImageButton(context)
        private val shape = Path()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint().apply { strokeWidth = density }
        private val showTipLater = Runnable { if (isAttachedToWindow && isTitleCut()) showTip(this, 0) }

        val fullTitle: CharSequence get() = title.text
        /** 끄는 동안 이 탭이 비켜 가려는 자리(translationX 목표) */
        var slideTarget = 0f
        private var lastRawX = 0f

        override fun onTouchEvent(e: MotionEvent): Boolean {
            lastRawX = e.rawX
            if (dragTab === this) {
                when (e.actionMasked) {
                    MotionEvent.ACTION_MOVE -> {
                        dragRawX = e.rawX
                        moveDrag(this)
                    }
                    MotionEvent.ACTION_UP -> endDrag(cancel = false)
                    MotionEvent.ACTION_CANCEL -> endDrag(cancel = true)
                }
                return true
            }
            return super.onTouchEvent(e)
        }

        /** 이름이 잘려서 ...으로 줄었는지 */
        private fun isTitleCut() = title.layout?.let { it.lineCount > 0 && it.getEllipsisCount(0) > 0 } == true

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setWillNotDraw(false)
            isClickable = true
            addView(icon, LayoutParams(px(16f), px(16f)))
            title.setSingleLine()
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            // 이름이 자리보다 길면 (고른 탭만) 저절로 흘러 보인다
            title.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateMarquee() }
            unsaved.setImageResource(R.drawable.ic_unsaved)
            unsaved.contentDescription = "저장하지 않음"
            unsaved.visibility = View.GONE
            addView(unsaved, LayoutParams(px(18f), px(18f)))
            if (closable) {
                close.setImageResource(R.drawable.ic_close)
                close.scaleType = ImageView.ScaleType.FIT_CENTER
                close.setBackgroundResource(attrRes(android.R.attr.selectableItemBackgroundBorderless))
                close.contentDescription = "탭 닫기"
                close.setOnClickListener { listener?.onTabClose(tabs.indexOf(this)) }
                addView(close, LayoutParams(px(26f), px(26f)))
            }
            applyCompact(false)
            setOnClickListener {
                val i = tabs.indexOf(this)
                // 이미 고른 탭을 한 번 더 누르면 풀네임
                if (i == selectedIndex) showTip(this, 3000) else select(i, notify = true)
            }
            // 길게 누르면 끌어서 순서를 바꾼다
            setOnLongClickListener {
                tipHandler.removeCallbacks(showTipLater)
                startDrag(this, lastRawX)
                dragTab != null
            }
            // 펜·마우스를 탭 위에 잠시 두면 (이름이 잘렸을 때만) 풀네임
            setOnHoverListener { _, e ->
                when (e.actionMasked) {
                    MotionEvent.ACTION_HOVER_ENTER -> tipHandler.postDelayed(showTipLater, 500)
                    MotionEvent.ACTION_HOVER_EXIT -> {
                        tipHandler.removeCallbacks(showTipLater)
                        if (tip != null) dismissTip()
                    }
                }
                false
            }
        }

        /** 작은 탭: 여백·아이콘·글자·× 단추를 줄인다 */
        fun applyCompact(c: Boolean) {
            small = c
            (icon.layoutParams as LayoutParams).apply {
                width = px(if (c) 14f else 16f); height = width
                marginEnd = px(if (c) 3f else 5f)
            }
            (unsaved.layoutParams as LayoutParams).apply {
                width = px(if (c) 16f else 18f); height = width
                marginStart = px(2f)
            }
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (c) 12f else 13f)
            if (closable) {
                val pad = px(if (c) 7f else 9f)
                close.setPadding(pad, pad, pad, pad)
                (close.layoutParams as LayoutParams).apply {
                    width = px(if (c) 28f else 34f); height = width
                    marginStart = px(if (c) 1f else 2f)
                }
            }
            refreshClose()
            requestLayout()
        }

        /** 작은 탭에서는 고른 탭에만 × (고르지 않은 탭은 이름을 더 보이게) */
        fun refreshClose() {
            val showClose = closable && (!small || active)
            if (closable) close.visibility = if (showClose) View.VISIBLE else View.GONE
            val f = foot.toInt()
            val side = px(if (small) 3f else 4f)
            setPadding(f + side, px(2f), f + if (showClose) px(2f) else side, 0)
        }

        fun setTitle(t: CharSequence) {
            title.text = t
            contentDescription = t
            title.post { updateMarquee() }
        }

        fun setUnsaved(on: Boolean) {
            unsaved.visibility = if (on) View.VISIBLE else View.GONE
        }

        fun setIcon(res: Int, tint: Int?) {
            icon.visibility = if (res == 0) View.GONE else View.VISIBLE
            if (res != 0) icon.setImageResource(res)
            icon.imageTintList = tint?.let { ColorStateList.valueOf(it) }
        }

        fun refreshColors() {
            title.setTextColor(if (active) textActive else textInactive)
            title.paint.isFakeBoldText = active
            isSelected = active
            title.post { updateMarquee() }
        }

        // ----- 긴 이름 자동으로 흘리기 -----
        // 처음에서 잠깐 멈춤 → 끝까지 흐름 → 끝에서 잠깐 멈춤 → 처음으로 돌아감, 되풀이 (고른 탭만)

        private var marquee: ValueAnimator? = null
        private var marqueeRange = -1f

        private fun updateMarquee() {
            val range = title.overflowPx()
            val want = active && range > 0f && isAttachedToWindow
            if (!want) {
                marquee?.cancel()
                marquee = null
                marqueeRange = -1f
                title.scrolled = -1f
                title.invalidate()
                return
            }
            if (marquee?.isRunning == true && marqueeRange == range) return
            marquee?.cancel()
            marqueeRange = range
            val scrollMs = max(MARQUEE_MIN_SCROLL_MS, range / density / MARQUEE_DP_PER_SEC * 1000f)
            val total = MARQUEE_HOLD_MS + scrollMs + MARQUEE_HOLD_MS + MARQUEE_BACK_MS
            title.scrolled = 0f
            marquee = ValueAnimator.ofFloat(0f, total).apply {
                duration = total.toLong()
                interpolator = LinearInterpolator()
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener {
                    val t = it.animatedValue as Float
                    title.scrolled = when {
                        t < MARQUEE_HOLD_MS -> 0f
                        t < MARQUEE_HOLD_MS + scrollMs -> range * (t - MARQUEE_HOLD_MS) / scrollMs
                        t < MARQUEE_HOLD_MS + scrollMs + MARQUEE_HOLD_MS -> range
                        else -> range * (1f - (t - MARQUEE_HOLD_MS - scrollMs - MARQUEE_HOLD_MS) / MARQUEE_BACK_MS)
                    }
                    title.invalidate()
                }
                start()
            }
        }

        override fun onAttachedToWindow() {
            super.onAttachedToWindow()
            title.post { updateMarquee() }
        }

        override fun onDetachedFromWindow() {
            marquee?.cancel()
            marquee = null
            marqueeRange = -1f
            super.onDetachedFromWindow()
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            val wf = w.toFloat(); val hf = h.toFloat()
            shape.reset()
            shape.moveTo(0f, hf)
            shape.quadTo(foot, hf, foot, hf - foot)
            shape.lineTo(foot, radius)
            shape.quadTo(foot, 0f, foot + radius, 0f)
            shape.lineTo(wf - foot - radius, 0f)
            shape.quadTo(wf - foot, 0f, wf - foot, radius)
            shape.lineTo(wf - foot, hf - foot)
            shape.quadTo(wf - foot, hf, wf, hf)
            shape.close()
        }

        override fun onDraw(canvas: Canvas) {
            if (active) {
                fill.color = activeFill
                canvas.drawPath(shape, fill)
            } else if (isPressed) {
                fill.color = ColorUtils.setAlphaComponent(activeFill, 140)
                canvas.drawPath(shape, fill)
            }
            if (divider) {
                line.color = ColorUtils.setAlphaComponent(dividerColor, 120)
                val x = width - density / 2
                canvas.drawLine(x, height * 0.28f, x, height * 0.78f, line)
            }
        }

        override fun drawableStateChanged() {
            super.drawableStateChanged()
            invalidate()
        }
    }
}
