package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class AutoRunTest {
    private fun kst(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, Parse.ZONE).toInstant().toEpochMilli()

    private val h12 = 12 * 3600
    private val h18 = 18 * 3600

    @Test
    fun enabledSlotsExcludeTheOnesTurnedOff() {
        assertEquals(listOf(h12, 0), Schedule.enabled("12:00, 18:00, 24:00", "18:00"))
        assertEquals(listOf(h12, h18, 0), Schedule.enabled("12:00, 18:00, 24:00", ""))
        assertTrue(Schedule.enabled("12:00, 18:00", "12:00, 18:00").isEmpty())
    }

    @Test
    fun displayOrderPutsMidnightLast() {
        // 화면/리포트에서 24:00 이 맨 위에 오던 표시 버그 고정
        assertEquals("12:00, 18:00, 24:00", Schedule.parseSlots("24:00, 18:00, 12:00").joinToString(", ") { Schedule.label(it) })
        assertEquals(listOf(h12, h18, 0), Schedule.sortDisplay(listOf(0, h18, h12, h12)))
    }

    @Test
    fun anyNumberOfTimesWorks() {
        val two = "09:30, 21:00"
        val four = "07:00, 12:00, 18:00, 24:00"
        assertEquals(2, Schedule.enabled(two, "").size)
        assertEquals(4, Schedule.enabled(four, "").size)
        val f = FormState(pkg = "a.b", targets = "x", coupons = "c", slots = four, slotsOff = "12:00")
        // 10:00 기준 켜진 시각 중 다음은 18:00 (12:00 은 꺼 둠)
        assertEquals(kst(2026, 11, 5, 18, 0), f.toJob(true, kst(2026, 11, 5, 10, 0)).job!!.openAtMillis)
        // 18:30 기준 다음은 자정(24:00)
        assertEquals(kst(2026, 11, 6, 0, 0), f.toJob(true, kst(2026, 11, 5, 18, 30)).job!!.openAtMillis)
    }

    @Test
    fun noEnabledTimeIsAnError() {
        val f = FormState(pkg = "a.b", targets = "x", coupons = "c", slots = "12:00, 18:00", slotsOff = "12:00, 18:00")
        assertNotNull(f.toJob(requireOpenAt = true).error)
        // 테스트 모드는 시각이 없어도 됨
        assertNull(f.toJob(requireOpenAt = false).error)
    }

    @Test
    fun alarmIsOneMinuteBeforeAndRepeatsDaily() {
        val entries = listOf(AutoEntry("coupang", h18, 60))
        val t = Schedule.nextTrigger(entries, kst(2026, 11, 5, 17, 0), emptySet())!!
        assertEquals(kst(2026, 11, 5, 18, 0), t.occurrenceMs)
        assertEquals(kst(2026, 11, 5, 17, 59), t.triggerAtMs)

        // 이미 처리한 회차는 건너뛰고 다음 날 같은 시각
        val next = Schedule.nextTrigger(entries, kst(2026, 11, 5, 17, 59, 5), setOf(t.key))!!
        assertEquals(kst(2026, 11, 6, 18, 0), next.occurrenceMs)
        assertEquals(kst(2026, 11, 6, 17, 59), next.triggerAtMs)
    }

    @Test
    fun picksEarliestAlarmAcrossProfilesAndSlots() {
        val entries = listOf(
            AutoEntry("coupang", h18, 60),
            AutoEntry("naverplus", h12, 120),
            AutoEntry("naverplus", 0, 120),
        )
        val t = Schedule.nextTrigger(entries, kst(2026, 11, 5, 10, 0), emptySet())!!
        assertEquals("naverplus", t.profileId)
        assertEquals(kst(2026, 11, 5, 11, 58), t.triggerAtMs) // 12:00 - 120초
    }

    @Test
    fun midnightSlotAlarmsBeforeMidnight() {
        val t = Schedule.nextTrigger(listOf(AutoEntry("coupang", 0, 60)), kst(2026, 11, 5, 23, 0), emptySet())!!
        assertEquals(kst(2026, 11, 6, 0, 0), t.occurrenceMs)
        assertEquals(kst(2026, 11, 5, 23, 59), t.triggerAtMs)
    }

    @Test
    fun lateButStillPossibleStartsAlmostImmediately() {
        val now = kst(2026, 11, 5, 17, 59, 30) // 알람 시각은 지났지만 오픈 30초 전
        val t = Schedule.nextTrigger(listOf(AutoEntry("coupang", h18, 60)), now, emptySet())!!
        assertEquals(kst(2026, 11, 5, 18, 0), t.occurrenceMs)
        assertEquals(now + 1500, t.triggerAtMs)
    }

    @Test
    fun tooCloseToOpenIsSkippedUntilTomorrow() {
        val now = kst(2026, 11, 5, 17, 59, 55) // 오픈 5초 전: 준비 불가
        val t = Schedule.nextTrigger(listOf(AutoEntry("coupang", h18, 60)), now, emptySet())!!
        assertEquals(kst(2026, 11, 6, 18, 0), t.occurrenceMs)
    }

    @Test
    fun noEntriesMeansNoAlarm() {
        assertNull(Schedule.nextTrigger(emptyList(), 0L, emptySet()))
    }

    @Test
    fun openAtFormatRoundTrips() {
        val ms = kst(2026, 11, 5, 11, 59, 59)
        assertEquals("2026-11-05 11:59:59", Schedule.formatOpenAt(ms))
        assertEquals(ms, Parse.openAt(Schedule.formatOpenAt(ms)))
    }

    @Test
    fun awaitUnlockDefaultsOff() {
        val job = FormState(pkg = "a.b", targets = "x", coupons = "c").toJob(false).job!!
        assertFalse(job.awaitUnlock)
        assertTrue(job.copy(awaitUnlock = true).awaitUnlock)
    }
}

class StabilityTrackerTest {
    @Test
    fun stableAfterQuietPeriodWithoutChange() {
        val t = StabilityTracker(1000)
        assertFalse(t.update(1, false, true, 0))
        assertFalse(t.update(1, false, true, 500))
        assertTrue(t.update(1, false, true, 1000))
    }

    @Test
    fun contentChangeRestartsTheWait() {
        val t = StabilityTracker(1000)
        t.update(1, false, true, 0)
        assertFalse(t.update(2, false, true, 1100)) // 바뀜 -> 다시 대기
        assertFalse(t.update(2, false, true, 1900))
        assertTrue(t.update(2, false, true, 2100))
    }

    @Test
    fun loadingIndicatorBlocksUntilItDisappearsAndSettles() {
        val t = StabilityTracker(1000)
        t.update(2, false, true, 0)
        assertFalse(t.update(2, true, true, 5000))   // 로딩 표시 중
        assertFalse(t.update(2, false, true, 5100))  // 사라진 직후부터 다시 센다
        assertFalse(t.update(2, false, true, 5600))
        assertTrue(t.update(2, false, true, 6100))
    }

    @Test
    fun emptyPageIsNeverConsideredLoaded() {
        val t = StabilityTracker(1000)
        assertFalse(t.update(0, false, false, 0))
        assertFalse(t.update(0, false, false, 5000))
    }
}
