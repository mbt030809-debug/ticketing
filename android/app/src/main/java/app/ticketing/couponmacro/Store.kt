package app.ticketing.couponmacro

import android.content.Context

/** 입력값 저장/복원 (SharedPreferences). */
object Store {
    private const val FILE = "form"

    fun save(ctx: Context, f: FormState) {
        ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
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
            .putString("success", f.success)
            .putString("already", f.already)
            .putString("soldout", f.soldout)
            .putBoolean("refreshOnMiss", f.refreshOnMiss)
            .apply()
    }

    fun load(ctx: Context): FormState {
        val p = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        if (!p.contains("pkg")) return Presets.COUPANG
        val d = FormState()
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
            success = p.getString("success", d.success).orEmpty(),
            already = p.getString("already", d.already).orEmpty(),
            soldout = p.getString("soldout", d.soldout).orEmpty(),
            refreshOnMiss = p.getBoolean("refreshOnMiss", d.refreshOnMiss),
        )
    }
}
