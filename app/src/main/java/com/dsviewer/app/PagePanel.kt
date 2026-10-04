package com.dsviewer.app

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.util.LruCache
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.DefaultItemAnimator
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 쪽 미리보기 목록. 두 가지로 쓴다 (삼성 노트 참고).
 *  - 페이지 관리 창 ([overview] = false): 문서 왼쪽에 붙는다. 가로 화면 두 줄, 세로 화면 한 줄.
 *    미리보기를 누르면 문서가 그 쪽으로 가고 창은 그대로 둔다.
 *  - 쪽 한눈에 보기 ([overview] = true): 문서 화면을 덮는다. 돋보기 −/＋로 미리보기 크기를 바꾸고,
 *    전체 / 필기된 쪽 / 북마크한 쪽을 아이콘으로 골라 본다. 미리보기를 누르면 그 쪽으로 가며 닫힌다.
 * 둘 다: 오른쪽 위 리본 = 북마크, ⋮ = 쪽 메뉴, 돋보기 = 글자 찾기(찾은 쪽만 보이고 문서에 강조),
 * 꾹 누르기 = 쪽 선택 (누른 채 끌면 범위 선택, 선택 중에는 톡 눌러 넣고 빼기).
 * 고른 쪽은 한꺼번에 돌리기·저장·지우기(쪽 한눈에 보기는 위 선택 줄, 페이지 관리 창은 아래 막대), 고른 쪽을 꾹 눌러 끌면 함께 옮긴다.
 * PDF 쪽 그림은 한 번 그려 두고, 필기는 미리보기를 그릴 때마다 얹어 필기가 바뀌면 바로 따라간다.
 */
class PagePanel(
    private val panel: LinearLayout,
    private val scope: CoroutineScope,
    private val overview: Boolean,
    private val host: Host,
) {
    interface Host {
        /** 미리보기를 누름. [hit]는 찾은 글자가 있으면 그 첫 자리 (쪽 좌표), [query]는 찾던 말 */
        fun pick(page: Int, hit: RectF?, query: String?)
        fun menu(page: Int, anchor: View)
        /** 쪽 순서를 [order]로 (새 k번째 = 원래 order[k]번째). 바로 할 수 없으면 false */
        fun reorder(order: List<Int>): Boolean
        fun deletePages(pages: List<Int>, onDone: () -> Unit)
        fun rotatePages(pages: List<Int>)
        /** 좌우([horizontal]) 또는 상하로 뒤집기 (쪽 한눈에 보기에만 단추가 있다) */
        fun flipPages(pages: List<Int>, horizontal: Boolean)
        fun savePages(pages: List<Int>, asPdf: Boolean)
        /** 지금 문서의 글자 찾기 (처음 부르면 글자를 꺼내기 시작) */
        fun search(): DocSearch?
        /** 문서 화면에 찾은 자리를 강조 (빈 것이면 지움) */
        fun showHits(hits: Map<Int, List<RectF>>)
        /** 필기 검색에 쓸 한국어 필기 데이터를 받을지 묻고 받는다 */
        fun downloadHandwriting() {}
        /** 쪽 한눈에 보기가 닫힘 */
        fun closed() {}
    }

    private enum class Filter(val icon: Int, val desc: String) {
        ALL(R.drawable.ic_grid_view, "모든 쪽"),
        INKED(R.drawable.ic_pages_inked, "필기된 쪽만"),
        MARKED(R.drawable.ic_bookmark, "북마크한 쪽만"),
    }

    private val ctx: Context = panel.context
    private val density = ctx.resources.displayMetrics.density
    private fun px(v: Float) = (v * density).roundToInt()
    private val prefs = ctx.getSharedPreferences("tools", Context.MODE_PRIVATE)

    private val accent = 0xFF3E82F7.toInt()
    private val onSurface = MaterialColors.getColor(panel, com.google.android.material.R.attr.colorOnSurface)
    private val onSurfaceVariant = MaterialColors.getColor(panel, com.google.android.material.R.attr.colorOnSurfaceVariant)
    private val outline = MaterialColors.getColor(panel, com.google.android.material.R.attr.colorOutlineVariant)
    private val selectedFill = MaterialColors.getColor(panel, com.google.android.material.R.attr.colorSecondaryContainer)

    // ---- 머리 줄: 보통 / 찾기 / 선택 세 가지 중 하나만 보인다 ----
    private val normalBar = row()
    private val searchBar = row()
    private val selectBar = row()
    private val title = TextView(ctx).apply {
        textSize = if (overview) 17f else 20f
        setTextColor(onSurface)
        typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
    }
    private val filterButtons = HashMap<Filter, ImageButton>()
    private val searchField = EditText(ctx).apply {
        hint = "글자 찾기"
        isSingleLine = true
        textSize = 15f
        imeOptions = EditorInfo.IME_ACTION_SEARCH
        background = null
        setPadding(px(4f), 0, px(4f), 0)
    }
    private val selectCount = TextView(ctx).apply {
        textSize = 16f
        setTextColor(onSurface)
        typeface = Typeface.DEFAULT_BOLD
        isSingleLine = true
    }
    /** 머리 줄 아래 한 줄 알림 (찾은 결과, 선택 도움말) */
    private val status = TextView(ctx).apply {
        textSize = 12f
        setTextColor(onSurfaceVariant)
        setPadding(px(14f), 0, px(10f), px(4f))
        visibility = View.GONE
        // 필기 읽을 데이터가 없다는 안내를 누르면 받는다
        setOnClickListener { if (search?.handwriting?.state == HandwritingIndex.State.NEEDS_DATA) host.downloadHandwriting() }
    }
    private val grid = RecyclerView(ctx).apply {
        clipToPadding = false
        setPadding(px(6f), px(2f), px(6f), px(12f))
        isVerticalScrollBarEnabled = true
        // 쪽을 끌어 옮길 때 다른 쪽이 비켜나는 움직임만 (내용 바뀜은 깜빡이지 않게)
        itemAnimator = DefaultItemAnimator().apply { supportsChangeAnimations = false }
    }
    private val empty = TextView(ctx).apply {
        gravity = Gravity.CENTER
        textSize = 13f
        setTextColor(onSurfaceVariant)
        setPadding(px(12f), px(32f), px(12f), px(32f))
        visibility = View.GONE
    }
    /** 선택 중에 아래에 뜨는 막대: 돌리기 · 저장 · 지우기 */
    private val actionBar = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER
        setBackgroundColor(MaterialColors.getColor(panel, com.google.android.material.R.attr.colorSurfaceContainerHigh))
        visibility = View.GONE
    }
    private val actionButtons = ArrayList<View>()

    private val layout = GridLayoutManager(ctx, 2)
    private val adapter = ThumbAdapter()
    private val touchHelper = ItemTouchHelper(DragCallback())

    private var doc: PdfDoc? = null
    private var ink: InkDocument? = null
    private var current = 0
    private var filter = Filter.ALL
    private var pages: MutableList<Int> = mutableListOf()
    /** 미리보기 그림 너비 (px, 테두리 안쪽) */
    private var thumbW = 0
    /** 쪽 한눈에 보기의 열 수 (돋보기 −/＋) */
    private var cols = 0

    // ---- 글자 찾기 ----
    private var searchOpen = false
    private var query = ""
    private var hits: Map<Int, List<RectF>> = emptyMap()
    private var search: DocSearch? = null
    private val runSearch = Runnable { updateSearch() }

    // ---- 선택 ----
    private var selecting = false
    /**
     * 고른 쪽. 쪽 번호가 아니라 쪽 자체([PageKey])로 기억해서 쪽 순서를 바꾸거나 실행 취소해도 고른 쪽을 따라간다.
     * 돌린 쪽은 획 목록이 새것으로 바뀌므로 [aliases]로 앞뒤 목록을 서로 잇는다 ([pageReplaced])
     */
    private val selKeys = HashSet<PageKey>()
    private val aliases = HashMap<PageKey, PageKey>()

    /**
     * PDF 쪽 그림 (필기 없이). 열쇠도 쪽 자체라서 쪽을 넣고 빼거나 옮겨도 다시 그리지 않는다
     * (돌린 쪽은 새 열쇠라 새로 그린다). 다른 문서가 되면 비운다
     */
    private val cache = object : LruCache<PageKey, Bitmap>((Runtime.getRuntime().maxMemory() / 12).toInt()) {
        override fun sizeOf(key: PageKey, value: Bitmap) = value.byteCount
    }
    private val jobs = HashMap<Int, Job>()
    private val inkPaint = inkPaint()
    private val refreshInk = Runnable { forEachHolder { it.thumb.invalidate(); it.bindMark() } }

    val isShowing get() = panel.visibility == View.VISIBLE

    init {
        panel.orientation = LinearLayout.VERTICAL
        buildNormalBar()
        buildSearchBar()
        buildSelectBar()
        val header = FrameLayout(ctx).apply {
            for (b in listOf(normalBar, searchBar, selectBar)) addView(b, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            if (overview) setBackgroundColor(MaterialColors.getColor(panel, com.google.android.material.R.attr.colorSurfaceContainer))
        }
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(if (overview) 52f else 56f)))
        panel.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val body = FrameLayout(ctx).apply {
            addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            addView(empty, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP))
        }
        panel.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        buildActionBar()
        panel.addView(actionBar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(48f)))
        grid.layoutManager = layout
        grid.adapter = adapter
        touchHelper.attachToRecyclerView(grid)
        // 쪽 한눈에 보기는 창 너비가 정해진 뒤에 열 수와 미리보기 크기를 정한다
        grid.addOnLayoutChangeListener { _, l, _, r, _, ol, _, or, _ -> if (r - l != or - ol) grid.post { fitColumns() } }
        if (overview) filter = Filter.entries.firstOrNull { it.name == prefs.getString("overviewFilter", null) } ?: Filter.ALL
        showBar(normalBar)
        updateFilterButtons()
    }

    // ================= 머리 줄 만들기 =================

    private fun row() = LinearLayout(ctx).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(px(if (overview) 16f else 14f), 0, px(4f), 0)
    }

    private fun iconButton(res: Int, desc: String, onClick: (View) -> Unit) = ImageButton(ctx).apply {
        setImageResource(res)
        imageTintList = ColorStateList.valueOf(onSurface)
        background = themeDrawable(android.R.attr.selectableItemBackgroundBorderless)
        scaleType = ImageView.ScaleType.CENTER
        contentDescription = desc
        tooltipText = desc
        setOnClickListener(onClick)
    }

    private fun LinearLayout.addIcon(b: View, size: Float = 40f) = addView(b, LinearLayout.LayoutParams(px(size), px(size)))

    private fun buildNormalBar() {
        normalBar.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        if (overview) {
            // 보기: 전체 / 필기된 쪽 / 북마크한 쪽
            for (f in Filter.entries) {
                val b = iconButton(f.icon, f.desc) { setFilter(f) }
                filterButtons[f] = b
                normalBar.addIcon(b)
            }
            normalBar.addView(View(ctx).apply { setBackgroundColor(outline) }, LinearLayout.LayoutParams(px(1f), px(24f)).apply {
                setMargins(px(8f), 0, px(8f), 0)
            })
            normalBar.addIcon(iconButton(R.drawable.ic_zoom_out, "미리보기 작게") { zoom(+1) })
            normalBar.addIcon(iconButton(R.drawable.ic_zoom_in, "미리보기 크게") { zoom(-1) })
            normalBar.addView(selectButton())
            normalBar.addIcon(iconButton(R.drawable.ic_search, "글자 찾기") { openSearch(null) })
            normalBar.addIcon(iconButton(R.drawable.ic_close, "닫기") { hide() }, 44f)
        } else {
            // 페이지 관리 창은 폭이 좁으니 '선택' 단추 없이 꾹 눌러 선택
            normalBar.addIcon(iconButton(R.drawable.ic_search, "글자 찾기") { openSearch(null) }, 36f)
            val mark = iconButton(R.drawable.ic_bookmark_border, "북마크한 쪽만 보기") {
                setFilter(if (filter == Filter.MARKED) Filter.ALL else Filter.MARKED)
            }
            filterButtons[Filter.MARKED] = mark
            normalBar.addIcon(mark, 36f)
        }
    }

    /** 글자 단추 '선택' (쪽 한눈에 보기만. 삼성 노트처럼) */
    private fun selectButton() = TextView(ctx).apply {
        text = "선택"
        textSize = 15f
        typeface = Typeface.DEFAULT_BOLD
        setTextColor(onSurface)
        gravity = Gravity.CENTER
        setPadding(px(8f), px(8f), px(8f), px(8f))
        background = themeDrawable(android.R.attr.selectableItemBackground)
        contentDescription = "쪽 선택"
        setOnClickListener { startSelection(null) }
    }

    private fun buildSearchBar() {
        searchBar.setPadding(px(2f), 0, px(2f), 0)
        searchBar.addIcon(iconButton(R.drawable.ic_back, "찾기 끝내기") { closeSearch() }, 40f)
        searchBar.addView(searchField, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        searchBar.addIcon(iconButton(R.drawable.ic_close, "지우기") { searchField.setText("") }, 36f)
        searchField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) {
                if (!searchOpen) return
                query = s?.toString() ?: ""
                panel.removeCallbacks(runSearch)
                panel.postDelayed(runSearch, 250)
            }
        })
        searchField.setOnEditorActionListener { _, _, _ ->
            panel.removeCallbacks(runSearch)
            updateSearch()
            hideKeyboard()
            true
        }
    }

    private fun buildSelectBar() {
        selectBar.setPadding(px(2f), 0, px(4f), 0)
        selectBar.addIcon(iconButton(R.drawable.ic_close, "선택 끝내기") { endSelection() }, 40f)
        selectBar.addView(selectCount, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        selectBar.addIcon(iconButton(R.drawable.ic_select_all, "모두 선택") {
            if (pages.all { isSelected(it) }) clearSelected() else pages.forEach { select(it) }
            onSelectionChanged()
        }, 40f)
    }

    /**
     * 고른 쪽에 하는 일: 돌리기 · (쪽 한눈에 보기만) 좌우·상하 반전 · 저장 · 지우기.
     * 쪽 한눈에 보기는 위 선택 줄의 '모두 선택' 앞에, 폭이 좁은 페이지 관리 창은 아래 막대에. 둘 다 아이콘만
     */
    private fun buildActionBar() {
        // 아이콘만 (이름은 꾹 누르면 뜨는 풍선 도움말로)
        fun action(res: Int, label: String, dropDown: Boolean = false, onClick: (View) -> Unit) = ImageButton(ctx).apply {
            // 펼쳐 고르는 단추(저장)는 오른쪽 아래에 작은 삼각형
            if (dropDown) setImageDrawable(android.graphics.drawable.LayerDrawable(arrayOf(
                ctx.getDrawable(res)!!, ctx.getDrawable(R.drawable.ic_corner_more)!!,
            )))
            else setImageResource(res)
            imageTintList = ColorStateList.valueOf(onSurface)
            scaleType = ImageView.ScaleType.CENTER
            background = themeDrawable(
                if (overview) android.R.attr.selectableItemBackgroundBorderless else android.R.attr.selectableItemBackground
            )
            contentDescription = label
            tooltipText = label
            setOnClickListener(onClick)
            actionButtons.add(this)
        }
        val lp = {
            if (overview) LinearLayout.LayoutParams(px(44f), px(44f))
            else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f)
        }
        // 쪽 한눈에 보기: 선택 줄의 '모두 선택' 바로 앞에 차례로 끼운다
        val target = if (overview) selectBar else actionBar
        fun add(v: View) = if (overview) target.addView(v, target.childCount - 1, lp()) else target.addView(v, lp())
        add(action(R.drawable.ic_rotate_page, "돌리기") {
            host.rotatePages(selectedPages())
        })
        // 좌우·상하 반전은 쪽 한눈에 보기에만 (페이지 관리 창은 폭이 좁아서)
        if (overview) {
            add(action(R.drawable.ic_flip_h, "좌우 반전") { host.flipPages(selectedPages(), horizontal = true) })
            add(action(R.drawable.ic_flip_v, "상하 반전") { host.flipPages(selectedPages(), horizontal = false) })
        }
        add(action(R.drawable.ic_save, "저장", dropDown = true) { v ->
            val pop = PopupMenu(ctx, v)
            pop.menu.add(0, 1, 0, "이미지로 저장").setIcon(R.drawable.ic_image)
            pop.menu.add(0, 2, 1, "파일(PDF)로 저장").setIcon(R.drawable.ic_pdf)
            pop.setForceShowIcon(true)
            pop.setOnMenuItemClickListener { item ->
                host.savePages(selectedPages(), asPdf = item.itemId == 2)
                endSelection()
                true
            }
            pop.show()
        })
        add(action(R.drawable.ic_delete, "지우기") {
            host.deletePages(selectedPages()) { endSelection() }
        })
        if (overview) target.addView(View(ctx), target.childCount - 1, LinearLayout.LayoutParams(px(8f), 1))
    }

    private fun showBar(bar: View) {
        for (b in listOf(normalBar, searchBar, selectBar)) b.visibility = if (b === bar) View.VISIBLE else View.GONE
    }

    private fun themeDrawable(attr: Int) = android.util.TypedValue().let { tv ->
        ctx.theme.resolveAttribute(attr, tv, true)
        ctx.getDrawable(tv.resourceId)
    }

    // ================= 열고 닫기 · 문서 =================

    fun show(d: PdfDoc, inkDoc: InkDocument, currentPage: Int) {
        panel.visibility = View.VISIBLE
        if (!overview) fitWidth()
        setDocument(d, inkDoc)
        setCurrent(currentPage, scroll = false)
        val pos = pages.indexOf(current)
        if (pos >= 0) layout.scrollToPositionWithOffset(pos, px(if (overview) 120f else 40f))
        // 쪽 한눈에 보기: 창이 그려져 너비가 정해진 뒤에 열 수를 맞춘다
        if (overview) grid.post {
            fitColumns()
            val p = pages.indexOf(current)
            if (p >= 0) layout.scrollToPositionWithOffset(p, px(120f))
        }
    }

    fun hide() {
        if (!isShowing) return
        endSelection()
        closeSearch()
        panel.visibility = View.GONE
        clear()
        if (overview) host.closed()
    }

    /** 탭을 닫거나 바꾸는 등 문서가 없어질 때 */
    fun clear() {
        panel.removeCallbacks(refreshInk)
        panel.removeCallbacks(runSearch)
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        detachSearch()
        doc = null
        ink = null
        pages = mutableListOf()
        clearSelected()
        adapter.notifyDataSetChanged()
        cache.evictAll()
    }

    /** 뒤로 가기: 선택 → 찾기 → (쪽 한눈에 보기면) 닫기 순으로 하나씩 끝낸다. 한 게 있으면 true */
    fun back(): Boolean {
        if (!isShowing) return false
        when {
            selecting -> endSelection()
            searchOpen -> closeSearch()
            overview -> hide()
            else -> return false
        }
        return true
    }

    /** 문서(쪽 구성)가 바뀌었으면 미리보기를 새로. 같은 문서면 그대로 */
    fun setDocument(d: PdfDoc, inkDoc: InkDocument) {
        if (!isShowing) return
        if (d === doc && inkDoc === ink) return
        // 쪽 번호가 바뀌었을 수 있으니 그리던 것은 그만둔다. 그려 둔 그림과 선택은 쪽을 따라가므로 그대로
        jobs.values.forEach { it.cancel() }
        jobs.clear()
        if (inkDoc !== ink) {
            cache.evictAll()
            clearSelected()
        }
        doc = d
        ink = inkDoc
        // 쪽이 바뀌면 글자도 새로 꺼낸다
        if (searchOpen) attachSearch()
        refreshList()
        if (selecting) onSelectionChanged()
    }

    /** 페이지 관리 창: 가로 화면은 두 줄, 세로 화면은 한 줄. 창 너비도 그에 맞춘다 */
    fun fitWidth() {
        if (overview) return
        val land = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        val w = px(if (land) 250f else 160f)
        panel.layoutParams = panel.layoutParams.apply { width = w }
        title.textSize = if (land) 20f else 16f
        applyColumns(if (land) 2 else 1, w)
    }

    /** 쪽 한눈에 보기: 지금 너비와 열 수로 미리보기 크기를 정한다 */
    private fun fitColumns() {
        if (!overview || grid.width == 0) return
        if (cols == 0) cols = prefs.getInt("overviewCols", 0).takeIf { it > 0 } ?: defaultColumns()
        applyColumns(cols, grid.width)
    }

    /** 한 화면에 20쪽 안팎이 들어가는 가장 적은 열 */
    private fun defaultColumns(): Int {
        val d = doc ?: return 5
        val r = d.sizes.map { it.height / it.width }.sorted().let { it[it.size / 2] }
        val usableW = (grid.width - grid.paddingLeft - grid.paddingRight).toFloat()
        val usableH = grid.height.toFloat().coerceAtLeast(1f)
        for (c in 3..10) {
            val cellW = usableW / c - px(ITEM_PAD) * 2
            if (c * usableH / (cellW * r + px(26f) + px(ITEM_PAD) * 2) >= 20) return c
        }
        return 10
    }

    private fun applyColumns(c: Int, width: Int) {
        val cellW = (width - grid.paddingLeft - grid.paddingRight) / c
        val w = cellW - px(ITEM_PAD * 2) - px(BORDER) * 2
        if (w == thumbW && c == layout.spanCount) return
        layout.spanCount = c
        thumbW = w
        if (isShowing) {
            jobs.values.forEach { it.cancel() }
            jobs.clear()
            cache.evictAll()
            adapter.notifyDataSetChanged()
        }
    }

    /** 돋보기 −/＋: 열을 늘리면 미리보기가 작아진다 */
    private fun zoom(dCols: Int) {
        val c = (cols + dCols).coerceIn(MIN_COLS, MAX_COLS)
        if (c == cols) return
        // 보던 자리를 지키며
        val first = layout.findFirstVisibleItemPosition()
        cols = c
        prefs.edit().putInt("overviewCols", c).apply()
        applyColumns(c, grid.width)
        if (first >= 0) layout.scrollToPositionWithOffset(first, 0)
    }

    /** 문서에서 지금 보는 쪽이 바뀜: 위 쪽 번호와 테두리, 목록이 그 쪽을 보이게 */
    fun setCurrent(page: Int, scroll: Boolean = true) {
        val old = current
        current = page
        updateTitle()
        if (!isShowing) return
        pages.indexOf(old).takeIf { it >= 0 }?.let { adapter.notifyItemChanged(it) }
        val pos = pages.indexOf(page)
        if (pos < 0) return
        adapter.notifyItemChanged(pos)
        if (scroll && !overview) {
            val first = layout.findFirstCompletelyVisibleItemPosition()
            val last = layout.findLastCompletelyVisibleItemPosition()
            if (first == RecyclerView.NO_POSITION || pos < first || pos > last) grid.smoothScrollToPosition(pos)
        }
    }

    private fun updateTitle() {
        val count = doc?.pageCount ?: 0
        title.text = if (overview) SpannableStringBuilder().apply {
            append("쪽 한눈에 보기")
            val start = length
            append("  ${count}쪽")
            setSpan(ForegroundColorSpan(onSurfaceVariant), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.85f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        } else SpannableStringBuilder().apply {
            append("${current + 1}")
            val start = length
            append("/$count")
            setSpan(ForegroundColorSpan(onSurfaceVariant), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(RelativeSizeSpan(0.9f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
    }

    /** 필기나 북마크가 바뀜: 보이는 미리보기를 조금 뒤에 다시 그린다 (획을 긋는 동안 여러 번 불려도 한 번만) */
    fun inkChanged() {
        if (!isShowing) return
        // 북마크를 풀거나 필기를 다 지우면 목록에서 빠진다
        if (filter != Filter.ALL && !searching() && filteredPages() != pages) refreshList()
        panel.removeCallbacks(refreshInk)
        panel.postDelayed(refreshInk, 250)
    }

    private fun searching() = searchOpen && PdfText.normalize(query).isNotEmpty()

    private fun filteredPages(): List<Int> {
        val d = doc ?: return emptyList()
        val inkDoc = ink ?: return emptyList()
        return (0 until d.pageCount).filter {
            when (filter) {
                Filter.ALL -> true
                Filter.INKED -> inkDoc.pages.getOrNull(it)?.isNotEmpty() == true
                Filter.MARKED -> inkDoc.isBookmarked(it)
            }
        }
    }

    private fun refreshList() {
        if (doc == null || ink == null) return
        // 글자를 찾는 중이면 찾은 쪽만, 아니면 보기(필터)대로
        pages = if (searching()) hits.keys.sorted().toMutableList() else filteredPages().toMutableList()
        empty.text = when {
            searching() -> if (search?.ready == false) "찾는 중…" else "'${query.trim()}'을(를) 찾지 못했습니다."
            filter == Filter.MARKED -> "북마크한 쪽이 없습니다.\n미리보기 오른쪽 위 리본을 누르면\n북마크됩니다."
            else -> "아직 필기한 쪽이 없습니다."
        }
        empty.visibility = if (pages.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
        setCurrent(current, scroll = false)
    }

    private fun setFilter(f: Filter) {
        if (filter == f) return
        filter = f
        if (overview) prefs.edit().putString("overviewFilter", f.name).apply()
        updateFilterButtons()
        refreshList()
        grid.scrollToPosition(0)
    }

    private fun updateFilterButtons() {
        for ((f, b) in filterButtons) {
            val on = filter == f
            if (!overview) b.setImageResource(if (on) R.drawable.ic_bookmark else R.drawable.ic_bookmark_border)
            b.imageTintList = ColorStateList.valueOf(if (on) accent else onSurface)
            b.background = if (on && overview) GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(selectedFill)
            } else themeDrawable(android.R.attr.selectableItemBackgroundBorderless)
            if (!overview) {
                b.contentDescription = if (on) "모든 쪽 보기" else "북마크한 쪽만 보기"
                b.tooltipText = b.contentDescription
            }
        }
    }

    // ================= 글자 찾기 =================

    /** 찾기 줄을 연다. [text]가 있으면 그 말로 바로 찾는다 (쪽 한눈에 보기에서 찾던 것을 이어받을 때) */
    fun openSearch(text: String?) {
        if (selecting) endSelection()
        searchOpen = true
        showBar(searchBar)
        attachSearch()
        if (text != null) {
            query = text
            searchField.setText(text)
            searchField.setSelection(text.length)
            updateSearch()
        } else {
            searchField.requestFocus()
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(searchField, 0)
        }
    }

    fun closeSearch() {
        if (!searchOpen) return
        searchOpen = false
        hideKeyboard()
        panel.removeCallbacks(runSearch)
        detachSearch()
        query = ""
        hits = emptyMap()
        searchField.setText("")
        status.visibility = View.GONE
        host.showHits(emptyMap())
        showBar(normalBar)
        refreshList()
    }

    private fun attachSearch() {
        val s = host.search() ?: return
        if (s !== search) search?.onProgress = null
        search = s
        s.onProgress = { if (searchOpen) updateSearch() }
        s.start(doc?.pageCount ?: 0)
    }

    private fun detachSearch() {
        search?.pause()
        search = null
    }

    private fun hideKeyboard() {
        (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(searchField.windowToken, 0)
        searchField.clearFocus()
    }

    private fun updateSearch() {
        if (!searchOpen) return
        val s = search
        hits = s?.find(query, ink) ?: emptyMap()
        host.showHits(hits)
        val count = hits.values.sumOf { it.size }
        val base = when {
            PdfText.normalize(query).isEmpty() -> if (s != null && !s.ready) "글자 읽는 중 ${s.indexed}/${s.pageCount}쪽" else ""
            s == null || s.failed -> "이 문서에서는 글자를 읽지 못했습니다 (넣은 글 상자만 찾음). ${hits.size}쪽에서 ${count}곳"
            !s.ready -> "${hits.size}쪽에서 ${count}곳 찾음 · 글자 읽는 중 ${s.indexed}/${s.pageCount}쪽"
            else -> "${hits.size}쪽에서 ${count}곳 찾음"
        }
        // 필기를 읽는 중이거나 읽을 데이터가 없으면 그 안내를 덧붙인다
        status.text = listOfNotNull(base.takeIf { it.isNotEmpty() }, s?.handwriting?.statusText()).joinToString(" · ")
        status.visibility = if (status.text.isEmpty()) View.GONE else View.VISIBLE
        refreshList()
    }

    // ================= 선택 =================

    private fun startSelection(page: Int?) {
        if (searchOpen) hideKeyboard()
        if (!selecting) {
            selecting = true
            clearSelected()
            showBar(selectBar)
            // 아래 막대는 페이지 관리 창에서만 (쪽 한눈에 보기는 위 선택 줄에 있다)
            if (!overview) actionBar.visibility = View.VISIBLE
            Toast.makeText(ctx, "고른 쪽을 꾹 눌러 끌면 함께 옮길 수 있습니다.", Toast.LENGTH_SHORT).show()
        }
        page?.let { select(it) }
        onSelectionChanged()
    }

    fun endSelection() {
        if (!selecting) return
        selecting = false
        clearSelected()
        actionBar.visibility = View.GONE
        showBar(if (searchOpen) searchBar else normalBar)
        adapter.notifyDataSetChanged()
    }

    private fun onSelectionChanged() {
        val n = selectedPages().size
        selectCount.text = if (n == 0) "쪽 선택" else "${n}쪽 선택"
        for (b in actionButtons) {
            b.isEnabled = n > 0
            b.alpha = if (n > 0) 1f else 0.35f
        }
        forEachHolder { it.bindSelection() }
    }

    /** 쪽을 가리키는 열쇠: 그 쪽의 획 목록 자체 (내용이 같아도 다른 쪽이면 다른 열쇠) */
    private class PageKey(val list: List<Stroke>) {
        override fun equals(other: Any?) = other is PageKey && other.list === list
        override fun hashCode() = System.identityHashCode(list)
    }

    private fun keyOf(page: Int): PageKey? = ink?.pages?.getOrNull(page)?.let { PageKey(it) }

    private fun isSelected(page: Int) = keyOf(page)?.let { it in selKeys } == true

    /** 고른 쪽 번호 (지금 문서의 차례대로) */
    private fun selectedPages(): List<Int> = (0 until (doc?.pageCount ?: 0)).filter { isSelected(it) }

    private fun select(page: Int) {
        keyOf(page)?.let { selKeys.add(it) }
    }

    private fun deselect(page: Int) {
        val k = keyOf(page) ?: return
        selKeys.remove(k)
        aliases.remove(k)?.let { selKeys.remove(it); aliases.remove(it) }
    }

    private fun clearSelected() {
        selKeys.clear()
        aliases.clear()
    }

    /**
     * 쪽 돌리기처럼 한 쪽의 획 목록이 새 목록으로 바뀜: 고른 쪽이었으면 새 목록도 고른 것으로
     * (옛 목록도 남겨 두어 실행 취소로 돌아와도 골라져 있게)
     */
    fun pageReplaced(old: List<Stroke>, new: List<Stroke>) {
        val o = PageKey(old)
        if (o !in selKeys) return
        val n = PageKey(new)
        selKeys.add(n)
        aliases[o] = n
        aliases[n] = o
    }

    /** 고른 쪽을 끌어 옮길 수 있는지: 모든 쪽이 차례대로 보일 때만 */
    private fun canMove() = !searching() && filter == Filter.ALL

    /**
     * 꾹 누른 채 손가락을 움직이면 누른 칸부터 지금 칸까지를 고른다 (목록 위아래 끝에 가면 저절로 넘어감).
     * 누르기 전에 골라 둔 것은 그대로 둔다 (파일 탐색기와 같은 방식)
     */
    private val dragSelect = object : RecyclerView.OnItemTouchListener {
        private var active = false
        private var anchor = -1
        private var last = -1
        private var base: Set<PageKey> = emptySet()
        private var x = 0f
        private var y = 0f
        private var scrolling = false
        private val edge get() = 56 * density

        private val scroller = object : Runnable {
            override fun run() {
                if (!active) { scrolling = false; return }
                val h = grid.height
                val dy = when {
                    y < edge -> -((edge - y) / edge * 24 * density)
                    y > h - edge -> (y - (h - edge)) / edge * 24 * density
                    else -> 0f
                }
                if (dy == 0f) { scrolling = false; return }
                grid.scrollBy(0, dy.toInt())
                update()
                grid.postOnAnimation(this)
            }
        }

        fun begin(pos: Int) {
            if (pos < 0) return
            active = true
            anchor = pos
            last = pos
            base = selKeys.toSet()
        }

        private fun update() {
            val child = grid.findChildViewUnder(x, y) ?: return
            val pos = grid.getChildAdapterPosition(child)
            if (pos == RecyclerView.NO_POSITION || pos == last) return
            last = pos
            selKeys.retainAll(base)
            selKeys.addAll(base)
            for (i in min(anchor, pos)..max(anchor, pos)) pages.getOrNull(i)?.let { select(it) }
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
                    if (!scrolling && (y < edge || y > grid.height - edge)) {
                        scrolling = true
                        grid.postOnAnimation(scroller)
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> active = false
            }
        }

        override fun onRequestDisallowInterceptTouchEvent(disallow: Boolean) {}
    }

    init {
        // 위의 init보다 뒤에 만들어지므로 여기서 붙인다
        grid.addOnItemTouchListener(dragSelect)
    }

    /**
     * 고른 쪽 옮기기 (삼성 노트처럼): 고른 쪽 하나를 꾹 눌러 끌면 다른 고른 쪽들이 제자리에서 빠져
     * 잡은 쪽에 모이고(잡은 쪽에 'n쪽' 묶음 표시), 그 묶음이 손가락을 따라가며 다른 쪽이 비켜난다.
     * 놓으면 놓은 자리에 고른 쪽 모두가 원래 차례대로 들어간 모습을 바로 보여 주고 실제 순서를 바꾼다
     */
    private inner class DragCallback : ItemTouchHelper.Callback() {
        private var dragged = -1
        /** 함께 옮기는 고른 쪽 (원래 차례) */
        private var group: List<Int> = emptyList()

        override fun isLongPressDragEnabled() = false

        override fun getMovementFlags(rv: RecyclerView, vh: RecyclerView.ViewHolder): Int =
            makeMovementFlags(ItemTouchHelper.UP or ItemTouchHelper.DOWN or ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT, 0)

        override fun onMove(rv: RecyclerView, vh: RecyclerView.ViewHolder, target: RecyclerView.ViewHolder): Boolean {
            val a = vh.bindingAdapterPosition
            val b = target.bindingAdapterPosition
            if (a < 0 || b < 0) return false
            pages.add(b, pages.removeAt(a))
            adapter.notifyItemMoved(a, b)
            return true
        }

        override fun onSwiped(vh: RecyclerView.ViewHolder, direction: Int) {}

        override fun onSelectedChanged(vh: RecyclerView.ViewHolder?, actionState: Int) {
            super.onSelectedChanged(vh, actionState)
            if (actionState != ItemTouchHelper.ACTION_STATE_DRAG || vh !is Holder) return
            dragged = vh.page
            group = selectedPages()
            // 다른 고른 쪽은 제자리에서 빠져 잡은 쪽에 모인다
            for (p in group) {
                if (p == dragged) continue
                val pos = pages.indexOf(p)
                if (pos < 0) continue
                pages.removeAt(pos)
                adapter.notifyItemRemoved(pos)
            }
            vh.showStack(group.size)
            vh.itemView.animate().scaleX(1.06f).scaleY(1.06f).translationZ(px(8f).toFloat()).setDuration(120).start()
        }

        override fun clearView(rv: RecyclerView, vh: RecyclerView.ViewHolder) {
            super.clearView(rv, vh)
            vh.itemView.animate().scaleX(1f).scaleY(1f).translationZ(0f).setDuration(120).start()
            (vh as? Holder)?.showStack(0)
            val d = dragged
            val sel = group
            dragged = -1
            group = emptyList()
            if (d < 0) return
            // 놓은 자리에 고른 쪽을 원래 차례대로 끼워 넣는다
            val i = pages.indexOf(d)
            val selSet = sel.toSet()
            val order = pages.subList(0, i).filter { it !in selSet } + sel + pages.subList(i + 1, pages.size).filter { it !in selSet }
            if (order == order.indices.toList()) {
                refreshList()
                return
            }
            // 새 순서를 바로 보여 준다 (쪽 번호는 새 문서가 오면 새로 매겨진다)
            pages = order.toMutableList()
            adapter.notifyDataSetChanged()
            if (!host.reorder(order)) refreshList()
        }
    }

    // ================= 미리보기 칸 =================

    private inline fun forEachHolder(f: (Holder) -> Unit) {
        for (i in 0 until grid.childCount) (grid.getChildViewHolder(grid.getChildAt(i)) as? Holder)?.let(f)
    }

    /** 쪽 하나를 그리는 칸: 흰 바탕 + PDF 쪽 그림 + 찾은 글자 강조 + 필기 */
    private inner class ThumbView(context: Context) : View(context) {
        var page = -1
        private val dst = RectF()
        private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val hitPaint = Paint().apply {
            color = 0xFFFFC94D.toInt()
            blendMode = android.graphics.BlendMode.MULTIPLY
        }

        override fun onDraw(canvas: Canvas) {
            val d = doc ?: return
            if (page !in 0 until d.pageCount) return
            val l = paddingLeft.toFloat()
            val t = paddingTop.toFloat()
            val w = (width - paddingLeft - paddingRight).toFloat()
            val h = (height - paddingTop - paddingBottom).toFloat()
            dst.set(l, t, l + w, t + h)
            canvas.save()
            canvas.clipRect(dst)
            canvas.drawColor(Color.WHITE)
            keyOf(page)?.let { cache.get(it) }?.let { canvas.drawBitmap(it, null, dst, bmpPaint) }
            val s = w / d.sizes[page].width
            canvas.translate(l, t)
            canvas.scale(s, s)
            hits[page]?.forEach { canvas.drawRect(it, hitPaint) }
            val strokes = ink?.pages?.getOrNull(page)
            if (!strokes.isNullOrEmpty()) {
                // 그림을 먼저, 필기를 그 위에
                for (st in strokes.sortedBy { inkLayer(it) }) drawInkStroke(canvas, inkPaint, st)
            }
            canvas.restore()
        }
    }

    private inner class Holder(
        root: View, val thumb: ThumbView, val ribbon: ImageView, val more: ImageButton,
        val check: ImageView, val stack: TextView, val label: TextView,
    ) : RecyclerView.ViewHolder(root) {
        var page = -1

        /** 여러 쪽을 함께 끄는 중: 'n쪽' 묶음 표시와 뒤에 겹친 종이 (n이 1 이하면 없앰) */
        fun showStack(n: Int) {
            val on = n > 1
            stack.visibility = if (on) View.VISIBLE else View.GONE
            stack.text = "${n}쪽"
            behind.forEachIndexed { k, v ->
                v.visibility = if (on && k < n - 1) View.VISIBLE else View.GONE
            }
        }

        /** 묶음일 때 미리보기 뒤로 오른쪽 아래에 비껴 겹쳐 보이는 종이 두 장 */
        val behind: List<View> = (root as ViewGroup).getChildAt(0).let { frame ->
            frame as ViewGroup
            listOf(frame.getChildAt(0), frame.getChildAt(1))
        }

        fun bindMark() {
            val on = ink?.isBookmarked(page) == true
            ribbon.setImageResource(if (on) R.drawable.ic_ribbon_on else R.drawable.ic_ribbon_off)
            ribbon.contentDescription = if (on) "${page + 1}쪽 북마크 풀기" else "${page + 1}쪽 북마크"
        }

        fun bindSelection() {
            val on = selecting && isSelected(page)
            check.visibility = if (selecting) View.VISIBLE else View.GONE
            check.setImageResource(if (on) R.drawable.ic_page_check_on else R.drawable.ic_page_check_off)
            more.visibility = if (selecting) View.GONE else View.VISIBLE
            ribbon.isClickable = !selecting
            bindFrame()
        }

        /** 테두리: 고른 쪽·지금 보는 쪽은 파란 굵은 테두리 */
        fun bindFrame() {
            val border = px(BORDER)
            val strong = if (selecting) isSelected(page) else page == current
            thumb.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = px(3f).toFloat()
                if (strong) setStroke(border, accent) else setStroke(px(1f), outline)
            }
            val isCurrent = page == current
            label.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            label.setTextColor(if (isCurrent) accent else onSurfaceVariant)
        }
    }

    private inner class ThumbAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val pad = px(ITEM_PAD)
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(pad, pad, pad, pad)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            }
            // 미리보기 칸. 묶음으로 끌 때 쓰는 뒤 종이 두 장을 맨 아래에 깔아 둔다 (Holder.behind)
            val frame = FrameLayout(ctx).apply { clipChildren = false }
            for (k in 2 downTo 1) frame.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    setStroke(px(1f), outline)
                    cornerRadius = px(3f).toFloat()
                }
                translationX = px(6f * k).toFloat()
                translationY = px(6f * k).toFloat()
                visibility = View.GONE
            }, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            root.clipChildren = false
            val thumb = ThumbView(ctx).apply {
                isClickable = true
                isLongClickable = true
                foreground = themeDrawable(android.R.attr.selectableItemBackground)
            }
            frame.addView(thumb, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            // 오른쪽 위 리본: 누르기 쉽게 누르는 자리는 그림보다 넓게
            val ribbon = ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.FIT_END
                setPadding(px(10f), 0, px(8f), px(8f))
                isClickable = true
            }
            frame.addView(ribbon, FrameLayout.LayoutParams(px(40f), px(36f), Gravity.TOP or Gravity.END))
            val more = ImageButton(ctx).apply {
                setImageResource(R.drawable.ic_more)
                imageTintList = ColorStateList.valueOf(onSurfaceVariant)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(px(4f), px(4f), px(4f), px(4f))
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(0xB3FFFFFF.toInt())
                }
                contentDescription = "쪽 메뉴"
            }
            frame.addView(more, FrameLayout.LayoutParams(px(26f), px(26f), Gravity.BOTTOM or Gravity.END).apply {
                setMargins(0, 0, px(6f), px(6f))
            })
            // 선택 중: 왼쪽 위 동그라미 (고르면 파란 체크)
            val check = ImageView(ctx).apply { visibility = View.GONE }
            // 여러 쪽을 함께 끌 때 가운데에 뜨는 'n쪽' 묶음 표시
            val stack = TextView(ctx).apply {
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(px(12f), px(5f), px(12f), px(5f))
                background = GradientDrawable().apply {
                    setColor(accent)
                    cornerRadius = px(16f).toFloat()
                }
                visibility = View.GONE
            }
            frame.addView(check, FrameLayout.LayoutParams(px(24f), px(24f), Gravity.TOP or Gravity.START).apply {
                setMargins(px(6f), px(6f), 0, 0)
            })
            frame.addView(stack, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            val label = TextView(ctx).apply {
                gravity = Gravity.CENTER
                textSize = 13f
                setPadding(0, px(5f), 0, px(2f))
            }
            root.addView(frame)
            root.addView(label, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            val h = Holder(root, thumb, ribbon, more, check, stack, label)
            thumb.setOnClickListener {
                val p = h.page
                if (p < 0) return@setOnClickListener
                if (selecting) {
                    if (isSelected(p)) deselect(p) else select(p)
                    onSelectionChanged()
                } else host.pick(p, hits[p]?.firstOrNull(), if (searching()) query else null)
            }
            thumb.setOnLongClickListener {
                val p = h.page
                val pos = h.bindingAdapterPosition
                if (p < 0 || pos == RecyclerView.NO_POSITION) return@setOnLongClickListener true
                thumb.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                when {
                    // 고른 쪽을 꾹 누르면 끌어 옮기기
                    selecting && isSelected(p) -> {
                        if (canMove()) touchHelper.startDrag(h)
                        else Toast.makeText(ctx, "쪽 옮기기는 모든 쪽을 볼 때 할 수 있습니다.", Toast.LENGTH_SHORT).show()
                    }
                    else -> {
                        startSelection(p)
                        dragSelect.begin(pos)
                    }
                }
                true
            }
            ribbon.setOnClickListener {
                val inkDoc = ink ?: return@setOnClickListener
                if (h.page < 0 || selecting) return@setOnClickListener
                inkDoc.setBookmark(h.page, !inkDoc.isBookmarked(h.page))
                h.bindMark()
            }
            more.setOnClickListener { if (h.page >= 0) host.menu(h.page, it) }
            return h
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val d = doc ?: return
            val page = pages[position]
            holder.page = page
            holder.thumb.page = page
            holder.itemView.alpha = 1f
            val size = d.sizes[page]
            val border = px(BORDER)
            val w = thumbW.coerceAtLeast(1)
            val h = (w * size.height / size.width).toInt().coerceAtLeast(1)
            (holder.thumb.parent as View).layoutParams = LinearLayout.LayoutParams(w + border * 2, h + border * 2)
            holder.thumb.setPadding(border, border, border, border)
            holder.thumb.contentDescription = "${page + 1}쪽${if (page == current) " (지금 보는 쪽)" else ""}"
            holder.thumb.invalidate()
            val n = hits[page]?.size ?: 0
            holder.label.text = if (n > 0) "${page + 1}  ·  ${n}곳" else "${page + 1}"
            holder.bindMark()
            holder.bindSelection()
            holder.showStack(0)
            if (keyOf(page)?.let { cache.get(it) } == null) render(page, w, h)
        }

        override fun onViewRecycled(holder: Holder) {
            holder.page = -1
            holder.thumb.page = -1
        }
    }

    private fun render(page: Int, w: Int, h: Int) {
        if (jobs.containsKey(page)) return
        val d = doc ?: return
        val key = keyOf(page) ?: return
        jobs[page] = scope.launch {
            try {
                val scale = w / d.sizes[page].width
                val bmp = withContext(d.dispatcher) { d.render(page, scale, 0f, 0f, w, h) }
                if (doc !== d) return@launch  // 그사이 문서가 바뀜
                cache.put(key, bmp)
                forEachHolder { if (it.page == page) it.thumb.invalidate() }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // 탭을 닫아 PDF가 닫히는 등: 흰 쪽으로 둔다
            } finally {
                if (doc === d) jobs.remove(page)
            }
        }
    }

    companion object {
        /** 칸 안쪽 여백과 미리보기 테두리 (dp) */
        private const val ITEM_PAD = 6f
        private const val BORDER = 2.5f
        private const val MIN_COLS = 2
        private const val MAX_COLS = 12
    }
}
