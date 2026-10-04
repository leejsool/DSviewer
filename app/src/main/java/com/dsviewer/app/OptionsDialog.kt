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

/** 옵션 창 왼쪽 목록의 한 범주와 그 안의 줄들 */
class OptionCategory(val title: String, val desc: String, val items: List<OptionItem>)

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
            addView(ScrollView(a).apply { addView(rightCol) }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
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
        for (item in c.items) rightCol.addView(itemRow(item))
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

    private fun itemRow(item: OptionItem): View {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(6), dp(8), dp(6))
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
        row.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = dp(14) })
        val sw = MaterialSwitch(a).apply { isChecked = item.get() }
        sw.setOnCheckedChangeListener { _, on ->
            if (!item.set(on)) sw.post { sw.isChecked = item.get() }
        }
        row.addView(sw)
        row.setOnClickListener { sw.toggle() }
        return row
    }
}
