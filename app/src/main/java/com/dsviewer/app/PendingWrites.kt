package com.dsviewer.app

import kotlinx.coroutines.Job
import java.util.concurrent.ConcurrentHashMap

/**
 * 지금 뒤에서 쓰는 중인 문서들 (문서 주소 → 그 일).
 * 탭을 닫으며 저장을 뒤에 맡기면, 같은 문서를 곧바로 다시 열 때 아직 옛 내용이 파일에 있을 수 있다.
 * 열기 쪽이 [await]로 그 저장이 끝나기를 기다리면 옛 내용을 열지 않는다
 */
internal class PendingWrites {
    private val jobs = ConcurrentHashMap<String, Job>()

    /** [key] 문서를 [job]이 쓰는 중이라고 적어 둔다 */
    fun add(key: String, job: Job) {
        jobs[key] = job
    }

    /** [job]이 끝났으면 지운다 (그새 같은 문서를 다른 일이 쓰기 시작했으면 그대로 둔다) */
    fun remove(key: String, job: Job) {
        jobs.remove(key, job)
    }

    fun isPending(key: String) = jobs.containsKey(key)

    /** [key] 문서를 쓰는 일이 있으면 끝나기를 기다린다. 기다리는 사이 이어서 시작한 일도 기다린다 */
    suspend fun await(key: String) {
        while (true) {
            val job = jobs[key] ?: return
            job.join()
            jobs.remove(key, job)  // 끝난 일이 남아 있으면 지우고 다시 본다
        }
    }
}
