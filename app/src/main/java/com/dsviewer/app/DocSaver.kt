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
import android.os.Process
import android.provider.MediaStore
import android.text.format.DateFormat
import android.view.View
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Date
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
        if (!asNew && t.canOverwrite && t.type == DocType.PDF && t.sourcePdf != null) overwriteHere(t)
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

    /** 덮어쓰려고 화면 스레드에서 찍어 둔 것: 필기, 북마크, 오답 정보와 그때까지의 필기 변경 횟수 */
    private class Capture(
        val inkDoc: InkDocument, val base: File, val target: Uri,
        val pages: List<List<Stroke>>, val marks: Set<Int>, val wrongSave: WrongSave?, val seen: Int,
    )

    /** 지금 상태를 찍고 이 탭을 '덮어쓰는 중'으로 만든다 (끝나면 [Capture]를 쓴 쪽이 `overwriting`을 푼다) */
    private fun capture(t: DocTab, inkDoc: InkDocument, src: File): Capture {
        if (current() === t) textEditor.commit()
        t.overwriting = true
        return Capture(
            inkDoc, t.renderPdf ?: src, t.uri, inkDoc.snapshot(), inkDoc.bookmarkedPages(), inkDoc.wrongSave(), inkDoc.changeCount,
        )
    }

    /** 덮어쓰기를 끝낸 결과: 성공 여부와, 성공 뒤 뒤에서 할 일(보관본 쓰기, 없으면 null) */
    private class Written(val ok: Boolean, val after: (() -> Unit)?)

    private suspend fun write(c: Capture, perf: Perf): Written = try {
        val after = withContext(Dispatchers.IO) { writeOver(c.base, c.target, c.pages, c.marks, c.wrongSave, perf) }
        Written(true, after)
    } catch (e: Throwable) {  // 메모리 부족도 앱을 죽이지 않고 이번만 건너뛴다
        Written(false, null)
    }

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
        val c = capture(t, inkDoc, src)
        launchTracked(c.target) {
            val perf = Perf("자동저장 ${t.name}")
            val w = write(c, perf)
            t.overwriting = false
            if (showProgress) progress.visibility = View.GONE
            if (w.ok) {
                if (c.target.scheme == "file") c.target.path?.let { MediaScannerConnection.scanFile(activity, arrayOf(it), null, null) }
                if (inkDoc.changeCount == c.seen) {
                    inkDoc.markSaved()
                    ReviewIndexStore.updateLater(activity, t.uri.toString(), t.name, inkDoc.allWrongs().map { it.second })
                }
            } else {
                Toast.makeText(activity, "'${t.name}' 자동 저장에 실패했습니다.", Toast.LENGTH_SHORT).show()
            }
            done?.invoke(w.ok)
            storeCache(c, w, perf)
            perf.done()
        }
    }

    /**
     * 저장 단추(원본 위치에 덮어쓰기): [autoOverwrite]처럼 필기를 찍어 두고 뒤에서 쓰므로, 쓰는 동안에도 필기를 계속할 수 있다.
     * 원본에 쓸 수 없으면 '다른 이름으로 저장'으로 넘어간다. 닫기 전 저장([closeAfterSave])이면 쓰는 것은 뒤에 맡기고 곧바로 닫는다
     */
    private fun overwriteHere(t: DocTab) {
        val inkDoc = t.ink ?: return
        val src = t.sourcePdf ?: return
        if (t.overwriting) {
            closeAfterSave = null
            Toast.makeText(activity, "저장하는 중입니다. 잠시 뒤에 다시 눌러 주세요.", Toast.LENGTH_SHORT).show()
            return
        }
        if (closeAfterSave === t) {
            closeAfterSave = null
            if (overwriteAndLetGo(t)) {
                removeTab(t)
                return
            }
        }
        progress.visibility = View.VISIBLE
        val c = capture(t, inkDoc, src)
        launchTracked(c.target) {
            val perf = Perf("저장 ${t.name}")
            val w = write(c, perf)
            t.overwriting = false
            progress.visibility = View.GONE
            if (w.ok) {
                if (c.target.scheme == "file") c.target.path?.let { MediaScannerConnection.scanFile(activity, arrayOf(it), null, null) }
                if (inkDoc.changeCount == c.seen) inkDoc.markSaved()
                ReviewIndexStore.updateLater(activity, t.uri.toString(), t.name, inkDoc.allWrongs().map { it.second })
                Toast.makeText(activity, "'${t.name}' 저장했습니다.", Toast.LENGTH_SHORT).show()
            } else {
                t.canOverwrite = false
                Toast.makeText(activity, "원본에 저장할 수 없어 다른 이름으로 저장합니다.", Toast.LENGTH_LONG).show()
                saveAs(t)
            }
            storeCache(c, w, perf)
            perf.done()
        }
    }

    /** 뒤에서 덮어쓰는 중에 닫은 탭들 (화면에서는 사라졌고, 다 쓸 때까지 자동 저장본을 지우지 않는다) */
    private val letGo = HashSet<DocTab>()

    /** 닫은 탭의 덮어쓰기가 끝났을 때 (탭, 성공 여부): 성공이면 자동 저장본을 지우고, 실패하면 자동 저장본에 남긴다 */
    var onLetGoDone: ((DocTab, Boolean) -> Unit)? = null

    fun isLetGo(t: DocTab) = t in letGo

    /**
     * 탭을 닫을 때 원본에 덮어쓰는 일을 기다리지 않고 뒤에 맡긴다: 필기를 찍어 두고 곧바로 돌아오니 호출한 쪽은 바로 탭을 닫으면 된다.
     * 덮어쓸 수 없는 탭이면 false (아무것도 하지 않음). 같은 문서를 곧바로 다시 열면 [awaitPending]으로 이 저장이 끝나기를 기다린다
     */
    fun overwriteAndLetGo(t: DocTab): Boolean {
        val inkDoc = t.ink ?: return false
        val src = t.sourcePdf ?: return false
        if (t.overwriting || t.pagesBusy || !t.canOverwrite || t.type != DocType.PDF || t.isNewNote || t.review != null) return false
        val c = capture(t, inkDoc, src)
        letGo += t
        val appContext = activity.applicationContext
        val name = t.name
        launchTracked(c.target, bg) {
            val perf = Perf("닫으며저장 $name")
            val w = write(c, perf)
            t.overwriting = false
            if (w.ok && c.target.scheme == "file") c.target.path?.let { MediaScannerConnection.scanFile(appContext, arrayOf(it), null, null) }
            if (w.ok) ReviewIndexStore.updateLater(appContext, c.target.toString(), name, inkDoc.allWrongs().map { it.second })
            else Toast.makeText(appContext, "'$name' 저장에 실패했습니다. 필기는 임시본에 남겨 두었으니 다시 열어 복구해 주세요.", Toast.LENGTH_LONG).show()
            letGo -= t
            onLetGoDone?.invoke(t, w.ok)
            storeCache(c, w, perf)
            perf.done()
        }
        return true
    }

    /** 성공한 저장 뒤 보관본을 쓴다 (저장이 끝났다고 알린 다음에. 그새 필기가 바뀌었으면 보관본과 어긋나니 쓰지 않는다) */
    private suspend fun storeCache(c: Capture, w: Written, perf: Perf) {
        val after = w.after ?: return
        if (!w.ok) return
        withContext(Dispatchers.IO) {
            if (c.inkDoc.changeCount == c.seen) after()
        }
        perf.step("보관본쓰기")
    }

    /** [target] 문서를 쓰는 일을 [scope]에서 돌리고, 같은 문서를 곧바로 열 때 기다릴 수 있게 등록해 둔다 */
    private fun launchTracked(target: Uri, scope: CoroutineScope = activity.lifecycleScope, block: suspend CoroutineScope.() -> Unit) {
        val key = target.toString()
        val job = scope.launch(start = CoroutineStart.LAZY) {
            val me = coroutineContext[Job]
            try {
                block()
            } finally {
                if (me != null) pending.remove(key, me)
            }
        }
        pending.add(key, job)
        job.start()
    }

    /**
     * 필기를 얹은 PDF를 [target]에 쓴다. 파일 주소면 같은 폴더의 임시 파일에 바로 다 쓴 뒤 바꿔 끼워서
     * 쓰는 도중 앱이 꺼져도 원본이 반쯤 쓰인 채로 남지 않게 하고, 그럴 수 없으면 (문서 제공자 주소) 캐시의 임시 파일에 쓴 뒤 덮어쓴다.
     * 돌려주는 것은 성공 뒤 뒤에서 할 일 (다음에 빨리 열리도록 필기와 화면용 PDF를 보관해 둔다. [src]가 필기를 뺀 화면용 PDF일 때), 없으면 null
     */
    private fun writeOver(src: File, target: Uri, snapshot: List<List<Stroke>>, marks: Set<Int>, wrongSave: WrongSave?, perf: Perf): (() -> Unit)? {
        val dest = if (target.scheme == "file") target.path?.let(::File) else null
        if (dest != null) {
            val side = File(dest.parentFile, dest.name + ".tmp")
            // 같은 폴더에 임시 파일을 둘 수 있는지 먼저 본다 (못 두면 아래처럼 캐시에 쓰고 덮어쓴다)
            val usable = try {
                side.outputStream().close()
                true
            } catch (e: Exception) {
                false
            }
            if (usable) {
                try {
                    PdfInk.save(src, side, snapshot, marks, wrongSave, perf::step)
                    try {
                        java.nio.file.Files.move(side.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } catch (e: Exception) {
                        // 바꿔 끼울 수 없으면 다 쓴 파일을 원본 자리에 바로 덮어쓴다
                        val out = activity.contentResolver.openOutputStream(target, "wt") ?: error("저장 위치를 열 수 없습니다.")
                        out.use { o -> side.inputStream().use { it.copyTo(o, 1 shl 16) } }
                        side.delete()
                    }
                    perf.step("바꿔끼우기")
                } catch (e: Throwable) {
                    side.delete()
                    throw e
                }
                return { InkCache.store(activity, dest, src, snapshot, marks, wrongSave?.metas ?: emptyMap()) }
            }
        }
        val tmp = FileUtil.tempFile(activity, "out", "pdf")
        try {
            PdfInk.save(src, tmp, snapshot, marks, wrongSave, perf::step)
            val out = activity.contentResolver.openOutputStream(target, "wt") ?: error("저장 위치를 열 수 없습니다.")
            out.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
            perf.step("덮어쓰기")
        } finally {
            tmp.delete()
        }
        return null
    }

    /** 다른 이름으로 저장 창에서 고른 [target]에 쓴다 (새 노트·한글 문서의 첫 저장 포함) */
    fun saveTo(t: DocTab, target: Uri) {
        val inkDoc = t.ink ?: return
        val src = t.sourcePdf ?: return
        val snapshot = inkDoc.snapshot()
        val marks = inkDoc.bookmarkedPages()
        val wrongSave = inkDoc.wrongSave()
        val seen = inkDoc.changeCount
        activity.lifecycleScope.launch {
            progress.visibility = View.VISIBLE
            val perf = Perf("다른이름저장 ${t.name}")
            var after: (() -> Unit)? = null
            try {
                withContext(Dispatchers.IO) {
                    val tmp = FileUtil.tempFile(activity, "out", "pdf")
                    val base = t.renderPdf ?: src
                    try {
                        PdfInk.save(base, tmp, snapshot, marks, wrongSave, perf::step)
                        val out = activity.contentResolver.openOutputStream(target, "wt") ?: error("저장 위치를 열 수 없습니다.")
                        out.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
                        perf.step("덮어쓰기")
                        // 다음에 빨리 열리도록 필기와 화면용 PDF를 보관해 둔다 (내 기기 안의 파일일 때. 저장을 알린 뒤에 쓴다)
                        if (target.scheme == "file") target.path?.let { path ->
                            after = { InkCache.store(activity, File(path), base, snapshot, marks, wrongSave?.metas ?: emptyMap()) }
                        }
                    } finally {
                        tmp.delete()
                    }
                }
                // 파일 탐색기의 '모든 문서' 목록에 바뀐 날짜가 바로 보이도록 색인 갱신
                if (target.scheme == "file") target.path?.let {
                    MediaScannerConnection.scanFile(activity, arrayOf(it), null, null)
                }
                adoptSavedFile(t, target)
                if (inkDoc.changeCount == seen) inkDoc.markSaved()
                ReviewIndexStore.updateLater(activity, t.uri.toString(), t.name, inkDoc.allWrongs().map { it.second })
                Toast.makeText(activity, "'${t.name}' 저장했습니다.", Toast.LENGTH_SHORT).show()
                if (closeAfterSave === t) {
                    closeAfterSave = null
                    removeTab(t)
                }
            } catch (e: Exception) {
                closeAfterSave = null
                MaterialAlertDialogBuilder(activity)
                    .setMessage("저장하지 못했습니다.\n${e.message ?: e.javaClass.simpleName}")
                    .setPositiveButton("확인", null)
                    .show()
            } finally {
                progress.visibility = View.GONE
            }
            after?.let { run ->
                withContext(Dispatchers.IO) { if (inkDoc.changeCount == seen) run() }
                perf.step("보관본쓰기")
            }
            perf.done()
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

    /**
     * 문서를 연 뒤 저장 준비: 잠시 뒤 낮은 우선순위 스레드에서 펜 획의 주석 재료를 미리 만들어 둔다 ([PdfInk.prewarm]).
     * 그러면 문서를 열고 처음 저장할 때도 바뀐 획만 새로 만든다. 필기가 적으면 효과가 작아 건너뛴다.
     * 탭을 닫거나 화면용 PDF가 바뀌면(쪽 넣기·빼기) 그만두고, 저장이 돌기 시작해도 비켜선다 (그 저장이 필요한 것을 만든다)
     */
    fun prewarm(t: DocTab) {
        if (t.prewarm?.isActive == true) return
        val inkDoc = t.ink ?: return
        // 저장할 때와 같은 바탕 PDF ([saveTo]·[writeOver])
        val base = t.renderPdf ?: t.sourcePdf ?: return
        t.prewarm = activity.lifecycleScope.launch {
            delay(PREWARM_DELAY_MS)
            if (t.ink !== inkDoc || (t.renderPdf ?: t.sourcePdf) !== base) return@launch
            // 화면 스레드에서 지금 획 목록을 찍어 두고, 재료 만들기만 뒤에서 한다 (저장과 같은 방식)
            val pages = inkDoc.snapshot()
            if (pages.sumOf { p -> p.count(PdfInk::isPenStroke) } < PREWARM_MIN_STROKES) return@launch
            val job = coroutineContext[Job]
            withContext(prewarmThread) {
                try {
                    PdfInk.prewarm(base, pages) { job?.isActive != false && (t.renderPdf ?: t.sourcePdf) === base }
                } catch (e: Exception) {
                    // 준비가 안 돼도 저장할 때 만들면 된다
                }
            }
        }
    }

    companion object {
        /** 문서를 다 띄운 뒤 이만큼(ms) 기다렸다가 저장 준비를 시작한다 (열기·첫 필기를 방해하지 않게) */
        private const val PREWARM_DELAY_MS = 3000L

        /** 닫은 뒤에도 끝까지 마쳐야 하는 저장을 돌리는 곳 (화면이 닫혀도 계속된다) */
        private val bg = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        /** 지금 쓰는 중인 문서 주소 → 그 일. 같은 문서를 곧바로 열 때 옛 내용을 열지 않으려고 기다린다 */
        private val pending = PendingWrites()

        /** [uri] 문서를 지금 쓰는 중인가 */
        fun isPending(uri: Uri) = pending.isPending(uri.toString())

        /** [uri] 문서를 쓰는 중이면 끝나기를 기다린다 */
        suspend fun awaitPending(uri: Uri) {
            pending.await(uri.toString())
        }
        /** 펜 획이 이보다 적으면 저장 준비를 하지 않는다 (300획을 새로 만드는 데 0.2초 안쪽) */
        private const val PREWARM_MIN_STROKES = 300

        /** 저장 준비를 도는 낮은 우선순위 스레드 하나 (문서를 여럿 열어도 하나씩. 한동안 일이 없으면 스레드를 닫는다) */
        private val prewarmThread = ThreadPoolExecutor(0, 1, 30L, TimeUnit.SECONDS, LinkedBlockingQueue()) { r ->
            Thread({
                Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
                r.run()
            }, "ink-prewarm").apply { isDaemon = true }
        }.asCoroutineDispatcher()

        /** 이미지로 저장할 때 해상도 */
        private const val EXPORT_DPI = 150f
        /** 고른 부분 이미지: 가로가 이보다 좁으면 더 크게 그리고(최대 4배), 전체 픽셀은 이 수를 넘기지 않는다 */
        private const val REGION_MIN_PX = 1000f
        private const val REGION_MAX_SCALE = 4f
        private const val REGION_MAX_PIXELS = 12_000_000f
    }
}
