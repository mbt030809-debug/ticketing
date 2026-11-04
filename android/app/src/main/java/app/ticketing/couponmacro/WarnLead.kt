package app.ticketing.couponmacro

import kotlin.math.abs

/** 자동 실행 알람을 회차 몇 초 전에 울릴지: 30초 ~ 10분 (화면에서 고르는 목록) */
object WarnLead {
    val OPTIONS_SEC = listOf(30, 60, 120, 180, 300, 600)
    const val MIN_SEC = 20
    const val MAX_SEC = 600 // 10분
    const val DEFAULT_SEC = 60

    /** 저장된 값(초)을 허용 범위(20초~10분)로. 비었거나 숫자가 아니면 기본 1분 */
    fun clamp(sec: Int?): Int = (sec ?: DEFAULT_SEC).coerceIn(MIN_SEC, MAX_SEC)

    /** "30초 전" / "1분 전" / "10분 전" / "1분 30초 전" */
    fun label(sec: Int): String {
        val s = clamp(sec)
        return when {
            s < 60 -> "${s}초 전"
            s % 60 == 0 -> "${s / 60}분 전"
            else -> "${s / 60}분 ${s % 60}초 전"
        }
    }

    /** 목록에서 가장 가까운 항목의 위치(예전에 직접 입력한 90초 같은 값도 가까운 항목으로) */
    fun indexFor(sec: Int?): Int {
        val s = clamp(sec)
        return OPTIONS_SEC.indices.minByOrNull { abs(OPTIONS_SEC[it] - s) } ?: 1
    }

    fun secAt(index: Int): Int = OPTIONS_SEC.getOrElse(index) { DEFAULT_SEC }
}

/** 이어폰으로 소리를 내야 하는 출력 장치 종류(AudioDeviceInfo.TYPE_*) */
object AudioRoute {
    /** 유선 헤드셋(3), 유선 헤드폰(4), 블루투스 SCO(7), 블루투스 A2DP(8), USB 헤드셋(22), 보청기(23), BLE 헤드셋(26) */
    private val EARPHONE_TYPES = setOf(3, 4, 7, 8, 22, 23, 26)

    fun isEarphone(type: Int): Boolean = type in EARPHONE_TYPES

    fun typeName(type: Int): String = when (type) {
        3, 4 -> "유선 이어폰"
        7, 8 -> "블루투스 이어폰"
        22 -> "USB 이어폰"
        23 -> "보청기"
        26 -> "BLE 이어폰"
        else -> "이어폰"
    }
}
