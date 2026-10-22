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
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SearchView
import androidx.recyclerview.widget.ItemTouchHelper
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

    /** ⋮ ▸ 앱 정보 · 업데이트 */
    private val updater by lazy { Updater(this) }

    private enum class Tab(val label: String, val icon: Int) {
        RECENT("최근 파일", R.drawable.ic_history),
        FAVORITE("즐겨찾기", R.drawable.ic_star),
        ALL("모든 문서", R.drawable.ic_doc),
        FOLDER("폴더", R.drawable.ic_folder),
    }
    /** HEADER: 태그로 모아 볼 때 문서들 위에 붙는 폴더 머리글 */
    private enum class Kind { FOLDER, DRIVE, DOC, HEADER }

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
        /** 기기 폴더·저장소 줄이면 그 폴더 (문서를 끌어다 놓아 옮길 수 있음) */
        val dir: File? = null,
    ) {
        /** 폴더 머리글 아래 문서의 가지선: 0 없음, 1 중간(├), 2 마지막(└) */
        var tree = 0
        /** 폴더 계단에서 들여쓰는 칸 수 (머리글은 그 폴더의 깊이, 가지에 달린 줄은 맨 아래 폴더의 깊이) */
        var indent = 0
        /** 태그를 골랐을 때 태그에 안 맞는 문서: 흐리게 보인다 */
        var dim = false
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
    private lateinit var crumbRow: LinearLayout
    private lateinit var upButton: View
    private lateinit var newFolderButton: View
    private lateinit var filterButton: MaterialButton
    private lateinit var sortButton: MaterialButton
    private lateinit var viewButton: MaterialButton
    private lateinit var searchItem: MenuItem
    private lateinit var selectBar: View
    private lateinit var tagBar: View
    private lateinit var tagClear: View
    private lateinit var tagRow: LinearLayout
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
    /** 고른 추천 태그들 (비어 있으면 안 고름). 탭이나 폴더를 바꾸면 풀린다 */
    private val tagFilters = LinkedHashSet<String>()
    /** 정렬한 줄들 (태그를 골랐으면 [rows]는 이것을 태그 차례로 바꾼 것) */
    private var baseRows: List<Row> = emptyList()
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
                tagFilters.isNotEmpty() -> clearTags()
                else -> goUp()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // 기기가 다크 모드여도 밝은 바탕에 맞춰 상태 표시줄 아이콘을 어둡게
        enableEdgeToEdge(
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
        )
        super.onCreate(savedInstanceState)
        // 업데이트로 받아 둔 설치 파일은 앱이 새로 켜질 때 지운다
        if (savedInstanceState == null) Updater.clearOld(this)
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
        crumbRow = findViewById(R.id.crumbRow)
        upButton = findViewById(R.id.upButton)
        newFolderButton = findViewById(R.id.newFolderButton)
        filterButton = findViewById(R.id.filterButton)
        sortButton = findViewById(R.id.sortButton)
        viewButton = findViewById(R.id.viewButton)
        selectBar = findViewById(R.id.selectBar)
        tagBar = findViewById(R.id.tagBar)
        tagClear = findViewById(R.id.tagClear)
        tagClear.setOnClickListener { clearTags() }
        tagRow = findViewById(R.id.tagRow)
        selectCount = findViewById(R.id.selectCount)
        selectAll = findViewById(R.id.selectAll)
        actionBar = findViewById(R.id.actionBar)
        actionRow = findViewById(R.id.actionRow)
        thumbTarget = savedInstanceState?.getString("thumbTarget")
        setupSelection()

        list.adapter = adapter
        list.addItemDecoration(TreeDecoration())
        grid = prefs.getBoolean("grid", false)
        applyViewMode()
        list.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ ->
            // 레이아웃 도중에 바꾸면 무시될 수 있어 다음 차례에
            if (r - l != or - ol) list.post { (list.layoutManager as? GridLayoutManager)?.spanCount = spanCount() }
        }
        upButton.setOnClickListener { goUp() }
        newFolderButton.setOnClickListener { editFolder(null, favDir) }
        filterButton.setOnClickListener { showFilterDialog() }
        sortButton.setOnClickListener { showSortMenu() }
        viewButton.setOnClickListener {
            grid = !grid
            prefs.edit().putBoolean("grid", grid).apply()
            applyViewMode()
        }
        findViewById<View>(R.id.permButton).setOnClickListener { askAccess() }
        setupMenu()
        reorder.attachToRecyclerView(list)

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
                resetTags()
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
        checkDrafts()
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
        updater.onResume()
        // 권한 설정에서 돌아왔거나, 문서를 저장하고 돌아왔을 수 있으므로 매번 새로 읽는다
        refresh()
        updateOpenTabs()
        updateRecoverItem()
        updateReviewItem()
    }

    /** '종류' 정렬 순서: PDF · 한글 · 워드 · 파워포인트 · 글 · 그림 */
    private fun typeRank(t: DocType) = DocGroup.of(t)?.ordinal ?: DocGroup.entries.size

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
                R.id.action_recover -> { showRecoverDialog(pendingDrafts()); true }
                R.id.action_review -> { showReviewDialog(); true }
                R.id.action_lock_password -> { Locks.changePassword(this); true }
                R.id.action_about -> { updater.show(); true }
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
        updateFilterButton()
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
                crumbs = null
                show(recentRows(), "최근에 연 파일이 없습니다.\n'모든 문서'나 '폴더' 탭에서 문서를 골라 보세요.")
            }
            Tab.FAVORITE -> {
                progress.isVisible = false
                val rows = favoriteRows()
                val path = FavFolders.path(FavFolders.list(this), favDir)
                barLabel = { listOf("즐겨찾기").plus(path.map { it.name }).joinToString("  ›  ") }
                crumbs = favCrumbs(path)
                upButton.isEnabled = favDir != null
                upButton.alpha = if (favDir != null) 1f else 0.3f
                show(
                    rows,
                    if (favDir == null) "즐겨찾기한 문서가 없습니다.\n'최근 파일'에서 문서 옆의 ☆를 누르면 여기에 모입니다.\n오른쪽 위 폴더 단추로 즐겨찾기 폴더를 만들 수 있습니다."
                    else "이 폴더는 비어 있습니다.\n즐겨찾기 문서를 길게 눌러 '폴더로 옮기기'를 고르세요."
                )
            }
            Tab.ALL -> {
                barLabel = { n -> if (n == 0) "기기의 문서" else "기기의 문서 ${n}개" }
                crumbs = null
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
                crumbs = folderCrumbs(d)
                upButton.isEnabled = canGoUp()
                upButton.alpha = if (canGoUp()) 1f else 0.3f
                val key = sortKey()
                load("이 폴더에는 문서가 없습니다.") {
                    val rec = Recents.list(this).associateBy { it.uri }
                    val locked = Locks.locked(this)
                    if (d == null) roots.map { r ->
                        Row(r.name, r.file.path, Kind.DRIVE, { openDir(r.file) }, folder = FolderDrawable(DocColors.HWP, TapePattern.SOLID), short = "", dir = r.file)
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

    private fun show(all: List<Row>, emptyMsg: String) {
        val groups = shownGroups()
        val rows = all.filter { it.kind != Kind.DOC || DocGroup.of(DocType.ofName(it.title))?.let { g -> g in groups } ?: true }
        baseRows = if (manualOn()) ListSort.sortManual(sortRows(rows), FavOrder.load(this), { it.key }, rowFields, foldersFirst()) else sortRows(rows)
        renderTags()
        arrangeTag()
        if (selecting) selected.retainAll(this.rows.mapNotNull { it.key }.toSet())
        this.emptyMsg = if (rows.none { !it.isFolder } && all.any { !it.isFolder })
            "고른 형식(${groups.joinToString("·") { it.label }})의 문서가 없습니다.\n깔때기 단추에서 볼 형식을 고르세요."
        else emptyMsg
        renderPath(rows.count { !it.isFolder })
        applyFilter()
        onSelectionChanged()
        pendingScroll?.let { list.layoutManager?.onRestoreInstanceState(it) }
        pendingScroll = null
    }

    private fun applyFilter() {
        val q = query.trim()
        val shown = ListSort.filterByTitle(rows, q, rowFields)
        // 즐겨찾기 / 지금 폴더까지의 길을 계단 모양 폴더 머리글로 두고 그 아래에 줄들을 가지로 이은다
        val links = when {
            tab == Tab.FAVORITE -> favLinks()
            tab == Tab.FOLDER -> dir?.let { folderLinks(it) } ?: emptyList()
            else -> emptyList()
        }
        adapter.submit(withChain(shown, links))
        // 안내 글은 폴더 머리글들 아래에 뜬다
        emptyText.setPadding(dp(32), dp(32 + CHAIN_ROW_DP * links.size), dp(32), dp(32))
        emptyText.text = if (q.isEmpty()) emptyMsg else "'$q' 이름의 문서가 없습니다."
        emptyText.isVisible = shown.isEmpty() && !progress.isVisible && !permCard.isVisible
    }

    // ================= 추천 태그 =================
    // 최근 파일·즐겨찾기·모든 문서·폴더 탭: 지금 목록(즐겨찾기·폴더는 지금 들어간 폴더)의 문서 제목에 자주 나오는 낱말을 '# 태그'로 보여 준다.
    // 태그는 여러 개 고를 수 있고(누르면 켜고, 다시 누르면 끈다), 고른 태그에 하나라도 맞는 문서가 맨 위로, 맞는 태그가 많은 문서부터 온다.
    // 나머지 문서는 그 아래에서 흐리게 보인다 (없애지는 않는다). 폴더를 옮기면 태그도 바뀐다. 줄 맨 오른쪽 ✕로 고른 태그를 모두 푼다

    /** 태그 줄을 그린다. 지금 목록의 문서([baseRows])에 공통 낱말이 있을 때만 보이고, 없어진 태그를 골라 둔 상태면 푼다 */
    private fun renderTags() {
        val tags = TitleTags.extract(baseRows.filter { !it.isFolder }.map { it.title })
        tagFilters.retainAll(tags.toSet())
        tagBar.isVisible = !selecting && tags.isNotEmpty()
        tagClear.isVisible = tagFilters.isNotEmpty()
        tagRow.removeAllViews()
        for (t in tags) tagRow.addView(tagChip(t, t in tagFilters))
    }

    /** 고른 태그에 맞게 [rows]의 차례와 흐림을 정한다 (태그를 안 골랐으면 정렬한 그대로) */
    private fun arrangeTag() {
        if (tagFilters.isEmpty()) {
            baseRows.forEach { it.dim = false }
            rows = baseRows
            return
        }
        val (hit, rest) = TitleTags.rank(baseRows, tagFilters, { it.title }, { !it.isFolder })
        hit.forEach { it.dim = false }
        rest.forEach { it.dim = !it.isFolder }
        rows = hit + rest
    }

    /** 고른 태그를 풀고 태그 줄을 감춘다 (탭이나 폴더를 옮길 때: 새 목록이 올 때까지 옛 태그가 보이지 않게) */
    private fun resetTags() {
        tagFilters.clear()
        tagBar.isVisible = false
    }

    private fun tagChip(tag: String, on: Boolean): View {
        fun color(attr: Int, fallback: Int) = com.google.android.material.color.MaterialColors.getColor(this, attr, fallback)
        val bg = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(18).toFloat()
            setColor(
                if (on) color(com.google.android.material.R.attr.colorSecondaryContainer, Color.LTGRAY)
                else color(com.google.android.material.R.attr.colorSurface, Color.WHITE)
            )
            setStroke(dp(1), color(com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY))
        }
        return TextView(this).apply {
            text = "# $tag"
            setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
            setTextColor(
                if (on) color(com.google.android.material.R.attr.colorOnSecondaryContainer, Color.BLACK)
                else color(com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
            )
            if (on) setTypeface(typeface, android.graphics.Typeface.BOLD)
            background = bg
            gravity = android.view.Gravity.CENTER
            setPadding(dp(14), 0, dp(14), 0)
            isSingleLine = true
            contentDescription = if (on) "$tag 태그 (눌러서 끄기)" else "$tag 태그 켜기"
            // apply 안이라 setTag만 쓰면 View.setTag로 읽히므로 이름을 달리한다
            setOnClickListener { toggleTag(tag) }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(36)).apply { marginEnd = dp(8) }
        }
    }

    /** 태그 [t]를 켜거나 끈다 */
    private fun toggleTag(t: String) {
        if (!tagFilters.remove(t)) tagFilters += t
        retag()
    }

    /** 고른 태그를 모두 푼다 */
    private fun clearTags() {
        tagFilters.clear()
        retag()
    }

    /** 고른 태그가 바뀐 뒤: 줄 차례·흐림과 태그 줄을 다시 그린다 (목록을 다시 읽지 않는다) */
    private fun retag() {
        endSelection()
        renderTags()
        arrangeTag()
        applyFilter()
        list.scrollToPosition(0)
    }

    /** 폴더 계단의 한 칸: 폴더 이름과 그림, 누르면 그 폴더로 (지금 폴더는 null) */
    private class Link(val name: String, val icon: Drawable, val onClick: (() -> Unit)?)

    /** 즐겨찾기 맨 위부터 지금 폴더까지 */
    private fun favLinks(): List<Link> {
        val path = FavFolders.path(FavFolders.list(this), favDir)
        val out = ArrayList<Link>()
        out += Link("즐겨찾기", FolderDrawable(FOLDER_COLOR, TapePattern.SOLID, open = path.isEmpty()), if (path.isEmpty()) null else ({ openFavRoot() }))
        path.forEachIndexed { i, f ->
            val here = i == path.lastIndex
            out += Link(f.name, FolderDrawable(f.color, f.pattern, open = here), if (here) null else ({ openFavDir(f.id) }))
        }
        return out
    }

    /** 폴더 탭: 저장소부터 지금 폴더까지 */
    private fun folderLinks(d: File): List<Link> {
        val chain = ArrayList<File>()
        var f: File? = d
        while (f != null) {
            chain.add(0, f)
            if (isRoot(f)) break
            f = f.parentFile
        }
        return chain.mapIndexed { i, dir ->
            val here = i == chain.lastIndex
            val root = roots.firstOrNull { it.file.path == dir.path }
            Link(
                root?.name ?: dir.name,
                FolderDrawable(if (root != null) DocColors.HWP else FOLDER_COLOR, TapePattern.SOLID, open = here),
                if (here) null else ({ openDir(dir) }),
            )
        }
    }

    /**
     * 폴더 계단: [links](맨 위 폴더 … 지금 폴더)를 한 칸씩 더 들여 쓴 폴더 머리글로 늘어놓고,
     * 지금 폴더 안의 줄들을 그 아래에 가지선으로 잇는다. 빈 폴더도 머리글은 보이고 (안내 글은 그 아래에 따로 뜬다)
     */
    private fun withChain(children: List<Row>, links: List<Link>): List<Row> {
        if (links.isEmpty()) return children
        val out = ArrayList<Row>()
        links.forEachIndexed { i, l ->
            val last = i == links.lastIndex
            val count = if (!last) "" else if (children.isEmpty()) "비어 있음" else "${children.size}개"
            out += Row(l.name, count, Kind.HEADER, { l.onClick?.invoke() }, folder = l.icon).also { it.indent = i }
        }
        children.forEachIndexed { n, r ->
            r.tree = if (n == children.lastIndex) 2 else 1
            r.indent = links.lastIndex
            out += r
        }
        return out
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
        val resume = if (item.lastPage > 0) "${item.lastPage + 1}쪽에서 이어 보기" else null
        val sub = listOfNotNull(date(shownTime), resume, file?.parentFile?.let(::shortPath)).joinToString(" · ")
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
            folder = FolderDrawable(FOLDER_COLOR, TapePattern.SOLID), modified = e.time, dir = e.file,
        )
        val info = "${date(e.time)} · ${Formatter.formatShortFileSize(this, e.size)}"
        val sub = if (showPath) "${e.file.parentFile?.let(::shortPath) ?: ""} · $info" else info
        val uri = Uri.fromFile(e.file)
        val item = rec[uri.toString()]
        // 모든 문서·폴더 탭에서도 별을 눌러 즐겨찾기를 넣고 뺀다 (목록에 없던 문서는 기록을 새로 만든다)
        return Row(
            e.name, sub, Kind.DOC, { unlockThen(uri.toString()) { openFile(e.file) } },
            star = item?.star == true,
            onStar = {
                val cur = Recents.get(this, uri.toString())
                if (cur != null) toggleStar(cur)
                else {
                    Recents.add(this, uri.toString(), e.name)
                    Recents.setStar(this, uri.toString(), true)
                    refresh()
                }
            },
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

    /** 경로 줄의 폴더 하나: 그림 · 이름 · 누르면 그 폴더로 (지금 들어간 폴더는 null) */
    private class Crumb(val name: String, val icon: Drawable, val onClick: (() -> Unit)?)

    /** 폴더·즐겨찾기 탭의 경로(맨 위 폴더 … 지금 폴더). 다른 탭은 null이라 글자만 보인다 */
    private var crumbs: List<Crumb>? = null

    /** 폴더 탭: 저장소 … 지금 폴더. 지금 폴더는 열린 폴더 그림 */
    private fun folderCrumbs(d: File?): List<Crumb> {
        if (d == null) return listOf(Crumb("저장소", FolderDrawable(DocColors.HWP, TapePattern.SOLID, open = true), null))
        val chain = ArrayList<File>()
        var f: File? = d
        while (f != null) {
            chain.add(0, f)
            if (isRoot(f)) break
            f = f.parentFile
        }
        return chain.mapIndexed { i, dir ->
            val here = i == chain.lastIndex
            val root = roots.firstOrNull { it.file.path == dir.path }
            Crumb(
                root?.name ?: dir.name,
                FolderDrawable(if (root != null) DocColors.HWP else FOLDER_COLOR, TapePattern.SOLID, open = here),
                if (here) null else ({ openDir(dir) }),
            )
        }
    }

    /** 즐겨찾기 탭: 즐겨찾기 … 지금 폴더 ([path]는 맨 위 폴더부터) */
    private fun favCrumbs(path: List<FavFolder>): List<Crumb> {
        val out = ArrayList<Crumb>()
        out += Crumb("즐겨찾기", FolderDrawable(FOLDER_COLOR, TapePattern.SOLID, open = path.isEmpty()), if (path.isEmpty()) null else ({ openFavRoot() }))
        path.forEachIndexed { i, f ->
            val here = i == path.lastIndex
            out += Crumb(f.name, FolderDrawable(f.color, f.pattern, open = here), if (here) null else ({ openFavDir(f.id) }))
        }
        return out
    }

    /**
     * 목록 위 경로 줄을 그린다. 폴더·즐겨찾기 탭은 지금 폴더와 그 위 두 폴더를 폴더 그림과 이름으로 (더 위는 …),
     * 다른 탭은 글자 하나. 위 폴더를 누르면 그 폴더로 간다
     */
    private fun renderPath(count: Int) {
        val cs = crumbs
        if (cs == null) {
            crumbRow.isVisible = false
            pathText.isVisible = true
            pathText.text = barLabel(count)
            return
        }
        pathText.isVisible = false
        crumbRow.isVisible = true
        crumbRow.removeAllViews()
        val shown = cs.takeLast(CRUMBS_SHOWN)
        if (cs.size > shown.size) crumbRow.addView(crumbSeparator("…"))
        shown.forEachIndexed { i, c ->
            if (i > 0 || cs.size > shown.size) crumbRow.addView(crumbSeparator("›"))
            crumbRow.addView(crumbView(c, here = i == shown.lastIndex))
        }
        pathScroll.post { pathScroll.fullScroll(View.FOCUS_RIGHT) }
    }

    private fun crumbSeparator(text: String) = TextView(this).apply {
        this.text = text
        setTextColor(com.google.android.material.color.MaterialColors.getColor(this@MainActivity, com.google.android.material.R.attr.colorOutline, Color.GRAY))
        setPadding(dp(4), 0, dp(4), 0)
    }

    /** 폴더 그림 + 이름. 지금 들어간 폴더는 굵게, 위 폴더는 누를 수 있다 */
    private fun crumbView(c: Crumb, here: Boolean): View {
        val onSurface = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        val variant = com.google.android.material.color.MaterialColors.getColor(this, com.google.android.material.R.attr.colorOnSurfaceVariant, Color.DKGRAY)
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(4), dp(8), dp(4))
            addView(ImageView(context).apply { setImageDrawable(c.icon) }, LinearLayout.LayoutParams(dp(if (here) 28 else 24), dp(if (here) 24 else 20)))
            addView(TextView(context).apply {
                text = c.name
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(if (here) onSurface else variant)
                if (here) setTypeface(typeface, android.graphics.Typeface.BOLD)
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
                maxWidth = dp(180)
                setPadding(dp(6), 0, 0, 0)
            })
            c.onClick?.let { go ->
                val out = android.util.TypedValue()
                theme.resolveAttribute(android.R.attr.selectableItemBackground, out, true)
                setBackgroundResource(out.resourceId)
                contentDescription = "${c.name} 폴더로 가기"
                setOnClickListener { go() }
            } ?: run { contentDescription = "지금 ${c.name} 폴더" }
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    /** 즐겨찾기 맨 위로 (지금 폴더에서 한꺼번에 올라간다) */
    private fun openFavRoot() {
        scrollStates["fav:${favDir ?: ""}"] = list.layoutManager?.onSaveInstanceState()
        setFavDir(null)
        pendingScroll = scrollStates.remove("fav:")
        adapter.submit(emptyList())
        refresh()
    }

    // ================= 정렬 · 보기 =================

    private fun sortKeys(t: Tab) = ListSort.keysFor(t == Tab.RECENT || t == Tab.FAVORITE)

    private fun defaultSort(t: Tab) = when (t) {
        Tab.RECENT, Tab.FAVORITE -> SortKey.OPENED
        Tab.ALL -> SortKey.MODIFIED
        Tab.FOLDER -> SortKey.NAME
    }

    private fun sortKey(): SortKey {
        return ListSort.resolveKey(prefs.getString("sort_${tab.name}", null), sortKeys(tab), defaultSort(tab))
    }

    private fun sortDesc(): Boolean = prefs.getBoolean("sortDesc_${tab.name}", sortKey().descFirst)

    private fun setSort(key: SortKey, desc: Boolean) {
        prefs.edit().putString("sort_${tab.name}", key.name).putBoolean("sortDesc_${tab.name}", desc).putBoolean("favManual", false).apply()
        refresh()
    }

    /** 즐겨찾기를 내가 끌어서 정한 순서로 보는 중인가 */
    private fun manualOn() = tab == Tab.FAVORITE && prefs.getBoolean("favManual", false)

    private fun setManual() {
        prefs.edit().putBoolean("favManual", true).apply()
        refresh()
        android.widget.Toast.makeText(this, "문서·폴더 옆 손잡이를 끌어서 순서를 바꾸세요.", android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun updateSortButton() {
        if (manualOn()) {
            sortButton.text = MANUAL_LABEL
            sortButton.contentDescription = "정렬: $MANUAL_LABEL"
            return
        }
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
        val manual = manualOn()
        keys.forEachIndexed { i, k -> m.add(1, i, i, k.label).isChecked = k == key && !manual }
        m.setGroupCheckable(1, true, true)
        val (asc, dsc) = key.dirLabels
        // 이 기준에서 먼저 고르는 쪽을 위에
        val dirs = if (key.descFirst) listOf(true to dsc, false to asc) else listOf(false to asc, true to dsc)
        // 직접 정한 순서로 볼 때는 오름·내림이 뜻이 없다
        if (!manual) dirs.forEachIndexed { i, (d, label) -> m.add(2, 100 + i, 100 + i, label).isChecked = d == desc }
        m.setGroupCheckable(2, true, true)
        // 즐겨찾기: 위 기준 대신 내가 끌어서 정한 순서
        if (tab == Tab.FAVORITE) m.add(3, MANUAL_ID, MANUAL_ID, "$MANUAL_LABEL (끌어서 바꾸기)").isChecked = manual
        m.setGroupCheckable(3, true, true)
        // 폴더 줄을 파일의 위에 둘지 아래에 둘지
        val first = foldersFirst()
        m.add(4, FOLDERS_UP_ID, FOLDERS_UP_ID, "폴더를 파일보다 위로").isChecked = first
        m.add(4, FOLDERS_DOWN_ID, FOLDERS_DOWN_ID, "폴더를 파일보다 아래로").isChecked = !first
        m.setGroupCheckable(4, true, true)
        MenuCompat.setGroupDividerEnabled(m, true)
        pm.setOnMenuItemClickListener { item ->
            when {
                item.groupId == 4 -> {
                    prefs.edit().putBoolean("foldersFirst", item.itemId == FOLDERS_UP_ID).apply()
                    refresh()
                }
                item.itemId == MANUAL_ID -> setManual()
                item.groupId == 1 -> {
                    val k = keys[item.itemId]
                    setSort(k, if (k == key && !manual) desc else k.descFirst)
                }
                else -> setSort(key, dirs[item.itemId - 100].first)
            }
            true
        }
        pm.show()
    }

    // ================= 보여 줄 형식 =================

    /** 이 탭에서 고를 수 있는 형식. 그림은 기기 사진이 모두 섞이지 않게 '모든 문서'에서는 뺀다 */
    private fun groupsFor(t: Tab) = ListSort.groupsFor(t == Tab.ALL)

    /** 이 탭에서 보여 줄 형식 (처음엔 모두) */
    private fun shownGroups(): List<DocGroup> {
        return ListSort.shownGroups(groupsFor(tab), prefs.getStringSet("groups_${tab.name}", null))
    }

    private fun updateFilterButton() {
        val all = groupsFor(tab)
        val shown = shownGroups()
        val active = shown.size < all.size
        val attr = if (active) androidx.appcompat.R.attr.colorPrimary else com.google.android.material.R.attr.colorOnSurfaceVariant
        filterButton.iconTint = ColorStateList.valueOf(com.google.android.material.color.MaterialColors.getColor(filterButton, attr))
        filterButton.backgroundTintList = if (active) ColorStateList.valueOf(
            com.google.android.material.color.MaterialColors.getColor(filterButton, com.google.android.material.R.attr.colorSecondaryContainer)
        ) else ColorStateList.valueOf(android.graphics.Color.TRANSPARENT)  // null을 주면 Material 버튼이 검정으로 칠한다
        val desc = if (active) "보여 줄 형식: ${shown.joinToString(", ") { it.label }}" else "보여 줄 형식: 모두"
        filterButton.contentDescription = desc
        filterButton.tooltipText = desc
    }

    private fun showFilterDialog() {
        val all = groupsFor(tab)
        val shown = shownGroups().toMutableSet()
        val d = resources.displayMetrics.density
        val gray = com.google.android.material.color.MaterialColors.getColor(filterButton, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * d).toInt(), (8 * d).toInt(), (20 * d).toInt(), 0)
        }
        lateinit var dialog: androidx.appcompat.app.AlertDialog
        val checks = all.map { g ->
            com.google.android.material.checkbox.MaterialCheckBox(this).apply {
                text = android.text.SpannableStringBuilder(g.label).append(
                    "   ${g.exts}",
                    android.text.style.ForegroundColorSpan(gray),
                    android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                textSize = 16f
                minHeight = (48 * d).toInt()
                isChecked = g in shown
                setOnCheckedChangeListener { _, on ->
                    if (on) shown += g else shown -= g
                    // 하나도 안 고르면 볼 것이 없으므로 '확인'을 막는다
                    dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE)?.isEnabled = shown.isNotEmpty()
                }
                box.addView(this)
            }
        }
        if (tab == Tab.ALL) box.addView(TextView(this).apply {
            text = "그림(사진)은 '폴더' 탭에서 고를 수 있습니다."
            setTextColor(gray)
            textSize = 13f
            setPadding((8 * d).toInt(), (8 * d).toInt(), 0, 0)
        })
        dialog = MaterialAlertDialogBuilder(this)
            .setTitle("보여 줄 형식")
            .setView(box)
            .setPositiveButton("확인") { _, _ ->
                val set = if (shown.size == all.size) null else shown.map { it.name }.toSet()
                prefs.edit().putStringSet("groups_${tab.name}", set).apply()
                refresh()
            }
            .setNegativeButton("취소", null)
            .setNeutralButton("모두", null)
            .create()
        dialog.setOnShowListener {
            // '모두'는 창을 닫지 않고 전부 체크만 한다
            dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener { checks.forEach { it.isChecked = true } }
        }
        dialog.show()
    }

    /** 폴더는 늘 먼저. 폴더끼리는 이름이나 날짜로만 (크기·종류가 없으므로 그때는 이름순) */
    private val rowFields = object : ListSort.Fields<Row> {
        override fun title(r: Row) = r.title
        override fun isFolder(r: Row) = r.isFolder
        override fun isDrive(r: Row) = r.kind == Kind.DRIVE
        override fun opened(r: Row) = r.opened
        override fun modified(r: Row) = r.modified
        override fun size(r: Row) = r.size
        override fun typeRank(r: Row) = typeRank(DocType.ofName(r.title))
    }

    private fun sortRows(rows: List<Row>): List<Row> = ListSort.sort(rows, sortKey(), sortDesc(), collator, rowFields, foldersFirst())

    /** 정렬 ▸ 폴더를 파일보다 위로(기본) / 아래로 */
    private fun foldersFirst() = prefs.getBoolean("foldersFirst", true)

    private fun spanCount(): Int {
        // 목록으로 볼 때 가로 화면이면 두 칸으로 나눠 오른쪽도 쓴다
        val landscape = resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        return ListSort.spanCount(grid, landscape, list.width - list.paddingLeft - list.paddingRight, resources.displayMetrics.density)
    }

    private fun applyViewMode() {
        val d = resources.displayMetrics.density
        val state = list.layoutManager?.onSaveInstanceState()
        if (grid) list.setPadding((8 * d).toInt(), (4 * d).toInt(), (8 * d).toInt(), (12 * d).toInt())
        else list.setPadding(0, (4 * d).toInt(), 0, (4 * d).toInt())
        val lm = GridLayoutManager(this, spanCount())
        // 폴더 머리글은 한 줄을 다 쓴다
        lm.spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
            override fun getSpanSize(position: Int) = if (adapter.items.getOrNull(position)?.kind == Kind.HEADER) lm.spanCount else 1
        }
        list.layoutManager = lm
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
        resetTags()
        dir = f
        prefs.edit().putString("dir", f?.path).apply()
    }

    private fun setFavDir(id: String?) {
        resetTags()
        favDir = id
        prefs.edit().putString("favDir", id).apply()
    }

    private fun updateBack() {
        backCallback.isEnabled = selecting || searchItem.isActionViewExpanded || tagFilters.isNotEmpty() ||
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
        val orientGroup = view.findViewById<MaterialButtonToggleGroup>(R.id.orientGroup)
        val lastPaper = prefs.getString("notePaper", null)?.let { n -> Paper.entries.firstOrNull { it.name == n } } ?: Paper.GRID
        val picker = PaperPicker(this, lastPaper)
        view.findViewById<android.widget.FrameLayout>(R.id.paperPickerHost).addView(picker.view)
        // 방향은 지금 기기가 세로면 세로, 가로면 가로가 기본
        val dm = resources.displayMetrics
        orientGroup.check(if (NoteDefaults.portrait(dm.widthPixels, dm.heightPixels)) R.id.orientPortrait else R.id.orientLandscape)
        view.findViewById<TextView>(R.id.orientLabel).text = "방향 (A4)"
        MaterialAlertDialogBuilder(this)
            .setTitle("새 노트")
            .setView(view)
            .setPositiveButton("만들기") { _, _ ->
                val paper = picker.selected
                val portrait = orientGroup.checkedButtonId == R.id.orientPortrait
                prefs.edit().putString("notePaper", paper.name).apply()
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

    /** 검색 중이면 검색을 끝내고 전체 목록으로 (검색해서 문서를 열고 돌아와도 다른 파일이 바로 보이게) */
    private fun endSearch() {
        if (::searchItem.isInitialized && searchItem.isActionViewExpanded) searchItem.collapseActionView()
    }

    private fun openViewer(uri: Uri, newNote: Boolean = false, startReview: Boolean = false) {
        endSearch()
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
                .putExtra(ViewerActivity.EXTRA_START_REVIEW, startReview)
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
        // 고르는 중에는 왼쪽 ✕로 취소하고 열려 있던 탭으로 돌아간다 (이 화면에서 기존 탭을 보려면 취소해야 하므로)
        if (on) {
            toolbar.setNavigationIcon(R.drawable.ic_close)
            toolbar.navigationContentDescription = "취소하고 열린 문서로 돌아가기"
            toolbar.setNavigationOnClickListener { backToViewer() }
        } else {
            toolbar.navigationIcon = null
            toolbar.setNavigationOnClickListener(null)
        }
    }

    // ================= 오늘 복습 =================

    /** 오늘 복습할 오답이 있는 문서들과 그 개수 (지워진 파일은 뺀다) */
    private fun dueDocs(): List<Pair<ReviewIndexItem, Int>> =
        ReviewIndex.dueItems(ReviewIndexStore.load(this), ReviewSchedule.today()).filter { (item, _) ->
            !item.uri.startsWith("file:") || Uri.parse(item.uri).path?.let { File(it).exists() } == true
        }

    private fun updateReviewItem() {
        toolbar.menu.findItem(R.id.action_review)?.apply {
            val n = dueDocs().sumOf { it.second }
            isVisible = n > 0 && !pickMode
            title = "오늘 복습 $n"
        }
    }

    /** 문서별로 오늘 복습할 오답 수를 보이고, 고르면 그 문서를 열어 바로 복습을 시작한다 */
    private fun showReviewDialog() {
        val due = dueDocs()
        if (due.isEmpty()) {
            android.widget.Toast.makeText(this, "오늘 복습할 스크랩이 없습니다.", android.widget.Toast.LENGTH_SHORT).show()
            return
        }
        val items = due.map { (item, n) ->
            val name = if (Locks.isLocked(this, item.uri)) "잠긴 문서" else item.name
            "$name\n스크랩 ${n}개"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("오늘 복습할 스크랩 ${due.sumOf { it.second }}개")
            .setItems(items) { _, i ->
                val uri = due[i].first.uri
                unlockThen(uri) { openViewer(Uri.parse(uri), startReview = true) }
            }
            .setNegativeButton("닫기", null)
            .show()
    }

    // ================= 자동 저장 복구 =================

    /** 아직 열려 있지 않은, 복구할 수 있는 자동 저장본 */
    private fun pendingDrafts() = Drafts.store(this).list().filter { it.uri !in ViewerActivity.openUris }

    private fun updateRecoverItem() {
        toolbar.menu.findItem(R.id.action_recover)?.apply {
            val n = pendingDrafts().size
            isVisible = n > 0 && !pickMode
            title = "복구할 필기 $n"
        }
    }

    /**
     * 앱을 새로 켰을 때(시스템이 끈 뒤 포함) 저장하지 않은 채 남은 필기가 있으면 복구할지 묻는다.
     * 뷰어가 열려 있으면 앱이 죽었다 살아난 것이 아니니 묻지 않는다
     */
    private fun checkDrafts() {
        if (draftsChecked) return
        draftsChecked = true
        if (ViewerActivity.openTabs > 0) return
        val store = Drafts.store(this)
        lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) {
                store.cleanup(System.currentTimeMillis() - DRAFT_KEEP_MS)
                HandwritingIndex.cleanOld(HandwritingIndex.dir(this@MainActivity), System.currentTimeMillis() - DRAFT_KEEP_MS * 2)
                store.list()
            }
            updateRecoverItem()
            if (found.isNotEmpty()) showRecoverDialog(found)
        }
    }

    private fun showRecoverDialog(found: List<DraftMeta>) {
        if (found.isEmpty()) return
        // 여기서 한 번 물었으니, 나중에 그 문서를 열 때 뷰어가 또 묻지 않는다
        Drafts.asked.addAll(found.map { it.id })
        val items = found.map {
            val name = if (Locks.isLocked(this, it.uri)) "잠긴 문서" else it.name
            "$name\n${DateFormat.format("M월 d일 a h:mm", Date(it.time))}까지 쓴 필기"
        }.toTypedArray()
        MaterialAlertDialogBuilder(this)
            .setTitle("저장하지 않은 필기 ${found.size}개")
            .setItems(items) { _, i -> unlockThen(found[i].uri) { openDraft(found[i]) } }
            .setPositiveButton("모두 저장") { _, _ -> saveAllDrafts(found) }
            .setNegativeButton("나중에", null)
            .setNeutralButton("모두 버리기") { _, _ -> confirmDiscardDrafts(found) }
            .show()
    }

    private var savingDrafts = false

    /**
     * 저장하지 않은 필기를 문서를 열지 않고 모두 저장한다 ([DraftSaver]): 원래 문서가 있으면 덮어쓰고,
     * 새 노트처럼 덮어쓸 곳이 없으면 문서 폴더의 DSnote 폴더에 새 PDF로 둔다. 저장하지 못한 것은 그대로 남는다
     */
    private fun saveAllDrafts(found: List<DraftMeta>) {
        if (savingDrafts || found.isEmpty()) return
        savingDrafts = true
        fun shown(name: String, uri: String) = if (Locks.isLocked(this, uri)) "잠긴 문서" else name
        val names = found.associate { it.name to shown(it.name, it.uri) }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("저장하지 않은 필기를 저장하는 중")
            .setMessage("준비하는 중입니다…")
            .setCancelable(false)
            .show()
        lifecycleScope.launch {
            val result = withContext(Dispatchers.IO) {
                DraftSaver.saveAll(applicationContext, Drafts.store(this@MainActivity), found) { i, n, name ->
                    if (name.isNotEmpty()) runOnUiThread { dialog.setMessage("${i + 1} / $n\n${names[name] ?: name}") }
                }
            }
            runCatching { dialog.dismiss() }
            savingDrafts = false
            updateRecoverItem()
            refresh()
            val text = StringBuilder()
            if (result.saved.isNotEmpty()) text.append("${result.saved.size}개를 저장했습니다.")
            val moved = result.saved.filter { it.folder != null }
            if (moved.isNotEmpty()) {
                text.append("\n\n덮어쓸 원본이 없는 ${moved.size}개는 ${moved[0].folder} 폴더에 새 PDF로 두었습니다:")
                moved.forEach { text.append("\n· ${it.name}") }
            }
            if (result.left.isNotEmpty()) {
                text.append("\n\n저장하지 못한 ${result.left.size}개는 그대로 남겨 두었습니다. 하나씩 열어 복구할 수 있습니다:")
                result.left.forEach { text.append("\n· ${names[it.name] ?: it.name} (${it.reason})") }
            }
            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(if (result.left.isEmpty()) "저장했습니다" else "일부만 저장했습니다")
                .setMessage(text.toString())
                .setPositiveButton("확인", null)
                .show()
        }
    }

    private fun confirmDiscardDrafts(found: List<DraftMeta>) {
        MaterialAlertDialogBuilder(this)
            .setMessage("저장하지 않은 필기 ${found.size}개를 지웁니다. 되돌릴 수 없습니다.")
            .setPositiveButton("지우기") { _, _ ->
                val store = Drafts.store(this)
                found.forEach { store.delete(it.id) }
                updateRecoverItem()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun openDraft(meta: DraftMeta) {
        endSearch()
        startActivity(
            Intent(this, ViewerActivity::class.java)
                .setData(Uri.parse(meta.uri))
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                .putExtra(ViewerActivity.EXTRA_FROM_BROWSER, true)
                .putExtra(ViewerActivity.EXTRA_DRAFT, meta.id)
        )
        setPickMode(false)
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
            tagBar.isVisible = false
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
        renderTags()
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
        val base = FileNames.baseName(r.title)
        val ext = FileNames.extension(r.title)
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
                val name = FileNames.renamedBase(edit.text.toString(), base) ?: return@setPositiveButton
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
        private val edge get() = DragSelect.EDGE_DP * resources.displayMetrics.density

        private val scroller = object : Runnable {
            override fun run() {
                if (!active) { scrolling = false; return }
                val dy = DragSelect.autoScroll(y, list.height, edge, DragSelect.MAX_SPEED_DP * resources.displayMetrics.density)
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
            lift(pos)
        }

        // ---- 끌고 가는 동안 손가락을 따라다니는 떠 있는 썸네일 ----

        /** 손가락을 따라다니는 떠 있는 썸네일 (끌기 시작할 때 만들고 손을 떼면 없앤다) */
        private var ghost: android.view.View? = null
        /** 꾹 누른 줄: 떠오른 동안 자리에 흐리게 남는다 */
        private var lifted: android.view.View? = null
        /** 꾹 누른 줄의 모습 (누른 순간 찍어 둔다) */
        private var ghostBmp: android.graphics.Bitmap? = null

        /** 꾹 누른 줄 [pos]의 모습을 찍어 둔다 (손가락이 움직이기 시작하면 [showGhost]가 이것을 띄운다) */
        private fun lift(pos: Int) {
            removeGhost(animate = false)
            val v = list.findViewHolderForAdapterPosition(pos)?.itemView ?: return
            if (v.width <= 0 || v.height <= 0) return
            ghostBmp = android.graphics.Bitmap.createBitmap(v.width, v.height, android.graphics.Bitmap.Config.ARGB_8888)
                .also { v.draw(android.graphics.Canvas(it)) }
            lifted = v
        }

        /** 찍어 둔 줄의 모습을 문서 고르는 화면 위에 띄운다. 여러 개를 골랐으면 개수 표시를 붙인다 */
        private fun showGhost() {
            if (ghost != null) return
            val bmp = ghostBmp ?: return
            val d = resources.displayMetrics.density
            val box = android.widget.FrameLayout(this@MainActivity)
            box.addView(
                android.widget.ImageView(this@MainActivity).apply { setImageBitmap(bmp) },
                android.widget.FrameLayout.LayoutParams(bmp.width, bmp.height),
            )
            val n = selected.size
            if (n > 1) {
                box.addView(
                    TextView(this@MainActivity).apply {
                        text = "${n}개"
                        setTextColor(Color.WHITE)
                        textSize = 13f
                        gravity = android.view.Gravity.CENTER
                        setPadding((10 * d).toInt(), (3 * d).toInt(), (10 * d).toInt(), (3 * d).toInt())
                        background = android.graphics.drawable.GradientDrawable().apply {
                            cornerRadius = 14 * d
                            setColor(Color.parseColor("#E53935"))
                        }
                    },
                    android.widget.FrameLayout.LayoutParams(
                        android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                        android.view.Gravity.TOP or android.view.Gravity.END,
                    ).apply { setMargins(0, (4 * d).toInt(), (4 * d).toInt(), 0) },
                )
            }
            box.alpha = 0f
            box.elevation = 16 * d
            (window.decorView as android.view.ViewGroup).addView(box, android.widget.FrameLayout.LayoutParams(bmp.width, bmp.height))
            box.animate().alpha(0.92f).scaleX(1.05f).scaleY(1.05f).setDuration(120).start()
            lifted?.alpha = 0.3f
            ghost = box
            moveGhost()
        }

        /** 떠 있는 썸네일의 가운데를 지금 손가락 자리로 */
        private fun moveGhost() {
            val g = ghost ?: return
            val bmp = ghostBmp ?: return
            val listLoc = IntArray(2)
            val decorLoc = IntArray(2)
            list.getLocationOnScreen(listLoc)
            window.decorView.getLocationOnScreen(decorLoc)
            g.translationX = listLoc[0] + x - bmp.width / 2f - decorLoc[0]
            g.translationY = listLoc[1] + y - bmp.height / 2f - decorLoc[1]
        }

        private fun removeGhost(animate: Boolean = true) {
            lifted?.alpha = 1f
            lifted = null
            val g = ghost
            ghost = null
            ghostBmp = null
            g ?: return
            val decor = window.decorView as android.view.ViewGroup
            if (animate) g.animate().alpha(0f).scaleX(1f).scaleY(1f).setDuration(120).withEndAction { decor.removeView(g) }.start()
            else decor.removeView(g)
        }

        /** 손가락이 있는 폴더 줄: 이 줄에서 손을 떼면 고른 문서를 그 폴더로 옮긴다 (없으면 -1) */
        private fun setDrop(pos: Int) {
            val old = dropPos
            if (old == pos) return
            dropPos = pos
            if (old >= 0) adapter.notifyItemChanged(old, SELECTION)
            if (pos >= 0) {
                adapter.notifyItemChanged(pos, SELECTION)
                selectCount.text = "'${adapter.items[pos].title}' 폴더로 옮기기 (손을 떼면 이동)"
            } else onSelectionChanged()
        }

        private fun update() {
            val child = list.findChildViewUnder(x, y) ?: return
            val pos = list.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION) return
            // 폴더 위로 끌어 가면 고르기를 멈추고 그 폴더를 놓을 자리로 보여 준다
            if (adapter.items.getOrNull(pos)?.let { canDropOn(it) } == true) {
                setDrop(pos)
                last = pos
                return
            }
            setDrop(-1)
            if (pos == last) return
            last = pos
            val items = adapter.items
            val now = DragSelect.select(base, anchor, pos) { items.getOrNull(it)?.key }
            selected.clear()
            selected.addAll(now)
            onSelectionChanged()
        }

        override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
            if (!active) return false
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> return true
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    active = false
                    setDrop(-1)
                    removeGhost()
                }
            }
            return false
        }

        override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {
            when (e.actionMasked) {
                MotionEvent.ACTION_MOVE -> {
                    x = e.x
                    y = e.y
                    update()
                    showGhost()
                    moveGhost()
                    if (!scrolling && DragSelect.inEdge(y, list.height, edge)) {
                        scrolling = true
                        list.postOnAnimation(scroller)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    active = false
                    removeGhost()
                    val target = if (e.actionMasked == MotionEvent.ACTION_UP) adapter.items.getOrNull(dropPos) else null
                    setDrop(-1)
                    if (target != null) dropSelectedOn(target)
                }
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
    }

    /** 끌어다 놓은 문서를 받을 수 있는 줄: 폴더 탭의 폴더·저장소, 즐겨찾기 탭의 즐겨찾기 폴더 */
    private fun canDropOn(r: Row) = when (tab) {
        Tab.FOLDER -> r.dir != null
        Tab.FAVORITE -> r.vfolder != null && r.key !in selected
        else -> false
    }

    /** 끌던 줄: [dragSelect]가 폴더 위에 있다고 본 줄의 번호 (아니면 -1) */
    private var dropPos = -1

    /** 꾹 눌러 끌어 폴더 위에서 뗐다: 고른 문서(와 즐겨찾기 폴더)를 [target] 폴더로 옮긴다 */
    private fun dropSelectedOn(target: Row) {
        val sel = selectedRows()
        if (sel.isEmpty()) return
        if (tab == Tab.FAVORITE) {
            val folder = target.vfolder ?: return
            val all = FavFolders.list(this)
            val inside = sel.mapNotNull { it.vfolder?.id }.flatMap { FavFolders.subtree(all, it) }.toSet()
            if (folder.id in inside) {
                android.widget.Toast.makeText(this, "폴더를 그 안의 폴더로는 옮길 수 없습니다", android.widget.Toast.LENGTH_SHORT).show()
                onSelectionChanged()
                return
            }
            for (r in sel) {
                r.docUri?.let { Recents.setFolder(this, it, folder.id) }
                r.vfolder?.let { f -> FavFolders.get(this, f.id)?.let { FavFolders.update(this, it.copy(parent = folder.id)) } }
            }
            finishAction("${sel.size}개를 '${folder.name}'(으)로 옮겼습니다")
            return
        }
        val dest = target.dir ?: return
        val files = sel.mapNotNull { it.docUri }.map(Uri::parse).filter { it.scheme == "file" }.map { File(it.path!!) }
        if (files.isEmpty()) {
            android.widget.Toast.makeText(this, "이 문서는 폴더로 옮길 수 없습니다", android.widget.Toast.LENGTH_SHORT).show()
            onSelectionChanged()
            return
        }
        lifecycleScope.launch {
            val failed = withContext(Dispatchers.IO) {
                files.count { f -> runCatching { FileOps.move(this@MainActivity, f, dest) }.isFailure }
            }
            finishAction(if (failed == 0) "${files.size}개를 '${target.title}'(으)로 옮겼습니다" else "${failed}개는 옮기지 못했습니다")
        }
    }

    // ================= 직접 정한 순서 (즐겨찾기) =================

    /** 손잡이를 끌어서 문서·폴더의 자리를 바꾼다. 폴더끼리, 문서끼리만 바꿀 수 있다 (폴더는 늘 문서 앞) */
    private val reorder = ItemTouchHelper(object : ItemTouchHelper.SimpleCallback(
        ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0,
    ) {
        override fun isLongPressDragEnabled() = false
        override fun onMove(rv: RecyclerView, from: RecyclerView.ViewHolder, to: RecyclerView.ViewHolder): Boolean {
            val a = from.bindingAdapterPosition
            val b = to.bindingAdapterPosition
            return a != RecyclerView.NO_POSITION && b != RecyclerView.NO_POSITION && adapter.move(a, b)
        }
        override fun onSwiped(holder: RecyclerView.ViewHolder, direction: Int) {}
        override fun clearView(rv: RecyclerView, holder: RecyclerView.ViewHolder) {
            super.clearView(rv, holder)
            saveManualOrder()
        }
    })

    /** 끌어서 바꾼 차례를 적어 둔다 (찾는 글로 걸러 보는 중에는 끌 수 없으므로 목록 전체의 차례) */
    private fun saveManualOrder() {
        if (!manualOn() || query.isNotBlank()) return
        val shown = adapter.items.mapNotNull { it.key }
        rows = adapter.items.filter { it.kind != Kind.HEADER }
        FavOrder.save(this, ListSort.mergeOrder(FavOrder.load(this), shown))
    }

    /** 손잡이를 보일 때: 직접 정한 순서로 보는 중이고, 고르는 중이나 찾는 중이 아닐 때 */
    private fun canReorder() = manualOn() && !selecting && query.isBlank() && tagFilters.isEmpty()

    // ================= 목록 어댑터 =================

    private inner class RowAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        var items: List<Row> = emptyList()
            private set

        @Suppress("NotifyDataSetChanged")
        fun submit(list: List<Row>) {
            items = list
            notifyDataSetChanged()
        }

        /** [from]번째 줄을 [to]번째로 옮긴다 (폴더와 문서는 서로 넘나들 수 없다) */
        fun move(from: Int, to: Int): Boolean {
            if (from !in items.indices || to !in items.indices || items[from].isFolder != items[to].isFolder) return false
            // 폴더 머리글을 넘나들 수 없다 (같은 폴더 안에서만)
            if (items[from].kind == Kind.HEADER || items[to].kind == Kind.HEADER) return false
            items = items.toMutableList().also { it.add(to, it.removeAt(from)) }
            notifyItemMoved(from, to)
            return true
        }

        override fun getItemCount() = items.size
        override fun getItemViewType(position: Int) = if (items[position].kind == Kind.HEADER) 2 else if (grid) 1 else 0
        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            if (viewType == 2) HeaderHolder(layoutInflater.inflate(R.layout.item_group, parent, false))
            else RowHolder(layoutInflater.inflate(if (viewType == 1) R.layout.item_grid else R.layout.item_recent, parent, false), viewType == 1)
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (holder) {
                is HeaderHolder -> holder.bind(items[position])
                is RowHolder -> holder.bind(items[position])
            }
        }
        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int, payloads: MutableList<Any>) {
            // 선택만 바뀌었으면 체크 표시만 (썸네일을 다시 읽지 않게)
            if (holder is RowHolder && payloads.isNotEmpty() && payloads.all { it === SELECTION }) holder.bindSelection(items[position])
            else onBindViewHolder(holder, position)
        }
        override fun onViewRecycled(holder: RecyclerView.ViewHolder) {
            if (holder is RowHolder) holder.unbind()
        }
    }

    /**
     * 폴더 머리글 아래 줄들의 가지선(├ └): 줄마다 따로 그리면 줄 사이 여백에서 끊기므로,
     * 목록이 줄 위 칸 전체에 걸쳐 세로선을 한꺼번에 그린다 (마지막 줄은 가운데서 꺾여 가로선으로)
     */
    private inner class TreeDecoration : RecyclerView.ItemDecoration() {
        private val d = resources.displayMetrics.density
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 1.5f * d
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = com.google.android.material.color.MaterialColors.getColor(
                list, com.google.android.material.R.attr.colorOutline, Color.GRAY
            )
        }

        override fun onDraw(c: android.graphics.Canvas, parent: RecyclerView, state: RecyclerView.State) {
            if (grid) return
            for (i in 0 until parent.childCount) {
                val child = parent.getChildAt(i)
                val pos = parent.getChildAdapterPosition(child)
                val row = adapter.items.getOrNull(pos) ?: continue
                if (row.tree == 0 || row.kind == Kind.HEADER) continue
                val tv = child.findViewById<View>(R.id.tree) ?: continue
                if (tv.visibility != View.VISIBLE) continue
                val x = child.left + child.translationX + tv.left + 24 * d
                val y0 = child.top + child.translationY
                // 머리글 바로 아래 첫 줄은 머리글 칸 아래 여백(2dp)까지 이어서 그린다
                val top = if (adapter.items.getOrNull(pos - 1)?.kind == Kind.HEADER) y0 - 2 * d else y0
                val mid = y0 + child.height / 2f
                c.drawLine(x, top, x, if (row.tree == 2) mid else y0 + child.height, paint)
                c.drawLine(x, mid, child.left + child.translationX + tv.right - 2 * d, mid, paint)
            }
        }
    }

    /** 태그로 모아 볼 때 문서들 위의 폴더 머리글 한 줄 */
    private inner class HeaderHolder(v: View) : RecyclerView.ViewHolder(v) {
        private val chip: View = v.findViewById(R.id.groupChip)
        private val icon: ImageView = v.findViewById(R.id.groupIcon)
        private val name: TextView = v.findViewById(R.id.groupName)
        private val count: TextView = v.findViewById(R.id.groupCount)
        private val basePad = v.paddingLeft

        fun bind(r: Row) {
            // 폴더 계단: 깊은 폴더일수록 오른쪽으로
            itemView.setPadding(basePad + dp(STEP_DP) * r.indent, itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)
            icon.setImageDrawable(r.folder)
            name.text = r.title
            count.text = r.sub
            chip.contentDescription = "${r.title} 폴더, 문서 ${r.sub}"
            chip.setOnClickListener { if (!selecting) r.onClick() }
        }
    }

    private inner class RowHolder(v: View, private val isGrid: Boolean) : RecyclerView.ViewHolder(v) {
        private val thumb: ThumbView = v.findViewById(R.id.thumb)
        private val name: TextView = v.findViewById(R.id.name)
        private val sub: TextView = v.findViewById(R.id.date)
        private val badge: TextView = v.findViewById(R.id.badge)
        private val star: ImageView = v.findViewById(R.id.star)
        private val check: ImageView = v.findViewById(R.id.check)
        private val drag: ImageView = v.findViewById(R.id.drag)
        /** 목록 보기에서만 있다 (큰 아이콘 보기에는 없음) */
        private val tree: View? = v.findViewById(R.id.tree)
        private var job: Job? = null
        private var source: Thumbs.Source? = null
        private val basePad = v.paddingLeft

        init {
            if (isGrid) thumb.ratio = 1.25f
            // 손잡이에 손가락을 대면 바로 끌기 시작 (꾹 누를 필요 없이)
            drag.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_DOWN) reorder.startDrag(this)
                false
            }
        }

        fun bind(r: Row) {
            // 폴더 계단 아래 줄: 맨 아래 폴더만큼 들여 쓴다 (큰 아이콘 보기는 그대로)
            if (!isGrid) itemView.setPadding(basePad + dp(STEP_DP) * r.indent, itemView.paddingTop, itemView.paddingRight, itemView.paddingBottom)
            // 태그에 안 맞는 문서는 흐리게
            itemView.alpha = if (r.dim) DIM_ALPHA else 1f
            name.text = r.title
            val s = if (isGrid) r.short else r.sub
            sub.text = s
            sub.isVisible = s.isNotEmpty()
            val color = DocColors.of(r.title)
            val ext = r.title.substringAfterLast('.', "").uppercase()
            badge.isVisible = !r.isFolder && ext.isNotEmpty() && ext.length <= 4
            badge.text = ext
            badge.backgroundTintList = ColorStateList.valueOf(color)
            tree?.isVisible = r.tree != 0
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
            drag.isVisible = r.selectable && canReorder()
            check.setImageResource(if (on) R.drawable.ic_check_circle else R.drawable.ic_circle_outline)
            // 끌던 문서를 놓을 폴더도 선택한 것처럼 짙게
            itemView.isActivated = (selecting && on) || (dropPos >= 0 && bindingAdapterPosition == dropPos)
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
        /** 폴더 머리글 한 줄이 차지하는 높이 (dp, 빈 폴더의 안내 글을 그 아래로 내리는 데 쓴다) */
        private const val CHAIN_ROW_DP = 56

        /** 태그에 안 맞는 문서의 투명도 */
        private const val DIM_ALPHA = 0.38f

        /** 폴더 계단에서 한 칸 들여 쓰는 너비 (dp) */
        private const val STEP_DP = 18

        /** 경로 줄에 폴더 그림으로 보일 폴더 수 (지금 폴더 + 그 위 두 폴더) */
        private const val CRUMBS_SHOWN = 3

        /** 자동 저장본은 마지막 저장 뒤 이만큼(30일) 지나면 지운다 */
        private const val DRAFT_KEEP_MS = 30L * 24 * 3600 * 1000
        /** 이번 실행에서 복구할 필기를 이미 찾아봤는지 */
        private var draftsChecked = false
        /** 뷰어의 ＋ 버튼으로 띄웠을 때: 고른 문서를 새 탭으로 열고, 뒤로 가면 뷰어로 돌아간다 */
        const val EXTRA_PICK = "pick"
        private val FOLDER_COLOR = Color.parseColor("#E8A317")
        private const val MANUAL_LABEL = "직접 정한 순서"
        private const val MANUAL_ID = 200
        private const val FOLDERS_UP_ID = 300
        private const val FOLDERS_DOWN_ID = 301
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
