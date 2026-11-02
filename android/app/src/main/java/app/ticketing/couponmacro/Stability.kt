package app.ticketing.couponmacro

/**
 * 화면 로딩 완료 판정: 로딩 표시(ProgressBar)가 없고, 내용이 quietMs 동안 변하지 않으면 '완료'.
 * (화면 내용의 시그니처를 주기적으로 넣어 준다. 실기기 화면 읽기는 MacroService 가 담당)
 */
class StabilityTracker(private val quietMs: Long) {
    private var lastSig: Int? = null
    private var since = 0L

    fun reset() {
        lastSig = null
    }

    /** @return 로딩이 끝났다고 볼 수 있으면 true */
    fun update(sig: Int, loading: Boolean, hasContent: Boolean, nowMs: Long): Boolean {
        if (loading || !hasContent || sig != lastSig) {
            lastSig = if (loading || !hasContent) null else sig
            since = nowMs
            return false
        }
        return nowMs - since >= quietMs
    }
}

/** 경로 이동 중 '다음 동작을 해도 되는 때'를 정하는 규칙값 */
object Settle {
    /** 화면이 바뀐 뒤 이만큼(ms) 변하지 않아야 '다 바뀜' */
    const val QUIET_MS = 500L

    /** 최소 대기를 채운 뒤에도 화면이 계속 바뀌면 이만큼(ms) 더 기다려 보고 그대로 진행 */
    const val EXTRA_MS = 4_000L

    /** 마지막 이동 단계 뒤(쿠폰 화면이 뜨기 전) 최소 대기 */
    const val LAST_STEP_MIN_MS = 400L

    /** 앱을 연 직후 첫 화면이 안정되기를 정각 이후로 더 기다려 주는 한도 */
    const val START_EXTRA_MS = 5_000L

    /** 다음 동작 전 최소 대기: 사람이 기록할 때 기다렸던 시간(0.5~4초). 화면이 안 바뀌는 곳에서도 이만큼은 기다린다. */
    fun minWaitMs(nextDelayMs: Long?, isLast: Boolean): Long =
        if (isLast) LAST_STEP_MIN_MS else (nextDelayMs ?: 600L).coerceIn(500L, 4_000L)
}

/**
 * 탭 한 번 뒤 '화면이 다 바뀌었는지' 판정 (순수 로직 — 실기기 화면 읽기는 MacroService 가 담당).
 *  - 최소 minWaitMs 는 항상 기다린다: 화면이 안 바뀌는 곳(이미지/웹 등)에서도 사람이 기다렸던 만큼은 기다린다.
 *  - 그 사이 화면이 바뀌기 시작했다면(내용이 달라짐 / 로딩 표시 / 화면 전환 중) 로딩 표시가 없고 내용이 quietMs 동안
 *    그대로일 때까지 더 기다린다 — '바뀌는 중'에 다음 곳을 누르지 않도록.
 *  - 상한 maxWaitMs 를 넘기면(계속 바뀌는 화면 등) 그대로 진행한다.
 * 탭 전부터 떠 있던 로딩 표시(상시 스피너)는 무시한다.
 */
class StepSettle(
    private val beforeSig: Int?,
    private val beforeLoading: Boolean,
    private val minWaitMs: Long,
    private val maxWaitMs: Long,
    quietMs: Long,
    private val startMs: Long,
) {
    private val tracker = StabilityTracker(quietMs)
    private var baseSig: Int? = beforeSig

    /** 탭 뒤 화면이 바뀌기 시작한 적이 있는지 */
    var changed = false
        private set

    /** 상한에 걸려 어쩔 수 없이 진행하는지 */
    var timedOut = false
        private set

    /**
     * @param sig 화면 내용 시그니처. null 이면 대상 앱 화면을 읽을 수 없음(화면 전환 중이거나 다른 앱)
     * @return 다음 동작을 해도 되면 true
     */
    fun ready(sig: Int?, loading: Boolean, hasContent: Boolean, nowMs: Long): Boolean {
        val busy = loading && !beforeLoading
        var stable = false
        if (sig == null) {
            changed = true
            tracker.reset()
        } else {
            if (baseSig == null) baseSig = sig
            if (sig != baseSig || busy) changed = true
            stable = tracker.update(sig, busy, hasContent, nowMs)
        }
        val elapsed = nowMs - startMs
        if (elapsed < minWaitMs) return false
        if (!changed || stable) return true
        if (elapsed >= maxWaitMs) {
            timedOut = true
            return true
        }
        return false
    }
}
