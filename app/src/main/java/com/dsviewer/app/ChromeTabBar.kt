package com.dsviewer.app

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import com.google.android.material.color.MaterialColors
import kotlin.math.min

/**
 * 크롬 브라우저 모양의 탭 막대.
 * 선택한 탭은 윗모서리가 둥글고 아래쪽이 바깥으로 휘어져 바로 아래 화면(colorSurface)과 이어진다.
 * 막대 바탕은 한 단계 어두운 색이어야 한다 (레이아웃에서 부모 배경으로 준다).
 * 탭이 많으면 탭 너비가 줄어들고, 최소 너비보다 좁아지면 옆으로 밀린다. ＋ 버튼은 늘 탭 바로 오른쪽.
 */
class ChromeTabBar @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {

    interface Listener {
        fun onTabSelected(index: Int) {}
        fun onTabClose(index: Int) {}
        fun onAddTab() {}
    }

    var listener: Listener? = null
    var minTabWidthDp = 110f
    var maxTabWidthDp = 220f

    private val density = resources.displayMetrics.density
    private fun px(dp: Float) = (dp * density).toInt()

    private val scroll = HorizontalScrollView(ctx).apply {
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
        addView(addButton, LayoutParams(px(38f), px(38f)).apply {
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
        tabs.add(tv)
        row.addView(tv, LayoutParams(px(minTabWidthDp), ViewGroup.LayoutParams.MATCH_PARENT))
        refreshStates()
        return tabs.size - 1
    }

    fun removeTab(i: Int) {
        if (i !in tabs.indices) return
        row.removeView(tabs.removeAt(i))
        if (selectedIndex == i) selectedIndex = -1 else if (selectedIndex > i) selectedIndex--
        refreshStates()
    }

    fun setTitle(i: Int, title: CharSequence) = tabs.getOrNull(i)?.setTitle(title)

    fun setIcon(i: Int, icon: Int, tint: Int?) = tabs.getOrNull(i)?.setIcon(icon, tint)

    /** i번 탭을 고른다. notify면 바뀌었을 때 listener.onTabSelected를 부른다 */
    fun select(i: Int, notify: Boolean = false) {
        if (i !in tabs.indices) return
        val changed = i != selectedIndex
        selectedIndex = i
        refreshStates()
        post { tabs.getOrNull(selectedIndex)?.let { scroll.smoothScrollTo(it.left - (scroll.width - it.width) / 2, 0) } }
        if (notify && changed) listener?.onTabSelected(i)
    }

    private fun refreshStates() {
        tabs.forEachIndexed { k, t ->
            t.active = k == selectedIndex
            // 크롬처럼 선택한 탭 양옆에는 구분선을 긋지 않는다
            t.divider = k != selectedIndex && k + 1 != selectedIndex && k != tabs.lastIndex
            t.invalidate()
            t.refreshColors()
        }
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        // 탭 너비: 자리를 탭 수로 나눈 값을 최소~최대 사이로
        val addW = if (showAddButton) px(40f) else 0
        val avail = (MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight - addW).coerceAtLeast(0)
        val n = tabs.size.coerceAtLeast(1)
        val tw = (avail / n).coerceIn(px(minTabWidthDp), px(maxTabWidthDp))
        tabs.forEach { it.layoutParams.width = tw }
        (scroll.layoutParams as LayoutParams).width = min(tw * tabs.size, avail)
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    /** 탭 하나: [아이콘] 이름 [×] */
    private inner class TabView(closable: Boolean) : LinearLayout(context) {
        var active = false
        var divider = false

        private val foot = px(8f).toFloat()      // 아래쪽 바깥으로 휘는 부분
        private val radius = px(10f).toFloat()   // 위쪽 둥근 모서리
        private val icon = ImageView(context)
        private val title = TextView(context)
        private val close = ImageButton(context)
        private val shape = Path()
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val line = Paint().apply { strokeWidth = density }

        init {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setWillNotDraw(false)
            isClickable = true
            setPadding(foot.toInt() + px(10f), px(2f), foot.toInt() + px(if (closable) 2f else 10f), 0)
            addView(icon, LayoutParams(px(16f), px(16f)).apply { marginEnd = px(8f) })
            title.setSingleLine()
            title.ellipsize = android.text.TextUtils.TruncateAt.END
            title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            addView(title, LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            if (closable) {
                close.setImageResource(R.drawable.ic_close)
                close.scaleType = ImageView.ScaleType.FIT_CENTER
                close.setPadding(px(5f), px(5f), px(5f), px(5f))
                close.setBackgroundResource(attrRes(android.R.attr.selectableItemBackgroundBorderless))
                close.contentDescription = "탭 닫기"
                close.setOnClickListener { listener?.onTabClose(tabs.indexOf(this)) }
                addView(close, LayoutParams(px(26f), px(26f)).apply { marginStart = px(2f) })
            }
            setOnClickListener { select(tabs.indexOf(this), notify = true) }
        }

        fun setTitle(t: CharSequence) {
            title.text = t
            contentDescription = t
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
