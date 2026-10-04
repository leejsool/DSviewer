package com.dsviewer.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

/** 오답노트 창들: 담기(분류 입력) · 분류 고치기 · 오답 목록(기호·해시태그로 거르기) */
class WrongUi(private val a: AppCompatActivity, private val prefs: SharedPreferences) {

    /** 담기 창에서 고른 것 */
    class Choice(val symbol: Int, val tags: List<String>, val title: String, val half: Boolean, val paper: Paper)

    private fun dp(v: Int) = (v * a.resources.displayMetrics.density).roundToInt()

    private fun label(text: String) = TextView(a).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(14), 0, dp(4))
    }

    /** 분류 입력 칸들: 기호 칩, 해시태그 글, (이미 쓴 태그 칩), 제목 */
    private class Form(val root: LinearLayout, val symbol: () -> Int, val tags: () -> List<String>, val title: () -> String)

    private fun buildForm(symbol0: Int, tags0: List<String>, title0: String, allTags: List<String>): Form {
        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }
        var symbol = symbol0

        root.addView(label("① 기호  (다시 누르면 해제)"))
        val group = ChipGroup(a).apply {
            isSingleSelection = true
            isSelectionRequired = false
        }
        for (s in WrongSymbol.ALL) {
            val chip = Chip(a).apply {
                text = WrongSymbol.label(s)
                isCheckable = true
                isChecked = s == symbol0
                setTextColor(WrongSymbol.color(s))
            }
            chip.setOnCheckedChangeListener { _, on ->
                if (on) symbol = s else if (symbol == s) symbol = WrongSymbol.NONE
            }
            group.addView(chip)
        }
        root.addView(group)

        root.addView(label("② 해시태그  (띄어쓰기로 구분)"))
        val tagInput = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
            isSingleLine = true
            hint = "#미적분 #극한"
            setText(if (tags0.isEmpty()) "" else WrongNote.tagsText(tags0) + " ")
            setSelection(text.length)
        }
        root.addView(tagInput)
        if (allTags.isNotEmpty()) {
            val scroll = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
            val tagGroup = ChipGroup(a).apply { isSingleLine = true }
            for (t in allTags) {
                val chip = Chip(a).apply { text = "#$t" }
                chip.setOnClickListener {
                    val now = WrongNote.parseTags(tagInput.text.toString())
                    val next = if (t in now) now - t else now + t
                    tagInput.setText(if (next.isEmpty()) "" else WrongNote.tagsText(next) + " ")
                    tagInput.setSelection(tagInput.text.length)
                }
                tagGroup.addView(chip)
            }
            scroll.addView(tagGroup)
            root.addView(scroll)
        }

        root.addView(label("제목 · 메모  (선택)"))
        val titleInput = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            isSingleLine = true
            hint = "예: 3번 — 치환 적분"
            setText(title0)
            setSelection(text.length)
        }
        root.addView(titleInput)

        return Form(root, { symbol }, { WrongNote.parseTags(tagInput.text.toString()) }, { titleInput.text.toString().trim() })
    }

    private fun radioRow(options: List<String>, checked: Int): RadioGroup = RadioGroup(a).apply {
        orientation = RadioGroup.HORIZONTAL
        for ((i, text) in options.withIndex()) {
            addView(RadioButton(a).apply {
                id = View.generateViewId()
                this.text = text
                isChecked = i == checked
                setPadding(dp(4), dp(4), dp(14), dp(4))
            })
        }
    }

    private fun RadioGroup.checkedIndex(): Int {
        for (i in 0 until childCount) if ((getChildAt(i) as RadioButton).isChecked) return i
        return 0
    }

    /** 고른 영역을 오답노트에 담는 창. [onRetry]는 '다시 고르기', [onDismiss]는 어떻게 닫히든 마지막에 */
    fun showCapture(
        preview: Bitmap, allTags: List<String>,
        onOk: (Choice) -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit,
    ) {
        val form = buildForm(WrongSymbol.NONE, emptyList(), "", allTags)
        val image = ImageView(a).apply {
            setImageBitmap(preview)
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            maxHeight = dp(190)
            setBackgroundColor(0xFFFFFFFF.toInt())
            setPadding(dp(2), dp(2), dp(2), dp(2))
        }
        form.root.addView(image, 0, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        form.root.addView(label("오답 쪽 배치"))
        val layout = radioRow(listOf("한 문제 한 쪽", "반 쪽 (한 쪽에 두 문제)"), if (prefs.getBoolean("wrongHalf", false)) 1 else 0)
        form.root.addView(layout)
        form.root.addView(label("풀이 칸 바탕  (새 쪽을 만들 때)"))
        val papers = listOf(Paper.LINED, Paper.GRID, Paper.PLAIN)
        val paper = radioRow(papers.map { it.label }, papers.indexOf(
            Paper.values().firstOrNull { it.name == prefs.getString("wrongPaper", Paper.LINED.name) } ?: Paper.LINED))
        form.root.addView(paper)

        MaterialAlertDialogBuilder(a)
            .setTitle("오답노트에 담기")
            .setView(ScrollView(a).apply { addView(form.root) })
            .setPositiveButton("담기") { _, _ ->
                val half = layout.checkedIndex() == 1
                val p = papers[paper.checkedIndex()]
                prefs.edit().putBoolean("wrongHalf", half).putString("wrongPaper", p.name).apply()
                onOk(Choice(form.symbol(), form.tags(), form.title(), half, p))
            }
            .setNeutralButton("다시 고르기") { _, _ -> onRetry() }
            .setNegativeButton("취소", null)
            .setOnDismissListener { onDismiss() }
            .show()
    }

    /** 이미 담은 오답의 기호·해시태그·제목 고치기 */
    fun showEdit(e: WrongEntry, allTags: List<String>, onOk: (symbol: Int, tags: List<String>, title: String) -> Unit) {
        val form = buildForm(e.symbol, e.tags, e.title, allTags)
        MaterialAlertDialogBuilder(a)
            .setTitle("#${e.number} 분류 고치기")
            .setView(ScrollView(a).apply { addView(form.root) })
            .setPositiveButton("저장") { _, _ -> onOk(form.symbol(), form.tags(), form.title()) }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 목록 줄 앞에 그리는 기호 */
    private class SymbolView(ctx: Context, private val symbol: Int, private val sizePx: Float) : View(ctx) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension((sizePx * 5.6f).roundToInt(), (sizePx * 1.5f).roundToInt())
        }

        override fun onDraw(c: Canvas) {
            if (symbol == WrongSymbol.NONE) {
                paint.color = 0xFF9E9E9E.toInt()
                paint.textSize = sizePx
                c.drawText("–", sizePx * 0.4f, height / 2f + sizePx * 0.35f, paint)
            } else WrongSymbol.draw(c, symbol, 0f, height / 2f, sizePx, paint)
        }
    }

    /**
     * 오답 목록. 기호·해시태그 칩과 검색 글로 거르고, 줄을 누르면 그 오답으로 간다.
     * [provider]는 지금의 오답 전부 (쪽 번호, 항목), [onEdit]는 분류 고치기 ([done]을 부르면 목록을 새로 그린다)
     */
    fun showList(
        provider: () -> List<Pair<Int, WrongEntry>>,
        onGo: (page: Int, e: WrongEntry) -> Unit,
        onSource: (e: WrongEntry) -> Unit,
        onEdit: (e: WrongEntry, done: () -> Unit) -> Unit,
    ) {
        var symbolFilter: Int? = null
        var tagFilter: String? = null
        var query = ""

        val root = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }
        val search = EditText(a).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            isSingleLine = true
            hint = "제목 · 태그 검색"
        }
        val symbolScroll = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
        val tagScroll = HorizontalScrollView(a).apply { isHorizontalScrollBarEnabled = false }
        val countText = TextView(a).apply { textSize = 12f; setPadding(0, dp(8), 0, dp(4)) }
        val rows = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        root.addView(search)
        root.addView(symbolScroll)
        root.addView(tagScroll)
        root.addView(countText)
        root.addView(rows)

        lateinit var render: () -> Unit
        var dialog: androidx.appcompat.app.AlertDialog? = null
        val go = { page: Int, e: WrongEntry -> dialog?.dismiss(); onGo(page, e) }
        val goSource = { e: WrongEntry -> dialog?.dismiss(); onSource(e) }

        fun chipRow(host: HorizontalScrollView, items: List<Pair<String, Any?>>, selected: Any?, onPick: (Any?) -> Unit) {
            host.removeAllViews()
            val g = ChipGroup(a).apply {
                isSingleLine = true
                isSingleSelection = true
                isSelectionRequired = true
            }
            for ((text, key) in items) {
                val chip = Chip(a).apply {
                    this.text = text
                    isCheckable = true
                    isChecked = key == selected
                }
                chip.setOnCheckedChangeListener { _, on -> if (on && key != selected) onPick(key) }
                g.addView(chip)
            }
            host.addView(g)
        }

        render = {
            val all = provider()
            // 기호·태그 칩: 지금 있는 것만, 개수와 함께
            val symbols = all.groupBy { it.second.symbol }.toSortedMap(compareBy { WrongSymbol.order(it) })
            chipRow(symbolScroll,
                listOf<Pair<String, Any?>>("전체 (${all.size})" to null) + symbols.map { (s, l) -> "${WrongSymbol.label(s)} (${l.size})" to (s as Any?) },
                symbolFilter) { symbolFilter = it as Int?; render() }
            val tags = all.flatMap { p -> p.second.tags }.groupingBy { it }.eachCount().toList().sortedByDescending { it.second }
            tagScroll.visibility = if (tags.isEmpty()) View.GONE else View.VISIBLE
            chipRow(tagScroll,
                listOf<Pair<String, Any?>>("모든 태그" to null) + tags.map { (t, n) -> "#$t ($n)" to (t as Any?) },
                tagFilter) { tagFilter = it as String?; render() }

            val shown = all.filter { (_, e) ->
                (symbolFilter == null || e.symbol == symbolFilter) &&
                    (tagFilter == null || tagFilter in e.tags) &&
                    (query.isEmpty() || e.title.contains(query, true) || e.tags.any { it.contains(query.trimStart('#'), true) })
            }
            countText.text = if (all.isEmpty()) "아직 담은 오답이 없습니다. 삽입 ▸ 오답 담기로 시작하세요." else "${shown.size}개 (전체 ${all.size}개)"
            rows.removeAllViews()
            for ((page, e) in shown) rows.addView(row(page, e, go, goSource) { onEdit(e) { render() } })
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                query = s?.toString()?.trim().orEmpty()
                render()
            }
        })
        render()

        dialog = MaterialAlertDialogBuilder(a)
            .setTitle("오답노트")
            .setView(ScrollView(a).apply { addView(root) })
            .setPositiveButton("닫기", null)
            .show()
    }

    private fun row(
        page: Int, e: WrongEntry,
        onGo: (Int, WrongEntry) -> Unit, onSource: (WrongEntry) -> Unit, onEdit: () -> Unit,
    ): View {
        val line = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(8))
            isClickable = true
            setOnClickListener { onGo(page, e) }
        }
        line.addView(SymbolView(a, e.symbol, dp(13).toFloat()))
        val mid = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        mid.addView(TextView(a).apply {
            text = "#${e.number}" + if (e.title.isNotBlank()) "  ${e.title}" else ""
            textSize = 15f
            setTypeface(typeface, Typeface.BOLD)
        })
        if (e.tags.isNotEmpty()) mid.addView(TextView(a).apply {
            text = WrongNote.tagsText(e.tags)
            textSize = 13f
            setTextColor(0xFF1565C0.toInt())
        })
        mid.addView(TextView(a).apply {
            text = "${page + 1}쪽 · ${e.date}"
            textSize = 11f
            setTextColor(0xFF78909C.toInt())
        })
        line.addView(mid, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        fun action(text: String, f: () -> Unit) = TextView(a).apply {
            this.text = text
            textSize = 13f
            setTextColor(0xFF1565C0.toInt())
            setPadding(dp(10), dp(8), dp(10), dp(8))
            setOnClickListener { f() }
        }
        if (e.srcList != null) line.addView(action("원문") { onSource(e) })
        line.addView(action("분류") { onEdit() })
        return line
    }
}
