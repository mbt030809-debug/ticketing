package app.ticketing.couponmacro

import kotlin.math.hypot

/** OCR 로 읽은 한 줄 (화면 픽셀 좌표) */
data class OcrLine(val text: String, val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val cx: Float get() = (left + right) / 2f
    val cy: Float get() = (top + bottom) / 2f
}

/**
 * OCR 결과(줄 목록)에서 쿠폰 문구/기준 문구/후보를 찾는 순수 로직.
 * 이미지로 된 쿠폰(접근성에 글자가 없음)을 위해 화면 캡처를 OCR 한 뒤 사용한다.
 */
object OcrMatcher {
    /** 비교용 정규화: 공백/쉼표/마침표 제거, 소문자, OCR 이 자주 섞는 o→0, l→1 */
    fun norm(s: String): String =
        s.lowercase().replace(Regex("[\\s,.]+"), "").replace('o', '0').replace('l', '1')

    fun matches(line: OcrLine, term: String): Boolean {
        val t = norm(term)
        return t.isNotEmpty() && norm(line.text).contains(t)
    }

    /** text 를 포함하는 줄 중 화면에서 가장 위(같으면 왼쪽)의 것 */
    fun findLine(lines: List<OcrLine>, text: String): OcrLine? =
        lines.filter { matches(it, text) }.minWithOrNull(compareBy({ it.top }, { it.left }))

    /**
     * 쿠폰 문구 entry("A" 또는 "A+B+C") 의 위치.
     * A 를 먼저 찾고, B 는 A 아래에서 가장 가까운 줄, C 는 B 아래…  마지막 줄을 반환한다.
     * 예: "오후 5시 오픈+10,000원" -> 오후 5시 구역 아래의 10,000원 (오전 구역의 같은 금액과 구분)
     */
    fun resolve(lines: List<OcrLine>, entry: String, maxGapPx: Int): OcrLine? {
        val terms = CouponMatcher.terms(entry)
        if (terms.isEmpty()) return null
        val ordered = lines.sortedWith(compareBy({ it.top }, { it.left }))
        for (first in ordered.filter { matches(it, terms[0]) }) {
            var prev = first
            var ok = true
            for (t in terms.drop(1)) {
                val cur = prev
                val next = ordered
                    .filter { it !== cur && matches(it, t) && it.top >= cur.top - 6 && it.top - cur.bottom <= maxGapPx }
                    .minByOrNull { hypot((it.cx - cur.cx).toDouble(), (it.cy - cur.cy).toDouble()) }
                if (next == null) {
                    ok = false
                    break
                }
                prev = next
            }
            if (ok) return prev
        }
        return null
    }

    /** 눌린 위치(tapY) 바로 위의 '유일한' 줄 = 위치 지정의 기준점(앵커) */
    fun findAnchor(lines: List<OcrLine>, tapY: Int, maxGapPx: Int, exclude: (OcrLine) -> Boolean = { false }): OcrLine? {
        val counts = lines.groupingBy { norm(it.text) }.eachCount()
        return lines
            .filter {
                it.text.trim().length in 2..40 && it.bottom <= tapY + 8 && tapY - it.bottom <= maxGapPx &&
                    counts[norm(it.text)] == 1 && !exclude(it)
            }
            .maxByOrNull { it.bottom }
    }

    private val AMOUNT = Regex("^[0-9][0-9,]{2,}\\s*원")
    private val PERCENT = Regex("^[0-9]{1,3}\\s*%")

    fun isAmountLike(text: String): Boolean {
        val t = text.trim()
        return AMOUNT.containsMatchIn(t) || PERCENT.containsMatchIn(t)
    }

    /**
     * 이미지 쿠폰 후보: 금액/할인율처럼 보이는 줄마다, 바로 위의 유일한 제목 줄("오후 5시 오픈")과 묶어
     * "제목+금액" 을 저장 문구로 제안한다.
     */
    fun couponCandidates(lines: List<OcrLine>, maxGapPx: Int): List<PickItem> {
        val counts = lines.groupingBy { norm(it.text) }.eachCount()
        val ordered = lines.sortedWith(compareBy({ it.top }, { it.left }))
        val out = LinkedHashMap<String, PickItem>()
        for (l in ordered) {
            if (!isAmountLike(l.text) || l.text.length > 20) continue
            val heading = ordered
                .filter {
                    it !== l && it.bottom <= l.top + 4 && l.top - it.bottom <= maxGapPx &&
                        counts[norm(it.text)] == 1 && it.text.trim().length in 2..24 && !isAmountLike(it.text)
                }
                .maxByOrNull { it.bottom }
            val amount = l.text.trim()
            val keyword = if (heading != null) "${heading.text.trim()}+$amount" else amount
            val lines2 = listOfNotNull(heading?.text?.trim(), amount)
            out.putIfAbsent(keyword, PickItem(lines2, keyword))
        }
        return out.values.toList()
    }
}
