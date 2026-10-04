package com.dsviewer.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 탭 하나의 자동 저장 진행 상태 */
internal class DraftState {
    /** 저장하지 않은 변경이 처음 생긴 때 (0 = 없음) */
    var dirtySince = 0L
    var lastChange = 0L
    var lastSaveEnd = 0L
    var lastCostMs = 0L
    var saving = false
    /** 저장하거나 버릴 때마다 올린다: 쓰는 동안 바뀌었으면 그 결과는 쓰지 않는다 */
    var generation = 0
    /** 닫기 직전처럼 간격을 두지 않고 곧바로 저장해야 함 */
    var urgent = false
    /** 지금 자동 저장본이 담고 있는 마지막 변경의 때 */
    var savedChange = 0L
    /** 자동 저장본의 이름표 (문서 주소가 바뀌어도 옛 것을 지울 수 있게) */
    var draftId: String? = null
    var scheduled: Runnable? = null
    /** 탭을 닫는 중: 탭 목록에서 빠진 뒤에도 마지막 필기를 적는다 */
    var closing = false
}

/**
 * 자동 저장: 저장하지 않은 필기를 일정 간격으로(그리고 앱이 뒤로 갈 때) 앱 저장소에 PDF로 적어 둔다.
 * 시스템이 앱을 끄거나 배터리가 나가도, 다시 열 때 [DraftStore]에서 꺼내 복구할 수 있다.
 * 원래 문서에는 손대지 않는다 (사용자가 '저장'을 눌러야 원본에 쓴다).
 */
internal class AutoSaver(
    context: Context,
    private val store: DraftStore,
    private val tabs: () -> List<DocTab>,
) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())

    /** 탭의 필기가 바뀔 때마다 (저장돼서 깨끗해졌으면 자동 저장본을 지운다) */
    fun onInkChanged(t: DocTab) {
        val st = t.draft
        if (t.ink?.dirty != true) {
            discard(t)
            return
        }
        val now = System.currentTimeMillis()
        if (st.dirtySince == 0L) st.dirtySince = now
        st.lastChange = now
        schedule(t)
    }

    /** 앱이 뒤로 가거나 닫힐 때: 아직 적지 않은 변경을 곧바로 적는다 */
    fun flush() {
        for (t in tabs()) {
            val st = t.draft
            if (t.ink?.dirty != true || st.lastChange <= st.savedChange) continue
            st.urgent = true
            schedule(t)
        }
    }

    /** 저장했거나 '저장 안 함'으로 닫아서 더 필요 없는 자동 저장본을 지운다 */
    fun discard(t: DocTab) {
        if (t.review != null) return  // 복습 풀이는 파일로 남기는 것이라 지우지 않는다
        val st = t.draft
        st.generation++
        st.scheduled?.let(handler::removeCallbacks)
        st.scheduled = null
        st.dirtySince = 0L
        st.savedChange = 0L
        st.urgent = false
        st.draftId?.let(store::delete)
        st.draftId = null
        store.delete(store.idOf(t.uri.toString()))
    }

    /** 탭을 닫기 전에 마지막 필기까지 곧바로 적는다 (복습 풀이처럼 닫아도 남아야 하는 것) */
    fun saveNow(t: DocTab) {
        val st = t.draft
        st.closing = true
        st.scheduled?.let(handler::removeCallbacks)
        st.scheduled = null
        if (st.saving) {  // 쓰는 중이면 끝난 뒤 바로 이어서 적는다
            st.urgent = true
            return
        }
        if (t.ink?.dirty == true && st.lastChange > st.savedChange) run(t)
    }

    private fun schedule(t: DocTab) {
        val st = t.draft
        if (st.saving) return  // 끝나면 다시 따진다
        st.scheduled?.let(handler::removeCallbacks)
        val wait = if (st.urgent) 0L
        else AutoSavePolicy.delay(System.currentTimeMillis(), st.dirtySince, st.lastChange, st.lastSaveEnd, st.lastCostMs)
        val r = Runnable { st.scheduled = null; run(t) }
        st.scheduled = r
        handler.postDelayed(r, wait.coerceAtLeast(0L))
    }

    private fun run(t: DocTab) {
        val st = t.draft
        val inkDoc = t.ink ?: return
        val src = t.sourcePdf ?: return
        if (!inkDoc.dirty || (t !in tabs() && !st.closing)) return
        if (t.pagesBusy) {  // 쪽을 넣고 빼는 중에는 PDF가 바뀌고 있으니 잠시 뒤에
            handler.postDelayed({ schedule(t) }, BUSY_RETRY_MS)
            return
        }
        // 화면 스레드에서 지금 상태를 찍어 두고, 파일로 쓰는 일만 뒤에서 한다 (필기를 고치는 동안에도 안전하게)
        val snapshot = inkDoc.snapshot()
        val marks = inkDoc.bookmarkedPages()
        val wrongSave = inkDoc.wrongSave()
        val id = store.idOf(t.uri.toString())
        val meta = DraftMeta(id, t.uri.toString(), t.name, t.type.name, t.isNewNote, t.canOverwrite, System.currentTimeMillis())
        val gen = st.generation
        val startedChange = st.lastChange
        val started = System.currentTimeMillis()
        st.saving = true
        st.urgent = false
        // 복습 풀이는 자동 저장본이 아니라 그 파일에 바로 쓴다 (같은 폴더의 임시 파일에 다 쓴 뒤 바꿔 끼운다)
        val reviewFile = t.review?.saveFile
        scope.launch {
            val tmp = if (reviewFile != null) File(reviewFile.parentFile, reviewFile.name + ".tmp").also { it.parentFile?.mkdirs() }
            else store.tmp(id)
            val ok = try {
                withContext(Dispatchers.IO) { PdfInk.save(src, tmp, snapshot, marks, wrongSave) }
                true
            } catch (e: Throwable) {  // 메모리 부족도 앱을 죽이지 않고 이번만 건너뛴다
                tmp.delete()
                false
            }
            val now = System.currentTimeMillis()
            st.saving = false
            val committed = ok && gen == st.generation &&
                if (reviewFile != null) moveReview(tmp, reviewFile) else store.commit(meta.copy(time = now), tmp)
            if (committed) {
                if (reviewFile == null) st.draftId = id
                st.savedChange = startedChange
            } else {
                tmp.delete()
            }
            st.lastSaveEnd = now
            st.lastCostMs = now - started
            if (gen != st.generation) return@launch  // 쓰는 사이 저장되거나 닫힘
            // 쓰는 사이 또 바뀌었으면 이어서
            if (st.lastChange > startedChange) {
                st.dirtySince = now
                schedule(t)
            } else {
                st.dirtySince = 0L
                st.urgent = false
            }
        }
    }

    /** 다 쓴 임시 파일을 복습 풀이 파일로 바꿔 끼우고 목록에 보이게 한다 */
    private fun moveReview(tmp: File, target: File): Boolean = try {
        java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        android.media.MediaScannerConnection.scanFile(appContext, arrayOf(target.path), null, null)
        true
    } catch (e: Exception) {
        false
    }

    companion object {
        private const val BUSY_RETRY_MS = 3_000L
        /** 화면이 닫혀도 쓰던 자동 저장은 끝까지 마친다 */
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }
}

/** 자동 저장본 보관소 (앱 전용 저장소 안 drafts 폴더) */
internal object Drafts {
    fun store(ctx: Context) = DraftStore(java.io.File(ctx.filesDir, "drafts"))
}
