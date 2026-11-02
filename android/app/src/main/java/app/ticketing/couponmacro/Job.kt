package app.ticketing.couponmacro

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

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
    /** 받을 쿠폰 문구들 (카드 안에 적힌 문구, +로 이으면 모두 포함). 이 카드의 버튼만 누른다 */
    val coupons: List<String>,
    val successTexts: List<String>,
    val alreadyTexts: List<String>,
    val soldoutTexts: List<String>,
    val refreshOnMiss: Boolean,
    /** 클릭한 위치에 빨간 점(찾기만: 초록 점)을 잠깐 표시 */
    val showDots: Boolean,
    /** 자동 실행(알람)으로 시작된 작업: 화면 잠금이 풀릴 때까지 기다린 뒤 진행 */
    val awaitUnlock: Boolean = false,
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

    /** 받을 쿠폰: 한 줄(또는 ;)에 하나. 금액의 쉼표(5,000원)가 있어 쉼표로는 나누지 않는다. */
    fun coupons(s: String): List<String> =
        s.split('\n', ';').map { it.trim() }.filter { it.isNotEmpty() }

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

/** 자동 실행 후보: 어떤 이벤트(프로필)의 어느 회차(하루 중 초)를 몇 초 전에 알릴지 */
data class AutoEntry(val profileId: String, val slotSec: Int, val warnSec: Int)

/** 다음 알람: occurrenceMs=오픈 시각, triggerAtMs=알람(작업 시작) 시각 */
data class Trigger(val profileId: String, val occurrenceMs: Long, val triggerAtMs: Long, val warnSec: Int) {
    val key: String get() = "$profileId@$occurrenceMs"
}

/** 매일 반복되는 회차 시각(KST) 계산. 24:00 은 자정(00:00)으로 취급. */
object Schedule {
    const val AUTO = "auto"
    const val DEFAULT_SLOTS = "12:00, 18:00, 24:00"

    /** 회차 시각 직후 이만큼은 '진행 중'으로 보고 바로 시도한다 (조금 늦게 시작해도 그 회차를 노림) */
    const val GRACE_MS = 120_000L

    private val SLOT_RE = Regex("^(\\d{1,2}):(\\d{2})(?::(\\d{2}))?$")

    /** "HH:mm[:ss]" -> 하루 중 초(0..86399). 24:00 은 0. 형식 오류는 null. */
    fun parseSlot(s: String): Int? {
        val m = SLOT_RE.matchEntire(s.trim()) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        val sec = m.groupValues[3].ifEmpty { "0" }.toInt()
        if (min > 59 || sec > 59 || h > 24 || (h == 24 && (min != 0 || sec != 0))) return null
        return (h % 24) * 3600 + min * 60 + sec
    }

    /** 쉼표/줄바꿈 구분 문자열 -> 정렬·중복 제거된 슬롯(초). 잘못된 항목은 무시. */
    fun parseSlots(s: String): List<Int> = Parse.list(s).mapNotNull { parseSlot(it) }.distinct().sorted()

    /** 켜진 회차 = slots 중 off(꺼 둔 시각)를 뺀 것 */
    fun enabled(slots: String, off: String): List<Int> {
        val offSet = parseSlots(off).toSet()
        return parseSlots(slots).filter { it !in offSet }
    }

    /** epoch ms -> "yyyy-MM-dd HH:mm:ss" (KST). FormState.openAt 형식 */
    fun formatOpenAt(ms: Long): String =
        Instant.ofEpochMilli(ms).atZone(Parse.ZONE).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))

    /** 오픈 이만큼 이내로 임박했으면 자동 실행을 건너뜀 (준비할 시간이 없음) */
    const val MIN_LEAD_MS = 10_000L

    /**
     * 자동 실행: 다음에 울릴 알람. 매일 반복(오늘/내일 후보 중 가장 이른 것).
     * handled 에 있는 회차(이미 알람이 울린 것)는 건너뛴다. 알람 시각이 이미 지났지만 아직 오픈 전이면 곧바로(1.5초 뒤) 실행.
     */
    fun nextTrigger(entries: List<AutoEntry>, nowMs: Long, handled: Set<String>): Trigger? {
        val zone = Parse.ZONE
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        var best: Trigger? = null
        for (e in entries) {
            for (d in -1L..1L) {
                val occ = today.plusDays(d).atStartOfDay(zone).plusSeconds(e.slotSec.toLong()).toInstant().toEpochMilli()
                if (occ - MIN_LEAD_MS <= nowMs) continue
                if ("${e.profileId}@$occ" in handled) continue
                val t = Trigger(e.profileId, occ, maxOf(occ - e.warnSec * 1000L, nowMs + 1500), e.warnSec)
                val b = best
                if (b == null || t.triggerAtMs < b.triggerAtMs || (t.triggerAtMs == b.triggerAtMs && t.occurrenceMs < b.occurrenceMs)) best = t
            }
        }
        return best
    }

    /** 자정은 24:00 으로 표기 */
    fun label(sec: Int): String {
        if (sec == 0) return "24:00"
        val h = sec / 3600
        val m = sec % 3600 / 60
        val s = sec % 60
        return if (s == 0) "%02d:%02d".format(h, m) else "%02d:%02d:%02d".format(h, m, s)
    }

    /**
     * 다음 오픈 시각(epoch ms). selected 가 "auto" 면 slotSecs 중 가장 가까운 회차,
     * 아니면 selected 한 회차. GRACE_MS 이내로 지난 회차는 '지금 진행 중'으로 그대로 반환.
     */
    fun nextOpen(slotSecs: List<Int>, selected: String, nowMs: Long): Long? {
        val targets = if (selected == AUTO) slotSecs else listOfNotNull(parseSlot(selected))
        if (targets.isEmpty()) return null
        val zone = Parse.ZONE
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val candidates = ArrayList<Long>()
        for (d in -1L..1L) {
            val start = today.plusDays(d).atStartOfDay(zone)
            for (s in targets) candidates += start.plusSeconds(s.toLong()).toInstant().toEpochMilli()
        }
        return candidates.filter { it + GRACE_MS >= nowMs }.minOrNull()
    }

    /** 예: "오늘 18:00 (2시간 14분 뒤)" */
    fun describe(openMs: Long, nowMs: Long): String {
        val zone = Parse.ZONE
        val t = Instant.ofEpochMilli(openMs).atZone(zone)
        val today = Instant.ofEpochMilli(nowMs).atZone(zone).toLocalDate()
        val day = when (ChronoUnit.DAYS.between(today, t.toLocalDate())) {
            0L -> "오늘"
            1L -> "내일"
            else -> "${t.monthValue}/${t.dayOfMonth}"
        }
        val sec = t.hour * 3600 + t.minute * 60 + t.second
        val clock = if (sec == 0) "00:00(24시)" else label(sec)
        val diff = (openMs - nowMs) / 1000
        val remain = when {
            diff < 0 -> "진행 중"
            diff >= 3600 -> "${diff / 3600}시간 ${diff % 3600 / 60}분 뒤"
            diff >= 60 -> "${diff / 60}분 뒤"
            else -> "${diff}초 뒤"
        }
        return "$day $clock ($remain)"
    }
}

/** 화면 입력값(문자열) 그대로의 상태. toJob 으로 검증/변환. */
data class FormState(
    val name: String = "",
    val pkg: String = "",
    val launchApp: Boolean = true,
    val openAt: String = "",
    val leadMs: String = "100",
    val intervalMs: String = "100",
    val maxAttempts: String = "200",
    val resultWaitSec: String = "3",
    val steps: String = "",
    val popups: String = "닫기, 나중에",
    val targets: String = "",
    /** 받을 쿠폰(필수): 한 줄에 하나 */
    val coupons: String = "",
    val success: String = "",
    val already: String = "",
    val soldout: String = "",
    val refreshOnMiss: Boolean = true,
    /** 매일 반복되는 회차 시각(쉼표 구분, 24시간제, 24:00 = 자정). 개수 제한 없음 */
    val slots: String = Schedule.DEFAULT_SLOTS,
    /** slots 중 꺼 둔(사용 안 함) 시각 */
    val slotsOff: String = "",
    /** 켜진 회차가 되면 매일 자동 실행 (+ 1분 전 잠금 해제 알람) */
    val autoRun: Boolean = false,
    /** 자동 실행 알람을 회차 몇 초 전에 울릴지 (20~600) */
    val warnSec: String = "60",
    /** 클릭 위치 표시(빨간 점) */
    val showDots: Boolean = true,
) {
    /**
     * openAt 이 있으면 그 날짜/시각을, 없으면 켜진 회차(slots - slotsOff) 중 다음 오픈 시각을 계산한다.
     * (UI 는 회차 방식만 사용하고 openAt 은 비워 둔다)
     */
    fun toJob(requireOpenAt: Boolean, nowMs: Long = System.currentTimeMillis(), requireCoupons: Boolean = true): Parsed {
        if (pkg.isBlank()) return Parsed(null, "패키지명이 비어있음 ([앱 선택] 사용)")
        val targetList = Parse.list(targets)
        val couponList = Parse.coupons(coupons)
        if (requireCoupons && couponList.isEmpty()) {
            return Parsed(null, "받을 쿠폰을 정하세요 (쿠폰 카드에 적힌 문구, 예: 5,000원)")
        }
        if (targetList.isEmpty()) return Parsed(null, "받기 버튼 문구가 비어있음")
        val lead = leadMs.trim().toLongOrNull() ?: return Parsed(null, "lead(ms) 는 숫자")
        val interval = intervalMs.trim().toLongOrNull()?.takeIf { it >= 10 } ?: return Parsed(null, "간격(ms) 는 10 이상 숫자")
        val attempts = maxAttempts.trim().toIntOrNull()?.takeIf { it >= 1 } ?: return Parsed(null, "최대 시도는 1 이상 숫자")
        val waitSec = resultWaitSec.trim().toDoubleOrNull()?.takeIf { it >= 0 } ?: return Parsed(null, "결과 대기(초) 는 숫자")
        val openMs = when {
            openAt.isNotBlank() -> try {
                Parse.openAt(openAt)
            } catch (e: Exception) {
                return Parsed(null, "오픈 시각 형식 오류 — 예: 2026-11-05 10:00:00")
            }
            !requireOpenAt -> 0L
            else -> Schedule.nextOpen(Schedule.enabled(slots, slotsOff), Schedule.AUTO, nowMs)
                ?: return Parsed(null, "켜진 회차 시각이 없습니다 — 시각을 추가하거나 켜 주세요 (예: 12:00, 18:00, 24:00)")
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
                coupons = couponList,
                successTexts = Parse.list(success),
                alreadyTexts = Parse.list(already),
                soldoutTexts = Parse.list(soldout),
                refreshOnMiss = refreshOnMiss,
                showDots = showDots,
            ),
            null,
        )
    }
}

/**
 * 이벤트 프로필. 프로필마다 설정이 따로 저장된다.
 * appKeywords: 패키지가 비어 있을 때 설치된 앱 이름에서 자동으로 찾는 키워드.
 */
data class Profile(val id: String, val label: String, val defaults: FormState, val appKeywords: List<String>)

/** 시작용 기본값. 문구는 예시이므로 [화면 요소 보기]로 실제 문구 확인 후 고급 설정에서 수정할 것. */
object Presets {
    private val COMMON = FormState(
        targets = "쿠폰 받기, 쿠폰받기, 받기, 다운로드, 다운받기",
        success = "발급, 받기 완료, 다운로드 완료, 다운 완료",
        already = "이미 받, 이미 발급",
        soldout = "소진, 마감, 품절, 종료",
    )
    val COUPANG = Profile(
        id = "coupang",
        label = "쿠팡 와우멤버데이",
        defaults = COMMON.copy(name = "쿠팡 와우멤버데이", pkg = "com.coupang.mobile"),
        appKeywords = listOf("쿠팡", "coupang"),
    )
    val NAVER_PLUS = Profile(
        id = "naverplus",
        label = "N+ 녭다세일",
        defaults = COMMON.copy(name = "N+ 녭다세일", pkg = ""),
        appKeywords = listOf("네이버플러스", "네이버 플러스", "N+ 스토어", "N+스토어"),
    )
    val ALL = listOf(COUPANG, NAVER_PLUS)

    fun byId(id: String): Profile = ALL.firstOrNull { it.id == id } ?: ALL[0]
}
