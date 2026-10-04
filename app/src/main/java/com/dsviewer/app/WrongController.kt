package com.dsviewer.app

import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * 삽입 ▸ 오답 담기와 오답노트 목록: 문제 영역을 골라 PDF·필기 그대로 캡처해 문서 맨 뒤 오답 쪽에 담고,
 * 담은 오답을 목록에서 거르고 찾아가고 고친다. 탭과 화면은 뷰어가 쥐고 있고 여기서는 넘겨받은 것만 쓴다.
 */
internal class WrongController(
    private val activity: AppCompatActivity,
    prefs: SharedPreferences,
    private val docView: DocumentView,
    private val textEditor: InlineTextEditor,
    private val progress: View,
    /** 지금 보는 탭 */
    private val current: () -> DocTab?,
    private val toast: (String) -> Unit,
    /** 쪽을 넣고 빼며 필기를 함께 고친다 (뷰어의 editPages) */
    private val editPages: (DocTab, (File, File) -> Unit, (() -> Unit)?, (MutableList<MutableList<Stroke>>) -> Int) -> Unit,
) {
    private val wrongUi by lazy { WrongUi(activity, prefs) }

    /** '문제 영역을 끌어 고르세요' 안내 (고르거나 취소하면 닫는다) */
    private var wrongHint: Snackbar? = null

    /** 삽입 ▸ 오답 담기: 안내를 띄우고, 끌어서 고른 네모 영역을 오답노트에 담는다 */
    fun startPick() {
        textEditor.commit()
        if (current()?.pagesBusy == true) return
        if (!docView.startWrongPick()) return
        wrongHint?.dismiss()
        wrongHint = Snackbar.make(activity.findViewById(R.id.docFrame), "오답으로 담을 문제 영역을 끌어서 고르세요. (두 손가락: 이동 · 확대)", Snackbar.LENGTH_INDEFINITE)
            .setAction("취소") { docView.cancelWrongPick() }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(bar: Snackbar?, event: Int) {
                    // 새 안내로 바뀌며 닫힌 옛 안내는 새로 시작한 고르기를 건드리지 않는다
                    if (wrongHint !== bar) return
                    wrongHint = null
                    docView.cancelWrongPick()
                }
            })
            .also { it.show() }
    }

    private fun wrongTagsByUse(inkDoc: InkDocument): List<String> =
        inkDoc.allWrongs().flatMap { it.second.tags }.groupingBy { it }.eachCount().entries
            .sortedByDescending { it.value }.map { it.key }

    /** 고른 영역을 PDF 내용 + 필기 그대로 그림으로 만들어 분류 창을 띄운다 */
    fun capture(page: Int, rect: RectF) {
        val t = current() ?: return
        val d = t.pdf ?: return
        val inkDoc = t.ink ?: return
        activity.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val capture = try {
                val scale = WrongNote.captureScale(rect)
                val w = (rect.width() * scale).roundToInt().coerceAtLeast(1)
                val h = (rect.height() * scale).roundToInt().coerceAtLeast(1)
                val base = withContext(d.dispatcher) { d.render(page, scale, rect.left, rect.top, w, h) }
                WrongNote.compose(base, inkDoc.pages[page].toList(), rect, scale)
            } catch (e: Exception) {
                toast("영역을 가져오지 못했습니다.")
                docView.clearWrongRect()
                return@launch
            } finally {
                if (current() === t && !t.pagesBusy) progress.visibility = View.GONE
            }
            if (current() !== t || t.ink !== inkDoc) {
                docView.clearWrongRect()
                return@launch
            }
            wrongUi.showCapture(
                capture, wrongTagsByUse(inkDoc),
                onOk = { choice -> addWrong(t, page, rect, capture, choice) },
                onRetry = { startPick() },
                onDismiss = { docView.clearWrongRect() },
            )
        }
    }

    /**
     * 오답 한 문제를 문서 맨 뒤의 오답 쪽에 담는다. 반 쪽 배치인데 맨 뒤 쪽이 위 칸만 쓴 오답 쪽이면 그 아래 칸에,
     * 아니면 새 쪽을 맨 뒤에 붙인다. 원문 쪽에는 '오답 #번호' 배지를 붙인다
     */
    private fun addWrong(t: DocTab, srcPage: Int, rect: RectF, capture: Bitmap, c: WrongUi.Choice) {
        val d = t.pdf ?: return
        val inkDoc = t.ink ?: return
        if (t.pagesBusy) return
        val srcList = inkDoc.pages.getOrNull(srcPage) ?: return
        val last = d.pageCount - 1
        val lastEntries = inkDoc.wrongEntries(last)
        val reuse = c.half && lastEntries.size == 1 && lastEntries[0].slot == 1
        val slot = when {
            !c.half -> 0
            reuse -> 2
            else -> 1
        }
        val number = inkDoc.nextWrongNumber()
        val entry = WrongEntry(number, c.symbol, c.tags, c.title, WrongNote.today(), slot, srcList, RectF(rect))
        val taken = srcList.filter { it.isWrongBadge() }.map { wrongBounds(it) }
        val built = WrongNote.build(entry, capture, rect, d.sizes[srcPage].width, d.sizes[srcPage].height, taken)
        if (reuse) {
            val adds = listOfNotNull(last to built.header, last to built.body, built.badge?.let { srcPage to it })
            inkDoc.addWrongToPage(last, entry, adds)
            docView.scrollToPageY(last, WrongNote.slotTop(slot))
            toast("오답 #$number 을(를) ${last + 1}쪽 아래 칸에 담았습니다.")
            return
        }
        val index = d.pageCount
        val onDone = {
            built.badge?.let { badge ->
                val i = inkDoc.pages.indexOfFirst { it === srcList }
                if (i >= 0) inkDoc.add(i, badge)
            }
            toast("오답 #$number 을(를) ${index + 1}쪽에 담았습니다. 아래 빈 곳에 풀이를 써 보세요.")
        }
        editPages(t, { src, out -> PdfPages.insert(src, out, index, c.paper, WrongNote.PAGE_W, WrongNote.PAGE_H) }, onDone) { pages ->
            val list = mutableListOf(built.header, built.body)
            pages.add(index, list)
            inkDoc.attachWrong(list, entry)
            index
        }
    }

    /** 링크·목록으로 [page]쪽 [y] 높이로 간다 (실행 취소하면 돌아온다) */
    fun goToSpot(page: Int, y: Float) {
        val before = docView.topSpot()
        val to = Spot(page, y)
        docView.scrollToPageY(to.page, to.y)
        if (before != null) current()?.ink?.jumped(Spot(before.first, before.second), to)
    }

    /** 오답노트 목록: 기호·해시태그로 거르고, 누르면 그 오답으로 간다 */
    fun showList() {
        val t = current() ?: return
        val inkDoc = t.ink ?: return
        wrongUi.showList(
            provider = { inkDoc.allWrongs() },
            onGo = { page, e -> goToSpot(page, WrongNote.slotTop(e.slot)) },
            onSource = { e ->
                val idx = inkDoc.pages.indexOfFirst { it === e.srcList }
                if (idx >= 0) goToSpot(idx, ((e.srcRect?.top ?: 0f) - 24f).coerceAtLeast(0f))
            },
            onEdit = { e, done -> editWrong(t, e, done) },
        )
    }

    /** 담은 오답의 기호·해시태그·제목을 고치고 머리줄과 원문 쪽 배지를 다시 그린다 */
    private fun editWrong(t: DocTab, e: WrongEntry, done: () -> Unit) {
        val inkDoc = t.ink ?: return
        wrongUi.showEdit(e, wrongTagsByUse(inkDoc)) { symbol, tags, title ->
            e.symbol = symbol
            e.tags = tags
            e.title = title
            for ((i, list) in inkDoc.pages.withIndex()) for (st in list.toList()) {
                val role = st.role ?: continue
                if (st.image == null || st.count < 4 || role.length < 3 || role.substring(2).toIntOrNull() != e.number) continue
                val box = wrongBounds(st)
                when {
                    role.startsWith(WrongNote.ROLE_HEADER) -> inkDoc.swapWrongStroke(i, st, WrongNote.rebuildHeader(e, box))
                    role.startsWith(WrongNote.ROLE_BADGE) -> inkDoc.swapWrongStroke(i, st, WrongNote.rebuildBadge(e, box))
                }
            }
            inkDoc.wrongEdited()
            done()
        }
    }

    /** 영역 고르기가 끝나면(골랐거나 취소) 안내를 닫는다 */
    fun onPickEnded() {
        wrongHint?.let {
            wrongHint = null
            it.dismiss()
        }
    }
}
