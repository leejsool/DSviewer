package com.dsviewer.app

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.dsviewer.app.conv.DocConvert
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 기기의 /sdcard/Download/convtest 안 파일(pptx·docx·txt·그림 …)을 PDF로 바꾸고 쪽마다 PNG로 그린다.
 * 결과는 앱 캐시 폴더 convTest/<파일 이름>/ 에 (adb로 꺼내 눈으로 확인).
 * 실행: gradlew installDebug installDebugAndroidTest 후
 *   adb shell am instrument -w -e class com.dsviewer.app.ConvertTest com.dsviewer.app.test/androidx.test.runner.AndroidJUnitRunner
 */
@RunWith(AndroidJUnit4::class)
class ConvertTest {
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val outDir = File(ctx.cacheDir, "convTest").apply { deleteRecursively(); mkdirs() }

    /** 기기 글꼴(한글 변환에 쓰는 Noto CJK)의 글자 폭 (글자 크기 비율) */
    @Test
    fun fontMetrics() {
        val tf = android.graphics.Typeface.Builder(File("/system/fonts/NotoSansCJK-Regular.ttc")).setTtcIndex(1).build()
        val p = android.graphics.Paint().apply { typeface = tf; textSize = 100f; textLocale = java.util.Locale.forLanguageTag("zxx") }
        val sb = StringBuilder()
        for (t in listOf("가", "한", " ", "a", "1", ".", "A", "그림 아래 문단입니다.")) sb.append("'$t' ${p.measureText(t) / 100f}\n")
        File(outDir.parentFile, "fontMetrics.txt").writeText(sb.toString())
    }

    @Test
    fun convertAll() {
        PDFBoxResourceLoader.init(ctx)
        val src = File("/sdcard/Download/convtest")
        val files = src.listFiles()?.filter { it.isFile }.orEmpty()
        assertTrue("no test files in $src", files.isNotEmpty())
        val report = StringBuilder()
        for (f in files.sortedBy { it.name }) {
            val t0 = System.currentTimeMillis()
            try {
                val type = FileUtil.detect(f.name, null, f)
                val pdf = DocConvert.toPdf(ctx, f, type, f.name)
                val dir = File(outDir, f.nameWithoutExtension.take(20) + "_" + f.extension).apply { mkdirs() }
                pdf.copyTo(File(dir, "out.pdf"), overwrite = true)
                val n = PdfPages.pageCount(pdf)
                for (i in 0 until minOf(n, 30)) {
                    val bmp = Thumbs.renderPdf(pdf, i, withInk = true, size = 1400) ?: continue
                    File(dir, "p%02d.png".format(i + 1)).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bmp.recycle()
                }
                report.append("${f.name}: $type, $n pages, ${System.currentTimeMillis() - t0} ms\n")
            } catch (e: Throwable) {
                report.append("${f.name}: FAILED ${e.javaClass.simpleName}: ${e.message}\n${e.stackTrace.take(8).joinToString("\n")}\n")
            }
        }
        File(outDir, "report.txt").writeText(report.toString())
        println(report)
    }
}
