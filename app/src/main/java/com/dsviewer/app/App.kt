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
    }
}
