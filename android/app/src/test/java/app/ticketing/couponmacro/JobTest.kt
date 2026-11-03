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
    fun stepsParsePositionTapsWithRecordedGaps() {
        val steps = Parse.steps("@0.2000,0.3500~1200\n@0.5000,0.9000\n?닫기@0.5,0.8~400\n혜택@0.1,0.2\n쿠폰함")
        assertEquals(
            listOf(
                Step("", false, 0.2f, 0.35f, 1200),   // 눌렀던 위치 + 이전 클릭 후 1.2초
                Step("", false, 0.5f, 0.9f, null),
                Step("닫기", true, 0.5f, 0.8f, 400),   // 팝업 닫기: 없어도 진행
                Step("혜택", false, 0.1f, 0.2f, null), // 글자 + 위치 힌트
                Step("쿠폰함", false),                  // 글자만 (직접 적은 경우)
            ),
            steps,
        )
        assertTrue(steps[0].isTap)
        assertFalse(steps[3].isTap)
        assertEquals("(탭 20%,35%)", steps[0].display())
        assertEquals("?닫기", steps[2].display())
    }

    @Test
    fun malformedPositionSuffixIsTreatedAsPlainText() {
        // '@' 뒤가 숫자 쌍이 아니면 그냥 글자로 취급
        assertEquals(listOf(Step("문의@회사", false)), Parse.steps("문의@회사"))
    }

    @Test
    fun stepsParseSwipesAndLongPresses() {
        val steps = Parse.steps("S@0.5000,0.7000>0.5000,0.3000#350~900\n@0.2,0.3#800~100\n?S@0.1,0.2>0.9,0.2")
        assertEquals(
            listOf(
                Step("", false, 0.5f, 0.7f, 900, 0.5f, 0.3f, 350),
                Step("", false, 0.2f, 0.3f, 100, null, null, 800),
                Step("", true, 0.1f, 0.2f, null, 0.9f, 0.2f, null),
            ),
            steps,
        )
        assertTrue(steps[0].isSwipe)
        assertFalse(steps[0].isTap)
        assertTrue(steps[1].isTap)
        assertFalse(steps[1].isSwipe)
        assertEquals("(스와이프 50%,70%→50%,30%)", steps[0].display())
        assertEquals("(탭 20%,30%)", steps[1].display())
        assertEquals("?(스와이프 10%,20%→90%,20%)", steps[2].display())
    }

    @Test
    fun routeRecordingStoresTouchesAsPositionsAndGaps() {
        val w = 1000
        val h = 2000
        val tap = RecordedGesture(GestureKind.TAP, 200f, 700f, 200f, 700f, Gestures.TAP_MS)
        // 눌린 위치와 간격이 핵심 — 글자는 저장하지 않는다
        assertEquals("@0.2000,0.3500~1200", RouteSteps.line(tap, w, h, 1200, null))
        assertEquals("@0.2000,0.3500", RouteSteps.line(tap, w, h, null, null))
        // 팝업 닫기 류는 매번 뜨지 않으므로 '없어도 진행'
        assertEquals("?닫기@0.2000,0.3500~400", RouteSteps.line(tap, w, h, 400, "닫기"))
        // 길게 누르기는 누른 시간을 함께
        val hold = RecordedGesture(GestureKind.TAP, 500f, 1800f, 500f, 1800f, 800)
        assertEquals("@0.5000,0.9000#800~100", RouteSteps.line(hold, w, h, 100, null))
        // 스와이프(스크롤)도 시작→끝 위치와 걸린 시간으로 기록
        val swipe = RecordedGesture(GestureKind.SWIPE, 500f, 1400f, 500f, 600f, 350)
        assertEquals("S@0.5000,0.7000>0.5000,0.3000#350~900", RouteSteps.line(swipe, w, h, 900, null))
        // 기록한 줄들은 그대로 다시 읽혀 같은 순서로 재생된다
        val lines = listOf(
            RouteSteps.line(tap, w, h, 900, null),
            RouteSteps.line(tap, w, h, 300, "닫기"),
            RouteSteps.line(swipe, w, h, 1500, null),
            RouteSteps.line(hold, w, h, null, null),
        )
        assertEquals(
            listOf(
                Step("", false, 0.2f, 0.35f, 900),
                Step("닫기", true, 0.2f, 0.35f, 300),
                Step("", false, 0.5f, 0.7f, 1500, 0.5f, 0.3f, 350),
                Step("", false, 0.5f, 0.9f, null, null, null, 800),
            ),
            Parse.steps(lines.joinToString("\n")),
        )
    }

    @Test
    fun stepsCarryTheRecordedScreenReferences() {
        // 줄 끝 ^기준화면(탭 직전 화면), 마지막 단계는 ^기준화면^끝화면, 기준화면이 없으면 ^^끝화면
        val steps = Parse.steps(
            "@0.2000,0.3500~1200^AAAA\nS@0.5,0.7>0.5,0.3#350~900^BBBB\n?닫기@0.5,0.8^CCCC\n@0.5000,0.9000#800~100^DDDD^EEEE\n@0.1,0.1^^FFFF",
        )
        assertEquals(
            listOf(
                Step("", false, 0.2f, 0.35f, 1200, ref = "AAAA"),
                Step("", false, 0.5f, 0.7f, 900, 0.5f, 0.3f, 350, "BBBB"),
                Step("닫기", true, 0.5f, 0.8f, null, ref = "CCCC"),
                Step("", false, 0.5f, 0.9f, 100, null, null, 800, "DDDD", "EEEE"),
                Step("", false, 0.1f, 0.1f, null, endRef = "FFFF"),
            ),
            steps,
        )
        assertEquals("(탭 20%,35%)◎", steps[0].display())
        assertEquals("?닫기◎", steps[2].display())
        assertNull(Parse.steps("@0.1,0.1")[0].ref)
    }

    @Test
    fun routeRecordingStoresScreenReferencesAndReadsThemBack() {
        val tap = RecordedGesture(GestureKind.TAP, 200f, 700f, 200f, 700f, Gestures.TAP_MS)
        val swipe = RecordedGesture(GestureKind.SWIPE, 500f, 1400f, 500f, 600f, 350)
        assertEquals("@0.2000,0.3500~1200^AAAA", RouteSteps.line(tap, 1000, 2000, 1200, null, "AAAA"))
        assertEquals("@0.2000,0.3500^AAAA^ZZZZ", RouteSteps.line(tap, 1000, 2000, null, null, "AAAA", "ZZZZ"))
        assertEquals("@0.2000,0.3500^^ZZZZ", RouteSteps.line(tap, 1000, 2000, null, null, null, "ZZZZ"))
        assertEquals("S@0.5000,0.7000>0.5000,0.3000#350~900^BBBB", RouteSteps.line(swipe, 1000, 2000, 900, null, "BBBB"))
        // 기준 화면이 없으면 예전과 같은 줄
        assertEquals("@0.2000,0.3500~1200", RouteSteps.line(tap, 1000, 2000, 1200, null))
        val lines = listOf(
            RouteSteps.line(tap, 1000, 2000, 900, null, "AAAA"),
            RouteSteps.line(swipe, 1000, 2000, 1500, null, "BBBB", "ZZZZ"),
        )
        val back = Parse.steps(lines.joinToString("\n"))
        assertEquals("AAAA", back[0].ref)
        assertEquals("BBBB", back[1].ref)
        assertEquals("ZZZZ", back[1].endRef)
        assertTrue(back[1].isSwipe)
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
        assertEquals(10_000L, job.launchLeadMs) // 오픈 10초 전에 앱 실행
        val custom = FormState(pkg = "a.b", targets = "x", coupons = "c", routeAtOpen = false, launchLeadSec = "12.5").toJob(false).job!!
        assertFalse(custom.routeAtOpen)
        assertEquals(12_500L, custom.launchLeadMs)
    }

    @Test
    fun swipeSpeedDefaultsToTwiceAndMustBeInRange() {
        val base = FormState(pkg = "a.b", targets = "x", coupons = "c")
        assertEquals(2f, base.toJob(false).job!!.swipeSpeed, 0f)
        assertEquals(3.5f, base.copy(swipeSpeed = "3.5").toJob(false).job!!.swipeSpeed, 0f)
        assertNotNull(base.copy(swipeSpeed = "0.1").toJob(false).error)
        assertNotNull(base.copy(swipeSpeed = "9").toJob(false).error)
        assertNotNull(base.copy(swipeSpeed = "abc").toJob(false).error)
    }

    @Test
    fun afterScreenWaitDefaultsToHalfASecondAndMustBeInRange() {
        val base = FormState(pkg = "a.b", targets = "x", coupons = "c")
        assertEquals(500L, base.toJob(false).job!!.afterScreenMs)
        assertEquals(1_200L, base.copy(afterScreenSec = "1.2").toJob(false).job!!.afterScreenMs)
        assertEquals(0L, base.copy(afterScreenSec = "0").toJob(false).job!!.afterScreenMs)
        assertNotNull(base.copy(afterScreenSec = "-1").toJob(false).error)
        assertNotNull(base.copy(afterScreenSec = "6").toJob(false).error)
        assertNotNull(base.copy(afterScreenSec = "abc").toJob(false).error)
    }

    @Test
    fun waitAfterScrollDefaultsToNoneAndMustBeInRange() {
        val base = FormState(pkg = "a.b", targets = "x", coupons = "c")
        assertEquals(0L, base.toJob(false).job!!.afterScrollMs) // 스크롤 직후 바로 누른다
        assertEquals(400L, base.copy(afterScrollSec = "0.4").toJob(false).job!!.afterScrollMs)
        assertNotNull(base.copy(afterScrollSec = "-1").toJob(false).error)
        assertNotNull(base.copy(afterScrollSec = "9").toJob(false).error)
        assertNotNull(base.copy(afterScrollSec = "x").toJob(false).error)
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
