package com.dsviewer.app

import android.graphics.Color
import android.graphics.Typeface
import android.text.InputType
import android.text.Layout
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import androidx.core.graphics.ColorUtils
import kotlin.math.roundToInt

/**
 * 포스트잇 메모의 글을 따로 창 없이 메모 위에서 바로 친다. 펼친 메모의 몸통 자리에 메모 색 입력칸이 겹쳐 뜨고
 * (쪽에 그려질 글과 같은 자리·크기), 스크롤·확대를 따라간다. 메모 밖을 누르거나 다른 일을 하면 [commit]으로 글을 넣는다
 * (실행 취소 한 단계, [DocumentView.setNoteText]).
 */
class NoteInlineEditor(private var host: TextEditHost, private var docView: DocumentView) {
    private val density = host.resources.displayMetrics.density

    private val edit: EditText = EditText(host.context).apply {
        includeFontPadding = false
        isFallbackLineSpacing = true
        breakStrategy = Layout.BREAK_STRATEGY_SIMPLE
        hyphenationFrequency = Layout.HYPHENATION_FREQUENCY_NONE
        typeface = Typeface.DEFAULT
        setTextColor(0xFF33302A.toInt())
        hint = "메모 입력"
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN
        gravity = Gravity.TOP or Gravity.START
        setHorizontallyScrolling(false)
        setLineSpacing(0f, 1f)
        if (android.os.Build.VERSION.SDK_INT >= 35) isLocalePreferredLineHeightForMinimumUsed = false
        pivotX = 0f
        pivotY = 0f
        visibility = View.GONE
    }

    var isEditing = false
        private set

    private var page = -1
    private var note: Stroke? = null
    private var appliedScale = -1f

    /** 글자판 변화를 지켜보고 있는 층들 (init보다 먼저 만들어져야 한다) */
    private val watched = HashSet<TextEditHost>()

    init {
        host.addView(edit, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        watchLayout(host)
    }

    /** 화면 키보드가 올라와 판이 줄면 메모가 가려지지 않게 문서를 올린다 */
    private fun watchLayout(h: TextEditHost) {
        if (!watched.add(h)) return
        h.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (isEditing && h === host && bottom - top != oldBottom - oldTop) host.post { ensureVisible() }
        }
    }

    /** 분할 보기에서 입력칸을 다른 칸으로 옮긴다. 치던 글은 먼저 지금 칸의 메모에 넣는다 */
    fun rebind(newHost: TextEditHost, newView: DocumentView) {
        if (newHost === host && newView === docView) return
        commit()
        (edit.parent as? ViewGroup)?.removeView(edit)
        host = newHost
        docView = newView
        host.addView(edit, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        watchLayout(host)
    }

    /** 펼친 메모 [st]의 몸통에서 글 치기를 시작한다 (끝에 커서) */
    fun start(page: Int, st: Stroke) {
        commit()
        val n = st.note ?: return
        if (n.collapsed) return
        this.page = page
        note = st
        edit.setText(n.text)
        edit.setBackgroundColor(ColorUtils.setAlphaComponent(st.color, 255))
        edit.setSelection(edit.text.length)
        isEditing = true
        edit.visibility = View.VISIBLE
        appliedScale = -1f
        reposition()
        edit.requestFocus()
        val imm = host.context.getSystemService(InputMethodManager::class.java)
        edit.post {
            imm.showSoftInput(edit, 0)
            ensureVisible()
        }
    }

    /** 친 글을 메모에 넣고 입력칸을 닫는다 (글이 그대로면 아무것도 하지 않는다) */
    fun commit() {
        if (!isEditing) return
        isEditing = false
        val st = note
        note = null
        val text = edit.text.toString().trimEnd()
        val imm = host.context.getSystemService(InputMethodManager::class.java)
        imm.hideSoftInputFromWindow(edit.windowToken, 0)
        edit.clearFocus()
        edit.visibility = View.GONE
        if (st != null) docView.setNoteText(page, st, text)
    }

    /** 문서가 스크롤·확대되면 입력칸을 메모 몸통에 맞춰 따라 움직인다 */
    fun reposition() {
        val st = note ?: return
        if (!isEditing) return
        val n = st.note ?: return
        val sc = docView.viewScale
        if (sc != appliedScale) {
            appliedScale = sc
            edit.setTextSize(TypedValue.COMPLEX_UNIT_PX, StickyNote.TEXT_SIZE * sc)
            val pad = (StickyNote.PAD * sc).roundToInt()
            edit.setPadding(pad, pad, pad, pad)
            edit.setWidth((n.w * sc).roundToInt())
            edit.setHeight(((n.h - StickyNote.HEADER) * sc).roundToInt())
        }
        val p = docView.pageToScreen(page, st.x(1), st.y(1) + StickyNote.HEADER) ?: return
        if (edit.translationX != p.x) edit.translationX = p.x
        if (edit.translationY != p.y) edit.translationY = p.y
    }

    /** 메모 아래쪽이 키보드·아래 줄에 가려지면 보이는 곳까지 문서를 올린다 */
    private fun ensureVisible() {
        if (!isEditing || host.height == 0) return
        val bottom = edit.translationY + edit.height
        val visBottom = host.height - docView.bottomInset - 12 * density
        if (bottom > visBottom) docView.scrollByPx(bottom - visBottom)
    }
}
