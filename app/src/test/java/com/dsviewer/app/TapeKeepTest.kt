package com.dsviewer.app

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 다 그린 테이프를 남길지·글자 줄을 어디서 찾을지·어느 점이 글자인지 */
class TapeKeepTest {

    private val d = 2f

    // ================= 남길지 =================

    @Test fun rectTapeNeedsSixDpOnItsShortSide() {
        assertTrue(TapeFit.keepRect(100f, 3f, 4f, d))         // 짧은 쪽 3pt × 4 = 12px ≥ 12px
        assertFalse(TapeFit.keepRect(100f, 2.9f, 4f, d))
        assertFalse(TapeFit.keepRect(2.9f, 100f, 4f, d))      // 가로가 짧아도
        assertTrue(TapeFit.keepRect(30f, 30f, 1f, d))
    }

    @Test fun rectTapeScalesWithZoom() {
        // 같은 크기도 확대해서 그리면 남는다
        assertFalse(TapeFit.keepRect(5f, 5f, 1f, d))
        assertTrue(TapeFit.keepRect(5f, 5f, 3f, d))
    }

    @Test fun lineTapeNeedsTwoPointsAndEightDpLength() {
        assertTrue(TapeFit.keepLine(2, 4f, 4f, d))            // 4pt × 4 = 16px ≥ 16px
        assertFalse(TapeFit.keepLine(2, 3.9f, 4f, d))
        assertFalse(TapeFit.keepLine(1, 1000f, 4f, d))        // 점 하나는 아무리 길어도(길이 값이 이상해도) 버린다
        assertFalse(TapeFit.keepLine(0, 0f, 4f, d))
    }

    // ================= 글자 점 =================

    @Test fun darkPixelsAreBelowLuminance160() {
        assertTrue(TapeFit.isDark(0, 0, 0))
        assertFalse(TapeFit.isDark(255, 255, 255))
        assertTrue(TapeFit.isDark(100, 100, 100))
        assertFalse(TapeFit.isDark(160, 160, 160))            // 경계: 160은 글자가 아님
        assertTrue(TapeFit.isDark(159, 159, 159))
    }

    @Test fun luminanceWeightsGreenMostAndBlueLeast() {
        // 순수한 색의 밝기: 파랑 29, 빨강 76, 초록 149 (모두 160 미만 = 글자 점)
        assertTrue(TapeFit.isDark(0, 0, 255))
        assertTrue(TapeFit.isDark(255, 0, 0))
        assertTrue(TapeFit.isDark(0, 255, 0))
        // 초록이 섞이면 금방 밝아져 글자가 아니다: 노랑 225, 청록 188, 자홍 105
        assertFalse(TapeFit.isDark(255, 255, 0))
        assertFalse(TapeFit.isDark(0, 255, 255))
        assertTrue(TapeFit.isDark(255, 0, 255))
    }

    // ================= 찾는 영역 =================

    @Test fun fitRegionGrowsByFortyAroundTheTape() {
        val r = TapeFit.fitRegion(100f, 200f, 300f, 210f, 600f, 800f)
        assertEquals(listOf(60f, 160f, 340f, 250f), r.toList())
    }

    @Test fun fitRegionStaysInsideThePage() {
        val r = TapeFit.fitRegion(10f, 5f, 590f, 795f, 600f, 800f)
        assertEquals(listOf(0f, 0f, 600f, 800f), r.toList())
    }

    @Test fun fitBitmapSizeLimits() {
        assertTrue(TapeFit.fitBitmapOk(100, 100))
        assertTrue(TapeFit.fitBitmapOk(2000, 4000))           // 8,000,000 딱 맞으면 통과
        assertFalse(TapeFit.fitBitmapOk(2001, 4000))
        assertFalse(TapeFit.fitBitmapOk(0, 100))
        assertFalse(TapeFit.fitBitmapOk(100, 0))
        assertFalse(TapeFit.fitBitmapOk(-5, 100))
        assertFalse(TapeFit.fitBitmapOk(100000, 100000))      // Int 곱셈이 넘쳐도 Long으로 따진다
        assertFalse(TapeFit.fitBitmapOk(65536, 65536))        // Int로 곱하면 넘쳐서 0이 되는 크기
    }

    // ================= 옛 계산과 대조 =================

    @Test fun darknessMatchesTheOldCodeOnAllGrays() {
        val rnd = Random(5)
        repeat(20_000) {
            val r = rnd.nextInt(256); val g = rnd.nextInt(256); val b = rnd.nextInt(256)
            val lum = (r * 299 + g * 587 + b * 114) / 1000
            assertEquals(lum < 160, TapeFit.isDark(r, g, b))
        }
    }
}
