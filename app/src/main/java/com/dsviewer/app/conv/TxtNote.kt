package com.dsviewer.app.conv

import android.graphics.Color
import com.dsviewer.app.InkText
import com.dsviewer.app.Paper
import com.dsviewer.app.PdfInk
import com.dsviewer.app.PdfPages
import com.dsviewer.app.Stroke
import com.dsviewer.app.Tool
import java.io.File
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * 글 파일(.txt) → 흰 A4 쪽들 + 쪽마다 글 상자 하나.
 * 글 상자는 T 도구로 누르면 바로 고칠 수 있는 이 앱의 글이다 (저장은 PDF로).
 */
object TxtNote {
    /** 글자 크기 (pt) — 툴바의 가장 작은 글자 크기와 같다 */
    private const val SIZE = 14f
    /** 쪽 여백 (pt, 20mm) */
    private const val MARGIN = 56.7f

    fun make(src: File, out: File, maxPages: Int = 0) {
        val text = decode(src.readBytes())
            .replace("\r\n", "\n").replace('\r', '\n')
            .replace("\t", "    ")
            .replace("\u000C", "\n")
            .trimEnd()
        val w = PdfPages.A4_SHORT
        val h = PdfPages.A4_LONG
        val wrap = w - MARGIN * 2 - InkText.PAD * 2
        var ranges = InkText.paginate(text, SIZE, wrap, h - MARGIN * 2)
        if (maxPages > 0) ranges = ranges.take(maxPages)
        val pages = ranges.map { r ->
            val chunk = text.substring(r.first, r.last + 1).removeSuffix("\n")
            if (chunk.isBlank()) emptyList()
            else listOf(textStroke(InkText(chunk, SIZE, wrap), MARGIN, MARGIN))
        }
        val blank = File(out.parentFile, "blank_${System.nanoTime()}.pdf")
        try {
            PdfPages.create(blank, Paper.PLAIN, w, h, pages.size.coerceAtLeast(1))
            PdfInk.save(blank, out, pages)
        } finally {
            blank.delete()
        }
    }

    private fun textStroke(t: InkText, x: Float, y: Float) = Stroke(Tool.PEN, Color.BLACK, 0f).apply {
        text = t
        add(x, y, 1f)
        add(x + t.boxW, y, 1f)
        add(x + t.boxW, y + t.boxH, 1f)
        add(x, y + t.boxH, 1f)
    }

    /** 글자 인코딩 알아내기: BOM → UTF-8 → 한국어 Windows(CP949) */
    fun decode(bytes: ByteArray): String {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte())
            return String(bytes, 3, bytes.size - 3, Charsets.UTF_8)
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte())
            return String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        try {
            return Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString()
        } catch (e: CharacterCodingException) {
            // UTF-8이 아니면 한국어 Windows 인코딩
        }
        val cs = listOf("x-windows-949", "MS949", "EUC-KR").firstOrNull { runCatching { Charset.isSupported(it) }.getOrDefault(false) }
        return String(bytes, if (cs != null) Charset.forName(cs) else Charsets.ISO_8859_1)
    }
}
