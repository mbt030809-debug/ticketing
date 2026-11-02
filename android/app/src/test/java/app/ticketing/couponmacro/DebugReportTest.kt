package app.ticketing.couponmacro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DebugReportTest {
    private val form = FormState(
        pkg = "com.coupang.mobile", steps = "와우\n?닫기\n쿠폰함", coupons = "오후 5시 오픈+10,000원\n위치:오후 5시 오픈|35,120|0.2500,0.5500",
        targets = "받기, 다운로드", slots = "12:00, 18:00, 24:00", slotsOff = "12:00", autoRun = true, warnSec = "60",
    )

    @Test
    fun reportContainsEnvironmentSettingsAndLog() {
        val env = DebugReport.Env("0.1.16 (빌드 16)", 34, "samsung SM-S911N", accessibilityOn = true, running = false)
        val r = DebugReport.build(env, "쿠팡 와우멤버데이", form, "12:00:01 결과: success")
        for (needle in listOf(
            "디버그 리포트", "0.1.16 (빌드 16)", "samsung SM-S911N", "API 34", "접근성 서비스: 켜짐",
            "com.coupang.mobile", "전체 [12:00, 18:00, 24:00] / 켜짐 [18:00, 24:00]", "자동 실행: 켜짐(알람 60초 전)",
            "오픈 5초 전 앱 실행 → 정각 경로 이동", "경로 3단계: 와우 → ?닫기 → 쿠폰함", "받을 쿠폰 2개", "오후 5시 오픈+10,000원",
            "12:00:01 결과: success",
        )) {
            assertTrue("리포트에 '$needle' 가 있어야 함:\n$r", r.contains(needle))
        }
    }

    @Test
    fun reportShowsWhenOcrOrAccessibilityIsUnavailable() {
        val env = DebugReport.Env("0.1.16 (빌드 16)", 29, "x y", accessibilityOn = false, running = true)
        val r = DebugReport.build(env, "N+", form.copy(autoRun = false), "")
        assertTrue(r.contains("접근성 서비스: 꺼짐"))
        assertTrue(r.contains("자동 실행: 꺼짐"))
        assertFalse(r.contains("알람 60초"))
    }
}
