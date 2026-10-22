package com.dsviewer.app

import android.app.Application
import com.dsviewer.app.hwp.EqRenderer
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        PDFBoxResourceLoader.init(applicationContext)
        EqRenderer.init(assets)
        TextFont.init(this)
        CrashLog.install(this)
        // 처음 문서를 열거나 저장할 때 PDFBox 클래스를 읽느라 느려지지 않게 뒤에서 미리 불러 둔다
        Thread({ PdfInk.warmUp() }, "pdfbox-warmup").apply { priority = Thread.MIN_PRIORITY; isDaemon = true }.start()
    }
}
