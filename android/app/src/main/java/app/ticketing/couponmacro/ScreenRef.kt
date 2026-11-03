package app.ticketing.couponmacro

import java.util.Base64
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * '기록할 때 그 화면' 기준: 화면을 가로 COLS × 세로 ROWS 칸으로 나눈 밝기(0~255) 격자.
 * 경로를 기록할 때 각 탭 직전 화면을 이 격자로 저장해 두고(탭 줄 끝 ^…), 재생할 때 지금 화면이 그 격자와 같아 보일 때 누른다.
 * 글자가 없는 이미지 화면, 로딩 뒤 저절로 스크롤되는 화면에서도 '원하는 화면이 나왔는지'를 알 수 있다.
 * (화면 읽기는 MacroService 가 담당. 여기는 저장 형식/비교 규칙만 — 단위 테스트 가능)
 */
object ScreenRef {
    const val COLS = 24
    const val ROWS = 40
    const val SIZE = COLS * ROWS

    /** 칸 밝기가 이만큼(0~255) 이내로 비슷하면 '같은 칸' */
    const val TOL = 24

    /** 같은 화면으로 보는 칸 비율: 평소 / 오래 기다려도 안 맞을 때(배너가 계속 바뀌는 화면 등) */
    const val STRICT = 0.80f
    const val RELAXED = 0.70f
    const val RELAX_AFTER_MS = 3_000L

    /** 아주 오래 기다려도 안 맞을 때(렉으로 느리게 뜨는 화면, 배너/타이머가 계속 바뀌는 화면)의 마지막 기준 */
    const val VERY_RELAXED = 0.60f
    const val VERY_RELAX_AFTER_MS = 10_000L

    /** 거의 똑같은 화면(전체 90%+, 누를 자리 주변 85%+)은 한 번만 봐도 인정 — 연속 2회 확인을 기다리지 않아 단계마다 약 0.4초 빠르다 */
    const val EXACT = 0.90f
    const val EXACT_REGION = 0.85f

    /** 누른 곳 주변(가로 ±2칸, 세로 ±1칸)의 같은 칸 비율 */
    const val REGION = 0.75f

    /**
     * 원하는 화면이 뜰 때까지 기다리는 최대 시간. 선착순은 서버 렉으로 화면이 한참 늦게 뜰 수 있고,
     * 화면이 전환되기 전에 누르면 엉뚱한 곳이 눌리므로 충분히 길게(1분) 기다린다. 이 시간이 지나야 어쩔 수 없이 진행한다.
     */
    const val MAX_WAIT_MS = 60_000L

    /** 화면 캡처 최소 간격(시스템 제한 약 1/3초 — 걸리면 코드 3 으로 알려 주고 다시 시도한다) */
    const val MIN_SHOT_INTERVAL_MS = 330L

    /** '앞 단계와 같은 화면'(탭해도 화면이 안 바뀌는 단계)으로 보는 칸 비율 */
    const val SAME = 0.92f

    /**
     * 비교에서 빼는 칸: 상단 상태 표시줄(시계/배터리가 계속 바뀜), 기록 중에만 떠 있는 [■ 기록 끝] 버튼 자리(오른쪽 위).
     * 기록과 재생 양쪽에서 같이 뺀다.
     */
    fun isIgnored(col: Int, row: Int): Boolean = row <= 2 || (col >= COLS / 2 && row in 4..8)

    fun encode(luma: IntArray): String {
        require(luma.size == SIZE) { "격자 크기 ${luma.size} != $SIZE" }
        val b = ByteArray(SIZE) { luma[it].coerceIn(0, 255).toByte() }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b)
    }

    /** 저장된 문자열 -> 격자. 형식/크기가 맞지 않으면 null */
    fun decode(s: String?): IntArray? {
        if (s.isNullOrEmpty()) return null
        return try {
            val b = Base64.getUrlDecoder().decode(s)
            if (b.size != SIZE) null else IntArray(SIZE) { b[it].toInt() and 0xff }
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    /** 기준으로 쓸 만한 격자만(검은 화면/보안 화면 등 한 색이면 쓸모 없음) */
    fun gridOf(s: String?): IntArray? = decode(s)?.takeIf { informative(it) }

    /** 화면에 어느 정도 무늬가 있는지(밝기 표준편차). 한 색이면 '아무 화면이나 같다'고 나오므로 기준으로 쓰지 않는다. */
    fun informative(g: IntArray): Boolean {
        var n = 0
        var sum = 0.0
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (isIgnored(c, r)) continue
            sum += g[r * COLS + c]
            n++
        }
        if (n == 0) return false
        val mean = sum / n
        var v = 0.0
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (isIgnored(c, r)) continue
            val d = g[r * COLS + c] - mean
            v += d * d
        }
        return sqrt(v / n) >= 6.0
    }

    /** global: 전체에서 같은 칸 비율, region: 누른 위치 주변에서 같은 칸 비율(위치를 모르면 global) */
    class Score(val global: Float, val region: Float)

    fun score(ref: IntArray, cur: IntArray, fx: Float?, fy: Float?): Score {
        val tapCol = fx?.let { (it * COLS).toInt().coerceIn(0, COLS - 1) }
        val tapRow = fy?.let { (it * ROWS).toInt().coerceIn(0, ROWS - 1) }
        var total = 0
        var same = 0
        var rTotal = 0
        var rSame = 0
        for (r in 0 until ROWS) for (c in 0 until COLS) {
            if (isIgnored(c, r)) continue
            val ok = abs(ref[r * COLS + c] - cur[r * COLS + c]) <= TOL
            total++
            if (ok) same++
            if (tapCol != null && tapRow != null && abs(c - tapCol) <= 2 && abs(r - tapRow) <= 1) {
                rTotal++
                if (ok) rSame++
            }
        }
        val g = if (total == 0) 0f else same.toFloat() / total
        val rg = if (rTotal == 0) g else rSame.toFloat() / rTotal
        return Score(g, rg)
    }

    /** 두 기준 화면이 거의 같은가 (= 그 사이 탭이 화면을 바꾸지 않는 단계) */
    fun sameScreen(a: IntArray, b: IntArray): Boolean = score(a, b, null, null).global >= SAME
}

enum class ScreenVerdict { WAIT, MATCH, TIMEOUT }

/**
 * 기록 때 화면(ref)이 지금 화면으로 나타나기를 기다리는 판정.
 *  - 거의 똑같으면(EXACT) 한 번만 봐도 '나타남' — 스크롤/전환이 끝나 기록 때와 똑같아진 화면
 *  - 그 정도는 아니면(STRICT 이상) 연속 두 번 맞아야 '나타남'(애니메이션이 지나가는 순간에 속지 않도록)
 *  - RELAX_AFTER_MS 넘게 안 맞으면 RELAXED, VERY_RELAX_AFTER_MS 넘으면 VERY_RELAXED 로 완화(배너가 계속 바뀌는 화면 대비)
 *  - 누른 곳 주변은 항상 REGION 이상 같아야 함
 *  - maxWaitMs 를 넘기면 TIMEOUT(그대로 진행)
 */
class ScreenWait(
    private val ref: IntArray,
    private val fx: Float?,
    private val fy: Float?,
    private val startMs: Long,
    private val maxWaitMs: Long = ScreenRef.MAX_WAIT_MS,
) {
    var last: ScreenRef.Score? = null
        private set
    private var streak = 0

    /** @param cur 지금 화면 격자(캡처 실패면 null) */
    fun update(cur: IntArray?, nowMs: Long): ScreenVerdict {
        val elapsed = nowMs - startMs
        if (cur == null) {
            streak = 0
        } else {
            val s = ScreenRef.score(ref, cur, fx, fy)
            last = s
            if (s.global >= ScreenRef.EXACT && s.region >= ScreenRef.EXACT_REGION) return ScreenVerdict.MATCH
            val need = when {
                elapsed >= ScreenRef.VERY_RELAX_AFTER_MS -> ScreenRef.VERY_RELAXED
                elapsed >= ScreenRef.RELAX_AFTER_MS -> ScreenRef.RELAXED
                else -> ScreenRef.STRICT
            }
            if (s.global >= need && s.region >= ScreenRef.REGION) streak++ else streak = 0
            if (streak >= 2) return ScreenVerdict.MATCH
        }
        return if (elapsed >= maxWaitMs) ScreenVerdict.TIMEOUT else ScreenVerdict.WAIT
    }
}

/** 경로 재생에서 '원하는 화면이 나올 때까지 기다릴 단계' 결정 (순수 함수) */
object RouteScreens {
    /**
     * i번째 단계를 누르기 전에 기록 때 화면이 뜰 때까지 기다릴지: 기준 화면이 있고 필수 단계이며, 앞 단계와 다른 화면일 때.
     * 스크롤(스와이프)도 마찬가지로 그 화면이 뜬 뒤에 한다 — 화면이 아직 안 떴을 때 쓸면 동작이 헛돌아 이후 단계가 모두 어긋난다.
     */
    fun waitsBefore(steps: List<Step>, i: Int): Boolean {
        val s = steps.getOrNull(i) ?: return false
        if (s.optional) return false
        val mine = ScreenRef.gridOf(s.ref) ?: return false
        val prev = if (i > 0) ScreenRef.gridOf(steps[i - 1].ref) else null
        return prev == null || !ScreenRef.sameScreen(prev, mine)
    }

    /** 마지막 이동 뒤(쿠폰 선택 전) 기다릴 화면: 기록을 끝낸 순간의 화면 */
    fun endGrid(steps: List<Step>): IntArray? = steps.lastOrNull()?.let { ScreenRef.gridOf(it.endRef) }
}
