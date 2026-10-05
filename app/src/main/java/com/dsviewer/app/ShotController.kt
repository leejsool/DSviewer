package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.RectF
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 올가미 ▸ 영역 스크린샷: 답지 같은 문서에서 필요한 영역만 끌어 골라 그림으로 찍어 복사해 두고,
 * 공부하는 문서(다른 탭이나 분할 보기의 다른 칸)에서 '붙여넣기'로 넣는다. 붙이는 쪽은 올가미 복사·붙여넣기와 같다.
 * 영역 고르기는 오답 담기와 같은 [WrongPicker]를 쓴다.
 */
internal class ShotController(
    private val activity: AppCompatActivity,
    /** 지금 고른 칸의 문서 화면 (분할 보기에서는 칸이 바뀐다) */
    private val docViewOf: () -> DocumentView,
    private val textEditor: InlineTextEditor,
    private val progress: View,
    /** 지금 보는 탭 */
    private val current: () -> DocTab?,
    private val toast: (String) -> Unit,
    /** 복사해 둔 뒤: 툴바의 '붙여넣기' 단추를 보이게 한다 */
    private val onCopied: () -> Unit,
) {
    private val docView get() = docViewOf()

    /** '찍을 영역을 끌어 고르세요' 안내 (고르거나 취소하면 닫는다) */
    private var hint: Snackbar? = null

    fun startPick() {
        textEditor.commit()
        if (current()?.pagesBusy == true) return
        onPickEnded()
        if (!docView.startWrongPick(forShot = true)) return
        hint = Snackbar.make(activity.findViewById(R.id.docFrame), "찍을 영역을 끌어서 고르세요. (두 손가락: 이동 · 확대)", Snackbar.LENGTH_INDEFINITE)
            .setAction("취소") { docView.cancelWrongPick() }
            .addCallback(object : Snackbar.Callback() {
                override fun onDismissed(bar: Snackbar?, event: Int) {
                    // 새 안내로 바뀌며 닫힌 옛 안내는 새로 시작한 고르기를 건드리지 않는다
                    if (hint !== bar) return
                    hint = null
                    docView.cancelWrongPick()
                }
            })
            .also { it.show() }
    }

    /** 영역 고르기가 끝나면(골랐거나 취소) 안내를 닫는다 */
    fun onPickEnded() {
        hint?.let {
            hint = null
            it.dismiss()
        }
    }

    /** 고른 영역을 PDF 내용 + 필기 그대로 그림으로 만들어 복사해 둔다 */
    fun capture(page: Int, rect: RectF) {
        val t = current() ?: return
        val d = t.pdf ?: return
        val inkDoc = t.ink ?: return
        val view = docView
        activity.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val img = try {
                val scale = WrongNote.captureScale(rect)
                val w = (rect.width() * scale).roundToInt().coerceAtLeast(1)
                val h = (rect.height() * scale).roundToInt().coerceAtLeast(1)
                val base = withContext(d.dispatcher) { d.render(page, scale, rect.left, rect.top, w, h) }
                val bmp = WrongNote.compose(base, inkDoc.pages[page].toList(), rect, scale)
                // 바탕이 흰색이라 JPEG로 담아 PDF가 커지지 않게 한다
                val bytes = java.io.ByteArrayOutputStream().use {
                    bmp.compress(Bitmap.CompressFormat.JPEG, 92, it)
                    it.toByteArray()
                }
                InkImage(bmp, bytes)
            } catch (e: Exception) {
                toast("영역을 가져오지 못했습니다.")
                view.clearWrongRect()
                return@launch
            } finally {
                if (current() === t && !t.pagesBusy) progress.visibility = View.GONE
            }
            view.clearWrongRect()
            // 그새 칸이 바뀌었어도 지금 고른 칸이 복사본을 쥔다 (붙여넣을 칸)
            docView.copyImage(img, rect.width(), rect.height())
            onCopied()
            toast("영역을 복사했습니다. 공부할 문서에서 올가미 도구의 '붙여넣기'를 누르세요.")
        }
    }
}
