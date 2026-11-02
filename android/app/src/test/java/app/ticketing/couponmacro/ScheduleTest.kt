package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZonedDateTime

class ScheduleTest {
    private fun kst(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int = 0): Long =
        ZonedDateTime.of(y, mo, d, h, mi, s, 0, Parse.ZONE).toInstant().toEpochMilli()

    private val slots = Schedule.parseSlots("12:00, 18:00, 24:00")

    @Test
    fun parseSlotAcceptsValidForms() {
        assertEquals(12 * 3600, Schedule.parseSlot("12:00"))
        assertEquals(0, Schedule.parseSlot("24:00"))
        assertEquals(0, Schedule.parseSlot("00:00"))
        assertEquals(11 * 3600 + 59 * 60 + 59, Schedule.parseSlot("11:59:59"))
        assertEquals(9 * 3600, Schedule.parseSlot(" 9:00 "))
    }

    @Test
    fun parseSlotRejectsInvalid() {
        for (bad in listOf("25:00", "12:60", "24:30", "24:00:01", "abc", "", "12", "12:0")) {
            assertNull("'$bad' 는 거부되어야 함", Schedule.parseSlot(bad))
        }
    }

    @Test
    fun parseSlotsSortsDedupsAndSkipsGarbage() {
        assertEquals(listOf(0, 12 * 3600, 18 * 3600), Schedule.parseSlots("18:00, 12:00, 24:00, 12:00, 아무거나"))
    }

    @Test
    fun labelShowsMidnightAs24() {
        assertEquals("24:00", Schedule.label(0))
        assertEquals("18:00", Schedule.label(18 * 3600))
        assertEquals("11:59:59", Schedule.label(11 * 3600 + 59 * 60 + 59))
    }

    @Test
    fun autoPicksNearestUpcomingSlot() {
        assertEquals(kst(2026, 11, 5, 12, 0), Schedule.nextOpen(slots, "auto", kst(2026, 11, 5, 10, 0)))
        assertEquals(kst(2026, 11, 5, 18, 0), Schedule.nextOpen(slots, "auto", kst(2026, 11, 5, 12, 3)))
        // 18시 이후엔 자정(24시) = 다음 날 00:00
        assertEquals(kst(2026, 11, 6, 0, 0), Schedule.nextOpen(slots, "auto", kst(2026, 11, 5, 19, 0)))
        // 자정 직후엔 그날 12시
        assertEquals(kst(2026, 11, 6, 12, 0), Schedule.nextOpen(slots, "auto", kst(2026, 11, 6, 0, 5)))
    }

    @Test
    fun slightlyLateStartStillTargetsRunningSlot() {
        val now = kst(2026, 11, 5, 12, 0, 30)
        assertEquals(kst(2026, 11, 5, 12, 0), Schedule.nextOpen(slots, "auto", now))
        assertEquals(kst(2026, 11, 5, 12, 0), Schedule.nextOpen(slots, "12:00", now))
    }

    @Test
    fun explicitSlotRollsToNextDay() {
        assertEquals(kst(2026, 11, 6, 18, 0), Schedule.nextOpen(slots, "18:00", kst(2026, 11, 5, 19, 0)))
        assertEquals(kst(2026, 11, 6, 0, 0), Schedule.nextOpen(slots, "24:00", kst(2026, 11, 5, 23, 59)))
    }

    @Test
    fun emptyOrInvalidGivesNull() {
        assertNull(Schedule.nextOpen(emptyList(), "auto", 0L))
        assertNull(Schedule.nextOpen(slots, "아무거나", 0L))
    }

    @Test
    fun describeIsReadable() {
        val now = kst(2026, 11, 5, 15, 46)
        val s = Schedule.describe(kst(2026, 11, 5, 18, 0), now)
        assertEquals("오늘 18:00 (2시간 14분 뒤)", s)
        assertTrue(Schedule.describe(kst(2026, 11, 6, 0, 0), now).startsWith("내일 00:00(24시)"))
        assertTrue(Schedule.describe(kst(2026, 11, 5, 15, 45), now).contains("진행 중"))
    }

    @Test
    fun formStateUsesSlotsForLive() {
        val f = FormState(pkg = "a.b", targets = "x")
        val ok = f.toJob(requireOpenAt = true, nowMs = kst(2026, 11, 5, 10, 0))
        assertNull(ok.error)
        assertEquals(kst(2026, 11, 5, 12, 0), ok.job!!.openAtMillis)
        assertNotNull(f.copy(slots = "").toJob(requireOpenAt = true, nowMs = 0L).error)
        // 테스트 모드(LIVE 아님)는 회차가 없어도 됨
        assertNull(f.copy(slots = "").toJob(requireOpenAt = false).error)
    }
}
