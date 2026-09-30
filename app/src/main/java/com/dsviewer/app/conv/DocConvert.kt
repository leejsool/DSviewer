package com.dsviewer.app.conv

import android.content.Context
import com.dsviewer.app.DocType
import com.dsviewer.app.FileUtil
import com.dsviewer.app.hwp.HRenderer
import com.dsviewer.app.hwp.HwpReader
import com.dsviewer.app.hwp.HwpxReader
import java.io.File

/**
 * PDF가 아닌 문서를 기기 안에서 PDF로 바꾼다 (인터넷·다른 앱 없이).
 * 한글·워드·파워포인트는 한글 문서 모델([com.dsviewer.app.hwp.HDoc])로 옮겨 HRenderer로 그리고,
 * 글 파일은 고칠 수 있는 글 상자로, 그림은 쪽을 꽉 채운 그림으로 넣는다.
 */
object DocConvert {

    /** [file]을 화면에 띄울 PDF로. PDF면 그대로 돌려준다. [maxPages] > 0 이면 앞쪽만 (썸네일) */
    fun toPdf(ctx: Context, file: File, type: DocType, name: String, maxPages: Int = 0, prefix: String = "conv"): File {
        if (type == DocType.PDF) return file
        val out = FileUtil.tempFile(ctx, prefix, "pdf")
        try {
            when (type) {
                DocType.HWP -> HRenderer(HwpReader.read(file)).render(out, maxPages)
                DocType.HWPX -> HRenderer(HwpxReader.read(file)).render(out, maxPages)
                DocType.PPTX -> HRenderer(PptxReader.read(file, maxPages)).render(out, maxPages)
                DocType.DOCX -> HRenderer(DocxReader.read(file, maxPages)).render(out, maxPages)
                DocType.DOC -> HRenderer(DocReader.read(file, maxPages)).render(out, maxPages)
                DocType.PPT -> HRenderer(PptReader.read(file, maxPages)).render(out, maxPages)
                DocType.TXT -> TxtNote.make(file, out, maxPages)
                DocType.IMAGE -> {
                    val ext = name.substringAfterLast('.', "").lowercase()
                    val jpeg = FileUtil.isJpeg(file)
                    ImagePdf.make(file, out, jpeg, photo = jpeg || ext in PHOTO_EXTS)
                }
                else -> error("지원하지 않는 형식")
            }
        } catch (e: Throwable) {
            out.delete()
            throw e
        }
        return out
    }

    private val PHOTO_EXTS = setOf("jpg", "jpeg", "heic", "heif", "webp", "avif")
}
