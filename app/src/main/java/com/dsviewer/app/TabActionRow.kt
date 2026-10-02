package com.dsviewer.app

import android.content.Context
import android.util.AttributeSet
import android.view.ViewGroup
import android.widget.LinearLayout

/**
 * 뷰어 탭 줄: 왼쪽 [ChromeTabBar], 오른쪽 단추 줄(가로 스크롤).
 * 자리가 넉넉하면 단추를 다 보이고 탭이 나머지를 쓴다.
 * 좁으면(휴대폰 세로) 탭 줄이 [ChromeTabBar.reserveWidth]만큼은 먼저 갖고 (고른 탭은 늘 보이게),
 * 단추 줄은 남은 자리에서 옆으로 밀어 본다.
 */
class TabActionRow @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : LinearLayout(ctx, attrs) {

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val tabs = getChildAt(0) as? ChromeTabBar
        val actions = getChildAt(1) as? ViewGroup
        val inner = actions?.getChildAt(0)
        if (tabs == null || actions == null || inner == null || MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val total = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        // 단추 줄이 다 보이려면 얼마나 필요한지
        inner.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED))
        val need = inner.measuredWidth + actions.paddingLeft + actions.paddingRight
        val room = (total - tabs.reserveWidth(total)).coerceAtLeast(0)
        val lp = actions.layoutParams
        val w = minOf(need, room)
        if (lp.width != w) lp.width = w
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
    }
}
