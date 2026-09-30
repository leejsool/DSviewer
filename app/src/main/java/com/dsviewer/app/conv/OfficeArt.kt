package com.dsviewer.app.conv

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** 오피스 97~2003 그림 자료(OfficeArt BLIP) 공통: DOC·PPT */
object OfficeArt {

    /** 기록 머리 (8바이트) */
    class Rec(val ver: Int, val inst: Int, val type: Int, val len: Int, val body: Int) {
        val end get() = body + len
        val isContainer get() = ver == 0xF
    }

    fun rec(bb: ByteBuffer, at: Int): Rec? {
        if (at < 0 || at + 8 > bb.limit()) return null
        val vi = bb.getShort(at).toInt() and 0xFFFF
        val type = bb.getShort(at + 2).toInt() and 0xFFFF
        val len = bb.getInt(at + 4)
        if (len < 0 || at + 8 + len > bb.limit() + 8) return null
        return Rec(vi and 0xF, vi shr 4, type, minOf(len, bb.limit() - at - 8), at + 8)
    }

    /** 안의 기록들 */
    inline fun children(bb: ByteBuffer, parent: Rec, block: (Rec) -> Unit) {
        var p = parent.body
        while (p + 8 <= parent.end) {
            val r = rec(bb, p) ?: break
            block(r)
            p = r.end
        }
    }

    /**
     * BLIP 기록 하나 → 그림 파일 바이트 (JPEG·PNG·BMP·TIFF). 메타파일(EMF·WMF·PICT)은 그릴 수 없어 null.
     * DIB는 BMP 파일 머리를 붙여 준다
     */
    fun blip(data: ByteArray, r: Rec): ByteArray? {
        val type = r.type
        if (type !in 0xF01D..0xF01F && type != 0xF029 && type != 0xF02A) return null
        val two = r.inst == 0x46B || r.inst == 0x6E1 || r.inst == 0x6E3 || r.inst == 0x7A9 || r.inst == 0x6E5
        val from = r.body + (if (two) 32 else 16) + 1
        val to = minOf(r.end, data.size)
        if (from >= to) return null
        val bytes = data.copyOfRange(from, to)
        return if (type == 0xF01F) dibToBmp(bytes) else bytes
    }

    /** 그림 기록을 [start, end) 에서 찾는다 (FBSE·컨테이너 안까지) */
    fun findBlip(data: ByteArray, start: Int, end: Int): ByteArray? {
        val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)
        var p = start
        while (p + 8 <= end) {
            val r = rec(bb, p) ?: return null
            if (r.type in 0xF018..0xF117 && r.type != 0xF007) blip(data, r)?.let { return it }
            p = when {
                r.isContainer -> r.body
                // FBSE: 36바이트 머리 + 이름 뒤에 그림 자료
                r.type == 0xF007 && r.body + 36 <= end -> r.body + 36 + (data[r.body + 33].toInt() and 0xFF)
                else -> r.end
            }
        }
        return null
    }

    private fun dibToBmp(dib: ByteArray): ByteArray? {
        if (dib.size < 40) return null
        val bb = ByteBuffer.wrap(dib).order(ByteOrder.LITTLE_ENDIAN)
        val hdr = bb.getInt(0)
        val bpp = bb.getShort(14).toInt()
        val used = if (hdr >= 36) bb.getInt(32) else 0
        val palette = if (bpp <= 8) (if (used > 0) used else 1 shl bpp) * 4 else 0
        val out = ByteBuffer.allocate(14 + dib.size).order(ByteOrder.LITTLE_ENDIAN)
        out.put('B'.code.toByte()).put('M'.code.toByte())
        out.putInt(14 + dib.size).putInt(0).putInt(14 + hdr + palette)
        out.put(dib)
        return out.array()
    }
}
