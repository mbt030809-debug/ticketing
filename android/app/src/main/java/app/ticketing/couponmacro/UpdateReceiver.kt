package app.ticketing.couponmacro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build

/** PackageInstaller 세션 결과 수신: 사용자 확인 화면 띄우기 / 결과 로그. */
class UpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, -1)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm: Intent? = if (Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirm != null) {
                    confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirm)
                } else {
                    LogStore.add("업데이트: 확인 화면 인텐트 없음")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> LogStore.add("업데이트 설치 완료")
            else -> LogStore.add(
                "업데이트 실패($status): ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)}",
            )
        }
    }

    companion object {
        const val ACTION = "app.ticketing.couponmacro.UPDATE_RESULT"
    }
}
