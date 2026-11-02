package app.ticketing.couponmacro

import java.util.Locale

/**
 * 글자가 없는 쿠폰 카드(이미지)용 '위치' 대상.
 * 화면의 문구(anchor) 기준 상대 위치(dp)로 저장해서, 페이지가 조금 밀려도 같은 카드를 누른다.
 * anchor 가 비어 있으면 화면 비율(fx, fy) 절대 위치를 쓴다.
 *
 * 쿠폰 목록에는 한 줄 문자열로 저장된다:  위치:오후 5시 오픈|35,120|0.2500,0.5500   (예전 버전이 붙이던 끝의 |o 표시는 무시)
 */
data class PosTarget(
    val anchor: String,
    val dxDp: Int,
    val dyDp: Int,
    val fx: Float,
    val fy: Float,
) {

    fun toEntry(): String {
        val a = anchor.replace("|", " ").replace(";", " ").replace("\n", " ").trim()
        return "$PREFIX$a|$dxDp,$dyDp|" +
            String.format(Locale.ROOT, "%.4f,%.4f", fx, fy)
    }

    /** 사람이 읽는 요약 (로그/안내용) */
    fun describe(): String =
        if (anchor.isNotEmpty()) "'$anchor' 기준 (${dxDp}dp, ${dyDp}dp)"
        else "화면 비율 (${(fx * 100).toInt()}%, ${(fy * 100).toInt()}%)"

    companion object {
        const val PREFIX = "위치:"

        fun parse(entry: String): PosTarget? {
            val e = entry.trim()
            if (!e.startsWith(PREFIX)) return null
            val parts = e.removePrefix(PREFIX).split('|')
            if (parts.size != 3 && parts.size != 4) return null
            val off = parts[1].split(',')
            val fr = parts[2].split(',')
            if (off.size != 2 || fr.size != 2) return null
            return try {
                PosTarget(
                    anchor = parts[0].trim(),
                    dxDp = off[0].trim().toInt(),
                    dyDp = off[1].trim().toInt(),
                    fx = fr[0].trim().toFloat(),
                    fy = fr[1].trim().toFloat(),
                )
            } catch (_: NumberFormatException) {
                null
            }
        }

        fun isPos(entry: String) = entry.trim().startsWith(PREFIX)
    }
}
