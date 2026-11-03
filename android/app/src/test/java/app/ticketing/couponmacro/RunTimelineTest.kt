package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** "정각에 경로로 바로 들어가는지, 몇 초 걸리는지" 를 로그로 확인하기 위한 타임라인 */
class RunTimelineTest {
    private val t0 = 1_000_000L

    @Test
    fun marksAreRelativeToTheTargetTimeWithSigns() {
        assertEquals("+0.03s", RunTimeline.fmt(30))
        assertEquals("-4.98s", RunTimeline.fmt(-4_980))
        assertEquals("+0.00s", RunTimeline.fmt(0))
        val tl = RunTimeline(t0)
        tl.mark("앱 실행", t0 - 5_000)
        tl.mark("이동1", t0 + 40)
        tl.mark("경로 끝", t0 + 3_400)
        assertEquals("앱 실행 -5.00s → 이동1 +0.04s → 경로 끝 +3.40s", tl.summary())
    }

    @Test
    fun statsShowFirstActionRouteEndAndRouteDuration() {
        val tl = RunTimeline(t0)
        tl.mark("이동1", t0 + 40)
        tl.mark("이동2", t0 + 1_600)
        tl.mark("경로 끝", t0 + 3_400)
        tl.markOnce("첫 쿠폰 클릭", t0 + 3_650)
        tl.markOnce("첫 쿠폰 클릭", t0 + 9_999) // 두 번째는 무시
        assertEquals("정각→첫 동작 +0.04s · 정각→경로 끝 +3.40s (경로 3.36초) · 정각→첫 쿠폰 클릭 +3.65s", tl.stats())
        assertEquals(3_650L, tl.rel("첫 쿠폰 클릭"))
        assertNull(tl.rel("없는 단계"))
    }

    @Test
    fun onScreenClockCountsFromMinusTenThroughZeroUpwards() {
        // "10초 먼저 시작이면 -10 -9 … 0 1 2": 정각(=0)을 기준으로 내림한 초
        assertEquals(-10L, RunTimeline.clockSecond(-10_000))
        assertEquals(-10L, RunTimeline.clockSecond(-9_500))
        assertEquals(-9L, RunTimeline.clockSecond(-9_000))
        assertEquals(-1L, RunTimeline.clockSecond(-1))
        assertEquals(0L, RunTimeline.clockSecond(0))      // 정각 순간부터 0
        assertEquals(0L, RunTimeline.clockSecond(999))
        assertEquals(1L, RunTimeline.clockSecond(1_000))
        assertEquals(2L, RunTimeline.clockSecond(2_345))
        assertEquals(-11L, RunTimeline.clockSecond(-10_001))
        // 값이 바뀌는 시각은 정확히 정각의 정수 초 — 10초 전부터 정각까지 시계는 -10, -9, …, -1, 0 순서로 11번 바뀐다
        val seq = (-10_000L..0L step 500L).map { RunTimeline.clockSecond(it) }.distinct()
        assertEquals((-10L..0L).toList(), seq)
    }

    @Test
    fun emptyTimelineSaysSo() {
        val tl = RunTimeline(t0)
        assertEquals("", tl.summary())
        assertTrue(tl.stats().contains("기록된 단계 없음"))
    }
}
