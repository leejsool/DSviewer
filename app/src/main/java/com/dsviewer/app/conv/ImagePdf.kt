package com.dsviewer.app.conv

import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.ExifInterface
import com.dsviewer.app.PdfPages
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.PDPage
import com.tom_roush.pdfbox.pdmodel.PDPageContentStream
import com.tom_roush.pdfbox.pdmodel.common.PDRectangle
import com.tom_roush.pdfbox.pdmodel.graphics.image.JPEGFactory
import com.tom_roush.pdfbox.pdmodel.graphics.image.LosslessFactory
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 그림 파일(JPEG·PNG·WebP·GIF·BMP·HEIC) → 그림 한 장이 한 쪽을 꽉 채운 PDF.
 * 쪽 폭은 세로 그림이면 A4 짧은 변, 가로 그림이면 A4 긴 변 (펜 굵기가 다른 문서와 비슷하게 보이도록).
 * 사진의 회전 정보(EXIF)를 따르고, 아주 큰 그림은 긴 변 [MAX_PX]로 줄인다.
 */
object ImagePdf {
    private const val MAX_PX = 3200

    /** [photo]: 사진 형식(JPEG·HEIC·WebP)이면 JPEG로, 아니면(PNG·GIF·BMP) 글자가 번지지 않게 무손실로 넣는다 */
    fun make(src: File, out: File, isJpeg: Boolean, photo: Boolean) {
        // 돌릴 필요 없고 너무 크지 않은 JPEG는 그대로 넣는다 (다시 압축하지 않아 선명하고 작다)
        if (isJpeg && jpegAsIs(src)) {
            PDDocument().use { doc ->
                val img = JPEGFactory.createFromByteArray(doc, src.readBytes())
                addPage(doc, img.width, img.height) { cs, w, h -> cs.drawImage(img, 0f, 0f, w, h) }
                doc.save(out)
            }
            return
        }
        val bmp = decode(src)
        try {
            PDDocument().use { doc ->
                val img = if (photo && !bmp.hasAlpha()) JPEGFactory.createFromImage(doc, bmp, 0.92f)
                else LosslessFactory.createFromImage(doc, bmp)
                addPage(doc, bmp.width, bmp.height) { cs, w, h -> cs.drawImage(img, 0f, 0f, w, h) }
                doc.save(out)
            }
        } finally {
            bmp.recycle()
        }
    }

    private fun jpegAsIs(src: File): Boolean {
        val exif = runCatching { ExifInterface(src.path) }.getOrNull() ?: return false
        val o = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        if (o != ExifInterface.ORIENTATION_NORMAL && o != ExifInterface.ORIENTATION_UNDEFINED) return false
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeFile(src.path, opts)
        // CMYK JPEG 등은 BitmapFactory가 크기를 못 읽는다 → 다시 그려 넣는다
        return opts.outWidth > 0 && max(opts.outWidth, opts.outHeight) <= MAX_PX && opts.outMimeType == "image/jpeg"
    }

    /** 그림 읽기: 회전 정보를 따르고 긴 변을 [MAX_PX] 이하로, 투명한 곳은 그대로 */
    fun decode(src: File): Bitmap {
        val source = ImageDecoder.createSource(src)
        return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val k = MAX_PX.toFloat() / max(w, h)
            if (k < 1f) decoder.setTargetSize((w * k).roundToInt().coerceAtLeast(1), (h * k).roundToInt().coerceAtLeast(1))
        }
    }

    private fun addPage(doc: PDDocument, px: Int, py: Int, draw: (PDPageContentStream, Float, Float) -> Unit) {
        val pw = if (px >= py) PdfPages.A4_LONG else PdfPages.A4_SHORT
        val ph = pw * py / px.coerceAtLeast(1)
        val page = PDPage(PDRectangle(pw, ph))
        doc.addPage(page)
        PDPageContentStream(doc, page).use { cs -> draw(cs, pw, ph) }
    }
}
