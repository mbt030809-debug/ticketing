package app.ticketing.couponmacro

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/**
 * 매일 자동 실행 예약. 다음 알람 하나만 AlarmManager 에 걸어 두고,
 * 알람이 울릴 때마다(또는 설정 변경/재부팅 때) 다음 것을 다시 계산한다.
 * setAlarmClock: 절전(Doze) 중에도 정확한 시각에 울리고 별도 권한이 필요 없다.
 */
object Scheduler {
    const val ACTION_TRIGGER = "app.ticketing.couponmacro.AUTO_TRIGGER"
    const val EXTRA_PROFILE = "profile"
    const val EXTRA_OCC = "occ"
    private const val REQ = 1001

    fun next(ctx: Context, nowMs: Long = System.currentTimeMillis()): Trigger? =
        Schedule.nextTrigger(Store.autoEntries(ctx), nowMs, Store.handledKeys(ctx))

    private fun pending(ctx: Context, t: Trigger?): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).setAction(ACTION_TRIGGER)
        if (t != null) i.putExtra(EXTRA_PROFILE, t.profileId).putExtra(EXTRA_OCC, t.occurrenceMs)
        return PendingIntent.getBroadcast(ctx, REQ, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** 설정이 바뀌었거나 재부팅/업데이트 직후 등: 다음 알람을 다시 계산해서 등록(없으면 취소) */
    fun rescheduleAll(ctx: Context) {
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val t = next(ctx)
            if (t == null) {
                am.cancel(pending(ctx, null))
                return
            }
            val show = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
            am.setAlarmClock(AlarmManager.AlarmClockInfo(t.triggerAtMs, show), pending(ctx, t))
        } catch (e: Exception) {
            LogStore.add("자동 실행 예약 실패: $e")
        }
    }
}
