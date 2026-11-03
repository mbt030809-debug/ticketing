package app.ticketing.couponmacro

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * 간이 SNTP 클라이언트. offset = 서버 시각 - 기기 시각 (ms).
 * 여러 번 재서 '왕복 시간(RTT)이 가장 짧은' 표본을 쓴다 — 왕복이 짧을수록 오차(≈ RTT/2 이내)가 작다.
 */
object Ntp {
    private const val NTP_EPOCH_DELTA_SEC = 2_208_988_800L

    /** offsetMs: 서버-기기 시각 차, rttMs: 왕복 시간(서버 처리 시간 제외). 오차는 대략 ±rttMs/2 이내 */
    data class Sample(val offsetMs: Long, val rttMs: Long)

    /** 한 번의 요청/응답 시각으로 offset 과 RTT 계산. t0 기기 송신, t1 서버 수신, t2 서버 송신, t3 기기 수신 */
    internal fun compute(t0: Long, t1: Long, t2: Long, t3: Long): Sample =
        Sample(offsetMs = ((t1 - t0) + (t2 - t3)) / 2, rttMs = ((t3 - t0) - (t2 - t1)).coerceAtLeast(0))

    /** 표본 중 왕복이 가장 짧은 것(같으면 먼저 잰 것). 없으면 null */
    internal fun pickBest(samples: List<Sample>): Sample? = samples.minByOrNull { it.rttMs }

    fun measure(server: String = "time.google.com", samples: Int = 5, timeoutMs: Int = 1500): Sample? {
        val list = mutableListOf<Sample>()
        repeat(samples) {
            try {
                DatagramSocket().use { s ->
                    s.soTimeout = timeoutMs
                    val req = ByteArray(48)
                    req[0] = 0x1B
                    val addr = InetAddress.getByName(server)
                    val t0 = System.currentTimeMillis()
                    s.send(DatagramPacket(req, req.size, addr, 123))
                    val resp = DatagramPacket(ByteArray(48), 48)
                    s.receive(resp)
                    val t3 = System.currentTimeMillis()
                    val t1 = parseTimestampMs(resp.data, 32) // 서버 수신
                    val t2 = parseTimestampMs(resp.data, 40) // 서버 송신
                    list += compute(t0, t1, t2, t3)
                }
            } catch (_: Exception) {
                // 다음 샘플 시도
            }
        }
        return pickBest(list)
    }

    /** (이전 호환) 서버-기기 시각 차만 */
    fun offsetMs(server: String = "time.google.com", samples: Int = 5, timeoutMs: Int = 1500): Long? =
        measure(server, samples, timeoutMs)?.offsetMs

    /** NTP 64bit 타임스탬프(초 32bit + 분수 32bit) -> epoch ms */
    internal fun parseTimestampMs(b: ByteArray, off: Int): Long {
        var secs = 0L
        for (i in 0 until 4) secs = (secs shl 8) or (b[off + i].toLong() and 0xff)
        var frac = 0L
        for (i in 4 until 8) frac = (frac shl 8) or (b[off + i].toLong() and 0xff)
        return (secs - NTP_EPOCH_DELTA_SEC) * 1000 + (frac * 1000L) / 0x1_0000_0000L
    }
}
