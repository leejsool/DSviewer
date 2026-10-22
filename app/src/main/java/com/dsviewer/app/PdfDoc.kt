package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.SizeF
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.Closeable
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 안드로이드 내장 PdfRenderer 래퍼.
 * PdfRenderer는 스레드 안전하지 않으므로 모든 호출을 전용 스레드([dispatcher])에서 한다.
 *
 * 쪽 수가 많은 문서는 쪽의 크기를 읽는 것만으로 오래 걸린다 (쪽을 열 때마다 그 쪽의 내용을 해석하므로, 느린 기기에서는 1분 가까이).
 * 그래서 [open]에 보던 쪽을 알려 주면 그 근처 쪽만 먼저 읽고 문서를 내준다.
 * 나머지 쪽은 가까운 쪽의 크기로 어림해 두고 뒤에서 하나씩 읽어 (그리는 일이 끼어들 수 있게) 실제 크기가 어림과 다르면
 * [sizes]를 갈아 끼우고 [addSizesListener]로 알린다. 쪽 크기가 모두 같은 문서는 어림이 맞으므로 알릴 일이 없다.
 */
class PdfDoc private constructor(
    private val pfd: ParcelFileDescriptor,
    private val renderer: PdfRenderer,
    initial: List<SizeF>,
    /** 실제 크기를 읽은 쪽 (아니면 어림한 크기) */
    private val known: BooleanArray,
    private val executor: ExecutorService,
    val dispatcher: ExecutorCoroutineDispatcher,
) : Closeable {

    /** 쪽 크기. 아직 못 읽은 쪽은 어림한 값이고, 다 읽기 전에 바뀔 수 있다 (바뀌면 새 목록으로 갈아 끼운다) */
    @Volatile
    var sizes: List<SizeF> = initial
        private set

    val pageCount get() = sizes.size

    /** 작업 스레드에서만 고치는 쪽 크기 (고칠 때마다 [sizes]에 복사해 내놓는다) */
    private val work = initial.toTypedArray()
    @Volatile
    private var closed = false
    private val listeners = CopyOnWriteArrayList<Runnable>()
    private val notifyPending = AtomicBoolean(false)

    /** [sizes]가 바뀌면 (화면 스레드에서) 불린다 */
    fun addSizesListener(l: Runnable) { listeners.addIfAbsent(l) }
    fun removeSizesListener(l: Runnable) { listeners.remove(l) }

    /**
     * 페이지 [index]의 (left, top)부터 시작하는 영역을 [scale] 배율(픽셀/포인트)로 그린다.
     * 반드시 [dispatcher]에서 호출할 것.
     */
    fun render(index: Int, scale: Float, left: Float, top: Float, wPx: Int, hPx: Int): Bitmap {
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        renderer.openPage(index).use { page ->
            // 어림했던 쪽을 그리게 되면 그 김에 실제 크기를 알아 둔다
            if (!known[index]) note(index, page.width.toFloat(), page.height.toFloat())
            val m = Matrix()
            m.setScale(scale, scale)
            m.postTranslate(-left * scale, -top * scale)
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        return bmp
    }

    /** 작업 스레드에서: [index]쪽의 실제 크기를 적고, 어림과 다르면 새 목록을 내놓고 알린다 */
    private fun note(index: Int, w: Float, h: Float) {
        val cur = work[index]
        if (abs(cur.width - w) > 0.5f || abs(cur.height - h) > 0.5f) {
            work[index] = SizeF(w, h)
            sizes = work.toList()
            // 여러 쪽이 잇따라 달라져도 한 번에 알린다 (그때마다 쪽 배치를 다시 하지 않게)
            if (notifyPending.compareAndSet(false, true)) {
                MAIN.postDelayed({
                    notifyPending.set(false)
                    if (!closed) for (l in listeners) l.run()
                }, 250)
            }
        }
        // 크기를 내놓은 뒤에 읽었다고 적는다 ([knownSizes]가 다른 스레드에서 읽는다)
        known[index] = true
    }

    /** 실제로 읽은 쪽의 크기 (아직 어림인 쪽은 null): 같은 쪽 구성의 다른 PDF를 열 때 [open]의 힌트로 쓴다 */
    fun knownSizes(): List<SizeF?> {
        val s = sizes
        val k = known.copyOf()
        return List(s.size) { if (k.getOrElse(it) { false }) s[it] else null }
    }

    /** 아직 못 읽은 쪽의 크기를 [order] 차례로 뒤에서 읽는다. 한 번에 짧게만 하고 줄 맨 뒤로 다시 서서 그리는 일이 끼어들게 한다 */
    private fun scan(order: IntArray, from: Int) {
        if (closed) return
        val end = SystemClock.uptimeMillis() + SCAN_SLICE_MS
        var k = from
        while (k < order.size && !closed) {
            val i = order[k++]
            if (known[i]) continue
            runCatching { renderer.openPage(i).use { note(i, it.width.toFloat(), it.height.toFloat()) } }
            if (SystemClock.uptimeMillis() >= end) break
        }
        if (k < order.size && !closed) {
            try {
                executor.execute { scan(order, k) }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                // 문서를 닫는 중
            }
        }
    }

    override fun close() {
        closed = true
        executor.execute {
            runCatching { renderer.close() }
            runCatching { pfd.close() }
        }
        executor.shutdown()
    }

    companion object {
        private val MAIN = Handler(Looper.getMainLooper())

        /** 이 쪽 수 이하면 보던 쪽을 알려 줘도 모두 먼저 읽는다 (어림할 만큼 오래 걸리지 않는다) */
        private const val LAZY_MIN_PAGES = 16

        /** 보던 쪽 앞뒤로 먼저 읽는 쪽 수 (화면에 보일 쪽과 미리 그릴 쪽) */
        private const val NEAR_BEFORE = 2
        private const val NEAR_AFTER = 3

        /** 뒤에서 쪽 크기를 읽을 때 한 번에 일하는 시간 (ms) */
        private const val SCAN_SLICE_MS = 40L

        /**
         * [file]을 연다. [sizesHint]는 쪽 수만큼의 크기 목록으로, 이미 아는 쪽은 그 크기를 쓰고 null인 쪽만 읽는다
         * (쪽 수가 많은 문서는 쪽마다 열어 보는 데 오래 걸린다). 쪽 수가 맞지 않으면 힌트를 버리고 모두 읽는다.
         * [focusPage](0부터)를 주면 쪽 수가 많은 문서는 그 근처 쪽만 읽고 먼저 내준다 (나머지는 어림해 두고 뒤에서 읽는다)
         */
        suspend fun open(file: File, sizesHint: List<SizeF?>? = null, focusPage: Int = -1): PdfDoc {
            val executor = Executors.newSingleThreadExecutor()
            val dispatcher = executor.asCoroutineDispatcher()
            try {
                val doc = withContext(dispatcher) {
                    val pfd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
                    val r = try {
                        PdfRenderer(pfd)
                    } catch (e: Throwable) {
                        pfd.close()
                        throw e
                    }
                    val n = r.pageCount
                    val hint = sizesHint?.takeIf { it.size == n }
                    fun read(i: Int) = r.openPage(i).use { p -> SizeF(p.width.toFloat(), p.height.toFloat()) }
                    if (focusPage < 0 || n <= LAZY_MIN_PAGES) {
                        PdfDoc(pfd, r, (0 until n).map { i -> hint?.get(i) ?: read(i) }, BooleanArray(n) { true }, executor, dispatcher)
                    } else {
                        val sizes = arrayOfNulls<SizeF>(n)
                        val known = BooleanArray(n)
                        hint?.forEachIndexed { i, s -> if (s != null) { sizes[i] = s; known[i] = true } }
                        val focus = focusPage.coerceIn(0, n - 1)
                        for (i in (focus - NEAR_BEFORE).coerceAtLeast(0)..(focus + NEAR_AFTER).coerceAtMost(n - 1)) {
                            if (!known[i]) { sizes[i] = read(i); known[i] = true }
                        }
                        // 못 읽은 쪽은 보던 쪽의 크기로 어림 (쪽 크기가 모두 같은 문서가 대부분)
                        val guess = sizes[focus] ?: sizes.firstOrNull { it != null }!!
                        PdfDoc(pfd, r, sizes.map { it ?: guess }, known, executor, dispatcher).also { d ->
                            // 보던 쪽 뒤로 끝까지, 그다음 앞쪽
                            val after = (focus + NEAR_AFTER + 1 until n)
                            val before = (0 until (focus - NEAR_BEFORE).coerceAtLeast(0))
                            val order = (after + before).toList().filter { !known[it] }.toIntArray()
                            executor.execute { d.scan(order, 0) }
                        }
                    }
                }
                return doc
            } catch (e: Throwable) {
                executor.shutdown()
                throw e
            }
        }
    }
}
