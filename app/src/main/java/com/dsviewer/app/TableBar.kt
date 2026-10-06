package com.dsviewer.app

import android.graphics.RectF
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import com.google.android.material.card.MaterialCardView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder

/**
 * 표를 골랐을 때 표 아래(자리가 없으면 위)에 뜨는 표 편집 막대 (한글의 표 도구 모음처럼):
 * 칸 선택 · 표 그리기(줄 긋기) · 표 지우개(줄 지우기) | 줄 추가 · 칸 추가 · 줄 삭제 · 칸 삭제 | 셀 합치기 · 셀 나누기 |
 * 같게 · 제목 줄 · 선 굵기. 칸을 고르지 않으면 칸이 필요한 단추는 흐리게 보이고, 누르면 안내가 뜬다
 */
class TableBar(
    private val activity: AppCompatActivity,
    private val frame: FrameLayout,
    /** 지금 고른 칸의 문서 화면 */
    private val view: () -> DocumentView,
) {
    private val d = activity.resources.displayMetrics.density
    private val card = MaterialCardView(activity).apply {
        radius = 22 * d
        cardElevation = 6 * d
        visibility = View.GONE
        isClickable = true
    }
    private val row = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (4 * d).toInt())
    }
    private var lastRect: RectF? = null

    private class Item(val view: View, val needsCells: Boolean, val enabled: (() -> Boolean)? = null)
    private val items = ArrayList<Pair<TableController.Mode?, Item>>()
    private val modeItems = HashMap<TableController.Mode, View>()
    private lateinit var headerLabel: TextView
    private lateinit var splitLabel: TextView

    private val tables get() = view().tables

    init {
        // 스크롤이 생기면 양 끝에 « » 단추가 나타난다 (다른 툴바와 같게)
        val scroll = EdgeArrowScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(row)
        }
        card.addView(scroll)
        frame.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        build()
    }

    // ================= 칸 만들기 =================

    private fun item(icon: Int, label: String, desc: String, needsCells: Boolean = false, onClick: (View) -> Unit): View {
        val v = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            minimumWidth = (52 * d).toInt()
            setPadding((5 * d).toInt(), (4 * d).toInt(), (5 * d).toInt(), (3 * d).toInt())
            setBackgroundResource(R.drawable.bg_tool)
            contentDescription = desc
            tooltipText = desc
            setOnClickListener { onClick(it) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = (1 * d).toInt() }
        }
        v.addView(ImageView(activity).apply {
            setImageResource(icon)
            layoutParams = LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        v.addView(TextView(activity).apply {
            text = label
            isSingleLine = true
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        row.addView(v)
        items.add(null to Item(v, needsCells))
        return v
    }

    private fun separator() = row.addView(View(activity).apply {
        layoutParams = LinearLayout.LayoutParams((1 * d).toInt(), (32 * d).toInt()).apply {
            marginStart = (4 * d).toInt()
            marginEnd = (5 * d).toInt()
        }
        setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant))
    })

    private fun menu(anchor: View, entries: List<Triple<String, Boolean, () -> Unit>>, title: String? = null) {
        val popup = PopupMenu(activity, anchor)
        entries.forEachIndexed { i, (label, checked, _) ->
            popup.menu.add(0, i, i, label).apply { isCheckable = checked; isChecked = checked }
        }
        popup.menu.setGroupCheckable(0, true, true)
        popup.setOnMenuItemClickListener { item -> entries[item.itemId].third(); true }
        popup.show()
    }

    private fun build() {
        // 방식: 칸 선택 / 표 그리기 / 표 지우개
        fun modeItem(m: TableController.Mode, icon: Int, label: String, desc: String, toast: String?) {
            val v = item(icon, label, desc) {
                tables.setMode(m)
                if (toast != null && tables.mode == m) Toast.makeText(activity, toast, Toast.LENGTH_SHORT).show()
            }
            modeItems[m] = v
        }
        modeItem(TableController.Mode.SELECT, R.drawable.ic_tbl_select, "칸 선택", "칸을 누르거나 끌어 고르기. 선을 끌면 간격이 바뀝니다", null)
        modeItem(
            TableController.Mode.DRAW, R.drawable.ic_tbl_draw, "표 그리기", "줄을 그어 칸 나누기",
            "칸을 가로지르거나 세로지르게 줄을 그으면 그 칸이 나뉩니다.",
        )
        modeItem(
            TableController.Mode.ERASE, R.drawable.ic_tbl_erase, "표 지우개", "줄을 따라 그어 그 줄만 지워 칸 합치기",
            "지우려는 줄 위를 따라 그으면, 그은 구간의 그 줄만 지워져 양쪽 칸이 합쳐집니다.",
        )
        separator()
        item(R.drawable.ic_tbl_row_add, "줄 추가 ▾", "고른 칸의 위나 아래에 줄 넣기", needsCells = true) { a ->
            menu(a, listOf(
                Triple("위에 줄 추가", false) { tables.insertRow(below = false) },
                Triple("아래에 줄 추가", false) { tables.insertRow(below = true) },
            ))
        }
        item(R.drawable.ic_tbl_col_add, "칸 추가 ▾", "고른 칸의 왼쪽이나 오른쪽에 칸 넣기", needsCells = true) { a ->
            menu(a, listOf(
                Triple("왼쪽에 칸 추가", false) { tables.insertCol(right = false) },
                Triple("오른쪽에 칸 추가", false) { tables.insertCol(right = true) },
            ))
        }
        item(R.drawable.ic_tbl_row_del, "줄 삭제", "고른 칸이 있는 줄 지우기", needsCells = true) { tables.deleteRows() }
        item(R.drawable.ic_tbl_col_del, "칸 삭제", "고른 칸이 있는 칸 지우기", needsCells = true) { tables.deleteCols() }
        separator()
        item(R.drawable.ic_tbl_merge, "셀 합치기", "고른 칸들을 한 셀로", needsCells = true) { tables.merge() }
        item(R.drawable.ic_tbl_split, "셀 나누기", "합친 셀 풀기, 한 칸을 여러 줄·칸으로 나누기", needsCells = true) { split() }
        separator()
        item(R.drawable.ic_tbl_equal, "같게 ▾", "줄 높이·칸 너비를 같게") { a ->
            menu(a, listOf(
                Triple("줄 높이를 같게", false) { tables.equalizeRows() },
                Triple("칸 너비를 같게", false) { tables.equalizeCols() },
            ))
        }
        val header = item(R.drawable.ic_tbl_header, "제목 줄 ▾", "제목 줄: 가로만·세로만·가로세로 모두") { a ->
            val cur = tables.header
            menu(a, TableHeader.entries.map { h -> Triple(headerName(h), h == cur) { tables.setHeader(h) } })
        }
        headerLabel = (header as LinearLayout).getChildAt(1) as TextView
        item(R.drawable.ic_tbl_weight, "선 굵기 ▾", "표 선의 굵기") { a ->
            val cur = tables.lineWidth
            val near = WIDTHS.minByOrNull { kotlin.math.abs(it.first - cur) }?.first
            menu(a, WIDTHS.map { (w, label) -> Triple(label, w == near) { tables.setLineWidth(w) } })
        }
    }

    private fun headerName(h: TableHeader) = when (h) {
        TableHeader.NONE -> "제목 줄 없음"
        TableHeader.ROW -> "가로 제목 (첫 줄)"
        TableHeader.COL -> "세로 제목 (첫 칸)"
        TableHeader.BOTH -> "가로·세로 모두"
    }

    /** 셀 나누기: 합친 셀이면 바로 풀고, 한 칸이면 몇 줄 몇 칸으로 나눌지 묻는다 */
    private fun split() {
        val t = tables
        if (t.cells == null) { t.unmerge(); return }   // 안내는 컨트롤러가 띄운다
        if (t.canUnmerge()) { t.unmerge(); return }
        if (!t.singlePlainCell()) return
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
        }
        var rows = 1
        var cols = 2
        fun stepper(label: String, get: () -> Int, set: (Int) -> Unit): View {
            val line = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            line.addView(TextView(activity).apply {
                text = label
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val value = TextView(activity).apply {
                textSize = 18f
                gravity = Gravity.CENTER
                minWidth = (44 * d).toInt()
            }
            fun show() { value.text = get().toString() }
            fun button(text: String, delta: Int) = com.google.android.material.button.MaterialButton(
                activity, null, com.google.android.material.R.attr.materialIconButtonFilledTonalStyle,
            ).apply {
                this.text = text
                setOnClickListener { set((get() + delta).coerceIn(1, 10)); show() }
            }
            line.addView(button("−", -1))
            line.addView(value)
            line.addView(button("+", 1))
            show()
            return line
        }
        box.addView(stepper("줄 수", { rows }, { rows = it }))
        box.addView(stepper("칸 수", { cols }, { cols = it }))
        MaterialAlertDialogBuilder(activity)
            .setTitle("셀 나누기")
            .setMessage("고른 칸을 몇 줄, 몇 칸으로 나눌까요?")
            .setView(box)
            .setNegativeButton("취소", null)
            .setPositiveButton("나누기") { _, _ ->
                if (rows * cols < 2) Toast.makeText(activity, "줄이나 칸 중 하나는 2 이상이어야 합니다.", Toast.LENGTH_SHORT).show()
                else tables.subdivide(rows, cols)
            }
            .show()
    }

    // ================= 보이기·자리 =================

    /** 표 편집 상태가 바뀜: 보이고 숨기고, 방식·흐림·제목 줄 이름을 맞춘다 */
    fun refresh() {
        val t = tables
        if (!t.active) {
            card.visibility = View.GONE
            return
        }
        val hasCells = t.cells != null
        for ((_, it) in items) it.view.alpha = if (it.needsCells && !hasCells) 0.38f else 1f
        for ((m, v) in modeItems) v.isSelected = t.mode == m
        headerLabel.text = if (t.header == TableHeader.NONE) "제목 줄 ▾" else "제목:" + when (t.header) {
            TableHeader.ROW -> "가로"
            TableHeader.COL -> "세로"
            else -> "가로세로"
        } + " ▾"
        if (card.visibility != View.VISIBLE) {
            card.visibility = View.VISIBLE
            // 처음 뜰 때는 자리를 바로 잡아 둔다 (자리를 못 받았으면 위쪽 가운데)
            place(lastRect)
        }
    }

    /** 선택 상자 [rect](화면 좌표)에 맞춰 표 아래로, 자리가 없으면 선택 막대 위로 옮긴다 */
    fun place(rect: RectF?) {
        lastRect = rect
        if (card.visibility != View.VISIBLE) return
        val v = view()
        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = card.measuredWidth.coerceAtMost(v.width)
        val h = card.measuredHeight
        val gap = 8 * d
        var ox = 0f
        var oy = 0f
        var cur: View = v
        while (cur !== frame) {
            ox += cur.left + cur.translationX
            oy += cur.top + cur.translationY
            cur = cur.parent as? View ?: break
        }
        var x = gap
        var y = gap
        if (rect != null && rect.intersects(0f, 0f, v.width.toFloat(), v.height.toFloat())) {
            x = (rect.centerX() - w / 2f).coerceIn(gap, (v.width - w - gap).coerceAtLeast(gap))
            // 표 아래 (회전 손잡이 아래), 안 들어가면 선택 막대(삭제·복사) 위
            y = rect.bottom + 64 * d
            if (y + h > v.height - gap) y = rect.top - 8 * d - 44 * d - 8 * d - h
            y = y.coerceIn(gap, (v.height - h - gap).coerceAtLeast(gap))
        } else {
            x = ((v.width - w) / 2f).coerceAtLeast(gap)
        }
        card.layoutParams = card.layoutParams.also { it.width = w }
        card.translationX = ox + x
        card.translationY = oy + y
    }

    companion object {
        private val WIDTHS = listOf(
            0.6f to "가늘게 (0.6)", 1.2f to "보통 (1.2)", 2f to "굵게 (2)", 3.2f to "아주 굵게 (3.2)",
        )
    }
}
