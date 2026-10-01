package com.dsviewer.app

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 시험지 PDF에서 한글·수식 글자를 꺼내 찾을 수 있는지 (기기의 Download에 있는 모의평가 문제지로).
 * 실행: adb shell am instrument -w -e class com.dsviewer.app.SearchTest com.dsviewer.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class SearchTest {
    @Test
    fun findKorean() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        PDFBoxResourceLoader.init(ctx)
        val file = File("/sdcard/Download/math_main_mun_S9YL7W55.pdf")
        assumeTrue(file.canRead())
        val texts = HashMap<Int, PageText>()
        val t0 = System.currentTimeMillis()
        PdfText.extract(file, { false }) { p, t -> texts[p] = t }
        Log.i("SearchTest", "extract ${texts.size} pages in ${System.currentTimeMillis() - t0}ms; p1=${texts[0]?.chars?.take(80)}")
        // 대·점은 PDFBox가 그냥 읽으면 빠지는 글자 (PdfText.toUnicodeMap)
        for (q in listOf("등차수열", "대학수학능력시험", "의 값은? [3점]", "에 대하여", "lim")) {
            val n = PdfText.normalize(q)
            val hits = texts.mapValues { PdfText.find(it.value, n) }.filterValues { it.isNotEmpty() }
            Log.i("SearchTest", "'$q' → ${hits.keys.sorted().map { it + 1 }} ${hits.values.firstOrNull()?.firstOrNull()}")
            assertTrue("'$q' 못 찾음", hits.isNotEmpty())
        }
    }
}
