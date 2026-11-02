package app.ticketing.couponmacro

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private val fields = LinkedHashMap<String, EditText>()
    private lateinit var launchCb: CheckBox
    private lateinit var refreshCb: CheckBox
    private lateinit var statusView: TextView
    private lateinit var logView: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(title("쿠폰 매크로"))
        statusView = TextView(this)
        root.addView(statusView)
        root.addView(
            TextView(this).apply {
                text = "※ 접근성 서비스를 켜야 동작합니다. 설치 후 서비스가 회색이면 [앱 정보 열기] → ⋮ → '제한된 설정 허용' 후 다시 시도."
                textSize = 12f
            },
        )
        root.addView(row(button("접근성 설정 열기") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }, button("앱 정보 열기") { openAppInfo() }))

        root.addView(section("업데이트"))
        root.addView(TextView(this).apply { text = "현재 버전 ${BuildConfig.VERSION_NAME} (빌드 ${BuildConfig.VERSION_CODE})" })
        root.addView(button("업데이트 확인") { checkUpdate(silent = false) })

        root.addView(section("프리셋 (시작용 예시값)"))
        root.addView(
            row(
                button("쿠팡 와우멤버데이") { showForm(Presets.COUPANG) },
                button("N+ 녭다세일") { showForm(Presets.NAVER_PLUS) },
            ),
        )

        root.addView(section("대상"))
        field(root, K_NAME, "이름")
        field(root, K_PKG, "대상 앱 패키지")
        root.addView(button("설치된 앱에서 선택") { pickApp() })
        launchCb = CheckBox(this).apply { text = "시작 시 앱 자동 실행 (끄면 직접 쿠폰 화면에 가 있어야 함)" }
        root.addView(launchCb)
        field(root, K_STEPS, "이동 단계 (한 줄에 하나, 해당 문구 클릭. 앞에 ? 붙이면 없어도 진행)", lines = 3)

        root.addView(section("시각 / 타이밍"))
        field(root, K_OPEN, "오픈 시각 (KST) 예: 2026-11-05 10:00:00")
        field(root, K_LEAD, "lead (ms) — 오픈보다 이만큼 먼저 시작")
        field(root, K_INTERVAL, "재시도 간격 (ms)")
        field(root, K_ATTEMPTS, "최대 시도 횟수")
        field(root, K_WAIT, "클릭 후 결과 대기 (초)")
        refreshCb = CheckBox(this).apply { text = "받기 버튼 안 보이면 아래로 당겨 새로고침" }
        root.addView(refreshCb)

        root.addView(section("문구 (쉼표로 구분, 부분 일치)"))
        field(root, K_TARGETS, "받기 버튼 문구")
        field(root, K_SUCCESS, "성공 문구")
        field(root, K_ALREADY, "이미 받음 문구")
        field(root, K_SOLDOUT, "소진 문구")
        field(root, K_POPUPS, "자동으로 닫을 팝업 버튼 문구")

        root.addView(section("실행"))
        root.addView(row(button("예약 시작") { run(Mode.LIVE) }, button("취소") { MacroService.instance?.cancel() }))
        root.addView(row(button("즉시 실행(리허설)") { run(Mode.NOW) }, button("찾기만(클릭 안 함)") { run(Mode.DRY) }))
        root.addView(
            button("5초 뒤 화면 요소 덤프 (문구 확인용)") {
                val svc = MacroService.instance
                if (svc == null) toast("접근성 서비스를 먼저 켜세요") else svc.dumpAfter(5000)
            },
        )

        root.addView(section("로그 (최신순)"))
        root.addView(row(button("로그 복사") { copyLog() }, button("로그 지우기") { LogStore.clear() }))
        logView = TextView(this).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            textSize = 11f
            setTextIsSelectable(true)
        }
        root.addView(logView)

        showForm(Store.load(this))
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        checkUpdate(silent = true)
    }

    override fun onResume() {
        super.onResume()
        refreshStatus()
        LogStore.listener = { runOnUiThread { logView.text = LogStore.text(); refreshStatus() } }
        logView.text = LogStore.text()
    }

    override fun onPause() {
        LogStore.listener = null
        Store.save(this, readForm())
        super.onPause()
    }

    // ---- 동작 ----
    private fun run(mode: Mode) {
        val svc = MacroService.instance
        if (svc == null) {
            toast("접근성 서비스를 먼저 켜세요")
            return
        }
        val form = readForm()
        Store.save(this, form)
        val parsed = form.toJob(requireOpenAt = mode == Mode.LIVE)
        val job = parsed.job
        if (job == null) {
            toast(parsed.error ?: "입력 오류")
            return
        }
        svc.start(job, mode)
        toast(
            if (job.launchApp) "시작됨 — 대상 앱을 실행합니다"
            else "시작됨 — 대상 앱의 쿠폰 화면으로 이동해 두세요",
        )
    }

    // ---- 업데이트 ----
    /** silent: 앱 시작 시 자동 확인 — 새 버전이 있을 때만 알림창, 그 외엔 조용히. */
    private fun checkUpdate(silent: Boolean) {
        Thread({
            try {
                val rel = Updater.fetchLatest(BuildConfig.UPDATE_REPO)
                runOnUiThread {
                    when {
                        rel == null -> if (!silent) toast("배포된 릴리스가 없습니다")
                        Updater.isNewer(rel.code, BuildConfig.VERSION_CODE) -> promptUpdate(rel)
                        else -> if (!silent) toast("최신 버전입니다 (빌드 ${BuildConfig.VERSION_CODE})")
                    }
                }
            } catch (e: Exception) {
                LogStore.add("업데이트 확인 실패: ${e.message}")
                if (!silent) runOnUiThread { toast("업데이트 확인 실패: ${e.message}") }
            }
        }, "update-check").start()
    }

    private fun promptUpdate(rel: Updater.Release) {
        AlertDialog.Builder(this)
            .setTitle("새 버전 ${rel.tag}")
            .setMessage(rel.notes.take(400).ifBlank { rel.name })
            .setPositiveButton("업데이트") { _, _ -> downloadAndInstall(rel) }
            .setNegativeButton("나중에", null)
            .show()
    }

    private fun downloadAndInstall(rel: Updater.Release) {
        if (MacroService.instance?.isRunning() == true) {
            toast("작업 실행 중에는 업데이트할 수 없습니다")
            return
        }
        if (!packageManager.canRequestPackageInstalls()) {
            toast("'이 출처 허용'을 켠 뒤 다시 눌러주세요")
            startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            return
        }
        toast("다운로드 시작 (${rel.apkName})")
        Thread({
            try {
                val apk = File(cacheDir, "update.apk")
                Updater.download(rel, apk) { pct -> if (pct % 25 == 0) LogStore.add("업데이트 다운로드 $pct%") }
                val info = packageManager.getPackageArchiveInfo(apk.path, 0)
                if (info == null || info.packageName != packageName) {
                    apk.delete()
                    throw IllegalStateException("받은 파일이 이 앱의 APK 가 아님")
                }
                LogStore.add("업데이트 설치 요청: ${rel.tag}")
                Updater.install(this, apk)
            } catch (e: Exception) {
                LogStore.add("업데이트 실패: ${e.message}")
                runOnUiThread { toast("업데이트 실패: ${e.message}") }
            }
        }, "update-install").start()
    }

    private fun refreshStatus() {
        val on = MacroService.instance != null
        val running = MacroService.instance?.isRunning() == true
        statusView.text = "접근성 서비스: " + if (!on) "꺼짐 (설정에서 켜기)" else if (running) "연결됨 · 작업 실행 중" else "연결됨 · 대기"
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun copyLog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", LogStore.text(300)))
        toast("로그 복사됨")
    }

    private fun pickApp() {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        fun hinted(p: Pair<String, String>): Boolean {
            val k = (p.first + p.second).lowercase()
            return listOf("naver", "coupang", "네이버", "쿠팡").any { k.contains(it) }
        }
        val apps = pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedWith(compareByDescending<Pair<String, String>> { hinted(it) }.thenBy { it.first })
        AlertDialog.Builder(this)
            .setTitle("앱 선택")
            .setItems(apps.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, i ->
                fields.getValue(K_PKG).setText(apps[i].second)
            }
            .show()
    }

    // ---- 폼 ----
    private fun f(k: String) = fields.getValue(k).text.toString()

    private fun readForm() = FormState(
        name = f(K_NAME), pkg = f(K_PKG), launchApp = launchCb.isChecked,
        openAt = f(K_OPEN), leadMs = f(K_LEAD), intervalMs = f(K_INTERVAL),
        maxAttempts = f(K_ATTEMPTS), resultWaitSec = f(K_WAIT),
        steps = f(K_STEPS), popups = f(K_POPUPS), targets = f(K_TARGETS),
        success = f(K_SUCCESS), already = f(K_ALREADY), soldout = f(K_SOLDOUT),
        refreshOnMiss = refreshCb.isChecked,
    )

    private fun showForm(s: FormState) {
        fields.getValue(K_NAME).setText(s.name)
        fields.getValue(K_PKG).setText(s.pkg)
        launchCb.isChecked = s.launchApp
        fields.getValue(K_OPEN).setText(s.openAt)
        fields.getValue(K_LEAD).setText(s.leadMs)
        fields.getValue(K_INTERVAL).setText(s.intervalMs)
        fields.getValue(K_ATTEMPTS).setText(s.maxAttempts)
        fields.getValue(K_WAIT).setText(s.resultWaitSec)
        fields.getValue(K_STEPS).setText(s.steps)
        fields.getValue(K_POPUPS).setText(s.popups)
        fields.getValue(K_TARGETS).setText(s.targets)
        fields.getValue(K_SUCCESS).setText(s.success)
        fields.getValue(K_ALREADY).setText(s.already)
        fields.getValue(K_SOLDOUT).setText(s.soldout)
        refreshCb.isChecked = s.refreshOnMiss
    }

    // ---- UI 헬퍼 ----
    private fun toast(msg: String) = Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun title(t: String) = TextView(this).apply { text = t; textSize = 22f }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 15f
        setPadding(0, dp(20), 0, dp(4))
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    private fun field(parent: LinearLayout, key: String, label: String, lines: Int = 1) {
        parent.addView(TextView(this).apply { text = label; textSize = 12f; setPadding(0, dp(8), 0, 0) })
        val et = EditText(this).apply {
            if (lines > 1) {
                isSingleLine = false
                minLines = lines
                gravity = Gravity.TOP
            } else {
                isSingleLine = true
            }
        }
        fields[key] = et
        parent.addView(et)
    }

    companion object {
        private const val K_NAME = "name"
        private const val K_PKG = "pkg"
        private const val K_OPEN = "openAt"
        private const val K_LEAD = "leadMs"
        private const val K_INTERVAL = "intervalMs"
        private const val K_ATTEMPTS = "maxAttempts"
        private const val K_WAIT = "resultWaitSec"
        private const val K_STEPS = "steps"
        private const val K_POPUPS = "popups"
        private const val K_TARGETS = "targets"
        private const val K_SUCCESS = "success"
        private const val K_ALREADY = "already"
        private const val K_SOLDOUT = "soldout"
    }
}
