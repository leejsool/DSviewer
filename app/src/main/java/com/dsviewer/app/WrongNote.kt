package com.dsviewer.app

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 오답노트: 문제 영역을 (PDF 내용 + 필기 그대로) 그림으로 담아 문서 맨 뒤의 오답 쪽에 모은다.
 * 첫째 분류는 기호([WrongSymbol]), 둘째 분류는 해시태그. 한 문제가 한 쪽을 쓰거나(칸 0),
 * 반 쪽씩 둘이 쪽을 나눠 쓴다(칸 1 = 위, 칸 2 = 아래).
 */
object WrongSymbol {
    const val NONE = 0
    const val TRIANGLE = 6
    const val CROSS = 7
    const val SQUARE = 8

    /** 고를 수 있는 기호: 별 1~5개, 세모, 엑스, 네모 */
    val ALL = (1..8).toList()

    fun label(s: Int) = when (s) {
        in 1..5 -> "★".repeat(s)
        TRIANGLE -> "△"
        CROSS -> "✕"
        SQUARE -> "□"
        else -> "분류 없음"
    }

    fun color(s: Int) = when (s) {
        in 1..5 -> 0xFFF9A825.toInt()
        TRIANGLE -> 0xFFEF6C00.toInt()
        CROSS -> 0xFFD32F2F.toInt()
        SQUARE -> 0xFF1976D2.toInt()
        else -> 0xFF757575.toInt()
    }

    /** 목록·목차에서 늘어놓는 차례: 별 5개부터 1개, 세모, 엑스, 네모, 분류 없음 */
    fun order(s: Int) = when (s) {
        in 1..5 -> 5 - s
        TRIANGLE -> 5
        CROSS -> 6
        SQUARE -> 7
        else -> 8
    }

    /**
     * [left]에서 시작해 세로 가운데 [cy]에 [size] 크기로 기호를 그리고, 차지한 너비를 돌려준다.
     * 글꼴에 기대지 않으려고 모양을 직접 그린다
     */
    fun draw(c: Canvas, s: Int, left: Float, cy: Float, size: Float, paint: Paint): Float {
        val saved = Paint(paint)
        paint.color = color(s)
        val r = size / 2f
        var width = 0f
        when (s) {
            in 1..5 -> {
                paint.style = Paint.Style.FILL
                for (k in 0 until s) {
                    val path = Path()
                    val cx = left + r + k * size * 1.08f
                    for (i in 0 until 10) {
                        val a = -PI / 2 + i * PI / 5
                        val rad = if (i % 2 == 0) r else r * 0.42f
                        val x = (cx + rad * cos(a)).toFloat()
                        val y = (cy + rad * sin(a)).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    c.drawPath(path, paint)
                }
                width = s * size * 1.08f
            }
            TRIANGLE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.16f
                paint.strokeJoin = Paint.Join.ROUND
                val path = Path().apply {
                    moveTo(left + r, cy - r * 0.9f)
                    lineTo(left + size * 0.95f, cy + r * 0.8f)
                    lineTo(left + size * 0.05f, cy + r * 0.8f)
                    close()
                }
                c.drawPath(path, paint)
                width = size
            }
            CROSS -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.18f
                paint.strokeCap = Paint.Cap.ROUND
                c.drawLine(left + size * 0.12f, cy - r * 0.78f, left + size * 0.88f, cy + r * 0.78f, paint)
                c.drawLine(left + size * 0.88f, cy - r * 0.78f, left + size * 0.12f, cy + r * 0.78f, paint)
                width = size
            }
            SQUARE -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = size * 0.16f
                paint.strokeJoin = Paint.Join.ROUND
                c.drawRect(left + size * 0.1f, cy - r * 0.8f, left + size * 0.9f, cy + r * 0.8f, paint)
                width = size
            }
        }
        paint.set(saved)
        return width
    }
}

/**
 * 오답 한 문제. 쪽의 [slot] 자리를 차지한다. [srcList]는 문제를 가져온 쪽의 필기 목록(쪽 번호 대신 그 쪽 자체를
 * 가리켜 쪽 순서가 바뀌어도 따라간다), [srcRect]는 가져온 영역(그 쪽의 좌표)
 */
class WrongEntry(
    val number: Int,
    var symbol: Int,
    var tags: List<String>,
    var title: String,
    val date: String,
    val slot: Int,
    var srcList: MutableList<Stroke>?,
    val srcRect: RectF?,
)

/** 오답노트 목차 항목: [page]쪽 위에서 [top]pt 자리 */
class WrongOutlineItem(val page: Int, val top: Float, val entry: WrongEntry)

/** 저장할 오답 정보: 쪽마다 한 줄 글과 목차 */
class WrongSave(val metas: Map<Int, String>, val outline: List<WrongOutlineItem>)

object WrongNote {
    const val PAGE_W = PdfPages.A4_SHORT
    const val PAGE_H = PdfPages.A4_LONG
    private const val MARGIN = 24f
    private const val HEADER_H = 36f
    private const val GAP = 6f
    private const val LABEL_H = 18f
    private const val MIN_BODY_W = 160f
    /** 머리줄 오른쪽 '원문 보기' 누름 칸 너비 (pt) */
    const val LINK_W = 150f
    private const val BADGE_W = 62f
    private const val BADGE_H = 16f
    private const val HEADER_PPP = 4f
    private const val CAPTURE_PPP = 3f
    private const val CAPTURE_MAX_PX = 2400f

    /** 그림 획에 붙이는 이름표: 머리줄 [ROLE_HEADER], 원문 쪽 배지 [ROLE_BADGE] 뒤에 오답 번호 */
    const val ROLE_HEADER = "WH"
    const val ROLE_BADGE = "WS"

    fun slotTop(slot: Int) = if (slot == 2) PAGE_H / 2f else 0f
    private fun slotHeight(slot: Int) = if (slot == 0) PAGE_H else PAGE_H / 2f

    fun today(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    // ---------------- 저장 형식 ----------------
    // 쪽 사전의 /DSViewerWrong: 항목들을 ~로 잇고, 항목은 번호|기호|칸|날짜|원문 쪽(없으면 -1)|l,t,r,b|제목|태그,태그
    // 제목과 태그는 URL 인코딩이라 | ~ , 를 안전하게 쓴다

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun dec(s: String) = URLDecoder.decode(s, "UTF-8")

    fun encode(entries: List<WrongEntry>, srcPage: (WrongEntry) -> Int): String = entries.joinToString("~") { e ->
        val r = e.srcRect?.let { "${it.left},${it.top},${it.right},${it.bottom}" } ?: ""
        listOf(
            e.number.toString(), e.symbol.toString(), e.slot.toString(), e.date, srcPage(e).toString(), r,
            enc(e.title), e.tags.joinToString(",") { enc(it) },
        ).joinToString("|")
    }

    /** (항목, 원문 쪽 번호) 목록. 알아볼 수 없는 항목은 버린다 */
    fun decode(s: String): List<Pair<WrongEntry, Int>> = s.split('~').mapNotNull { part ->
        try {
            val f = part.split('|')
            if (f.size < 8) return@mapNotNull null
            val rect = f[5].split(',').mapNotNull { it.toFloatOrNull() }.takeIf { it.size == 4 }?.let { RectF(it[0], it[1], it[2], it[3]) }
            val tags = if (f[7].isEmpty()) emptyList() else f[7].split(',').map { dec(it) }
            WrongEntry(f[0].toInt(), f[1].toInt(), tags, dec(f[6]), f[3], f[2].toInt().coerceIn(0, 2), null, rect) to f[4].toInt()
        } catch (e: Exception) {
            null
        }
    }

    /** 사용자가 친 해시태그 글 → 태그 목록 ('#' 떼고, 띄어쓰기·쉼표로 나누고, 겹침 없이) */
    fun parseTags(text: String): List<String> =
        text.split(Regex("[\\s,]+")).map { it.trim().trimStart('#') }.filter { it.isNotEmpty() }.distinct()

    fun tagsText(tags: List<String>) = tags.joinToString(" ") { "#$it" }

    fun outlineTitle(e: WrongEntry): String {
        val sb = StringBuilder("#").append(e.number)
        if (e.title.isNotBlank()) sb.append(' ').append(e.title.trim())
        if (e.tags.isNotEmpty()) sb.append("  ").append(tagsText(e.tags))
        return sb.toString()
    }

    // ---------------- 캡처 ----------------

    /** 고른 영역을 그릴 배율 (pt당 픽셀): 보통 3배, 큰 영역은 긴 변이 2400px을 넘지 않게 */
    fun captureScale(rect: RectF): Float =
        min(CAPTURE_PPP, CAPTURE_MAX_PX / max(rect.width(), rect.height())).coerceAtLeast(0.5f)

    /**
     * [base](PDF 내용만 그린 영역 그림)에 그 쪽의 필기를 얹는다. 오답노트 배지·머리줄 같은 표시는 뺀다.
     * 필기 획을 그 자리에서 바로 그리므로 화면 스레드에서 부를 것
     */
    fun compose(base: Bitmap, strokes: List<Stroke>, rect: RectF, scale: Float): Bitmap {
        val c = Canvas(base)
        c.scale(scale, scale)
        c.translate(-rect.left, -rect.top)
        c.clipRect(rect)
        val paint = inkPaint()
        val list = strokes.filter { it.role?.startsWith("W") != true }
        for (layer in 0..3) for (st in list) if (inkLayer(st) == layer) drawInkStroke(c, paint, st)
        return base
    }

    // ---------------- 쪽 구성 ----------------

    class Built(val header: Stroke, val body: Stroke, val badge: Stroke?)

    /**
     * 오답 한 문제의 획들: 머리줄, 문제 그림(+ '정답 · 풀이' 구분), 원문 쪽의 배지.
     * 그림은 칸 너비에 맞춰 줄이되 늘리지는 않고, 아래에 풀이 쓸 자리가 남게 높이를 제한한다
     */
    fun build(e: WrongEntry, capture: Bitmap, rect: RectF, srcPageW: Float): Built {
        val capW = rect.width()
        val capH = rect.height()
        val contentW = PAGE_W - 2 * MARGIN
        val solveFrac = if (e.slot == 0) 0.5f else 0.45f
        val maxH = slotHeight(e.slot) * (1f - solveFrac) - MARGIN - HEADER_H - GAP - LABEL_H
        val k = min(1f, min(contentW / capW, maxH / capH))
        val dw = capW * k
        val dh = capH * k
        val top = slotTop(e.slot) + MARGIN
        val header = RectF(MARGIN, top, PAGE_W - MARGIN, top + HEADER_H)
        val body = RectF(MARGIN, header.bottom + GAP, MARGIN + max(dw, MIN_BODY_W), header.bottom + GAP + dh + LABEL_H)
        val badge = if (e.srcRect == null) null else {
            val x = e.srcRect.left.coerceIn(0f, max(0f, srcPageW - BADGE_W))
            val y = (e.srcRect.top - BADGE_H - 1f).coerceAtLeast(0f)
            imageStroke(badgeImage(e), RectF(x, y, x + BADGE_W, y + BADGE_H), ROLE_BADGE + e.number)
        }
        return Built(
            imageStroke(headerImage(e, header.width()), header, ROLE_HEADER + e.number),
            imageStroke(bodyImage(capture, dw, dh), body, null),
            badge,
        )
    }

    /** 분류를 바꾼 뒤 머리줄 획을 새로 만든다 (원래 자리 [rect]에) */
    fun rebuildHeader(e: WrongEntry, rect: RectF): Stroke =
        imageStroke(headerImage(e, rect.width()), rect, ROLE_HEADER + e.number)

    fun rebuildBadge(e: WrongEntry, rect: RectF): Stroke =
        imageStroke(badgeImage(e), rect, ROLE_BADGE + e.number)

    private fun imageStroke(img: InkImage, r: RectF, role: String?): Stroke =
        Stroke(Tool.PEN, Color.BLACK, 0f).apply {
            image = img
            this.role = role
            add(r.left, r.top, 1f)
            add(r.right, r.top, 1f)
            add(r.right, r.bottom, 1f)
            add(r.left, r.bottom, 1f)
        }

    /** 문제 그림 아래에 '정답 · 풀이' 점선과 글자를 붙인 흰 바탕 그림 (JPEG) */
    private fun bodyImage(capture: Bitmap, dw: Float, dh: Float): InkImage {
        val ppp = capture.width / dw
        val wPx = (max(dw, MIN_BODY_W) * ppp).roundToInt().coerceAtLeast(1)
        val hPx = ((dh + LABEL_H) * ppp).roundToInt().coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(wPx, hPx, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        c.drawBitmap(capture, null, RectF(0f, 0f, dw * ppp, dh * ppp), Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = 0xFF90A4AE.toInt()
            strokeWidth = ppp
            pathEffect = DashPathEffect(floatArrayOf(4 * ppp, 3 * ppp), 0f)
        }
        c.drawLine(0f, (dh + 3f) * ppp, wPx.toFloat(), (dh + 3f) * ppp, line)
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF607D8B.toInt()
            textSize = 10f * ppp
        }
        c.drawText("정답 · 풀이", 2f * ppp, (dh + LABEL_H - 4f) * ppp, text)
        val bytes = java.io.ByteArrayOutputStream().use {
            bmp.compress(Bitmap.CompressFormat.JPEG, 90, it)
            it.toByteArray()
        }
        return InkImage(bmp, bytes)
    }

    /** 머리줄: 기호 · #번호 제목 · 해시태그 · 날짜 · '원문 보기' (글꼴이 달라도 같게 그림으로) */
    private fun headerImage(e: WrongEntry, widthPt: Float): InkImage {
        val ppp = HEADER_PPP
        val bmp = Bitmap.createBitmap((widthPt * ppp).roundToInt(), (HEADER_H * ppp).roundToInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(ppp, ppp)
        val accent = WrongSymbol.color(e.symbol)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = blend(accent, 0.12f)
        c.drawRoundRect(0f, 0f, widthPt, HEADER_H, 6f, 6f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = (accent and 0x00FFFFFF) or 0x80000000.toInt()
        c.drawRoundRect(0.5f, 0.5f, widthPt - 0.5f, HEADER_H - 0.5f, 6f, 6f, paint)
        paint.style = Paint.Style.FILL

        var x = 9f
        if (e.symbol != WrongSymbol.NONE) x += WrongSymbol.draw(c, e.symbol, x, 11.5f, 11f, paint) + 6f
        val leftW = widthPt - LINK_W - x - 4f

        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 13f; color = 0xFF263238.toInt() }
        val head = "#${e.number}" + if (e.title.isNotBlank()) "  ${e.title.trim()}" else ""
        c.drawText(fit(head, t, leftW), x, 16f, t)
        if (e.tags.isNotEmpty()) {
            t.typeface = Typeface.DEFAULT
            t.textSize = 10.5f
            t.color = 0xFF1565C0.toInt()
            c.drawText(fit(tagsText(e.tags), t, widthPt - LINK_W - 9f - 4f), 9f, 30f, t)
        }
        t.textAlign = Paint.Align.RIGHT
        if (e.srcList != null) {
            t.typeface = Typeface.DEFAULT_BOLD
            t.textSize = 11f
            t.color = 0xFF1565C0.toInt()
            c.drawText("원문 보기 ›", widthPt - 10f, 16f, t)
        }
        t.typeface = Typeface.DEFAULT
        t.textSize = 9.5f
        t.color = 0xFF78909C.toInt()
        c.drawText(e.date, widthPt - 10f, 30f, t)
        return InkImage(bmp, null)
    }

    /** 원문 쪽에 붙이는 작은 배지 '(기호) 오답 #번호' */
    private fun badgeImage(e: WrongEntry): InkImage {
        val ppp = 6f
        val bmp = Bitmap.createBitmap((BADGE_W * ppp).roundToInt(), (BADGE_H * ppp).roundToInt(), Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.scale(ppp, ppp)
        val accent = WrongSymbol.color(e.symbol)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = blend(accent, 0.2f)
        c.drawRoundRect(0f, 0f, BADGE_W, BADGE_H, 8f, 8f, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 0.8f
        paint.color = accent
        c.drawRoundRect(0.4f, 0.4f, BADGE_W - 0.4f, BADGE_H - 0.4f, 8f, 8f, paint)
        paint.style = Paint.Style.FILL
        var x = 5f
        // 별이 여럿이어도 배지에서는 별 하나로 줄여 그린다
        if (e.symbol != WrongSymbol.NONE) x += WrongSymbol.draw(c, if (e.symbol in 1..5) 1 else e.symbol, x, BADGE_H / 2f, 8f, paint) + 3f
        val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; textSize = 9f; color = 0xFF263238.toInt() }
        c.drawText("오답 #${e.number}", x, BADGE_H / 2f + 3.2f, t)
        return InkImage(bmp, null)
    }

    private fun fit(s: String, p: Paint, maxW: Float): String {
        if (maxW <= 0f) return ""
        if (p.measureText(s) <= maxW) return s
        val n = p.breakText(s, true, maxW - p.measureText("…"), null)
        return s.substring(0, n.coerceAtLeast(0)) + "…"
    }

    /** [color]를 [amount]만큼만 섞은 흰색에 가까운 바탕색 */
    private fun blend(color: Int, amount: Float): Int {
        fun ch(v: Int) = (255 + (v - 255) * amount).roundToInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(color)), ch(Color.green(color)), ch(Color.blue(color)))
    }
}

/**
 * 오답노트 링크 (읽기 모드에서 누를 때): 원문 쪽의 '오답 #n' 배지 → 그 오답 쪽,
 * 오답 쪽 머리줄 오른쪽 → 원문 쪽. (이동할 쪽, 그 쪽 안 높이)
 */
fun InkDocument.wrongLinkAt(page: Int, x: Float, y: Float): Pair<Int, Float>? {
    val list = pages.getOrNull(page) ?: return null
    for (st in list.asReversed()) {
        val role = st.role ?: continue
        if (st.image == null || st.count < 4 || role.length < 3) continue
        val n = role.substring(2).toIntOrNull() ?: continue
        val box = wrongBounds(st)
        val r = box.right
        if (x < box.left - 2f || x > r + 2f || y < box.top - 2f || y > box.bottom + 2f) continue
        val hit = allWrongs().firstOrNull { it.second.number == n } ?: continue
        if (role.startsWith(WrongNote.ROLE_BADGE)) return hit.first to WrongNote.slotTop(hit.second.slot)
        if (role.startsWith(WrongNote.ROLE_HEADER) && x >= r - WrongNote.LINK_W) {
            val src = hit.second.srcList ?: return null
            val idx = pages.indexOfFirst { it === src }
            if (idx < 0) return null
            return idx to ((hit.second.srcRect?.top ?: 0f) - 24f).coerceAtLeast(0f)
        }
    }
    return null
}

/** 네 모서리로 된 그림 획이 차지하는 네모 (쪽 좌표) */
fun wrongBounds(st: Stroke): RectF {
    var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
    for (k in 0 until min(4, st.count)) {
        l = min(l, st.x(k)); r = max(r, st.x(k)); t = min(t, st.y(k)); b = max(b, st.y(k))
    }
    return RectF(l, t, r, b)
}
