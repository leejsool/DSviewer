package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import android.util.SizeF
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 안드로이드 내장 PdfRenderer 래퍼.
 * PdfRenderer는 스레드 안전하지 않으므로 모든 호출을 전용 스레드([dispatcher])에서 한다.
 */
class PdfDoc private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    val sizes: List<SizeF>,
    private val executor: ExecutorService,
    val dispatcher: ExecutorCoroutineDispatcher,
) : Closeable {

    val pageCount get() = sizes.size

    /**
     * 페이지 [index]의 (left, top)부터 시작하는 영역을 [scale] 배율(픽셀/포인트)로 그린다.
     * 반드시 [dispatcher]에서 호출할 것.
     */
    fun render(index: Int, scale: Float, left: Float, top: Float, wPx: Int, hPx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        renderer.openPage(index).use { page ->
            val m = Matrix()
            m.setScale(scale, scale)
            m.postTranslate(-left * scale, -top * scale)
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        return bmp
    }

    override fun close() {
        executor.execute {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
        executor.shutdown()
    }

    companion object {
        suspend fun open(file: File): PdfDoc {
            val executor = Executors.newSingleThreadExecutor()
            val dispatcher = executor.asCoroutineDispatcher()
            try {
                return withContext(dispatcher) {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = try {
                        PdfRenderer(pfd)
                    } catch (e: Throwable) {
                        pfd.close()
                        throw e
                    }
                    val sizes = (0 until r.pageCount).map { i ->
                        r.openPage(i).use { p -> SizeF(p.width.toFloat(), p.height.toFloat()) }
                    }
                    PdfDoc(pfd, r, sizes, executor, dispatcher)
                }
            } catch (e: Throwable) {
                executor.shutdown()
                throw e
            }
        }
    }
}
