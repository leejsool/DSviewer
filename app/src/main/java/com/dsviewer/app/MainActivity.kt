package com.dsviewer.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date

/** 첫 화면: 최근 파일 · 기기 전체 문서 · 폴더 탐색 */
class MainActivity : AppCompatActivity() {

    private enum class Tab(val label: String, val icon: Int) {
        RECENT("최근 파일", R.drawable.ic_history),
        FAVORITE("즐겨찾기", R.drawable.ic_star),
        ALL("모든 문서", R.drawable.ic_doc),
        FOLDER("폴더", R.drawable.ic_folder),
    }
    private enum class Kind { FOLDER, DRIVE, PDF, HWP }

    private class Row(
        val title: String,
        val sub: String,
        val kind: Kind,
        val onClick: () -> Unit,
        val onLongClick: (() -> Unit)? = null,
        /** null이면 별표를 숨긴다 (최근 파일 탭에서만 보임) */
        val star: Boolean? = null,
        val onStar: (() -> Unit)? = null,
    )

    private lateinit var toolbar: MaterialToolbar
    private lateinit var tabs: ChromeTabBar
    private lateinit var list: RecyclerView
    private lateinit var emptyText: TextView
    private lateinit var progress: View
    private lateinit var permCard: View
    private lateinit var pathBar: View
    private lateinit var pathScroll: HorizontalScrollView
    private lateinit var pathText: TextView
    private lateinit var upButton: View
    private lateinit var searchItem: MenuItem

    private val adapter = RowAdapter()
    private val prefs by lazy { getSharedPreferences("browser", MODE_PRIVATE) }

    private var tab = Tab.RECENT
    /** 폴더 탭의 현재 폴더 (null = 저장소 목록) */
    private var dir: File? = null
    private var roots: List<Entry> = emptyList()
    private var rows: List<Row> = emptyList()
    private var emptyMsg = ""
    private var query = ""
    private var loadJob: Job? = null
    /** 위 폴더로 돌아갈 때 이전 스크롤 위치를 되살린다 */
    private val scrollStates = HashMap<String, Parcelable?>()
    private var pendingScroll: Parcelable? = null
    private var pickMode = false

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    private val requestRead = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            if (searchItem.isActionViewExpanded) searchItem.collapseActionView() else goUp()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        applyInsets(findViewById(R.id.root))

        toolbar = findViewById(R.id.toolbar)
        tabs = findViewById(R.id.tabs)
        list = findViewById(R.id.list)
        emptyText = findViewById(R.id.emptyText)
        progress = findViewById(R.id.progress)
        permCard = findViewById(R.id.permCard)
        pathBar = findViewById(R.id.pathBar)
        pathScroll = findViewById(R.id.pathScroll)
        pathText = findViewById(R.id.pathText)
        upButton = findViewById(R.id.upButton)

        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        upButton.setOnClickListener { goUp() }
        findViewById<View>(R.id.permButton).setOnClickListener { askAccess() }
        setupMenu()

        dir = prefs.getString("dir", null)?.let(::File)?.takeIf { it.isDirectory }
        val defaultTab = if (Recents.list(this).isEmpty()) Tab.ALL else Tab.RECENT
        tab = prefs.getString("tabName", null)?.let { n -> Tab.entries.firstOrNull { it.name == n } } ?: defaultTab
        val gray = com.google.android.material.color.MaterialColors.getColor(
            tabs, com.google.android.material.R.attr.colorOnSurfaceVariant
        )
        tabs.maxTabWidthDp = 180f
        for (t in Tab.entries) tabs.addTab(t.label, t.icon, if (t == Tab.FAVORITE) null else gray)
        tabs.select(tab.ordinal)
        tabs.listener = object : ChromeTabBar.Listener {
            override fun onTabSelected(index: Int) {
                tab = Tab.entries[index]
                prefs.edit().putString("tabName", tab.name).apply()
                adapter.submit(emptyList())
                refresh()
            }
        }

        // 맨 바깥 뒤로 가기: 문서 고르기 중이면 뷰어로, 아니면 앱을 뒤로 보낸다(열린 탭은 유지).
        // 폴더 올라가기·검색 닫기(backCallback)가 먼저 처리되도록 먼저 등록한다
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (pickMode) backToViewer() else moveTaskToBack(true)
            }
        })
        onBackPressedDispatcher.addCallback(this, backCallback)
        setPickMode(intent.getBooleanExtra(EXTRA_PICK, false))
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        setPickMode(intent.getBooleanExtra(EXTRA_PICK, false))
    }

    override fun onResume() {
        super.onResume()
        // 권한 설정에서 돌아왔거나, 문서를 저장하고 돌아왔을 수 있으므로 매번 새로 읽는다
        refresh()
        updateOpenTabs()
    }

    private fun setupMenu() {
        toolbar.inflateMenu(R.menu.main)
        searchItem = toolbar.menu.findItem(R.id.action_search)
        (searchItem.actionView as SearchView).apply {
            queryHint = "파일 이름"
            setOnQueryTextListener(object : SearchView.OnQueryTextListener {
                override fun onQueryTextSubmit(q: String) = true
                override fun onQueryTextChange(q: String): Boolean {
                    this@MainActivity.query = q
                    applyFilter()
                    return true
                }
            })
        }
        searchItem.setOnActionExpandListener(object : MenuItem.OnActionExpandListener {
            override fun onMenuItemActionExpand(item: MenuItem): Boolean {
                backCallback.isEnabled = true
                return true
            }
            override fun onMenuItemActionCollapse(item: MenuItem): Boolean {
                query = ""
                applyFilter()
                list.post { updateBack() }
                return true
            }
        })
        toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_new_note -> { newNote(); true }
                R.id.action_pick -> { openDoc.launch(arrayOf("*/*")); true }
                R.id.action_open_tabs -> { backToViewer(); true }
                else -> false
            }
        }
    }

    // ================= 목록 =================

    private fun refresh() {
        loadJob?.cancel()
        val needAccess = (tab == Tab.ALL || tab == Tab.FOLDER) && !DocFiles.hasAccess(this)
        permCard.isVisible = needAccess
        pathBar.isVisible = tab == Tab.FOLDER && !needAccess
        if (needAccess) {
            progress.isVisible = false
            show(emptyList(), "")
            updateBack()
            return
        }
        roots = DocFiles.roots(this)
        when (tab) {
            Tab.RECENT -> {
                progress.isVisible = false
                show(recentRows(), "최근에 연 파일이 없습니다.\n'모든 문서'나 '폴더' 탭에서 문서를 골라 보세요.")
            }
            Tab.FAVORITE -> {
                progress.isVisible = false
                show(recentRows(starredOnly = true), "즐겨찾기한 문서가 없습니다.\n'최근 파일'에서 문서 옆의 ☆를 누르면 여기에 모입니다.")
            }
            Tab.ALL -> load("기기에서 PDF · HWP · HWPX 문서를 찾지 못했습니다.") {
                DocFiles.scanAll(this).map { fileRow(it, showPath = true) }
            }
            Tab.FOLDER -> {
                if (dir?.let { d -> roots.none { d.path.startsWith(it.file.path) } } == true) dir = null
                if (dir == null && roots.size == 1) dir = roots[0].file
                val d = dir
                pathText.text = pathLabel(d)
                pathScroll.post { pathScroll.fullScroll(View.FOCUS_RIGHT) }
                upButton.isEnabled = canGoUp()
                upButton.alpha = if (canGoUp()) 1f else 0.3f
                load("이 폴더에는 문서가 없습니다.") {
                    if (d == null) roots.map { r -> Row(r.name, r.file.path, Kind.DRIVE, { openDir(r.file) }) }
                    else DocFiles.list(d).map { fileRow(it, showPath = false) }
                }
            }
        }
        updateBack()
    }

    private fun load(emptyMsg: String, block: () -> List<Row>) {
        progress.isVisible = adapter.itemCount == 0
        emptyText.isVisible = false
        loadJob = lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) { runCatching(block).getOrDefault(emptyList()) }
            progress.isVisible = false
            show(result, emptyMsg)
            pendingScroll?.let { list.layoutManager?.onRestoreInstanceState(it) }
            pendingScroll = null
        }
    }

    private fun show(rows: List<Row>, emptyMsg: String) {
        this.rows = rows
        this.emptyMsg = emptyMsg
        applyFilter()
    }

    private fun applyFilter() {
        val q = query.trim()
        val shown = if (q.isEmpty()) rows else rows.filter { it.title.contains(q, ignoreCase = true) }
        adapter.submit(shown)
        emptyText.text = if (q.isEmpty()) emptyMsg else "'$q' 이름의 문서가 없습니다."
        emptyText.isVisible = shown.isEmpty() && !progress.isVisible && !permCard.isVisible
    }

    /** 즐겨찾기를 맨 위에, 그 안에서는 최근에 연 순서 그대로. starredOnly면 즐겨찾기만 */
    private fun recentRows(starredOnly: Boolean = false): List<Row> = Recents.list(this)
        .filter { !starredOnly || it.star }
        .sortedByDescending { it.star }.mapNotNull { item ->
        val uri = Uri.parse(item.uri)
        val file = if (uri.scheme == "file") File(uri.path ?: "") else null
        if (file != null && !file.exists()) {
            Recents.remove(this, item.uri)
            return@mapNotNull null
        }
        val sub = listOfNotNull(date(item.time), file?.parentFile?.let(::shortPath)).joinToString(" · ")
        Row(item.name, sub, kindOf(item.name), {
            Recents.add(this, item.uri, item.name)
            openViewer(uri)
        }, { confirmRemoveRecent(item) }, star = item.star, onStar = {
            Recents.setStar(this, item.uri, !item.star)
            refresh()
        })
    }

    private fun fileRow(e: Entry, showPath: Boolean): Row {
        if (e.isDir) return Row(e.name, "", Kind.FOLDER, { openDir(e.file) })
        val info = "${date(e.time)} · ${Formatter.formatShortFileSize(this, e.size)}"
        val sub = if (showPath) "${e.file.parentFile?.let(::shortPath) ?: ""} · $info" else info
        return Row(e.name, sub, kindOf(e.name), { openFile(e.file) })
    }

    private fun kindOf(name: String) = if (name.lowercase().endsWith(".pdf")) Kind.PDF else Kind.HWP

    private fun date(t: Long) = DateFormat.format("yyyy.MM.dd HH:mm", Date(t)).toString()

    /** /storage/emulated/0/Download/학교 → 내장 메모리/Download/학교 */
    private fun shortPath(f: File): String {
        val root = roots.firstOrNull { f.path == it.file.path || f.path.startsWith(it.file.path + "/") }
            ?: return f.path
        return root.name + f.path.removePrefix(root.file.path)
    }

    private fun pathLabel(d: File?): String {
        if (d == null) return "저장소"
        return shortPath(d).split('/').filter { it.isNotEmpty() }.joinToString("  ›  ")
    }

    // ================= 폴더 이동 =================

    private fun isRoot(d: File) = roots.any { it.file.path == d.path }

    private fun canGoUp(): Boolean {
        val d = dir ?: return false
        return !isRoot(d) || roots.size > 1
    }

    private fun openDir(f: File) {
        scrollStates[dir?.path ?: ""] = list.layoutManager?.onSaveInstanceState()
        setDir(f)
        pendingScroll = null
        adapter.submit(emptyList())
        refresh()
    }

    private fun goUp() {
        val d = dir ?: return
        if (!canGoUp()) return
        val parent = if (isRoot(d)) null else d.parentFile
        setDir(parent)
        pendingScroll = scrollStates.remove(parent?.path ?: "")
        adapter.submit(emptyList())
        refresh()
    }

    private fun setDir(f: File?) {
        dir = f
        prefs.edit().putString("dir", f?.path).apply()
    }

    private fun updateBack() {
        backCallback.isEnabled = searchItem.isActionViewExpanded ||
            (tab == Tab.FOLDER && !permCard.isVisible && canGoUp())
    }

    // ================= 권한 =================

    private fun askAccess() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                startActivity(
                    Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName"))
                )
            } catch (e: ActivityNotFoundException) {
                startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else {
            requestRead.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
    }

    // ================= 열기 =================

    private fun openFile(f: File) {
        val uri = Uri.fromFile(f)
        Recents.add(this, uri.toString(), f.name)
        openViewer(uri)
    }

    private fun onPicked(uri: Uri) {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching { contentResolver.takePersistableUriPermission(uri, flags) }
            .onFailure { runCatching { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        Recents.add(this, uri.toString(), FileUtil.displayName(this, uri))
        openViewer(uri)
    }

    /** 새 노트: 바탕·방향을 골라 빈 쪽 문서를 만들어 연다. 처음 저장할 때 저장 위치를 고른다 */
    private fun newNote() {
        val view = layoutInflater.inflate(R.layout.dialog_new_note, null)
        val paperGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.paperGroup)
        val orientGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.orientGroup)
        val paperIds = mapOf(Paper.PLAIN to R.id.paperPlain, Paper.GRID to R.id.paperGrid, Paper.LINED to R.id.paperLined)
        val lastPaper = prefs.getString("notePaper", null)?.let { n -> Paper.entries.firstOrNull { it.name == n } } ?: Paper.GRID
        paperGroup.check(paperIds.getValue(lastPaper))
        orientGroup.check(if (prefs.getBoolean("notePortrait", false)) R.id.orientPortrait else R.id.orientLandscape)
        MaterialAlertDialogBuilder(this)
            .setTitle("새 노트")
            .setView(view)
            .setPositiveButton("만들기") { _, _ ->
                val paper = paperIds.entries.first { it.value == paperGroup.checkedButtonId }.key
                val portrait = orientGroup.checkedButtonId == R.id.orientPortrait
                prefs.edit().putString("notePaper", paper.name).putBoolean("notePortrait", portrait).apply()
                createNote(paper, portrait)
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun createNote(paper: Paper, portrait: Boolean) {
        lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    // 탭 이름이 '새 노트.pdf'로 보이도록 폴더를 따로 만든다. 하루 지난 폴더는 정리
                    val notes = File(cacheDir, "notes")
                    val limit = System.currentTimeMillis() - 24L * 3600 * 1000
                    notes.listFiles()?.forEach { if (it.lastModified() < limit) it.deleteRecursively() }
                    val dir = File(notes, "${System.currentTimeMillis()}").apply { mkdirs() }
                    val f = File(dir, "새 노트.pdf")
                    val (w, h) = if (portrait) BlankPages.A4_SHORT to BlankPages.A4_LONG
                    else BlankPages.A4_LONG to BlankPages.A4_SHORT
                    BlankPages.create(f, paper, w, h)
                    f
                }
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setMessage("노트를 만들지 못했습니다.\n${e.message ?: e.javaClass.simpleName}")
                    .setPositiveButton("확인", null)
                    .show()
                return@launch
            }
            openViewer(Uri.fromFile(file), newNote = true)
        }
    }

    private fun openViewer(uri: Uri, newNote: Boolean = false) {
        val writable = if (newNote) false else if (uri.scheme == "file") File(uri.path ?: "").canWrite()
        else contentResolver.persistedUriPermissions.any { it.uri == uri && it.isWritePermission }
        // 뷰어가 이미 떠 있으면 그 뷰어에 새 탭으로 연다
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .setData(uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(ViewerActivity.EXTRA_WRITABLE, writable)
                .putExtra(ViewerActivity.EXTRA_FROM_BROWSER, true)
                .putExtra(ViewerActivity.EXTRA_NEW_NOTE, newNote)
        )
        setPickMode(false)
    }

    /** 열린 문서 탭이 있는 뷰어로 돌아간다 */
    private fun backToViewer() {
        startActivity(Intent(this, ViewerActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT))
        setPickMode(false)
    }

    private fun setPickMode(on: Boolean) {
        pickMode = on
        toolbar.subtitle = if (on) "새 탭에 열 문서를 고르세요" else null
    }

    private fun updateOpenTabs() {
        val n = ViewerActivity.openTabs
        toolbar.menu.findItem(R.id.action_open_tabs)?.apply {
            isVisible = n > 0 && !pickMode
            title = "열린 문서 $n"
        }
    }

    private fun confirmRemoveRecent(item: Recents.Item) {
        MaterialAlertDialogBuilder(this)
            .setTitle(item.name)
            .setMessage("최근 목록에서 지울까요? (파일은 지워지지 않습니다)")
            .setPositiveButton("목록에서 지우기") { _, _ ->
                Recents.remove(this, item.uri)
                val uri = Uri.parse(item.uri)
                if (uri.scheme == "content") runCatching {
                    contentResolver.releasePersistableUriPermission(
                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ================= 목록 어댑터 =================

    private inner class RowAdapter : RecyclerView.Adapter<RowHolder>() {
        private var items: List<Row> = emptyList()

        @Suppress("NotifyDataSetChanged")
        fun submit(list: List<Row>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(layoutInflater.inflate(R.layout.item_recent, parent, false))
        override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(items[position])
    }

    private class RowHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val icon: ImageView = v.findViewById(R.id.icon)
        private val name: TextView = v.findViewById(R.id.name)
        private val sub: TextView = v.findViewById(R.id.date)
        private val badge: TextView = v.findViewById(R.id.badge)
        private val star: ImageView = v.findViewById(R.id.star)

        fun bind(r: Row) {
            name.text = r.title
            sub.text = r.sub
            sub.isVisible = r.sub.isNotEmpty()
            val color = when (r.kind) {
                Kind.FOLDER -> FOLDER_COLOR
                Kind.DRIVE -> HWP_COLOR
                Kind.PDF -> PDF_COLOR
                Kind.HWP -> HWP_COLOR
            }
            icon.setImageResource(if (r.kind == Kind.PDF || r.kind == Kind.HWP) R.drawable.ic_doc else R.drawable.ic_folder)
            icon.imageTintList = ColorStateList.valueOf(color)
            val ext = r.title.substringAfterLast('.', "").uppercase()
            badge.isVisible = (r.kind == Kind.PDF || r.kind == Kind.HWP) && ext.isNotEmpty() && ext.length <= 4
            badge.text = ext
            badge.backgroundTintList = ColorStateList.valueOf(color)
            star.isVisible = r.star != null
            if (r.star != null) {
                star.setImageResource(if (r.star) R.drawable.ic_star else R.drawable.ic_star_border)
                star.contentDescription = if (r.star) "즐겨찾기 해제" else "즐겨찾기"
                star.setOnClickListener { r.onStar?.invoke() }
            }
            itemView.setOnClickListener { r.onClick() }
            val long = r.onLongClick
            if (long != null) itemView.setOnLongClickListener { long(); true }
            else {
                itemView.setOnLongClickListener(null)
                itemView.isLongClickable = false
            }
        }
    }

    companion object {
        /** 뷰어의 ＋ 버튼으로 띄웠을 때: 고른 문서를 새 탭으로 열고, 뒤로 가면 뷰어로 돌아간다 */
        const val EXTRA_PICK = "pick"
        private val PDF_COLOR = Color.parseColor("#D93025")
        private val HWP_COLOR = Color.parseColor("#2F6FC4")
        private val FOLDER_COLOR = Color.parseColor("#E8A317")
    }
}

fun applyInsets(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        insets
    }
}
