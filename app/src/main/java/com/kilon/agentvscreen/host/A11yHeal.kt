package com.kilon.agentvscreen.host

import com.kilon.agentvscreen.tr

import android.content.Context
import android.os.SystemClock

/**
 * Keeps the read-only accessibility service on after the user asked for it once (setup), the way android-vdisplay
 * does, with three guard rails:
 *  - append-only (Setup.enableAccessibility keeps every other service), at most once a minute;
 *  - when it goes off again shortly after we turned it back on, that is the user (or the ROM) saying no: we stop and
 *    the app says so, until the user runs setup again;
 *  - when the system blocks the write (MagicOS / HarmonyOS), we remember it and stop trying; the app shows the manual way.
 * A force-stop or an app update drops the service on some ROMs; this is what brings it back without the user.
 */
object A11yHeal {
    private const val MIN_GAP_MS = 60_000L
    private const val USER_OFF_WINDOW_MS = 10 * 60_000L
    @Volatile private var lastTry = 0L
    @Volatile private var healedAt = 0L
    @Volatile var lastResult: String? = null; private set

    private fun p(ctx: Context) = ctx.getSharedPreferences("agent_vscreen", Context.MODE_PRIVATE)
    fun wanted(ctx: Context) = p(ctx).getBoolean("a11y_wanted", false)
    fun blocked(ctx: Context) = p(ctx).getBoolean("a11y_blocked", false)
    fun userOff(ctx: Context) = p(ctx).getBoolean("a11y_user_off", false)
    /** The last heal wrote the setting but the service never bound (the system recorded it as crashed, e.g.
     *  after a force-stop or a crash while it was bound). Re-adding the entry does not clear that list — only
     *  the user toggling it in Settings does — so this is a reminder for the home page shortcut, not a lock. */
    fun needsManual(ctx: Context) = p(ctx).getBoolean("a11y_needs_manual", false)

    /** The user asked for it (setup): heal from now on, forget earlier refusals. */
    fun want(ctx: Context) { p(ctx).edit().putBoolean("a11y_wanted", true).putBoolean("a11y_blocked", false).putBoolean("a11y_user_off", false).apply() }

    /** Called when the service disconnects while the app runs. */
    fun onLost(ctx: Context) {
        if (healedAt > 0 && SystemClock.elapsedRealtime() - healedAt < USER_OFF_WINDOW_MS) {
            p(ctx).edit().putBoolean("a11y_user_off", true).apply()
            lastResult = tr("无障碍刚自动打开又被关掉了：当作你不想开，不再自动打开（首页“一键配置”可再开）", "Accessibility was turned off right after being turned on automatically: taken as your choice, so it won't be turned on again (Set up the rest on the home page can turn it on)")
            AppLogger.info("A11yHeal", "turned off again after heal: stop healing")
        }
    }

    /** Turn the service back on if the user wants it and nothing says no. Cheap when there is nothing to do. */
    fun maybeHeal(ctx: Context, why: String) {
        if (VScreenAccessibilityService.getInstance() != null) {
            // Connected now: forget an earlier "needs manual toggle" and an "a11y_blocked" left by the old
            // logic (it locked the flag for every failure, including the crashed-list kind a manual toggle
            // fixes). Only write when there is something to clear — this runs on every command. If the write
            // is genuinely intercepted, a later disconnect re-locks it.
            if (needsManual(ctx) || blocked(ctx))
                p(ctx).edit().putBoolean("a11y_needs_manual", false).putBoolean("a11y_blocked", false).apply()
            return
        }
        if (!wanted(ctx) || blocked(ctx) || userOff(ctx) || !ShizukuManager.isReady()) return
        val now = SystemClock.elapsedRealtime()
        if (lastTry != 0L && now - lastTry < MIN_GAP_MS) return
        lastTry = now
        Thread {
            val (ok, msg) = runCatching { Setup.enableAccessibility(ctx) }.getOrElse { false to tr("无障碍：${it.message}", "Accessibility: ${it.message}") }
            lastResult = msg
            if (ok) {
                healedAt = SystemClock.elapsedRealtime()
                p(ctx).edit().putBoolean("a11y_needs_manual", false).apply()
            } else if (msg.contains(tr("写系统设置失败", "writing system settings failed"))) {
                // The system blocks the write itself (MagicOS / HarmonyOS): respect it, stop trying.
                p(ctx).edit().putBoolean("a11y_blocked", true).apply()
            } else if (msg.contains(tr("没连上", "did not connect"))) {
                // Written but never bound: the system recorded the service as crashed (a force-stop or a
                // crash while it was bound). Re-adding the entry does not clear that list — only the user
                // toggling it in Settings does. Never lock this as blocked: the home page offers a one-tap
                // shortcut to the accessibility settings instead of giving up silently.
                p(ctx).edit().putBoolean("a11y_needs_manual", true).apply()
            }
            AppLogger.info("A11yHeal", "$why: $msg")
        }.apply { isDaemon = true; name = "a11y-heal" }.start()
    }
}
