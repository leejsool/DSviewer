package com.dsviewer.app

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Properties
import kotlin.math.max

/** 자동 저장해 둔 문서 하나의 정보 (원래 문서가 어디인지, 어떤 상태였는지) */
internal data class DraftMeta(
    val id: String,
    /** 원래 문서의 uri (복구한 뒤 '저장'이 덮어쓸 곳) */
    val uri: String,
    val name: String,
    /** [DocType] 이름 (한글 문서를 PDF로 바꿔 열었더라도 처음 형식) */
    val type: String,
    val isNewNote: Boolean,
    val canOverwrite: Boolean,
    /** 마지막으로 자동 저장한 때 (ms) */
    val time: Long,
)

/**
 * 자동 저장 임시 파일 보관소: 문서마다 `<id>.pdf`(필기를 얹은 PDF)와 `<id>.meta`(정보) 한 쌍.
 * 쓰는 도중에 죽어도 옛 쌍이 멀쩡하도록 임시 파일에 다 쓴 뒤 바꿔 끼운다.
 * Android 클래스를 모르는 순수 파일 다루기라 JVM 단위 시험으로 확인한다.
 */
internal class DraftStore(private val dir: File) {

    /** 문서 주소 → 파일 이름에 쓸 수 있는 고정된 짧은 이름 */
    fun idOf(uri: String): String =
        MessageDigest.getInstance("SHA-1").digest(uri.toByteArray(StandardCharsets.UTF_8))
            .take(10).joinToString("") { "%02x".format(it) }

    fun pdf(id: String) = File(dir, "$id.pdf")

    /** 필기를 얹은 PDF를 쓰는 곳 (다 쓰면 [commit]) */
    fun tmp(id: String): File = File(dir, "$id.tmp").also { dir.mkdirs() }

    private fun metaFile(id: String) = File(dir, "$id.meta")

    /** 다 쓴 임시 PDF [written]를 자동 저장본으로 삼고 정보를 적는다. 못 하면 false */
    fun commit(meta: DraftMeta, written: File): Boolean = try {
        dir.mkdirs()
        Files.move(written.toPath(), pdf(meta.id).toPath(), StandardCopyOption.REPLACE_EXISTING)
        val props = Properties().apply {
            setProperty("uri", meta.uri)
            setProperty("name", meta.name)
            setProperty("type", meta.type)
            setProperty("newNote", meta.isNewNote.toString())
            setProperty("canOverwrite", meta.canOverwrite.toString())
            setProperty("time", meta.time.toString())
        }
        val tmpMeta = File(dir, "${meta.id}.meta.tmp")
        tmpMeta.writer(StandardCharsets.UTF_8).use { props.store(it, null) }
        Files.move(tmpMeta.toPath(), metaFile(meta.id).toPath(), StandardCopyOption.REPLACE_EXISTING)
        true
    } catch (e: Exception) {
        written.delete()
        false
    }

    /** 자동 저장본의 정보. 없거나 망가졌거나 PDF가 없으면 null */
    fun meta(id: String): DraftMeta? {
        val f = metaFile(id)
        if (!f.isFile || !pdf(id).isFile) return null
        return try {
            val p = Properties().apply { f.reader(StandardCharsets.UTF_8).use { load(it) } }
            val uri = p.getProperty("uri") ?: return null
            DraftMeta(
                id, uri, p.getProperty("name") ?: "문서",
                p.getProperty("type") ?: DocType.PDF.name,
                p.getProperty("newNote") == "true", p.getProperty("canOverwrite") == "true",
                p.getProperty("time")?.toLongOrNull() ?: 0L,
            )
        } catch (e: Exception) {
            null
        }
    }

    /** 자동 저장본 모두, 최근 것부터 */
    fun list(): List<DraftMeta> =
        (dir.listFiles() ?: emptyArray()).filter { it.name.endsWith(".meta") }
            .mapNotNull { meta(it.name.removeSuffix(".meta")) }
            .sortedByDescending { it.time }

    fun delete(id: String) {
        pdf(id).delete()
        metaFile(id).delete()
        File(dir, "$id.tmp").delete()
        File(dir, "$id.meta.tmp").delete()
    }

    /**
     * 오래됐거나(마지막 저장이 [oldestTime]보다 전) 짝이 맞지 않는 파일과 쓰다 만 임시 파일을 지운다.
     * 쓰는 중인 파일을 지우지 않도록 열려 있는 문서가 없을 때만 부른다
     */
    fun cleanup(oldestTime: Long) {
        val files = dir.listFiles() ?: return
        files.filter { it.name.endsWith(".tmp") }.forEach { it.delete() }
        val valid = HashSet<String>()
        for (m in list()) {
            if (m.time < oldestTime) delete(m.id) else valid.add(m.id)
        }
        files.forEach { f ->
            val id = f.name.substringBefore('.')
            if (id !in valid) f.delete()
        }
    }
}

/**
 * 자동 저장을 언제 할지. 필기가 멈추고 잠깐 뒤에 저장하되, 계속 쓰는 중이어도 너무 오래 미루지 않고,
 * 큰 문서처럼 저장이 오래 걸리면 그만큼 간격을 벌려 기기를 붙잡지 않는다.
 */
internal object AutoSavePolicy {
    /** 마지막 변경 뒤 이만큼 가만히 있으면 저장 */
    const val IDLE_MS = 5_000L
    /** 계속 쓰는 중이어도 저장하지 않은 채로 이보다 오래 두지 않는다 */
    const val MAX_STALE_MS = 60_000L
    /** 저장과 저장 사이 최소 간격 */
    const val MIN_GAP_MS = 20_000L
    /** 저장에 걸린 시간의 이 배수만큼은 쉰다 */
    const val COST_FACTOR = 10L
    private const val MAX_GAP_MS = 10 * 60_000L

    fun gap(lastCostMs: Long): Long = max(MIN_GAP_MS, lastCostMs * COST_FACTOR).coerceAtMost(MAX_GAP_MS)

    /**
     * 지금부터 몇 ms 뒤에 저장할지 (0 이하면 지금).
     * [dirtySince]는 저장하지 않은 변경이 처음 생긴 때, [lastChange]는 마지막 변경, [lastSaveEnd]는 지난 저장이 끝난 때(없으면 0)
     */
    fun delay(now: Long, dirtySince: Long, lastChange: Long, lastSaveEnd: Long, lastCostMs: Long): Long {
        val want = minOf(lastChange + IDLE_MS, dirtySince + MAX_STALE_MS)
        val earliest = if (lastSaveEnd > 0) lastSaveEnd + gap(lastCostMs) else 0L
        return max(want, earliest) - now
    }
}
