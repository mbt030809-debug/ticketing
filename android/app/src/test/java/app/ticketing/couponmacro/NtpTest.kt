package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 서버(NTP) 시각 보정: 왕복이 가장 짧은 표본을 쓴다 */
class NtpTest {
    @Test
    fun offsetAndRoundTripFromTheFourTimestamps() {
        // 기기 시계가 서버보다 100ms 느림, 왕복 40ms(편도 20ms씩), 서버 처리 2ms
        val t0 = 1_000L                  // 기기 송신
        val t1 = t0 + 100 + 20           // 서버 수신 (서버시각 = 기기시각 + 100)
        val t2 = t1 + 2                  // 서버 송신
        val t3 = t0 + 20 + 2 + 20        // 기기 수신
        val s = Ntp.compute(t0, t1, t2, t3)
        assertEquals(100L, s.offsetMs)
        assertEquals(40L, s.rttMs)
    }

    @Test
    fun usesTheSampleWithTheShortestRoundTrip() {
        // 왕복이 길수록 오차가 크다(경로가 한쪽으로 느린 경우) -> 가장 짧은 표본의 보정값을 쓴다
        val best = Ntp.pickBest(listOf(Ntp.Sample(150, 180), Ntp.Sample(95, 24), Ntp.Sample(40, 90)))!!
        assertEquals(95L, best.offsetMs)
        assertEquals(24L, best.rttMs)
        assertNull(Ntp.pickBest(emptyList()))
    }
}
