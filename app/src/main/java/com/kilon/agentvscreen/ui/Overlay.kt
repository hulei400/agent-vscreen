package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.tr

import androidx.compose.foundation.border
import androidx.compose.ui.draw.shadow
import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.OpenInFull
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.kilon.agentvscreen.api.Turns
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenTerminal
import com.kilon.agentvscreen.host.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The floating window over every app (a system overlay), so the user sees the background screen whatever agent app is
 * in front. The picture fills a rounded card edge to edge, inside a breathing border whose colour says who has the
 * screen (rainbow: the AI acts; blue: it waits; green: you drive; red: you stopped it; grey: idle).
 *
 * Gestures (handled on raw screen coordinates by [GestureFrame], so moving the window never jitters):
 *  - one finger drags the window, and it keeps gliding when you let go of it moving (inertia);
 *  - a fling only moves it (1.3.0-beta16); the size changes by pinching only;
 *  - let go (or fling) with half of it past the left / right edge: it slides on into that edge until a tab-wide strip
 *    shows, and the tab takes that very strip (1.3.0-beta17); tap the tab and the window slides back out; pull the tab
 *    inward and the window opens under the finger;
 *  - two fingers pinch to resize, anchored at the point between them (120 dp … 85 % of the screen);
 *  - hold one finger still for 0.5 s to take over (a ring fills); a light tap only shows these hints; the window is
 *    view-only by default, never operable by accident. (v18: the “接管” pill is gone; hold is the way now, and
 *    “交还 AI” hands back.)
 *  - while you hold the screen, taps / swipes / long presses on the picture go to the background screen, and you move
 *    the window by its title pill. Tap an input box on the picture and the system keyboard pops up: a hidden sink
 *    in this window carries the typing to the background screen's focused field (see [ImeBridge]).
 *  - the “终端” pill at the top right switches the picture to the terminal: every `vscreen shell` run is listed
 *    there in full (command, stdout, stderr, exit code), newest at the bottom, and the log scrolls. The terminal
 *    is read-only here; commands are run by the agent (`vscreen shell`), not typed in the window.
 * Buttons (1.1.6: one job per place, so a quick double tap can never stop the AI by accident):
 *  - top right: ⤢ full screen and × close. × is always there and always one tap: while the AI works it only hides the
 *    window for this turn (the AI goes on; the notification can bring it back); when nothing runs it closes the screen.
 *    Closing never stops or locks the AI.
 *  - bottom centre, only while you hold the screen or the AI is stopped: the green 交还 AI pill (or the "已停止 AI"
 *    notification, or the app's home). (v18: the red “停止 AI” pill is gone from the window; stop from the
 *    app's home or the full-screen page.)
 * While our own full-screen page is in front, the window steps aside (the page already shows the same picture).
 */
object Overlay {
    private const val PREFS = "agent_vscreen_overlay"
    private var ctx: Context? = null
    private var root: GestureFrame? = null
    /** The window manager the window lives in, and whether that is the accessibility service's (see [show]). */
    private var hostWm: WindowManager? = null
    @Volatile var hostedByA11y = false; private set
    private var owner: OverlayOwner? = null
    internal var lp: WindowManager.LayoutParams? = null
    private var job: Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // UI state, main thread only.
    internal val widthPx = mutableFloatStateOf(0f)
    internal val docked = mutableStateOf(false)
    internal val dockRight = mutableStateOf(true)

    init {
        // r46: the shell pop decision lives in BackgroundOperation (core) and needs to know
        // whether the window is folded into its edge tab; the fold state lives here, so hand
        // it over as a provider instead of letting core import ui (same pattern as
        // VScreenRules.hostFactory). Unset until this object loads = not folded.
        BackgroundOperation.dockedProvider = { docked.value }
    }
    internal val hold = mutableFloatStateOf(0f)
    internal val holdAt = mutableStateOf(Offset.Zero)
    internal val hintAt = mutableLongStateOf(0L)
    internal val closeArmedAt = mutableLongStateOf(0L)
    internal val frameRect = RectF()
    internal val pillRect = RectF()
    internal val controls = HashMap<String, RectF>()
    /** Regions whose drag scrolls their content instead of moving the window (the terminal log). */
    internal val scrollControls = HashMap<String, RectF>()
    /** The keyboard bridge (hidden input sink); created with the window, gone with it. */
    private var imeBridge: ImeBridge? = null
    /** 1.3.0-beta16: what the current touch is, said by the picture layer (see GestureFrame). */
    @Volatile internal var pictureDown = false
    @Volatile internal var pictureLive = false
    @Volatile internal var pictureHeld = false
    @Volatile internal var frameClaimed = false

    /** A question card hangs below the window, or above it when the window sits too low for it. */
    /**
     * 1.4.0-beta3: the window and its question card are ONE group for placement. beta2 placed the window by the
     * picture's height alone (opening from the tab, and the 190 dp guess that chose above / below), so the card hung
     * past the bottom edge and a later settle() jumped the whole thing up. Now the card is measured as it lays out
     * ([cardH]), it always hangs below the picture, and every resting place (opening, release, a card arriving) keeps
     * the whole group on screen; the window glides up in step with the card growing in.
     */
    internal val cardH = androidx.compose.runtime.mutableFloatStateOf(0f)
    internal val cardShown = androidx.compose.runtime.mutableStateOf(false)
    private const val CARD_GUESS = 190f
    /** The group's height when it comes to rest: picture (with its frame margin) plus the card and the gap. */
    internal fun groupH(): Float {
        val pic = (widthPx.floatValue + 2 * pad()) / aspect()
        return pic + if (cardShown.value) (cardH.floatValue.takeIf { it > 0f } ?: dp(CARD_GUESS)) + dp(6f) else 0f
    }
    /** Keep the whole group on screen: glide up (or down) only as far as needed, in [ms] (the card's grow time). */
    internal fun fitGroup(ms: Long = CARD_MS) {
        val l = lp ?: return
        if (docked.value) return
        val sh = screenH().toFloat(); val h = groupH()
        val lo = topMargin().coerceAtMost(sh - h)
        val hi = (sh - h - bottomMargin()).coerceAtLeast(0f)
        // The group (picture + card) can grow taller than the screen; then lo > hi and
        // coerceIn would throw "Cannot coerce value to an empty range" and crash the process
        // (killed the accessibility service twice on 2026-11-19 08:09/08:11). Clamp the range.
        val y = l.y.toFloat().coerceIn(minOf(lo, hi), maxOf(lo, hi))
        if (y.toInt() != l.y) animateTo(l.x, y.toInt(), ms = ms)
    }
    /** How long the card takes to grow in or out; the window's glide uses the same time and easing. */
    internal const val CARD_MS = 260L

    /** Our full-screen page is in front: it shows the same picture, so the floating window steps aside. */
    /**
     * 1.3.0-beta18: how many of our own full pages are in front (the full screen page, a question's answer page such
     * as pick-a-point / draw-a-box, the lesson review). While any is, the window steps aside: those pages show the
     * screen themselves, and the window and its question card would sit on top of what the user has to touch. A count,
     * not a flag: going from one page to the next starts the new one before the old one stops.
     */
    private val pagesInFront = MutableStateFlow(0)
    fun fullscreenShown(open: Boolean) { pagesInFront.update { (if (open) it + 1 else it - 1).coerceAtLeast(0) } }

    fun attach(c: Context) {
        ctx = c.applicationContext
        job?.cancel()
        job = scope.launch {
            // The window is up while there is a screen, or while a shell command popped it up on the
            // terminal page (no screen needed) — and not while the user hid it with ×, switched the
            // preview off, or the full-screen page is in front.
            combine(BackgroundOperation.state.map { it.showPreview && !it.windowHidden && (it.displayId != null || it.terminalPopAt > 0L) }.distinctUntilChanged(), pagesInFront.map { it > 0 }.distinctUntilChanged()) { up, full -> up to full }
                .distinctUntilChanged().collect { (up, full) ->
                    AppLogger.info("Overlay", when { !up -> "no screen / preview off / closed by the user: hide"; full -> "full-screen page in front: hide"; else -> "screen up: show" })
                    if (up && !full) show() else hide()
                }
        }
    }

    fun detach() { job?.cancel(); job = null; hide() }

    /** Re-check after the overlay permission was granted. */
    fun refresh() { val s = BackgroundOperation.state.value; if (s.showPreview && !s.windowHidden && pagesInFront.value == 0 && (s.displayId != null || s.terminalPopAt > 0L)) show() }

    /**
     * Let the window take keyboard focus (so the keyboard bridge's hidden sink can open the soft keyboard), or
     * back to not focusable. The window is not focusable by default: it floats over the user's apps and must
     * never steal the back key or the keyboard from them. Only the keyboard bridge turns it on, and only while
     * it is open.
     */
    fun setFocusable(on: Boolean) {
        val v = root ?: return; val l = lp ?: return
        val flags = if (on) l.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        else l.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
        if (flags == l.flags) return
        l.flags = flags
        runCatching { hostWm?.updateViewLayout(v, l) }
            .onFailure { AppLogger.warning("Overlay", "setFocusable: ${it.message}") }
    }

    /** A takeover touch just ended: maybe an input box now has focus — check the keyboard bridge. */
    internal fun onTakeoverTouchUp() { imeBridge?.check() }

    /** Close the keyboard bridge (takeover ended, tab left the screen, window going away). */
    internal fun closeImeBridge() { imeBridge?.close() }

    internal fun hitScroll(x: Float, y: Float) = scrollControls.values.any { it.contains(x, y) }

    internal fun hitControl(x: Float, y: Float) = controls.values.any { it.contains(x, y) }

    /** 1.1.6: the × button. One tap, never a lock: see [BackgroundOperation.closeWindow]. */
    fun closeTapped() {
        // 1.4.0-beta11 (DESIGN §4.2): while the user shows a lesson the × folds the window to the edge; it never hands
        // the screen back or ends the lesson (that is 完成 / 不教 on the strip).
        val l = com.kilon.agentvscreen.cmd.Teach.lesson.value
        if (l is com.kilon.agentvscreen.cmd.Lesson.Ready || l is com.kilon.agentvscreen.cmd.Lesson.Recording) {
            dock(dockRight.value)
            ctx?.let { android.widget.Toast.makeText(it, tr("收到边上了，示教还在继续", "Folded to the edge; the lesson goes on"), android.widget.Toast.LENGTH_SHORT).show() }
            return
        }
        val msg = BackgroundOperation.closeWindow()
        ctx?.let { android.widget.Toast.makeText(it, msg, android.widget.Toast.LENGTH_LONG).show() }
    }

    /** The accessibility service came or went: move the window to the better host. Main thread. */
    fun rehost() {
        android.os.Handler(Looper.getMainLooper()).post {
            val want = com.kilon.agentvscreen.host.VScreenAccessibilityService.getInstance() != null
            if (root != null && want != hostedByA11y) { AppLogger.info("Overlay", "rehost (a11y=$want)"); hide(); refresh() }
        }
    }

    private fun prefs() = ctx!!.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun metricsWm(): WindowManager? = hostWm ?: ctx?.getSystemService(WindowManager::class.java)
    internal fun screenW() = runCatching { metricsWm()!!.maximumWindowMetrics.bounds.width() }.getOrNull() ?: ctx?.resources?.displayMetrics?.widthPixels ?: 1080
    internal fun screenH() = runCatching { metricsWm()!!.maximumWindowMetrics.bounds.height() }.getOrNull() ?: ctx?.resources?.displayMetrics?.heightPixels ?: 2400
    internal fun dp(v: Float) = v * (ctx?.resources?.displayMetrics?.density ?: 3f)

    /** Aspect (w / h) of the background screen. */
    internal fun aspect(): Float {
        val s = BackgroundOperation.state.value
        val d = s.ownerSessionId?.let { BackgroundOperation.previewDisplayFor(it) }
        val w = d?.width ?: s.screenWidth; val h = d?.height ?: s.screenHeight
        return if (w > 0 && h > 0) w.toFloat() / h else 9f / 19.5f
    }

    internal fun clampWidth(w: Float): Float {
        val a = aspect()
        // A landscape screen on an upright phone may use almost the whole width: it is short, and anything narrower
        // makes its text unreadable.
        val max = minOf(screenW() * (if (a > 1f) 0.94f else 0.85f), screenH() * 0.85f * a)
        return w.coerceIn(dp(120f).coerceAtMost(max), max)
    }

    /**
     * Where the window lives. Not a copy of Minis (whose preview sat inside its own chat activity): a system overlay of an
     * ordinary app (TYPE_APPLICATION_OVERLAY) is hidden by Android on EVERY display while any window that asks to hide
     * non-system overlays is showing — Settings, the package installer, permission dialogs, many bank apps do — and the
     * agent opens exactly those on the background screen. So the window is an accessibility overlay of our read-only
     * service when it is on (those are never hidden that way), and an app overlay only as the fallback.
     */
    private fun show() {
        val c = ctx ?: return
        if (root != null) return
        val svc = com.kilon.agentvscreen.host.VScreenAccessibilityService.getInstance()
        val a11y = svc != null
        if (!a11y && !Settings.canDrawOverlays(c)) { AppLogger.info("Overlay", "no overlay permission and no accessibility: window not shown"); return }
        val wm: WindowManager = if (svc != null) svc.getSystemService(WindowManager::class.java)
            else runCatching {
                val dm = c.getSystemService(android.hardware.display.DisplayManager::class.java)
                c.createWindowContext(dm.getDisplay(android.view.Display.DEFAULT_DISPLAY), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
                    .getSystemService(WindowManager::class.java)
            }.getOrElse { c.getSystemService(WindowManager::class.java) }
        val o = OverlayOwner().also { owner = it }
        val p = prefs()
        shownLandscape = aspect() > 1f
        widthPx.floatValue = clampWidth(p.getFloat(widthKey(shownLandscape), defaultWidth(shownLandscape)))
        docked.value = p.getBoolean("docked", false)
        dockRight.value = p.getBoolean("dock_right", true)
        val l = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            if (a11y) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = p.getInt("x", (screenW() - widthPx.floatValue - dp(16f)).toInt())
            y = p.getInt("y", (screenH() * 0.18f).toInt())
        }
        if (docked.value) l.x = if (dockRight.value) screenW() - dp(TAB_W).toInt() else 0
        else {
            // 1.2.0: the window opens wholly on screen. The place is remembered for whatever size it had then; a
            // wider window (landscape, or a larger default) used to open partly off the right edge.
            val w = (widthPx.floatValue + dp(14f)).toInt()
            val h = (widthPx.floatValue / aspect() + dp(14f)).toInt()
            l.x = l.x.coerceIn(0, (screenW() - w).coerceAtLeast(0))
            l.y = l.y.coerceIn(topMargin().toInt(), (screenH() - h - bottomMargin().toInt()).coerceAtLeast(topMargin().toInt()))
        }
        lp = l
        val g = GestureFrame(c)
        val v = ComposeView(c).apply {
            setViewTreeLifecycleOwner(o); setViewTreeSavedStateRegistryOwner(o); setViewTreeViewModelStoreOwner(o)
            setContent { OverlayContent() }
        }
        g.setViewTreeLifecycleOwner(o); g.setViewTreeSavedStateRegistryOwner(o); g.setViewTreeViewModelStoreOwner(o)
        g.addView(v, FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        runCatching { wm.addView(g, l); root = g; hostWm = wm; hostedByA11y = a11y }
            .onSuccess {
                imeBridge = ImeBridge(c, g)
                AppLogger.info("Overlay", "window added (${if (a11y) "accessibility overlay" else "app overlay"})")
                // Once laid out its real size is known: keep all of it on screen.
                if (!docked.value) g.post { keepOnScreen() }
            }
            .onFailure { AppLogger.warning("Overlay", "addView: ${it.message}"); o.destroy(); owner = null }
    }

    private fun hide() {
        stopAnim()
        imeBridge?.detach(); imeBridge = null
        val v = root ?: return
        runCatching { hostWm?.removeView(v) }.onFailure { AppLogger.warning("Overlay", "removeView: ${it.message}") }
        placeQueued = false
        root = null; hostWm = null; owner?.destroy(); owner = null
        controls.clear()
        scrollControls.clear()
    }

    // 1.2.0-beta2: the window's width is remembered per orientation of the background screen. One number for both
    // meant a landscape screen opened at the width chosen for a portrait one: a strip a third of the screen wide.
    private var shownLandscape = false
    // 1.2.0: a landscape screen opens at about three quarters of the phone's width (readable, and the app behind it
    // stays usable); the key is new so the larger beta default is not kept.
    private fun widthKey(landscape: Boolean) = if (landscape) "w_landscape" else "w"
    private fun defaultWidth(landscape: Boolean) = if (landscape) minOf(screenW(), screenH()) * 0.76f else minOf(screenW(), screenH()) * 0.34f

    /** The background screen changed between portrait and landscape: use that orientation's own width. */
    internal fun orientationChanged(landscape: Boolean) {
        if (root == null || landscape == shownLandscape) return
        prefs().edit().putFloat(widthKey(shownLandscape), widthPx.floatValue).apply()
        shownLandscape = landscape
        widthPx.floatValue = clampWidth(prefs().getFloat(widthKey(landscape), defaultWidth(landscape)))
        // Moved back wholly on screen, never folded: the window grew, the user did not push it to an edge. The new
        // size is known only after the next layout, so it is checked again then.
        root?.post { if (docked.value) dock(dockRight.value) else keepOnScreen() }
        root?.postDelayed({ if (!docked.value) keepOnScreen() }, 120L)
    }

    /** Move the window the least needed to have all of it on screen. */
    internal fun keepOnScreen() {
        val l = lp ?: return
        val x = l.x.coerceIn(0, (screenW() - winW()).toInt().coerceAtLeast(0))
        val y = l.y.coerceIn(topMargin().toInt(), (screenH() - winH() - bottomMargin()).toInt().coerceAtLeast(topMargin().toInt()))
        if (x != l.x || y != l.y) { place(x, y); save() }
    }

    // 1.2.0-beta2: moving the window is one call to the window manager per display frame, with the newest position.
    // Before, every touch sample (120–240 a second) made that call and waited for it, so the main thread fell behind
    // the finger and the window trailed it.
    private var placeQueued = false
    private val applyPlace = Runnable {
        placeQueued = false
        val v = root ?: return@Runnable; val l = lp ?: return@Runnable
        runCatching { hostWm?.updateViewLayout(v, l) }
    }

    internal fun place(x: Int, y: Int) {
        val v = root ?: return; val l = lp ?: return
        l.x = x; l.y = y.coerceIn(if (docked.value) 0 else -(winH() * 0.6f).toInt(), screenH() - dp(48f).toInt())
        if (!placeQueued) { placeQueued = true; v.postOnAnimation(applyPlace) }
    }

    internal fun save() {
        val l = lp ?: return
        prefs().edit().putFloat(widthKey(shownLandscape), widthPx.floatValue).putInt("x", l.x).putInt("y", l.y)
            .putBoolean("docked", docked.value).putBoolean("dock_right", dockRight.value).apply()
    }

    // ---- Where the window comes to rest (release, fling, rotation). ----
    private const val FOLD = 0.5f      // half the window past a side edge (on release) → fold into a tab
    private const val GLIDE_S = 0.2f   // inertia: where a fling would carry the window (velocity × this)
    private var anim: android.animation.ValueAnimator? = null

    internal fun winW(): Float = root?.width?.takeIf { it > 0 }?.toFloat() ?: (widthPx.floatValue + dp(12f))
    internal fun winH(): Float = root?.height?.takeIf { it > 0 }?.toFloat() ?: (widthPx.floatValue / aspect() + dp(12f))
    private fun topMargin() = dp(36f)
    /** The folded tab (see [DockTab]). */
    internal const val TAB_W = 26f
    internal const val TAB_H = 60f
    private fun bottomMargin() = dp(48f)
    internal fun stopAnim() { anim?.cancel(); anim = null }

    /** Glide to (x, y), optionally changing the width, then run [then] (default: remember the place). */
    private fun animateTo(x: Int, y: Int, width: Float? = null, ms: Long = 240L, then: (() -> Unit)? = null) {
        if (lp == null) return
        stopAnim()
        val l = lp!!
        val x0 = l.x; val y0 = l.y; val w0 = widthPx.floatValue
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = if (animateLook) ms else 0L
        a.interpolator = android.view.animation.DecelerateInterpolator(1.5f)
        a.addUpdateListener {
            val t = it.animatedValue as Float
            if (width != null) widthPx.floatValue = w0 + (width - w0) * t
            place((x0 + (x - x0) * t).roundToInt(), (y0 + (y - y0) * t).roundToInt())
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (anim === a) anim = null
                if (!cancelled) { if (then != null) then() else save() }
            }
        })
        anim = a; a.start()
    }

    /** After a plain release or a rotation: fold if a good part is past an edge, else keep it fully on screen. */
    internal fun settle(fingerY: Float? = null) { val l = lp ?: return; restAt(l.x.toFloat(), l.y.toFloat(), fingerY) }

    /**
     * The window was let go while moving (raw px/s). It glides on; flung toward a corner it shrinks to the smallest size
     * there; a small one flung back toward the middle grows to its earlier size; otherwise it rests like a release.
     */
    internal fun fling(vx: Float, vy: Float, fingerY: Float? = null) {
        val l = lp ?: return
        val px = l.x + vx * GLIDE_S; val py = l.y + vy * GLIDE_S
        // 1.3.0-beta16: a fling only moves the window (it used to shrink it into a corner or grow it back): the size
        // changes by pinching only. Flung half past an edge, it folds there (decided now, at the release).
        restAt(px, py, fingerY?.let { it + vy * GLIDE_S }, vx)
    }

    /**
     * Come to rest with the window's top-left at (px, py): fold past any edge, else glide fully onto the screen.
     * The tab lands where the user let go ([fingerY], centred on the finger), not at the window's middle — otherwise
     * it appears somewhere other than where it was put. Without a finger (rotation) it keeps the window's top.
     */
    private fun restAt(px: Float, py: Float, fingerY: Float? = null, vx: Float = 0f) {
        val sw = screenW().toFloat(); val sh = screenH().toFloat()
        val w = winW(); val h = maxOf(winH(), groupH())
        val side: Boolean? = when {
            px < -w * FOLD -> false
            px + w > sw + w * FOLD -> true
            else -> null
        }
        if (side != null) { foldInto(side, fingerY, vx); return }
        val x = px.coerceIn(0f, (sw - w).coerceAtLeast(0f))
        val y = py.coerceIn(topMargin().coerceAtMost(sh - h), (sh - h - bottomMargin()).coerceAtLeast(0f))
        animateTo(x.toInt(), y.toInt())
    }

    internal fun dock(right: Boolean, y: Int? = null) {
        val l = lp ?: return
        stopAnim()
        docked.value = true; dockRight.value = right
        val ty = (y ?: l.y).coerceIn(topMargin().toInt(), (screenH() - dp(TAB_H) - bottomMargin()).toInt())
        place(if (right) screenW() - dp(TAB_W).toInt() else 0, ty)
        save()
    }

    // ---- The window <-> tab change: one continuous slide at the edge (1.3.0-beta17). ----
    // Before, the window shrank where it was let go (or where a fling left it, still well inside the screen) and the
    // tab then appeared at the edge: a small thing away from the edge that jumped to its final place. Now the window
    // keeps its size and slides on, at the speed it was let go with, until only a tab-wide strip of it shows at the
    // edge; the tab takes over exactly that strip. Opening plays the same slide backwards.
    private fun content(): android.view.View? = root?.getChildAt(0)
    private fun tabScale(): Float = (dp(TAB_W) * 1.6f / winW()).coerceIn(0.08f, 1f)

    /**
     * Near a side edge the window shrinks toward the finger ([p] 0 -> 1 as the finger nears the edge), so by the time it
     * becomes the tab it is already tab-sized right where the finger is: no jump from "big window" to "tab elsewhere".
     */
    internal fun shrinkLive(pivotX: Float, pivotY: Float, p: Float) {
        val v = content() ?: return
        v.animate().cancel()
        val sc = 1f - p * (1f - tabScale())
        v.pivotX = pivotX; v.pivotY = pivotY; v.scaleX = sc; v.scaleY = sc; v.alpha = 1f - 0.4f * p
    }

    internal fun resetShrink() { content()?.let { it.animate().cancel(); it.scaleX = 1f; it.scaleY = 1f; it.alpha = 1f } }
    /** The transparent margin around the window's frame (PAD in [OverlayContent]). */
    private fun pad() = dp(7f)

    /** Where the window's left edge is when only the tab-wide strip of it shows on [right]'s side. */
    private fun stripX(right: Boolean, w: Float): Float = if (right) screenW() - dp(TAB_W) - pad() else -(w - dp(TAB_W) - pad())

    /** A duration whose opening speed matches [v] px/s (DecelerateInterpolator(1) starts at twice the mean speed). */
    private fun slideMs(dist: Float, v: Float): Long =
        if (abs(v) < 1f) 220L else (2f * abs(dist) / abs(v) * 1000f).toLong().coerceIn(120L, 260L)

    /** Released half past a side edge (or flung there): slide into that edge and become the tab there. */
    private fun foldInto(right: Boolean, fingerY: Float?, vx: Float) {
        val l = lp ?: return
        val sh = screenH().toFloat(); val w = winW(); val h = winH(); val tabH = dp(TAB_H)
        // The tab sits where the finger let go, but never outside the window's own height (no jump up or down);
        // only the screen's margins may move it, and then the window slides by the same amount.
        val within = ((fingerY ?: (l.y + h / 2)) - tabH / 2).coerceIn(l.y.toFloat(), (l.y + h - tabH).coerceAtLeast(l.y.toFloat()))
        val ty = within.coerceIn(topMargin(), sh - tabH - bottomMargin())
        val x0 = l.x.toFloat(); val y0 = l.y.toFloat()
        val x1 = stripX(right, w); val y1 = y0 + (ty - within)
        val v = content()
        stopAnim()
        if (v == null || !animateLook) { dock(right, ty.toInt()); return }
        v.animate().cancel(); v.scaleX = 1f; v.scaleY = 1f
        val a = android.animation.ValueAnimator.ofFloat(0f, 1f)
        a.duration = slideMs(x1 - x0, if ((x1 - x0) * vx > 0) vx else 0f)
        a.interpolator = android.view.animation.DecelerateInterpolator(1f)
        a.addUpdateListener {
            val t = it.animatedValue as Float
            v.alpha = 1f - 0.35f * ((t - 0.6f) / 0.4f).coerceIn(0f, 1f)
            place((x0 + (x1 - x0) * t).roundToInt(), (y0 + (y1 - y0) * t).roundToInt())
        }
        a.addListener(object : android.animation.AnimatorListenerAdapter() {
            private var cancelled = false
            override fun onAnimationCancel(animation: android.animation.Animator) { cancelled = true; v.alpha = 1f }
            override fun onAnimationEnd(animation: android.animation.Animator) {
                if (anim === a) anim = null
                if (!cancelled) { swapFade(); dock(right, ty.toInt()) }
            }
        })
        anim = a; a.start()
    }

    /** The content is about to change between window and tab: hide the stale frames, then fade the new one in. */
    private fun swapFade(then: (() -> Unit)? = null) {
        val v = content() ?: return
        v.animate().cancel(); v.alpha = 0f; v.scaleX = 1f; v.scaleY = 1f
        // Two frames: the window manager has the new size and place before anything shows.
        v.postOnAnimationDelayed({
            if (then != null) then() else v.animate().alpha(1f).setDuration(if (animateLook) 110L else 0L).start()
        }, 34L)
    }

    /** While a finger holds it: become (or stay) the tab on that side, centred on the finger, [give] px inward. */
    internal fun dockLive(right: Boolean, fingerY: Float, give: Float) {
        stopAnim()
        if (!docked.value || dockRight.value != right) { if (!docked.value) swapFade(); docked.value = true; dockRight.value = right }
        val ty = (fingerY - dp(TAB_H) / 2).coerceIn(topMargin(), screenH() - dp(TAB_H) - bottomMargin())
        place(if (right) (screenW() - dp(TAB_W) - give).toInt() else give.toInt(), ty.toInt())
    }

    /** While a finger holds the tab and pulls it inward: open the window under the finger, held near its edge side. */
    internal fun undockAt(fingerX: Float, fingerY: Float) {
        stopAnim()
        // The user unfolded by hand: a pending fold-after-move request is spent, or a later
        // re-attach would fold the window again against their will.
        BackgroundOperation.clearDockRequest()
        val right = dockRight.value
        docked.value = false
        val w = widthPx.floatValue + dp(12f)
        val h = w / aspect()
        place((fingerX - if (right) w * 0.8f else w * 0.2f).toInt(), (fingerY - h * 0.2f).toInt())
        swapFade()
    }

    /**
     * A tap on the tab: the window opens wholly on screen, at its final place beside that edge, and grows in from
     * the tab's side. 1.3.0-beta18: beta17 first placed it mostly off screen and slid it in; the window manager showed
     * that off-screen frame before the slide began, so it flashed half outside and then jumped in. Now it is never
     * anywhere but where it ends, and stays invisible until it has its real size there.
     */
    internal fun undock() {
        val l = lp ?: return
        stopAnim()
        val right = dockRight.value
        val w = widthPx.floatValue + 2 * pad()
        val h = groupH()
        // The tab stays a third of the way down the window, as when it folded.
        val y = (l.y + dp(TAB_H) / 2 - h * 0.3f).coerceIn(topMargin(), (screenH() - h - bottomMargin()).coerceAtLeast(topMargin()))
        val x = (if (right) screenW() - w - dp(8f) else dp(8f)).coerceAtLeast(0f)
        val v = content()
        v?.animate()?.cancel(); v?.alpha = 0f
        docked.value = false
        place(x.toInt(), y.toInt())
        save()
        if (v == null || !animateLook) { v?.alpha = 1f; return }
        swapFade {
            v.pivotX = if (right) w else 0f; v.pivotY = (l.y + dp(TAB_H) / 2 - y).coerceIn(0f, h)
            v.scaleX = 0.82f; v.scaleY = 0.82f
            v.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L)
                .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
        }
    }

    internal fun openFull() {
        val c = ctx ?: return
        // The full-screen page works with no background screen and no session too (it shows the
        // terminal and its history then, and picks the screen up when an app is launched), so
        // only open it when there is something to show: a screen, or a popped-up terminal
        // window. A shell-only turn never owns a session (ownerSessionId stays null), so a
        // missing session must not stop it.
        val s = BackgroundOperation.state.value
        if (s.displayId == null && s.terminalPopAt <= 0L) return
        // A lesson waiting to be saved opens its save pane; otherwise the live screen (a recording goes on there).
        if (com.kilon.agentvscreen.cmd.Teach.result != null) Surface.openReview(c) else Surface.openLive(c)
    }

    private class OverlayOwner : LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {
        private val reg = LifecycleRegistry(this)
        private val ssr = SavedStateRegistryController.create(this)
        private val store = ViewModelStore()
        init { ssr.performRestore(null); reg.currentState = Lifecycle.State.RESUMED }
        override val lifecycle: Lifecycle get() = reg
        override val savedStateRegistry: SavedStateRegistry get() = ssr.savedStateRegistry
        override val viewModelStore: ViewModelStore get() = store
        fun destroy() { reg.currentState = Lifecycle.State.DESTROYED; store.clear() }
    }
}

/** Map a point inside the picture to background-screen pixels (null: outside the picture). */
internal fun pictureToScreen(lx: Float, ly: Float, frame: RectF): Pair<Int, Int>? {
    val s = BackgroundOperation.state.value
    val d = s.ownerSessionId?.let { BackgroundOperation.previewDisplayFor(it) } ?: return null
    if (frame.width() <= 0f || !frame.contains(lx, ly)) return null
    val x = ((lx - frame.left) / frame.width() * d.width).roundToInt().coerceIn(0, d.width - 1)
    val y = ((ly - frame.top) / frame.height() * d.height).roundToInt().coerceIn(0, d.height - 1)
    return x to y
}

/**
 * All window gestures, on raw coordinates. Events go to the Compose buttons only when the finger went down on one and
 * stays put; anything that becomes a drag / pinch / hold is ours (the buttons get a cancel).
 */
@SuppressLint("ViewConstructor")
private class GestureFrame(ctx: Context) : FrameLayout(ctx) {
    private enum class Claim { NONE, MOVE, PINCH, LIVE, HELD }

    /** A point of the window as a point of the background screen (kept inside the picture). */
    private fun onScreen(x: Float, y: Float): Pair<Int, Int>? {
        val f = Overlay.frameRect
        if (f.width() <= 1f || f.height() <= 1f) return null
        return pictureToScreen(x.coerceIn(f.left, f.right - 1), y.coerceIn(f.top, f.bottom - 1), f)
    }
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop.toFloat()
    private val main = Handler(Looper.getMainLooper())
    private var claim = Claim.NONE
    private var onControl = false
    private var onScroll = false
    private var onPill = false
    private var downRaw = Offset.Zero
    private var downLocal = Offset.Zero
    private var lastLocal = Offset.Zero
    private var downAt = 0L
    private var winX = 0; private var winY = 0
    private var pinchDist = 0f; private var pinchW = 0f; private var pinchMid = Offset.Zero
    private var childGotDown = false
    /** Recent raw positions while moving the window (time ms, x, y): the release speed for inertia. */
    private val trail = ArrayDeque<Triple<Long, Float, Float>>()
    private val flingMin = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity.toFloat().coerceAtLeast(Overlay.dp(450f))

    private fun releaseSpeed(): Pair<Float, Float>? {
        val now = SystemClock.uptimeMillis()
        while (trail.size > 2 && now - trail.first().first > 100L) trail.removeFirst()
        if (trail.size < 2) return null
        val a = trail.first(); val b = trail.last()
        val dt = (b.first - a.first).coerceAtLeast(8L) / 1000f
        return (b.second - a.second) / dt to (b.third - a.third) / dt
    }

    private val holdTick = object : Runnable {
        override fun run() {
            if (claim != Claim.NONE) return
            val p = ((SystemClock.uptimeMillis() - downAt - 150L) / 850f).coerceIn(0f, 1f)
            Overlay.hold.floatValue = p
            if (p >= 1f) {
                claim = Claim.HELD
                Overlay.hold.floatValue = 0f
                val s = BackgroundOperation.state.value
                val owner = s.ownerSessionId
                if (owner != null) {
                    if (s.userStopped) BackgroundOperation.userResume()
                    BackgroundOperation.beginTakeover(owner)
                    performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
                }
            } else main.postDelayed(this, 16L)
        }
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        AppLogger.info("Overlay", "window visibility ${if (visibility == VISIBLE) "visible" else "hidden ($visibility)"}" +
            if (!Overlay.hostedByA11y) tr("（app overlay：系统可能因副屏上的安全页面隐藏它；打开无障碍可避免）", "(app overlay: the system may hide it for a secure page on the screen; turning on accessibility prevents that)") else "")
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration?) {
        super.onConfigurationChanged(newConfig)
        // The main screen turned: keep the window (or its tab) on screen.
        post { if (Overlay.docked.value) Overlay.dock(Overlay.dockRight.value) else Overlay.settle() }
    }

    private fun takenOver(): Boolean = BackgroundOperation.state.value.let { it.takeoverSessionId != null && it.takeoverSessionId == it.ownerSessionId }

    private fun stopHold() { main.removeCallbacks(holdTick); Overlay.hold.floatValue = 0f }

    private fun cancelChildren(ev: MotionEvent) {
        if (!childGotDown) return
        childGotDown = false
        val c = MotionEvent.obtain(ev); c.action = MotionEvent.ACTION_CANCEL
        super.dispatchTouchEvent(c); c.recycle()
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val docked = Overlay.docked.value
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                claim = Claim.NONE
                Overlay.stopAnim(); trail.clear()
                downRaw = Offset(ev.rawX, ev.rawY); downLocal = Offset(ev.x, ev.y); lastLocal = downLocal
                downAt = SystemClock.uptimeMillis()
                winX = Overlay.lp?.x ?: 0; winY = Overlay.lp?.y ?: 0
                onControl = !docked && Overlay.hitControl(ev.x, ev.y)
                onScroll = !docked && Overlay.hitScroll(ev.x, ev.y)
                onPill = !docked && Overlay.pillRect.contains(ev.x, ev.y)
                childGotDown = false
                if (onControl) { childGotDown = true; super.dispatchTouchEvent(ev) }
                else if (!docked && takenOver() && !onPill && Overlay.frameRect.contains(ev.x, ev.y)) {
                    // 1.2.0-beta2: while the user controls the screen, the finger on the picture IS a finger on the
                    // screen, from the moment it lands (see UserTouch). Nothing is replayed after the fact.
                    val owner = BackgroundOperation.state.value.ownerSessionId
                    val p = onScreen(ev.x, ev.y)
                    if (owner != null && p != null && com.kilon.agentvscreen.core.UserTouch.down(owner, p.first, p.second)) claim = Claim.LIVE
                }
                else if (!docked && !takenOver() && Overlay.frameRect.contains(ev.x, ev.y)) {
                    Overlay.holdAt.value = downLocal
                    main.postDelayed(holdTick, 150L)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (!docked && ev.pointerCount == 2 && claim != Claim.HELD) {
                    stopHold(); cancelChildren(ev)
                    if (claim == Claim.LIVE) com.kilon.agentvscreen.core.UserTouch.cancel()
                    claim = Claim.PINCH
                    pinchDist = dist(ev).coerceAtLeast(1f)
                    pinchW = Overlay.widthPx.floatValue
                    pinchMid = midRaw(ev)
                    winX = Overlay.lp?.x ?: 0; winY = Overlay.lp?.y ?: 0
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val raw = Offset(ev.rawX, ev.rawY)
                lastLocal = Offset(ev.x, ev.y)
                when (claim) {
                    Claim.LIVE -> onScreen(ev.x, ev.y)?.let { com.kilon.agentvscreen.core.UserTouch.move(it.first, it.second) }
                    Claim.PINCH -> if (ev.pointerCount >= 2) {
                        val nw = Overlay.clampWidth(pinchW * dist(ev) / pinchDist)
                        val k = nw / pinchW
                        Overlay.widthPx.floatValue = nw
                        Overlay.place((pinchMid.x - (pinchMid.x - winX) * k).roundToInt(), (pinchMid.y - (pinchMid.y - winY) * k).roundToInt())
                    }
                    Claim.MOVE -> {
                        trail.addLast(Triple(SystemClock.uptimeMillis(), raw.x, raw.y)); if (trail.size > 12) trail.removeFirst()
                        val sw = Overlay.screenW()
                        if (docked) {
                            // The tab rides its edge under the finger (with a little give inward); pulled well inward it
                            // opens right under the finger and keeps following — never a jump on release.
                            val right = Overlay.dockRight.value
                            val inward = if (right) sw - raw.x else raw.x
                            if (inward > Overlay.dp(72f)) {
                                Overlay.undockAt(raw.x, raw.y)
                                downRaw = raw; winX = Overlay.lp?.x ?: 0; winY = Overlay.lp?.y ?: 0
                            } else Overlay.dockLive(right, raw.y, (inward - Overlay.dp(Overlay.TAB_W)).coerceIn(0f, Overlay.dp(16f)))
                        } else {
                            val d = raw - downRaw
                            Overlay.place(winX + d.x.roundToInt(), winY + d.y.roundToInt())
                            // Folding follows the finger: within ~110 dp of a side edge the window shrinks toward the
                            // finger; at the edge it is tab-sized and becomes the tab right there, still under the finger.
                            val edge = Overlay.dp(16f); val zone = Overlay.dp(110f)
                            val dist = min(raw.x, sw - raw.x)
                            if (dist <= edge) Overlay.dockLive(raw.x > sw / 2f, raw.y, 0f)
                            else Overlay.shrinkLive(ev.x, ev.y, ((zone - dist) / (zone - edge)).coerceIn(0f, 1f))
                        }
                    }
                    Claim.NONE -> when {
                        // A drag that starts on a scrollable region (the terminal log) scrolls that region: the
                        // child keeps getting the moves and the window never claims the drag. Without this the
                        // drag past the touch slop cancelled the child and moved the window instead, so the log
                        // could never be scrolled. The window is still moved by its title pill.
                        onScroll -> super.dispatchTouchEvent(ev)
                        hypot(raw.x - downRaw.x, raw.y - downRaw.y) > slop -> {
                            stopHold(); cancelChildren(ev)
                            claim = Claim.MOVE
                            if (docked) winX = Overlay.lp?.x ?: 0
                        }
                        onControl -> super.dispatchTouchEvent(ev)
                        else -> {}
                    }
                    Claim.HELD -> {}
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> return true
            MotionEvent.ACTION_UP -> {
                stopHold()
                val ms = SystemClock.uptimeMillis() - downAt
                when (claim) {
                    Claim.PINCH -> Overlay.save()
                    Claim.MOVE -> if (docked) {
                        // Let go as a tab: it settles flat on its edge where it is.
                        Overlay.lp?.let { Overlay.dock(Overlay.dockRight.value, it.y) }
                    } else {
                        // A finger that stopped before lifting leaves only old samples: no fling.
                        val stale = trail.lastOrNull()?.let { SystemClock.uptimeMillis() - it.first > 60L } ?: true
                        trail.addLast(Triple(SystemClock.uptimeMillis(), ev.rawX, ev.rawY))
                        val v = if (stale) null else releaseSpeed()
                        Overlay.resetShrink()
                        if (v != null && hypot(v.first, v.second) > flingMin) Overlay.fling(v.first, v.second, ev.rawY) else Overlay.settle(ev.rawY)
                    }
                    Claim.LIVE -> {
                        val p = onScreen(ev.x, ev.y); com.kilon.agentvscreen.core.UserTouch.up(p?.first ?: -1, p?.second ?: -1)
                        // The tap may have focused an input box: check the keyboard bridge.
                        Overlay.onTakeoverTouchUp()
                    }
                    Claim.HELD -> {}
                    Claim.NONE -> when {
                        onControl -> super.dispatchTouchEvent(ev)
                        docked -> Overlay.undock()
                        else -> Overlay.hintAt.longValue = SystemClock.uptimeMillis()
                    }
                }
                claim = Claim.NONE; childGotDown = false
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                stopHold(); cancelChildren(ev)
                if (claim == Claim.LIVE) com.kilon.agentvscreen.core.UserTouch.cancel()
                if (claim == Claim.MOVE) {
                    if (Overlay.docked.value) Overlay.lp?.let { Overlay.dock(Overlay.dockRight.value, it.y) } else { Overlay.resetShrink(); Overlay.settle() }
                }
                claim = Claim.NONE
                return true
            }
        }
        return true
    }

    private fun dist(ev: MotionEvent): Float = if (ev.pointerCount < 2) 0f else hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    private fun midRaw(ev: MotionEvent): Offset {
        // Raw coordinates of the midpoint: the view's screen position plus the local midpoint.
        val loc = IntArray(2); getLocationOnScreen(loc)
        return Offset(loc[0] + (ev.getX(0) + ev.getX(1)) / 2f, loc[1] + (ev.getY(0) + ev.getY(1)) / 2f)
    }
}

private val PAD = 7.dp
/** 1.3.0-beta18: one height for the title pill and the round buttons beside it. */
private val CHIP_H = 24.dp

/** Report a button's bounds so the gesture frame lets its taps through. */
@Composable
private fun Modifier.control(key: String): Modifier {
    DisposableEffect(key) { onDispose { Overlay.controls.remove(key) } }
    return this.onGloballyPositioned { c -> val b = c.boundsInRoot(); Overlay.controls[key] = RectF(b.left, b.top, b.right, b.bottom) }
}

/** Report a scrollable region's bounds so the gesture frame lets its drags scroll it instead of moving the window. */
@Composable
private fun Modifier.scrollControl(key: String): Modifier {
    DisposableEffect(key) { onDispose { Overlay.scrollControls.remove(key) } }
    return this.onGloballyPositioned { c -> val b = c.boundsInRoot(); Overlay.scrollControls[key] = RectF(b.left, b.top, b.right, b.bottom) }
}

/** The floating window's title pill — and the fullscreen page's second line: the
 * agent's current step (`vscreen title`) when set, else the mode word. One
 * function, so both places always show the same text. [left] is the ms left in
 * this turn (the standby countdown); -1 when there is none. */
internal fun agentTitleText(s: BackgroundOperation.State, left: Long): String {
    val mode = modeOf(s)
    return s.agentTitle ?: when (mode) {
        Mode.ACTING -> s.action?.take(18) ?: Palette.word(mode)
        Mode.STANDBY -> if (left >= 0) tr("AI 待命", "AI on standby") + " · ${left / 60000}:${"%02d".format((left / 1000) % 60)}" else Palette.word(mode)
        Mode.IDLE -> if (s.powerSaving) tr("空闲 · 省电暂停", "Idle · paused to save power") else tr("空闲", "Idle")
        else -> Palette.word(mode)
    }
}

@Composable
internal fun OverlayContent(fixed: BackgroundOperation.State? = null, picture: (@Composable (String) -> Unit)? = null) {
    val live by BackgroundOperation.state.collectAsState()
    val s = fixed ?: live
    // No owner session (no screen): the window can still show the terminal page, popped up by a shell
    // command. Everything that needs a screen (picture, takeover) is guarded below.
    val owner = s.ownerSessionId
    val mode = modeOf(s)
    // The window can be folded into its edge tab (docked) or unfolded. Two requests move it: a
    // shell command unfolds it so the user sees the command, a move to the main screen folds it so
    // it does not cover the app the user is now using. Both effects live above the docked early
    // return, so they run while the window is folded — below it, a command could never unfold it
    // again. Each effect only acts on a request newer than the last one it handled, and when both
    // requests are pending the newer one wins (the timestamps are compared), so the two never
    // fight over the window. `lastPopAt` starts at the current pop time so a re-attach does not
    // re-expand a window the user folded by hand for an old command.
    var lastPopAt by remember { mutableLongStateOf(s.terminalPopAt) }
    LaunchedEffect(s.terminalPopAt) {
        val at = s.terminalPopAt
        if (at > lastPopAt) {
            lastPopAt = at
            if (at > 0L && at > s.dockRequestedAt && Overlay.docked.value) Overlay.undock()
        }
    }
    LaunchedEffect(s.dockRequested) {
        if (s.dockRequested) {
            if (s.dockRequestedAt >= s.terminalPopAt && !Overlay.docked.value) Overlay.dock(Overlay.dockRight.value)
            BackgroundOperation.clearDockRequest()
        }
    }
    // r46: the mirror of the fold effect — a shell command's first pop unfolds a folded window.
    // It has to be a request of its own: the pop-time effect above only fires on a pop newer than
    // the last one it saw, and a re-attached window starts its counter at the current pop time,
    // so without this a first pop that re-attaches a folded window would leave it as an edge tab.
    LaunchedEffect(s.undockRequested) {
        if (s.undockRequested) {
            if (Overlay.docked.value) Overlay.undock()
            BackgroundOperation.clearUndockRequest()
        }
    }
    val waiting = pendingAsks()
    // 1.4.0-beta10: a lesson waiting to be saved is a card too (it replaces the question it answers).
    val lesson by com.kilon.agentvscreen.cmd.Teach.lesson.collectAsState()
    val review = (lesson as? com.kilon.agentvscreen.cmd.Lesson.Review)?.result
    // 1.4.0-beta11: getting ready / recording: the lesson strip hangs under the window (same place as a question card).
    val showing = lesson is com.kilon.agentvscreen.cmd.Lesson.Ready || lesson is com.kilon.agentvscreen.cmd.Lesson.Recording
    // Known before any placement, docked or not, so opening from the tab already counts the card.
    androidx.compose.runtime.SideEffect { Overlay.cardShown.value = waiting.isNotEmpty() || review != null || showing }
    if (Overlay.docked.value) { DockTab(mode, Overlay.dockRight.value, waiting.size + if (review != null || showing) 1 else 0); return }
    val density = LocalDensity.current
    val landscape = Overlay.aspect() > 1f
    LaunchedEffect(landscape) { Overlay.orientationChanged(landscape) }
    val w = with(density) { Overlay.widthPx.floatValue.toDp() }
    val h = w / Overlay.aspect()
    // 1.4.0-beta4: the controls scale with the window's short side. Scaled by the width, a landscape window (wide,
    // short) got the largest controls over the smallest picture height; now both orientations get the same size.
    val k = (minOf(w.value, h.value) / 200f).coerceIn(0.75f, 1.15f)
    val corner = (14 + 6 * k).dp

    var left by remember { mutableLongStateOf(-1L) }
    LaunchedEffect(owner, mode) {
        while (true) { left = owner?.let { Turns.leftMs(it) } ?: -1L; delay(1000) }
    }

    // 画面 / 终端 切换是共享状态（BackgroundOperation.State.page）：浮窗和全屏页读同一份，
    // 一边切了另一边也跟着切。终端页是只读的（命令由 agent 跑，`vscreen shell`）；窗口的获焦只归
    // 输入法桥管（接管后点输入框弹系统键盘），这里不再为终端或打字行改获焦。
    val termLog by VScreenTerminal.log.collectAsState()
    val haptic = LocalHapticFeedback.current
    // The keyboard bridge closes when the user hands the screen back or leaves the screen page.
    LaunchedEffect(mode, s.page) {
        if (mode != Mode.TAKEOVER || s.page != BackgroundOperation.ViewPage.SCREEN) Overlay.closeImeBridge()
    }
    val octx = LocalContext.current
    // Same text as the fullscreen page's second line (agentTitleText).
    val title = agentTitleText(s, left)

    // 1.3.0-beta17: the controls sit ON the picture again (rainbow: no extra height above or below it), small and in
    // the corners: title pill top left, full screen / close top right, stop / hand back / done at the bottom. They do
    // not leak through: each is a hit target of its own (clickable or eatTouches), and Compose gives a touch only to
    // the topmost thing it lands on, so the picture under them never gets it (never sent, never recorded). A question
    // card still hangs OUTSIDE the window (below it, or above when there is no room below): a card over the picture
    // would hide what the user has to tap.
    val asking = waiting.firstOrNull()
    // The last question stays composed while its card shrinks away.
    var shownAsk by remember { mutableStateOf(asking) }
    if (asking != null) shownAsk = asking
    val chip = Color(0xCC12141C)

    Column(Modifier.padding(PAD).width(w), verticalArrangement = Arrangement.spacedBy((6 * k).dp)) {
        Box(Modifier.width(w).height(h).glowBorder(mode, corner, pad = PAD - 1.dp)) {
            Box(
                Modifier.fillMaxSize().clip(RoundedCornerShape(corner)).background(Palette.ink)
                    .onGloballyPositioned { c -> val b = c.boundsInRoot(); Overlay.frameRect.set(b.left, b.top, b.right, b.bottom) },
            ) {
                if (s.page == BackgroundOperation.ViewPage.TERMINAL) {
                    TerminalView(k, termLog)
                } else if (owner != null) {
                    if (picture != null) picture(owner) else VScreenLiveFrame(owner, Modifier.fillMaxSize(), interactive = false)
                } else {
                    // No screen and the picture tab was picked: nothing to show — fall back to the terminal.
                    TerminalView(k, termLog)
                }
                // A soft scrim under the pill and buttons keeps them readable on white pages.
                Box(Modifier.fillMaxWidth().height((44 * k).dp).background(Brush.verticalGradient(listOf(Color(0x99000000), Color.Transparent))))
            }

            // Title pill: a handle (drag or hold it to move the window, also while you hold the screen).
            Row(
                Modifier.align(Alignment.TopStart).padding(start = (8 * k).dp, top = (8 * k).dp).widthIn(max = w - (126 * k).dp)
                    .clip(RoundedCornerShape(50)).background(Color(0xCC12141C))
                    .onGloballyPositioned { c -> val b = c.boundsInRoot(); Overlay.pillRect.set(b.left, b.top, b.right, b.bottom) }
                    .padding(horizontal = (8 * k).dp, vertical = (4 * k).dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 1.4.0-beta15 (rainbow): no grip icon; the state dot already marks the pill, and the grip took room from the title.
                Box(Modifier.size((7 * k).dp).clip(CircleShape).background(if (mode == Mode.ACTING) Brush.sweepGradient(Palette.rainbow) else Brush.linearGradient(listOf(Palette.of(mode), Palette.of(mode)))))
                Spacer(Modifier.width((5 * k).dp))
                // Long title scrolls sideways (marquee) instead of "…"; short titles stay still.
                Text(title, Modifier.weight(1f, fill = false).basicMarquee(initialDelayMillis = 1000, velocity = 30.dp), color = Color.White, fontSize = (11 * k).sp, maxLines = 1, overflow = TextOverflow.Visible, fontWeight = FontWeight.Medium)
            }

            // Buttons, top right: terminal tab, full screen, close. Same places in every state.
            // No screen: there is only the terminal page, so the tab toggle is hidden — but full
            // screen stays: the full-screen page shows the terminal and its history when there
            // is no background screen yet.
            Row(Modifier.align(Alignment.TopEnd).padding(end = (6 * k).dp, top = (6 * k).dp)) {
                if (owner != null) {
                    PillBtn(if (s.page == BackgroundOperation.ViewPage.TERMINAL) tr("画面", "Screen") else tr("终端", "Terminal"), Color(0xCC12141C), Color.White, k, "termTab") {
                        BackgroundOperation.setPage(if (s.page == BackgroundOperation.ViewPage.TERMINAL) BackgroundOperation.ViewPage.SCREEN else BackgroundOperation.ViewPage.TERMINAL)
                    }
                    Spacer(Modifier.width((5 * k).dp))
                }
                RoundBtn(Icons.Filled.OpenInFull, tr("全屏", "Full screen"), Color(0xCC12141C), k, "full") { Overlay.openFull() }
                Spacer(Modifier.width((5 * k).dp))
                RoundBtn(Icons.Filled.Close, if (mode == Mode.ACTING || mode == Mode.STANDBY || mode == Mode.TAKEOVER) tr("收起小窗", "Hide window") else tr("关闭副屏", "Close screen"),
                    Color(0xCC12141C), k, "close") { Overlay.closeTapped() }
            }

            // v18: the "停止 AI" pill is gone from the window (it covered the picture). Stop the AI from the
            // app's home ("停止") or the full-screen page ("停止 AI"); long-pressing the picture to take over
            // also freezes it at once.

            // Stopped: say what that means right on the window.
            if (mode == Mode.STOPPED) {
                Text(tr("已停止 AI：它的操作会被拒绝，点“交还 AI”或通知里的“允许继续”放开", "AI stopped: its actions are refused. Tap \"Hand back\" or \"Allow again\" in the notification to lift it"), color = Color.White, fontSize = (10 * k).sp, lineHeight = (13 * k).sp,
                    modifier = Modifier.align(Alignment.Center).padding(horizontal = (12 * k).dp).clip(RoundedCornerShape(10.dp)).background(Color(0xDD7F1D1D)).padding(horizontal = 8.dp, vertical = 5.dp))
            }

            // Hand back, bottom centre.
            if (mode == Mode.TAKEOVER || mode == Mode.STOPPED) {
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = (10 * k).dp).control("handback")
                        .clip(RoundedCornerShape(50)).background(Palette.takeover).clickable { BackgroundOperation.userResume() }
                        .padding(horizontal = (12 * k).dp, vertical = (6 * k).dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.SmartToy, null, tint = Color(0xFF052E1C), modifier = Modifier.size((14 * k).dp))
                    Spacer(Modifier.width((4 * k).dp))
                    Text(tr("交还 AI", "Hand back"), color = Color(0xFF052E1C), fontSize = (12 * k).sp, fontWeight = FontWeight.SemiBold)
                }
            }

            // v18: the "接管" pill is gone from the window (it covered the picture). Hold one finger still
            // for 0.5 s on the picture to take over (the ring fills); the full-screen page and the app's
            // home still have a 接管 button.

            // Messages: gesture hints.
            var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
            LaunchedEffect(Overlay.hintAt.longValue) { now = SystemClock.uptimeMillis(); delay(3100); now = SystemClock.uptimeMillis() }
            val hint = when {
                Overlay.hintAt.longValue > 0 && now - Overlay.hintAt.longValue < 2500 -> tr("长按 1 秒接管 · “终端”看 shell 输出 · 双指缩放 · 拖到边缘收起 · × 关闭", "Hold 1 s to take over · “Terminal” shows shell output · pinch to resize · drag to an edge to dock · × closes")
                else -> null
            }
            GestureHint(hint != null, Modifier.align(Alignment.Center), k, hint ?: "")

            // Hold-to-take-over ring.
            val p = Overlay.hold.floatValue
            if (p > 0f) {
                val at = Overlay.holdAt.value
                val ringR = with(density) { (28 * k).dp.toPx() }
                Canvas(Modifier.fillMaxSize()) {
                    val c = Offset(at.x - Overlay.frameRect.left, at.y - Overlay.frameRect.top)
                    drawCircle(Color(0x66000000), ringR + 6f, c)
                    drawArc(Palette.takeover, -90f, 360f * p, false, topLeft = Offset(c.x - ringR, c.y - ringR),
                        size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2), style = Stroke(width = 6f, cap = StrokeCap.Round))
                }
                Icon(Icons.Filled.PanTool, null, tint = Color.White, modifier = Modifier.offset {
                    IntOffset((at.x - Overlay.frameRect.left).roundToInt() - 24, (at.y - Overlay.frameRect.top).roundToInt() - 24) }.size(16.dp))
            }
        }
        val ease = androidx.compose.animation.core.tween<androidx.compose.ui.unit.IntSize>(Overlay.CARD_MS.toInt(), easing = androidx.compose.animation.core.LinearOutSlowInEasing)
        // The card hangs below the window, outside the picture: without this its taps never reach it. The gesture
        // frame only lets a touch through to the content when it lands on a registered control, on the picture, or
        // on the title pill; a touch on the card is none of those, so it is swallowed and the card's buttons (the
        // lesson strip, a question, a lesson waiting to be saved) never see it.
        AnimatedVisibility(asking != null || review != null || showing,
            modifier = Modifier.control("card"),
            enter = androidx.compose.animation.expandVertically(ease, expandFrom = Alignment.Top) + fadeIn(androidx.compose.animation.core.tween(Overlay.CARD_MS.toInt())),
            exit = androidx.compose.animation.shrinkVertically(ease, shrinkTowards = Alignment.Top) + fadeOut(androidx.compose.animation.core.tween(160))) {
            val rv = review
            if (showing && owner != null) androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().eatTouches().glassDark(RoundedCornerShape((18 * k).dp))
                .padding(horizontal = (12 * k).dp, vertical = (11 * k).dp)
                .onSizeChanged { sz -> if (sz.height.toFloat() != Overlay.cardH.floatValue) { Overlay.cardH.floatValue = sz.height.toFloat() }; Overlay.fitGroup() }) {
                LessonStrip(owner, k, withHead = true, onSay = { Surface.openLive(octx, say = true) })
            }
            else if (asking == null && rv != null) ReviewQuick(rv, k, Modifier.fillMaxWidth()
                .onSizeChanged { sz -> if (sz.height.toFloat() != Overlay.cardH.floatValue) { Overlay.cardH.floatValue = sz.height.toFloat() }; Overlay.fitGroup() })
            else shownAsk?.let { a ->
                // Measured at its full size from the first frame (the expansion only clips it), so the window starts
                // its glide up at the same moment and lands as the card finishes growing.
                AskQuick(a, waiting.size.coerceAtLeast(1), k, modifier = Modifier.fillMaxWidth()
                    .onSizeChanged { sz -> if (sz.height.toFloat() != Overlay.cardH.floatValue) { Overlay.cardH.floatValue = sz.height.toFloat() }; Overlay.fitGroup() })
            }
        }
    }
}

/**
 * The picture. Taken over: a finger here IS a finger on the background screen from the moment it lands (UserTouch),
 * cancelled (never tapped) when the window takes the touch back for a pinch. Not taken over: hold 0.5 s to take over
 * (a ring shows it filling), a tap shows the gesture hint, a drag is the window's (the frame takes it).
 */
@Composable
private fun PictureLayer(owner: String, k: Float, picture: (@Composable (String) -> Unit)?) {
    var hold by remember { mutableFloatStateOf(0f) }
    var holdAt by remember { mutableStateOf(Offset.Zero) }
    val density = LocalDensity.current
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    Box(Modifier.fillMaxSize().pointerInput(owner) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            Overlay.pictureDown = true
            val frame = RectF(0f, 0f, size.width.toFloat(), size.height.toFloat())
            fun screen(p: Offset) = pictureToScreen(p.x.coerceIn(0f, frame.right - 1f), p.y.coerceIn(0f, frame.bottom - 1f), frame)
            val st = BackgroundOperation.state.value
            val taken = st.takeoverSessionId != null && st.takeoverSessionId == st.ownerSessionId
            if (taken) {
                val p0 = screen(down.position) ?: return@awaitEachGesture
                if (!com.kilon.agentvscreen.core.UserTouch.down(owner, p0.first, p0.second)) return@awaitEachGesture
                Overlay.pictureLive = true
                var ended = false
                try {
                    while (true) {
                        val ev = awaitPointerEvent()
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) {
                            if (!Overlay.frameClaimed) { val p = screen(ch.position); com.kilon.agentvscreen.core.UserTouch.up(p?.first ?: -1, p?.second ?: -1); ended = true }
                            break
                        }
                        screen(ch.position)?.let { com.kilon.agentvscreen.core.UserTouch.move(it.first, it.second) }
                        ch.consume()
                    }
                } finally {
                    if (!ended) com.kilon.agentvscreen.core.UserTouch.cancel()
                    Overlay.pictureLive = false
                }
            } else {
                holdAt = down.position
                val t0 = SystemClock.uptimeMillis()
                var held = false
                var up = false
                try {
                    while (true) {
                        val ev = withTimeoutOrNull(16L) { awaitPointerEvent() }
                        if (Overlay.frameClaimed) break
                        if (!held) {
                            hold = ((SystemClock.uptimeMillis() - t0 - 100L) / 400f).coerceIn(0f, 1f)
                            if (hold >= 1f) {
                                held = true; hold = 0f; Overlay.pictureHeld = true
                                if (BackgroundOperation.state.value.userStopped) BackgroundOperation.userResume()
                                BackgroundOperation.beginTakeover(owner)
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                            }
                        }
                        if (ev == null) continue
                        val ch = ev.changes.firstOrNull { it.id == down.id } ?: break
                        if (!ch.pressed) { up = true; break }
                    }
                } finally { hold = 0f }
                if (up && !held && !Overlay.frameClaimed) Overlay.hintAt.longValue = SystemClock.uptimeMillis()
            }
        }
    }) {
        if (picture != null) picture(owner) else VScreenLiveFrame(owner, Modifier.fillMaxSize())

        // Gesture hint (a tap on the picture while the AI has the screen).
        var now by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
        LaunchedEffect(Overlay.hintAt.longValue) { now = SystemClock.uptimeMillis(); delay(3100); now = SystemClock.uptimeMillis() }
        val showHint = Overlay.hintAt.longValue > 0 && now - Overlay.hintAt.longValue < 2500
        AnimatedVisibility(showHint, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
            // 1.3.0-beta18: one gesture per line (rainbow: three lines, not one run-on line).
            Text(tr("长按 0.5 秒接管\n双指缩放\n拖出屏幕一半收起", "Hold 0.5 s to take over\nPinch to resize\nDrag half off screen to fold"), color = Color.White, fontSize = (11 * k).sp,
                lineHeight = (16 * k).sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xDD12141C)).padding(horizontal = 10.dp, vertical = 6.dp))
        }

        // Hold-to-take-over ring.
        if (hold > 0f) {
            val ringR = with(density) { (28 * k).dp.toPx() }
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(Color(0x66000000), ringR + 6f, holdAt)
                drawArc(Palette.takeover, -90f, 360f * hold, false, topLeft = Offset(holdAt.x - ringR, holdAt.y - ringR),
                    size = androidx.compose.ui.geometry.Size(ringR * 2, ringR * 2), style = Stroke(width = 6f, cap = StrokeCap.Round))
            }
            Icon(Icons.Filled.PanTool, null, tint = Color.White, modifier = Modifier.offset { IntOffset(holdAt.x.roundToInt() - 24, holdAt.y.roundToInt() - 24) }.size(16.dp))
        }
    }
}

/**
 * Gesture-hint toast over the picture. A file-scope helper (not inline in OverlayContent): inside the picture
 * Box the compiler resolves an inline AnimatedVisibility call to the ColumnScope overload and rejects it
 * ("cannot be called in this context with an implicit receiver"); here only the plain overload applies.
 */
@Composable
private fun GestureHint(visible: Boolean, modifier: Modifier = Modifier, k: Float, text: String) {
    AnimatedVisibility(visible, modifier, enter = fadeIn(), exit = fadeOut()) {
        Text(text, color = Color.White, fontSize = (11 * k).sp,
            modifier = Modifier.padding(8.dp).clip(RoundedCornerShape(10.dp)).background(Color(0xDD12141C)).padding(horizontal = 10.dp, vertical = 6.dp))
    }
}

@Composable
private fun RoundBtn(icon: ImageVector, desc: String, bg: Color, k: Float, key: String, onClick: () -> Unit) {
    Box(
        Modifier.size((28 * k).dp).control(key).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, desc, tint = Color.White, modifier = Modifier.size((15 * k).dp)) }
}

/** Small text pill button (top-right terminal tab, and anywhere a round icon does not read). */
@Composable
private fun PillBtn(text: String, bg: Color, fg: Color, k: Float, key: String, onClick: () -> Unit) {
    Box(
        Modifier.control(key).clip(RoundedCornerShape(50)).background(bg).clickable(onClick = onClick)
            .padding(horizontal = (10 * k).dp, vertical = (5 * k).dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = fg, fontSize = (11 * k).sp, fontWeight = FontWeight.SemiBold) }
}

/**
 * The terminal page of the floating window (Fluid Cloud): every `vscreen shell` run, in full — command, stdout,
 * stderr, exit code — newest at the bottom. Read-only: commands are run by the agent, not typed here.
 *
 * The log area is registered as a control and as a scroll region, so dragging there scrolls the log instead of
 * injecting into the screen or moving the window; the window is still moved by its title pill.
 */
@Composable
private fun TerminalView(k: Float, entries: List<VScreenTerminal.Entry>) {
    val s by BackgroundOperation.state.collectAsState()
    val autoScroll = s.termAutoscroll
    // r38: 开关开着时打开页面就停在最下面（initialFirstVisibleItemIndex）；关着时从最上面开始。
    // r46: 第一次弹起（popScrollAt，有请求）也从最下面开始，哪怕开关关着——弹出来要看到最新那条命令。
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if ((autoScroll || s.popScrollAt > 0L) && entries.isNotEmpty()) entries.size - 1 else 0)
    // r38: 开关开着时，每来一条新命令就直接滚到底部，不再判断“是否已在底部附近”。
    // 开关打开的瞬间也会滚到底部；关着时不自动滚，由用户手动滑。
    // r43: key 不能是 entries.size——内存日志满 50 条后 size 永远停在 50，key 不变、effect
    // 不再触发，跑多了命令就不滚了（用户就是这么碰上的）。改成最新一条的单调 id
    // （Entry.nextId 每条新命令 +1，满了顶掉旧条也照样变），满 50 条后照样滚到底部。
    // r46: 开关关着时，只有第一次弹起才滚一次（popScrollAt）：弹起时这条命令的记录还没到
    // （run 里先弹、跑完才记），所以等最新一条记录的时间 >= 弹起时间再滚——也就是这条命令
    // 的结果到了——滚完把请求清掉，后面的命令不再滚。开关开着时 popScrollAt 不用管，
    // 自动滚本来就会滚；这时顺带把残留的请求清掉，免得它以后开关关了又冒出来滚一次。
    LaunchedEffect(entries.lastOrNull()?.id, autoScroll, s.popScrollAt) {
        if (entries.isEmpty()) return@LaunchedEffect
        if (autoScroll) {
            if (s.popScrollAt > 0L) BackgroundOperation.clearPopScroll()
            listState.scrollToItem(entries.size - 1)
            return@LaunchedEffect
        }
        val req = s.popScrollAt
        if (req <= 0L) return@LaunchedEffect
        val last = entries.lastOrNull() ?: return@LaunchedEffect
        if (last.atMs >= req) {
            listState.scrollToItem(entries.size - 1)
            BackgroundOperation.clearPopScroll()
        }
    }
    Column(
        Modifier.fillMaxSize().background(Color(0xFF0B0D14))
            .padding(start = (10 * k).dp, end = (10 * k).dp, top = (46 * k).dp, bottom = (44 * k).dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("终端", "Terminal"), color = Color.White, fontSize = (12 * k).sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width((6 * k).dp))
            Text(tr("KernelSU root · uid 0", "KernelSU root · uid 0"), color = Color(0x88FFFFFF), fontSize = (10 * k).sp)
            // r40: “长按命令复制”这段提示直接删掉（全屏页里已经有这句，浮窗上不用重复），腾出来的地方给开关。
            Spacer(Modifier.weight(1f))
            // r40: 自动滚到底部开关（光秃秃的开关，无文字；开=蓝底，关=灰底；点一下切换）。
            // 最小状态（k=0.75）下尺寸不跟 k 一起缩到看不见：至少 32×20dp，圆点至少 16dp，顶栏再挤也一眼能看见、手指点得准。
            Box(Modifier.control("termAutoscroll")
                .size(width = maxOf((38 * k).dp, 32.dp), height = maxOf((22 * k).dp, 20.dp))
                .clip(RoundedCornerShape(50)).background(if (autoScroll) Color(0xFF7CC8FF) else Color(0x44FFFFFF))
                .clickable { BackgroundOperation.setTermAutoscroll(!autoScroll) }.padding(2.dp),
                contentAlignment = if (autoScroll) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.size(maxOf((18 * k).dp, 16.dp)).clip(CircleShape).background(Color.White))
            }
        }
        Spacer(Modifier.height((6 * k).dp))
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(tr("还没有跑过命令\n用 vscreen shell 跑", "No commands run yet\nrun one with vscreen shell"),
                    color = Color(0x88FFFFFF), fontSize = (11 * k).sp, lineHeight = (15 * k).sp)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().control("termLog").scrollControl("termLog"), contentPadding = PaddingValues(end = (8 * k).dp)) {
                    items(entries, key = { it.id }) { e -> TerminalEntryView(e, k, collapsible = true) }
                }
                TerminalScrollbar(listState)
            }
        }
    }
}

// 终端条目跑完的时间，和历史页 formatTime 同格式（MM-dd HH:mm:ss），不再显示耗时毫秒。
private fun formatTime(ms: Long): String =
    SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()).format(Date(ms))

/**
 * One terminal record: full text, nothing cut off (no maxLines anywhere). Long-press the command line to copy
 * the command (not the output) to the clipboard — combinedClickable's built-in long-press haptic gives one
 * short buzz, and a "已复制" toast confirms; a tap does nothing, so scrolling never copies by accident.
 *
 * `collapsible` (floating window only): output is fully folded by default — the entry shows just the command
 * line and the exit code, plus an "展开输出 ▾" button when there is output to show; tap it to read the full
 * output, tap again to fold. The floating window is small, so this saves a lot of space. The fullscreen
 * terminal page calls without it and keeps the full output expanded as before.
 */
@Composable
internal fun TerminalEntryView(e: VScreenTerminal.Entry, k: Float, collapsible: Boolean = false) {
    val ctx = LocalContext.current
    var expanded by remember { mutableStateOf(false) }
    val hasOutput = e.stdout.isNotEmpty() || e.stderr.isNotEmpty()
    val showOutput = !collapsible || expanded
    Column(Modifier.fillMaxWidth().padding(vertical = (4 * k).dp)) {
        Text("$ " + e.command, color = Color(0xFF7CC8FF), fontFamily = FontFamily.Monospace, fontSize = (11 * k).sp,
            modifier = Modifier.clip(RoundedCornerShape(4.dp))
                .combinedClickable(onClick = {}, onLongClick = {
                    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                    cm?.setPrimaryClip(ClipData.newPlainText("command", e.command))
                    Toast.makeText(ctx, tr("已复制", "Copied"), Toast.LENGTH_SHORT).show()
                })
                .padding(vertical = (1 * k).dp))
        if (showOutput) {
            if (e.stdout.isNotEmpty()) Text(e.stdout.trimEnd('\n'), color = Color(0xFFE8E8E8), fontFamily = FontFamily.Monospace, fontSize = (11 * k).sp)
            if (e.stderr.isNotEmpty()) Text(e.stderr.trimEnd('\n'), color = Color(0xFFFF8A8A), fontFamily = FontFamily.Monospace, fontSize = (11 * k).sp)
        }
        if (e.exitCode != 0) Text(
            if (e.timedOut) tr("⏱ 超时，已终止（退出码 124，${formatTime(e.atMs)}）", "⏱ timed out, killed (exit 124, ${formatTime(e.atMs)})")
            else tr("退出码 ${e.exitCode}（${formatTime(e.atMs)}）", "exit ${e.exitCode} (${formatTime(e.atMs)})"),
            color = Color(0xFFFFB347), fontSize = (10 * k).sp)
        else Text(tr("✓ 退出码 0（${formatTime(e.atMs)}）", "✓ exit 0 (${formatTime(e.atMs)})"),
            color = Color(0xFF8FD694), fontSize = (10 * k).sp)
        if (collapsible && hasOutput) {
            val lines = (e.stdout + e.stderr).trimEnd('\n').lines().size
            Text(
                if (expanded) tr("收起 ▴", "Collapse ▴")
                else tr("展开输出（共 $lines 行）▾", "Show output ($lines lines) ▾"),
                color = Color(0xFF7CC8FF), fontSize = (10 * k).sp,
                modifier = Modifier.padding(top = (2 * k).dp).clip(RoundedCornerShape(4.dp))
                    .clickable { expanded = !expanded }.padding(vertical = (1 * k).dp))
        }
        Box(Modifier.fillMaxWidth().padding(top = (4 * k).dp).height(1.dp).background(Color(0x22FFFFFF)))
    }
}

/**
 * The folded window: a half capsule peeking from the edge, holding a tiny phone whose outline breathes in the same
 * colour as the big window (rainbow while the AI acts) — the app's own mark, so it reads as "the background screen".
 */
@Composable
internal fun DockTab(mode: Mode, right: Boolean, asking: Int = 0) {
    val shape = if (right) RoundedCornerShape(topStart = 30.dp, bottomStart = 30.dp) else RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp)
    val glass = listOf(Color(0xF0262A38), Color(0xF014161F))
    // 1.3 beta2: an agent is waiting for an answer: the folded tab says so by itself (the user may never open the
    // notification shade). Amber, pulsing, with a question mark where the little phone is: unlike standby and acting.
    if (asking > 0) {
        // 1.3 beta3: the same glass as the window, an orchid edge that breathes, and the question mark in its place.
        val pulse = if (animateLook) {
            val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "ask")
            t.animateFloat(0.35f, 1f, androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse), label = "pulse").value
        } else 1f
        Box(
            Modifier.width(Overlay.TAB_W.dp).height(Overlay.TAB_H.dp)
                .shadow(14.dp, shape, ambientColor = Palette.asking, spotColor = Palette.asking)
                .glassDark(shape)
                .border(1.5.dp, Brush.verticalGradient(listOf(Color(0xFF8B7CFF).copy(alpha = pulse), Palette.asking.copy(alpha = pulse))), shape),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.offset(x = if (right) 2.dp else (-2).dp)) { AskMark(18f, asking, 0.55f + 0.45f * pulse) }
        }
        return
    }
    Box(
        Modifier.width(Overlay.TAB_W.dp).height(Overlay.TAB_H.dp)
            .shadow(10.dp, shape)
            .clip(shape)
            .background(Brush.horizontalGradient(if (right) glass else glass.reversed()))
            .border(1.dp, Color(0x2EFFFFFF), shape),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.offset(x = if (right) 2.dp else (-2).dp).size(width = 11.dp, height = 20.dp)
                .glowBorder(mode, 3.5.dp, width = 1.6.dp, pad = 2.dp)
                .clip(RoundedCornerShape(3.5.dp)).background(Color(0xFF0B0D14)),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // A navigation bar at its bottom: it reads as a phone screen, not an empty frame.
            Box(Modifier.padding(bottom = 2.dp).width(5.dp).height(1.2.dp).clip(RoundedCornerShape(1.dp)).background(Color(0xCCFFFFFF)))
        }
    }
}
