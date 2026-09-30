package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.dsviewer.app.conv.DocConvert
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 썸네일로 쓸 쪽 고르기: 문서의 쪽들을 (필기와 함께) 작게 늘어놓고, 누른 쪽을 [onPick]으로 알린다 */
class ThumbPagePicker(
    private val activity: AppCompatActivity,
    private val uri: Uri,
    private val name: String,
    private val current: Int,
    private val onPick: (page: Int) -> Unit,
) {
    private val density = activity.resources.displayMetrics.density
    private val grid = RecyclerView(activity)
    private val progress = CircularProgressIndicator(activity).apply { isIndeterminate = true }
    private val message = TextView(activity).apply {
        gravity = Gravity.CENTER
        setPadding((24 * density).toInt(), 0, (24 * density).toInt(), 0)
        visibility = View.GONE
    }
    private var doc: PdfDoc? = null
    private var strokes: List<List<Stroke>>? = null
    private val temps = ArrayList<File>()
    private val cache = object : LruCache<Int, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
    }
    private val accent = MaterialColors.getColor(activity.window.decorView, androidx.appcompat.R.attr.colorPrimary)
    private val outline = MaterialColors.getColor(activity.window.decorView, com.google.android.material.R.attr.colorOutlineVariant)
    private var thumbW = (110 * density).toInt()
    private var dialog: androidx.appcompat.app.AlertDialog? = null
    private var loadJob: Job? = null

    fun show() {
        val frame = FrameLayout(activity).apply {
            val h = (activity.resources.displayMetrics.heightPixels * 0.62f).toInt()
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h)
            minimumHeight = h
            addView(grid, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, h))
            addView(progress, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
            addView(message, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER))
        }
        grid.setPadding((12 * density).toInt(), 0, (12 * density).toInt(), 0)
        grid.clipToPadding = false
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("썸네일로 쓸 쪽")
            .setView(frame)
            .setNegativeButton("취소", null)
            .setOnDismissListener { close() }
            .show()
        loadJob = activity.lifecycleScope.launch {
            try {
                open()
                progress.visibility = View.GONE
                val d = doc ?: return@launch
                val usable = (grid.width.takeIf { it > 0 } ?: (activity.resources.displayMetrics.widthPixels * 0.8f).toInt()) -
                    grid.paddingLeft - grid.paddingRight
                val cols = (usable / (130 * density)).toInt().coerceIn(2, 8)
                thumbW = usable / cols - (10 * density).toInt()
                grid.layoutManager = GridLayoutManager(activity, cols)
                grid.adapter = Adapter(d)
                if (current > 0) grid.scrollToPosition(current)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                progress.visibility = View.GONE
                message.text = "문서를 읽지 못했습니다.\n${e.message ?: e.javaClass.simpleName}"
                message.visibility = View.VISIBLE
            }
        }
    }

    private suspend fun open() {
        val (render, ink) = withContext(Dispatchers.IO) {
            val local = uri.scheme == "file"
            val file = if (local) File(uri.path!!) else FileUtil.copyToCache(activity, uri, name).also { temps += it }
            when (val type = FileUtil.detect(name, null, file)) {
                DocType.PDF -> if (PdfInk.containsInk(file)) {
                    val clean = FileUtil.tempFile(activity, "pick", "pdf").also { temps += it }
                    clean to PdfInk.extract(file, clean)
                } else file to null
                DocType.UNKNOWN -> error("지원하지 않는 형식")
                else -> {
                    val out = DocConvert.toPdf(activity, file, type, name, prefix = "pick").also { temps += it }
                    // 글 파일은 글 상자(이 앱의 필기)로 들어 있다
                    if (type == DocType.TXT) {
                        val clean = FileUtil.tempFile(activity, "pick", "pdf").also { temps += it }
                        clean to PdfInk.extract(out, clean)
                    } else out to null
                }
            }
        }
        strokes = ink
        doc = PdfDoc.open(render)
    }

    private fun close() {
        loadJob?.cancel()
        doc?.close()
        doc = null
        cache.evictAll()
        val files = temps.toList()
        temps.clear()
        // PdfDoc이 제 스레드에서 닫은 뒤에 지운다
        activity.lifecycleScope.launch(Dispatchers.IO) {
            kotlinx.coroutines.delay(500)
            files.forEach { it.delete() }
        }
    }

    private class Holder(val root: LinearLayout, val image: ImageView, val label: TextView) : RecyclerView.ViewHolder(root) {
        var page = -1
        var job: Job? = null
    }

    private inner class Adapter(val d: PdfDoc) : RecyclerView.Adapter<Holder>() {
        private val paint = inkPaint()

        override fun getItemCount() = d.pageCount

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val pad = (5 * density).toInt()
            val root = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(pad, pad, pad, pad)
                layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                isClickable = true
                val tv = android.util.TypedValue()
                activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
                foreground = activity.getDrawable(tv.resourceId)
            }
            val image = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_XY }
            val label = TextView(activity).apply {
                gravity = Gravity.CENTER
                textSize = 12f
            }
            root.addView(image)
            root.addView(label)
            return Holder(root, image, label)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.page = position
            holder.job?.cancel()
            val size = d.sizes[position]
            val isCurrent = position == current
            val border = ((if (isCurrent) 3 else 1) * density).toInt()
            val w = (thumbW - border * 2).coerceAtLeast(1)
            val h = (w * size.height / size.width).toInt().coerceAtLeast(1)
            holder.image.layoutParams = LinearLayout.LayoutParams(w + border * 2, h + border * 2)
            holder.image.setPadding(border, border, border, border)
            holder.image.background = GradientDrawable().apply {
                setColor(Color.WHITE)
                setStroke(border, if (isCurrent) accent else outline)
            }
            holder.label.text = if (isCurrent) "${position + 1} (지금)" else "${position + 1}"
            holder.root.setOnClickListener {
                onPick(position)
                dialog?.dismiss()
            }
            cache.get(position)?.let {
                holder.image.setImageBitmap(it)
                return
            }
            holder.image.setImageDrawable(null)
            holder.job = activity.lifecycleScope.launch {
                try {
                    val scale = w / size.width
                    val bmp = withContext(d.dispatcher) { d.render(position, scale, 0f, 0f, w, h) }
                    if (doc !== d) return@launch
                    strokes?.getOrNull(position)?.let { list ->
                        val c = Canvas(bmp)
                        c.scale(scale, scale)
                        list.sortedBy { if (it.image != null) 0 else 1 }.forEach { drawInkStroke(c, paint, it) }
                    }
                    cache.put(position, bmp)
                    if (holder.page == position) holder.image.setImageBitmap(bmp)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // 닫힘 등
                }
            }
        }

        override fun onViewRecycled(holder: Holder) {
            holder.job?.cancel()
            holder.page = -1
        }
    }
}
