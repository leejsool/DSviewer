package com.dsviewer.app

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.view.InputDevice
import android.widget.PopupMenu
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dsviewer.app.hwp.HRenderer
import com.dsviewer.app.hwp.HwpReader
import com.dsviewer.app.hwp.HwpxReader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.abs

class ViewerActivity : AppCompatActivity() {

    private lateinit var docView: DocumentView
    private lateinit var progress: ProgressBar
    private lateinit var pageLabel: TextView
    private lateinit var colorRow: LinearLayout
    private lateinit var widthRow: LinearLayout
    private lateinit var colorSep: View
    private lateinit var shapeBar: View
    private lateinit var lassoRow: View
    private lateinit var pasteButton: View
    private lateinit var selectionBar: View
    private lateinit var toolButtons: Map<Tool, ImageButton>

    private lateinit var docTabs: ChromeTabBar
    private lateinit var undoButton: View
    private lateinit var redoButton: View
    private lateinit var saveButton: View

    /** 탭 하나 = 열린 문서 하나. 문서 화면(DocumentView)은 하나를 같이 쓰고 탭을 바꿀 때 갈아 끼운다 */
    private class DocTab(val uri: Uri, var canOverwrite: Boolean) {
        var name = "문서"
        var type = DocType.UNKNOWN
        var pdf: PdfDoc? = null
        var ink: InkDocument? = null
        /** 앱 캐시에 복사한 원본 PDF (저장할 때 이 파일에 필기를 얹는다) */
        var sourcePdf: File? = null
        /** 다른 탭에 가 있는 동안 기억해 둔 스크롤·확대 위치 */
        var viewState: DocumentView.ViewState? = null
    }

    private val docs = ArrayList<DocTab>()
    private var current: DocTab? = null
    private val ink get() = current?.ink
    /** '다른 이름으로 저장' 창을 띄운 탭 */
    private var saveTarget: DocTab? = null
    /** 저장이 끝나면 닫을 탭 */
    private var closeAfterSave: DocTab? = null
    /** 파일 탐색기에서 열었는지 (뒤로 가면 탭을 그대로 둔 채 탐색기로) */
    private var fromBrowser = false

    private val prefs by lazy { getSharedPreferences("tools", Context.MODE_PRIVATE) }

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val t = saveTarget
        saveTarget = null
        if (uri != null && t != null && t in docs) saveTo(t, uri, overwrite = false)
        else closeAfterSave = null
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = goBack()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        applyInsets(findViewById(R.id.root))

        undoButton = findViewById(R.id.actionUndo)
        redoButton = findViewById(R.id.actionRedo)
        saveButton = findViewById(R.id.actionSave)

        docView = findViewById(R.id.docView)
        progress = findViewById(R.id.progress)
        pageLabel = findViewById(R.id.pageLabel)
        colorRow = findViewById(R.id.colorRow)
        widthRow = findViewById(R.id.widthRow)
        colorSep = findViewById(R.id.colorSep)
        shapeBar = findViewById(R.id.shapeBar)
        lassoRow = findViewById(R.id.lassoRow)
        pasteButton = findViewById(R.id.pasteButton)
        selectionBar = findViewById(R.id.selectionBar)
        setupSelectionTools()

        docView.listener = object : DocumentView.Listener {
            override fun onPageChanged(page: Int, count: Int) {
                pageLabel.visibility = View.VISIBLE
                pageLabel.text = "${page + 1} / $count"
            }

            override fun onSelectionChanged(rect: RectF?, count: Int) = placeSelectionBar(rect, count)

            override fun onShapeFailed(kind: ShapeKind) {
                Toast.makeText(this@ViewerActivity, "${kind.label}으로 맞추지 못했어요. 조금 더 크게 그려 보세요.", Toast.LENGTH_SHORT).show()
            }
        }

        setupTools()
        setupTabs()
        setupActions()
        onBackPressedDispatcher.addCallback(this, backCallback)

        if (!handleIntent(intent)) {
            Toast.makeText(this, "열 파일이 없습니다.", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    /** 뷰어가 이미 떠 있을 때 탐색기에서 문서를 고르면 새 탭으로 연다 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 인텐트의 문서를 탭으로 연다. 열 문서가 없으면 false (탐색기에서 '뷰어로 돌아가기'만 한 경우) */
    private fun handleIntent(intent: Intent): Boolean {
        if (intent.getBooleanExtra(EXTRA_FROM_BROWSER, false)) fromBrowser = true
        val uri = if (intent.action == Intent.ACTION_SEND)
            IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
        else intent.data
        uri ?: return false
        val writable = intent.getBooleanExtra(EXTRA_WRITABLE, false) ||
            (intent.flags and Intent.FLAG_GRANT_WRITE_URI_PERMISSION) != 0
        openTab(uri, writable)
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        docs.forEach { it.pdf?.close() }
        openTabs = 0
    }

    // ================= 탭 =================

    private fun setupTabs() {
        docTabs = findViewById(R.id.docTabs)
        docTabs.showAddButton = true
        docTabs.activeFill = getColor(R.color.doc_bg)  // 고른 탭이 아래 문서 바탕과 이어지게
        docTabs.listener = object : ChromeTabBar.Listener {
            override fun onTabSelected(index: Int) {
                docs.getOrNull(index)?.let(::showTab)
            }
            override fun onTabClose(index: Int) {
                docs.getOrNull(index)?.let(::closeTab)
            }
            override fun onAddTab() = pickAnotherDocument()
        }
    }

    private fun openTab(uri: Uri, writable: Boolean) {
        docs.firstOrNull { it.uri == uri }?.let {
            // 이미 열려 있는 문서면 그 탭으로
            docTabs.select(docs.indexOf(it), notify = true)
            return
        }
        if (docs.size >= MAX_TABS) {
            MaterialAlertDialogBuilder(this)
                .setMessage("문서는 ${MAX_TABS}개까지 열 수 있습니다.\n탭을 하나 닫고 다시 열어 주세요.")
                .setPositiveButton("확인", null)
                .show()
            return
        }
        val t = DocTab(uri, writable)
        docs.add(t)
        openTabs = docs.size
        updateAddButton()
        docTabs.addTab(t.name, R.drawable.ic_doc, TAB_ICON_GRAY, closable = true)
        updateTabTitle(t)
        docTabs.select(docs.lastIndex, notify = true)
        load(t)
    }

    private fun updateTabTitle(t: DocTab) {
        val i = docs.indexOf(t)
        if (i < 0) return
        // 저장하지 않은 필기가 있으면 앞에 점
        docTabs.setTitle(i, if (t.ink?.dirty == true) "● ${t.name}" else t.name)
        val color = when (t.type) {
            DocType.PDF -> TAB_ICON_PDF
            DocType.HWP, DocType.HWPX -> TAB_ICON_HWP
            DocType.UNKNOWN -> TAB_ICON_GRAY
        }
        docTabs.setIcon(i, R.drawable.ic_doc, color)
    }

    /** 이 탭의 문서를 화면에 띄운다 */
    private fun showTab(t: DocTab) {
        if (current === t) return
        current?.let { it.viewState = docView.viewState() }
        current = t
        val d = t.pdf
        val inkDoc = t.ink
        if (d != null && inkDoc != null) {
            docView.setDocument(d, inkDoc, t.viewState)
            progress.visibility = View.GONE
        } else {
            docView.clearDocument()
            pageLabel.visibility = View.GONE
            progress.visibility = View.VISIBLE
        }
        updateActions()
    }

    private fun closeTab(t: DocTab) {
        if (t.ink?.dirty != true) {
            removeTab(t)
            return
        }
        docTabs.select(docs.indexOf(t), notify = true)
        MaterialAlertDialogBuilder(this)
            .setTitle("'${t.name}'에 저장하지 않은 필기가 있습니다")
            .setMessage("저장할까요?")
            .setPositiveButton("저장") { _, _ ->
                closeAfterSave = t
                save(t, asNew = false)
            }
            .setNegativeButton("저장 안 함") { _, _ -> removeTab(t) }
            .setNeutralButton("취소", null)
            .show()
    }

    private fun removeTab(t: DocTab) {
        val index = docs.indexOf(t)
        if (index < 0) return
        val wasCurrent = current === t
        if (wasCurrent) {
            current = null
            docView.clearDocument()
        }
        docs.removeAt(index)
        openTabs = docs.size
        updateAddButton()
        docTabs.removeTab(index)
        t.pdf?.close()
        if (docs.isEmpty()) {
            // 마지막 탭을 닫으면 뷰어를 닫고 탐색기로
            if (fromBrowser) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
            finish()
            return
        }
        // 보고 있던 탭을 닫았으면 옆 탭으로, 뒤에 있던 탭을 닫았으면 그대로
        if (wasCurrent) docTabs.select(minOf(index, docs.size - 1), notify = true)
    }

    /** ＋ 버튼: 탐색기를 '새 탭에 열 문서 고르기'로 띄운다 */
    private fun pickAnotherDocument() {
        if (docs.size >= MAX_TABS) {
            Toast.makeText(this, "문서는 ${MAX_TABS}개까지 열 수 있습니다. 탭을 하나 닫아 주세요.", Toast.LENGTH_LONG).show()
            return
        }
        fromBrowser = true
        startActivity(
            Intent(this, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(MainActivity.EXTRA_PICK, true)
        )
    }

    /** 뒤로 가기(← 버튼·시스템 뒤로): 지금 보고 있는 탭을 닫는다. 마지막 탭이면 탐색기로 */
    private fun goBack() {
        val t = current
        if (t == null) finish() else closeTab(t)
    }

    /** 탭이 가득 차면 ＋를 회색으로 */
    private fun updateAddButton() {
        val canAdd = docs.size < MAX_TABS
        docTabs.setAddEnabled(canAdd, if (canAdd) "다른 문서 열기" else "문서는 ${MAX_TABS}개까지 열 수 있습니다")
    }

    /** 뷰어가 앱의 첫 화면으로 열린 경우(다른 앱에서 새 창으로 열기 등), 닫을 때 파일 탐색기로 돌아간다 */
    override fun finish() {
        if (isTaskRoot) startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        super.finish()
    }

    // ================= 열기 =================

    private fun load(t: DocTab) {
        val uri = t.uri
        lifecycleScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { FileUtil.displayName(this@ViewerActivity, uri) }
                t.name = name
                updateTabTitle(t)
                val file = withContext(Dispatchers.IO) {
                    // 다른 탭이 쓰고 있을 수 있으므로 오래된 캐시 정리는 첫 탭에서만
                    if (docs.size == 1) FileUtil.cleanOld(this@ViewerActivity)
                    FileUtil.copyToCache(this@ViewerActivity, uri, name)
                }
                t.type = withContext(Dispatchers.IO) { FileUtil.detect(name, contentResolver.getType(uri), file) }
                updateTabTitle(t)  // 탭 아이콘 색 (PDF 빨강, 한글 파랑)
                when (t.type) {
                    DocType.PDF -> openPdf(t, file)
                    DocType.HWPX, DocType.HWP -> openPdf(t, convertHwp(file, t.type))
                    DocType.UNKNOWN -> fail(t, "지원하지 않는 파일 형식입니다.\n(PDF, HWP, HWPX 파일만 열 수 있습니다)")
                }
            } catch (e: SecurityException) {
                fail(t, "파일을 열 권한이 없거나, 암호가 걸린 PDF입니다.")
            } catch (e: Exception) {
                fail(t, "파일을 열지 못했습니다.\n${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** HWP/HWPX → PDF (기기 안에서 변환) */
    private suspend fun convertHwp(file: File, type: DocType): File = withContext(Dispatchers.Default) {
        val doc = when (type) {
            DocType.HWPX -> HwpxReader.read(file)
            DocType.HWP -> HwpReader.read(file)
            else -> error("지원하지 않는 형식")
        }
        val out = FileUtil.tempFile(this@ViewerActivity, "conv", "pdf")
        HRenderer(doc).render(out)
        out
    }

    private suspend fun openPdf(t: DocTab, file: File) {
        t.sourcePdf = file
        // 이 앱으로 저장한 필기가 있으면 꺼내서 편집 가능한 상태로 만든다
        val (renderFile, strokes) = withContext(Dispatchers.IO) {
            if (PdfInk.containsInk(file)) {
                val clean = FileUtil.tempFile(this@ViewerActivity, "clean", "pdf")
                clean to PdfInk.extract(file, clean)
            } else file to null
        }
        val d = PdfDoc.open(renderFile)
        if (t !in docs) {
            // 읽는 사이 탭이 닫힘
            d.close()
            return
        }
        val inkDoc = InkDocument(d.pageCount)
        strokes?.let { inkDoc.load(it) }
        inkDoc.onChanged = {
            if (current === t) {
                updateActions()
                docView.invalidate()
            }
            updateTabTitle(t)
        }
        t.pdf = d
        t.ink = inkDoc
        if (current === t) {
            docView.setDocument(d, inkDoc)
            progress.visibility = View.GONE
            updateActions()
        }
    }

    private fun fail(t: DocTab, msg: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(t.name)
            .setMessage(msg)
            .setPositiveButton("확인", null)
            .setOnDismissListener { removeTab(t) }
            .show()
    }

    // ================= 메뉴 =================

    // 탭 줄 오른쪽의 실행 취소 · 다시 실행 · 저장 · ⋮ 버튼

    private fun setupActions() {
        undoButton.setOnClickListener { docView.clearSelection(); ink?.undo() }
        redoButton.setOnClickListener { docView.clearSelection(); ink?.redo() }
        saveButton.setOnClickListener { current?.let { save(it, asNew = false) } }
        findViewById<View>(R.id.actionMore).setOnClickListener { showMoreMenu(it) }
        updateActions()
    }

    /** 지금 탭의 상태에 맞춰 버튼을 켜고 끈다 (예전 invalidateOptionsMenu 자리) */
    private fun updateActions() {
        val inkDoc = ink
        undoButton.setEnabledAlpha(inkDoc?.canUndo == true)
        redoButton.setEnabledAlpha(inkDoc?.canRedo == true)
        saveButton.setEnabledAlpha(inkDoc != null)
    }

    private fun View.setEnabledAlpha(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.35f
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.viewer, popup.menu)
        popup.menu.findItem(R.id.action_save_as).isEnabled = ink != null
        popup.menu.findItem(R.id.action_finger).isChecked = docView.fingerDrawing
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_save_as -> current?.let { save(it, asNew = true) }
                R.id.action_finger -> {
                    docView.fingerDrawing = !docView.fingerDrawing
                    prefs.edit().putBoolean("finger", docView.fingerDrawing).apply()
                    Toast.makeText(
                        this,
                        if (docView.fingerDrawing) "손가락 한 개로 필기, 두 손가락으로 이동/확대" else "펜으로만 필기합니다",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
            true
        }
        popup.show()
    }

    // ================= 저장 =================

    private fun suggestedName(t: DocTab): String {
        val base = FileUtil.baseName(t.name)
        return if (t.type == DocType.PDF) "${base}_필기.pdf" else "$base.pdf"
    }

    private fun save(t: DocTab, asNew: Boolean) {
        if (t.ink == null) return
        if (!asNew && t.canOverwrite && t.type == DocType.PDF) saveTo(t, t.uri, overwrite = true)
        else saveAs(t)
    }

    private fun saveAs(t: DocTab) {
        saveTarget = t
        createDoc.launch(suggestedName(t))
    }

    private fun saveTo(t: DocTab, target: Uri, overwrite: Boolean) {
        val inkDoc = t.ink ?: return
        val src = t.sourcePdf ?: return
        val snapshot = inkDoc.snapshot()
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                withContext(Dispatchers.IO) {
                    val tmp = FileUtil.tempFile(this@ViewerActivity, "out", "pdf")
                    try {
                        PdfInk.save(src, tmp, snapshot)
                        val out = contentResolver.openOutputStream(target, "wt") ?: error("저장 위치를 열 수 없습니다.")
                        out.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
                    } finally {
                        tmp.delete()
                    }
                }
                // 파일 탐색기의 '모든 문서' 목록에 바뀐 날짜가 바로 보이도록 색인 갱신
                if (target.scheme == "file") target.path?.let {
                    MediaScannerConnection.scanFile(this@ViewerActivity, arrayOf(it), null, null)
                }
                inkDoc.markSaved()
                Toast.makeText(this@ViewerActivity, "'${t.name}' 저장했습니다.", Toast.LENGTH_SHORT).show()
                if (closeAfterSave === t) {
                    closeAfterSave = null
                    removeTab(t)
                }
            } catch (e: Exception) {
                if (overwrite) {
                    t.canOverwrite = false
                    Toast.makeText(this@ViewerActivity, "원본에 저장할 수 없어 다른 이름으로 저장합니다.", Toast.LENGTH_LONG).show()
                    saveAs(t)
                } else {
                    closeAfterSave = null
                    MaterialAlertDialogBuilder(this@ViewerActivity)
                        .setMessage("저장하지 못했습니다.\n${e.message ?: e.javaClass.simpleName}")
                        .setPositiveButton("확인", null)
                        .show()
                }
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /** 기기에 펜 입력(S펜 등)이 있는지. 펜을 인식하는 화면은 입력 장치에 SOURCE_STYLUS가 붙는다 */
    private fun hasStylus() = InputDevice.getDeviceIds().any { id ->
        InputDevice.getDevice(id)?.supportsSource(InputDevice.SOURCE_STYLUS) == true
    }

    // ================= 올가미 선택 막대 =================

    private fun setupSelectionTools() {
        findViewById<View>(R.id.selectionDelete).setOnClickListener { docView.deleteSelection() }
        findViewById<View>(R.id.selectionCopy).setOnClickListener {
            docView.copySelection()
            pasteButton.visibility = View.VISIBLE
            Toast.makeText(this, "복사했습니다. '붙여넣기'로 원하는 페이지에 붙일 수 있습니다.", Toast.LENGTH_SHORT).show()
        }
        findViewById<View>(R.id.selectionColor).setOnClickListener { v ->
            val initial = docView.selectionColor ?: return@setOnClickListener
            ColorPickerPopup(this, initial, recentColors(Tool.PEN)) { c, done ->
                if (done) {
                    docView.recolorSelection(c)
                    addRecent(Tool.PEN, c)
                }
            }.show(v)
        }
        pasteButton.setOnClickListener { docView.pasteClipboard() }

        // 자유 선택 / 네모 선택
        docView.lassoRect = prefs.getBoolean("lassoRect", false)
        val mode = findViewById<MaterialButtonToggleGroup>(R.id.lassoMode)
        mode.check(if (docView.lassoRect) R.id.lassoRectBtn else R.id.lassoFree)
        mode.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            docView.lassoRect = id == R.id.lassoRectBtn
            prefs.edit().putBoolean("lassoRect", docView.lassoRect).apply()
        }
    }

    /** 선택 상자 바로 위(자리가 없으면 아래)에 '삭제' 막대를 띄운다 */
    private fun placeSelectionBar(rect: RectF?, count: Int) {
        // 선택이 없거나 선택 상자가 화면 밖으로 스크롤되면 숨긴다
        if (rect == null || !rect.intersects(0f, 0f, docView.width.toFloat(), docView.height.toFloat())) {
            selectionBar.visibility = View.GONE
            return
        }
        selectionBar.findViewById<TextView>(R.id.selectionCount).text = "${count}개 선택"
        selectionBar.visibility = View.VISIBLE
        selectionBar.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = selectionBar.measuredWidth
        val h = selectionBar.measuredHeight
        val gap = 8 * resources.displayMetrics.density
        val maxX = (docView.width - w - gap).coerceAtLeast(gap)
        val maxY = (docView.height - h - gap).coerceAtLeast(gap)
        var y = rect.top - h - gap
        if (y < gap) y = rect.bottom + gap
        selectionBar.translationX = (rect.centerX() - w / 2f).coerceIn(gap, maxX)
        selectionBar.translationY = y.coerceIn(gap, maxY)
    }

    // ================= 도구 막대 =================

    private val penDefaults = intArrayOf(
        0xFF000000.toInt(), 0xFFE53935.toInt(), 0xFF1E63D6.toInt(), 0xFF2E9D48.toInt(), 0xFF8E24AA.toInt()
    )
    private val hlDefaults = intArrayOf(
        0xFFFFEB3B.toInt(), 0xFF9CFF57.toInt(), 0xFFFF8AB8.toInt(), 0xFF7FD8FF.toInt(), 0xFFFFB74D.toInt()
    )

    /** 도구막대의 색 칸 (굿노트처럼 칸마다 색을 바꿔 넣을 수 있음). active 칸의 색이 지금 쓰는 색 */
    private class ColorSlots(val key: String, val slots: IntArray, var active: Int) {
        val color get() = slots[active]
    }

    private lateinit var penSlots: ColorSlots
    private lateinit var hlSlots: ColorSlots

    private fun slotsOf(t: Tool) = if (t == Tool.HIGHLIGHTER) hlSlots else penSlots

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
        if (t == Tool.HIGHLIGHTER) docView.hlColor = hlSlots.color else docView.penColor = penSlots.color
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
        ColorPickerPopup(this, s.color, recentColors(t)) { c, done ->
            s.slots[s.active] = c
            applyToolColor(t)
            buildColors()
            if (done) {
                saveSlots(s)
                addRecent(t, c)
            }
        }.show(anchor)
    }

    private fun setupTools() {
        toolButtons = mapOf(
            Tool.PEN to findViewById(R.id.toolPen),
            Tool.SHAPE to findViewById(R.id.toolShape),
            Tool.HIGHLIGHTER to findViewById(R.id.toolHighlighter),
            Tool.ERASER to findViewById(R.id.toolEraser),
            Tool.LASSO to findViewById(R.id.toolLasso),
        )
        penSlots = loadSlots("pen", penDefaults)
        hlSlots = loadSlots("hl", hlDefaults)
        applyToolColor(Tool.PEN)
        applyToolColor(Tool.HIGHLIGHTER)
        widthSlots = listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER).associateWith { loadWidths(WidthKind.of(it)) }
        widthSlots.keys.forEach(::applyToolWidth)
        docView.fingerDrawing = if (prefs.contains("finger")) prefs.getBoolean("finger", false) else {
            // 처음 한 번만: 펜 입력이 없는 기기면 손가락 쓰기를 켜 두고 알려 준다 (이후엔 사용자가 메뉴에서 바꾼 값)
            val auto = !hasStylus()
            prefs.edit().putBoolean("finger", auto).apply()
            if (auto) Toast.makeText(
                this, "펜이 없는 기기라서 손가락으로 쓰기를 켰습니다. (⋮ 메뉴에서 끌 수 있습니다)", Toast.LENGTH_LONG
            ).show()
            auto
        }

        toolButtons.getValue(Tool.SHAPE).setImageDrawable(ShapePenDrawable(this))
        setupShapeBar()
        toolButtons.forEach { (t, b) -> b.setOnClickListener { selectTool(t) } }
        selectTool(Tool.PEN)
    }

    private fun selectTool(t: Tool) {
        docView.tool = t
        toolButtons.forEach { (k, b) -> b.isSelected = k == t }
        shapeBar.visibility = if (t == Tool.SHAPE) View.VISIBLE else View.GONE
        buildColors()
    }

    // ----- 보정 펜 도형 줄 -----

    private fun setupShapeBar() {
        val group = findViewById<ChipGroup>(R.id.shapeChips)
        docView.shapeKind = prefs.getString("shapeKind", null)
            ?.let { n -> ShapeKind.entries.firstOrNull { it.name == n } } ?: ShapeKind.LINE
        fun planned(label: String) =
            Toast.makeText(this, "$label: 구현 예정입니다", Toast.LENGTH_SHORT).show()

        // 펼쳐 고르는 무리: 칩에는 지금 고른 종류 이름이 보이고, 누르면 펼쳐진다
        val families = mapOf(
            ShapeKind.CIRCLE to listOf(ShapeKind.CIRCLE, ShapeKind.ELLIPSE, ShapeKind.CIRCLE_CR),
            ShapeKind.TRIANGLE to listOf(
                ShapeKind.TRIANGLE, ShapeKind.TRI_EQUILATERAL, ShapeKind.TRI_RIGHT,
                ShapeKind.TRI_ISOSCELES, ShapeKind.TRI_RIGHT_ISOSCELES,
            ),
            ShapeKind.QUADRILATERAL to listOf(
                ShapeKind.QUADRILATERAL, ShapeKind.SQUARE, ShapeKind.RECTANGLE,
                ShapeKind.RHOMBUS, ShapeKind.PARALLELOGRAM,
            ),
        )
        // null = 아직 구현하지 않은 도형 (버튼만)
        val order = listOf(
            "직선" to ShapeKind.LINE, "이차" to ShapeKind.QUADRATIC, "삼차" to ShapeKind.CUBIC,
            "사차" to ShapeKind.QUARTIC, "원" to ShapeKind.CIRCLE, "쌍곡선" to null,
            "삼각형" to ShapeKind.TRIANGLE, "사각형" to ShapeKind.QUADRILATERAL,
            "지수" to null, "로그" to null, "사인·코사인" to null, "탄젠트" to null,
        )
        fun selectKind(kind: ShapeKind) {
            docView.shapeKind = kind
            prefs.edit().putString("shapeKind", kind.name).apply()
        }
        for ((label, kind) in order) {
            val chip = layoutInflater.inflate(R.layout.item_shape_chip, group, false) as Chip
            chip.text = label
            chip.id = View.generateViewId()
            val family = families[kind]
            if (family != null) {
                val cur = docView.shapeKind.takeIf { it in family } ?: family[0]
                chip.tag = cur
                chip.text = "${cur.label} ▾"
                chip.isCheckable = true
                group.addView(chip)
                if (docView.shapeKind in family) group.check(chip.id)
                chip.setOnClickListener { v ->
                    PopupMenu(this, v).apply {
                        family.forEachIndexed { i, k -> menu.add(0, i, i, k.label) }
                        setOnMenuItemClickListener { item ->
                            val k = family[item.itemId]
                            chip.tag = k
                            chip.text = "${k.label} ▾"
                            group.check(chip.id)
                            selectKind(k)
                            true
                        }
                        show()
                    }
                }
            } else if (kind != null) {
                chip.tag = kind
                chip.isCheckable = true
                group.addView(chip)
                if (kind == docView.shapeKind) group.check(chip.id)
            } else {
                chip.isCheckable = false
                chip.setOnClickListener { planned(label) }
                group.addView(chip)
            }
        }
        group.setOnCheckedStateChangeListener { g, ids ->
            val kind = ids.firstOrNull()?.let { g.findViewById<Chip>(it)?.tag as? ShapeKind } ?: return@setOnCheckedStateChangeListener
            selectKind(kind)
        }
    }

    // ----- 굵기 칸 (굿노트처럼 3칸, 고른 칸을 다시 누르면 조절 창) -----

    private class WidthSlots(val kind: WidthKind, val slots: FloatArray, var active: Int) {
        val value get() = slots[active]
    }

    private lateinit var widthSlots: Map<Tool, WidthSlots>

    private fun loadWidths(kind: WidthKind): WidthSlots {
        val saved = prefs.getString("${kind.key}Slots", null)?.split(',')?.mapNotNull { it.toFloatOrNull() }
        val slots = if (saved?.size == kind.defaults.size) saved.toFloatArray() else kind.defaults.copyOf()
        var active = prefs.getInt("${kind.key}Active", -1)
        if (active !in slots.indices) {
            // 칸 기능 이전 버전의 굵기를 가장 가까운 칸에 넣는다
            active = 1
            if (prefs.contains(kind.key)) {
                val legacy = prefs.getFloat(kind.key, slots[1]).coerceIn(kind.min, kind.max)
                active = slots.indices.minBy { abs(slots[it] - legacy) }
                slots[active] = legacy
            }
        }
        return WidthSlots(kind, slots, active)
    }

    private fun saveWidths(s: WidthSlots) {
        prefs.edit()
            .putString("${s.kind.key}Slots", s.slots.joinToString(","))
            .putInt("${s.kind.key}Active", s.active)
            .putFloat(s.kind.key, s.value)
            .apply()
    }

    /** 보정 펜은 펜과 굵기 칸을 같이 쓴다 */
    private fun widthTool(t: Tool) = if (t == Tool.SHAPE) Tool.PEN else t

    private fun applyToolWidth(t: Tool) {
        val v = widthSlots[widthTool(t)]?.value ?: return
        when (t) {
            Tool.PEN, Tool.SHAPE -> docView.penWidth = v
            Tool.HIGHLIGHTER -> docView.hlWidth = v
            Tool.ERASER -> docView.eraserRadiusDp = v
            Tool.LASSO -> {}
        }
    }

    private fun toolColor(t: Tool) = when (t) {
        Tool.PEN, Tool.SHAPE -> docView.penColor
        Tool.HIGHLIGHTER -> docView.hlColor
        Tool.ERASER, Tool.LASSO -> Color.BLACK
    }

    private fun buildWidths() {
        widthRow.removeAllViews()
        val t = docView.tool
        val s = widthSlots[widthTool(t)]
        widthRow.visibility = if (s == null) View.GONE else View.VISIBLE
        if (s == null) return
        val d = resources.displayMetrics.density
        s.slots.forEachIndexed { i, w ->
            val v = WidthSwatchView(this).apply {
                layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply {
                    marginStart = (if (i == 0) 0 else 4 * d).toInt()
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
    }

    private fun openWidthPopup(t: Tool, anchor: View) {
        val s = widthSlots.getValue(widthTool(t))
        WidthPopup(this, s.kind, s.value, toolColor(t)) { w, done ->
            s.slots[s.active] = w
            applyToolWidth(t)
            (widthRow.getChildAt(s.active) as? WidthSwatchView)?.value = w
            if (done) saveWidths(s)
        }.show(anchor)
    }

    /** 펜 아래 S자 곡선과 형광펜 아래 줄을 지금 고른 색으로 칠한다 */
    private fun updateToolMarks() {
        for ((t, color) in listOf(Tool.PEN to docView.penColor, Tool.HIGHLIGHTER to docView.hlColor)) {
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
        val noColor = t == Tool.ERASER || t == Tool.LASSO
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
        val size = (30 * d).toInt()
        s.slots.forEachIndexed { i, c ->
            val v = View(this)
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginStart = (5 * d).toInt()
            lp.marginEnd = (5 * d).toInt()
            v.layoutParams = lp
            v.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(c)
                if (i == s.active) setStroke((3 * d).toInt(), ColorStateList.valueOf(0xFF1E5AA8.toInt()))
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
        val palette = ImageButton(this).apply {
            layoutParams = LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()).apply {
                marginStart = (4 * d).toInt()
            }
            setImageResource(R.drawable.ic_palette)
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            setBackgroundResource(tv.resourceId)
            contentDescription = "색 더 보기"
            setOnClickListener { openColorPicker(t, this) }
        }
        colorRow.addView(palette)
    }

    companion object {
        const val EXTRA_WRITABLE = "writable"
        /** 앱의 파일 탐색기에서 연 문서 */
        const val EXTRA_FROM_BROWSER = "fromBrowser"
        private const val MAX_TABS = 6
        private val TAB_ICON_PDF = Color.parseColor("#D93025")
        private val TAB_ICON_HWP = Color.parseColor("#2F6FC4")
        private val TAB_ICON_GRAY = Color.parseColor("#9E9E9E")
        /** 열려 있는 탭 수 (탐색기의 '열린 문서' 버튼용) */
        var openTabs = 0
            private set
    }
}
