package com.dsviewer.app

import android.content.Context
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout

/**
 * 뷰어 탭 줄: [ChromeTabBar]와 단추 줄(가로 스크롤).
 * 폭이 넉넉하면(태블릿·가로 화면) 한 줄: 왼쪽 탭, 오른쪽 단추. 단추를 다 보이고 탭이 나머지를 쓴다.
 * 폭이 좁으면(휴대폰 세로, [STACK_BELOW_DP] 미만) 두 줄: 윗줄은 탭만 가득 써서 문서 이름이 보이게 하고,
 * 아랫줄은 단추 줄(넘치면 옆으로 민다). 아랫줄은 문서 바탕색이라 고른 탭이 아래로 이어진다.
 */
class TabActionRow @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {

    /** 한 줄의 높이 (아이콘 크기 옵션에 따라 [ViewerActivity]가 정한다) */
    var rowHeightPx: Int = (48 * resources.displayMetrics.density).toInt()
        set(v) {
            field = v
            requestLayout()
        }

    private var stacked = false

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tabs = getChildAt(0) as? ChromeTabBar
        val actions = getChildAt(1) as? ViewGroup
        val inner = actions?.getChildAt(0)
        if (tabs == null || actions == null || inner == null || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val total = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        val narrow = resources.configuration.screenWidthDp in 1 until STACK_BELOW_DP
        if (narrow != stacked || orientation != (if (narrow) VERTICAL else HORIZONTAL)) {
            stacked = narrow
            orientation = if (narrow) VERTICAL else HORIZONTAL
            actions.setBackgroundColor(if (narrow) context.getColor(R.color.doc_bg) else 0)
            // 두 줄일 때 단추가 폭보다 적으면 오른쪽에 붙인다 (넘치면 평소처럼 옆으로 민다)
            (actions as? HorizontalScrollView)?.isFillViewport = narrow
            (inner as? LinearLayout)?.gravity = Gravity.CENTER_VERTICAL or (if (narrow) Gravity.END else Gravity.START)
        }
        val tlp = tabs.layoutParams as LayoutParams
        val alp = actions.layoutParams as LayoutParams
        tlp.height = rowHeightPx
        alp.height = rowHeightPx
        if (narrow) {
            tlp.width = LayoutParams.MATCH_PARENT
            tlp.weight = 0f
            alp.width = LayoutParams.MATCH_PARENT
        } else {
            tlp.width = 0
            tlp.weight = 1f
            // 단추 줄이 다 보이려면 얼마나 필요한지
            inner.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
            val need = inner.measuredWidth + actions.paddingLeft + actions.paddingRight
            val room = (total - tabs.reserveWidth(total)).coerceAtLeast(0)
            alp.width = minOf(need, room)
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }

    private companion object {
        /** 이보다 좁은 화면(dp)이면 탭 줄과 단추 줄을 두 줄로 */
        const val STACK_BELOW_DP = 600
    }
}
