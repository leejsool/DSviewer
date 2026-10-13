package com.dsviewer.app

import android.app.Activity
import android.view.View
import com.google.android.material.snackbar.Snackbar

/**
 * 뷰어의 스낵바(실행 취소 알림·'끌어서 고르세요' 안내 …)를 만든다.
 *
 * 그냥 Snackbar.make(문서 칸)을 쓰면 화면 맨 아래에 붙어서, 그 아래 있는 툴바(아래·왼쪽·오른쪽에 붙인)를 가린다.
 * 그래서 문서 칸 안의 [R.id.snackHost]에 달아 문서 칸 아래쪽에만 뜨게 하고,
 * 쪽 번호·도형 줄·지우개 옵션 줄처럼 문서 위에 겹쳐 뜬 아래 묶음([R.id.bottomOverlay])이 있으면 그 위로 올린다.
 */
internal object SnackHost {
    fun make(activity: Activity, message: CharSequence, duration: Int): Snackbar {
        val host = activity.findViewById<View>(R.id.snackHost) ?: activity.findViewById(R.id.docFrame)
        val bar = Snackbar.make(host, message, duration)
        activity.findViewById<View>(R.id.bottomOverlay)?.let { bar.anchorView = it }
        return bar
    }
}
