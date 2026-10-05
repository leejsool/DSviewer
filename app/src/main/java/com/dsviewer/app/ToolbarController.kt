package com.dsviewer.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.view.InputDevice
import android.widget.FrameLayout
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import android.content.SharedPreferences

/**
 * 툴바 쪽 일체: 도구 버튼 · 색 칸 · 굵기 칸 · 옵션 줄 · 펼침 창 · 보정 펜 도형 줄 · 올가미 선택 막대 · 글 서식 줄,
 * 그리고 이들을 툴바 방향(위·아래·왼쪽·오른쪽)에 맞춰 놓는 일. 문서와 탭은 뷰어가 쥐고 있고 여기서는 넘겨받은 것만 쓴다.
 */
internal class ToolbarController(
    private val activity: AppCompatActivity,
    /** 지금 고른 칸의 문서 화면 (분할 보기에서는 칸이 바뀐다) */
    private val docViewOf: () -> DocumentView,
    private val textEditor: InlineTextEditor,
    private val prefs: SharedPreferences,
    /** 지금 보는 문서의 필기 */
    private val ink: () -> InkDocument?,
    /** 오답 ▸ 오답 담기 */
    private val startWrongPick: () -> Unit,
    /** 올가미 ▸ 영역 스크린샷 */
    private val startShot: () -> Unit,
    /** 옵션 창 (⋮ ▸ 옵션, 툴바 손잡이 톡) */
    private val showOptions: (start: Int) -> Unit,

) {
    private val docView get() = docViewOf()
    /** 모든 칸의 문서 화면 (겹쳐 뜬 줄이 가리는 만큼을 칸마다 따로 알려 줄 때) */
    var allViews: () -> List<DocumentView> = { listOf(docView) }
    /** 겹쳐 뜬 줄들의 둘레를 담는 틀 (칸의 자리를 이 틀 기준으로 재려고) */
    private val docFrame get() = findViewById<FrameLayout>(R.id.docFrame)
    private val resources get() = activity.resources
    private val window get() = activity.window
    private val theme get() = activity.theme
    private fun getDrawable(id: Int) = activity.getDrawable(id)
    private fun <T : View> findViewById(id: Int): T = activity.findViewById(id)

    private val colorRow: LinearLayout = findViewById(R.id.colorRow)
    private val widthRow: LinearLayout = findViewById(R.id.widthRow)
    private val colorSep: View = findViewById(R.id.colorSep)
    /** 지금 쓰는 도형 줄: 가로 줄([shapeBarH]) 또는 세로 줄([shapeBarV]). 칸들은 [shapeRow]에 */
    private var shapeBar: View = findViewById(R.id.shapeBar)
    private lateinit var shapeBarH: View
    private lateinit var shapeBarV: View
    private val shapeRow: LinearLayout = findViewById(R.id.shapeRow)
    private val lassoRow: View = findViewById(R.id.lassoRow)
    private val pasteButton: View = findViewById(R.id.pasteButton)
    private val selectionBar: View = findViewById(R.id.selectionBar)
    lateinit var toolButtons: Map<Tool, ImageButton>
    private lateinit var dock: ToolbarDock
    private lateinit var topOverlay: LinearLayout
    private lateinit var bottomOverlay: LinearLayout

    /** 문서 위에 겹쳐 뜬 줄들의 높이만큼 문서를 더 스크롤할 수 있게 한다 */
    fun watchOverlays() {
        for (o in listOf(topOverlay, bottomOverlay)) o.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayInsets() }
    }

    /** 읽기 모드가 바뀔 때: 도형 줄은 읽기 모드가 아니고 보정 펜일 때만 */
    fun updateShapeBarForReadMode(readMode: Boolean) {
        shapeBar.visibility = if (!readMode && docView.tool == Tool.SHAPE) View.VISIBLE else View.GONE
    }

    /** 기기에 펜 입력(S펜 등)이 있는지. 펜을 인식하는 화면은 입력 장치에 SOURCE_STYLUS가 붙는다 */
    private fun hasStylus() = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.supportsSource(InputDevice.SOURCE_STYLUS) == true
    }


    // ================= 올가미 선택 막대 =================

    fun setupSelectionTools() {
        findViewById<View>(R.id.selectionDelete).setOnClickListener { docView.deleteSelection() }
        findViewById<View>(R.id.selectionFlipH).setOnClickListener { docView.flipSelection(horizontal = true) }
        findViewById<View>(R.id.selectionFlipV).setOnClickListener { docView.flipSelection(horizontal = false) }
        findViewById<View>(R.id.selectionCopy).setOnClickListener {
            docView.copySelection()
            pasteButton.visibility = View.VISIBLE
            Toast.makeText(activity, "복사했습니다. '붙여넣기'로 원하는 페이지에 붙일 수 있습니다.", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.selectionColor).setOnClickListener { v ->
            val initial = docView.selectionColor ?: return@setOnClickListener
            ColorPickerPopup(activity, initial, recentColors(Tool.PEN)) { c, done ->
                if (done) {
                    docView.recolorSelection(c)
                    addRecent(Tool.PEN, c)
                }
            }.show(v)
        }
        pasteButton.setOnClickListener { docView.pasteClipboard() }

        // 자유 선택 / 네모 선택 / 대상 선택 (고르는 곳은 옵션 줄)
        val lassoMode = prefs.getInt("lassoMode", if (prefs.getBoolean("lassoRect", false)) 1 else 0)
        docView.lassoRect = lassoMode == 1
        docView.lassoTap = lassoMode == 2
    }


    // ================= 글 서식 줄 =================
    // 글 상자를 치는 동안 옵션 줄 자리에 뜬다: 글자색 · 배경색 · 서식 · 글씨체 · 크기 · 굵게 · 기울임 · 밑줄 · 취소선 ·
    // 목록(체크 · 번호 · 점) · 정렬(왼쪽 · 가운데 · 오른쪽) · 들여쓰기 · 내어쓰기.
    // 툴바가 왼쪽·오른쪽이면 서식 줄도 세로로 세워 툴바 옆에 붙인다 (placeOverlays)

    /** 지금 쓰는 서식 줄: 가로 줄([formatBarH]) 또는 세로 줄([formatBarV]) */
    private lateinit var formatBar: View
    private lateinit var formatBarH: View
    private lateinit var formatBarV: View
    private lateinit var textFormat: TextFormatBar

    fun setupFormatBar() {
        textFormat = TextFormatBar(
            activity, textEditor, dock, findViewById(R.id.formatRow), { formatBar },
            { recentColors(Tool.PEN) }, { addRecent(Tool.PEN, it) }, ::hideOptionBar,
        )
        textFormat.setup()
    }

    // ================= 툴바 자리 =================
    // 툴바 맨 앞 손잡이를 끌어 위·아래·왼쪽·오른쪽에 붙인다 (ToolbarDock). 자리는 기억해 둔다.

    fun setupToolbarDock() {
        formatBarH = findViewById(R.id.formatBar)
        formatBar = formatBarH
        formatBarV = sideScroll()
        shapeBarH = shapeBar
        shapeBarV = sideScroll()
        optionBarH = optionBar
        optionBarV = sideScroll()
        topOverlay = findViewById(R.id.topOverlay)
        bottomOverlay = findViewById(R.id.bottomOverlay)
        dock = ToolbarDock(
            root = findViewById(R.id.root),
            docRow = findViewById(R.id.docRow),
            toolbar = findViewById(R.id.toolbar),
            handle = findViewById(R.id.toolbarHandle),
            scrollH = findViewById(R.id.toolScrollH),
            content = findViewById(R.id.toolContent),
            onDocked = { side ->
                placeOverlays(side)
                prefs.edit().putString("toolbarSide", side.name).apply()
            },
            onTap = { showOptions(1) },
        )
        dock.setIconScale(toolIconScale())
        val saved = prefs.getString("toolbarSide", null)
        dock.dock(ToolbarSide.entries.firstOrNull { it.name == saved } ?: ToolbarSide.BOTTOM)
    }

    // ================= 툴바 아이콘 크기 =================

    fun toolIconScale() = IconSize.scale(prefs.getInt("toolIconSize", IconSize.DEFAULT))

    /** 옵션에서 하단(도구) 툴바 아이콘 크기를 바꿨을 때. 열려 있던 줄·창은 자리가 달라지므로 닫는다 */
    fun applyToolIconSize() {
        hideOptionBar()
        closeFlyout()
        dock.setIconScale(toolIconScale())
        findViewById<View>(R.id.toolbar).post { updateOverlayInsets() }
    }

    // ================= 툴바에 보일 도구 =================
    // 툴바 손잡이를 톡 누르거나 ⋮ 메뉴 '툴바 옵션'에서 도구마다 보이기·숨기기 (prefs hiddenTools)

    /** 툴바 도구 차례와 이름 (보이기·숨기기 창) */
    val toolNames = listOf(
        Tool.PEN to "펜", Tool.SHAPE to "보정 펜", Tool.HIGHLIGHTER to "형광펜", Tool.FILL to "채우기", Tool.TAPE to "테이프",
        Tool.TEXT to "글 넣기", Tool.ERASER to "지우개", Tool.LASSO to "선택", Tool.LASER to "레이저 포인터",
    )

    fun hiddenTools(): Set<Tool> =
        prefs.getString("hiddenTools", null)?.split(',')?.mapNotNull { n -> Tool.entries.firstOrNull { it.name == n } }?.toSet()
            ?: emptySet()

    /** 숨긴 도구 버튼을 감춘다. 쓰던 도구를 숨겼으면 보이는 첫 도구로 */
    fun applyToolVisibility() {
        val hidden = hiddenTools()
        toolButtons.forEach { (t, b) -> b.visibility = if (t in hidden) View.GONE else View.VISIBLE }
        if (docView.tool in hidden) toolNames.firstOrNull { it.first !in hidden }?.let { selectTool(it.first) }
    }


    /** 세로 툴바 옆에 붙는 줄(서식·도형·옵션)을 담는 세로 스크롤 */
    private fun sideScroll() = android.widget.ScrollView(activity).apply {
        setBackgroundColor(MaterialColors.getColor(shapeBar, com.google.android.material.R.attr.colorSurfaceContainer))
        isVerticalScrollBarEnabled = false
        // 칸이 적으면 세로 줄 가운데에
        isFillViewport = true
        visibility = View.GONE
    }

    /** 줄 [row]를 [old] 스크롤에서 [new] 스크롤로 옮겨 담고 보임 상태를 넘긴다. 새로 쓰는 스크롤을 돌려준다 */
    private fun swapBar(old: View, new: View, row: View): View {
        if (new === old) return old
        (row.parent as? ViewGroup)?.removeView(row)
        (new as ViewGroup).addView(row)
        new.visibility = old.visibility
        old.visibility = View.GONE
        (old.parent as? ViewGroup)?.removeView(old)
        return new
    }

    /**
     * 도형 줄·옵션 줄·서식 줄은 툴바 옆에 나란히 띄운다: 툴바가 위면 문서 위쪽에, 아래면 아래쪽에 가로로,
     * 왼쪽·오른쪽이면 문서 화면의 그쪽 가장자리에 세로로. 세로 툴바에서는 '붙여넣기'를 아이콘만 보인다
     */
    private fun placeOverlays(side: ToolbarSide) {
        hideOptionBar()
        closeFlyout()
        val top = side == ToolbarSide.TOP
        val v = side.vertical
        val target = if (top) topOverlay else bottomOverlay
        formatBar = swapBar(formatBar, if (v) formatBarV else formatBarH, findViewById(R.id.formatRow))
        shapeBar = swapBar(shapeBar, if (v) shapeBarV else shapeBarH, shapeRow)
        optionBar = swapBar(optionBar, if (v) optionBarV else optionBarH, optionRow)
        val bars = if (v) listOf(shapeBar, optionBar, formatBar)
            else if (top) listOf(optionBar, formatBar, shapeBar) else listOf(shapeBar, formatBar, optionBar)
        val frame = findViewById<FrameLayout>(R.id.docFrame)
        for (b in bars) {
            (b.parent as? ViewGroup)?.removeView(b)
            if (v) frame.addView(b, frame.indexOfChild(topOverlay) + 1, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT,
                if (side == ToolbarSide.LEFT) android.view.Gravity.START else android.view.Gravity.END))
            else target.addView(b)
        }
        if (::textFormat.isInitialized) textFormat.fit()
        val d = resources.displayMetrics.density
        fun px(x: Int) = (x * d).toInt()
        // 도형 줄·옵션 줄: 가로면 칸이 옆으로, 세로면 아래로 늘어선다. 가로 줄은 툴바에서 먼 쪽에 여백을 조금 더
        for (row in listOf(shapeRow, optionRow)) {
            row.orientation = if (v) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            // 칸이 적어 줄이 남으면 툴바처럼 가운데에 모은다
            row.gravity = android.view.Gravity.CENTER
            if (v) row.setPadding(px(4), px(8), px(4), px(8))
            else row.setPadding(px(8), px(if (top) 2 else 6), px(8), px(if (top) 6 else 2))
        }
        if (::toolButtons.isInitialized) buildShapeBar()
        (pasteButton as MaterialButton).apply {
            text = if (v) "" else "붙여넣기"
            iconPadding = if (v) 0 else px(8)
            tooltipText = "붙여넣기"
        }
        updateOverlayInsets()
    }

    /** 칸의 크기나 자리가 바뀌었을 때 (분할 보기를 켜고 끄거나 구분선을 끌 때) */
    fun refreshOverlayInsets() = updateOverlayInsets()

    private fun updateOverlayInsets() {
        fun barsHeight(o: ViewGroup) = (0 until o.childCount).map { o.getChildAt(it) }
            .filter { it === shapeBar || it === optionBar || it === formatBar || it.id == R.id.reviewBar }
            .sumOf { if (it.visibility == View.VISIBLE) it.height else 0 }.toFloat()
        val top = barsHeight(topOverlay)
        val bottom = barsHeight(bottomOverlay)
        // 줄은 문서 틀 위·아래에 겹쳐 뜨므로, 칸마다 그 줄에 가려지는 만큼만 (위아래 분할이면 아래 칸만 아래 줄에 가려진다)
        val frame = docFrame
        for (v in allViews()) {
            val pos = offsetIn(v, frame)
            val vTop = pos.y
            val vBottom = pos.y + v.height
            v.topInset = (top - vTop).coerceIn(0f, v.height.toFloat())
            v.bottomInset = (vBottom - (frame.height - bottom)).coerceIn(0f, v.height.toFloat())
        }
    }

    /** [v]의 왼쪽 위가 [ancestor] 안 어디인지 */
    private fun offsetIn(v: View, ancestor: View): android.graphics.PointF {
        var x = 0f
        var y = 0f
        var cur: View = v
        while (cur !== ancestor) {
            x += cur.left + cur.translationX
            y += cur.top + cur.translationY
            cur = cur.parent as? View ?: break
        }
        return android.graphics.PointF(x, y)
    }


    // ================= 지우개 · 선택 옵션 줄 =================
    // 지우개나 선택 도구를 누르면 도구막대 위로 옵션 줄이 올라오고, 하나 고르면 사라진다.
    // 도구 버튼 아이콘은 지금 고른 방식을 보여 준다.

    /** 지금 쓰는 옵션 줄: 가로 줄([optionBarH]) 또는 세로 줄([optionBarV]). 칸들은 [optionRow]에 */
    private lateinit var optionBar: View
    private lateinit var optionBarH: View
    private lateinit var optionBarV: View
    private lateinit var optionRow: LinearLayout
    /** 옵션 줄이 떠 있는 도구 (없으면 null) */
    private var optionTool: Tool? = null

    fun setupEraserTools() {
        optionBar = findViewById(R.id.toolOptionBar)
        optionRow = findViewById(R.id.toolOptions)
        docView.eraserMode = prefs.getString("eraserMode", null)
            ?.let { n -> EraserMode.entries.firstOrNull { it.name == n } } ?: EraserMode.STROKE
        docView.eraseHlOnly = prefs.getBoolean("eraseHlOnly", false)
        docView.scribbleErase = prefs.getBoolean("scribbleErase", true)
        docView.palmErase = prefs.getBoolean("palmErase", true)
        val buttonAction = PenInputRules.ButtonAction.named(prefs.getString("penButton", null))
        val tilt = prefs.getBoolean("penTilt", true)
        for (v in allViews()) {
            v.penButtonAction = buttonAction
            v.penTilt = tilt
        }
        docView.noteColor = prefs.getInt("noteColor", StickyNote.COLORS[0])
    }

    /** 지우개 버튼: 획/영역 표시 + 형광펜만이면 형광색 지우개 */
    private fun updateEraserIcon() {
        val area = docView.eraserMode == EraserMode.AREA
        val mark = getDrawable(if (area) R.drawable.ic_eraser_mark_area else R.drawable.ic_eraser_mark_stroke)
        val body = getDrawable(if (docView.eraseHlOnly) R.drawable.ic_eraser_body_hl else R.drawable.ic_eraser_body)
        val button = toolButtons.getValue(Tool.ERASER)
        button.setImageDrawable(LayerDrawable(arrayOf(mark, body)))
        button.contentDescription = (if (area) "영역 지우개" else "획 지우개") + if (docView.eraseHlOnly) " (형광펜만)" else ""
    }

    /** 선택 버튼: 자유 선택이면 올가미, 네모 선택이면 점선 네모, 대상 선택이면 손가락 */
    private fun updateLassoIcon() {
        val button = toolButtons.getValue(Tool.LASSO)
        when {
            docView.lassoTap -> { button.setImageResource(R.drawable.ic_select_tap); button.contentDescription = "대상 선택" }
            docView.lassoRect -> { button.setImageResource(R.drawable.ic_select_rect); button.contentDescription = "네모 선택" }
            else -> { button.setImageResource(R.drawable.ic_lasso); button.contentDescription = "자유 선택" }
        }
    }

    private fun showOptionBar(t: Tool) {
        optionRow.removeAllViews()
        when (t) {
            Tool.PEN -> {
                for (style in PenStyle.entries) addOption(penIcon(style), style.label, docView.penStyle == style) { setPenStyle(style) }
                addOptionSeparator()
                // 손떨림 보정: 누르면 끔·약하게·보통·강하게가 펼쳐진다
                val level = docView.penSmoothing
                addOption(R.drawable.ic_stabilizer, "손떨림 보정 ▾", level > 0, closeBar = false) { showSmoothingPopup(it) }
            }
            Tool.ERASER -> {
                val hl = docView.eraseHlOnly
                addOption(R.drawable.ic_eraser_stroke, "획 지우개", docView.eraserMode == EraserMode.STROKE) {
                    setEraserMode(EraserMode.STROKE)
                }
                addOption(R.drawable.ic_eraser_area, "영역 지우개", docView.eraserMode == EraserMode.AREA) {
                    setEraserMode(EraserMode.AREA)
                }
                addOptionSeparator()
                addOption(R.drawable.ic_eraser_body_hl, "형광펜만", hl) {
                    docView.eraseHlOnly = !hl
                    prefs.edit().putBoolean("eraseHlOnly", !hl).apply()
                    updateEraserIcon()
                }
                addOptionSeparator()
                // 펜을 든 채 지우기: 좌우로 긁기, 손바닥으로 문지르기 (켜고 끄기)
                addOption(R.drawable.ic_scribble_erase, "긁어서 지우기", docView.scribbleErase) {
                    docView.scribbleErase = !docView.scribbleErase
                    prefs.edit().putBoolean("scribbleErase", docView.scribbleErase).apply()
                    toast(if (docView.scribbleErase) "펜으로 좌우나 위아래로 마구 긁으면 긁은 자리가 지워집니다." else "긁어서 지우기를 껐습니다.")
                }
                if (docView.fingerDrawing) {
                    addOption(R.drawable.ic_palm_erase, "손바닥 지우기", docView.palmErase) {
                        docView.palmErase = !docView.palmErase
                        prefs.edit().putBoolean("palmErase", docView.palmErase).apply()
                        toast(if (docView.palmErase) "손가락으로 쓰기 중 손바닥으로 문지르면 지워집니다." else "손바닥 지우기를 껐습니다.")
                    }
                } else {
                    // 손가락으로 쓰기가 꺼져 있으면 동작하지 않으므로 흐리게 두고, 누르면 켤지 묻는다
                    addOption(R.drawable.ic_palm_erase, "손바닥 지우기", false, closeBar = false, dimmed = true) { askEnableFingerForPalm() }
                }
                addOptionSeparator()
                addOption(R.drawable.ic_eraser_page, if (hl) "쪽 형광펜 모두 지우기" else "쪽 전체 지우기", false) {
                    confirmClearPage()
                }
            }
            Tool.FILL -> {
                val erasing = docView.fillErasing
                val free = docView.fillMode == FillMode.FREE
                addOption(R.drawable.ic_fill_bucket, "칠하기", !erasing && !free) { setFillMode(FillMode.BUCKET) }
                addOption(R.drawable.ic_fill_free, "자유 영역", !erasing && free) { setFillMode(FillMode.FREE) }
                // 채우기만 지우는 지우개: 누르면 획 지우개 / 영역 지우개가 펼쳐진다
                addOption(tapeEraserIcon(docView.fillEraseMode), "지우개 ▾", erasing, closeBar = false) { showFillEraserPopup(it) }
                addOptionSeparator()
                if (!erasing && free) {
                    // 자유 영역만: 손떨림 보정 단계, 다각형 보정 켜고 끄기
                    addOption(R.drawable.ic_stabilizer, "손떨림 보정 ▾", docView.fillSmoothing > 0, closeBar = false) {
                        showFillSmoothingPopup(it)
                    }
                    addOption(R.drawable.ic_fill_polygon, "다각형 보정", docView.fillPolygon) {
                        docView.fillPolygon = !docView.fillPolygon
                        prefs.edit().putBoolean("fillPolygon", docView.fillPolygon).apply()
                        toast(if (docView.fillPolygon) "거의 곧게 그린 변을 곧게 폅니다." else "그린 모양 그대로 채웁니다.")
                    }
                    addOptionSeparator()
                }
                if (!erasing) {
                    // 무늬: 한 칸에 지금 무늬만 보이고, 누르면 모두 펼쳐진다
                    val p = docView.fillPattern
                    addOption(FillPatternDrawable(p, docView.fillColor, resources.displayMetrics.density), "${p.label} ▾", false,
                        closeBar = false) { showFillPatternPopup(it) }
                }
            }
            Tool.TAPE -> {
                val erasing = docView.tapeErasing
                addOption(R.drawable.ic_tape_pen, "펜", !erasing && !docView.tapeRect) { setTapeMode(rect = false) }
                addOption(R.drawable.ic_tape_rect, "사각", !erasing && docView.tapeRect) { setTapeMode(rect = true) }
                // 테이프만 지우는 지우개: 누르면 획 지우개 / 영역 지우개가 펼쳐진다
                addOption(tapeEraserIcon(docView.tapeEraseMode), "지우개 ▾", erasing, closeBar = false) { showTapeEraserPopup(it) }
                addOptionSeparator()
                if (!erasing && !docView.tapeRect) {
                    // 펜 테이프만: 곧게 펴기, 글자 크기에 맞추기 (켜고 끄기)
                    addOption(R.drawable.ic_ruler, "직선 보정", docView.tapeStraight) {
                        docView.tapeStraight = !docView.tapeStraight
                        prefs.edit().putBoolean("tapeStraight", docView.tapeStraight).apply()
                        toast(if (docView.tapeStraight) "직선 자동 보정을 켰습니다." else "직선 자동 보정을 껐습니다.")
                    }
                    addOption(R.drawable.ic_tape_fit, "글자 크기 맞춤", docView.tapeFitText) {
                        docView.tapeFitText = !docView.tapeFitText
                        prefs.edit().putBoolean("tapeFitText", docView.tapeFitText).apply()
                        toast(if (docView.tapeFitText) "테이프 굵기를 글자 크기에 맞춥니다." else "고른 굵기 그대로 붙입니다.")
                    }
                    addOptionSeparator()
                }
                if (!erasing) {
                    // 무늬: 한 칸에 지금 무늬만 보이고, 누르면 다섯 가지가 펼쳐진다
                    val p = docView.tapePattern
                    addOption(TapePatternDrawable(p, docView.tapeColor, resources.displayMetrics.density), "${p.label} ▾", false,
                        closeBar = false) { showPatternPopup(it) }
                    addOptionSeparator()
                }
                // 아래 세 칸은 보고 있는 쪽에만 해당한다는 표시
                addOptionLabel("현재\n페이지:")
                addOption(R.drawable.ic_tape_hide, "모두 가리기", false) { revealPageTapes(false) }
                addOption(R.drawable.ic_tape_show, "모두 보이기", false) { revealPageTapes(true) }
                addOption(R.drawable.ic_eraser_page, "모두 지우기", false) { clearPageTapes() }
            }
            else -> return
        }
        optionTool = t
        optionBar.visibility = View.VISIBLE
        // 툴바 쪽에서 살짝 밀려 나오며 나타난다 (툴바가 위면 위에서 내려온다)
        optionBar.animate().cancel()
        optionBar.alpha = 0f
        val shift = 12 * resources.displayMetrics.density
        optionBar.translationX = when (dock.side) { ToolbarSide.LEFT -> -shift; ToolbarSide.RIGHT -> shift; else -> 0f }
        optionBar.translationY = when (dock.side) { ToolbarSide.TOP -> -shift; ToolbarSide.BOTTOM -> shift; else -> 0f }
        optionBar.animate().alpha(1f).translationX(0f).translationY(0f).setDuration(150).start()
    }

    fun hideOptionBar() {
        if (optionTool == null) return
        optionTool = null
        optionBar.animate().cancel()
        optionBar.visibility = View.GONE
    }

    /** 펜 종류 아이콘: 그 펜의 몸통 + 지금 펜 색의 S자 곡선 (툴바 펜 버튼과 같은 모양) */
    private fun penIcon(style: PenStyle): android.graphics.drawable.Drawable {
        val mark = getDrawable(R.drawable.ic_pen_mark)!!.mutate()
        mark.setTint(docView.penColor)
        return LayerDrawable(arrayOf(mark, getDrawable(style.bodyIcon)!!)).apply { setId(0, R.id.tool_mark) }
    }

    /** 펜 버튼: 고른 펜 종류의 몸통 */
    private fun updatePenIcon() {
        val button = toolButtons.getValue(Tool.PEN)
        button.setImageDrawable(penIcon(docView.penStyle))
        button.contentDescription = docView.penStyle.label
        updateToolMarks()
    }

    private fun setPenStyle(style: PenStyle) {
        docView.penStyle = style
        prefs.edit().putString("penStyle", style.name).apply()
        applyToolWidth(Tool.PEN)
        updatePenIcon()
        buildColors()  // 굵기 칸이 그 펜 종류의 칸으로
    }

    /** 손떨림 보정 단계 고르기 */
    private fun showSmoothingPopup(anchor: View) {
        showFlyout(anchor, PenSmoothing.labels.mapIndexed { i, label ->
            Triple(getDrawable(R.drawable.ic_stabilizer)!!, label, docView.penSmoothing == i)
        }, title = "손떨림\n보정") { i ->
            docView.penSmoothing = i
            prefs.edit().putInt("penSmoothing", i).apply()
            toast(if (i == 0) "손떨림 보정을 껐습니다." else "손떨림 보정: ${PenSmoothing.labels[i]}")
        }
    }

    private fun setEraserMode(m: EraserMode) {
        docView.eraserMode = m
        prefs.edit().putString("eraserMode", m.name).apply()
        updateEraserIcon()
    }

    private fun setHlStraight(on: Boolean) {
        docView.hlStraight = on
        // 자유·직선을 고르면 글자 기반 주석은 끈다
        docView.textMark = TextMark.NONE
        prefs.edit().putBoolean("hlStraight", on).putString("textMark", TextMark.NONE.name).apply()
        updateHighlighterIcon()
    }

    private fun setTextMark(m: TextMark) {
        docView.textMark = m
        prefs.edit().putString("textMark", m.name).apply()
        updateHighlighterIcon()
    }

    /** 옵션 ▸ 글자 기반 주석이 켜져 있는가 (처음 설치하면 켜짐. 꺼 두면 형광펜 펼침 창에 글자 모드가 안 나온다) */
    fun textMarkupOn() = prefs.getBoolean("textMarkup", true)

    /** 옵션에서 글자 기반 주석을 켜거나 껐을 때: 끄면 보통 형광펜으로 돌아간다 */
    fun onTextMarkupChanged() {
        if (!textMarkupOn()) {
            docView.textMark = TextMark.NONE
            prefs.edit().putString("textMark", TextMark.NONE.name).apply()
        }
        updateHighlighterIcon()
    }

    /** 형광펜 버튼: 직선(줄자)이면 오른쪽 아래에 작은 자, 글자 기반 주석이면 작은 'T' */
    private fun updateHighlighterIcon() {
        val button = toolButtons.getValue(Tool.HIGHLIGHTER)
        val mark = docView.textMark
        button.setImageResource(
            when {
                mark != TextMark.NONE -> R.drawable.ic_highlighter_text
                docView.hlStraight -> R.drawable.ic_highlighter_ruler
                else -> R.drawable.ic_highlighter
            }
        )
        button.contentDescription = when {
            mark != TextMark.NONE -> mark.label
            docView.hlStraight -> "직선 형광펜"
            else -> "형광펜"
        }
        updateToolMarks()
    }

    // ================= 자 =================

    private lateinit var rulerButton: ImageButton

    private fun rulerIcon(m: RulerController.Mode) = when (m) {
        RulerController.Mode.MEASURE -> R.drawable.ic_ruler
        RulerController.Mode.PROTRACTOR -> R.drawable.ic_protractor
    }

    /** 자 단추: 켜져 있으면 눌린 모양으로, 아이콘은 지금 모양 (꺼져 있으면 마지막으로 쓴 모양) */
    fun updateRulerButton() {
        if (!::rulerButton.isInitialized) return
        val ruler = docView.ruler
        val m = RulerController.Mode.named(prefs.getString("rulerMode", null))
        val shown = if (ruler.active) ruler.mode else m
        rulerButton.setImageResource(rulerIcon(shown))
        rulerButton.isSelected = ruler.active
        rulerButton.contentDescription = if (ruler.active) shown.label else "자"
    }

    fun setupRuler() {
        rulerButton = findViewById(R.id.toolRuler)
        rulerButton.setOnClickListener {
            // 열려 있을 때 누르면 닫는다 (펼침 창 공통 규칙)
            if (!flyoutJustClosed(rulerButton)) showRulerFlyout(rulerButton)
        }
        updateRulerButton()
    }

    private fun showRulerFlyout(anchor: View) {
        fun item(res: Int, label: String, on: Boolean) = Triple(getDrawable(res)!!, label, on)
        val ruler = docView.ruler
        val modes = RulerController.Mode.entries
        val items = modes.map { m -> item(rulerIcon(m), m.label, ruler.active && ruler.mode == m) } +
            if (ruler.active) listOf(item(R.drawable.ic_close, "자 끄기", false)) else emptyList()
        showFlyout(anchor, items, separatorBefore = setOf(modes.size)) { i ->
            if (i < modes.size) {
                prefs.edit().putString("rulerMode", modes[i].name).apply()
                docView.ruler.show(modes[i])
                toast(
                    when (modes[i]) {
                        RulerController.Mode.MEASURE -> "눈금자입니다. 한 손가락으로 옮기고 두 손가락으로 돌리거나 키웁니다. 가장자리를 따라 그으면 곧은 선과 길이가 나옵니다."
                        RulerController.Mode.PROTRACTOR -> "각도기입니다. 두 손가락으로 돌리고 키웁니다. 가운데 고리에서 펜을 대고 끌면 정수 도 선이 그어지고, 밑변·둥근 가장자리를 따라서도 그을 수 있습니다."
                    }
                )
            } else docView.ruler.hide()
            updateRulerButton()
        }
    }

    /** 선택 방식: 0 자유 선택, 1 네모 선택, 2 대상 선택 */
    private fun setLassoMode(mode: Int) {
        docView.lassoRect = mode == 1
        docView.lassoTap = mode == 2
        prefs.edit().putInt("lassoMode", mode).apply()
        updateLassoIcon()
    }

    private fun toast(msg: String) = Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show()

    /** 테이프 붙이기 (펜 / 사각). 테이프 지우개는 끈다 */
    private fun setTapeMode(rect: Boolean) {
        docView.tapeRect = rect
        docView.tapeErasing = false
        prefs.edit().putBoolean("tapeRect", rect).putBoolean("tapeErasing", false).apply()
        updateTapeIcon()
        applyToolWidth(Tool.TAPE)
        buildColors()  // 네모 테이프는 굵기 칸이 없다
    }

    /** 테이프만 지우는 지우개로 (획 / 영역) */
    private fun setTapeEraser(mode: EraserMode) {
        docView.tapeErasing = true
        docView.tapeEraseMode = mode
        prefs.edit().putBoolean("tapeErasing", true).putString("tapeEraseMode", mode.name).apply()
        updateTapeIcon()
        applyToolWidth(Tool.TAPE)
        buildColors()  // 지우개는 색 칸 없이 크기 칸
    }

    private fun tapeEraserIcon(mode: EraserMode) =
        getDrawable(if (mode == EraserMode.AREA) R.drawable.ic_eraser_area else R.drawable.ic_eraser_stroke)!!

    /**
     * 테이프 버튼: 테이프 그림 (색 자국은 지금 테이프 색). 테이프 지우개면 오른쪽 아래에 작은 지우개를 겹친다
     */
    private fun updateTapeIcon() {
        val button = toolButtons.getValue(Tool.TAPE)
        // 펜·사각: 위에 테이프, 아래에 테이프 색의 S자 띠·네모 (펜 버튼처럼). 지우개: 테이프 색 테이프 + 작은 지우개
        val layers = when {
            docView.tapeErasing -> mutableListOf(
                getDrawable(R.drawable.ic_tape_mark)!!, getDrawable(R.drawable.ic_tape_body)!!, getDrawable(R.drawable.ic_eraser_body)!!)
            docView.tapeRect -> mutableListOf(getDrawable(R.drawable.ic_tape_mark_rect)!!, getDrawable(R.drawable.ic_tape_body_top)!!)
            else -> mutableListOf(getDrawable(R.drawable.ic_tape_mark_pen)!!, getDrawable(R.drawable.ic_tape_body_top)!!)
        }
        val icon = LayerDrawable(layers.toTypedArray())
        icon.setId(0, R.id.tool_mark)
        if (docView.tapeErasing) {
            val d = resources.displayMetrics.density
            // 테이프는 왼쪽 위로 조금 작게, 지우개는 오른쪽 아래에 작게
            icon.setLayerInset(0, 0, 0, (5 * d).toInt(), (5 * d).toInt())
            icon.setLayerInset(1, 0, 0, (5 * d).toInt(), (5 * d).toInt())
            icon.setLayerInset(2, (11 * d).toInt(), (11 * d).toInt(), 0, 0)
        }
        button.setImageDrawable(icon)
        button.contentDescription = when {
            docView.tapeErasing -> if (docView.tapeEraseMode == EraserMode.AREA) "테이프 영역 지우개" else "테이프 획 지우개"
            docView.tapeRect -> "사각 테이프"
            else -> "펜 테이프"
        }
        updateToolMarks()
    }

    /** 테이프 지우개 칸: 획 지우개 / 영역 지우개를 펼쳐 고른다 */
    private fun showTapeEraserPopup(anchor: View) {
        val erasing = docView.tapeErasing
        val modes = listOf(EraserMode.STROKE to "획 지우개", EraserMode.AREA to "영역 지우개")
        showFlyout(anchor, modes.map { (m, label) ->
            Triple(tapeEraserIcon(m), label, erasing && docView.tapeEraseMode == m)
        }) { i -> setTapeEraser(modes[i].first) }
    }

    /** 보고 있는 쪽의 테이프를 모두 보이게 하거나 모두 가린다 */
    private fun revealPageTapes(reveal: Boolean) {
        val page = docView.currentPage() + 1
        if (docView.revealPageTapes(reveal) == 0) toast("${page}쪽에는 테이프가 없습니다.")
    }

    /** 보고 있는 쪽의 테이프를 모두 지운다. 실행 취소로 되돌릴 수 있으니 묻지 않는다 */
    private fun clearPageTapes() {
        val page = docView.currentPage()
        if (page < 0) return
        val n = docView.clearPageTapes()
        toast(if (n == 0) "${page + 1}쪽에는 지울 테이프가 없습니다." else "테이프 ${n}개를 지웠습니다. 실행 취소로 되돌릴 수 있습니다.")
    }

    /** 테이프 무늬 고르기: 다섯 가지가 펼쳐진다 */
    private fun showPatternPopup(anchor: View) {
        val d = resources.displayMetrics.density
        showFlyout(anchor, TapePattern.entries.map { p ->
            Triple(TapePatternDrawable(p, docView.tapeColor, d), p.label, p == docView.tapePattern)
        }) { i ->
            val p = TapePattern.entries[i]
            docView.tapePattern = p
            prefs.edit().putString("tapePattern", p.name).apply()
        }
    }

    // ----- 채우기 -----

    /** 칠하기 / 자유 영역. 채우기 지우개는 끈다 */
    private fun setFillMode(m: FillMode) {
        docView.fillMode = m
        docView.fillErasing = false
        prefs.edit().putString("fillMode", m.name).putBoolean("fillErasing", false).apply()
        updateFillIcon()
        buildColors()
        toast(if (m == FillMode.BUCKET) "도형 안을 누르면 그 안을 채웁니다." else "닫힌 곡선을 그리면 가장 바깥 선 안을 모두 채웁니다.")
    }

    /** 채우기만 지우는 지우개로 (획 / 영역) */
    private fun setFillEraser(mode: EraserMode) {
        docView.fillErasing = true
        docView.fillEraseMode = mode
        prefs.edit().putBoolean("fillErasing", true).putString("fillEraseMode", mode.name).apply()
        updateFillIcon()
        applyToolWidth(Tool.FILL)
        buildColors()  // 지우개는 색 칸 없이 크기 칸
    }

    private fun showFillEraserPopup(anchor: View) {
        val erasing = docView.fillErasing
        val modes = listOf(EraserMode.STROKE to "획 지우개", EraserMode.AREA to "영역 지우개")
        showFlyout(anchor, modes.map { (m, label) ->
            Triple(tapeEraserIcon(m), label, erasing && docView.fillEraseMode == m)
        }) { i -> setFillEraser(modes[i].first) }
    }

    private fun showFillSmoothingPopup(anchor: View) {
        showFlyout(anchor, PenSmoothing.labels.mapIndexed { i, label ->
            Triple(getDrawable(R.drawable.ic_stabilizer)!!, label, docView.fillSmoothing == i)
        }, title = "손떨림\n보정") { i ->
            docView.fillSmoothing = i
            prefs.edit().putInt("fillSmoothing", i).apply()
            toast(if (i == 0) "손떨림 보정을 껐습니다." else "손떨림 보정: ${PenSmoothing.labels[i]}")
        }
    }

    /** 채우기 무늬 고르기: 색(꽉 채움)과 무늬들이 펼쳐진다 */
    private fun showFillPatternPopup(anchor: View) {
        val d = resources.displayMetrics.density
        showFlyout(anchor, FillPattern.entries.map { p ->
            Triple(FillPatternDrawable(p, docView.fillColor, d), p.label, p == docView.fillPattern)
        }, separatorBefore = setOf(1)) { i ->
            val p = FillPattern.entries[i]
            docView.fillPattern = p
            prefs.edit().putString("fillPattern", p.name).apply()
            updateFillIcon()
        }
    }

    /**
     * 채우기 버튼: 칠하기면 페인트 통, 자유 영역이면 닫힌 곡선 (색 자국은 지금 채우기 색).
     * 채우기 지우개면 오른쪽 아래에 작은 지우개를 겹친다
     */
    private fun updateFillIcon() {
        val button = toolButtons.getValue(Tool.FILL)
        val free = docView.fillMode == FillMode.FREE
        val layers = mutableListOf(
            getDrawable(if (free) R.drawable.ic_fill_free_mark else R.drawable.ic_fill_mark)!!,
            getDrawable(if (free) R.drawable.ic_fill_free_body else R.drawable.ic_fill_body)!!,
        )
        if (docView.fillErasing) layers.add(getDrawable(R.drawable.ic_eraser_body)!!)
        val icon = LayerDrawable(layers.toTypedArray())
        icon.setId(0, R.id.tool_mark)
        if (docView.fillErasing) {
            val d = resources.displayMetrics.density
            icon.setLayerInset(0, 0, 0, (5 * d).toInt(), (5 * d).toInt())
            icon.setLayerInset(1, 0, 0, (5 * d).toInt(), (5 * d).toInt())
            icon.setLayerInset(2, (11 * d).toInt(), (11 * d).toInt(), 0, 0)
        }
        button.setImageDrawable(icon)
        button.contentDescription = when {
            docView.fillErasing -> if (docView.fillEraseMode == EraserMode.AREA) "채우기 영역 지우개" else "채우기 획 지우개"
            free -> "자유 영역 채우기"
            else -> "칠하기"
        }
        updateToolMarks()
    }

    // ----- 펼침 창 (롤오버) -----
    // 형광펜·선택·레이저 버튼, 도형 줄·옵션 줄의 '▾' 칸을 누르면 그 칸 옆(문서 쪽)에 고를 것들이 펼쳐진다.
    // 창 밖을 누르면 닫히고, 그 누름은 아래(문서·툴바)로 그대로 전달된다 (바로 필기할 수 있게)

    private var flyout: android.widget.PopupWindow? = null
    private var flyoutAnchor: View? = null
    private var flyoutClosedAt = 0L

    /**
     * [anchor] 옆에 [items](아이콘, 이름, 고른 것인지)를 툴바 방향으로 늘어놓은 창을 띄운다.
     * 고르면 창과 옵션 줄을 닫고 [onPick]에 몇 번째인지 넘긴다. [title]은 앞에 붙는 작은 설명,
     * [separatorBefore]는 그 앞에 나누는 막대를 넣을 칸 번호들 (따로 고르는 묶음을 나눌 때)
     */
    private fun showFlyout(
        anchor: View, items: List<Triple<android.graphics.drawable.Drawable, String, Boolean>>,
        title: String? = null, separatorBefore: Set<Int> = emptySet(), onPick: (Int) -> Unit,
    ) {
        closeFlyout()
        val d = resources.displayMetrics.density
        val vertical = dock.side.vertical
        val box = LinearLayout(activity).apply {
            orientation = if (vertical) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = if (vertical) android.view.Gravity.CENTER_HORIZONTAL else android.view.Gravity.CENTER_VERTICAL
            val pd = (4 * d).toInt()
            setPadding(pd, pd, pd, pd)
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(MaterialColors.getColor(anchor, com.google.android.material.R.attr.colorSurfaceContainerHigh))
            }
        }
        val popup = android.widget.PopupWindow(box, LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, false)
        popup.isOutsideTouchable = true
        popup.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
        popup.elevation = 8 * d
        if (title != null) box.addView(optionLabel(title, (4 * d).toInt()))
        items.forEachIndexed { i, (icon, label, selected) ->
            if (i in separatorBefore) box.addView(optionSeparator())
            box.addView(optionItem(icon, label, selected) {
                popup.dismiss()
                hideOptionBar()
                onPick(i)
            })
        }
        popup.setOnDismissListener {
            flyoutClosedAt = android.os.SystemClock.uptimeMillis()
            if (flyout === popup) flyout = null
        }
        flyout = popup
        flyoutAnchor = anchor
        showBeside(popup, box, anchor)
    }

    fun closeFlyout() {
        flyout?.dismiss()
    }

    /** [anchor]의 창이 방금(이 누름으로) 닫혔는가. 그렇다면 다시 열지 않는다 (한 번 더 누르면 닫히게) */
    private fun flyoutJustClosed(anchor: View) =
        flyoutAnchor === anchor && android.os.SystemClock.uptimeMillis() - flyoutClosedAt < 600

    /** 형광펜·선택·레이저 버튼의 펼침 창 */
    private fun showToolFlyout(t: Tool, anchor: View) {
        fun item(res: Int, label: String, on: Boolean) = Triple(getDrawable(res)!!, label, on)
        when (t) {
            Tool.HIGHLIGHTER -> {
                val plain = docView.textMark == TextMark.NONE
                val items = arrayListOf(
                    item(R.drawable.ic_highlighter, "자유 형광펜", plain && !docView.hlStraight),
                    item(R.drawable.ic_ruler, "직선 형광펜", plain && docView.hlStraight),
                )
                // 옵션에서 켠 글자 기반 주석: 형광펜으로 PDF 글자를 끌어 글줄에 맞춰 칠하기·밑줄·취소선·복사
                val marks = if (textMarkupOn()) TextMark.entries.filter { it != TextMark.NONE } else emptyList()
                for (m in marks) {
                    val icon = when (m) {
                        TextMark.HIGHLIGHT -> R.drawable.ic_text_highlight
                        TextMark.UNDERLINE -> R.drawable.ic_text_underline
                        TextMark.STRIKE -> R.drawable.ic_text_strike
                        else -> R.drawable.ic_text_copy
                    }
                    items.add(item(icon, m.label, docView.textMark == m))
                }
                showFlyout(anchor, items, separatorBefore = if (marks.isEmpty()) emptySet() else setOf(2)) { i ->
                    if (i < 2) setHlStraight(i == 1) else setTextMark(marks[i - 2])
                }
            }
            Tool.LASSO -> showFlyout(anchor, listOf(
                item(R.drawable.ic_lasso, "자유 선택", !docView.lassoRect && !docView.lassoTap),
                item(R.drawable.ic_select_rect, "네모 선택", docView.lassoRect),
                item(R.drawable.ic_select_tap, "대상 선택", docView.lassoTap),
                // 선택 방식이 아니라 한 번 하는 동작: 영역 스크린샷, 그리고 오답 ▸ 오답 담기와 같은 것
                item(R.drawable.ic_screenshot, "영역 스크린샷", false),
                item(R.drawable.ic_wrong_note, "오답 담기", false),
            ), separatorBefore = setOf(3)) { i ->
                when (i) {
                    3 -> startShot()
                    4 -> startWrongPick()
                    else -> setLassoMode(i)
                }
            }
            Tool.LASER -> {
                // 레이저가 사라지는 시간 (색·굵기는 툴바)
                val secs = intArrayOf(1, 2, 3, 5)
                showFlyout(anchor, secs.map { sec -> item(R.drawable.ic_timer, "${sec}초", docView.laserFadeMs == sec * 1000L) },
                    title = "사라지는\n시간") { i ->
                    docView.laserFadeMs = secs[i] * 1000L
                    prefs.edit().putLong("laserFadeMs", docView.laserFadeMs).apply()
                }
            }
            else -> {}
        }
    }

    private fun showBeside(popup: android.widget.PopupWindow, box: View, anchor: View) =
        showPopupBeside(window, dock.side, popup, box, anchor)

    /**
     * 옵션 줄의 칸 하나 (아이콘 + 이름). 누르면 옵션 줄을 닫고 실행한다.
     * [closeBar]가 false면 옵션 줄을 둔 채 실행한다 (칸에서 창을 펼칠 때). onClick은 누른 칸을 받는다
     */
    private fun addOption(
        icon: Int, label: String, selected: Boolean, closeBar: Boolean = true, dimmed: Boolean = false, onClick: (View) -> Unit,
    ) = addOption(getDrawable(icon)!!, label, selected, closeBar, dimmed, onClick)

    private fun addOption(
        icon: android.graphics.drawable.Drawable, label: String, selected: Boolean,
        closeBar: Boolean = true, dimmed: Boolean = false, onClick: (View) -> Unit,
    ) {
        optionRow.addView(optionItem(icon, label, selected) {
            if (closeBar) hideOptionBar()
            onClick(it)
        }.also { if (dimmed) it.alpha = 0.38f })
    }

    /** 손바닥 지우기는 '손가락으로 쓰기'가 켜져 있을 때만 동작한다: 꺼져 있을 때 누르면 켤지 묻는다 */
    private fun askEnableFingerForPalm() {
        MaterialAlertDialogBuilder(activity)
            .setMessage("\"손가락으로 쓰기\"가 켜져 있을 때만 동작합니다.\n손가락으로 쓰기 옵션을 켜시겠습니까?")
            .setNegativeButton("취소", null)
            .setPositiveButton("켜기") { _, _ ->
                docView.fingerDrawing = true
                docView.palmErase = true
                prefs.edit().putBoolean("finger", true).putBoolean("palmErase", true).apply()
                toast("손가락으로 쓰기와 손바닥 지우기를 켰습니다.")
                if (optionTool == Tool.ERASER) showOptionBar(Tool.ERASER)
            }
            .show()
    }

    /** 아이콘 아래 이름이 붙은 칸 (옵션 줄, 무늬 고르기 창) */
    private fun optionItem(
        icon: android.graphics.drawable.Drawable, label: String, selected: Boolean, onClick: (View) -> Unit,
    ): View {
        val d = resources.displayMetrics.density
        // 세로 줄(툴바가 왼쪽·오른쪽)에서는 줄 폭이 넓어지지 않게 긴 이름을 두 줄로
        val vertical = dock.side.vertical
        val item = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            minimumWidth = (48 * d).toInt()
            setPadding((6 * d).toInt(), (5 * d).toInt(), (6 * d).toInt(), (3 * d).toInt())
            setBackgroundResource(R.drawable.bg_tool)
            isSelected = selected
            contentDescription = if (selected) "$label (선택됨)" else label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { if (vertical) bottomMargin = (1 * d).toInt() else marginEnd = (1 * d).toInt() }
            setOnClickListener { onClick(it) }
        }
        item.addView(ImageView(activity).apply {
            setImageDrawable(icon)
            layoutParams = LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        item.addView(TextView(activity).apply {
            text = label
            if (vertical) {
                maxLines = 2
                maxWidth = (60 * d).toInt()
                gravity = android.view.Gravity.CENTER
                setLineSpacing(0f, 0.9f)
            } else isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        return item
    }

    /** 옵션 줄의 작은 글 (뒤따르는 칸들이 어디에 해당하는지 알려 준다). 누를 수 없다 */
    private fun addOptionLabel(text: String) = optionRow.addView(optionLabel(text, (4 * resources.displayMetrics.density).toInt()))

    private fun optionLabel(text: String, gap: Int) = TextView(activity).apply {
        this.text = text
        gravity = android.view.Gravity.CENTER
        setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
        setTextColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant))
        setLineSpacing(0f, 0.9f)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { if (dock.side.vertical) bottomMargin = gap else marginEnd = gap }
    }

    /** 칸 묶음 사이 구분선 (가로 줄은 세로선, 세로 줄은 가로선) */
    private fun addOptionSeparator() = optionRow.addView(optionSeparator())

    /** 칸 묶음을 나누는 가는 막대 (가로 줄이면 세로 막대, 세로 줄이면 가로 막대) */
    private fun optionSeparator(): View {
        val d = resources.displayMetrics.density
        val vertical = dock.side.vertical
        return View(activity).apply {
            val long = (32 * d).toInt()
            val thin = (1 * d).toInt()
            layoutParams = (if (vertical) LinearLayout.LayoutParams(long, thin) else LinearLayout.LayoutParams(thin, long)).apply {
                if (vertical) { topMargin = (4 * d).toInt(); bottomMargin = (5 * d).toInt() }
                else { marginStart = (4 * d).toInt(); marginEnd = (5 * d).toInt() }
            }
            setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant))
        }
    }

    /** 보고 있는 쪽의 필기(형광펜만 켜 두었으면 형광펜만)를 모두 지운다. 실행 취소로 되돌릴 수 있다 */
    private fun confirmClearPage() {
        val inkDoc = ink() ?: return
        val page = docView.currentPage()
        if (page < 0) return
        val hl = docView.eraseHlOnly
        val (subj, obj) = if (hl) "형광펜이" to "형광펜을" else "필기가" to "필기를"
        if (inkDoc.pages[page].none { it.image == null && (!hl || it.tool == Tool.HIGHLIGHTER) }) {
            Toast.makeText(activity, "${page + 1}쪽에는 지울 ${subj} 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("${page + 1}쪽의 ${obj} 모두 지울까요?")
            .setMessage("실행 취소로 되돌릴 수 있습니다.")
            .setPositiveButton("지우기") { _, _ -> docView.clearPage(hl) }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 선택 상자 바로 위(자리가 없으면 아래)에 '삭제' 막대를 띄운다 */
    fun placeSelectionBar(rect: RectF?, count: Int) {
        // 선택이 없거나 선택 상자가 화면 밖으로 스크롤되면 숨긴다
        if (rect == null || !rect.intersects(0f, 0f, docView.width.toFloat(), docView.height.toFloat())) {
            selectionBar.visibility = View.GONE
            return
        }
        selectionBar.findViewById<TextView>(R.id.selectionCount).text = "${count}개 선택"
        // 그림만 골랐으면 색 버튼을 뺀다 (그림에는 색을 입힐 수 없음)
        selectionBar.findViewById<View>(R.id.selectionColor).visibility =
            if (docView.selectionColor != null) View.VISIBLE else View.GONE
        selectionBar.visibility = View.VISIBLE
        selectionBar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = selectionBar.measuredWidth
        val h = selectionBar.measuredHeight
        val gap = 8 * resources.displayMetrics.density
        val maxX = (docView.width - w - gap).coerceAtLeast(gap)
        val maxY = (docView.height - h - gap).coerceAtLeast(gap)
        var y = rect.top - h - gap
        if (y < gap) y = rect.bottom + gap
        // 문서 틀 안에서의 자리로 (분할 보기에서는 칸이 틀의 한쪽에 있다)
        val at = offsetIn(docView, docFrame)
        selectionBar.translationX = at.x + (rect.centerX() - w / 2f).coerceIn(gap, maxX)
        selectionBar.translationY = at.y + y.coerceIn(gap, maxY)
    }

    // ================= 도구 막대 =================

    private val penDefaults = intArrayOf(
        0xFF000000.toInt(), 0xFFE53935.toInt(), 0xFF1E63D6.toInt(), 0xFF2E9D48.toInt(), 0xFF8E24AA.toInt()
    )
    private val hlDefaults = intArrayOf(
        0xFFFFEB3B.toInt(), 0xFF9CFF57.toInt(), 0xFFFF8AB8.toInt(), 0xFF7FD8FF.toInt(), 0xFFFFB74D.toInt()
    )
    /** 채우기: 도형을 칠하는 옅은 색들 (곱하기로 칠해 선·글자가 비친다) */
    private val fillDefaults = intArrayOf(
        0xFFFFE082.toInt(), 0xFFA5D6A7.toInt(), 0xFF90CAF9.toInt(), 0xFFF8BBD0.toInt(), 0xFFCE93D8.toInt()
    )
    /** 테이프: 마스킹 테이프 같은 옅은 색들 */
    private val tapeDefaults = intArrayOf(
        0xFFF6C744.toInt(), 0xFF7FC8F8.toInt(), 0xFFF48FB1.toInt(), 0xFF81C784.toInt(), 0xFFB0B0B0.toInt()
    )
    /** 레이저: 확 눈에 띄는 빨강이 기본 */
    private val laserDefaults = intArrayOf(
        0xFFFF1744.toInt(), 0xFF00E676.toInt(), 0xFF2979FF.toInt(), 0xFFFFEA00.toInt(), 0xFFF500F5.toInt()
    )

    /** 도구막대의 색 칸 (굿노트처럼 칸마다 색을 바꿔 넣을 수 있음). active 칸의 색이 지금 쓰는 색 */
    private class ColorSlots(val key: String, val slots: IntArray, var active: Int) {
        val color get() = slots[active]
    }

    private lateinit var penSlots: ColorSlots
    private lateinit var hlSlots: ColorSlots
    private lateinit var laserSlots: ColorSlots
    private lateinit var tapeSlots: ColorSlots
    private lateinit var fillSlots: ColorSlots

    private fun slotsOf(t: Tool) = when (t) {
        Tool.HIGHLIGHTER -> hlSlots
        Tool.FILL -> fillSlots
        Tool.LASER -> laserSlots
        Tool.TAPE -> tapeSlots
        else -> penSlots
    }

    private fun loadSlots(key: String, defaults: IntArray): ColorSlots {
        val saved = prefs.getString("${key}Slots", null)?.split(',')?.mapNotNull { it.toIntOrNull() }
        val slots = if (saved?.size == defaults.size) saved.toIntArray() else defaults.copyOf()
        var active = prefs.getInt("${key}Active", -1)
        if (active !in slots.indices) {
            // 칸 기능 이전 버전에서 고른 색을 이어받는다
            val legacy = prefs.getInt("${key}Color", slots[0])
            active = slots.indexOf(legacy).takeIf { it >= 0 } ?: 0
            slots[active] = legacy
        }
        return ColorSlots(key, slots, active)
    }

    private fun saveSlots(s: ColorSlots) {
        prefs.edit()
            .putString("${s.key}Slots", s.slots.joinToString(","))
            .putInt("${s.key}Active", s.active)
            .putInt("${s.key}Color", s.color)
            .apply()
    }

    private fun applyToolColor(t: Tool) {
        when (t) {
            Tool.HIGHLIGHTER -> docView.hlColor = hlSlots.color
            Tool.LASER -> docView.laserColor = laserSlots.color
            Tool.FILL -> {
                docView.fillColor = fillSlots.color
                // 무늬 칸이 떠 있으면 새 색으로
                if (optionTool == Tool.FILL) showOptionBar(Tool.FILL)
            }
            Tool.TAPE -> {
                docView.tapeColor = tapeSlots.color
                // 무늬 칸이 떠 있으면 새 색으로
                if (optionTool == Tool.TAPE) showOptionBar(Tool.TAPE)
            }
            else -> {
                docView.penColor = penSlots.color
                // 글 상자를 치는 중이면 고른 글자(없으면 이어서 칠 글자)의 색을 바꾼다
                if (textEditor.isEditing) textEditor.setTextColor(penSlots.color)
            }
        }
    }

    private fun recentColors(t: Tool): List<Int> =
        prefs.getString("${slotsOf(t).key}Recent", null)?.split(',')?.mapNotNull { it.toIntOrNull() } ?: emptyList()

    private fun addRecent(t: Tool, c: Int) {
        val list = (listOf(c) + recentColors(t).filter { it != c }).take(ColorPalette.COLUMNS)
        prefs.edit().putString("${slotsOf(t).key}Recent", list.joinToString(",")).apply()
    }

    /** 선택한 칸의 색을 바꾸는 색 고르기 창 */
    private fun openColorPicker(t: Tool, anchor: View) {
        val s = slotsOf(t)
        ColorPickerPopup(activity, s.color, recentColors(t)) { c, done ->
            s.slots[s.active] = c
            applyToolColor(t)
            buildColors()
            if (done) {
                saveSlots(s)
                addRecent(t, c)
            }
        }.show(anchor, dock.side)
    }

    fun setupTools() {
        toolButtons = mapOf(
            Tool.PEN to findViewById(R.id.toolPen),
            Tool.SHAPE to findViewById(R.id.toolShape),
            Tool.HIGHLIGHTER to findViewById(R.id.toolHighlighter),
            Tool.ERASER to findViewById(R.id.toolEraser),
            Tool.LASSO to findViewById(R.id.toolLasso),
            Tool.LASER to findViewById(R.id.toolLaser),
            Tool.TEXT to findViewById(R.id.toolText),
            Tool.TAPE to findViewById(R.id.toolTape),
            Tool.FILL to findViewById(R.id.toolFill),
        )
        penSlots = loadSlots("pen", penDefaults)
        hlSlots = loadSlots("hl", hlDefaults)
        laserSlots = loadSlots("laser", laserDefaults)
        tapeSlots = loadSlots("tape", tapeDefaults)
        fillSlots = loadSlots("fill", fillDefaults)
        applyToolColor(Tool.PEN)
        applyToolColor(Tool.HIGHLIGHTER)
        applyToolColor(Tool.LASER)
        applyToolColor(Tool.TAPE)
        applyToolColor(Tool.FILL)
        docView.fillMode = if (prefs.getString("fillMode", null) == FillMode.FREE.name) FillMode.FREE else FillMode.BUCKET
        docView.fillPattern = FillPattern.of(prefs.getString("fillPattern", null))
        docView.fillSmoothing = prefs.getInt("fillSmoothing", 2)
        docView.fillPolygon = prefs.getBoolean("fillPolygon", true)
        docView.fillErasing = prefs.getBoolean("fillErasing", false)
        docView.fillEraseMode = if (prefs.getString("fillEraseMode", null) == EraserMode.AREA.name) EraserMode.AREA else EraserMode.STROKE
        docView.tapeRect = prefs.getBoolean("tapeRect", false)
        docView.tapeErasing = prefs.getBoolean("tapeErasing", false)
        docView.tapeEraseMode = if (prefs.getString("tapeEraseMode", null) == EraserMode.AREA.name) EraserMode.AREA else EraserMode.STROKE
        docView.tapeStraight = prefs.getBoolean("tapeStraight", true)
        docView.tapeFitText = prefs.getBoolean("tapeFitText", true)
        docView.tapePattern = TapePattern.of(prefs.getString("tapePattern", null))
        docView.laserFadeMs = prefs.getLong("laserFadeMs", 2000L)
        docView.hlStraight = prefs.getBoolean("hlStraight", false)
        docView.textMark = if (textMarkupOn()) TextMark.named(prefs.getString("textMark", null)) else TextMark.NONE
        docView.penStyle = PenStyle.named(prefs.getString("penStyle", null))
        docView.penSmoothing = prefs.getInt("penSmoothing", 0)
        widthSlots = listOf(Tool.HIGHLIGHTER, Tool.ERASER, Tool.LASER, Tool.TAPE).associateWith { loadWidths(WidthKind.of(it)) }
        penWidthSlots = PenStyle.entries.associateWith { loadWidths(it.widthKind, it.widthKey, it.widthDefaults) }
        (widthSlots.keys + Tool.PEN).forEach(::applySlotWidth)
        docView.fingerDrawing = if (prefs.contains("finger")) prefs.getBoolean("finger", false) else {
            // 처음 한 번만: 펜 입력이 없는 기기면 손가락 쓰기를 켜 두고 알려 준다 (이후엔 사용자가 메뉴에서 바꾼 값)
            val auto = !hasStylus()
            prefs.edit().putBoolean("finger", auto).apply()
            if (auto) Toast.makeText(
                activity, "펜이 없는 기기라서 손가락으로 쓰기를 켰습니다. (⋮ 메뉴에서 끌 수 있습니다)", Toast.LENGTH_LONG
            ).show()
            auto
        }

        toolButtons.getValue(Tool.SHAPE).setImageDrawable(ShapePenDrawable(activity))
        setupShapeBar()
        updateEraserIcon()
        updateLassoIcon()
        updateHighlighterIcon()
        updateTapeIcon()
        updateFillIcon()
        updatePenIcon()
        toolButtons.forEach { (t, b) ->
            b.setOnClickListener {
                if (t == Tool.LASSO || t == Tool.LASER || t == Tool.HIGHLIGHTER) {
                    // 형광펜·선택·레이저는 고를 것이 적어 옵션 줄 대신 버튼 옆에 펼침 창. 열려 있을 때 누르면 닫는다
                    val reopen = !flyoutJustClosed(b)
                    selectTool(t)
                    if (reopen) showToolFlyout(t, b)
                } else if (t == Tool.PEN) {
                    // 펜: 처음 누르면 펜으로, 펜일 때 다시 누르면 펜 종류·손떨림 보정 줄을 열고 닫는다
                    val wasPen = docView.tool == Tool.PEN
                    val showing = optionTool == Tool.PEN
                    selectTool(t)
                    when {
                        showing -> hideOptionBar()
                        wasPen -> showOptionBar(Tool.PEN)
                        !prefs.getBoolean("penHintShown", false) -> {
                            prefs.edit().putBoolean("penHintShown", true).apply()
                            toast("펜을 한 번 더 누르면 펜 종류(만년필·붓펜·연필 등)와 손떨림 보정을 고를 수 있습니다.")
                        }
                    }
                } else if (t == Tool.ERASER || t == Tool.TAPE || t == Tool.FILL) {
                    // 테이프·채우기·지우개는 누를 때마다 옵션 줄을 열고, 열려 있으면 닫는다
                    if (t == Tool.TAPE && docView.tool != Tool.TAPE) {
                        toast("테이프를 누르면 가린 내용이 보이고, 다시 누르면 가려집니다. 손가락으로는 어느 도구에서나 됩니다.")
                    }
                    if (t == Tool.FILL && docView.tool != Tool.FILL && !docView.fillErasing) {
                        toast(if (docView.fillMode == FillMode.BUCKET) "도형 안을 누르면 그 안을 채웁니다."
                            else "닫힌 곡선을 그리면 가장 바깥 선 안을 모두 채웁니다.")
                    }
                    val showing = optionTool == t
                    selectTool(t)
                    if (showing) hideOptionBar() else showOptionBar(t)
                } else {
                    if (t == Tool.TEXT && docView.tool != Tool.TEXT) {
                        Toast.makeText(activity, "글을 넣을 자리를 누르면 글 상자가 생깁니다. 넣은 글을 누르면 고칠 수 있습니다.", Toast.LENGTH_SHORT).show()
                    }
                    selectTool(t)
                }
            }
        }
        selectTool(Tool.PEN)
        applyToolVisibility()
        setupRuler()
    }

    fun selectTool(t: Tool) {
        if (t != Tool.TEXT) textEditor.commit()
        if (optionTool != t) hideOptionBar()
        closeFlyout()
        docView.tool = t
        toolButtons.forEach { (k, b) -> b.isSelected = k == t }
        shapeBar.visibility = if (t == Tool.SHAPE) View.VISIBLE else View.GONE
        buildColors()
    }

    // ----- 보정 펜 도형 줄 -----

    /** 펼쳐 고르는 무리: 칸에는 지금 고른 종류가 보이고, 누르면 펼쳐진다 */
    private val shapeFamilies = mapOf(
        // 보조선 화살표: 직선·곡선·돼지꼬리
        ShapeKind.ARROW to listOf(ShapeKind.ARROW, ShapeKind.ARROW_CURVE, ShapeKind.ARROW_PIGTAIL),
        // 다항: 직선(일차)·이차·삼차·사차
        ShapeKind.LINE to listOf(ShapeKind.LINE, ShapeKind.QUADRATIC, ShapeKind.CUBIC, ShapeKind.QUARTIC),
        ShapeKind.CIRCLE to listOf(ShapeKind.CIRCLE, ShapeKind.CIRCLE_CR, ShapeKind.ELLIPSE, ShapeKind.SECTOR, ShapeKind.SEMICIRCLE),
        ShapeKind.TRIANGLE to listOf(
            ShapeKind.TRIANGLE, ShapeKind.TRI_EQUILATERAL, ShapeKind.TRI_RIGHT,
            ShapeKind.TRI_ISOSCELES, ShapeKind.TRI_RIGHT_ISOSCELES,
        ),
        ShapeKind.QUADRILATERAL to listOf(
            ShapeKind.QUADRILATERAL, ShapeKind.SQUARE, ShapeKind.RECTANGLE,
            ShapeKind.RHOMBUS, ShapeKind.PARALLELOGRAM,
        ),
    )
    private val shapeOrder = listOf(
        "화살표" to ShapeKind.ARROW, "길이 표시" to ShapeKind.LENGTH_MARK,
        "다항" to ShapeKind.LINE, "원" to ShapeKind.CIRCLE, "쌍곡선" to ShapeKind.HYPERBOLA,
        "삼각형" to ShapeKind.TRIANGLE, "사각형" to ShapeKind.QUADRILATERAL,
        "지수·로그" to ShapeKind.EXP_LOG, "이차×지수" to ShapeKind.QUAD_EXP,
        "사인·코사인" to ShapeKind.SINE, "탄젠트" to ShapeKind.TANGENT,
    )
    /** 무리마다 마지막으로 고른 종류 (다른 무리를 쓰는 동안 칸에 보인다) */
    private val shapeFamilyLast = mutableMapOf<ShapeKind, ShapeKind>()

    private fun setupShapeBar() {
        docView.shapeKind = prefs.getString("shapeKind", null)
            // 지수·로그 버튼이 따로 있던 때 고른 값
            ?.let { n -> if (n == "EXPONENTIAL" || n == "LOG") ShapeKind.EXP_LOG.name else n }
            ?.let { n -> ShapeKind.entries.firstOrNull { it.name == n } } ?: ShapeKind.LINE
        docView.shapeGuide = guideStyle(docView.shapeKind)
        docView.shapeDashed = lineDashed(docView.shapeKind)
        buildShapeBar()
    }

    private fun selectShapeKind(kind: ShapeKind) {
        docView.shapeKind = kind
        docView.shapeGuide = guideStyle(kind)
        docView.shapeDashed = lineDashed(kind)
        prefs.edit().putString("shapeKind", kind.name).apply()
        shapeFamilies.entries.firstOrNull { kind in it.value }?.let { shapeFamilyLast[it.key] = kind }
        buildShapeBar()
    }

    /** 도형 줄의 칸들 (도형 아이콘 + 이름). 툴바가 왼쪽·오른쪽이면 세로로 늘어선다 */
    private fun buildShapeBar() {
        shapeRow.removeAllViews()
        for ((label, kind) in shapeOrder) {
            val family = shapeFamilies[kind]
            val cur = when {
                family == null -> kind
                docView.shapeKind in family -> docView.shapeKind
                else -> shapeFamilyLast[kind] ?: family[0]
            }
            val selected = if (family != null) docView.shapeKind in family else docView.shapeKind == kind
            val text = when {
                kind.isGuideLine -> "$label ▾"
                family != null -> "${cur.label} ▾"
                kind in GUIDE_KINDS -> "$label ▾"
                else -> label
            }
            val index = shapeRow.childCount
            shapeRow.addView(optionItem(ShapeIconDrawable(activity, cur, kind.isGuideLine && lineDashed(kind),
                if (kind in GUIDE_KINDS) guideStyle(kind) else GuideStyle.DASHED), text, selected) { v ->
                when {
                    // 보조선(화살표·길이 표시): 바로 고르고, 모양·실선/점선을 고르는 창
                    kind.isGuideLine -> {
                        selectShapeKind(cur)
                        // 칸을 새로 만들었으니 자리가 잡힌 뒤에 그 옆에 띄운다
                        shapeRow.post { showGuideLineFlyout(shapeRow.getChildAt(index) ?: v, family ?: listOf(kind)) }
                    }
                    // 무리: 칸에 보이는 종류로 바로 바꾸고, 펼친 창에서 다른 종류를 고를 수도 있다
                    family != null -> {
                        selectShapeKind(cur)
                        // 칸을 새로 만들었으니 자리가 잡힌 뒤에 그 옆에 띄운다
                        shapeRow.post {
                            showFlyout(shapeRow.getChildAt(index) ?: v, family.map { k ->
                                Triple(ShapeIconDrawable(activity, k), k.label, k == docView.shapeKind)
                            }) { i -> selectShapeKind(family[i]) }
                        }
                    }
                    // 점근선·축이 있는 도형: 고르고 보조선 방식 고르는 창
                    kind in GUIDE_KINDS -> {
                        selectShapeKind(kind)
                        // 칸을 새로 만들었으니 자리가 잡힌 뒤에 그 옆에 띄운다
                        shapeRow.post { showGuideMenu(shapeRow.getChildAt(index) ?: v, kind) }
                    }
                    else -> selectShapeKind(kind)
                }
            })
        }
    }

    /** 화살표·길이 표시의 몸통이 점선인지 (화살표 셋은 함께, 길이 표시는 따로 기억) */
    private fun lineDashed(kind: ShapeKind): Boolean {
        if (!kind.isGuideLine) return false
        return prefs.getBoolean("dashed_${lineGroup(kind).name}", false)
    }

    private fun lineGroup(kind: ShapeKind) = if (kind == ShapeKind.LENGTH_MARK) ShapeKind.LENGTH_MARK else ShapeKind.ARROW

    /**
     * 화살표·길이 표시 칸의 펼침 창: (화살표면) 직선·곡선·돼지꼬리, 그리고 실선·점선.
     * 실선·점선 아이콘은 지금 고른 모양으로 보인다
     */
    private fun showGuideLineFlyout(anchor: View, kinds: List<ShapeKind>) {
        val cur = docView.shapeKind
        val dashed = lineDashed(cur)
        val shapes = if (kinds.size > 1) kinds else emptyList()
        val items = shapes.map { k -> Triple<android.graphics.drawable.Drawable, String, Boolean>(
            ShapeIconDrawable(activity, k, dashed), k.label.removeSuffix(" 화살표"), k == cur) } +
            listOf(
                Triple(ShapeIconDrawable(activity, cur, false), "실선", !dashed),
                Triple(ShapeIconDrawable(activity, cur, true), "점선", dashed),
            )
        // 모양(직선·곡선·돼지꼬리)과 실선·점선은 따로 고르므로 막대로 나눈다
        showFlyout(anchor, items, separatorBefore = if (shapes.isNotEmpty()) setOf(shapes.size) else emptySet()) { i ->
            if (i < shapes.size) selectShapeKind(shapes[i])
            else {
                prefs.edit().putBoolean("dashed_${lineGroup(cur).name}", i == shapes.size + 1).apply()
                selectShapeKind(cur)
            }
        }
    }

    /** 보정 펜 도형별 보조선 방식 (도형마다 따로 기억) */
    private fun guideStyle(kind: ShapeKind): GuideStyle {
        if (kind !in GUIDE_KINDS) return GuideStyle.NONE
        val n = prefs.getString("guide_${kind.name}", null)
        return GuideStyle.entries.firstOrNull { it.name == n } ?: GuideStyle.NONE
    }

    /** 점근선(또는 사인·코사인의 축)을 안 그림 / 점선 / (축만) 실선 중에서 고르는 창 */
    private fun showGuideMenu(anchor: View, kind: ShapeKind) {
        val what = if (kind == ShapeKind.SINE) "축" else "점근선"
        val styles = if (kind == ShapeKind.SINE) GuideStyle.entries else listOf(GuideStyle.NONE, GuideStyle.DASHED)
        val names = mapOf(GuideStyle.NONE to "안 그림", GuideStyle.DASHED to "점선", GuideStyle.SOLID to "실선")
        val cur = guideStyle(kind)
        // 창 밖을 누르면 닫히되 그 누름은 아래 문서로 전달되는 펼침 창 (PopupMenu는 그 누름을 삼킨다)
        val items = styles.map { st -> Triple<android.graphics.drawable.Drawable, String, Boolean>(
            ShapeIconDrawable(activity, kind, guideStyle = st), "$what ${names.getValue(st)}", st == cur) }
        showFlyout(anchor, items) { i ->
            val st = styles[i]
            prefs.edit().putString("guide_${kind.name}", st.name).apply()
            if (docView.shapeKind == kind) docView.shapeGuide = st
            // 칸의 아이콘에도 고른 방식이 보이게
            buildShapeBar()
        }
    }

    // ----- 굵기 칸 (굿노트처럼 3칸, 고른 칸을 다시 누르면 조절 창) -----

    private class WidthSlots(val kind: WidthKind, val key: String, val slots: FloatArray, var active: Int) {
        val value get() = slots[active]
    }

    private lateinit var widthSlots: Map<Tool, WidthSlots>
    /** 펜은 펜 종류마다 굵기 칸을 따로 (붓펜·캘리그래피는 범위도 다르다) */
    private lateinit var penWidthSlots: Map<PenStyle, WidthSlots>

    private fun widthSlotsOf(k: Tool) = if (k == Tool.PEN) penWidthSlots[docView.penStyle] else widthSlots[k]

    private fun loadWidths(kind: WidthKind, key: String = kind.key, defaults: FloatArray = kind.defaults): WidthSlots {
        val saved = prefs.getString("${key}Slots", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
        val slots = if (saved?.size == defaults.size) saved.toFloatArray() else defaults.copyOf()
        var active = prefs.getInt("${key}Active", -1)
        if (active !in slots.indices) {
            // 칸 기능 이전 버전의 굵기를 가장 가까운 칸에 넣는다
            active = 1
            if (prefs.contains(key)) {
                val legacy = prefs.getFloat(key, slots[1]).coerceIn(kind.min, kind.max)
                active = slots.indices.minBy { abs(slots[it] - legacy) }
                slots[active] = legacy
            }
        }
        return WidthSlots(kind, key, slots, active)
    }

    private fun saveWidths(s: WidthSlots) {
        prefs.edit()
            .putString("${s.key}Slots", s.slots.joinToString(","))
            .putInt("${s.key}Active", s.active)
            .putFloat(s.key, s.value)
            .apply()
    }

    /** 보정 펜은 펜과, 테이프 지우개는 지우개와 굵기 칸을 같이 쓴다 */
    private fun widthTool(t: Tool) = when {
        t == Tool.SHAPE -> Tool.PEN
        t == Tool.TAPE && docView.tapeErasing -> Tool.ERASER
        t == Tool.FILL && docView.fillErasing -> Tool.ERASER
        else -> t
    }

    private fun applyToolWidth(t: Tool) = applySlotWidth(widthTool(t))

    /** 굵기 칸 묶음(도구 [k]의 칸)에서 고른 굵기를 문서 화면에 넣는다 */
    private fun applySlotWidth(k: Tool) {
        val v = widthSlotsOf(k)?.value ?: return
        when (k) {
            Tool.PEN, Tool.SHAPE -> docView.penWidth = v
            Tool.HIGHLIGHTER -> docView.hlWidth = v
            Tool.ERASER -> docView.eraserRadiusDp = v
            Tool.LASER -> docView.laserWidthDp = v
            Tool.TAPE -> docView.tapeWidth = v
            Tool.LASSO, Tool.TEXT, Tool.FILL -> {}
        }
    }

    private fun toolColor(t: Tool) = when (t) {
        Tool.PEN, Tool.SHAPE, Tool.TEXT -> docView.penColor
        Tool.HIGHLIGHTER -> docView.hlColor
        Tool.LASER -> docView.laserColor
        Tool.TAPE -> docView.tapeColor
        Tool.FILL -> docView.fillColor
        Tool.ERASER, Tool.LASSO -> Color.BLACK
    }

    private fun buildWidths() {
        widthRow.removeAllViews()
        val t = docView.tool
        // 네모 테이프는 끌어서 크기를 정하므로 굵기 칸이 없다
        val s = if (t == Tool.TAPE && docView.tapeRect && !docView.tapeErasing) null else widthSlotsOf(widthTool(t))
        widthRow.visibility = if (s == null) View.GONE else View.VISIBLE
        if (s == null) return
        val d = resources.displayMetrics.density
        s.slots.forEachIndexed { i, w ->
            // 툴바 방향으로 좁은 칸 (가로 툴바 기준 너비 32dp × 높이 44dp, 세로 툴바면 dock.fit이 맞바꾼다). 아래에 수치
            val v = WidthSwatchView(activity).apply {
                layoutParams = LinearLayout.LayoutParams((32 * d).toInt(), (44 * d).toInt()).apply {
                    marginStart = (if (i == 0) 0 else 2 * d).toInt()
                }
                setBackgroundResource(R.drawable.bg_tool)
                kind = s.kind
                color = toolColor(t)
                value = w
                isSelected = i == s.active
                contentDescription = "${s.kind.label(w)}${if (i == s.active) " (선택됨, 다시 누르면 조절)" else ""}"
            }
            v.setOnClickListener {
                if (i == s.active) openWidthPopup(t, v)
                else {
                    s.active = i
                    applyToolWidth(t)
                    saveWidths(s)
                    buildWidths()
                }
            }
            v.setOnLongClickListener {
                s.active = i
                applyToolWidth(t)
                saveWidths(s)
                buildWidths()
                openWidthPopup(t, widthRow.getChildAt(i) ?: v)
                true
            }
            widthRow.addView(v)
        }
        dock.fit(widthRow)
    }

    private fun openWidthPopup(t: Tool, anchor: View) {
        val s = widthSlotsOf(widthTool(t)) ?: return
        WidthPopup(activity, s.kind, s.value, toolColor(t)) { w, done ->
            s.slots[s.active] = w
            applyToolWidth(t)
            (widthRow.getChildAt(s.active) as? WidthSwatchView)?.value = w
            if (done) saveWidths(s)
        }.show(anchor, dock.side)
    }

    /** 펜 아래 S자 곡선과 형광펜 아래 줄을 지금 고른 색으로 칠한다 */
    private fun updateToolMarks() {
        for ((t, color) in listOf(
            Tool.PEN to docView.penColor, Tool.HIGHLIGHTER to docView.hlColor, Tool.LASER to docView.laserColor,
            Tool.TAPE to docView.tapeColor, Tool.FILL to docView.fillColor,
        )) {
            val button = toolButtons[t] ?: continue
            val layers = button.drawable?.mutate() as? LayerDrawable ?: continue
            layers.findDrawableByLayerId(R.id.tool_mark)?.mutate()?.setTint(color)
            button.invalidate()
        }
        (toolButtons[Tool.SHAPE]?.drawable as? ShapePenDrawable)?.markColor = docView.penColor
    }

    private fun buildColors() {
        updateToolMarks()
        buildWidths()
        colorRow.removeAllViews()
        val t = docView.tool
        val noColor = t == Tool.ERASER || t == Tool.LASSO || (t == Tool.TAPE && docView.tapeErasing) ||
            (t == Tool.FILL && docView.fillErasing)
        colorSep.visibility = if (noColor) View.GONE else View.VISIBLE
        lassoRow.visibility = if (t == Tool.LASSO) View.VISIBLE else View.GONE
        pasteButton.visibility = if (docView.hasClipboard) View.VISIBLE else View.GONE
        if (noColor) {
            colorRow.visibility = View.GONE
            return
        }
        colorRow.visibility = View.VISIBLE
        val s = slotsOf(t)
        val d = resources.displayMetrics.density
        s.slots.forEachIndexed { i, c ->
            // 모서리가 둥근 막대. 툴바 방향으로 좁게 (가로 툴바 기준 20dp × 36dp, 세로 툴바면 dock.fit이 맞바꾼다)
            val v = View(activity)
            val lp = LinearLayout.LayoutParams((20 * d).toInt(), (36 * d).toInt())
            lp.marginStart = (3 * d).toInt()
            lp.marginEnd = (3 * d).toInt()
            v.layoutParams = lp
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 6 * d
                setColor(c)
                if (i == s.active) setStroke((2.5f * d).toInt(), ColorStateList.valueOf(0xFF1E5AA8.toInt()))
                else setStroke((1 * d).toInt(), ColorStateList.valueOf(Color.argb(60, 0, 0, 0)))
            }
            v.contentDescription = if (i == s.active) "색상 (선택됨, 다시 누르면 색 바꾸기)" else "색상"
            // 한 번 누르면 그 칸의 색으로, 이미 고른 칸을 다시 누르거나 길게 누르면 색 고르기 창
            v.setOnClickListener {
                if (i == s.active) openColorPicker(t, v)
                else {
                    s.active = i
                    applyToolColor(t)
                    saveSlots(s)
                    buildColors()
                }
            }
            v.setOnLongClickListener {
                s.active = i
                applyToolColor(t)
                saveSlots(s)
                buildColors()
                openColorPicker(t, colorRow.getChildAt(i) ?: v)
                true
            }
            colorRow.addView(v)
        }
        val palette = ImageButton(activity).apply {
            layoutParams = LinearLayout.LayoutParams((36 * d).toInt(), (36 * d).toInt()).apply {
                marginStart = (3 * d).toInt()
            }
            setImageResource(R.drawable.ic_palette)
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            contentDescription = "색 더 보기"
            setOnClickListener { openColorPicker(t, this) }
        }
        colorRow.addView(palette)
        dock.fit(colorRow)
    }

    companion object {
        /** 보조선(점근선·축)을 고를 수 있는 보정 펜 도형 */
        private val GUIDE_KINDS = setOf(ShapeKind.HYPERBOLA, ShapeKind.EXP_LOG, ShapeKind.QUAD_EXP, ShapeKind.TANGENT, ShapeKind.SINE)
    }
}
