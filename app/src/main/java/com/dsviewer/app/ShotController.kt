package com.dsviewer.app

import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * 올가미 ▸ 영역 스크린샷: 답지 같은 문서에서 필요한 영역만 끌어 골라 그림으로 찍는다. 받는 곳은 셋:
 * - [DEST_PASTE]: 복사해 두고 공부하는 문서(다른 탭이나 분할 보기의 다른 칸)에서 '붙여넣기'로 넣는다. 붙이는 쪽은 올가미 복사·붙여넣기와 같다.
 * - [DEST_CLIPBOARD]: 기기 클립보드에 이미지로 복사한다 (다른 앱에 붙여넣기).
 * - [DEST_GALLERY]: 사진첩(Pictures/DSnote)에 PNG로 저장한다.
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
    /** 클립보드·사진첩으로 보낼 때 쓴다 */
    private val saver: DocSaver,
    /** 복사해 둔 뒤: 올가미 도구로 바꾸고 붙여넣기 알림을 띄운다 */
    private val onCopied: () -> Unit,
) {
    private val docView get() = docViewOf()

    /** '찍을 영역을 끌어 고르세요' 안내 (고르거나 취소하면 닫는다) */
    private var hint: Snackbar? = null

    /** 지금 고르는 영역을 보낼 곳 ([DEST_PASTE] 등) */
    private var dest = DEST_PASTE

    /** 영역 스크린샷 시작: 먼저 보낼 곳(클립보드 · 사진첩 · 문서에 붙이기)을 고르고, 그다음 영역을 끌어 고른다 */
    fun startPick() {
        textEditor.commit()
        if (current()?.pagesBusy == true) return
        onPickEnded()
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val outline = com.google.android.material.color.MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOutlineVariant, Color.LTGRAY)
        val onSurface = com.google.android.material.color.MaterialColors.getColor(activity, com.google.android.material.R.attr.colorOnSurface, Color.BLACK)
        var dialog: androidx.appcompat.app.AlertDialog? = null
        // 한눈에 알아보도록 큰 아이콘 세 칸 (아이콘 아래에 짧은 설명)
        val choices = listOf(
            Triple(R.drawable.ic_paste, "클립보드로\n복사", DEST_CLIPBOARD),
            Triple(R.drawable.ic_image, "사진첩에\n저장", DEST_GALLERY),
            Triple(R.drawable.ic_paste_ds, "문서에\n붙이기", DEST_PASTE),
        )
        val row = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(8), dp(16), dp(0))
        }
        for ((icon, label, to) in choices) {
            val tile = LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(8), dp(16), dp(8), dp(12))
                background = RippleDrawable(
                    ColorStateList.valueOf(0x22000000),
                    GradientDrawable().apply { cornerRadius = dp(16).toFloat(); setStroke(dp(1), outline) },
                    null,
                )
                contentDescription = label.replace("\n", " ")
                setOnClickListener {
                    dialog?.dismiss()
                    beginPick(to)
                }
                addView(ImageView(activity).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(40), dp(40)))
                addView(TextView(activity).apply {
                    text = label
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setTextColor(onSurface)
                    setPadding(0, dp(8), 0, 0)
                })
            }
            row.addView(tile, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
            })
        }
        dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("영역 스크린샷")
            .setView(row)
            .setNegativeButton("취소", null)
            .show()
    }

    private fun beginPick(dest: Int) {
        if (current()?.pagesBusy == true) return
        this.dest = dest
        if (!docView.startWrongPick(forShot = true)) return
        hint = SnackHost.make(activity, "찍을 영역을 끌어서 고르세요. (두 손가락: 이동 · 확대)", Snackbar.LENGTH_INDEFINITE)
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
        val to = dest
        activity.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val img = try {
                val scale = WrongNote.captureScale(rect)
                val w = (rect.width() * scale).roundToInt().coerceAtLeast(1)
                val h = (rect.height() * scale).roundToInt().coerceAtLeast(1)
                val base = withContext(d.dispatcher) { d.render(page, scale, rect.left, rect.top, w, h) }
                val bmp = WrongNote.compose(base, inkDoc.pages[page].toList(), rect, scale)
                if (to != DEST_PASTE) {
                    // 문서에 붙이지 않고 클립보드·사진첩으로: 글자가 또렷하도록 PNG(무손실)로 보낸다
                    val name = "${FileNames.safe(FileUtil.baseName(t.name))}_${page + 1}쪽_영역_${android.text.format.DateFormat.format("HHmmss", java.util.Date())}.png"
                    withContext(Dispatchers.IO) {
                        if (to == DEST_CLIPBOARD) saver.copyBitmapToClipboard(bmp) else saver.saveBitmapToGallery(bmp, name)
                    }
                    bmp.recycle()
                    view.clearWrongRect()
                    toast(
                        if (to == DEST_CLIPBOARD) "영역을 클립보드에 복사했습니다. 붙여넣을 앱에서 붙여넣기를 누르세요."
                        else "영역을 사진첩(Pictures/DSnote)에 저장했습니다."
                    )
                    return@launch
                }
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
        }
    }

    companion object {
        /** 복사해 두고 '붙여넣기'로 문서에 넣는다 */
        const val DEST_PASTE = 0
        /** 기기 클립보드에 이미지로 복사 */
        const val DEST_CLIPBOARD = 1
        /** 사진첩에 PNG로 저장 */
        const val DEST_GALLERY = 2
    }
}
