package com.dsviewer.app

import android.graphics.RectF
import com.google.android.gms.tasks.Task
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognition
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModel
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognitionModelIdentifier
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizer
import com.google.mlkit.vision.digitalink.recognition.DigitalInkRecognizerOptions
import com.google.mlkit.vision.digitalink.recognition.Ink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 필기(획)를 글자로 읽는 것. 읽는 방법은 바꿔 끼울 수 있다 */
internal interface InkRecognizer {
    /** 읽는 데 필요한 데이터가 기기에 있는가 */
    suspend fun isReady(): Boolean

    /** 데이터를 내려받는다. 성공하면 true */
    suspend fun download(): Boolean

    /** 한 줄의 획들([strokes]는 획마다 x, y를 번갈아 담은 배열, 쓴 차례)을 읽은 글 후보들 (가능성이 높은 것부터) */
    suspend fun recognize(strokes: List<FloatArray>): List<String>
}

/**
 * ML Kit 디지털 잉크 인식 (기기 안에서 읽는다). 한국어 모델은 한글만, 영어 모델은 영문·숫자·기호를 읽으므로
 * 한 줄을 두 모델로 모두 읽어 후보를 합친다 (필기에는 한글과 수식·영어가 섞인다). 데이터는 처음 한 번 내려받는다
 */
internal object MlKitInkRecognizer : InkRecognizer {
    private val models: List<DigitalInkRecognitionModel> by lazy {
        listOf(DigitalInkRecognitionModelIdentifier.KO, DigitalInkRecognitionModelIdentifier.EN_US)
            .map { DigitalInkRecognitionModel.builder(it).build() }
    }
    private val clients = HashMap<DigitalInkRecognitionModel, DigitalInkRecognizer>()

    override suspend fun isReady(): Boolean = models.all { RemoteModelManager.getInstance().isModelDownloaded(it).await() }

    override suspend fun download(): Boolean = try {
        for (m in models) {
            if (!RemoteModelManager.getInstance().isModelDownloaded(m).await()) {
                RemoteModelManager.getInstance().download(m, DownloadConditions.Builder().build()).await()
            }
        }
        true
    } catch (e: Exception) {
        false
    }

    override suspend fun recognize(strokes: List<FloatArray>): List<String> {
        val ink = Ink.builder().apply {
            for (s in strokes) {
                val b = Ink.Stroke.builder()
                for (i in 0 until s.size / 2) b.addPoint(Ink.Point.create(s[i * 2], s[i * 2 + 1]))
                addStroke(b.build())
            }
        }.build()
        val out = ArrayList<String>()
        for (m in models) {
            val c = clients.getOrPut(m) {
                DigitalInkRecognition.getClient(DigitalInkRecognizerOptions.builder(m).setMaxResultCount(MAX_CANDIDATES).build())
            }
            c.recognize(ink).await().candidates.forEach { cand -> cand.text?.takeIf(String::isNotBlank)?.let(out::add) }
        }
        return out.distinct()
    }

    /** 앱을 끝낼 때 읽기 엔진을 내려놓는다 */
    fun release() {
        clients.values.forEach { it.close() }
        clients.clear()
    }

    /** 모델마다 한 줄에서 글 후보를 몇 개까지 남길지 (필기는 헷갈리기 쉬워 비슷한 후보로도 찾는다) */
    const val MAX_CANDIDATES = 3
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnSuccessListener { cont.resume(it) }
    addOnFailureListener { cont.resumeWithException(it) }
    addOnCanceledListener { cont.cancel() }
}

/**
 * 한 문서의 필기 색인: 쪽마다 필기를 글줄로 묶어 글로 읽어 두고, 찾는 말이 들어 있는 줄의 자리를 돌려준다.
 * 쪽 내용 서명([InkHash])으로 읽은 결과를 기억하므로 바뀌지 않은 쪽은 다시 읽지 않고, 파일에도 남겨 다음에 문서를 열 때 바로 쓴다.
 * 글자 찾기를 열어 둔 동안만 일한다 ([onProgress]가 있는 동안)
 */
internal class HandwritingIndex(
    private val scope: CoroutineScope,
    private val recognizer: InkRecognizer,
    private val ink: InkDocument,
    /** 읽은 결과를 적어 둘 파일 (null이면 기억만) */
    private val cacheFile: File?,
) {
    enum class State { IDLE, NEEDS_DATA, DOWNLOADING, WORKING, DONE, FAILED }

    var state = State.IDLE
        private set
    /** 필기가 있는 쪽 수와 그중 읽은 쪽 수 */
    var totalPages = 0
        private set
    var donePages = 0
        private set
    /** 진행이 바뀔 때마다 (메인 스레드). 글자 찾기를 열어 둔 동안만 켜 둔다 */
    var onProgress: (() -> Unit)? = null
    /** 읽을 데이터가 없어 내려받아야 할 때 (처음 한 번 알린다) */
    var onNeedsData: (() -> Unit)? = null

    private val byHash = HashMap<Long, List<InkLineData>>()
    private var pageHashes: List<Long?> = emptyList()
    private var cacheLoaded = false
    private var job: Job? = null
    private var generation = 0

    private fun notifyProgress() = onProgress?.invoke()

    /** 필기가 바뀌어도 읽은 것을 다시 맞추도록, 잠깐 뒤 다시 따져 본다 (글자 찾기를 열어 둔 동안만) */
    fun inkChanged() {
        if (onProgress == null) return
        val g = ++generation
        scope.launch {
            kotlinx.coroutines.delay(REINDEX_DELAY_MS)
            if (g == generation && onProgress != null) start()
        }
    }

    /** 문서의 필기를 훑어 아직 읽지 않은 쪽을 읽는다. 이미 읽은 쪽(서명이 같은 쪽)은 건너뛴다 */
    fun start() {
        generation++
        job?.cancel()
        // 획은 화면 스레드에서 복사해 둔다 (읽는 동안 필기를 고쳐도 안전하게)
        val pages = ink.pages.map { readableStrokes(it) }
        pageHashes = pages.map { if (it.isEmpty()) null else InkHash.of(it) }
        totalPages = pageHashes.count { it != null }
        val hashes = pageHashes
        val gen = generation
        job = scope.launch {
            try {
                if (!cacheLoaded) {
                    cacheLoaded = true
                    withContext(Dispatchers.IO) { loadCache() }
                }
                val pending = hashes.indices.filter { hashes[it] != null && hashes[it] !in byHash }
                donePages = totalPages - pending.size
                if (pending.isEmpty()) {
                    state = State.DONE
                    notifyProgress()
                    return@launch
                }
                if (!recognizer.isReady()) {
                    state = State.NEEDS_DATA
                    notifyProgress()
                    onNeedsData?.invoke()
                    return@launch
                }
                state = State.WORKING
                notifyProgress()
                for (p in pending) {
                    if (gen != generation) return@launch
                    byHash[hashes[p]!!] = readPage(pages[p])
                    donePages++
                    notifyProgress()
                }
                state = State.DONE
                notifyProgress()
                withContext(Dispatchers.IO) { saveCache(hashes) }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                state = State.FAILED
                notifyProgress()
            }
        }
    }

    /** 읽는 데 필요한 데이터를 내려받고, 받았으면 이어서 읽는다. 못 받으면 false */
    suspend fun downloadData(): Boolean {
        state = State.DOWNLOADING
        notifyProgress()
        val ok = recognizer.download()
        if (ok) start() else {
            state = State.NEEDS_DATA
            notifyProgress()
        }
        return ok
    }

    /** [query]가 들어 있는 줄의 자리 (쪽 번호별, 쪽 좌표) */
    fun find(query: String): Map<Int, List<RectF>> {
        val q = PdfText.normalize(query)
        if (q.isEmpty()) return emptyMap()
        val out = sortedMapOf<Int, MutableList<RectF>>()
        for ((p, h) in pageHashes.withIndex()) {
            val lines = byHash[h ?: continue] ?: continue
            for (ln in lines) {
                // 후보 글 중 처음으로 찾는 말이 들어 있는 것으로 자리를 짐작한다 (같은 줄을 두 번 세지 않게)
                for (text in ln.texts) {
                    val t = PdfText.normalize(text)
                    var from = 0
                    var found = false
                    while (true) {
                        val i = t.indexOf(q, from)
                        if (i < 0) break
                        found = true
                        val b = InkLineMath.hitBox(ln.box, t.length, i, i + q.length)
                        out.getOrPut(p) { ArrayList() }.add(RectF(b[0], b[1], b[2], b[3]))
                        from = i + q.length
                    }
                    if (found) break
                }
            }
        }
        return out
    }

    /** 화면 아래에 보일 진행 안내 (없으면 null) */
    fun statusText(): String? = when {
        totalPages == 0 || state == State.IDLE -> null
        state == State.NEEDS_DATA -> "필기 검색: 데이터 받기 (눌러서)"
        state == State.DOWNLOADING -> "필기 데이터 받는 중…"
        state == State.WORKING -> "필기 읽는 중 $donePages/${totalPages}쪽"
        state == State.FAILED -> "필기를 읽지 못했습니다"
        else -> null
    }

    // ---- 읽기 ----

    /** 쪽의 필기 중 읽을 획들 (획마다 x, y를 번갈아 담은 복사본, 쓴 차례) */
    private fun readableStrokes(strokes: List<Stroke>): List<FloatArray> = strokes.mapNotNull { s ->
        if (s.tool != Tool.PEN || s.dashed || s.image != null || s.text != null || s.note != null ||
            s.tape != null || s.fill != null || s.count < 2
        ) null
        else FloatArray(s.count * 2).also { xy ->
            for (i in 0 until s.count) { xy[i * 2] = s.x(i); xy[i * 2 + 1] = s.y(i) }
        }
    }

    private fun boxOf(xy: FloatArray): FloatArray {
        val b = floatArrayOf(xy[0], xy[1], xy[0], xy[1])
        for (i in 1 until xy.size / 2) {
            val x = xy[i * 2]; val y = xy[i * 2 + 1]
            if (x < b[0]) b[0] = x; if (y < b[1]) b[1] = y; if (x > b[2]) b[2] = x; if (y > b[3]) b[3] = y
        }
        return b
    }

    /** 쪽의 획들을 글줄로 묶어 줄마다 읽는다 */
    private suspend fun readPage(strokes: List<FloatArray>): List<InkLineData> {
        val boxes = strokes.map(::boxOf)
        val out = ArrayList<InkLineData>()
        for (idx in LineGrouper.group(boxes)) {
            val box = LineGrouper.union(boxes, idx)
            if (!LineGrouper.worthReading(box, idx.size)) continue
            val texts = try {
                recognizer.recognize(idx.map { strokes[it] })
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                continue  // 이 줄만 건너뛴다
            }
            if (texts.isNotEmpty()) out.add(InkLineData(box, texts))
        }
        return out
    }

    // ---- 파일 ----

    private fun loadCache() {
        val f = cacheFile ?: return
        runCatching { if (f.isFile) byHash.putAll(HandwritingCodec.decode(f.readText())) }
    }

    /** 지금 문서에 있는 쪽의 결과만 남겨 적는다 (없어진 쪽의 결과가 쌓이지 않게) */
    private fun saveCache(hashes: List<Long?>) {
        val f = cacheFile ?: return
        runCatching {
            val keep = LinkedHashMap<Long, List<InkLineData>>()
            for (h in hashes) if (h != null) byHash[h]?.let { keep[h] = it }
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(HandwritingCodec.encode(keep))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    companion object {
        private const val REINDEX_DELAY_MS = 1500L

        /** 읽은 결과 파일들이 있는 폴더 */
        fun dir(ctx: android.content.Context) = File(ctx.filesDir, "hw_index")

        /** [oldestTime]보다 오래 쓰지 않은 결과 파일을 지운다 */
        fun cleanOld(dir: File, oldestTime: Long) {
            dir.listFiles()?.forEach { if (it.lastModified() < oldestTime) it.delete() }
        }
    }
}
