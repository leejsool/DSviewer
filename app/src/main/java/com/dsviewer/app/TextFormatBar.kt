package com.dsviewer.app

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * 글 상자를 치는 동안 옵션 줄 자리에 뜬다: 글자색 · 배경색 · 서식 · 글씨체 · 크기 · 굵게 · 기울임 · 밑줄 · 취소선 ·
 * 목록(체크 · 번호 · 점) · 정렬(왼쪽 · 가운데 · 오른쪽) · 들여쓰기 · 내어쓰기.
 * 툴바가 왼쪽·오른쪽이면 서식 줄도 세로로 세워 툴바 옆에 붙인다 (placeOverlays)
 */
class TextFormatBar(
    private val ctx: AppCompatActivity,
    private val textEditor: InlineTextEditor,
    private val dock: ToolbarDock,
    /** 칸들이 놓인 줄 ([R.id.formatRow]) */
    private val row: LinearLayout,
    /** 지금 쓰는 서식 줄 (툴바 방향에 따라 가로 줄·세로 줄이 바뀐다) */
    private val bar: () -> View,
    private val recentColors: () -> List<Int>,
    private val addRecent: (Int) -> Unit,
    private val hideOptionBar: () -> Unit,
) {

    private lateinit var fmtToggles: Map<CharToggle, TextView>
    private lateinit var fmtListButton: ImageButton
    private lateinit var fmtAlignButton: ImageButton
    private lateinit var fmtColorBar: View
    private lateinit var fmtBgSwatch: TextView
    private lateinit var fmtPresetLabel: TextView
    private lateinit var fmtFontLabel: TextView
    private lateinit var fmtSizeLabel: TextView

    private class Choice<T>(val value: T, val icon: Int, val desc: String)
    private val listChoices = listOf(
        Choice(ListKind.CHECK, R.drawable.ic_fmt_checklist, "체크 목록"),
        Choice(ListKind.NUMBER, R.drawable.ic_fmt_list_number, "번호 목록"),
        Choice(ListKind.BULLET, R.drawable.ic_fmt_list_bullet, "점 목록"),
    )
    private val alignChoices = listOf(
        Choice(TextAlign.LEFT, R.drawable.ic_fmt_align_left, "왼쪽 맞춤"),
        Choice(TextAlign.CENTER, R.drawable.ic_fmt_align_center, "가운데 맞춤"),
        Choice(TextAlign.RIGHT, R.drawable.ic_fmt_align_right, "오른쪽 맞춤"),
    )
    private var fmtState: InlineTextEditor.FormatState? = null

    /** 글자 배경색 (형광펜처럼 옅은 색) */
    private val bgColors = intArrayOf(
        0xFFFFF176.toInt(), 0xFFC5E1A5.toInt(), 0xFFB3E5FC.toInt(), 0xFFF8BBD0.toInt(),
        0xFFFFCC80.toInt(), 0xFFE1BEE7.toInt(), 0xFFE0E0E0.toInt(),
    )
    private val fmtSizes = floatArrayOf(10f, 12f, 14f, 16f, 18f, 20f, 24f, 28f, 32f, 36f, 40f, 48f, 60f, 72f)

    fun setup() {
        val d = ctx.resources.displayMetrics.density
        fun <T : View> cell(v: T, w: Float = 40f): T {
            v.layoutParams = LinearLayout.LayoutParams((w * d).toInt(), (40 * d).toInt()).apply { marginEnd = (2 * d).toInt() }
            v.setBackgroundResource(R.drawable.bg_tool)
            row.addView(v)
            return v
        }
        fun icon(res: Int, desc: String, onClick: (View) -> Unit) = cell(ImageButton(ctx).apply {
            setImageResource(res)
            contentDescription = desc
            tooltipText = desc
            setOnClickListener { onClick(it) }
        })
        fun label(text: CharSequence, desc: String, w: Float = 40f, onClick: (View) -> Unit) = cell(TextView(ctx).apply {
            this.text = text
            gravity = android.view.Gravity.CENTER
            textSize = 17f
            contentDescription = desc
            tooltipText = desc
            setOnClickListener { onClick(it) }
        }, w)
        fun sep() = row.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams((1 * d).toInt(), (24 * d).toInt()).apply {
                marginStart = (6 * d).toInt()
                marginEnd = (8 * d).toInt()
            }
            setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant))
        })

        icon(R.drawable.ic_paste, "붙여넣기") {
            if (!textEditor.paste()) Toast.makeText(ctx, "붙여넣을 글이 없습니다.", Toast.LENGTH_SHORT).show()
        }
        sep()
        // 글자색: '가' 아래 색 막대
        val colorCell = cell(FrameLayout(ctx).apply {
            contentDescription = "글자색"
            tooltipText = "글자색"
            setOnClickListener { openTextColorPicker(it) }
            addView(TextView(context).apply {
                text = "가"
                textSize = 17f
                gravity = android.view.Gravity.CENTER
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (32 * d).toInt()))
        })
        fmtColorBar = View(ctx)
        colorCell.addView(fmtColorBar, FrameLayout.LayoutParams((22 * d).toInt(), (4 * d).toInt(),
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (5 * d).toInt() })
        // 배경색: 배경을 칠한 '가'
        val bgCell = cell(FrameLayout(ctx).apply {
            contentDescription = "배경색"
            tooltipText = "배경색"
            setOnClickListener { showBgColorPopup(it) }
        })
        fmtBgSwatch = TextView(ctx).apply {
            text = "가"
            textSize = 15f
            gravity = android.view.Gravity.CENTER
        }
        bgCell.addView(fmtBgSwatch, FrameLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt(), android.view.Gravity.CENTER))
        sep()
        fmtPresetLabel = label("서식 ▾", "기본 서식", 64f) { showPresetMenu(it) }
        // 글씨체: 지금 글씨체로 쓴 '가'
        fmtFontLabel = label("가 ▾", "글씨체", 48f) { showFontMenu(it) }
        fmtSizeLabel = label("20 ▾", "글자 크기", 56f) { showSizeMenu(it) }
        sep()
        val bold = label("B", "굵게") { textEditor.toggle(CharToggle.BOLD) }.apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val italic = label("I", "기울임") { textEditor.toggle(CharToggle.ITALIC) }.apply {
            typeface = android.graphics.Typeface.create(android.graphics.Typeface.SERIF, android.graphics.Typeface.ITALIC)
        }
        val under = label("U", "밑줄") { textEditor.toggle(CharToggle.UNDERLINE) }.apply {
            paintFlags = paintFlags or android.graphics.Paint.UNDERLINE_TEXT_FLAG
        }
        val strike = label("S", "취소선") { textEditor.toggle(CharToggle.STRIKE) }.apply {
            paintFlags = paintFlags or android.graphics.Paint.STRIKE_THRU_TEXT_FLAG
        }
        fmtToggles = mapOf(CharToggle.BOLD to bold, CharToggle.ITALIC to italic, CharToggle.UNDERLINE to under, CharToggle.STRIKE to strike)
        sep()
        // 목록 (체크 · 번호 · 점)과 정렬 (왼쪽 · 가운데 · 오른쪽)은 한 칸씩. 누르면 셋 중 고르는 창이 뜬다
        fmtListButton = icon(R.drawable.ic_fmt_list_bullet, "목록") { showChoicePopup(it, listChoices, fmtState?.para?.list) { k -> textEditor.setList(k) } }
        fmtAlignButton = icon(R.drawable.ic_fmt_align_left, "정렬") { showChoicePopup(it, alignChoices, fmtState?.para?.align) { a -> textEditor.setAlign(a) } }
        sep()
        icon(R.drawable.ic_fmt_indent, "들여쓰기") { textEditor.indent(1) }
        icon(R.drawable.ic_fmt_outdent, "내어쓰기") { textEditor.indent(-1) }
        fmtListButton.setImageDrawable(choiceIcon(R.drawable.ic_fmt_list_bullet))
        fmtAlignButton.setImageDrawable(choiceIcon(R.drawable.ic_fmt_align_left))
        fit()

        textEditor.onEditingChanged = { editing ->
            bar().visibility = if (editing) View.VISIBLE else View.GONE
            if (editing) hideOptionBar()
        }
        textEditor.onFormatChanged = { update(it) }
    }

    /** 서식 줄에 지금 서식을 보인다 (켜진 서식은 칸이 칠해진다) */
    private fun update(st: InlineTextEditor.FormatState) {
        fmtState = st
        fmtToggles.forEach { (t, v) -> v.isSelected = Rich.has(st.style, t) }
        // 목록 칸: 켜진 목록의 아이콘 (없으면 점 목록 아이콘, 칠하지 않음). 정렬 칸: 지금 정렬의 아이콘
        val list = listChoices.firstOrNull { it.value == st.para.list }
        fmtListButton.setImageDrawable(choiceIcon((list ?: listChoices.last()).icon))
        fmtListButton.isSelected = list != null
        fmtAlignButton.setImageDrawable(choiceIcon(alignChoices.first { it.value == st.para.align }.icon))
        fmtColorBar.setBackgroundColor(st.style.color ?: textEditor.color)
        val d = ctx.resources.displayMetrics.density
        fmtBgSwatch.background = GradientDrawable().apply {
            cornerRadius = 6 * d
            setColor(st.style.bg ?: Color.TRANSPARENT)
            setStroke((1 * d).toInt(), Color.argb(if (st.style.bg == null) 90 else 40, 0, 0, 0))
        }
        fmtSizeLabel.text = "${ptLabel(st.sizePt)}${dropSep()}▾"
        fmtFontLabel.typeface = st.style.font.typeface
    }

    /** 세로 서식 줄에서는 '▾'를 글 아래 줄로 */
    private fun dropSep() = if (dock.side.vertical) "\n" else " "

    /** 서식 줄의 칸들을 지금 툴바 방향(가로/세로)에 맞춘다 */
    fun fit() {
        val v = dock.side.vertical
        val d = ctx.resources.displayMetrics.density
        dock.fit(row)
        row.gravity = android.view.Gravity.CENTER
        row.setPadding(((if (v) 4 else 8) * d).toInt(), ((if (v) 8 else 4) * d).toInt(),
            ((if (v) 4 else 8) * d).toInt(), ((if (v) 8 else 4) * d).toInt())
        // 세로 줄은 칸이 좁아 글을 줄여 두 줄로
        fmtPresetLabel.text = "서식${dropSep()}▾"
        fmtFontLabel.text = "가${dropSep()}▾"
        fmtFontLabel.textSize = if (v) 15f else 17f
        fmtFontLabel.setLineSpacing(0f, if (v) 0.9f else 1f)
        fmtSizeLabel.text = "${ptLabel(fmtState?.sizePt ?: 20f)}${dropSep()}▾"
        for (l in listOf(fmtPresetLabel, fmtSizeLabel)) {
            l.textSize = if (v) 13f else 17f
            l.setLineSpacing(0f, if (v) 0.9f else 1f)
        }
    }

    /** 서식 아이콘 + 오른쪽 아래 작은 삼각형 (누르면 고르는 창이 뜬다는 표시) */
    private fun choiceIcon(res: Int) = LayerDrawable(arrayOf(ctx.getDrawable(res)!!, ctx.getDrawable(R.drawable.ic_corner_more)!!))

    /**
     * 목록·정렬 칸을 누르면 뜨는 창: 셋 중 하나를 고른다 (지금 것은 칠해져 있다).
     * 서식 줄이 가로면 칸 위(아래)에 가로로, 세로면 칸 옆(문서 쪽)에 세로로 뜬다
     */
    private fun <T> showChoicePopup(anchor: View, choices: List<Choice<T>>, current: T?, onPick: (T) -> Unit) {
        val d = ctx.resources.displayMetrics.density
        val vertical = dock.side.vertical
        val box = LinearLayout(ctx).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            val pd = (6 * d).toInt()
            setPadding(pd, pd, pd, pd)
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurfaceContainerHigh))
            }
        }
        val popup = android.widget.PopupWindow(box, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 8 * d
        choices.forEachIndexed { i, c ->
            box.addView(ImageButton(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply {
                    if (i > 0) { if (vertical) topMargin = (2 * d).toInt() else marginStart = (2 * d).toInt() }
                }
                setBackgroundResource(R.drawable.bg_tool)
                setImageResource(c.icon)
                contentDescription = c.desc
                tooltipText = c.desc
                isSelected = c.value == current
                setOnClickListener {
                    onPick(c.value)
                    popup.dismiss()
                }
            })
        }
        showPopupBeside(ctx.window, dock.side, popup, box, anchor)
    }

    private fun ptLabel(v: Float) = if (kotlin.math.abs(v - v.roundToInt()) < 0.05f) "${v.roundToInt()}" else String.format("%.1f", v)

    /** 서식 줄의 창은 문서 쪽으로 (툴바가 위면 아래로, 아래면 위로, 왼쪽·오른쪽이면 옆으로) */
    private fun fmtSide() = dock.side

    private fun openTextColorPicker(anchor: View) {
        val initial = fmtState?.style?.color ?: textEditor.color
        ColorPickerPopup(ctx, initial, recentColors()) { c, done ->
            textEditor.setTextColor(c)
            if (done) addRecent(c)
        }.show(anchor, fmtSide())
    }

    /** 배경색 고르기: 없음 + 옅은 색들 */
    private fun showBgColorPopup(anchor: View) {
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            val pd = (8 * d).toInt()
            setPadding(pd, pd, pd, pd)
            background = GradientDrawable().apply {
                cornerRadius = 16 * d
                setColor(MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurfaceContainerHigh))
            }
            elevation = 8 * d
        }
        val popup = android.widget.PopupWindow(row, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, true)
        popup.elevation = 8 * d
        for (c in listOf<Int?>(null) + bgColors.toList()) {
            row.addView(TextView(ctx).apply {
                layoutParams = LinearLayout.LayoutParams((34 * d).toInt(), (34 * d).toInt()).apply {
                    marginStart = (3 * d).toInt()
                    marginEnd = (3 * d).toInt()
                }
                gravity = android.view.Gravity.CENTER
                text = if (c == null) "없음" else "가"
                textSize = if (c == null) 11f else 15f
                contentDescription = if (c == null) "배경색 없음" else "배경색"
                background = GradientDrawable().apply {
                    cornerRadius = 8 * d
                    setColor(c ?: Color.TRANSPARENT)
                    setStroke((1 * d).toInt(), Color.argb(70, 0, 0, 0))
                }
                setOnClickListener {
                    textEditor.setBgColor(c)
                    popup.dismiss()
                }
            })
        }
        val (x, y, w) = placeNear(ctx, row, anchor, (bgColors.size + 1) * 40f + 16f, fmtSide())
        popup.width = w
        popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, x, y)
    }

    /** 기본 서식: 제목 · 소제목 · 본문 · 작은 글 (고른 문단 전체에) */
    private fun showPresetMenu(anchor: View) {
        val popup = PopupMenu(ctx, anchor)
        TextPreset.entries.forEachIndexed { i, p ->
            val title = android.text.SpannableString(p.label).apply {
                setSpan(android.text.style.RelativeSizeSpan(p.ratio.coerceIn(0.85f, 1.5f)), 0, length, 0)
                if (p.bold) setSpan(android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, length, 0)
            }
            popup.menu.add(0, i, i, title)
        }
        popup.setOnMenuItemClickListener { item ->
            textEditor.applyPreset(TextPreset.entries[item.itemId])
            true
        }
        popup.show()
    }

    /** 글씨체 고르기: 이름을 그 글씨체로 보인다 */
    private fun showFontMenu(anchor: View) {
        val popup = PopupMenu(ctx, anchor)
        val cur = fmtState?.style?.font ?: TextFont.DEFAULT
        TextFont.entries.forEach { f ->
            val title = android.text.SpannableString(f.label).apply {
                setSpan(android.text.style.TypefaceSpan(f.typeface), 0, length, 0)
                setSpan(android.text.style.RelativeSizeSpan(1.15f), 0, length, 0)
            }
            popup.menu.add(2, f.ordinal, f.ordinal, title).isChecked = f == cur
        }
        popup.menu.setGroupCheckable(2, true, true)
        popup.setOnMenuItemClickListener { item ->
            textEditor.setFont(TextFont.entries[item.itemId])
            true
        }
        popup.show()
    }

    /** 글자 크기 (pt) 고르기 */
    private fun showSizeMenu(anchor: View) {
        val popup = PopupMenu(ctx, anchor)
        val cur = fmtState?.sizePt ?: 20f
        val nearest = fmtSizes.indices.minBy { kotlin.math.abs(fmtSizes[it] - cur) }
        fmtSizes.forEachIndexed { i, pt ->
            popup.menu.add(1, i, i, "${ptLabel(pt)}pt").isChecked = i == nearest
        }
        popup.menu.setGroupCheckable(1, true, true)
        popup.setOnMenuItemClickListener { item ->
            textEditor.setSizePt(fmtSizes[item.itemId])
            true
        }
        popup.show()
    }
}
