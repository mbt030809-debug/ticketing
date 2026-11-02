package app.ticketing.couponmacro

/**
 * 한 번의 실행에서 '어떤 단계를 하는지'를 모드/설정별로 한곳에 모아 둔 계획.
 * 실기기 없이도 단위 테스트로 흐름(경로 이동 여부, 새로고침 여부 등)을 고정해 두기 위해 순수 함수로 분리했다.
 */
data class FlowPlan(
    /** 예약 실행의 기본 흐름: 오픈 N초 전 앱 실행 → 정각에 경로 이동 → 쿠폰 받기 */
    val atOpen: Boolean,
    /** 대상 앱을 앞으로 가져올지 */
    val launch: Boolean,
    /** 앱을 첫 화면에서 새로 시작할지(경로를 따라갈 때) / 아니면 보던 화면 유지 */
    val freshStart: Boolean,
    val replayRoute: Boolean,
    /** 경로 단계마다 화면 로딩 완료를 기다릴지 (정각 이동은 속도 우선이라 안 기다림) */
    val waitLoadedBetweenSteps: Boolean,
    /** 오픈 시각까지 기다릴지 (예약 실행만) */
    val waitForOpen: Boolean,
    /** 오픈 전에 OCR 로 쿠폰 위치를 미리 계산할지 (미리 이동해 두는 방식만) */
    val prelocateOcr: Boolean,
    /** 오픈 3초 전 새로고침 (미리 이동해 두는 방식만) */
    val preRefresh: Boolean,
    /** 쿠폰이 안 보일 때 화면을 아래로 당겨 새로고침 (예약 실행만 — 테스트 중에는 화면을 밀지 않는다) */
    val pullRefreshOnMiss: Boolean,
)

data class Timeline(val launchAtMs: Long, val fireAtMs: Long)

object Flow {
    fun plan(job: Job, mode: Mode, appInFront: Boolean): FlowPlan {
        val live = mode == Mode.LIVE
        val hasRoute = job.steps.isNotEmpty()
        val atOpen = live && job.routeAtOpen
        return FlowPlan(
            atOpen = atOpen,
            launch = if (live) job.launchApp else (hasRoute || !appInFront),
            freshStart = hasRoute,
            replayRoute = hasRoute,
            waitLoadedBetweenSteps = !atOpen,
            waitForOpen = live,
            prelocateOcr = live && !atOpen && job.coupons.isNotEmpty(),
            preRefresh = live && !atOpen && job.refreshOnMiss,
            pullRefreshOnMiss = live && job.refreshOnMiss,
        )
    }

    /** 정각 이동 흐름의 시각표: 앱 실행 시각 / 경로 이동(클릭) 시작 시각 */
    fun timeline(job: Job): Timeline = Timeline(job.openAtMillis - job.launchLeadMs, job.openAtMillis - job.leadMs)

    /** 예약 실행 동안 CPU 를 깨어 있게 할 시간: 오픈까지 + 15분(최소 1시간, 최대 24시간) */
    fun wakeLockMs(job: Job, mode: Mode, nowMs: Long): Long {
        val hour = 60 * 60 * 1000L
        if (mode != Mode.LIVE) return hour
        return maxOf(hour, job.openAtMillis - nowMs + 15 * 60 * 1000L).coerceAtMost(24 * hour)
    }
}
