package app.ticketing.couponmacro

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.TextWatcher
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
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 메인 화면: 이벤트 선택 → 오픈 시각 선택 → 시작. 나머지는 접어 둔 메뉴(테스트/고급/로그/업데이트).
 * 설정은 이벤트(프로필)별로 자동 저장되어 다음 실행 때 그대로 복원된다.
 */
class MainActivity : Activity() {

    private val fields = LinkedHashMap<String, EditText>()
    private val profileButtons = LinkedHashMap<String, Button>()
    private lateinit var launchCb: CheckBox
    private lateinit var refreshCb: CheckBox
    private lateinit var accStatus: TextView
    private lateinit var accBox: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var appLine: TextView
    private lateinit var timeButton: Button
    private lateinit var logView: TextView
    private var profile: Profile = Presets.ALL[0]

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(32))
        }
        setContentView(ScrollView(this).apply { addView(root) })

        root.addView(TextView(this).apply { text = "쿠폰 매크로   v${BuildConfig.VERSION_NAME}"; textSize = 20f })

        // 0) 접근성 상태 (꺼져 있을 때만 안내 표시)
        accStatus = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        root.addView(accStatus)
        accBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        accBox.addView(note("아래 [접근성 설정]에서 '쿠폰 매크로'를 켜세요.\n켜기가 막혀 있으면 [앱 정보] → ⋮ → '제한된 설정 허용' 후 다시."))
        accBox.addView(
            row(
                button("접근성 설정") { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) },
                button("앱 정보") { openAppInfo() },
            ),
        )
        root.addView(accBox)

        // 1) 이벤트 선택
        root.addView(section("1. 이벤트"))
        val btns = Presets.ALL.map { p ->
            button(p.label) { if (p.id != profile.id) selectProfile(p, persist = true) }.also { profileButtons[p.id] = it }
        }
        root.addView(row(*btns.toTypedArray()))
        appLine = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        root.addView(appLine)
        root.addView(button("대상 앱 변경") { pickApp() })

        // 2) 오픈 시각
        root.addView(section("2. 오픈 시각"))
        timeButton = button("") { pickTime() }
        root.addView(timeButton)

        // 3) 시작
        root.addView(section("3. 시작"))
        root.addView(
            Button(this).apply {
                text = "▶ 시작"
                textSize = 20f
                setMinHeight(dp(64))
                setOnClickListener { run(Mode.LIVE) }
            },
        )
        root.addView(button("■ 중지") { MacroService.instance?.cancel() })
        statusLine = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, 0) }
        root.addView(statusLine)
        root.addView(note("시작하면 대상 앱이 열립니다. 쿠폰 페이지로 이동해 두면 오픈 시각에 자동으로 누릅니다. (화면은 켜 둔 채로)"))

        // 접이식 메뉴
        collapsible(root, "테스트하기") { b ->
            b.addView(note("쿠폰 페이지에서 먼저 시험해 보세요. 상시 받을 수 있는 쿠폰으로 연습하면 좋습니다."))
            b.addView(
                row(
                    button("찾기만\n(클릭 안 함)") { run(Mode.DRY) },
                    button("지금 바로\n실행") { run(Mode.NOW) },
                ),
            )
            b.addView(
                button("5초 뒤 화면 요소 보기") {
                    val svc = MacroService.instance
                    if (svc == null) toast("접근성 서비스를 먼저 켜세요") else svc.dumpAfter(5000)
                },
            )
            b.addView(note("→ 대상 앱의 쿠폰 화면으로 전환해 두면 5초 뒤 버튼 문구가 '로그'에 나옵니다."))
        }

        collapsible(root, "고급 설정") { b ->
            b.addView(note("문구는 쉼표(,)로 구분, 부분 일치. 설정은 이벤트별로 자동 저장됩니다."))
            field(b, K_PKG, "대상 앱 패키지").onChanged { refreshAppLine() }
            launchCb = CheckBox(this).apply { text = "시작 시 앱 자동 실행" }
            b.addView(launchCb)
            field(b, K_OPEN, "오픈 시각 직접 입력 (초 단위) 예: 2026-11-05 10:00:00").onChanged { refreshTime() }
            field(b, K_STEPS, "이동 단계 (한 줄에 하나, 해당 문구를 눌러 이동. 앞에 ? 붙이면 없어도 진행)", lines = 3)
            field(b, K_TARGETS, "받기 버튼 문구")
            field(b, K_SUCCESS, "성공 문구")
            field(b, K_ALREADY, "이미 받음 문구")
            field(b, K_SOLDOUT, "소진 문구")
            field(b, K_POPUPS, "자동으로 닫을 팝업 버튼 문구")
            field(b, K_LEAD, "미리 누르기 lead (ms)")
            field(b, K_INTERVAL, "재시도 간격 (ms)")
            field(b, K_ATTEMPTS, "최대 시도 횟수")
            field(b, K_WAIT, "클릭 후 결과 대기 (초)")
            refreshCb = CheckBox(this).apply { text = "버튼이 안 보이면 아래로 당겨 새로고침" }
            b.addView(refreshCb)
            b.addView(button("이 이벤트 설정 초기화") { resetProfile() })
        }

        collapsible(root, "로그") { b ->
            b.addView(row(button("로그 복사") { copyLog() }, button("로그 지우기") { LogStore.clear() }))
            logView = TextView(this).apply {
                typeface = Typeface.MONOSPACE
                textSize = 11f
                setTextIsSelectable(true)
            }
            b.addView(logView)
        }

        collapsible(root, "업데이트") { b ->
            b.addView(note("현재 버전 ${BuildConfig.VERSION_NAME} (빌드 ${BuildConfig.VERSION_CODE}). 앱을 켤 때 새 버전이 있으면 자동으로 알려줍니다."))
            b.addView(button("업데이트 확인") { checkUpdate(silent = false) })
        }

        selectProfile(Presets.byId(Store.lastProfileId(this)), persist = false)

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
        saveCurrent()
        super.onPause()
    }

    // ---- 저장 / 프로필 ----
    private fun saveCurrent() = Store.save(this, profile.id, readForm())

    private fun selectProfile(p: Profile, persist: Boolean) {
        if (persist) saveCurrent()
        profile = p
        Store.setLastProfile(this, p.id)
        showForm(Store.load(this, p.id) ?: p.defaults)
        if (f(K_PKG).isBlank()) autoDetectApp()
        for ((id, b) in profileButtons) {
            val on = id == p.id
            b.text = (if (on) "● " else "○ ") + Presets.byId(id).label
            b.setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            b.alpha = if (on) 1f else 0.6f
        }
        refreshAppLine()
        refreshTime()
    }

    private fun resetProfile() {
        AlertDialog.Builder(this)
            .setMessage("'${profile.label}' 설정을 기본값으로 되돌릴까요? (대상 앱과 오픈 시각은 유지)")
            .setPositiveButton("초기화") { _, _ ->
                showForm(profile.defaults.copy(pkg = f(K_PKG).ifBlank { profile.defaults.pkg }, openAt = f(K_OPEN)))
                saveCurrent()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---- 동작 ----
    private fun run(mode: Mode) {
        val svc = MacroService.instance
        if (svc == null) {
            toast("접근성 서비스를 먼저 켜세요")
            return
        }
        if (f(K_PKG).isBlank()) {
            toast("대상 앱을 먼저 선택하세요")
            pickApp()
            return
        }
        val form = readForm()
        saveCurrent()
        val parsed = form.toJob(requireOpenAt = mode == Mode.LIVE)
        val job = parsed.job
        if (job == null) {
            toast(parsed.error ?: "입력 오류")
            if (mode == Mode.LIVE && form.openAt.isBlank()) pickTime()
            return
        }
        if (mode == Mode.LIVE && job.openAtMillis < System.currentTimeMillis()) {
            toast("오픈 시각이 이미 지났습니다. 다시 선택하세요")
            pickTime()
            return
        }
        svc.start(job, mode)
        toast(
            if (job.launchApp) "시작됨 — 대상 앱이 열리면 쿠폰 페이지로 이동해 두세요"
            else "시작됨 — 대상 앱의 쿠폰 페이지로 이동해 두세요",
        )
    }

    private fun refreshStatus() {
        val svc = MacroService.instance
        val on = svc != null
        accStatus.text = if (on) "접근성: 켜짐" else "접근성: 꺼짐"
        accBox.visibility = if (on) View.GONE else View.VISIBLE
        statusLine.text = if (svc?.isRunning() == true) "▶ 실행 중 · ${LogStore.last()}" else "대기 중"
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun copyLog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", LogStore.text(300)))
        toast("로그 복사됨")
    }

    // ---- 대상 앱 ----
    private fun launchableApps(): List<Pair<String, String>> {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return pm.queryIntentActivities(intent, 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
    }

    /** 패키지가 비어 있으면 설치된 앱 이름에서 프로필 키워드로 한 개만 걸릴 때 자동 선택. */
    private fun autoDetectApp() {
        val hits = launchableApps().filter { (label, pkg) ->
            profile.appKeywords.any { label.contains(it, ignoreCase = true) || pkg.contains(it, ignoreCase = true) }
        }
        if (hits.size == 1) {
            fields.getValue(K_PKG).setText(hits[0].second)
            toast("대상 앱 자동 선택: ${hits[0].first}")
        }
    }

    private fun pickApp() {
        fun hinted(p: Pair<String, String>): Boolean {
            val k = (p.first + p.second).lowercase()
            return (profile.appKeywords + listOf("naver", "coupang", "네이버", "쿠팡")).any { k.contains(it.lowercase()) }
        }
        val apps = launchableApps()
            .sortedWith(compareByDescending<Pair<String, String>> { hinted(it) }.thenBy { it.first })
        AlertDialog.Builder(this)
            .setTitle("대상 앱 선택")
            .setItems(apps.map { "${it.first}\n${it.second}" }.toTypedArray()) { _, i ->
                fields.getValue(K_PKG).setText(apps[i].second)
                saveCurrent()
            }
            .show()
    }

    private fun labelOf(pkg: String): String = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) {
        "설치 안 됨?"
    }

    private fun refreshAppLine() {
        val pkg = f(K_PKG).trim()
        appLine.text = if (pkg.isEmpty()) "대상 앱: 미지정 — [대상 앱 변경]을 눌러 선택" else "대상 앱: ${labelOf(pkg)}  ($pkg)"
    }

    // ---- 오픈 시각 ----
    private fun refreshTime() {
        val raw = f(K_OPEN)
        val ms = runCatching { Parse.openAt(raw) }.getOrNull()
        timeButton.text = when {
            raw.isBlank() -> "탭해서 오픈 시각 선택"
            ms == null -> "시각 형식 오류 — 탭해서 다시 선택"
            else -> {
                val t = Instant.ofEpochMilli(ms).atZone(Parse.ZONE)
                val pattern = if (t.second == 0) "M월 d일(E) HH:mm" else "M월 d일(E) HH:mm:ss"
                t.format(DateTimeFormatter.ofPattern(pattern, Locale.KOREAN)) +
                    if (ms < System.currentTimeMillis()) "   (지난 시각 — 다시 선택)" else ""
            }
        }
    }

    private fun pickTime() {
        val cur = runCatching { LocalDateTime.ofInstant(Instant.ofEpochMilli(Parse.openAt(f(K_OPEN))), Parse.ZONE) }
            .getOrNull()
            ?.takeIf { it.isAfter(LocalDateTime.now(Parse.ZONE)) }
            ?: LocalDateTime.now(Parse.ZONE).plusDays(1).withHour(10).withMinute(0).withSecond(0).withNano(0)
        DatePickerDialog(
            this,
            { _, y, m, d ->
                TimePickerDialog(
                    this,
                    { _, h, min ->
                        val t = LocalDateTime.of(y, m + 1, d, h, min, 0)
                        fields.getValue(K_OPEN).setText(t.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")))
                        saveCurrent()
                    },
                    cur.hour, cur.minute, true,
                ).show()
            },
            cur.year, cur.monthValue - 1, cur.dayOfMonth,
        ).show()
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

    // ---- 폼 ----
    private fun f(k: String) = fields.getValue(k).text.toString()

    private fun readForm() = FormState(
        name = profile.label, pkg = f(K_PKG).trim(), launchApp = launchCb.isChecked,
        openAt = f(K_OPEN).trim(), leadMs = f(K_LEAD), intervalMs = f(K_INTERVAL),
        maxAttempts = f(K_ATTEMPTS), resultWaitSec = f(K_WAIT),
        steps = f(K_STEPS), popups = f(K_POPUPS), targets = f(K_TARGETS),
        success = f(K_SUCCESS), already = f(K_ALREADY), soldout = f(K_SOLDOUT),
        refreshOnMiss = refreshCb.isChecked,
    )

    private fun showForm(s: FormState) {
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

    private fun note(t: String) = TextView(this).apply {
        text = t
        textSize = 12f
        setPadding(0, dp(4), 0, dp(4))
    }

    private fun section(t: String) = TextView(this).apply {
        text = t
        textSize = 16f
        setPadding(0, dp(20), 0, dp(4))
        setTypeface(typeface, Typeface.BOLD)
    }

    private fun button(t: String, onClick: () -> Unit) = Button(this).apply {
        text = t
        setOnClickListener { onClick() }
    }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        views.forEach { addView(it, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)) }
    }

    /** 제목을 누르면 펼쳐지는 영역 */
    private fun collapsible(parent: LinearLayout, title: String, build: (LinearLayout) -> Unit) {
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val header = TextView(this).apply {
            text = "▸ $title"
            textSize = 16f
            setPadding(0, dp(18), 0, dp(8))
            setTypeface(typeface, Typeface.BOLD)
        }
        header.setOnClickListener {
            val show = body.visibility != View.VISIBLE
            body.visibility = if (show) View.VISIBLE else View.GONE
            header.text = (if (show) "▾ " else "▸ ") + title
        }
        parent.addView(header)
        parent.addView(body)
        build(body)
    }

    private fun field(parent: LinearLayout, key: String, label: String, lines: Int = 1): EditText {
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
        return et
    }

    private fun EditText.onChanged(cb: () -> Unit) {
        addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) = cb()
        })
    }

    companion object {
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
