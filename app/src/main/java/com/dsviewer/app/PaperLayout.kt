package com.dsviewer.app

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 바탕의 선 하나 (PDF 좌표: 쪽 왼쪽 아래가 원점, y는 위로). [dash]가 있으면 점선 (켜짐, 꺼짐 길이),
 * [round]면 끝이 둥글어서 켜짐 길이가 아주 짧은 점선이 점으로 보인다
 */
internal class PaperSeg(
    val x1: Float, val y1: Float, val x2: Float, val y2: Float,
    val rgb: Int, val width: Float, val dash: FloatArray? = null, val round: Boolean = false,
)

/** 바탕에 칠하는 네모 */
internal class PaperFill(val x: Float, val y: Float, val w: Float, val h: Float, val rgb: Int)

internal class PaperDrawing(val fills: List<PaperFill>, val segs: List<PaperSeg>)

/**
 * 빈 쪽 바탕들이 어떻게 생겼는지: 쪽 크기에서 선과 칠할 네모의 목록을 계산한다.
 * PDF에 그려 넣을 때와 서식 고르기 미리보기가 같은 계산을 쓴다. 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 * (모눈·줄의 실제 PDF 그리기는 [PdfPages]에 그대로 두었다: 이미 저장된 문서의 바탕과 같은지 비교하는 데 쓰이므로.
 *  여기의 모눈·줄은 미리보기용으로 같은 모양을 옮겨 놓은 것)
 */
internal object PaperLayout {
    const val MM = 72f / 25.4f

    private val NONE = PaperDrawing(emptyList(), emptyList())

    fun of(paper: Paper, w: Float, h: Float): PaperDrawing = when (paper) {
        Paper.PLAIN -> NONE
        Paper.GRID -> grid(w, h)
        Paper.LINED -> lined(w, h)
        Paper.DOTS -> dots(w, h)
        Paper.CORNELL -> cornell(w, h)
        Paper.MANUSCRIPT -> manuscript(w, h)
        Paper.ENGLISH -> english(w, h)
        Paper.AXES -> axes(w, h)
        Paper.TIMETABLE -> timetable(w, h)
        Paper.STAFF -> staff(w, h)
    }

    private fun seg(x1: Float, y1: Float, x2: Float, y2: Float, rgb: Int, width: Float, dash: FloatArray? = null, round: Boolean = false) =
        PaperSeg(x1, y1, x2, y2, rgb, width, dash, round)

    // ---- 모눈 · 줄 (미리보기용 복사) ----

    private fun grid(w: Float, h: Float): PaperDrawing {
        val step = 5 * MM
        val cx = w / 2f
        val cy = h / 2f
        val nx = (cx / step).toInt()
        val ny = (cy / step).toInt()
        val segs = ArrayList<PaperSeg>()
        for (major in listOf(false, true)) {
            val rgb = if (major) 0xB8C6D6 else 0xDCE3EC
            val width = if (major) 0.6f else 0.35f
            for (k in -nx..nx) if ((k % 5 == 0) == major) segs.add(seg(cx + k * step, 0f, cx + k * step, h, rgb, width))
            for (k in -ny..ny) if ((k % 5 == 0) == major) segs.add(seg(0f, cy + k * step, w, cy + k * step, rgb, width))
        }
        return PaperDrawing(emptyList(), segs)
    }

    private fun lined(w: Float, h: Float): PaperDrawing {
        val margin = 12 * MM
        val segs = ArrayList<PaperSeg>()
        var y = h - 25 * MM
        while (y > margin) {
            segs.add(seg(margin, y, w - margin, y, 0xC5D3E3, 0.5f))
            y -= 8 * MM
        }
        return PaperDrawing(emptyList(), segs)
    }

    // ---- 새 서식 ----

    /** 점 격자: 모눈과 같은 5mm 간격, 가운데를 기준으로 맞춘 점. 점마다 그리지 않고 점선(켜짐 0.1pt, 둥근 끝) 가로줄로 파일이 커지지 않게 */
    private fun dots(w: Float, h: Float): PaperDrawing {
        val step = 5 * MM
        val margin = 6 * MM
        val cx = w / 2f
        val cy = h / 2f
        val nx = ((cx - margin) / step).toInt()
        val ny = ((cy - margin) / step).toInt()
        val dash = floatArrayOf(0.1f, step - 0.1f)
        val segs = ArrayList<PaperSeg>()
        for (k in -ny..ny) {
            val y = cy + k * step
            segs.add(seg(cx - nx * step, y, cx + nx * step + 0.1f, y, 0x9FAFC0, 1.3f, dash, round = true))
        }
        return PaperDrawing(emptyList(), segs)
    }

    /** 코넬 노트: 위 제목 칸, 왼쪽 키워드(큐) 칸, 오른쪽 필기 칸(옅은 줄), 아래 요약 칸 */
    private fun cornell(w: Float, h: Float): PaperDrawing {
        val m = 10 * MM
        val top = h - m
        val bottom = m
        val titleY = top - 26 * MM
        val summaryY = bottom + 0.2f * (h - 2 * m)
        val cueX = m + 0.3f * (w - 2 * m)
        val right = w - m
        val frame = 0xB0BEC5
        val rule = 0x78909C
        val segs = ArrayList<PaperSeg>()
        segs.add(seg(m, top, right, top, frame, 0.8f))
        segs.add(seg(m, bottom, right, bottom, frame, 0.8f))
        segs.add(seg(m, bottom, m, top, frame, 0.8f))
        segs.add(seg(right, bottom, right, top, frame, 0.8f))
        segs.add(seg(m, titleY, right, titleY, rule, 1.1f))
        segs.add(seg(m, summaryY, right, summaryY, rule, 1.1f))
        segs.add(seg(cueX, titleY, cueX, summaryY, rule, 1.1f))
        var y = titleY - 8 * MM
        while (y > summaryY + 3 * MM) {
            segs.add(seg(cueX, y, right, y, 0xDCE3EC, 0.45f))
            y -= 8 * MM
        }
        return PaperDrawing(emptyList(), segs)
    }

    /** 원고지: 가로 20칸(세로 쪽) 정사각 칸이 줄마다 이어지고 줄 사이는 교정용 빈 띠 */
    private fun manuscript(w: Float, h: Float): PaperDrawing {
        val m = 12 * MM
        val cols = floor((w - 2 * m) / (9.3f * MM) + 0.05f).toInt().coerceAtLeast(5)
        val cell = (w - 2 * m) / cols
        val gap = cell * 0.45f
        val pitch = cell + gap
        val rows = floor((h - 2 * m + gap) / pitch).toInt().coerceAtLeast(1)
        val rgb = 0xE0B48A
        val segs = ArrayList<PaperSeg>()
        for (r in 0 until rows) {
            val yTop = h - m - r * pitch
            val yBot = yTop - cell
            segs.add(seg(m, yTop, m + cols * cell, yTop, rgb, 0.5f))
            segs.add(seg(m, yBot, m + cols * cell, yBot, rgb, 0.5f))
            for (c in 0..cols) segs.add(seg(m + c * cell, yBot, m + c * cell, yTop, rgb, 0.5f))
        }
        return PaperDrawing(emptyList(), segs)
    }

    /** 영어 4선: 네 줄이 한 묶음 (위 줄, 점선 가운데 줄, 붉은 기준선, 아래 줄) */
    private fun english(w: Float, h: Float): PaperDrawing {
        val m = 12 * MM
        val s = 4.2f * MM
        val pitch = 3 * s + 11 * MM
        val blue = 0xA9BDD8
        val segs = ArrayList<PaperSeg>()
        var top = h - 25 * MM
        while (top - 3 * s > m) {
            segs.add(seg(m, top, w - m, top, blue, 0.5f))
            segs.add(seg(m, top - s, w - m, top - s, 0xC3D1E6, 0.45f, floatArrayOf(3f, 2f)))
            segs.add(seg(m, top - 2 * s, w - m, top - 2 * s, 0xE79A9A, 0.8f))
            segs.add(seg(m, top - 3 * s, w - m, top - 3 * s, blue, 0.5f))
            top -= pitch
        }
        return PaperDrawing(emptyList(), segs)
    }

    /** 좌표평면: 옅은 5mm 눈금 위에 쪽 가운데로 x축·y축(끝에 화살표)과 1cm마다 눈금 표시 */
    private fun axes(w: Float, h: Float): PaperDrawing {
        val step = 5 * MM
        val cx = w / 2f
        val cy = h / 2f
        val nx = (cx / step).toInt()
        val ny = (cy / step).toInt()
        val segs = ArrayList<PaperSeg>()
        for (k in -nx..nx) segs.add(seg(cx + k * step, 0f, cx + k * step, h, 0xE3E9F0, 0.3f))
        for (k in -ny..ny) segs.add(seg(0f, cy + k * step, w, cy + k * step, 0xE3E9F0, 0.3f))
        val dark = 0x37474F
        val edge = 6 * MM
        segs.add(seg(edge, cy, w - edge, cy, dark, 1.2f))
        segs.add(seg(cx, edge, cx, h - edge, dark, 1.2f))
        // 화살표: 끝점에서 25도로 벌어진 두 짧은 선
        val len = 3.2f * MM
        val a = (25 * PI / 180).toFloat()
        val tipX = w - edge
        segs.add(seg(tipX, cy, tipX - len * cos(a), cy + len * sin(a), dark, 1.2f))
        segs.add(seg(tipX, cy, tipX - len * cos(a), cy - len * sin(a), dark, 1.2f))
        val tipY = h - edge
        segs.add(seg(cx, tipY, cx + len * sin(a), tipY - len * cos(a), dark, 1.2f))
        segs.add(seg(cx, tipY, cx - len * sin(a), tipY - len * cos(a), dark, 1.2f))
        // 1cm마다 눈금
        val tick = 1.2f * MM
        for (k in -nx..nx step 2) {
            if (k == 0) continue
            val x = cx + k * step
            if (x > edge + len && x < w - edge - len) segs.add(seg(x, cy - tick, x, cy + tick, dark, 0.8f))
        }
        for (k in -ny..ny step 2) {
            if (k == 0) continue
            val y = cy + k * step
            if (y > edge + len && y < h - edge - len) segs.add(seg(cx - tick, y, cx + tick, y, dark, 0.8f))
        }
        return PaperDrawing(emptyList(), segs)
    }

    /** 시간표: 맨 위 요일 줄과 맨 왼쪽 시간 칸은 옅게 칠하고, 요일 5칸 × 교시 칸 (글자는 직접 쓴다) */
    private fun timetable(w: Float, h: Float): PaperDrawing {
        val m = 12 * MM
        val headH = 10 * MM
        val timeW = 14 * MM
        val top = h - m
        val bottom = m
        val rows = ((h - 2 * m - headH) / (30 * MM)).roundToInt().coerceAtLeast(4)
        val rowH = (h - 2 * m - headH) / rows
        val dayW = (w - 2 * m - timeW) / 5
        val right = w - m
        val rgb = 0x90A4AE
        val fills = listOf(
            PaperFill(m, top - headH, right - m, headH, 0xEEF2F6),
            PaperFill(m, bottom, timeW, h - 2 * m - headH, 0xF6F8FA),
        )
        val segs = ArrayList<PaperSeg>()
        segs.add(seg(m, top, right, top, rgb, 0.8f))
        segs.add(seg(m, top - headH, right, top - headH, rgb, 0.8f))
        for (r in 1..rows) segs.add(seg(m, top - headH - r * rowH, right, top - headH - r * rowH, rgb, 0.6f))
        segs.add(seg(m, bottom, m, top, rgb, 0.8f))
        segs.add(seg(m + timeW, bottom, m + timeW, top, rgb, 0.8f))
        for (c in 1..5) segs.add(seg(m + timeW + c * dayW, bottom, m + timeW + c * dayW, top, rgb, 0.6f))
        return PaperDrawing(fills, segs)
    }

    /** 오선지: 다섯 줄이 한 묶음, 왼쪽에 세로 막대 */
    private fun staff(w: Float, h: Float): PaperDrawing {
        val m = 12 * MM
        val sp = 2.4f * MM
        val staffH = 4 * sp
        val pitch = staffH + 13 * MM
        val rgb = 0x78909C
        val segs = ArrayList<PaperSeg>()
        var top = h - 25 * MM
        while (top - staffH > m) {
            for (i in 0..4) segs.add(seg(m, top - i * sp, w - m, top - i * sp, rgb, 0.5f))
            segs.add(seg(m, top, m, top - staffH, rgb, 0.8f))
            top -= pitch
        }
        return PaperDrawing(emptyList(), segs)
    }
}
