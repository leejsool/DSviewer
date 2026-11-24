package com.dsviewer.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.RenderEffect
import android.graphics.RenderNode
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 플로팅 툴바: 상단·하단 툴바까지 손이 닿지 않을 때 문서 위 아무 곳에나 띄워 두는 작은 유리 툴바.
 *
 * - 처음 단추는 실행 취소 · 펜 · 형광펜 · 직전 도구(실행 취소·펜·형광펜이 아닌, 가장 최근에 쓴 도구) 2×2.
 *   앞의 셋도 옵션에서 끌 수 있어서 2×2보다 작아질 수 있다 (1칸, 1×2, 2칸+1).
 * - 위쪽 손잡이: 끌면 옮기고, 톡 누르면 플로팅 툴바 옵션(단추 더하기 · 투명도). 오른쪽 꺾쇠로 접고 편다.
 * - 단추는 상단·하단 툴바의 진짜 단추를 그대로 부른다: 아이콘을 비춰 그리고([MirrorDrawable]) 누르면 같은 일을 한다.
 *   그래서 펜 색·지우개 모양·켜짐 표시가 원래 단추와 늘 같다.
 * - 크기는 사용자가 정하지 않는다: 단추 수에 따라 칸 수가 정해진다 ([FloatBarMath.columns]).
 * - 바탕은 뒤의 문서를 흐리게 다시 그린 위에 옅은 흰색과 광택을 얹은 유리 ([GlassPanel]). 문서와 같은 화면에서 그려 뒤처지지 않는다.
 * - 처음부터 켜져 있다 (1.2.2부터. 업데이트 뒤 한 번은 꺼 두었어도 켠다: floatBarDefaultOn).
 *
 * prefs: floatBarOn · floatBarDefaultOn · floatBarFolded · floatBarX/Y(화면 크기에 대한 비율) · floatBarExtra · floatBarHidden(끈 기본 단추) · floatBarHistory · floatBarTransparency
 */
internal class FloatingToolbar(
    private val activity: AppCompatActivity,
    private val prefs: SharedPreferences,
    private val frame: FrameLayout,
    private val backdropSource: () -> View,
    private val items: List<Item>,
    private val iconScale: () -> Float,
    private val currentToolKey: () -> String,
) {
    /** 플로팅 툴바에 올릴 수 있는 단추. [source]는 아이콘과 켜짐 상태를 빌려 올 원래 단추 */
    class Item(val key: String, val label: String, val group: Group, val source: () -> View?, val onClick: (anchor: View) -> Unit)

    enum class Group { TOOL, TOP }

    private val d = activity.resources.displayMetrics.density
    private fun dpx(v: Float): Int = (v * d).roundToInt()
    private val byKey = items.associateBy { it.key }

    private val panel = GlassPanel(activity)
    private val column = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val handle = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumWidth = dpx(2 * (FloatBarMath.BUTTON_DP + 4f))
    }
    private val grip = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        contentDescription = "플로팅 툴바 손잡이 (끌어서 옮기기, 누르면 옵션)"
        tooltipText = "끌면 옮기고, 누르면 단추 더하기·투명도"
    }
    private val gripIcon = ImageView(activity).apply {
        setImageDrawable(MirrorDrawable({ sourceIcon(currentToolKey()) }, dpx(20f)))
    }
    private val gripDots = GripDots(activity)
    private val gripPlus = ImageView(activity).apply { setImageResource(R.drawable.ic_add) }
    private val foldButton = ImageView(activity).apply {
        setImageResource(R.drawable.ic_chevron_right)
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        setPadding(dpx(4f), dpx(4f), dpx(4f), dpx(4f))
        background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.argb(90, 255, 255, 255)) }
    }
    private val grid = GridLayout(activity)
    private var buttons = listOf<FloatButton>()
    private var gripSig = 0

    private val valid = items.map { it.key }.toSet()
    private var history = FloatBarMath.parseKeys(prefs.getString("floatBarHistory", null), FloatBarMath.RECENT_CANDIDATES)
    private var extra = FloatBarMath.parseKeys(prefs.getString("floatBarExtra", null), valid - FloatBarMath.DEFAULTS.toSet())
    // 기본 세 칸(실행 취소 · 펜 · 형광펜) 가운데 사용자가 옵션에서 끈 것
    private var hidden = FloatBarMath.parseKeys(prefs.getString("floatBarHidden", null), FloatBarMath.DEFAULTS)
    private var folded = prefs.getBoolean("floatBarFolded", false)
    private var transparency = prefs.getInt("floatBarTransparency", FloatBarMath.DEFAULT_TRANSPARENCY)
    // 처음 자리: 왼쪽 아래 (키가 작은 사람이 닿기 쉬운 곳)
    private var fx = prefs.getFloat("floatBarX", 0.03f)
    private var fy = prefs.getFloat("floatBarY", 0.62f)
    private var placed = false

    private var popup: PopupWindow? = null

    val isShown get() = prefs.getBoolean("floatBarOn", true)

    fun setup() {
        val iconColor = MaterialColors.getColor(frame, com.google.android.material.R.attr.colorOnSurfaceVariant)
        gripPlus.imageTintList = ColorStateList.valueOf(iconColor)
        foldButton.imageTintList = ColorStateList.valueOf(iconColor)
        gripDots.color = iconColor
        grip.addView(gripIcon, LinearLayout.LayoutParams(dpx(20f), dpx(20f)).apply { marginEnd = dpx(6f) })
        grip.addView(gripDots, LinearLayout.LayoutParams(dpx(22f), dpx(10f)))
        grip.addView(gripPlus, LinearLayout.LayoutParams(dpx(14f), dpx(14f)).apply { marginStart = dpx(4f) })
        handle.addView(grip, LinearLayout.LayoutParams(0, dpx(26f), 1f))
        handle.addView(foldButton, LinearLayout.LayoutParams(dpx(26f), dpx(26f)).apply { marginStart = dpx(4f) })
        column.addView(handle, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dpx(26f)))
        // 단추가 한두 개면 손잡이보다 좁다: 가운데에
        column.addView(grid, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dpx(4f)
            gravity = Gravity.CENTER_HORIZONTAL
        })
        panel.addView(column, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        panel.tint = FloatBarMath.tintAlpha(transparency)
        panel.backdropSource = backdropSource
        panel.backdropColor = ContextCompat.getColor(activity, R.color.doc_bg)
        // 문서·선택 막대 위에 뜨도록 높이를 주되, 그림자는 유리 모양대로 직접 그린다 (시스템 그림자는 반투명 바탕 안으로 비친다)
        panel.elevation = 8 * d
        panel.outlineProvider = null
        frame.addView(panel, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.START))

        setupGrip()
        foldButton.setOnClickListener { setFolded(!folded) }
        frame.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (r - l != or - ol || b - t != ob - ot) placeFromFraction()
        }
        panel.addOnLayoutChangeListener { _, l, t, r, b, ol, ot, or, ob ->
            if (!placed) placeFromFraction()
            else if (r - l != or - ol || b - t != ob - ot) {
                // 접거나 단추를 더해 크기가 바뀌면 왼쪽 위를 그대로 두고 화면 안으로만 맞춘다
                moveTo(panel.x, panel.y)
                popup?.let { positionPopup(it) }
            }
        }
        // 원래 단추의 아이콘·켜짐이 바뀌면 (그림이 바뀐 단추만) 다시 그린다
        frame.viewTreeObserver.addOnPreDrawListener {
            if (panel.visibility == View.VISIBLE) syncIcons()
            true
        }
        // 1.2.2부터 플로팅 툴바가 기본으로 켜진다. 업데이트한 뒤 처음 한 번은 (예전에 꺼 두었어도) 켜서 보여 준다
        if (!prefs.getBoolean("floatBarDefaultOn", false)) {
            prefs.edit().putBoolean("floatBarOn", true).putBoolean("floatBarDefaultOn", true).apply()
        }
        rebuild()
        applyShown()
    }

    // ================= 단추 =================

    private fun sourceIcon(key: String): Drawable? = (byKey[key]?.source?.invoke() as? ImageView)?.drawable

    /** 단추를 다시 늘어놓는다 (더한 단추·직전 도구·아이콘 크기가 바뀌었을 때) */
    fun rebuild() {
        val keys = currentSlots()
        val recentKey = FloatBarMath.recentSlot(history, extra)
        val size = dpx(FloatBarMath.BUTTON_DP * iconScale())
        val gap = dpx(4f)
        grid.removeAllViews()
        grid.columnCount = FloatBarMath.columns(keys.size)
        buttons = keys.mapIndexed { i, k ->
            val item = byKey.getValue(k)
            FloatButton(activity, k, item.source, recent = k == recentKey).also { b ->
                b.contentDescription = item.label
                b.tooltipText = item.label
                b.setOnClickListener { item.onClick(b) }
                grid.addView(b, GridLayout.LayoutParams().apply {
                    width = size
                    height = size
                    setMargins(gap / 2, gap / 2, gap / 2, gap / 2)
                })
            }
        }
        applyFolded()
    }

    private fun currentSlots() = FloatBarMath.slots(history, extra, hidden).filter { it in byKey }

    /** 펜 색처럼 원래 단추 아이콘 속만 바뀌었을 때 (그림 개체는 같아서 [syncIcons]가 모른다) */
    fun invalidateIcons() {
        buttons.forEach { it.invalidate() }
        gripIcon.invalidate()
    }

    private fun syncIcons() {
        for (b in buttons) {
            val src = byKey[b.key]?.source?.invoke()
            val sig = arrayOf(System.identityHashCode((src as? ImageView)?.drawable), src?.isSelected, src?.alpha).contentHashCode()
            if (sig != b.sig) {
                b.sig = sig
                b.invalidate()
            }
        }
        val key = currentToolKey()
        val sig = arrayOf(key, System.identityHashCode(sourceIcon(key))).contentHashCode()
        if (sig != gripSig) {
            gripSig = sig
            gripIcon.invalidate()
        }
    }

    /** 도구를 골랐을 때 (하단 툴바·플로팅 툴바 어디서든). 직전 도구 칸을 맞춘다 */
    fun onToolSelected(key: String) {
        val next = FloatBarMath.pushHistory(history, key)
        if (next != history) {
            history = next
            prefs.edit().putString("floatBarHistory", history.joinToString(",")).apply()
        }
        if (currentSlots() != buttons.map { it.key }) rebuild()
        invalidateIcons()
    }

    // ================= 보이기 · 접기 =================

    fun setShown(on: Boolean) {
        prefs.edit().putBoolean("floatBarOn", on).apply()
        applyShown()
    }

    private fun applyShown() {
        val on = isShown
        panel.visibility = if (on) View.VISIBLE else View.GONE
        if (on) queueCapture() else popup?.dismiss()
    }

    private fun setFolded(f: Boolean) {
        folded = f
        prefs.edit().putBoolean("floatBarFolded", f).apply()
        popup?.dismiss()
        applyFolded()
    }

    private fun applyFolded() {
        grid.visibility = if (folded) View.GONE else View.VISIBLE
        gripIcon.visibility = if (folded) View.VISIBLE else View.GONE
        gripPlus.visibility = if (folded) View.GONE else View.VISIBLE
        // 꺾쇠(>)를 돌려 접기는 위(∧), 펴기는 아래(∨)
        foldButton.rotation = if (folded) 90f else -90f
        foldButton.contentDescription = if (folded) "플로팅 툴바 펴기" else "플로팅 툴바 접기"
        foldButton.tooltipText = foldButton.contentDescription
        grip.contentDescription = if (folded) "플로팅 툴바 손잡이 (끌어서 옮기기, 누르면 펴기)" else "플로팅 툴바 손잡이 (끌어서 옮기기, 누르면 옵션)"
    }

    // ================= 옮기기 =================

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGrip() {
        val slop = ViewConfiguration.get(activity).scaledTouchSlop
        var downX = 0f
        var downY = 0f
        var startX = 0f
        var startY = 0f
        var dragging = false
        grip.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY
                    startX = panel.x; startY = panel.y
                    dragging = false
                    v.isPressed = true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!dragging && hypot(dx, dy) > slop) {
                        dragging = true
                        v.isPressed = false
                        popup?.dismiss()
                    }
                    if (dragging) moveTo(startX + dx, startY + dy)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    if (dragging) savePosition() else onGripTap()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    if (dragging) savePosition()
                }
            }
            true
        }
    }

    private fun onGripTap() {
        if (folded) setFolded(false)
        else if (popup?.isShowing == true) popup?.dismiss()
        else showOptions()
    }

    /** 툴바를 옮긴다. 유리 부분이 화면 밖으로 나가지 않게 (바깥 그림자 여백은 넘어가도 된다) */
    private fun moveTo(x: Float, y: Float) {
        val m = panel.margin
        panel.x = FloatBarMath.clamp(x + m, panel.width - 2 * m, frame.width.toFloat()) - m
        panel.y = FloatBarMath.clamp(y + m, panel.glassHeight, frame.height.toFloat()) - m
        queueCapture()
    }

    private fun savePosition() {
        val m = panel.margin
        fx = FloatBarMath.toFraction(panel.x + m, panel.width - 2 * m, frame.width.toFloat())
        fy = FloatBarMath.toFraction(panel.y + m, panel.glassHeight, frame.height.toFloat())
        prefs.edit().putFloat("floatBarX", fx).putFloat("floatBarY", fy).apply()
    }

    /** 화면 크기가 바뀌면 (돌리기·분할 보기·페이지 관리 창) 저장한 비율 자리로 */
    private fun placeFromFraction() {
        if (panel.width == 0 || frame.width == 0) return
        placed = true
        val m = panel.margin
        panel.x = FloatBarMath.fromFraction(fx, panel.width - 2 * m, frame.width.toFloat()) - m
        panel.y = FloatBarMath.fromFraction(fy, panel.glassHeight, frame.height.toFloat()) - m
        queueCapture()
    }

    // ================= 유리 뒤 흐린 문서 =================
    // 흐린 바탕은 유리 판이 자기를 그릴 때 그 자리에서 문서를 다시 그려 만든다 ([GlassPanel.drawBackdrop]).
    // 문서와 같은 화면(프레임)에서 그려지므로, 옮기거나 스크롤해도 뒤처지지 않는다.

    /** 문서 화면이 다시 그려졌을 때 (DocumentView.onDrawn). 같은 화면에서 유리도 다시 그린다 */
    fun onDocDrawn() {
        if (panel.isCapturing || panel.visibility != View.VISIBLE) return
        panel.invalidate()
    }

    private fun queueCapture() {
        panel.invalidate()
    }

    // ================= 옵션 창 (손잡이 톡) =================

    private fun showOptions() {
        popup?.dismiss()
        val onSurface = MaterialColors.getColor(frame, com.google.android.material.R.attr.colorOnSurface)
        val muted = MaterialColors.getColor(frame, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dpx(14f), dpx(12f), dpx(14f), dpx(12f))
        }
        fun label(text: String, top: Float) = TextView(activity).apply {
            this.text = text
            textSize = 13f
            setTextColor(muted)
            setPadding(dpx(2f), dpx(top), 0, dpx(4f))
        }
        content.addView(TextView(activity).apply {
            text = "플로팅 툴바"
            textSize = 15f
            setTextColor(onSurface)
            typeface = android.graphics.Typeface.create(typeface, android.graphics.Typeface.BOLD)
        })
        content.addView(label("그리기 도구 (하단 툴바)", 10f))
        content.addView(chips(items.filter { it.group == Group.TOOL }))
        content.addView(label("문서 · 보기 기능 (상단 툴바)", 10f))
        content.addView(chips(items.filter { it.group == Group.TOP }))

        val value = TextView(activity).apply {
            textSize = 13f
            setTextColor(muted)
            text = "$transparency%"
        }
        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(label("투명도", 12f), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(value.apply { setPadding(0, dpx(12f), dpx(2f), dpx(4f)) })
        })
        content.addView(SeekBar(activity).apply {
            max = FloatBarMath.MAX_TRANSPARENCY - FloatBarMath.MIN_TRANSPARENCY
            progress = transparency - FloatBarMath.MIN_TRANSPARENCY
            contentDescription = "투명도"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, p: Int, fromUser: Boolean) {
                    transparency = FloatBarMath.MIN_TRANSPARENCY + p
                    value.text = "$transparency%"
                    panel.tint = FloatBarMath.tintAlpha(transparency)
                    panel.invalidate()
                }
                override fun onStartTrackingTouch(bar: SeekBar) {}
                override fun onStopTrackingTouch(bar: SeekBar) {
                    prefs.edit().putInt("floatBarTransparency", transparency).apply()
                }
            })
        })
        content.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dpx(8f), 0, 0)
            addView(actionChip("기본으로", R.drawable.ic_history) {
                extra = emptyList()
                hidden = emptyList()
                transparency = FloatBarMath.DEFAULT_TRANSPARENCY
                prefs.edit().remove("floatBarExtra").remove("floatBarHidden").remove("floatBarTransparency").apply()
                panel.tint = FloatBarMath.tintAlpha(transparency)
                rebuild()
                showOptions()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dpx(6f) })
            addView(actionChip("툴바 끄기", R.drawable.ic_close) {
                setShown(false)
                Toast.makeText(activity, "보기 메뉴의 '플로팅 툴바'로 다시 켤 수 있습니다.", Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        })

        val scroll = ScrollView(activity).apply { addView(content) }
        val p = PopupWindow(scroll, dpx(300f), ViewGroup.LayoutParams.WRAP_CONTENT, true).apply {
            setBackgroundDrawable(GradientDrawable().apply {
                cornerRadius = 18 * d
                setColor(Color.argb(246, 249, 251, 254))
                setStroke(dpx(1f), MaterialColors.getColor(frame, com.google.android.material.R.attr.colorOutlineVariant))
            })
            elevation = 10 * d
            setOnDismissListener {
                if (popup === this) popup = null
                setGripOpen(false)
            }
        }
        popup = p
        setGripOpen(true)
        positionPopup(p)
    }

    private fun setGripOpen(open: Boolean) {
        grip.background = if (open) GradientDrawable().apply {
            cornerRadius = 13 * d
            setColor(ContextCompat.getColor(activity, R.color.active_fill))
            alpha = 140
        } else null
    }

    /** 옵션 창은 툴바 옆 (오른쪽에 자리가 없으면 왼쪽), 화면 밖으로 나가지 않게 */
    private fun positionPopup(p: PopupWindow) {
        val root = activity.window.decorView
        val margin = dpx(8f)
        val pw = dpx(300f)
        val content = p.contentView
        content.measure(View.MeasureSpec.makeMeasureSpec(pw, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        val ph = min(content.measuredHeight, root.height - 2 * margin)
        val loc = IntArray(2)
        frame.getLocationInWindow(loc)
        val m = panel.margin
        val gx = (loc[0] + panel.x + m).toInt()
        val gy = (loc[1] + panel.y + m).toInt()
        val gw = (panel.width - 2 * m).toInt()
        var x = gx + gw + margin
        if (x + pw > root.width - margin) x = gx - pw - margin
        x = x.coerceIn(margin, max(margin, root.width - pw - margin))
        val y = gy.coerceIn(margin, max(margin, root.height - ph - margin))
        if (p.isShowing) p.update(x, y, pw, ph) else {
            p.height = ph
            p.showAtLocation(frame, Gravity.NO_GRAVITY, x, y)
        }
    }

    private fun chips(list: List<Item>): ChipGroup {
        val group = ChipGroup(activity).apply {
            chipSpacingHorizontal = dpx(6f)
            chipSpacingVertical = dpx(2f)
        }
        val checked = intArrayOf(android.R.attr.state_checked)
        val none = intArrayOf()
        for (item in list) {
            group.addView(Chip(activity).apply {
                text = item.label
                textSize = 13f
                isCheckable = true
                val isDefault = item.key in FloatBarMath.DEFAULTS
                isChecked = if (isDefault) item.key !in hidden else item.key in extra
                isCheckedIconVisible = false
                chipIcon = MirrorDrawable({ sourceIcon(item.key) }, dpx(18f))
                isChipIconVisible = true
                chipIconSize = 18 * d
                chipIconTint = null
                setEnsureMinTouchTargetSize(false)
                chipMinHeight = 34 * d
                chipStrokeWidth = 1 * d
                chipBackgroundColor = ColorStateList(arrayOf(checked, none), intArrayOf(ContextCompat.getColor(activity, R.color.active_fill), Color.WHITE))
                chipStrokeColor = ColorStateList(arrayOf(checked, none), intArrayOf(Color.rgb(0x9D, 0xBD, 0xE6), Color.rgb(0xC9, 0xD3, 0xE2)))
                setTextColor(ColorStateList(arrayOf(checked, none), intArrayOf(Color.rgb(0x1B, 0x35, 0x57), Color.rgb(0x3E, 0x4A, 0x5C))))
                setOnCheckedChangeListener { _, on ->
                    if (isDefault) {
                        // 기본 세 칸은 끄면 '뺀 목록'에 넣는다 (켜면 원래 자리로 돌아온다)
                        hidden = if (on) hidden - item.key else (hidden + item.key).distinct()
                        prefs.edit().putString("floatBarHidden", hidden.joinToString(",")).apply()
                    } else {
                        extra = if (on) (extra + item.key).distinct() else extra - item.key
                        prefs.edit().putString("floatBarExtra", extra.joinToString(",")).apply()
                    }
                    rebuild()
                }
            })
        }
        return group
    }

    private fun actionChip(text: String, icon: Int, onClick: () -> Unit) = Chip(activity).apply {
        this.text = text
        textSize = 13f
        setChipIconResource(icon)
        isChipIconVisible = true
        chipIconSize = 18 * d
        setEnsureMinTouchTargetSize(false)
        chipMinHeight = 36 * d
        chipStrokeWidth = 1 * d
        chipBackgroundColor = ColorStateList.valueOf(Color.WHITE)
        chipStrokeColor = ColorStateList.valueOf(Color.rgb(0xC9, 0xD3, 0xE2))
        textAlignment = View.TEXT_ALIGNMENT_CENTER
        setOnClickListener { onClick() }
    }
}

/** 다른 단추의 아이콘을 그대로 비춰 그린다 (원래 단추의 아이콘·켜짐 색이 바뀌면 그대로 따라간다) */
internal class MirrorDrawable(private val source: () -> Drawable?, private val sizePx: Int) : Drawable() {
    private val saved = Rect()

    override fun draw(canvas: Canvas) {
        val d = source() ?: return
        if (d === this) return
        d.copyBounds(saved)
        d.bounds = bounds
        d.draw(canvas)
        d.bounds = saved
    }

    override fun getIntrinsicWidth() = sizePx
    override fun getIntrinsicHeight() = sizePx
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}

/**
 * 플로팅 툴바의 유리 판. 바깥 [margin](아래는 [marginBottom])은 그림자 자리이고 그 안쪽 둥근 네모가 유리다.
 * 뒤 문서를 흐리게 한 바탕([drawBackdrop]) 위에 옅은 흰색([tint]), 위쪽 반사광, 대각선 광택, 흰 테두리를 얹는다
 */
@SuppressLint("ViewConstructor")
internal class GlassPanel(ctx: Context) : FrameLayout(ctx) {
    private val d = resources.displayMetrics.density
    // 그림자 자리. 그림자는 14dp로 퍼지고 5dp 아래로 내려가 있어서 아래쪽에 더 넉넉히 둔다 (모자라면 끄트머리가 잘려 보인다)
    val margin = 16 * d
    val marginBottom = 28 * d
    val glassHeight get() = height - margin - marginBottom
    private val radius = 22 * d

    /** 유리 뒤에 비칠 문서 화면 */
    var backdropSource: (() -> View)? = null
    var backdropColor = Color.WHITE

    /** 유리 뒤를 그리려고 문서를 다시 그리는 중 (그 사이 문서가 '그려졌다'고 알려도 무시한다) */
    var isCapturing = false
        private set
    private val blurRadius = 2.5f * d
    private var blurNode: RenderNode? = null
    private var snapshot: Bitmap? = null
    private var pixels = IntArray(0)
    private val srcLoc = IntArray(2)
    private val selfLoc = IntArray(2)

    var tint = 0.05f
        set(v) {
            if (field != v) {
                field = v
                tintShaderFor = -1f
            }
        }

    private val glass = RectF()
    private val gloss = RectF()
    private val rim = RectF()
    private val line = RectF()
    private val path = Path()
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        setShadowLayer(14 * d, 0f, 5 * d, Color.argb(60, 31, 39, 51))
    }
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val tintPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var tintShaderFor = -1f
    private val glossPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val sheenPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.2f * d
        color = Color.argb(215, 255, 255, 255)
    }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.8f * d
        color = Color.argb(100, 74, 120, 184)
    }

    init {
        setWillNotDraw(false)
        val m = margin.toInt()
        setPadding(m + (8 * d).toInt(), m + (6 * d).toInt(), m + (8 * d).toInt(), marginBottom.toInt() + (8 * d).toInt())
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        glass.set(margin, margin, w - margin, h - marginBottom)
        path.reset()
        path.addRoundRect(glass, radius, radius, Path.Direction.CW)
        gloss.set(glass.left + 10 * d, glass.top + 2 * d, glass.right - 10 * d, glass.top + min(glass.height() * 0.46f, 60 * d))
        glossPaint.shader = LinearGradient(0f, gloss.top, 0f, gloss.bottom, Color.argb(100, 255, 255, 255), Color.argb(8, 255, 255, 255), Shader.TileMode.CLAMP)
        sheenPaint.shader = LinearGradient(
            glass.left, glass.top, glass.right, glass.bottom,
            intArrayOf(Color.argb(55, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(0, 255, 255, 255), Color.argb(34, 255, 255, 255)),
            floatArrayOf(0f, 0.32f, 0.7f, 1f), Shader.TileMode.CLAMP,
        )
        rim.set(glass); rim.inset(0.6f * d, 0.6f * d)
        line.set(glass); line.inset(-0.4f * d, -0.4f * d)
        tintShaderFor = -1f
    }

    override fun onDraw(canvas: Canvas) {
        if (glass.isEmpty) return
        // 그림자는 유리 바깥에만 (반투명한 유리 안으로 그림자가 비치지 않게)
        canvas.save()
        canvas.clipOutPath(path)
        canvas.drawRoundRect(glass, radius, radius, shadowPaint)
        canvas.restore()

        canvas.save()
        canvas.clipPath(path)
        drawBackdrop(canvas)
        if (tintShaderFor != tint) {
            tintShaderFor = tint
            val top = ((tint + 0.06f).coerceAtMost(1f) * 255).toInt()
            val bottom = (tint * 255).toInt()
            tintPaint.shader = LinearGradient(0f, glass.top, 0f, glass.bottom, Color.argb(top, 255, 255, 255), Color.argb(bottom, 240, 245, 252), Shader.TileMode.CLAMP)
        }
        canvas.drawRect(glass, tintPaint)
        canvas.drawRoundRect(gloss, radius - 2 * d, radius - 2 * d, glossPaint)
        canvas.drawRect(glass, sheenPaint)
        canvas.restore()
        canvas.drawRoundRect(rim, radius, radius, rimPaint)
        canvas.drawRoundRect(line, radius, radius, linePaint)
    }

    /**
     * 유리 뒤 문서를 흐리게 그린다. 유리 판이 그려지는 바로 그 화면에서 문서를 다시 그려 만들기 때문에
     * 툴바를 옮기거나 문서를 스크롤해도 바탕이 늦게 따라오지 않는다.
     * Android 12 이상: 문서를 그리기 목록(RenderNode)으로 기록해 GPU 흐림(RenderEffect)을 건다 — 픽셀을 CPU로 만지지 않는다.
     * 그 아래: 작은 그림에 그려 상자 흐림 (1.5dp가 한 점).
     */
    private fun drawBackdrop(canvas: Canvas) {
        val src = backdropSource?.invoke() ?: return
        if (isCapturing || src.width == 0 || src.height == 0) return
        src.getLocationInWindow(srcLoc)
        getLocationInWindow(selfLoc)
        val dx = (srcLoc[0] - selfLoc[0]).toFloat()
        val dy = (srcLoc[1] - selfLoc[1]).toFloat()
        isCapturing = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canvas.isHardwareAccelerated) drawBlurredGpu(canvas, src, dx, dy)
            else drawBlurredBitmap(canvas, src, dx, dy)
        } catch (_: RuntimeException) {
        } finally {
            isCapturing = false
        }
    }

    @RequiresApi(Build.VERSION_CODES.S)
    private fun drawBlurredGpu(canvas: Canvas, src: View, dx: Float, dy: Float) {
        val node = blurNode ?: RenderNode("glassBackdrop").also {
            it.setRenderEffect(RenderEffect.createBlurEffect(blurRadius, blurRadius, Shader.TileMode.CLAMP))
            blurNode = it
        }
        // 가장자리도 진짜 문서로 흐리도록 흐림 폭만큼 넓게 기록한다 (바깥은 유리 모양으로 잘린다)
        val pad = ceil(blurRadius * 2).toInt()
        val l = glass.left.toInt() - pad
        val t = glass.top.toInt() - pad
        node.setPosition(l, t, ceil(glass.right).toInt() + pad, ceil(glass.bottom).toInt() + pad)
        val rc = node.beginRecording()
        try {
            rc.drawColor(backdropColor)
            rc.translate(dx - l, dy - t)
            src.draw(rc)
        } finally {
            node.endRecording()
        }
        canvas.drawRenderNode(node)
    }

    private fun drawBlurredBitmap(canvas: Canvas, src: View, dx: Float, dy: Float) {
        val s = 1f / (1.5f * d)
        val bw = max(1, ceil(glass.width() * s).toInt())
        val bh = max(1, ceil(glass.height() * s).toInt())
        val bmp = snapshot?.takeIf { it.width == bw && it.height == bh }
            ?: Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888).also { snapshot = it }
        val c = Canvas(bmp)
        c.drawColor(backdropColor)
        c.scale(s, s)
        c.translate(dx - glass.left, dy - glass.top)
        src.draw(c)
        if (pixels.size != bw * bh) pixels = IntArray(bw * bh)
        bmp.getPixels(pixels, 0, bw, 0, 0, bw, bh)
        FloatBarMath.boxBlur(pixels, bw, bh, radius = 1, passes = 2)
        bmp.setPixels(pixels, 0, bw, 0, 0, bw, bh)
        canvas.drawBitmap(bmp, null, glass, bmpPaint)
    }

    /** 유리 위를 누른 것은 문서로 넘기지 않는다 (바깥 그림자 여백은 넘긴다) */
    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent) = glass.contains(event.x, event.y)
}

/** 유리 단추: 왼쪽 위에서 빛이 비치는 둥근 네모 위에 원래 단추의 아이콘, 켜져 있으면 파란 바탕과 밑줄 */
@SuppressLint("ViewConstructor")
internal class FloatButton(ctx: Context, val key: String, private val source: () -> View?, private val recent: Boolean) : View(ctx) {
    private val d = resources.displayMetrics.density
    var sig = 0
    private val icon = MirrorDrawable({ (source() as? ImageView)?.drawable }, (24 * d).toInt())
    private val bg = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = d
        color = Color.argb(242, 255, 255, 255)
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 0.6f * d
        color = Color.argb(40, 74, 120, 184)
    }
    private val press = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(30, 31, 39, 51) }
    private val mark = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(ctx, R.color.active_stroke) }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(ctx, R.color.brand)
        alpha = 150
    }
    private val rect = RectF()
    private val bar = RectF()
    private var shaderOn: Boolean? = null

    init {
        isClickable = true
        isFocusable = true
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        shaderOn = null
    }

    override fun onDraw(canvas: Canvas) {
        val src = source()
        val on = src?.isSelected == true
        val w = width.toFloat()
        val h = height.toFloat()
        // k = 아이콘 배율. 단추는 작아졌어도 아이콘은 24dp × 배율 그대로
        val k = w / (FloatBarMath.BUTTON_DP * d)
        rect.set(0.5f * d, 0.5f * d, w - 0.5f * d, h - 0.5f * d)
        val r = 10.5f * d * k
        if (shaderOn != on) {
            shaderOn = on
            bg.shader = RadialGradient(
                w * 0.3f, h * 0.12f, w * 1.2f,
                if (on) intArrayOf(Color.argb(242, 255, 255, 255), Color.argb(217, 206, 225, 250), Color.argb(204, 179, 208, 245))
                else intArrayOf(Color.argb(250, 255, 255, 255), Color.argb(184, 255, 255, 255), Color.argb(168, 236, 242, 250)),
                floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRoundRect(rect, r, r, bg)
        canvas.drawRoundRect(rect, r, r, edge)
        canvas.drawRoundRect(rect, r, r, stroke)
        if (isPressed) canvas.drawRoundRect(rect, r, r, press)
        val s = 24 * d * k
        val l = ((w - s) / 2).toInt()
        val t = ((h - s) / 2).toInt()
        icon.setBounds(l, t, l + s.toInt(), t + s.toInt())
        val a = src?.alpha ?: 1f
        if (a < 1f) {
            val save = canvas.saveLayerAlpha(0f, 0f, w, h, (a * 255).toInt())
            icon.draw(canvas)
            canvas.restoreToCount(save)
        } else icon.draw(canvas)
        if (on) {
            val bw = 16 * d
            val bh = 2.5f * d
            bar.set(w / 2 - bw / 2, h - 2.5f * d * k - bh, w / 2 + bw / 2, h - 2.5f * d * k)
            canvas.drawRoundRect(bar, bh / 2, bh / 2, mark)
        }
        if (recent) canvas.drawCircle(w - 5.5f * d * k, 5.5f * d * k, 2.2f * d, dot)
    }

    override fun drawableStateChanged() {
        super.drawableStateChanged()
        invalidate()
    }
}

/** 손잡이 점 (가로 3 × 세로 2) */
internal class GripDots(ctx: Context) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    var color: Int = Color.GRAY
        set(v) {
            field = v
            paint.color = v
            invalidate()
        }

    override fun onDraw(canvas: Canvas) {
        val r = 1.4f * d
        val cx = width / 2f
        val cy = height / 2f
        for (i in -1..1) for (j in 0..1) {
            canvas.drawCircle(cx + i * 6 * d, cy + (j - 0.5f) * 6 * d, r, paint)
        }
    }
}
