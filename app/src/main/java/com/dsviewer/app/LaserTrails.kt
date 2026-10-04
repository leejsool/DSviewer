package com.dsviewer.app

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.roundToInt

/**
 * 레이저 포인터의 자국: 쪽 좌표로 그리고 저장하지 않는다. 마지막 획을 떼고 [fadeMs]가 지나면 한꺼번에 사라진다.
 */
internal class LaserTrails(
    private val view: View,
    private val density: Float,
    /** 지금 화면 배율 (쪽 좌표 1이 화면 몇 px인지) */
    private val scale: () -> Float,
    private val fadeMs: () -> Long,
) {
    private val strokes = ArrayList<Pair<Int, Stroke>>()
    private var alpha = 1f
    private var fadeAnim: ValueAnimator? = null
    private val fadeRunnable = Runnable { startFade() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val path = Path()
    private var blur: BlurMaskFilter? = null
    private var blurRadius = -1f

    /** 사라지는 중인 (또는 남아 있는) 자국 중 [page]쪽의 것을 그린다 */
    fun drawFaded(canvas: Canvas, page: Int) {
        for ((p, st) in strokes) if (p == page) draw(canvas, st, alpha)
    }

    /**
     * 레이저 획: 번진 빛 + 색 선 + 가운데 흰 심. 캔버스는 쪽 좌표라서
     * 굵기(dp)를 지금 배율로 나눠 화면에서 늘 같은 굵기로 보이게 한다
     */
    fun draw(c: Canvas, st: Stroke, a: Float) {
        if (st.count == 0 || a <= 0f) return
        path.reset()
        path.moveTo(st.x(0), st.y(0))
        if (st.count == 1) path.lineTo(st.x(0) + 0.01f, st.y(0))
        else for (k in 1 until st.count) path.lineTo(st.x(k), st.y(k))
        val w = st.width * density / scale()
        val radius = w * 0.9f
        if (radius != blurRadius) {
            blur = BlurMaskFilter(radius, BlurMaskFilter.Blur.NORMAL)
            blurRadius = radius
        }
        paint.maskFilter = blur
        paint.color = st.color
        paint.alpha = (140 * a).roundToInt()
        paint.strokeWidth = w * 2.6f
        c.drawPath(path, paint)
        paint.maskFilter = null
        paint.color = st.color
        paint.alpha = (255 * a).roundToInt()
        paint.strokeWidth = w
        c.drawPath(path, paint)
        paint.color = Color.WHITE
        paint.alpha = (210 * a).roundToInt()
        paint.strokeWidth = w * 0.35f
        c.drawPath(path, paint)
    }

    /** 레이저를 새로 긋기 시작하면 사라지던 것도 다시 또렷하게 */
    fun hold() {
        view.removeCallbacks(fadeRunnable)
        fadeAnim?.cancel()
        fadeAnim = null
        alpha = 1f
    }

    /** 획을 뗐다: 남겨 두었다가 [fadeMs] 뒤 사라지게 한다 (필기에는 넣지 않는다) */
    fun release(page: Int, st: Stroke) {
        strokes.add(page to st)
        view.removeCallbacks(fadeRunnable)
        view.postDelayed(fadeRunnable, fadeMs())
    }

    private fun startFade() {
        fadeAnim?.cancel()
        fadeAnim = ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 400
            addUpdateListener {
                alpha = it.animatedValue as Float
                view.invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                private var canceled = false
                override fun onAnimationCancel(animation: Animator) { canceled = true }
                override fun onAnimationEnd(animation: Animator) {
                    if (canceled) return
                    strokes.clear()
                    alpha = 1f
                    fadeAnim = null
                    view.invalidate()
                }
            })
            start()
        }
    }

    /** 남은 자국을 바로 없앤다 (쪽을 넘기거나 스크롤할 때) */
    fun clear() {
        hold()
        strokes.clear()
        view.invalidate()
    }

    /** 창이 닫힐 때: 예약한 사라지기와 애니메이션을 멈춘다 */
    fun cancel() {
        view.removeCallbacks(fadeRunnable)
        fadeAnim?.cancel()
    }
}
