package app.ticketing.couponmacro

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** GitHub 최신 릴리스(공개 저장소)에서 새 APK 를 확인/다운로드/설치. */
object Updater {

    data class Release(
        val code: Int,          // 릴리스 태그 끝 숫자 = versionCode (예: build-12 -> 12)
        val tag: String,
        val name: String,
        val apkUrl: String,
        val apkName: String,
        val size: Long,
        val notes: String,
    )

    /** releases/latest JSON -> Release. APK 에셋이 없거나 태그에 숫자가 없으면 null. */
    fun parseRelease(json: String): Release? {
        val o = JSONObject(json)
        val tag = o.optString("tag_name")
        val code = Regex("(\\d+)$").find(tag)?.value?.toIntOrNull() ?: return null
        val assets = o.optJSONArray("assets") ?: return null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            val n = a.optString("name")
            if (n.endsWith(".apk")) {
                return Release(
                    code = code,
                    tag = tag,
                    name = o.optString("name"),
                    apkUrl = a.getString("browser_download_url"),
                    apkName = n,
                    size = a.optLong("size", 0),
                    notes = o.optString("body"),
                )
            }
        }
        return null
    }

    fun isNewer(remoteCode: Int, localCode: Int) = remoteCode > localCode

    /** 최신 릴리스. 릴리스가 없으면 null, 통신 오류는 IOException. */
    fun fetchLatest(repo: String): Release? {
        val c = open("https://api.github.com/repos/$repo/releases/latest")
        c.setRequestProperty("Accept", "application/vnd.github+json")
        try {
            return when (val code = c.responseCode) {
                200 -> parseRelease(c.inputStream.bufferedReader().use { it.readText() })
                404 -> null
                else -> throw IOException("HTTP $code")
            }
        } finally {
            c.disconnect()
        }
    }

    fun download(r: Release, dest: File, onProgress: (Int) -> Unit) {
        val c = open(r.apkUrl)
        c.readTimeout = 60_000
        try {
            if (c.responseCode != 200) throw IOException("다운로드 실패 HTTP ${c.responseCode}")
            val total = c.contentLengthLong.takeIf { it > 0 } ?: r.size
            var done = 0L
            var lastPct = -1
            c.inputStream.use { input ->
                dest.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        if (r.size > 0 && dest.length() != r.size) {
            dest.delete()
            throw IOException("파일 크기 불일치 (${dest.length()} != ${r.size})")
        }
    }

    /** PackageInstaller 세션으로 설치(업데이트). 사용자 확인 화면은 UpdateReceiver 가 띄운다. */
    fun install(ctx: Context, apk: File) {
        val installer = ctx.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(ctx.packageName)
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            apk.inputStream().use { input ->
                session.openWrite("couponmacro.apk", 0, apk.length()).use { out ->
                    input.copyTo(out)
                    session.fsync(out)
                }
            }
            val intent = Intent(ctx, UpdateReceiver::class.java).setAction(UpdateReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val pending = PendingIntent.getBroadcast(ctx, id, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 10_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "couponmacro-updater")
        }
}
