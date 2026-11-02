package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** 테스트용 가짜 화면 노드 */
class FakeNode(
    override val label: String = "",
    override var left: Int = 0,
    override var top: Int = 0,
    override var right: Int = 100,
    override var bottom: Int = 40,
    override val visible: Boolean = true,
    override val enabled: Boolean = true,
) : UiNode {
    override var parent: UiNode? = null
    override val children = ArrayList<UiNode>()

    fun add(vararg kids: FakeNode): FakeNode {
        kids.forEach { it.parent = this; children += it }
        return this
    }
}

class CouponMatcherTest {
    private val rules = CouponRules(
        buttonTexts = listOf("받기", "다운로드"),
        successTexts = listOf("받기 완료", "발급"),
        alreadyTexts = listOf("이미 받"),
        soldoutTexts = listOf("소진", "마감", "품절"),
        maxCardHeight = 150,
    )

    /** 세로로 쌓인 카드 한 장: 제목 + 설명 + 버튼 */
    private fun card(index: Int, title: String, desc: String, btn: String, btnEnabled: Boolean = true): Triple<FakeNode, FakeNode, FakeNode> {
        val top = index * 120
        val t = FakeNode(title, 10, top + 5, 300, top + 35)
        val d = FakeNode(desc, 10, top + 40, 300, top + 70)
        val b = FakeNode(btn, 320, top + 40, 400, top + 90, enabled = btnEnabled)
        val c = FakeNode("", 0, top, 420, top + 100).add(t, d, b)
        return Triple(c, t, b)
    }

    private fun screen(vararg cards: Triple<FakeNode, FakeNode, FakeNode>): FakeNode =
        FakeNode("", 0, 0, 420, 2000).add(*cards.map { it.first }.toTypedArray())

    private fun finder(root: FakeNode): (String) -> List<UiNode> = { text ->
        val out = ArrayList<UiNode>()
        fun walk(n: UiNode) {
            if (n.label.contains(text, ignoreCase = true)) out += n
            n.children.forEach { walk(it) }
        }
        walk(root)
        out
    }

    @Test
    fun picksOnlyTheChosenCouponsButton() {
        val a = card(0, "3,000원 할인", "1만원 이상", "받기")
        val b = card(1, "5,000원 할인", "3만원 이상", "받기")
        val c = card(2, "10% 할인", "최대 1만원", "받기")
        val root = screen(a, b, c)

        val m = CouponMatcher.resolve("5,000원", finder(root), rules)
        assertNotNull(m)
        assertSame(b.third, m!!.button)
        assertFalse(m.ambiguous)
    }

    @Test
    fun commasAndSpacesInKeywordAreNormalizedForExtraTerms() {
        val a = card(0, "5,000원 할인", "3만원 이상 구매", "받기")
        val b = card(1, "5,000원 할인", "10만원 이상 구매", "받기")
        val root = screen(a, b)
        // 같은 금액 두 장 중 조건 문구로 구분: '+' 로 이으면 모두 포함해야 함
        val m = CouponMatcher.resolve("5,000원+10만원 이상", finder(root), rules)
        assertSame(b.third, m!!.button)
        val n = CouponMatcher.resolve("5,000원+3만원 이상", finder(root), rules)
        assertSame(a.third, n!!.button)
    }

    @Test
    fun alreadyReceivedCardHasStateButNoButton() {
        val a = card(0, "5,000원 할인", "3만원 이상", "받기 완료") // 이미 받은 카드: 버튼 문구가 상태로 바뀜
        val b = card(1, "10% 할인", "최대 1만원", "받기")
        val m = CouponMatcher.resolve("5,000원", finder(screen(a, b)), rules)
        assertNotNull(m)
        assertNull(m!!.button)
        assertEquals("success", m.state) // 클릭 전에 보이면 호출부에서 'already' 로 취급
    }

    @Test
    fun soldOutCard() {
        val a = card(0, "5,000원 할인", "3만원 이상", "마감")
        val m = CouponMatcher.resolve("5,000원", finder(screen(a)), rules)
        assertNull(m!!.button)
        assertEquals("soldout", m.state)
    }

    @Test
    fun neverBorrowsNeighborsButtonWhenOwnCardHasNone() {
        // 5,000원 카드는 '오픈 예정'(버튼/상태 문구 없음). 이웃의 받기 버튼을 눌러서는 안 됨.
        val a = card(0, "5,000원 할인", "3만원 이상", "오픈 예정")
        val b = card(1, "10% 할인", "최대 1만원", "받기")
        val root = screen(a, b)
        val m = CouponMatcher.resolve("5,000원", finder(root), rules)
        assertNull(m) // 카드 범위(150px)를 넘는 컨테이너까지는 올라가지 않음 → 아직 못 찾은 것으로 처리
    }

    @Test
    fun disabledButtonIsNotClicked() {
        val a = card(0, "5,000원 할인", "3만원 이상", "받기", btnEnabled = false)
        val m = CouponMatcher.resolve("5,000원", finder(screen(a)), rules)
        assertNotNull(m)
        assertNull(m!!.button)
        assertNull(m.state)
    }

    @Test
    fun unrelatedKeywordNotFound() {
        val a = card(0, "5,000원 할인", "3만원 이상", "받기")
        assertNull(CouponMatcher.resolve("20% 할인", finder(screen(a)), rules))
    }

    @Test
    fun deadlineWordInDescriptionIsNotSoldOut() {
        // 설명문의 "마감" 은 상태가 아니고, 버튼이 살아 있으므로 눌러야 함
        val a = card(0, "5,000원 할인", "선착순 100명 마감 시 종료됩니다 (긴 설명문)", "받기")
        val m = CouponMatcher.resolve("5,000원", finder(screen(a)), rules)
        assertSame(a.third, m!!.button)
        assertNull(m.state)
    }

    @Test
    fun twoButtonsInOneCardAreAmbiguousUnlessOneIsClearlyNearer() {
        val title = FakeNode("5,000원 할인", 10, 5, 300, 35)
        val b1 = FakeNode("받기", 320, 40, 400, 90)
        val b2 = FakeNode("받기", 320, 45, 400, 95)
        val card = FakeNode("", 0, 0, 420, 100).add(title, b1, b2)
        val root = FakeNode("", 0, 0, 420, 2000).add(card)
        val m = CouponMatcher.resolve("5,000원", finder(root), rules)
        assertNull(m!!.button)
        assertTrue(m.ambiguous)
    }

    @Test
    fun listCardsBuildsLabelsAndKeywords() {
        val a = card(0, "5,000원 할인", "3만원 이상", "받기")
        val b = card(1, "와우 전용 쿠폰", "무료배송", "다운로드")
        val root = screen(a, b)
        val buttons = CouponMatcher.findButtons(root, rules)
        assertEquals(2, buttons.size)
        val items = CouponMatcher.listCards(buttons, rules)
        assertEquals(2, items.size)
        assertEquals(listOf("5,000원 할인", "3만원 이상"), items[0].lines)
        assertEquals("5,000원 할인", items[0].keyword)
        assertEquals("와우 전용 쿠폰", items[1].keyword) // 숫자 없으면 첫 줄
    }

    @Test
    fun termsSplitOnPlus() {
        assertEquals(listOf("5,000원", "3만원 이상"), CouponMatcher.terms(" 5,000원 + 3만원 이상 "))
        assertTrue(CouponMatcher.terms("  ").isEmpty())
    }
}
