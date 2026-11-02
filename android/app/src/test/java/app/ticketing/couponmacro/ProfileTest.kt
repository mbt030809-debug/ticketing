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
        assertNull(d.toJob(requireOpenAt = false).error)
        assertTrue(d.toJob(false).job!!.hasResultTexts)
    }

    @Test
    fun naverDefaultsNeedAppSelection() {
        // 패키지는 설치된 앱에서 자동/수동 선택해야 하므로 기본값으로는 시작 불가
        assertNotNull(Presets.NAVER_PLUS.defaults.toJob(requireOpenAt = false).error)
        assertNull(Presets.NAVER_PLUS.defaults.copy(pkg = "x.y").toJob(false).error)
    }
}
