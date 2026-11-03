package app.ticketing.couponmacro

/** [디버그 리포트 복사]: 설정 + 환경 + 최근 로그를 한 번에 복사 (원인 분석용, 비밀 정보 없음) */
object DebugReport {
    data class Env(
        val appVersion: String,
        val sdk: Int,
        val device: String,
        val accessibilityOn: Boolean,
        val running: Boolean,
    )

    fun build(env: Env, profileLabel: String, form: FormState, log: String): String {
        val on = Schedule.enabled(form.slots, form.slotsOff).joinToString(", ") { Schedule.label(it) }
        val all = Schedule.parseSlots(form.slots).joinToString(", ") { Schedule.label(it) }
        val steps = Parse.steps(form.steps)
        val coupons = Parse.coupons(form.coupons)
        return buildString {
            appendLine("== 쿠폰 매크로 디버그 리포트 ==")
            appendLine("앱 ${env.appVersion} · 기기 ${env.device} · 안드로이드 API ${env.sdk}")
            appendLine("접근성 서비스: ${if (env.accessibilityOn) "켜짐" else "꺼짐"} · 실행 중: ${if (env.running) "예" else "아니오"}")
            appendLine("이벤트: $profileLabel · 앱: ${form.pkg.ifBlank { "(미지정)" }}")
            appendLine("회차: 전체 [$all] / 켜짐 [$on] · 자동 실행: ${if (form.autoRun) "켜짐(알람 ${form.warnSec}초 전)" else "꺼짐"}")
            appendLine("예약 흐름: ${if (form.routeAtOpen) "오픈 ${form.launchLeadSec}초 전 앱 실행 → 정각 경로 이동" else "미리 이동 후 대기"} · 새로고침 ${if (form.refreshOnMiss) "사용" else "안 함"} · 클릭 점 ${if (form.showDots) "표시" else "숨김"} · 스크롤 속도 ${form.swipeSpeed}배 · 화면 뜬 뒤 대기 ${form.afterScreenSec}초 · 스크롤 뒤 대기 ${form.afterScrollSec}초")
            appendLine("경로 ${steps.size}단계: " + steps.joinToString(" → ") { it.display() })
            appendLine(
                "기준 화면(◎): ${steps.count { it.ref != null }}/${steps.size}단계 · 끝 화면 ${if (steps.lastOrNull()?.endRef != null) "있음" else "없음"}" +
                    if (env.sdk < 30) " · 이 기기(API ${env.sdk})는 화면 캡처 불가 → 시간 기준" else "",
            )
            appendLine("받을 쿠폰 ${coupons.size}개: " + coupons.joinToString(" | "))
            appendLine("받기 버튼 문구: ${form.targets}")
            appendLine("성공/이미/소진 문구: ${form.success} / ${form.already} / ${form.soldout}")
            appendLine("lead ${form.leadMs}ms · 간격 ${form.intervalMs}ms · 최대 ${form.maxAttempts}회 · 결과 대기 ${form.resultWaitSec}초")
            appendLine("---- 최근 로그 (최신순) ----")
            append(log)
        }
    }
}
