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
    fun stepsSupportPositionHintsAndIconTaps() {
        val steps = Parse.steps("녭다세일@0.2000,0.3500\n@0.5000,0.9000\n?닫기@0.5,0.8\n쿠폰함")
        assertEquals(
            listOf(
                Step("녭다세일", false, 0.2f, 0.35f),   // 글자 + 위치 힌트(같은 글자가 여러 곳이면 가까운 것)
                Step("", false, 0.5f, 0.9f),           // 글자 없는 아이콘: 위치 탭
                Step("닫기", true, 0.5f, 0.8f),
                Step("쿠폰함", false),
            ),
            steps,
        )
        assertTrue(steps[1].isTap)
        assertFalse(steps[0].isTap)
        assertEquals("(아이콘 탭)", steps[1].display())
        assertEquals("?닫기", steps[2].display())
    }

    @Test
    fun malformedPositionSuffixIsTreatedAsPlainText() {
        // '@' 뒤가 숫자 쌍이 아니면 그냥 글자로 취급
        assertEquals(listOf(Step("문의@회사", false)), Parse.steps("문의@회사"))
    }

    @Test
    fun routeRecordingBuildsLinesForLabelledAndIconClicks() {
        // 글자 있는 요소: 글자 + 위치 힌트 / 아이콘만: 위치만 / 아무것도 모르면 기록 불가
        assertEquals("녭다세일@0.2000,0.3500", RouteSteps.line("녭다세일", 0.2f, 0.35f))
        assertEquals("@0.2000,0.3500", RouteSteps.line(null, 0.2f, 0.35f))
        assertEquals("혜택", RouteSteps.line("혜택", null, null))
        assertNull(RouteSteps.line(null, null, null))
        assertNull(RouteSteps.line("  ", null, 0.5f))
        // 글자 속 '@' 는 위치 표시와 헷갈리지 않게 공백으로
        assertEquals("a b@0.1000,0.2000", RouteSteps.line("a@b", 0.1f, 0.2f))
        // 기록한 줄은 그대로 다시 읽힌다
        val back = Parse.steps(RouteSteps.line("녭다세일", 0.2f, 0.35f)!! + "\n" + RouteSteps.line(null, 0.5f, 0.9f)!!)
        assertEquals(listOf(Step("녭다세일", false, 0.2f, 0.35f), Step("", false, 0.5f, 0.9f)), back)
    }

    @Test
    fun popupLinesAreRecognizedByTheirTextPart() {
        val words = listOf("닫기", "나중에")
        assertTrue(RouteSteps.isPopup("닫기@0.5000,0.8000", words))
        assertTrue(RouteSteps.isPopup("나중에", words))
        assertFalse(RouteSteps.isPopup("쿠폰함@0.5000,0.8000", words))
        assertFalse(RouteSteps.isPopup("@0.5000,0.8000", words))
    }

    @Test
    fun linksAreExtractedFromSharedTextAndValidated() {
        // 앱의 [공유] 문구에는 설명이 섞여 있다 — 주소만 뽑는다
        assertEquals("https://link.coupang.com/a/abc123", Links.extract("와우멤버스데이 특가 https://link.coupang.com/a/abc123 지금 확인하세요"))
        assertEquals("https://example.com/event?id=1&x=2", Links.extract("  https://example.com/event?id=1&x=2.  "))
        assertEquals("coupang://event/123", Links.extract("coupang://event/123"))
        assertNull(Links.extract("링크 없음"))
        assertNull(Links.extract("http://"))

        val base = FormState(pkg = "a.b", targets = "x", coupons = "c")
        assertEquals("", base.toJob(false).job!!.eventUrl)
        assertEquals(
            "https://link.coupang.com/a/abc123",
            base.copy(eventUrl = "특가 https://link.coupang.com/a/abc123 !").toJob(false).job!!.eventUrl,
        )
        assertNotNull(base.copy(eventUrl = "그냥 글자").toJob(false).error)
    }

    @Test
    fun toJobRequiresPackageAndTarget() {
        assertNotNull(FormState(pkg = "", targets = "x").toJob(false).error)
        assertNotNull(FormState(pkg = "a.b", targets = "").toJob(false).error)
        assertNull(FormState(pkg = "a.b", targets = "x", coupons = "c").toJob(false).error)
    }

    @Test
    fun toJobOpenAtRequiredOnlyForLive() {
        // openAt(날짜 지정)이 비어 있고 회차(slots)도 없으면 LIVE 는 오픈 시각을 알 수 없어 오류
        val f = FormState(pkg = "a.b", targets = "x", coupons = "c", openAt = "", slots = "")
        assertNull(f.toJob(requireOpenAt = false).error)
        assertNotNull(f.toJob(requireOpenAt = true).error)
        assertNotNull(f.copy(openAt = "내일 10시").toJob(true).error)
        val ok = f.copy(openAt = "2026-11-05 10:00:00").toJob(true)
        assertEquals(Instant.parse("2026-11-05T01:00:00Z").toEpochMilli(), ok.job!!.openAtMillis)
    }

    @Test
    fun toJobRejectsBadNumbers() {
        val base = FormState(pkg = "a.b", targets = "x", coupons = "c")
        assertNotNull(base.copy(intervalMs = "5").toJob(false).error)
        assertNotNull(base.copy(maxAttempts = "0").toJob(false).error)
        assertNotNull(base.copy(leadMs = "abc").toJob(false).error)
    }

    @Test
    fun hasResultTexts() {
        val none = FormState(pkg = "a.b", targets = "x", coupons = "c").toJob(false).job!!
        assertFalse(none.hasResultTexts)
        val some = FormState(pkg = "a.b", targets = "x", coupons = "c", success = "발급").toJob(false).job!!
        assertTrue(some.hasResultTexts)
    }

    @Test
    fun couponsParsedPerLineAndKeepCommasInAmounts() {
        assertEquals(listOf("5,000원 할인", "10% 할인", "와우+무료배송"), Parse.coupons("5,000원 할인\n10% 할인; 와우+무료배송\n\n"))
        assertTrue(Parse.coupons("  \n ; ").isEmpty())
    }

    @Test
    fun couponsAreRequiredToStart() {
        val noCoupon = FormState(pkg = "a.b", targets = "x")
        val e = noCoupon.toJob(false).error
        assertNotNull(e)
        assertTrue(e!!.contains("쿠폰"))
        // 쿠폰 고르기(목록 읽기)용으로는 쿠폰 없이도 Job 생성 가능
        assertNull(noCoupon.toJob(requireOpenAt = false, requireCoupons = false).error)
        val ok = noCoupon.copy(coupons = "5,000원\n10% 할인").toJob(false).job!!
        assertEquals(listOf("5,000원", "10% 할인"), ok.coupons)
    }

    @Test
    fun clickDotsDefaultOnAndCanBeDisabled() {
        val base = FormState(pkg = "a.b", targets = "x", coupons = "c")
        assertTrue(base.toJob(false).job!!.showDots)
        assertFalse(base.copy(showDots = false).toJob(false).job!!.showDots)
    }

    @Test
    fun reservedFlowDefaultsToLaunchFiveSecondsBeforeAndRouteAtOpen() {
        val job = FormState(pkg = "a.b", targets = "x", coupons = "c").toJob(false).job!!
        assertTrue(job.routeAtOpen)
        assertEquals(5_000L, job.launchLeadMs)
        val custom = FormState(pkg = "a.b", targets = "x", coupons = "c", routeAtOpen = false, launchLeadSec = "12.5").toJob(false).job!!
        assertFalse(custom.routeAtOpen)
        assertEquals(12_500L, custom.launchLeadMs)
    }

    @Test
    fun launchLeadMustBeBetweenZeroAndSixHundredSeconds() {
        val base = FormState(pkg = "a.b", targets = "x", coupons = "c")
        assertNotNull(base.copy(launchLeadSec = "-1").toJob(false).error)
        assertNotNull(base.copy(launchLeadSec = "601").toJob(false).error)
        assertNotNull(base.copy(launchLeadSec = "abc").toJob(false).error)
        assertNull(base.copy(launchLeadSec = "0").toJob(false).error)
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
