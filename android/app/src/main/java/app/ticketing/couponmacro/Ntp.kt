package app.ticketing.couponmacro

import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/** 간이 SNTP 클라이언트. 반환값 = 서버 시각 - 기기 시각 (ms). */
object Ntp {
    private const val NTP_EPOCH_DELTA_SEC = 2_208_988_800L

    fun offsetMs(server: String = "time.google.com", samples: Int = 3, timeoutMs: Int = 2000): Long? {
        val offsets = mutableListOf<Long>()
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
                    offsets += ((t1 - t0) + (t2 - t3)) / 2
                }
            } catch (_: Exception) {
                // 다음 샘플 시도
            }
        }
        if (offsets.isEmpty()) return null
        return offsets.sorted()[offsets.size / 2]
    }

    /** NTP 64bit 타임스탬프(초 32bit + 분수 32bit) -> epoch ms */
    internal fun parseTimestampMs(b: ByteArray, off: Int): Long {
        var secs = 0L
        for (i in 0 until 4) secs = (secs shl 8) or (b[off + i].toLong() and 0xff)
        var frac = 0L
        for (i in 4 until 8) frac = (frac shl 8) or (b[off + i].toLong() and 0xff)
        return (secs - NTP_EPOCH_DELTA_SEC) * 1000 + (frac * 1000L) / 0x1_0000_0000L
    }
}
