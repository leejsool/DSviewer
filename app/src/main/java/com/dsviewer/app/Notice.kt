package com.dsviewer.app

import android.app.Activity
import android.view.View
import com.google.android.material.snackbar.Snackbar

/**
 * 되돌릴 수 있는 일(쪽 지우기·순서 바꾸기·테이프 지우기 …)을 한 뒤 문서 화면 아래에 잠깐 뜨는 알림.
 * 사라지기 전에 '실행 취소'를 누르면 바로 되돌린다. 한 번에 하나만 뜨고, 새 알림이 오면 앞의 것은 거둔다.
 */
internal object Notice {
    /** 알림이 떠 있는 시간 (밀리초) */
    private const val SHOW_MS = 6000
    private var bar: Snackbar? = null

    /** [message]를 보이고 '[action]'(기본 '실행 취소')을 누르면 [onAction]을 한다. 문서 화면이 없는 화면이면 아무것도 하지 않는다 */
    fun show(activity: Activity, message: String, action: String = "실행 취소", onAction: () -> Unit) {
        val host = activity.findViewById<View>(R.id.docFrame) ?: return
        bar?.dismiss()
        val shown = Snackbar.make(host, message, SHOW_MS).setAction(action) { onAction() }
        shown.addCallback(object : Snackbar.Callback() {
            // 화면을 붙들고 있지 않게 닫히면 놓는다 (새 알림으로 바뀐 경우엔 새것을 지우지 않는다)
            override fun onDismissed(transientBottomBar: Snackbar?, event: Int) {
                if (bar === transientBottomBar) bar = null
            }
        })
        bar = shown
        shown.show()
    }
}
