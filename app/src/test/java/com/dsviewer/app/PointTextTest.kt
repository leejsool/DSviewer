package com.dsviewer.app

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

/** 필기 점 글을 빠르게 읽는 길이 옛 방식(split 뒤 toFloat)과 같은 값을 주는지 */
class PointTextTest {

    /** 옛 방식 그대로 */
    private fun legacy(src: String): FloatArray {
        val out = ArrayList<Float>()
        for (pt in src.split(';')) {
            val v = pt.split(',')
            if (v.size == 3) { out.add(v[0].toFloat()); out.add(v[1].toFloat()); out.add(v[2].toFloat()) }
        }
        return out.toFloatArray()
    }

    @Test fun singlePoint() {
        assertArrayEquals(floatArrayOf(12.5f, 3.25f, 0.5f), PointText.parse("12.5,3.25,0.5")!!, 0f)
    }

    @Test fun severalPointsWithNegativesAndIntegers() {
        val src = "0.0,0.0,1.0;-1.5,200.25,0.75;300.0,-0.01,0.0;7,8,9"
        assertArrayEquals(legacy(src), PointText.parse(src)!!, 0f)
    }

    @Test fun matchesTheOldWayOnManyRandomStrokes() {
        val rnd = Random(7)
        repeat(300) {
            val n = 1 + rnd.nextInt(200)
            val src = (0 until n).joinToString(";") {
                // 앱이 쓰는 모양: 소수 둘째 자리까지 반올림한 Float.toString
                fun r2(v: Float) = ((v * 100f).toInt() / 100f).toString()
                "${r2(rnd.nextFloat() * 700f - 20f)},${r2(rnd.nextFloat() * 1000f - 20f)},${r2(rnd.nextFloat())}"
            }
            assertArrayEquals(src, legacy(src), PointText.parse(src)!!, 0f)
        }
    }

    @Test fun veryLargeAndVerySmallValuesKeepWorking() {
        // 큰 쪽 좌표와 0.01 같은 작은 값
        val src = "9999.99,0.01,0.01;123456.78,0.0,1.0"
        assertArrayEquals(legacy(src), PointText.parse(src)!!, 0f)
    }

    @Test fun badShapesFallBackToTheOldWay() {
        assertNull(PointText.parse(""))
        assertNull(PointText.parse("1,2"))             // 값이 둘뿐인 점
        assertNull(PointText.parse("1,2,3;"))          // 끝의 빈 점
        assertNull(PointText.parse("1,2,3;4,5"))
        assertNull(PointText.parse("1,2,x"))           // 숫자가 아님
        assertNull(PointText.parse("1.0E-4,2,3"))      // 지수 표기
        assertNull(PointText.parse("1,2,3,4"))         // 값이 넷
    }

    @Test fun pointCountMatches() {
        val a = PointText.parse("1,2,3;4,5,6;7,8,9")
        assertNotNull(a)
        assertEquals(9, a!!.size)
    }
}
