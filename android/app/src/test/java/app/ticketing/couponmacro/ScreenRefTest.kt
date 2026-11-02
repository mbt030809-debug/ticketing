package app.ticketing.couponmacro

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 가짜 화면(칸 밝기 격자). scroll 만큼 내용이 밀린 화면을 흉내 낸다. */
internal fun fakePage(scroll: Int, noise: Int = 0): IntArray = IntArray(ScreenRef.SIZE) { i ->
    val r = i / ScreenRef.COLS
    val c = i % ScreenRef.COLS
    val base = ((((r + scroll) * 7919) xor ((c / 3) * 104729)) % 211 + 211) % 211 + 20
    (base + if (noise != 0) (i * 31) % (2 * noise + 1) - noise else 0).coerceIn(0, 255)
}

/**
 * 회귀: 로딩 뒤 저절로 스크롤되는 화면에서, 스크롤 전에 눌러 맨 위 버튼이 눌리던 문제.
 * '기록 때 화면과 같아 보이는가'를 칸 밝기 격자로 판정한다.
 */
class ScreenRefTest {
    @Test
    fun encodeDecodeRoundTripAndRejectsGarbage() {
        val g = fakePage(0)
        assertArrayEquals(g, ScreenRef.decode(ScreenRef.encode(g)))
        assertNull(ScreenRef.decode(null))
        assertNull(ScreenRef.decode(""))
        assertNull(ScreenRef.decode("@@@"))
        assertNull("크기가 다르면 무시", ScreenRef.decode(ScreenRef.encode(g).take(20)))
        // 저장 줄(Step)에 쓰이는 글자만으로 이루어져야 한다
        assertTrue(ScreenRef.encode(g).all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun blankOrBlackScreenIsNotUsedAsReference() {
        // 보안 화면/검은 화면은 '아무 화면이나 같다'고 나오므로 기준으로 쓰지 않는다
        val black = IntArray(ScreenRef.SIZE)
        assertFalse(ScreenRef.informative(black))
        assertNull(ScreenRef.gridOf(ScreenRef.encode(black)))
        assertTrue(ScreenRef.informative(fakePage(0)))
        assertNotNull(ScreenRef.gridOf(ScreenRef.encode(fakePage(0))))
    }

    @Test
    fun sameScreenWithSmallNoiseStillMatches() {
        val s = ScreenRef.score(fakePage(0), fakePage(0, noise = 8), 0.5f, 0.5f)
        assertEquals(1.0f, s.global, 0.0001f)
        assertEquals(1.0f, s.region, 0.0001f)
    }

    @Test
    fun scrolledScreenIsClearlyDifferent() {
        // 스크롤 전/후 화면이 같다고 나오면 안 된다
        for (shift in listOf(3, 6, 10)) {
            val s = ScreenRef.score(fakePage(0), fakePage(shift), 0.5f, 0.5f)
            assertTrue("shift=$shift global=${s.global}", s.global < ScreenRef.RELAXED)
        }
    }

    @Test
    fun statusBarAndRecordButtonAreIgnored() {
        val ref = fakePage(0)
        val cur = ref.copyOf()
        for (r in 0 until ScreenRef.ROWS) for (c in 0 until ScreenRef.COLS) {
            if (ScreenRef.isIgnored(c, r)) cur[r * ScreenRef.COLS + c] = (cur[r * ScreenRef.COLS + c] + 120) % 256
        }
        assertEquals(1.0f, ScreenRef.score(ref, cur, null, null).global, 0.0001f)
    }

    @Test
    fun regionScoreLooksAtTheTappedNeighborhood() {
        // 누를 자리 주변만 다르면(= 그 자리에 다른 버튼이 있음) 전체는 거의 같아도 region 은 낮다
        val ref = fakePage(0)
        val cur = ref.copyOf()
        for (r in 19..21) for (c in 10..14) cur[r * ScreenRef.COLS + c] = (cur[r * ScreenRef.COLS + c] + 100) % 256
        val s = ScreenRef.score(ref, cur, 0.5f, 0.5f)
        assertTrue(s.global > 0.95f)
        assertTrue(s.region < ScreenRef.REGION)
    }

    @Test
    fun sameScreenDetectsStepsThatDoNotChangeThePage() {
        val a = fakePage(0)
        val fewChanged = IntArray(a.size) { if (it % 20 == 0) (a[it] + 100) % 256 else a[it] } // 5% 정도만 다름
        assertTrue(ScreenRef.sameScreen(a, fewChanged))
        assertFalse(ScreenRef.sameScreen(a, fakePage(6)))
    }
}

class ScreenWaitTest {
    private val ref = fakePage(0)

    /** 기준과 약 86% 만 같은 화면: 거의 똑같다(90%)고는 못 하지만 같은 화면(80%)으로는 볼 수 있다 */
    private val near = IntArray(ref.size) { if (it % 7 == 0) (ref[it] + 100) % 256 else ref[it] }

    @Test
    fun exactlyTheSameScreenIsAcceptedAtOnce() {
        // 속도: 스크롤이 끝나 기록 때와 똑같아진 화면은 한 번만 봐도 바로 누른다
        val w = ScreenWait(ref, 0.5f, 0.5f, startMs = 0)
        assertEquals(ScreenVerdict.MATCH, w.update(fakePage(0, noise = 8), 100))
    }

    @Test
    fun similarButNotExactNeedsTwoConsecutiveMatches() {
        val w = ScreenWait(ref, null, null, startMs = 0)
        assertEquals(ScreenVerdict.WAIT, w.update(near, 100))
        assertEquals(ScreenVerdict.MATCH, w.update(near, 500))
    }

    @Test
    fun screenStillScrollingNeverMatchesAndTimesOut() {
        // 스크롤 전(맨 위) 화면에서는 누르지 않는다 — 기다리다 시간이 다 되면 그대로 진행
        val w = ScreenWait(ref, 0.5f, 0.5f, startMs = 0)
        var t = 100L
        while (t < ScreenRef.MAX_WAIT_MS) {
            assertEquals("t=$t", ScreenVerdict.WAIT, w.update(fakePage(6), t))
            t += 400
        }
        assertEquals(ScreenVerdict.TIMEOUT, w.update(fakePage(6), ScreenRef.MAX_WAIT_MS))
    }

    @Test
    fun matchesOnceTheScrollFinishes() {
        val w = ScreenWait(ref, 0.5f, 0.5f, startMs = 0)
        assertEquals(ScreenVerdict.WAIT, w.update(fakePage(6), 100))  // 로딩 직후: 아직 스크롤 전
        assertEquals(ScreenVerdict.WAIT, w.update(fakePage(3), 500))  // 스크롤 중
        assertEquals(ScreenVerdict.MATCH, w.update(fakePage(0), 900)) // 도착 -> 바로 누른다
    }

    @Test
    fun aPassingSimilarScreenDoesNotCount() {
        // 비슷해 보이는 화면이 잠깐 지나간 것만으로는 누르지 않는다(연속 2회 필요)
        val w = ScreenWait(ref, null, null, startMs = 0)
        assertEquals(ScreenVerdict.WAIT, w.update(near, 100))
        assertEquals(ScreenVerdict.WAIT, w.update(fakePage(2), 500))
        assertEquals(ScreenVerdict.WAIT, w.update(near, 900))
        assertEquals(ScreenVerdict.MATCH, w.update(near, 1_300))
    }

    @Test
    fun captureFailureBreaksTheStreak() {
        val w = ScreenWait(ref, null, null, startMs = 0)
        assertEquals(ScreenVerdict.WAIT, w.update(near, 100))
        assertEquals(ScreenVerdict.WAIT, w.update(null, 500))
        assertEquals(ScreenVerdict.WAIT, w.update(near, 900))
        assertEquals(ScreenVerdict.MATCH, w.update(near, 1_300))
    }

    @Test
    fun looserMatchOnlyAfterWaitingLong() {
        // 배너 등이 계속 바뀌어 77% 만 같은 화면: 처음엔 안 맞고, 조금 기다리면 인정
        val cur = IntArray(ref.size) { if (it % 9 < 2) (ref[it] + 100) % 256 else ref[it] }
        val w = ScreenWait(ref, null, null, startMs = 0)
        val relax = ScreenRef.RELAX_AFTER_MS
        assertEquals(ScreenVerdict.WAIT, w.update(cur, 100))
        assertEquals(ScreenVerdict.WAIT, w.update(cur, relax - 500))
        assertEquals(ScreenVerdict.WAIT, w.update(cur, relax + 100))
        assertEquals(ScreenVerdict.MATCH, w.update(cur, relax + 500))
        assertTrue("완화 구간이 최대 대기 안에 있어야 함", relax + 500 < ScreenRef.MAX_WAIT_MS)
    }

    @Test
    fun tappedNeighborhoodMustAlwaysMatch() {
        // 전체는 거의 같아도 누를 자리 주변이 다르면(다른 버튼이 있음) 누르지 않는다
        val cur = ref.copyOf()
        for (r in 19..21) for (c in 10..14) cur[r * ScreenRef.COLS + c] = (cur[r * ScreenRef.COLS + c] + 100) % 256
        val w = ScreenWait(ref, 0.5f, 0.5f, startMs = 0)
        assertEquals(ScreenVerdict.WAIT, w.update(cur, 100))
        assertEquals(ScreenVerdict.WAIT, w.update(cur, 500))
        assertEquals(ScreenVerdict.WAIT, w.update(cur, ScreenRef.RELAX_AFTER_MS + 100))
        assertEquals(ScreenVerdict.WAIT, w.update(cur, ScreenRef.RELAX_AFTER_MS + 500))
    }
}

class RouteScreensTest {
    private val a = ScreenRef.encode(fakePage(0))
    private val b = ScreenRef.encode(fakePage(6))

    @Test
    fun waitsOnlyWhereThereIsADifferentRecordedScreen() {
        val steps = listOf(
            Step("", false, 0.2f, 0.3f, null, ref = a),                   // 첫 화면
            Step("", false, 0.5f, 0.9f, 800, ref = b),                    // 스크롤된 화면 -> 기다림
            Step("", false, 0.5f, 0.9f, 800, ref = b),                    // 앞 단계와 같은 화면(탭해도 안 바뀜) -> 시간 기준
            Step("닫기", true, 0.5f, 0.8f, 300, ref = a),                 // 없어도 되는 단계 -> 기다리지 않음
            Step("", false, 0.5f, 0.5f, 500),                             // 기준 화면 없음 -> 시간 기준
        )
        assertTrue(RouteScreens.waitsBefore(steps, 0))
        assertTrue(RouteScreens.waitsBefore(steps, 1))
        assertFalse(RouteScreens.waitsBefore(steps, 2))
        assertFalse(RouteScreens.waitsBefore(steps, 3))
        assertFalse(RouteScreens.waitsBefore(steps, 4))
        assertFalse(RouteScreens.waitsBefore(steps, 9))
    }

    @Test
    fun endScreenIsOnlyUsedWhenRecorded() {
        assertNull(RouteScreens.endGrid(listOf(Step("", false, 0.1f, 0.1f))))
        assertNull(RouteScreens.endGrid(emptyList()))
        assertNotNull(RouteScreens.endGrid(listOf(Step("", false, 0.1f, 0.1f, null, null, null, null, a, b))))
    }
}
