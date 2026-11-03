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

    /** 손가락이 지나는 한 구간 */
    data class Seg(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val durMs: Long)

    /** 손가락을 떼기 직전 마지막 구간의 길이(ms): 안드로이드는 뗄 때의 속도를 마지막 약 0.1초 움직임으로 계산한다 */
    const val TAIL_MS = 100L

    /**
     * 스크롤을 '빠르게 하되 스크롤 거리는 그대로' 재생하기 위한 구간 나누기.
     * 스크롤 거리는 손가락이 움직인 거리 + 뗀 뒤 관성(fling)으로 정해지고, 관성은 뗄 때의 속도가 정한다.
     * 전체를 빠르게 하면 뗄 때 속도가 커져 관성이 길어지므로(더 멀리 넘어감),
     *  - 앞부분(마지막 TAIL_MS 전까지)만 speed 배 빠르게 움직이고
     *  - 마지막 TAIL_MS 는 기록한 평균 속도 그대로 움직인다 -> 뗄 때 속도(= 관성)가 기록과 같다.
     * 같은 손가락이 이어서 움직이므로 두 구간의 끝점/시작점은 같다. speed 가 1 이하이거나 너무 짧은 동작은 한 구간.
     */
    fun swipeSegments(x1: Float, y1: Float, x2: Float, y2: Float, recordedMs: Long?, speed: Float): List<Seg> {
        val rec = (recordedMs ?: 300L).coerceIn(80L, 2_000L)
        val sp = speed.coerceAtLeast(0.1f)
        if (sp <= 1.05f || rec <= TAIL_MS * 2) return listOf(Seg(x1, y1, x2, y2, replayDurationMs(recordedMs, sp)))
        val frac = TAIL_MS.toFloat() / rec
        val px = x2 - (x2 - x1) * frac
        val py = y2 - (y2 - y1) * frac
        val mainMs = ((rec - TAIL_MS) / sp).toLong().coerceAtLeast(60L)
        return listOf(Seg(x1, y1, px, py, mainMs), Seg(px, py, x2, y2, TAIL_MS))
    }

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
