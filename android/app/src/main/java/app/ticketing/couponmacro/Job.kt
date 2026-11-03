package app.ticketing.couponmacro

import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/** LIVE: 오픈 시각까지 대기 후 클릭 / NOW: 대기 없이 즉시 클릭(리허설) / DRY: 찾기만, 클릭 안 함 */
enum class Mode { LIVE, NOW, DRY }

/**
 * 이동 단계 한 칸 — 경로는 '내가 한 터치(위치)'를 그대로 기억해 같은 순서·간격으로 다시 하는 방식이다.
 *  - 탭:        "@0.2000,0.3500~1200"            화면 비율 위치를 탭. ~뒤는 이전 동작 후 사람이 기다린 시간(ms, 재생 시 최대 3초)
 *  - 길게 누르기: "@0.2000,0.3500#800~1200"        #뒤는 누른 시간(ms)
 *  - 스와이프:   "S@0.50,0.70>0.50,0.30#350~900"   시작→끝 위치, #뒤는 걸린 시간(ms) — 스크롤도 기록된다
 *  - 글자 단계:  "혜택" / "혜택@0.20,0.35"         직접 적을 때(같은 글자가 여러 곳이면 힌트에 가장 가까운 것)
 *  - 앞에 ? 를 붙이면 없어도 진행(optional) — 팝업 닫기처럼 매번 뜨지 않는 것
 *  - 줄 끝 "^기준화면" : 이 단계를 누르기 직전 화면(ScreenRef). 재생 때 그 화면이 뜰 때까지 기다린다.
 *    마지막 단계는 "^기준화면^끝화면"(기준화면이 없으면 "^^끝화면") — 끝화면은 기록을 끝낸 순간의 화면
 */
data class Step(
    val text: String,
    val optional: Boolean,
    val hintX: Float? = null,
    val hintY: Float? = null,
    val delayMs: Long? = null,
    val endX: Float? = null,
    val endY: Float? = null,
    val durMs: Long? = null,
    /** 이 단계를 누르기 직전 화면(기록 때) — ScreenRef 격자. 재생 때 이 화면이 뜰 때까지 기다린 뒤 누른다 */
    val ref: String? = null,
    /** (마지막 단계만) 기록을 끝낸 순간의 화면 — 쿠폰 선택 전에 이 화면이 뜰 때까지 기다린다 */
    val endRef: String? = null,
) {
    /** 스와이프 단계 */
    val isSwipe: Boolean get() = hintX != null && hintY != null && endX != null && endY != null

    /** 글자 없이 위치만으로 탭하는 단계 */
    val isTap: Boolean get() = text.isEmpty() && hintX != null && hintY != null && !isSwipe

    /** 화면/리포트 표시용 */
    fun display(): String = (if (optional) "?" else "") + when {
        isSwipe -> "(스와이프 ${pct(hintX)},${pct(hintY)}→${pct(endX)},${pct(endY)})"
        isTap -> "(탭 ${pct(hintX)},${pct(hintY)})"
        else -> text
    } + (if (ref != null) "◎" else "")

    private fun pct(v: Float?) = "${((v ?: 0f) * 100).toInt()}%"
}

/** 경로 기록: 한 번의 터치 동작을 이동 단계 한 줄로 만든다 (순수 함수) */
object RouteSteps {
    private fun frac(v: Float, size: Int) = String.format(java.util.Locale.ROOT, "%.4f", v / size)

    /**
     * g: 사용자가 한 터치(px), widthPx/heightPx: 화면 크기, gapMs: 이전 동작 후 흐른 시간,
     * popupLabel: 눌린 곳이 팝업 닫기 류("닫기" 등)면 그 글자(없어도 진행하는 '?글자' 단계로 저장), 아니면 null.
     * 글자는 저장하지 않는다(위치만).
     */
    fun line(
        g: RecordedGesture, widthPx: Int, heightPx: Int, gapMs: Long?, popupLabel: String?,
        ref: String? = null, endRef: String? = null,
    ): String {
        val refs = if (ref == null && endRef == null) "" else "^${ref.orEmpty()}" + (if (endRef != null) "^$endRef" else "")
        val gap = if (gapMs != null) "~${gapMs.coerceAtLeast(0)}" else ""
        return when (g.kind) {
            GestureKind.SWIPE ->
                "S@${frac(g.x1, widthPx)},${frac(g.y1, heightPx)}>${frac(g.x2, widthPx)},${frac(g.y2, heightPx)}#${g.durMs}$gap$refs"
            GestureKind.TAP -> {
                val text = popupLabel?.replace('@', ' ')?.replace('~', ' ')?.replace('#', ' ')?.trim().orEmpty()
                val hold = if (g.durMs >= Gestures.LONG_PRESS_MS) "#${g.durMs}" else ""
                (if (text.isNotEmpty()) "?$text" else "") + "@${frac(g.x1, widthPx)},${frac(g.y1, heightPx)}$hold$gap$refs"
            }
        }
    }
}

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
    /** true(기본): 오픈 launchLeadMs 전에 앱 실행 → 오픈 정각에 경로 이동 → 쿠폰 받기. false: 미리 경로 이동해 두고 대기 */
    val routeAtOpen: Boolean = true,
    /** 오픈 몇 ms 전에 앱을 실행할지 (routeAtOpen 일 때) */
    val launchLeadMs: Long = 10_000,
    /** 경로의 스와이프(스크롤) 재생 속도: 기록한 속도의 몇 배인지 */
    val swipeSpeed: Float = Gestures.DEFAULT_SWIPE_SPEED,
    /** 원하는 화면이 뜬 걸 확인한 뒤 클릭까지 더 기다리는 시간(ms) — 뜨자마자 누르면 버튼이 아직 눌리지 않는 경우 대비 */
    val afterScreenMs: Long = Settle.AFTER_SCREEN_MS,
    /** 경로의 스크롤이 끝난 뒤 다음 동작까지 기다리는 시간(ms) — 기본 0: 스크롤 직후 바로 누른다 */
    val afterScrollMs: Long = 0L,
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

    private const val NUM = "([0-9]*\\.?[0-9]+)"
    private const val REFS = "(?:\\^([A-Za-z0-9_-]*)(?:\\^([A-Za-z0-9_-]+))?)?"
    private val STEP_SWIPE = Regex("^S@" + NUM + "\\s*,\\s*" + NUM + ">" + NUM + "\\s*,\\s*" + NUM + "(?:#([0-9]+))?(?:~([0-9]+))?" + REFS + "$")
    private val STEP_POS = Regex("^(.*?)@" + NUM + "\\s*,\\s*" + NUM + "(?:#([0-9]+))?(?:~([0-9]+))?" + REFS + "$")

    /** 한 줄에 하나(Step 설명 참고). 앞에 ? 를 붙이면 optional(못 찾아도 계속). */
    fun steps(s: String): List<Step> =
        s.lines().map { it.trim() }.filter { it.isNotEmpty() }.mapNotNull { raw ->
            val optional = raw.startsWith("?")
            val body = raw.removePrefix("?").trim()
            val sw = STEP_SWIPE.matchEntire(body)
            val m = STEP_POS.matchEntire(body)
            if (sw != null) {
                val v = (1..4).map { sw.groupValues[it].toFloatOrNull() }
                if (v.any { it == null }) null
                else Step(
                    "", optional, v[0], v[1], sw.groupValues[6].toLongOrNull(), v[2], v[3], sw.groupValues[5].toLongOrNull(),
                    sw.groupValues[7].ifEmpty { null }, sw.groupValues[8].ifEmpty { null },
                )
            } else if (m != null) {
                val fx = m.groupValues[2].toFloatOrNull()
                val fy = m.groupValues[3].toFloatOrNull()
                if (fx != null && fy != null) {
                    Step(
                        m.groupValues[1].trim(), optional, fx, fy, m.groupValues[5].toLongOrNull(), null, null, m.groupValues[4].toLongOrNull(),
                        m.groupValues[6].ifEmpty { null }, m.groupValues[7].ifEmpty { null },
                    )
                } else {
                    null
                }
            } else if (body.isNotEmpty()) {
                Step(body, optional)
            } else {
                null
            }
        }

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
    fun parseSlots(s: String): List<Int> = sortDisplay(Parse.list(s).mapNotNull { parseSlot(it) })

    /** 표시/저장 순서: 하루 흐름대로 (자정 24:00 은 맨 뒤) */
    fun sortDisplay(slots: List<Int>): List<Int> = slots.distinct().sortedBy { if (it == 0) 24 * 3600 else it }

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
    /** 경로 이동을 오픈 정각에 시작할지 (끄면 미리 이동해 두고 대기) */
    val routeAtOpen: Boolean = true,
    /** 앱 실행 시점: 오픈 몇 초 전 (0~600) */
    val launchLeadSec: String = "10",
    /** 스크롤(스와이프) 재생 속도 배율: 기록한 속도의 몇 배 (0.5~8, 기본 2) */
    val swipeSpeed: String = "2",
    /** 원하는 화면이 뜬 뒤 클릭까지 대기(초, 0~5, 기본 0.5) */
    val afterScreenSec: String = "0.5",
    /** 스크롤이 끝난 뒤 다음 동작까지 대기(초, 0~5, 기본 0 = 바로) */
    val afterScrollSec: String = "0",
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
        val launchLead = launchLeadSec.trim().toDoubleOrNull()?.takeIf { it in 0.0..600.0 } ?: return Parsed(null, "앱 실행 시점(초)은 0~600 숫자")
        val swipeX = swipeSpeed.trim().toFloatOrNull()?.takeIf { it in 0.5f..8f } ?: return Parsed(null, "스크롤 속도 배율은 0.5~8 숫자")
        val afterSec = afterScreenSec.trim().toDoubleOrNull()?.takeIf { it in 0.0..5.0 } ?: return Parsed(null, "화면이 뜬 뒤 대기(초)는 0~5 숫자")
        val scrollSec = afterScrollSec.trim().toDoubleOrNull()?.takeIf { it in 0.0..5.0 } ?: return Parsed(null, "스크롤 뒤 대기(초)는 0~5 숫자")
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
                routeAtOpen = routeAtOpen,
                launchLeadMs = (launchLead * 1000).toLong(),
                swipeSpeed = swipeX,
                afterScreenMs = (afterSec * 1000).toLong(),
                afterScrollMs = (scrollSec * 1000).toLong(),
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
