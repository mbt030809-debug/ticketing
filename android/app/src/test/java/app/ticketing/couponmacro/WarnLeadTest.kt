package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WarnLeadTest {
    @Test
    fun choicesGoUpToTenMinutes() {
        // 요청: 알림을 1분이 아닌 10분까지 설정
        assertEquals(600, WarnLead.OPTIONS_SEC.max())
        assertTrue(WarnLead.OPTIONS_SEC.contains(60))
        assertEquals(WarnLead.OPTIONS_SEC.sorted(), WarnLead.OPTIONS_SEC)
        assertEquals(600, WarnLead.clamp(600))
        assertEquals(600, WarnLead.clamp(99_999)) // 10분이 상한
        assertEquals(20, WarnLead.clamp(5))
        assertEquals(60, WarnLead.clamp(null))
    }

    @Test
    fun labelsAreInMinutes() {
        assertEquals("30초 전", WarnLead.label(30))
        assertEquals("1분 전", WarnLead.label(60))
        assertEquals("5분 전", WarnLead.label(300))
        assertEquals("10분 전", WarnLead.label(600))
        assertEquals("1분 30초 전", WarnLead.label(90))
    }

    @Test
    fun oldCustomValuesMapToTheNearestChoice() {
        assertEquals(1, WarnLead.indexFor(60))
        assertEquals(5, WarnLead.indexFor(600))
        assertEquals(WarnLead.OPTIONS_SEC.indexOf(120), WarnLead.indexFor(100))   // 100초 -> 2분
        assertEquals(WarnLead.OPTIONS_SEC.indexOf(60), WarnLead.indexFor(70))     // 70초 -> 1분
        assertEquals(WarnLead.OPTIONS_SEC.indexOf(60), WarnLead.indexFor(null))
        assertEquals(60, WarnLead.secAt(1))
        assertEquals(60, WarnLead.secAt(99)) // 범위 밖은 기본값
    }

    @Test
    fun earphonesAreWiredBluetoothAndUsbHeadsetsButNotTheSpeaker() {
        for (t in listOf(3, 4, 7, 8, 22, 23, 26)) assertTrue("type=$t", AudioRoute.isEarphone(t))
        for (t in listOf(1 /*내장 이어피스*/, 2 /*스피커*/, 11, 12, 15)) assertFalse("type=$t", AudioRoute.isEarphone(t))
        assertEquals("유선 이어폰", AudioRoute.typeName(3))
        assertEquals("블루투스 이어폰", AudioRoute.typeName(8))
    }
}
