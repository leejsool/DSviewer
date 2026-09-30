package com.dsviewer.app

import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File

enum class DocType {
    PDF, HWP, HWPX, DOCX, DOC, PPTX, PPT, TXT, IMAGE, UNKNOWN;

    /** 한글 문서 (탭·목록에서 같은 색) */
    val isHangul get() = this == HWP || this == HWPX
    val isWord get() = this == DOCX || this == DOC
    val isPowerPoint get() = this == PPTX || this == PPT

    companion object {
        /** 확장자 → 형식 */
        val BY_EXT = mapOf(
            "pdf" to PDF, "hwp" to HWP, "hwpx" to HWPX,
            "docx" to DOCX, "docm" to DOCX, "dotx" to DOCX, "dotm" to DOCX, "doc" to DOC, "dot" to DOC,
            "pptx" to PPTX, "pptm" to PPTX, "ppsx" to PPTX, "ppsm" to PPTX, "potx" to PPTX, "potm" to PPTX,
            "ppt" to PPT, "pps" to PPT, "pot" to PPT,
            "txt" to TXT, "text" to TXT, "log" to TXT, "md" to TXT, "csv" to TXT,
            "jpg" to IMAGE, "jpeg" to IMAGE, "png" to IMAGE, "webp" to IMAGE, "gif" to IMAGE, "bmp" to IMAGE,
            "heic" to IMAGE, "heif" to IMAGE, "avif" to IMAGE,
        )

        fun ofName(name: String): DocType = BY_EXT[name.substringAfterLast('.', "").lowercase()] ?: UNKNOWN
    }
}

/** 파일 탐색기에서 골라 볼 수 있는 형식 묶음 (이 순서가 '종류' 정렬 순서) */
enum class DocGroup(val label: String, val exts: String) {
    PDF("PDF", "pdf"),
    HANGUL("한글", "hwp · hwpx"),
    WORD("워드", "docx · doc"),
    POWERPOINT("파워포인트", "pptx · ppt"),
    TEXT("텍스트", "txt · md · csv"),
    IMAGE("그림", "jpg · png · heic 등");

    companion object {
        fun of(t: DocType): DocGroup? = when {
            t == DocType.PDF -> PDF
            t.isHangul -> HANGUL
            t.isWord -> WORD
            t.isPowerPoint -> POWERPOINT
            t == DocType.TXT -> TEXT
            t == DocType.IMAGE -> IMAGE
            else -> null
        }
    }
}

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
        val byName = DocType.ofName(name)
        val byMime = when {
            mime == null -> DocType.UNKNOWN
            mime == "application/pdf" -> DocType.PDF
            mime in HWP_MIMES -> DocType.HWP
            mime in HWPX_MIMES -> DocType.HWPX
            mime == "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocType.DOCX
            mime == "application/msword" -> DocType.DOC
            mime.startsWith("application/vnd.openxmlformats-officedocument.presentationml") -> DocType.PPTX
            mime == "application/vnd.ms-powerpoint" || mime == "application/mspowerpoint" -> DocType.PPT
            mime == "text/plain" -> DocType.TXT
            mime.startsWith("image/") -> DocType.IMAGE
            else -> DocType.UNKNOWN
        }
        // 파일 앞부분으로 확인 (확장자가 틀리거나 없는 경우: 예를 들어 .doc 이름의 docx)
        val sniffed = runCatching { sniff(file) }.getOrDefault(DocType.UNKNOWN)
        val claimed = if (byName != DocType.UNKNOWN) byName else byMime
        return when {
            sniffed == DocType.UNKNOWN -> claimed
            claimed == DocType.UNKNOWN -> sniffed
            // 글 파일은 내용으로 알아낼 수 없으니 이름을 믿는다. 나머지는 내용이 다르면 내용을 따른다
            claimed == DocType.TXT && sniffed != DocType.PDF -> claimed
            else -> sniffed
        }
    }

    private val HWP_MIMES = setOf("application/x-hwp", "application/haansofthwp", "application/vnd.hancom.hwp")
    private val HWPX_MIMES = setOf("application/x-hwpx", "application/haansofthwpx", "application/vnd.hancom.hwpx", "application/hwp+zip")

    private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "mif1", "msf1", "avif", "avis")

    /** 파일 머리로 형식 알아내기 (모르면 UNKNOWN) */
    private fun sniff(file: File): DocType {
        val head = ByteArray(16)
        val n = file.inputStream().use { it.read(head) }
        if (n < 4) return DocType.UNKNOWN
        fun at(i: Int, vararg b: Int) = b.withIndex().all { (k, v) -> head[i + k] == v.toByte() }
        return when {
            at(0, '%'.code, 'P'.code, 'D'.code, 'F'.code) -> DocType.PDF
            at(0, 0xD0, 0xCF, 0x11, 0xE0) -> {
                // OLE 복합 문서: 한글·워드·파워포인트 모두 이 형식
                val names = com.dsviewer.app.hwp.Cfb(file).names()
                when {
                    names.any { it == "WordDocument" } -> DocType.DOC
                    names.any { it == "PowerPoint Document" } -> DocType.PPT
                    names.any { it == "FileHeader" } -> DocType.HWP
                    else -> DocType.UNKNOWN
                }
            }
            at(0, 'P'.code, 'K'.code) -> java.util.zip.ZipFile(file).use { z ->
                when {
                    z.getEntry("ppt/presentation.xml") != null -> DocType.PPTX
                    z.getEntry("word/document.xml") != null -> DocType.DOCX
                    z.getEntry("Contents/header.xml") != null || z.getEntry("mimetype") != null -> DocType.HWPX
                    else -> DocType.UNKNOWN
                }
            }
            at(0, 0xFF, 0xD8, 0xFF) || at(0, 0x89, 'P'.code, 'N'.code, 'G'.code) || at(0, 'G'.code, 'I'.code, 'F'.code, '8'.code) ||
                (at(0, 'R'.code, 'I'.code, 'F'.code, 'F'.code) && n >= 12 && at(8, 'W'.code, 'E'.code, 'B'.code, 'P'.code)) ||
                at(0, 'B'.code, 'M'.code) && n >= 14 ||
                (n >= 12 && at(4, 'f'.code, 't'.code, 'y'.code, 'p'.code) && String(head, 8, 4, Charsets.ISO_8859_1) in HEIF_BRANDS) -> DocType.IMAGE
            else -> DocType.UNKNOWN
        }
    }

    /** 공유할 때 알릴 파일 종류 */
    fun mimeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase()
        return when (DocType.ofName(name)) {
            DocType.PDF -> "application/pdf"
            DocType.HWP -> "application/x-hwp"
            DocType.HWPX -> "application/hwp+zip"
            DocType.DOCX -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            DocType.DOC -> "application/msword"
            DocType.PPTX -> if (ext.startsWith("pps")) "application/vnd.openxmlformats-officedocument.presentationml.slideshow"
            else "application/vnd.openxmlformats-officedocument.presentationml.presentation"
            DocType.PPT -> "application/vnd.ms-powerpoint"
            DocType.TXT -> "text/plain"
            DocType.IMAGE -> when (ext) {
                "jpg", "jpeg" -> "image/jpeg"
                "heic", "heif" -> "image/heif"
                else -> "image/$ext"
            }
            DocType.UNKNOWN -> "application/octet-stream"
        }
    }

    fun isJpeg(file: File): Boolean = runCatching {
        val h = ByteArray(3)
        file.inputStream().use { it.read(h) } == 3 && h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() && h[2] == 0xFF.toByte()
    }.getOrDefault(false)

    fun baseName(name: String): String {
        val dot = name.lastIndexOf('.')
        return if (dot > 0) name.substring(0, dot) else name
    }
}

/** 문서 종류 색 (탭 아이콘, 탐색기 배지) */
object DocColors {
    val PDF = Color.parseColor("#D93025")
    val HWP = Color.parseColor("#2F6FC4")
    val WORD = Color.parseColor("#1F5BB5")
    val POWERPOINT = Color.parseColor("#D24726")
    val TEXT = Color.parseColor("#5F6B7A")
    val IMAGE = Color.parseColor("#188038")

    fun of(type: DocType): Int = when {
        type == DocType.PDF -> PDF
        type.isHangul -> HWP
        type.isWord -> WORD
        type.isPowerPoint -> POWERPOINT
        type == DocType.TXT -> TEXT
        type == DocType.IMAGE -> IMAGE
        else -> TEXT
    }

    fun of(name: String): Int = of(DocType.ofName(name).let { if (it == DocType.UNKNOWN) DocType.HWP else it })
}
