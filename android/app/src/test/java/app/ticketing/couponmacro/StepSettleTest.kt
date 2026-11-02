package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 회귀: 화면이 로딩되기 전에 바로 다음 곳을 눌러 엉뚱한 화면으로 넘어가던 문제.
 * 화면이 안 바뀌는 곳(이미지 등)에서도 동작해야 하므로 '최소 대기'와 '바뀌면 다 바뀔 때까지' 두 규칙을 함께 검증한다.
 */
class StepSettleTest {
    private fun settle(min: Long = 1_000, max: Long = 5_000, beforeLoading: Boolean = false) =
        StepSettle(beforeSig = 1, beforeLoading = beforeLoading, minWaitMs = min, maxWaitMs = max, quietMs = 500, startMs = 0)

    @Test
    fun unchangedScreenWaitsExactlyTheRecordedGap() {
        val s = settle(min = 1_000)
        assertFalse(s.ready(1, false, true, 100))
        assertFalse(s.ready(1, false, true, 900))
        assertTrue("안 바뀌는 화면은 사람이 기다린 시간만 기다리고 진행", s.ready(1, false, true, 1_000))
        assertFalse(s.changed)
    }

    @Test
    fun changedScreenWaitsUntilItStopsChanging() {
        val s = settle(min = 1_000)
        assertFalse(s.ready(2, false, true, 100))     // 바뀌기 시작
        assertFalse(s.ready(3, false, true, 900))     // 계속 바뀜(로딩 중)
        assertFalse("최소 대기는 지났지만 아직 바뀌는 중", s.ready(4, false, true, 1_200))
        assertFalse(s.ready(4, false, true, 1_600))
        assertTrue("바뀐 뒤 0.5초 동안 그대로", s.ready(4, false, true, 1_700))
        assertTrue(s.changed)
        assertFalse(s.timedOut)
    }

    @Test
    fun stableEarlyStillWaitsTheMinimum() {
        // 금방 다 바뀌었어도(0.2초) 사람이 기다린 시간(1초)은 지킨다 — 이미지 등 접근성에 안 보이는 로딩 대비
        val s = settle(min = 1_000)
        assertFalse(s.ready(2, false, true, 100))
        assertFalse(s.ready(2, false, true, 700))     // 이미 안정(0.6초)이지만 최소 대기 전
        assertTrue(s.ready(2, false, true, 1_000))
    }

    @Test
    fun loadingIndicatorAfterTapBlocksEvenIfTextDidNotChange() {
        val s = settle(min = 500)
        assertFalse(s.ready(1, true, true, 100))      // 같은 글자인데 로딩 표시가 떴다
        assertFalse(s.ready(1, true, true, 2_000))
        assertTrue(s.changed)
        assertFalse(s.ready(1, false, true, 2_100))   // 사라진 직후부터 다시 센다
        assertFalse(s.ready(1, false, true, 2_500))
        assertTrue(s.ready(1, false, true, 2_600))
    }

    @Test
    fun alwaysVisibleSpinnerBeforeTheTapIsIgnored() {
        // 탭 전부터 떠 있던 로딩 표시 때문에 매 단계 끝없이 기다리지 않는다
        val s = settle(min = 500, beforeLoading = true)
        assertFalse(s.ready(1, true, true, 100))
        assertTrue(s.ready(1, true, true, 500))
        assertFalse(s.changed)
    }

    @Test
    fun screenSwitchingCountsAsChangingAndMustSettle() {
        // 화면 전환 중에는 대상 앱 화면을 읽을 수 없다(null) — 그 사이에 누르지 않는다
        val s = settle(min = 500)
        assertFalse(s.ready(null, false, false, 100))
        assertFalse(s.ready(null, false, false, 800))
        assertFalse("나타난 직후", s.ready(7, false, true, 900))
        assertFalse(s.ready(7, false, true, 1_300))
        assertTrue(s.ready(7, false, true, 1_400))
    }

    @Test
    fun neverWaitsForeverOnAScreenThatKeepsChanging() {
        val s = settle(min = 500, max = 3_000)
        var t = 100L
        var sig = 10
        while (t < 3_000) {
            assertFalse("t=$t", s.ready(sig++, false, true, t))
            t += 300
        }
        assertTrue(s.ready(sig, false, true, 3_000))
        assertTrue(s.timedOut)
    }

    @Test
    fun unknownBaselineUsesTheFirstReading() {
        val s = StepSettle(beforeSig = null, beforeLoading = false, minWaitMs = 500, maxWaitMs = 5_000, quietMs = 500, startMs = 0)
        assertFalse(s.ready(5, false, true, 100))
        assertTrue(s.ready(5, false, true, 600))
        assertFalse(s.changed)
    }

    @Test
    fun minimumWaitFollowsTheRecordedGapWithinBounds() {
        assertEquals(600L, Settle.minWaitMs(null, isLast = false))
        assertEquals(300L, Settle.minWaitMs(100, isLast = false))
        assertEquals(1_800L, Settle.minWaitMs(1_800, isLast = false))
        assertEquals(2_500L, Settle.minWaitMs(60_000, isLast = false))
        assertEquals(Settle.LAST_STEP_MIN_MS, Settle.minWaitMs(1_800, isLast = true))
    }
}
