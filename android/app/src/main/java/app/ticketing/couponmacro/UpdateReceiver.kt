package app.ticketing.couponmacro

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.widget.Toast

/** PackageInstaller 세션 결과 수신: 사용자 확인 화면 띄우기 / 결과 안내. */
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
            else -> {
                val raw = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()
                val msg = explain(status)
                LogStore.add("업데이트 실패($status): $raw")
                Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            }
        }
    }

    /** 실패 원인을 사용자가 취할 행동으로 풀어서 안내 */
    private fun explain(status: Int): String = when (status) {
        PackageInstaller.STATUS_FAILURE_CONFLICT ->
            "서명이 달라 덮어쓸 수 없습니다. 기존 앱을 삭제한 뒤 최신 APK를 새로 설치하세요 (한 번만 필요)"
        PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
            "이 기기와 호환되지 않는 APK 입니다"
        PackageInstaller.STATUS_FAILURE_STORAGE ->
            "저장 공간이 부족합니다"
        PackageInstaller.STATUS_FAILURE_BLOCKED ->
            "설치가 차단되었습니다 (보안/제한 설정 확인)"
        PackageInstaller.STATUS_FAILURE_ABORTED ->
            "설치를 취소했습니다"
        else -> "업데이트 실패 ($status) — [로그]를 확인하세요"
    }

    companion object {
        const val ACTION = "app.ticketing.couponmacro.UPDATE_RESULT"
    }
}
