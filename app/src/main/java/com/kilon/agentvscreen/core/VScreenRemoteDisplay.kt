package com.kilon.agentvscreen.core

import com.kilon.agentvscreen.tr

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import android.view.Surface
import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.host.ShizukuManager
import com.kilon.agentvscreen.host.AppLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import rikka.shizuku.Shizuku

/**
 * [vscreen] App-side client of [VScreenDisplayService]: binds the Shizuku
 * UserService once and asks it to create / release shell-owned virtual
 * displays that render into the app's own Surface.
 */
internal object VScreenRemoteDisplay {
    private const val TAG = "VScreenRemoteDisplay"
    private const val BIND_TIMEOUT_S = 10L
    /** Longest one transact to the screen service may take (a normal one is well under 100 ms). */
    private const val TRANSACT_TIMEOUT_MS = 10_000L
    /** Longest a binder liveness ping may take. */
    private const val PING_TIMEOUT_MS = 3_000L
    /** A transact that took this long still finished, but slowly — worth a log line. */
    private const val TRANSACT_SLOW_MS = 5_000L

    /**
     * r45: run [block] on a fresh thread, wait at most [timeoutMs]. A transact to a frozen service blocks forever in
     * Binder; the timeout turns it into a failure instead of hanging the caller (and the pool worker) for good. The
     * orphaned thread holds no shared resource — its Parcels are its own. Note the ambiguity a timeout leaves: the
     * service may still have carried the call out (a release that timed out may already have released). Retries must
     * be idempotent — release, removeTask, moveTask and the read-only calls are; a create that timed out may have
     * left an orphaned display behind (the service's orphan reaper cleans it up).
     */
    private fun <T> timed(timeoutMs: Long, what: String, block: () -> T): Result<T> {
        val res = AtomicReference<Result<T>?>()
        val done = CountDownLatch(1)
        Thread({
            try { res.set(runCatching(block)) } catch (t: Throwable) { res.set(Result.failure(t)) } finally { done.countDown() }
        }, "vscreen-transact").apply { isDaemon = true }.start()
        val t0 = SystemClock.elapsedRealtime()
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            val took = SystemClock.elapsedRealtime() - t0
            AppLogger.warning(TAG, "$what timed out after ${took}ms (service not responding; it may still complete on the service side)")
            return Result.failure(IllegalStateException(tr("副屏服务 ${took / 1000} 秒没有响应（可能被系统冻结）", "The screen service did not respond for ${took / 1000} s (it may be frozen by the system)")))
        }
        val took = SystemClock.elapsedRealtime() - t0
        if (took > TRANSACT_SLOW_MS) AppLogger.warning(TAG, "$what took ${took}ms (slow)")
        return res.get() ?: Result.failure(IllegalStateException("$what: no result"))
    }

    /** r45: pingBinder with a timeout. A frozen service blocks a plain isBinderAlive forever. */
    private fun binderAlive(b: IBinder?, timeoutMs: Long = PING_TIMEOUT_MS): Boolean {
        if (b == null) return false
        val done = CountDownLatch(1)
        val alive = AtomicBoolean(false)
        Thread({
            try { alive.set(b.pingBinder()) } catch (t: Throwable) { /* dead */ } finally { done.countDown() }
        }, "vscreen-ping").apply { isDaemon = true }.start()
        if (!done.await(timeoutMs, TimeUnit.MILLISECONDS)) {
            AppLogger.warning(TAG, "service pingBinder timed out after ${timeoutMs}ms")
            return false
        }
        return alive.get()
    }

    data class Created(val displayId: Int, val flags: Int) {
        val trusted: Boolean get() = VScreenDisplayService.isTrusted(flags)
    }

    @Volatile private var binder: IBinder? = null
    private val lock = Any()

    private val args by lazy {
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, VScreenDisplayService::class.java.name))
            .daemon(true) // survive an app restart so the next process can adopt the screen
            .processNameSuffix("vscreen")
            .debuggable(false)
            .version(BuildConfig.VERSION_CODE * 100 + VScreenDisplayService.PROTOCOL)
    }

    private val conn = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            // r45: bounded ping (this runs on the main thread; a plain pingBinder to a frozen service would ANR).
            binder = service?.takeIf { binderAlive(it) }
            latch?.countDown()
        }
        override fun onServiceDisconnected(name: ComponentName?) { binder = null }
    }
    @Volatile private var latch: CountDownLatch? = null

    /** A live service binder, binding (and waiting) if needed. Never on the main thread. */
    private fun service(): Result<IBinder> {
        binder?.takeIf { binderAlive(it) }?.let { return Result.success(it) }
        if (!ShizukuManager.isReady()) return Result.failure(IllegalStateException(tr("Shizuku 未就绪", "Shizuku not ready")))
        synchronized(lock) {
            binder?.takeIf { binderAlive(it) }?.let { return Result.success(it) }
            val l = CountDownLatch(1).also { latch = it }
            try {
                Shizuku.bindUserService(args, conn)
            } catch (t: Throwable) {
                return Result.failure(IllegalStateException(tr("Shizuku 不支持 UserService：${t.message}", "Shizuku does not support UserService: ${t.message}")))
            }
            if (!l.await(BIND_TIMEOUT_S, TimeUnit.SECONDS)) {
                return Result.failure(IllegalStateException(tr("等待 Shizuku 启动副屏服务超时", "Timed out waiting for Shizuku to start the screen service")))
            }
            return binder?.let { Result.success(it) }
                ?: Result.failure(IllegalStateException(tr("副屏服务连接失败", "Connecting to the screen service failed")))
        }
    }

    fun create(name: String, width: Int, height: Int, dpi: Int, surface: Surface, screenOffKeep: Boolean = true): Result<Created> {
        val b = service().getOrElse { e -> return Result.failure<Created>(e).also { AppLogger.warning(TAG, "remote create failed: ${e.message}") } }
        return timed(TRANSACT_TIMEOUT_MS, "create display") {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
                data.writeString(name); data.writeInt(width); data.writeInt(height); data.writeInt(dpi)
                data.writeInt(0) // 0 = let the service walk its flag ladder
                // Protocol 11: whether the new display keeps a screen-off wake lock (see VScreenDisplayService).
                data.writeInt(if (screenOffKeep) 1 else 0)
                surface.writeToParcel(data, 0)
                b.transact(VScreenDisplayService.CREATE, data, reply, 0)
                if (reply.readInt() != 0) error(reply.readString() ?: "unknown error")
                Created(reply.readInt(), reply.readInt())
            } finally { data.recycle(); reply.recycle() }
        }.onFailure { AppLogger.warning(TAG, "remote create failed: ${it.message}") }
    }

    fun release(displayId: Int) {
        val b = binder?.takeIf { binderAlive(it) } ?: return
        // Best effort, and the caller must not wait long for it: 5 s, not the full transact timeout.
        timed(5_000L, "release $displayId") {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
                data.writeInt(displayId)
                b.transact(VScreenDisplayService.RELEASE, data, reply, 0)
            } finally { data.recycle(); reply.recycle() }
        }.onFailure { AppLogger.warning(TAG, "remote release($displayId) failed: ${it.message}") }
    }

    /** Run one call on the service; null when the service is not available. */
    private fun <T> call(code: Int, write: (Parcel) -> Unit, read: (Parcel) -> T): Result<T> {
        val b = service().getOrElse { return Result.failure(it) }
        return timed(TRANSACT_TIMEOUT_MS, "transact $code") {
            val data = Parcel.obtain(); val reply = Parcel.obtain()
            try {
                data.writeInterfaceToken(VScreenDisplayService.DESCRIPTOR)
                write(data)
                if (!b.transact(code, data, reply, 0)) error(tr("副屏服务不认识这个调用（旧版本服务仍在运行，重连后会更新）", "The screen service does not know this call (an old service version is still running; it updates after a reconnect)"))
                if (reply.readInt() != 0) error(reply.readString() ?: "unknown error")
                read(reply)
            } finally { data.recycle(); reply.recycle() }
        }
    }

    /** Key event with the display id set (reaches a display with its own focus). */
    fun injectKey(displayId: Int, keyCode: Int, longPress: Boolean = false): Result<Boolean> =
        call(VScreenDisplayService.INJECT_KEY, { it.writeInt(displayId); it.writeInt(keyCode); it.writeInt(if (longPress) 1 else 0) }) { it.readInt() != 0 }

    /** 1.2: characters typed as key events on [displayId] (ASCII; see VScreenDisplayService.injectText). */
    fun injectText(displayId: Int, text: String): Result<Boolean> =
        call(VScreenDisplayService.INJECT_TEXT, { it.writeInt(displayId); it.writeString(text) }) { it.readInt() != 0 }

    /** Finger drag (or tap when start == end) on [displayId]; see VScreenDisplayService.injectDrag. */
    fun injectDrag(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int, holdMs: Int): Result<Boolean> =
        call(VScreenDisplayService.INJECT_DRAG, {
            it.writeInt(displayId); it.writeInt(x1); it.writeInt(y1); it.writeInt(x2); it.writeInt(y2)
            it.writeInt(durationMs); it.writeInt(holdMs)
        }) { it.readInt() != 0 }

    /** (rotation, logicalWidth, logicalHeight) as the system sees [displayId]. */
    fun rotation(displayId: Int): Result<Triple<Int, Int, Int>> =
        call(VScreenDisplayService.ROTATION, { it.writeInt(displayId) }) { Triple(it.readInt(), it.readInt(), it.readInt()) }

    /** Held finger: op 0 down, 1 move, 2 up (x,y < 0 keeps the last point), 3 lift all. Returns (injected, fingers still down). */
    fun pointer(displayId: Int, op: Int, pointerId: Int, x: Float, y: Float): Result<Pair<Boolean, Int>> =
        call(VScreenDisplayService.POINTER, {
            it.writeInt(displayId); it.writeInt(op); it.writeInt(pointerId); it.writeFloat(x); it.writeFloat(y)
        }) { (it.readInt() != 0) to it.readInt() }

    /** Remove [taskId] only if the system says it is on [displayId] (the service checks; refuses otherwise). */
    fun removeTask(taskId: Int, displayId: Int): Result<Boolean> =
        call(VScreenDisplayService.REMOVE_TASK, { it.writeInt(taskId); it.writeInt(displayId) }) { it.readInt() != 0 }

    /** A task on a display, as ActivityTaskManager reports it (display-scoped by the system, not by parsing dumpsys). */
    data class DisplayTask(val taskId: Int, val component: String?, val visible: Boolean, val activityType: Int, val rootTop: String?) {
        val pkg: String? get() = (rootTop ?: component)?.substringBefore('/')
        /** HOME = 2, RECENTS = 3, ASSISTANT = 4, DREAM = 5. */
        val isAppTask: Boolean get() = activityType !in setOf(2, 3, 4, 5)
    }

    fun isConnected(): Boolean = binderAlive(binder)

    /** [listTasks] only when the service is already connected (never binds or waits); null otherwise. */
    fun listTasksIfConnected(displayId: Int): List<DisplayTask>? {
        if (!binderAlive(binder)) return null
        return listTasks(displayId).getOrNull()
    }

    fun listTasks(displayId: Int): Result<List<DisplayTask>> = call(VScreenDisplayService.LIST_TASKS, { it.writeInt(displayId) }) { r ->
        val n = r.readInt()
        (0 until n).map { DisplayTask(r.readInt(), r.readString(), r.readInt() != 0, r.readInt(), r.readString()) }
    }

    /** (displayId, taskId) that has the system's input focus. */
    fun focused(): Result<Pair<Int, Int>> = call(VScreenDisplayService.FOCUSED, {}) { it.readInt() to it.readInt() }

    fun focusTask(taskId: Int): Result<Boolean> = call(VScreenDisplayService.FOCUS_TASK, { it.writeInt(taskId) }) { it.readInt() != 0 }

    /** v22 接力: (moved, system's message when not). */
    fun moveTask(taskId: Int, displayId: Int): Result<Pair<Boolean, String>> =
        call(VScreenDisplayService.MOVE_TASK, { it.writeInt(taskId); it.writeInt(displayId) }) { (it.readInt() != 0) to (it.readString() ?: "") }

    data class Existing(val displayId: Int, val width: Int, val height: Int, val dpi: Int, val flags: Int)

    /** Displays the service still holds (e.g. left by a previous app process). */
    fun list(): Result<List<Existing>> = call(VScreenDisplayService.LIST, {}) { r ->
        val n = r.readInt()
        (0 until n).map { Existing(r.readInt(), r.readInt(), r.readInt(), r.readInt(), r.readInt()) }
    }

    fun setSurface(displayId: Int, surface: Surface): Result<Unit> =
        call(VScreenDisplayService.SET_SURFACE, { it.writeInt(displayId); surface.writeToParcel(it, 0) }) { }

    /** v21: take the surface away (display off) to save power; setSurface brings it back. */
    fun park(displayId: Int): Result<Unit> =
        call(VScreenDisplayService.PARK, { it.writeInt(displayId) }) { }

    /** Honor/Huawei APS state of [pkg] (available=false on other phones). */
    fun apsGet(pkg: String): Result<VScreenDownscale.Aps> =
        call(VScreenDisplayService.APS, { it.writeInt(0); it.writeString(pkg) }) { r ->
            if (r.readInt() == 0) VScreenDownscale.Aps(false, 1f, 1f, false, null, r.readString())
            else VScreenDownscale.Aps(true, r.readFloat(), r.readFloat(), r.readInt() != 0, r.createByteArray())
        }

    /** Set the APS ratio; (return code, ratio read back). */
    fun apsSet(pkg: String, ratio: Float): Result<Pair<Int, Float>> =
        call(VScreenDisplayService.APS, { it.writeInt(1); it.writeString(pkg); it.writeFloat(ratio) }) { it.readInt() to it.readFloat() }

    /** Put the saved APS entry back (or delete ours when there was none); returns the ratio read back. */
    data class ApsRestored(val readBack: Float, val hasEntry: Boolean, val detail: String)

    fun apsRestore(pkg: String, hadEntry: Boolean, entry: ByteArray?, ratio: Float): Result<ApsRestored> =
        call(VScreenDisplayService.APS, {
            it.writeInt(2); it.writeString(pkg); it.writeInt(if (hadEntry) 1 else 0); it.writeByteArray(entry); it.writeFloat(ratio)
        }) { ApsRestored(it.readFloat(), it.readInt() != 0, it.readString() ?: "") }

    /** Keep-alive for the orphan reaper; cheap, fire-and-forget. */
    fun touch(displayId: Int) {
        if (!binderAlive(binder)) return
        call(VScreenDisplayService.TOUCH, { it.writeInt(displayId) }) { }
    }
}
