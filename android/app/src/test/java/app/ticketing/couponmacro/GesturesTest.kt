package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class GesturesTest {
    private val slop = 16f

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
