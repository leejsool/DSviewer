package com.dsviewer.app

import android.app.Activity
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch

/** 옵션 창의 한 줄: 아이콘 · 이름 · 켜고 끄는 값. [set]이 false를 돌려주면 바꾸지 못한 것(스위치를 되돌린다) */
class OptionItem(
    val icon: () -> Drawable?,
    val label: String,
    val get: () -> Boolean,
    val set: (Boolean) -> Boolean,
    val hint: String? = null,
)

/**
 * 여럿 중 하나를 고르는 항목 (라디오): [labels]마다 설명 [hints], 지금 고른 번호 [get], 고르면 [set].
 * [icon]이 있으면 제목 앞에, [icons]가 있으면 각 고를 것의 이름 앞에 아이콘을 둔다
 */
class OptionChoice(
    val title: String,
    val labels: List<String>,
    val hints: List<String>,
    val get: () -> Int,
    val set: (Int) -> Unit,
    val icon: (() -> Drawable?)? = null,
    val icons: List<() -> Drawable?> = emptyList(),
)

/**
 * 옵션 창 왼쪽 목록의 한 범주와 그 안의 줄들 ([choices]는 켜고 끄는 줄들 아래에 이어진다).
 * [items]는 줄을 그릴 때마다 불러 지금 차례를 얻는다. [onReorder]가 있으면 줄 왼쪽에 손잡이가 생겨
 * 끌어서 차례를 바꿀 수 있다 (옮기는 줄의 원래 번호, 새 번호)
 */
class OptionCategory(
    val title: String,
    val desc: String,
    val items: () -> List<OptionItem>,
    val choices: List<OptionChoice> = emptyList(),
    val onReorder: ((from: Int, to: Int) -> Unit)? = null,
    val extras: List<OptionExtra> = emptyList(),
) {
    constructor(
        title: String, desc: String, items: List<OptionItem>, choices: List<OptionChoice> = emptyList(),
        extras: List<OptionExtra> = emptyList(),
    ) : this(title, desc, { items }, choices, null, extras)
}

/** 켜고 끄기·고르기로 나타낼 수 없는 항목: 제목 아래에 [view]가 만든 화면을 그대로 둔다 (목록을 새로 그릴 때마다 새로 만든다) */
class OptionExtra(val title: String, val view: () -> View)

/**
 * ⋮ ▸ 옵션: 한컴오피스 '사용자 설정'처럼 왼쪽에 범주(상단 툴바 · 하단 툴바 · 편의 옵션), 오른쪽에 그 범주의 항목을
 * 아이콘과 함께 늘어놓고 하나씩 보이기/숨기기(켜고 끄기)를 고른다. 고른 즉시 적용된다.
 */
class OptionsDialog(
    private val a: Activity,
    private val categories: List<OptionCategory>,
    private val onReset: () -> Unit,
) {
    private val d = a.resources.displayMetrics.density
    private fun dp(v: Int) = (v * d).toInt()

    private var selected = 0
    private lateinit var leftCol: LinearLayout
    private lateinit var rightCol: LinearLayout
    private lateinit var scroll: ScrollView
    private lateinit var body: LinearLayout
    private lateinit var header: TextView
    private lateinit var catScroll: HorizontalScrollView
    private lateinit var divider: View
    private var dialog: AlertDialog? = null
    /** 좁은 화면 배치(범주를 위에 가로로, 항목은 그 아래 온 너비로)인지. null = 아직 배치 전 */
    private var narrow: Boolean? = null
    /** 휴대폰 (가장 짧은 변이 600dp보다 좁다): 창을 화면 가득에 가깝게 */
    private val phone get() = a.resources.configuration.smallestScreenWidthDp in 1 until 600
    /** 범주가 하나뿐이면 범주 목록을 그리지 않는다 */
    private val showCategories get() = categories.size > 1

    /** 창이 떠 있는지 (화면을 새로 만들 때 다시 띄우려고) */
    val isShowing get() = dialog?.isShowing == true

    fun dismiss() {
        dialog?.dismiss()
    }

    fun show(start: Int = 0) {
        selected = start.coerceIn(0, categories.size - 1)
        leftCol = LinearLayout(a)
        rightCol = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val outline = MaterialColors.getColor(a.window.decorView, com.google.android.material.R.attr.colorOutlineVariant)
        divider = View(a).apply { setBackgroundColor(outline) }
        scroll = ScrollView(a).apply {
            addView(rightCol)
            // 아래에 항목이 더 있는 것을 알 수 있게 스크롤 막대를 늘 보인다 (가만히 있어도 흐려지지 않게)
            isVerticalScrollBarEnabled = true
            isScrollbarFadingEnabled = false
            scrollBarStyle = View.SCROLLBARS_OUTSIDE_OVERLAY
        }
        catScroll = HorizontalScrollView(a).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        body = LinearLayout(a)
        // 제목은 창 제목 대신 직접 그린다: 가로로 눕힌 휴대폰처럼 높이가 모자라면 숨겨 항목 자리를 넓힌다
        header = TextView(a).apply {
            text = "옵션"
            textSize = 22f
            setTextColor(MaterialColors.getColor(a.window.decorView, com.google.android.material.R.attr.colorOnSurface))
            val side = dp(if (phone) 20 else 24)
            setPadding(side, dp(if (phone) 18 else 24), side, dp(8))
        }
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(header)
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)))
        }
        val builder = MaterialAlertDialogBuilder(a)
            .setView(box)
            .setNeutralButton("처음 값으로", null)
            .setPositiveButton("닫기", null)
        // 휴대폰에서는 창 바깥 여백을 줄여 화면을 넉넉히 쓴다 (기본은 위아래 80dp씩 비워 세로 화면에서 항목이 거의 안 보였다)
        if (phone) builder.setBackgroundInsetStart(dp(8)).setBackgroundInsetEnd(dp(8))
            .setBackgroundInsetTop(dp(12)).setBackgroundInsetBottom(dp(12))
        val dlg = builder.create()
        dialog = dlg
        dlg.show()
        // '처음 값으로'는 눌러도 창을 닫지 않고 목록만 새로 그린다
        dlg.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            onReset()
            rebuild()
        }
        applySize()
        // 화면을 돌리면(뷰어는 화면을 새로 만들지 않는다) 새 크기에 맞춰 창 너비·높이와 배치를 다시 정한다
        val root = a.window.decorView
        val onResize = View.OnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) body.post { applySize() }
        }
        root.addOnLayoutChangeListener(onResize)
        dlg.setOnDismissListener {
            root.removeOnLayoutChangeListener(onResize)
            dialog = null
        }
    }

    /** 지금 화면 크기에 맞춰 배치·창 너비·항목 칸 높이를 정한다 */
    private fun applySize() {
        val dlg = dialog ?: return
        val root = a.window.decorView
        val dm = a.resources.displayMetrics
        val w = root.width.takeIf { it > 0 } ?: dm.widthPixels
        val h = root.height.takeIf { it > 0 } ?: dm.heightPixels
        val bars = ViewCompat.getRootWindowInsets(root)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        val usableW = w - (bars?.let { it.left + it.right } ?: 0)
        val usableH = h - (bars?.let { it.top + it.bottom } ?: 0)
        arrange(narrowNow = usableW / d < 560)
        // 높이가 모자라면(가로로 눕힌 휴대폰) 제목을 숨긴다
        val short = usableH / d < 480
        header.visibility = if (short) View.GONE else View.VISIBLE
        // 창 바깥 여백 + 아래 단추 줄 + 제목
        val chrome = dp(if (phone) 24 else 160) + dp(68) + if (short) dp(8) else dp(64)
        val bodyH = (usableH - chrome).coerceIn(dp(140), dp(if (phone) 560 else 380))
        (body.layoutParams as LinearLayout.LayoutParams).let {
            if (it.height != bodyH) {
                it.height = bodyH
                body.layoutParams = it
            }
        }
        // 태블릿에서는 한컴 설정 창처럼 넉넉한 너비로, 휴대폰은 화면 가득에 가깝게 (가로로 눕혀도 지나치게 넓지 않게)
        val winW = if (phone) minOf(usableW, dp(760)) else minOf(dp(640), (usableW * 0.94f).toInt())
        dlg.window?.setLayout(winW, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    /** 넓은 화면: 왼쪽에 범주, 오른쪽에 항목. 좁은 화면(휴대폰 세로): 위에 범주를 가로로, 그 아래 항목을 온 너비로 */
    private fun arrange(narrowNow: Boolean) {
        if (narrow == narrowNow) return
        narrow = narrowNow
        body.removeAllViews()
        catScroll.removeAllViews()
        if (narrowNow) {
            body.orientation = LinearLayout.VERTICAL
            body.setPadding(dp(12), 0, dp(12), 0)
            leftCol.orientation = LinearLayout.HORIZONTAL
            leftCol.setPadding(dp(4), dp(2), dp(4), dp(8))
            if (showCategories) {
                catScroll.addView(leftCol)
                body.addView(catScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                body.addView(divider, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(1)))
            }
            body.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        } else {
            body.orientation = LinearLayout.HORIZONTAL
            body.setPadding(dp(20), dp(8), dp(20), 0)
            leftCol.orientation = LinearLayout.VERTICAL
            leftCol.setPadding(0, dp(4), dp(8), dp(4))
            if (showCategories) {
                body.addView(leftCol, LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.MATCH_PARENT))
                body.addView(divider, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            }
            body.addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        rebuild()
    }

    private fun rebuild() {
        leftCol.removeAllViews()
        categories.forEachIndexed { i, c ->
            val row = categoryRow(c.title, i == selected) {
                selected = i
                rebuild()
                scroll.scrollTo(0, 0)
            }
            if (narrow == true) leftCol.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            else leftCol.addView(row)
        }
        rightCol.removeAllViews()
        val c = categories[selected]
        rightCol.addView(TextView(a).apply {
            text = c.desc
            textSize = 13f
            setTextColor(MaterialColors.getColor(a.window.decorView, com.google.android.material.R.attr.colorOnSurfaceVariant))
            setPadding(dp(12), dp(4), dp(8), dp(10))
        })
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        c.items().forEachIndexed { i, item -> box.addView(itemRow(item, if (c.onReorder != null) i else -1, box, c)) }
        rightCol.addView(box)
        for (extra in c.extras) rightCol.addView(extraBlock(extra))
        for (choice in c.choices) rightCol.addView(choiceBlock(choice))
    }

    private fun extraBlock(e: OptionExtra): View = LinearLayout(a).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(12), dp(14), dp(8), dp(6))
        addView(TextView(a).apply {
            text = e.title
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
            setPadding(0, 0, 0, dp(8))
        })
        addView(e.view())
    }

    private fun choiceBlock(c: OptionChoice): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(8), dp(6))
        }
        val titleRow = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        c.icon?.let { ic ->
            titleRow.addView(ImageView(a).apply {
                setImageDrawable(ic())
                scaleType = ImageView.ScaleType.FIT_CENTER
            }, LinearLayout.LayoutParams(dp(28), dp(28)).apply { marginEnd = dp(10) })
        }
        titleRow.addView(TextView(a).apply {
            text = c.title
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        })
        box.addView(titleRow)
        val group = android.widget.RadioGroup(a).apply { orientation = LinearLayout.VERTICAL }
        val hintColor = MaterialColors.getColor(a.window.decorView, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val iconSize = dp(24)
        val iconGap = dp(8)
        c.labels.forEachIndexed { i, label ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            val choiceIcon = c.icons.getOrNull(i)?.invoke()?.apply { setBounds(0, 0, iconSize, iconSize) }
            row.addView(android.widget.RadioButton(a).apply {
                text = label
                textSize = 15f
                id = View.generateViewId()
                // 동그라미와 이름 사이에 아이콘
                if (choiceIcon != null) {
                    setCompoundDrawablesRelative(choiceIcon, null, null, null)
                    compoundDrawablePadding = iconGap
                }
                isChecked = i == c.get()
                setOnClickListener {
                    if (c.get() != i) {
                        c.set(i)
                        rebuild()
                    }
                }
            })
            row.addView(TextView(a).apply {
                text = c.hints.getOrNull(i).orEmpty()
                textSize = 13f
                setTextColor(hintColor)
                // 설명은 이름 첫 글자에 맞춘다 (아이콘이 있으면 그만큼 더 들여)
                setPadding(dp(40) + if (choiceIcon != null) iconSize + iconGap else 0, 0, dp(8), dp(6))
            })
            group.addView(row)
        }
        box.addView(group)
        return box
    }

    private fun categoryRow(title: String, on: Boolean, click: () -> Unit) = TextView(a).apply {
        text = title
        textSize = 15f
        // 좁은 화면에서 위에 가로로 놓을 때는 글자가 파란 네모 한가운데 오게 좌우 여백을 같게
        if (narrow == true) {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(12), dp(14), dp(12))
        } else {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(12), dp(8), dp(12))
        }
        setTextColor(if (on) ContextCompat.getColor(a, R.color.active_stroke) else MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        if (on) {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = GradientDrawable().apply {
                cornerRadius = 10 * d
                setColor(ContextCompat.getColor(a, R.color.active_fill))
                setStroke(dp(2), ContextCompat.getColor(a, R.color.active_stroke))
            }
        }
        setOnClickListener { click() }
    }

    /**
     * 줄 하나. [index]가 0 이상이면 왼쪽에 손잡이를 달아 끌어서 [box] 안의 차례를 바꿀 수 있다
     * (끄는 동안 줄이 손가락을 따라오고 다른 줄들이 비켜선다. 가장자리로 끌면 목록이 스크롤된다)
     */
    private fun itemRow(item: OptionItem, index: Int, box: LinearLayout, c: OptionCategory): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(if (index >= 0) 0 else 12), dp(6), dp(8), dp(6))
        }
        if (index >= 0) {
            val handle = ImageView(a).apply {
                setImageResource(R.drawable.ic_drag_handle)
                imageTintList = android.content.res.ColorStateList.valueOf(
                    MaterialColors.getColor(a.window.decorView, com.google.android.material.R.attr.colorOnSurfaceVariant)
                )
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = "끌어서 순서 바꾸기"
            }
            row.addView(handle, LinearLayout.LayoutParams(dp(40), dp(44)))
            enableDrag(handle, row, box, index, c)
        }
        row.addView(ImageView(a).apply {
            setImageDrawable(item.icon())
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(28), dp(28)))
        val texts = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(TextView(a).apply {
            text = item.label
            textSize = 15f
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        })
        item.hint?.let {
            texts.addView(TextView(a).apply {
                text = it
                textSize = 13f
                setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
            })
        }
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(if (index >= 0) 10 else 14) })
        val sw = MaterialSwitch(a).apply { isChecked = item.get() }
        sw.setOnCheckedChangeListener { _, on ->
            if (!item.set(on)) sw.post { sw.isChecked = item.get() }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }
    /** [handle]을 잡고 끌면 [row]가 따라오고, 놓으면 [c]의 [OptionCategory.onReorder]로 새 차례를 알린 뒤 목록을 새로 그린다 */
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun enableDrag(handle: View, row: View, box: LinearLayout, from: Int, c: OptionCategory) {
        var startY = 0f
        var startScroll = 0
        var lastRaw = 0f
        var target = from
        var dragging = false
        fun update() {
            val dy = lastRaw - startY + (scroll.scrollY - startScroll)
            row.translationY = dy
            val center = row.top + row.height / 2f + dy
            target = (0 until box.childCount).count { it != from && box.getChildAt(it).let { v -> v.top + v.height / 2f < center } }
            for (j in 0 until box.childCount) {
                if (j == from) continue
                box.getChildAt(j).translationY = when {
                    j in (from + 1)..target -> -row.height.toFloat()
                    j in target until from -> row.height.toFloat()
                    else -> 0f
                }
            }
        }
        val auto = object : Runnable {
            override fun run() {
                if (!dragging) return
                val loc = IntArray(2)
                scroll.getLocationOnScreen(loc)
                val rel = lastRaw - loc[1]
                when {
                    rel < dp(56) -> scroll.scrollBy(0, -dp(8))
                    rel > scroll.height - dp(56) -> scroll.scrollBy(0, dp(8))
                }
                update()
                scroll.postDelayed(this, 16)
            }
        }
        handle.setOnTouchListener { _, ev ->
            when (ev.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    startY = ev.rawY
                    lastRaw = ev.rawY
                    startScroll = scroll.scrollY
                    target = from
                    row.elevation = dp(6).toFloat()
                    row.setBackgroundColor(MaterialColors.getColor(row, com.google.android.material.R.attr.colorSurfaceContainerHigh))
                    scroll.requestDisallowInterceptTouchEvent(true)
                    scroll.post(auto)
                }
                android.view.MotionEvent.ACTION_MOVE -> {
                    lastRaw = ev.rawY
                    update()
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    scroll.removeCallbacks(auto)
                    val to = target
                    // 그림자·밀림을 거두고, 목록은 이 터치 처리가 끝난 뒤에 새로 그린다
                    // (처리 중에 줄들을 지우면 그림자 있는 자식의 그리기 순서가 어긋나 앱이 죽는다)
                    row.elevation = 0f
                    for (j in 0 until box.childCount) box.getChildAt(j).translationY = 0f
                    val commit = ev.actionMasked == android.view.MotionEvent.ACTION_UP && to != from
                    scroll.post {
                        if (commit) c.onReorder?.invoke(from, to)
                        rebuild()
                    }
                }
            }
            true
        }
    }
}
