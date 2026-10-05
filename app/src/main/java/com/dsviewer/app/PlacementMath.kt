package com.dsviewer.app

import kotlin.math.hypot
import kotlin.math.max

/**
 * 글 상자·붙여넣기 같은 '어디에 어떤 모양으로 놓을지' 계산. 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다.
 */
internal object PlacementMath {

    /** 왼쪽 위 ([left], [top]), 가로 [w] 세로 [h]인 곧은 상자의 네 모서리: [x,y] × 4 (왼쪽 위부터 시계 방향) */
    fun upright(left: Float, top: Float, w: Float, h: Float): FloatArray =
        floatArrayOf(left, top, left + w, top, left + w, top + h, left, top + h)

    /**
     * 글을 고칠 때 새 글 상자: 옛 상자의 왼쪽 위 모서리·기울기·배율은 그대로 두고 새 글 크기([newW]×[newH], 글 단위)에 맞춰 다시 잡는다.
     * [old]는 옛 상자의 네 모서리 (왼쪽 위, 오른쪽 위, 오른쪽 아래, 왼쪽 아래 순서의 [x,y] × 4), [oldW]×[oldH]는 옛 글 크기.
     * 가로 방향(모서리 0→1)과 세로 방향(0→3)이 각자 배율을 가지므로 늘려 놓은 상자도 그 비율이 이어진다.
     */
    fun retargetTextBox(old: FloatArray, oldW: Float, oldH: Float, newW: Float, newH: Float): FloatArray {
        val x0 = old[0]; val y0 = old[1]
        val ax = old[2] - x0; val ay = old[3] - y0
        val bx = old[6] - x0; val by = old[7] - y0
        val aLen = max(hypot(ax, ay), 0.01f)
        val bLen = max(hypot(bx, by), 0.01f)
        val kx = aLen / oldW
        val ky = bLen / oldH
        val w = newW * kx
        val h = newH * ky
        val ux = ax / aLen * w; val uy = ay / aLen * w
        val vx = bx / bLen * h; val vy = by / bLen * h
        return floatArrayOf(x0, y0, x0 + ux, y0 + uy, x0 + ux + vx, y0 + uy + vy, x0 + vx, y0 + vy)
    }

    /**
     * 그림 하나([clipW]×[clipH])를 쪽([pageW]×[pageH])에 붙일 때 곱할 배율: 쪽 안에 [FIT_FRACTION]까지만 차지하게 줄이고, 작으면 그대로(1)
     */
    fun pasteFit(clipW: Float, clipH: Float, pageW: Float, pageH: Float): Float {
        if (clipW <= 0f || clipH <= 0f) return 1f
        return minOf(1f, pageW * FIT_FRACTION / clipW, pageH * FIT_FRACTION / clipH)
    }

    /** 큰 그림을 쪽에 붙일 때 쪽 가로·세로의 이 비율 안에 들어오게 줄인다 (손잡이·선택 막대 자리가 남도록) */
    const val FIT_FRACTION = 0.9f

    /**
     * 복사해 둔 획 묶음(크기 [clipW]×[clipH])을 붙일 자리: 보이는 화면 가운데에 놓되 쪽 밖으로 나가지 않게 한다.
     * 돌려주는 값은 쪽 좌표의 왼쪽 위 [x, y].
     * 화면 가운데는 (스크롤 [offX], [offY], 화면 [viewW]×[viewH], 배율 [scale])에서 문서 좌표로 구하고 쪽 위치 ([pageLeft], [pageTop])를 빼 쪽 좌표로 바꾼다
     */
    fun pasteOrigin(
        offX: Float, offY: Float, viewW: Float, viewH: Float, scale: Float,
        pageLeft: Float, pageTop: Float, pageW: Float, pageH: Float,
        clipW: Float, clipH: Float,
    ): FloatArray {
        val centerDoc = (offY + viewH / 2f) / scale
        val cx = (offX + viewW / 2f) / scale - pageLeft
        val cy = (centerDoc - pageTop).coerceIn(0f, pageH)
        val x0 = (cx - clipW / 2).coerceIn(0f, max(0f, pageW - clipW))
        val y0 = (cy - clipH / 2).coerceIn(0f, max(0f, pageH - clipH))
        return floatArrayOf(x0, y0)
    }
}
