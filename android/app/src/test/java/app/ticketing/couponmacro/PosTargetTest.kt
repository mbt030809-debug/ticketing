package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PosTargetTest {
    @Test
    fun roundTripsThroughEntryString() {
        val p = PosTarget("오후 5시 오픈", 35, 120, 0.25f, 0.55f)
        val e = p.toEntry()
        assertEquals("위치:오후 5시 오픈|35,120|0.2500,0.5500", e)
        assertEquals(p, PosTarget.parse(e))
        assertTrue(PosTarget.isPos(e))
    }

    @Test
    fun entryWithoutAnchorUsesScreenFractions() {
        val p = PosTarget("", 0, 0, 0.3f, 0.7f)
        val back = PosTarget.parse(p.toEntry())!!
        assertEquals("", back.anchor)
        assertEquals(0.3f, back.fx, 0.0001f)
        assertEquals(0.7f, back.fy, 0.0001f)
        assertTrue(back.describe().contains("화면 비율"))
    }

    @Test
    fun separatorsInAnchorAreSanitizedSoTheLineStaysParsable() {
        val p = PosTarget("a|b;c\nd", 1, 2, 0.1f, 0.2f)
        val back = PosTarget.parse(p.toEntry())
        assertNotNull(back)
        assertEquals("a b c d", back!!.anchor)
        // 쿠폰 목록(줄/; 구분)에 넣어도 한 항목으로 유지
        assertEquals(1, Parse.coupons(p.toEntry()).size)
    }

    @Test
    fun legacyTrailingMarkerFromOlderBuildsIsIgnored() {
        // 이전 빌드가 붙이던 끝의 |o 표시가 있어도 같은 위치로 읽힌다 (저장된 쿠폰 목록 호환)
        val p = PosTarget.parse("위치:오후 5시 오픈|35,120|0.2500,0.5500|o")
        assertEquals(PosTarget("오후 5시 오픈", 35, 120, 0.25f, 0.55f), p)
        assertEquals("위치:오후 5시 오픈|35,120|0.2500,0.5500", p!!.toEntry())
    }

    @Test
    fun rejectsMalformedAndPlainTextEntries() {
        assertNull(PosTarget.parse("5,000원 할인"))
        assertNull(PosTarget.parse("위치:앵커|1,2"))
        assertNull(PosTarget.parse("위치:앵커|a,b|0.1,0.2"))
        assertNull(PosTarget.parse("위치:앵커|1,2|x,y"))
        assertFalse(PosTarget.isPos("5,000원 할인"))
    }
}
