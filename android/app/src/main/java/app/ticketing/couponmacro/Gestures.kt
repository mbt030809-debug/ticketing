package app.ticketing.couponmacro

import kotlin.math.hypot

/** 화면 터치 한 점: 화면 좌표(px)와 시각(ms) */
data class TouchPoint(val x: Float, val y: Float, val t: Long)

enum class GestureKind { TAP, SWIPE }

/** 사용자가 한 번 한 터치 동작: 탭(길게 누르면 durMs 가 길어짐) 또는 스와이프 */
data class RecordedGesture(val kind: GestureKind, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durMs: Long)

object Gestures {
    const val TAP_MS = 40L
    const val LONG_PRESS_MS = 500L

    /** 스와이프(스크롤) 재생 속도 배율 기본값: 기록한 속도의 2배 */
    const val DEFAULT_SWIPE_SPEED = 2f

    /** 재생할 스와이프 시간: 기록한 시간을 speed 배 빠르게 (80~2000ms). 기록이 없으면 0.3초 기준 */
    fun replayDurationMs(recordedMs: Long?, speed: Float): Long =
        ((recordedMs ?: 300L) / speed.coerceAtLeast(0.1f)).toLong().coerceIn(80L, 2_000L)

    /**
     * ACTION_DOWN~UP 사이의 터치 궤적을 탭/스와이프로 구분한다.
     * 시작점에서 slopPx 이상 움직이지 않았으면 탭(0.5초 넘게 눌렀으면 길게 누르기), 아니면 시작점→끝점 스와이프.
     */
    fun classify(points: List<TouchPoint>, slopPx: Float): RecordedGesture? {
        if (points.isEmpty()) return null
        val first = points.first()
        val last = points.last()
        val dur = (last.t - first.t).coerceAtLeast(0)
        val maxMove = points.maxOf { hypot((it.x - first.x).toDouble(), (it.y - first.y).toDouble()) }
        return if (maxMove <= slopPx) {
            RecordedGesture(GestureKind.TAP, first.x, first.y, first.x, first.y, if (dur >= LONG_PRESS_MS) dur.coerceAtMost(3_000) else TAP_MS)
        } else {
            RecordedGesture(GestureKind.SWIPE, first.x, first.y, last.x, last.y, dur.coerceIn(80, 2_000))
        }
    }
}
