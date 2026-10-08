package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.IdentityHashMap
import kotlin.math.roundToInt

/** 복습 문제 하나: 오답 항목, 오답 쪽에 있는 문제 그림, 그 쪽 번호 (원문 문서의) */
internal class ReviewProblem(val entry: WrongEntry, val stroke: Stroke, val sourcePage: Int)

/**
 * 복습 한 번. 오답마다 쪽 하나씩 가진 복습용 문서를 새 탭으로 열고, 쪽마다 문제 그림만 맨 위에 둔다.
 * 그 아래 빈 곳에 평소 필기 도구로 바로 풀면 된다. 풀이는 [saveFile](복습을 시작한 날짜·시간이 이름에 든 PDF)에 자동으로 저장된다.
 * 채점은 끝낼 때 한꺼번에 원래 문서의 오답 항목에 반영한다.
 */
internal class ReviewSession(val source: DocTab, val problems: List<ReviewProblem>, val saveFile: File) {
    /** 이번에 채점한 결과 */
    val results = IdentityHashMap<WrongEntry, ReviewResult>()
    /** 복습 문서의 쪽(획 목록 자체) → 그 쪽의 오답. 쪽을 옮기거나 지워도 따라간다 */
    val entryOfPage = IdentityHashMap<MutableList<Stroke>, WrongEntry>()
    /** 정답 보기에 쓰려고 그려 둔 그림 (끝내면 버린다) */
    val answers = IdentityHashMap<WrongEntry, Bitmap>()

    /** 문서가 열리면 쪽마다 문제 그림을 넣는다 (실행 취소 기록·'저장 안 됨' 표시에 남기지 않는다) */
    fun populate(ink: InkDocument) {
        for ((i, p) in problems.withIndex()) {
            val list = ink.pages.getOrNull(i) ?: continue
            val top = wrongBounds(p.stroke).top
            list.add(WrongNote.moved(p.stroke, WrongNote.PROBLEM_TOP - top))
            entryOfPage[list] = p.entry
        }
    }
}

/**
 * 오답 복습: 범위를 골라 복습용 문서를 열고, 화면 아래 줄에서 정답 보기 · 맞음/애매/틀림 채점 · 끝내기를 한다.
 * 문서 화면과 탭은 뷰어가 쥐고 있고 여기서는 넘겨받은 것만 쓴다.
 */
internal class ReviewController(
    private val activity: AppCompatActivity,
    /** 지금 고른 칸의 문서 화면 (분할 보기에서는 칸이 바뀐다) */
    private val docViewOf: () -> DocumentView,
    private val frame: FrameLayout,
    private val bar: HorizontalScrollView,
    private val row: LinearLayout,
    private val bottomOverlay: View,
    private val progress: View,
    /** 지금 보는 탭 */
    private val current: () -> DocTab?,
    private val saver: DocSaver,
    private val toast: (String) -> Unit,
    /** 복습용 문서(탭)를 연다 */
    private val openReviewTab: (ReviewSession, File) -> Unit,
    /** 확인 없이 탭을 닫는다 */
    private val removeTab: (DocTab) -> Unit,
    /** 이 탭의 마지막 필기까지 곧바로 파일에 적는다 */
    private val saveNow: (DocTab) -> Unit,
    /** 만든 PDF를 새 탭으로 연다 */
    private val openFile: (File) -> Unit,
) {
    private val docView get() = docViewOf()
    private val density = activity.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).roundToInt()

    private val title = TextView(activity).apply {
        textSize = 14f
        setTypeface(typeface, android.graphics.Typeface.BOLD)
        setSingleLine()
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val answerButton = button("정답 보기", outlined = true) { toggleAnswer() }
    private val gradeButtons = listOf(
        ReviewResult.RIGHT to button("맞음", color = 0xFF2E7D32.toInt()) { grade(ReviewResult.RIGHT) },
        ReviewResult.UNSURE to button("애매", color = 0xFFEF6C00.toInt()) { grade(ReviewResult.UNSURE) },
        ReviewResult.WRONG to button("틀림", color = 0xFFD32F2F.toInt()) { grade(ReviewResult.WRONG) },
    )
    private val endButton = button("끝내기", outlined = true) { current()?.let(::askClose) }

    /** 정답 보기 창: 원래 오답 쪽(문제와 내가 전에 쓴 풀이)을 그대로 보여 준다 */
    private val answerImage = ImageView(activity).apply {
        adjustViewBounds = true
        scaleType = ImageView.ScaleType.FIT_START
    }
    private val answerPanel = FrameLayout(activity).apply {
        setBackgroundColor(Color.WHITE)
        elevation = dp(8).toFloat()
        visibility = View.GONE
        isClickable = true
        addView(ScrollView(activity).apply { addView(answerImage, FrameLayout.LayoutParams(-1, -2)) }, FrameLayout.LayoutParams(-1, -1))
        addView(TextView(activity).apply {
            text = "정답 · 전에 쓴 풀이"
            textSize = 12f
            setTextColor(Color.WHITE)
            setPadding(dp(10), dp(4), dp(10), dp(4))
            background = GradientDrawable().apply { setColor(0xCC37474F.toInt()); cornerRadius = dp(12).toFloat() }
        }, FrameLayout.LayoutParams(-2, -2, Gravity.START or Gravity.TOP).apply { setMargins(dp(8), dp(8), 0, 0) })
    }
    private var answerShown = false

    init {
        row.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) })
        row.addView(answerButton)
        for ((_, b) in gradeButtons) row.addView(b)
        row.addView(endButton)
        frame.addView(answerPanel, FrameLayout.LayoutParams(-1, -1))
    }

    private fun button(text: String, outlined: Boolean = false, color: Int = 0, onClick: () -> Unit) =
        MaterialButton(
            activity, null,
            if (outlined) com.google.android.material.R.attr.materialButtonOutlinedStyle else com.google.android.material.R.attr.materialButtonStyle,
        ).apply {
            this.text = text
            isAllCaps = false
            textSize = 13f
            insetTop = 0
            insetBottom = 0
            minWidth = 0
            minimumWidth = 0
            setPadding(dp(14), 0, dp(14), 0)
            if (color != 0) backgroundTintList = android.content.res.ColorStateList.valueOf(color)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply { marginStart = dp(6) }
            setOnClickListener { onClick() }
        }

    // ================= 시작 =================

    /** [entries]의 문제 그림을 원문 문서의 오답 쪽에서 찾는다 (복습 순서: 안 본 것 먼저, 밀린 것 먼저). 그림을 지운 오답은 빠진다 */
    private fun collectProblems(source: DocTab, entries: List<WrongEntry>): List<ReviewProblem> {
        val inkDoc = source.ink ?: return emptyList()
        val pageOf = inkDoc.allWrongs().associateByTo(IdentityHashMap(), { it.second }, { it.first })
        return entries.sortedWith(compareBy({ ReviewSchedule.order(it.stage, it.dueDay) }, { it.number })).mapNotNull { e ->
            val page = pageOf[e] ?: return@mapNotNull null
            val st = WrongNote.problemStroke(inkDoc.pages[page], e.slot) ?: return@mapNotNull null
            ReviewProblem(e, st, page)
        }
    }

    /**
     * [entries]의 문제만 모은 문제지 PDF를 만든다: 문제 그림 하나와 아래 풀이 칸이 한 쪽씩, 정답과 전에 쓴 풀이는 없다.
     * 복습 풀이와 같은 폴더에 '원본이름_오답문제지_날짜시간.pdf'로 저장하고, 열기 · 공유(인쇄)를 묻는다
     */
    fun exportSheet(source: DocTab, entries: List<WrongEntry>) {
        val renderPdf = source.renderPdf ?: return
        if (source.pagesBusy) return
        val problems = collectProblems(source, entries)
        if (problems.isEmpty()) {
            toast("문제 그림을 찾지 못했습니다.")
            return
        }
        // 필기 획은 화면 스레드에서 만들어 둔다
        val pages = problems.map { p -> listOf(WrongNote.moved(p.stroke, WrongNote.PROBLEM_TOP - wrongBounds(p.stroke).top)) }
        activity.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val out = withContext(Dispatchers.IO) {
                    val dir = reviewDir(source)
                    val name = ReviewFiles.unique(ReviewFiles.fileName(source.name, System.currentTimeMillis(), label = "스크랩문제지")) { File(dir, it).exists() }
                    val target = File(dir, name)
                    val blank = FileUtil.tempFile(activity, "sheet", "pdf")
                    try {
                        PdfPages.create(blank, PdfPages.paperOf(renderPdf, problems[0].sourcePage), WrongNote.PAGE_W, WrongNote.PAGE_H, problems.size)
                        PdfInk.save(blank, target, pages)
                    } finally {
                        blank.delete()
                    }
                    android.media.MediaScannerConnection.scanFile(activity.applicationContext, arrayOf(target.path), null, null)
                    target
                }
                MaterialAlertDialogBuilder(activity)
                    .setTitle("문제지를 만들었습니다")
                    .setMessage("스크랩 ${problems.size}문제 · ${out.parentFile?.name}/${out.name}\n인쇄하거나 다른 앱으로 보낼 수 있습니다.")
                    .setPositiveButton("열기") { _, _ -> openFile(out) }
                    .setNeutralButton("공유 · 인쇄") { _, _ -> share(out) }
                    .setNegativeButton("닫기", null)
                    .show()
            } catch (e: Exception) {
                toast("문제지를 만들지 못했습니다.")
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    private fun share(file: File) {
        val uri = androidx.core.content.FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val send = android.content.Intent(android.content.Intent.ACTION_SEND)
            .setType("application/pdf")
            .putExtra(android.content.Intent.EXTRA_STREAM, uri)
            .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        activity.startActivity(android.content.Intent.createChooser(send, "문제지 공유 · 인쇄"))
    }

    /** [entries]를 복습한다: 오답 쪽에서 문제 그림을 찾아 쪽마다 하나씩 담은 복습용 문서를 만들어 새 탭으로 연다 */
    fun start(source: DocTab, entries: List<WrongEntry>) {
        val renderPdf = source.renderPdf ?: return
        if (source.pagesBusy) return
        val problems = collectProblems(source, entries)
        if (problems.isEmpty()) {
            toast("복습할 문제 그림을 찾지 못했습니다.")
            return
        }
        val skipped = entries.size - problems.size
        activity.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val saveFile = withContext(Dispatchers.IO) {
                    val dir = reviewDir(source)
                    File(dir, ReviewFiles.unique(ReviewFiles.fileName(source.name, System.currentTimeMillis())) { File(dir, it).exists() })
                }
                val session = ReviewSession(source, problems, saveFile)
                val out = withContext(Dispatchers.IO) {
                    val paper = PdfPages.paperOf(renderPdf, problems[0].sourcePage)
                    FileUtil.tempFile(activity, "review", "pdf").also {
                        PdfPages.create(it, paper, WrongNote.PAGE_W, WrongNote.PAGE_H, problems.size)
                    }
                }
                openReviewTab(session, out)
                if (skipped > 0) toast("문제 그림을 지운 스크랩 ${skipped}개는 뺐습니다.")
            } catch (e: Exception) {
                toast("복습 문서를 만들지 못했습니다.")
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /**
     * 복습 풀이를 저장할 폴더: 원본 문서가 있는 폴더 안의 '복습' 폴더. 원본이 앱 임시 저장소에 있거나(저장하지 않은 새 노트)
     * 폴더에 쓸 수 없으면 문서 폴더의 'DSnote/복습'
     */
    private fun reviewDir(source: DocTab): File {
        val parent = if (source.uri.scheme == "file") source.uri.path?.let(::File)?.parentFile else null
        if (parent != null && !parent.absolutePath.startsWith(activity.cacheDir.absolutePath) && parent.canWrite()) {
            File(parent, "복습").let { if (it.isDirectory || it.mkdirs()) return it }
        }
        val docs = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOCUMENTS), "DSnote/복습")
        if (docs.isDirectory || docs.mkdirs()) return docs
        return File(activity.getExternalFilesDir(null) ?: activity.filesDir, "복습").apply { mkdirs() }
    }

    // ================= 화면 =================

    /** 지금 보는 탭에 맞춰 아래 줄을 보이거나 감추고 내용을 맞춘다 */
    fun sync() {
        val t = current()
        val s = t?.review
        if (s == null) {
            bar.visibility = View.GONE
            hideAnswer()
            return
        }
        bar.visibility = View.VISIBLE
        val entry = entryOf(t)
        val index = entry?.let { e -> s.problems.indexOfFirst { it.entry === e } } ?: -1
        val graded = s.results.size
        title.text = if (entry == null) "복습" else
            "복습 ${index + 1}/${s.problems.size}  ·  #${entry.number}" + (if (entry.title.isNotBlank()) " ${entry.title}" else "") +
                if (graded > 0) "  (채점 $graded)" else ""
        val mine = entry?.let { s.results[it] }
        for ((r, b) in gradeButtons) {
            b.isEnabled = entry != null
            b.alpha = if (mine == null || mine == r) 1f else 0.4f
            b.strokeWidth = if (mine == r) dp(3) else 0
            b.strokeColor = android.content.res.ColorStateList.valueOf(Color.BLACK)
        }
        answerButton.isEnabled = entry != null
        answerButton.text = if (answerShown) "정답 숨기기" else "정답 보기"
        if (answerShown) showAnswerOf(entry)
    }

    private fun entryOf(t: DocTab?): WrongEntry? {
        val s = t?.review ?: return null
        val ink = t.ink ?: return null
        return ink.pages.getOrNull(docView.currentPage())?.let { s.entryOfPage[it] }
    }

    // ================= 정답 보기 =================

    private fun toggleAnswer() {
        if (answerShown) hideAnswer() else {
            answerShown = true
            sync()
        }
    }

    fun hideAnswer() {
        if (!answerShown && answerPanel.visibility != View.VISIBLE) return
        answerShown = false
        answerPanel.visibility = View.GONE
        answerButton.text = "정답 보기"
    }

    /** 원래 오답 쪽에서 그 오답의 칸을 그려 (문제와 전에 쓴 풀이 모두) 문서 화면 아래(가로 화면이면 오른쪽 절반)에 띄운다 */
    private fun showAnswerOf(entry: WrongEntry?) {
        val t = current() ?: return
        val s = t.review ?: return
        if (entry == null) {
            answerPanel.visibility = View.GONE
            return
        }
        placeAnswerPanel()
        s.answers[entry]?.let {
            answerImage.setImageBitmap(it)
            answerPanel.visibility = View.VISIBLE
            return
        }
        val src = s.source
        val d = src.pdf ?: return
        val ink = src.ink ?: return
        val page = ink.allWrongs().firstOrNull { it.second === entry }?.first ?: return
        activity.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val full = saver.pageBitmap(d, ink, page)
                val scale = full.width / d.sizes[page].width
                val top = (WrongNote.slotTop(entry.slot) * scale).roundToInt().coerceIn(0, full.height - 1)
                val h = (WrongNote.slotHeight(entry.slot) * scale).roundToInt().coerceIn(1, full.height - top)
                val crop = Bitmap.createBitmap(full, 0, top, full.width, h)
                if (crop !== full) full.recycle()
                s.answers[entry] = crop
                // 그리는 사이 다른 쪽으로 넘겼으면 그 쪽 것을 보여 준다
                if (answerShown && current()?.review === s && entryOf(current()) === entry) {
                    answerImage.setImageBitmap(crop)
                    answerPanel.visibility = View.VISIBLE
                }
            } catch (e: Exception) {
                toast("정답을 그리지 못했습니다.")
                hideAnswer()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /** 문서 화면이 세로면 아래 절반, 가로면 오른쪽 절반에 (아래 줄은 가리지 않게) */
    private fun placeAnswerPanel() {
        val lp = answerPanel.layoutParams as FrameLayout.LayoutParams
        val bottom = bottomOverlay.height
        val w = frame.width
        val h = frame.height
        if (w > h) {
            lp.width = w / 2
            lp.height = (h - bottom).coerceAtLeast(dp(120))
            lp.gravity = Gravity.END or Gravity.TOP
        } else {
            lp.width = ViewGroup.LayoutParams.MATCH_PARENT
            lp.height = ((h - bottom) / 2).coerceAtLeast(dp(120))
            lp.gravity = Gravity.BOTTOM
        }
        lp.bottomMargin = bottom
        answerPanel.layoutParams = lp
    }

    // ================= 채점 · 끝내기 =================

    private fun grade(r: ReviewResult) {
        val t = current() ?: return
        val s = t.review ?: return
        val entry = entryOf(t) ?: return
        s.results[entry] = r
        sync()
        val ink = t.ink ?: return
        // 아직 채점하지 않은 다음 문제로 (끝까지 갔으면 처음부터 훑는다)
        val n = ink.pages.size
        val here = docView.currentPage()
        val next = (1..n).map { (here + it) % n }.firstOrNull { i -> ink.pages[i].let { l -> s.entryOfPage[l]?.let { e -> s.results[e] == null } == true } }
        if (next != null) {
            hideAnswer()
            docView.scrollToPage(next)
        } else {
            MaterialAlertDialogBuilder(activity)
                .setTitle("모두 채점했습니다")
                .setMessage(summary(s))
                .setPositiveButton("끝내기") { _, _ -> finish(t, apply = true) }
                .setNegativeButton("계속 보기", null)
                .show()
        }
    }

    private fun summary(s: ReviewSession): String {
        val c = ReviewResult.entries.associateWith { r -> s.results.values.count { it == r } }
        return "맞음 ${c[ReviewResult.RIGHT]} · 애매 ${c[ReviewResult.UNSURE]} · 틀림 ${c[ReviewResult.WRONG]}"
    }

    /** 복습 탭을 닫으려 할 때: 채점한 것이 있거나 풀이를 썼으면 끝낼지 묻는다 */
    fun askClose(t: DocTab) {
        val s = t.review ?: return
        val wrote = (t.ink?.pages?.sumOf { it.size } ?: 0) > s.problems.size
        if (s.results.isEmpty() && !wrote) {
            finish(t, apply = false)
            return
        }
        val saved = if (wrote) "\n쓴 풀이는 '${s.saveFile.parentFile?.name}' 폴더에 자동 저장됩니다." else ""
        val msg = if (s.results.isEmpty()) "채점한 문제가 없습니다.$saved"
        else "채점한 ${s.results.size}개를 스크랩에 반영합니다. (${summary(s)})$saved"
        MaterialAlertDialogBuilder(activity)
            .setTitle("복습을 끝낼까요?")
            .setMessage(msg)
            .setPositiveButton(if (s.results.isEmpty()) "끝내기" else "반영하고 끝내기") { _, _ -> finish(t, apply = true) }
            .setNegativeButton("반영 안 함") { _, _ -> finish(t, apply = false) }
            .setNeutralButton("계속", null)
            .show()
    }

    /** 복습을 끝낸다. [apply]면 채점을 원래 문서의 오답 항목에 반영하고 결과를 보여 준다 */
    private fun finish(t: DocTab, apply: Boolean) {
        val s = t.review ?: return
        val src = s.source
        val applied = apply && s.results.isNotEmpty()
        val text = if (applied) summary(s) else null
        if (applied) {
            val today = ReviewSchedule.today()
            val ink = src.ink
            for ((e, r) in s.results) {
                e.grade(r, today)
                ink?.rebuildWrongStrokes(e)  // 머리줄에 복습 단계·다음 복습일·정답률을 새로 그린다
            }
            ink?.wrongEdited()
        }
        // 쓴 풀이가 있으면 마지막 필기까지 파일에 적은 뒤 닫는다
        val wrote = (t.ink?.pages?.sumOf { it.size } ?: 0) > s.problems.size
        if (wrote) saveNow(t)
        release(s)
        removeTab(t)
        val savedNote = if (wrote) "복습 풀이를 저장했습니다: ${s.saveFile.parentFile?.name}/${s.saveFile.name}" else null
        if (text == null && savedNote != null) toast(savedNote)
        if (text != null) {
            MaterialAlertDialogBuilder(activity)
                .setTitle("복습 끝")
                .setMessage("$text\n" + (savedNote?.let { "$it\n" } ?: "") + "\n복습 기록은 문서를 저장해야 남습니다. 지금 저장할까요?")
                .setPositiveButton("저장") { _, _ -> saver.save(src, asNew = false) }
                .setNegativeButton("나중에", null)
                .show()
        }
    }

    /** 탭이 닫힐 때 그려 둔 정답 그림을 놓는다 */
    fun release(s: ReviewSession) {
        hideAnswer()
        answerImage.setImageDrawable(null)
        s.answers.values.forEach { it.recycle() }
        s.answers.clear()
    }
}
