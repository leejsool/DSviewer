package com.dsviewer.app

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

enum class DocType { PDF, HWP, HWPX, UNKNOWN }

object FileUtil {

    fun displayName(ctx: Context, uri: Uri): String {
        runCatching {
            ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val name = c.getString(0)
                    if (!name.isNullOrBlank()) return name
                }
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() } ?: "문서"
    }

    private fun docsDir(ctx: Context) = File(ctx.cacheDir, "docs").apply { mkdirs() }

    /** 원본을 앱 캐시로 복사 (PdfRenderer는 탐색 가능한 파일이 필요) */
    fun copyToCache(ctx: Context, uri: Uri, name: String): File {
        val safe = name.replace(Regex("[\\\\/:*?\"<>|]"), "_").takeLast(80)
        val f = File(docsDir(ctx), "src_${System.currentTimeMillis()}_$safe")
        val input = ctx.contentResolver.openInputStream(uri) ?: error("파일을 열 수 없습니다.")
        input.use { i -> f.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
        return f
    }

    fun tempFile(ctx: Context, prefix: String, ext: String) =
        File(docsDir(ctx), "${prefix}_${System.currentTimeMillis()}.$ext")

    /** 하루 넘은 캐시 파일 정리 */
    fun cleanOld(ctx: Context) {
        val limit = System.currentTimeMillis() - 24L * 3600 * 1000
        docsDir(ctx).listFiles()?.forEach { if (it.lastModified() < limit) it.delete() }
    }

    fun detect(name: String, mime: String?, file: File): DocType {
        val lower = name.lowercase()
        when {
            lower.endsWith(".pdf") -> return DocType.PDF
            lower.endsWith(".hwpx") -> return DocType.HWPX
            lower.endsWith(".hwp") -> return DocType.HWP
        }
        when (mime) {
            "application/pdf" -> return DocType.PDF
            "application/x-hwp", "application/haansofthwp", "application/vnd.hancom.hwp" -> return DocType.HWP
            "application/x-hwpx", "application/haansofthwpx", "application/vnd.hancom.hwpx", "application/hwp+zip" -> return DocType.HWPX
        }
        // 확장자/형식을 모르면 파일 앞부분으로 판별
        val head = ByteArray(8)
        val n = file.inputStream().use { it.read(head) }
        if (n >= 4) {
            if (head[0] == '%'.code.toByte() && head[1] == 'P'.code.toByte() && head[2] == 'D'.code.toByte() && head[3] == 'F'.code.toByte()) return DocType.PDF
            if (head[0] == 0xD0.toByte() && head[1] == 0xCF.toByte() && head[2] == 0x11.toByte() && head[3] == 0xE0.toByte()) return DocType.HWP
            if (head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()) return DocType.HWPX
        }
        return DocType.UNKNOWN
    }

    fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}
