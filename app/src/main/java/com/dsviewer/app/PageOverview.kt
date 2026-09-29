package com.dsviewer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 쪽 한눈에 보기: 문서 화면을 덮는 쪽 미리보기 모음.
 * 한 화면에 20쪽 안팎이 보이도록 쪽 모양과 화면 크기로 열 수를 정하고,
 * 미리보기는 PDF 쪽 그림 위에 필기를 얹어 그린다. 누르면 그 쪽으로 간다.
 * '필기된 쪽'을 고르면 필기가 있는 쪽만 모아 보여 준다.
 */
class PageOverview(
    private val panel: View,
    private val scope: CoroutineScope,
    private val onPick: (page: Int) -> Unit,
) {
    private val ctx: Context = panel.context
    private val density = ctx.resources.displayMetrics.density
    private val prefs = ctx.getSharedPreferences("tools", Context.MODE_PRIVATE)
    private val grid: RecyclerView = panel.findViewById(R.id.overviewGrid)
    private val empty: View = panel.findViewById(R.id.overviewEmpty)
    private val title: TextView = panel.findViewById(R.id.overviewTitle)
    private val filter: MaterialButtonToggleGroup = panel.findViewById(R.id.overviewFilter)
    private val layout = GridLayoutManager(ctx, 4)
    private val adapter = ThumbAdapter()

    private var doc: PdfDoc? = null
    private var ink: InkDocument? = null
    private var current = 0
    private var pages: List<Int> = emptyList()
    /** 미리보기 그림 너비 (px) */
    private var thumbW = 0

    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 8).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val paint = inkPaint()
    private val itemPad = (5 * density).toInt()
    private val accent = MaterialColors.getColor(panel, androidx.appcompat.R.attr.colorPrimary)
    private val outline = MaterialColors.getColor(panel, com.google.android.material.R.attr.colorOutlineVariant)

    val isShowing get() = panel.visibility == View.VISIBLE

    init {
        grid.layoutManager = layout
        grid.adapter = adapter
        panel.findViewById<View>(R.id.overviewClose).setOnClickListener { hide() }
        filter.check(if (prefs.getBoolean("overviewInked", false)) R.id.overviewInked else R.id.overviewAll)
        filter.addOnButtonCheckedListener { _, id, checked ->
            if (!checked) return@addOnButtonCheckedListener
            prefs.edit().putBoolean("overviewInked", id == R.id.overviewInked).apply()
            refreshList()
            grid.scrollToPosition(0)
        }
    }

    /** [width]×[height]는 덮을 문서 화면 크기 */
    fun show(d: PdfDoc, inkDoc: InkDocument, currentPage: Int, width: Int, height: Int) {
        doc = d
        ink = inkDoc
        current = currentPage
        cache.evictAll()
        // 열 수: 대표 쪽 모양(세로/가로 비율의 가운데값)으로 한 화면에 20쪽 안팎이 들어가는 가장 적은 열
        val ratios = d.sizes.map { it.height / it.width }.sorted()
        val r = ratios[ratios.size / 2]
        val usableW = (width - grid.paddingLeft - grid.paddingRight).toFloat()
        val usableH = height - 52 * density - grid.paddingTop - grid.paddingBottom
        val labelH = 22 * density
        var cols = 10
        for (c in 3..10) {
            val cellW = usableW / c - itemPad * 2
            if (c * usableH / (cellW * r + labelH + itemPad * 2) >= 20) { cols = c; break }
        }
        layout.spanCount = cols
        thumbW = (usableW / cols).toInt() - itemPad * 2
        title.text = "쪽 한눈에 보기 · ${d.pageCount}쪽"
        panel.visibility = View.VISIBLE
        refreshList()
        // 지금 보던 쪽이 보이도록
        val pos = pages.indexOf(current)
        if (pos >= 0) layout.scrollToPositionWithOffset(pos, (usableH / 3).toInt()) else grid.scrollToPosition(0)
    }

    fun hide() {
        if (!isShowing) return
        panel.visibility = View.GONE
        doc = null
        ink = null
        pages = emptyList()
        adapter.notifyDataSetChanged()
        cache.evictAll()
    }

    private fun refreshList() {
        val inkDoc = ink ?: return
        val d = doc ?: return
        val inkedOnly = filter.checkedButtonId == R.id.overviewInked
        pages = (0 until d.pageCount).filter { !inkedOnly || inkDoc.pages.getOrNull(it)?.isNotEmpty() == true }
        empty.visibility = if (pages.isEmpty()) View.VISIBLE else View.GONE
        adapter.notifyDataSetChanged()
    }

    private inner class Holder(val root: LinearLayout, val image: ImageView, val label: TextView) : RecyclerView.ViewHolder(root) {
        var page = -1
        var job: Job? = null
    }

    private inner class ThumbAdapter : RecyclerView.Adapter<Holder>() {
        override fun getItemCount() = pages.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val root = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(itemPad, itemPad, itemPad, itemPad)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                isClickable = true
                val tv = android.util.TypedValue()
                ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                foreground = ctx.getDrawable(tv.resourceId)
            }
            val image = ImageView(ctx).apply { scaleType = ImageView.ScaleType.FIT_XY }
            val label = TextView(ctx).apply {
                gravity = Gravity.CENTER
                textSize = 12f
                setPadding(0, (3 * density).toInt(), 0, 0)
            }
            root.addView(image)
            root.addView(label)
            return Holder(root, image, label)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val d = doc ?: return
            val page = pages[position]
            holder.page = page
            holder.job?.cancel()
            val size = d.sizes[page]
            // 지금 보던 쪽은 굵은 색 테두리. 테두리는 칸 너비 안쪽으로
            val isCurrent = page == current
            val border = ((if (isCurrent) 3 else 1) * density).toInt()
            val w = (thumbW - border * 2).coerceAtLeast(1)
            val h = (w * size.height / size.width).toInt().coerceAtLeast(1)
            holder.image.layoutParams = LinearLayout.LayoutParams(w + border * 2, h + border * 2)
            holder.image.setPadding(border, border, border, border)
            holder.image.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(border, if (isCurrent) accent else outline)
            }
            holder.label.text = "${page + 1}"
            holder.label.setTypeface(null, if (isCurrent) Typeface.BOLD else Typeface.NORMAL)
            holder.label.setTextColor(if (isCurrent) accent else MaterialColors.getColor(holder.label, com.google.android.material.R.attr.colorOnSurfaceVariant))
            holder.root.contentDescription = "${page + 1}쪽${if (isCurrent) " (지금 보는 쪽)" else ""}"
            holder.root.setOnClickListener {
                onPick(page)
                hide()
            }

            val cached = cache.get(page)
            if (cached != null) {
                holder.image.setImageBitmap(cached)
                return
            }
            holder.image.setImageDrawable(null)
            val inkDoc = ink ?: return
            holder.job = scope.launch {
                try {
                    val scale = w / size.width
                    val bmp = withContext(d.dispatcher) { d.render(page, scale, 0f, 0f, w, h) }
                    if (doc !== d) return@launch  // 그사이 닫힘
                    // 필기 얹기 (획의 경로 캐시를 문서 화면과 같이 쓰므로 메인 스레드에서)
                    val c = Canvas(bmp)
                    c.scale(scale, scale)
                    // 그림을 먼저, 필기를 그 위에
                    inkDoc.pages.getOrNull(page)?.sortedBy { if (it.image != null) 0 else 1 }?.forEach { drawInkStroke(c, paint, it) }
                    cache.put(page, bmp)
                    if (holder.page == page) holder.image.setImageBitmap(bmp)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 탭을 닫아 PDF가 닫히는 등: 미리보기는 비워 둔다
                }
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.job?.cancel()
            holder.job = null
            holder.page = -1
        }
    }
}
