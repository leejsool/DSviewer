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
 *
 * 화면용 PDF는 한 번 쓰면 고치지도 덮어쓰지도 않는 파일이라(이름에 만든 때가 들어 있다) 문서를 열 때 복사하지 않고
 * 그 파일을 그대로 화면용·저장 바탕 PDF로 쓴다 (수십 MB를 복사하는 시간이 사라진다). 열어 쓰는 동안 지워지지 않게
 * 꺼낸 파일은 [pin]으로 붙잡아 두고, 보관함 정리는 붙잡히지 않은 것만 지운다
 */
internal object InkCache {
    private const val DIR = "inkcache"
    private const val MAGIC = 0x44534943
    private const val VERSION = 2
    private const val MAX_ENTRIES = 8
    private const val MAX_BYTES = 320L shl 20

    /** 짝 없는 파일을 지우기 전에 기다리는 시간: 방금 만든 화면용 PDF는 아직 정보 파일이 안 생겼을 수 있다 */
    private const val ORPHAN_GRACE_MS = 60_000L

    /** 보관함에서 꺼낸 것: 쪽마다 필기, 북마크한 쪽, 쪽별 오답 정보, 필기를 뺀 화면용 PDF (고치지 말고 읽기만 한다) */
    class Hit(val strokes: List<List<Stroke>>, val marks: Set<Int>, val wrongs: Map<Int, String>, val clean: File)

    /** 문서마다 마지막으로 보관함에 둔 화면용 PDF: 같은 파일이면 다시 복사하지 않고 그 이름을 쓴다 */
    private class Copied(val sig: String, val name: String)

    private val lastClean = HashMap<String, Copied>()

    /** 열려 있는 문서가 쓰고 있어 지우면 안 되는 화면용 PDF의 이름 (앱이 켜져 있는 동안) */
    private val pinned = HashSet<String>()

    fun pin(name: String) {
        synchronized(pinned) { pinned.add(name) }
    }

    private fun isPinned(name: String) = synchronized(pinned) { name in pinned }

    private fun dir(ctx: Context) = File(ctx.cacheDir, DIR)

    /** 문서 파일마다 하나: 길이 다른 이름(/sdcard ↔ /storage/emulated/0)으로 열어도 같은 파일이면 같은 열쇠 */
    private fun keyOf(doc: File): String {
        val path = runCatching { doc.canonicalPath }.getOrDefault(doc.absolutePath)
        return MessageDigest.getInstance("SHA-1").digest(path.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    /** 정보 파일 머리: 문서의 크기·수정 시각과 화면용 PDF의 이름·크기 */
    private class Header(val docLen: Long, val docModified: Long, val pdfName: String, val pdfLen: Long)

    private fun readHeader(i: DataInputStream): Header? {
        if (i.readInt() != MAGIC || i.readInt() != VERSION) return null
        val docLen = i.readLong()
        val docModified = i.readLong()
        val name = readText(i)
        if (name.isEmpty() || name.contains('/') || name.contains('\\')) return null
        return Header(docLen, docModified, name, i.readLong())
    }

    private fun headerOf(meta: File): Header? = try {
        DataInputStream(BufferedInputStream(meta.inputStream(), 1 shl 12)).use { readHeader(it) }
    } catch (e: Exception) {
        null
    }

    /** [doc] 문서의 보관본이 지금 파일과 맞으면 꺼낸다 (아니면 null) */
    fun load(ctx: Context, doc: File): Hit? = load(dir(ctx), doc)

    internal fun load(dir: File, doc: File): Hit? {
        val meta = File(dir, "${keyOf(doc)}.ink")
        if (!meta.isFile || !doc.isFile) return null
        return try {
            DataInputStream(BufferedInputStream(meta.inputStream(), 1 shl 16)).use { i ->
                val h = readHeader(i) ?: return null
                if (h.docLen != doc.length() || h.docModified != doc.lastModified()) return null
                val pdf = File(dir, h.pdfName)
                if (!pdf.isFile || pdf.length() != h.pdfLen) return null
                // 이 PDF를 쓰는 동안 보관함 정리가 지우지 않게 붙잡는다 (아래 읽는 사이에 지워질 수도 있어 먼저)
                pin(h.pdfName)
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
                val now = System.currentTimeMillis()
                pdf.setLastModified(now)
                meta.setLastModified(now)
                Hit(pages, marks, wrongs, pdf)
            }
        } catch (e: Exception) {
            // 깨졌으면 정보 파일을 지우고 예전 방식으로 읽는다 (화면용 PDF는 정리할 때 짝이 없으면 지워진다)
            meta.delete()
            null
        }
    }

    /**
     * [doc](방금 저장했거나 읽은 파일)의 필기 [pages]·[marks]·[wrongs]와 화면용 PDF [clean]을 보관한다.
     * [clean]이 이미 보관함 안의 파일이면 그대로 가리키고, 아니면 복사해 둔다.
     * 저장하지 못하면(그림 바이트가 없는 등) 예전 보관본을 지운다. 실패해도 문서 저장·열기에는 영향이 없다
     */
    fun store(ctx: Context, doc: File, clean: File?, pages: List<List<Stroke>>, marks: Set<Int>, wrongs: Map<Int, String>) =
        store(dir(ctx), doc, clean, pages, marks, wrongs)

    internal fun store(dir: File, doc: File, clean: File?, pages: List<List<Stroke>>, marks: Set<Int>, wrongs: Map<Int, String>) {
        val key = keyOf(doc)
        dir.mkdirs()
        val meta = File(dir, "$key.ink")
        try {
            if (clean == null || !clean.isFile || !doc.isFile || !storable(pages)) {
                forget(dir, doc)
                return
            }
            val pdf = pdfIn(dir, key, clean)
            val tmp = File(dir, "$key.ink.tmp")
            DataOutputStream(BufferedOutputStream(tmp.outputStream(), 1 shl 16)).use { o ->
                o.writeInt(MAGIC)
                o.writeInt(VERSION)
                o.writeLong(doc.length())
                o.writeLong(doc.lastModified())
                writeText(o, pdf.name)
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
            trim(dir)
        } catch (e: Throwable) {
            forget(dir, doc)
        }
    }

    /**
     * 보관함 안의 화면용 PDF로 쓸 파일을 정한다: [clean]이 이미 보관함 안의 파일이면 그것 (고치지 않는 파일이라 복사할 필요가 없다),
     * 같은 파일을 방금 복사해 두었으면 그것, 아니면 새 이름으로 복사한다
     */
    private fun pdfIn(dir: File, key: String, clean: File): File {
        if (isInside(dir, clean)) {
            pin(clean.name)
            return clean
        }
        val sig = "${clean.absolutePath}|${clean.length()}|${clean.lastModified()}"
        synchronized(lastClean) {
            val prev = lastClean[key]
            if (prev != null && prev.sig == sig) {
                val f = File(dir, prev.name)
                if (f.isFile && f.length() == clean.length()) return f
            }
            val name = "$key-${System.currentTimeMillis()}.pdf"
            val tmp = File(dir, "$name.tmp")
            clean.inputStream().use { i -> tmp.outputStream().use { o -> i.copyTo(o, 1 shl 16) } }
            val f = File(dir, name)
            if (!tmp.renameTo(f)) {
                tmp.delete()
                throw IOException("복사하지 못했습니다.")
            }
            lastClean[key] = Copied(sig, name)
            return f
        }
    }

    private fun isInside(dir: File, f: File): Boolean = runCatching { f.canonicalFile.parentFile == dir.canonicalFile }.getOrDefault(false)

    /** [doc]의 보관본을 지운다 (화면용 PDF는 정리할 때 짝이 없으면 지워진다) */
    fun forget(ctx: Context, doc: File) = forget(dir(ctx), doc)

    internal fun forget(dir: File, doc: File) {
        val key = keyOf(doc)
        File(dir, "$key.ink").delete()
        synchronized(lastClean) { lastClean.remove(key) }
        trim(dir)
    }

    /** 그림은 원본 바이트가 있어야 보관한다 (없는 무손실 그림은 PNG로 다시 인코딩해야 해서 저장이 느려진다) */
    private fun storable(pages: List<List<Stroke>>) = pages.all { p -> p.all { it.image == null || it.image?.bytes != null } }

    /**
     * 오래 안 쓴 것부터 지워 [MAX_ENTRIES]개, [MAX_BYTES] 이내로 두고, 어느 정보 파일도 가리키지 않는
     * 화면용 PDF와 임시 파일을 지운다 (붙잡힌 것과 방금 만든 것은 둔다)
     */
    internal fun trim(dir: File, now: Long = System.currentTimeMillis()) {
        val files = dir.listFiles() ?: return
        val metas = files.filter { it.name.endsWith(".ink") }.sortedByDescending { it.lastModified() }
        val kept = HashSet<String>()
        var total = 0L
        var count = 0
        for (meta in metas) {
            val h = headerOf(meta)
            val pdf = h?.let { File(dir, it.pdfName) }
            if (h == null || pdf == null || !pdf.isFile) {  // 옛 형식이거나 화면용 PDF가 없어진 것
                meta.delete()
                continue
            }
            // 같은 화면용 PDF를 가리키는 정보 파일이 여럿이어도 크기는 한 번만 센다
            val size = meta.length() + if (h.pdfName in kept) 0L else pdf.length()
            if (count >= MAX_ENTRIES || total + size > MAX_BYTES) {
                meta.delete()
                synchronized(lastClean) { lastClean.values.removeAll { it.name == h.pdfName } }
                continue
            }
            total += size
            count++
            kept.add(h.pdfName)
        }
        for (f in files) {
            if (f.name.endsWith(".ink") || f.name in kept) continue
            val old = now - f.lastModified() > ORPHAN_GRACE_MS
            val orphanPdf = f.name.endsWith(".pdf") && !isPinned(f.name)
            if (old && (orphanPdf || f.name.endsWith(".tmp"))) f.delete()
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
