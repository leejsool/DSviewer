package com.dsviewer.app

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.view.View
import android.widget.ImageButton
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
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.dsviewer.app.conv.DocConvert
import com.dsviewer.app.conv.ImagePdf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max
import kotlin.math.min

class ViewerActivity : AppCompatActivity() {

    private lateinit var docView: DocumentView
    private lateinit var progress: ProgressBar
    private lateinit var pageLabel: TextView
    private lateinit var textEditor: InlineTextEditor
    private lateinit var tools: ToolbarController
    private lateinit var noteEditor: NoteInlineEditor

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

    private val docs = ArrayList<DocTab>()
    private var current: DocTab? = null
    private val ink get() = current?.ink
    private val saver: DocSaver by lazy {
        DocSaver(this, docView, textEditor, progress, { current }, { createDoc.launch(it) }, ::removeTab, ::updateTabTitle)
    }
    /** 파일 탐색기에서 열었는지 (뒤로 가면 탭을 그대로 둔 채 탐색기로) */
    private var fromBrowser = false

    private val prefs by lazy { getSharedPreferences("tools", Context.MODE_PRIVATE) }

    private val createDoc = registerForActivityResult(ActivityResultContracts.CreateDocument("application/pdf")) { uri ->
        val t = saver.takeSaveTarget()
        if (uri != null && t != null && t in docs) saver.saveTo(t, uri, overwrite = false)
        else saver.closeAfterSave = null
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
        noteEditor = NoteInlineEditor(findViewById(R.id.textEditHost), docView)
        // 글 상자를 닫는 모든 자리(도구 바꾸기·저장·탭 전환…)에서 포스트잇 입력도 함께 넣는다
        textEditor.alsoCommit = { noteEditor.commit() }
        progress = findViewById(R.id.progress)
        pageLabel = findViewById(R.id.pageLabel)
        // 쪽 번호를 누르면 쪽 이동
        pageLabel.setOnClickListener { showGoToPage() }
        tools = ToolbarController(this, docView, textEditor, prefs, { current?.ink }, { wrong.startPick() }, ::showOptionsDialog)
        tools.setupSelectionTools()
        tools.setupEraserTools()
        tools.setupToolbarDock()

        docView.listener = object : DocumentView.Listener {
            override fun onPageChanged(page: Int, count: Int) {
                flashPageLabel()
                // 양쪽 보기면 나란히 놓인 두 쪽을 '1-2 / 20'처럼
                pageLabel.text = if (docView.isSpread && page + 1 < count) "${page + 1}-${page + 2} / $count" else "${page + 1} / $count"
                pagePanel.setCurrent(page)
                overview.setCurrent(page)
            }

            override fun onSelectionChanged(rect: RectF?, count: Int) = tools.placeSelectionBar(rect, count)

            override fun onPenDown() {
                tools.hideOptionBar()
                tools.closeFlyout()
            }

            override fun onTextTap(page: Int, x: Float, y: Float, existing: Stroke?) =
                this@ViewerActivity.onTextTap(page, x, y, existing)

            override fun onViewportChanged() {
                textEditor.reposition()
                noteEditor.reposition()
            }

            // 포스트잇 입력칸 밖을 누르면 친 글을 메모에 넣는다 (누름은 그대로 처리된다)
            override fun onTouchDown() = noteEditor.commit()

            override fun onScrolled() = flashPageLabel()

            override fun onPullAddPage() = appendBlankPage()

            override fun onReadTap(page: Int, x: Float, y: Float) = followLinkAt(page, x, y)
            override fun onWrongTap(page: Int, x: Float, y: Float) {
                current?.ink?.wrongLinkAt(page, x, y)?.let { (p, yy) -> wrong.goToSpot(p, yy) }
            }

            override fun onNoteEdit(page: Int, note: Stroke) {
                textEditor.commit()
                noteEditor.start(page, note)
            }

            override fun onNoteColorPicked(color: Int) {
                prefs.edit().putInt("noteColor", color).apply()
            }

            override fun onNotePlacementEnded() {
                placeHint?.let {
                    placeHint = null
                    it.dismiss()
                }
            }

            override fun onWrongPicked(page: Int, rect: RectF) = wrong.capture(page, rect)

            override fun onWrongPickEnded() = wrong.onPickEnded()

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

        tools.setupTools()
        tools.setupFormatBar()
        tools.watchOverlays()
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
            val region = intent.getBooleanExtra(EXTRA_CAPTURE_REGION, false)
            intent.removeExtra(EXTRA_CAPTURE_REGION)
            if (region) pickCaptureRegion(File(path)) else insertCapturedPage(File(path))
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
            pageLabel.removeCallbacks(hidePageLabel)
            pageLabel.animate().cancel()
            pageLabel.visibility = View.GONE
            progress.visibility = View.VISIBLE
        }
        updateActions()
    }

    /** 쪽 번호가 사라지게 하는 일 (넘기면 다시 미룬다) */
    private val hidePageLabel = Runnable {
        pageLabel.animate().alpha(0f).setDuration(PAGE_LABEL_FADE_MS).withEndAction {
            // 투명해진 쪽 번호가 누름(쪽 이동)을 받지 않게. 자리는 그대로 두어 아래 줄들이 흔들리지 않게
            if (pageLabel.alpha == 0f) pageLabel.visibility = View.INVISIBLE
        }.start()
    }

    /** 쪽 번호를 보이고, 넘기지 않은 채 몇 초 지나면 흐려지며 사라지게 한다 */
    private fun flashPageLabel() {
        pageLabel.removeCallbacks(hidePageLabel)
        pageLabel.animate().cancel()
        pageLabel.visibility = View.VISIBLE
        pageLabel.alpha = 1f
        pageLabel.postDelayed(hidePageLabel, PAGE_LABEL_SHOW_MS)
    }

    private fun closeTab(t: DocTab) {
        // 치던 글(과 포스트잇 입력)을 먼저 쪽에 넣는다: 그래야 '저장하지 않은 필기'로 잡혀 물어본다
        if (current === t) textEditor.commit()
        if (t.ink?.dirty != true) {
            removeTab(t)
            return
        }
        docTabs.select(docs.indexOf(t), notify = true)
        MaterialAlertDialogBuilder(this)
            .setTitle("'${t.name}'에 저장하지 않은 필기가 있습니다")
            .setMessage("저장할까요?")
            .setPositiveButton("저장") { _, _ ->
                saver.closeAfterSave = t
                saver.save(t, asNew = false)
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
        val wrongs = HashMap<Int, String>()
        val (renderFile, strokes) = withContext(Dispatchers.IO) {
            if (PdfInk.containsInk(file)) {
                val clean = FileUtil.tempFile(this@ViewerActivity, "clean", "pdf")
                clean to PdfInk.extract(file, clean, marks, wrongs)
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
        inkDoc.loadWrongs(wrongs)
        inkDoc.onChanged = {
            if (current === t) {
                updateActions()
                docView.refreshMargin()
                docView.invalidate()
                pagePanel.inkChanged()
                overview.inkChanged()
            }
            updateTabTitle(t)
        }
        inkDoc.swapPages = { files, apply -> restorePageFiles(t, files as PageFiles, apply) }
        inkDoc.jumpTo = { spot -> if (current === t) (spot as Spot).let { docView.scrollToPageY(it.page, it.y) } }
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
        saveButton.setOnClickListener { saver.showSaveMenu(it) }
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
        applyActionVisibility()
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
        // 읽기 전용이면 책에 눈, 쓸 수 있으면 책에 펜
        readModeButton.setImageResource(if (readMode) R.drawable.ic_read_mode else R.drawable.ic_write_mode)
        readModeButton.contentDescription = if (readMode) "읽기 모드 끝내기" else "읽기 모드"
    }

    /** 켜진 보기 단추는 바탕에 옅은 동그라미 */
    private fun ImageButton.setActive(on: Boolean) {
        if (isSelected == on && background != null) return
        isSelected = on
        background = if (on) GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            // 탭 줄 바탕과 확실히 구분되는 진한 파랑 바탕 + 테두리
            setColor(getColor(R.color.active_fill))
            setStroke((2 * resources.displayMetrics.density).toInt(), getColor(R.color.active_stroke))
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
            if (asPdf) savePageFile(t, pages) else saver.exportImages(t, pages)
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

    /**
     * 읽기 모드에서 누른 자리의 링크: 다른 쪽이면 바로 가고(실행 취소하면 돌아온다),
     * 웹 주소면 물어보고 브라우저로. 링크 삽입으로 단 글이 PDF 링크보다 먼저
     */
    private fun followLinkAt(page: Int, x: Float, y: Float) {
        docView.inkLinkAt(page, x, y)?.let { openWebLink(it); return }
        current?.ink?.wrongLinkAt(page, x, y)?.let { (p, yy) -> wrong.goToSpot(p, yy); return }
        val links = currentLinks() ?: return
        links.whenReady { l ->
            val t = current
            if (t?.links !== l || !readMode) return@whenReady
            val link = l.at(page, x, y) ?: return@whenReady
            val uri = link.uri
            if (uri != null) {
                openWebLink(uri)
                return@whenReady
            }
            val before = docView.topSpot()
            val to = Spot(link.page, link.y ?: 0f)
            docView.scrollToPageY(to.page, to.y)
            if (before != null) t.ink?.jumped(Spot(before.first, before.second), to)
        }
    }

    /** 'www.…'처럼 앞이 빠진 주소는 웹 주소로. 웹·메일 주소가 아니면 null */
    private fun webUri(text: String): Uri? {
        val s = text.trim()
        if (s.isEmpty()) return null
        val full = if (Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:").containsMatchIn(s)) s else "http://$s"
        val u = Uri.parse(full)
        return u.takeIf { it.scheme?.lowercase() in setOf("http", "https", "mailto") }
    }

    /** "이 주소로 이동할까요?"를 묻고 브라우저로 */
    private fun openWebLink(uri: String) {
        val parsed = webUri(uri)
        if (parsed == null) {
            toast("열 수 없는 링크입니다: $uri")
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("이 주소로 이동할까요?")
            .setMessage(parsed.toString())
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
                41 -> saver.exportImages(t, listOf(page))
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
                    val bmp = saver.pageBitmap(d, inkDoc, page)
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
            tools.hideOptionBar()
            tools.closeFlyout()
            docView.clearSelection()
        }
        readMode = on
        docView.readOnly = on
        // 링크를 미리 꺼내 둔다 (처음 누를 때 기다리지 않게)
        if (on) currentLinks()
        findViewById<View>(R.id.toolbar).visibility = if (on) View.GONE else View.VISIBLE
        tools.updateShapeBarForReadMode(on)
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
        popup.menu.findItem(R.id.action_save_menu).isEnabled = ink != null
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
                R.id.action_save_now -> current?.let { if (it.ink != null) saver.save(it, asNew = false) }
                R.id.action_save_as -> current?.let { if (it.ink != null) saver.save(it, asNew = true) }
                R.id.action_save_image -> current?.let { if (it.ink != null) saver.askExportImages(it) }
                R.id.action_options -> showOptionsDialog(0)
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
        popup.menu.add(0, 6, 1, "빈 쪽").setIcon(R.drawable.ic_paper_plain)
            .isEnabled = ink != null && !docView.readOnly
        popup.menu.add(0, 7, 1, "포스트잇 메모").setIcon(R.drawable.ic_sticky_note)
            .isEnabled = ink != null && !docView.readOnly
        popup.menu.add(0, 8, 1, "오답 담기 (영역 선택)").setIcon(R.drawable.ic_wrong_note)
            .isEnabled = ink != null && !docView.readOnly
        popup.menu.add(0, 9, 1, "오답노트 목록 · 분류").setIcon(R.drawable.ic_wrong_note)
            .isEnabled = ink != null
        popup.menu.add(0, 5, 2, "링크").setIcon(R.drawable.ic_link)
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
                item.itemId == 5 -> showInsertLink()
                item.itemId == 6 -> showInsertBlankPage()
                item.itemId == 7 -> startNotePlacement()
                item.itemId == 8 -> wrong.startPick()
                item.itemId == 9 -> wrong.showList()
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

    /** '포스트잇을 붙일 곳을 탭해 주세요' 안내 (붙이거나 취소하면 닫는다) */
    private var placeHint: Snackbar? = null

    /** 삽입 ▸ 포스트잇 메모: 안내를 띄우고, 다음에 탭한 자리에 메모를 붙인다 */
    private fun startNotePlacement() {
        textEditor.commit()
        if (!docView.startNotePlacement()) return
        placeHint?.dismiss()
        placeHint = Snackbar.make(findViewById(R.id.docFrame), "포스트잇을 붙일 곳을 탭해 주세요.", Snackbar.LENGTH_INDEFINITE)
            .setAction("취소") { docView.cancelNotePlacement() }
            .addCallback(object : Snackbar.Callback() {
                // 밀어서 닫아도 붙이기를 그만둔다
                override fun onDismissed(bar: Snackbar?, event: Int) {
                    if (placeHint === bar) placeHint = null
                    docView.cancelNotePlacement()
                }
            })
            .also { it.show() }
    }

    // ================= 오답노트 =================

    private val wrong: WrongController by lazy {
        WrongController(this, prefs, docView, textEditor, progress, { current }, ::toast, ::editPages)
    }

    /**
     * 삽입 ▸ 빈 쪽: 넣을 자리(맨 앞 · 지금 쪽 다음 · 맨 뒤)와 서식을 골라 빈 쪽을 넣는다.
     * 서식은 '기존대로'(옆 쪽과 같은 바탕·크기) 또는 '직접 정하기'(바탕·방향을 그림 단추로). 고른 것은 다음에도 그대로
     */
    private fun showInsertBlankPage() {
        val t = current ?: return
        val d = t.pdf ?: return
        if (t.ink == null || docView.readOnly) return
        val page = docView.currentPage().coerceIn(0, d.pageCount - 1)
        val view = layoutInflater.inflate(R.layout.dialog_insert_page, null)
        val posGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.posGroup)
        val formatGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.formatGroup)
        val hint = view.findViewById<TextView>(R.id.formatHint)
        val custom = view.findViewById<View>(R.id.customFormat)
        val paperGroup = custom.findViewById<MaterialButtonToggleGroup>(R.id.paperGroup)
        val orientGroup = custom.findViewById<MaterialButtonToggleGroup>(R.id.orientGroup)
        view.findViewById<TextView>(R.id.posAfter).text = "${page + 1}쪽 다음"

        val posIds = mapOf(PdfInsertAt.FIRST to R.id.posFirst, PdfInsertAt.AFTER_CURRENT to R.id.posAfter, PdfInsertAt.LAST to R.id.posLast)
        val paperIds = mapOf(Paper.PLAIN to R.id.paperPlain, Paper.GRID to R.id.paperGrid, Paper.LINED to R.id.paperLined)
        val lastPos = prefs.getString("blankPos", null)?.let { n -> PdfInsertAt.entries.firstOrNull { it.name == n } } ?: PdfInsertAt.AFTER_CURRENT
        val lastPaper = prefs.getString("blankPaper", null)?.let { n -> Paper.entries.firstOrNull { it.name == n } } ?: Paper.GRID
        // 직접 정하기의 방향은 처음엔 지금 쪽 방향으로
        val curSize = d.sizes[page]
        val portrait0 = prefs.getString("blankOrient", null)?.let { it == "portrait" } ?: (curSize.height >= curSize.width)
        posGroup.check(posIds.getValue(lastPos))
        formatGroup.check(if (prefs.getBoolean("blankCustom", false)) R.id.formatCustom else R.id.formatSame)
        paperGroup.check(paperIds.getValue(lastPaper))
        orientGroup.check(if (portrait0) R.id.orientPortrait else R.id.orientLandscape)

        fun indexOf(pos: PdfInsertAt) = when (pos) {
            PdfInsertAt.FIRST -> 0
            PdfInsertAt.AFTER_CURRENT -> page + 1
            PdfInsertAt.LAST -> d.pageCount
        }
        fun pos() = posIds.entries.first { it.value == posGroup.checkedButtonId }.key
        fun refresh() {
            val isCustom = formatGroup.checkedButtonId == R.id.formatCustom
            custom.visibility = if (isCustom) View.VISIBLE else View.GONE
            hint.visibility = if (isCustom) View.GONE else View.VISIBLE
            val ref = blankPageRef(d, indexOf(pos()))
            hint.text = "${ref + 1}쪽과 같은 바탕(흰 바탕·모눈·줄)·크기·방향으로 넣습니다."
        }
        posGroup.addOnButtonCheckedListener { _, _, checked -> if (checked) refresh() }
        formatGroup.addOnButtonCheckedListener { _, _, checked -> if (checked) refresh() }
        refresh()

        MaterialAlertDialogBuilder(this)
            .setTitle("빈 쪽 넣기")
            .setView(view)
            .setPositiveButton("넣기") { _, _ ->
                val p = pos()
                val isCustom = formatGroup.checkedButtonId == R.id.formatCustom
                val paper = paperIds.entries.first { it.value == paperGroup.checkedButtonId }.key
                val portrait = orientGroup.checkedButtonId == R.id.orientPortrait
                val e = prefs.edit().putString("blankPos", p.name).putBoolean("blankCustom", isCustom)
                if (isCustom) e.putString("blankPaper", paper.name).putString("blankOrient", if (portrait) "portrait" else "landscape")
                e.apply()
                if (isCustom) insertBlankPage(paper, indexOf(p), portrait) else insertBlankPage(null, indexOf(p))
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 링크 넣기: 웹 주소와 보일 글자를 받아 파란 밑줄 글로 넣는다. 읽기 모드에서 누르면 열린다 */
    private fun showInsertLink() {
        if (current?.ink == null) return
        val d = resources.displayMetrics.density
        val url = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            isSingleLine = true
            hint = "웹 주소 (예: www.example.com)"
        }
        val label = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            imeOptions = EditorInfo.IME_ACTION_DONE
            isSingleLine = true
            hint = "보일 글자 (비우면 주소 그대로)"
        }
        // 주소 칸 옆: 클립보드의 글을 주소 칸에 붙여 넣는다
        val paste = ImageButton(this).apply {
            setImageResource(R.drawable.ic_paste)
            contentDescription = "붙여넣기"
            tooltipText = "클립보드에서 붙여넣기"
            android.util.TypedValue().let { tv ->
                theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
                setBackgroundResource(tv.resourceId)
            }
            setOnClickListener {
                val cm = getSystemService(android.content.ClipboardManager::class.java)
                val clip = cm?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this@ViewerActivity)
                    ?.toString()?.trim()
                if (clip.isNullOrEmpty()) {
                    toast("클립보드에 붙여 넣을 글이 없습니다.")
                    return@setOnClickListener
                }
                url.setText(clip)
                url.setSelection(url.text.length)
                url.error = null
            }
        }
        val urlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            addView(url, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            addView(paste, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()))
        }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * d).toInt(), (4 * d).toInt(), (24 * d).toInt(), 0)
            addView(urlRow)
            addView(label)
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("링크 넣기")
            .setView(box)
            .setPositiveButton("넣기", null)
            .setNegativeButton("취소", null)
            .create()
        fun insert() {
            val u = webUri(url.text.toString())
            if (u == null) {
                url.error = "웹 주소를 입력해 주세요"
                return
            }
            val shown = label.text.toString().trim().ifEmpty { url.text.toString().trim() }
            val text = InkText(RichDoc(shown, listOf(RichDoc.Run(0, shown.length, 'u')), emptyList()), defaultTextSize)
            dialog.dismiss()
            if (readMode) setReadMode(false)
            // 넣은 링크를 바로 옮길 수 있게 선택 도구로
            tools.selectTool(Tool.LASSO)
            if (!docView.insertLink(text, LINK_COLOR, u.toString())) toast("링크를 넣지 못했습니다.")
            else toast("읽기 모드에서 누르면 열립니다.")
        }
        label.setOnEditorActionListener { _, _, _ -> insert(); true }
        dialog.setOnShowListener { dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { insert() } }
        dialog.show()
        url.requestFocus()
        dialog.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
    }

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
            tools.selectTool(Tool.LASSO)
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

    /** 화면 전송 허락을 받고 떠 있는 캡처 단추(화면 전체 · 일부분)를 띄운다. 처음엔 '다른 앱 위에 표시' 권한부터 */
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

    /**
     * 일부분 가져오기: 찍어 온 화면을 크게 보여 주고 끌어서 네모로 고르면 그 부분만 새 쪽으로 넣는다.
     * [고른 부분 가져오기]·[전체]·[취소]
     */
    private fun pickCaptureRegion(png: File) {
        val bmp = android.graphics.BitmapFactory.decodeFile(png.path)
        if (bmp == null) {
            png.delete(); captureTab = null
            toast("찍은 화면을 열지 못했습니다.")
            return
        }
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val dialog = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
        val picker = RegionPickView(this, bmp)
        val hint = TextView(this).apply {
            text = "가져올 부분을 끌어서 네모로 고르세요. 모서리·변을 끌면 크기, 안쪽을 끌면 옮겨집니다."
            setTextColor(Color.WHITE)
            textSize = 14f
            setPadding(dp(16), dp(10), dp(16), dp(10))
            setBackgroundColor(0xAA000000.toInt())
        }
        fun button(text: String) = com.google.android.material.button.MaterialButton(this).apply {
            this.text = text
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                .apply { marginStart = dp(4); marginEnd = dp(4) }
        }
        val cancel = button("취소")
        val whole = button("전체")
        val ok = button("고른 부분 가져오기").apply { isEnabled = false }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(0xAA000000.toInt())
            addView(cancel); addView(whole); addView(ok)
        }
        val root = android.widget.FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(picker, android.widget.FrameLayout.LayoutParams(-1, -1).apply { topMargin = dp(40); bottomMargin = dp(64) })
            addView(hint, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.TOP))
            addView(bar, android.widget.FrameLayout.LayoutParams(-1, -2, android.view.Gravity.BOTTOM))
        }
        picker.onSelectionChanged = { ok.isEnabled = it }
        fun finish(file: File?) {
            dialog.setOnDismissListener(null)
            dialog.dismiss()
            bmp.recycle()
            if (file == null) { png.delete(); captureTab = null } else insertCapturedPage(file)
        }
        cancel.setOnClickListener { finish(null) }
        whole.setOnClickListener { finish(png) }
        ok.setOnClickListener {
            val r = picker.selection() ?: return@setOnClickListener
            val out = runCatching {
                val crop = Bitmap.createBitmap(bmp, r.left, r.top, r.width(), r.height())
                val f = FileUtil.tempFile(this, "capture", "png")
                f.outputStream().use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
                crop.recycle()
                png.delete()
                f
            }.getOrNull()
            if (out == null) toast("고른 부분을 만들지 못했습니다.")
            else finish(out)
        }
        dialog.setContentView(root)
        dialog.setOnCancelListener { finish(null) }
        dialog.show()
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
        tools.hideOptionBar()
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

    /** [at]번째 자리에 넣을 빈 쪽이 크기·바탕을 따를 쪽: 바로 앞 쪽 (맨 앞에 넣으면 첫 쪽) */
    private fun blankPageRef(d: PdfDoc, at: Int) = (at - 1).coerceIn(0, d.pageCount - 1)

    /**
     * [at]번째 자리(0부터, 기본은 보고 있는 쪽 뒤)에 빈 쪽을 넣고 그 쪽으로 간다.
     * 크기는 앞 쪽(맨 앞이면 첫 쪽)과 같고, [portrait]를 주면 그 방향으로 긴 변·짧은 변을 맞춘다.
     * [paper]가 null이면 바탕도 앞 쪽(흰 바탕·모눈·줄)을 따른다
     */
    private fun insertBlankPage(paper: Paper?, at: Int = docView.currentPage() + 1, portrait: Boolean? = null) {
        val t = current ?: return
        val d = t.pdf ?: return
        val ref = blankPageRef(d, at)
        val size = d.sizes[ref]
        val long = max(size.width, size.height)
        val short = min(size.width, size.height)
        val (w, h) = when (portrait) {
            null -> size.width to size.height
            true -> short to long
            false -> long to short
        }
        var found = paper
        editPages(t, { src, out ->
            // 원본에서 한 번 알아낸 바탕을 화면용 PDF에도 똑같이
            val p = found ?: runCatching { PdfPages.paperOf(src, ref) }.getOrDefault(Paper.PLAIN).also { found = it }
            PdfPages.insert(src, out, at, p, w, h)
        }) { pages ->
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
        insertBlankPage(null, d.pageCount)
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
                    this, d.pageCount, page, page, "지울 쪽", "지우기",
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

    /** '직선으로', '지수로', '원으로' (받침이 없거나 ㄹ받침이면 '로') */
    private fun withRo(word: String): String {
        val last = word.lastOrNull() ?: return word
        if (last !in '가'..'힣') return word + "로"
        val jong = (last - '가') % 28
        return word + if (jong == 0 || jong == 8) "로" else "으로"
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

    // ================= 옵션 (⋮ ▸ 옵션, 툴바 손잡이 톡) =================
    // 상단 툴바(탭 줄 오른쪽) 아이콘, 하단 툴바(도구) 아이콘 보이기·숨기기와 편의 옵션 켜고 끄기

    /** 상단 툴바 단추: prefs에 남기는 이름, 옵션 창에 보일 이름, 단추, 아이콘 */
    private class TopAction(val key: String, val label: String, val button: () -> View, val icon: Int)

    private val topActions by lazy {
        listOf(
            TopAction("undo", "실행 취소", { undoButton }, R.drawable.ic_undo),
            TopAction("redo", "다시 실행", { redoButton }, R.drawable.ic_redo),
            TopAction("save", "저장", { saveButton }, R.drawable.ic_save),
            TopAction("insert", "삽입", { insertButton }, R.drawable.ic_insert),
            TopAction("twoPage", "양쪽 보기", { twoPageButton }, R.drawable.ic_two_page),
            TopAction("pages", "페이지 관리", { pagesButton }, R.drawable.ic_page_panel),
            TopAction("overview", "쪽 한눈에 보기", { overviewButton }, R.drawable.ic_grid_view),
            TopAction("readMode", "읽기 모드", { readModeButton }, R.drawable.ic_write_mode),
            TopAction("fullscreen", "전체 화면", { fullscreenButton }, R.drawable.ic_fullscreen),
        )
    }

    private fun hiddenActions(): Set<String> =
        prefs.getString("hiddenActions", null)?.split(',')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    /** 숨긴 상단 단추를 감춘다. 읽기 모드 중에는 끝낼 길이 필요해서 읽기 모드 단추는 늘 보인다 */
    private fun applyActionVisibility() {
        val hidden = hiddenActions()
        for (a in topActions) a.button().visibility = if (a.key in hidden && !(a.key == "readMode" && readMode)) View.GONE else View.VISIBLE
    }

    private fun showOptionsDialog(start: Int = 0) {
        fun icon(res: Int) = { getDrawable(res)?.mutate() }
        val top = OptionCategory(
            "상단 툴바", "탭 줄 오른쪽의 아이콘을 표시하거나 숨깁니다. ⋮(더 보기)는 늘 보입니다.",
            topActions.map { a ->
                OptionItem(icon(a.icon), a.label, { a.key !in hiddenActions() }, { on ->
                    val hidden = hiddenActions().toMutableSet()
                    if (on) hidden.remove(a.key) else hidden.add(a.key)
                    prefs.edit().putString("hiddenActions", hidden.joinToString(",")).apply()
                    applyActionVisibility()
                    true
                })
            },
        )
        val bottom = OptionCategory(
            "하단 툴바", "펜·지우개 같은 도구 아이콘을 표시하거나 숨깁니다. 도구는 하나는 남겨 두어야 합니다.",
            tools.toolNames.map { (t, name) ->
                // 보정 펜 아이콘은 직접 그리는 Drawable이라 복사할 수 없어서 새로 만든다
                OptionItem({
                    if (t == Tool.SHAPE) ShapePenDrawable(this).also { it.markColor = docView.penColor }
                    else tools.toolButtons[t]?.drawable?.constantState?.newDrawable()?.mutate()
                }, name, { t !in tools.hiddenTools() }, { on ->
                    val hidden = tools.hiddenTools().toMutableSet()
                    if (!on && tools.toolNames.count { it.first !in hidden } <= 1) {
                        toast("도구를 하나는 남겨 두어야 합니다.")
                        return@OptionItem false
                    }
                    if (on) hidden.remove(t) else hidden.add(t)
                    prefs.edit().putString("hiddenTools", hidden.joinToString(",") { it.name }).apply()
                    tools.applyToolVisibility()
                    true
                })
            },
        )
        val convenience = OptionCategory(
            "편의 옵션", "필기할 때 쓰는 편의 기능을 켜고 끕니다.",
            listOf(
                OptionItem(icon(R.drawable.ic_scribble_erase), "긁어서 지우기", { docView.scribbleErase }, { on ->
                    docView.scribbleErase = on
                    prefs.edit().putBoolean("scribbleErase", on).apply()
                    true
                }, "펜으로 좌우나 위아래로 마구 긁으면 긁은 자리가 지워집니다."),
                OptionItem(icon(R.drawable.ic_palm_erase), "손바닥 지우기", { docView.palmErase }, { on ->
                    docView.palmErase = on
                    prefs.edit().putBoolean("palmErase", on).apply()
                    true
                }, "손가락으로 쓰는 중 손바닥으로 문지르면 지워집니다."),
            ),
        )
        OptionsDialog(this, listOf(top, bottom, convenience)) {
            prefs.edit().remove("hiddenActions").remove("hiddenTools").putBoolean("scribbleErase", true).putBoolean("palmErase", true).apply()
            docView.scribbleErase = true
            docView.palmErase = true
            applyActionVisibility()
            tools.applyToolVisibility()
        }.show(start)
    }

    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    companion object {
        /** 넘기지 않은 채 이만큼(ms) 지나면 쪽 번호가 흐려지며 사라진다 */
        private const val PAGE_LABEL_SHOW_MS = 2500L
        private const val PAGE_LABEL_FADE_MS = 400L
        const val EXTRA_WRITABLE = "writable"
        /** 앱의 파일 탐색기에서 연 문서 */
        const val EXTRA_FROM_BROWSER = "fromBrowser"
        /** 탐색기의 '새 노트'로 만든 빈 문서 (처음 저장할 때 저장 위치를 고른다) */
        const val EXTRA_NEW_NOTE = "newNote"
        /** 다른 앱 화면 가져오기로 찍은 PNG 경로 (새 쪽으로 넣는다) */
        const val EXTRA_CAPTURE = "capture"
        /** true면 찍은 화면에서 네모로 부분을 골라 그 부분만 넣는다 */
        const val EXTRA_CAPTURE_REGION = "captureRegion"
        private const val MAX_TABS = 6
        /** 넣는 그림의 긴 변 최대 픽셀 */
        private const val MAX_IMAGE_PX = 2048
        /** 링크 넣기로 넣은 글의 색 (파란 밑줄) */
        private val LINK_COLOR = Color.parseColor("#1A5FD0")
        private val TAB_ICON_GRAY = Color.parseColor("#9E9E9E")
        /** 열려 있는 탭 수 (탐색기의 '열린 문서' 버튼용) */
        var openTabs = 0
            private set
    }
}
