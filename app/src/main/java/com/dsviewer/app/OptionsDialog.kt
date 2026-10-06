package com.dsviewer.app

import android.app.Activity
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
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

/** 여럿 중 하나를 고르는 항목 (라디오): [labels]마다 설명 [hints], 지금 고른 번호 [get], 고르면 [set] */
class OptionChoice(
    val title: String,
    val labels: List<String>,
    val hints: List<String>,
    val get: () -> Int,
    val set: (Int) -> Unit,
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
) {
    constructor(title: String, desc: String, items: List<OptionItem>, choices: List<OptionChoice> = emptyList()) :
        this(title, desc, { items }, choices)
}

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

    fun show(start: Int = 0) {
        selected = start.coerceIn(0, categories.size - 1)
        leftCol = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(4), dp(8), dp(4))
        }
        rightCol = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        val outline = MaterialColors.getColor(a.window.decorView, com.google.android.material.R.attr.colorOutlineVariant)
        val body = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(leftCol, LinearLayout.LayoutParams(dp(130), ViewGroup.LayoutParams.MATCH_PARENT))
            addView(View(a).apply { setBackgroundColor(outline) }, LinearLayout.LayoutParams(dp(1), ViewGroup.LayoutParams.MATCH_PARENT))
            scroll = ScrollView(a).apply { addView(rightCol) }
            addView(scroll, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        }
        rebuild()
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(380)))
        }
        val dialog = MaterialAlertDialogBuilder(a)
            .setTitle("옵션")
            .setView(box)
            .setNeutralButton("처음 값으로", null)
            .setPositiveButton("닫기", null)
            .create()
        dialog.show()
        // '처음 값으로'는 눌러도 창을 닫지 않고 목록만 새로 그린다
        dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
            onReset()
            rebuild()
        }
        // 태블릿에서는 한컴 설정 창처럼 넉넉한 너비로
        dialog.window?.setLayout(minOf(dp(640), (a.resources.displayMetrics.widthPixels * 0.94f).toInt()), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun rebuild() {
        leftCol.removeAllViews()
        categories.forEachIndexed { i, c ->
            leftCol.addView(categoryRow(c.title, i == selected) {
                selected = i
                rebuild()
            })
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
        for (choice in c.choices) rightCol.addView(choiceBlock(choice))
    }

    private fun choiceBlock(c: OptionChoice): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(14), dp(8), dp(6))
        }
        box.addView(TextView(a).apply {
            text = c.title
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface))
        })
        val group = android.widget.RadioGroup(a).apply { orientation = LinearLayout.VERTICAL }
        val hintColor = MaterialColors.getColor(a.window.decorView, com.google.android.material.R.attr.colorOnSurfaceVariant)
        c.labels.forEachIndexed { i, label ->
            val row = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
            row.addView(android.widget.RadioButton(a).apply {
                text = label
                textSize = 15f
                id = View.generateViewId()
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
                textSize = 12f
                setTextColor(hintColor)
                setPadding(dp(40), 0, dp(8), dp(6))
            })
            group.addView(row)
        }
        box.addView(group)
        return box
    }

    private fun categoryRow(title: String, on: Boolean, click: () -> Unit) = TextView(a).apply {
        text = title
        textSize = 15f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(12), dp(12), dp(8), dp(12))
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
                textSize = 12f
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
