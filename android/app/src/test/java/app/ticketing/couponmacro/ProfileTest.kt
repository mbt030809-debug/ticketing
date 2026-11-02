package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProfileTest {
    @Test
    fun profileIdsAreUniqueAndLookupFallsBack() {
        val ids = Presets.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(Presets.ALL[0], Presets.byId("없는-id"))
        assertEquals(Presets.NAVER_PLUS, Presets.byId("naverplus"))
    }

    @Test
    fun coupangDefaultsAreStartable() {
        val d = Presets.COUPANG.defaults
        assertEquals("com.coupang.mobile", d.pkg)
        // 받을 쿠폰은 사용자가 정해야 하므로 기본값만으로는 시작 불가
        assertNotNull(d.toJob(requireOpenAt = false).error)
        val withCoupon = d.copy(coupons = "5,000원")
        assertNull(withCoupon.toJob(false).error)
        assertTrue(withCoupon.toJob(false).job!!.hasResultTexts)
    }

    @Test
    fun naverDefaultsNeedAppSelection() {
        // 패키지는 설치된 앱에서 자동/수동 선택해야 하므로 기본값으로는 시작 불가
        val d = Presets.NAVER_PLUS.defaults.copy(coupons = "5,000원")
        assertNotNull(d.toJob(requireOpenAt = false).error)
        assertNull(d.copy(pkg = "x.y").toJob(false).error)
    }
}
