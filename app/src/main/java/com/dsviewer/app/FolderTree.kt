package com.dsviewer.app

import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.Collator
import java.util.Locale

/**
 * 옮길 폴더 고르기 (삼성노트의 '저장 폴더 선택'처럼): 펼치고 접는 폴더 나무, 오른쪽에 문서 수, 맨 아래 '폴더 추가'.
 * 폴더 이름을 누르면 [onPick]. 지금 있는 폴더([current])는 색으로 표시한다
 */
class FolderTree(
    private val activity: AppCompatActivity,
    private val title: String,
    private val source: Source,
    private val current: String?,
    private val onPick: (Node) -> Unit,
) {
    class Node(val id: String, val name: String, val icon: Drawable, val depth: Int)

    interface Source {
        fun roots(): List<Node>
        /** 아래 폴더 (파일을 읽을 수 있어 IO 스레드에서 부른다) */
        fun children(n: Node): List<Node>
        /** 폴더 안 문서 수 (IO 스레드) */
        fun count(n: Node): Int
        /** 맨 위부터 [id]까지 (처음에 펼쳐 둘 폴더들) */
        fun pathTo(id: String?): List<String>
        /** [parent] 안에 새 폴더를 만든다 (이름 묻기 포함). 다 되면 [done] */
        fun add(parent: Node, done: () -> Unit)
    }

    private val density = activity.resources.displayMetrics.density
    private fun px(v: Float) = (v * density).toInt()
    private val expanded = HashSet<String>()
    private val kids = HashMap<String, List<Node>>()
    private val counts = HashMap<String, Int>()
    private val pending = HashSet<String>()
    private var shown: List<Node> = emptyList()
    private val adapter = Adapter()
    /** '폴더 추가'가 새 폴더를 만들 곳: 마지막으로 펼친 폴더 */
    private var addTarget: Node? = null
    private var dialog: AlertDialog? = null
    private val accent = MaterialColors.getColor(activity.window.decorView, androidx.appcompat.R.attr.colorPrimary)
    private val onSurface = MaterialColors.getColor(activity.window.decorView, com.google.android.material.R.attr.colorOnSurface)
    private val muted = MaterialColors.getColor(activity.window.decorView, com.google.android.material.R.attr.colorOnSurfaceVariant)

    fun show() {
        val h = (activity.resources.displayMetrics.heightPixels * 0.55f).toInt()
        // 폴더가 적으면 창도 작게, 많으면 화면의 55%까지
        val list = object : RecyclerView(activity) {
            override fun onMeasure(widthSpec: Int, heightSpec: Int) =
                super.onMeasure(widthSpec, View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST))
        }.apply {
            layoutManager = LinearLayoutManager(activity)
            adapter = this@FolderTree.adapter
        }
        val add = row().apply {
            val plus = ImageView(activity).apply {
                setImageResource(R.drawable.ic_add)
                imageTintList = android.content.res.ColorStateList.valueOf(accent)
            }
            addView(plus, LinearLayout.LayoutParams(px(28f), px(28f)).apply { marginStart = px(20f); marginEnd = px(16f) })
            addView(TextView(activity).apply {
                text = "폴더 추가"
                textSize = 16f
                setTextColor(onSurface)
            })
            setOnClickListener {
                val parent = addTarget ?: shown.firstOrNull() ?: return@setOnClickListener
                source.add(parent) {
                    kids.remove(parent.id)
                    counts.clear()
                    expanded += parent.id
                    reload()
                }
            }
        }
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(View(activity).apply { setBackgroundColor(MaterialColors.getColor(this, com.google.android.material.R.attr.colorOutlineVariant)) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, px(1f)))
            addView(add)
        }
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle(title)
            .setView(box)
            .setNegativeButton("취소", null)
            .show()
        // 지금 폴더까지 펼쳐 둔다
        val path = source.pathTo(current)
        expanded += path.dropLast(1)
        if (path.size <= 1) path.firstOrNull()?.let { expanded += it }
        reload(scrollTo = current)
    }

    /** 펼친 폴더들의 아래 폴더를 (없으면) 읽고 목록을 다시 만든다 */
    private fun reload(scrollTo: String? = null) {
        activity.lifecycleScope.launch {
            val out = ArrayList<Node>()
            suspend fun walk(nodes: List<Node>) {
                for (n in nodes) {
                    out += n
                    if (n.id in expanded) {
                        val c = kids[n.id] ?: withContext(Dispatchers.IO) { runCatching { source.children(n) }.getOrDefault(emptyList()) }
                            .also { kids[n.id] = it }
                        walk(c)
                    }
                }
            }
            walk(source.roots())
            shown = out
            if (addTarget == null || shown.none { it.id == addTarget?.id }) {
                addTarget = shown.firstOrNull { it.id == current } ?: shown.firstOrNull()
            }
            adapter.notifyDataSetChanged()
            scrollTo?.let { id -> shown.indexOfFirst { it.id == id }.takeIf { it > 3 }?.let { adapter.recycler?.scrollToPosition(it) } }
        }
    }

    private fun row() = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = px(52f)
        layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        val tv = android.util.TypedValue()
        activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        background = activity.getDrawable(tv.resourceId)
    }

    private class Holder(val root: LinearLayout, val indent: View, val chevron: ImageView, val icon: ImageView, val name: TextView, val count: TextView) :
        RecyclerView.ViewHolder(root)

    private inner class Adapter : RecyclerView.Adapter<Holder>() {
        var recycler: RecyclerView? = null
        override fun onAttachedToRecyclerView(rv: RecyclerView) { recycler = rv }
        override fun getItemCount() = shown.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val root = row()
            val indent = View(activity)
            val chevron = ImageView(activity).apply {
                setImageResource(R.drawable.ic_chevron_right)
                scaleType = ImageView.ScaleType.CENTER
                imageTintList = android.content.res.ColorStateList.valueOf(muted)
            }
            val icon = ImageView(activity).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
            val name = TextView(activity).apply {
                textSize = 16f
                isSingleLine = true
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val count = TextView(activity).apply {
                textSize = 14f
                setTextColor(muted)
            }
            root.addView(indent, LinearLayout.LayoutParams(0, 1))
            root.addView(chevron, LinearLayout.LayoutParams(px(40f), px(48f)))
            root.addView(icon, LinearLayout.LayoutParams(px(30f), px(24f)).apply { marginEnd = px(14f) })
            root.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            root.addView(count, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = px(20f) })
            return Holder(root, indent, chevron, icon, name, count)
        }

        override fun onBindViewHolder(h: Holder, position: Int) {
            val n = shown[position]
            h.indent.layoutParams = (h.indent.layoutParams as LinearLayout.LayoutParams).apply { width = px(8f + n.depth * 26f) }
            h.icon.setImageDrawable(n.icon)
            h.name.text = n.name
            val here = n.id == current
            h.name.setTextColor(if (here) accent else onSurface)
            h.name.setTypeface(null, if (here) Typeface.BOLD else Typeface.NORMAL)
            val open = n.id in expanded
            h.chevron.rotation = if (open) 90f else 0f
            val known = kids[n.id]
            // 아래 폴더가 없다고 확인된 폴더는 화살표를 숨긴다
            h.chevron.visibility = if (known != null && known.isEmpty()) View.INVISIBLE else View.VISIBLE
            h.chevron.setOnClickListener {
                if (open) expanded -= n.id else {
                    expanded += n.id
                    addTarget = n
                }
                reload()
            }
            val c = counts[n.id]
            h.count.text = if (c != null && c > 0) "$c" else ""
            if ((c == null || known == null) && pending.add(n.id)) activity.lifecycleScope.launch {
                val (cnt, ch) = withContext(Dispatchers.IO) {
                    runCatching { source.count(n) }.getOrDefault(0) to (kids[n.id] ?: runCatching { source.children(n) }.getOrDefault(emptyList()))
                }
                counts[n.id] = cnt
                kids[n.id] = ch
                pending.remove(n.id)
                val p = shown.indexOf(n)
                if (p >= 0) notifyItemChanged(p)
            }
            h.root.setOnClickListener {
                dialog?.dismiss()
                onPick(n)
            }
        }
    }

    companion object {
        private val collator: Collator = Collator.getInstance(Locale.KOREAN)

        /** 기기 저장소의 실제 폴더 */
        fun storage(activity: AppCompatActivity): Source = object : Source {
            private val roots = DocFiles.roots(activity)
            private val yellow = android.graphics.Color.parseColor("#E8A317")
            private val blue = android.graphics.Color.parseColor("#2F6FC4")

            override fun roots() = roots.map { Node(it.file.path, it.name, FolderDrawable(blue, TapePattern.SOLID), 0) }

            override fun children(n: Node): List<Node> {
                val dirs = File(n.id).listFiles { f -> f.isDirectory && !f.name.startsWith(".") } ?: return emptyList()
                return dirs.sortedWith { a, b -> collator.compare(a.name, b.name) }
                    .map { Node(it.path, it.name, FolderDrawable(yellow, TapePattern.SOLID), n.depth + 1) }
            }

            override fun count(n: Node) = File(n.id).listFiles { f -> f.isFile && DocFiles.isDoc(f.name) }?.size ?: 0

            override fun pathTo(id: String?): List<String> {
                if (id == null) return emptyList()
                val root = roots.firstOrNull { id == it.file.path || id.startsWith(it.file.path + "/") } ?: return emptyList()
                val out = arrayListOf(root.file.path)
                var cur = root.file.path
                for (part in id.removePrefix(root.file.path).split('/').filter { it.isNotEmpty() }) {
                    cur = "$cur/$part"
                    out += cur
                }
                return out
            }

            override fun add(parent: Node, done: () -> Unit) {
                val edit = EditText(activity).apply {
                    setText("새 폴더")
                    selectAll()
                    isSingleLine = true
                }
                val d = activity.resources.displayMetrics.density
                val box = LinearLayout(activity).apply {
                    setPadding((24 * d).toInt(), (8 * d).toInt(), (24 * d).toInt(), 0)
                    addView(edit, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
                MaterialAlertDialogBuilder(activity)
                    .setTitle("'${parent.name}' 안에 새 폴더")
                    .setView(box)
                    .setPositiveButton("만들기") { _, _ ->
                        val raw = FileNames.safe(edit.text.toString().trim()).ifEmpty { "새 폴더" }
                        val dir = File(parent.id)
                        val name = FileOps.uniqueName(raw, withExt = false) { File(dir, it).exists() }
                        if (File(dir, name).mkdirs()) done()
                        else MaterialAlertDialogBuilder(activity).setMessage("폴더를 만들지 못했습니다.").setPositiveButton("확인", null).show()
                    }
                    .setNegativeButton("취소", null)
                    .show()
            }
        }
    }
}
