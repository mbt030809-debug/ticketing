package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 실제 쿠폰 페이지(이미지 카드) 스크린샷을 흉내 낸 OCR 결과로 검증 */
class OcrMatcherTest {
    private fun l(t: String, x: Int, y: Int, w: Int = 200, h: Int = 40) = OcrLine(t, x, y, x + w, y + h)

    private val screen = listOf(
        l("기획전 내 상품 3만원/10만원 이상", 410, 450, 420, 36),
        l("장바구니 선착순 쿠폰", 410, 495, 430, 56),
        l("오전 10시 오픈", 194, 575, 190, 36),
        l("3만원 구매시", 350, 672, 110, 22), l("10,000원", 290, 700, 230, 70), l("선착순 마감", 350, 815, 110, 28),
        l("10만원 구매시", 785, 672, 120, 22), l("30,000원", 730, 700, 230, 70), l("선착순 마감", 790, 815, 110, 28),
        l("오후 5시 오픈", 194, 900, 190, 36),
        l("3만원 구매시", 350, 997, 110, 22), l("10,000원", 290, 1025, 230, 70), l("곧 오픈 예정", 350, 1140, 110, 28),
        l("10만원 구매시", 785, 997, 120, 22), l("30,000원", 730, 1025, 230, 70), l("곧 오픈 예정", 790, 1140, 110, 28),
        l("쿠폰 유의사항 꼭 확인하세요", 233, 1225, 250, 28),
    )

    @Test
    fun sectionPlusAmountPicksTheRightCard() {
        val hit = OcrMatcher.resolve(screen, "오후 5시 오픈+10,000원", 500)
        assertNotNull(hit)
        // 오후 5시 구역(y≈1025)의 왼쪽 10,000원 — 오전 구역(y≈700)이 아님
        assertEquals(1025, hit!!.top)
        assertEquals(290, hit.left)
        val morning = OcrMatcher.resolve(screen, "오전 10시 오픈+30,000원", 500)
        assertEquals(700, morning!!.top)
        assertEquals(730, morning.left)
    }

    @Test
    fun plainAmountReturnsFirstInReadingOrder() {
        assertEquals(700, OcrMatcher.resolve(screen, "10,000원", 500)!!.top)
    }

    @Test
    fun matchingIgnoresSpacesCommasAndCommonOcrConfusions() {
        assertNotNull(OcrMatcher.findLine(screen, "10000원"))
        assertNotNull(OcrMatcher.findLine(screen, "오후5시오픈"))
        val confused = listOf(l("1O,OOO원", 0, 0)) // 숫자 0 을 알파벳 O 로 읽은 경우
        assertNotNull(OcrMatcher.findLine(confused, "10,000원"))
    }

    @Test
    fun missingTermOrTooFarBelowIsNotFound() {
        assertNull(OcrMatcher.resolve(screen, "오후 5시 오픈+50,000원", 500))
        assertNull(OcrMatcher.resolve(screen, "오후 5시 오픈+10,000원", 50)) // 간격 제한
        assertNull(OcrMatcher.resolve(emptyList(), "10,000원", 500))
    }

    @Test
    fun anchorIsNearestUniqueLineAboveTap() {
        // 오후 구역 왼쪽 카드 한가운데를 터치: 위의 줄 중 중복되는 "3만원 구매시"/"10,000원"/"곧 오픈 예정" 은 제외 -> 제목
        val a = OcrMatcher.findAnchor(screen, 1060, 500)
        assertNotNull(a)
        assertEquals("오후 5시 오픈", a!!.text)
        // 제외 조건: 제목을 제외하면 남는 후보가 없음(나머지는 모두 중복 문구)
        val ex = OcrMatcher.findAnchor(screen, 1060, 500) { it.text.contains("오후") }
        assertTrue(ex == null || !ex.text.contains("오후"))
    }

    @Test
    fun amountDetectionAndCandidates() {
        assertTrue(OcrMatcher.isAmountLike("10,000원"))
        assertTrue(OcrMatcher.isAmountLike("30,000 원"))
        assertTrue(OcrMatcher.isAmountLike("10% 할인"))
        assertTrue(!OcrMatcher.isAmountLike("3만원 구매시"))
        assertTrue(!OcrMatcher.isAmountLike("오후 5시 오픈"))

        val items = OcrMatcher.couponCandidates(screen, 500)
        val keys = items.map { it.keyword }
        assertEquals(
            listOf("오전 10시 오픈+10,000원", "오전 10시 오픈+30,000원", "오후 5시 오픈+10,000원", "오후 5시 오픈+30,000원"),
            keys,
        )
        // 추천된 저장 문구는 그대로 resolve 에 쓸 수 있어야 함
        for (k in keys) assertNotNull(OcrMatcher.resolve(screen, k, 500))
    }
}
