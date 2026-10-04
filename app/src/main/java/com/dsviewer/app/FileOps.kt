package com.dsviewer.app

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/** 파일 탐색기의 여러 문서 명령: 옮기기 · 사본 만들기 · 지우기. 최근 목록·잠금 기록도 새 자리로 옮긴다 */
object FileOps {

    /** "이름 (2)", "이름 (3)" … 처럼 [taken]에 없는 이름. [withExt]면 확장자 앞에 번호 */
    fun uniqueName(name: String, withExt: Boolean = true, taken: (String) -> Boolean): String {
        if (!taken(name)) return name
        val dot = name.lastIndexOf('.')
        val hasExt = withExt && dot > 0 && name.length - dot <= 6
        val base = if (hasExt) name.substring(0, dot) else name
        val ext = if (hasExt) name.substring(dot) else ""
        val m = Regex("^(.*) \\((\\d+)\\)$").find(base)
        val stem = m?.groupValues?.get(1) ?: base
        var n = m?.groupValues?.get(2)?.toInt()?.plus(1) ?: 2
        while (true) {
            val cand = "$stem ($n)$ext"
            if (!taken(cand)) return cand
            n++
        }
    }

    private fun uniqueFile(dir: File, name: String) = File(dir, uniqueName(name) { File(dir, it).exists() })

    /** [src]를 [dir] 폴더로 옮긴다 (같은 이름이 있으면 번호를 붙임). 새 파일을 돌려준다 */
    fun move(ctx: Context, src: File, dir: File): File {
        if (src.parentFile?.path == dir.path) return src
        val dest = uniqueFile(dir, src.name)
        if (!src.renameTo(dest)) {
            // 다른 저장소(SD카드 등)로: 복사 후 지움
            src.copyTo(dest)
            if (!src.delete()) {
                dest.delete()
                error("원래 파일을 지우지 못했습니다.")
            }
        }
        relink(ctx, Uri.fromFile(src).toString(), Uri.fromFile(dest).toString(), dest.name)
        scan(ctx, src, dest)
        return dest
    }

    /** 같은 폴더에 "이름 (사본).pdf". 다른 위치에서 연 문서([uri]가 content://)는 다운로드 폴더에 */
    fun copy(ctx: Context, uri: Uri, name: String): File {
        val local = uri.scheme == "file"
        val dir = if (local) File(uri.path!!).parentFile!!
        else Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val base = FileUtil.baseName(name)
        val ext = name.substring(base.length)
        val dest = uniqueFile(dir, "$base (사본)$ext")
        if (local) File(uri.path!!).copyTo(dest)
        else {
            val input = ctx.contentResolver.openInputStream(uri) ?: error("파일을 열 수 없습니다.")
            input.use { i -> dest.outputStream().use { o -> i.copyTo(o) } }
        }
        scan(ctx, dest)
        return dest
    }

    /** 이름 바꾸기 ([name]은 확장자까지). 같은 이름이 있으면 번호를 붙인다. 새 uri를 돌려준다 */
    fun rename(ctx: Context, uri: Uri, name: String): Uri {
        val new = if (uri.scheme == "file") {
            val src = File(uri.path!!)
            if (src.name == name) return uri
            val dest = uniqueFile(src.parentFile!!, name)
            if (!src.renameTo(dest)) error("이름을 바꾸지 못했습니다.")
            scan(ctx, src, dest)
            Uri.fromFile(dest)
        } else {
            DocumentsContract.renameDocument(ctx.contentResolver, uri, name) ?: error("이름을 바꾸지 못했습니다.")
        }
        relink(ctx, uri.toString(), new.toString(), FileUtil.displayName(ctx, new))
        return new
    }

    /** 기기에서 지운다. 최근 목록·잠금 기록도 지움 */
    fun delete(ctx: Context, uri: Uri): Boolean {
        val ok = if (uri.scheme == "file") {
            val f = File(uri.path ?: return false)
            (f.delete() || !f.exists()).also { if (it) scan(ctx, f) }
        } else runCatching { DocumentsContract.deleteDocument(ctx.contentResolver, uri) }.getOrDefault(false)
        if (ok) {
            Recents.remove(ctx, uri.toString())
            Locks.forget(ctx, uri.toString())
            ReviewIndexStore.remove(ctx, uri.toString())
        }
        return ok
    }

    private fun relink(ctx: Context, old: String, new: String, name: String) {
        Recents.relink(ctx, old, new, name)
        Locks.relink(ctx, old, new)
        ReviewIndexStore.relink(ctx, old, new, name)
    }

    /** 미디어 색인('모든 문서' 탭이 쓰는 목록)에 알린다. 없어진 파일은 색인에서 빠진다 */
    private fun scan(ctx: Context, vararg files: File) {
        MediaScannerConnection.scanFile(ctx.applicationContext, files.map { it.path }.toTypedArray(), null, null)
    }
}
