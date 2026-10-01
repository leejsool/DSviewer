package com.dsviewer.app

import android.app.Activity
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.view.WindowInsets

/**
 * 알림의 '이 화면 가져오기'가 띄우는 보이지 않는 활동. 알림에서 활동을 띄워야 알림창이 저절로 닫힌다.
 * 화면 전체를 덮는 이 창에서 지금 보이는 상태 표시줄·내비게이션 줄의 두께를 읽어 [CaptureService]에 넘기고 바로 닫는다.
 * 자기 작업(taskAffinity 없음)으로 떠서, 닫히면 바로 전에 보던 앱이 그대로 보인다
 */
class ShotActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!CaptureService.running) {
            finishQuietly()
            return
        }
        window.decorView.post {
            CaptureService.shoot(this, bars())
            finishQuietly()
        }
    }

    private fun bars(): Rect {
        val wi = window.decorView.rootWindowInsets ?: return Rect()
        if (Build.VERSION.SDK_INT >= 30) {
            val types = WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars() or
                WindowInsets.Type.tappableElement() or WindowInsets.Type.displayCutout()
            val i = wi.getInsets(types)
            return Rect(i.left, i.top, i.right, i.bottom)
        }
        @Suppress("DEPRECATION")
        return Rect(wi.systemWindowInsetLeft, wi.systemWindowInsetTop, wi.systemWindowInsetRight, wi.systemWindowInsetBottom)
    }

    @Suppress("DEPRECATION")
    private fun finishQuietly() {
        finish()
        overridePendingTransition(0, 0)
    }
}
