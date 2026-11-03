package app.ticketing.couponmacro

import android.content.Context
import android.content.SharedPreferences

/** 프로필별 입력값 저장/복원 (SharedPreferences). 앱을 업데이트해도 유지된다. */
object Store {
    private fun prefs(ctx: Context, name: String): SharedPreferences =
        ctx.getSharedPreferences(name, Context.MODE_PRIVATE)

    fun lastProfileId(ctx: Context): String = prefs(ctx, "app").getString("last", Presets.ALL[0].id).orEmpty()

    fun setLastProfile(ctx: Context, id: String) {
        prefs(ctx, "app").edit().putString("last", id).commit()
    }

    fun save(ctx: Context, id: String, f: FormState) {
        // commit(): 앱 전환/강제 종료 직전에도 확실히 기록
        prefs(ctx, "form_$id").edit()
            .putString("name", f.name)
            .putString("pkg", f.pkg)
            .putBoolean("launchApp", f.launchApp)
            .putString("openAt", f.openAt)
            .putString("leadMs", f.leadMs)
            .putString("intervalMs", f.intervalMs)
            .putString("maxAttempts", f.maxAttempts)
            .putString("resultWaitSec", f.resultWaitSec)
            .putString("steps", f.steps)
            .putString("popups", f.popups)
            .putString("targets", f.targets)
            .putString("coupons", f.coupons)
            .putString("success", f.success)
            .putString("already", f.already)
            .putString("soldout", f.soldout)
            .putBoolean("refreshOnMiss", f.refreshOnMiss)
            .putString("slots", f.slots)
            .putString("slotsOff", f.slotsOff)
            .putBoolean("autoRun", f.autoRun)
            .putBoolean("routeAtOpen", f.routeAtOpen)
            .putString("launchLeadSec", f.launchLeadSec)
            .putString("swipeSpeed", f.swipeSpeed)
            .putString("afterScreenSec", f.afterScreenSec)
            .putString("afterScrollSec", f.afterScrollSec)
            .putString("warnSec", f.warnSec)
            .putBoolean("showDots", f.showDots)
            .commit()
    }

    /** 자동 실행이 켜진 이벤트들의 켜진 회차 전부 */
    fun autoEntries(ctx: Context): List<AutoEntry> = Presets.ALL.flatMap { pr ->
        val f = load(ctx, pr.id) ?: pr.defaults
        if (!f.autoRun) {
            emptyList()
        } else {
            val warn = f.warnSec.trim().toIntOrNull()?.coerceIn(20, 600) ?: 60
            Schedule.enabled(f.slots, f.slotsOff).map { AutoEntry(pr.id, it, warn) }
        }
    }

    /** 이미 알람이 울린 회차 키("프로필@오픈시각") — 같은 회차를 두 번 실행하지 않기 위함 */
    fun handledKeys(ctx: Context): Set<String> =
        prefs(ctx, "auto").getStringSet("handled", emptySet())?.toSet() ?: emptySet()

    fun markHandled(ctx: Context, key: String, nowMs: Long) {
        val keep = handledKeys(ctx).filter { (it.substringAfter('@').toLongOrNull() ?: 0L) > nowMs - 3L * 86_400_000 }
        prefs(ctx, "auto").edit().putStringSet("handled", (keep + key).toSet()).commit()
    }

    /** 서비스가 기록한 이동 경로를 앱 화면이 다음에 열릴 때 가져가도록 임시 보관 */
    fun setPendingSteps(ctx: Context, profileId: String, steps: String) {
        prefs(ctx, "app").edit().putString("pending_steps_$profileId", steps).commit()
    }

    /**
     * 화면에서 고른 쿠폰 목록도 같은 방식으로 전달.
     * append=true 면 기존 목록에 덧붙이고(위치 지정), false 면 교체한다(문구 목록 고르기).
     */
    fun setPendingCoupons(ctx: Context, profileId: String, coupons: String, append: Boolean = false) {
        prefs(ctx, "app").edit()
            .putString("pending_coupons_$profileId", coupons)
            .putBoolean("pending_coupons_append_$profileId", append)
            .commit()
    }

    /** (쿠폰 문자열, 덧붙이기 여부) */
    fun takePendingCoupons(ctx: Context, profileId: String): Pair<String, Boolean>? {
        val p = prefs(ctx, "app")
        val key = "pending_coupons_$profileId"
        val v = p.getString(key, null) ?: return null
        val append = p.getBoolean("pending_coupons_append_$profileId", false)
        p.edit().remove(key).remove("pending_coupons_append_$profileId").commit()
        return v to append
    }

    fun takePendingSteps(ctx: Context, profileId: String): String? {
        val p = prefs(ctx, "app")
        val key = "pending_steps_$profileId"
        val v = p.getString(key, null) ?: return null
        p.edit().remove(key).commit()
        return v
    }

    /** 저장된 값이 없으면 null (구버전 단일 폼은 패키지로 판별해 해당 프로필로 이전). */
    fun load(ctx: Context, id: String): FormState? {
        val p = prefs(ctx, "form_$id")
        if (p.contains("pkg")) return read(p)
        val old = prefs(ctx, "form")
        if (old.contains("pkg")) {
            val f = read(old)
            val isCoupang = f.pkg == Presets.COUPANG.defaults.pkg
            if ((id == Presets.COUPANG.id) == isCoupang) return f
        }
        return null
    }

    private fun read(p: SharedPreferences): FormState {
        val d = FormState()
        val slots = p.getString("slots", d.slots).orEmpty()
        // 구버전: 선택한 회차 하나만 사용(slot) -> 나머지는 꺼 둔 것(slotsOff)으로 이전
        val slotsOff = p.getString("slotsOff", null) ?: run {
            val sel = Schedule.parseSlot(p.getString("slot", Schedule.AUTO).orEmpty())
            if (sel == null) "" else Schedule.parseSlots(slots).filter { it != sel }.joinToString(", ") { Schedule.label(it) }
        }
        return FormState(
            name = p.getString("name", d.name).orEmpty(),
            pkg = p.getString("pkg", d.pkg).orEmpty(),
            launchApp = p.getBoolean("launchApp", d.launchApp),
            openAt = p.getString("openAt", d.openAt).orEmpty(),
            leadMs = p.getString("leadMs", d.leadMs).orEmpty(),
            intervalMs = p.getString("intervalMs", d.intervalMs).orEmpty(),
            maxAttempts = p.getString("maxAttempts", d.maxAttempts).orEmpty(),
            resultWaitSec = p.getString("resultWaitSec", d.resultWaitSec).orEmpty(),
            steps = p.getString("steps", d.steps).orEmpty(),
            popups = p.getString("popups", d.popups).orEmpty(),
            targets = p.getString("targets", d.targets).orEmpty(),
            coupons = p.getString("coupons", d.coupons).orEmpty(),
            success = p.getString("success", d.success).orEmpty(),
            already = p.getString("already", d.already).orEmpty(),
            soldout = p.getString("soldout", d.soldout).orEmpty(),
            refreshOnMiss = p.getBoolean("refreshOnMiss", d.refreshOnMiss),
            slots = slots,
            slotsOff = slotsOff,
            autoRun = p.getBoolean("autoRun", d.autoRun),
            routeAtOpen = p.getBoolean("routeAtOpen", d.routeAtOpen),
            launchLeadSec = p.getString("launchLeadSec", d.launchLeadSec).orEmpty().ifBlank { d.launchLeadSec },
            swipeSpeed = p.getString("swipeSpeed", d.swipeSpeed).orEmpty().ifBlank { d.swipeSpeed },
            afterScreenSec = p.getString("afterScreenSec", d.afterScreenSec).orEmpty().ifBlank { d.afterScreenSec },
            afterScrollSec = p.getString("afterScrollSec", d.afterScrollSec).orEmpty().ifBlank { d.afterScrollSec },
            warnSec = p.getString("warnSec", d.warnSec).orEmpty().ifBlank { d.warnSec },
            showDots = p.getBoolean("showDots", d.showDots),
        )
    }
}
