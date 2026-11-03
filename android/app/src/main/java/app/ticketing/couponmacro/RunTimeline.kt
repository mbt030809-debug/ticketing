package app.ticketing.couponmacro

import kotlin.math.abs

/**
 * 한 번의 실행에서 각 단계가 '정각(목표 시각)'을 0으로 해서 언제 일어났는지 기록한다 — "정각에 경로로 바로 들어가는지, 몇 초 걸리는지" 확인용.
 * 시간은 NTP 보정 시각(epoch ms) 기준. 실행이 끝나면 로그에 한 줄 요약(summary)과 핵심 수치(stats)를 남긴다.
 */
class RunTimeline(private val t0Ms: Long) {
    data class Mark(val label: String, val relMs: Long)

    private val marks = ArrayList<Mark>()

    fun mark(label: String, trueMs: Long) {
        synchronized(marks) { marks += Mark(label, trueMs - t0Ms) }
    }

    /** 같은 이름이 이미 있으면 무시(첫 번째 시각만) */
    fun markOnce(label: String, trueMs: Long) {
        synchronized(marks) {
            if (marks.none { it.label == label }) marks += Mark(label, trueMs - t0Ms)
        }
    }

    fun all(): List<Mark> = synchronized(marks) { marks.toList() }

    fun rel(label: String): Long? = all().firstOrNull { it.label == label }?.relMs

    /** "앱 실행 -4.98s → 첫 화면 확인 -3.10s → 이동1 +0.03s → …" */
    fun summary(): String = all().joinToString(" → ") { "${it.label} ${fmt(it.relMs)}" }

    /** 핵심 수치: 정각→첫 동작, 정각→경로 끝, 경로에 걸린 시간, 정각→첫 쿠폰 클릭 */
    fun stats(): String {
        val first = rel("이동1")
        val routeEnd = rel("경로 끝")
        val coupon = rel("첫 쿠폰 클릭")
        val parts = ArrayList<String>()
        if (first != null) parts += "정각→첫 동작 ${fmt(first)}"
        if (routeEnd != null) parts += "정각→경로 끝 ${fmt(routeEnd)}" + if (first != null) " (경로 ${secs(routeEnd - first)}초)" else ""
        if (coupon != null) parts += "정각→첫 쿠폰 클릭 ${fmt(coupon)}"
        return if (parts.isEmpty()) "(기록된 단계 없음)" else parts.joinToString(" · ")
    }

    companion object {
        /**
         * 화면에 띄우는 시계의 '초' 값: 정각(오픈 시각)=0 기준, 내림. 앱 시작이 10초 전이면 -10, -9, … -1, 0, 1, 2 …
         * (-10 은 정각 10초 전 ~ 9초 전 사이, 0 은 정각 ~ 1초 뒤)
         */
        fun clockSecond(relMs: Long): Long = Math.floorDiv(relMs, 1000L)

        private fun secs(ms: Long): String = String.format(java.util.Locale.ROOT, "%.2f", abs(ms) / 1000.0)

        /** +1.23s / -4.98s (정각 전은 -, 후는 +) */
        fun fmt(relMs: Long): String = (if (relMs < 0) "-" else "+") + secs(relMs) + "s"
    }
}
