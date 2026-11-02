package app.ticketing.couponmacro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 자동 실행 알람 수신.
 *  - 회차 1분 전(설정값) 알람이 울리면: 잠금 해제 알림(소리·진동·화면 켜기) + 매크로 작업 시작
 *    (작업은 잠금이 풀릴 때까지 기다렸다가 앱 실행 → 경로 이동 → 오픈 시각 클릭)
 *  - 재부팅/앱 업데이트/시각 변경 후에는 알람을 다시 등록
 */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Scheduler.ACTION_TRIGGER) {
            Scheduler.rescheduleAll(context) // BOOT_COMPLETED, MY_PACKAGE_REPLACED, TIME_SET, TIMEZONE_CHANGED
            return
        }
        try {
            onTrigger(context, intent)
        } finally {
            Scheduler.rescheduleAll(context)
        }
    }

    private fun onTrigger(ctx: Context, intent: Intent) {
        val pid = intent.getStringExtra(Scheduler.EXTRA_PROFILE) ?: return
        val occ = intent.getLongExtra(Scheduler.EXTRA_OCC, 0L)
        val now = System.currentTimeMillis()
        Store.markHandled(ctx, "$pid@$occ", now)

        val profile = Presets.byId(pid)
        val form = Store.load(ctx, pid) ?: profile.defaults
        if (!form.autoRun) return // 그 사이 사용자가 자동 실행을 껐음

        // [예약 시작]으로 이미 같은 회차를 실행 중이면 그대로 진행 (건너뛰었다는 알림을 내지 않는다)
        val running = MacroService.instance
        if (running != null && running.isRunning() && running.runningOpenAt() == occ) {
            LogStore.add("[자동] ${profile.label} ${Schedule.describe(occ, now)} — 이미 예약 실행 중이라 그대로 진행")
            return
        }

        val title = "⏰ ${profile.label}"
        Alerts.warn(
            ctx,
            "$title — ${Schedule.describe(occ, now)}",
            "지금 화면 잠금을 해제하고 폰을 켜 두세요. 앱이 자동으로 열려 쿠폰 페이지로 이동합니다.",
        )

        val parsed = form.copy(openAt = Schedule.formatOpenAt(occ)).toJob(requireOpenAt = true)
        val job = parsed.job?.copy(awaitUnlock = true)
        val svc = MacroService.instance
        val problem = when {
            job == null -> "자동 실행 불가: ${parsed.error}"
            svc == null -> "접근성 서비스가 꺼져 있어 자동 실행하지 못했습니다. 접근성에서 '쿠폰 매크로'를 켜 주세요"
            !svc.start(job, Mode.LIVE) -> "다른 작업이 실행 중이라 이번 회차는 건너뜁니다"
            else -> null
        }
        if (problem != null) {
            LogStore.add("[자동] $problem")
            Alerts.result(ctx, title, problem)
        } else {
            LogStore.add("[자동] ${profile.label} ${Schedule.describe(occ, now)} 작업 시작 (잠금 해제 대기)")
        }
    }
}
