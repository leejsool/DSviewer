package com.dsviewer.app

import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * 저장하지 않은 채 남은 자동 저장본을 문서를 열지 않고 한꺼번에 저장한다 (앱을 새로 켰을 때 '모두 저장').
 * 원래 문서가 있으면 그 파일에 덮어쓰고, 새 노트나 한글·워드처럼 덮어쓸 원본이 없는 것은
 * 문서 폴더의 `DSnote` 폴더에 새 PDF로 둔다 (폴더를 만들 수 없으면 그대로 남겨 둔다).
 * 저장에 성공한 자동 저장본만 지우고, 실패한 것은 그대로 남아 나중에 하나씩 복구할 수 있다
 */
internal object DraftSaver {

    /** 저장 방법: 원래 문서에 덮어쓰기 / 새 PDF로 두기 */
    enum class Plan { OVERWRITE, SAVE_NEW }

    /** 저장하고 나서 알려 줄 것: 이름과 둔 곳 (덮어썼으면 null) */
    class Saved(val name: String, val folder: String?)

    /** 저장하지 못해 남겨 둔 것: 이름과 이유 */
    class Left(val name: String, val reason: String)

    class Result(val saved: List<Saved>, val left: List<Left>)

    /** 새 PDF를 두는 폴더 이름 (문서 폴더 안) */
    const val FOLDER = "DSnote"

    /**
     * 이 자동 저장본을 어떻게 저장할지. 덮어쓸 수 있는 PDF 문서([DraftMeta.canOverwrite])이고 원본이 아직 있으면 덮어쓰고,
     * 새 노트·변환한 문서·원본이 없어진 문서는 새 PDF로 둔다
     */
    fun plan(m: DraftMeta, originalExists: Boolean): Plan =
        if (m.canOverwrite && !m.isNewNote && m.type == DocType.PDF.name && originalExists) Plan.OVERWRITE else Plan.SAVE_NEW

    /** 새 PDF로 둘 때의 파일 이름: 새 노트는 '노트 날짜', PDF는 '이름_필기', 그 밖의 문서는 '이름' ([date]는 yyyy-MM-dd) */
    fun newFileName(m: DraftMeta, date: String): String {
        if (m.isNewNote) return "노트 $date.pdf"
        val base = FileNames.safe(FileNames.baseName(m.name)).ifBlank { "문서" }
        return if (m.type == DocType.PDF.name) "${base}_필기.pdf" else "$base.pdf"
    }

    /**
     * [metas]를 차례로 저장한다 (IO 스레드에서 부른다). 하나가 끝날 때마다 [progress]에 (몇 번째, 전체, 이름)을 알린다.
     * 같은 문서를 뒤에서 저장하는 중이면 그 문서는 건너뛴다
     */
    fun saveAll(
        ctx: Context, store: DraftStore, metas: List<DraftMeta>, progress: (Int, Int, String) -> Unit,
    ): Result {
        val saved = ArrayList<Saved>()
        val left = ArrayList<Left>()
        metas.forEachIndexed { i, m ->
            progress(i, metas.size, m.name)
            if (DocSaver.isPending(Uri.parse(m.uri))) {
                left += Left(m.name, "지금 저장하는 중입니다")
                return@forEachIndexed
            }
            try {
                saved += saveOne(ctx, store, m)
            } catch (e: Throwable) {  // 메모리 부족도 앱을 죽이지 않고 이 문서만 건너뛴다
                left += Left(m.name, e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName)
            }
        }
        progress(metas.size, metas.size, "")
        return Result(saved, left)
    }

    private fun originalExists(ctx: Context, uri: Uri): Boolean = when (uri.scheme) {
        "file" -> uri.path?.let { File(it).isFile } == true
        else -> runCatching { ctx.contentResolver.openFileDescriptor(uri, "r")?.close() != null }.getOrDefault(false)
    }

    /** 자동 저장본 하나를 저장하고 지운다. 못 하면 예외 (자동 저장본은 그대로 남는다) */
    private fun saveOne(ctx: Context, store: DraftStore, m: DraftMeta): Saved {
        val uri = Uri.parse(m.uri)
        val plan = plan(m, originalExists(ctx, uri))
        val dir = File(ctx.cacheDir, "draftsave").apply { mkdirs() }
        val made = ArrayList<File>()  // 지울 임시 파일
        try {
            // 문서(필기 없는 PDF)와 필기를 꺼낸다: 복구해서 여는 것(ViewerActivity.openPdf)과 같은 방식
            val marks = HashSet<Int>()
            val wrongs = HashMap<Int, String>()
            val clean: File
            val strokes: List<List<Stroke>>
            if (m.lite) {
                val base = store.base(m.id)
                clean = if (PdfInk.containsInk(base)) File.createTempFile("clean", ".pdf", dir).also { f ->
                    made += f
                    PdfInk.extract(base, f)
                } else base
                val scratch = File.createTempFile("ink", ".pdf", dir).also { made += it }
                strokes = PdfInk.extract(store.pdf(m.id), scratch, marks, wrongs)
            } else {
                clean = File.createTempFile("clean", ".pdf", dir).also { made += it }
                strokes = PdfInk.extract(store.pdf(m.id), clean, marks, wrongs)
            }
            val ink = InkDocument(strokes.size).apply { load(strokes, marks); loadWrongs(wrongs) }
            val wrongSave = ink.wrongSave()

            val saved: Saved
            val cacheFor: File?  // 내 기기 안의 파일일 때만 (보관본을 둘 곳)
            val indexUri: String  // 복습 색인에 적을 문서 주소
            when (plan) {
                Plan.OVERWRITE -> {
                    cacheFor = writeOriginal(ctx, uri, clean, strokes, marks, wrongSave)
                    saved = Saved(m.name, null)
                    indexUri = m.uri
                }
                Plan.SAVE_NEW -> {
                    val folder = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS), FOLDER)
                    if (!folder.isDirectory && !folder.mkdirs()) error("문서 폴더에 저장할 수 없습니다 (모든 파일 접근 권한이 필요합니다)")
                    val date = android.text.format.DateFormat.format("yyyy-MM-dd", m.time).toString()
                    val name = FileOps.uniqueName(newFileName(m, date)) { File(folder, it).exists() }
                    val dest = File(folder, name)
                    writeFile(dest, clean, strokes, marks, wrongSave)
                    cacheFor = dest
                    saved = Saved(name, "${Environment.DIRECTORY_DOCUMENTS}/$FOLDER")
                    indexUri = Uri.fromFile(dest).toString()
                }
            }
            if (cacheFor != null) {
                MediaScannerConnection.scanFile(ctx, arrayOf(cacheFor.path), null, null)
                // 다음에 열 때 빨리 열리도록 보관해 둔다 (못 해도 저장에는 영향이 없다)
                runCatching { InkCache.store(ctx, cacheFor, clean, strokes, marks, wrongSave.metas) }
            }
            ReviewIndexStore.updateLater(ctx, indexUri, saved.name, ink.allWrongs().map { it.second })
            store.delete(m.id)
            return saved
        } finally {
            made.forEach { it.delete() }
        }
    }

    /** 원래 문서에 덮어쓴다. 파일 주소면 같은 폴더의 임시 파일에 다 쓴 뒤 바꿔 끼운다. 보관본을 둘 파일(내 기기 안의 파일일 때 그 문서, 아니면 null)을 돌려준다 */
    private fun writeOriginal(
        ctx: Context, uri: Uri, clean: File, strokes: List<List<Stroke>>, marks: Set<Int>, wrongSave: WrongSave,
    ): File? {
        if (uri.scheme == "file") {
            val dest = File(uri.path ?: error("문서 위치를 알 수 없습니다"))
            writeFile(dest, clean, strokes, marks, wrongSave)
            return dest
        }
        val tmp = File.createTempFile("out", ".pdf", File(ctx.cacheDir, "draftsave"))
        try {
            PdfInk.save(clean, tmp, strokes, marks, wrongSave)
            val out = ctx.contentResolver.openOutputStream(uri, "wt") ?: error("저장 위치를 열 수 없습니다")
            out.use { o -> tmp.inputStream().use { it.copyTo(o, 1 shl 16) } }
        } finally {
            tmp.delete()
        }
        return null  // 내 기기 안의 파일이 아니라 보관본은 쓰이지 않는다
    }

    /** [dest] 옆의 임시 파일에 다 쓴 뒤 바꿔 끼운다 (쓰는 도중 꺼져도 원본이 반쯤 쓰인 채 남지 않게) */
    private fun writeFile(dest: File, clean: File, strokes: List<List<Stroke>>, marks: Set<Int>, wrongSave: WrongSave) {
        val side = File(dest.parentFile, dest.name + ".tmp")
        try {
            PdfInk.save(clean, side, strokes, marks, wrongSave)
            Files.move(side.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            side.delete()
            throw e
        }
    }
}
