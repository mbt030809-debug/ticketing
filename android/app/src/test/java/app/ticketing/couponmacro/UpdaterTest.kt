package app.ticketing.couponmacro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdaterTest {
    private val sample = """
        {
          "tag_name": "build-12",
          "name": "쿠폰 매크로 0.1.12",
          "body": "빌드 12 · 커밋 abc1234 · 업데이트 기능 추가",
          "assets": [
            {"name": "notes.txt", "size": 3, "browser_download_url": "https://example.com/notes.txt"},
            {"name": "couponmacro_20261102.apk", "size": 845494,
             "browser_download_url": "https://github.com/o/r/releases/download/build-12/couponmacro_20261102.apk"}
          ]
        }
    """.trimIndent()

    @Test
    fun parsesLatestRelease() {
        val r = Updater.parseRelease(sample)
        assertNotNull(r)
        r!!
        assertEquals(12, r.code)
        assertEquals("build-12", r.tag)
        assertEquals("couponmacro_20261102.apk", r.apkName)
        assertEquals(845494L, r.size)
        assertTrue(r.apkUrl.endsWith("/build-12/couponmacro_20261102.apk"))
        assertTrue(r.notes.contains("업데이트 기능"))
    }

    @Test
    fun nullWithoutApkAsset() {
        assertNull(Updater.parseRelease("""{"tag_name":"build-3","assets":[{"name":"a.txt","size":1,"browser_download_url":"x"}]}"""))
    }

    @Test
    fun nullWithoutNumericTag() {
        assertNull(Updater.parseRelease("""{"tag_name":"latest","assets":[{"name":"a.apk","size":1,"browser_download_url":"x"}]}"""))
    }

    @Test
    fun versionComparison() {
        assertTrue(Updater.isNewer(2, 1))
        assertFalse(Updater.isNewer(1, 1))
        assertFalse(Updater.isNewer(1, 2))
    }
}
