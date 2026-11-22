package com.kilon.agentvscreen.host

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import com.kilon.agentvscreen.host.AppLogger
import rikka.shizuku.Shizuku
import rikka.sui.Sui
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * [T-android-privileged-backend] Shizuku-protocol privileged-execution backend.
 *
 * Three providers speak the SAME Shizuku binder protocol and push their binder
 * into the SAME client-side [rikka.shizuku.ShizukuProvider]:
 *   • official Shizuku manager (`moe.shizuku.privileged.api`, RikkaApps) — APK
 *   • AXManager (`frb.axeron.manager`, Axeron) — APK
 *   • Sui (RikkaApps) — a Magisk/KernelSU **module**, usually NO APK at all
 *
 * The `rikka.shizuku.Shizuku` singleton is therefore a single slot, owned by
 * whichever provider the user authorized — the SDK and this class are unaware
 * of (and indifferent to) which one is running. The earlier two-backend split
 * confused detection because both `ShizukuBackend` and `AxeronBackend`
 * reported state off the same shared singleton; that abstraction has been
 * removed and must NOT come back for Sui either.
 *
 * [T-android-sui-support] (GH#110 / GH#97) Detection is **binder-first**: a
 * live binder means we are usable, full stop. The installed-package scan is
 * only consulted when the binder is DOWN, to explain why and pick the right
 * install guidance. The previous package-first order hard-blocked Sui: Sui
 * ships no APK, so the whitelist scan returned "not installed" and
 * short-circuited before `pingBinder()` was ever called — a live, authorized
 * Sui rendered as "Manager Not Installed".
 *
 * No Sui-specific dependency or init is needed: `dev.rikka.shizuku:provider`
 * calls `Sui.init(packageName)` automatically from `ShizukuProvider.onCreate`
 * (verified in the 13.1.5 bytecode), and `rikka.sui.Sui` ships inside
 * `dev.rikka.shizuku:api`. [Sui.isSui] is used for LABELS ONLY — never for
 * state decisions (there is no supported way to identify the binder's sender;
 * see the reverted probe in 09f12761).
 */
class ShizukuBackend(private val appContext: Context) {

    /**
     * Which permission request is current and whether Shizuku denied it. A denial alone does not say why: Shizuku
     * sends the same one for the user's Deny and for "adb permission limited" (the maker took the grant right from
     * adb), so the UI names both. Each request sends its own code (Shizuku hands it back), so a late answer to an
     * older request is not taken as the current one's; a new binder ends the current request and its checks too.
     */
    class Attempts {
        @Volatile var current = 0
            private set
        @Volatile private var deniedIn = 0
        @Synchronized fun start(): Int { deniedIn = 0; return ++current }
        fun codeOf(id: Int): Int = PERMISSION_REQUEST_CODE + id
        /** False when the answer belongs to another request (or another app's code) and was ignored. */
        @Synchronized fun answered(requestCode: Int, granted: Boolean): Boolean {
            if (current == 0 || requestCode != codeOf(current)) return false
            deniedIn = if (granted) 0 else current
            return true
        }
        @Synchronized fun invalidate() { deniedIn = 0; current++ }
        val lastDenied: Boolean get() = current > 0 && deniedIn == current
    }

    @Volatile private var onStateChanged: (() -> Unit)? = null
    private var listenersRegistered = false
    val attempts = Attempts()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        AppLogger.info(TAG, "binder received")
        attempts.invalidate()
        onStateChanged?.invoke()
    }
    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        AppLogger.warning(TAG, "binder died")
        onStateChanged?.invoke()
    }
    private val permissionResultListener =
        Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
            val granted = grantResult == PackageManager.PERMISSION_GRANTED
            val mine = attempts.answered(requestCode, granted)
            AppLogger.info(TAG, "permission result code=$requestCode grant=$granted attempt=${attempts.current} current=$mine")
            onStateChanged?.invoke()
        }

    /**
     * Any known Shizuku-protocol **manager APK** installed?
     *
     * [T-android-sui-support] NOTE: this is NOT "is a privileged backend
     * available" — Sui provides the binder with no APK to find. Never gate
     * binder work on this; use [snapshot] / [isPermissionGranted] instead.
     * It survives only to drive install guidance and the "open manager app"
     * affordance.
     */
    fun isInstalled(): Boolean = installedManagerPackage() != null

    /**
     * [T-android-sui-support] True when the live binder is provided by Sui
     * (root module) rather than a manager APK. Display-only: it picks labels
     * and hides the meaningless "Open Manager App" row for Sui users. It must
     * never influence state resolution — see [decideState].
     */
    fun isSui(): Boolean = runCatching { Sui.isSui() }.getOrDefault(false)

    /**
     * The first installed Shizuku-protocol manager package (in preference
     * order: official Shizuku, then AXManager), or null if none installed.
     * Used to drive "open the manager app" actions.
     */
    fun installedManagerPackage(): String? = SHIZUKU_COMPATIBLE_PACKAGES.firstOrNull { pkg ->
        runCatching { appContext.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
    }

    /**
     * r45: pingBinder with a timeout, on a fresh thread. A frozen Shizuku server blocks a plain pingBinder() forever,
     * and the old code called it before every command (via refreshStatus) and before every shell run. A timeout counts
     * as "not alive" — the caller gets a clear error instead of hanging.
     */
    fun pingTimed(timeoutMs: Long = 5_000L): Boolean {
        val done = CountDownLatch(1)
        val alive = AtomicBoolean(false)
        Thread({
            try { alive.set(Shizuku.pingBinder()) } catch (t: Throwable) { /* dead */ } finally { done.countDown() }
        }, "shizuku-ping").apply { isDaemon = true }.start()
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            AppLogger.warning(TAG, "pingBinder timed out after ${timeoutMs}ms (Shizuku server not responding)")
            return false
        }
        return alive.get()
    }

    fun isBinderAlive(): Boolean =
        runCatching { pingTimed() }.getOrDefault(false)

    fun isPermissionGranted(): Boolean = runCatching {
        // The ping just proved the server answers, so the permission check after it should return promptly.
        pingTimed() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun snapshot(): ShizukuManager.Snapshot {
        val binderAlive = isBinderAlive()
        val state = decideState(
            binderAlive = binderAlive,
            permissionGranted = binderAlive && isPermissionGranted(),
            managerApkInstalled = isInstalled(),
        )
        return if (state == ShizukuManager.State.READY) {
            ShizukuManager.Snapshot(state, versionOrUnknown(), uidOrUnknown(), isSui())
        } else {
            ShizukuManager.Snapshot(state, isSui = binderAlive && isSui(),
                denied = state == ShizukuManager.State.NEED_PERMISSION && attempts.lastDenied)
        }
    }

    fun registerListeners(onStateChanged: () -> Unit) {
        this.onStateChanged = onStateChanged
        if (listenersRegistered) return
        listenersRegistered = true
        runCatching {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            Shizuku.addRequestPermissionResultListener(permissionResultListener)
        }.onFailure { AppLogger.warning(TAG, "init listeners failed: ${it.message}") }
    }

    fun refresh() {
        onStateChanged?.invoke()
    }

    /**
     * Launch the installed manager app (Shizuku or AXManager — whichever is
     * present). If none is installed, falls back to an install page; the
     * multi-option install UI lives in the Settings screen, not here.
     *
     * [T-android-sui-support] Sui has no launcher activity, so a Sui user has
     * nothing to open. The UI hides this row for them; if it is somehow
     * reached anyway, send them to Sui's page rather than to Shizuku's
     * releases (which is what the old unconditional fallback did).
     */
    fun openManagerApp(context: Context) {
        val pkg = installedManagerPackage()
        if (pkg != null) {
            val intent = context.packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { context.startActivity(intent) }
                    .onFailure { AppLogger.warning(TAG, "open $pkg failed: ${it.message}") }
                return
            }
        }
        openInstallPage(context, if (isSui()) SUI_GITHUB_URL else SHIZUKU_GITHUB_URL)
    }

    fun openInstallPage(context: Context, url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
            .onFailure { AppLogger.warning(TAG, "openInstallPage($url) failed: ${it.message}") }
    }

    /**
     * 1.3.0-beta18 (GitHub #2): what a tap on "grant" did. It used to return silently when Shizuku had the app on
     * record as denied, so the button "did nothing" and the user had to revoke it in Shizuku before asking again.
     */
    enum class Ask { NO_BINDER, ALREADY, ASKED, DENIED_ON_RECORD, FAILED }

    fun requestPermission(): Ask {
        // r45: pingTimed() — a dead binder must time out instead of blocking this call forever.
        if (!pingTimed()) return Ask.NO_BINDER
        if (isPermissionGranted()) return Ask.ALREADY
        if (runCatching { Shizuku.shouldShowRequestPermissionRationale() }.getOrDefault(false)) {
            AppLogger.warning(TAG, "Shizuku has this app on record as denied: it shows no dialog; the user allows it in the manager app")
            return Ask.DENIED_ON_RECORD
        }
        val id = attempts.start()
        return runCatching { Shizuku.requestPermission(attempts.codeOf(id)); Ask.ASKED }
            .getOrElse { AppLogger.warning(TAG, "requestPermission failed: ${it.message}"); Ask.FAILED }
    }

    private fun versionOrUnknown(): Int = runCatching {
        if (pingTimed()) Shizuku.getVersion() else -1
    }.getOrDefault(-1)

    private fun uidOrUnknown(): Int = runCatching {
        if (pingTimed()) Shizuku.getUid() else -1
    }.getOrDefault(-1)

    /**
     * Shizuku-SDK process path (reflection-based `Shizuku.newProcess`).
     */
    fun runProcess(
        argv: Array<String>,
        env: Array<String>?,
        cwd: String?,
        timeoutMs: Long,
    ): ShizukuManager.ProcessResult {
        if (!isPermissionGranted()) {
            return ShizukuManager.ProcessResult(
                exitCode = 126,
                stdout = "",
                stderr = "shizuku not ready (state=${snapshot().state})",
            )
        }
        // Shizuku.newProcess is hidden API — invoke via reflection so the
        // SDK upgrade doesn't break our build. The returned object is a
        // ShizukuRemoteProcess which extends java.lang.Process, so once we
        // have it, treat it as a normal Process and use the standard
        // waitFor(timeout, unit) API rather than reflecting for a
        // nonexistent `waitForTimeout` method (T343).
        // r45: newProcess is a Binder call; a frozen server blocks it forever, so it runs on a fresh thread with a
        // timeout (the 8 s waitFor below only covers a process that already exists).
        val procAny = newProcessTimed(argv, env, cwd).getOrElse {
            AppLogger.warning(TAG, "newProcess failed: ${it.message}")
            return ShizukuManager.ProcessResult(1, "", "shizuku.newProcess unavailable: ${it.message}")
        } ?: return ShizukuManager.ProcessResult(1, "", "shizuku.newProcess returned null")

        val proc = procAny as? Process ?: return ShizukuManager.ProcessResult(
            1, "",
            "shizuku.newProcess returned ${procAny.javaClass.name}, not java.lang.Process",
        )

        val out = StringBuilder()
        val err = StringBuilder()
        val outThread = Thread {
            runCatching {
                proc.inputStream?.use { s ->
                    s.bufferedReader().forEachLine { l -> synchronized(out) { out.append(l).append('\n') } }
                }
            }
        }
        val errThread = Thread {
            runCatching {
                proc.errorStream?.use { s ->
                    s.bufferedReader().forEachLine { l -> synchronized(err) { err.append(l).append('\n') } }
                }
            }
        }
        outThread.isDaemon = true; errThread.isDaemon = true
        // GH #453: per-name thread counts.
        outThread.name = "shizuku-out"; errThread.name = "shizuku-err"
        outThread.start(); errThread.start()

        val exited: Boolean = try {
            proc.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (t: IllegalArgumentException) {
            // Shizuku v13 RemoteProcess.waitFor(long, TimeUnit) throws IllegalArgumentException
            // ("process hasn't exited") instead of blocking. Fall back to polling exitValue().
            if (polledFallbackLogged.compareAndSet(false, true)) {
                AppLogger.info(TAG, "Using polling fallback for Shizuku RemoteProcess.waitFor(timeout) (SDK bug — throws IllegalArgumentException)")
            }
            val deadline = System.currentTimeMillis() + timeoutMs
            var done = false
            while (System.currentTimeMillis() < deadline) {
                try {
                    proc.exitValue()
                    done = true
                    break
                } catch (e: RuntimeException) {
                    if (e is IllegalThreadStateException || e is IllegalArgumentException) {
                        Thread.sleep(50)
                    } else {
                        throw e
                    }
                }
            }
            done
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "Shizuku waitFor failed: type=${t::class.java.name} msg=${t.message}")
            com.kilon.agentvscreen.host.quiet("ShizukuBackend:267") { proc.destroy() }
            com.kilon.agentvscreen.host.quiet("ShizukuBackend:268") { outThread.join(2000) }
            com.kilon.agentvscreen.host.quiet("ShizukuBackend:269") { errThread.join(2000) }
            return ShizukuManager.ProcessResult(1, out.toString().trimEnd('\n'), (err.toString().trimEnd('\n') + "\nwaitFor failed: ${t.message}").trim())
        }

        if (!exited) {
            com.kilon.agentvscreen.host.quiet("ShizukuBackend:274") { proc.destroy() }
            com.kilon.agentvscreen.host.quiet("ShizukuBackend:275") { outThread.join(2000) }
            com.kilon.agentvscreen.host.quiet("ShizukuBackend:276") { errThread.join(2000) }
            // Linux convention: 124 = command timed out (coreutils `timeout`).
            return ShizukuManager.ProcessResult(124, out.toString().trimEnd('\n'), err.toString().trimEnd('\n'))
        }

        com.kilon.agentvscreen.host.quiet("ShizukuBackend:281") { outThread.join(2000) }
        com.kilon.agentvscreen.host.quiet("ShizukuBackend:282") { errThread.join(2000) }
        val rc = runCatching { proc.exitValue() }.getOrDefault(-1)
        return ShizukuManager.ProcessResult(rc, out.toString().trimEnd('\n'), err.toString().trimEnd('\n'))
    }

    /** r45: Shizuku.newProcess on a fresh thread, with a timeout. A timeout is a failure, not a null — the caller logs it and the command fails with a clear error instead of hanging. */
    private fun newProcessTimed(argv: Array<String>, env: Array<String>?, cwd: String?, timeoutMs: Long = 10_000L): Result<Any?> {
        val done = CountDownLatch(1)
        val res = AtomicReference<Result<Any?>?>()
        Thread({
            try {
                val m = Shizuku::class.java.getDeclaredMethod(
                    "newProcess",
                    Array<String>::class.java,
                    Array<String>::class.java,
                    String::class.java,
                )
                m.isAccessible = true
                res.set(Result.success(m.invoke(null, argv, env, cwd)))
            } catch (t: Throwable) {
                res.set(Result.failure(t))
            } finally { done.countDown() }
        }, "shizuku-newprocess").apply { isDaemon = true }.start()
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            AppLogger.warning(TAG, "newProcess timed out after ${timeoutMs}ms (Shizuku server not responding)")
            return Result.failure(IllegalStateException("shizuku.newProcess timed out after ${timeoutMs}ms"))
        }
        return res.get() ?: Result.failure(IllegalStateException("shizuku.newProcess: no result"))
    }

    companion object {
        private const val TAG = "ShizukuBackend"

        // Known Shizuku-protocol manager packages. Order = UI install-preference.
        // Sui is deliberately ABSENT: it is a root module, not an APK, and
        // adding a package name for it would re-create the very whitelist trap
        // that hid it (GH#110). Sui is detected purely by its live binder.
        const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
        const val AXMANAGER_PACKAGE = "frb.axeron.manager"
        val SHIZUKU_COMPATIBLE_PACKAGES = listOf(SHIZUKU_PACKAGE, AXMANAGER_PACKAGE)

        const val SHIZUKU_GITHUB_URL = "https://github.com/RikkaApps/Shizuku/releases"
        const val AXMANAGER_GITHUB_URL = "https://github.com/fahrez182/AxManager/releases"
        const val SUI_GITHUB_URL = "https://github.com/RikkaApps/Sui"

        const val PERMISSION_REQUEST_CODE = 0xC1A4D

        private val polledFallbackLogged = AtomicBoolean(false)


        /**
         * [T-android-sui-support] The binder-first decision, extracted as a
         * PURE function so every branch is unit-testable without a device, a
         * root environment, or the Shizuku SDK's static singleton
         * (ShizukuBackendStateTest).
         *
         * Order matters and is the whole fix:
         *   1. binder alive?  → READY / NEED_PERMISSION. Whoever supplied it
         *      (Shizuku APK, AXManager, or Sui module) is irrelevant — this is
         *      what makes Sui work with zero Sui-specific plumbing.
         *   2. binder down, manager APK present → NOT_RUNNING ("start it").
         *   3. binder down, nothing installed → NOT_INSTALLED ("install one").
         *
         * NOT_INSTALLED keeps its name for call-site compatibility but now
         * means "no binder AND no manager APK" — with Sui in the picture it
         * reads as "no privileged backend available", which is what the UI
         * copy says.
         */
        fun decideState(
            binderAlive: Boolean,
            permissionGranted: Boolean,
            managerApkInstalled: Boolean,
        ): ShizukuManager.State = when {
            binderAlive && permissionGranted -> ShizukuManager.State.READY
            binderAlive -> ShizukuManager.State.NEED_PERMISSION
            managerApkInstalled -> ShizukuManager.State.NOT_RUNNING
            else -> ShizukuManager.State.NOT_INSTALLED
        }
    }
}
