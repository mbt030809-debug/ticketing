package app.ticketing.couponmacro

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.GestureDescription
import android.app.ActivityManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Rect
import android.widget.Button
import android.widget.CheckBox
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.view.MotionEvent
import android.widget.FrameLayout
import kotlin.math.hypot
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

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
        private const val MAX_POS_CLICKS = 60
        private val POPUP_WORDS = listOf("닫기", "나중에", "다음에", "건너뛰기", "취소", "오늘 하루 보지 않기", "확인")
    }

    @Volatile private var worker: Thread? = null
    @Volatile private var cancelled = false
    @Volatile private var dots = true
    @Volatile private var baselineProbe: Map<String, Int>? = null   // 위치 탭 결과 판별용: 탭 전부터 보이던 결과 문구 개수
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
        hideRunGuard()
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

    /** @return 시작했으면 true, 이미 실행 중이라 못 했으면 false */
    fun start(job: Job, mode: Mode): Boolean {
        if (isRunning()) {
            LogStore.add("이미 실행 중 — 먼저 취소하세요")
            return false
        }
        cancelled = false
        worker = Thread({ runJob(job, mode) }, "macro").also { it.start() }
        return true
    }

    fun cancel() {
        cancelled = true
        worker?.interrupt()
        hidePick()
        hideRunGuard()
    }

    // ---- 실행 중 화면 잠금(사용자 터치 차단) + 긴급 정지 버튼 ----
    private var guardView: View? = null
    private var guardLp: WindowManager.LayoutParams? = null
    private var stopBtn: Button? = null
    private var stopLp: WindowManager.LayoutParams? = null
    @Volatile private var guardOn = false

    /** 매크로 동작을 보내는 중인 횟수. 0보다 크면 잠금 창이 터치를 통과시킨다(그래야 매크로의 탭/스크롤이 아래 앱에 닿는다) */
    private val passCount = AtomicInteger(0)
    @Volatile private var stopPass = false

    /** 정각 첫 탭을 늦추지 않으려고 미리 열어 둔 상태(첫 동작이 끝나면 닫는다) */
    @Volatile private var guardHeld = false

    /** 통과 설정이 입력 시스템에 닿을 때까지 기다리는 시간. 잠금 창이 매크로 동작을 가로채면 늘린다 */
    @Volatile private var guardSettleMs = 40L

    /** 이 기기에서 잠금 창이 매크로 동작을 계속 가로채 잠금을 포기했는지(이 실행 동안) */
    @Volatile private var guardDropped = false

    /** 이미지 쿠폰 연타 동안 터치 잠금을 풀어 둔 상태(동작마다 창 설정을 바꾸지 않는다) */
    @Volatile private var guardOpenAll = false

    /** 지금 보내는 중인 매크로 동작(시작점/끝점/유효 시각) — 잠금 창이나 긴급 정지 버튼에 이 동작이 닿았는지 알아보는 용도 */
    private class MacroTouch(val x1: Float, val y1: Float, val x2: Float, val y2: Float, val untilMs: Long)

    @Volatile private var macroTouch: MacroTouch? = null
    @Volatile private var guardLeaked = false

    private fun noteGuardTouch(x: Float, y: Float) {
        val mt = macroTouch ?: return
        if (SystemClock.elapsedRealtime() <= mt.untilMs && hypot(x - mt.x1, y - mt.y1) <= 60f) guardLeaked = true
    }

    private fun guardFlags(pass: Boolean) =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            (if (pass) WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else 0)

    /**
     * 실행이 시작되면(앱을 연 때부터) 화면 전체를 투명 창으로 덮어 사용자의 터치를 막고, 오른쪽 위에 [■ 긴급 정지] 버튼만 눌리게 둔다.
     * 투명 창이라 화면 모습은 그대로(기준 화면 비교에 영향 없음). 끝나거나 정지하면 자동으로 사라진다.
     */
    private fun showRunGuard() {
        if (guardOn) return
        guardOn = true
        passCount.set(0)
        stopPass = false
        guardHeld = false
        guardDropped = false
        guardOpenAll = false
        main.post {
            if (!guardOn || guardView != null) return@post
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val v = View(this)
                v.setOnTouchListener { _, ev ->
                    // 모든 터치를 먹는다. 단, 매크로가 보낸 동작이 여기 닿았다면(통과 설정이 늦음) 기록해 두었다가 다시 보낸다
                    if (ev.actionMasked == MotionEvent.ACTION_DOWN) noteGuardTouch(ev.rawX, ev.rawY)
                    true
                }
                val glp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    guardFlags(false),
                    PixelFormat.TRANSLUCENT,
                )
                wm.addView(v, glp)
                guardView = v
                guardLp = glp

                val b = Button(this).apply {
                    text = "■ 긴급 정지"
                    setTextColor(Color.WHITE)
                    textSize = 15f
                    background = GradientDrawable().apply {
                        setColor(0xE6D32F2F.toInt())
                        cornerRadius = dp(10).toFloat()
                    }
                    setPadding(dp(14), 0, dp(14), 0)
                    setOnClickListener {
                        // 매크로가 보낸 탭이 이 버튼에 닿은 거라면(사용자가 누른 게 아님) 무시하고 그 동작을 다시 보내게 한다
                        val mt = macroTouch
                        if (mt != null && SystemClock.elapsedRealtime() <= mt.untilMs) {
                            val dm = resources.displayMetrics
                            if (RunGuard.stopZone(dm.widthPixels, dm.heightPixels, dm.density).touches(mt.x1, mt.y1, mt.x2, mt.y2, 16 * dm.density)) {
                                guardLeaked = true
                                LogStore.add("매크로 동작이 긴급 정지 버튼에 닿아 무시했습니다")
                                return@setOnClickListener
                            }
                        }
                        LogStore.add("긴급 정지 버튼 — 매크로를 멈춥니다")
                        cancel()
                        notifyResult("긴급 정지", "매크로를 멈췄습니다 (예약이 켜져 있으면 다음 회차는 계속됩니다 — 끄려면 앱에서 [예약 중지])")
                    }
                }
                val blp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    dp(RunGuard.BUTTON_HEIGHT_DP),
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    guardFlags(false) and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN.inv(),
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    y = dp(RunGuard.BUTTON_TOP_DP)
                    x = dp(8)
                }
                wm.addView(b, blp)
                stopBtn = b
                stopLp = blp
                LogStore.add("실행 중 화면 터치 잠금 — 오른쪽 위 [■ 긴급 정지]로 멈출 수 있습니다")
            } catch (e: Exception) {
                LogStore.add("화면 잠금 표시 실패: $e")
            }
        }
    }

    /** 잠금 창만 없앤다(긴급 정지 버튼은 유지): 잠금 창이 매크로 동작을 계속 가로채는 기기에서 매크로를 우선하기 위해 */
    private fun dropGuardWindow() {
        guardDropped = true
        passCount.set(0)
        stopPass = false
        main.post {
            try {
                val v = guardView
                if (v != null) (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
            } catch (_: Exception) {
            }
            guardView = null
            guardLp = null
        }
    }

    private fun hideRunGuard() {
        guardOn = false
        guardHeld = false
        guardDropped = false
        guardOpenAll = false
        macroTouch = null
        passCount.set(0)
        stopPass = false
        main.post {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            for (v in listOf(stopBtn, guardView)) {
                try {
                    if (v != null) wm.removeView(v)
                } catch (_: Exception) {
                }
            }
            stopBtn = null
            stopLp = null
            guardView = null
            guardLp = null
        }
    }

    /** (메인 스레드) 동작을 보내는 중이면 잠금 창(과 필요하면 긴급 정지 버튼)이 터치를 통과시키게 설정 */
    private fun applyGuardFlags() {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val pass = passCount.get() > 0
        val gv = guardView
        val glp = guardLp
        if (gv != null && glp != null) {
            glp.flags = guardFlags(pass)
            try {
                wm.updateViewLayout(gv, glp)
            } catch (_: Exception) {
            }
        }
        val sv = stopBtn
        val slp = stopLp
        if (sv != null && slp != null) {
            slp.flags = guardFlags(pass && stopPass) and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN.inv()
            try {
                wm.updateViewLayout(sv, slp)
            } catch (_: Exception) {
            }
        }
    }

    /** 동작을 보내기 전: 잠금 창이 그 동작을 가로채지 않도록 터치 통과로 바꾸고 반영될 때까지 잠깐 기다린다 */
    private fun acquireGuardPass(hitsStop: Boolean) {
        if (!guardOn || guardDropped) return
        passCount.incrementAndGet()
        if (hitsStop) stopPass = true
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyGuardFlags()
            return
        }
        val applied = CountDownLatch(1)
        main.post {
            try {
                applyGuardFlags()
            } finally {
                applied.countDown()
            }
        }
        applied.await(150, TimeUnit.MILLISECONDS)
        Thread.sleep(guardSettleMs) // 창 변경이 입력 시스템에 닿을 시간(기기마다 달라 가로채임이 감지되면 늘린다)
    }

    private fun releaseGuardPass() {
        if (!guardOn || guardDropped) return
        if (passCount.decrementAndGet() <= 0) {
            passCount.set(0)
            stopPass = false
        }
        main.post { applyGuardFlags() }
    }

    /** 정각 첫 동작 직전에 미리 통과 상태로 열어 둔다(정각에 창 설정을 바꾸느라 첫 탭이 늦어지지 않도록) */
    private fun holdGuardOpen(first: Step?) {
        if (!guardOn || guardDropped || guardHeld) return
        val dm = resources.displayMetrics
        val fx = first?.hintX
        val fy = first?.hintY
        val hits = fx != null && fy != null &&
            RunGuard.stopZone(dm.widthPixels, dm.heightPixels, dm.density)
                .touches(fx * dm.widthPixels, fy * dm.heightPixels, (first?.endX ?: fx) * dm.widthPixels, (first?.endY ?: fy) * dm.heightPixels, 8 * dm.density)
        guardHeld = true
        acquireGuardPass(hits)
    }

    private fun releaseHeldGuard() {
        if (!guardHeld) return
        guardHeld = false
        releaseGuardPass()
    }

    // ---- 경로 기록: 사용자가 직접 눌러 이동하는 순서를 '이동 단계'로 저장 ----
    @Volatile private var recordPkg: String? = null
    private var recordProfile = ""
    private class Rec(val g: RecordedGesture, val gapMs: Long, val popup: String?, val ref: String?)

    /** 탭 직전 화면 캡처를 비동기로 받아 두는 곳 */
    private class RefShot {
        val latch = CountDownLatch(1)
        @Volatile var grid: IntArray? = null
    }

    private val recorded = ArrayList<Rec>()
    @Volatile private var stopping = false
    private var curShot: RefShot? = null
    private var recordOverlay: View? = null
    private var recordButton: Button? = null
    private var lastRecordAt = 0L
    private var proxyView: View? = null
    private var proxyLp: WindowManager.LayoutParams? = null
    private val proxyPoints = ArrayList<TouchPoint>()
    private val recordExecutor = Executors.newSingleThreadExecutor() // 기록한 동작을 순서대로 처리
    private val refExecutor = Executors.newSingleThreadExecutor()    // 탭 직전 화면 캡처(기록)
    private val shotCbExecutor = Executors.newSingleThreadExecutor() // 시스템 캡처 콜백 전용

    fun isRecording(): Boolean = recordPkg != null

    /** 대상 앱을 첫 화면으로 열고 기록 시작. 화면 위에 [■ 기록 끝] 버튼이 뜬다. */
    fun startRecording(pkg: String, profileId: String, cold: Boolean = true): Boolean {
        if (isRunning() || isRecording()) return false
        if (packageManager.getLaunchIntentForPackage(pkg) == null) return false
        synchronized(recorded) { recorded.clear() }
        stopping = false
        recordProfile = profileId
        recordPkg = pkg // 앱을 새로 시작하는 동안에도 '기록 중'으로 두어 다른 작업이 끼어들지 않게
        Thread({
            try {
                // 재생할 때와 똑같이 앱을 시작한다(앱 종료 후 처음부터) -> 기록할 때와 재생할 때 환경이 같다
                val ok = if (cold) coldStart(pkg) else launch(pkg)
                if (!ok) throw IllegalStateException("앱 실행 실패")
                lastRecordAt = SystemClock.elapsedRealtime()
                showRecordOverlay()
                LogStore.add("경로 기록 시작(${if (cold) "앱을 새로 시작함" else "앱 실행"}): 앱이 다 뜬 뒤 쿠폰 페이지까지 평소처럼 눌러 이동하고 [■ 기록 끝]")
            } catch (e: Exception) {
                recordPkg = null
                LogStore.add("경로 기록을 시작하지 못했습니다: $e")
                notifyResult("경로 기록", "시작하지 못했습니다: ${e.message}")
            }
        }, "record-start").start()
        return true
    }

    /** [■ 기록 끝]: 마지막 동작까지 처리한 뒤(순서대로) 끝 화면을 저장하고 기록을 마친다 */
    fun stopRecording() {
        if (recordPkg == null || stopping) return
        stopping = true
        main.post {
            recordButton?.text = "저장 중…"
            recordButton?.isEnabled = false
        }
        recordExecutor.execute { finishRecording() }
    }

    private fun finishRecording() {
        // 기록을 끝낸 순간의 화면(= 쿠폰을 고를 화면)도 기준으로 저장: 재생 때 이 화면이 뜬 뒤에 쿠폰을 고른다
        // (캡처가 예외로 실패해도 기록 화면이 남아 터치를 계속 막는 일이 없도록 반드시 정리한다)
        val end = try {
            captureGrid()?.takeIf { ScreenRef.informative(it) }?.let { ScreenRef.encode(it) }
        } catch (e: Exception) {
            LogStore.add("끝 화면 캡처 실패: $e")
            null
        }
        recordPkg = null
        hideRecordOverlay()
        val dm = resources.displayMetrics
        val recs = synchronized(recorded) { recorded.toList() }
        val lines = recs.mapIndexed { i, r ->
            // 한 줄 = 눌렀던 위치(+간격 +직전 화면). 팝업 닫기 류는 기록 때 이미 '?글자' 로 저장됨
            RouteSteps.line(r.g, dm.widthPixels, dm.heightPixels, r.gapMs, r.popup, r.ref, if (i == recs.lastIndex) end else null)
        }
        if (lines.isNotEmpty()) Store.setPendingSteps(this, recordProfile, lines.joinToString("\n"))
        val refs = recs.count { it.ref != null }
        LogStore.add("경로 기록 끝: ${lines.size}단계 · 기준 화면 ${refs}/${lines.size}" + if (end != null) " + 끝 화면" else "")
        if (lines.isNotEmpty() && (refs < lines.size || end == null)) {
            LogStore.add(
                if (Build.VERSION.SDK_INT < 30) "기준 화면은 안드로이드 11 이상에서만 저장됩니다 — 이 기기에서는 시간 기준으로 진행합니다"
                else "일부 화면은 기준으로 저장하지 못했습니다(캡처 실패 코드 $lastShotFail / 한 색 화면 / 보안 화면) — 그 단계는 시간 기준으로 진행합니다",
            )
        }
        notifyResult(
            "경로 기록 완료",
            if (lines.isEmpty()) "기록된 단계가 없습니다 (화면을 한 번도 누르지 않음)" else "${lines.size}단계 저장됨 (기준 화면 ${refs}개) — 탭해서 앱 열기",
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

    /**
     * 사용자의 터치 한 동작(탭/스와이프)을 기록하고, 같은 동작을 아래 앱에 대신 전달한다.
     * (기록 중에는 화면 전체를 이 앱의 투명 창이 먼저 받는다 — 어떤 화면이든(웹/직접 그린 화면 포함) 터치 위치로 기록된다)
     */
    private fun onRecordedGesture(g: RecordedGesture, shot: RefShot?) {
        if (recordPkg == null) return
        val dm = resources.displayMetrics
        val now = SystemClock.elapsedRealtime()
        val gap = now - lastRecordAt
        lastRecordAt = now
        // 팝업 닫기 류인지: 탭이 팝업을 닫아 버리기 전에 확인
        val popup = if (g.kind == GestureKind.TAP) popupLabelAt(g.x1, g.y1) else null
        // 탭 직전 화면(누르기 시작할 때 캡처해 둔 것) — 재생 때 '원하는 화면이 뜰 때까지' 기다리는 기준
        val grid = if (shot != null && shot.latch.await(3, TimeUnit.SECONDS)) shot.grid else null
        val ref = grid?.takeIf { ScreenRef.informative(it) }?.let { ScreenRef.encode(it) }
        val n = synchronized(recorded) {
            recorded += Rec(g, gap, popup, ref)
            recorded.size
        }
        showDot(g.x1, g.y1, Color.BLUE)
        val what = if (g.kind == GestureKind.SWIPE) {
            "스와이프 ${(g.x1 * 100 / dm.widthPixels).toInt()}%,${(g.y1 * 100 / dm.heightPixels).toInt()}% → ${(g.x2 * 100 / dm.widthPixels).toInt()}%,${(g.y2 * 100 / dm.heightPixels).toInt()}%"
        } else {
            "탭 ${(g.x1 * 100 / dm.widthPixels).toInt()}%,${(g.y1 * 100 / dm.heightPixels).toInt()}%" + if (g.durMs >= Gestures.LONG_PRESS_MS) " (길게 ${g.durMs}ms)" else ""
        }
        LogStore.add(
            "기록 $n: $what · 이전 동작 후 ${gap / 100 / 10.0}초" + (if (popup != null) " · 팝업 '$popup'(없어도 진행)" else "") +
                (if (ref != null) " · 기준 화면 저장" else " · 기준 화면 없음"),
        )
        main.post { recordButton?.text = "■ 기록 끝 (${n}단계)" }

        // 같은 동작을 아래 앱에 전달하는 동안만 투명 창이 터치를 통과시킨다 (무슨 일이 있어도 끝나면 다시 터치를 받도록 복구)
        setProxyTouchable(false)
        try {
            val done = CountDownLatch(1)
            val ok = gesture(
                g.x1, g.y1, g.x2, g.y2, g.durMs,
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) = done.countDown()
                    override fun onCancelled(gestureDescription: GestureDescription?) = done.countDown()
                },
            )
            if (ok) done.await(g.durMs + 1_500, TimeUnit.MILLISECONDS) else LogStore.add("기록: 동작을 앱에 전달하지 못했습니다")
            Thread.sleep(60)
        } finally {
            setProxyTouchable(true)
        }
    }

    /** (x, y) 위치에 있는 팝업 닫기 류 버튼의 글자. 아니면 null */
    private fun popupLabelAt(x: Float, y: Float): String? {
        val root = rootInActiveWindow
            ?: windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }?.root
            ?: return null
        val r = Rect()
        for (w in POPUP_WORDS) {
            for (n in root.findAccessibilityNodeInfosByText(w)) {
                n.getBoundsInScreen(r)
                if (r.contains(x.toInt(), y.toInt())) return w
            }
        }
        return null
    }

    /** 투명 창의 터치 가로채기를 켜고 끈다. 반영될 때까지 기다린다(꺼지기 전에 동작을 보내면 자기 창이 받아 버림). */
    private fun setProxyTouchable(on: Boolean) {
        val applied = CountDownLatch(1)
        main.post {
            try {
                val v = proxyView
                val lp = proxyLp
                if (v != null && lp != null) {
                    val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                    lp.flags = if (on) base else base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    (getSystemService(WINDOW_SERVICE) as WindowManager).updateViewLayout(v, lp)
                }
            } catch (_: Exception) {
            } finally {
                applied.countDown()
            }
        }
        applied.await(500, TimeUnit.MILLISECONDS)
        if (!on) Thread.sleep(30) // 창 변경이 입력 시스템에 닿을 시간
    }

    private fun showRecordOverlay() {
        main.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val dm = resources.displayMetrics
                val slop = 16 * dm.density
                // 1) 터치를 먼저 받는 투명 창: 기록하고, 같은 동작을 아래 앱에 대신 전달
                val proxy = View(this)
                proxy.setOnTouchListener { _, ev ->
                    when (ev.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            proxyPoints.clear()
                            proxyPoints += TouchPoint(ev.rawX, ev.rawY, ev.eventTime)
                            // 누르는 순간의 화면을 기준으로 저장(안드로이드 11+). 이 창이 터치를 받는 동안엔 아래 화면이 그대로다
                            val shot = RefShot()
                            curShot = shot
                            if (Build.VERSION.SDK_INT >= 30) {
                                refExecutor.execute {
                                    try {
                                        shot.grid = captureGrid()
                                    } finally {
                                        shot.latch.countDown()
                                    }
                                }
                            } else {
                                shot.latch.countDown()
                            }
                        }
                        MotionEvent.ACTION_MOVE -> if (ev.pointerCount == 1) proxyPoints += TouchPoint(ev.rawX, ev.rawY, ev.eventTime)
                        MotionEvent.ACTION_UP -> {
                            proxyPoints += TouchPoint(ev.rawX, ev.rawY, ev.eventTime)
                            val g = Gestures.classify(proxyPoints.toList(), slop)
                            proxyPoints.clear()
                            val shot = curShot
                            curShot = null
                            if (g != null) recordExecutor.execute { onRecordedGesture(g, shot) }
                        }
                        MotionEvent.ACTION_CANCEL -> proxyPoints.clear()
                    }
                    true
                }
                val plp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                    PixelFormat.TRANSLUCENT,
                )
                wm.addView(proxy, plp)
                proxyView = proxy
                proxyLp = plp

                // 2) [■ 기록 끝] 버튼 (투명 창 위)
                val b = Button(this).apply {
                    text = "■ 기록 끝 (0단계)"
                    setOnClickListener { stopRecording() }
                }
                recordButton = b
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.END
                    y = (96 * dm.density).toInt()
                }
                wm.addView(b, lp)
                recordOverlay = b
            } catch (e: Exception) {
                LogStore.add("기록 화면 표시 실패: $e")
            }
        }
    }

    private fun hideRecordOverlay() {
        main.post {
            val wm = getSystemService(WINDOW_SERVICE) as WindowManager
            for (v in listOf(recordOverlay, proxyView)) {
                try {
                    if (v != null) wm.removeView(v)
                } catch (_: Exception) {
                }
            }
            recordOverlay = null
            proxyView = null
            proxyLp = null
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
        dots = job.showDots
        try {
            if (job.launchApp) {
                val fresh = job.steps.isNotEmpty() // 경로를 따라갈 때만 첫 화면에서 시작
                if (!startTarget(job, fresh)) {
                    LogStore.add("앱 실행 실패: ${job.pkg}")
                    return
                }
                LogStore.add(if (fresh) "쿠폰 고르기: 앱 실행(첫 화면에서 시작)" else "쿠폰 고르기: 앱을 앞으로 가져옴(현재 화면 유지)")
                sleep(2000)
            }
            if (job.steps.isNotEmpty() && replayRoute(job, waitLoaded = false) != null) {
                LogStore.add("이동 단계 실패 — 직접 쿠폰 페이지로 이동한 뒤 [쿠폰 목록 읽기]를 누르세요")
            }
            waitForLoaded(job, "이동 완료")
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
                    orientation = LinearLayout.VERTICAL
                    addView(Button(this@MacroService).apply {
                        text = "쿠폰 목록 읽기 (글자 있는 쿠폰)"
                        setOnClickListener { Thread({ scanCoupons(job) }, "pick-scan").start() }
                    })
                    addView(Button(this@MacroService).apply {
                        text = "위치로 찍기 (이미지 쿠폰)"
                        setOnClickListener { showCapture(job) }
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
        try {
            waitForLoaded(job, "목록 읽기", maxMs = 10_000)
        } catch (_: InterruptedException) {
            return
        }
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

    // ---- 위치로 찍기: 글자 없는(이미지) 쿠폰 카드를 화면에서 직접 터치해 지정 ----
    @Volatile private var capturePanel: View? = null
    private val capturePoints = ArrayList<PosTarget>()

    private fun showCapture(job: Job) {
        main.post {
            try {
                hidePickNow()
                synchronized(capturePoints) { capturePoints.clear() }
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val info = TextView(this).apply {
                    text = "받을 쿠폰 카드를 터치하세요 (0개)"
                    textSize = 15f
                    setTextColor(Color.WHITE)
                    setPadding(dp(12), dp(12), dp(12), dp(12))
                }
                val bar = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundColor(0xE6202020.toInt())
                    addView(info, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    addView(Button(this@MacroService).apply {
                        text = "완료"
                        setOnClickListener { finishCapture() }
                    })
                    addView(Button(this@MacroService).apply {
                        text = "취소"
                        setOnClickListener { hidePick() }
                    })
                }
                val guardPx = dp(96) // 위쪽 안내 바 영역의 터치는 지정으로 보지 않음
                val frame = FrameLayout(this).apply {
                    setBackgroundColor(0x33000000)
                    setOnTouchListener { _, ev ->
                        if (ev.action == MotionEvent.ACTION_UP && ev.rawY > guardPx) {
                            val x = ev.rawX
                            val y = ev.rawY
                            Thread({ onCapture(job, x, y, info) }, "capture").start()
                        }
                        true
                    }
                    addView(bar, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP))
                }
                val lp = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                )
                wm.addView(frame, lp)
                capturePanel = frame
                LogStore.add("위치 지정: 받을 쿠폰 카드를 터치하고, 끝나면 [완료]")
            } catch (e: Exception) {
                LogStore.add("위치 지정 화면 표시 실패: $e")
            }
        }
    }

    /** 터치한 위치 바로 위의 유일한 문구를 기준점으로 삼아 '기준 문구 + 상대 위치'로 저장 */
    private fun onCapture(job: Job, x: Float, y: Float, info: TextView) {
        val dm = resources.displayMetrics
        val root = targetRoot(job)
        val anchor = root?.let { CouponMatcher.findAnchor(AxNode(it), y.toInt(), rulesFor(job), dp(320)) }
        val fx = x / dm.widthPixels
        val fy = y / dm.heightPixels
        val pt = if (anchor != null) {
            PosTarget(
                anchor.label.trim(),
                ((x - anchor.left) / dm.density).roundToInt(),
                ((y - anchor.top) / dm.density).roundToInt(),
                fx, fy,
            )
        } else {
            PosTarget("", 0, 0, fx, fy) // 기준으로 삼을 글자가 없으면 화면 비율 위치
        }
        val n = synchronized(capturePoints) {
            capturePoints += pt
            capturePoints.size
        }
        LogStore.add("위치 $n: ${pt.describe()}")
        showDot(x, y, Color.GREEN)
        main.post { info.text = "받을 쿠폰 카드를 터치하세요 (${n}개 선택됨)" }
    }

    private fun finishCapture() {
        val entries = synchronized(capturePoints) { capturePoints.map { it.toEntry() }.distinct() }
        hidePickNow()
        if (entries.isEmpty()) {
            LogStore.add("지정된 위치가 없습니다")
            return
        }
        Store.setPendingCoupons(this, pickProfile, entries.joinToString("\n"), append = true)
        LogStore.add("위치 ${entries.size}개 저장")
        notifyResult("위치 지정 완료", "${entries.size}개 저장됨 — 탭해서 앱 열기")
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
        for (v in listOf(pickBar, pickPanel, capturePanel)) {
            if (v != null) runCatching { wm.removeView(v) }
        }
        pickBar = null
        pickPanel = null
        capturePanel = null
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
    /** 지금 실행 중인 예약 작업의 오픈 시각(epoch ms). 없으면 0 — 자동 실행 알람이 같은 회차를 중복 시작하지 않도록 */
    @Volatile private var runningOpenAtMs = 0L

    fun runningOpenAt(): Long = if (isRunning()) runningOpenAtMs else 0L

    private fun runJob(job: Job, mode: Mode) {
        runningOpenAtMs = if (mode == Mode.LIVE) job.openAtMillis else 0L
        screenMatchedAt = 0L
        captureWarned = false
        timeline = null
        runOffset = 0L
        dots = job.showDots
        acquireWakeLock(Flow.wakeLockMs(job, mode, System.currentTimeMillis()))
        setKeepScreenOn(true)
        try {
            LogStore.add("== 시작: ${job.name} [${mode.name}] ==")
            val out = execute(job, mode)
            tlMark("결과")
            timeline?.let {
                LogStore.add("⏱ 타임라인(정각=0): ${it.summary()}")
                LogStore.add("⏱ ${it.stats()}")
            }
            LogStore.add("결과: ${out.status} (시도 ${out.attempts}회) ${out.detail}")
            notifyResult(job.name, label(out.status) + if (out.detail.isNotEmpty()) " — ${out.detail}" else "")
        } catch (e: InterruptedException) {
            LogStore.add("취소됨")
        } catch (e: Exception) {
            LogStore.add("오류: $e")
            notifyResult(job.name, "오류: ${e.message}")
        } finally {
            runningOpenAtMs = 0L
            hideRunGuard()
            setKeepScreenOn(false)
            releaseWakeLock()
        }
    }

    /**
     * 기록한 이동 경로를 순서대로 따라간다. 실패하면 오류 결과, 성공이면 null.
     * 각 단계는 '원하는 화면이 나왔을 때' 누른다:
     *  - 기록 때 저장한 기준 화면(◎)이 있으면, 지금 화면이 그 화면과 같아 보일 때까지 기다린 뒤 누른다 (awaitScreen).
     *    글자가 없는 이미지 화면이나, 로딩 뒤 저절로 스크롤되는 화면에서도 스크롤이 끝나 그 화면이 된 다음에 누른다.
     *  - 기준 화면이 없거나 앞 단계와 같은 화면(탭해도 안 바뀌는 곳)이면 시간 기준(settleAfterStep):
     *    사람이 기다렸던 시간은 항상 기다리고, 그 사이 화면이 바뀌기 시작했다면 로딩이 끝나 잠잠해질 때까지 더 기다린다.
     * waitLoaded: (예전 방식) 마지막 이동 뒤 화면 로딩 완료까지 추가 대기
     * firstScreenChecked: 첫 단계의 기준 화면은 이미 기다렸음(예약 흐름에서 앱이 뜨는 동안 미리 확인)
     */
    private fun replayRoute(
        job: Job, waitLoaded: Boolean, initial: PageSig? = null, firstScreenChecked: Boolean = false,
        route: RoutePlan = prepareRoute(job), fastFirst: Boolean = false, fire: Pair<Long, Long>? = null,
        refsUsable: Boolean = true,
    ): Outcome? {
        var cur: PageSig? = initial // 없으면 첫 시간 기준 대기 때 처음 읽은 화면을 기준으로 삼는다(첫 탭 전에 화면 전체를 읽느라 늦추지 않음)
        val n = job.steps.size
        var useRefs = route.useRefs && refsUsable // 정각 전 확인에서 캡처가 안 되는 걸 알았으면 정각에 또 시도하며 늦지 않도록 처음부터 시간 기준
        // 선착순은 렉으로 화면이 늦게 뜰 수 있다: 화면이 전환돼 원하는 화면이 뜰 때까지 충분히(최대 1분) 기다린 뒤에 누른다
        val maxWait = ScreenRef.MAX_WAIT_MS
        for ((i, s) in job.steps.withIndex()) {
            if (useRefs && !(i == 0 && firstScreenChecked) && route.waits[i]) {
                val ref = route.grids[i]
                val v = if (ref == null) null else awaitScreen(ref, s.hintX, s.hintY, "이동 ${i + 1}/$n", maxWait, route.grids.getOrNull(i - 1))
                if (v == null) {
                    useRefs = false // 화면 캡처가 안 되는 기기/상태 — 이 실행은 시간 기준으로
                    cur = settleAfterStep(job, null, Settle.minWaitMs(s.delayMs, false), "이동 ${i + 1}/$n")
                } else if (v == ScreenVerdict.MATCH) {
                    holdAfterScreen(job.afterScreenMs) // 화면이 뜬 뒤 0.5초(설정) 지나서 누른다
                }
            } else if (useRefs && i == 0 && firstScreenChecked && route.waits[0]) {
                // 앱 첫 화면은 정각 전에 미리 확인했다. '화면이 뜬 뒤 0.5초' 규칙 때문에 정각을 넘겨 늦게 누르지 않는다(앱은 10초 전부터 열려 있음)
                val fireElapsed = fire?.let { SystemClock.elapsedRealtime() + (it.first - (System.currentTimeMillis() + it.second)) }
                holdAfterScreen(job.afterScreenMs, if (fireElapsed != null && screenMatchedAt <= fireElapsed) fireElapsed else null)
            }
            // 첫 탭: 정각에 늦지 않도록 확인 없이 바로 누른다(앞에서 미리 확인해 둠). 기록은 누른 뒤에 남긴다
            val ok = runStep(job, s, if (s.optional) 1_500 else 10_000, assumeForeground = i == 0 && fastFirst)
            if (i == 0) {
                releaseHeldGuard() // 정각 첫 동작을 위해 미리 열어 둔 터치 통과 상태를 닫는다
                if (fire != null) LogStore.add("첫 동작이 나간 시각: 목표(정각-lead) 대비 ${lastDispatchAtMs + fire.second - fire.first}ms")
            }
            if (ok) tlMark("이동${i + 1}", if (s.isTap || s.isSwipe) lastDispatchAtMs else System.currentTimeMillis())
            LogStore.add("이동 ${i + 1}/$n '${s.display()}' ${if (ok) "OK" else "못 찾음"}")
            if (!ok) {
                if (!s.optional) return Outcome("error", 0, "이동 단계 실패: ${s.display()}")
                continue // 없어도 되는 단계(팝업 닫기 등)가 안 보이면 화면이 안 바뀌었으니 기다릴 것 없이 다음으로
            }
            val last = i == n - 1
            if (s.isSwipe) {
                // 스크롤 직후에는 화면 확인·안정 대기 없이 바로 다음 동작 (설정으로 조금 기다리게 할 수 있음)
                if (job.afterScrollMs > 0) sleep(job.afterScrollMs)
                cur = null
                continue
            }
            if (useRefs && !last && route.waits[i + 1]) {
                cur = null // 다음 단계가 '원하는 화면'이 뜰 때까지 기다린다
                continue
            }
            if (useRefs && last) {
                val end = route.end
                if (end != null) {
                    if (awaitScreen(end, null, null, "쿠폰 화면", maxWait, route.grids.getOrNull(n - 1)) != null) {
                        holdAfterScreen(job.afterScreenMs) // 쿠폰 화면이 뜬 뒤 0.5초(설정) 지나서 쿠폰 선택 시작
                        continue
                    }
                    useRefs = false
                }
            }
            cur = settleAfterStep(job, cur, Settle.minWaitMs(job.steps.getOrNull(i + 1)?.delayMs, last), "이동 ${i + 1}/$n")
        }
        if (waitLoaded && n > 0) waitForLoaded(job, "이동 완료") // 예전 방식: 쿠폰 선택은 화면 로딩이 끝난 뒤에
        return null
    }

    /** 경로 재생에 필요한 계산(기준 화면 해석, 기다릴 단계)을 미리 해 둔 것 — 정각에 무거운 계산을 하지 않도록 정각 전에 만든다 */
    private class RoutePlan(val steps: List<Step>) {
        val grids: List<IntArray?> = steps.map { ScreenRef.gridOf(it.ref) }
        val waits: List<Boolean> = steps.indices.map { RouteScreens.waitsBefore(steps, it) }
        val end: IntArray? = RouteScreens.endGrid(steps)
        val hasRefs: Boolean = steps.any { it.ref != null || it.endRef != null }
        val useRefs: Boolean = hasRefs && Build.VERSION.SDK_INT >= 30
    }

    private fun prepareRoute(job: Job): RoutePlan {
        val r = RoutePlan(job.steps)
        if (r.hasRefs && !r.useRefs) LogStore.add("기준 화면은 안드로이드 11 이상에서만 사용합니다 — 시간 기준으로 진행")
        if (r.useRefs && captureCapability() == false) {
            LogStore.add("⚠ 접근성 서비스에 '화면 캡처' 권한이 없습니다 — 접근성 설정에서 '쿠폰 매크로'를 껐다가 다시 켜세요 (업데이트 후 필요할 수 있음). 지금은 시간 기준으로 진행합니다")
        }
        if (r.useRefs) {
            LogStore.add("기준 화면 사용: 원하는 화면이 뜰 때 누릅니다 (대기 ${r.waits.count { it }}단계${if (r.end != null) " + 쿠폰 화면" else ""})")
        }
        return r
    }

    /**
     * 지금 화면이 기록 때 화면(ref)과 같아 보일 때까지 기다린다 (ScreenWait 규칙: 연속 2회 일치, 최대 9초).
     * @return MATCH: 화면이 나타남 / TIMEOUT: 시간이 다 돼 그대로 진행 / null: 화면 캡처를 할 수 없음(시간 기준으로 전환 필요)
     */
    private fun awaitScreen(
        ref: IntArray, fx: Float?, fy: Float?, why: String, maxWaitMs: Long = ScreenRef.MAX_WAIT_MS, prev: IntArray? = null,
        stallDeadlineElapsed: Long? = null, canGiveUp: (() -> Boolean)? = null,
    ): ScreenVerdict? {
        val start = SystemClock.elapsedRealtime()
        val w = ScreenWait(ref, fx, fy, start, maxWaitMs, prev)
        var fails = 0
        var lastLog = start
        fun pct(v: Float?) = "${((v ?: 0f) * 100).toInt()}%"
        while (true) {
            checkCancel()
            val cur = captureGrid()
            val now = SystemClock.elapsedRealtime()
            if (cur == null) {
                if (++fails >= 3) {
                    LogStore.add("화면 캡처를 못 해 시간 기준으로 진행($why, 코드 $lastShotFail)")
                    return null
                }
            } else {
                fails = 0
            }
            val sc = w.last
            val verdict = w.update(cur, now)
            // 정각(마감)까지 기록 화면과 안 맞았는데 화면이 멈춰 있으면(로딩 중이 아님) 더 기다려도 소용없다 -> 정각에 그대로 진행
            if (verdict == ScreenVerdict.WAIT && stallDeadlineElapsed != null && now >= stallDeadlineElapsed &&
                w.stillForMs(now) >= ScreenRef.STILL_MS && (canGiveUp?.invoke() ?: true)
            ) {
                val s = w.last
                LogStore.add("첫 화면이 기록과 달라(전체 ${pct(s?.global)} 주변 ${pct(s?.region)}) 확인은 못 했지만 화면이 멈춰 있어 정각에 그대로 누릅니다($why)")
                return ScreenVerdict.STALLED
            }
            when (verdict) {
                ScreenVerdict.MATCH -> {
                    val s = w.last
                    LogStore.add("원하는 화면 확인($why): ${now - start}ms 대기 · 전체 ${pct(s?.global)} 누를 곳 주변 ${pct(s?.region)}")
                    screenMatchedAt = now
                    return ScreenVerdict.MATCH
                }
                ScreenVerdict.TIMEOUT -> {
                    val s = w.last
                    LogStore.add("원하는 화면이 ${(now - start) / 1000}초가 지나도 안 떠서 어쩔 수 없이 진행($why) · 일치 전체 ${pct(s?.global)} 누를 곳 주변 ${pct(s?.region)}")
                    return ScreenVerdict.TIMEOUT
                }
                ScreenVerdict.STALLED -> {}
                ScreenVerdict.WAIT -> if (now - lastLog >= 2_000) {
                    lastLog = now
                    val s = w.last ?: sc
                    LogStore.add("원하는 화면 대기 중($why)… 일치 전체 ${pct(s?.global)} 누를 곳 주변 ${pct(s?.region)}")
                }
            }
        }
    }

    /**
     * 탭 뒤 다음 동작을 해도 될 때까지 기다린다 (StepSettle 규칙: 최소 대기 + 바뀌기 시작했다면 다 바뀔 때까지).
     * 마지막으로 읽은 화면을 돌려줘 다음 탭의 '탭 전 화면' 기준으로 쓴다.
     */
    private fun settleAfterStep(job: Job, before: PageSig?, minWaitMs: Long, why: String): PageSig? {
        val start = SystemClock.elapsedRealtime()
        val st = StepSettle(before?.hash, before?.loading ?: false, minWaitMs, minWaitMs + Settle.EXTRA_MS, Settle.QUIET_MS, start)
        var last = before
        while (true) {
            checkCancel()
            val now = SystemClock.elapsedRealtime()
            val sig = targetRoot(job)?.let { pageSignature(it) }
            if (sig != null) last = sig
            if (st.ready(sig?.hash, sig?.loading ?: false, sig?.hasContent ?: false, now)) {
                val took = now - start
                LogStore.add(
                    "  └ 화면 확인($why): ${took}ms 대기 · " + when {
                        st.timedOut -> "계속 바뀌어 그대로 진행"
                        st.changed -> "화면이 바뀐 뒤 안정됨"
                        else -> "화면 변화 없음(기록한 대기 시간만 기다림)"
                    },
                )
                return last
            }
            Thread.sleep(100)
        }
    }

    /**
     * 정각(fireAt)이 되고, 앱 첫 화면이 다 뜰 때까지(로딩 표시 없음 + 내용이 0.5초 안정) 기다린 뒤 돌아온다.
     * 앱을 연 직후부터 화면을 지켜보다가 정각 전에 안정이 확인되면 더 읽지 않고 정각에 바로 돌아오므로 첫 탭이 늦어지지 않는다.
     * 정각이 지나도 안정되지 않으면 Settle.START_EXTRA_MS 까지만 더 기다리고 진행한다.
     */
    private fun waitScreenReady(job: Job, fireAt: Long, offset: Long, beforeFire: (() -> Unit)? = null): PageSig? {
        val tracker = StabilityTracker(Settle.QUIET_MS)
        var last: PageSig? = null
        var waitingLogged = false
        while (true) {
            checkCancel()
            val nowMs = System.currentTimeMillis() + offset
            val sig = targetRoot(job)?.let { pageSignature(it) }
            val stable = if (sig == null) {
                tracker.reset()
                false
            } else {
                last = sig
                tracker.update(sig.hash, sig.loading, sig.hasContent, SystemClock.elapsedRealtime())
            }
            if (stable && nowMs < fireAt) {
                // 정각 전에 이미 안정이 확인됐다 -> 더 읽지 않고(읽다가 정각을 넘기지 않도록) 정각까지 쉬었다가 바로 반환
                if (fireAt - (System.currentTimeMillis() + offset) > 300) waitUntil(fireAt - 260, offset)
                beforeFire?.invoke()
                waitUntil(fireAt, offset)
                return last
            }
            if (nowMs >= fireAt) {
                if (stable || sig != null) {
                    // 안정이 확인됐거나, 앱이 앞에 떠 있으면(앱은 10초 전부터 열려 있다) 정각에 그대로 누른다 — 화면 구성이 달라 안정 판정이 안 되는 앱(이미지 위주)에서 늦지 않도록
                    if (waitingLogged) LogStore.add("앱 화면 확인 — 경로 이동 시작 (정각보다 ${nowMs - fireAt}ms 늦음)")
                    beforeFire?.invoke()
                    return last
                }
                if (nowMs >= fireAt + Settle.START_EXTRA_MS) {
                    LogStore.add("앱 화면이 ${Settle.START_EXTRA_MS / 1000}초 넘게 안정되지 않아 그대로 진행")
                    return last
                }
                if (!waitingLogged) {
                    waitingLogged = true
                    LogStore.add("정각이지만 앱 화면이 아직 로딩 중 — 다 뜰 때까지 기다립니다")
                }
            }
            Thread.sleep(if (nowMs >= fireAt) 100L else minOf(100L, maxOf(1L, fireAt - nowMs)))
        }
    }

    /**
     * 이동 단계 하나 실행.
     * 글자 단계: 글자가 보이면 클릭(위치 힌트가 있으면 같은 글자 중 힌트에 가장 가까운 것), 아이콘 단계: 저장한 화면 비율 위치를 탭.
     */
    private fun runStep(job: Job, s: Step, timeoutMs: Long, assumeForeground: Boolean = false): Boolean {
        if (s.isSwipe || s.isTap) {
            if (!assumeForeground && !waitForeground(job, timeoutMs)) return false
            val dm = resources.displayMetrics
            val fx = s.hintX ?: return false
            val fy = s.hintY ?: return false
            val x = fx * dm.widthPixels
            val y = fy * dm.heightPixels
            val ex = (s.endX ?: fx) * dm.widthPixels
            val ey = (s.endY ?: fy) * dm.heightPixels
            // 동작이 끝난 뒤에 돌아온다: 끝나기 전에 다음 동작을 보내면 앞 동작(스크롤 등)이 중간에 취소되기 때문
            val ok: Boolean
            if (s.isSwipe) {
                // 스크롤: 손가락 이동은 빠르게, 떼는 속도는 기록 그대로(= 스크롤 거리 같음)
                val segs = Gestures.swipeSegments(x, y, ex, ey, s.durMs, job.swipeSpeed)
                ok = swipeAndWait(segs)
                LogStore.add(
                    "스크롤 ${if (ok) "실행" else "실패"}: ${segs.sumOf { it.durMs }}ms" +
                        " (기록 ${s.durMs ?: "-"}ms, ${job.swipeSpeed}배${if (segs.size > 1) ", 떼는 속도는 기록 그대로" else ""})",
                )
            } else {
                ok = gestureAndWait(x, y, ex, ey, s.durMs ?: Gestures.TAP_MS)
            }
            if (ok) showDot(x, y, Color.RED)
            return ok
        }
        return waitAndClick(job, s.text, timeoutMs, s.hintX, s.hintY)
    }

    /**
     * 예약 실행(기본 흐름): 오픈 launchLeadMs(기본 5초) 전 앱 실행 → 오픈 정각(lead 보정)에 경로 이동 → 쿠폰 받기.
     * 경로 이동은 정각에 시작하므로 단계마다 오래 기다리지 않고, 마지막에만 짧게 화면 로딩 완료를 확인한 뒤 쿠폰을 누른다.
     * 테스트(즉시 실행/찾기만)는 job 의 오픈 시각이 '지금 + launchLeadMs' 로 바뀐 채 같은 흐름을 탄다(Flow.rehearsal).
     */
    private fun executeAtOpen(job: Job, offset: Long, mode: Mode = Mode.LIVE): Outcome {
        val fmt = SimpleDateFormat("HH:mm:ss.SSS", Locale.KOREA)
        val plan = Flow.plan(job, mode, appInFront = false)
        val hasRoute = plan.replayRoute
        val tl = Flow.timeline(job)
        val launchAt = tl.launchAtMs
        val fireAt = tl.fireAtMs
        timeline = RunTimeline(fireAt) // 정각(목표 시각)을 0으로 각 단계 시각을 기록
        runOffset = offset
        val waitMin = (launchAt - (System.currentTimeMillis() + offset)) / 60_000
        if (waitMin >= 10) {
            LogStore.add("오픈까지 ${waitMin}분 — 그동안 화면이 켜진 채로 유지됩니다 (자동 실행(알람)을 켜면 폰을 잠가 둬도 됩니다)")
        }
        LogStore.add(
            (if (mode == Mode.LIVE) "예약 흐름: 앱 실행 ${fmt.format(Date(launchAt))} → 경로 이동 ${fmt.format(Date(fireAt))}(정각) → 쿠폰 받기"
            else "테스트 흐름: 앱 실행 → ${(fireAt - launchAt) / 1000.0}초 기다린 뒤 경로 이동 → 쿠폰 선택") +
                if (hasRoute) " · 경로 ${job.steps.size}단계" else " · 기록한 경로 없음(현재 화면에서 이어서)",
        )

        if (plan.launch) {
            // 앱을 새로 시작하려면 준비(홈으로 보내기 + 프로세스 종료)가 필요해서 그만큼 앞당겨 시작한다
            waitUntil(launchAt - if (plan.freshStart && job.coldStart) Settle.COLD_PREP_MS else 0L, offset)
            showRunGuard() // 앱을 여는 순간부터 사용자 터치 차단 + 긴급 정지 버튼
            if (!startTarget(job, plan.freshStart)) return Outcome("error", 0, "앱 실행 실패: ${job.pkg} (패키지명 확인)")
            tlMark("앱 실행")
            LogStore.add(if (hasRoute) "앱 실행(첫 화면에서 시작): ${job.pkg}" else "앱을 앞으로 가져옴(현재 화면 유지): ${job.pkg}")
            if (!waitForeground(job, 3_000)) {
                LogStore.add("대상 앱이 자동으로 열리지 않았습니다 (백그라운드 실행 제한일 수 있음) — 직접 열어 주세요")
                Alerts.warn(this, "앱을 직접 열어 주세요", "${job.name}: 대상 앱이 자동으로 열리지 않았습니다. 앱을 열면 매크로가 이어서 진행합니다")
            }
        } else {
            LogStore.add("앱 실행 생략 — 대상 앱의 시작 화면에 가 있어야 합니다")
            waitUntil(launchAt, offset)
            showRunGuard()
        }

        // 정각까지 기다리되, 앱 첫 화면이 다 뜬 뒤에 경로를 시작한다 (로딩 중에 누르면 엉뚱한 화면으로 넘어감).
        // 무거운 일(기준 화면 해석, 화면 확인, 앱이 앞에 있는지 확인)은 전부 정각 전에 끝내 두고, 정각에는 곧바로 누르기만 한다.
        var start: PageSig? = null
        var firstChecked = false
        var fastFirst = false
        var refsBroken = false
        var route: RoutePlan? = null
        if (plan.replayRoute) {
            route = prepareRoute(job)
            // 앱이 뜨는 동안 '기록 때 첫 화면'이 나타나는지 미리 지켜본다 (정각 전에 확인이 끝나 있으면 정각에 바로 시작)
            val s0 = job.steps.firstOrNull()
            val g0 = route.grids.firstOrNull()
            if (s0 != null && route.useRefs && route.waits[0] && g0 != null) {
                // 정각까지 확인이 안 되더라도 화면이 멈춰 있으면(앱은 10초 전부터 열려 있다) 정각에 그대로 누른다
                val fireElapsed = SystemClock.elapsedRealtime() + (fireAt - (System.currentTimeMillis() + offset))
                val v0 = awaitScreen(g0, s0.hintX, s0.hintY, "앱 첫 화면", stallDeadlineElapsed = fireElapsed, canGiveUp = { targetRoot(job) != null })
                firstChecked = v0 != null
                if (v0 == null) refsBroken = true // 화면 캡처가 안 됨: 경로 전체를 시간 기준으로(정각에 다시 시도하느라 늦어지지 않게)
                if (firstChecked) tlMark(if (v0 == ScreenVerdict.STALLED) "첫 화면(확인 못 함, 정각 진행)" else "첫 화면 확인")
            }
            if (firstChecked) {
                // 정각 0.3초 전에 대상 앱이 앞에 있는지만 가볍게 확인 -> 정각에는 확인 없이 바로 누름
                if (fireAt - (System.currentTimeMillis() + offset) > 400) {
                    waitUntil(fireAt - 300, offset)
                    fastFirst = targetRoot(job) != null
                }
                if (fireAt - (System.currentTimeMillis() + offset) > 300) waitUntil(fireAt - 260, offset)
                holdGuardOpen(job.steps.firstOrNull()) // 정각 첫 탭이 잠금 창에 막히지 않게 미리 통과 상태로
                waitUntil(fireAt, offset)
            } else {
                start = waitScreenReady(job, fireAt, offset, beforeFire = { holdGuardOpen(job.steps.firstOrNull()) }) // 화면이 안정된 걸 확인했으면 정각까지 쉬었다가 바로 반환
                tlMark("첫 화면 안정(정각 도달)")
                fastFirst = start != null
            }
        } else {
            waitUntil(fireAt, offset)
        }
        if (route != null) {
            replayRoute(
                job, waitLoaded = plan.waitLoadedBetweenSteps, initial = start, firstScreenChecked = firstChecked,
                route = route, fastFirst = fastFirst, fire = fireAt to offset, refsUsable = !refsBroken,
            )?.let { return it }
            tlMark("경로 끝")
        } else {
            LogStore.add(if (mode == Mode.LIVE) "정각 — 쿠폰 선택 시작" else "앱 실행 후 ${(fireAt - launchAt) / 1000.0}초 경과")
        }
        // 쿠폰 이미지/카드가 화면에 뜨는 즉시 선택한다 (별도 로딩 대기 없음: 클릭 루프가 대상이 보일 때까지 빠르게 확인)
        LogStore.add("경로 이동 끝 — 쿠폰 선택 시작")
        return clickLoop(job, mode)
    }

    private fun execute(job: Job, mode: Mode): Outcome {
        baselineProbe = null
        fractionLogged.clear()
        var offset = 0L
        if (mode == Mode.LIVE) {
            val o = Ntp.offsetMs()
            if (o == null) {
                LogStore.add("NTP 실패 → 기기 시계 그대로 사용")
            } else {
                offset = o
                LogStore.add("NTP 오프셋 ${o}ms")
            }
            runOffset = offset
            if (job.openAtMillis - (System.currentTimeMillis() + offset) < -Schedule.GRACE_MS) {
                return Outcome("error", 0, "오픈 시각이 이미 지났습니다")
            }
        }

        // 자동 실행(알람)으로 시작됐으면 사용자가 잠금을 풀 때까지 기다린다
        if (job.awaitUnlock && mode == Mode.LIVE && isLocked()) {
            val deadline = job.openAtMillis - 8_000
            var reminded = false
            var lastLog = 0L
            LogStore.add("잠금 해제 대기 중 — 화면 잠금을 풀어 주세요")
            while (isLocked()) {
                checkCancel()
                val remain = job.openAtMillis - (System.currentTimeMillis() + offset)
                if (System.currentTimeMillis() + offset >= deadline) {
                    return Outcome("error", 0, "화면 잠금이 풀리지 않아 이번 회차 자동 실행을 하지 못했습니다")
                }
                if (!reminded && remain < 35_000) {
                    reminded = true
                    Alerts.warn(this, "🔒 아직 잠겨 있어요!", "지금 잠금을 해제하세요 — 오픈까지 ${remain / 1000}초")
                }
                if (SystemClock.elapsedRealtime() - lastLog > 10_000) {
                    lastLog = SystemClock.elapsedRealtime()
                    LogStore.add("잠금 해제 대기… 오픈까지 ${remain / 1000}초")
                }
                Thread.sleep(500)
            }
            LogStore.add("잠금 해제 확인")
        }

        // 단계 구성은 Flow.plan 이 정한다 (단위 테스트로 고정)
        val plan = Flow.plan(job, mode, appInFront = targetRoot(job) != null)

        // 기본 흐름: 오픈 N초 전 앱 실행 → 정각에 경로 이동 → 쿠폰 받기. 테스트(즉시 실행/찾기만)도 같은 흐름: 앱 실행 → N초 뒤 경로 이동 → 쿠폰 선택
        if (plan.atOpen) {
            val flowJob = if (mode == Mode.LIVE) job else Flow.rehearsal(job, System.currentTimeMillis())
            return executeAtOpen(flowJob, offset, mode)
        }

        if (mode != Mode.LIVE) showRunGuard() // 테스트는 시작하자마자 잠금 (예약은 오픈 직전에 아래에서)
        if (plan.launch) {
            if (!startTarget(job, plan.freshStart)) return Outcome("error", 0, "앱 실행 실패: ${job.pkg} (패키지명 확인)")
            LogStore.add(if (plan.freshStart) "앱 실행(첫 화면에서 시작): ${job.pkg}" else "앱을 앞으로 가져옴(현재 화면 유지): ${job.pkg}")
            sleep(1500)
            if (!waitForeground(job, 6_000)) {
                LogStore.add("대상 앱이 자동으로 열리지 않았습니다 (백그라운드 실행 제한일 수 있음) — 직접 열어 주세요")
                Alerts.warn(this, "앱을 직접 열어 주세요", "${job.name}: 대상 앱이 자동으로 열리지 않았습니다. 앱을 열면 매크로가 이어서 진행합니다")
            }
            waitForLoaded(job, "앱 시작", maxMs = 10_000)
        } else if (plan.waitForOpen) {
            LogStore.add("앱 실행 생략 — 대상 앱의 쿠폰 화면으로 직접 이동해 두세요")
        } else {
            LogStore.add("테스트: 기록한 경로가 없어 지금 보이는 화면에서 시도합니다")
        }

        if (plan.replayRoute) replayRoute(job, waitLoaded = plan.waitLoadedBetweenSteps)?.let { return it }

        if (plan.waitForOpen) {
            val fmt = SimpleDateFormat("MM-dd HH:mm:ss", Locale.KOREA)
            LogStore.add("대기: 오픈 ${fmt.format(Date(job.openAtMillis))} (lead ${job.leadMs}ms)")
            val fireAt = Flow.timeline(job).fireAtMs
            // 미리 이동해 두고 기다리는 방식: 오픈 5초 전부터 화면 잠금 + 긴급 정지 (그전엔 폰을 쓸 수 있다)
            if (fireAt - (System.currentTimeMillis() + offset) > 5_500) waitUntil(fireAt - 5_000, offset)
            showRunGuard()
            // 오래 전에 열어 둔 페이지는 낡았을 수 있어 오픈 3초 전에 한 번 새로고침
            if (plan.preRefresh && fireAt - (System.currentTimeMillis() + offset) > 3500) {
                waitUntil(fireAt - 3000, offset)
                if (targetRoot(job) != null) {
                    swipeDown()
                    LogStore.add("오픈 직전 새로고침")
                    waitForLoaded(job, "새로고침", maxMs = 8_000, untilMs = fireAt, offset = offset)
                }
            }
            // 위치(이미지) 쿠폰은 결과를 '탭 전엔 없던 결과 문구'로 판별하므로, 오픈 1.5초 전 화면을 기준으로 저장
            if (job.coupons.any { PosTarget.isPos(it) } && fireAt - (System.currentTimeMillis() + offset) > 2500) {
                waitUntil(fireAt - 1500, offset)
                targetRoot(job)?.let { baselineProbe = resultProbe(it, rulesFor(job)) }
            }
            waitUntil(fireAt, offset)
        } else {
            waitForLoaded(job, "선택 전")
        }
        return clickLoop(job, mode)
    }

    /**
     * 지정한 쿠폰(job.coupons)마다 '그 쿠폰 카드 안의 받기 버튼'만 누른다.
     * 모든 쿠폰이 성공/이미 받음/소진 중 하나로 정리되면 종료.
     */
    private fun clickLoop(job: Job, mode: Mode): Outcome {
        // 위치(이미지) 쿠폰은 탭을 빠르게 연타해야 해서, 탭마다 잠금 창 설정을 바꾸면 느려진다 -> 쿠폰 선택 동안만 터치 잠금을 풀어 둔다
        // (글자 쿠폰은 접근성 클릭이라 잠금과 무관. 긴급 정지 버튼은 그대로)
        val openForTaps = guardOn && !guardDropped && job.coupons.any { PosTarget.isPos(it) }
        if (openForTaps) {
            guardOpenAll = true
            acquireGuardPass(false)
            LogStore.add("이미지 쿠폰을 빠르게 누르는 동안은 터치 잠금을 잠시 풀어 둡니다 (긴급 정지 버튼은 그대로)")
        }
        try {
            return clickLoopInner(job, mode)
        } finally {
            if (openForTaps && guardOpenAll) {
                guardOpenAll = false
                releaseGuardPass()
            }
        }
    }

    private fun clickLoopInner(job: Job, mode: Mode): Outcome {
        val rules = rulesFor(job)
        val done = LinkedHashMap<String, String>()   // 쿠폰 문구 -> success|already|soldout|clicked|dry_run
        val clicked = HashSet<String>()
        val posStates = HashMap<String, PosState>()
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
                    val pos = PosTarget.parse(entry)
                    if (pos != null) {
                        if (handlePos(job, mode, rules, root, attempt, entry, pos, posStates.getOrPut(entry) { PosState() }, done)) {
                            foundAny = true
                        }
                        continue
                    }
                    val m = CouponMatcher.resolve(entry, { t -> findNodes(root, t) }, rules) ?: continue
                    foundAny = true
                    when {
                        m.button != null -> {
                            if (mode == Mode.DRY) {
                                done[entry] = "dry_run"
                                showDotOn((m.button as AxNode).raw, Color.GREEN)
                                LogStore.add("[$entry] 카드 발견 → 받기 버튼 확인, 클릭 생략 (카드: ${m.text.take(60)})")
                            } else {
                                val t0 = SystemClock.elapsedRealtime()
                                val ok = clickNode((m.button as AxNode).raw)
                                if (ok) timeline?.markOnce("첫 쿠폰 클릭", System.currentTimeMillis() + runOffset)
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
                    // 못 찾은 이유 (처음 한 번 + 가끔 다시)
                    if (misses == 3 || misses % 40 == 0) explainMiss(job, root)
                    // 화면을 당겨 새로고침하는 건 예약 실행에서만 (테스트 중 화면이 위로 밀리지 않도록)
                    if (Flow.plan(job, mode, appInFront = true).pullRefreshOnMiss && misses % 8 == 0) {
                        swipeDown()
                        LogStore.add("#$attempt 쿠폰이 안 보임 → 새로고침")
                    }
                }
            }
            sleep(if (foundAny) job.intervalMs else minOf(job.intervalMs, 60L))
        }
        val missing = job.coupons.filter { it !in done }
        val partial = if (done.isEmpty()) "" else summarize(job, done, maxAttempts).detail + " · "
        return Outcome("timeout", maxAttempts, partial + "못 찾음/미완료: " + missing.joinToString(", ") { shown(it) })
    }

    private class PosState {
        var clicks = 0
        var lastAt = 0L
        var anchorMisses = 0 // 기준 글자가 안 보인 횟수
    }

    private fun shown(entry: String): String =
        PosTarget.parse(entry)?.let { "위치(${it.anchor.ifEmpty { "화면 비율" }})" } ?: entry

    /**
     * 위치로 지정한 쿠폰: 기준 글자가 보이면 그 글자 + 상대 위치를, 기준 글자가 없거나 안 보이면
     * 저장해 둔 화면 비율 위치를 탭한다 (이미지 화면은 접근성에 글자가 없어도 위치만으로 동작해야 한다).
     */
    private fun handlePos(
        job: Job, mode: Mode, rules: CouponRules, root: AccessibilityNodeInfo, attempt: Int,
        entry: String, pos: PosTarget, st: PosState, done: MutableMap<String, String>,
    ): Boolean = handleTap(job, mode, rules, attempt, entry, st, done) {
        val byAnchor = if (pos.anchor.isEmpty()) null else posByAnchor(root, pos)
        when (Flow.posSource(pos.anchor.isEmpty(), byAnchor != null, st.anchorMisses, mode == Mode.LIVE)) {
            PosSource.ANCHOR -> byAnchor
            PosSource.FRACTION -> {
                if (pos.anchor.isNotEmpty() && fractionLogged.add(entry)) {
                    LogStore.add("[${shown(entry)}] 기준 글자 '${pos.anchor}'가 화면에 안 보여, 저장된 화면 비율 위치(${(pos.fx * 100).toInt()}%, ${(pos.fy * 100).toInt()}%)로 탭합니다")
                }
                posByFraction(pos)
            }
            PosSource.WAIT -> {
                st.anchorMisses++
                null
            }
        }
    }

    /** 위치 대상에서 기준 글자를 못 찾았다고 한 번만 알리기 위한 기록 */
    private val fractionLogged = HashSet<String>()

    /**
     * 좌표 하나를 반복 탭하는 처리. 화면에서 대상 좌표를 얻었으면 true.
     * 결과(성공/이미/소진)는 토스트, 또는 탭 전엔 없던 결과 문구(개수 증가)로 판별한다.
     * 첫 탭을 늦추지 않도록 '기준 화면'은 첫 탭을 보낸 직후(서버 응답 전)에 저장한다.
     */
    private fun handleTap(
        job: Job, mode: Mode, rules: CouponRules, attempt: Int, entry: String,
        st: PosState, done: MutableMap<String, String>, resolve: () -> Pair<Float, Float>?,
    ): Boolean {
        if (st.clicks > 0) {
            val r = posResult(job, rules, st.lastAt)
            if (r != null) {
                done[entry] = r
                LogStore.add("[${shown(entry)}] → ${label(r)}")
                return true
            }
            if (st.clicks >= MAX_POS_CLICKS) {
                done[entry] = "clicked"
                LogStore.add("[${shown(entry)}] ${MAX_POS_CLICKS}회 탭했지만 결과 문구를 확인하지 못해 중단")
                return true
            }
        }
        val pt = resolve() ?: return false
        if (mode == Mode.DRY) {
            done[entry] = "dry_run"
            showDot(pt.first, pt.second, Color.GREEN)
            LogStore.add("[${shown(entry)}] 탭 예정 위치 (${pt.first.toInt()}, ${pt.second.toInt()}) — 클릭 생략 (초록 점 위치 확인)")
            return true
        }
        val ok = gesture(pt.first, pt.second, pt.first, pt.second, 40)
        if (ok) {
            timeline?.markOnce("첫 쿠폰 클릭", lastDispatchAtMs + runOffset)
            st.clicks++
            st.lastAt = SystemClock.elapsedRealtime()
            showDot(pt.first, pt.second, Color.RED)
            LogStore.add("#$attempt [${shown(entry)}] 탭 ${st.clicks}회 (${pt.first.toInt()}, ${pt.second.toInt()})")
            if (baselineProbe == null) targetRoot(job)?.let { baselineProbe = resultProbe(it, rules) }
            if (!job.hasResultTexts) done[entry] = "clicked"
        } else {
            LogStore.add("#$attempt [${shown(entry)}] 탭 실패")
        }
        return true
    }

    /** 기준 글자(접근성) + 상대 위치(dp) -> 화면 좌표. 기준 글자가 안 보이면 null */
    private fun posByAnchor(root: AccessibilityNodeInfo, p: PosTarget): Pair<Float, Float>? {
        val dm = resources.displayMetrics
        val a = root.findAccessibilityNodeInfosByText(p.anchor).firstOrNull { it.isVisibleToUser } ?: return null
        val r = Rect()
        a.getBoundsInScreen(r)
        return inScreen((r.left + p.dxDp * dm.density) to (r.top + p.dyDp * dm.density))
    }

    /** 저장된 화면 비율 위치 -> 화면 좌표 */
    private fun posByFraction(p: PosTarget): Pair<Float, Float>? {
        val dm = resources.displayMetrics
        return inScreen((p.fx * dm.widthPixels) to (p.fy * dm.heightPixels))
    }

    private fun inScreen(pt: Pair<Float, Float>): Pair<Float, Float>? {
        val dm = resources.displayMetrics
        return if (pt.first < 0 || pt.second < 0 || pt.first > dm.widthPixels || pt.second > dm.heightPixels) null else pt
    }

    private fun posResult(job: Job, rules: CouponRules, clickedAt: Long): String? {
        val toast = if (lastToastAt >= clickedAt) lastToast else ""
        if (toast.isNotEmpty()) CouponMatcher.classify(toast, rules, 80)?.let { return it }
        val base = baselineProbe ?: return null
        val root = targetRoot(job) ?: return null
        val now = resultProbe(root, rules)
        fun grew(texts: List<String>) = texts.any { (now[it] ?: 0) > (base[it] ?: 0) }
        return when {
            grew(rules.successTexts) -> "success"
            grew(rules.alreadyTexts) -> "already"
            grew(rules.soldoutTexts) -> "soldout"
            else -> null
        }
    }

    /** 결과 문구가 화면에 몇 개 보이는지(문구별). 전체 화면을 읽지 않고 문구마다 한 번씩만 조회해 빠르다. */
    private fun resultProbe(root: AccessibilityNodeInfo, rules: CouponRules): Map<String, Int> {
        val m = HashMap<String, Int>()
        for (t in rules.successTexts + rules.alreadyTexts + rules.soldoutTexts) {
            m[t] = root.findAccessibilityNodeInfosByText(t).count { it.isVisibleToUser }
        }
        return m
    }

    /** 화면에 보이는 모든 문구(text/contentDescription). 클릭 전후 비교용. */
    private fun snapshotTexts(root: AccessibilityNodeInfo): Set<String> {
        val out = HashSet<String>()
        var seen = 0
        fun walk(n: AccessibilityNodeInfo) {
            if (seen++ >= 1500) return
            n.text?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            n.contentDescription?.toString()?.trim()?.takeIf { it.isNotEmpty() }?.let { out += it }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return out
    }

    /** 쿠폰을 못 찾을 때 이유를 로그에 남긴다: 문구가 화면에 있는지, 기준 문구가 보이는지, 화면에서 읽은 문구 샘플 */
    private fun explainMiss(job: Job, root: AccessibilityNodeInfo) {
        try {
            val texts = snapshotTexts(root)
            for (entry in job.coupons) {
                val pos = PosTarget.parse(entry)
                if (pos != null) {
                    if (pos.anchor.isNotEmpty()) {
                        val n = root.findAccessibilityNodeInfosByText(pos.anchor).count { it.isVisibleToUser }
                        LogStore.add(
                            "[${shown(entry)}] 기준 글자 '${pos.anchor}' " +
                                if (n > 0) "${n}개 보임 (계산한 위치가 화면 밖이면 탭 못 함)"
                                else "접근성에 안 보임 — 화면 비율 위치(${(pos.fx * 100).toInt()}%, ${(pos.fy * 100).toInt()}%)로 탭합니다",
                        )
                    }
                } else {
                    val term = CouponMatcher.terms(entry).firstOrNull() ?: continue
                    val n = root.findAccessibilityNodeInfosByText(term).count { it.isVisibleToUser }
                    LogStore.add(
                        "[$entry] 문구 '$term' " +
                            if (n > 0) "${n}개 보이나 쿠폰 카드/받기 버튼을 못 찾음 (고급 설정의 받기 버튼 문구 확인)"
                            else "화면에 없음 — 이미지로 된 쿠폰이면 [화면에서 고르기 → 위치로 찍기]로 지정하세요",
                    )
                }
            }
            LogStore.add("화면에서 읽은 문구 ${texts.size}개: " + texts.take(12).joinToString(" | ") { it.take(14) })
            if (texts.size < 8) {
                LogStore.add("읽히는 문구가 거의 없습니다 — 이미지 위주 화면이면 쿠폰을 [화면에서 고르기 → 위치로 찍기]로 지정하세요")
            }
        } catch (e: Exception) {
            LogStore.add("원인 분석 실패: $e")
        }
    }

    private fun summarize(job: Job, done: Map<String, String>, attempts: Int): Outcome {
        val parts = listOf("success", "already", "soldout", "clicked", "dry_run").mapNotNull { st ->
            val names = job.coupons.filter { done[it] == st }
            if (names.isEmpty()) null else "${label(st)}: ${names.joinToString(", ") { shown(it) }}"
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
    private fun targetRoot(job: Job): AccessibilityNodeInfo? {
        val active = rootInActiveWindow
        if (active != null && active.packageName?.toString() == job.pkg) return active
        // 오버레이 등으로 활성 창이 바뀐 경우: 가장 위의 앱 창이 대상 앱이면 그 창을 읽는다
        return try {
            windows.firstOrNull { it.type == AccessibilityWindowInfo.TYPE_APPLICATION }
                ?.root?.takeIf { it.packageName?.toString() == job.pkg }
        } catch (_: Exception) {
            null
        }
    }

    private fun find(root: AccessibilityNodeInfo, texts: List<String>): AccessibilityNodeInfo? {
        for (t in texts) {
            val hit = root.findAccessibilityNodeInfosByText(t).firstOrNull { it.isVisibleToUser && it.isEnabled }
            if (hit != null) return hit
        }
        return null
    }

    /** 같은 글자가 여러 곳이면 힌트(화면 비율)에 가장 가까운 것, 힌트가 없으면 첫 번째 */
    private fun findNear(root: AccessibilityNodeInfo, text: String, hx: Float?, hy: Float?): AccessibilityNodeInfo? {
        if (hx == null || hy == null) return find(root, listOf(text))
        val dm = resources.displayMetrics
        val px = hx * dm.widthPixels
        val py = hy * dm.heightPixels
        return root.findAccessibilityNodeInfosByText(text)
            .filter { it.isVisibleToUser && it.isEnabled }
            .minByOrNull { n ->
                val r = Rect()
                n.getBoundsInScreen(r)
                hypot((r.exactCenterX() - px).toDouble(), (r.exactCenterY() - py).toDouble())
            }
    }

    private fun waitAndClick(job: Job, text: String, timeoutMs: Long, hx: Float? = null, hy: Float? = null): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        var n = 0
        while (SystemClock.elapsedRealtime() < end) {
            checkCancel()
            val root = targetRoot(job)
            if (root != null) {
                val node = findNear(root, text, hx, hy)
                if (node != null && clickNode(node)) return true
                if (n++ % 4 == 0) dismissPopups(job, root)
            }
            sleep(150)
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
        val ok = clickNodeRaw(node)
        if (ok) showDotOn(node, Color.RED)
        return ok
    }

    /** 노드 중앙에 점을 잠깐 표시 (클릭이 실제로 나갔는지 눈으로 확인용). 터치는 가로채지 않는다. */
    private fun showDotOn(node: AccessibilityNodeInfo, color: Int) {
        if (!dots) return
        val r = Rect()
        node.getBoundsInScreen(r)
        if (r.isEmpty) return
        showDot(r.exactCenterX(), r.exactCenterY(), color)
    }

    private fun showDot(cx: Float, cy: Float, argb: Int) {
        main.post {
            try {
                val wm = getSystemService(WINDOW_SERVICE) as WindowManager
                val size = dp(28)
                val v = View(this).apply {
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(argb)
                        setStroke(dp(2), Color.WHITE)
                    }
                    alpha = 0.9f
                }
                val lp = WindowManager.LayoutParams(
                    size, size,
                    WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT,
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                    x = (cx - size / 2f).toInt()
                    y = (cy - size / 2f).toInt()
                }
                wm.addView(v, lp)
                main.postDelayed({ runCatching { wm.removeView(v) } }, 800)
            } catch (_: Exception) {
            }
        }
    }

    private fun clickNodeRaw(node: AccessibilityNodeInfo): Boolean {
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

    @Volatile private var lastShotAt = 0L
    @Volatile private var lastShotFail = -1
    @Volatile private var captureWarned = false

    /** 접근성 서비스가 '화면 캡처' 권한을 갖고 있는지: true/false, 안드로이드 10 이하는 null(해당 없음) */
    @SuppressLint("NewApi")
    fun captureCapability(): Boolean? {
        if (Build.VERSION.SDK_INT < 30) return null
        val caps = serviceInfo?.capabilities ?: return false
        return (caps and AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT) != 0
    }

    fun captureStatus(): String = when (captureCapability()) {
        null -> "안드로이드 10 이하라 사용 안 함(시간 기준)"
        true -> "사용 가능"
        false -> "권한 없음 → 접근성에서 '쿠폰 매크로'를 껐다 켜기"
    }

    /**
     * 지금 화면을 ScreenRef 격자(칸별 밝기)로 읽는다. 안드로이드 11+ 에서만 가능하고, 실패하면 null(lastShotFail 에 시스템 코드).
     * 캡처 간격 제한(약 1/3초)이 있어 호출 사이를 스스로 맞춘다. 한 번에 한 곳에서만 부른다(synchronized).
     */
    @Synchronized
    @SuppressLint("NewApi")
    private fun captureGrid(): IntArray? {
        if (Build.VERSION.SDK_INT < 30) return null
        repeat(3) {
            val wait = ScreenRef.MIN_SHOT_INTERVAL_MS - (SystemClock.elapsedRealtime() - lastShotAt)
            if (wait > 0) Thread.sleep(wait)
            lastShotAt = SystemClock.elapsedRealtime()
            val latch = CountDownLatch(1)
            var grid: IntArray? = null
            var fail = -1
            try {
            takeScreenshot(
                Display.DEFAULT_DISPLAY,
                shotCbExecutor,
                object : AccessibilityService.TakeScreenshotCallback {
                    override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                        try {
                            val hb = result.hardwareBuffer
                            val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                            val soft = hw?.copy(Bitmap.Config.ARGB_8888, false)
                            hw?.recycle()
                            hb.close()
                            if (soft != null) {
                                grid = bitmapToGrid(soft)
                                soft.recycle()
                            }
                        } catch (e: Exception) {
                            LogStore.add("화면 캡처 변환 실패: $e")
                        } finally {
                            latch.countDown()
                        }
                    }

                    override fun onFailure(errorCode: Int) {
                        fail = errorCode
                        latch.countDown()
                    }
                },
            )
            } catch (e: SecurityException) {
                // 접근성 서비스에 '화면 캡처' 권한이 없다(업데이트 직후 등) — 실행이 중단되지 않게 하고 안내를 남긴다
                lastShotFail = -2
                if (!captureWarned) {
                    captureWarned = true
                    LogStore.add("화면 캡처 권한이 없습니다 → 접근성 설정에서 '쿠폰 매크로'를 껐다가 다시 켜 주세요. 그동안은 시간 기준으로 진행합니다 ($e)")
                }
                return null
            }
            latch.await(3, TimeUnit.SECONDS)
            grid?.let { return it }
            lastShotFail = fail
            if (fail != 3) return null // 3: 너무 자주 캡처 — 간격을 두고 한 번 더. 그 외는 포기
        }
        return null
    }

    /** 화면 비트맵 -> ScreenRef 격자(칸별 평균 밝기). 반씩 줄여 가며 면적 평균에 가깝게 줄인다 */
    private fun bitmapToGrid(src: Bitmap): IntArray {
        val cell = 4
        val tw = ScreenRef.COLS * cell
        val th = ScreenRef.ROWS * cell
        var bmp = src
        while (bmp.width >= tw * 2 && bmp.height >= th * 2) {
            val next = Bitmap.createScaledBitmap(bmp, bmp.width / 2, bmp.height / 2, true)
            if (bmp !== src) bmp.recycle()
            bmp = next
        }
        val small = Bitmap.createScaledBitmap(bmp, tw, th, true)
        val px = IntArray(tw * th)
        small.getPixels(px, 0, tw, 0, 0, tw, th)
        if (small !== bmp) small.recycle()
        if (bmp !== src) bmp.recycle()
        val out = IntArray(ScreenRef.SIZE)
        for (r in 0 until ScreenRef.ROWS) for (c in 0 until ScreenRef.COLS) {
            var sum = 0
            for (dy in 0 until cell) for (dx in 0 until cell) {
                val p = px[(r * cell + dy) * tw + c * cell + dx]
                sum += (Color.red(p) * 299 + Color.green(p) * 587 + Color.blue(p) * 114) / 1000
            }
            out[r * ScreenRef.COLS + c] = sum / (cell * cell)
        }
        return out
    }

    /**
     * 손가락을 떼지 않고 이어지는 여러 구간의 스와이프를 보내고 끝날 때까지 기다린다.
     * 구간마다 따로 dispatchGesture 하되, 앞 구간은 willContinue 로 손가락을 누른 채 두고 다음 구간을 continueStroke 로 이어 붙인다.
     * 구간이 하나면 일반 동작과 같다.
     */
    private fun swipeAndWait(segs: List<Gestures.Seg>): Boolean {
        if (segs.size == 1) {
            val s = segs[0]
            return gestureAndWait(s.x1, s.y1, s.x2, s.y2, s.durMs)
        }
        val dm = resources.displayMetrics
        val zone = RunGuard.stopZone(dm.widthPixels, dm.heightPixels, dm.density)
        val hits = segs.any { zone.touches(it.x1, it.y1, it.x2, it.y2, 8 * dm.density) }
        val total = segs.sumOf { it.durMs }
        var attempt = 0
        while (true) {
            val ownsPass = guardOn && !guardHeld && !guardDropped
            if (ownsPass) acquireGuardPass(hits)
            guardLeaked = false
            macroTouch = MacroTouch(segs.first().x1, segs.first().y1, segs.last().x2, segs.last().y2, SystemClock.elapsedRealtime() + total + 300)
            val finished = CountDownLatch(1)
            var okAll = true
            fun send(i: Int, prev: GestureDescription.StrokeDescription?) {
                val s = segs[i]
                val last = i == segs.lastIndex
                val path = Path().apply {
                    moveTo(s.x1, s.y1)
                    lineTo(s.x2, s.y2)
                }
                val stroke = if (prev == null) {
                    GestureDescription.StrokeDescription(path, 0, s.durMs, !last)
                } else {
                    prev.continueStroke(path, 0, s.durMs, !last)
                }
                val cb = object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription?) {
                        if (last) finished.countDown() else send(i + 1, stroke)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription?) {
                        okAll = false
                        finished.countDown()
                    }
                }
                if (i == 0) lastDispatchAtMs = System.currentTimeMillis()
                if (!dispatchGesture(GestureDescription.Builder().addStroke(stroke).build(), cb, null)) {
                    okAll = false
                    finished.countDown()
                }
            }
            try {
                send(0, null)
                finished.await(total + 2_000, TimeUnit.MILLISECONDS)
                if (guardOn) Thread.sleep(15) // 가로채기 감지가 끝날 시간
            } finally {
                macroTouch = null
                if (ownsPass) releaseGuardPass()
            }
            if (!guardLeaked || !guardOn || guardDropped) return okAll
            attempt++
            if (!onGuardLeak(attempt)) return okAll
        }
    }

    /** 동작을 보내고 끝날 때까지(최대 길이+0.8초) 기다린다. 못 보내면 false */
    private fun gestureAndWait(x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long): Boolean {
        val done = CountDownLatch(1)
        val ok = gesture(
            x1, y1, x2, y2, durationMs,
            object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) = done.countDown()
                override fun onCancelled(gestureDescription: GestureDescription?) = done.countDown()
            },
        )
        if (ok) done.await(durationMs + 800, TimeUnit.MILLISECONDS)
        return ok
    }

    /** 이번 실행의 타임라인(정각=0). 정각에 경로로 바로 들어가는지, 몇 초 걸리는지 로그로 남긴다 */
    @Volatile private var timeline: RunTimeline? = null

    /** NTP 보정값(ms): 기기 시계 + 이 값 = 실제 시각 */
    @Volatile private var runOffset = 0L

    private fun tlMark(label: String, atDeviceMs: Long = System.currentTimeMillis()) {
        timeline?.mark(label, atDeviceMs + runOffset)
    }

    /** 원하는 화면이 떴다고 마지막으로 확인한 시각(elapsedRealtime) — 그로부터 afterScreenMs 뒤에 누른다 */
    @Volatile private var screenMatchedAt = 0L

    /** 화면이 뜬 걸 확인한 뒤 ms 가 지나기까지 기다린다(이미 지났으면 바로). 뜨자마자 누르면 버튼이 아직 안 눌리는 경우 대비 */
    private fun holdAfterScreen(ms: Long, capAtElapsed: Long? = null) {
        if (ms <= 0) return
        val wait = Settle.holdWaitMs(screenMatchedAt, SystemClock.elapsedRealtime(), ms, capAtElapsed)
        if (wait > 0) {
            LogStore.add("화면이 뜬 뒤 ${wait}ms 더 기다렸다가 누릅니다 (설정 ${ms}ms)")
            sleep(wait)
        }
    }

    /** 마지막으로 동작(탭/스와이프)을 시스템에 보낸 시각(epoch ms) — 정각 대비 지연 확인용 */
    @Volatile private var lastDispatchAtMs = 0L

    private fun gesture(
        x1: Float, y1: Float, x2: Float, y2: Float, durationMs: Long,
        callback: AccessibilityService.GestureResultCallback? = null,
    ): Boolean {
        val p = Path().apply {
            moveTo(x1, y1)
            if (x1 != x2 || y1 != y2) lineTo(x2, y2)
        }
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0, durationMs))
            .build()
        // 실행 중 화면 잠금(사용자 터치 차단)이 없거나 메인 스레드면 그냥 보낸다
        if (!guardOn || guardDropped || guardOpenAll || Looper.myLooper() == Looper.getMainLooper()) {
            lastDispatchAtMs = System.currentTimeMillis()
            return dispatchGesture(g, callback, null)
        }
        // 잠금이 켜져 있으면: 이 동작을 보내는 동안만 잠금 창이 터치를 통과시키고, 끝나면 되돌린다.
        // 그런데 창 설정이 입력 시스템에 닿기 전에 동작이 먼저 도착하면 잠금 창이 매크로 동작을 먹어 버린다 -> 먹혔는지 확인하고
        // 통과 대기 시간을 늘려 다시 보낸다. 그래도 안 되면 잠금을 포기하고 매크로를 우선한다(긴급 정지 버튼은 유지).
        val dm = resources.displayMetrics
        val hits = RunGuard.stopZone(dm.widthPixels, dm.heightPixels, dm.density).touches(x1, y1, x2, y2, 8 * dm.density)
        var attempt = 0
        while (true) {
            val own = !guardHeld && !guardDropped
            if (own) acquireGuardPass(hits)
            guardLeaked = false
            macroTouch = MacroTouch(x1, y1, x2, y2, SystemClock.elapsedRealtime() + durationMs + 300)
            val done = CountDownLatch(1)
            var completed = false
            val cb = object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    completed = true
                    done.countDown()
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    done.countDown()
                }
            }
            lastDispatchAtMs = System.currentTimeMillis()
            val ok = dispatchGesture(g, cb, null)
            if (ok) {
                done.await(durationMs + 800, TimeUnit.MILLISECONDS)
                Thread.sleep(15) // 가로채기 감지(터치 이벤트 전달)가 끝날 시간
            }
            macroTouch = null
            if (own) releaseGuardPass()
            if (!ok) return false
            if (!guardLeaked || !guardOn || guardDropped) {
                if (completed) callback?.onCompleted(g) else callback?.onCancelled(g)
                return true
            }
            attempt++
            if (!onGuardLeak(attempt)) {
                callback?.onCompleted(g)
                return true
            }
        }
    }

    /**
     * 잠금 창이 매크로 동작을 먹었을 때: 통과 대기 시간을 늘리고, 거듭되면 잠금을 포기한다.
     * @return true 면 같은 동작을 다시 보낸다 / false 면 더 시도하지 않는다
     */
    private fun onGuardLeak(attempt: Int): Boolean {
        guardSettleMs = minOf(guardSettleMs + 50, 250L)
        LogStore.add("잠금 창이 매크로 동작을 가로챘습니다 → 통과 대기를 ${guardSettleMs}ms 로 늘려 다시 보냅니다 (${attempt}회)")
        if (guardHeld) {
            guardHeld = false
            releaseGuardPass()
        }
        if (attempt >= 2) {
            LogStore.add("이 기기에서는 터치 잠금이 매크로 동작과 함께 동작하지 않아 잠금을 풀고 진행합니다 (긴급 정지 버튼은 유지)")
            dropGuardWindow()
        }
        return attempt < 3
    }

    /** 경로를 따라갈 때(fresh)는 설정에 따라 앱을 새로 시작(coldStart)하거나 그냥 첫 화면부터 연다 */
    private fun startTarget(job: Job, fresh: Boolean): Boolean {
        val ok = if (fresh && job.coldStart) coldStart(job.pkg) else launch(job.pkg, fresh)
        return ok
    }

    /**
     * 앱을 '새로 시작'한다 — 경로를 기록할 때와 재생할 때 앱 상태(데이터 새로고침 등)를 같게 하려고 항상 같은 방식으로 연다.
     *  ① 대상 앱이 앞에 있으면 홈으로 보내고(앞에 있는 앱은 종료할 수 없다) ② 백그라운드 프로세스를 종료한 뒤 ③ 첫 화면부터 실행.
     * 프로세스가 살아 있으면 앱이 첫 화면을 다시 만들기만 하고 데이터는 새로고침하지 않는 경우가 있어서 기록 때와 환경이 달라진다.
     * (종료는 시스템이 허용하는 범위에서 최선 — 서비스가 떠 있는 앱 등은 종료되지 않을 수 있다)
     */
    private fun coldStart(pkg: String): Boolean {
        val front = try {
            rootInActiveWindow?.packageName?.toString() == pkg
        } catch (_: Exception) {
            false
        }
        if (front) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            Thread.sleep(700)
        }
        try {
            (getSystemService(ACTIVITY_SERVICE) as ActivityManager).killBackgroundProcesses(pkg)
            LogStore.add("대상 앱 프로세스를 종료하고 처음부터 새로 시작합니다")
        } catch (e: Exception) {
            LogStore.add("앱 프로세스 종료 실패(그냥 처음 화면부터 엽니다): $e")
        }
        Thread.sleep(300)
        return launch(pkg, fresh = true)
    }

    /**
     * 대상 앱을 앞으로 가져온다.
     * fresh=true: 앱 첫 화면에서 새로 시작(경로 기록/재생의 출발점), false: 지금 보던 화면 그대로 이어서(현재 화면 유지).
     */
    private fun launch(pkg: String, fresh: Boolean = true): Boolean {
        val i = packageManager.getLaunchIntentForPackage(pkg) ?: return false
        i.addFlags(if (fresh) Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK else Intent.FLAG_ACTIVITY_NEW_TASK)
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

    private fun isLocked(): Boolean =
        (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    /** 대상 앱이 화면 앞에 나타날 때까지 대기 */
    private fun waitForeground(job: Job, timeoutMs: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + timeoutMs
        while (SystemClock.elapsedRealtime() < end) {
            checkCancel()
            if (targetRoot(job) != null) return true
            Thread.sleep(250)
        }
        return false
    }

    private class PageSig(val hash: Int, val loading: Boolean, val hasContent: Boolean)

    /** 화면 내용 시그니처 + 로딩 표시(ProgressBar) 유무 */
    private fun pageSignature(root: AccessibilityNodeInfo): PageSig {
        var h = 17
        var loading = false
        var seen = 0
        fun walk(n: AccessibilityNodeInfo) {
            if (seen++ >= 800) return
            val cls = n.className?.toString().orEmpty()
            if (cls.contains("ProgressBar") && n.isVisibleToUser) loading = true
            for (t in listOf(n.text?.toString(), n.contentDescription?.toString())) {
                if (!t.isNullOrBlank() && t.none { it.isDigit() }) h += t.hashCode()
            }
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it) }
        }
        walk(root)
        return PageSig(h, loading, seen >= 5)
    }

    /**
     * 화면 로딩이 끝날 때까지 대기: 로딩 표시가 없고 내용이 1초간 변하지 않으면 완료.
     * 쿠폰 선택/클릭은 이 대기가 끝난 뒤에 한다. 제한 시간(maxMs)이나 untilMs(오픈 시각 등)를 넘기면 그대로 진행.
     * 숫자가 들어간 문구(카운트다운·가격 등)는 계속 바뀌므로 안정 판정에서 제외한다.
     */
    private fun waitForLoaded(
        job: Job, why: String, maxMs: Long = 6_000, untilMs: Long = Long.MAX_VALUE, offset: Long = 0L,
        quietMs: Long = 1_000,
    ): Boolean {
        val tracker = StabilityTracker(quietMs)
        val start = SystemClock.elapsedRealtime()
        var announced = false
        while (true) {
            checkCancel()
            val now = SystemClock.elapsedRealtime()
            if (now - start >= maxMs || System.currentTimeMillis() + offset >= untilMs) {
                LogStore.add("화면 로딩 대기 시간 초과($why) — 그대로 진행")
                return false
            }
            val root = targetRoot(job)
            if (root == null) {
                tracker.reset()
            } else {
                val sig = pageSignature(root)
                if (tracker.update(sig.hash, sig.loading, sig.hasContent, now)) {
                    if (announced) LogStore.add("화면 로딩 완료($why)")
                    return true
                }
                if (!announced && now - start > 1_500) {
                    announced = true
                    LogStore.add("화면 로딩 대기 중($why)…")
                }
            }
            Thread.sleep(250)
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

    private fun acquireWakeLock(timeoutMs: Long) {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "couponmacro:job").apply {
            acquire(timeoutMs)
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

    private fun notifyResult(title: String, text: String) = Alerts.result(this, title, text)
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
