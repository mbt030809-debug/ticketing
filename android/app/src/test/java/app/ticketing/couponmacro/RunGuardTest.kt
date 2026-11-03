package app.ticketing.couponmacro

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실행 중 화면 잠금 + 긴급 정지 버튼: 버튼 자리 규칙 */
class RunGuardTest {
    @Test
    fun actionsNearTheStopButtonLetItPassThroughButOthersDoNot() {
        val z = RunGuard.stopZone(1080, 2400, 3f)
        // 오른쪽 위 버튼 자리를 누르는 탭/스크롤은 버튼도 통과시켜야 한다(안 그러면 매크로가 긴급 정지를 눌러 버림)
        assertTrue(z.touches(900f, 330f, 900f, 330f))
        assertTrue(z.touches(900f, 600f, 900f, 100f)) // 위로 쓸어 올리며 버튼 자리를 지나는 스크롤
        // 왼쪽/아래쪽은 상관없음
        assertFalse(z.touches(200f, 330f, 200f, 330f))
        assertFalse(z.touches(900f, 1200f, 900f, 1200f))
    }

    @Test
    fun marginMakesNearMissesCount() {
        val z = RunGuard.stopZone(1080, 2400, 3f)
        val justBelow = z.bottom + 5f
        assertFalse(z.touches(900f, justBelow, 900f, justBelow))
        assertTrue(z.touches(900f, justBelow, 900f, justBelow, margin = 24f))
    }

    @Test
    fun stopButtonSitsInsideTheAreaIgnoredByScreenComparison() {
        // 버튼이 떠 있어도 기준 화면 비교(원하는 화면 판정)에 영향이 없어야 한다: 버튼이 놓이는 칸은 모두 비교에서 제외된 칸
        for ((density, h, w) in listOf(Triple(2.0f, 1600, 720), Triple(2.625f, 2400, 1080), Triple(3.0f, 2400, 1080), Triple(2.75f, 2400, 1080), Triple(3.5f, 3000, 1344))) {
            val top = RunGuard.BUTTON_TOP_DP * density
            val bottom = (RunGuard.BUTTON_TOP_DP + RunGuard.BUTTON_HEIGHT_DP) * density
            val rowTop = (top / h * ScreenRef.ROWS).toInt()
            val rowBottom = (bottom / h * ScreenRef.ROWS).toInt()
            // 버튼은 오른쪽 정렬, 폭은 화면의 절반 이하(약 130dp)
            val colLeft = ((w - 150 * density) / w * ScreenRef.COLS).toInt()
            for (r in rowTop..rowBottom) for (c in colLeft until ScreenRef.COLS) {
                assertTrue("density=$density h=$h cell=($c,$r)", ScreenRef.isIgnored(c, r))
            }
        }
    }
}
