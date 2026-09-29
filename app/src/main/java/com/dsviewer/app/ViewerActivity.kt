package com.dsviewer.app

import android.content.ContentValues
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.InputType
import android.text.format.DateFormat
import android.view.InputDevice
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
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
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

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
    private lateinit var dock: ToolbarDock
    private lateinit var topOverlay: LinearLayout
    private lateinit var bottomOverlay: LinearLayout

    private lateinit var docTabs: ChromeTabBar
    private lateinit var undoButton: View
    private lateinit var redoButton: View
    private lateinit var saveButton: View
    private lateinit var overviewButton: View
    private lateinit var insertButton: View
    private lateinit var overview: PageOverview

    /** 탭 하나 = 열린 문서 하나. 문서 화면(DocumentView)은 하나를 같이 쓰고 탭을 바꿀 때 갈아 끼운다 */
    private class DocTab(var uri: Uri, var canOverwrite: Boolean, var isNewNote: Boolean) {
        var name = "문서"
        var type = DocType.UNKNOWN
        var pdf: PdfDoc? = null
        var ink: InkDocument? = null
        /** 앱 캐시에 복사한 원본 PDF (저장할 때 이 파일에 필기를 얹는다) */
        var sourcePdf: File? = null
        /** 화면에 그리는 PDF (필기를 뺀 사본이거나 [sourcePdf] 그대로) */
        var renderPdf: File? = null
        /** 빈 쪽 넣기·쪽 지우기로 PDF를 다시 만드는 중 */
        var pagesBusy = false
        /** 다른 탭에 가 있는 동안 기억해 둔 스크롤·확대 위치 */
        var viewState: DocumentView.ViewState? = null
    }

    /** 쪽을 넣고 빼기 전·후의 PDF 파일과 그때 보던 쪽 (실행 취소하면 이 상태로 돌아간다) */
    private class PageFiles(val source: File, val render: File, var page: Int)

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

    /** PDF 넣기: 넣을 자리를 고른 뒤 파일 고르기 */
    private var pdfInsertAt: PdfInsertAt? = null
    private val pickPdf = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val at = pdfInsertAt
        pdfInsertAt = null
        if (uri != null && at != null) insertPdfFrom(uri, at)
    }

    /** 그림 넣기: 갤러리·파일에서 그림 고르기 */
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) insertImageFrom(uri)
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
        overviewButton = findViewById(R.id.actionOverview)
        insertButton = findViewById(R.id.actionInsert)

        docView = findViewById(R.id.docView)
        progress = findViewById(R.id.progress)
        pageLabel = findViewById(R.id.pageLabel)
        // 쪽 번호를 누르면 쪽 이동
        pageLabel.setOnClickListener { showGoToPage() }
        colorRow = findViewById(R.id.colorRow)
        widthRow = findViewById(R.id.widthRow)
        colorSep = findViewById(R.id.colorSep)
        shapeBar = findViewById(R.id.shapeBar)
        lassoRow = findViewById(R.id.lassoRow)
        pasteButton = findViewById(R.id.pasteButton)
        selectionBar = findViewById(R.id.selectionBar)
        setupSelectionTools()
        setupEraserTools()
        setupToolbarDock()

        docView.listener = object : DocumentView.Listener {
            override fun onPageChanged(page: Int, count: Int) {
                pageLabel.visibility = View.VISIBLE
                pageLabel.text = "${page + 1} / $count"
            }

            override fun onSelectionChanged(rect: RectF?, count: Int) = placeSelectionBar(rect, count)

            override fun onPenDown() = hideOptionBar()

            override fun onShapeFailed(kind: ShapeKind) {
                Toast.makeText(this@ViewerActivity, "${withRo(kind.label)} 맞추지 못했어요. 조금 더 크게 그려 보세요.", Toast.LENGTH_SHORT).show()
            }
        }

        setupTools()
        // 문서 위에 겹쳐 뜬 줄들의 높이만큼 문서를 더 스크롤할 수 있게 한다
        for (o in listOf(topOverlay, bottomOverlay)) o.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> updateOverlayInsets() }
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
        openTab(uri, writable, intent.getBooleanExtra(EXTRA_NEW_NOTE, false))
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

    private fun openTab(uri: Uri, writable: Boolean, newNote: Boolean) {
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
        val t = DocTab(uri, writable && !newNote, newNote)
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
        overview.hide()
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
            overview.hide()
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
        if (overview.isShowing) {
            overview.hide()
            return
        }
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
        t.renderPdf = renderFile
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
        inkDoc.swapPages = { files, apply -> restorePageFiles(t, files as PageFiles, apply) }
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
        // 쪽을 넣고 빼는 중에는 기다린다 (PDF를 다 만든 뒤에야 기록이 생기므로)
        undoButton.setOnClickListener { if (current?.pagesBusy == false) { docView.clearSelection(); ink?.undo() } }
        redoButton.setOnClickListener { if (current?.pagesBusy == false) { docView.clearSelection(); ink?.redo() } }
        saveButton.setOnClickListener { showSaveMenu(it) }
        overview = PageOverview(findViewById(R.id.overviewPanel), lifecycleScope) { page -> docView.scrollToPage(page) }
        overviewButton.setOnClickListener { toggleOverview() }
        insertButton.setOnClickListener { showInsertMenu(it) }
        findViewById<View>(R.id.actionMore).setOnClickListener { showMoreMenu(it) }
        updateActions()
    }

    /** 지금 탭의 상태에 맞춰 버튼을 켜고 끈다 (예전 invalidateOptionsMenu 자리) */
    private fun updateActions() {
        val inkDoc = ink
        undoButton.setEnabledAlpha(inkDoc?.canUndo == true)
        redoButton.setEnabledAlpha(inkDoc?.canRedo == true)
        saveButton.setEnabledAlpha(inkDoc != null)
        overviewButton.setEnabledAlpha(inkDoc != null)
        insertButton.setEnabledAlpha(inkDoc != null)
    }

    private fun View.setEnabledAlpha(enabled: Boolean) {
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.35f
    }

    private fun showMoreMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.viewer, popup.menu)
        popup.menu.findItem(R.id.action_insert_page).isEnabled = ink != null
        popup.menu.findItem(R.id.action_go_page).isEnabled = (current?.pdf?.pageCount ?: 0) > 1
        popup.menu.findItem(R.id.action_delete_page).isEnabled = (current?.pdf?.pageCount ?: 0) > 1
        popup.menu.findItem(R.id.action_finger).isChecked = docView.fingerDrawing
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_go_page -> showGoToPage()
                R.id.action_insert_plain -> insertBlankPage(Paper.PLAIN)
                R.id.action_insert_grid -> insertBlankPage(Paper.GRID)
                R.id.action_insert_lined -> insertBlankPage(Paper.LINED)
                R.id.action_delete_page -> askDeletePages()
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

    // ================= 삽입 =================

    /** 삽입 ▾: 그림 / PDF */
    private fun showInsertMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "그림").setIcon(R.drawable.ic_image)
        // PDF ▸ 넣을 자리
        val page = docView.currentPage().coerceAtLeast(0) + 1
        val pdf = popup.menu.addSubMenu(0, 2, 1, "PDF")
        pdf.item.setIcon(R.drawable.ic_pdf)
        pdf.add(0, 21, 0, "맨 앞에 넣기")
        pdf.add(0, 22, 1, "지금 보는 ${page}쪽 다음에 넣기")
        pdf.add(0, 23, 2, "맨 뒤에 넣기")
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item ->
            val at = when (item.itemId) {
                21 -> PdfInsertAt.FIRST
                22 -> PdfInsertAt.AFTER_CURRENT
                23 -> PdfInsertAt.LAST
                else -> null
            }
            when {
                item.itemId == 1 -> pickImage.launch("image/*")
                at != null -> {
                    pdfInsertAt = at
                    pickPdf.launch(arrayOf("application/pdf"))
                }
            }
            true
        }
        popup.show()
    }

    private enum class PdfInsertAt { FIRST, AFTER_CURRENT, LAST }

    /** 다른 PDF의 쪽을 넣는다. 그 PDF에 이 앱으로 쓴 필기가 있으면 필기도 함께 (계속 고칠 수 있게) */
    private fun insertPdfFrom(uri: Uri, at: PdfInsertAt) {
        val t = current ?: return
        val d = t.pdf ?: return
        if (t.pagesBusy) return
        val index = when (at) {
            PdfInsertAt.FIRST -> 0
            PdfInsertAt.AFTER_CURRENT -> docView.currentPage().coerceIn(0, d.pageCount - 1) + 1
            PdfInsertAt.LAST -> d.pageCount
        }
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val (file, strokes) = try {
                withContext(Dispatchers.IO) {
                    val name = FileUtil.displayName(this@ViewerActivity, uri)
                    val f = FileUtil.copyToCache(this@ViewerActivity, uri, name)
                    if (FileUtil.detect(name, contentResolver.getType(uri), f) != DocType.PDF) error("PDF 파일이 아닙니다.")
                    if (PdfInk.containsInk(f)) {
                        val clean = FileUtil.tempFile(this@ViewerActivity, "ins_clean", "pdf")
                        clean to PdfInk.extract(f, clean)
                    } else f to List(PdfPages.pageCount(f)) { emptyList<Stroke>() }
                }
            } catch (e: Exception) {
                progress.visibility = View.GONE
                val msg = if (e is SecurityException || e.javaClass.simpleName.contains("Password")) "암호가 걸린 PDF는 넣을 수 없습니다."
                else "PDF를 넣지 못했습니다.\n${e.message ?: e.javaClass.simpleName}"
                MaterialAlertDialogBuilder(this@ViewerActivity).setMessage(msg).setPositiveButton("확인", null).show()
                return@launch
            }
            if (current !== t) {
                progress.visibility = View.GONE
                return@launch
            }
            val done = {
                Toast.makeText(this@ViewerActivity, "${strokes.size}쪽을 ${index + 1}쪽부터 넣었습니다.", Toast.LENGTH_SHORT).show()
            }
            editPages(t, { src, out -> PdfPages.insertPdf(src, out, index, file) }, done) { pages ->
                pages.addAll(index, strokes.map { it.toMutableList() })
                index
            }
        }
    }

    private fun insertImageFrom(uri: Uri) {
        val t = current ?: return
        lifecycleScope.launch {
            val img = try {
                withContext(Dispatchers.IO) { loadImage(uri) }
            } catch (e: Exception) {
                Toast.makeText(this@ViewerActivity, "그림을 읽지 못했습니다.", Toast.LENGTH_SHORT).show()
                return@launch
            }
            if (current !== t) return@launch
            // 넣은 그림을 바로 옮기거나 크기를 바꿀 수 있게 선택 도구로
            selectTool(Tool.LASSO)
            if (!docView.insertImage(img)) Toast.makeText(this@ViewerActivity, "그림을 넣지 못했습니다.", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 그림 읽기: 사진 방향(EXIF)을 바로잡고, 긴 변이 2048px을 넘으면 줄인다.
     * 투명한 부분이 있거나 PNG면 무손실로, 아니면 JPEG로 담아 둔다 (PDF 크기를 줄이려고)
     */
    private fun loadImage(uri: Uri): InkImage {
        val src = ImageDecoder.createSource(contentResolver, uri)
        val bmp = ImageDecoder.decodeBitmap(src) { d, info, _ ->
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val longSide = max(info.size.width, info.size.height)
            if (longSide > MAX_IMAGE_PX) {
                val k = MAX_IMAGE_PX.toFloat() / longSide
                d.setTargetSize((info.size.width * k).toInt().coerceAtLeast(1), (info.size.height * k).toInt().coerceAtLeast(1))
            }
        }
        val lossless = bmp.hasAlpha() || contentResolver.getType(uri) == "image/png"
        val bytes = if (lossless) null else java.io.ByteArrayOutputStream().use {
            bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
            it.toByteArray()
        }
        return InkImage(bmp, bytes)
    }

    // ================= 쪽 한눈에 보기 =================

    private fun toggleOverview() {
        if (overview.isShowing) {
            overview.hide()
            return
        }
        val t = current ?: return
        val d = t.pdf ?: return
        val inkDoc = t.ink ?: return
        hideOptionBar()
        docView.clearSelection()
        overview.show(d, inkDoc, docView.currentPage().coerceAtLeast(0), docView.width, docView.height)
    }

    // ================= 쪽 이동 =================

    /** 쪽 번호를 입력해 그 쪽으로 간다 (쪽 번호 표시나 ⋮ 메뉴에서) */
    private fun showGoToPage() {
        val count = current?.pdf?.pageCount ?: return
        val d = resources.displayMetrics.density
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = EditorInfo.IME_ACTION_GO
            isSingleLine = true
            hint = "1 ~ $count"
            setText("${docView.currentPage() + 1}")
            selectAll()
        }
        val box = FrameLayout(this).apply {
            setPadding((24 * d).toInt(), (4 * d).toInt(), (24 * d).toInt(), 0)
            addView(input)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("쪽 이동")
            .setMessage("몇 쪽으로 갈까요? (전체 ${count}쪽)")
            .setView(box)
            .setPositiveButton("이동", null)
            .setNegativeButton("취소", null)
            .create()
        fun go() {
            val n = input.text.toString().toIntOrNull()
            if (n == null || n !in 1..count) {
                input.error = "1부터 ${count} 사이로 입력해 주세요"
                return
            }
            docView.scrollToPage(n - 1)
            dialog.dismiss()
        }
        // 화면 키보드의 '이동' 또는 실물 키보드의 Enter
        input.setOnEditorActionListener { _, id, ev ->
            val enter = ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (id == EditorInfo.IME_ACTION_GO || enter) { go(); true } else false
        }
        dialog.setOnShowListener {
            // 이동 버튼은 잘못된 번호면 창을 닫지 않는다
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { go() }
            // 바로 숫자를 칠 수 있게 키보드를 띄운다
            input.requestFocus()
            dialog.window?.let { WindowCompat.getInsetsController(it, input).show(WindowInsetsCompat.Type.ime()) }
        }
        dialog.show()
    }

    // ================= 쪽 넣기 · 지우기 =================

    /** 보고 있는 쪽 뒤에 같은 크기의 빈 쪽을 넣고 그 쪽으로 간다 */
    private fun insertBlankPage(paper: Paper) {
        val t = current ?: return
        val d = t.pdf ?: return
        val page = docView.currentPage().coerceIn(0, d.pageCount - 1)
        val size = d.sizes[page]
        val at = page + 1
        editPages(t, { src, out -> PdfPages.insert(src, out, at, paper, size.width, size.height) }) { pages ->
            pages.add(at, mutableListOf())
            at
        }
    }

    /** 쪽 지우기: 지금 쪽만 / n쪽부터 m쪽까지 (실행 취소로 되돌릴 수 있다) */
    private fun askDeletePages() {
        val t = current ?: return
        val d = t.pdf ?: return
        if (d.pageCount <= 1) return
        val page = docView.currentPage().coerceIn(0, d.pageCount - 1)
        val choices = arrayOf("지금 보는 ${page + 1}쪽만", "쪽 범위 지정 (n쪽부터 m쪽까지)")
        MaterialAlertDialogBuilder(this)
            .setTitle("쪽 지우기")
            .setItems(choices) { _, which ->
                if (which == 0) deletePages(t, page, page)
                else askPageRange(
                    t, page, page, "지울 쪽", "지우기",
                    check = { n, m -> if (m - n + 1 >= d.pageCount) "모든 쪽을 지울 수는 없습니다" else null },
                ) { n, m -> deletePages(t, n - 1, m - 1) }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** [from]~[to]번째 쪽(0부터)을 필기와 함께 지운다 */
    private fun deletePages(t: DocTab, from: Int, to: Int) {
        val done = {
            val what = if (from == to) "${from + 1}쪽을" else "${from + 1}~${to + 1}쪽을"
            Toast.makeText(this, "$what 지웠습니다. 실행 취소로 되돌릴 수 있습니다.", Toast.LENGTH_SHORT).show()
        }
        editPages(t, { src, out -> PdfPages.remove(src, out, from, to) }, done) { pages ->
            repeat(to - from + 1) { pages.removeAt(from) }
            from.coerceAtMost(pages.size - 1)
        }
    }

    /**
     * 탭의 PDF 쪽 구성을 바꾼다. [pdfOp]로 원본·화면용 PDF를 새로 만들고,
     * 다 되면 [applyInk]로 필기 쪽 목록을 같이 맞춘 뒤 돌려준 쪽으로 옮긴다.
     * 바꾸기 전 파일은 지우지 않고 실행 취소 기록에 남겨 둔다.
     */
    private fun editPages(
        t: DocTab, pdfOp: (File, File) -> Unit, onDone: (() -> Unit)? = null,
        applyInk: (MutableList<MutableList<Stroke>>) -> Int,
    ) {
        val src = t.sourcePdf ?: return
        val render = t.renderPdf ?: return
        val inkDoc = t.ink ?: return
        if (t.pagesBusy) return
        t.pagesBusy = true
        overview.hide()
        docView.clearSelection()
        val before = PageFiles(src, render, docView.currentPage().coerceAtLeast(0))
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val (newSrc, newRender) = withContext(Dispatchers.IO) {
                    val ns = FileUtil.tempFile(this@ViewerActivity, "pages_src", "pdf")
                    pdfOp(src, ns)
                    val nr = if (render == src) ns
                    else FileUtil.tempFile(this@ViewerActivity, "pages_view", "pdf").also { pdfOp(render, it) }
                    ns to nr
                }
                val nd = PdfDoc.open(newRender)
                if (t !in docs) {
                    nd.close()
                    return@launch
                }
                val old = t.pdf
                t.pdf = nd
                t.sourcePdf = newSrc
                t.renderPdf = newRender
                val after = PageFiles(newSrc, newRender, 0)
                inkDoc.changePages(before, after) { pages -> after.page = applyInk(pages) }
                val target = after.page
                if (current === t) {
                    docView.setDocument(nd, inkDoc, docView.viewState())
                    docView.post { docView.scrollToPage(target) }
                } else t.viewState = null
                old?.close()
                updateTabTitle(t)
                onDone?.invoke()
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@ViewerActivity)
                    .setMessage("쪽을 바꾸지 못했습니다.\n${e.message ?: e.javaClass.simpleName}")
                    .setPositiveButton("확인", null)
                    .show()
            } finally {
                t.pagesBusy = false
                if (current === t) progress.visibility = View.GONE
            }
        }
    }

    /** 쪽 넣기·지우기를 실행 취소·다시 실행하면, 그때의 PDF를 연 뒤 [apply]로 필기 쪽 목록도 맞춘다 */
    private fun restorePageFiles(t: DocTab, f: PageFiles, apply: () -> Boolean) {
        val inkDoc = t.ink ?: return
        if (t.pagesBusy) return
        t.pagesBusy = true
        overview.hide()
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val nd = PdfDoc.open(f.render)
                // 탭이 닫혔거나, 여는 사이 새 필기로 기록이 바뀌었으면 그만둔다
                if (t !in docs || !apply()) {
                    nd.close()
                    return@launch
                }
                val old = t.pdf
                t.pdf = nd
                t.sourcePdf = f.source
                t.renderPdf = f.render
                if (current === t) {
                    docView.setDocument(nd, inkDoc, docView.viewState())
                    docView.post { docView.scrollToPage(f.page.coerceIn(0, nd.pageCount - 1)) }
                } else t.viewState = null
                old?.close()
                updateTabTitle(t)
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@ViewerActivity)
                    .setMessage("쪽을 되돌리지 못했습니다.\n${e.message ?: e.javaClass.simpleName}")
                    .setPositiveButton("확인", null)
                    .show()
            } finally {
                t.pagesBusy = false
                if (current === t) progress.visibility = View.GONE
            }
        }
    }

    // ================= 저장 =================

    /** 저장 ▾: 저장 / 다른 이름으로 저장 / 이미지로 저장 */
    private fun showSaveMenu(anchor: View) {
        val t = current ?: return
        if (t.ink == null) return
        val popup = PopupMenu(this, anchor)
        popup.menu.add(0, 1, 0, "저장").setIcon(R.drawable.ic_save)
        popup.menu.add(0, 2, 1, "다른 이름으로 저장").setIcon(R.drawable.ic_save_as)
        popup.menu.add(0, 3, 2, "이미지로 저장").setIcon(R.drawable.ic_image)
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> save(t, asNew = false)
                2 -> save(t, asNew = true)
                3 -> askExportImages(t)
            }
            true
        }
        popup.show()
    }

    /** 이미지로 저장할 쪽 고르기: 지금 쪽만 / 모든 쪽 / n쪽부터 m쪽까지 */
    private fun askExportImages(t: DocTab) {
        val d = t.pdf ?: return
        val page = docView.currentPage().coerceIn(0, d.pageCount - 1)
        val choices = arrayOf("지금 보는 ${page + 1}쪽만", "모든 쪽 (${d.pageCount}쪽)", "쪽 범위 지정 (n쪽부터 m쪽까지)")
        MaterialAlertDialogBuilder(this)
            .setTitle("이미지로 저장")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> exportImages(t, listOf(page))
                    1 -> exportImages(t, (0 until d.pageCount).toList())
                    else -> askPageRange(t, page, d.pageCount - 1, "이미지로 저장할 쪽", "저장") { n, m ->
                        exportImages(t, (n - 1 until m).toList())
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 쪽 범위 입력: [ n ] 쪽부터 [ m ] 쪽까지 (처음 값은 [first]~[last], 0부터 센 쪽).
     * [check]가 문구를 돌려주면 그 문구를 띄우고 창을 닫지 않는다. [onOk]는 1부터 센 n, m을 받는다
     */
    private fun askPageRange(
        t: DocTab, first: Int, last: Int, title: String, okLabel: String,
        check: ((Int, Int) -> String?)? = null, onOk: (Int, Int) -> Unit,
    ) {
        val count = t.pdf?.pageCount ?: return
        val d = resources.displayMetrics.density
        fun numberField(value: Int, action: Int) = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            imeOptions = action
            isSingleLine = true
            gravity = android.view.Gravity.CENTER
            minEms = 3
            setText("$value")
            setSelectAllOnFocus(true)
        }
        fun label(text: String) = TextView(this).apply {
            this.text = text
            setPadding((6 * d).toInt(), 0, (14 * d).toInt(), 0)
        }
        val from = numberField(first + 1, EditorInfo.IME_ACTION_NEXT)
        val to = numberField(last + 1, EditorInfo.IME_ACTION_DONE)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding((24 * d).toInt(), (4 * d).toInt(), (24 * d).toInt(), 0)
            addView(from)
            addView(label("쪽부터"))
            addView(to)
            addView(label("쪽까지"))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage("전체 ${count}쪽")
            .setView(row)
            .setPositiveButton(okLabel, null)
            .setNegativeButton("취소", null)
            .create()
        fun go() {
            val n = from.text.toString().toIntOrNull()
            val m = to.text.toString().toIntOrNull()
            when {
                n == null || n !in 1..count -> from.error = "1부터 ${count} 사이로 입력해 주세요"
                m == null || m !in 1..count -> to.error = "1부터 ${count} 사이로 입력해 주세요"
                n > m -> to.error = "시작 쪽(${n}쪽)보다 작을 수 없습니다"
                else -> {
                    val problem = check?.invoke(n, m)
                    if (problem != null) {
                        to.error = problem
                        return
                    }
                    dialog.dismiss()
                    onOk(n, m)
                }
            }
        }
        // 실물 키보드 Enter나 화면 키보드 '완료'로 바로 실행
        to.setOnEditorActionListener { _, id, ev ->
            val enter = ev?.keyCode == KeyEvent.KEYCODE_ENTER && ev.action == KeyEvent.ACTION_DOWN
            if (id == EditorInfo.IME_ACTION_DONE || enter) { go(); true } else false
        }
        dialog.setOnShowListener {
            // 잘못된 번호면 창을 닫지 않는다
            dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener { go() }
            from.requestFocus()
            dialog.window?.let { WindowCompat.getInsetsController(it, from).show(WindowInsetsCompat.Type.ime()) }
        }
        dialog.show()
    }

    /**
     * 쪽을 필기·그림과 함께 PNG로 그려 사진첩(Pictures/DSnote)에 넣는다. 150dpi (A4 한 쪽 약 1240×1754)
     */
    private fun exportImages(t: DocTab, pages: List<Int>) {
        val d = t.pdf ?: return
        val inkDoc = t.ink ?: return
        val base = FileUtil.baseName(t.name).replace(Regex("[\\\\/:*?\"<>|]"), "_")
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val paint = inkPaint()
                val scale = EXPORT_DPI / 72f
                for (p in pages) {
                    val size = d.sizes[p]
                    val w = (size.width * scale).roundToInt().coerceAtLeast(1)
                    val h = (size.height * scale).roundToInt().coerceAtLeast(1)
                    val bmp = withContext(d.dispatcher) { d.render(p, scale, 0f, 0f, w, h) }
                    // 필기 얹기 (획의 경로 캐시를 문서 화면과 같이 쓰므로 메인 스레드에서). 그림을 먼저
                    val c = Canvas(bmp)
                    c.scale(scale, scale)
                    inkDoc.pages.getOrNull(p)?.sortedBy { if (it.image != null) 0 else 1 }?.forEach { drawInkStroke(c, paint, it) }
                    withContext(Dispatchers.IO) { saveToGallery(bmp, "${base}_${p + 1}쪽.png") }
                    bmp.recycle()
                }
                Toast.makeText(
                    this@ViewerActivity, "${pages.size}장을 사진첩(Pictures/DSnote)에 저장했습니다.", Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@ViewerActivity)
                    .setMessage("이미지로 저장하지 못했습니다.\n${e.message ?: e.javaClass.simpleName}")
                    .setPositiveButton("확인", null)
                    .show()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun saveToGallery(bmp: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DSnote")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("사진첩에 저장할 곳을 만들지 못했습니다.")
        try {
            contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: error("사진첩에 쓸 수 없습니다.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun suggestedName(t: DocTab): String {
        if (t.isNewNote) return "노트 ${DateFormat.format("yyyy-MM-dd", Date())}.pdf"
        val base = FileUtil.baseName(t.name)
        return if (t.type == DocType.PDF) "${base}_필기.pdf" else "$base.pdf"
    }

    private fun save(t: DocTab, asNew: Boolean) {
        if (t.ink == null) return
        if (t.pagesBusy) {
            Toast.makeText(this, "쪽을 바꾸는 중입니다. 잠시 뒤에 저장해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
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
                if (!overwrite) adoptSavedFile(t, target)
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

    /**
     * 다른 이름으로 저장(새 노트·한글 문서의 첫 저장 포함)하면 그 파일을 이 탭의 문서로 삼는다
     * (다음부터는 '저장'이 그 파일에 덮어쓴다)
     */
    private fun adoptSavedFile(t: DocTab, target: Uri) {
        runCatching {
            contentResolver.takePersistableUriPermission(
                target, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        t.uri = target
        t.isNewNote = false
        t.canOverwrite = true
        t.type = DocType.PDF  // 한글 문서도 PDF로 저장되므로
        t.name = FileUtil.displayName(this, target)
        Recents.add(this, target.toString(), t.name)
        updateTabTitle(t)
    }

    /** '직선으로', '지수로', '원으로' (받침이 없거나 ㄹ받침이면 '로') */
    private fun withRo(word: String): String {
        val last = word.lastOrNull() ?: return word
        if (last !in '가'..'힣') return word + "로"
        val jong = (last - '가') % 28
        return word + if (jong == 0 || jong == 8) "로" else "으로"
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

        // 자유 선택 / 네모 선택 (고르는 곳은 옵션 줄)
        docView.lassoRect = prefs.getBoolean("lassoRect", false)
    }

    // ================= 툴바 자리 =================
    // 툴바 맨 앞 손잡이를 끌어 위·아래·왼쪽·오른쪽에 붙인다 (ToolbarDock). 자리는 기억해 둔다.

    private fun setupToolbarDock() {
        topOverlay = findViewById(R.id.topOverlay)
        bottomOverlay = findViewById(R.id.bottomOverlay)
        dock = ToolbarDock(
            root = findViewById(R.id.root),
            docRow = findViewById(R.id.docRow),
            toolbar = findViewById(R.id.toolbar),
            handle = findViewById(R.id.toolbarHandle),
            scrollH = findViewById(R.id.toolScrollH),
            content = findViewById(R.id.toolContent),
        ) { side ->
            placeOverlays(side)
            prefs.edit().putString("toolbarSide", side.name).apply()
        }
        val saved = prefs.getString("toolbarSide", null)
        dock.dock(ToolbarSide.entries.firstOrNull { it.name == saved } ?: ToolbarSide.BOTTOM)
    }

    /**
     * 도형 줄·옵션 줄은 툴바가 위면 문서 위쪽에, 아니면 아래쪽에 겹쳐 띄운다 (옵션 줄이 툴바에 가깝게).
     * 세로 툴바에서는 '붙여넣기'를 아이콘만 보인다
     */
    private fun placeOverlays(side: ToolbarSide) {
        hideOptionBar()
        val top = side == ToolbarSide.TOP
        val target = if (top) topOverlay else bottomOverlay
        for (v in if (top) listOf(optionBar, shapeBar) else listOf(shapeBar, optionBar)) {
            (v.parent as? ViewGroup)?.removeView(v)
            target.addView(v)
        }
        val d = resources.displayMetrics.density
        // 툴바에서 먼 쪽에 여백을 둔다
        findViewById<View>(R.id.shapeChips).let {
            it.setPadding(it.paddingLeft, if (top) 0 else (6 * d).toInt(), it.paddingRight, if (top) (6 * d).toInt() else 0)
        }
        findViewById<View>(R.id.toolOptions).let {
            it.setPadding(it.paddingLeft, ((if (top) 2 else 6) * d).toInt(), it.paddingRight, ((if (top) 6 else 2) * d).toInt())
        }
        (pasteButton as MaterialButton).apply {
            text = if (side.vertical) "" else "붙여넣기"
            iconPadding = if (side.vertical) 0 else (8 * d).toInt()
            tooltipText = "붙여넣기"
        }
        updateOverlayInsets()
    }

    private fun updateOverlayInsets() {
        fun barsHeight(o: ViewGroup) = (0 until o.childCount).map { o.getChildAt(it) }
            .filter { it === shapeBar || it === optionBar }
            .sumOf { if (it.visibility == View.VISIBLE) it.height else 0 }.toFloat()
        docView.topInset = barsHeight(topOverlay)
        docView.bottomInset = barsHeight(bottomOverlay)
    }

    // ================= 지우개 · 선택 옵션 줄 =================
    // 지우개나 선택 도구를 누르면 도구막대 위로 옵션 줄이 올라오고, 하나 고르면 사라진다.
    // 도구 버튼 아이콘은 지금 고른 방식을 보여 준다.

    private lateinit var optionBar: View
    private lateinit var optionRow: LinearLayout
    /** 옵션 줄이 떠 있는 도구 (없으면 null) */
    private var optionTool: Tool? = null

    private fun setupEraserTools() {
        optionBar = findViewById(R.id.toolOptionBar)
        optionRow = findViewById(R.id.toolOptions)
        docView.eraserMode = prefs.getString("eraserMode", null)
            ?.let { n -> EraserMode.entries.firstOrNull { it.name == n } } ?: EraserMode.STROKE
        docView.eraseHlOnly = prefs.getBoolean("eraseHlOnly", false)
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

    /** 선택 버튼: 자유 선택이면 올가미, 네모 선택이면 점선 네모 */
    private fun updateLassoIcon() {
        val button = toolButtons.getValue(Tool.LASSO)
        button.setImageResource(if (docView.lassoRect) R.drawable.ic_select_rect else R.drawable.ic_lasso)
        button.contentDescription = if (docView.lassoRect) "네모 선택" else "자유 선택"
    }

    private fun showOptionBar(t: Tool) {
        optionRow.removeAllViews()
        when (t) {
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
                addOption(R.drawable.ic_eraser_page, if (hl) "쪽 형광펜 모두 지우기" else "쪽 전체 지우기", false) {
                    confirmClearPage()
                }
            }
            Tool.HIGHLIGHTER -> {
                addOption(R.drawable.ic_highlighter, "자유 형광펜", !docView.hlStraight) { setHlStraight(false) }
                addOption(R.drawable.ic_ruler, "직선 형광펜", docView.hlStraight) { setHlStraight(true) }
            }
            Tool.LASER -> {
                // 레이저가 사라지는 시간 (색·굵기는 아래 도구막대)
                for (sec in intArrayOf(1, 2, 3, 5)) {
                    addOption(R.drawable.ic_timer, "${sec}초 뒤 사라짐", docView.laserFadeMs == sec * 1000L) {
                        docView.laserFadeMs = sec * 1000L
                        prefs.edit().putLong("laserFadeMs", sec * 1000L).apply()
                    }
                }
            }
            Tool.LASSO -> {
                addOption(R.drawable.ic_lasso, "자유 선택", !docView.lassoRect) { setLassoRect(false) }
                addOption(R.drawable.ic_select_rect, "네모 선택", docView.lassoRect) { setLassoRect(true) }
            }
            else -> return
        }
        optionTool = t
        optionBar.visibility = View.VISIBLE
        // 툴바 쪽에서 살짝 밀려 나오며 나타난다 (툴바가 위면 위에서 내려온다)
        optionBar.animate().cancel()
        optionBar.alpha = 0f
        optionBar.translationY = (if (dock.side == ToolbarSide.TOP) -12 else 12) * resources.displayMetrics.density
        optionBar.animate().alpha(1f).translationY(0f).setDuration(150).start()
    }

    private fun hideOptionBar() {
        if (optionTool == null) return
        optionTool = null
        optionBar.animate().cancel()
        optionBar.visibility = View.GONE
    }

    private fun setEraserMode(m: EraserMode) {
        docView.eraserMode = m
        prefs.edit().putString("eraserMode", m.name).apply()
        updateEraserIcon()
    }

    private fun setHlStraight(on: Boolean) {
        docView.hlStraight = on
        prefs.edit().putBoolean("hlStraight", on).apply()
        updateHighlighterIcon()
    }

    /** 형광펜 버튼: 직선(줄자)이면 오른쪽 아래에 작은 자 */
    private fun updateHighlighterIcon() {
        val button = toolButtons.getValue(Tool.HIGHLIGHTER)
        button.setImageResource(if (docView.hlStraight) R.drawable.ic_highlighter_ruler else R.drawable.ic_highlighter)
        button.contentDescription = if (docView.hlStraight) "직선 형광펜" else "형광펜"
        updateToolMarks()
    }

    private fun setLassoRect(rect: Boolean) {
        docView.lassoRect = rect
        prefs.edit().putBoolean("lassoRect", rect).apply()
        updateLassoIcon()
    }

    /** 옵션 줄의 칸 하나 (아이콘 + 이름). 누르면 옵션 줄을 닫고 실행한다 */
    private fun addOption(icon: Int, label: String, selected: Boolean, onClick: () -> Unit) {
        val d = resources.displayMetrics.density
        val item = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER_HORIZONTAL
            minimumWidth = (72 * d).toInt()
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (4 * d).toInt())
            setBackgroundResource(R.drawable.bg_tool)
            isSelected = selected
            contentDescription = if (selected) "$label (선택됨)" else label
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = (4 * d).toInt() }
            setOnClickListener {
                hideOptionBar()
                onClick()
            }
        }
        item.addView(ImageView(this).apply {
            setImageResource(icon)
            layoutParams = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        item.addView(TextView(this).apply {
            text = label
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelMedium)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        optionRow.addView(item)
    }

    private fun addOptionSeparator() {
        val d = resources.displayMetrics.density
        optionRow.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams((1 * d).toInt(), (32 * d).toInt()).apply {
                marginStart = (6 * d).toInt()
                marginEnd = (10 * d).toInt()
            }
            setBackgroundColor(com.google.android.material.color.MaterialColors.getColor(
                this, com.google.android.material.R.attr.colorOutlineVariant
            ))
        })
    }

    /** 보고 있는 쪽의 필기(형광펜만 켜 두었으면 형광펜만)를 모두 지운다. 실행 취소로 되돌릴 수 있다 */
    private fun confirmClearPage() {
        val inkDoc = ink ?: return
        val page = docView.currentPage()
        if (page < 0) return
        val hl = docView.eraseHlOnly
        val (subj, obj) = if (hl) "형광펜이" to "형광펜을" else "필기가" to "필기를"
        if (inkDoc.pages[page].none { it.image == null && (!hl || it.tool == Tool.HIGHLIGHTER) }) {
            Toast.makeText(this, "${page + 1}쪽에는 지울 ${subj} 없습니다.", Toast.LENGTH_SHORT).show()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("${page + 1}쪽의 ${obj} 모두 지울까요?")
            .setMessage("실행 취소로 되돌릴 수 있습니다.")
            .setPositiveButton("지우기") { _, _ -> docView.clearPage(hl) }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 선택 상자 바로 위(자리가 없으면 아래)에 '삭제' 막대를 띄운다 */
    private fun placeSelectionBar(rect: RectF?, count: Int) {
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

    private fun slotsOf(t: Tool) = when (t) {
        Tool.HIGHLIGHTER -> hlSlots
        Tool.LASER -> laserSlots
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
            else -> docView.penColor = penSlots.color
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
        ColorPickerPopup(this, s.color, recentColors(t)) { c, done ->
            s.slots[s.active] = c
            applyToolColor(t)
            buildColors()
            if (done) {
                saveSlots(s)
                addRecent(t, c)
            }
        }.show(anchor, dock.side)
    }

    private fun setupTools() {
        toolButtons = mapOf(
            Tool.PEN to findViewById(R.id.toolPen),
            Tool.SHAPE to findViewById(R.id.toolShape),
            Tool.HIGHLIGHTER to findViewById(R.id.toolHighlighter),
            Tool.ERASER to findViewById(R.id.toolEraser),
            Tool.LASSO to findViewById(R.id.toolLasso),
            Tool.LASER to findViewById(R.id.toolLaser),
        )
        penSlots = loadSlots("pen", penDefaults)
        hlSlots = loadSlots("hl", hlDefaults)
        laserSlots = loadSlots("laser", laserDefaults)
        applyToolColor(Tool.PEN)
        applyToolColor(Tool.HIGHLIGHTER)
        applyToolColor(Tool.LASER)
        docView.laserFadeMs = prefs.getLong("laserFadeMs", 2000L)
        docView.hlStraight = prefs.getBoolean("hlStraight", false)
        widthSlots = listOf(Tool.PEN, Tool.HIGHLIGHTER, Tool.ERASER, Tool.LASER).associateWith { loadWidths(WidthKind.of(it)) }
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
        updateEraserIcon()
        updateLassoIcon()
        updateHighlighterIcon()
        toolButtons.forEach { (t, b) ->
            b.setOnClickListener {
                if (t == Tool.ERASER || t == Tool.LASSO || t == Tool.LASER || t == Tool.HIGHLIGHTER) {
                    // 형광펜·지우개·선택·레이저는 누를 때마다 옵션 줄을 열고, 열려 있으면 닫는다
                    val showing = optionTool == t
                    selectTool(t)
                    if (showing) hideOptionBar() else showOptionBar(t)
                } else selectTool(t)
            }
        }
        selectTool(Tool.PEN)
    }

    private fun selectTool(t: Tool) {
        if (optionTool != t) hideOptionBar()
        docView.tool = t
        toolButtons.forEach { (k, b) -> b.isSelected = k == t }
        shapeBar.visibility = if (t == Tool.SHAPE) View.VISIBLE else View.GONE
        buildColors()
    }

    // ----- 보정 펜 도형 줄 -----

    private fun setupShapeBar() {
        val group = findViewById<ChipGroup>(R.id.shapeChips)
        docView.shapeKind = prefs.getString("shapeKind", null)
            // 지수·로그 버튼이 따로 있던 때 고른 값
            ?.let { n -> if (n == "EXPONENTIAL" || n == "LOG") ShapeKind.EXP_LOG.name else n }
            ?.let { n -> ShapeKind.entries.firstOrNull { it.name == n } } ?: ShapeKind.LINE
        // 펼쳐 고르는 무리: 칩에는 지금 고른 종류 이름이 보이고, 누르면 펼쳐진다
        val families = mapOf(
            // 다항: 직선(일차)·이차·삼차·사차
            ShapeKind.LINE to listOf(ShapeKind.LINE, ShapeKind.QUADRATIC, ShapeKind.CUBIC, ShapeKind.QUARTIC),
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
        val order = listOf(
            "다항" to ShapeKind.LINE, "원" to ShapeKind.CIRCLE, "쌍곡선" to ShapeKind.HYPERBOLA,
            "삼각형" to ShapeKind.TRIANGLE, "사각형" to ShapeKind.QUADRILATERAL,
            "지수·로그" to ShapeKind.EXP_LOG,
            "사인·코사인" to ShapeKind.SINE, "탄젠트" to ShapeKind.TANGENT,
        )
        fun selectKind(kind: ShapeKind) {
            docView.shapeKind = kind
            docView.shapeGuide = guideStyle(kind)
            prefs.edit().putString("shapeKind", kind.name).apply()
        }
        docView.shapeGuide = guideStyle(docView.shapeKind)
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
            } else {
                chip.tag = kind
                chip.isCheckable = true
                group.addView(chip)
                if (kind == docView.shapeKind) group.check(chip.id)
                // 점근선·축이 있는 도형: 누르면 위로 보조선 방식 고르는 창
                if (kind in GUIDE_KINDS) {
                    chip.text = "$label ▾"
                    chip.setOnClickListener { v ->
                        group.check(chip.id)
                        showGuideMenu(v, kind)
                    }
                }
            }
        }


        group.setOnCheckedStateChangeListener { g, ids ->
            val kind = ids.firstOrNull()?.let { g.findViewById<Chip>(it)?.tag as? ShapeKind } ?: return@setOnCheckedStateChangeListener
            selectKind(kind)
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
        PopupMenu(this, anchor).apply {
            styles.forEachIndexed { i, st ->
                menu.add(1, i, i, "$what ${names.getValue(st)}").isChecked = st == cur
            }
            menu.setGroupCheckable(1, true, true)
            setOnMenuItemClickListener { item ->
                val st = styles[item.itemId]
                prefs.edit().putString("guide_${kind.name}", st.name).apply()
                if (docView.shapeKind == kind) docView.shapeGuide = st
                true
            }
            show()
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
            Tool.LASER -> docView.laserWidthDp = v
            Tool.LASSO -> {}
        }
    }

    private fun toolColor(t: Tool) = when (t) {
        Tool.PEN, Tool.SHAPE -> docView.penColor
        Tool.HIGHLIGHTER -> docView.hlColor
        Tool.LASER -> docView.laserColor
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
            // 툴바 방향으로 좁은 칸 (가로 툴바 기준 너비 28dp × 높이 40dp, 세로 툴바면 dock.fit이 맞바꾼다)
            val v = WidthSwatchView(this).apply {
                layoutParams = LinearLayout.LayoutParams((28 * d).toInt(), (40 * d).toInt()).apply {
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
        val s = widthSlots.getValue(widthTool(t))
        WidthPopup(this, s.kind, s.value, toolColor(t)) { w, done ->
            s.slots[s.active] = w
            applyToolWidth(t)
            (widthRow.getChildAt(s.active) as? WidthSwatchView)?.value = w
            if (done) saveWidths(s)
        }.show(anchor, dock.side)
    }

    /** 펜 아래 S자 곡선과 형광펜 아래 줄을 지금 고른 색으로 칠한다 */
    private fun updateToolMarks() {
        for ((t, color) in listOf(Tool.PEN to docView.penColor, Tool.HIGHLIGHTER to docView.hlColor, Tool.LASER to docView.laserColor)) {
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
        s.slots.forEachIndexed { i, c ->
            // 모서리가 둥근 막대. 툴바 방향으로 좁게 (가로 툴바 기준 20dp × 36dp, 세로 툴바면 dock.fit이 맞바꾼다)
            val v = View(this)
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
        val palette = ImageButton(this).apply {
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
        const val EXTRA_WRITABLE = "writable"
        /** 앱의 파일 탐색기에서 연 문서 */
        const val EXTRA_FROM_BROWSER = "fromBrowser"
        /** 탐색기의 '새 노트'로 만든 빈 문서 (처음 저장할 때 저장 위치를 고른다) */
        const val EXTRA_NEW_NOTE = "newNote"
        private const val MAX_TABS = 6
        /** 이미지로 저장할 때 해상도 */
        private const val EXPORT_DPI = 150f
        /** 넣는 그림의 긴 변 최대 픽셀 */
        private const val MAX_IMAGE_PX = 2048
        /** 보조선(점근선·축)을 고를 수 있는 보정 펜 도형 */
        private val GUIDE_KINDS = setOf(ShapeKind.HYPERBOLA, ShapeKind.EXP_LOG, ShapeKind.TANGENT, ShapeKind.SINE)
        private val TAB_ICON_PDF = Color.parseColor("#D93025")
        private val TAB_ICON_HWP = Color.parseColor("#2F6FC4")
        private val TAB_ICON_GRAY = Color.parseColor("#9E9E9E")
        /** 열려 있는 탭 수 (탐색기의 '열린 문서' 버튼용) */
        var openTabs = 0
            private set
    }
}
