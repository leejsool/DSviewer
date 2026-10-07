package com.dsviewer.app

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 선대칭: 대칭축(점 A에서 B로 가는 직선)에 대한 반사, 긋는 축 맞추기, 축을 기준으로 접기의 계산.
 * 화면·Android 클래스를 모르는 순수 계산이라 JVM 단위 시험으로 확인한다. 좌표는 쪽 좌표(y는 아래로).
 */
internal object SymmetryMath {

    /** 이보다 짧게 그은 축은 버린다 (쪽 좌표, pt) */
    const val MIN_AXIS = 16f

    /** 긋는 방향이 가로·세로·±45°에서 이만큼(도) 안이면 그 방향으로 맞춘다 */
    private const val SNAP_DEG = 6.0

    /**
     * 처음 누른 점 ([x0], [y0])에서 지금 점 ([x1], [y1])까지 그은 선을 가로·세로·±45°에 가까우면 그 방향으로 맞춘다
     * (시작점과 길이는 그대로). 돌려주는 값은 [Ax, Ay, Bx, By]
     */
    fun snapAxis(x0: Float, y0: Float, x1: Float, y1: Float): FloatArray {
        val len = hypot((x1 - x0).toDouble(), (y1 - y0).toDouble())
        if (len == 0.0) return floatArrayOf(x0, y0, x1, y1)
        val deg = Math.toDegrees(atan2((y1 - y0).toDouble(), (x1 - x0).toDouble()))
        val snapped = (deg / 45.0).roundToInt() * 45.0
        if (abs(deg - snapped) > SNAP_DEG) return floatArrayOf(x0, y0, x1, y1)
        val r = Math.toRadians(snapped)
        return floatArrayOf(x0, y0, (x0 + len * cos(r)).toFloat(), (y0 + len * sin(r)).toFloat())
    }

    /** 축이 쓸 만큼 긴가 */
    fun longEnough(axis: FloatArray): Boolean = hypot((axis[2] - axis[0]).toDouble(), (axis[3] - axis[1]).toDouble()) >= MIN_AXIS

    /** 점 ([x], [y])을 축 (A=[ax],[ay] → B=[bx],[by])에 대해 반사한 점 [x', y'] */
    fun reflect(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): FloatArray {
        val dx = (bx - ax).toDouble()
        val dy = (by - ay).toDouble()
        val l2 = dx * dx + dy * dy
        if (l2 == 0.0) return floatArrayOf(x, y)
        val t = ((x - ax) * dx + (y - ay) * dy) / l2
        val fx = ax + t * dx
        val fy = ay + t * dy
        return floatArrayOf((2 * fx - x).toFloat(), (2 * fy - y).toFloat())
    }

    /** 점이 축의 어느 쪽인가: 양수·음수는 서로 반대쪽, 0은 축 위 (A→B를 보고 왼쪽·오른쪽을 가르는 외적) */
    fun side(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Double =
        (bx - ax).toDouble() * (y - ay) - (by - ay).toDouble() * (x - ax)

    /** 점의 쪽 부호 (+1, -1, 0) */
    fun sideSign(x: Float, y: Float, ax: Float, ay: Float, bx: Float, by: Float): Int =
        side(x, y, ax, ay, bx, by).let { if (it > 0) 1 else if (it < 0) -1 else 0 }

    /**
     * 점 (x, y, 필압) [count]개로 된 획 [data]를 축으로 접는다: 축의 [keep] 쪽(+1·-1, [sideSign]과 같은 부호)이 남고
     * 반대쪽 부분은 반사되어 이쪽으로 넘어온다. 축을 가로지르는 곳에는 교점을 넣어 끊기지 않게 잇는다.
     * 접을 부분이 없으면(모두 남길 쪽에 있으면) null, 아니면 새 점들 (x, y, 필압)
     */
    fun fold(data: FloatArray, count: Int, ax: Float, ay: Float, bx: Float, by: Float, keep: Int): FloatArray? {
        if (count == 0 || keep == 0) return null
        val out = ArrayList<Float>(count * 3 + 12)
        var changed = false
        var px = 0f
        var py = 0f
        var pp = 0f
        var ps = 0.0
        for (i in 0 until count) {
            val x = data[i * 3]
            val y = data[i * 3 + 1]
            val p = data[i * 3 + 2]
            val s = side(x, y, ax, ay, bx, by)
            if (i > 0 && ps * s < 0) {
                // 앞 점과 이 점 사이에서 축을 가로지른다: 교점은 축 위라 반사해도 제자리
                val t = (ps / (ps - s)).toFloat()
                out.add(px + (x - px) * t)
                out.add(py + (y - py) * t)
                out.add(pp + (p - pp) * t)
            }
            if (s * keep < 0) {
                val r = reflect(x, y, ax, ay, bx, by)
                out.add(r[0]); out.add(r[1]); out.add(p)
                changed = true
            } else {
                out.add(x); out.add(y); out.add(p)
            }
            px = x; py = y; pp = p; ps = s
        }
        return if (changed) out.toFloatArray() else null
    }
}
