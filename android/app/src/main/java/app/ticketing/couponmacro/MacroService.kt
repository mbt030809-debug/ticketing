package app.ticketing.couponmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class Outcome(val status: String, val attempts: Int, val detail: String)

/**
 * 접근성 서비스: 대상 앱을 실행하고, 지정 시각에 "쿠폰 받기" 버튼을 반복 클릭한다.
 * 작업(Job) 실행 중에만 대상 앱 화면을 읽으며, 그 외 화면 내용은 읽지/저장하지 않는다(토스트 문구 제외).
 */
class MacroService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: MacroService? = null
        private const val CHANNEL = "result"
        private val POPUP_WORDS = listOf("닫기", "나중에", "다음에", "건너뛰기", "취소", "오늘 하루 보지 않기", "확인")
    }

    @Volatile private var worker: Thread? = null
    @Volatile private var cancelled = false
    @Volatile private var lastToast = ""
    @Volatile private var lastToastAt = 0L
    private var overlay: View? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val main = Handler(Looper.getMainLooper())

    // ---- 수명주기 ----
    override fun onServiceConnected() {
        instance = this
        LogStore.add("접근성 서비스 연결됨")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        cancel()
        if (recordPkg != null) {
            recordPkg = null
            hideRecordOverlay()
        }
        hidePick()
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        // 경로 기록 중에만, 대상 앱에서 눌린 요소의 문구를 기록
        if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {
            onRecordedClick(event)
            return
        }
        // 쿠폰 결과가 토스트로만 뜨는 경우를 위해 토스트 문구만 보관
        if (event.eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED &&
            event.className?.toString()?.contains("Toast") == true
        ) {
            val t = event.text.joinToString(" ")
            if (t.isNotBlank() && isRunning()) {
                lastToast = t
                lastToastAt = SystemClock.elapsedRealtime()
                LogStore.add("토스트: $t")
            }
        }
    }

    // ---- 외부 API ----
    fun isRunning(): Boolean = worker?.isAlive == true

    fun start(job: Job, mode: Mode) {
        if (isRunning()) {
            LogStore.add("이미 실행 중 — 먼저 취소하세요")
            return
        }
        cancelled = false
        worker = Thread({ runJob(job, mode) }, "macro").also { it.start() }
    }

    fun cancel() {
        cancelled = true
        worker?.interrupt()
        hidePick()
    }

    // ---- 경로 기록: 사용자가 직접 눌러 이동하는 순서를 '이동 단계'로 저장 ----
    @Volatile private var recordPkg: String? = null
    private var recordProfile = ""
    private val recorded = ArrayList<String>()
    private var recordOverlay: View? = null

    fun isRecording(): Boolean = recordPkg != null

    /** 대상 앱을 첫 화면으로 열고 기록 시작. 화면 위에 [■ 기록 끝] 버튼이 뜬다. */
    fun startRecording(pkg: String, profileId: String): Boolean {
        if (isRunning() || isRecording()) return false
        synchronized(recorded) { recorded.clear() }
        recordProfile = profileId
        if (!launch(pkg)) return false
        recordPkg = pkg
        showRecordOverlay()
        LogStore.add("경로 기록 시작: 쿠폰 페이지까지 평소처럼 눌러 이동한 뒤 [■ 기록 끝]")
        return true
    }

    fun stopRecording() {
        if (recordPkg == null) return
        recordPkg = null
        hideRecordOverlay()
        val steps = synchronized(recorded) { recorded.toList() }
        // 팝업 닫기 류는 매번 뜨는 게 아니므로 '없어도 진행'(?)으로 저장
        val lines = steps.map { s -> if (POPUP_WORDS.any { s == it }) "?$s" else s }
        if (lines.isNotEmpty()) Store.setPendingSteps(this, recordProfile, lines.joinToString("\n"))
        LogStore.add("경로 기록 끝: ${lines.size}단계")
        notifyResult(
            "경로 기록 완료",
            if (lines.isEmpty()) "기록된 단계가 없습니다 (문구 없는 버튼은 기록되지 않음)" else "${lines.size}단계 저장됨 — 탭해서 앱 열기",
        )
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        } catch (e: Exception) {
            LogStore.add("앱 열기 실패(알림을 눌러 여세요): ${e.message}")
        }
    }

    private fun onRecordedClick(event: AccessibilityEvent) {
        val pkg = recordPkg ?: return
        if (event.packageName?.toString() != pkg) return
        val label = clickLabel(event)
        if (label == null) {
            LogStore.add("기록: 문구 없는 버튼 클릭 — 건너뜀 (직접 이동 단계에 추가하세요)")
            return
        }
        val n = synchronized(recorded) {
            if (recorded.lastOrNull() != label) recorded += label
            recorded.size
        }
        LogStore.add("기록 $n: $label")
    }

    private fun clickLabel(event: AccessibilityEvent): String? {
        val direct = event.text.firstOrNull()?.toString()?.takeIf { it.isNotBlank() }
            ?: event.contentDescription?.toString()?.takeIf { it.isNotBlank() }
        val raw = direct ?: event.source?.let { firstLabel(it, 3) }
        return raw?.replace(Regex("\\s+"), " ")?.trim()?.take(40)?.takeIf { it.isNotEmpty() }
    }

    private fun firstLabel(n: AccessibilityNodeInfo, depth: Int): String? {
        n.text?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        n.contentDescription?.toString()?.takeIf { it.isNotBlank() }?.let { return it }
        if (depth <= 0) return null
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            firstLabel(c, depth - 1)?.let { return it }
        }
        return null
    }

    private fun showRecordOverlay() {
        main.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val b = Button(this).apply {
                    text = "■ 기록 끝"
                    setOnClickListener { stopRecording() }
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    y = (96 * resources.displayMetrics.density).toInt()
                }
                wm.addView(b, lp)
                recordOverlay = b
            } catch (e: Exception) {
                LogStore.add("기록 버튼 표시 실패: $e")
            }
        }
    }

    private fun hideRecordOverlay() {
        main.post {
            try {
                recordOverlay?.let { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) }
            } catch (_: Exception) {
            }
            recordOverlay = null
        }
    }

    // ---- 화면에서 쿠폰 고르기: 지금 보이는 쿠폰 카드를 목록으로 보여주고 받을 것만 체크 ----
    @Volatile private var pickBar: View? = null
    @Volatile private var pickPanel: View? = null
    @Volatile private var pickProfile = ""

    /** 대상 앱을 열고(기록된 경로가 있으면 따라 이동) 화면 위에 [쿠폰 목록 읽기] 버튼을 띄운다. */
    fun startPicking(job: Job, profileId: String): Boolean {
        if (isRunning() || isRecording()) return false
        pickProfile = profileId
        cancelled = false
        worker = Thread({ runPick(job) }, "pick").also { it.start() }
        return true
    }

    private fun runPick(job: Job) {
        try {
            if (job.launchApp) {
                if (!launch(job.pkg)) {
                    LogStore.add("앱 실행 실패: ${job.pkg}")
                    return
                }
                LogStore.add("쿠폰 고르기: 앱 실행")
                sleep(2000)
            }
            for ((i, s) in job.steps.withIndex()) {
                val ok = waitAndClick(job, s.text, 10_000)
                LogStore.add("이동 ${i + 1}/${job.steps.size} '${s.text}' ${if (ok) "OK" else "못 찾음"}")
                if (!ok && !s.optional) {
                    LogStore.add("이동 단계 실패 — 직접 쿠폰 페이지로 이동한 뒤 [쿠폰 목록 읽기]를 누르세요")
                    break
                }
                sleep(600)
            }
            showPickBar(job)
            LogStore.add("쿠폰 페이지가 보이면 화면 위 [쿠폰 목록 읽기]를 누르세요")
        } catch (e: InterruptedException) {
            LogStore.add("취소됨")
        } catch (e: Exception) {
            LogStore.add("오류: $e")
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showPickBar(job: Job) {
        main.post {
            try {
                hidePickNow()
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val bar = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(this@MacroService).apply {
                        text = "쿠폰 목록 읽기"
                        setOnClickListener { Thread({ scanCoupons(job) }, "pick-scan").start() }
                    })
                    addView(Button(this@MacroService).apply {
                        text = "✕"
                        setOnClickListener { hidePick() }
                    })
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    y = dp(96)
                }
                wm.addView(bar, lp)
                pickBar = bar
            } catch (e: Exception) {
                LogStore.add("버튼 표시 실패: $e")
            }
        }
    }

    private fun scanCoupons(job: Job) {
        val root = targetRoot(job)
        if (root == null) {
            LogStore.add("대상 앱 화면이 아닙니다 — 쿠폰 페이지를 연 뒤 다시 누르세요")
            return
        }
        val rules = rulesFor(job)
        val buttons = CouponMatcher.findButtons(AxNode(root), rules)
        val items = CouponMatcher.listCards(buttons, rules)
        LogStore.add("받기 버튼 ${buttons.size}개 · 쿠폰 후보 ${items.size}개")
        main.post {
            if (items.isEmpty()) {
                notifyResult("쿠폰 목록", "받기 버튼을 찾지 못했습니다. 쿠폰 페이지인지, 고급 설정의 '받기 버튼 문구'를 확인하세요")
            } else {
                showPickPanel(items)
            }
        }
    }

    private fun showPickPanel(items: List<PickItem>) {
        try {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            val dm = resources.displayMetrics
            val pad = dp(12)
            val checks = items.map { item ->
                CheckBox(this).apply {
                    text = item.lines.joinToString("\n") + "\n→ 저장 문구: ${item.keyword}"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                    setPadding(0, dp(6), 0, dp(6))
                }
            }
            val list = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                checks.forEach { addView(it) }
            }
            val panel = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(0xF2202020.toInt())
                setPadding(pad, pad, pad, pad)
                addView(TextView(this@MacroService).apply {
                    text = "받을 쿠폰을 고르세요 (체크한 것만 받습니다)"
                    textSize = 16f
                    setTextColor(Color.WHITE)
                })
                addView(
                    ScrollView(this@MacroService).apply { addView(list) },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (dm.heightPixels * 0.4).toInt()),
                )
                addView(LinearLayout(this@MacroService).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(Button(this@MacroService).apply {
                        text = "선택 완료"
                        setOnClickListener { finishPick(items, checks) }
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(Button(this@MacroService).apply {
                        text = "취소"
                        setOnClickListener { hidePick() }
                    }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                })
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.BOTTOM }
            pickPanel?.let { runCatching { wm.removeView(it) } }
            wm.addView(panel, lp)
            pickPanel = panel
        } catch (e: Exception) {
            LogStore.add("목록 표시 실패: $e")
        }
    }

    private fun finishPick(items: List<PickItem>, checks: List<CheckBox>) {
        val chosen = items.indices.filter { checks[it].isChecked }.map { items[it].keyword }.distinct()
        hidePickNow()
        if (chosen.isEmpty()) {
            LogStore.add("선택된 쿠폰이 없습니다")
            return
        }
        Store.setPendingCoupons(this, pickProfile, chosen.joinToString("\n"))
        LogStore.add("쿠폰 ${chosen.size}개 선택: ${chosen.joinToString(" | ")}")
        notifyResult("쿠폰 선택 완료", "${chosen.size}개 저장됨 — 탭해서 앱 열기")
        try {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            )
        } catch (e: Exception) {
            LogStore.add("앱 열기 실패(알림을 눌러 여세요): ${e.message}")
        }
    }

    private fun hidePickNow() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        for (v in listOf(pickBar, pickPanel)) {
            if (v != null) runCatching { wm.removeView(v) }
        }
        pickBar = null
        pickPanel = null
    }

    private fun hidePick() {
        main.post { hidePickNow() }
    }

    /** delayMs 뒤 현재 전면 화면의 요소(text/desc/id)를 로그로 덤프. 그 사이 대상 앱으로 전환해 둘 것. */
    fun dumpAfter(delayMs: Long) {
        LogStore.add("${delayMs / 1000}초 뒤 현재 화면 덤프 — 대상 앱 화면으로 전환하세요")
        Thread({
            try {
                Thread.sleep(delayMs)
                dumpScreen()
            } catch (_: InterruptedException) {
            }
        }, "dump").start()
    }

    // ---- 작업 실행 ----
    private fun runJob(job: Job, mode: Mode) {
        acquireWakeLock()
        setKeepScreenOn(true)
        try {
            LogStore.add("== 시작: ${job.name} [${mode.name}] ==")
            val out = execute(job, mode)
            LogStore.add("결과: ${out.status} (시도 ${out.attempts}회) ${out.detail}")
            notifyResult(job.name, label(out.status) + if (out.detail.isNotEmpty()) " — ${out.detail}" else "")
        } catch (e: InterruptedException) {
            LogStore.add("취소됨")
        } catch (e: Exception) {
            LogStore.add("오류: $e")
            notifyResult(job.name, "오류: ${e.message}")
        } finally {
            setKeepScreenOn(false)
            releaseWakeLock()
        }
    }

    private fun execute(job: Job, mode: Mode): Outcome {
        var offset = 0L
        if (mode == Mode.LIVE) {
            val o = Ntp.offsetMs()
            if (o == null) {
                LogStore.add("NTP 실패 → 기기 시계 그대로 사용")
            } else {
                offset = o
                LogStore.add("NTP 오프셋 ${o}ms")
            }
            if (job.openAtMillis - (System.currentTimeMillis() + offset) < -Schedule.GRACE_MS) {
                return Outcome("error", 0, "오픈 시각이 이미 지났습니다")
            }
        }

        if (job.launchApp) {
            if (!launch(job.pkg)) return Outcome("error", 0, "앱 실행 실패: ${job.pkg} (패키지명 확인)")
            LogStore.add("앱 실행: ${job.pkg}")
            sleep(2000)
        } else {
            LogStore.add("앱 실행 생략 — 대상 앱의 쿠폰 화면으로 직접 이동해 두세요")
        }

        for ((i, s) in job.steps.withIndex()) {
            val ok = waitAndClick(job, s.text, 10_000)
            LogStore.add("이동 ${i + 1}/${job.steps.size} '${s.text}' ${if (ok) "OK" else "못 찾음"}")
            if (!ok && !s.optional) return Outcome("error", 0, "이동 단계 실패: ${s.text}")
            sleep(600)
        }

        if (mode == Mode.LIVE) {
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)
            LogStore.add("대기: 오픈 ${fmt.format(Date(job.openAtMillis))} (lead ${job.leadMs}ms)")
            val fireAt = job.openAtMillis - job.leadMs
            // 오래 전에 열어 둔 페이지는 낡았을 수 있어 오픈 3초 전에 한 번 새로고침
            if (job.refreshOnMiss && fireAt - (System.currentTimeMillis() + offset) > 3500) {
                waitUntil(fireAt - 3000, offset)
                if (targetRoot(job) != null) {
                    swipeDown()
                    LogStore.add("오픈 직전 새로고침")
                }
            }
            waitUntil(fireAt, offset)
        }
        return clickLoop(job, mode)
    }

    /**
     * 지정한 쿠폰(job.coupons)마다 '그 쿠폰 카드 안의 받기 버튼'만 누른다.
     * 모든 쿠폰이 성공/이미 받음/소진 중 하나로 정리되면 종료.
     */
    private fun clickLoop(job: Job, mode: Mode): Outcome {
        val rules = rulesFor(job)
        val done = LinkedHashMap<String, String>()   // 쿠폰 문구 -> success|already|soldout|clicked|dry_run
        val clicked = HashSet<String>()
        var misses = 0
        var ambiguousLogged = false
        val maxAttempts = if (mode == Mode.DRY) minOf(job.maxAttempts, 15) else job.maxAttempts
        for (attempt in 1..maxAttempts) {
            checkCancel()
            val root = targetRoot(job)
            var foundAny = false
            if (root != null) {
                for (entry in job.coupons) {
                    if (entry in done) continue
                    val m = CouponMatcher.resolve(entry, { t -> findNodes(root, t) }, rules) ?: continue
                    foundAny = true
                    when {
                        m.button != null -> {
                            if (mode == Mode.DRY) {
                                done[entry] = "dry_run"
                                LogStore.add("[$entry] 카드 발견 → 받기 버튼 확인, 클릭 생략 (카드: ${m.text.take(60)})")
                            } else {
                                val t0 = SystemClock.elapsedRealtime()
                                val ok = clickNode((m.button as AxNode).raw)
                                LogStore.add("#$attempt [$entry] 클릭 ${if (ok) "OK" else "실패"}")
                                if (ok) {
                                    clicked += entry
                                    val st = if (!job.hasResultTexts) "clicked" else detectFor(job, entry, t0, rules)
                                    if (st != null) {
                                        done[entry] = st
                                        LogStore.add("[$entry] → ${label(st)}")
                                    } else {
                                        LogStore.add("#$attempt [$entry] 결과 문구 미확인 → 재시도")
                                    }
                                }
                            }
                        }
                        m.state != null -> {
                            // 클릭 전부터 '받기 완료'로 보이면 이미 받은 쿠폰
                            val st = if (m.state == "success" && entry !in clicked) "already" else m.state
                            done[entry] = st
                            LogStore.add("[$entry] 버튼 없음, 카드 상태: ${label(st)}")
                        }
                        m.ambiguous && !ambiguousLogged -> {
                            ambiguousLogged = true
                            LogStore.add("[$entry] 카드 안에 받기 버튼이 여러 개라 구분 불가 — 쿠폰 문구를 더 구체적으로(+로 연결) 적어주세요")
                        }
                    }
                }
            }
            if (job.coupons.all { it in done }) return summarize(job, done, attempt)
            if (!foundAny) {
                misses++
                if (root == null && misses % 20 == 1) LogStore.add("대상 앱(${job.pkg})이 전면에 없음")
                if (root != null) {
                    if (misses % 3 == 1) dismissPopups(job, root)
                    if (job.refreshOnMiss && misses % 5 == 0) {
                        swipeDown()
                        LogStore.add("#$attempt 쿠폰이 안 보임 → 새로고침")
                    }
                }
            }
            sleep(job.intervalMs)
        }
        val missing = job.coupons.filter { it !in done }
        val partial = if (done.isEmpty()) "" else summarize(job, done, maxAttempts).detail + " · "
        return Outcome("timeout", maxAttempts, partial + "못 찾음/미완료: " + missing.joinToString(", "))
    }

    private fun summarize(job: Job, done: Map<String, String>, attempts: Int): Outcome {
        val parts = listOf("success", "already", "soldout", "clicked", "dry_run").mapNotNull { st ->
            val names = job.coupons.filter { done[it] == st }
            if (names.isEmpty()) null else "${label(st)}: ${names.joinToString(", ")}"
        }
        val values = done.values.toSet()
        val overall = when {
            "success" in values -> "success"
            "soldout" in values && values.all { it == "soldout" || it == "already" } -> "soldout"
            else -> values.firstOrNull() ?: "error"
        }
        return Outcome(overall, attempts, parts.joinToString(" / "))
    }

    /** 클릭 뒤 결과 확인: 토스트 또는 그 쿠폰 카드의 상태 문구. 확인 못 하면 null(재시도). */
    private fun detectFor(job: Job, entry: String, clickedAt: Long, rules: CouponRules): String? {
        val deadline = SystemClock.elapsedRealtime() + job.resultWaitMs
        while (true) {
            val toast = if (lastToastAt >= clickedAt) lastToast else ""
            if (toast.isNotEmpty()) {
                if (job.successTexts.any { toast.contains(it) }) return "success"
                if (job.alreadyTexts.any { toast.contains(it) }) return "already"
                if (job.soldoutTexts.any { toast.contains(it) }) return "soldout"
            }
            val root = targetRoot(job)
            if (root != null) {
                val m = CouponMatcher.resolve(entry, { t -> findNodes(root, t) }, rules)
                if (m?.state != null) return m.state
            }
            if (SystemClock.elapsedRealtime() >= deadline) return null
            sleep(100)
        }
    }

    private fun rulesFor(job: Job) = CouponRules(
        buttonTexts = job.targets,
        successTexts = job.successTexts,
        alreadyTexts = job.alreadyTexts,
        soldoutTexts = job.soldoutTexts,
        maxCardHeight = (resources.displayMetrics.heightPixels * 0.45).toInt(),
    )

    private fun findNodes(root: AccessibilityNodeInfo, text: String): List<UiNode> =
        root.findAccessibilityNodeInfosByText(text).map { AxNode(it) }

    // ---- 화면 조작 ----
    private fun targetRoot(job: Job): AccessibilityNodeInfo? =
        rootInActiveWindow?.takeIf { it.packageName?.toString() == job.pkg }

    private fun find(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        for (t in texts) {
            val hit = root.findAccessibilityNodeInfosByText(t).firstOrNull { it.isVisibleToUser && it.isEnabled }
            if (hit != null) return hit
        }
        return null
    }

    private fun waitAndClick(job: Job, text: String, timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        var n = 0
        while (SystemClock.elapsedRealtime() < end) {
            checkCancel()
            val root = targetRoot(job)
            if (root != null) {
                val node = find(root, listOf(text))
                if (node != null && clickNode(node)) return true
                if (n++ % 4 == 0) dismissPopups(job, root)
            }
            sleep(300)
        }
        return false
    }

    private fun dismissPopups(job: Job, root: AccessibilityNodeInfo) {
        for (t in job.popups) {
            val n = find(root, listOf(t)) ?: continue
            if (clickNode(n)) LogStore.add("팝업 닫음: $t")
        }
    }

    /** 클릭 가능한 노드(자신 또는 조상)에 ACTION_CLICK, 안 되면 중앙 좌표 탭. */
    private fun clickNode(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        var depth = 0
        while (n != null && depth < 6) {
            if (n.isClickable && n.isEnabled && n.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            n = n.parent
            depth++
        }
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return false
        return gesture(r.exactCenterX(), r.exactCenterY(), r.exactCenterX(), r.exactCenterY(), 40)
    }

    private fun swipeDown(): Boolean {
        val dm = resources.displayMetrics
        val x = dm.widthPixels * 0.5f
        return gesture(x, dm.heightPixels * 0.3f, x, dm.heightPixels * 0.75f, 200)
    }

    private fun gesture(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val p = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
            .build()
        return dispatchGesture(g, null, null)
    }

    private fun launch(pkg: String): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        // 항상 앱 첫 화면에서 시작 (경로 기록 때와 같은 출발점)
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        startActivity(i)
        return true
    }

    private fun dumpScreen() {
        val root = rootInActiveWindow
        if (root == null) {
            LogStore.add("덤프: 활성 창 없음")
            return
        }
        LogStore.add("== 덤프: ${root.packageName} ==")
        var count = 0
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            if (count >= 120 || depth > 40) return
            val text = n.text?.toString().orEmpty()
            val desc = n.contentDescription?.toString().orEmpty()
            val id = n.viewIdResourceName.orEmpty()
            if (text.isNotEmpty() || desc.isNotEmpty() || id.isNotEmpty()) {
                count++
                LogStore.add("text='$text' desc='$desc' id='$id' click=${n.isClickable}")
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        LogStore.add("== 덤프 끝 (${count}개) ==")
    }

    // ---- 시간/취소 ----
    private fun waitUntil(targetMs: Long, offset: Long) {
        var lastSec = -1L
        while (true) {
            checkCancel()
            val remain = targetMs - (System.currentTimeMillis() + offset)
            if (remain <= 0) return
            val sec = remain / 1000
            if ((remain <= 10_000 || sec % 30 == 0L) && sec != lastSec) {
                lastSec = sec
                LogStore.add("T-${sec}s")
            }
            // 멀리 있을 땐 크게 쉬고, 마지막 50ms 구간은 1ms 단위
            Thread.sleep(maxOf(1L, minOf(remain - 50, 500L)))
        }
    }

    private fun sleep(ms: Long) {
        checkCancel()
        Thread.sleep(ms)
    }

    private fun checkCancel() {
        if (cancelled) throw InterruptedException("cancelled")
    }

    // ---- 화면 유지 / 웨이크락 / 알림 ----
    private fun setKeepScreenOn(on: Boolean) {
        main.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                if (on && overlay == null) {
                    val v = View(this).apply { keepScreenOn = true }
                    val lp = WindowManager.LayoutParams(
                        1, 1,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT,
                    )
                    wm.addView(v, lp)
                    overlay = v
                } else if (!on) {
                    overlay?.let { wm.removeView(it) }
                    overlay = null
                }
            } catch (e: Exception) {
                LogStore.add("화면 유지 설정 실패: $e")
            }
        }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "couponmacro:job").apply {
            acquire(60 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun label(status: String) = when (status) {
        "success" -> "발급 성공"
        "already" -> "이미 받음"
        "soldout" -> "소진"
        "clicked" -> "클릭 완료"
        "dry_run" -> "탐색 완료(클릭 안 함)"
        "timeout" -> "시간 초과"
        else -> "오류"
    }

    private fun notifyResult(title: String, text: String) {
        try {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "결과 알림", NotificationManager.IMPORTANCE_HIGH))
            val n = Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(
                    PendingIntent.getActivity(
                        this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                .setAutoCancel(true)
                .build()
            nm.notify(1, n)
        } catch (e: Exception) {
            LogStore.add("알림 실패: $e")
        }
    }
}

/** AccessibilityNodeInfo -> UiNode 어댑터 */
class AxNode(val raw: AccessibilityNodeInfo) : UiNode {
    private val rect = Rect().also { raw.getBoundsInScreen(it) }
    override val label: String
        get() = raw.text?.toString()?.takeIf { it.isNotBlank() } ?: raw.contentDescription?.toString().orEmpty()
    override val visible: Boolean get() = raw.isVisibleToUser
    override val enabled: Boolean get() = raw.isEnabled
    override val left: Int get() = rect.left
    override val top: Int get() = rect.top
    override val right: Int get() = rect.right
    override val bottom: Int get() = rect.bottom
    override val parent: UiNode? get() = raw.parent?.let { AxNode(it) }
    override val children: List<UiNode>
        get() = (0 until raw.childCount).mapNotNull { i -> raw.getChild(i)?.let { AxNode(it) } }
}
