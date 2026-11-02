package app.ticketing.couponmacro

import kotlin.math.sqrt

/** 화면 요소 최소 인터페이스 (실기기: AccessibilityNodeInfo 어댑터 / 테스트: 가짜 노드). */
interface UiNode {
    /** text, 없으면 contentDescription. 둘 다 없으면 "" */
    val label: String
    val visible: Boolean
    val enabled: Boolean
    val left: Int
    val top: Int
    val right: Int
    val bottom: Int
    val parent: UiNode?
    val children: List<UiNode>
}

class CouponRules(
    val buttonTexts: List<String>,
    val successTexts: List<String>,
    val alreadyTexts: List<String>,
    val soldoutTexts: List<String>,
    /** 이보다 큰 컨테이너는 '쿠폰 한 장'으로 보지 않는다 (여러 장이 섞여 엉뚱한 버튼을 누르는 것 방지) */
    val maxCardHeight: Int,
)

/**
 * entry 에 해당하는 쿠폰 카드의 현재 상태.
 * button: 지금 누를 수 있는 받기 버튼 / state: 버튼이 없을 때 카드에 적힌 상태(success·already·soldout)
 * ambiguous: 카드 안에 받기 버튼이 여러 개라 어느 것인지 확신할 수 없음 (누르지 않음)
 */
class CardMatch(
    val entry: String,
    val button: UiNode?,
    val state: String?,
    val ambiguous: Boolean,
    val text: String,
)

class PickItem(val lines: List<String>, val keyword: String)

/**
 * 쿠폰 '카드' 단위로 받기 버튼을 찾는다.
 * 화면 전체에서 첫 번째 "받기" 를 누르면 다른 쿠폰까지 받아 버리므로,
 * 쿠폰 문구(entry)가 들어 있는 카드 안의 버튼만 대상으로 한다.
 */
object CouponMatcher {
    private const val MAX_CLIMB = 8
    private const val MAX_NODES = 400
    private const val MAX_SCAN_NODES = 3000

    /** 버튼/상태 문구는 짧다. 긴 설명문("선착순 마감 시 종료")은 버튼·상태로 보지 않는다. */
    private const val MAX_LABEL = 14

    /** "5,000원+3만원 이상" 처럼 +로 이으면 카드 안에 모두 있어야 한다 */
    fun terms(entry: String): List<String> = entry.split('+').map { it.trim() }.filter { it.isNotEmpty() }

    private fun norm(s: String) = s.lowercase().replace(",", "").replace(Regex("\\s+"), "")

    private fun has(label: String, needles: List<String>) = needles.any { label.contains(it, ignoreCase = true) }

    private fun stateOf(label: String, r: CouponRules): String? {
        if (label.length > MAX_LABEL) return null
        return when {
            has(label, r.successTexts) -> "success"
            has(label, r.alreadyTexts) -> "already"
            has(label, r.soldoutTexts) -> "soldout"
            else -> null
        }
    }

    private fun isButton(label: String, r: CouponRules) =
        label.length <= MAX_LABEL && stateOf(label, r) == null && has(label, r.buttonTexts)

    private class Info(val text: String, val buttons: List<UiNode>, val state: String?)

    private fun inspect(card: UiNode, r: CouponRules): Info {
        val texts = ArrayList<String>()
        val buttons = ArrayList<UiNode>()
        val states = HashSet<String>()
        var seen = 0
        fun walk(n: UiNode) {
            if (seen++ >= MAX_NODES) return
            val l = n.label
            if (l.isNotBlank()) {
                texts += l
                val st = stateOf(l, r)
                if (st != null) states += st else if (isButton(l, r)) buttons += n
            }
            for (c in n.children) walk(c)
        }
        walk(card)
        val state = listOf("success", "already", "soldout").firstOrNull { it in states }
        return Info(texts.joinToString(" "), buttons, state)
    }

    private fun cx(n: UiNode) = (n.left + n.right) / 2.0
    private fun cy(n: UiNode) = (n.top + n.bottom) / 2.0
    private fun dist(a: UiNode, b: UiNode): Double {
        val dx = cx(a) - cx(b)
        val dy = cy(a) - cy(b)
        return sqrt(dx * dx + dy * dy)
    }

    /**
     * entry 의 쿠폰 카드를 찾는다. 못 찾으면 null.
     * find(text): 화면에서 text 를 포함하는 노드들 (예: findAccessibilityNodeInfosByText)
     */
    fun resolve(entry: String, find: (String) -> List<UiNode>, r: CouponRules): CardMatch? {
        val terms = terms(entry)
        if (terms.isEmpty()) return null
        val matches = find(terms[0]).filter { it.visible }.mapNotNull { climb(entry, terms, it, r) }
        // 같은 문구가 여러 곳에 있으면 누를 수 있는 쪽 > 상태가 있는 쪽 > 아무거나
        return matches.firstOrNull { it.button != null }
            ?: matches.firstOrNull { it.state != null }
            ?: matches.firstOrNull()
    }

    private fun climb(entry: String, terms: List<String>, key: UiNode, r: CouponRules): CardMatch? {
        var a: UiNode = key
        for (i in 0 until MAX_CLIMB) {
            a = a.parent ?: return null
            if (a.bottom - a.top > r.maxCardHeight) return null
            val info = inspect(a, r)
            if (info.buttons.isEmpty() && info.state == null) continue
            val text = norm(info.text)
            if (!terms.all { text.contains(norm(it)) }) continue
            return build(entry, key, info)
        }
        return null
    }

    private fun build(entry: String, key: UiNode, info: Info): CardMatch {
        val all = info.buttons
        var target: UiNode? = null
        var ambiguous = false
        if (all.size == 1) {
            target = all[0]
        } else if (all.size > 1) {
            val sorted = all.sortedBy { dist(it, key) }
            // 쿠폰 문구에서 확실히 가장 가까운 버튼만 인정 (2배 이상 가까울 때)
            if (dist(sorted[0], key) * 2 <= dist(sorted[1], key)) target = sorted[0] else ambiguous = true
        }
        val button = target?.takeIf { it.visible && it.enabled }
        val state = if (button == null && !ambiguous && all.size <= 1) info.state else null
        return CardMatch(entry, button, state, ambiguous, info.text)
    }

    // ---- 화면에서 쿠폰 고르기 (목록 만들기) ----

    /** 화면 전체에서 지금 누를 수 있는 받기 버튼들 */
    fun findButtons(root: UiNode, r: CouponRules): List<UiNode> {
        val out = ArrayList<UiNode>()
        var seen = 0
        fun walk(n: UiNode) {
            if (seen++ >= MAX_SCAN_NODES) return
            val l = n.label
            if (l.isNotBlank() && n.visible && n.enabled && isButton(l, r)) out += n
            for (c in n.children) walk(c)
        }
        walk(root)
        return out
    }

    /** 각 버튼이 속한 카드의 문구 줄들 + 추천 키워드 */
    fun listCards(buttons: List<UiNode>, r: CouponRules): List<PickItem> {
        val items = ArrayList<PickItem>()
        for (b in buttons) {
            var a: UiNode = b
            var lines: List<String> = emptyList()
            for (i in 0 until MAX_CLIMB) {
                a = a.parent ?: break
                if (a.bottom - a.top > r.maxCardHeight) break
                val l = cardLines(a, r)
                if (l.isNotEmpty()) {
                    lines = l
                    break
                }
            }
            if (lines.isNotEmpty()) items += PickItem(lines, pickKeyword(lines))
        }
        return items
    }

    private fun cardLines(card: UiNode, r: CouponRules): List<String> {
        val out = LinkedHashSet<String>()
        var seen = 0
        fun walk(n: UiNode) {
            if (seen++ >= MAX_NODES || out.size >= 6) return
            val l = n.label.replace(Regex("\\s+"), " ").trim()
            if (l.isNotEmpty() && l.length <= 50 && !isButton(l, r) && stateOf(l, r) == null) out += l
            for (c in n.children) walk(c)
        }
        walk(card)
        return out.toList()
    }

    /** 금액/할인율이 있는 줄을 우선, 없으면 숫자가 있는 줄, 없으면 첫 줄 */
    fun pickKeyword(lines: List<String>): String {
        val amount = lines.firstOrNull { l -> l.any { it.isDigit() } && (l.contains("원") || l.contains("%")) }
        val anyDigit = lines.firstOrNull { l -> l.any { it.isDigit() } }
        return (amount ?: anyDigit ?: lines.first()).take(30)
    }
}
