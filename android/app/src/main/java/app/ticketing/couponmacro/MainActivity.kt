package app.ticketing.couponmacro

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

/**
 * 메인 화면: 이벤트 선택 → 회차(12/18/24시) 선택 → 쿠폰 페이지 가는 길 기록 → 시작.
 * 나머지는 접어 둔 메뉴(테스트/고급/로그/업데이트). 설정은 이벤트(프로필)별로 자동 저장된다.
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
    private lateinit var chips: LinearLayout
    private lateinit var nextLine: TextView
    private lateinit var routeLine: TextView
    private lateinit var logView: TextView
    private var profile: Profile = Presets.ALL[0]

    /** 매일 반복되는 회차(예: "12:00, 18:00, 24:00")와 선택값("auto" 또는 "18:00") */
    private var slotsText: String = Schedule.DEFAULT_SLOTS
    private var slotSel: String = Schedule.AUTO

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

        // 2) 회차 (매일 반복)
        root.addView(section("2. 회차 (매일)"))
        chips = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(chips) })
        nextLine = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        root.addView(nextLine)
        root.addView(button("회차 시각 수정") { editSlots() })
        root.addView(note("'자동'은 다음 회차를 알아서 골라요. 시작 버튼은 회차마다 한 번씩 누르면 됩니다."))

        // 3) 쿠폰 페이지 가는 길
        root.addView(section("3. 쿠폰 페이지 가는 길"))
        routeLine = TextView(this).apply { setPadding(0, dp(4), 0, dp(4)) }
        root.addView(routeLine)
        root.addView(row(button("● 경로 기록") { startRecord() }, button("경로 지우기") { clearRoute() }))
        root.addView(
            note(
                "[경로 기록]을 누르면 대상 앱이 열립니다. 쿠폰 페이지까지 평소처럼 눌러서 이동한 뒤, " +
                    "화면 위에 뜨는 [■ 기록 끝]을 누르세요. (쿠폰 받기 버튼은 누르지 마세요) " +
                    "이후 시작하면 같은 순서로 자동 이동합니다.",
            ),
        )

        // 4) 시작
        root.addView(section("4. 시작"))
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
        root.addView(note("회차 몇 분 전에 시작하세요. 화면은 켜 둔 채(잠금 해제)로 두면 됩니다."))

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
            launchCb = CheckBox(this).apply { text = "시작 시 앱 자동 실행 (끄면 직접 쿠폰 페이지에 가 있어야 함)" }
            b.addView(launchCb)
            field(b, K_STEPS, "이동 단계 (한 줄에 하나, 해당 문구를 눌러 이동. 앞에 ? 붙이면 없어도 진행)", lines = 3)
                .onChanged { refreshRoute() }
            field(b, K_TARGETS, "받기 버튼 문구")
            field(b, K_SUCCESS, "성공 문구")
            field(b, K_ALREADY, "이미 받음 문구")
            field(b, K_SOLDOUT, "소진 문구")
            field(b, K_POPUPS, "자동으로 닫을 팝업 버튼 문구")
            field(b, K_LEAD, "미리 누르기 lead (ms)")
            field(b, K_INTERVAL, "재시도 간격 (ms)")
            field(b, K_ATTEMPTS, "최대 시도 횟수")
            field(b, K_WAIT, "클릭 후 결과 대기 (초)")
            refreshCb = CheckBox(this).apply { text = "새로고침 사용 (오픈 3초 전 1회 + 버튼이 안 보일 때 아래로 당김)" }
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
        // 경로 기록이 끝났으면 기록된 단계를 가져와 저장
        Store.takePendingSteps(this, profile.id)?.let { steps ->
            fields.getValue(K_STEPS).setText(steps)
            saveCurrent()
            toast("경로 ${Parse.steps(steps).size}단계 저장됨")
        }
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
        refreshRoute()
    }

    private fun resetProfile() {
        AlertDialog.Builder(this)
            .setMessage("'${profile.label}' 설정을 기본값으로 되돌릴까요? (대상 앱은 유지, 이동 경로·회차는 기본값으로)")
            .setPositiveButton("초기화") { _, _ ->
                showForm(profile.defaults.copy(pkg = f(K_PKG).ifBlank { profile.defaults.pkg }))
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
        if (svc.isRecording()) {
            toast("경로 기록 중입니다. 화면 위 [■ 기록 끝]을 먼저 누르세요")
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
            return
        }
        svc.start(job, mode)
        val when_ = if (mode == Mode.LIVE) " · ${Schedule.describe(job.openAtMillis, System.currentTimeMillis())}에 맞춰 대기" else ""
        toast("시작됨$when_")
    }

    private fun refreshStatus() {
        val svc = MacroService.instance
        val on = svc != null
        accStatus.text = if (on) "접근성: 켜짐" else "접근성: 꺼짐"
        accBox.visibility = if (on) View.GONE else View.VISIBLE
        statusLine.text = when {
            svc?.isRecording() == true -> "● 경로 기록 중 — 쿠폰 페이지까지 이동 후 [■ 기록 끝]"
            svc?.isRunning() == true -> "▶ 실행 중 · ${LogStore.last()}"
            else -> "대기 중"
        }
        refreshNext()
    }

    private fun openAppInfo() {
        startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
    }

    private fun copyLog() {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("log", LogStore.text(300)))
        toast("로그 복사됨")
    }

    // ---- 쿠폰 페이지 가는 길 ----
    private fun startRecord() {
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
        if (svc.isRunning()) {
            toast("실행 중에는 기록할 수 없습니다")
            return
        }
        saveCurrent()
        if (svc.startRecording(f(K_PKG).trim(), profile.id)) {
            toast("대상 앱이 열립니다. 쿠폰 페이지까지 이동한 뒤 [■ 기록 끝]을 누르세요")
        } else {
            toast("기록을 시작할 수 없습니다 (앱 실행 실패 또는 이미 기록 중)")
        }
    }

    private fun clearRoute() {
        fields.getValue(K_STEPS).setText("")
        saveCurrent()
    }

    private fun refreshRoute() {
        val steps = Parse.steps(f(K_STEPS))
        routeLine.text = if (steps.isEmpty()) {
            "경로 없음 — 시작하면 앱만 열립니다. 직접 쿠폰 페이지로 이동해 두거나 아래에서 기록하세요."
        } else {
            "경로 ${steps.size}단계: " + steps.joinToString(" → ") { (if (it.optional) "(" else "") + it.text + (if (it.optional) ")" else "") }
        }
    }

    // ---- 회차 ----
    private fun applySlots(slots: String, sel: String) {
        val parsed = Schedule.parseSlots(slots).ifEmpty { Schedule.parseSlots(Schedule.DEFAULT_SLOTS) }
        slotsText = parsed.joinToString(", ") { Schedule.label(it) }
        val selSec = Schedule.parseSlot(sel)
        slotSel = if (selSec != null && selSec in parsed) Schedule.label(selSec) else Schedule.AUTO
        refreshSchedule()
    }

    private fun refreshSchedule() {
        chips.removeAllViews()
        fun chip(text: String, key: String) = Button(this).apply {
            val on = slotSel == key
            this.text = (if (on) "● " else "") + text
            setTypeface(null, if (on) Typeface.BOLD else Typeface.NORMAL)
            alpha = if (on) 1f else 0.6f
            setOnClickListener {
                slotSel = key
                refreshSchedule()
                saveCurrent()
            }
        }
        chips.addView(chip("자동", Schedule.AUTO))
        for (s in Schedule.parseSlots(slotsText)) chips.addView(chip(Schedule.label(s), Schedule.label(s)))
        refreshNext()
    }

    private fun refreshNext() {
        val now = System.currentTimeMillis()
        val open = Schedule.nextOpen(Schedule.parseSlots(slotsText), slotSel, now)
        nextLine.text = if (open == null) "회차 시각을 확인하세요" else "시작하면 → " + Schedule.describe(open, now) + "에 맞춰 대기"
    }

    private fun editSlots() {
        val et = EditText(this).apply {
            setText(slotsText)
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("회차 시각 수정")
            .setMessage("쉼표로 구분, 24시간제. 24:00 = 자정.\n예: 12:00, 18:00, 24:00 (초도 가능: 11:59:59)")
            .setView(et)
            .setPositiveButton("저장") { _, _ ->
                val raw = et.text.toString()
                if (Schedule.parseSlots(raw).isEmpty()) {
                    toast("시각 형식 오류 — 예: 12:00, 18:00, 24:00")
                } else {
                    applySlots(raw, slotSel)
                    saveCurrent()
                }
            }
            .setNeutralButton("기본값") { _, _ ->
                applySlots(Schedule.DEFAULT_SLOTS, slotSel)
                saveCurrent()
            }
            .setNegativeButton("취소", null)
            .show()
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
        openAt = "", leadMs = f(K_LEAD), intervalMs = f(K_INTERVAL),
        maxAttempts = f(K_ATTEMPTS), resultWaitSec = f(K_WAIT),
        steps = f(K_STEPS), popups = f(K_POPUPS), targets = f(K_TARGETS),
        success = f(K_SUCCESS), already = f(K_ALREADY), soldout = f(K_SOLDOUT),
        refreshOnMiss = refreshCb.isChecked,
        slots = slotsText, slot = slotSel,
    )

    private fun showForm(s: FormState) {
        fields.getValue(K_PKG).setText(s.pkg)
        launchCb.isChecked = s.launchApp
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
        applySlots(s.slots, s.slot)
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
