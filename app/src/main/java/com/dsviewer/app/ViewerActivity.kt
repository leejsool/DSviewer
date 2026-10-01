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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.IntentCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.dsviewer.app.conv.DocConvert
import com.dsviewer.app.conv.ImagePdf
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
    /** 지금 쓰는 도형 줄: 가로 줄([shapeBarH]) 또는 세로 줄([shapeBarV]). 칸들은 [shapeRow]에 */
    private lateinit var shapeBar: View
    private lateinit var shapeBarH: View
    private lateinit var shapeBarV: View
    private lateinit var shapeRow: LinearLayout
    private lateinit var lassoRow: View
    private lateinit var pasteButton: View
    private lateinit var selectionBar: View
    private lateinit var toolButtons: Map<Tool, ImageButton>
    private lateinit var dock: ToolbarDock
    private lateinit var textEditor: InlineTextEditor
    private lateinit var topOverlay: LinearLayout
    private lateinit var bottomOverlay: LinearLayout

    private lateinit var docTabs: ChromeTabBar
    private lateinit var undoButton: View
    private lateinit var redoButton: View
    private lateinit var saveButton: View
    private lateinit var overviewButton: ImageButton
    private lateinit var insertButton: View
    private lateinit var overview: PagePanel
    private lateinit var pagePanel: PagePanel
    private lateinit var pagesButton: ImageButton
    private lateinit var twoPageButton: ImageButton
    private lateinit var readModeButton: ImageButton
    private lateinit var fullscreenButton: ImageButton
    private lateinit var exitFullscreenButton: View
    /** 읽기 모드: 툴바를 숨기고 펜으로도 넘겨 보기만 한다 */
    private var readMode = false
    /** 전체 화면: 탭 줄·상태 표시줄·페이지 관리 창을 숨기고 툴바만 남긴다 */
    private var fullscreen = false

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
        /** 글자 찾기 (처음 찾을 때 만든다. 쪽을 바꾸면 화면용 PDF가 바뀌어 새로 만든다) */
        var search: DocSearch? = null
        /** PDF 링크 (읽기 모드에서 처음 누를 때 꺼낸다. 쪽을 바꾸면 새로) */
        var links: DocLinks? = null
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
    private var imageImportMode = ImageImportMode.IN_PAGE
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            if (imageImportMode == ImageImportMode.NEW_PAGE) insertImagePageFrom(uri)
            else insertImageFrom(uri)
        }
    }

    private enum class ImageImportMode { IN_PAGE, NEW_PAGE }

    /** 다른 앱 화면 가져오기: 찍은 화면을 넣을 탭과 자리 (시작할 때 보던 쪽 다음) */
    private var captureTab: DocTab? = null
    private var captureIndex = 0

    /** '다른 앱 위에 표시' 권한 설정에서 돌아오면 이어서 화면 전송 허락을 묻는다 */
    private val overlayPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (android.provider.Settings.canDrawOverlays(this)) requestProjection()
        else toast("'다른 앱 위에 표시'를 허용해야 화면을 가져올 수 있습니다.")
    }

    private val projectionConsent = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode != RESULT_OK || data == null) { captureTab = null; return@registerForActivityResult }
        CaptureService.start(this, r.resultCode, data)
        // 뷰어를 뒤로 보내 바로 전에 쓰던 앱이 보이게 한다
        moveTaskToBack(true)
    }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() = goBack()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 기기가 다크 모드여도 밝은 바탕에 맞춰 상태 표시줄 아이콘을 어둡게
        enableEdgeToEdge(
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_viewer)
        // 화면 키보드가 올라오면 그 높이만큼 화면 전체를 줄여 아래 툴바·서식 줄이 키보드 위에 보이게 한다
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root)) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, maxOf(b.bottom, ime.bottom))
            insets
        }

        undoButton = findViewById(R.id.actionUndo)
        redoButton = findViewById(R.id.actionRedo)
        saveButton = findViewById(R.id.actionSave)
        overviewButton = findViewById(R.id.actionOverview)
        insertButton = findViewById(R.id.actionInsert)

        docView = findViewById(R.id.docView)
        textEditor = InlineTextEditor(findViewById(R.id.textEditHost), docView)
        progress = findViewById(R.id.progress)
        pageLabel = findViewById(R.id.pageLabel)
        // 쪽 번호를 누르면 쪽 이동
        pageLabel.setOnClickListener { showGoToPage() }
        colorRow = findViewById(R.id.colorRow)
        widthRow = findViewById(R.id.widthRow)
        colorSep = findViewById(R.id.colorSep)
        shapeBar = findViewById(R.id.shapeBar)
        shapeRow = findViewById(R.id.shapeRow)
        lassoRow = findViewById(R.id.lassoRow)
        pasteButton = findViewById(R.id.pasteButton)
        selectionBar = findViewById(R.id.selectionBar)
        setupSelectionTools()
        setupEraserTools()
        setupToolbarDock()

        docView.listener = object : DocumentView.Listener {
            override fun onPageChanged(page: Int, count: Int) {
                pageLabel.visibility = View.VISIBLE
                // 양쪽 보기면 나란히 놓인 두 쪽을 '1-2 / 20'처럼
                pageLabel.text = if (docView.isSpread && page + 1 < count) "${page + 1}-${page + 2} / $count" else "${page + 1} / $count"
                pagePanel.setCurrent(page)
                overview.setCurrent(page)
            }

            override fun onSelectionChanged(rect: RectF?, count: Int) = placeSelectionBar(rect, count)

            override fun onPenDown() {
                hideOptionBar()
                closeFlyout()
            }

            override fun onTextTap(page: Int, x: Float, y: Float, existing: Stroke?) =
                this@ViewerActivity.onTextTap(page, x, y, existing)

            override fun onViewportChanged() = textEditor.reposition()

            override fun onPullAddPage() = appendBlankPage()

            override fun onReadTap(page: Int, x: Float, y: Float) = followLinkAt(page, x, y)

            override fun onFillFailed() {
                Toast.makeText(
                    this@ViewerActivity, "닫힌 영역을 찾지 못했어요. 도형 안을 누르거나, 선이 끊긴 곳을 이어 그려 주세요.",
                    Toast.LENGTH_SHORT,
                ).show()
            }

            override fun onShapeFailed(kind: ShapeKind) {
                Toast.makeText(this@ViewerActivity, "${withRo(kind.label)} 맞추지 못했어요. 조금 더 크게 그려 보세요.", Toast.LENGTH_SHORT).show()
            }
        }

        setupTools()
        setupFormatBar()
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

    override fun onPause() {
        super.onPause()
        // 다른 앱으로 가거나 화면이 꺼지면 치던 글을 쪽에 넣어 둔다
        textEditor.commit()
    }

    /** 뷰어가 이미 떠 있을 때 탐색기에서 문서를 고르면 새 탭으로 연다 */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    /** 인텐트의 문서를 탭으로 연다. 열 문서가 없으면 false (탐색기에서 '뷰어로 돌아가기'만 한 경우) */
    private fun handleIntent(intent: Intent): Boolean {
        intent.getStringExtra(EXTRA_CAPTURE)?.let { path ->
            intent.removeExtra(EXTRA_CAPTURE)
            insertCapturedPage(File(path))
            return true
        }
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
        if (isFinishing) CaptureService.stop(this)
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
        val color = if (t.type == DocType.UNKNOWN) TAB_ICON_GRAY else DocColors.of(t.type)
        docTabs.setIcon(i, R.drawable.ic_doc, color)
    }

    /** 이 탭의 문서를 화면에 띄운다 */
    private fun showTab(t: DocTab) {
        if (current === t) return
        textEditor.commit()
        overview.hide()
        docView.searchHits = emptyMap()
        current?.let { it.viewState = docView.viewState() }
        current = t
        val d = t.pdf
        val inkDoc = t.ink
        if (d != null && inkDoc != null) {
            docView.setDocument(d, inkDoc, t.viewState)
            syncPagePanel()
            progress.visibility = View.GONE
        } else {
            docView.clearDocument()
            pagePanel.clear()
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
            pagePanel.clear()
        }
        docs.removeAt(index)
        t.search?.cancel()
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
        // 쪽 선택·글자 찾기·쪽 한눈에 보기부터 하나씩 끝낸다
        if (overview.back() || pagePanel.back()) return
        if (fullscreen) {
            setFullscreen(false)
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
                updateTabTitle(t)  // 탭 아이콘 색 (PDF 빨강, 한글 파랑 …)
                when (t.type) {
                    DocType.PDF -> openPdf(t, file)
                    DocType.UNKNOWN -> fail(t, "지원하지 않는 파일 형식입니다.\n(PDF, 한글, 워드, 파워포인트, 글(txt), 그림 파일을 열 수 있습니다)")
                    else -> openPdf(t, convert(file, t.type, name))
                }
            } catch (e: SecurityException) {
                fail(t, "파일을 열 권한이 없거나, 암호가 걸린 PDF입니다.")
            } catch (e: Exception) {
                fail(t, "파일을 열지 못했습니다.\n${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** 한글·워드·파워포인트·글·그림 → PDF (기기 안에서 변환) */
    private suspend fun convert(file: File, type: DocType, name: String): File = withContext(Dispatchers.Default) {
        DocConvert.toPdf(this@ViewerActivity, file, type, name)
    }

    private suspend fun openPdf(t: DocTab, file: File) {
        t.sourcePdf = file
        // 이 앱으로 저장한 필기가 있으면 꺼내서 편집 가능한 상태로 만든다
        val marks = HashSet<Int>()
        val (renderFile, strokes) = withContext(Dispatchers.IO) {
            if (PdfInk.containsInk(file)) {
                val clean = FileUtil.tempFile(this@ViewerActivity, "clean", "pdf")
                clean to PdfInk.extract(file, clean, marks)
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
        strokes?.let { inkDoc.load(it, marks) }
        inkDoc.onChanged = {
            if (current === t) {
                updateActions()
                docView.invalidate()
                pagePanel.inkChanged()
                overview.inkChanged()
            }
            updateTabTitle(t)
        }
        inkDoc.swapPages = { files, apply -> restorePageFiles(t, files as PageFiles, apply) }
        t.pdf = d
        t.ink = inkDoc
        if (current === t) {
            docView.setDocument(d, inkDoc)
            syncPagePanel()
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
        undoButton.setOnClickListener { if (current?.pagesBusy == false) { textEditor.commit(); docView.clearSelection(); ink?.undo() } }
        redoButton.setOnClickListener { if (current?.pagesBusy == false) { textEditor.commit(); docView.clearSelection(); ink?.redo() } }
        saveButton.setOnClickListener { showSaveMenu(it) }
        overview = PagePanel(findViewById(R.id.overviewPanel), lifecycleScope, overview = true, host = pageHost(true))
        overviewButton.setOnClickListener { toggleOverview() }
        insertButton.setOnClickListener { showInsertMenu(it) }
        findViewById<View>(R.id.actionMore).setOnClickListener { showMoreMenu(it) }

        pagePanel = PagePanel(findViewById(R.id.pagePanel), lifecycleScope, overview = false, host = pageHost(false))
        pagesButton = findViewById(R.id.actionPages)
        pagesButton.setOnClickListener {
            val open = !pagePanel.isShowing
            if (open) overview.hide()
            prefs.edit().putBoolean("pagePanel", open).apply()
            syncPagePanel()
        }
        twoPageButton = findViewById(R.id.actionTwoPage)
        docView.twoPage = prefs.getBoolean("twoPage", false)
        twoPageButton.setOnClickListener {
            val on = !docView.twoPage
            prefs.edit().putBoolean("twoPage", on).apply()
            textEditor.commit()
            docView.twoPage = on
            updateActions()
            if (on && docView.width <= docView.height) toast("가로 화면에서 두 쪽씩 나란히 보입니다.")
        }
        readModeButton = findViewById(R.id.actionReadMode)
        readModeButton.setOnClickListener { setReadMode(!readMode) }
        fullscreenButton = findViewById(R.id.actionFullscreen)
        fullscreenButton.setOnClickListener { setFullscreen(true) }
        exitFullscreenButton = findViewById(R.id.exitFullscreen)
        exitFullscreenButton.setOnClickListener { setFullscreen(false) }
        updateActions()
    }

    /** 지금 탭의 상태에 맞춰 버튼을 켜고 끈다 (예전 invalidateOptionsMenu 자리) */
    private fun updateActions() {
        val inkDoc = ink
        undoButton.setEnabledAlpha(inkDoc?.canUndo == true)
        redoButton.setEnabledAlpha(inkDoc?.canRedo == true)
        saveButton.setEnabledAlpha(inkDoc != null)
        overviewButton.setEnabledAlpha(inkDoc != null)
        overviewButton.setActive(overview.isShowing)
        insertButton.setEnabledAlpha(inkDoc != null)
        pagesButton.setActive(pagePanel.isShowing)
        twoPageButton.setActive(docView.twoPage)
        readModeButton.setActive(readMode)
    }

    /** 켜진 보기 단추는 바탕에 옅은 동그라미 */
    private fun ImageButton.setActive(on: Boolean) {
        if (isSelected == on && background != null) return
        isSelected = on
        background = if (on) GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(MaterialColors.getColor(this@setActive, com.google.android.material.R.attr.colorSecondaryContainer))
        } else android.util.TypedValue().let { tv ->
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            getDrawable(tv.resourceId)
        }
    }

    // ================= 페이지 관리 · 읽기 모드 · 전체 화면 =================

    /** 페이지 관리 창·쪽 한눈에 보기가 하는 일 */
    private fun pageHost(isOverview: Boolean) = object : PagePanel.Host {
        override fun pick(page: Int, hit: RectF?, query: String?) {
            if (hit != null) docView.scrollToPoint(page, hit.centerX(), hit.top) else docView.scrollToPage(page)
            if (!isOverview) return
            overview.hide()
            // 쪽 한눈에 보기에서 찾던 말은 페이지 관리 창에서 이어서 (다른 찾은 쪽으로 바로 갈 수 있게)
            if (query != null && !fullscreen) {
                prefs.edit().putBoolean("pagePanel", true).apply()
                syncPagePanel()
                pagePanel.openSearch(query)
            }
        }
        override fun menu(page: Int, anchor: View) = showPageMenu(page, anchor, isOverview)
        override fun reorder(order: List<Int>) = reorderPages(order)
        override fun deletePages(pages: List<Int>, onDone: () -> Unit) {
            current?.let { deletePageList(it, pages, onDone) }
        }
        override fun rotatePages(pages: List<Int>) {
            current?.let { rotatePages(it, pages) }
        }
        override fun flipPages(pages: List<Int>, horizontal: Boolean) {
            current?.let { flipPages(it, pages, horizontal) }
        }
        override fun savePages(pages: List<Int>, asPdf: Boolean) {
            val t = current ?: return
            if (asPdf) savePageFile(t, pages) else exportImages(t, pages)
        }
        override fun search() = currentSearch()
        override fun showHits(hits: Map<Int, List<RectF>>) {
            docView.searchHits = hits
        }
        override fun closed() = updateActions()
    }

    /** 지금 탭의 글자 찾기. 화면용 PDF가 바뀌었으면(쪽 넣기·지우기 등) 새로 */
    private fun currentSearch(): DocSearch? {
        val t = current ?: return null
        val f = t.renderPdf ?: return null
        t.search?.let { if (it.file == f) return it else it.cancel() }
        return DocSearch(f, lifecycleScope).also { t.search = it }
    }

    /** 지금 탭의 PDF 링크. 화면용 PDF가 바뀌었으면(쪽 넣기·지우기 등) 새로 */
    private fun currentLinks(): DocLinks? {
        val t = current ?: return null
        val f = t.renderPdf ?: return null
        t.links?.let { if (it.file == f) return it }
        return DocLinks(f, lifecycleScope).also { t.links = it }
    }

    /** 읽기 모드에서 누른 자리의 링크: 다른 쪽이면 바로 가고, 웹 주소면 물어보고 브라우저로 */
    private fun followLinkAt(page: Int, x: Float, y: Float) {
        val links = currentLinks() ?: return
        links.whenReady { l ->
            if (current?.links !== l || !readMode) return@whenReady
            val link = l.at(page, x, y) ?: return@whenReady
            val uri = link.uri
            if (uri == null) {
                if (link.y != null) docView.scrollToPageY(link.page, link.y) else docView.scrollToPage(link.page)
                return@whenReady
            }
            // 'www.…'처럼 앞이 빠진 주소는 웹 주소로. 웹·메일 말고는 열지 않는다
            val full = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(uri)) uri else "http://$uri"
            val parsed = Uri.parse(full)
            if (parsed.scheme?.lowercase() !in setOf("http", "https", "mailto")) {
                toast("열 수 없는 링크입니다: $uri")
                return@whenReady
            }
            MaterialAlertDialogBuilder(this)
                .setTitle("이 주소로 이동할까요?")
                .setMessage(full)
                .setPositiveButton("이동") { _, _ ->
                    try {
                        startActivity(Intent(Intent.ACTION_VIEW, parsed).addCategory(Intent.CATEGORY_BROWSABLE))
                    } catch (e: android.content.ActivityNotFoundException) {
                        toast("이 주소를 열 수 있는 앱이 없습니다.")
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }
    }

    /** 페이지 관리 창을 저장된 설정대로 열거나 닫는다 (전체 화면에서는 늘 닫음) */
    private fun syncPagePanel() {
        val t = current
        val d = t?.pdf
        val inkDoc = t?.ink
        val want = prefs.getBoolean("pagePanel", false) && !fullscreen
        if (want && d != null && inkDoc != null) {
            if (pagePanel.isShowing) pagePanel.setDocument(d, inkDoc)
            else pagePanel.show(d, inkDoc, docView.currentPage().coerceAtLeast(0))
        } else if (!want) pagePanel.hide()
        updateActions()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // 가로는 두 줄, 세로는 한 줄
        if (::pagePanel.isInitialized) pagePanel.fitWidth()
    }

    /** 페이지 관리 창 미리보기의 ⋮: 북마크 · 앞/뒤에 빈 쪽 넣기 · 이미지로 저장 · 쪽 지우기 */
    private fun showPageMenu(page: Int, anchor: View, inOverview: Boolean = false) {
        val t = current ?: return
        val d = t.pdf ?: return
        val inkDoc = t.ink ?: return
        if (page !in 0 until d.pageCount) return
        val popup = PopupMenu(this, anchor)
        val marked = inkDoc.isBookmarked(page)
        popup.menu.add(0, 1, 0, if (marked) "북마크 풀기" else "북마크")
            .setIcon(if (marked) R.drawable.ic_bookmark_border else R.drawable.ic_bookmark)
        val papers = listOf(Paper.PLAIN to "흰 바탕", Paper.GRID to "모눈", Paper.LINED to "줄")
        val before = popup.menu.addSubMenu(0, 2, 1, "앞에 빈 쪽 넣기")
        before.item.setIcon(R.drawable.ic_add)
        papers.forEachIndexed { i, (_, label) -> before.add(0, 20 + i, i, label) }
        val after = popup.menu.addSubMenu(0, 3, 2, "뒤에 빈 쪽 넣기")
        after.item.setIcon(R.drawable.ic_add)
        papers.forEachIndexed { i, (_, label) -> after.add(0, 30 + i, i, label) }
        popup.menu.add(0, 6, 3, "복사").setIcon(R.drawable.ic_copy)
        popup.menu.add(0, 7, 4, "잘라내기").setIcon(R.drawable.ic_cut).isEnabled = d.pageCount > 1
        // 붙여넣기는 복사·잘라낸 쪽이 있을 때만 (다른 문서 탭에서 복사한 쪽도 된다)
        val paste = popup.menu.addSubMenu(0, 8, 5, "붙여넣기")
        paste.item.setIcon(R.drawable.ic_paste)
        paste.item.isEnabled = pageClip != null
        paste.add(0, 81, 0, "앞에 붙여넣기")
        paste.add(0, 82, 1, "뒤에 붙여넣기")
        val save = popup.menu.addSubMenu(0, 4, 6, "저장")
        save.item.setIcon(R.drawable.ic_save)
        save.add(0, 41, 0, "이미지로 저장")
        save.add(0, 42, 1, "파일(PDF)로 저장")
        val share = popup.menu.addSubMenu(0, 9, 7, "공유")
        share.item.setIcon(R.drawable.ic_share)
        share.add(0, 91, 0, "이미지로 공유")
        share.add(0, 92, 1, "파일(PDF)로 공유")
        popup.menu.add(0, 5, 8, "쪽 지우기").setIcon(R.drawable.ic_delete).isEnabled = d.pageCount > 1
        // 쪽 한눈에 보기에서만: 이 쪽 뒤·앞의 쪽을 한꺼번에 (이 쪽은 남긴다)
        if (inOverview) {
            val last = d.pageCount
            popup.menu.add(0, 10, 9, if (page + 1 < last) "이 쪽 뒤 모두 지우기 (${page + 2}~${last}쪽)" else "이 쪽 뒤 모두 지우기")
                .setIcon(R.drawable.ic_delete).isEnabled = page + 1 < last
            popup.menu.add(0, 11, 10, if (page > 0) "이 쪽 앞 모두 지우기 (1~${page}쪽)" else "이 쪽 앞 모두 지우기")
                .setIcon(R.drawable.ic_delete).isEnabled = page > 0
        }
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item ->
            when (val id = item.itemId) {
                1 -> inkDoc.setBookmark(page, !marked)
                in 20..22 -> insertBlankPage(papers[id - 20].first, at = page)
                in 30..32 -> insertBlankPage(papers[id - 30].first, at = page + 1)
                6 -> copyPage(t, page, cut = false)
                7 -> copyPage(t, page, cut = true)
                81 -> pastePage(t, page)
                82 -> pastePage(t, page + 1)
                41 -> exportImages(t, listOf(page))
                42 -> savePageFile(t, listOf(page))
                91 -> sharePage(t, page, asPdf = false)
                92 -> sharePage(t, page, asPdf = true)
                5 -> deletePages(t, page, page)
                10 -> deletePageList(t, (page + 1 until d.pageCount).toList()) {}
                11 -> deletePageList(t, (0 until page).toList()) {}
            }
            true
        }
        popup.show()
    }

    // ----- 쪽 복사 · 잘라내기 · 붙여넣기 · 옮기기 · 저장 · 공유 -----

    /** 복사하거나 잘라낸 쪽: 필기를 뺀 한 쪽짜리 PDF + 그 쪽 필기(사본) + 북마크 */
    private class PageClip(val pdf: File, val strokes: List<Stroke>, val bookmarked: Boolean)
    private var pageClip: PageClip? = null

    /** [pages] 쪽만 그 차례로 담은 PDF (이 앱 필기 주석은 뺀 것). IO 스레드에서 */
    private fun cleanPagePdf(src: File, pages: List<Int>, prefix: String): File {
        val raw = FileUtil.tempFile(this, "${prefix}_raw", "pdf")
        PdfPages.reorder(src, raw, pages)
        if (!PdfInk.containsInk(raw)) return raw
        val clean = FileUtil.tempFile(this, prefix, "pdf")
        PdfInk.extract(raw, clean)
        raw.delete()
        return clean
    }

    private fun copyPage(t: DocTab, page: Int, cut: Boolean) {
        val src = t.sourcePdf ?: return
        val inkDoc = t.ink ?: return
        if (t.pagesBusy) return
        val strokes = inkDoc.pages.getOrNull(page)?.map { it.copy() } ?: return
        val marked = inkDoc.isBookmarked(page)
        lifecycleScope.launch {
            try {
                val pdf = withContext(Dispatchers.IO) { cleanPagePdf(src, listOf(page), "clip") }
                pageClip = PageClip(pdf, strokes, marked)
                if (cut && t in docs) deletePages(t, page, page, cut = true)
                else toast("${page + 1}쪽을 복사했습니다. ⋮ → 붙여넣기로 원하는 자리에 넣을 수 있습니다.")
            } catch (e: Exception) {
                toast("쪽을 복사하지 못했습니다.")
            }
        }
    }

    /** 복사·잘라낸 쪽을 [at]번째 자리(0부터)에 넣는다 (실행 취소 가능) */
    private fun pastePage(t: DocTab, at: Int) {
        val clip = pageClip ?: return
        val inkDoc = t.ink ?: return
        val done = { toast("${at + 1}쪽에 붙여넣었습니다.") }
        editPages(t, { src, out -> PdfPages.insertPdf(src, out, at, clip.pdf) }, done) { pages ->
            // 여러 번 붙여도 서로 따로 고칠 수 있게 붙일 때마다 사본
            val list = clip.strokes.mapTo(ArrayList()) { it.copy() }
            if (clip.bookmarked) inkDoc.markList(list)
            pages.add(at, list)
            at
        }
    }

    /** 쪽 순서를 [order]로 바꾼다 (새 k번째 = 원래 order[k]번째). 보던 쪽은 그대로 본다 (실행 취소 가능) */
    private fun reorderPages(order: List<Int>): Boolean {
        val t = current ?: return false
        if (t.pagesBusy || t.ink == null || t.sourcePdf == null) return false
        val viewing = docView.currentPage()
        val done = { toast("쪽 순서를 바꿨습니다. 실행 취소로 되돌릴 수 있습니다.") }
        editPages(t, { src, out -> PdfPages.reorder(src, out, order) }, done) { pages ->
            val old = pages.toList()
            pages.clear()
            order.mapTo(pages) { old[it] }
            order.indexOf(viewing).coerceAtLeast(0)
        }
        return true
    }

    /** 고른 쪽들을 지운다 (여러 쪽이면 묻고). 다 지울 수는 없다. 실행 취소 가능 */
    private fun deletePageList(t: DocTab, list: List<Int>, onDone: () -> Unit) {
        val d = t.pdf ?: return
        if (list.isEmpty()) return
        if (list.size >= d.pageCount) {
            toast("모든 쪽을 지울 수는 없습니다.")
            return
        }
        val go = {
            val gone = list.toSet()
            val keep = (0 until d.pageCount).filter { it !in gone }
            val done = { toast("${list.size}쪽을 지웠습니다. 실행 취소로 되돌릴 수 있습니다.") }
            editPages(t, { src, out -> PdfPages.reorder(src, out, keep) }, done) { pages ->
                val old = pages.toList()
                pages.clear()
                keep.mapTo(pages) { old[it] }
                list.min().coerceAtMost(pages.size - 1)
            }
            onDone()
        }
        if (list.size == 1) go()
        else MaterialAlertDialogBuilder(this)
            .setMessage("고른 ${list.size}쪽을 지울까요?\n(실행 취소로 되돌릴 수 있습니다)")
            .setPositiveButton("지우기") { _, _ -> go() }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 고른 쪽들을 시계 방향으로 90° 돌린다. PDF 쪽을 돌리고, 그 쪽 필기도 같이 돌린다
     * (돌린 사본으로 바꿔 넣으므로 실행 취소하면 원래 획으로 돌아간다)
     */
    private fun rotatePages(t: DocTab, list: List<Int>) {
        val d = t.pdf ?: return
        val inkDoc = t.ink ?: return
        if (list.isEmpty()) return
        val sizes = d.sizes
        val viewing = docView.currentPage()
        editPages(t, { src, out -> PdfPages.rotate(src, out, list, 90) }) { pages ->
            for (i in list) {
                // 쪽 (x, y) → 돌린 쪽 (높이 - y, x)
                val h = sizes[i].height
                val turned = pages[i].mapTo(ArrayList()) { it.copy().apply { rotate(90f, 0f, 0f); translate(h, 0f) } }
                if (inkDoc.isBookmarked(i)) inkDoc.markList(turned)
                // 고른 쪽이었으면 돌린 쪽도 골라진 채로
                pagePanel.pageReplaced(pages[i], turned)
                overview.pageReplaced(pages[i], turned)
                pages[i] = turned
            }
            viewing.coerceAtLeast(0)
        }
    }

    /**
     * 고른 쪽들을 좌우([horizontal]) 또는 상하로 뒤집는다. 필기도 같이 거울에 비추되 글·그림은 읽히게 둔다
     * (뒤집은 사본으로 바꿔 넣으므로 실행 취소하면 원래 획으로 돌아간다)
     */
    private fun flipPages(t: DocTab, list: List<Int>, horizontal: Boolean) {
        val d = t.pdf ?: return
        val inkDoc = t.ink ?: return
        if (list.isEmpty()) return
        val sizes = d.sizes
        val viewing = docView.currentPage()
        editPages(t, { src, out -> PdfPages.flip(src, out, list, horizontal) }) { pages ->
            for (i in list) {
                val extent = if (horizontal) sizes[i].width else sizes[i].height
                val flipped = pages[i].mapTo(ArrayList()) { it.copy().apply { mirror(horizontal, extent) } }
                if (inkDoc.isBookmarked(i)) inkDoc.markList(flipped)
                pagePanel.pageReplaced(pages[i], flipped)
                overview.pageReplaced(pages[i], flipped)
                pages[i] = flipped
            }
            viewing.coerceAtLeast(0)
        }
    }

    /** 고른 쪽들을 필기와 함께 한 PDF로 만든다 ([out]에). 필기는 부르는 순간의 것 */
    private suspend fun buildPagePdf(t: DocTab, list: List<Int>, out: File) {
        val src = t.sourcePdf ?: error("문서가 없습니다.")
        val inkDoc = t.ink ?: error("문서가 없습니다.")
        val strokes = list.map { inkDoc.pages.getOrNull(it)?.toList() ?: error("쪽이 없습니다.") }
        val marks = list.indices.filterTo(HashSet()) { inkDoc.isBookmarked(list[it]) }
        withContext(Dispatchers.IO) {
            val part = cleanPagePdf(src, list, "page")
            try {
                PdfInk.save(part, out, strokes, marks)
            } finally {
                part.delete()
            }
        }
    }

    /** '문서_3쪽.pdf', 여러 쪽이면 '문서_3쪽 외 2쪽.pdf' */
    private fun pageFileName(t: DocTab, list: List<Int>, ext: String): String {
        val base = FileUtil.baseName(t.name).replace(Regex("[\\\\/:*?\"<>|]"), "_")
        val pages = if (list.size == 1) "${list[0] + 1}쪽" else "${list[0] + 1}쪽 외 ${list.size - 1}쪽"
        return "${base}_$pages.$ext"
    }

    /** '파일로 저장': 만들어 둔 한 쪽 PDF를 사용자가 고른 곳에 쓴다 */
    private var pageFileToSave: File? = null
    private val createPagePdf = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val f = pageFileToSave
        pageFileToSave = null
        if (uri == null || f == null) return@registerForActivityResult
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val out = contentResolver.openOutputStream(uri, "wt") ?: error("저장 위치를 열 수 없습니다.")
                    out.use { o -> f.inputStream().use { it.copyTo(o, 1 shl 16) } }
                    f.delete()
                }
                if (uri.scheme == "file") uri.path?.let { MediaScannerConnection.scanFile(this@ViewerActivity, arrayOf(it), null, null) }
                toast("'${FileUtil.displayName(this@ViewerActivity, uri)}' 저장했습니다.")
            } catch (e: Exception) {
                toast("저장하지 못했습니다. ${e.message ?: ""}")
            }
        }
    }

    private fun savePageFile(t: DocTab, list: List<Int>) {
        if (list.isEmpty()) return
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val f = FileUtil.tempFile(this@ViewerActivity, "page_save", "pdf")
                buildPagePdf(t, list, f)
                pageFileToSave = f
                createPagePdf.launch(pageFileName(t, list, "pdf"))
            } catch (e: Exception) {
                toast("쪽을 파일로 만들지 못했습니다.")
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /** 쪽 공유: 그림(PNG)이나 한 쪽 PDF를 앱 캐시(share/)에 만들어 다른 앱으로 보낸다 */
    private fun sharePage(t: DocTab, page: Int, asPdf: Boolean) {
        val d = t.pdf ?: return
        val inkDoc = t.ink ?: return
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val dir = File(cacheDir, "share").apply { deleteRecursively(); mkdirs() }
                val file = File(dir, pageFileName(t, listOf(page), if (asPdf) "pdf" else "png"))
                if (asPdf) buildPagePdf(t, listOf(page), file)
                else {
                    val bmp = pageBitmap(d, inkDoc, page)
                    withContext(Dispatchers.IO) { file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                    bmp.recycle()
                }
                val uri = androidx.core.content.FileProvider.getUriForFile(this@ViewerActivity, "$packageName.files", file)
                val send = Intent(Intent.ACTION_SEND)
                    .setType(if (asPdf) "application/pdf" else "image/png")
                    .putExtra(Intent.EXTRA_STREAM, uri)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                startActivity(Intent.createChooser(send, "${page + 1}쪽 공유"))
            } catch (e: Exception) {
                toast("쪽을 공유하지 못했습니다.")
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /** 읽기 모드: 툴바와 도구 줄을 숨기고, 펜으로도 넘기기·확대만 한다 */
    private fun setReadMode(on: Boolean) {
        if (on) {
            docView.clearLaser()
            textEditor.commit()
            hideOptionBar()
            closeFlyout()
            docView.clearSelection()
        }
        readMode = on
        docView.readOnly = on
        // 링크를 미리 꺼내 둔다 (처음 누를 때 기다리지 않게)
        if (on) currentLinks()
        findViewById<View>(R.id.toolbar).visibility = if (on) View.GONE else View.VISIBLE
        shapeBar.visibility = if (!on && docView.tool == Tool.SHAPE) View.VISIBLE else View.GONE
        updateActions()
        toast(if (on) "읽기 모드: 필기하지 않고 넘겨 보기만 합니다." else "읽기 모드를 끝냈습니다.")
    }

    /**
     * 전체 화면: 탭 줄·상태 표시줄·페이지 관리 창을 숨겨 필기할 자리를 넓힌다. 오른쪽 위 단추나 뒤로 가기로 끝낸다.
     * 상태 표시줄의 시계 대신 왼쪽 위에 지금 시각을 띄운다
     */
    private fun setFullscreen(on: Boolean) {
        if (fullscreen == on) return
        fullscreen = on
        overview.hide()
        findViewById<View>(R.id.tabRow).visibility = if (on) View.GONE else View.VISIBLE
        exitFullscreenButton.visibility = if (on) View.VISIBLE else View.GONE
        findViewById<View>(R.id.fullscreenClock).visibility = if (on) View.VISIBLE else View.GONE
        val ctl = WindowCompat.getInsetsController(window, window.decorView)
        if (on) {
            // 가장자리에서 밀면 잠깐 나타났다 사라진다
            ctl.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            ctl.hide(WindowInsetsCompat.Type.systemBars())
        } else ctl.show(WindowInsetsCompat.Type.systemBars())
        syncPagePanel()
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
                R.id.action_toolbar_options -> showToolVisibilityDialog()
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
        popup.menu.add(0, 1, 0, "그림을 현재 쪽에 넣기").setIcon(R.drawable.ic_image)
        popup.menu.add(0, 3, 1, "그림을 새 쪽으로").setIcon(R.drawable.ic_image)
        popup.menu.add(0, 4, 1, "다른 앱 화면 가져오기").setIcon(R.drawable.ic_screen_capture)
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
                item.itemId == 4 -> startScreenCapture()
                item.itemId == 1 || item.itemId == 3 -> {
                    imageImportMode = if (item.itemId == 3) ImageImportMode.NEW_PAGE else ImageImportMode.IN_PAGE
                    pickImage.launch("image/*")
                }
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
            val marks = HashSet<Int>()
            val (file, strokes) = try {
                withContext(Dispatchers.IO) {
                    val name = FileUtil.displayName(this@ViewerActivity, uri)
                    val f = FileUtil.copyToCache(this@ViewerActivity, uri, name)
                    if (FileUtil.detect(name, contentResolver.getType(uri), f) != DocType.PDF) error("PDF 파일이 아닙니다.")
                    if (PdfInk.containsInk(f)) {
                        val clean = FileUtil.tempFile(this@ViewerActivity, "ins_clean", "pdf")
                        clean to PdfInk.extract(f, clean, marks)
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
            val inkDoc = t.ink ?: return@launch
            editPages(t, { src, out -> PdfPages.insertPdf(src, out, index, file) }, done) { pages ->
                val lists = strokes.map { it.toMutableList() }
                // 넣은 PDF의 북마크도 함께
                for (i in marks) lists.getOrNull(i)?.let(inkDoc::markList)
                pages.addAll(index, lists)
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

    /** 고른 그림을 한 쪽짜리 PDF로 만들어 현재 쪽 뒤에 넣는다. */
    private fun insertImagePageFrom(uri: Uri) {
        val t = current ?: return
        val d = t.pdf ?: return
        val index = docView.currentPage().coerceIn(0, d.pageCount - 1) + 1
        insertImagePage(t, index, "그림") {
            val name = FileUtil.displayName(this@ViewerActivity, uri)
            val source = FileUtil.copyToCache(this@ViewerActivity, uri, name)
            val mime = contentResolver.getType(uri).orEmpty()
            Pair(source, Pair(mime == "image/jpeg", mime != "image/png"))
        }
    }

    // ================= 다른 앱 화면 가져오기 =================

    /** 화면 전송 허락을 받고 떠 있는 캡처 단추를 띄운다. 처음엔 '다른 앱 위에 표시' 권한부터 */
    private fun startScreenCapture() {
        val t = current ?: return
        val d = t.pdf ?: return
        if (t.pagesBusy) return
        captureTab = t
        captureIndex = docView.currentPage().coerceIn(0, d.pageCount - 1) + 1
        if (android.provider.Settings.canDrawOverlays(this)) { requestProjection(); return }
        MaterialAlertDialogBuilder(this)
            .setTitle("다른 앱 화면 가져오기")
            .setMessage("다른 앱 위에 캡처 단추를 띄우려면 'DSnote'의 '다른 앱 위에 표시'를 허용해 주세요.\n허용한 뒤 뒤로 가기를 누르면 이어서 진행합니다.")
            .setPositiveButton("설정 열기") { _, _ ->
                val pkg = Uri.parse("package:$packageName")
                runCatching { overlayPermission.launch(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, pkg)) }
                    .recoverCatching { overlayPermission.launch(Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)) }
                    .onFailure { toast("이 기기에서는 권한 설정 화면을 열 수 없습니다.") }
            }
            .setNegativeButton("취소") { _, _ -> captureTab = null }
            .show()
    }

    private fun requestProjection() {
        if (CaptureService.running) { moveTaskToBack(true); return }
        val mpm = getSystemService(android.media.projection.MediaProjectionManager::class.java)
        // 안드로이드 14부터는 '앱 하나만'도 고를 수 있는데, 앱을 오가며 찍어야 하므로 화면 전체로 받는다
        val consent = if (android.os.Build.VERSION.SDK_INT >= 34)
            mpm.createScreenCaptureIntent(android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay())
        else mpm.createScreenCaptureIntent()
        runCatching { projectionConsent.launch(consent) }.onFailure { toast("이 기기에서는 화면을 가져올 수 없습니다.") }
    }

    /** 떠 있는 단추로 찍어 온 화면을 시작할 때 보던 쪽 다음에 넣는다 */
    private fun insertCapturedPage(png: File) {
        val t = captureTab?.takeIf { it in docs } ?: current ?: return
        captureTab = null
        val d = t.pdf ?: return
        if (t !== current) docTabs.select(docs.indexOf(t), notify = true)
        val index = captureIndex.coerceIn(0, d.pageCount)
        insertImagePage(t, index, "화면") { Pair(png, Pair(false, false)) }
    }

    /**
     * 그림 한 장을 한 쪽짜리 PDF로 만들어 [index] 자리에 넣는다.
     * [prepare]는 IO 스레드에서 (그림 파일, (JPEG인가, 사진인가))를 돌려준다.
     */
    private fun insertImagePage(t: DocTab, index: Int, what: String, prepare: () -> Pair<File, Pair<Boolean, Boolean>>) {
        if (t.pagesBusy) return
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val imagePdf = withContext(Dispatchers.IO) {
                    val (source, kind) = prepare()
                    val output = FileUtil.tempFile(this@ViewerActivity, "image_page", "pdf")
                    ImagePdf.make(source, output, isJpeg = kind.first, photo = kind.second)
                    output
                }
                if (current !== t) return@launch
                editPages(t, { src, out -> PdfPages.insertPdf(src, out, index, imagePdf) },
                    onDone = { toast("${what}을 ${index + 1}쪽으로 넣었습니다. 바로 필기할 수 있어요.") }) { pages ->
                    pages.add(index, mutableListOf())
                    index
                }
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@ViewerActivity)
                    .setMessage("${what}을 새 쪽으로 넣지 못했습니다.\n${e.message ?: e.javaClass.simpleName}")
                    .setPositiveButton("확인", null)
                    .show()
            } finally {
                if (current === t && !t.pagesBusy) progress.visibility = View.GONE
            }
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
        textEditor.commit()
        if (overview.isShowing) {
            overview.hide()
            return
        }
        val t = current ?: return
        val d = t.pdf ?: return
        val inkDoc = t.ink ?: return
        hideOptionBar()
        docView.clearSelection()
        // 쪽 한눈에 보기가 페이지 관리 창의 일을 모두 하므로 페이지 관리 창은 닫는다
        if (pagePanel.isShowing) {
            prefs.edit().putBoolean("pagePanel", false).apply()
            syncPagePanel()
        }
        overview.show(d, inkDoc, docView.currentPage().coerceAtLeast(0))
        updateActions()
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

    /**
     * [at]번째 자리(0부터, 기본은 보고 있는 쪽 뒤)에 앞 쪽과 같은 크기의 빈 쪽을 넣고 그 쪽으로 간다.
     * 맨 앞에 넣으면 첫 쪽과 같은 크기
     */
    private fun insertBlankPage(paper: Paper, at: Int = docView.currentPage() + 1) {
        val t = current ?: return
        val d = t.pdf ?: return
        val size = d.sizes[(at - 1).coerceIn(0, d.pageCount - 1)]
        editPages(t, { src, out -> PdfPages.insert(src, out, at, paper, size.width, size.height) }) { pages ->
            pages.add(at, mutableListOf())
            at
        }
    }

    /**
     * 마지막 쪽 아래로 더 끌어 올렸을 때: 마지막 쪽과 같은 크기·바탕(흰 바탕·모눈·줄)의 빈 쪽을 맨 뒤에 붙인다
     */
    private fun appendBlankPage() {
        val t = current ?: return
        val d = t.pdf ?: return
        if (t.pagesBusy || docView.readOnly) return
        val last = d.pageCount - 1
        val size = d.sizes[last]
        var paper: Paper? = null
        editPages(t, { src, out ->
            // 원본에서 한 번 알아낸 바탕을 화면용 PDF에도 똑같이
            val p = paper ?: runCatching { PdfPages.paperOf(src, last) }.getOrDefault(Paper.PLAIN).also { paper = it }
            PdfPages.insert(src, out, last + 1, p, size.width, size.height)
        }) { pages ->
            pages.add(mutableListOf())
            pages.size - 1
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
    private fun deletePages(t: DocTab, from: Int, to: Int, cut: Boolean = false) {
        val done = {
            val what = if (from == to) "${from + 1}쪽을" else "${from + 1}~${to + 1}쪽을"
            val msg = if (cut) "$what 잘라냈습니다. 페이지 관리 창의 ⋮ → 붙여넣기로 원하는 자리에 넣을 수 있습니다."
                else "$what 지웠습니다. 실행 취소로 되돌릴 수 있습니다."
            Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
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
        textEditor.commit()
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
                    pagePanel.setDocument(nd, inkDoc)
                    overview.setDocument(nd, inkDoc)
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
                    pagePanel.setDocument(nd, inkDoc)
                    overview.setDocument(nd, inkDoc)
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
                for (p in pages) {
                    val bmp = pageBitmap(d, inkDoc, p)
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

    /** 쪽을 필기·그림과 함께 [EXPORT_DPI]로 그린다 */
    private suspend fun pageBitmap(d: PdfDoc, inkDoc: InkDocument, p: Int): Bitmap {
        val scale = EXPORT_DPI / 72f
        val size = d.sizes[p]
        val w = (size.width * scale).roundToInt().coerceAtLeast(1)
        val h = (size.height * scale).roundToInt().coerceAtLeast(1)
        val bmp = withContext(d.dispatcher) { d.render(p, scale, 0f, 0f, w, h) }
        // 필기 얹기 (획의 경로 캐시를 문서 화면과 같이 쓰므로 메인 스레드에서). 그림을 먼저
        val c = Canvas(bmp)
        c.scale(scale, scale)
        val paint = inkPaint()
        inkDoc.pages.getOrNull(p)?.sortedBy { inkLayer(it) }?.forEach { drawInkStroke(c, paint, it) }
        return bmp
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
        if (current === t) textEditor.commit()
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
        val marks = inkDoc.bookmarkedPages()
        lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                withContext(Dispatchers.IO) {
                    val tmp = FileUtil.tempFile(this@ViewerActivity, "out", "pdf")
                    try {
                        PdfInk.save(src, tmp, snapshot, marks)
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

        // 자유 선택 / 네모 선택 / 대상 선택 (고르는 곳은 옵션 줄)
        val lassoMode = prefs.getInt("lassoMode", if (prefs.getBoolean("lassoRect", false)) 1 else 0)
        docView.lassoRect = lassoMode == 1
        docView.lassoTap = lassoMode == 2
    }

    // ================= 글 넣기 =================
    // 글 도구로 쪽을 누르면 그 자리에 글 상자가 생기고 바로 친다 (InlineTextEditor).
    // 글자 크기는 글 상자를 치는 동안 뜨는 서식 줄의 크기 칸으로 고른다.

    /** 새 글 상자의 기본 글 크기 (pt) */
    private val defaultTextSize = 20f

    private fun onTextTap(page: Int, x: Float, y: Float, existing: Stroke?) {
        // 글 상자를 치는 중이면 상자 밖을 누른 것: 그 글만 넣고 끝낸다
        if (textEditor.isEditing) {
            textEditor.commit()
            return
        }
        textEditor.start(page, x, y, existing, defaultTextSize, docView.penColor)
    }

    // ================= 글 서식 줄 =================
    // 글 상자를 치는 동안 옵션 줄 자리에 뜬다: 글자색 · 배경색 · 서식 · 글씨체 · 크기 · 굵게 · 기울임 · 밑줄 · 취소선 ·
    // 목록(체크 · 번호 · 점) · 정렬(왼쪽 · 가운데 · 오른쪽) · 들여쓰기 · 내어쓰기.
    // 툴바가 왼쪽·오른쪽이면 서식 줄도 세로로 세워 툴바 옆에 붙인다 (placeOverlays)

    /** 지금 쓰는 서식 줄: 가로 줄([formatBarH]) 또는 세로 줄([formatBarV]) */
    private lateinit var formatBar: View
    private lateinit var formatBarH: View
    private lateinit var formatBarV: View
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

    private fun setupFormatBar() {
        val row = findViewById<LinearLayout>(R.id.formatRow)
        val d = resources.displayMetrics.density
        fun <T : View> cell(v: T, w: Float = 40f): T {
            v.layoutParams = LinearLayout.LayoutParams((w * d).toInt(), (40 * d).toInt()).apply { marginEnd = (2 * d).toInt() }
            v.setBackgroundResource(R.drawable.bg_tool)
            row.addView(v)
            return v
        }
        fun icon(res: Int, desc: String, onClick: (View) -> Unit) = cell(ImageButton(this).apply {
            setImageResource(res)
            contentDescription = desc
            tooltipText = desc
            setOnClickListener { onClick(it) }
        })
        fun label(text: CharSequence, desc: String, w: Float = 40f, onClick: (View) -> Unit) = cell(TextView(this).apply {
            this.text = text
            gravity = android.view.Gravity.CENTER
            textSize = 17f
            contentDescription = desc
            tooltipText = desc
            setOnClickListener { onClick(it) }
        }, w)
        fun sep() = row.addView(View(this).apply {
            layoutParams = LinearLayout.LayoutParams((1 * d).toInt(), (24 * d).toInt()).apply {
                marginStart = (6 * d).toInt()
                marginEnd = (8 * d).toInt()
            }
            setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant))
        })

        // 글자색: '가' 아래 색 막대
        val colorCell = cell(FrameLayout(this).apply {
            contentDescription = "글자색"
            tooltipText = "글자색"
            setOnClickListener { openTextColorPicker(it) }
            addView(TextView(context).apply {
                text = "가"
                textSize = 17f
                gravity = android.view.Gravity.CENTER
            }, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, (32 * d).toInt()))
        })
        fmtColorBar = View(this)
        colorCell.addView(fmtColorBar, FrameLayout.LayoutParams((22 * d).toInt(), (4 * d).toInt(),
            android.view.Gravity.BOTTOM or android.view.Gravity.CENTER_HORIZONTAL).apply { bottomMargin = (5 * d).toInt() })
        // 배경색: 배경을 칠한 '가'
        val bgCell = cell(FrameLayout(this).apply {
            contentDescription = "배경색"
            tooltipText = "배경색"
            setOnClickListener { showBgColorPopup(it) }
        })
        fmtBgSwatch = TextView(this).apply {
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
        fitFormatBar()

        textEditor.onEditingChanged = { editing ->
            formatBar.visibility = if (editing) View.VISIBLE else View.GONE
            if (editing) hideOptionBar()
        }
        textEditor.onFormatChanged = { updateFormatBar(it) }
    }

    /** 서식 줄에 지금 서식을 보인다 (켜진 서식은 칸이 칠해진다) */
    private fun updateFormatBar(st: InlineTextEditor.FormatState) {
        fmtState = st
        fmtToggles.forEach { (t, v) -> v.isSelected = Rich.has(st.style, t) }
        // 목록 칸: 켜진 목록의 아이콘 (없으면 점 목록 아이콘, 칠하지 않음). 정렬 칸: 지금 정렬의 아이콘
        val list = listChoices.firstOrNull { it.value == st.para.list }
        fmtListButton.setImageDrawable(choiceIcon((list ?: listChoices.last()).icon))
        fmtListButton.isSelected = list != null
        fmtAlignButton.setImageDrawable(choiceIcon(alignChoices.first { it.value == st.para.align }.icon))
        fmtColorBar.setBackgroundColor(st.style.color ?: textEditor.color)
        val d = resources.displayMetrics.density
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
    private fun fitFormatBar() {
        val row = findViewById<LinearLayout>(R.id.formatRow)
        val v = dock.side.vertical
        val d = resources.displayMetrics.density
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
    private fun choiceIcon(res: Int) = LayerDrawable(arrayOf(getDrawable(res)!!, getDrawable(R.drawable.ic_corner_more)!!))

    /**
     * 목록·정렬 칸을 누르면 뜨는 창: 셋 중 하나를 고른다 (지금 것은 칠해져 있다).
     * 서식 줄이 가로면 칸 위(아래)에 가로로, 세로면 칸 옆(문서 쪽)에 세로로 뜬다
     */
    private fun <T> showChoicePopup(anchor: View, choices: List<Choice<T>>, current: T?, onPick: (T) -> Unit) {
        val d = resources.displayMetrics.density
        val vertical = dock.side.vertical
        val box = LinearLayout(this).apply {
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
            box.addView(ImageButton(this).apply {
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
        showBeside(popup, box, anchor)
    }

    private fun ptLabel(v: Float) = if (kotlin.math.abs(v - v.roundToInt()) < 0.05f) "${v.roundToInt()}" else String.format("%.1f", v)

    /** 서식 줄의 창은 문서 쪽으로 (툴바가 위면 아래로, 아래면 위로, 왼쪽·오른쪽이면 옆으로) */
    private fun fmtSide() = dock.side

    private fun openTextColorPicker(anchor: View) {
        val initial = fmtState?.style?.color ?: textEditor.color
        ColorPickerPopup(this, initial, recentColors(Tool.PEN)) { c, done ->
            textEditor.setTextColor(c)
            if (done) addRecent(Tool.PEN, c)
        }.show(anchor, fmtSide())
    }

    /** 배경색 고르기: 없음 + 옅은 색들 */
    private fun showBgColorPopup(anchor: View) {
        val d = resources.displayMetrics.density
        val row = LinearLayout(this).apply {
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
            row.addView(TextView(this).apply {
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
        val (x, y, w) = placeNear(this, row, anchor, (bgColors.size + 1) * 40f + 16f, fmtSide())
        popup.width = w
        popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY, x, y)
    }

    /** 기본 서식: 제목 · 소제목 · 본문 · 작은 글 (고른 문단 전체에) */
    private fun showPresetMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
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
        val popup = PopupMenu(this, anchor)
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
        val popup = PopupMenu(this, anchor)
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

    // ================= 툴바 자리 =================
    // 툴바 맨 앞 손잡이를 끌어 위·아래·왼쪽·오른쪽에 붙인다 (ToolbarDock). 자리는 기억해 둔다.

    private fun setupToolbarDock() {
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
            onTap = { showToolVisibilityDialog() },
        )
        val saved = prefs.getString("toolbarSide", null)
        dock.dock(ToolbarSide.entries.firstOrNull { it.name == saved } ?: ToolbarSide.BOTTOM)
    }

    // ================= 툴바에 보일 도구 =================
    // 툴바 손잡이를 톡 누르거나 ⋮ 메뉴 '툴바 옵션'에서 도구마다 보이기·숨기기 (prefs hiddenTools)

    /** 툴바 도구 차례와 이름 (보이기·숨기기 창) */
    private val toolNames = listOf(
        Tool.PEN to "펜", Tool.SHAPE to "보정 펜", Tool.HIGHLIGHTER to "형광펜", Tool.FILL to "채우기", Tool.TAPE to "테이프",
        Tool.TEXT to "글 넣기", Tool.ERASER to "지우개", Tool.LASSO to "선택", Tool.LASER to "레이저 포인터",
    )

    private fun hiddenTools(): Set<Tool> =
        prefs.getString("hiddenTools", null)?.split(',')?.mapNotNull { n -> Tool.entries.firstOrNull { it.name == n } }?.toSet()
            ?: emptySet()

    /** 숨긴 도구 버튼을 감춘다. 쓰던 도구를 숨겼으면 보이는 첫 도구로 */
    private fun applyToolVisibility() {
        val hidden = hiddenTools()
        toolButtons.forEach { (t, b) -> b.visibility = if (t in hidden) View.GONE else View.VISIBLE }
        if (docView.tool in hidden) toolNames.firstOrNull { it.first !in hidden }?.let { selectTool(it.first) }
    }

    private fun showToolVisibilityDialog() {
        val hidden = hiddenTools().toMutableSet()
        val checked = BooleanArray(toolNames.size) { toolNames[it].first !in hidden }
        MaterialAlertDialogBuilder(this)
            .setTitle("툴바에 보일 도구")
            .setMultiChoiceItems(toolNames.map { it.second }.toTypedArray(), checked) { d, which, on ->
                val t = toolNames[which].first
                if (!on && toolNames.count { it.first !in hidden } <= 1) {
                    // 도구가 하나도 없으면 쓸 수 없으니 마지막 하나는 남긴다
                    (d as androidx.appcompat.app.AlertDialog).listView.setItemChecked(which, true)
                    checked[which] = true
                    toast("도구를 하나는 남겨 두어야 합니다.")
                    return@setMultiChoiceItems
                }
                if (on) hidden.remove(t) else hidden.add(t)
                prefs.edit().putString("hiddenTools", hidden.joinToString(",") { it.name }).apply()
                applyToolVisibility()
            }
            .setPositiveButton("닫기", null)
            .show()
    }

    /** 세로 툴바 옆에 붙는 줄(서식·도형·옵션)을 담는 세로 스크롤 */
    private fun sideScroll() = android.widget.ScrollView(this).apply {
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
        if (::fmtSizeLabel.isInitialized) fitFormatBar()
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

    private fun updateOverlayInsets() {
        fun barsHeight(o: ViewGroup) = (0 until o.childCount).map { o.getChildAt(it) }
            .filter { it === shapeBar || it === optionBar || it === formatBar }
            .sumOf { if (it.visibility == View.VISIBLE) it.height else 0 }.toFloat()
        docView.topInset = barsHeight(topOverlay)
        docView.bottomInset = barsHeight(bottomOverlay)
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

    private fun setupEraserTools() {
        optionBar = findViewById(R.id.toolOptionBar)
        optionRow = findViewById(R.id.toolOptions)
        docView.eraserMode = prefs.getString("eraserMode", null)
            ?.let { n -> EraserMode.entries.firstOrNull { it.name == n } } ?: EraserMode.STROKE
        docView.eraseHlOnly = prefs.getBoolean("eraseHlOnly", false)
        docView.scribbleErase = prefs.getBoolean("scribbleErase", true)
        docView.palmErase = prefs.getBoolean("palmErase", true)
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
                addOption(R.drawable.ic_palm_erase, "손바닥 지우기", docView.palmErase) {
                    docView.palmErase = !docView.palmErase
                    prefs.edit().putBoolean("palmErase", docView.palmErase).apply()
                    toast(if (docView.palmErase) "손가락으로 쓰기 중 손바닥으로 문지르면 지워집니다." else "손바닥 지우기를 껐습니다.")
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

    private fun hideOptionBar() {
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

    /** 선택 방식: 0 자유 선택, 1 네모 선택, 2 대상 선택 */
    private fun setLassoMode(mode: Int) {
        docView.lassoRect = mode == 1
        docView.lassoTap = mode == 2
        prefs.edit().putInt("lassoMode", mode).apply()
        updateLassoIcon()
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

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
        val box = LinearLayout(this).apply {
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

    private fun closeFlyout() {
        flyout?.dismiss()
    }

    /** [anchor]의 창이 방금(이 누름으로) 닫혔는가. 그렇다면 다시 열지 않는다 (한 번 더 누르면 닫히게) */
    private fun flyoutJustClosed(anchor: View) =
        flyoutAnchor === anchor && android.os.SystemClock.uptimeMillis() - flyoutClosedAt < 600

    /** 형광펜·선택·레이저 버튼의 펼침 창 */
    private fun showToolFlyout(t: Tool, anchor: View) {
        fun item(res: Int, label: String, on: Boolean) = Triple(getDrawable(res)!!, label, on)
        when (t) {
            Tool.HIGHLIGHTER -> showFlyout(anchor, listOf(
                item(R.drawable.ic_highlighter, "자유 형광펜", !docView.hlStraight),
                item(R.drawable.ic_ruler, "직선 형광펜", docView.hlStraight),
            )) { i -> setHlStraight(i == 1) }
            Tool.LASSO -> showFlyout(anchor, listOf(
                item(R.drawable.ic_lasso, "자유 선택", !docView.lassoRect && !docView.lassoTap),
                item(R.drawable.ic_select_rect, "네모 선택", docView.lassoRect),
                item(R.drawable.ic_select_tap, "대상 선택", docView.lassoTap),
            )) { i -> setLassoMode(i) }
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

    /** 창을 [anchor] 옆 문서 쪽에 띄운다 (툴바가 위면 아래로, 아래면 위로, 왼쪽·오른쪽이면 옆으로) */
    private fun showBeside(popup: android.widget.PopupWindow, box: View, anchor: View) {
        val d = resources.displayMetrics.density
        box.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val w = box.measuredWidth
        val h = box.measuredHeight
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        val gap = (6 * d).toInt()
        val screenW = window.decorView.width
        val screenH = window.decorView.height
        val (x, y) = when (dock.side) {
            ToolbarSide.LEFT -> loc[0] + anchor.width + gap to loc[1] + anchor.height / 2 - h / 2
            ToolbarSide.RIGHT -> loc[0] - w - gap to loc[1] + anchor.height / 2 - h / 2
            ToolbarSide.TOP -> loc[0] + anchor.width / 2 - w / 2 to loc[1] + anchor.height + gap
            ToolbarSide.BOTTOM -> loc[0] + anchor.width / 2 - w / 2 to loc[1] - h - gap
        }
        popup.showAtLocation(anchor, android.view.Gravity.NO_GRAVITY,
            x.coerceIn(gap, maxOf(gap, screenW - w - gap)), y.coerceIn(gap, maxOf(gap, screenH - h - gap)))
    }

    /**
     * 옵션 줄의 칸 하나 (아이콘 + 이름). 누르면 옵션 줄을 닫고 실행한다.
     * [closeBar]가 false면 옵션 줄을 둔 채 실행한다 (칸에서 창을 펼칠 때). onClick은 누른 칸을 받는다
     */
    private fun addOption(icon: Int, label: String, selected: Boolean, closeBar: Boolean = true, onClick: (View) -> Unit) =
        addOption(getDrawable(icon)!!, label, selected, closeBar, onClick)

    private fun addOption(
        icon: android.graphics.drawable.Drawable, label: String, selected: Boolean,
        closeBar: Boolean = true, onClick: (View) -> Unit,
    ) {
        optionRow.addView(optionItem(icon, label, selected) {
            if (closeBar) hideOptionBar()
            onClick(it)
        })
    }

    /** 아이콘 아래 이름이 붙은 칸 (옵션 줄, 무늬 고르기 창) */
    private fun optionItem(
        icon: android.graphics.drawable.Drawable, label: String, selected: Boolean, onClick: (View) -> Unit,
    ): View {
        val d = resources.displayMetrics.density
        // 세로 줄(툴바가 왼쪽·오른쪽)에서는 줄 폭이 넓어지지 않게 긴 이름을 두 줄로
        val vertical = dock.side.vertical
        val item = LinearLayout(this).apply {
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
        item.addView(ImageView(this).apply {
            setImageDrawable(icon)
            layoutParams = LinearLayout.LayoutParams((26 * d).toInt(), (26 * d).toInt())
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        item.addView(TextView(this).apply {
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

    private fun optionLabel(text: String, gap: Int) = TextView(this).apply {
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
        return View(this).apply {
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
                this, "펜이 없는 기기라서 손가락으로 쓰기를 켰습니다. (⋮ 메뉴에서 끌 수 있습니다)", Toast.LENGTH_LONG
            ).show()
            auto
        }

        toolButtons.getValue(Tool.SHAPE).setImageDrawable(ShapePenDrawable(this))
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
                        Toast.makeText(this, "글을 넣을 자리를 누르면 글 상자가 생깁니다. 넣은 글을 누르면 고칠 수 있습니다.", Toast.LENGTH_SHORT).show()
                    }
                    selectTool(t)
                }
            }
        }
        selectTool(Tool.PEN)
        applyToolVisibility()
    }

    private fun selectTool(t: Tool) {
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
            shapeRow.addView(optionItem(ShapeIconDrawable(this, cur, kind.isGuideLine && lineDashed(kind)), text, selected) { v ->
                when {
                    // 보조선(화살표·길이 표시): 바로 고르고, 모양·실선/점선을 고르는 창
                    kind.isGuideLine -> {
                        selectShapeKind(cur)
                        // 칸을 새로 만들었으니 자리가 잡힌 뒤에 그 옆에 띄운다
                        shapeRow.post { showGuideLineFlyout(shapeRow.getChildAt(index) ?: v, family ?: listOf(kind)) }
                    }
                    // 무리: 펼쳐서 하나 고른다
                    family != null -> showFlyout(v, family.map { k ->
                        Triple(ShapeIconDrawable(this, k), k.label, k == docView.shapeKind)
                    }) { i -> selectShapeKind(family[i]) }
                    // 점근선·축이 있는 도형: 고르고 보조선 방식 고르는 창
                    kind in GUIDE_KINDS -> {
                        selectShapeKind(kind)
                        showGuideMenu(shapeRow.getChildAt(index) ?: v, kind)
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
            ShapeIconDrawable(this, k, dashed), k.label.removeSuffix(" 화살표"), k == cur) } +
            listOf(
                Triple(ShapeIconDrawable(this, cur, false), "실선", !dashed),
                Triple(ShapeIconDrawable(this, cur, true), "점선", dashed),
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
            val v = WidthSwatchView(this).apply {
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
        WidthPopup(this, s.kind, s.value, toolColor(t)) { w, done ->
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
        /** 다른 앱 화면 가져오기로 찍은 PNG 경로 (새 쪽으로 넣는다) */
        const val EXTRA_CAPTURE = "capture"
        private const val MAX_TABS = 6
        /** 이미지로 저장할 때 해상도 */
        private const val EXPORT_DPI = 150f
        /** 넣는 그림의 긴 변 최대 픽셀 */
        private const val MAX_IMAGE_PX = 2048
        /** 보조선(점근선·축)을 고를 수 있는 보정 펜 도형 */
        private val GUIDE_KINDS = setOf(ShapeKind.HYPERBOLA, ShapeKind.EXP_LOG, ShapeKind.QUAD_EXP, ShapeKind.TANGENT, ShapeKind.SINE)
        private val TAB_ICON_GRAY = Color.parseColor("#9E9E9E")
        /** 열려 있는 탭 수 (탐색기의 '열린 문서' 버튼용) */
        var openTabs = 0
            private set
    }
}
