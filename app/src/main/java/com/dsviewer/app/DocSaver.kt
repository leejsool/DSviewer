package com.dsviewer.app

import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 문서 저장: 저장 · 다른 이름으로 저장 · 이미지로 저장 (사진첩 Pictures/DSnote).
 * 탭과 화면은 뷰어가 쥐고 있고, 여기서는 넘겨받은 것만 쓴다.
 */
internal class DocSaver(
    private val activity: AppCompatActivity,
    /** 지금 고른 칸의 문서 화면 (분할 보기에서는 칸이 바뀐다) */
    private val docViewOf: () -> DocumentView,
    private val textEditor: InlineTextEditor,
    private val progress: View,
    /** 지금 보는 탭 */
    private val current: () -> DocTab?,
    /** '다른 이름으로 저장' 창(파일 만들기)을 띄운다 */
    private val launchSaveAs: (suggestedName: String) -> Unit,
    private val removeTab: (DocTab) -> Unit,
    private val updateTabTitle: (DocTab) -> Unit,

) {
    private val docView get() = docViewOf()
    /** '다른 이름으로 저장' 창을 띄운 탭 */
    private var saveTarget: DocTab? = null
    /** 저장이 끝나면 닫을 탭 */
    var closeAfterSave: DocTab? = null

    /** '다른 이름으로 저장' 창이 끝났을 때 저장할 탭을 꺼낸다 (한 번만) */
    fun takeSaveTarget(): DocTab? = saveTarget.also { saveTarget = null }

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
        val base = FileNames.safe(FileUtil.baseName(t.name))
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

    // ----- 올가미로 고른 부분만 이미지로 -----

    /** 올가미 선택 막대의 '이미지' 단추: 고른 부분을 클립보드에 이미지로 복사 / 사진첩에 저장 */
    fun showSelectionImageMenu(anchor: View) {
        val t = current() ?: return
        val d = t.pdf ?: return
        val inkDoc = t.ink ?: return
        val (page, region) = docView.selectionRegion() ?: return
        val popup = PopupMenu(activity, anchor)
        popup.menu.add(0, 1, 0, "이미지로 복사 (클립보드)").setIcon(R.drawable.ic_copy)
        popup.menu.add(0, 2, 1, "사진첩에 저장").setIcon(R.drawable.ic_image)
        popup.setForceShowIcon(true)
        popup.setOnMenuItemClickListener { item ->
            exportRegion(t, d, inkDoc, page, region, toClipboard = item.itemId == 1)
            true
        }
        popup.show()
    }

    private fun exportRegion(t: DocTab, d: PdfDoc, inkDoc: InkDocument, page: Int, region: RectF, toClipboard: Boolean) {
        activity.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            try {
                val bmp = regionBitmap(d, inkDoc, page, region)
                if (toClipboard) {
                    withContext(Dispatchers.IO) {
                        val file = newClipFile()
                        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        putOnClipboard(file)
                    }
                    Toast.makeText(activity, "이미지를 클립보드에 복사했습니다. 붙여넣을 앱에서 붙여넣기를 누르세요.", Toast.LENGTH_LONG).show()
                } else {
                    val base = FileNames.safe(FileUtil.baseName(t.name))
                    val stamp = DateFormat.format("HHmmss", Date())
                    withContext(Dispatchers.IO) { saveToGallery(bmp, "${base}_${page + 1}쪽_선택_$stamp.png") }
                    Toast.makeText(activity, "선택한 부분을 사진첩(Pictures/DSnote)에 저장했습니다.", Toast.LENGTH_LONG).show()
                }
                bmp.recycle()
            } catch (e: Exception) {
                MaterialAlertDialogBuilder(activity)
                    .setMessage("이미지로 만들지 못했습니다.\n${e.message ?: e.javaClass.simpleName}")
                    .setPositiveButton("확인", null)
                    .show()
            } finally {
                progress.visibility = View.GONE
            }
        }
    }

    /** 쪽의 [region](쪽 좌표 pt)만 필기·그림과 함께 그린다. 작은 부분은 글자가 또렷하도록 더 크게 그린다 */
    private suspend fun regionBitmap(d: PdfDoc, inkDoc: InkDocument, p: Int, region: RectF): Bitmap {
        var scale = EXPORT_DPI / 72f
        if (region.width() * scale < REGION_MIN_PX) scale = (REGION_MIN_PX / region.width()).coerceAtMost(REGION_MAX_SCALE)
        val px = region.width() * scale * region.height() * scale
        if (px > REGION_MAX_PIXELS) scale *= sqrt(REGION_MAX_PIXELS / px)
        val w = (region.width() * scale).roundToInt().coerceAtLeast(1)
        val h = (region.height() * scale).roundToInt().coerceAtLeast(1)
        val bmp = withContext(d.dispatcher) { d.render(p, scale, region.left, region.top, w, h) }
        val c = Canvas(bmp)
        c.scale(scale, scale)
        c.translate(-region.left, -region.top)
        val paint = inkPaint()
        inkDoc.pages.getOrNull(p)?.sortedBy { inkLayer(it) }?.forEach { drawInkStroke(c, paint, it) }
        return bmp
    }

    /** 클립보드로 보낼 PNG를 쓸 새 파일 (앱 캐시 share/). 지난번 것은 지운다 */
    private fun newClipFile(): File {
        val dir = File(activity.cacheDir, "share").apply { mkdirs() }
        dir.listFiles { f -> f.name.startsWith("clip_") }?.forEach { it.delete() }
        return File(dir, "clip_${System.currentTimeMillis()}.png")
    }

    /** [file](PNG)을 클립보드에 이미지로 담는다. 이미지는 파일 주소로 담긴다 (붙여넣는 앱이 읽을 수 있게 FileProvider 주소) */
    private fun putOnClipboard(file: File) {
        val uri = FileProvider.getUriForFile(activity, "${activity.packageName}.files", file)
        val cm = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newUri(activity.contentResolver, "DSnote 이미지", uri))
    }

    /** [bmp]를 클립보드에 PNG 이미지로 복사한다 (IO 스레드에서) */
    fun copyBitmapToClipboard(bmp: Bitmap) {
        val file = newClipFile()
        file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        putOnClipboard(file)
    }

    /** [bmp]를 사진첩(Pictures/DSnote)에 PNG [name]으로 저장한다 (IO 스레드에서) */
    fun saveBitmapToGallery(bmp: Bitmap, name: String) = saveToGallery(bmp, name)

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
        if (t.ink == null || t.review != null) return
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

    /** 물어보지 않고 원본에 덮어쓸 수 있는 탭인가: 저장하지 않은 필기가 있는 PDF이고, 덮어쓸 파일이 있다 (새 노트·한글 문서·복습 풀이는 아니다) */
    fun canAutoOverwrite(t: DocTab): Boolean =
        t.ink?.dirty == true && t.canOverwrite && t.type == DocType.PDF && !t.isNewNote && t.review == null &&
            t.sourcePdf != null && !t.pagesBusy && !t.overwriting

    /**
     * 옵션 '자동으로 덮어쓰기 저장': 저장 단추를 누른 것처럼 원본 파일에 덮어쓰되, 아무것도 묻거나 알리지 않는다.
     * 덮어쓸 수 없는 탭은 건너뛰고, 쓰지 못해도 대화상자를 띄우지 않는다 (그 필기는 자동 저장본에 남아 있다).
     * 쓰는 사이 필기가 또 바뀌었으면 '저장됨'으로 만들지 않아 다음에 다시 쓴다. 끝나면 [done]에 성공 여부를 알린다
     */
    fun autoOverwrite(t: DocTab, showProgress: Boolean = false, done: ((Boolean) -> Unit)? = null) {
        val inkDoc = t.ink
        val src = t.sourcePdf
        if (inkDoc == null || src == null || !canAutoOverwrite(t)) {
            done?.invoke(false)
            return
        }
        if (showProgress) progress.visibility = View.VISIBLE
        if (current() === t) textEditor.commit()
        val target = t.uri
        val snapshot = inkDoc.snapshot()
        val marks = inkDoc.bookmarkedPages()
        val wrongSave = inkDoc.wrongSave()
        val seen = inkDoc.changeCount
        t.overwriting = true
        activity.lifecycleScope.launch {
            val ok = try {
                withContext(Dispatchers.IO) { writeOver(t.renderPdf ?: src, target, snapshot, marks, wrongSave) }
                true
            } catch (e: Throwable) {  // 메모리 부족도 앱을 죽이지 않고 이번만 건너뛴다
                false
            }
            t.overwriting = false
            if (showProgress) progress.visibility = View.GONE
            if (ok) {
                if (target.scheme == "file") target.path?.let { MediaScannerConnection.scanFile(activity, arrayOf(it), null, null) }
                if (inkDoc.changeCount == seen) {
                    inkDoc.markSaved()
                    ReviewIndexStore.update(activity, t.uri.toString(), t.name, inkDoc.allWrongs().map { it.second })
                }
            } else {
                Toast.makeText(activity, "'${t.name}' 자동 저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
            done?.invoke(ok)
        }
    }

    /**
     * 필기를 얹은 PDF를 임시 파일에 다 쓴 뒤 [target]에 옮긴다. 파일 주소면 같은 폴더의 임시 파일을 바꿔 끼워서
     * 쓰는 도중 앱이 꺼져도 원본이 반쯤 쓰인 채로 남지 않게 하고, 그럴 수 없으면 (문서 제공자 주소) 덮어쓴다
     */
    private fun writeOver(src: File, target: Uri, snapshot: List<List<Stroke>>, marks: Set<Int>, wrongSave: WrongSave?) {
        val tmp = FileUtil.tempFile(activity, "out", "pdf")
        try {
            PdfInk.save(src, tmp, snapshot, marks, wrongSave)
            val dest = if (target.scheme == "file") target.path?.let(::File) else null
            if (dest != null) {
                val side = File(dest.parentFile, dest.name + ".tmp")
                try {
                    tmp.inputStream().use { i -> side.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                    java.nio.file.Files.move(side.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    // 다음에 빨리 열리도록 필기와 화면용 PDF를 보관해 둔다 (src가 필기를 뺀 화면용 PDF일 때)
                    InkCache.store(activity, dest, src, snapshot, marks, wrongSave?.metas ?: emptyMap())
                    return
                } catch (e: Exception) {
                    side.delete()  // 같은 폴더에 임시 파일을 못 두면 아래처럼 바로 덮어쓴다
                }
            }
            val out = activity.contentResolver.openOutputStream(target, "wt") ?: error("저장 위치를 열 수 없습니다.")
            out.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
        } finally {
            tmp.delete()
        }
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
                    val base = t.renderPdf ?: src
                    try {
                        PdfInk.save(base, tmp, snapshot, marks, wrongSave)
                        val out = activity.contentResolver.openOutputStream(target, "wt") ?: error("저장 위치를 열 수 없습니다.")
                        out.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
                        // 다음에 빨리 열리도록 필기와 화면용 PDF를 보관해 둔다 (내 기기 안의 파일일 때)
                        if (target.scheme == "file") target.path?.let { InkCache.store(activity, File(it), base, snapshot, marks, wrongSave?.metas ?: emptyMap()) }
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
                ReviewIndexStore.update(activity, t.uri.toString(), t.name, inkDoc.allWrongs().map { it.second })
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
        /** 고른 부분 이미지: 가로가 이보다 좁으면 더 크게 그리고(최대 4배), 전체 픽셀은 이 수를 넘기지 않는다 */
        private const val REGION_MIN_PX = 1000f
        private const val REGION_MAX_SCALE = 4f
        private const val REGION_MAX_PIXELS = 12_000_000f
    }
}
