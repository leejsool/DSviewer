package com.dsviewer.app

import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import kotlin.math.roundToInt

/**
 * 문서 저장: 저장 · 다른 이름으로 저장 · 이미지로 저장 (사진첩 Pictures/DSnote).
 * 탭과 화면은 뷰어가 쥐고 있고, 여기서는 넘겨받은 것만 쓴다.
 */
internal class DocSaver(
    private val activity: AppCompatActivity,
    private val docView: DocumentView,
    private val textEditor: InlineTextEditor,
    private val progress: View,
    /** 지금 보는 탭 */
    private val current: () -> DocTab?,
    /** '다른 이름으로 저장' 창(파일 만들기)을 띄운다 */
    private val launchSaveAs: (suggestedName: String) -> Unit,
    private val removeTab: (DocTab) -> Unit,
    private val updateTabTitle: (DocTab) -> Unit,
) {
    /** '다른 이름으로 저장' 창을 띄운 탭 */
    private var saveTarget: DocTab? = null
    /** 저장이 끝나면 닫을 탭 */
    var closeAfterSave: DocTab? = null

    /** '다른 이름으로 저장' 창이 끝났을 때 저장할 탭을 꺼낸다 (한 번만) */
    fun takeSaveTarget(): DocTab? = saveTarget.also { saveTarget = null }

    /** 저장 ▾: 저장 / 다른 이름으로 저장 / 이미지로 저장 */
    fun showSaveMenu(anchor: View) {
        val t = current() ?: return
        if (t.ink == null) return
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, 1, 0, "저장").setIcon(R.drawable.ic_save)
        popup.menu.add(0, 2, 1, "다른 이름으로 저장").setIcon(R.drawable.ic_save_as)
        popup.menu.add(0, 3, 2, "이미지로 저장").setIcon(R.drawable.ic_image)
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                1 -> save(t, asNew = false)
                2 -> save(t, asNew = true)
                3 -> askExportImages(t)
            }
            true
        }
        popup.show()
    }

    /** 이미지로 저장할 쪽 고르기: 지금 쪽만 / 모든 쪽 / n쪽부터 m쪽까지 */
    fun askExportImages(t: DocTab) {
        val d = t.pdf ?: return
        val page = docView.currentPage().coerceIn(0, d.pageCount - 1)
        val choices = arrayOf("지금 보는 ${page + 1}쪽만", "모든 쪽 (${d.pageCount}쪽)", "쪽 범위 지정 (n쪽부터 m쪽까지)")
        MaterialAlertDialogBuilder(activity)
            .setTitle("이미지로 저장")
            .setItems(choices) { _, which ->
                when (which) {
                    0 -> exportImages(t, listOf(page))
                    1 -> exportImages(t, (0 until d.pageCount).toList())
                    else -> askPageRange(activity, d.pageCount, page, d.pageCount - 1, "이미지로 저장할 쪽", "저장") { n, m ->
                        exportImages(t, (n - 1 until m).toList())
                    }
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /**
     * 쪽을 필기·그림과 함께 PNG로 그려 사진첩(Pictures/DSnote)에 넣는다. 150dpi (A4 한 쪽 약 1240×1754)
     */
    fun exportImages(t: DocTab, pages: List<Int>) {
        val d = t.pdf ?: return
        val inkDoc = t.ink ?: return
        val base = FileUtil.baseName(t.name).replace(Regex("[\\\\/:*?\"<>|]"), "_")
        activity.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                for (p in pages) {
                    val bmp = pageBitmap(d, inkDoc, p)
                    withContext(Dispatchers.IO) { saveToGallery(bmp, "${base}_${p + 1}쪽.png") }
                    bmp.recycle()
                }
                Toast.makeText(
                    activity, "${pages.size}장을 사진첩(Pictures/DSnote)에 저장했습니다.", Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(activity)
                    .setMessage("이미지로 저장하지 못했습니다.\n${e.message ?: e.javaClass.simpleName}")
                    .setPositiveButton("확인", null)
                    .show()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /** 쪽을 필기·그림과 함께 [EXPORT_DPI]로 그린다 */
    suspend fun pageBitmap(d: PdfDoc, inkDoc: InkDocument, p: Int): Bitmap {
        val scale = EXPORT_DPI / 72f
        val size = d.sizes[p]
        val w = (size.width * scale).roundToInt().coerceAtLeast(1)
        val h = (size.height * scale).roundToInt().coerceAtLeast(1)
        val bmp = withContext(d.dispatcher) { d.render(p, scale, 0f, 0f, w, h) }
        // 필기 얹기 (획의 경로 캐시를 문서 화면과 같이 쓰므로 메인 스레드에서). 그림을 먼저
        val c = Canvas(bmp)
        c.scale(scale, scale)
        val paint = inkPaint()
        inkDoc.pages.getOrNull(p)?.sortedBy { inkLayer(it) }?.forEach { drawInkStroke(c, paint, it) }
        return bmp
    }

    private fun saveToGallery(bmp: Bitmap, name: String) {
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/DSnote")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = activity.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: error("사진첩에 저장할 곳을 만들지 못했습니다.")
        try {
            activity.contentResolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: error("사진첩에 쓸 수 없습니다.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            activity.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            activity.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    private fun suggestedName(t: DocTab): String {
        if (t.isNewNote) return "노트 ${DateFormat.format("yyyy-MM-dd", Date())}.pdf"
        val base = FileUtil.baseName(t.name)
        return if (t.type == DocType.PDF) "${base}_필기.pdf" else "$base.pdf"
    }

    fun save(t: DocTab, asNew: Boolean) {
        if (t.ink == null) return
        if (current() === t) textEditor.commit()
        if (t.pagesBusy) {
            Toast.makeText(activity, "쪽을 바꾸는 중입니다. 잠시 뒤에 저장해 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (!asNew && t.canOverwrite && t.type == DocType.PDF) saveTo(t, t.uri, overwrite = true)
        else saveAs(t)
    }

    private fun saveAs(t: DocTab) {
        saveTarget = t
        launchSaveAs(suggestedName(t))
    }

    fun saveTo(t: DocTab, target: Uri, overwrite: Boolean) {
        val inkDoc = t.ink ?: return
        val src = t.sourcePdf ?: return
        val snapshot = inkDoc.snapshot()
        val marks = inkDoc.bookmarkedPages()
        val wrongSave = inkDoc.wrongSave()
        activity.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                withContext(Dispatchers.IO) {
                    val tmp = FileUtil.tempFile(activity, "out", "pdf")
                    try {
                        PdfInk.save(src, tmp, snapshot, marks, wrongSave)
                        val out = activity.contentResolver.openOutputStream(target, "wt") ?: error("저장 위치를 열 수 없습니다.")
                        out.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
                    } finally {
                        tmp.delete()
                    }
                }
                // 파일 탐색기의 '모든 문서' 목록에 바뀐 날짜가 바로 보이도록 색인 갱신
                if (target.scheme == "file") target.path?.let {
                    MediaScannerConnection.scanFile(activity, arrayOf(it), null, null)
                }
                if (!overwrite) adoptSavedFile(t, target)
                inkDoc.markSaved()
                Toast.makeText(activity, "'${t.name}' 저장했습니다.", Toast.LENGTH_SHORT).show()
                if (closeAfterSave === t) {
                    closeAfterSave = null
                    removeTab(t)
                }
            } catch (e: Exception) {
                if (overwrite) {
                    t.canOverwrite = false
                    Toast.makeText(activity, "원본에 저장할 수 없어 다른 이름으로 저장합니다.", Toast.LENGTH_LONG).show()
                    saveAs(t)
                } else {
                    closeAfterSave = null
                    MaterialAlertDialogBuilder(activity)
                        .setMessage("저장하지 못했습니다.\n${e.message ?: e.javaClass.simpleName}")
                        .setPositiveButton("확인", null)
                        .show()
                }
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /**
     * 다른 이름으로 저장(새 노트·한글 문서의 첫 저장 포함)하면 그 파일을 이 탭의 문서로 삼는다
     * (다음부터는 '저장'이 그 파일에 덮어쓴다)
     */
    private fun adoptSavedFile(t: DocTab, target: Uri) {
        runCatching {
            activity.contentResolver.takePersistableUriPermission(
                target, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        }
        t.uri = target
        t.isNewNote = false
        t.canOverwrite = true
        t.type = DocType.PDF  // 한글 문서도 PDF로 저장되므로
        t.name = FileUtil.displayName(activity, target)
        Recents.add(activity, target.toString(), t.name)
        updateTabTitle(t)
    }

    companion object {
        /** 이미지로 저장할 때 해상도 */
        private const val EXPORT_DPI = 150f
    }
}
