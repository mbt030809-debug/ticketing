package app.ticketing.couponmacro

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
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
    private lateinit var dotsCb: CheckBox
    private lateinit var accStatus: TextView
    private lateinit var accBox: LinearLayout
    private lateinit var statusLine: TextView
    private lateinit var startBtn: Button
    private lateinit var testBtn: Button
    private lateinit var appLine: TextView
    private lateinit var timesBox: LinearLayout
    private lateinit var nextLine: TextView
    private lateinit var autoCb: CheckBox
    private lateinit var autoLine: TextView
    private lateinit var routeLine: TextView
    private lateinit var couponNote: TextView
    private lateinit var logView: TextView
    private var profile: Profile = Presets.ALL[0]

    /** 매일 반복되는 회차(예: "12:00, 18:00, 24:00")와 선택값("auto" 또는 "18:00") */
    private var slotsText: String = Schedule.DEFAULT_SLOTS
    private var slotsOff: String = ""

    /** showForm 이 값을 채우는 동안에는 변경 리스너가 저장하지 않도록 */
    private var loading = false

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

        // 2) 회차 시각 (매일 반복, 개수 제한 없음)
        root.addView(section("2. 회차 시각 (매일)"))
        timesBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(timesBox)
        root.addView(button("＋ 시각 추가") { addSlot() })
        nextLine = TextView(this).apply { setPadding(0, dp(8), 0, 0) }
        root.addView(nextLine)
        autoCb = CheckBox(this).apply { text = "매일 켜진 시각에 자동 실행 (1분 전 잠금 해제 알람)" }
        autoCb.setOnCheckedChangeListener { _, _ ->
            if (!loading) {
                saveCurrent()
                refreshNext()
            }
        }
        root.addView(autoCb)
        autoLine = TextView(this).apply { textSize = 13f }
        root.addView(autoLine)
        root.addView(
            note(
                "시각은 2개든 4개든 자유롭게 추가·삭제하고, 체크로 켜고 끕니다. 자동 실행을 켜면 켜진 시각 1분 전에 알람이 울립니다 — " +
                    "그때 화면 잠금을 풀고 폰을 켜 두세요. 앱이 자동으로 열려 경로를 따라 이동하고 오픈 시각에 누릅니다. " +
                    "접근성 서비스가 켜져 있어야 하고, 배터리 설정에서 이 앱을 '제한 없음'으로 두면 가장 안정적입니다.",
            ),
        )

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

        // 4) 받을 쿠폰 (필수)
        root.addView(section("4. 받을 쿠폰 (필수)"))
        field(root, K_COUPONS, "받을 쿠폰 문구 (한 줄에 하나)", lines = 3).onChanged { refreshCouponNote() }
        couponNote = note("")
        root.addView(couponNote)
        root.addView(row(button("화면에서 고르기") { startPick() }, button("목록 지우기") { clearCoupons() }))
        root.addView(
            note(
                "[화면에서 고르기]: 대상 앱이 열리고(기록한 경로로 이동), 쿠폰 페이지에서 화면 위 버튼을 누르세요.\n" +
                    "· [쿠폰 목록 읽기] — 쿠폰 목록에서 받을 것만 체크. 글자가 이미지로 된 쿠폰은 OCR(글자 인식)로 읽어 '오후 5시 오픈+10,000원' 처럼 제안합니다.\n" +
                    "· [위치로 찍기] — 카드를 직접 터치해 지정. 화면에 글자 정보가 없으면 OCR 로 바로 위의 제목 글자를 읽어 기준점으로 삼습니다. 페이지를 스크롤하지 않은 첫 화면에서 보이는 카드만 지정하세요.\n" +
                    "직접 적을 땐 카드에 적힌 문구(예: 5,000원 할인)를 한 줄에 하나씩, 비슷한 쿠폰은 +로 이으세요 (예: 5,000원+3만원 이상). " +
                    "[고급 설정 → 진단 도구 → 찾기만]을 하면 누를 위치에 초록 점이 뜹니다.",
            ),
        )

        // 5) 시작: 버튼은 딱 두 개 — 예약 시작 / 즉시 실행(테스트)
        root.addView(section("5. 시작"))
        startBtn = Button(this).apply {
            text = "▶ 예약 시작"
            textSize = 20f
            setMinHeight(dp(64))
            setOnClickListener { onStartClicked() }
        }
        root.addView(startBtn)
        testBtn = Button(this).apply {
            text = "⚡ 즉시 실행 (테스트)"
            textSize = 16f
            setMinHeight(dp(52))
            setOnClickListener { run(Mode.NOW) }
        }
        root.addView(testBtn)
        statusLine = TextView(this).apply { textSize = 14f; setPadding(0, dp(8), 0, 0) }
        root.addView(statusLine)
        root.addView(
            note(
                "[예약 시작]: 켜진 다음 회차 시각에 맞춰 대기했다가 누릅니다 (실행 중에는 [중지]로 바뀜). " +
                    "[즉시 실행]: 앱을 다시 시작하지 않고 지금 보던 화면에서 바로 시도합니다 (경로 이동 없음) — 쿠폰 페이지를 연 채로 누르세요. 상시 받을 수 있는 쿠폰으로 연습하세요. 클릭이 나가면 빨간 점이 잠깐 뜹니다.",
            ),
        )

        // 접이식 메뉴
        collapsible(root, "고급 설정") { b ->
            b.addView(note("문구는 쉼표(,)로 구분, 부분 일치. 설정은 이벤트별로 자동 저장됩니다."))
            field(b, K_PKG, "대상 앱 패키지").onChanged { refreshAppLine() }
            launchCb = CheckBox(this).apply { text = "시작 시 앱 자동 실행 (끄면 직접 쿠폰 페이지에 가 있어야 함)" }
            b.addView(launchCb)
            field(b, K_STEPS, "이동 단계 (한 줄에 하나, 해당 문구를 눌러 이동. 앞에 ? 붙이면 없어도 진행)", lines = 3)
                .onChanged { refreshRoute() }
            field(b, K_TARGETS, "받기 버튼 문구 (쿠폰 카드 안의 버튼)")
            field(b, K_SUCCESS, "성공 문구")
            field(b, K_ALREADY, "이미 받음 문구")
            field(b, K_SOLDOUT, "소진 문구")
            field(b, K_POPUPS, "자동으로 닫을 팝업 버튼 문구")
            field(b, K_WARN, "자동 실행 알람: 회차 몇 초 전에 울릴지 (20~600, 기본 60)")
            field(b, K_LEAD, "미리 누르기 lead (ms)")
            field(b, K_INTERVAL, "재시도 간격 (ms)")
            field(b, K_ATTEMPTS, "최대 시도 횟수")
            field(b, K_WAIT, "클릭 후 결과 대기 (초)")
            refreshCb = CheckBox(this).apply { text = "새로고침 사용 (오픈 3초 전 1회 + 버튼이 안 보일 때 아래로 당김)" }
            b.addView(refreshCb)
            dotsCb = CheckBox(this).apply { text = "클릭 위치를 빨간 점으로 표시 (찾기만: 초록 점)" }
            b.addView(dotsCb)
            b.addView(section("진단 도구"))
            b.addView(note("문제가 있을 때만 사용하세요. [찾기만]은 클릭하지 않고 누를 위치에 초록 점만 표시합니다."))
            b.addView(button("찾기만 (클릭 안 함)") { run(Mode.DRY) })
            b.addView(
                button("5초 뒤 화면 요소 보기") {
                    val svc = MacroService.instance
                    if (svc == null) toast("접근성 서비스를 먼저 켜세요") else svc.dumpAfter(5000)
                },
            )
            b.addView(note("→ 대상 앱의 쿠폰 화면으로 전환해 두면 5초 뒤 버튼 문구가 '로그'에 나옵니다."))
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
            b.addView(
                note(
                    "업데이트가 안 되면(서명이 다르다는 오류 등): 이 앱을 삭제한 뒤 아래 [릴리스 페이지 열기]에서 최신 APK를 받아 새로 설치하세요. " +
                        "한 번만 하면 이후부터는 앱 안에서 업데이트됩니다. (삭제하면 저장된 설정이 지워집니다)",
                ),
            )
            b.addView(
                button("릴리스 페이지 열기 (APK 직접 받기)") {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATE_REPO}/releases/latest")))
                },
            )
        }

        selectProfile(Presets.byId(Store.lastProfileId(this)), persist = false)

        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        checkUpdate(silent = true)
        Scheduler.rescheduleAll(this)
    }

    override fun onResume() {
        super.onResume()
        // 경로 기록이 끝났으면 기록된 단계를 가져와 저장
        Store.takePendingSteps(this, profile.id)?.let { steps ->
            fields.getValue(K_STEPS).setText(steps)
            saveCurrent()
            toast("경로 ${Parse.steps(steps).size}단계 저장됨")
        }
        Store.takePendingCoupons(this, profile.id)?.let { (c, append) ->
            val merged = if (append) (Parse.coupons(f(K_COUPONS)) + Parse.coupons(c)).distinct().joinToString("\n") else c
            fields.getValue(K_COUPONS).setText(merged)
            saveCurrent()
            toast("쿠폰 ${Parse.coupons(c).size}개 ${if (append) "추가" else "저장"}됨")
        }
        refreshStatus()
        LogStore.listener = {
            runOnUiThread {
                logView.text = LogStore.text()
                refreshStatus()
                logView.postDelayed({ refreshStatus() }, 1200) // 작업 스레드가 끝난 뒤 버튼 상태 정리
            }
        }
        logView.text = LogStore.text()
    }

    override fun onPause() {
        LogStore.listener = null
        saveCurrent()
        super.onPause()
    }

    // ---- 저장 / 프로필 ----
    private fun saveCurrent() {
        Store.save(this, profile.id, readForm())
        Scheduler.rescheduleAll(this) // 자동 실행 알람을 설정에 맞춰 다시 등록
    }

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
            .setMessage("'${profile.label}' 설정을 기본값으로 되돌릴까요? (대상 앱은 유지, 이동 경로·받을 쿠폰·회차·자동 실행은 기본값으로)")
            .setPositiveButton("초기화") { _, _ ->
                showForm(profile.defaults.copy(pkg = f(K_PKG).ifBlank { profile.defaults.pkg }))
                saveCurrent()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    // ---- 동작 ----
    /** 실행 중이면 [예약 시작] 버튼이 [중지] 역할 */
    private fun onStartClicked() {
        val svc = MacroService.instance
        if (svc != null && svc.isRunning()) {
            svc.cancel()
            toast("중지했습니다")
            refreshStatus()
            return
        }
        run(Mode.LIVE)
    }

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
        refreshStatus()
        val when_ = if (mode == Mode.LIVE) " · ${Schedule.describe(job.openAtMillis, System.currentTimeMillis())}에 맞춰 대기" else ""
        toast("시작됨$when_")
    }

    private fun refreshStatus() {
        val svc = MacroService.instance
        val on = svc != null
        accStatus.text = if (on) "접근성: 켜짐" else "접근성: 꺼짐"
        accBox.visibility = if (on) View.GONE else View.VISIBLE
        val running = svc?.isRunning() == true
        startBtn.text = if (running) "■ 중지" else "▶ 예약 시작"
        testBtn.isEnabled = !running
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

    // ---- 받을 쿠폰 ----
    private fun startPick() {
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
        if (svc.isRunning() || svc.isRecording()) {
            toast("실행/기록 중에는 쓸 수 없습니다")
            return
        }
        saveCurrent()
        val parsed = readForm().toJob(requireOpenAt = false, requireCoupons = false)
        val job = parsed.job
        if (job == null) {
            toast(parsed.error ?: "입력 오류")
            return
        }
        if (svc.startPicking(job, profile.id)) {
            toast("대상 앱이 열립니다. 쿠폰 페이지가 보이면 화면 위 [쿠폰 목록 읽기]를 누르세요")
        } else {
            toast("시작할 수 없습니다")
        }
    }

    private fun clearCoupons() {
        fields.getValue(K_COUPONS).setText("")
        saveCurrent()
    }

    private fun refreshCouponNote() {
        val list = Parse.coupons(f(K_COUPONS))
        val pos = list.count { PosTarget.isPos(it) }
        couponNote.text = if (list.isEmpty()) {
            "⚠ 받을 쿠폰이 정해지지 않았습니다. 정하지 않으면 시작할 수 없어요."
        } else {
            "받을 쿠폰 ${list.size}개 (문구 ${list.size - pos}, 위치 $pos): 정한 쿠폰만 누릅니다."
        }
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
    private fun applySlots(slots: String, off: String) {
        val all = Schedule.parseSlots(slots)
        slotsText = all.joinToString(", ") { Schedule.label(it) }
        slotsOff = Schedule.parseSlots(off).filter { it in all }.joinToString(", ") { Schedule.label(it) }
        refreshSchedule()
    }

    private fun refreshSchedule() {
        timesBox.removeAllViews()
        val all = Schedule.parseSlots(slotsText)
        val off = Schedule.parseSlots(slotsOff).toSet()
        for (sec in all) {
            val cb = CheckBox(this).apply {
                text = Schedule.label(sec)
                textSize = 18f
                isChecked = sec !in off
                setOnCheckedChangeListener { _, on -> setSlotEnabled(sec, on) }
            }
            val del = Button(this).apply {
                text = "✕"
                setOnClickListener { removeSlot(sec) }
            }
            timesBox.addView(
                LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    addView(cb, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(del)
                },
            )
        }
        if (all.isEmpty()) timesBox.addView(note("시각이 없습니다. [＋ 시각 추가]로 추가하세요."))
        refreshNext()
    }

    private fun setSlotEnabled(sec: Int, on: Boolean) {
        if (loading) return
        val off = Schedule.parseSlots(slotsOff).toMutableSet()
        if (on) off.remove(sec) else off.add(sec)
        slotsOff = off.sorted().joinToString(", ") { Schedule.label(it) }
        saveCurrent()
        refreshNext()
    }

    private fun removeSlot(sec: Int) {
        val all = Schedule.parseSlots(slotsText).filter { it != sec }
        slotsText = all.joinToString(", ") { Schedule.label(it) }
        slotsOff = Schedule.parseSlots(slotsOff).filter { it != sec }.joinToString(", ") { Schedule.label(it) }
        refreshSchedule()
        saveCurrent()
        refreshNext()
    }

    private fun addSlot() {
        val et = EditText(this).apply {
            hint = "예: 12:00  (초도 가능 11:59:59, 24:00 = 자정)"
            isSingleLine = true
        }
        AlertDialog.Builder(this)
            .setTitle("시각 추가 (24시간제)")
            .setView(et)
            .setPositiveButton("추가") { _, _ -> addSlotText(et.text.toString()) }
            .setNeutralButton("시계로 선택") { _, _ ->
                TimePickerDialog(this, { _, h, m -> addSlotText("%02d:%02d".format(h, m)) }, 12, 0, true).show()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    private fun addSlotText(raw: String) {
        val sec = Schedule.parseSlot(raw)
        if (sec == null) {
            toast("시각 형식 오류 — 예: 18:00")
            return
        }
        val all = Schedule.parseSlots(slotsText)
        if (sec in all) {
            toast("이미 있는 시각입니다")
            return
        }
        slotsText = (all + sec).sorted().joinToString(", ") { Schedule.label(it) }
        refreshSchedule()
        saveCurrent()
        refreshNext()
    }

    private fun refreshNext() {
        val now = System.currentTimeMillis()
        val open = Schedule.nextOpen(Schedule.enabled(slotsText, slotsOff), Schedule.AUTO, now)
        nextLine.text = if (open == null) "켜진 시각이 없습니다" else "다음 회차: " + Schedule.describe(open, now)
        autoLine.text = if (!autoCb.isChecked) {
            ""
        } else {
            val t = Scheduler.next(this, now)
            if (t == null) {
                "자동 실행할 회차가 없습니다 (켜진 시각 확인)"
            } else {
                "자동 실행 예정: " + Schedule.describe(t.occurrenceMs, now) + " · 알람 " +
                    Schedule.formatOpenAt(t.triggerAtMs).substring(11, 16)
            }
        }
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
                        rel == null -> if (!silent) toast("배포된 새 버전이 아직 없습니다")
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
        steps = f(K_STEPS), popups = f(K_POPUPS), targets = f(K_TARGETS), coupons = f(K_COUPONS),
        success = f(K_SUCCESS), already = f(K_ALREADY), soldout = f(K_SOLDOUT),
        refreshOnMiss = refreshCb.isChecked,
        slots = slotsText, slotsOff = slotsOff,
        autoRun = autoCb.isChecked, warnSec = f(K_WARN),
        showDots = dotsCb.isChecked,
    )

    private fun showForm(s: FormState) {
        loading = true
        try {
        fields.getValue(K_PKG).setText(s.pkg)
        launchCb.isChecked = s.launchApp
        fields.getValue(K_LEAD).setText(s.leadMs)
        fields.getValue(K_INTERVAL).setText(s.intervalMs)
        fields.getValue(K_ATTEMPTS).setText(s.maxAttempts)
        fields.getValue(K_WAIT).setText(s.resultWaitSec)
        fields.getValue(K_STEPS).setText(s.steps)
        fields.getValue(K_POPUPS).setText(s.popups)
        fields.getValue(K_TARGETS).setText(s.targets)
        fields.getValue(K_COUPONS).setText(s.coupons)
        fields.getValue(K_SUCCESS).setText(s.success)
        fields.getValue(K_ALREADY).setText(s.already)
        fields.getValue(K_SOLDOUT).setText(s.soldout)
        refreshCb.isChecked = s.refreshOnMiss
        dotsCb.isChecked = s.showDots
        fields.getValue(K_WARN).setText(s.warnSec)
        autoCb.isChecked = s.autoRun
        applySlots(s.slots, s.slotsOff)
        } finally {
            loading = false
        }
        refreshNext()
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
        private const val K_WARN = "warnSec"
        private const val K_INTERVAL = "intervalMs"
        private const val K_ATTEMPTS = "maxAttempts"
        private const val K_WAIT = "resultWaitSec"
        private const val K_STEPS = "steps"
        private const val K_POPUPS = "popups"
        private const val K_TARGETS = "targets"
        private const val K_COUPONS = "coupons"
        private const val K_SUCCESS = "success"
        private const val K_ALREADY = "already"
        private const val K_SOLDOUT = "soldout"
    }
}
