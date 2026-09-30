package com.dsviewer.app.conv

import com.dsviewer.app.hwp.Align
import com.dsviewer.app.hwp.BorderFill
import com.dsviewer.app.hwp.BorderLine
import com.dsviewer.app.hwp.Cfb
import com.dsviewer.app.hwp.HCell
import com.dsviewer.app.hwp.HDoc
import com.dsviewer.app.hwp.HPara
import com.dsviewer.app.hwp.HPicture
import com.dsviewer.app.hwp.HSection
import com.dsviewer.app.hwp.HTable
import com.dsviewer.app.hwp.PageDef
import com.dsviewer.app.hwp.ParaShape
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * DOC(워드 97~2003 바이너리) → [HDoc].
 * 본문 글(조각 표), 문단 모양(맞춤·들여쓰기·간격·줄 간격), 글자 모양(굵게·기울임·밑줄·크기·색·글꼴), 스타일,
 * 표(칸 폭·합치기·테두리·배경), 글자처럼 넣은 그림을 옮긴다.
 * 번호 매기기, 머리말·꼬리말, 글상자·도형, 각주는 옮기지 않는다.
 */
class DocReader private constructor(file: File) {

    private val cfb = Cfb(file)
    private val wd: ByteArray = cfb.read("WordDocument") ?: error("워드 문서가 아닙니다.")
    private val w = ByteBuffer.wrap(wd).order(ByteOrder.LITTLE_ENDIAN)
    private val table: ByteArray
    private val t: ByteBuffer
    private val data: ByteArray? = cfb.read("Data")
    private val b = HBuilder()

    /** CP(글자 위치) → 글자, FC(파일 위치) */
    private val chars: CharArray
    private val fcs: IntArray

    private val papx = ArrayList<Run>()
    private val chpx = ArrayList<Run>()
    private val styles = HashMap<Int, Style>()
    private val fonts = ArrayList<String>()
    private val pictures = HashMap<String, ByteArray>()
    /** 본문 끝, 머리말 문서 시작 (CP) */
    private var mainEnd = 0
    private var hdrStart = 0
    /** 기본 글꼴 번호 (스타일 시트의 표준 글꼴: 영문, 한글) */
    private var defLatin = 0
    private var defEa = 0
    /** 목록: lsid → 수준별 (번호 모양, 번호 글, 시작 번호, 문단 sprm) */
    private class Lvl(val nfc: Int, val text: String, val start: Int, val papx: ByteArray, val follow: Int)
    private val lists = HashMap<Int, List<Lvl>>()
    private val lfoLsid = ArrayList<Int>()
    private val listIds = HashMap<Int, Int>()

    /** FKP 한 칸: [fcStart, fcEnd) 에 붙은 sprm 묶음 (문단은 istd도) */
    private class Run(val fcStart: Int, val fcEnd: Int, val grpprl: ByteArray, val istd: Int = 0)

    private class Style(val base: Int, val papx: ByteArray?, val chpx: ByteArray?, val isPara: Boolean)

    init {
        if ((w.getShort(0).toInt() and 0xFFFF) != 0xA5EC) error("워드 97~2003 문서가 아닙니다.")
        val flags = w.getShort(0x0A).toInt() and 0xFFFF
        if (flags and 0x0100 != 0) error("암호가 걸린 워드 문서는 열 수 없습니다.")
        val tableName = if (flags and 0x0200 != 0) "1Table" else "0Table"
        table = cfb.read(tableName) ?: error("워드 문서의 표 스트림이 없습니다.")
        t = ByteBuffer.wrap(table).order(ByteOrder.LITTLE_ENDIAN)

        // 조각 표(Clx) → 본문 글
        val ccpText = w.getInt(0x4C)
        val fcClx = w.getInt(0x1A2)
        val lcbClx = w.getInt(0x1A6)
        val sb = StringBuilder(ccpText)
        val fcList = ArrayList<Int>(ccpText)
        var pos = fcClx
        val end = fcClx + lcbClx
        while (pos < end && pos < table.size) {
            val type = table[pos].toInt()
            if (type == 1) {
                val cb = t.getShort(pos + 1).toInt() and 0xFFFF
                pos += 3 + cb
            } else if (type == 2) {
                val lcb = t.getInt(pos + 1)
                val plc = pos + 5
                val n = (lcb - 4) / 12
                for (i in 0 until n) {
                    val cp0 = t.getInt(plc + i * 4)
                    val cp1 = t.getInt(plc + (i + 1) * 4)
                    val pcd = plc + (n + 1) * 4 + i * 8
                    val fcRaw = t.getInt(pcd + 2)
                    val compressed = fcRaw and 0x40000000 != 0
                    val fc = fcRaw and 0x3FFFFFFF
                    for (cp in cp0 until cp1) {
                        val k = cp - cp0
                        if (compressed) {
                            val off = fc / 2 + k
                            if (off >= wd.size) break
                            sb.append(cp1252(wd[off].toInt() and 0xFF))
                            fcList.add(off)
                        } else {
                            val off = fc + k * 2
                            if (off + 1 >= wd.size) break
                            sb.append(w.getChar(off))
                            fcList.add(off)
                        }
                    }
                }
                break
            } else break
        }
        chars = sb.toString().toCharArray()
        fcs = fcList.toIntArray()
        mainEnd = minOf(ccpText, chars.size)
        hdrStart = ccpText + w.getInt(0x50)

        readFkps(0xFA, isPapx = false, chpx)
        readFkps(0x102, isPapx = true, papx)
        readStyles()
        readFonts()
        runCatching { readLists() }
    }

    companion object {
        fun read(file: File, maxPages: Int = 0): HDoc = DocReader(file).build(maxPages)

        /** cp1252의 0x80~0x9F 자리 글자 */
        private val CP1252 = "€\u0081‚ƒ„…†‡ˆ‰Š‹Œ\u008DŽ\u008F\u0090‘’“”•–—˜™š›œ\u009DžŸ"

        private fun cp1252(b: Int): Char = if (b in 0x80..0x9F) CP1252[b - 0x80] else b.toChar()

        /** 워드 색 번호(ico) → 색 */
        private val ICO = intArrayOf(
            0xFF000000.toInt(), 0xFF000000.toInt(), 0xFF0000FF.toInt(), 0xFF00FFFF.toInt(), 0xFF00FF00.toInt(), 0xFFFF00FF.toInt(),
            0xFFFF0000.toInt(), 0xFFFFFF00.toInt(), 0xFFFFFFFF.toInt(), 0xFF000080.toInt(), 0xFF008080.toInt(), 0xFF008000.toInt(),
            0xFF800080.toInt(), 0xFF800000.toInt(), 0xFF808000.toInt(), 0xFF808080.toInt(), 0xFFC0C0C0.toInt(),
        )
    }

    // ================= 읽기 =================

    private fun readFkps(fibOff: Int, isPapx: Boolean, out: MutableList<Run>) {
        val fc = w.getInt(fibOff)
        val lcb = w.getInt(fibOff + 4)
        if (lcb <= 4 || fc + lcb > table.size) return
        val n = (lcb - 4) / 8
        for (i in 0 until n) {
            val pn = t.getInt(fc + (n + 1) * 4 + i * 4) and 0x3FFFFF
            val page = pn * 512
            if (page + 512 > wd.size) continue
            val crun = wd[page + 511].toInt() and 0xFF
            for (k in 0 until crun) {
                val f0 = w.getInt(page + k * 4)
                val f1 = w.getInt(page + (k + 1) * 4)
                if (isPapx) {
                    val bx = page + (crun + 1) * 4 + k * 13
                    val off = (wd[bx].toInt() and 0xFF) * 2
                    if (off == 0) { out.add(Run(f0, f1, ByteArray(0))); continue }
                    var p = page + off
                    var cb = wd[p].toInt() and 0xFF
                    p++
                    val len = if (cb == 0) { cb = wd[p].toInt() and 0xFF; p++; cb * 2 } else cb * 2 - 1
                    if (len < 2 || p + len > wd.size) { out.add(Run(f0, f1, ByteArray(0))); continue }
                    val istd = w.getShort(p).toInt() and 0xFFFF
                    out.add(Run(f0, f1, wd.copyOfRange(p + 2, p + len), istd))
                } else {
                    val off = (wd[page + (crun + 1) * 4 + k].toInt() and 0xFF) * 2
                    if (off == 0) { out.add(Run(f0, f1, ByteArray(0))); continue }
                    val cb = wd[page + off].toInt() and 0xFF
                    val p = page + off + 1
                    out.add(Run(f0, f1, if (p + cb <= wd.size) wd.copyOfRange(p, p + cb) else ByteArray(0)))
                }
            }
        }
        out.sortBy { it.fcStart }
    }

    private fun find(list: List<Run>, fc: Int): Run? {
        var lo = 0
        var hi = list.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = list[mid]
            when {
                fc < r.fcStart -> hi = mid - 1
                fc >= r.fcEnd -> lo = mid + 1
                else -> return r
            }
        }
        return null
    }

    private fun readStyles() {
        val fc = w.getInt(0xA2)
        val lcb = w.getInt(0xA6)
        if (lcb <= 2 || fc + lcb > table.size) return
        val cbStshi = t.getShort(fc).toInt() and 0xFFFF
        val stshi = fc + 2
        val cstd = t.getShort(stshi).toInt() and 0xFFFF
        val cbStdBase = t.getShort(stshi + 2).toInt() and 0xFFFF
        if (cbStshi >= 18) {
            defLatin = t.getShort(stshi + 12).toInt() and 0xFFFF
            defEa = t.getShort(stshi + 14).toInt() and 0xFFFF
        }
        var p = stshi + cbStshi
        for (istd in 0 until cstd) {
            if (p + 2 > fc + lcb) break
            val cbStd = t.getShort(p).toInt() and 0xFFFF
            val std = p + 2
            p = std + cbStd
            if (cbStd == 0) continue
            val word2 = t.getShort(std + 2).toInt() and 0xFFFF
            val sgc = word2 and 0x0F
            val base = (word2 shr 4) and 0x0FFF
            val word3 = t.getShort(std + 4).toInt() and 0xFFFF
            val cupx = word3 and 0x0F
            // 이름 (글자 수 + UTF-16 + 끝 0)
            var q = std + cbStdBase
            if (q + 2 > p) continue
            val cch = t.getShort(q).toInt() and 0xFFFF
            q += 2 + cch * 2 + 2
            var paraPr: ByteArray? = null
            var charPr: ByteArray? = null
            for (u in 0 until cupx) {
                if (q % 2 == 1) q++
                if (q + 2 > p) break
                val cb = t.getShort(q).toInt() and 0xFFFF
                val body = q + 2
                if (body + cb > p) break
                if (sgc == 1 && u == 0) paraPr = if (cb >= 2) table.copyOfRange(body + 2, body + cb) else ByteArray(0)
                else charPr = table.copyOfRange(body, body + cb)
                q = body + cb
            }
            styles[istd] = Style(if (base == 0x0FFF) -1 else base, paraPr, charPr, sgc == 1)
        }
    }

    private fun readFonts() {
        val fc = w.getInt(0x112)
        val lcb = w.getInt(0x116)
        if (lcb <= 4 || fc + lcb > table.size) return
        var p = fc
        var count = t.getShort(p).toInt() and 0xFFFF
        p += 2
        if (count == 0xFFFF) { count = t.getShort(p).toInt() and 0xFFFF; p += 2 }
        p += 2 // cbExtra
        for (i in 0 until count) {
            if (p >= fc + lcb) break
            val cch = table[p].toInt() and 0xFF
            val ffn = p + 1
            val sb = StringBuilder()
            var q = ffn + 39
            while (q + 1 < ffn + cch) {
                val c = t.getChar(q)
                if (c == '\u0000') break
                sb.append(c)
                q += 2
            }
            fonts.add(sb.toString())
            p = ffn + cch
        }
    }

    /** 목록 정의 (PlfLst + LVL들)와 목록 사용(PlfLfo) */
    private fun readLists() {
        val fc = w.getInt(0x2E2)
        val lcb = w.getInt(0x2E6)
        if (lcb >= 2 && fc + lcb <= table.size) {
            val cLst = t.getShort(fc).toInt()
            var lvlPos = fc + 2 + cLst * 28
            for (i in 0 until cLst) {
                val lstf = fc + 2 + i * 28
                val lsid = t.getInt(lstf)
                val simple = table[lstf + 26].toInt() and 1 != 0
                val levels = ArrayList<Lvl>()
                repeat(if (simple) 1 else 9) {
                    if (lvlPos + 28 > table.size) return
                    val start = t.getInt(lvlPos)
                    val nfc = table[lvlPos + 4].toInt() and 0xFF
                    val follow = table[lvlPos + 15].toInt() and 0xFF
                    val cbChpx = table[lvlPos + 24].toInt() and 0xFF
                    val cbPapx = table[lvlPos + 25].toInt() and 0xFF
                    var q = lvlPos + 28
                    val papx = table.copyOfRange(q, minOf(table.size, q + cbPapx))
                    q += cbPapx + cbChpx
                    val cch = t.getShort(q).toInt() and 0xFFFF
                    val sb = StringBuilder()
                    for (k in 0 until cch) if (q + 2 + k * 2 + 1 < table.size) sb.append(t.getChar(q + 2 + k * 2))
                    q += 2 + cch * 2
                    levels.add(Lvl(nfc, sb.toString(), start, papx, follow))
                    lvlPos = q
                }
                lists[lsid] = levels
            }
        }
        val fo = w.getInt(0x2EA)
        val lo = w.getInt(0x2EE)
        if (lo >= 4 && fo + lo <= table.size) {
            val n = t.getInt(fo)
            for (i in 0 until n) if (fo + 4 + i * 16 + 4 <= table.size) lfoLsid.add(t.getInt(fo + 4 + i * 16))
        }
    }

    /** 목록 번호 매기기 id (목록마다 하나) */
    private fun numberingOf(lsid: Int, levels: List<Lvl>): Int = listIds.getOrPut(lsid) {
        val n = com.dsviewer.app.hwp.Numbering()
        for ((lv, l) in levels.withIndex()) {
            if (lv >= 10) break
            val sb = StringBuilder()
            for (c in l.text) if (c.code in 0..8) sb.append('^').append(c.code + 1) else sb.append(c)
            n.formats[lv] = sb.toString()
            n.numTypes[lv] = when (l.nfc) {
                1 -> "ROMAN_CAPITAL"
                2 -> "ROMAN_SMALL"
                3 -> "LATIN_CAPITAL"
                4 -> "LATIN_SMALL"
                18 -> "CIRCLED_DIGIT"
                24 -> "HANGUL_SYLLABLE"
                25 -> "HANGUL_JAMO"
                else -> "DIGIT"
            }
            n.starts[lv] = l.start
        }
        b.numbering(n)
    }

    // ================= 모양 풀기 =================

    /** sprm 묶음을 하나씩: (sprm, 값 시작 위치, 값 길이) */
    private inline fun sprms(g: ByteArray, block: (Int, Int, Int) -> Unit) {
        val bb = ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN)
        var i = 0
        while (i + 2 <= g.size) {
            val sprm = bb.getShort(i).toInt() and 0xFFFF
            i += 2
            val len = when ((sprm shr 13) and 7) {
                0, 1 -> 1
                2, 4, 5 -> 2
                3 -> 4
                7 -> 3
                else -> {
                    // 길이가 앞에 붙는 값. 표 정의는 2바이트 길이
                    if (sprm == 0xD608 || sprm == 0xD606) {
                        if (i + 2 > g.size) return
                        val cb = (bb.getShort(i).toInt() and 0xFFFF)
                        i += 2
                        cb - 1
                    } else {
                        if (i >= g.size) return
                        val cb = g[i].toInt() and 0xFF
                        i += 1
                        cb
                    }
                }
            }
            if (len < 0 || i + len > g.size) return
            block(sprm, i, len)
            i += len
        }
    }

    private class CharProps {
        var bold = false
        var italic = false
        var underline = false
        var strike = false
        var size = 20
        var color = 0xFF000000.toInt()
        var sup = false
        var sub = false
        var latin = -1
        var ea = -1
        var vanish = false
        var shade: Int? = null
        var picLoc = -1
        var special = false
    }

    private fun toggle(cur: Boolean, v: Int): Boolean = when (v) {
        0 -> false
        1 -> true
        0x80 -> cur
        0x81 -> !cur
        else -> cur
    }

    private fun applyChar(g: ByteArray?, c: CharProps) {
        g ?: return
        val bb = ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN)
        sprms(g) { sprm, at, len ->
            val v1 = if (len >= 1) g[at].toInt() and 0xFF else 0
            val v2 = if (len >= 2) bb.getShort(at).toInt() and 0xFFFF else 0
            when (sprm) {
                0x0835 -> c.bold = toggle(c.bold, v1)
                0x0836 -> c.italic = toggle(c.italic, v1)
                0x0837 -> c.strike = toggle(c.strike, v1)
                0x083C -> c.vanish = toggle(c.vanish, v1)
                0x2A3E -> c.underline = v1 != 0
                0x4A43 -> c.size = v2
                0x2A42 -> c.color = ICO.getOrElse(v1) { 0xFF000000.toInt() }
                0x6870 -> if (len >= 4 && (g[at + 3].toInt() and 0xFF) != 0xFF) {
                    c.color = 0xFF000000.toInt() or ((g[at].toInt() and 0xFF) shl 16) or ((g[at + 1].toInt() and 0xFF) shl 8) or (g[at + 2].toInt() and 0xFF)
                }
                0x2A48 -> { c.sup = v1 == 1; c.sub = v1 == 2 }
                0x4A4F -> c.latin = v2
                0x4A50 -> c.ea = v2
                0x4A51 -> if (c.latin < 0) c.latin = v2
                0x2A0C -> c.shade = if (v1 == 0) null else ICO.getOrNull(v1)
                0x6A03 -> if (len >= 4) c.picLoc = bb.getInt(at)
                0x0855 -> c.special = v1 != 0
            }
        }
    }

    private fun styleChain(istd: Int): List<Style> {
        val out = ArrayList<Style>()
        var cur = istd
        val seen = HashSet<Int>()
        while (cur >= 0 && seen.add(cur)) {
            val st = styles[cur] ?: break
            out.add(st)
            cur = st.base
        }
        return out.reversed()
    }

    private val charCache = HashMap<Long, CharProps>()

    /** 글자 모양 (스타일 → 글자 모양 조각). 같은 조각·스타일이면 한 번만 푼다 */
    private fun charProps(fc: Int, istd: Int): CharProps {
        val run = find(chpx, fc)
        val key = ((run?.fcStart ?: -1).toLong() shl 20) or istd.toLong()
        return charCache.getOrPut(key) {
            val c = CharProps()
            for (st in styleChain(istd)) applyChar(st.chpx, c)
            applyChar(run?.grpprl, c)
            c
        }
    }

    private class ParaProps {
        var align = Align.LEFT
        var left = 0
        var right = 0
        var first = 0
        var before = 0
        var after = 0
        var line = 240
        var lineMult = true
        var inTable = false
        var ttp = false
        var pageBreak = false
        var tdef: ByteArray? = null
        var tdefAt = 0
        var tdefLen = 0
        var rowHeight = 0
        var tableBorders: Array<BorderLine>? = null
        var shades: IntArray? = null
        var ilfo = 0
        var ilvl = 0
        var frameAlign: Align? = null
    }

    private fun applyPara(g: ByteArray?, p: ParaProps) {
        g ?: return
        val bb = ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN)
        sprms(g) { sprm, at, len ->
            val v1 = if (len >= 1) g[at].toInt() and 0xFF else 0
            val s2 = if (len >= 2) bb.getShort(at).toInt() else 0
            when (sprm) {
                0x2403, 0x2461 -> p.align = when (v1) { 1 -> Align.CENTER; 2 -> Align.RIGHT; 3, 4 -> Align.JUSTIFY; else -> Align.LEFT }
                // 틀 가로 위치: -4 가운데, -8 오른쪽 (쪽 번호 틀 등)
                0x8418 -> when (s2) { -4 -> p.frameAlign = Align.CENTER; -8, -16 -> p.frameAlign = Align.RIGHT }
                0x840F, 0x845E -> p.left = s2
                0x840E, 0x845D -> p.right = s2
                0x8411, 0x8460 -> p.first = s2
                0xA413 -> p.before = s2 and 0xFFFF
                0xA414 -> p.after = s2 and 0xFFFF
                0x6412 -> if (len >= 4) { p.line = bb.getShort(at).toInt(); p.lineMult = bb.getShort(at + 2).toInt() != 0 }
                0x2416 -> p.inTable = v1 != 0
                0x2417 -> p.ttp = v1 != 0
                0x6649 -> if (len >= 4 && bb.getInt(at) > 0) p.inTable = true
                0x2407 -> p.pageBreak = v1 != 0
                0xD608 -> { p.tdef = g; p.tdefAt = at; p.tdefLen = len }
                0x9407 -> p.rowHeight = s2
                0x460B -> p.ilfo = s2 and 0xFFFF
                0x260A -> p.ilvl = v1
                0xD605 -> if (len >= 24) p.tableBorders = Array(6) { k -> brc80(g, at + k * 4) }
                0xD613 -> if (len >= 48) p.tableBorders = Array(6) { k -> brc(g, at + k * 8) }
                0xD612 -> if (len % 10 == 0) p.shades = IntArray(len / 10) { k ->
                    val o = at + k * 10 + 4
                    if ((g[o + 3].toInt() and 0xFF) == 0xFF) 0
                    else 0xFF000000.toInt() or ((g[o].toInt() and 0xFF) shl 16) or ((g[o + 1].toInt() and 0xFF) shl 8) or (g[o + 2].toInt() and 0xFF)
                }
            }
        }
    }

    private fun hasIndent(g: ByteArray): Boolean {
        var found = false
        sprms(g) { sprm, _, _ -> if (sprm == 0x840F || sprm == 0x845E || sprm == 0x8411 || sprm == 0x8460) found = true }
        return found
    }

    private fun brc80(g: ByteArray, o: Int): BorderLine {
        val width = g[o].toInt() and 0xFF
        val type = g[o + 1].toInt() and 0xFF
        val ico = g[o + 2].toInt() and 0xFF
        if (type == 0 || type == 0xFF) return BorderLine.NONE
        return BorderLine(if (type == 3) "DOUBLE" else if (type in 6..8) "DOT" else "SOLID", max(0.25f, width / 8f), ICO.getOrElse(ico) { 0xFF000000.toInt() })
    }

    private fun brc(g: ByteArray, o: Int): BorderLine {
        val auto = (g[o + 3].toInt() and 0xFF) == 0xFF
        val color = if (auto) 0xFF000000.toInt() else 0xFF000000.toInt() or ((g[o].toInt() and 0xFF) shl 16) or ((g[o + 1].toInt() and 0xFF) shl 8) or (g[o + 2].toInt() and 0xFF)
        val width = g[o + 4].toInt() and 0xFF
        val type = g[o + 5].toInt() and 0xFF
        if (type == 0 || type == 0xFF) return BorderLine.NONE
        return BorderLine(if (type == 3) "DOUBLE" else if (type in 6..8) "DOT" else "SOLID", max(0.25f, width / 8f), color)
    }

    private fun paraProps(fc: Int): Pair<ParaProps, Int> {
        val run = find(papx, fc)
        val istd = run?.istd ?: 0
        val p = ParaProps()
        for (st in styleChain(istd)) applyPara(st.papx, p)
        applyPara(run?.grpprl, p)
        // 목록 수준의 들여쓰기 (문단에 직접 준 들여쓰기가 우선이므로 다시 적용)
        if (p.ilfo in 1..lfoLsid.size) {
            lists[lfoLsid[p.ilfo - 1]]?.getOrNull(p.ilvl)?.let { lvl ->
                val direct = run?.grpprl
                applyPara(lvl.papx, p)
                if (direct != null && hasIndent(direct)) applyPara(direct, p)
            }
        }
        return p to istd
    }

    // ================= 문서 만들기 =================

    /** 본문 문단 하나: [cp0, cp1) (끝 글자 포함) */
    private class ParaSpan(val cp0: Int, val cp1: Int, val props: ParaProps, val istd: Int, val cellEnd: Boolean)

    private fun build(maxPages: Int): HDoc {
        // 문단으로 나누기
        val spans = ArrayList<ParaSpan>()
        var start = 0
        for (cp in 0 until mainEnd) {
            val c = chars[cp]
            if (c == '\r' || c == '\u0007') {
                val (pp, istd) = paraProps(fcs[cp])
                spans.add(ParaSpan(start, cp + 1, pp, istd, c == '\u0007'))
                start = cp + 1
                if (maxPages > 0 && spans.size > 80 * maxPages) break
            }
        }
        if (start < mainEnd && (maxPages <= 0 || spans.size <= 80 * maxPages)) {
            val (pp, istd) = paraProps(fcs[start])
            spans.add(ParaSpan(start, mainEnd, pp, istd, false))
        }

        val out = ArrayList<HPara>()
        headerFooter()?.let { out.add(it) }
        var i = 0
        while (i < spans.size) {
            val s = spans[i]
            if (s.props.inTable) {
                // 표: TTP(행 끝 표시)가 나올 때까지 칸들을 모아 행을 만든다
                val rows = ArrayList<Pair<List<List<ParaSpan>>, ParaProps>>()
                var cells = ArrayList<List<ParaSpan>>()
                var cell = ArrayList<ParaSpan>()
                while (i < spans.size && spans[i].props.inTable) {
                    val sp = spans[i]
                    if (sp.props.ttp) {
                        if (cell.isNotEmpty()) { cells.add(cell); cell = ArrayList() }
                        rows.add(cells to sp.props)
                        cells = ArrayList()
                    } else {
                        cell.add(sp)
                        if (sp.cellEnd) { cells.add(cell); cell = ArrayList() }
                    }
                    i++
                }
                if (cell.isNotEmpty()) cells.add(cell)
                if (cells.isNotEmpty()) rows.add(cells to ParaProps())
                out.add(table(rows))
                continue
            }
            out.addAll(paragraph(s))
            i++
        }
        if (out.isEmpty()) out.add(HBuilder.Para().build())
        val section = HSection(out).apply { page = pageDef() }
        return b.build(listOf(section)) { id -> pictures[id] }
    }

    private fun font(i: Int): String = fonts.getOrNull(i) ?: ""

    /** 첫 구역의 홀수(기본) 쪽 머리말·꼬리말 → 조판 부호만 담은 문단 */
    private fun headerFooter(): HPara? {
        val fc = w.getInt(0xF2)
        val lcb = w.getInt(0xF6)
        if (lcb < 4 * 11 || fc + lcb > table.size) return null
        val n = lcb / 4
        fun story(i: Int): List<HPara>? {
            if (i + 1 >= n) return null
            val a = hdrStart + t.getInt(fc + i * 4)
            val z = hdrStart + t.getInt(fc + (i + 1) * 4)
            if (z <= a || z > chars.size) return null
            val paras = ArrayList<HPara>()
            var st = a
            for (cp in a until z) {
                if (chars[cp] == '\r' || cp == z - 1) {
                    val (pp, istd) = paraProps(fcs[st])
                    paras.addAll(paragraph(ParaSpan(st, cp + 1, pp, istd, false)))
                    st = cp + 1
                }
            }
            // 끝의 빈 문단은 뺀다
            while (paras.isNotEmpty() && paras.last().items.none { it is com.dsviewer.app.hwp.PItem.Text && it.text.isNotBlank() || it is com.dsviewer.app.hwp.PItem.Ctrl || it is com.dsviewer.app.hwp.PItem.Obj }) paras.removeAt(paras.size - 1)
            return paras.ifEmpty { null }
        }
        val para = HBuilder.Para()
        story(7)?.let { para.ctrl(com.dsviewer.app.hwp.HCtrl.Header("BOTH", it, 0), 0) }
        story(9)?.let { para.ctrl(com.dsviewer.app.hwp.HCtrl.Footer("BOTH", it, 0), 0) }
        return if (para.isEmpty) null else para.build()
    }

    private fun runOf(c: CharProps): HBuilder.Run {
        val latin = font(if (c.latin >= 0) c.latin else defLatin).ifBlank { "바탕" }
        val ea = font(if (c.ea >= 0) c.ea else defEa).ifBlank { latin }
        return HBuilder.Run(
            latin = latin, ea = ea, size = c.size * 50, color = c.color, bold = c.bold, italic = c.italic,
            underline = c.underline, strike = c.strike, sup = c.sup, sub = c.sub, shade = c.shade,
            eaSpacing = if (WordFonts.fullWidthHangul(ea)) 8 else 0,
            spaceEm = if (WordFonts.fullWidthHangul(latin)) 0.5f else 0f,
        )
    }

    /** 문단 하나 (쪽 나누기 글자가 있으면 여러 문단) */
    private fun paragraph(s: ParaSpan): MutableList<HPara> {
        val pp = s.props
        val out = ArrayList<HPara>()
        val markProps = charProps(fcs[s.cp1 - 1], s.istd)
        val baseSize = markProps.size * 50

        val ps = ParaShape()
        ps.align = pp.frameAlign ?: pp.align
        ps.left = max(0, HBuilder.twip(pp.left + minOf(pp.first, 0)))
        ps.right = max(0, HBuilder.twip(pp.right))
        ps.indent = HBuilder.twip(pp.first)
        ps.prev = HBuilder.twip(pp.before)
        ps.next = HBuilder.twip(pp.after)
        ps.leadAbove = 0.6f
        ps.prefixTab = true
        if (pp.ilfo in 1..lfoLsid.size) {
            val lsid = lfoLsid[pp.ilfo - 1]
            val levels = lists[lsid]
            val lvl = levels?.getOrNull(pp.ilvl)
            if (levels != null && lvl != null && lvl.nfc != 0xFF && s.cp1 - s.cp0 > 1) {
                if (lvl.nfc == 23) {
                    val ch = lvl.text.firstOrNull()
                    val bullet = ch?.let { WordFonts.bullet(it) } ?: "•"
                    if (bullet.isNotBlank()) { ps.headingType = "BULLET"; ps.headingIdRef = b.bullet(bullet) }
                } else {
                    ps.headingType = "NUMBER"
                    ps.headingIdRef = numberingOf(lsid, levels)
                    ps.headingLevel = pp.ilvl
                }
            }
        }
        var factor = 0f
        for (cp in s.cp0 until s.cp1) {
            val ch = chars[cp]
            if (ch.code < 0x20) continue
            val cp2 = charProps(fcs[cp], s.istd)
            val sc = Character.UnicodeScript.of(ch.code)
            val lat = font(if (cp2.latin >= 0) cp2.latin else defLatin)
            val face = if (sc == Character.UnicodeScript.HANGUL || sc == Character.UnicodeScript.HAN) font(if (cp2.ea >= 0) cp2.ea else defEa).ifBlank { lat } else lat
            factor = max(factor, WordFonts.lineFactor(face.ifBlank { "바탕" }))
            if (cp - s.cp0 > 200) break
        }
        if (factor == 0f) factor = WordFonts.lineFactor(font(if (markProps.ea >= 0) markProps.ea else defEa).ifBlank { "바탕" })
        when {
            pp.lineMult -> { ps.lineSpacingType = "PERCENT"; ps.lineSpacing = (pp.line / 240f * factor * 100).roundToInt().coerceIn(60, 800) }
            pp.line < 0 -> { ps.lineSpacingType = "FIXED"; ps.lineSpacing = HBuilder.twip(abs(pp.line)) }
            else -> { ps.lineSpacingType = "AT_LEAST"; ps.lineSpacing = HBuilder.twip(pp.line) }
        }
        val psId = b.paraShape(ps)

        var para = HBuilder.Para(psId)
        para.pageBreak = pp.pageBreak
        para.endCharShape = b.charShape(runOf(markProps))
        // 필드: 코드 부분은 건너뛰고 결과만. 쪽 번호(PAGE) 필드는 결과 대신 자동 쪽 번호
        val fieldCode = ArrayList<StringBuilder?>() // null = 결과 부분
        val fieldSkip = ArrayList<Boolean>()
        // 같은 모양 글자는 모아서 붙인다
        val buf = StringBuilder()
        var bufCs = -1
        fun flush() {
            if (buf.isNotEmpty()) para.text(buf.toString(), bufCs)
            buf.setLength(0)
        }
        fun add(text: String, cs: Int) {
            if (cs != bufCs) { flush(); bufCs = cs }
            buf.append(text)
        }
        var cp = s.cp0
        while (cp < s.cp1) {
            val ch = chars[cp]
            when (ch) {
                '\u0013' -> { fieldCode.add(StringBuilder()); fieldSkip.add(false) }
                '\u0014' -> if (fieldCode.isNotEmpty()) {
                    val code = fieldCode.last()?.toString()?.trim()?.uppercase().orEmpty()
                    fieldCode[fieldCode.size - 1] = null
                    val cs = b.charShape(runOf(charProps(fcs[cp], s.istd)))
                    when {
                        code.startsWith("PAGE") -> { flush(); para.ctrl(com.dsviewer.app.hwp.HCtrl.AutoNum("PAGE", "DIGIT"), cs); fieldSkip[fieldSkip.size - 1] = true }
                        code.startsWith("NUMPAGES") -> { flush(); para.ctrl(com.dsviewer.app.hwp.HCtrl.AutoNum("TOTAL_PAGE", "DIGIT"), cs); fieldSkip[fieldSkip.size - 1] = true }
                    }
                }
                '\u0015' -> if (fieldCode.isNotEmpty()) { fieldCode.removeAt(fieldCode.size - 1); fieldSkip.removeAt(fieldSkip.size - 1) }
                else -> if (fieldCode.isNotEmpty() && fieldCode.last() != null) fieldCode.last()!!.append(ch)
                else if (fieldSkip.none { it }) {
                    val cprops = charProps(fcs[cp], s.istd)
                    if (!cprops.vanish) {
                        val cs = b.charShape(runOf(cprops))
                        when (ch) {
                            '\r', '\u0007' -> {}
                            '\u000C' -> {
                                // 쪽 나누기: 여기서 문단을 끊는다
                                flush()
                                out.add(para.build())
                                para = HBuilder.Para(psId).apply { pageBreak = true; endCharShape = b.charShape(runOf(markProps)) }
                            }
                            '\u000B', '\u000E' -> add("\n", cs)
                            '\t' -> { flush(); para.tab(3600, cs) }
                            '\u001E' -> add("-", cs)
                            '\u001F', '\u0002', '\u0003', '\u0004', '\u0005', '\u0008' -> {}
                            '\u0001' -> picture(cprops)?.let { flush(); para.obj(it, cs) }
                            else -> if (ch.code >= 0x20) add(ch.toString(), cs)
                        }
                    }
                }
            }
            cp++
        }
        flush()
        out.add(para.build())
        return out
    }

    /** 글자처럼 넣은 그림: Data 스트림의 PICF + 그림 자료(BLIP) */
    private fun picture(c: CharProps): HPicture? {
        val d = data ?: return null
        val off = c.picLoc
        if (off < 0 || off + 68 > d.size) return null
        val bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN)
        val lcb = bb.getInt(off)
        val cbHeader = bb.getShort(off + 4).toInt() and 0xFFFF
        if (lcb <= cbHeader || off + lcb > d.size) return null
        val dxa = bb.getShort(off + 28).toInt() and 0xFFFF
        val dya = bb.getShort(off + 30).toInt() and 0xFFFF
        val mx = (bb.getShort(off + 32).toInt() and 0xFFFF).let { if (it == 0) 1000 else it }
        val my = (bb.getShort(off + 34).toInt() and 0xFFFF).let { if (it == 0) 1000 else it }
        // 그림 자료 찾기: JPEG(0xF01D)·PNG(0xF01E)·DIB(0xF01F) 기록
        var p = off + cbHeader
        if ((bb.getShort(off + 6).toInt() and 0xFFFF) == 0x66) p += 1 + (d[p].toInt() and 0xFF)
        val end = off + lcb
        val bytes = OfficeArt.findBlip(d, p, end) ?: return null
        val id = "doc_pic_$off"
        pictures[id] = bytes
        val wTw = dxa.toLong() * mx / 1000
        val hTw = dya.toLong() * my / 1000
        if (wTw <= 0 || hTw <= 0) return null
        return HPicture().apply {
            binId = id
            width = HBuilder.twip(wTw.toInt())
            height = HBuilder.twip(hTw.toInt())
            orgWidth = width; orgHeight = height
            treatAsChar = true
        }
    }

    private fun table(rows: List<Pair<List<List<ParaSpan>>, ParaProps>>): HPara {
        val t = HTable()
        t.treatAsChar = true
        t.measureHeight = true
        var maxCols = 0
        // 행마다 칸 경계 (twip). TDefTable이 없으면 같은 폭
        class RowDef(val centers: IntArray, val merged: BooleanArray, val vMerge: BooleanArray, val vRestart: BooleanArray, val brc: Array<Array<BorderLine>?>)
        val defs = rows.map { (cells, pp) ->
            val g = pp.tdef
            if (g != null && pp.tdefLen >= 1) {
                val n = g[pp.tdefAt].toInt() and 0xFF
                val bb = ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN)
                val centers = IntArray(n + 1) { k -> if (pp.tdefAt + 1 + k * 2 + 2 <= g.size) bb.getShort(pp.tdefAt + 1 + k * 2).toInt() else 0 }
                val tcBase = pp.tdefAt + 1 + (n + 1) * 2
                val merged = BooleanArray(n)
                val vm = BooleanArray(n)
                val vr = BooleanArray(n)
                val brc = arrayOfNulls<Array<BorderLine>>(n)
                for (k in 0 until n) {
                    val o = tcBase + k * 20
                    if (o + 20 > pp.tdefAt + pp.tdefLen || o + 20 > g.size) break
                    val grf = bb.getShort(o).toInt() and 0xFFFF
                    merged[k] = grf and 0x0002 != 0
                    vm[k] = grf and 0x0020 != 0
                    vr[k] = grf and 0x0040 != 0
                    brc[k] = Array(4) { e -> brc80(g, o + 4 + e * 4) }
                }
                RowDef(centers, merged, vm, vr, brc)
            } else {
                val n = max(1, cells.size)
                RowDef(IntArray(n + 1) { it * 9000 / n }, BooleanArray(n), BooleanArray(n), BooleanArray(n), arrayOfNulls(n))
            }
        }
        // 모든 행의 경계를 모아 열 격자를 만든다
        val xs = sortedSetOf<Int>()
        for (d in defs) for (x in d.centers) xs.add(x)
        val grid = xs.toIntArray()
        maxCols = max(1, grid.size - 1)
        fun col(x: Int) = grid.indexOfFirst { it >= x }.let { if (it < 0) grid.size - 1 else it }
        val left0 = grid.first()

        // 세로 합치기: (행, 격자 열) → 시작 칸
        val owner = HashMap<Long, HCell>()
        for ((r, row) in rows.withIndex()) {
            val (cells, pp) = row
            val d = defs[r]
            val tb = pp.tableBorders
            for ((k, cellSpans) in cells.withIndex()) {
                if (k >= d.centers.size - 1) break
                if (d.merged.getOrElse(k) { false }) continue
                val c0 = col(d.centers[k])
                var kEnd = k + 1
                while (kEnd < d.centers.size - 1 && d.merged.getOrElse(kEnd) { false }) kEnd++
                val c1 = col(d.centers[kEnd])
                if (d.vMerge.getOrElse(k) { false } && !d.vRestart.getOrElse(k) { false }) {
                    // 위 칸에 합친다
                    owner[((r - 1).toLong() shl 20) or c0.toLong()]?.let { above ->
                        above.rowSpan++
                        owner[(r.toLong() shl 20) or c0.toLong()] = above
                    }
                    continue
                }
                val cell = HCell()
                cell.row = r
                cell.col = c0
                cell.colSpan = max(1, c1 - c0)
                cell.width = HBuilder.twip(grid[minOf(c0 + cell.colSpan, grid.size - 1)] - grid[c0])
                cell.height = max(400, HBuilder.twip(abs(pp.rowHeight)))
                cell.marginLeft = 540
                cell.marginRight = 540
                cell.hasMargin = true
                cell.vertAlign = "TOP"
                val bf = BorderFill()
                val own = d.brc.getOrNull(k)
                fun pick(ownIdx: Int, outer: Int, inner: Int, isOuter: Boolean): BorderLine {
                    own?.getOrNull(ownIdx)?.let { if (it.visible) return it }
                    return tb?.getOrNull(if (isOuter) outer else inner) ?: BorderLine.NONE
                }
                // BRC 순서: 위, 왼쪽, 아래, 오른쪽 (표 테두리는 + 안쪽 가로, 안쪽 세로)
                bf.top = pick(0, 0, 4, r == 0)
                bf.left = pick(1, 1, 5, c0 == 0)
                bf.bottom = pick(2, 2, 4, r == rows.size - 1)
                bf.right = pick(3, 3, 5, c0 + cell.colSpan >= maxCols)
                pp.shades?.getOrNull(k)?.let { if (it != 0) bf.fillColor = it }
                cell.borderFillId = b.borderFill(bf)
                val paras = ArrayList<HPara>()
                for (sp in cellSpans) paras.addAll(paragraph(sp))
                cell.paras = paras
                t.cells.add(cell)
                owner[(r.toLong() shl 20) or c0.toLong()] = cell
            }
        }
        // 합친 칸 높이
        for (c in t.cells) if (c.rowSpan > 1) c.height *= c.rowSpan
        t.rowCnt = rows.size
        t.colCnt = maxCols
        t.width = HBuilder.twip(grid.last() - left0)
        t.height = t.cells.filter { it.col == 0 }.sumOf { it.height }.coerceAtLeast(400)
        val ps = ParaShape().apply {
            lineSpacingType = "PERCENT"; lineSpacing = 100
            left = max(0, HBuilder.twip(left0))
        }
        val para = HBuilder.Para(b.paraShape(ps))
        para.obj(t, 0)
        return para.build()
    }

    /** 첫 구역의 용지·여백 */
    private fun pageDef(): PageDef {
        val pd = PageDef()
        var pw = 11906
        var ph = 16838
        var left = 1800
        var right = 1800
        var top = 1440
        var bottom = 1440
        var hdr = 851
        var ftr = 992
        runCatching {
            val fc = w.getInt(0xCA)
            val lcb = w.getInt(0xCE)
            if (lcb >= 16 && fc + lcb <= table.size) {
                val n = (lcb - 4) / 16
                val sed = fc + (n + 1) * 4
                val fcSepx = t.getInt(sed + 2)
                if (fcSepx > 0 && fcSepx + 2 < wd.size) {
                    val cb = w.getShort(fcSepx).toInt() and 0xFFFF
                    val g = wd.copyOfRange(fcSepx + 2, minOf(wd.size, fcSepx + 2 + cb))
                    val bb = ByteBuffer.wrap(g).order(ByteOrder.LITTLE_ENDIAN)
                    sprms(g) { sprm, at, len ->
                        if (len >= 2) {
                            val v = bb.getShort(at).toInt()
                            when (sprm) {
                                0xB01F -> pw = v and 0xFFFF
                                0xB020 -> ph = v and 0xFFFF
                                0xB021 -> left = v and 0xFFFF
                                0xB022 -> right = v and 0xFFFF
                                0x9023 -> top = abs(v)
                                0x9024 -> bottom = abs(v)
                                0xB017 -> hdr = v and 0xFFFF
                                0xB018 -> ftr = v and 0xFFFF
                            }
                        }
                    }
                }
            }
        }
        pd.width = HBuilder.twip(pw)
        pd.height = HBuilder.twip(ph)
        pd.left = HBuilder.twip(left)
        pd.right = HBuilder.twip(right)
        pd.top = HBuilder.twip(minOf(hdr, top))
        pd.header = HBuilder.twip(max(0, top - hdr))
        pd.bottom = HBuilder.twip(minOf(ftr, bottom))
        pd.footer = HBuilder.twip(max(0, bottom - ftr))
        return pd
    }
}
