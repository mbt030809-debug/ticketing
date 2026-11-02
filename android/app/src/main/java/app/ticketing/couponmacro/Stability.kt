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
