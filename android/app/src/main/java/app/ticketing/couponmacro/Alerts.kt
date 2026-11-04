package app.ticketing.couponmacro

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings

/** 알림: 결과 알림 / 회차 전 알람(소리 + 진동 + 화면 켜기, 이어폰이 연결돼 있으면 이어폰으로도 소리). */
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
            NotificationChannel(CH_ALARM, "회차 전 알람", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "정해진 시각 전(설정한 시점)에 잠금 해제를 알립니다"
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

    /**
     * 회차 전 알람: 소리 + 진동 + 화면 켜기. 잠금 해제를 재촉할 때도 사용.
     * 이어폰(유선/USB/블루투스)이 연결돼 있으면 이어폰으로도 소리를 낸다(playEarphoneSound).
     * @return 이어폰으로 소리를 냈으면 이어폰 이름, 아니면 null
     */
    fun warn(ctx: Context, title: String, text: String, timeoutMs: Long = 70_000): String? {
        appCtx = ctx.applicationContext
        val ear = playEarphoneSound(ctx)
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
                .setTimeoutAfter(timeoutMs)
                .build()
            nm(ctx).notify(2, n)
        } catch (e: Exception) {
            LogStore.add("알람 알림 실패: $e")
        }
        return ear
    }

    // ---- 이어폰 소리 ----
    private val main = Handler(Looper.getMainLooper())
    private var player: MediaPlayer? = null
    private var focusReq: AudioFocusRequest? = null
    private var savedVolume = -1
    private val stopRunnable = Runnable { stopSound() }

    /** 연결된 이어폰 이름(없으면 null). 유선/USB/블루투스/보청기 출력 장치를 본다 */
    fun earphoneName(ctx: Context): String? {
        return try {
            val am = ctx.getSystemService(AudioManager::class.java) ?: return null
            val dev: AudioDeviceInfo = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { AudioRoute.isEarphone(it.type) } ?: return null
            dev.productName?.toString()?.takeIf { it.isNotBlank() && it != Build.MODEL } ?: AudioRoute.typeName(dev.type)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 이어폰이 연결돼 있으면 알람음을 '미디어' 출력으로 틀어 이어폰으로 들리게 한다(알람 스트림은 기기에 따라 스피커로만 나옴).
     * 미디어 음량이 너무 낮으면 최대의 절반으로 올렸다가 끝나면 원래대로 되돌린다. seconds 초 뒤에 자동으로 멈춘다.
     * @return 소리를 낸 이어폰 이름 / 이어폰이 없으면 null
     */
    fun playEarphoneSound(ctx: Context, seconds: Int = 8): String? {
        val name = earphoneName(ctx) ?: return null
        val app = ctx.applicationContext
        main.post { startSound(app, seconds) }
        return name
    }

    private fun startSound(ctx: Context, seconds: Int) {
        stopSound()
        try {
            val am = ctx.getSystemService(AudioManager::class.java) ?: return
            val attrs = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            // 다른 소리(음악 등)는 잠깐 줄이거나 멈추게 한다
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).setAudioAttributes(attrs).build()
            am.requestAudioFocus(req)
            focusReq = req
            val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            val cur = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            if (cur < max / 2) {
                try {
                    am.setStreamVolume(AudioManager.STREAM_MUSIC, max / 2, 0)
                    savedVolume = cur
                } catch (e: Exception) {
                    LogStore.add("미디어 음량을 올리지 못했습니다(현재 음량으로 재생): $e") // 방해 금지 모드 등
                }
            }
            try {
                val mp = MediaPlayer()
                mp.setAudioAttributes(attrs)
                mp.setDataSource(ctx, Settings.System.DEFAULT_ALARM_ALERT_URI)
                mp.isLooping = true
                mp.prepare()
                mp.start()
                player = mp
            } catch (e: Exception) {
                // 알람 소리를 못 열면 신호음으로 대신
                LogStore.add("알람음 재생 실패 → 신호음 사용: $e")
                Thread {
                    try {
                        val tg = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
                        repeat(seconds.coerceAtMost(6)) {
                            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 400)
                            Thread.sleep(800)
                        }
                        tg.release()
                    } catch (_: Exception) {
                    }
                }.start()
            }
            main.removeCallbacks(stopRunnable)
            main.postDelayed(stopRunnable, seconds * 1000L)
        } catch (e: Exception) {
            LogStore.add("이어폰 소리 실패: $e")
        }
    }

    /** 이어폰 소리를 멈추고 음량/오디오 포커스를 되돌린다 */
    fun stopSound() {
        main.removeCallbacks(stopRunnable)
        val ctx = appCtx
        try {
            player?.let {
                try {
                    it.stop()
                } catch (_: Exception) {
                }
                it.release()
            }
        } catch (_: Exception) {
        }
        player = null
        try {
            if (ctx != null) {
                val am = ctx.getSystemService(AudioManager::class.java)
                if (am != null) {
                    if (savedVolume >= 0) am.setStreamVolume(AudioManager.STREAM_MUSIC, savedVolume, 0)
                    focusReq?.let { am.abandonAudioFocusRequest(it) }
                }
            }
        } catch (_: Exception) {
        }
        savedVolume = -1
        focusReq = null
    }

    private var appCtx: Context? = null

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
