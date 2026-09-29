package com.dsviewer.app.hwp

import android.graphics.Paint
import android.graphics.Typeface
import java.io.File

/**
 * 한글 문서의 글꼴 이름을 기기에 있는 글꼴로 대응시킨다.
 * 명조/바탕 계열 → Noto Serif CJK KR, 고딕/돋움 계열 → Noto Sans CJK KR (안드로이드 기본 탑재).
 */
class HFonts(private val doc: HDoc) {

    val serif: Typeface = loadTtc("/system/fonts/NotoSerifCJK-Regular.ttc", 1, "serif") ?: Typeface.SERIF
    val sans: Typeface = loadTtc("/system/fonts/NotoSansCJK-Regular.ttc", 1, "sans-serif") ?: Typeface.SANS_SERIF

    private val cache = HashMap<Long, Paint>()
    private val defaultShape = CharShape()

    fun charShape(id: Int): CharShape = doc.charShapes[id] ?: defaultShape

    fun faceName(cs: CharShape, lang: Int): String =
        doc.fontFaces[lang].getOrNull(cs.faceIds[lang]) ?: doc.fontFaces[0].getOrNull(cs.faceIds[0]) ?: ""

    fun paint(csId: Int, lang: Int): Paint {
        val key = (csId.toLong() shl 8) or lang.toLong()
        return cache.getOrPut(key) {
            val cs = charShape(csId)
            val face = faceName(cs, lang)
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                typeface = if (isSans(face)) sans else serif
                // 한국어 로캘이면 시스템의 한국어 기본 글꼴(고딕)이 우선 선택되므로 중립 언어로 지정
                textLocale = NEUTRAL_LOCALE
                var size = cs.height / 100f * cs.relSizes[lang] / 100f
                if (cs.supscript || cs.subscript) size *= 0.6f
                textSize = size.coerceAtLeast(1f)
                color = cs.color
                isFakeBoldText = cs.bold || isHeavy(face)
                if (cs.italic) textSkewX = -0.2f
                textScaleX = (cs.ratios[lang] / 100f).coerceIn(0.3f, 3f)
                letterSpacing = cs.spacings[lang] / 100f
            }
        }
    }

    /** 위/아래 첨자, 글자 위치에 따른 기준선 이동량 (pt, 양수 = 위로) */
    fun baselineShift(csId: Int, lang: Int): Float {
        val cs = charShape(csId)
        val size = cs.height / 100f * cs.relSizes[lang] / 100f
        var shift = -size * cs.offsets[lang] / 100f
        if (cs.supscript) shift += size * 0.35f
        if (cs.subscript) shift -= size * 0.15f
        return shift
    }

    companion object {
        val NEUTRAL_LOCALE: java.util.Locale = java.util.Locale.forLanguageTag("zxx")

        private val SANS_KEYS = listOf(
            "고딕", "돋움", "굴림", "맑은", "헤드라인", "견고딕", "그래픽", "산세리프", "나눔스퀘어", "스퀘어",
            "gothic", "dotum", "gulim", "malgun", "arial", "helvetica", "verdana", "tahoma", "segoe",
            "sans", "pretendard", "noto sans", "calibri", "roboto", "one ui"
        )
        private val HEAVY_KEYS = listOf("헤드라인", "견고딕", "견명조", "headline", "black", "heavy")

        fun isSans(face: String): Boolean {
            val f = face.lowercase()
            return SANS_KEYS.any { f.contains(it) }
        }

        fun isHeavy(face: String): Boolean {
            val f = face.lowercase()
            return HEAVY_KEYS.any { f.contains(it) }
        }

        /** 글꼴 파일을 읽는다. (Typeface.Builder 로 만든 글꼴은 없는 글자를 시스템 글꼴에서 찾는다) */
        @Suppress("UNUSED_PARAMETER")
        private fun loadTtc(path: String, index: Int, systemFallback: String): Typeface? = try {
            val f = File(path)
            if (!f.canRead()) null else Typeface.Builder(f).setTtcIndex(index).build()
        } catch (e: Throwable) {
            android.util.Log.w("HFonts", "font load failed: $path", e)
            null
        }
    }
}
