package app.ticketing.couponmacro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.os.PowerManager
import android.provider.Settings

/** 알림: 결과 알림 / 1분 전 알람(소리 + 진동 + 화면 켜기). */
object Alerts {
    private const val CH_RESULT = "result"
    private const val CH_ALARM = "alarm"

    private fun nm(ctx: Context) = ctx.getSystemService(NotificationManager::class.java)

    private fun ensureChannels(ctx: Context) {
        val m = nm(ctx)
        m.createNotificationChannel(NotificationChannel(CH_RESULT, "결과 알림", NotificationManager.IMPORTANCE_HIGH))
        // 알람용: 무음/진동 모드에서도 울리도록 '알람' 용도의 소리를 쓴다
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ALARM)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        m.createNotificationChannel(
            NotificationChannel(CH_ALARM, "회차 1분 전 알람", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "정해진 시각 1분 전에 잠금 해제를 알립니다"
                setSound(Settings.System.DEFAULT_ALARM_ALERT_URI, attrs)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 600, 300, 600, 300, 600)
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            },
        )
    }

    private fun openApp(ctx: Context): PendingIntent =
        PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)

    /** 작업 결과/안내 */
    fun result(ctx: Context, title: String, text: String) {
        try {
            ensureChannels(ctx)
            val n = Notification.Builder(ctx, CH_RESULT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .build()
            nm(ctx).notify(1, n)
        } catch (e: Exception) {
            LogStore.add("알림 실패: $e")
        }
    }

    /** 1분 전 알람: 소리 + 진동 + 화면 켜기. 잠금 해제를 재촉할 때도 사용 */
    fun warn(ctx: Context, title: String, text: String) {
        try {
            ensureChannels(ctx)
            wake(ctx)
            val n = Notification.Builder(ctx, CH_ALARM)
                .setSmallIcon(android.R.drawable.ic_lock_idle_alarm)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setCategory(Notification.CATEGORY_ALARM)
                .setContentIntent(openApp(ctx))
                .setAutoCancel(true)
                .setTimeoutAfter(70_000)
                .build()
            nm(ctx).notify(2, n)
        } catch (e: Exception) {
            LogStore.add("알람 알림 실패: $e")
        }
    }

    /** 꺼진 화면을 켠다(잠금 화면까지). 기기에 따라 제한될 수 있음 */
    @Suppress("DEPRECATION")
    private fun wake(ctx: Context) {
        try {
            val pm = ctx.getSystemService(Context.POWER_SERVICE) as PowerManager
            pm.newWakeLock(
                PowerManager.FULL_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "couponmacro:warn",
            ).acquire(15_000)
        } catch (_: Exception) {
        }
    }
}
