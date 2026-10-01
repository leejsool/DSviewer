package com.dsviewer.app

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.util.AttributeSet
import android.util.LruCache
import android.view.View
import androidx.core.graphics.ColorUtils
import com.dsviewer.app.hwp.Cfb
import com.dsviewer.app.conv.DocConvert
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipFile
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 파일 탐색기의 문서 썸네일. 기본은 첫 쪽(필기 포함)이고, 즐겨찾기 문서는 다른 쪽이나 그림으로 바꿀 수 있다.
 * 만든 썸네일은 파일이 바뀌지 않는 한 캐시(메모리 + 앱 캐시 폴더)에서 다시 쓴다.
 */
object Thumbs {
    /** 썸네일의 긴 변 (px) */
    const val SIZE = 480

    /** [page]쪽 (0부터) 썸네일. [custom]이 있으면 그 그림 (앱 저장소 thumbs 폴더의 파일 이름) */
    data class Source(val uri: Uri, val name: String, val page: Int = 0, val custom: String? = null)

    private val mem = object : LruCache<String, Bitmap>((Runtime.getRuntime().maxMemory() / 10).toInt()) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }
    /** 소스 → 마지막으로 쓴 캐시 키 (다시 그릴 때 기다리지 않고 바로 보이게) */
    private val lastKey = HashMap<Source, String>()
    private val failed = HashSet<String>()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val io = Dispatchers.IO.limitedParallelism(2)
    /** 한글 변환은 무거우므로 하나씩 */
    private val heavy = Mutex()

    /** 캐시에 있으면 바로 */
    fun peek(src: Source): Bitmap? = synchronized(lastKey) { lastKey[src] }?.let { mem.get(it) }

    suspend fun load(ctx: Context, src: Source): Bitmap? = withContext(io) {
        val app = ctx.applicationContext
        if (src.custom != null) {
            val key = "c|${src.custom}"
            mem.get(key)?.let { return@withContext it }
            val f = File(customDir(app), src.custom)
            val b = runCatching { BitmapFactory.decodeFile(f.path) }.getOrNull()
            if (b != null) remember(src, key, b)
            return@withContext b
        }
        val stamp = stamp(app, src.uri) ?: return@withContext null
        val key = "$stamp|p${src.page}"
        mem.get(key)?.let { remember(src, key, it); return@withContext it }
        synchronized(failed) { if (key in failed) return@withContext null }
        val disk = File(cacheDir(app), md5(key) + ".jpg")
        if (disk.isFile) {
            BitmapFactory.decodeFile(disk.path)?.let { remember(src, key, it); return@withContext it }
        }
        val b = try {
            render(app, src.uri, src.name, src.page)
        } catch (e: OutOfMemoryError) {
            null
        } catch (e: Exception) {
            null
        }
        if (b == null) {
            synchronized(failed) { failed.add(key) }
            return@withContext null
        }
        runCatching {
            disk.outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            trimCache(app)
        }
        remember(src, key, b)
        b
    }

    private fun remember(src: Source, key: String, b: Bitmap) {
        mem.put(key, b)
        synchronized(lastKey) { lastKey[src] = key }
    }

    /** 파일이 바뀌면 달라지는 표시 (경로·수정 시각·크기). 없는 파일이면 null */
    private fun stamp(ctx: Context, uri: Uri): String? {
        if (uri.scheme == "file") {
            val f = File(uri.path ?: return null)
            if (!f.isFile) return null
            return "f|${f.path}|${f.lastModified()}|${f.length()}"
        }
        var size = -1L
        var time = -1L
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) size = c.getLong(0)
            }
        }
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(DocumentsContract.Document.COLUMN_LAST_MODIFIED), null, null, null)?.use { c ->
                if (c.moveToFirst() && !c.isNull(0)) time = c.getLong(0)
            }
        }
        return "u|$uri|$time|$size"
    }

    /** 문서의 [page]쪽을 그린다. content:// 문서는 잠깐 앱 캐시로 복사 */
    private suspend fun render(ctx: Context, uri: Uri, name: String, page: Int): Bitmap? {
        val local = uri.scheme == "file"
        val file = if (local) File(uri.path!!) else FileUtil.copyToCache(ctx, uri, name)
        try {
            val type = FileUtil.detect(name, null, file)
            return when (type) {
                DocType.PDF -> renderPdf(file, page, withInk = true)
                DocType.IMAGE -> renderImage(file)
                DocType.UNKNOWN -> null
                else -> heavy.withLock { renderConverted(ctx, file, type, name, page) }
            }
        } finally {
            if (!local) file.delete()
        }
    }

    /** 한글·워드·파워포인트·글: 앞쪽만 PDF로 바꿔 그린다 */
    private fun renderConverted(ctx: Context, file: File, type: DocType, name: String, page: Int): Bitmap? {
        var out: File? = null
        try {
            out = DocConvert.toPdf(ctx, file, type, name, maxPages = page + 1, prefix = "thumb")
            return renderPdf(out, page, withInk = type == DocType.TXT)
        } catch (e: Exception) {
            // 한글이 저장해 둔 미리보기 그림이라도
            return if (type.isHangul) runCatching { hwpPreview(file, type) }.getOrNull() else null
        } finally {
            out?.delete()
        }
    }

    /** 그림: 긴 변을 썸네일 크기로 줄여 읽는다 */
    private fun renderImage(file: File): Bitmap? {
        val src = android.graphics.ImageDecoder.createSource(file)
        return android.graphics.ImageDecoder.decodeBitmap(src) { d, info, _ ->
            d.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
            val k = SIZE.toFloat() / maxOf(info.size.width, info.size.height)
            if (k < 1f) d.setTargetSize((info.size.width * k).roundToInt().coerceAtLeast(1), (info.size.height * k).roundToInt().coerceAtLeast(1))
        }
    }

    private fun hwpPreview(file: File, type: DocType): Bitmap? {
        val bytes = if (type == DocType.HWP) Cfb(file).read("PrvImage")
        else ZipFile(file).use { z -> z.getEntry("Preview/PrvImage.png")?.let { e -> z.getInputStream(e).use { it.readBytes() } } }
        return bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
    }

    /** PDF의 [page]쪽 (없는 쪽이면 마지막 쪽). PdfRenderer는 주석을 그리지 않으므로 이 앱의 필기는 따로 얹는다 */
    fun renderPdf(file: File, page: Int, withInk: Boolean, size: Int = SIZE): Bitmap? {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use { pfd ->
            PdfRenderer(pfd).use { r ->
                if (r.pageCount == 0) return null
                val index = page.coerceIn(0, r.pageCount - 1)
                r.openPage(index).use { p ->
                    val scale = size.toFloat() / maxOf(p.width, p.height)
                    val w = (p.width * scale).roundToInt().coerceAtLeast(1)
                    val h = (p.height * scale).roundToInt().coerceAtLeast(1)
                    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    bmp.eraseColor(Color.WHITE)
                    val m = Matrix().apply { setScale(scale, scale) }
                    p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    if (withInk && PdfInk.containsInk(file)) {
                        val strokes = runCatching { PdfInk.pageStrokes(file, index) }.getOrDefault(emptyList())
                        val c = Canvas(bmp)
                        c.scale(scale, scale)
                        val paint = inkPaint()
                        strokes.sortedBy { inkLayer(it) }.forEach { drawInkStroke(c, paint, it) }
                    }
                    return bmp
                }
            }
        }
    }

    // ---- 바꾼 썸네일 (불러온 그림, 손으로 그린 그림) ----

    private fun customDir(ctx: Context) = File(ctx.filesDir, "thumbs").apply { mkdirs() }

    /** 그림을 썸네일 파일로 저장하고 그 이름을 돌려준다 (긴 변이 [SIZE]를 넘으면 줄임) */
    fun saveCustom(ctx: Context, src: Bitmap): String {
        val k = min(1f, SIZE * 1.5f / maxOf(src.width, src.height))
        val b = if (k < 1f) Bitmap.createScaledBitmap(src, (src.width * k).roundToInt().coerceAtLeast(1), (src.height * k).roundToInt().coerceAtLeast(1), true) else src
        val name = "t${System.currentTimeMillis()}.png"
        File(customDir(ctx), name).outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return name
    }

    fun deleteCustom(ctx: Context, name: String) {
        File(customDir(ctx), name).delete()
        mem.remove("c|$name")
    }

    /** 사진 등을 썸네일 크기로 줄여 읽는다 */
    fun decodeImage(ctx: Context, uri: Uri): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth <= 0 || o.outHeight <= 0) return null
        var sample = 1
        while (maxOf(o.outWidth, o.outHeight) / (sample * 2) >= SIZE * 1.5f) sample *= 2
        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
        val b = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
        // 사진의 회전 정보
        val rot = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use {
                when (android.media.ExifInterface(it).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)) {
                    android.media.ExifInterface.ORIENTATION_ROTATE_90 -> 90
                    android.media.ExifInterface.ORIENTATION_ROTATE_180 -> 180
                    android.media.ExifInterface.ORIENTATION_ROTATE_270 -> 270
                    else -> 0
                }
            } ?: 0
        }.getOrDefault(0)
        if (rot == 0) return b
        val m = Matrix().apply { postRotate(rot.toFloat()) }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    private fun cacheDir(ctx: Context) = File(ctx.cacheDir, "thumbs").apply { mkdirs() }

    /** 캐시 썸네일은 400개까지 (오래 안 쓴 것부터 지움) */
    private fun trimCache(ctx: Context) {
        val files = cacheDir(ctx).listFiles() ?: return
        if (files.size <= 400) return
        files.sortedBy { it.lastModified() }.take(files.size - 300).forEach { it.delete() }
    }

    private fun md5(s: String): String =
        MessageDigest.getInstance("MD5").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}

/**
 * 썸네일 칸: 그림은 칸 안에 비율을 지켜 가운데에 (가는 테두리와 그림자), 없으면 빈 종이 모양 위에 [placeholder],
 * [folder]가 있으면 폴더 그림. [ratio] > 0이면 높이 = 너비 × ratio
 */
class ThumbView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    var ratio = 0f
        set(v) { field = v; requestLayout() }
    var bitmap: Bitmap? = null
        set(v) { field = v; invalidate() }
    var placeholder: Drawable? = null
        set(v) { field = v; invalidate() }
    var folder: Drawable? = null
        set(v) { field = v; invalidate() }

    private val density = resources.displayMetrics.density
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density
        color = MaterialColors.getColor(this@ThumbView, com.google.android.material.R.attr.colorOutlineVariant)
    }
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(28, 0, 0, 0) }
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val r = RectF()

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (ratio <= 0f) return super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val w = MeasureSpec.getSize(widthMeasureSpec)
        setMeasuredDimension(w, (w * ratio).roundToInt())
    }

    override fun onDraw(c: Canvas) {
        val l = paddingLeft.toFloat(); val t = paddingTop.toFloat()
        val w = (width - paddingLeft - paddingRight).toFloat()
        val h = (height - paddingTop - paddingBottom).toFloat()
        if (w <= 0 || h <= 0) return
        folder?.let { f ->
            // 폴더는 가로가 조금 긴 모양
            val fw = min(w, h * 1.25f) * 0.86f
            val fh = fw * 0.8f
            f.setBounds((l + (w - fw) / 2).roundToInt(), (t + (h - fh) / 2).roundToInt(), (l + (w + fw) / 2).roundToInt(), (t + (h + fh) / 2).roundToInt())
            f.draw(c)
            return
        }
        val b = bitmap
        // 그림이 없으면 A4 세로 비율의 빈 종이
        val bw = b?.width?.toFloat() ?: 1f
        val bh = b?.height?.toFloat() ?: 1.414f
        val k = min((w - 3 * density) / bw, (h - 3 * density) / bh)
        val dw = bw * k; val dh = bh * k
        r.set(l + (w - dw) / 2, t + (h - dh) / 2, l + (w + dw) / 2, t + (h + dh) / 2)
        val rad = 2 * density
        c.drawRoundRect(r.left + density, r.top + 2 * density, r.right + density, r.bottom + 2 * density, rad, rad, shadow)
        if (b != null) {
            c.drawRect(r, paper)
            c.drawBitmap(b, null, r, bmpPaint)
        } else {
            c.drawRoundRect(r, rad, rad, paper)
            placeholder?.let { p ->
                val s = (min(dw, dh) * 0.42f).roundToInt()
                val cx = r.centerX().roundToInt(); val cy = r.centerY().roundToInt()
                p.setBounds(cx - s / 2, cy - s / 2, cx + s / 2, cy + s / 2)
                p.draw(c)
            }
        }
        c.drawRoundRect(r, rad, rad, edge)
    }
}

/** 폴더 그림: 뒷면(탭)은 조금 진하게, 앞면은 [color]에 [pattern] 무늬 */
class FolderDrawable(private val color: Int, private val pattern: TapePattern) : Drawable() {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val path = Path()
    private val m = Matrix()

    override fun draw(canvas: Canvas) {
        val b = bounds
        val w = b.width().toFloat(); val h = b.height().toFloat()
        if (w <= 0 || h <= 0) return
        val x = b.left.toFloat(); val y = b.top.toFloat()
        val rad = min(w, h) * 0.07f
        // 뒷면과 탭
        paint.shader = null
        paint.color = ColorUtils.blendARGB(color, Color.BLACK, 0.22f)
        path.reset()
        path.addRoundRect(x, y, x + w * 0.42f, y + h * 0.3f, rad, rad, Path.Direction.CW)
        path.addRoundRect(x, y + h * 0.1f, x + w, y + h, rad, rad, Path.Direction.CW)
        canvas.drawPath(path, paint)
        // 앞면
        val ft = y + h * 0.22f
        paint.color = color
        canvas.drawRoundRect(x, ft, x + w, y + h, rad, rad, paint)
        TapeTile.shader(pattern, color)?.let { sh ->
            // 무늬 한 칸이 폴더 너비의 1/7쯤
            val k = w / 7f / (TapeTile.PERIOD * TapeTile.TILE_PX)
            m.setScale(k, k)
            m.postTranslate(x, ft)
            sh.setLocalMatrix(m)
            paint.shader = sh
            canvas.drawRoundRect(x, ft, x + w, y + h, rad, rad, paint)
            paint.shader = null
        }
        // 앞면 윗가장자리 밝은 줄
        paint.color = ColorUtils.blendARGB(color, Color.WHITE, 0.35f)
        canvas.drawRect(x + rad, ft, x + w - rad, ft + maxOf(1f, h * 0.025f), paint)
    }

    override fun getIntrinsicWidth() = -1
    override fun getIntrinsicHeight() = -1
    override fun setAlpha(alpha: Int) {}
    override fun setColorFilter(colorFilter: ColorFilter?) {}
    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
