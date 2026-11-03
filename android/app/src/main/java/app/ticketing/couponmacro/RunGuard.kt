package app.ticketing.couponmacro

/**
 * 매크로 실행 중 화면 잠금(사용자 터치 차단) + [■ 긴급 정지] 버튼의 배치 규칙 (순수 로직 — 화면 그리기는 MacroService).
 * 버튼은 경로 기록 때 [■ 기록 끝] 버튼과 같은 자리(오른쪽 위)에 둔다 -> 기준 화면 비교(ScreenRef.isIgnored)에서 이미 빠지는 영역이라
 * 버튼이 떠 있어도 '원하는 화면' 판정에 영향을 주지 않는다.
 */
object RunGuard {
    /** 버튼 위치: 위에서 96dp, 높이 약 52dp, 오른쪽 정렬 */
    const val BUTTON_TOP_DP = 96
    const val BUTTON_HEIGHT_DP = 52

    /** 화면 위 사각형 영역(px) */
    data class Zone(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        /** (x1,y1)->(x2,y2) 동작이 이 영역(여유 margin 포함)에 닿는가 — 동작 경로의 외접 사각형 기준 */
        fun touches(x1: Float, y1: Float, x2: Float, y2: Float, margin: Float = 0f): Boolean {
            val minX = minOf(x1, x2)
            val maxX = maxOf(x1, x2)
            val minY = minOf(y1, y2)
            val maxY = maxOf(y1, y2)
            return maxX >= left - margin && minX <= right + margin && maxY >= top - margin && minY <= bottom + margin
        }
    }

    /**
     * 긴급 정지 버튼이 있을 수 있는 영역(넉넉하게): 오른쪽 위 가로 55%, 세로 BUTTON_TOP_DP-6dp ~ 버튼 아래 12dp.
     * 매크로의 탭/스크롤이 이 영역에 닿으면 그 동작을 보내는 동안만 버튼도 터치를 통과시킨다(버튼이 동작을 가로채지 않도록).
     */
    fun stopZone(widthPx: Int, heightPx: Int, density: Float): Zone =
        Zone(widthPx * 0.45f, (BUTTON_TOP_DP - 6) * density, widthPx.toFloat(), (BUTTON_TOP_DP + BUTTON_HEIGHT_DP + 12) * density)
}
