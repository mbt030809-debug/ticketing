package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesTest {
    private val slop = 16f

    @Test
    fun replayedScrollIsFasterThanRecordedByTheSpeedSetting() {
        assertEquals(300L, Gestures.replayDurationMs(600, 2f))      // 기본 2배: 기록의 절반 시간
        assertEquals(600L, Gestures.replayDurationMs(600, 1f))      // 1배 = 기록한 속도 그대로
        assertEquals(150L, Gestures.replayDurationMs(null, 2f))     // 기록이 없으면 0.3초 기준
        assertEquals(80L, Gestures.replayDurationMs(100, 4f))       // 너무 짧아지지 않게
        assertEquals(2_000L, Gestures.replayDurationMs(5_000, 1f))  // 너무 길어지지 않게
        assertTrue(Gestures.replayDurationMs(800, Gestures.DEFAULT_SWIPE_SPEED) < 800)
    }

    @Test
    fun fasterScrollKeepsTheReleaseSpeedSoTheScrollDistanceStaysTheSame() {
        // 위로 1000px 를 0.6초에 쓸어 올린 스크롤을 2배 빠르게
        val segs = Gestures.swipeSegments(500f, 1800f, 500f, 800f, 600, 2f)
        assertEquals(2, segs.size)
        val (main, tail) = segs
        // 손가락은 떼지 않고 이어진다: 앞 구간 끝 = 뒤 구간 시작, 전체 시작/끝은 기록과 같다
        assertEquals(main.x2, tail.x1, 0f)
        assertEquals(main.y2, tail.y1, 0f)
        assertEquals(1800f, main.y1, 0.01f)
        assertEquals(800f, tail.y2, 0.01f)
        // 뗄 때 속도(마지막 구간) = 기록의 평균 속도 -> 관성이 같아 스크롤 거리가 같다
        val originalSpeed = 1000f / 600f                       // px/ms
        val tailSpeed = (tail.y1 - tail.y2) / tail.durMs
        assertEquals(originalSpeed, tailSpeed, 0.01f)
        // 앞 구간은 2배 빠르게 움직인다 -> 전체 시간은 줄어든다
        val mainSpeed = (main.y1 - main.y2) / main.durMs
        assertEquals(originalSpeed * 2, mainSpeed, 0.05f)
        assertTrue(segs.sumOf { it.durMs } < 600)
        // 손가락 이동 거리 합 = 기록과 같은 1000px
        assertEquals(1000f, (main.y1 - main.y2) + (tail.y1 - tail.y2), 0.01f)
    }

    @Test
    fun slowOrShortScrollsStaySingleStroke() {
        assertEquals(1, Gestures.swipeSegments(0f, 0f, 0f, 500f, 600, 1f).size)   // 1배: 기록 그대로
        assertEquals(1, Gestures.swipeSegments(0f, 0f, 0f, 500f, 600, 0.5f).size) // 느리게
        assertEquals(1, Gestures.swipeSegments(0f, 0f, 0f, 500f, 150, 3f).size)   // 너무 짧은 동작
        assertEquals(1200L, Gestures.swipeSegments(0f, 0f, 0f, 500f, 600, 0.5f)[0].durMs)
        assertEquals(2, Gestures.swipeSegments(0f, 0f, 0f, 500f, 600, 8f).size)
    }

    @Test
    fun emptyTouchIsIgnored() {
        assertNull(Gestures.classify(emptyList(), slop))
    }

    @Test
    fun shortPressWithinSlopIsTapAtStartPoint() {
        val g = Gestures.classify(
            listOf(TouchPoint(100f, 200f, 1_000), TouchPoint(104f, 203f, 1_060), TouchPoint(103f, 202f, 1_090)),
            slop,
        )
        assertNotNull(g)
        assertEquals(GestureKind.TAP, g!!.kind)
        assertEquals(100f, g.x1, 0f)
        assertEquals(200f, g.y1, 0f)
        assertEquals(100f, g.x2, 0f)
        assertEquals(Gestures.TAP_MS, g.durMs) // 짧게 누른 건 일정한 짧은 탭으로
    }

    @Test
    fun singleDownUpPointIsTap() {
        val g = Gestures.classify(listOf(TouchPoint(10f, 20f, 5)), slop)!!
        assertEquals(GestureKind.TAP, g.kind)
        assertEquals(Gestures.TAP_MS, g.durMs)
    }

    @Test
    fun holdingLongerThanHalfSecondIsLongPress() {
        val g = Gestures.classify(listOf(TouchPoint(50f, 60f, 0), TouchPoint(51f, 60f, 800)), slop)!!
        assertEquals(GestureKind.TAP, g.kind)
        assertEquals(800L, g.durMs)
        // 너무 오래 누른 건 3초로 제한
        val long = Gestures.classify(listOf(TouchPoint(50f, 60f, 0), TouchPoint(50f, 60f, 9_000)), slop)!!
        assertEquals(3_000L, long.durMs)
    }

    @Test
    fun movingBeyondSlopIsSwipeFromStartToEnd() {
        val g = Gestures.classify(
            listOf(TouchPoint(500f, 1400f, 0), TouchPoint(500f, 1000f, 150), TouchPoint(505f, 600f, 300)),
            slop,
        )!!
        assertEquals(GestureKind.SWIPE, g.kind)
        assertEquals(500f, g.x1, 0f)
        assertEquals(1400f, g.y1, 0f)
        assertEquals(505f, g.x2, 0f)
        assertEquals(600f, g.y2, 0f)
        assertEquals(300L, g.durMs)
    }

    @Test
    fun swipeDurationIsClamped() {
        val fast = Gestures.classify(listOf(TouchPoint(0f, 0f, 0), TouchPoint(300f, 0f, 20)), slop)!!
        assertEquals(80L, fast.durMs)
        val slow = Gestures.classify(listOf(TouchPoint(0f, 0f, 0), TouchPoint(300f, 0f, 9_000)), slop)!!
        assertEquals(2_000L, slow.durMs)
    }

    @Test
    fun wanderingAndReturningStillCountsAsSwipeByMaxMove() {
        // 멀리 갔다가 제자리로 돌아와도 한 번 slop 을 넘었으면 스와이프(끝점이 시작점과 같음)
        val g = Gestures.classify(
            listOf(TouchPoint(100f, 100f, 0), TouchPoint(300f, 100f, 100), TouchPoint(100f, 100f, 200)),
            slop,
        )!!
        assertEquals(GestureKind.SWIPE, g.kind)
    }
}
