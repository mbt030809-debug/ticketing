package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class JobTest {
    @Test
    fun openAtIsKst() {
        val expected = Instant.parse("2026-11-05T01:00:00Z").toEpochMilli()
        assertEquals(expected, Parse.openAt("2026-11-05 10:00:00"))
        assertEquals(expected, Parse.openAt("2026-11-05 10:00"))
    }

    @Test
    fun listSplitsOnCommaAndNewline() {
        assertEquals(listOf("쿠폰 받기", "다운로드", "x"), Parse.list("쿠폰 받기, 다운로드 ,,\nx"))
        assertTrue(Parse.list("  , ").isEmpty())
    }

    @Test
    fun stepsOptionalPrefix() {
        val s = Parse.steps("혜택\n?팝업닫기\n\n  ?  \n쿠폰함")
        assertEquals(listOf(Step("혜택", false), Step("팝업닫기", true), Step("쿠폰함", false)), s)
    }

    @Test
    fun toJobRequiresPackageAndTarget() {
        assertNotNull(FormState(pkg = "", targets = "x").toJob(false).error)
        assertNotNull(FormState(pkg = "a.b", targets = "").toJob(false).error)
        assertNull(FormState(pkg = "a.b", targets = "x").toJob(false).error)
    }

    @Test
    fun toJobOpenAtRequiredOnlyForLive() {
        // openAt(날짜 지정)이 비어 있고 회차(slots)도 없으면 LIVE 는 오픈 시각을 알 수 없어 오류
        val f = FormState(pkg = "a.b", targets = "x", openAt = "", slots = "")
        assertNull(f.toJob(requireOpenAt = false).error)
        assertNotNull(f.toJob(requireOpenAt = true).error)
        assertNotNull(f.copy(openAt = "내일 10시").toJob(true).error)
        val ok = f.copy(openAt = "2026-11-05 10:00:00").toJob(true)
        assertEquals(Instant.parse("2026-11-05T01:00:00Z").toEpochMilli(), ok.job!!.openAtMillis)
    }

    @Test
    fun toJobRejectsBadNumbers() {
        val base = FormState(pkg = "a.b", targets = "x")
        assertNotNull(base.copy(intervalMs = "5").toJob(false).error)
        assertNotNull(base.copy(maxAttempts = "0").toJob(false).error)
        assertNotNull(base.copy(leadMs = "abc").toJob(false).error)
    }

    @Test
    fun hasResultTexts() {
        val none = FormState(pkg = "a.b", targets = "x").toJob(false).job!!
        assertFalse(none.hasResultTexts)
        val some = FormState(pkg = "a.b", targets = "x", success = "발급").toJob(false).job!!
        assertTrue(some.hasResultTexts)
    }

    @Test
    fun ntpTimestampParsing() {
        // 1970-01-01 + 10.5 s
        val secs = 2_208_988_800L + 10
        val b = ByteArray(48)
        for (i in 0 until 4) b[32 + i] = ((secs shr (8 * (3 - i))) and 0xff).toByte()
        b[36] = 0x80.toByte() // 분수 0.5
        assertEquals(10_500L, Ntp.parseTimestampMs(b, 32))
    }
}
