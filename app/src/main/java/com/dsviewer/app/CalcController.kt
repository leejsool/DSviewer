package com.dsviewer.app

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.text.style.RelativeSizeSpan
import android.text.style.SubscriptSpan
import android.text.style.SuperscriptSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.view.doOnLayout
import androidx.core.widget.TextViewCompat
import com.google.android.material.color.MaterialColors
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 공학용 계산기. ⋮ 메뉴의 '계산기'로 부르면 문서 위에 작은 계산기 창이 뜬다.
 *
 * - 머리줄을 끌어 옮기고, ─ 로 접으면 동그란 플로팅 단추가 된다 (단추도 끌어 옮기고, 누르면 다시 펼친다).
 * - × 로 끄면 사라진다. 단추를 길게 눌러도 끌 수 있다.
 * - 켜 둔 상태(창·단추)와 놓은 자리는 기억해서 뷰어를 다시 열어도 그대로다. 탭을 바꿔도 계산기는 그대로다.
 *
 * 계산은 [CalcEngine]이 맡고, 여기는 키패드·표시·끌기만 한다.
 */
internal class CalcController(
    private val activity: AppCompatActivity,
    private val frame: FrameLayout,
    private val prefs: SharedPreferences,
    private val toast: (String) -> Unit,
) {
    private enum class Mode { OFF, FAB, PANEL }

    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()
    private val handler = Handler(Looper.getMainLooper())
    private val engine = CalcEngine()

    private var mode = Mode.OFF
    private var secondOn = false
    private var historyOpen = false
    private var compact = false

    private var panel: LinearLayout? = null
    private var fab: ImageView? = null

    private val panelSpot = Spot("calcPanel", 1f, 1f)
    private val fabSpot = Spot("calcFab", 1f, 1f)

    // 화면에 있는 부품 (한 번 만들어 두고 보였다 숨겼다 한다)
    private lateinit var header: LinearLayout
    private lateinit var exprView: TextView
    private lateinit var resultView: TextView
    private lateinit var displayBox: LinearLayout
    private lateinit var degButton: TextView
    private lateinit var sciButton: TextView
    private lateinit var trigButton: TextView
    private lateinit var funcButton: TextView
    private lateinit var memoryRow: LinearLayout
    private lateinit var optionRow: LinearLayout
    private lateinit var historyButton: ImageButton
    private lateinit var keypad: LinearLayout
    private lateinit var historyPane: LinearLayout
    private lateinit var historyList: LinearLayout
    private lateinit var memClear: TextView
    private lateinit var memRecall: TextView
    private lateinit var memList: TextView
    private lateinit var secondKey: Key
    private val keys = ArrayList<Key>()

    private val cSurface get() = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurface, Color.WHITE)
    private val cOnSurface get() = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
    private val cOnVariant get() = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
    private val cOutline get() = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)
    private val cDigit get() = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurfaceContainerLowest, Color.WHITE)
    private val cFunc get() = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurfaceContainerHigh, Color.LTGRAY)
    private val cOp get() = MaterialColors.getColor(activity, com.google.android.material.R.attr.colorPrimaryContainer, Color.LTGRAY)
    private val cPrimary get() = activity.getColor(R.color.brand)

    init {
        // 칸 크기가 바뀌면(회전·분할·창 크기) 계산기가 칸 밖으로 나가지 않게 다시 자리 잡는다
        frame.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or2, ob ->
            if (mode != Mode.OFF && (r - l != or2 - ol || b - t != ob - ot)) relayout()
        }
    }

    // ================= 생명주기 =================

    /** 뷰어를 열 때: 지난번에 켜 둔 상태를 되살린다 */
    fun restore() {
        when (prefs.getInt(KEY_MODE, 0)) {
            1 -> frame.doOnLayout { showFab() }
            2 -> frame.doOnLayout { showPanel() }
        }
    }

    fun onPause() = save()

    /** ⋮ 메뉴 '계산기': 꺼져 있거나 접혀 있으면 펼치고, 펼쳐져 있으면 끈다 */
    fun toggle() {
        if (mode == Mode.PANEL) close() else frame.doOnLayout { showPanel() }
    }

    private fun save() {
        prefs.edit().apply {
            putInt(KEY_MODE, mode.ordinal)
            panelSpot.write(this)
            fabSpot.write(this)
        }.apply()
    }

    // ================= 보이기·접기·끄기 =================

    private fun showPanel() {
        val p = panel ?: buildPanel().also { panel = it }
        fab?.visibility = View.GONE
        mode = Mode.PANEL
        p.visibility = View.VISIBLE
        relayout()
        p.bringToFront()
        refresh()
        save()
    }

    private fun showFab() {
        val f = fab ?: buildFab().also { fab = it }
        panel?.visibility = View.GONE
        mode = Mode.FAB
        f.visibility = View.VISIBLE
        relayout()
        f.bringToFront()
        save()
    }

    private fun close() {
        mode = Mode.OFF
        panel?.visibility = View.GONE
        fab?.visibility = View.GONE
        save()
    }

    // ================= 자리·크기 =================

    /** 칸 크기에 맞춰 크기를 정하고 기억해 둔 자리에 놓는다 */
    private fun relayout() {
        if (frame.width <= 0 || frame.height <= 0) return
        panel?.let { p ->
            val m = dp(8)
            val w = min(frame.width - 2 * m, dp(330)).coerceAtLeast(dp(200))
            val h = min(frame.height - 2 * m, dp(560)).coerceAtLeast(dp(260))
            compact = h < dp(470)
            applySizes(p, w, h)
            if (mode == Mode.PANEL) panelSpot.place(p)
        }
        fab?.let { if (mode == Mode.FAB) fabSpot.place(it) }
    }

    /** 창 크기와 머리줄·표시·줄 높이 (칸이 낮으면 촘촘하게) */
    private fun applySizes(p: LinearLayout, w: Int, h: Int) {
        val lp = p.layoutParams as FrameLayout.LayoutParams
        if (lp.width != w || lp.height != h) {
            lp.width = w
            lp.height = h
            p.layoutParams = lp
        }
        header.layoutParams.height = dp(if (compact) 34 else 40)
        displayBox.layoutParams.height = dp(if (compact) 66 else 88)
        optionRow.layoutParams.height = dp(if (compact) 28 else 32)
        memoryRow.layoutParams.height = dp(if (compact) 28 else 32)
        for (k in keys) k.setSizes(compact)
        header.requestLayout()
        displayBox.requestLayout()
    }

    /** 놓은 자리를 칸 크기에 대한 비율로 기억해서, 칸이 커지고 작아져도 비슷한 자리에 놓인다 */
    private inner class Spot(private val key: String, var fx: Float, var fy: Float) {
        private val margin get() = dp(8)

        init {
            fx = prefs.getFloat("${key}X", fx)
            fy = prefs.getFloat("${key}Y", fy)
        }

        private fun range(v: View): Pair<Int, Int> {
            val lp = v.layoutParams
            return max(0, frame.width - lp.width - 2 * margin) to max(0, frame.height - lp.height - 2 * margin)
        }

        fun place(v: View) {
            val (rx, ry) = range(v)
            v.translationX = margin + fx.coerceIn(0f, 1f) * rx
            v.translationY = margin + fy.coerceIn(0f, 1f) * ry
        }

        /** 지금 놓인 자리를 비율로 적어 둔다 */
        fun capture(v: View) {
            val (rx, ry) = range(v)
            fx = if (rx == 0) 1f else ((v.translationX - margin) / rx).coerceIn(0f, 1f)
            fy = if (ry == 0) 1f else ((v.translationY - margin) / ry).coerceIn(0f, 1f)
        }

        fun write(e: SharedPreferences.Editor) {
            e.putFloat("${key}X", fx)
            e.putFloat("${key}Y", fy)
        }
    }

    // ================= 끌기 =================

    /**
     * [handle]을 끌면 [target]이 따라 움직인다. 끌지 않고 떼면 [onTap], 오래 누르면 [onLong].
     * 칸 밖으로는 못 나간다.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun makeDraggable(
        handle: View,
        target: View,
        spot: Spot,
        onTap: (() -> Unit)? = null,
        onLong: (() -> Unit)? = null,
    ) {
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startTx = 0f
        var startTy = 0f
        var moved = false
        var longDone = false
        val longRun = Runnable {
            if (!moved) {
                longDone = true
                onLong?.invoke()
            }
        }
        handle.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    startTx = target.translationX
                    startTy = target.translationY
                    moved = false
                    longDone = false
                    if (onLong != null) handler.postDelayed(longRun, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (!moved && hypot(e.rawX - downX, e.rawY - downY) > slop) {
                        moved = true
                        handler.removeCallbacks(longRun)
                    }
                    if (moved) {
                        val m = dp(8)
                        val lp = target.layoutParams
                        target.translationX = (startTx + e.rawX - downX).coerceIn(m.toFloat(), max(m, frame.width - lp.width - m).toFloat())
                        target.translationY = (startTy + e.rawY - downY).coerceIn(m.toFloat(), max(m, frame.height - lp.height - m).toFloat())
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    handler.removeCallbacks(longRun)
                    if (moved) {
                        spot.capture(target)
                        save()
                    } else if (!longDone) onTap?.invoke()
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    handler.removeCallbacks(longRun)
                    if (moved) {
                        spot.capture(target)
                        save()
                    }
                    true
                }
                else -> false
            }
        }
    }

    // ================= 플로팅 단추 =================

    private fun buildFab(): ImageView {
        val size = dp(54)
        val f = ImageView(activity).apply {
            setImageResource(R.drawable.ic_calc)
            imageTintList = ColorStateList.valueOf(Color.WHITE)
            contentDescription = "계산기 (누르면 펼침, 길게 누르면 끄기)"
            scaleType = ImageView.ScaleType.FIT_CENTER
            setPadding(dp(13), dp(13), dp(13), dp(13))
            elevation = dp(8).toFloat()
            background = RippleDrawable(
                ColorStateList.valueOf(0x44FFFFFF),
                GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cPrimary) },
                null,
            )
            visibility = View.GONE
        }
        frame.addView(f, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.START))
        makeDraggable(f, f, fabSpot, onTap = { showPanel() }, onLong = { askTurnOff(f) })
        return f
    }

    private fun askTurnOff(anchor: View) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add("계산기 끄기")
        popup.setOnMenuItemClickListener { close(); true }
        popup.show()
    }

    // ================= 계산기 창 =================

    private fun buildPanel(): LinearLayout {
        val root = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            elevation = dp(12).toFloat()
            isClickable = true  // 창 위의 손길이 아래 문서로 새지 않게
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(cSurface)
                setStroke(dp(1), cOutline)
            }
            outlineProvider = ViewOutlineProvider.BACKGROUND
            clipToOutline = true
        }
        frame.addView(root, FrameLayout.LayoutParams(dp(330), dp(520), Gravity.TOP or Gravity.START))

        buildHeader(root)
        buildDisplay(root)
        buildOptionRow(root)
        buildMemoryRow(root)

        val body = FrameLayout(activity)
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        keypad = buildKeypad()
        body.addView(keypad, FrameLayout.LayoutParams(-1, -1))
        buildHistory(body)
        return root
    }

    private fun buildHeader(root: LinearLayout) {
        header = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(MaterialColors.getColor(activity, com.google.android.material.R.attr.colorSurfaceContainer, Color.LTGRAY))
            setPadding(dp(10), 0, dp(2), 0)
        }
        val icon = ImageView(activity).apply {
            setImageResource(R.drawable.ic_calc)
            imageTintList = ColorStateList.valueOf(cOnVariant)
        }
        header.addView(icon, LinearLayout.LayoutParams(dp(20), dp(20)))
        val title = TextView(activity).apply {
            text = "공학용 계산기"
            setTextColor(cOnSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            setPadding(dp(8), 0, 0, 0)
        }
        header.addView(title, LinearLayout.LayoutParams(0, -1, 1f))
        title.gravity = Gravity.CENTER_VERTICAL
        historyButton = headerButton(R.drawable.ic_history, "기록") { toggleHistory() }
        header.addView(historyButton)
        header.addView(headerButton(R.drawable.ic_minimize, "접기 (플로팅 단추로)") { showFab() })
        header.addView(headerButton(R.drawable.ic_close, "끄기") { close() })
        root.addView(header, LinearLayout.LayoutParams(-1, dp(40)))
        // 머리줄의 빈 곳을 끌면 창이 따라온다 (단추는 자기 눌림을 먼저 받는다)
        makeDraggable(title, root, panelSpot)
        makeDraggable(icon, root, panelSpot)
    }

    private fun headerButton(icon: Int, desc: String, onClick: () -> Unit) = ImageButton(activity).apply {
        setImageResource(icon)
        contentDescription = desc
        imageTintList = ColorStateList.valueOf(cOnVariant)
        val out = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, out, true)
        setBackgroundResource(out.resourceId)
        setPadding(dp(8), dp(8), dp(8), dp(8))
        layoutParams = LinearLayout.LayoutParams(dp(38), -1)
        setOnClickListener { onClick() }
    }

    private fun buildDisplay(root: LinearLayout) {
        displayBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(4), dp(14), dp(2))
        }
        exprView = TextView(activity).apply {
            setTextColor(cOnVariant)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            fontFeatureSettings = "tnum"
        }
        resultView = TextView(activity).apply {
            setTextColor(cOnSurface)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 38f)
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
            fontFeatureSettings = "tnum"
            TextViewCompat.setAutoSizeTextTypeUniformWithConfiguration(this, 16, 44, 2, TypedValue.COMPLEX_UNIT_SP)
            setOnLongClickListener { copyValue(); true }
        }
        displayBox.addView(exprView, LinearLayout.LayoutParams(-1, dp(20)))
        displayBox.addView(resultView, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(displayBox, LinearLayout.LayoutParams(-1, dp(88)))
    }

    /** DEG · F-E · 삼각법 · 함수 */
    private fun buildOptionRow(root: LinearLayout) {
        optionRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        degButton = textButton("DEG") { engine.angle = engine.angle.next(); refresh() }
        sciButton = textButton("F-E") { engine.sciNotation = !engine.sciNotation; refresh() }
        trigButton = textButton("삼각법 ▾") { showTrigMenu(it) }
        funcButton = textButton("함수 ▾") { showFuncMenu(it) }
        for (b in listOf(degButton, sciButton, trigButton, funcButton)) optionRow.addView(b, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(optionRow, LinearLayout.LayoutParams(-1, dp(32)))
    }

    /** MC · MR · M+ · M− · MS · M▾ */
    private fun buildMemoryRow(root: LinearLayout) {
        memoryRow = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        memClear = textButton("MC") { engine.memoryClear(); refresh() }
        memRecall = textButton("MR") { engine.memoryRecall(); refresh() }
        memList = textButton("M▾") { showMemoryMenu(it) }
        val list = listOf(
            memClear, memRecall,
            textButton("M+") { engine.memoryAdd(1); refresh() },
            textButton("M−") { engine.memoryAdd(-1); refresh() },
            textButton("MS") { engine.memoryStore(); refresh() },
            memList,
        )
        for (b in list) memoryRow.addView(b, LinearLayout.LayoutParams(0, -1, 1f))
        root.addView(memoryRow, LinearLayout.LayoutParams(-1, dp(32)))
    }

    private fun textButton(text: String, onClick: (View) -> Unit) = TextView(activity).apply {
        this.text = text
        gravity = Gravity.CENTER
        setTextColor(cOnSurface)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        val out = TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
        setBackgroundResource(out.resourceId)
        setOnClickListener { onClick(it) }
    }

    // ================= 키패드 =================

    private enum class Kind { DIGIT, FUNC, OP, EQUALS, SECOND }

    /** 키 하나: 누르는 칸. [second]가 있으면 '2nd'를 켰을 때 글자와 동작이 바뀐다 */
    private inner class Key(
        val kind: Kind,
        private val label: String,
        private val secondLabel: String?,
        private val action: () -> Unit,
        private val secondAction: (() -> Unit)?,
        icon: Int = 0,
    ) : FrameLayout(activity) {
        private val text = TextView(activity)
        private val shape = GradientDrawable()

        init {
            val fill = when (kind) {
                Kind.DIGIT -> cDigit
                Kind.FUNC, Kind.SECOND -> cFunc
                Kind.OP -> cOp
                Kind.EQUALS -> cPrimary
            }
            shape.cornerRadius = dp(8).toFloat()
            shape.setColor(fill)
            shape.setStroke(dp(1), cOutline)
            background = RippleDrawable(ColorStateList.valueOf(0x33000000), shape, null)
            isClickable = true
            if (icon != 0) {
                val img = ImageView(activity).apply {
                    setImageResource(icon)
                    imageTintList = ColorStateList.valueOf(cOnSurface)
                    setPadding(dp(14), dp(10), dp(14), dp(10))
                }
                addView(img, LayoutParams(-1, -1))
                contentDescription = "지우기"
            } else {
                text.gravity = Gravity.CENTER
                text.setTextColor(if (kind == Kind.EQUALS) Color.WHITE else cOnSurface)
                text.typeface = if (kind == Kind.DIGIT || kind == Kind.EQUALS) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
                addView(text, LayoutParams(-1, -1))
                setLabel(false)
            }
            setSizes(false)
            setOnClickListener {
                if (secondOn && secondAction != null) secondAction.invoke() else action()
                refresh()
            }
        }

        fun setLabel(second: Boolean) {
            val s = if (second && secondLabel != null) secondLabel else label
            text.text = rich(s)
        }

        fun setSizes(compact: Boolean) {
            val sp = when (kind) {
                Kind.DIGIT, Kind.EQUALS, Kind.OP -> if (compact) 16f else 20f
                else -> if (compact) 12f else 15f
            }
            text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        }

        /** '2nd' 키가 켜졌을 때 눌린 모양 */
        fun setOn(on: Boolean) {
            shape.setColor(if (on) cOp else cFunc)
            shape.setStroke(dp(if (on) 2 else 1), if (on) cPrimary else cOutline)
        }
    }

    private fun key(kind: Kind, label: String, action: () -> Unit, second: String? = null, secondAction: (() -> Unit)? = null, icon: Int = 0) =
        Key(kind, label, second, action, secondAction, icon).also { keys.add(it) }

    private fun digit(d: Int) = key(Kind.DIGIT, d.toString(), { engine.digit(d) })
    private fun op(symbol: String, o: CalcOp) = key(Kind.OP, symbol, { engine.binary(o) })
    private fun fn(label: String, f: CalcFn, second: String? = null, secondFn: CalcFn? = null) =
        key(Kind.FUNC, label, { engine.unary(f) }, second, secondFn?.let { s -> { engine.unary(s) } })

    private fun buildKeypad(): LinearLayout {
        secondKey = key(Kind.SECOND, "2^{nd}", { toggleSecond() })
        val rows = listOf(
            listOf(
                secondKey,
                key(Kind.FUNC, "π", { engine.constant(Math.PI, "π") }),
                key(Kind.FUNC, "e", { engine.constant(Math.E, "e") }),
                key(Kind.FUNC, "C", { engine.clear() }),
                key(Kind.FUNC, "", { engine.backspace() }, icon = R.drawable.ic_backspace),
            ),
            listOf(
                fn("x^{2}", CalcFn.SQR, "x^{3}", CalcFn.CUBE),
                fn("1/x", CalcFn.RECIP),
                fn("|x|", CalcFn.ABS),
                key(Kind.FUNC, "exp", { engine.exponent() }),
                key(Kind.FUNC, "mod", { engine.binary(CalcOp.MOD) }),
            ),
            listOf(
                fn("^{2}√x", CalcFn.SQRT, "^{3}√x", CalcFn.CBRT),
                key(Kind.FUNC, "(", { engine.openParen() }),
                key(Kind.FUNC, ")", { engine.closeParen() }),
                fn("n!", CalcFn.FACT),
                op("÷", CalcOp.DIV),
            ),
            listOf(
                key(Kind.FUNC, "x^{y}", { engine.binary(CalcOp.POW) }, "^{y}√x", { engine.binary(CalcOp.YROOT) }),
                digit(7), digit(8), digit(9),
                op("×", CalcOp.MUL),
            ),
            listOf(
                fn("10^{x}", CalcFn.POW10, "2^{x}", CalcFn.POW2),
                digit(4), digit(5), digit(6),
                op("−", CalcOp.SUB),
            ),
            listOf(
                key(Kind.FUNC, "log", { engine.unary(CalcFn.LOG) }, "log_{y}x", { engine.binary(CalcOp.LOGY) }),
                digit(1), digit(2), digit(3),
                op("+", CalcOp.ADD),
            ),
            listOf(
                fn("ln", CalcFn.LN, "e^{x}", CalcFn.EXP),
                key(Kind.DIGIT, "±", { engine.negate() }),
                digit(0),
                key(Kind.DIGIT, ".", { engine.dot() }),
                key(Kind.EQUALS, "=", { engine.equals() }),
            ),
        )
        val grid = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(2), dp(4), dp(4))
        }
        for (r in rows) {
            val line = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
            for (k in r) line.addView(k, LinearLayout.LayoutParams(0, -1, 1f).apply { setMargins(dp(2), dp(2), dp(2), dp(2)) })
            grid.addView(line, LinearLayout.LayoutParams(-1, 0, 1f))
        }
        return grid
    }

    private fun toggleSecond() {
        secondOn = !secondOn
        for (k in keys) k.setLabel(secondOn)
        secondKey.setOn(secondOn)
    }

    /** "x^{2}" 처럼 적은 글을 위 첨자(^{ })·아래 첨자(_{ })로 꾸민다 */
    private fun rich(s: String): CharSequence {
        val sb = SpannableStringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            val end = if ((c == '^' || c == '_') && s.getOrNull(i + 1) == '{') s.indexOf('}', i) else -1
            if (end > 0) {
                val start = sb.length
                sb.append(s, i + 2, end)
                sb.setSpan(if (c == '^') SuperscriptSpan() else SubscriptSpan(), start, sb.length, 0)
                sb.setSpan(RelativeSizeSpan(0.72f), start, sb.length, 0)
                i = end + 1
            } else {
                sb.append(c)
                i++
            }
        }
        return sb
    }

    // ================= 메뉴 =================

    private fun showTrigMenu(anchor: View) {
        val items = listOf(
            CalcFn.SIN, CalcFn.COS, CalcFn.TAN, CalcFn.SEC, CalcFn.CSC, CalcFn.COT,
            CalcFn.ASIN, CalcFn.ACOS, CalcFn.ATAN, CalcFn.SINH, CalcFn.COSH, CalcFn.TANH,
        )
        val popup = PopupMenu(activity, anchor)
        for ((i, f) in items.withIndex()) popup.menu.add(0, i, i, f.label)
        popup.setOnMenuItemClickListener { engine.unary(items[it.itemId]); refresh(); true }
        popup.show()
    }

    private fun showFuncMenu(anchor: View) {
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, 0, 0, "|x|  절댓값")
        popup.menu.add(0, 1, 1, "⌊x⌋  내림")
        popup.menu.add(0, 2, 2, "⌈x⌉  올림")
        popup.menu.add(0, 3, 3, "rand  무작위 수 (0~1)")
        popup.menu.add(0, 4, 4, "dms  도 → 도.분초")
        popup.menu.add(0, 5, 5, "deg  도.분초 → 도")
        popup.setOnMenuItemClickListener {
            when (it.itemId) {
                0 -> engine.unary(CalcFn.ABS)
                1 -> engine.unary(CalcFn.FLOOR)
                2 -> engine.unary(CalcFn.CEIL)
                3 -> engine.random()
                4 -> engine.unary(CalcFn.DMS)
                5 -> engine.unary(CalcFn.DEGREES)
            }
            refresh()
            true
        }
        popup.show()
    }

    private fun showMemoryMenu(anchor: View) {
        if (engine.memory.isEmpty()) return
        val popup = PopupMenu(activity, anchor)
        for ((i, v) in engine.memory.withIndex()) popup.menu.add(0, i, i, CalcEngine.group(CalcEngine.format(v, false)))
        popup.setOnMenuItemClickListener { engine.memoryRecall(it.itemId); refresh(); true }
        popup.show()
    }

    // ================= 기록 =================

    private fun buildHistory(body: FrameLayout) {
        historyPane = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(cSurface)
            visibility = View.GONE
            isClickable = true
            setPadding(dp(8), dp(4), dp(8), dp(6))
        }
        historyList = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(activity).apply { addView(historyList) }
        historyPane.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        historyPane.addView(
            textButton("기록 지우기") { engine.history.clear(); fillHistory() },
            LinearLayout.LayoutParams(-1, dp(40)),
        )
        body.addView(historyPane, FrameLayout.LayoutParams(-1, -1))
    }

    private fun toggleHistory() {
        historyOpen = !historyOpen
        keypad.visibility = if (historyOpen) View.INVISIBLE else View.VISIBLE
        historyPane.visibility = if (historyOpen) View.VISIBLE else View.GONE
        historyButton.imageTintList = ColorStateList.valueOf(if (historyOpen) cPrimary else cOnVariant)
        if (historyOpen) fillHistory()
    }

    private fun fillHistory() {
        historyList.removeAllViews()
        if (engine.history.isEmpty()) {
            historyList.addView(TextView(activity).apply {
                text = "아직 계산 기록이 없어요"
                setTextColor(cOnVariant)
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, 0)
            })
            return
        }
        for (h in engine.history) {
            val item = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.END
                setPadding(dp(8), dp(6), dp(8), dp(6))
                val out = TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                setBackgroundResource(out.resourceId)
                setOnClickListener {
                    engine.load(h.result)
                    toggleHistory()
                    refresh()
                }
            }
            item.addView(TextView(activity).apply {
                text = h.expression
                setTextColor(cOnVariant)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                gravity = Gravity.END
            })
            item.addView(TextView(activity).apply {
                text = CalcEngine.group(CalcEngine.format(h.result, engine.sciNotation))
                setTextColor(cOnSurface)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 22f)
                gravity = Gravity.END
            })
            historyList.addView(item, LinearLayout.LayoutParams(-1, -2))
        }
    }

    // ================= 갱신 =================

    private fun refresh() {
        if (panel == null) return
        exprView.text = engine.expression
        resultView.text = engine.display
        degButton.text = engine.angle.label
        sciButton.setTypeface(null, if (engine.sciNotation) Typeface.BOLD else Typeface.NORMAL)
        sciButton.setTextColor(if (engine.sciNotation) cPrimary else cOnSurface)
        val hasMem = engine.memory.isNotEmpty()
        for (b in listOf(memClear, memRecall, memList)) {
            b.isEnabled = hasMem
            b.alpha = if (hasMem) 1f else 0.4f
        }
    }

    private fun copyValue() {
        val v = engine.currentValue()
        if (v == null) {
            toast("복사할 값이 없어요")
            return
        }
        val text = CalcEngine.format(v, false)
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("계산 결과", text))
        toast("결과를 복사했어요: $text")
    }

    companion object {
        private const val KEY_MODE = "calcMode"
    }
}
