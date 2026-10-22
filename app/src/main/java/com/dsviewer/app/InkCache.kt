package com.dsviewer.app

import android.content.Context
import android.graphics.BitmapFactory
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * 필기가 많이 든 큰 PDF를 빨리 열기 위한 보관함.
 * PDF에 주석으로 저장된 필기는 PDFBox로 읽어야 하는데, 필기 수천 획·개체 수만 개짜리는 읽는 것만 수 초가 걸린다.
 * 그래서 이 앱이 저장하거나 한 번 읽은 문서는 (필기 + 필기를 뺀 화면용 PDF)를 앱 캐시에 따로 적어 두고,
 * 다음에 열 때 문서 파일의 크기·수정 시각이 그대로면 PDF를 다시 읽지 않고 이것을 쓴다.
 * 다른 앱이 파일을 바꾸면 크기나 시각이 달라져 저절로 안 쓰이고 (예전처럼 PDF에서 읽는다), 캐시는 시스템이 지워도 그만이다.
 * 그림이 들어 있어도 되지만, 원본 바이트가 없는 그림(무손실 그림)이 있으면 보관하지 않는다.
 */
internal object InkCache {
    private const val DIR = "inkcache"
    private const val MAGIC = 0x44534943
    private const val VERSION = 1
    private const val MAX_ENTRIES = 8
    private const val MAX_BYTES = 320L shl 20

    /** 보관함에서 꺼낸 것: 쪽마다 필기, 북마크한 쪽, 쪽별 오답 정보, 필기를 뺀 화면용 PDF */
    class Hit(val strokes: List<List<Stroke>>, val marks: Set<Int>, val wrongs: Map<Int, String>, val clean: File)

    /** 문서마다 마지막으로 보관함에 둔 화면용 PDF의 (경로, 크기, 시각): 같으면 다시 복사하지 않는다 */
    private val lastClean = HashMap<String, String>()

    private fun dir(ctx: Context) = File(ctx.cacheDir, DIR).also { it.mkdirs() }

    /** 문서 파일마다 하나: 길이 다른 이름(/sdcard ↔ /storage/emulated/0)으로 열어도 같은 파일이면 같은 열쇠 */
    private fun keyOf(doc: File): String {
        val path = runCatching { doc.canonicalPath }.getOrDefault(doc.absolutePath)
        return MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** [doc] 문서의 보관본이 지금 파일과 맞으면 꺼낸다 (아니면 null) */
    fun load(ctx: Context, doc: File): Hit? {
        val key = keyOf(doc)
        val meta = File(dir(ctx), "$key.ink")
        val pdf = File(dir(ctx), "$key.pdf")
        if (!meta.isFile || !pdf.isFile || !doc.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(meta.inputStream(), 1 shl 16)).use { i ->
                if (i.readInt() != MAGIC || i.readInt() != VERSION) return null
                if (i.readLong() != doc.length() || i.readLong() != doc.lastModified() || i.readLong() != pdf.length()) return null
                val marks = HashSet<Int>()
                repeat(i.readInt()) { marks += i.readInt() }
                val wrongs = HashMap<Int, String>()
                repeat(i.readInt()) { wrongs[i.readInt()] = readText(i) }
                val pages = ArrayList<List<Stroke>>()
                repeat(i.readInt()) {
                    val list = ArrayList<Stroke>()
                    repeat(i.readInt()) {
                        val st = PdfInk.decodeStroke(readText(i)) ?: error("필기를 읽지 못했습니다.")
                        val flags = i.readByte().toInt()
                        if (flags and 1 != 0) {
                            val bytes = ByteArray(i.readInt()).also { i.readFully(it) }
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("그림을 읽지 못했습니다.")
                            st.image = InkImage(bmp, bytes)
                        }
                        if (flags and 2 != 0) st.link = readText(i)
                        list.add(st)
                    }
                    pages.add(list)
                }
                pdf.setLastModified(System.currentTimeMillis())
                meta.setLastModified(System.currentTimeMillis())
                Hit(pages, marks, wrongs, pdf)
            }
        } catch (e: Exception) {
            // 깨졌으면 지우고 예전 방식으로 읽는다
            meta.delete()
            pdf.delete()
            null
        }
    }

    /**
     * [doc](방금 저장했거나 읽은 파일)의 필기 [pages]·[marks]·[wrongs]와 화면용 PDF [clean]을 보관한다.
     * 저장하지 못하면(그림 바이트가 없는 등) 예전 보관본을 지운다. 실패해도 문서 저장·열기에는 영향이 없다
     */
    fun store(ctx: Context, doc: File, clean: File?, pages: List<List<Stroke>>, marks: Set<Int>, wrongs: Map<Int, String>) {
        val key = keyOf(doc)
        val dir = dir(ctx)
        val meta = File(dir, "$key.ink")
        val pdf = File(dir, "$key.pdf")
        try {
            if (clean == null || !clean.isFile || !doc.isFile || !storable(pages)) {
                forget(ctx, doc)
                return
            }
            // 화면용 PDF: 이미 같은 것이 있으면 다시 복사하지 않는다
            val sig = "${clean.absolutePath}|${clean.length()}|${clean.lastModified()}"
            synchronized(lastClean) {
                if (!pdf.isFile || lastClean[key] != sig || pdf.length() != clean.length()) {
                    val tmp = File(dir, "$key.pdf.tmp")
                    clean.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
                    pdf.delete()
                    if (!tmp.renameTo(pdf)) {
                        tmp.delete()
                        throw IOException("복사하지 못했습니다.")
                    }
                    lastClean[key] = sig
                }
            }
            val tmp = File(dir, "$key.ink.tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { o ->
                o.writeInt(MAGIC)
                o.writeInt(VERSION)
                o.writeLong(doc.length())
                o.writeLong(doc.lastModified())
                o.writeLong(pdf.length())
                o.writeInt(marks.size)
                for (m in marks) o.writeInt(m)
                o.writeInt(wrongs.size)
                for ((k, v) in wrongs) { o.writeInt(k); writeText(o, v) }
                o.writeInt(pages.size)
                for (page in pages) {
                    val list = page.filter { it.count > 0 }
                    o.writeInt(list.size)
                    for (st in list) {
                        writeText(o, PdfInk.encodeStroke(st))
                        val img = st.image
                        val link = st.link.takeIf { st.text != null }
                        o.writeByte((if (img != null) 1 else 0) or (if (link != null) 2 else 0))
                        if (img != null) {
                            val bytes = img.bytes!!
                            o.writeInt(bytes.size)
                            o.write(bytes)
                        }
                        if (link != null) writeText(o, link)
                    }
                }
            }
            meta.delete()
            if (!tmp.renameTo(meta)) {
                tmp.delete()
                throw IOException("저장하지 못했습니다.")
            }
            trim(ctx)
        } catch (e: Throwable) {
            forget(ctx, doc)
        }
    }

    /** [doc]의 보관본을 지운다 */
    fun forget(ctx: Context, doc: File) {
        val key = keyOf(doc)
        val dir = dir(ctx)
        File(dir, "$key.ink").delete()
        File(dir, "$key.pdf").delete()
        synchronized(lastClean) { lastClean.remove(key) }
    }

    /** 그림은 원본 바이트가 있어야 보관한다 (없는 무손실 그림은 PNG로 다시 인코딩해야 해서 저장이 느려진다) */
    private fun storable(pages: List<List<Stroke>>) = pages.all { p -> p.all { it.image == null || it.image?.bytes != null } }

    /** 오래 안 쓴 것부터 지워 [MAX_ENTRIES]개, [MAX_BYTES] 이내로 */
    private fun trim(ctx: Context) {
        val files = dir(ctx).listFiles() ?: return
        val groups = files.filter { it.name.endsWith(".ink") }.sortedByDescending { it.lastModified() }
        var total = 0L
        groups.forEachIndexed { n, meta ->
            val pdf = File(meta.parentFile, meta.name.removeSuffix(".ink") + ".pdf")
            val size = meta.length() + pdf.length()
            total += size
            if (n >= MAX_ENTRIES || total > MAX_BYTES) {
                meta.delete()
                pdf.delete()
                synchronized(lastClean) { lastClean.remove(meta.name.removeSuffix(".ink")) }
            }
        }
        // 짝이 없는 파일 정리
        files.forEach { f ->
            if (f.name.endsWith(".tmp") || (f.name.endsWith(".pdf") && !File(f.parentFile, f.name.removeSuffix(".pdf") + ".ink").exists())) f.delete()
        }
    }

    private fun writeText(o: DataOutputStream, s: String) {
        val b = s.toByteArray(Charsets.UTF_8)
        o.writeInt(b.size)
        o.write(b)
    }

    private fun readText(i: DataInputStream): String {
        val n = i.readInt()
        if (n < 0 || n > (64 shl 20)) throw IOException("깨진 보관본")
        return String(ByteArray(n).also { i.readFully(it) }, Charsets.UTF_8)
    }
}
