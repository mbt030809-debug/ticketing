package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 실행 흐름(어떤 단계를 언제 하는지)을 고정하는 시나리오 테스트.
 * 실제로 났던 문제(즉시 실행이 경로를 안 따라감, 테스트 중 화면만 당김 등)가 다시 생기지 않도록 한다.
 */
class FlowTest {
    private val route = "와우\n쿠폰함"
    private val open = Parse.openAt("2026-11-05 10:00:00")
    private val hour = 60 * 60 * 1000L

    private fun job(
        steps: String = "",
        launchApp: Boolean = true,
        routeAtOpen: Boolean = true,
        refresh: Boolean = true,
    ): Job = FormState(
        pkg = "a.b", targets = "x", coupons = "c", steps = steps, launchApp = launchApp,
        routeAtOpen = routeAtOpen, refreshOnMiss = refresh, openAt = "2026-11-05 10:00:00",
    ).toJob(requireOpenAt = true).job!!

    @Test
    fun reservedDefaultLaunchesThenRoutesAtOpen() {
        val p = Flow.plan(job(route), Mode.LIVE, appInFront = false)
        assertTrue(p.atOpen)
        assertTrue(p.launch)
        assertTrue(p.freshStart)
        assertTrue(p.replayRoute)
        assertTrue(p.waitForOpen)
        assertFalse("정각 이동은 단계마다 로딩을 기다리지 않는다(속도 우선)", p.waitLoadedBetweenSteps)
        assertFalse(p.prelocateOcr)
        assertFalse(p.preRefresh)
    }

    @Test
    fun instantTestAndFindOnlyFollowTheRecordedRoute() {
        // 회귀: 즉시 실행이 경로를 따라가지 않던 문제
        for (mode in listOf(Mode.NOW, Mode.DRY)) {
            for (front in listOf(true, false)) {
                val p = Flow.plan(job(route), mode, appInFront = front)
                assertTrue("$mode/$front", p.launch)
                assertTrue(p.freshStart)
                assertTrue(p.replayRoute)
                assertFalse(p.waitForOpen)
                assertFalse(p.atOpen)
            }
        }
    }

    @Test
    fun testWithoutRouteStaysOnTheCurrentScreen() {
        val inFront = Flow.plan(job(), Mode.NOW, appInFront = true)
        assertFalse(inFront.launch)
        assertFalse(inFront.replayRoute)
        val notInFront = Flow.plan(job(), Mode.NOW, appInFront = false)
        assertTrue("앱을 앞으로 가져오되", notInFront.launch)
        assertFalse("첫 화면으로 초기화하지는 않는다", notInFront.freshStart)
    }

    @Test
    fun testsNeverPullToRefresh() {
        // 회귀: 테스트(즉시 실행)에서 못 찾을 때 화면만 위로 당기던 문제
        for (mode in listOf(Mode.NOW, Mode.DRY)) {
            val p = Flow.plan(job(route), mode, appInFront = true)
            assertFalse(p.pullRefreshOnMiss)
            assertFalse(p.preRefresh)
        }
        assertTrue(Flow.plan(job(route, refresh = true), Mode.LIVE, true).pullRefreshOnMiss)
        assertFalse(Flow.plan(job(route, refresh = false), Mode.LIVE, true).pullRefreshOnMiss)
    }

    @Test
    fun legacyPreNavigateModeWaitsOnThePageAndPrelocates() {
        val p = Flow.plan(job(route, routeAtOpen = false), Mode.LIVE, appInFront = false)
        assertFalse(p.atOpen)
        assertTrue(p.waitLoadedBetweenSteps)
        assertTrue(p.prelocateOcr)
        assertTrue(p.preRefresh)
        assertTrue(p.waitForOpen)
        assertFalse(Flow.plan(job(route, routeAtOpen = false, refresh = false), Mode.LIVE, false).preRefresh)
    }

    @Test
    fun autoLaunchOffStillReplaysRoute() {
        val p = Flow.plan(job(route, launchApp = false), Mode.LIVE, appInFront = true)
        assertFalse(p.launch)
        assertTrue(p.replayRoute)
    }

    @Test
    fun timelineLaunchesFiveSecondsBeforeAndRoutesAtOpen() {
        val tl = Flow.timeline(job(route))
        assertEquals(open - 5_000, tl.launchAtMs)   // 앱 실행: 오픈 5초 전
        assertEquals(open - 100, tl.fireAtMs)       // 경로 이동/클릭: 정각(lead 100ms 보정)
        assertTrue(tl.launchAtMs < tl.fireAtMs)
    }

    @Test
    fun wakeLockCoversTheWholeWaitButIsBounded() {
        val j = job(route)
        assertEquals(3 * hour + 15 * 60_000L, Flow.wakeLockMs(j, Mode.LIVE, open - 3 * hour)) // 3시간 전에 시작해도 끝까지 유지
        assertEquals(hour, Flow.wakeLockMs(j, Mode.LIVE, open - 10 * 60_000L))                // 곧 오픈이면 최소 1시간
        assertEquals(24 * hour, Flow.wakeLockMs(j, Mode.LIVE, open - 48 * hour))              // 최대 24시간
        assertEquals(hour, Flow.wakeLockMs(j, Mode.NOW, open - 3 * hour))
    }
}
