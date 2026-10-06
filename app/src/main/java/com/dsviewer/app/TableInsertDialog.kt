package com.dsviewer.app

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.roundToInt

/**
 * 표 만들기 창 (한글의 '표 만들기'를 본뜸): 칸을 끌어 줄·칸 수를 고르는 격자, 줄 수·칸 수, 제목 줄(없음·가로·세로·가로세로),
 * 크기(너비·높이 mm), 만들 표 미리 보기. 만들면 [onCreate]로 표를 넘긴다
 */
object TableInsertDialog {
    private const val MAX_ROWS = 30
    private const val MAX_COLS = 20
    private const val MM = 72f / 25.4f

    fun show(activity: AppCompatActivity, pageWidthPt: Float, pageHeightPt: Float, prefs: SharedPreferences, onCreate: (InkTable) -> Unit) {
        val d = activity.resources.displayMetrics.density
        var rows = prefs.getInt("tableRows", 5).coerceIn(1, MAX_ROWS)
        var cols = prefs.getInt("tableCols", 4).coerceIn(1, MAX_COLS)
        var header = TableHeader.of(prefs.getString("tableHeader", "N")?.firstOrNull())
        // 크기는 쪽에 맞춰 기본값을 잡고, 직접 고쳤으면 줄·칸 수를 바꿔도 그대로 둔다
        val maxW = pageWidthPt * 0.9f
        val maxH = pageHeightPt * 0.85f
        var widthMm = (maxW * 0.9f / MM).roundToInt().toFloat()
        var heightEdited = false
        // 높이 칸에 글자를 넣는 것은 직접 고친 것이 아니다
        var programmatic = false
        fun defaultHeight() = minOf(rows * 10f, maxH / MM).roundToInt().toFloat()
        var heightMm = defaultHeight()

        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((22 * d).toInt(), (6 * d).toInt(), (22 * d).toInt(), 0)
        }
        val label = TextView(activity).apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(0, (6 * d).toInt(), 0, (6 * d).toInt())
        }
        val picker = GridPicker(activity)
        val preview = PreviewView(activity)

        lateinit var rowsText: TextView
        lateinit var colsText: TextView
        lateinit var widthEdit: EditText
        lateinit var heightEdit: EditText

        fun table(): InkTable = InkTable.create(rows, cols, widthMm * MM, heightMm * MM, header)

        fun refresh(fromFields: Boolean = false) {
            label.text = "${rows}줄 × ${cols}칸"
            rowsText.text = rows.toString()
            colsText.text = cols.toString()
            picker.set(rows, cols)
            if (!heightEdited) {
                heightMm = defaultHeight()
                if (!fromFields) {
                    programmatic = true
                    heightEdit.setText(heightMm.roundToInt().toString())
                    programmatic = false
                }
            }
            preview.table = table()
        }

        picker.onPick = { r, c -> rows = r; cols = c; refresh() }

        fun stepper(name: String, get: () -> Int, set: (Int) -> Unit, max: Int): Pair<View, TextView> {
            val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(TextView(activity).apply {
                text = name
                textSize = 16f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            val value = TextView(activity).apply {
                textSize = 18f
                gravity = Gravity.CENTER
                minWidth = (40 * d).toInt()
            }
            fun button(text: String, delta: Int) = MaterialButton(
                activity, null, com.google.android.material.R.attr.materialIconButtonFilledTonalStyle,
            ).apply {
                this.text = text
                setOnClickListener { set((get() + delta).coerceIn(1, max)); refresh() }
            }
            line.addView(button("−", -1))
            line.addView(value)
            line.addView(button("+", 1))
            return line to value
        }

        // 격자 + 줄·칸 수 + 미리 보기
        root.addView(picker, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { gravity = Gravity.CENTER_HORIZONTAL })
        root.addView(label)
        val rowStep = stepper("줄 수", { rows }, { rows = it }, MAX_ROWS)
        val colStep = stepper("칸 수", { cols }, { cols = it }, MAX_COLS)
        rowsText = rowStep.second
        colsText = colStep.second
        root.addView(rowStep.first)
        root.addView(colStep.first)

        // 제목 줄
        root.addView(TextView(activity).apply {
            text = "제목 줄"
            textSize = 14f
            setPadding(0, (12 * d).toInt(), 0, (2 * d).toInt())
        })
        val group = RadioGroup(activity).apply { orientation = RadioGroup.HORIZONTAL }
        val ids = HashMap<TableHeader, Int>()
        for (h in TableHeader.entries) {
            val rb = RadioButton(activity).apply {
                id = View.generateViewId()
                text = when (h) {
                    TableHeader.NONE -> "없음"
                    TableHeader.ROW -> "가로"
                    TableHeader.COL -> "세로"
                    TableHeader.BOTH -> "가로·세로"
                }
                contentDescription = when (h) {
                    TableHeader.NONE -> "제목 줄 없음"
                    TableHeader.ROW -> "첫 줄을 제목으로 (가로)"
                    TableHeader.COL -> "첫 칸을 제목으로 (세로)"
                    TableHeader.BOTH -> "첫 줄과 첫 칸을 모두 제목으로"
                }
            }
            ids[h] = rb.id
            group.addView(rb, RadioGroup.LayoutParams(0, RadioGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        group.check(ids.getValue(header))
        group.setOnCheckedChangeListener { _, checked ->
            header = ids.entries.firstOrNull { it.value == checked }?.key ?: TableHeader.NONE
            refresh(fromFields = true)
        }
        root.addView(group)

        // 크기
        fun sizeField(hint: String, initial: Float, onChange: (Float) -> Unit): Pair<LinearLayout, EditText> {
            val box = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            box.addView(TextView(activity).apply { text = hint; textSize = 14f })
            val edit = EditText(activity).apply {
                inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
                setText(initial.roundToInt().toString())
                gravity = Gravity.CENTER
                minWidth = (56 * d).toInt()
                setSelectAllOnFocus(true)
                addTextChangedListener(object : android.text.TextWatcher {
                    override fun afterTextChanged(s: android.text.Editable?) {
                        val v = s?.toString()?.toFloatOrNull() ?: return
                        if (v >= 10f) onChange(v.coerceAtMost(2000f))
                    }
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                })
            }
            box.addView(edit)
            box.addView(TextView(activity).apply { text = "mm"; textSize = 14f })
            return box to edit
        }
        val sizeRow = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, (10 * d).toInt(), 0, 0)
        }
        val wf = sizeField("너비 ", widthMm) { widthMm = it; preview.table = table() }
        val hf = sizeField("높이 ", heightMm) {
            if (!programmatic) { heightMm = it; heightEdited = true }
            preview.table = table()
        }
        widthEdit = wf.second
        heightEdit = hf.second
        sizeRow.addView(wf.first)
        sizeRow.addView(hf.first)
        root.addView(sizeRow)
        root.addView(preview, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (110 * d).toInt())
            .apply { topMargin = (10 * d).toInt() })
        refresh()

        val scroll = android.widget.ScrollView(activity).apply { addView(root) }
        MaterialAlertDialogBuilder(activity)
            .setTitle("표 만들기")
            .setView(scroll)
            .setNegativeButton("취소", null)
            .setPositiveButton("만들기") { _, _ ->
                prefs.edit().putInt("tableRows", rows).putInt("tableCols", cols).putString("tableHeader", header.code.toString()).apply()
                onCreate(table())
            }
            .show()
    }

    /** 칸을 끌어 줄·칸 수를 고르는 격자 (한글의 표 넣기 격자처럼, 왼쪽 위부터 고른 곳까지 칠한다) */
    private class GridPicker(ctx: Context) : View(ctx) {
        private val maxRows = 8
        private val maxCols = 10
        private val d = resources.displayMetrics.density
        private val cell = 26f * d
        private val gap = 3f * d
        private var rows = 1
        private var cols = 1
        var onPick: ((Int, Int) -> Unit)? = null
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = MaterialColors.getColor(ctx, androidx.appcompat.R.attr.colorPrimary, 0xFF1E6FD9.toInt())
        }
        private val edgeOff = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.2f * d
            color = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOutline, 0xFF888888.toInt())
        }
        private val edgeOn = Paint(edgeOff).apply { color = fill.color }
        private val rect = RectF()

        fun set(r: Int, c: Int) {
            rows = r
            cols = c
            invalidate()
        }

        override fun onMeasure(w: Int, h: Int) {
            setMeasuredDimension(((cell + gap) * maxCols).toInt(), ((cell + gap) * maxRows).toInt())
        }

        override fun onDraw(canvas: Canvas) {
            for (r in 0 until maxRows) for (c in 0 until maxCols) {
                rect.set(c * (cell + gap), r * (cell + gap), c * (cell + gap) + cell, r * (cell + gap) + cell)
                val on = r < rows && c < cols
                if (on) {
                    fill.alpha = 90
                    canvas.drawRoundRect(rect, 3 * d, 3 * d, fill)
                }
                canvas.drawRoundRect(rect, 3 * d, 3 * d, if (on) edgeOn else edgeOff)
            }
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            if (e.actionMasked == MotionEvent.ACTION_DOWN) parent?.requestDisallowInterceptTouchEvent(true)
            if (e.actionMasked == MotionEvent.ACTION_DOWN || e.actionMasked == MotionEvent.ACTION_MOVE) {
                val c = ((e.x / (cell + gap)).toInt() + 1).coerceIn(1, maxCols)
                val r = ((e.y / (cell + gap)).toInt() + 1).coerceIn(1, maxRows)
                if (r != rows || c != cols) onPick?.invoke(r, c)
            }
            return true
        }
    }

    /** 만들 표 미리 보기 (제목 칸 색과 굵은 선까지 실제와 같게) */
    private class PreviewView(ctx: Context) : View(ctx) {
        private val d = resources.displayMetrics.density
        private val ink = MaterialColors.getColor(ctx, com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.SQUARE }
        var table: InkTable? = null
            set(v) { field = v; invalidate() }

        override fun onDraw(canvas: Canvas) {
            val t = table ?: return
            val pad = 6 * d
            val k = minOf((width - pad * 2) / t.width, (height - pad * 2) / t.height)
            val ox = (width - t.width * k) / 2f
            val oy = (height - t.height * k) / 2f
            val geo = t.geometry()
            paint.style = Paint.Style.FILL
            paint.color = (ink and 0x00FFFFFF) or ((255 * TABLE_SHADE_ALPHA).roundToInt() shl 24)
            for (s in geo.shades) canvas.drawRect(ox + s.l * k, oy + s.t * k, ox + s.r * k, oy + s.b * k, paint)
            paint.style = Paint.Style.STROKE
            paint.color = ink
            val w = 1.4f * d
            for (heavy in booleanArrayOf(false, true)) {
                paint.strokeWidth = if (heavy) w * TABLE_HEAVY else w
                for (l in geo.lines) if (l.heavy == heavy) canvas.drawLine(ox + l.x0 * k, oy + l.y0 * k, ox + l.x1 * k, oy + l.y1 * k, paint)
            }
            paint.strokeWidth = w
            canvas.drawRect(ox, oy, ox + t.width * k, oy + t.height * k, paint)
        }
    }
}
