package com.dsviewer.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Parcelable
import android.provider.Settings
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.PopupMenu
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.core.view.MenuCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.util.Date
import java.util.Locale

/** 첫 화면: 최근 파일 · 기기 전체 문서 · 폴더 탐색 */
class MainActivity : AppCompatActivity() {

    private enum class Tab(val label: String, val icon: Int) {
        RECENT("최근 파일", R.drawable.ic_history),
        FAVORITE("즐겨찾기", R.drawable.ic_star),
        ALL("모든 문서", R.drawable.ic_doc),
        FOLDER("폴더", R.drawable.ic_folder),
    }
    private enum class Kind { FOLDER, DRIVE, DOC }

    /** 정렬 기준. [descFirst]면 처음 고를 때 큰 것(최근 것)부터 */
    private enum class SortKey(val label: String, val descFirst: Boolean) {
        OPENED("연 날짜", true), MODIFIED("수정한 날짜", true), NAME("이름", false), SIZE("크기", true), TYPE("종류", false);

        /** (오름차순, 내림차순) 이름 */
        val dirLabels get() = when (this) {
            OPENED, MODIFIED -> "오래된 것 먼저" to "최근 것 먼저"
            NAME -> "가나다순" to "가나다 거꾸로"
            SIZE -> "작은 것 먼저" to "큰 것 먼저"
            TYPE -> "PDF 먼저" to "그림 먼저"
        }
    }

    private class Row(
        val title: String,
        val sub: String,
        val kind: Kind,
        val onClick: () -> Unit,
        val onLongClick: (() -> Unit)? = null,
        /** null이면 별표를 숨긴다 (최근 파일·즐겨찾기 탭에서만 보임) */
        val star: Boolean? = null,
        val onStar: (() -> Unit)? = null,
        /** 문서 썸네일 */
        val thumb: Thumbs.Source? = null,
        /** 폴더 그림 (폴더·저장소 줄) */
        val folder: Drawable? = null,
        /** 큰 아이콘으로 볼 때 이름 아래에 쓸 짧은 설명 */
        val short: String = sub,
        // 정렬용
        val opened: Long = 0,
        val modified: Long = 0,
        val size: Long = 0,
        /** 문서면 그 uri (선택 모드에서 고를 수 있음) */
        val docUri: String? = null,
        /** 즐겨찾기 폴더면 그 폴더 (선택 모드에서 고를 수 있음) */
        val vfolder: FavFolder? = null,
        /** 잠근 문서: 썸네일을 가린다 */
        val locked: Boolean = false,
    ) {
        val isFolder get() = kind == Kind.FOLDER || kind == Kind.DRIVE
        /** 선택 모드에서 쓰는 열쇠 */
        val key get() = docUri ?: vfolder?.let { "vf:${it.id}" }
        val selectable get() = key != null
    }

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
    private lateinit var newFolderButton: View
    private lateinit var sortButton: MaterialButton
    private lateinit var viewButton: MaterialButton
    private lateinit var searchItem: MenuItem
    private lateinit var selectBar: View
    private lateinit var selectCount: TextView
    private lateinit var selectAll: MaterialButton
    private lateinit var actionBar: View
    private lateinit var actionRow: LinearLayout

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
    /** 즐겨찾기 탭의 현재 폴더 (null = 즐겨찾기 맨 위) */
    private var favDir: String? = null
    /** 큰 아이콘으로 보기 */
    private var grid = false
    /** 목록 줄의 글: 문서 수를 받아 만든다 */
    private var barLabel: (Int) -> String = { "" }
    /** 불러온 그림을 썸네일로 쓸 문서 */
    private var thumbTarget: String? = null
    private val collator: Collator = Collator.getInstance(Locale.KOREAN)
    /** 선택 모드 (문서를 꾹 누르면 켜짐) */
    private var selecting = false
    private val selected = LinkedHashSet<String>()
    private val actions = HashMap<String, View>()

    private val openDoc = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) onPicked(uri)
    }
    private val pickImage = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        val target = thumbTarget
        if (uri != null && target != null) setImageThumb(target, uri)
    }
    private val requestRead = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }

    private val backCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                selecting -> endSelection()
                searchItem.isActionViewExpanded -> searchItem.collapseActionView()
                else -> goUp()
            }
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
        newFolderButton = findViewById(R.id.newFolderButton)
        sortButton = findViewById(R.id.sortButton)
        viewButton = findViewById(R.id.viewButton)
        selectBar = findViewById(R.id.selectBar)
        selectCount = findViewById(R.id.selectCount)
        selectAll = findViewById(R.id.selectAll)
        actionBar = findViewById(R.id.actionBar)
        actionRow = findViewById(R.id.actionRow)
        thumbTarget = savedInstanceState?.getString("thumbTarget")
        setupSelection()

        list.adapter = adapter
        grid = prefs.getBoolean("grid", false)
        applyViewMode()
        list.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            // 레이아웃 도중에 바꾸면 무시될 수 있어 다음 차례에
            if (r - l != or - ol) list.post { (list.layoutManager as? GridLayoutManager)?.spanCount = spanCount() }
        }
        upButton.setOnClickListener { goUp() }
        newFolderButton.setOnClickListener { editFolder(null, favDir) }
        sortButton.setOnClickListener { showSortMenu() }
        viewButton.setOnClickListener {
            grid = !grid
            prefs.edit().putBoolean("grid", grid).apply()
            applyViewMode()
        }
        findViewById<View>(R.id.permButton).setOnClickListener { askAccess() }
        setupMenu()

        favDir = prefs.getString("favDir", null)
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
                endSelection()
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

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString("thumbTarget", thumbTarget)
    }

    override fun onResume() {
        super.onResume()
        // 권한 설정에서 돌아왔거나, 문서를 저장하고 돌아왔을 수 있으므로 매번 새로 읽는다
        refresh()
        updateOpenTabs()
    }

    /** '종류' 정렬 순서: PDF · 한글 · 워드 · 파워포인트 · 글 · 그림 */
    private fun typeRank(t: DocType) = when {
        t == DocType.PDF -> 0
        t.isHangul -> 1
        t.isWord -> 2
        t.isPowerPoint -> 3
        t == DocType.TXT -> 4
        t == DocType.IMAGE -> 5
        else -> 6
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
                R.id.action_lock_password -> { Locks.changePassword(this); true }
                else -> false
            }
        }
    }

    // ================= 목록 =================

    private fun refresh() {
        loadJob?.cancel()
        val needAccess = (tab == Tab.ALL || tab == Tab.FOLDER) && !DocFiles.hasAccess(this)
        permCard.isVisible = needAccess
        pathBar.isVisible = !needAccess && !selecting
        toolbar.menu.findItem(R.id.action_lock_password)?.isVisible = Locks.hasPassword(this)
        upButton.isVisible = tab == Tab.FOLDER || tab == Tab.FAVORITE
        newFolderButton.isVisible = tab == Tab.FAVORITE
        updateSortButton()
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
                barLabel = { n -> if (n == 0) "최근 연 문서" else "최근 연 문서 ${n}개" }
                show(recentRows(), "최근에 연 파일이 없습니다.\n'모든 문서'나 '폴더' 탭에서 문서를 골라 보세요.")
            }
            Tab.FAVORITE -> {
                progress.isVisible = false
                val rows = favoriteRows()
                val path = FavFolders.path(FavFolders.list(this), favDir)
                barLabel = { listOf("즐겨찾기").plus(path.map { it.name }).joinToString("  ›  ") }
                upButton.isEnabled = favDir != null
                upButton.alpha = if (favDir != null) 1f else 0.3f
                pathScroll.post { pathScroll.fullScroll(View.FOCUS_RIGHT) }
                show(
                    rows,
                    if (favDir == null) "즐겨찾기한 문서가 없습니다.\n'최근 파일'에서 문서 옆의 ☆를 누르면 여기에 모입니다.\n오른쪽 위 폴더 단추로 즐겨찾기 폴더를 만들 수 있습니다."
                    else "이 폴더는 비어 있습니다.\n즐겨찾기 문서를 길게 눌러 '폴더로 옮기기'를 고르세요."
                )
            }
            Tab.ALL -> {
                barLabel = { n -> if (n == 0) "기기의 문서" else "기기의 문서 ${n}개" }
                val key = sortKey()
                load("기기에서 문서(PDF·한글·워드·파워포인트·글)를 찾지 못했습니다.") {
                    val rec = Recents.list(this).associateBy { it.uri }
                    val locked = Locks.locked(this)
                    DocFiles.scanAll(this).map { fileRow(it, showPath = true, rec, key, locked) }
                }
            }
            Tab.FOLDER -> {
                if (dir?.let { d -> roots.none { d.path.startsWith(it.file.path) } } == true) dir = null
                if (dir == null && roots.size == 1) dir = roots[0].file
                val d = dir
                val label = pathLabel(d)
                barLabel = { label }
                pathScroll.post { pathScroll.fullScroll(View.FOCUS_RIGHT) }
                upButton.isEnabled = canGoUp()
                upButton.alpha = if (canGoUp()) 1f else 0.3f
                val key = sortKey()
                load("이 폴더에는 문서가 없습니다.") {
                    val rec = Recents.list(this).associateBy { it.uri }
                    val locked = Locks.locked(this)
                    if (d == null) roots.map { r ->
                        Row(r.name, r.file.path, Kind.DRIVE, { openDir(r.file) }, folder = FolderDrawable(DocColors.HWP, TapePattern.SOLID), short = "")
                    }
                    else DocFiles.list(d).map { fileRow(it, showPath = false, rec, key, locked) }
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
        }
    }

    private fun show(rows: List<Row>, emptyMsg: String) {
        this.rows = sortRows(rows)
        if (selecting) selected.retainAll(this.rows.mapNotNull { it.key }.toSet())
        this.emptyMsg = emptyMsg
        pathText.text = barLabel(rows.count { !it.isFolder })
        applyFilter()
        onSelectionChanged()
        pendingScroll?.let { list.layoutManager?.onRestoreInstanceState(it) }
        pendingScroll = null
    }

    private fun applyFilter() {
        val q = query.trim()
        val shown = if (q.isEmpty()) rows else rows.filter { it.title.contains(q, ignoreCase = true) }
        adapter.submit(shown)
        emptyText.text = if (q.isEmpty()) emptyMsg else "'$q' 이름의 문서가 없습니다."
        emptyText.isVisible = shown.isEmpty() && !progress.isVisible && !permCard.isVisible
    }

    /** 최근에 연 문서 */
    private fun recentRows(): List<Row> {
        val key = sortKey()
        val locked = Locks.locked(this)
        return Recents.list(this).mapNotNull { itemRow(it, key, locked) }
    }

    /** 즐겨찾기: 지금 폴더 안의 폴더와 문서 (없어진 폴더에 있던 문서는 맨 위로) */
    private fun favoriteRows(): List<Row> {
        val folders = FavFolders.list(this)
        val ids = folders.map { it.id }.toSet()
        if (favDir != null && favDir !in ids) setFavDir(null)
        val items = Recents.list(this).filter { it.star }
        fun where(i: Recents.Item) = i.folder?.takeIf { it in ids }
        val key = sortKey()
        val locked = Locks.locked(this)
        val out = ArrayList<Row>()
        for (f in folders.filter { it.parent == favDir }) {
            val docs = items.count { where(it) == f.id }
            val subs = folders.count { it.parent == f.id }
            val sub = listOfNotNull(
                if (subs > 0) "폴더 ${subs}개" else null,
                if (docs > 0 || subs == 0) "문서 ${docs}개" else null,
            ).joinToString(" · ")
            out += Row(
                f.name, sub, Kind.FOLDER, { openFavDir(f.id) },
                folder = FolderDrawable(f.color, f.pattern), opened = f.time, modified = f.time, vfolder = f,
            )
        }
        items.filter { where(it) == favDir }.forEach { i -> itemRow(i, key, locked)?.let { out += it } }
        return out
    }

    /** 최근 목록의 문서 한 줄. 파일이 없어졌으면 목록에서 빼고 null */
    private fun itemRow(item: Recents.Item, key: SortKey, locked: Set<String>): Row? {
        val uri = Uri.parse(item.uri)
        val file = if (uri.scheme == "file") File(uri.path ?: "") else null
        if (file != null && !file.exists()) {
            Recents.remove(this, item.uri)
            return null
        }
        val modified = file?.lastModified() ?: 0L
        val shownTime = if (key == SortKey.MODIFIED && file != null) modified else item.time
        val sub = listOfNotNull(date(shownTime), file?.parentFile?.let(::shortPath)).joinToString(" · ")
        return Row(
            item.name, sub, Kind.DOC, {
                unlockThen(item.uri) {
                    Recents.add(this, item.uri, item.name)
                    openViewer(uri)
                }
            },
            star = item.star, onStar = { toggleStar(item) }, docUri = item.uri, locked = item.uri in locked,
            thumb = Thumbs.Source(uri, item.name, item.thumbPage, item.thumbFile),
            short = date(shownTime), opened = item.time, modified = modified, size = file?.length() ?: 0L,
        )
    }

    private fun fileRow(e: Entry, showPath: Boolean, rec: Map<String, Recents.Item>, key: SortKey, locked: Set<String>): Row {
        if (e.isDir) return Row(
            e.name, "", Kind.FOLDER, { openDir(e.file) },
            folder = FolderDrawable(FOLDER_COLOR, TapePattern.SOLID), modified = e.time,
        )
        val info = "${date(e.time)} · ${Formatter.formatShortFileSize(this, e.size)}"
        val sub = if (showPath) "${e.file.parentFile?.let(::shortPath) ?: ""} · $info" else info
        val uri = Uri.fromFile(e.file)
        val item = rec[uri.toString()]
        // 즐겨찾기한 문서는 별 표시만 (누르는 별은 최근 파일·즐겨찾기 탭에)
        return Row(
            e.name, sub, Kind.DOC, { unlockThen(uri.toString()) { openFile(e.file) } },
            star = if (item?.star == true) true else null,
            docUri = uri.toString(), locked = uri.toString() in locked,
            thumb = Thumbs.Source(uri, e.name, item?.thumbPage ?: 0, item?.thumbFile),
            short = date(e.time), opened = item?.time ?: 0L, modified = e.time, size = e.size,
        )
    }


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

    // ================= 정렬 · 보기 =================

    private fun sortKeys(t: Tab) =
        if (t == Tab.RECENT || t == Tab.FAVORITE) SortKey.entries.toList() else SortKey.entries.filter { it != SortKey.OPENED }

    private fun defaultSort(t: Tab) = when (t) {
        Tab.RECENT, Tab.FAVORITE -> SortKey.OPENED
        Tab.ALL -> SortKey.MODIFIED
        Tab.FOLDER -> SortKey.NAME
    }

    private fun sortKey(): SortKey {
        val k = prefs.getString("sort_${tab.name}", null)?.let { n -> SortKey.entries.firstOrNull { it.name == n } }
        return k?.takeIf { it in sortKeys(tab) } ?: defaultSort(tab)
    }

    private fun sortDesc(): Boolean = prefs.getBoolean("sortDesc_${tab.name}", sortKey().descFirst)

    private fun setSort(key: SortKey, desc: Boolean) {
        prefs.edit().putString("sort_${tab.name}", key.name).putBoolean("sortDesc_${tab.name}", desc).apply()
        refresh()
    }

    private fun updateSortButton() {
        val key = sortKey()
        val desc = sortDesc()
        sortButton.text = "${key.label} ${if (desc) "↓" else "↑"}"
        sortButton.contentDescription = "정렬: ${key.label}, ${if (desc) key.dirLabels.second else key.dirLabels.first}"
    }

    private fun showSortMenu() {
        val keys = sortKeys(tab)
        val key = sortKey()
        val desc = sortDesc()
        val pm = PopupMenu(this, sortButton)
        val m = pm.menu
        keys.forEachIndexed { i, k -> m.add(1, i, i, k.label).isChecked = k == key }
        m.setGroupCheckable(1, true, true)
        val (asc, dsc) = key.dirLabels
        // 이 기준에서 먼저 고르는 쪽을 위에
        val dirs = if (key.descFirst) listOf(true to dsc, false to asc) else listOf(false to asc, true to dsc)
        dirs.forEachIndexed { i, (d, label) -> m.add(2, 100 + i, 100 + i, label).isChecked = d == desc }
        m.setGroupCheckable(2, true, true)
        MenuCompat.setGroupDividerEnabled(m, true)
        pm.setOnMenuItemClickListener { item ->
            if (item.groupId == 1) {
                val k = keys[item.itemId]
                setSort(k, if (k == key) desc else k.descFirst)
            } else setSort(key, dirs[item.itemId - 100].first)
            true
        }
        pm.show()
    }

    /** 폴더는 늘 먼저. 폴더끼리는 이름이나 날짜로만 (크기·종류가 없으므로 그때는 이름순) */
    private fun sortRows(rows: List<Row>): List<Row> {
        if (rows.any { it.kind == Kind.DRIVE }) return rows
        val key = sortKey()
        val desc = sortDesc()
        val byName = Comparator<Row> { a, b -> collator.compare(a.title, b.title) }
        fun primary(k: SortKey): Comparator<Row> = when (k) {
            SortKey.NAME -> byName
            SortKey.OPENED -> compareBy { it.opened }
            SortKey.MODIFIED -> compareBy { it.modified }
            SortKey.SIZE -> compareBy { it.size }
            SortKey.TYPE -> compareBy { typeRank(DocType.ofName(it.title)) }
        }
        fun ordered(k: SortKey) = (if (desc) primary(k).reversed() else primary(k)).then(byName)
        val (folders, docs) = rows.partition { it.isFolder }
        val folderKey = if (key == SortKey.SIZE || key == SortKey.TYPE) null else key
        val sortedFolders = if (folderKey == null) folders.sortedWith(byName) else folders.sortedWith(ordered(folderKey))
        return sortedFolders + docs.sortedWith(ordered(key))
    }

    private fun spanCount(): Int {
        if (!grid) {
            // 목록으로 볼 때 가로 화면이면 두 칸으로 나눠 오른쪽도 쓴다
            val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            return if (landscape) 2 else 1
        }
        val w = list.width - list.paddingLeft - list.paddingRight
        if (w <= 0) return 3
        return (w / (140 * resources.displayMetrics.density)).toInt().coerceAtLeast(2)
    }

    private fun applyViewMode() {
        val d = resources.displayMetrics.density
        val state = list.layoutManager?.onSaveInstanceState()
        if (grid) list.setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (12 * d).toInt())
        else list.setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        list.layoutManager = GridLayoutManager(this, spanCount())
        state?.let { list.layoutManager?.onRestoreInstanceState(it) }
        viewButton.setIconResource(if (grid) R.drawable.ic_view_list else R.drawable.ic_grid_view)
        val label = if (grid) "목록으로 보기" else "큰 아이콘으로 보기"
        viewButton.contentDescription = label
        viewButton.tooltipText = label
        adapter.notifyDataSetChanged()
    }

    // ================= 폴더 이동 =================

    private fun isRoot(d: File) = roots.any { it.file.path == d.path }

    private fun canGoUp(): Boolean {
        if (tab == Tab.FAVORITE) return favDir != null
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

    private fun openFavDir(id: String) {
        scrollStates["fav:${favDir ?: ""}"] = list.layoutManager?.onSaveInstanceState()
        setFavDir(id)
        pendingScroll = null
        adapter.submit(emptyList())
        refresh()
        list.scrollToPosition(0)
    }

    private fun goUp() {
        if (tab == Tab.FAVORITE) {
            val cur = favDir ?: return
            val parent = FavFolders.get(this, cur)?.parent
            setFavDir(parent)
            pendingScroll = scrollStates.remove("fav:${parent ?: ""}")
            adapter.submit(emptyList())
            refresh()
            return
        }
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

    private fun setFavDir(id: String?) {
        favDir = id
        prefs.edit().putString("favDir", id).apply()
    }

    private fun updateBack() {
        backCallback.isEnabled = selecting || searchItem.isActionViewExpanded ||
            (tab == Tab.FOLDER && !permCard.isVisible && canGoUp()) ||
            (tab == Tab.FAVORITE && favDir != null)
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
                    val (w, h) = if (portrait) PdfPages.A4_SHORT to PdfPages.A4_LONG
                    else PdfPages.A4_LONG to PdfPages.A4_SHORT
                    PdfPages.create(f, paper, w, h)
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

    // ================= 즐겨찾기 · 썸네일 =================

    /** 별 누르기. 폴더에 넣었거나 썸네일을 바꾼 즐겨찾기를 뺄 때는 그것도 없어지므로 묻는다 */
    private fun toggleStar(item: Recents.Item) {
        val custom = item.folder != null || item.thumbPage != 0 || item.thumbFile != null
        if (!item.star || !custom) {
            Recents.setStar(this, item.uri, !item.star)
            refresh()
            return
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("즐겨찾기에서 뺄까요?")
            .setMessage("'${item.name}'의 즐겨찾기 폴더와 바꾼 썸네일도 없어집니다. (파일은 그대로 남습니다)")
            .setPositiveButton("빼기") { _, _ ->
                Recents.setStar(this, item.uri, false)
                refresh()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** 썸네일 바꾸기: 첫 쪽 · 쪽 고르기 · 그림 불러오기 · 손으로 그리기 */
    private fun thumbMenu(item: Recents.Item) {
        val uri = Uri.parse(item.uri)
        val actions = ArrayList<Pair<String, () -> Unit>>()
        if (item.thumbPage != 0 || item.thumbFile != null) actions += "첫 쪽으로 되돌리기" to {
            Recents.setThumbPage(this, item.uri, 0)
            refresh()
        }
        actions += "쪽 고르기…" to {
            ThumbPagePicker(this, uri, item.name, if (item.thumbFile == null) item.thumbPage else -1) { page ->
                Recents.setThumbPage(this, item.uri, page)
                refresh()
            }.show()
        }
        actions += "사진·그림 불러오기…" to {
            thumbTarget = item.uri
            pickImage.launch("image/*")
        }
        actions += "손으로 그리기…" to {
            lifecycleScope.launch {
                val bg = Thumbs.load(this@MainActivity, Thumbs.Source(uri, item.name, item.thumbPage, item.thumbFile))
                ThumbSketch(this@MainActivity, bg) { bmp ->
                    lifecycleScope.launch {
                        val name = withContext(Dispatchers.IO) { Thumbs.saveCustom(this@MainActivity, bmp) }
                        Recents.setThumbFile(this@MainActivity, item.uri, name)
                        refresh()
                    }
                }.show()
            }
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("썸네일 바꾸기")
            .setItems(actions.map { it.first }.toTypedArray()) { _, i -> actions[i].second() }
            .show()
    }

    private fun setImageThumb(target: String, image: Uri) {
        lifecycleScope.launch {
            val name = withContext(Dispatchers.IO) {
                runCatching { Thumbs.decodeImage(this@MainActivity, image)?.let { Thumbs.saveCustom(this@MainActivity, it) } }.getOrNull()
            }
            if (name == null) {
                MaterialAlertDialogBuilder(this@MainActivity)
                    .setMessage("그림을 읽지 못했습니다.")
                    .setPositiveButton("확인", null)
                    .show()
                return@launch
            }
            Recents.setThumbFile(this@MainActivity, target, name)
            thumbTarget = null
            refresh()
        }
    }

    /** 즐겨찾기 폴더 만들기([f] == null) 또는 꾸미기: 이름 · 색 · 무늬 */
    private fun editFolder(f: FavFolder?, parent: String?, onCreated: (FavFolder) -> Unit = {}) {
        val v = layoutInflater.inflate(R.layout.dialog_folder, null)
        val preview = v.findViewById<ThumbView>(R.id.folderPreview)
        val nameEdit = v.findViewById<EditText>(R.id.folderName)
        val colorGrid = v.findViewById<GridLayout>(R.id.folderColors)
        val patternRow = v.findViewById<LinearLayout>(R.id.folderPatterns)
        val d = resources.displayMetrics.density
        var color = f?.color ?: FavFolders.COLORS[0]
        var pattern = f?.pattern ?: TapePattern.SOLID
        val accent = com.google.android.material.color.MaterialColors.getColor(v, androidx.appcompat.R.attr.colorPrimary)
        val outline = com.google.android.material.color.MaterialColors.getColor(v, com.google.android.material.R.attr.colorOutlineVariant)
        val swatches = ArrayList<View>()
        val patterns = ArrayList<ThumbView>()
        fun update() {
            preview.folder = FolderDrawable(color, pattern)
            FavFolders.COLORS.forEachIndexed { i, c ->
                (swatches[i].background as GradientDrawable).setStroke((if (c == color) 3 * d else 0f).toInt(), accent)
            }
            TapePattern.entries.forEachIndexed { i, p ->
                patterns[i].folder = FolderDrawable(color, p)
                patterns[i].background = GradientDrawable().apply {
                    cornerRadius = 8 * d
                    setStroke((if (p == pattern) 2 * d else 1 * d).toInt(), if (p == pattern) accent else outline)
                }
            }
        }
        for (c in FavFolders.COLORS) {
            val s = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(c)
                }
                contentDescription = "폴더 색"
                setOnClickListener { color = c; update() }
            }
            swatches += s
            colorGrid.addView(s, GridLayout.LayoutParams().apply {
                width = (36 * d).toInt(); height = (36 * d).toInt()
                setMargins((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
            })
        }
        for (p in TapePattern.entries) {
            val t = ThumbView(this).apply {
                val pad = (6 * d).toInt()
                setPadding(pad, pad, pad, pad)
                contentDescription = "무늬: ${p.label}"
                setOnClickListener { pattern = p; update() }
            }
            patterns += t
            patternRow.addView(t, LinearLayout.LayoutParams((52 * d).toInt(), (46 * d).toInt()).apply { marginEnd = (8 * d).toInt() })
        }
        update()
        nameEdit.setText(f?.name ?: "새 폴더")
        nameEdit.selectAll()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(if (f == null) "새 폴더" else "폴더 꾸미기")
            .setView(v)
            .setPositiveButton(if (f == null) "만들기" else "바꾸기") { _, _ ->
                val name = nameEdit.text.toString().trim().ifEmpty { "새 폴더" }
                if (f == null) onCreated(FavFolders.add(this, name, color, pattern, parent))
                else FavFolders.get(this, f.id)?.let { FavFolders.update(this, it.copy(name = name, color = color, pattern = pattern)) }
                refresh()
            }
            .setNegativeButton("취소", null)
            .create()
        nameEdit.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) {
                dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick()
                true
            } else false
        }
        dialog.show()
    }

    // ================= 선택 모드 =================

    private fun setupSelection() {
        findViewById<View>(R.id.selectClose).setOnClickListener { endSelection() }
        selectAll.setOnClickListener {
            val keys = adapter.items.mapNotNull { it.key }
            if (selected.containsAll(keys)) selected.clear() else selected.addAll(keys)
            onSelectionChanged()
        }
        addAction("move", R.drawable.ic_move, "이동") { moveSelected() }
        addAction("rename", R.drawable.ic_rename, "이름 바꾸기") { renameSelected() }
        addAction("lock", R.drawable.ic_lock, "잠금") { lockSelected() }
        addAction("star", R.drawable.ic_star_border, "즐겨찾기") { starSelected() }
        addAction("thumb", R.drawable.ic_image, "썸네일") { selectedRows().singleOrNull()?.docUri?.let { u -> Recents.get(this, u)?.let(::thumbMenu) } }
        addAction("share", R.drawable.ic_share, "공유") { shareSelected() }
        addAction("copy", R.drawable.ic_copy, "사본 만들기") { copySelected() }
        addAction("delete", R.drawable.ic_delete, "삭제") { deleteSelected() }
        list.addOnItemTouchListener(dragSelect)
    }

    private fun addAction(id: String, icon: Int, label: String, onClick: () -> Unit) {
        val d = resources.displayMetrics.density
        val onSurface = com.google.android.material.color.MaterialColors.getColor(actionRow, com.google.android.material.R.attr.colorOnSurface)
        val v = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = android.view.Gravity.CENTER
            setPadding((10 * d).toInt(), (6 * d).toInt(), (10 * d).toInt(), (6 * d).toInt())
            minimumWidth = (76 * d).toInt()
            val tv = android.util.TypedValue()
            theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, tv, true)
            background = getDrawable(tv.resourceId)
            setOnClickListener { onClick() }
        }
        val img = ImageView(this).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(onSurface)
        }
        val text = TextView(this).apply {
            this.text = label
            textSize = 12f
            setTextColor(onSurface)
            setPadding(0, (3 * d).toInt(), 0, 0)
        }
        v.addView(img, LinearLayout.LayoutParams((24 * d).toInt(), (24 * d).toInt()))
        // 글자 칸은 글자 너비만큼, 아이콘 아래 가운데에
        text.gravity = android.view.Gravity.CENTER
        v.addView(text, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        v.tag = Triple(img, text, onSurface)
        actionRow.addView(v)
        actions[id] = v
    }

    private fun setAction(id: String, enabled: Boolean, label: String? = null, icon: Int? = null, visible: Boolean = true) {
        val v = actions[id] ?: return
        v.isVisible = visible
        v.isEnabled = enabled
        v.alpha = if (enabled) 1f else 0.35f
        val t = v.tag as Triple<*, *, *>
        val img = t.first as ImageView
        val text = t.second as TextView
        val tint = t.third as Int
        label?.let { text.text = it }
        icon?.let {
            img.setImageResource(it)
            // 채운 별은 제 색(노랑) 그대로
            img.imageTintList = if (it == R.drawable.ic_star) null else ColorStateList.valueOf(tint)
        }
    }

    private fun selectedRows() = rows.filter { it.key in selected }

    private fun startSelection(r: Row) {
        val key = r.key ?: return
        if (searchItem.isActionViewExpanded) searchItem.actionView?.clearFocus()
        selected += key
        if (!selecting) {
            selecting = true
            pathBar.isVisible = false
            selectBar.isVisible = true
            actionBar.isVisible = true
            adapter.notifyDataSetChanged()
        }
        onSelectionChanged()
        updateBack()
    }

    private fun endSelection() {
        if (!selecting) return
        selecting = false
        selected.clear()
        selectBar.isVisible = false
        actionBar.isVisible = false
        pathBar.isVisible = !permCard.isVisible
        adapter.notifyDataSetChanged()
        updateBack()
    }

    private fun onSelectionChanged() {
        if (!selecting) return
        val sel = selectedRows()
        val docs = sel.filter { it.docUri != null }
        val folders = sel.filter { it.vfolder != null }
        val onlyDocs = docs.isNotEmpty() && folders.isEmpty()
        selectCount.text = if (sel.isEmpty()) "문서를 고르세요" else "${sel.size}개 선택"
        val keys = adapter.items.mapNotNull { it.key }
        selectAll.text = if (keys.isNotEmpty() && selected.containsAll(keys)) "모두 해제" else "모두 선택"
        val filesOnly = docs.all { Uri.parse(it.docUri).scheme == "file" }
        setAction("move", sel.isNotEmpty() && (tab == Tab.FAVORITE || (filesOnly && folders.isEmpty())))
        setAction("rename", sel.size == 1)
        val allLocked = onlyDocs && docs.all { it.locked }
        setAction("lock", onlyDocs, if (allLocked) "잠금 해제" else "잠금", if (allLocked) R.drawable.ic_lock_open else R.drawable.ic_lock)
        val starred = starredUris()
        val allStar = onlyDocs && docs.all { it.docUri in starred }
        setAction("star", onlyDocs, if (allStar) "즐겨찾기 해제" else "즐겨찾기", if (allStar) R.drawable.ic_star else R.drawable.ic_star_border)
        setAction("thumb", true, visible = docs.size == 1 && folders.isEmpty() && docs[0].docUri in starred)
        setAction("share", onlyDocs)
        setAction("copy", onlyDocs)
        setAction("delete", sel.isNotEmpty())
        adapter.notifyItemRangeChanged(0, adapter.itemCount, SELECTION)
    }

    private fun starredUris() = Recents.list(this).filter { it.star }.map { it.uri }.toSet()

    /** 선택 명령을 마친 뒤: 선택을 끝내고 목록을 다시 읽고, [msg]가 있으면 잠깐 알린다 */
    private fun finishAction(msg: String?) {
        endSelection()
        refresh()
        msg?.let { android.widget.Toast.makeText(this, it, android.widget.Toast.LENGTH_SHORT).show() }
    }

    /** 잠긴 문서가 있으면 암호를 확인한 뒤 [block] */
    private fun unlockThen(rows: List<Row>, block: () -> Unit) {
        if (rows.any { it.locked }) Locks.askPassword(this, "잠긴 문서가 있습니다. 암호를 넣어 주세요", block) else block()
    }

    private fun unlockThen(uri: String, block: () -> Unit) {
        if (Locks.isLocked(this, uri)) Locks.askPassword(this, "잠긴 문서입니다", block) else block()
    }

    // ---- 이동 ----

    private fun moveSelected() {
        val sel = selectedRows()
        if (tab == Tab.FAVORITE) {
            val all = FavFolders.list(this)
            val exclude = sel.mapNotNull { it.vfolder?.id }.flatMap { FavFolders.subtree(all, it) }.toSet()
            FolderTree(this, "옮길 폴더 선택", favSource(exclude), favDir ?: "") { node ->
                val target = node.id.ifEmpty { null }
                for (r in sel) {
                    r.docUri?.let { Recents.setFolder(this, it, target) }
                    r.vfolder?.let { f -> FavFolders.get(this, f.id)?.let { FavFolders.update(this, it.copy(parent = target)) } }
                }
                finishAction("${sel.size}개를 '${node.name}'(으)로 옮겼습니다")
            }.show()
            return
        }
        val files = sel.mapNotNull { it.docUri }.map(Uri::parse).filter { it.scheme == "file" }.map { File(it.path!!) }
        if (files.isEmpty()) return
        val common = files.map { it.parent }.distinct().singleOrNull()
        FolderTree(this, "옮길 폴더 선택", FolderTree.storage(this), common) { node ->
            val dest = File(node.id)
            lifecycleScope.launch {
                val failed = withContext(Dispatchers.IO) {
                    files.count { f -> runCatching { FileOps.move(this@MainActivity, f, dest) }.isFailure }
                }
                finishAction(if (failed == 0) "${files.size}개를 '${node.name}'(으)로 옮겼습니다" else "${failed}개는 옮기지 못했습니다")
            }
        }.show()
    }

    /** 즐겨찾기 폴더 나무 ([exclude] 폴더는 빼고). 맨 위 '즐겨찾기'의 id는 "" */
    private fun favSource(exclude: Set<String>) = object : FolderTree.Source {
        override fun roots() = listOf(FolderTree.Node("", "즐겨찾기", getDrawable(R.drawable.ic_star)!!, 0))

        override fun children(n: FolderTree.Node): List<FolderTree.Node> =
            FavFolders.list(this@MainActivity).filter { (it.parent ?: "") == n.id && it.id !in exclude }
                .sortedWith { a, b -> collator.compare(a.name, b.name) }
                .map { FolderTree.Node(it.id, it.name, FolderDrawable(it.color, it.pattern), n.depth + 1) }

        override fun count(n: FolderTree.Node): Int {
            val ids = FavFolders.list(this@MainActivity).map { it.id }.toSet()
            return Recents.list(this@MainActivity).count { it.star && (it.folder?.takeIf { f -> f in ids } ?: "") == n.id }
        }

        override fun pathTo(id: String?) = listOf("") + FavFolders.path(FavFolders.list(this@MainActivity), id?.ifEmpty { null }).map { it.id }

        override fun add(parent: FolderTree.Node, done: () -> Unit) = editFolder(null, parent.id.ifEmpty { null }) { done() }
    }

    // ---- 이름 바꾸기 ----

    private fun renameSelected() {
        val r = selectedRows().singleOrNull() ?: return
        r.vfolder?.let { f ->
            FavFolders.get(this, f.id)?.let { editFolder(it, it.parent) { } }
            endSelection()
            return
        }
        val uri = Uri.parse(r.docUri ?: return)
        val base = FileUtil.baseName(r.title)
        val ext = r.title.substring(base.length)
        val edit = EditText(this).apply {
            setText(base)
            selectAll()
            isSingleLine = true
            imeOptions = EditorInfo.IME_ACTION_DONE
        }
        val d = resources.displayMetrics.density
        val box = LinearLayout(this).apply {
            setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
            addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("이름 바꾸기")
            .setView(box)
            .setPositiveButton("바꾸기") { _, _ ->
                val name = edit.text.toString().trim().replace(Regex("[\\\\/:*?\"<>|]"), "_")
                if (name.isEmpty() || name == base) return@setPositiveButton
                lifecycleScope.launch {
                    val ok = withContext(Dispatchers.IO) { runCatching { FileOps.rename(this@MainActivity, uri, name + ext) }.isSuccess }
                    finishAction(if (ok) null else "이름을 바꾸지 못했습니다")
                }
            }
            .setNegativeButton("취소", null)
            .create()
        edit.setOnEditorActionListener { _, id, _ ->
            if (id == EditorInfo.IME_ACTION_DONE) { dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).performClick(); true } else false
        }
        dialog.show()
    }

    // ---- 잠금 · 즐겨찾기 ----

    private fun lockSelected() {
        val docs = selectedRows().filter { it.docUri != null }
        val uris = docs.mapNotNull { it.docUri }
        if (docs.all { it.locked }) {
            Locks.askPassword(this, "잠금을 풀려면 암호를 넣어 주세요") {
                Locks.setLocked(this, uris, false)
                finishAction("${uris.size}개의 잠금을 풀었습니다")
            }
            return
        }
        val lock = {
            Locks.setLocked(this, uris, true)
            finishAction("${uris.size}개를 잠갔습니다")
        }
        if (Locks.hasPassword(this)) lock() else Locks.askNewPassword(this, "잠금 암호 정하기") { lock() }
    }

    private fun starSelected() {
        val docs = selectedRows().filter { it.docUri != null }
        val starred = starredUris()
        if (docs.all { it.docUri in starred }) {
            val items = docs.mapNotNull { r -> Recents.get(this, r.docUri!!) }
            val unstar = {
                items.forEach { Recents.setStar(this, it.uri, false) }
                finishAction("즐겨찾기에서 ${items.size}개를 뺐습니다")
            }
            if (items.none { it.folder != null || it.thumbPage != 0 || it.thumbFile != null }) unstar()
            else MaterialAlertDialogBuilder(this)
                .setTitle("즐겨찾기에서 뺄까요?")
                .setMessage("즐겨찾기 폴더에 넣은 자리와 바꾼 썸네일도 없어집니다. (파일은 그대로 남습니다)")
                .setPositiveButton("빼기") { _, _ -> unstar() }
                .setNegativeButton("취소", null)
                .show()
            return
        }
        for (r in docs) {
            val uri = r.docUri!!
            if (uri in starred) continue
            if (Recents.get(this, uri) == null) Recents.add(this, uri, r.title)
            Recents.setStar(this, uri, true)
        }
        finishAction("즐겨찾기에 넣었습니다")
    }

    // ---- 공유 · 사본 · 삭제 ----

    private fun shareSelected() {
        val docs = selectedRows().filter { it.docUri != null }
        unlockThen(docs) {
            val uris = try {
                docs.map { r ->
                    val u = Uri.parse(r.docUri)
                    if (u.scheme == "file") androidx.core.content.FileProvider.getUriForFile(this, "$packageName.files", File(u.path!!)) else u
                }
            } catch (e: IllegalArgumentException) {
                finishAction("이 위치의 파일은 공유할 수 없습니다")
                return@unlockThen
            }
            val types = docs.map { FileUtil.mimeOf(it.title) }.distinct()
            val type = types.singleOrNull() ?: "*/*"
            val send = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
            else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
            send.type = type
            send.clipData = android.content.ClipData.newRawUri(null, uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            endSelection()
            startActivity(Intent.createChooser(send, "공유"))
        }
    }

    private fun copySelected() {
        val docs = selectedRows().filter { it.docUri != null }
        unlockThen(docs) {
            lifecycleScope.launch {
                val made = withContext(Dispatchers.IO) {
                    docs.mapNotNull { r -> runCatching { r to FileOps.copy(this@MainActivity, Uri.parse(r.docUri), r.title) }.getOrNull() }
                }
                // 최근·즐겨찾기 탭에서는 사본도 목록에 (즐겨찾기 문서의 사본은 같은 즐겨찾기 폴더에)
                if (tab == Tab.RECENT || tab == Tab.FAVORITE) for ((r, f) in made) {
                    val src = Recents.get(this@MainActivity, r.docUri!!)
                    val u = Uri.fromFile(f).toString()
                    Recents.add(this@MainActivity, u, f.name)
                    if (src?.star == true) {
                        Recents.setStar(this@MainActivity, u, true)
                        Recents.setFolder(this@MainActivity, u, src.folder)
                    }
                }
                val failed = docs.size - made.size
                finishAction(if (failed == 0) "사본 ${made.size}개를 만들었습니다" else "${failed}개는 사본을 만들지 못했습니다")
            }
        }
    }

    private fun deleteSelected() {
        val sel = selectedRows()
        val docs = sel.filter { it.docUri != null }
        val folders = sel.mapNotNull { it.vfolder }
        val folderNote = if (folders.isNotEmpty()) "\n폴더 ${folders.size}개는 지우고, 안에 있던 문서·폴더는 한 단계 위로 옮깁니다." else ""
        fun deleteFolders() = folders.forEach { FavFolders.delete(this, it.id) }
        fun deleteFiles() = unlockThen(docs) {
            MaterialAlertDialogBuilder(this)
                .setTitle("파일 ${docs.size}개를 지울까요?")
                .setMessage("기기에서 완전히 지워지며 되돌릴 수 없습니다.$folderNote")
                .setPositiveButton("지우기") { _, _ ->
                    lifecycleScope.launch {
                        val failed = withContext(Dispatchers.IO) { docs.count { !FileOps.delete(this@MainActivity, Uri.parse(it.docUri)) } }
                        deleteFolders()
                        finishAction(if (failed == 0) "${docs.size}개를 지웠습니다" else "${failed}개는 지우지 못했습니다")
                    }
                }
                .setNegativeButton("취소", null)
                .show()
        }
        when {
            docs.isEmpty() -> MaterialAlertDialogBuilder(this)
                .setTitle("폴더 ${folders.size}개를 지울까요?")
                .setMessage("안에 있던 문서와 폴더는 즐겨찾기에 그대로 남고, 한 단계 위로 옮겨집니다.")
                .setPositiveButton("지우기") { _, _ ->
                    deleteFolders()
                    finishAction(null)
                }
                .setNegativeButton("취소", null)
                .show()
            tab == Tab.RECENT || tab == Tab.FAVORITE -> {
                val listOnly = if (tab == Tab.RECENT) "최근 목록에서만 지우기" else "즐겨찾기에서만 빼기"
                MaterialAlertDialogBuilder(this)
                    .setTitle("선택한 ${sel.size}개 지우기")
                    .setItems(arrayOf(listOnly, "파일까지 지우기 (기기에서 완전히 지움)")) { _, i ->
                        if (i == 0) {
                            for (r in docs) {
                                val uri = r.docUri!!
                                if (tab == Tab.RECENT) removeFromRecents(uri) else Recents.setStar(this, uri, false)
                            }
                            deleteFolders()
                            finishAction(null)
                        } else deleteFiles()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
            else -> deleteFiles()
        }
    }

    /** 최근 목록에서 뺀다 (파일은 그대로). 다른 위치에서 연 문서는 다시 열 권한도 돌려준다 */
    private fun removeFromRecents(uri: String) {
        Recents.remove(this, uri)
        val u = Uri.parse(uri)
        if (u.scheme == "content") runCatching {
            contentResolver.releasePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    // ---- 끌어서 여러 개 고르기 ----

    /**
     * 꾹 누른 채 손가락을 움직이면 누른 칸부터 지금 칸까지를 고른다 (목록 위아래 끝에 가면 저절로 넘어감).
     * 누르기 전에 골라 둔 것은 그대로 둔다
     */
    private val dragSelect = object : RecyclerView.OnItemTouchListener {
        private var active = false
        private var anchor = -1
        private var last = -1
        private var base: Set<String> = emptySet()
        private var x = 0f
        private var y = 0f
        private var scrolling = false
        private val edge get() = 72 * resources.displayMetrics.density

        private val scroller = object : Runnable {
            override fun run() {
                if (!active) { scrolling = false; return }
                val h = list.height
                val dy = when {
                    y < edge -> -((edge - y) / edge * 28 * resources.displayMetrics.density)
                    y > h - edge -> (y - (h - edge)) / edge * 28 * resources.displayMetrics.density
                    else -> 0f
                }
                if (dy == 0f) { scrolling = false; return }
                list.scrollBy(0, dy.toInt())
                update()
                list.postOnAnimation(this)
            }
        }

        fun begin(pos: Int) {
            if (pos < 0) return
            active = true
            anchor = pos
            last = pos
            base = selected.toSet()
        }

        private fun update() {
            val child = list.findChildViewUnder(x, y) ?: return
            val pos = list.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION || pos == last) return
            last = pos
            selected.clear()
            selected.addAll(base)
            val items = adapter.items
            for (i in minOf(anchor, pos)..maxOf(anchor, pos)) items.getOrNull(i)?.key?.let { selected += it }
            onSelectionChanged()
        }

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (!active) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> return true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> active = false
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    x = e.x
                    y = e.y
                    update()
                    if (!scrolling && (y < edge || y > list.height - edge)) {
                        scrolling = true
                        list.postOnAnimation(scroller)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> active = false
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    }

    // ================= 목록 어댑터 =================

    private inner class RowAdapter : RecyclerView.Adapter<RowHolder>() {
        var items: List<Row> = emptyList()
            private set

        @Suppress("NotifyDataSetChanged")
        fun submit(list: List<Row>) {
            items = list
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (grid) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            RowHolder(layoutInflater.inflate(if (viewType == 1) R.layout.item_grid else R.layout.item_recent, parent, false), viewType == 1)
        override fun onBindViewHolder(holder: RowHolder, position: Int) = holder.bind(items[position])
        override fun onBindViewHolder(holder: RowHolder, position: Int, payloads: MutableList<Any>) {
            // 선택만 바뀌었으면 체크 표시만 (썸네일을 다시 읽지 않게)
            if (payloads.isNotEmpty() && payloads.all { it === SELECTION }) holder.bindSelection(items[position])
            else holder.bind(items[position])
        }
        override fun onViewRecycled(holder: RowHolder) = holder.unbind()
    }

    private inner class RowHolder(v: View, private val isGrid: Boolean) : RecyclerView.ViewHolder(v) {
        private val thumb: ThumbView = v.findViewById(R.id.thumb)
        private val name: TextView = v.findViewById(R.id.name)
        private val sub: TextView = v.findViewById(R.id.date)
        private val badge: TextView = v.findViewById(R.id.badge)
        private val star: ImageView = v.findViewById(R.id.star)
        private val check: ImageView = v.findViewById(R.id.check)
        private var job: Job? = null
        private var source: Thumbs.Source? = null

        init {
            if (isGrid) thumb.ratio = 1.25f
        }

        fun bind(r: Row) {
            name.text = r.title
            val s = if (isGrid) r.short else r.sub
            sub.text = s
            sub.isVisible = s.isNotEmpty()
            val color = DocColors.of(r.title)
            val ext = r.title.substringAfterLast('.', "").uppercase()
            badge.isVisible = !r.isFolder && ext.isNotEmpty() && ext.length <= 4
            badge.text = ext
            badge.backgroundTintList = ColorStateList.valueOf(color)
            star.isVisible = r.star != null
            if (r.star != null) {
                star.setImageResource(if (r.star) R.drawable.ic_star else R.drawable.ic_star_border)
                star.contentDescription = if (r.star) "즐겨찾기 해제" else "즐겨찾기"
            }
            itemView.setOnClickListener {
                val key = r.key
                if (selecting) {
                    if (key == null) return@setOnClickListener
                    if (!selected.remove(key)) selected += key
                    onSelectionChanged()
                } else r.onClick()
            }
            if (r.selectable) itemView.setOnLongClickListener {
                // 목록을 새로 그리기 전에 자리를 먼저 (새로 그리는 동안은 자리가 -1)
                val pos = bindingAdapterPosition
                if (pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true
                if (selecting) {
                    r.key?.let { selected += it }
                    onSelectionChanged()
                } else startSelection(r)
                dragSelect.begin(pos)
                true
            } else {
                itemView.setOnLongClickListener(null)
                itemView.isLongClickable = false
            }
            bindSelection(r)
            bindThumb(r, color)
        }

        fun bindSelection(r: Row) {
            val on = r.key != null && r.key in selected
            check.isVisible = selecting && r.selectable
            check.setImageResource(if (on) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
            itemView.isActivated = selecting && on
            // 선택 중에는 별을 눌러도 바뀌지 않게
            val onStar = r.onStar
            if (onStar != null && !selecting) star.setOnClickListener { onStar() }
            else {
                star.setOnClickListener(null)
                star.isClickable = false
            }
        }

        private fun bindThumb(r: Row, color: Int) {
            job?.cancel()
            val src = r.thumb
            source = src
            thumb.folder = r.folder
            if (src == null) {
                thumb.bitmap = null
                return
            }
            if (r.locked) {
                // 잠근 문서는 내용을 보이지 않는다
                thumb.bitmap = null
                thumb.placeholder = getDrawable(R.drawable.ic_lock)?.mutate()?.apply { setTint(Color.GRAY) }
                return
            }
            thumb.placeholder = getDrawable(R.drawable.ic_doc)?.mutate()?.apply { setTint(color) }
            val cached = Thumbs.peek(src)
            thumb.bitmap = cached
            job = lifecycleScope.launch {
                val b = Thumbs.load(this@MainActivity, src)
                if (source == src && b != null) thumb.bitmap = b
            }
        }

        fun unbind() {
            job?.cancel()
            job = null
            source = null
        }
    }

    companion object {
        /** 뷰어의 ＋ 버튼으로 띄웠을 때: 고른 문서를 새 탭으로 열고, 뒤로 가면 뷰어로 돌아간다 */
        const val EXTRA_PICK = "pick"
        private val FOLDER_COLOR = Color.parseColor("#E8A317")
        /** 선택 표시만 다시 그리라는 알림 */
        private val SELECTION = Any()
    }
}

fun applyInsets(root: View) {
    ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
        val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        v.setPadding(b.left, b.top, b.right, b.bottom)
        insets
    }
}
