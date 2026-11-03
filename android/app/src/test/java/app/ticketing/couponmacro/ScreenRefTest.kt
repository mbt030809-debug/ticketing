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

    /** (0.5, 0.5) 를 누를 때 그 주변으로 보는 칸들 */
    private fun aroundTap(i: Int): Boolean = (i / ScreenRef.COLS) in 19..21 && (i % ScreenRef.COLS) in 10..14

    /** 누를 자리 주변 15칸 중 3칸만 다른 화면: 전체는 거의 같지만 주변은 80% (거의 똑같다/빠른 인정(85%)에는 못 미침) */
    private val nearRegion = ref.copyOf().also { for (c in 10..12) it[20 * ScreenRef.COLS + c] = (it[20 * ScreenRef.COLS + c] + 100) % 256 }

    /** 누를 자리 주변은 같고 나머지의 k/20 칸이 다른 화면 (k=6 -> 전체 약 70%, 9 -> 56%, 11 -> 46%) */
    private fun outsideChanged(k: Int) = IntArray(ref.size) { i -> if (!aroundTap(i) && i % 20 < k) (ref[i] + 100) % 256 else ref[i] }

    /** 앞 화면(A): 기준 화면과 전체 80% 만 같은 다른 화면 */
    private val prevScreen = IntArray(ref.size) { i -> if (!aroundTap(i) && i % 5 == 0) (ref[i] + 100) % 256 else ref[i] }

    @Test
    fun exactlyTheSameScreenIsAcceptedAtOnce() {
        // 속도: 스크롤이 끝나 기록 때와 똑같아진 화면은 한 번만 봐도 바로 누른다
        val w = ScreenWait(ref, 0.5f, 0.5f, startMs = 0)
        assertEquals(ScreenVerdict.MATCH, w.update(fakePage(0, noise = 8), 100))
    }

    @Test
    fun couponButtonAreaShownIsEnoughWithoutWaitingForFullLoading() {
        // 회귀: 화면 전체 로딩이 끝나길 기다리느라 너무 느렸다. 전환됐고 누를 자리(쿠폰 버튼)가 보이면 나머지(배너 등)가 덜 떠도 바로 누른다
        val cur = outsideChanged(6)
        val s = ScreenRef.score(ref, cur, 0.5f, 0.5f)
        assertTrue("전체 일치가 70% 안팎이어야 함: ${s.global}", s.global in 0.65f..0.75f)
        assertEquals(ScreenVerdict.MATCH, ScreenWait(ref, 0.5f, 0.5f, startMs = 0).update(cur, 100))
    }

    @Test
    fun fastAcceptGetsLooserTheLongerItWaits() {
        // 전체 일치 약 56%: 처음엔 안 되고(60% 필요) 3초 뒤부터 인정(50%)
        val a = ScreenWait(ref, 0.5f, 0.5f, startMs = 0)
        assertEquals(ScreenVerdict.WAIT, a.update(outsideChanged(9), 100))
        assertEquals(ScreenVerdict.WAIT, a.update(outsideChanged(9), ScreenRef.FAST1_AFTER_MS - 100))
        assertEquals(ScreenVerdict.MATCH, a.update(outsideChanged(9), ScreenRef.FAST1_AFTER_MS + 100))
        // 전체 일치 약 46%: 8초 뒤부터 인정(40%)
        val b = ScreenWait(ref, 0.5f, 0.5f, startMs = 0)
        assertEquals(ScreenVerdict.WAIT, b.update(outsideChanged(11), ScreenRef.FAST1_AFTER_MS + 100))
        assertEquals(ScreenVerdict.WAIT, b.update(outsideChanged(11), ScreenRef.FAST2_AFTER_MS - 100))
        assertEquals(ScreenVerdict.MATCH, b.update(outsideChanged(11), ScreenRef.FAST2_AFTER_MS + 100))
    }

    @Test
    fun notYetTransitionedScreenIsNeverTapped() {
        // 아직 앞 화면(A)이 그대로면(누른 게 안 넘어갔거나 렉) 누를 자리가 비슷해 보여도 누르지 않는다
        assertTrue(ScreenRef.score(prevScreen, prevScreen, null, null).global >= ScreenRef.LEFT_PREV)
        val w = ScreenWait(ref, 0.5f, 0.5f, startMs = 0, prev = prevScreen)
        assertEquals(ScreenVerdict.WAIT, w.update(prevScreen, 100))
        assertEquals(ScreenVerdict.WAIT, w.update(prevScreen, 500))
        assertEquals(ScreenVerdict.WAIT, w.update(prevScreen, ScreenRef.FAST2_AFTER_MS + 500)) // 오래 기다려도 앞 화면이면 안 누름
        // 화면이 전환되어 쿠폰 화면이 뜨면 바로
        val cur = outsideChanged(6)
        assertTrue(ScreenRef.score(prevScreen, cur, null, null).global < ScreenRef.LEFT_PREV)
        assertEquals(ScreenVerdict.MATCH, w.update(cur, ScreenRef.FAST2_AFTER_MS + 900))
    }

    @Test
    fun couponScreenWithoutATapPointOnlyNeedsTheTransitionAndRoughMatch() {
        // 기록을 끝낸 순간의 화면(누를 자리 모름): 전환됐고 전체가 대충 맞으면 인정
        val w = ScreenWait(ref, null, null, startMs = 0, prev = prevScreen)
        assertEquals(ScreenVerdict.WAIT, w.update(prevScreen, 100))
        assertEquals(ScreenVerdict.MATCH, w.update(outsideChanged(6), 500))
    }

    @Test
    fun similarButNotExactNeedsTwoConsecutiveMatches() {
        val w = ScreenWait(ref, 0.5f, 0.5f, startMs = 0)
        assertEquals(ScreenVerdict.WAIT, w.update(nearRegion, 100))
        assertEquals(ScreenVerdict.MATCH, w.update(nearRegion, 500))
    }

    @Test
    fun screenStillScrollingNeverMatchesAndTimesOut() {
        // 스크롤 전(맨 위) 화면에서는 누르지 않는다 — 한참 기다리다 시간이 다 되면 어쩔 수 없이 진행
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
        val w = ScreenWait(ref, 0.5f, 0.5f, startMs = 0)
        assertEquals(ScreenVerdict.WAIT, w.update(nearRegion, 100))
        assertEquals(ScreenVerdict.WAIT, w.update(fakePage(2), 500))
        assertEquals(ScreenVerdict.WAIT, w.update(nearRegion, 900))
        assertEquals(ScreenVerdict.MATCH, w.update(nearRegion, 1_300))
    }

    @Test
    fun captureFailureBreaksTheStreak() {
        val w = ScreenWait(ref, 0.5f, 0.5f, startMs = 0)
        assertEquals(ScreenVerdict.WAIT, w.update(nearRegion, 100))
        assertEquals(ScreenVerdict.WAIT, w.update(null, 500))
        assertEquals(ScreenVerdict.WAIT, w.update(nearRegion, 900))
        assertEquals(ScreenVerdict.MATCH, w.update(nearRegion, 1_300))
    }

    @Test
    fun looserMatchOnlyAfterWaitingLong() {
        // 주변은 80%, 전체는 77%: 평소(80%)엔 안 되고 3초 넘게 기다리면 70% 기준으로 연속 2회에 인정
        val cur = IntArray(ref.size) { i -> if (!aroundTap(i) && i % 9 < 2) (ref[i] + 100) % 256 else ref[i] }
            .also { for (c in 10..12) it[20 * ScreenRef.COLS + c] = (it[20 * ScreenRef.COLS + c] + 100) % 256 }
        val w = ScreenWait(ref, 0.5f, 0.5f, startMs = 0)
        val relax = ScreenRef.RELAX_AFTER_MS
        assertEquals(ScreenVerdict.WAIT, w.update(cur, 100))
        assertEquals(ScreenVerdict.WAIT, w.update(cur, relax - 500))
        assertEquals(ScreenVerdict.WAIT, w.update(cur, relax + 100))
        assertEquals(ScreenVerdict.MATCH, w.update(cur, relax + 500))
        assertTrue("완화 구간이 최대 대기 안에 있어야 함", relax + 500 < ScreenRef.MAX_WAIT_MS)
    }

    @Test
    fun laggyScreenIsWaitedForNotTappedBlindly() {
        // 선착순 렉: 화면이 한참 안 떠도(여기선 30초) 눌러 버리지 않고 계속 기다린다
        val w = ScreenWait(ref, 0.5f, 0.5f, startMs = 0)
        var t = 100L
        while (t <= 30_000) {
            assertEquals("t=$t", ScreenVerdict.WAIT, w.update(fakePage(6), t)) // 아직 이전/로딩 화면
            t += 500
        }
        // 마침내 화면이 뜨면 바로 인정
        assertEquals(ScreenVerdict.MATCH, w.update(ref, 30_600))
        assertTrue("충분히 길게 기다려야 함", ScreenRef.MAX_WAIT_MS >= 60_000)
    }

    @Test
    fun tappedNeighborhoodMustAlwaysMatch() {
        // 전체는 거의 같아도 누를 자리 주변이 다르면(다른 버튼이 있음) 누르지 않는다
        val cur = ref.copyOf()
        for (r in 19..21) for (c in 10..14) cur[r * ScreenRef.COLS + c] = (cur[r * ScreenRef.COLS + c] + 100) % 256
        val w = ScreenWait(ref, 0.5f, 0.5f, startMs = 0)
        assertEquals(ScreenVerdict.WAIT, w.update(cur, 100))
        assertEquals(ScreenVerdict.WAIT, w.update(cur, 500))
        assertEquals(ScreenVerdict.WAIT, w.update(cur, ScreenRef.FAST2_AFTER_MS + 100))
        assertEquals(ScreenVerdict.WAIT, w.update(cur, ScreenRef.FAST2_AFTER_MS + 500))
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
    fun scrollStepsAlsoWaitForTheirScreenSoALagDoesNotMakeThemFizzle() {
        // 렉으로 화면이 늦게 뜨는 중에 쓸면 스크롤이 헛돌아 이후 단계가 모두 어긋난다 -> 스크롤도 그 화면이 뜬 뒤에 한다
        val steps = listOf(
            Step("", false, 0.2f, 0.3f, null, ref = a),
            Step("", false, 0.5f, 0.7f, 800, 0.5f, 0.3f, 350, b),   // 스와이프
            Step("", false, 0.5f, 0.6f, 900, ref = a),              // 스크롤 뒤 화면에서 누르기
            Step("", true, 0.5f, 0.7f, 800, 0.5f, 0.3f, 350, b),    // 없어도 되는 스크롤은 기다리지 않음
        )
        assertTrue(RouteScreens.waitsBefore(steps, 0))
        assertTrue(RouteScreens.waitsBefore(steps, 1))
        assertTrue(RouteScreens.waitsBefore(steps, 2))
        assertFalse(RouteScreens.waitsBefore(steps, 3))
    }

    @Test
    fun endScreenIsOnlyUsedWhenRecorded() {
        assertNull(RouteScreens.endGrid(listOf(Step("", false, 0.1f, 0.1f))))
        assertNull(RouteScreens.endGrid(emptyList()))
        assertNotNull(RouteScreens.endGrid(listOf(Step("", false, 0.1f, 0.1f, null, null, null, null, a, b))))
    }
}
