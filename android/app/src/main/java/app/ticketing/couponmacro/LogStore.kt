package app.ticketing.couponmacro

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 메모리 로그(최근 300줄). 화면 표시/복사용. */
object LogStore {
    private val lines = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)

    @Volatile
    var listener: (() -> Unit)? = null

    fun add(msg: String) {
        synchronized(this) {
            lines.addLast("${fmt.format(Date())} $msg")
            while (lines.size > 300) lines.removeFirst()
        }
        listener?.invoke()
    }

    /** 최신 줄이 위로 오도록 */
    fun text(max: Int = 80): String = synchronized(this) { lines.toList().asReversed().take(max).joinToString("\n") }

    /** 가장 최근 로그 한 줄 (상태 표시용) */
    fun last(): String = synchronized(this) { lines.lastOrNull().orEmpty() }

    fun clear() {
        synchronized(this) { lines.clear() }
        listener?.invoke()
    }
}
