package app.ticketing.couponmacro

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** LIVE: 오픈 시각까지 대기 후 클릭 / NOW: 대기 없이 즉시 클릭(리허설) / DRY: 찾기만, 클릭 안 함 */
enum class Mode { LIVE, NOW, DRY }

data class Step(val text: String, val optional: Boolean)

data class Job(
    val name: String,
    val pkg: String,
    val launchApp: Boolean,
    val openAtMillis: Long,
    val leadMs: Long,
    val intervalMs: Long,
    val maxAttempts: Int,
    val resultWaitMs: Long,
    val steps: List<Step>,
    val popups: List<String>,
    val targets: List<String>,
    val successTexts: List<String>,
    val alreadyTexts: List<String>,
    val soldoutTexts: List<String>,
    val refreshOnMiss: Boolean,
) {
    val hasResultTexts: Boolean
        get() = successTexts.isNotEmpty() || alreadyTexts.isNotEmpty() || soldoutTexts.isNotEmpty()
}

object Parse {
    val ZONE: ZoneId = ZoneId.of("Asia/Seoul")
    private val FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm[:ss]")

    /** 쉼표/줄바꿈으로 구분된 문자열 목록 */
    fun list(s: String): List<String> =
        s.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }

    /** 한 줄에 하나. 앞에 ? 를 붙이면 optional(못 찾아도 계속). */
    fun steps(s: String): List<Step> =
        s.lines().map { it.trim() }.filter { it.isNotEmpty() }
            .map { if (it.startsWith("?")) Step(it.removePrefix("?").trim(), true) else Step(it, false) }
            .filter { it.text.isNotEmpty() }

    /** "yyyy-MM-dd HH:mm[:ss]" (KST) -> epoch ms */
    fun openAt(s: String): Long =
        LocalDateTime.parse(s.trim(), FMT).atZone(ZONE).toInstant().toEpochMilli()
}

class Parsed(val job: Job?, val error: String?)

/** 화면 입력값(문자열) 그대로의 상태. toJob 으로 검증/변환. */
data class FormState(
    val name: String = "",
    val pkg: String = "",
    val launchApp: Boolean = true,
    val openAt: String = "",
    val leadMs: String = "100",
    val intervalMs: String = "100",
    val maxAttempts: String = "100",
    val resultWaitSec: String = "3",
    val steps: String = "",
    val popups: String = "닫기, 나중에",
    val targets: String = "",
    val success: String = "",
    val already: String = "",
    val soldout: String = "",
    val refreshOnMiss: Boolean = true,
) {
    fun toJob(requireOpenAt: Boolean): Parsed {
        if (pkg.isBlank()) return Parsed(null, "패키지명이 비어있음 ([앱 선택] 사용)")
        val targetList = Parse.list(targets)
        if (targetList.isEmpty()) return Parsed(null, "받기 버튼 문구가 비어있음")
        val lead = leadMs.trim().toLongOrNull() ?: return Parsed(null, "lead(ms) 는 숫자")
        val interval = intervalMs.trim().toLongOrNull()?.takeIf { it >= 10 } ?: return Parsed(null, "간격(ms) 는 10 이상 숫자")
        val attempts = maxAttempts.trim().toIntOrNull()?.takeIf { it >= 1 } ?: return Parsed(null, "최대 시도는 1 이상 숫자")
        val waitSec = resultWaitSec.trim().toDoubleOrNull()?.takeIf { it >= 0 } ?: return Parsed(null, "결과 대기(초) 는 숫자")
        val openMs = if (openAt.isBlank() && !requireOpenAt) {
            0L
        } else {
            try {
                Parse.openAt(openAt)
            } catch (e: Exception) {
                return Parsed(null, "오픈 시각 형식 오류 — 예: 2026-11-05 10:00:00")
            }
        }
        return Parsed(
            Job(
                name = name.ifBlank { pkg },
                pkg = pkg.trim(),
                launchApp = launchApp,
                openAtMillis = openMs,
                leadMs = lead,
                intervalMs = interval,
                maxAttempts = attempts,
                resultWaitMs = (waitSec * 1000).toLong(),
                steps = Parse.steps(steps),
                popups = Parse.list(popups),
                targets = targetList,
                successTexts = Parse.list(success),
                alreadyTexts = Parse.list(already),
                soldoutTexts = Parse.list(soldout),
                refreshOnMiss = refreshOnMiss,
            ),
            null,
        )
    }
}

/** 시작용 기본값. 문구/경로는 예시이므로 [화면 요소 덤프]로 실제 문구 확인 후 수정할 것. */
object Presets {
    val COUPANG = FormState(
        name = "쿠팡 와우멤버데이",
        pkg = "com.coupang.mobile",
        targets = "쿠폰 받기, 쿠폰받기, 쿠폰 다운로드",
        success = "발급, 받기 완료, 다운로드 완료",
        already = "이미 받, 이미 발급",
        soldout = "소진, 마감",
    )
    val NAVER_PLUS = FormState(
        name = "네이버 N+ 녭다세일",
        pkg = "",
        targets = "쿠폰 받기, 쿠폰받기, 쿠폰 다운로드",
        success = "발급, 받기 완료, 다운로드 완료",
        already = "이미 받, 이미 발급",
        soldout = "소진, 마감",
    )
}
