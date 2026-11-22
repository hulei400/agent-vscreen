package com.kilon.agentvscreen.ui

import androidx.compose.foundation.layout.offset
import com.kilon.agentvscreen.steps
import com.kilon.agentvscreen.tr

import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.systemGestures
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.material.icons.automirrored.filled.Backspace
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.alpha
import androidx.compose.foundation.layout.ime
import com.kilon.agentvscreen.core.VScreenTyping
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ScreenRotation
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TouchApp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.kilon.agentvscreen.api.Turns
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenHandoff
import com.kilon.agentvscreen.core.VScreenShell
import com.kilon.agentvscreen.core.VScreenTerminal
import com.kilon.agentvscreen.core.VScreenTerminalHistory
import com.kilon.agentvscreen.cmd.Teach
import com.kilon.agentvscreen.host.UserAsk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.hypot

/**
 * The background screen full screen, as a native page: edge-to-edge and immersive, the picture fitted with the same
 * breathing border as the floating window. Hold the picture 0.5 s to take over; then taps, long presses and swipes go
 * to the background screen, and the bottom bar gives back and hand-back. Typing works like the floating window's
 * (see [ImeBridge]): after a takeover tap on an input box, the system keyboard pops up and the text is committed
 * to that box. Stop, hand back and "move this app to my screen" are one tap away.
 */
class FullscreenActivity : PageActivity() {
    override val closeWhenHidden = true
    // The keyboard bridge, same ImeBridge as the floating window — a hidden 1x1 sink in this window
    // summons the system keyboard for the background screen's focused field. An activity window is
    // focusable already, so setFocusable is a no-op here.
    private var imeBridge: ImeBridge? = null

    /** 1.3 示教: opened from an agent's "show me how" (UserAsk TEACH): take over (1.4.0-beta4: to get ready, not recording yet). */
    private val teachAsk = mutableStateOf(-1)
    /** 1.4.0-beta4: opened to answer this question (its card comes first). */
    private val askOpen = mutableStateOf(-1)
    /** 1.4.0-beta11: which pane this page shows (the live screen, or saving a lesson). Switching never starts a page. */
    private val pane = mutableStateOf(Pane.LIVE)
    private fun paneOf(i: android.content.Intent?) = runCatching { Pane.valueOf(i?.getStringExtra(Surface.EXTRA) ?: "") }.getOrNull()
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent); teachAsk.value = intent.getIntExtra("teach_ask", -1); askOpen.value = intent.getIntExtra("ask", askOpen.value)
        paneOf(intent)?.let { pane.value = it; Surface.report(it) }
        sayNow.value = intent.getBooleanExtra(Surface.SAY, false); if (sayNow.value) BriefPlace.ball = false
    }
    /** 1.4.0-beta12: opened from the floating window's 说一句: the card opens with its box. */
    private val sayNow = mutableStateOf(false)
    // PageActivity.onStart/onStop already step the floating window aside (Overlay.fullscreenShown);
    // here we only add the lesson-pane report and the keyboard-bridge close.
    override fun onStart() { super.onStart(); Surface.report(pane.value) }
    override fun onStop() { imeBridge?.close(); Surface.report(null); super.onStop() }
    override fun onDestroy() { imeBridge?.detach(); imeBridge = null; super.onDestroy() }

    /** System bars: shown while saving or holding the screen (so the system's own edge gestures stay the system's). */
    private fun bars(hidden: Boolean) = WindowInsetsControllerCompat(window, window.decorView).run {
        if (hidden) { hide(WindowInsetsCompat.Type.systemBars()); systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE }
        else show(WindowInsetsCompat.Type.systemBars())
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (MainDisplay.moved(this)) return
        // 历史命令存在文件里；服务一般已经初始化过，这里再调一次是防万一（幂等）。
        VScreenTerminalHistory.init(applicationContext)
        teachAsk.value = intent.getIntExtra("teach_ask", -1)
        askOpen.value = intent.getIntExtra("ask", -1)
        paneOf(intent)?.let { pane.value = it }
        sayNow.value = intent.getBooleanExtra(Surface.SAY, false); if (sayNow.value) BriefPlace.ball = false
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        bars(true)
        setContent {
            AvsTheme {
                val s by BackgroundOperation.state.collectAsState()
                val lesson by Teach.lesson.collectAsState()
                val session = s.ownerSessionId
                val p by pane
                val screenUp = session != null && s.displayId != null
                val saving = lesson is com.kilon.agentvscreen.cmd.Lesson.Review || lesson is com.kilon.agentvscreen.cmd.Lesson.Ending
                // Fork: no background screen and no session yet is fine — the page stays open showing the
                // terminal (FullScreen(null) below) and picks the screen up when an app is launched later.
                // A lesson waiting to be saved keeps this page (its save pane); saved or discarded on the
                // save pane: back to the floating window.
                LaunchedEffect(screenUp, saving, p) {
                    when {
                        p == Pane.REVIEW && !saving -> back()
                        !screenUp && saving -> { pane.value = Pane.REVIEW; Surface.report(Pane.REVIEW) }
                    }
                }
                val takenOver = s.takeoverSessionId != null
                LaunchedEffect(p, takenOver) { bars(p == Pane.LIVE && !takenOver) }
                when {
                    p == Pane.REVIEW -> when (val l = lesson) {
                        is com.kilon.agentvscreen.cmd.Lesson.Review -> Box(Modifier.fillMaxSize().backdrop()) {
                            ReviewPane(l.result, l.screenGone || !screenUp, onLeave = { back() }, onAgain = {
                                if (Teach.again()) { pane.value = Pane.LIVE; Surface.report(Pane.LIVE) }
                                else toast(tr("副屏已经关了，没法重录", "The background screen is closed; cannot record again"))
                            })
                        }
                        else -> Box(Modifier.fillMaxSize().backdrop(), contentAlignment = Alignment.Center) {
                            Text(tr("正在整理录下的步骤…", "Writing down the steps…"), fontSize = 15.sp)
                        }
                    }
                    session != null -> FullScreen(session)
                    // Fork: no background screen and no session yet is fine — the page shows the terminal
                    // and its history, and picks the screen up when an app is launched later.
                    else -> FullScreen(null)
                }
            }
        }
        imeBridge = (window.decorView as? ViewGroup)?.let { ImeBridge(this, it) { /* already focusable */ } }
    }

    private fun toast(msg: String) = runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }

    @Composable
    private fun FullScreen(session: String?) {
        val s by BackgroundOperation.state.collectAsState()
        val mode = modeOf(s)
        val takenOver = mode == Mode.TAKEOVER

        var hold by remember { mutableFloatStateOf(0f) }
        var holdAt by remember { mutableStateOf(Offset.Zero) }
        var hintAt by remember { mutableLongStateOf(0L) }
        var appLabel by remember { mutableStateOf<String?>(null) }
        var showHistory by remember { mutableStateOf(false) }
        var left by remember { mutableLongStateOf(-1L) }
        // The terminal log, for the no-screen state (and kept warm for a late screen).
        val termLog by VScreenTerminal.log.collectAsState()
        LaunchedEffect(s.displayId, s.operating) {
            val id = s.displayId ?: return@LaunchedEffect
            appLabel = withContext(Dispatchers.IO) {
                runCatching { VScreenShell.topPackage(id) }.getOrNull()?.let { pkg ->
                    runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                }
            }
        }
        LaunchedEffect(session) { val sid = session ?: return@LaunchedEffect; while (true) { left = Turns.leftMs(sid) ?: -1L; delay(1000) } }
        // The keyboard bridge closes when the user hands the screen back or leaves the screen page.
        LaunchedEffect(takenOver) { if (!takenOver) imeBridge?.close() }
        LaunchedEffect(mode, s.page) {
            if (mode != Mode.TAKEOVER || s.page != BackgroundOperation.ViewPage.SCREEN) imeBridge?.close()
        }
        val lesson by Teach.lesson.collectAsState()
        val prepId = (lesson as? com.kilon.agentvscreen.cmd.Lesson.Ready)?.askId
        val paused = (lesson as? com.kilon.agentvscreen.cmd.Lesson.Recording)?.paused == true
        // 1.4.0-beta5: what a hand-back does to a lesson is decided in one place (Teach.onTakeoverEnded), for every way back.
        val teachN = (lesson as? com.kilon.agentvscreen.cmd.Lesson.Recording)?.steps
        val recording = teachN != null
        // 1.4.0-beta4: the one request in front: the lesson under way, else the question this page was opened for, else the newest.
        val waiting = pendingAsks()
        val focus by askOpen
        val req: UserAsk.Ask? = when {
            recording -> Teach.recAskId()?.let { UserAsk.byId(it) }
            prepId != null -> UserAsk.byId(prepId)?.takeIf { it.live }
            else -> waiting.firstOrNull { it.id == focus } ?: waiting.firstOrNull()
        }
        // 1.4.0-beta5: every new request opens unfolded. The folded ball was kept from an earlier request (and folded
        // by itself during a lesson), so a lesson's Start and a text question's Send were hidden behind it.
        LaunchedEffect(req?.id, recording, prepId) { BriefPlace.ball = false; BriefPlace.open = true }
        // 1.4.0-beta15 (rainbow): once there was time to read it (longer text, longer time), the card shrinks by itself
        // to the ball at the top right, out of the picture's way; a tap on the ball opens it again. Not while the user
        // is writing a reply in it.
        LaunchedEffect(req?.id, recording, prepId, sayNow.value) {
            val r = req ?: return@LaunchedEffect
            if (sayNow.value) return@LaunchedEffect
            delay((2_500L + (r.title.length + r.detail.length) * 80L).clampIn(4_000L, 12_000L))
            if (!sayNow.value) BriefPlace.ball = true
        }
        val mark = remember { MarkState() }
        val marking = req != null && !recording && (req.kind == UserAsk.Kind.POINT || req.kind == UserAsk.Kind.AREA) && !mark.finding && !takenOver
        var picSize by remember { mutableStateOf(androidx.compose.ui.unit.IntSize.Zero) }
        var picAt by remember { mutableStateOf(Offset.Zero) }
        val gi = WindowInsets.systemGestures
        val win = androidx.compose.ui.platform.LocalWindowInfo.current.containerSize
        val ld = androidx.compose.ui.platform.LocalLayoutDirection.current
        val edges = GestureEdges(gi.getLeft(LocalDensity.current, ld).toFloat(), gi.getTop(LocalDensity.current).toFloat(),
            gi.getRight(LocalDensity.current, ld).toFloat(), gi.getBottom(LocalDensity.current).toFloat(), win.width.toFloat(), win.height.toFloat())
        fun markReply(): org.json.JSONObject? {
            val a = mark.a ?: return null
            val w = picSize.width.toFloat(); val h = picSize.height.toFloat()
            if (w <= 0f || h <= 0f) return null
            fun scr(o: Offset) = pictureToScreen(o.x.clampIn(0f, w - 1), o.y.clampIn(0f, h - 1), android.graphics.RectF(0f, 0f, w, h))
            return when (req?.kind) {
                UserAsk.Kind.POINT -> scr(a)?.let { (x, y) -> org.json.JSONObject().put("kind", "point").put("x", x).put("y", y) }
                UserAsk.Kind.AREA -> { val b = mark.b ?: return null; if (kotlin.math.hypot(a.x - b.x, a.y - b.y) < 12f) return null
                    val p = scr(a) ?: return null; val q = scr(b) ?: return null
                    org.json.JSONObject().put("kind", "area").put("x1", minOf(p.first, q.first)).put("y1", minOf(p.second, q.second)).put("x2", maxOf(p.first, q.first)).put("y2", maxOf(p.second, q.second)) }
                else -> null
            }
        }
        val askId by teachAsk
        LaunchedEffect(askId) {
            val a = UserAsk.byId(askId) ?: return@LaunchedEffect
            teachAsk.value = -1
            Teach.beginFor(a)
        }

        // 1.3.0-beta17: the picture is the fixed floor of the page. Its place depends only on the title bar above and
        // the button row below at rest, never on the keyboard, the typing box, a question or the request card: those
        // float over it (beta16 squeezed the picture into whatever space they left, a thumbnail once the keyboard
        // opened). Floating things never leak through: each is a hit target of its own (eatTouches / clickable /
        // its drag), and Compose gives a touch only to the topmost thing it lands on. The request card folds into the
        // ball in the bar as soon as the user touches the picture, so it never hides what they are about to tap.
        val density = LocalDensity.current
        var topEdge by remember { mutableFloatStateOf(0f) }
        var rowH by remember { mutableFloatStateOf(0f) }
        val navBottom = WindowInsets.navigationBars.getBottom(density).toFloat()
        val showCard = req != null || recording || prepId != null
        Box(Modifier.fillMaxSize().background(Color(0xFF07080D))) {
            // The picture, fitted, with its border — or the terminal when the shared page says so
            // (no background screen yet, or the user switched to it; the screen is picked up when
            // an app is launched). The floating window shows the same page: one state, both places.
            if (s.page == BackgroundOperation.ViewPage.SCREEN && s.displayId != null && session != null) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(top = with(density) { topEdge.toDp() } + 6.dp,
                bottom = with(density) { (rowH + navBottom).toDp() } + 24.dp + 6.dp, start = 14.dp, end = 14.dp),
                contentAlignment = Alignment.Center) {
                val aspect = Overlay.aspect()
                val boxAspect = maxWidth / maxHeight
                val pw = if (aspect > boxAspect) maxWidth else maxHeight * aspect
                val ph = pw / aspect
                Box(Modifier.size(pw, ph).glowBorder(mode, 22.dp, width = 3.dp, pad = 10.dp)) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(Palette.ink).onSizeChanged { picSize = it }
                        .onGloballyPositioned { picAt = it.positionInWindow() }
                        .pointerInput(session, takenOver, marking, edges) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
                                // 1.4.0-beta11 (DESIGN §4.1): a finger that starts in the system's gesture area (home,
                                // back, the status bar) is the system's: never sent to the screen, never a lesson step.
                                if (takenOver && edges.has(down.position + picAt)) return@awaitEachGesture
                                // 1.4.0-beta4: answering point / area: the finger marks the live picture instead.
                                if (marking) {
                                    mark.a = down.position; mark.b = down.position
                                    while (true) {
                                        val ev = awaitPointerEvent()
                                        val ch = ev.changes.firstOrNull() ?: break
                                        if (req?.kind == UserAsk.Kind.AREA) mark.b = ch.position else mark.a = ch.position
                                        ch.consume()
                                        if (!ch.pressed) break
                                    }
                                    return@awaitEachGesture
                                }
                                val t0 = SystemClock.uptimeMillis()
                                // 1.2.0-beta2: while the user controls the screen the finger is sent as it moves
                                // (UserTouch), not replayed as one swipe after it lifts.
                                val live = takenOver && map(down.position, size.width.toFloat(), size.height.toFloat())
                                    ?.let { com.kilon.agentvscreen.core.UserTouch.down(session, it.first, it.second) } == true
                                var end = down.position
                                var moved = false
                                var held = false
                                while (true) {
                                    val ev = withTimeoutOrNull(16L) { awaitPointerEvent() }
                                    val elapsed = SystemClock.uptimeMillis() - t0
                                    if (!takenOver && !moved && !held) {
                                        hold = ((elapsed - 100L) / 400f).clampIn(0f, 1f); holdAt = down.position
                                        if (hold >= 1f) {
                                            held = true; hold = 0f
                                            if (s.userStopped) BackgroundOperation.userResume()
                                            BackgroundOperation.beginTakeover(session)
                                        }
                                    }
                                    if (ev == null) continue
                                    val ch = ev.changes.firstOrNull() ?: break
                                    end = ch.position
                                    if (live && ch.pressed) map(end, size.width.toFloat(), size.height.toFloat())?.let { com.kilon.agentvscreen.core.UserTouch.move(it.first, it.second) }
                                    if (hypot(end.x - down.position.x, end.y - down.position.y) > viewConfiguration.touchSlop) { moved = true; hold = 0f }
                                    if (takenOver) ch.consume()
                                    if (!ch.pressed) break
                                }
                                hold = 0f
                                val ms = SystemClock.uptimeMillis() - t0
                                if (live) {
                                    val b = map(end, size.width.toFloat(), size.height.toFloat())
                                    com.kilon.agentvscreen.core.UserTouch.up(b?.first ?: -1, b?.second ?: -1)
                                    // The tap may have focused an input box: check the keyboard bridge,
                                    // same as the floating window after a takeover touch.
                                    imeBridge?.check()
                                } else if (takenOver) {
                                } else if (!held && !moved) hintAt = SystemClock.uptimeMillis()
                            }
                        }) {
                        VScreenLiveFrame(session, Modifier.fillMaxSize())
                        if (marking) Canvas(Modifier.fillMaxSize()) {
                            val pa = mark.a; val pb = mark.b
                            if (req?.kind == UserAsk.Kind.POINT && pa != null) {
                                drawCircle(Color(0x55000000), 34f, pa); drawCircle(Palette.asking, 26f, pa, style = Stroke(6f))
                            } else if (pa != null && pb != null) {
                                val tl = Offset(minOf(pa.x, pb.x), minOf(pa.y, pb.y)); val sz = Size(kotlin.math.abs(pa.x - pb.x), kotlin.math.abs(pa.y - pb.y))
                                drawRect(Palette.asking.copy(alpha = 0.18f), tl, sz); drawRect(Palette.asking, tl, sz, style = Stroke(5f))
                            }
                        }
                        if (hold > 0f) Canvas(Modifier.fillMaxSize()) {
                            val r = 40.dp.toPx()
                            drawCircle(Color(0x66000000), r + 8f, holdAt)
                            drawArc(Palette.takeover, -90f, 360f * hold, false, topLeft = Offset(holdAt.x - r, holdAt.y - r), size = Size(r * 2, r * 2),
                                style = Stroke(width = 8f, cap = StrokeCap.Round))
                        }
                    }
                }
            }
            } else {
                Box(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 56.dp, bottom = 96.dp, start = 14.dp, end = 14.dp)) {
                    FullscreenTerminalView(termLog)
                }
            }

            // Top block: the bar (it sets the picture's top), and the request card floating under it when at the top.
            Column(Modifier.fillMaxWidth().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().onGloballyPositioned { topEdge = it.boundsInRoot().bottom }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                // 1.4.0-beta11: 小窗 only leaves the page; a lesson goes on in the floating window (it never stops one).
                Circle(Icons.Filled.CloseFullscreen, tr("小窗", "Small window"), Color(0x33FFFFFF)) { back() }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(appLabel ?: if (s.displayId == null) tr("终端", "Terminal") else tr("后台副屏", "Background screen"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (mode == Mode.ACTING) Brush.sweepGradient(Palette.rainbow) else SolidColor(Palette.of(mode))))
                        Spacer(Modifier.width(5.dp))
                        // Same text as the floating window's title pill (the agent's
                        // current step when set); long text scrolls sideways, like there.
                        // During a lesson the recording state wins over the agent title.
                        val word = if (recording) tr("示教录制中 · 已记 $teachN 步", "Recording a lesson · $teachN steps") else if (prepId != null) tr("示教准备中：先走到起点", "Getting ready to show") else agentTitleText(s, left)
                        Text(word, Modifier.weight(1f, fill = false).basicMarquee(initialDelayMillis = 1000, velocity = 30.dp), color = Color(0xCCFFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Visible)
                    }
                }
                // 画面 / 终端 切换：同一份共享状态，浮窗和这里读同一份，一边切了另一边也跟着切。
                // 没副屏时只有终端页，开关不显示。
                if (s.displayId != null && session != null) {
                    TextPill(if (s.page == BackgroundOperation.ViewPage.TERMINAL) tr("画面", "Screen") else tr("终端", "Terminal")) {
                        BackgroundOperation.setPage(if (s.page == BackgroundOperation.ViewPage.TERMINAL) BackgroundOperation.ViewPage.SCREEN else BackgroundOperation.ViewPage.TERMINAL)
                    }
                    Spacer(Modifier.width(10.dp))
                }
                // 1.4.0-beta4: turn the page to landscape and back, whatever the phone's rotation lock says (a
                // landscape app is small on an upright page). Without recreating the page: the takeover goes on.
                val wide = androidx.compose.ui.platform.LocalConfiguration.current.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                // 1.4.0-beta5: only when it helps: a landscape picture on an upright page, or the page already turned.
                if (wide || s.screenWidth > s.screenHeight) {
                    Circle(Icons.Filled.ScreenRotation, if (wide) tr("转回竖屏", "Back to portrait") else tr("转成横屏", "Turn to landscape"), Color(0x33FFFFFF)) {
                        requestedOrientation = if (wide) android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
                    }
                    Spacer(Modifier.width(8.dp))
                }
                when {
                    recording || prepId != null -> {} // ended with 完成 / 不教 on the strip, not by closing the screen
                    mode == Mode.ACTING || mode == Mode.STANDBY -> Pill(tr("停止 AI", "Stop AI"), Icons.Filled.Stop, Palette.stopped, Color.White) { BackgroundOperation.userStop() }
                    // "Close screen" only makes sense once there is a screen; with none, the
                    // left "exit full screen" button is the way out.
                    else -> {
                        if (s.displayId != null && session != null) Circle(Icons.Filled.Close, tr("关闭副屏", "Close screen"), Color(0x33FFFFFF)) {
                            BackgroundOperation.releaseNow(session); back()
                        }
                    }
                }
                    }

            // 1.3.0-beta14: what the agent asked to be shown, in full, while the user shows it.
            if (showCard && session != null && !BriefPlace.ball && !BriefPlace.bottom) RequestCard(req, session, recording, prepId != null && !recording, teachN ?: 0, paused, takenOver, mark, ::markReply,
                Modifier.padding(start = 14.dp, end = 14.dp, bottom = 4.dp), onFold = { BriefPlace.ball = true }, onMove = { BriefPlace.bottom = true }, atBottom = false, startSaying = sayNow.value)
            }

            // 1.4.0-beta7 (rainbow: the card over the page could not be moved; earlier versions folded it into a button
            // that could be dragged): folded, the request is a ball floating over the picture, dragged anywhere, tapped
            // to open again.
            if (showCard && BriefPlace.ball) {
                Box(Modifier.align(Alignment.TopEnd).padding(top = 96.dp, end = 16.dp)
                    .offset { androidx.compose.ui.unit.IntOffset(BriefPlace.dx.toInt(), BriefPlace.dy.toInt()) }
                    .size(52.dp).clip(CircleShape).background(Color(0xCC1A1530)).border(1.5.dp, Palette.asking, CircleShape)
                    .pointerInput(Unit) { detectDragGestures { ch, d -> ch.consume(); BriefPlace.dx += d.x; BriefPlace.dy += d.y } }
                    .clickable { BriefPlace.ball = false; BriefPlace.open = true },
                    contentAlignment = Alignment.Center) { AskMark(30f) }
            }

            // Hint.
            var now by remember { mutableLongStateOf(0L) }
            LaunchedEffect(hintAt) { now = SystemClock.uptimeMillis(); delay(2600); now = SystemClock.uptimeMillis() }
            AnimatedVisibility(now - hintAt < 2500 && hintAt > 0, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Text(tr("长按画面 0.5 秒接管，接管后点、长按、滑动都会落到副屏上", "Hold the picture 0.5 s to take over; then taps, long presses and swipes go to the screen"), color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xE612141C)).padding(horizontal = 14.dp, vertical = 8.dp))
            }

            // Bottom block: floats over the picture and rides on top of the keyboard.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (showCard && session != null && !BriefPlace.ball && BriefPlace.bottom) RequestCard(req, session, recording, prepId != null && !recording, teachN ?: 0, paused, takenOver, mark, ::markReply,
                    onFold = { BriefPlace.ball = true }, onMove = { BriefPlace.bottom = false }, atBottom = true, startSaying = sayNow.value)
                // 底部药丸条：窄屏上三个按钮放不下就自动换行，不裁掉。
                // 打字走 ImeBridge（fork r18：全屏不另搞一套输入框），这里没有上游的 FieldMirror 打字盒。
                FlowRow(Modifier.onSizeChanged { rowH = it.height.toFloat() }.eatTouches().clip(RoundedCornerShape(30.dp)).background(Color(0xE612141C)).padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    if (takenOver) {
                        Circle(Icons.AutoMirrored.Filled.ArrowBack, tr("返回", "Back"), Color(0x22FFFFFF)) { vscreenUserInput(session) { Teach.key("back"); VScreenShell.key(it, "back") } }
                        // 示教: record what you do here as steps an agent can run again (its controls are on the card).
                        if (!recording && prepId == null && !mark.finding) {
                            Circle(Icons.Filled.FiberManualRecord, tr("示教：录下接下来的操作", "Teach: record what you do next"), Color(0x22FFFFFF)) {
                                session?.let { sid ->
                                    if (Teach.start(sid, null, null)) toast(if (Teach.recAskId() != null) tr("开始录制：照常操作，做完点“完成”", "Recording: do it as usual, then tap Done")
                                        else tr("开始录制。没有 agent 在等，这段会存为你的经验", "Recording. No agent is waiting: it is saved as your own"))
                                }
                            }
                        }
                        Pill(tr("交还 AI", "Hand back"), Icons.Filled.SmartToy, Palette.takeover, Color(0xFF052E1C)) { BackgroundOperation.userResume() }
                    } else {
                        if (mode == Mode.STOPPED) Pill(tr("交还 AI", "Hand back"), Icons.Filled.SmartToy, Palette.takeover, Color(0xFF052E1C)) { BackgroundOperation.userResume() }
                        // Take over / move to the main screen need a background screen; history
                        // works without one.
                        if (s.displayId != null && session != null) {
                            Pill(tr("接管", "Take over"), Icons.Filled.TouchApp, Color(0x22FFFFFF), Color.White) {
                                if (s.userStopped) BackgroundOperation.userResume()
                                BackgroundOperation.beginTakeover(session)
                            }
                            Pill(tr("移到主屏", "Move to main screen"), Icons.Filled.PhoneAndroid, Color(0x22FFFFFF), Color.White) {
                                Thread {
                                    val (ok, msg) = runCatching { VScreenHandoff.userToMain(session) }.getOrElse { false to (it.message ?: tr("失败", "failed")) }
                                    toast(msg); if (ok) runOnUiThread { back() }
                                }.start()
                            }
                        }
                        Pill(tr("历史命令", "History"), Icons.Filled.History, Color(0x22FFFFFF), Color.White) { showHistory = true }
                    }
                }
            }

            // 历史命令页：盖在画面和底栏上面，关掉就回到全屏。
            if (showHistory) TerminalHistoryPage(onClose = { showHistory = false })
        }
    }
}

/**
 * The terminal log, full screen, when there is no background screen yet. Same records as the
 * floating window's terminal page (long-press a command to copy it, "清空" clears them); the
 * history page covers everything that survived a restart.
 */
@Composable
private fun FullscreenTerminalView(entries: List<VScreenTerminal.Entry>) {
    val s by BackgroundOperation.state.collectAsState()
    val autoScroll = s.termAutoscroll
    // r38: 开关开着时打开页面就停在最下面（initialFirstVisibleItemIndex）；关着时从最上面开始。
    val listState = rememberLazyListState(
        initialFirstVisibleItemIndex = if (autoScroll && entries.isNotEmpty()) entries.size - 1 else 0)
    // r38: 开关开着时，每来一条新命令就直接滚到底部，不再判断“是否已在底部附近”。
    // 开关打开的瞬间也会滚到底部；关着时不自动滚，由用户手动滑。
    // r43: key 不能是 entries.size——内存日志满 50 条后 size 永远停在 50，key 不变、effect
    // 不再触发，跑多了命令就不滚了。改成最新一条的单调 id，每条新命令都会变，满了也照样滚。
    LaunchedEffect(entries.lastOrNull()?.id, autoScroll) {
        if (entries.isEmpty() || !autoScroll) return@LaunchedEffect
        listState.scrollToItem(entries.size - 1)
    }
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(Color(0xFF0B0D14)).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // r40: 头上的“终端”二字删掉（最顶层标题栏没有副屏时已经显示“终端”，这里不用重复）；其余不动。
            Text(tr("KernelSU root · uid 0", "KernelSU root · uid 0"), color = Color(0x88FFFFFF), fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text(tr("长按命令复制", "Long-press a command to copy"), color = Color(0x66FFFFFF), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            // r40: 自动滚到底部开关（光开关+旁边“滚动”二字，样式跟浮窗一致；开=蓝底，关=灰底；点一下切换；开关本身固定尺寸不缩）。
            Box(Modifier.size(width = 44.dp, height = 26.dp)
                .clip(RoundedCornerShape(50)).background(if (autoScroll) Color(0xFF7CC8FF) else Color(0x44FFFFFF))
                .clickable { BackgroundOperation.setTermAutoscroll(!autoScroll) }.padding(3.dp),
                contentAlignment = if (autoScroll) Alignment.CenterEnd else Alignment.CenterStart) {
                Box(Modifier.size(20.dp).clip(CircleShape).background(Color.White))
            }
            Spacer(Modifier.width(6.dp))
            Text(tr("滚动", "Auto-scroll"),
                color = if (autoScroll) Color(0xFF7CC8FF) else Color(0x66FFFFFF), fontSize = 15.sp,
                modifier = Modifier.clickable { BackgroundOperation.setTermAutoscroll(!autoScroll) })
            // r41: “清空”删掉（用户要两边都没有；VScreenTerminal.clear() 本身还在，shell --clear 照常清日志）。
        }
        Spacer(Modifier.height(8.dp))
        if (entries.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text(tr("还没有跑过命令\n用 vscreen shell 跑", "No commands run yet\nrun one with vscreen shell"),
                    color = Color(0x88FFFFFF), fontSize = 14.sp, lineHeight = 20.sp)
            }
        } else {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(end = 10.dp)) {
                    items(entries, key = { it.id }) { e -> TerminalEntryView(e, 1f) }
                }
                TerminalScrollbar(listState)
            }
        }
    }
}

    private fun androidx.compose.ui.input.pointer.PointerInputScope.map(p: Offset, w: Float, h: Float): Pair<Int, Int>? {
        val r = android.graphics.RectF(0f, 0f, w, h)
        return pictureToScreen(p.x.clampIn(0f, w - 1), p.y.clampIn(0f, h - 1), r)
    }

@Composable
private fun Circle(icon: ImageVector, desc: String, bg: Color, onClick: () -> Unit) {
    Box(Modifier.size(44.dp).clip(CircleShape).background(bg).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Icon(icon, desc, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun Pill(label: String, icon: ImageVector, bg: Color, fg: Color, onClick: () -> Unit) {
    Row(Modifier.height(44.dp).clip(RoundedCornerShape(22.dp)).background(bg).clickable(onClick = onClick).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Small text-only pill (the 画面 / 终端 switch in the top bar — same control as the floating window's). */
@Composable
private fun TextPill(label: String, onClick: () -> Unit) {
    Row(Modifier.height(36.dp).clip(RoundedCornerShape(18.dp)).background(Color(0x33FFFFFF)).clickable(onClick = onClick).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Runs [op] with the display id off the main thread, only while [sessionId] holds the screen (接管). */
/** The user's typing goes out on one lane, in order, newest value only (a burst of keystrokes is one write). */
private val typingLane = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "vscreen-typing").apply { isDaemon = true } }

/** 1.3.0-beta14: keeps the full screen box and the screen's focused field the same.
 * (Kept for upstream's typing path; the fork's own typing goes through ImeBridge instead.) */
internal class FieldMirror(private val session: String) {
    /** What the screen's field reads, as far as we know. */
    @Volatile var sent = ""
    private var start = ""
    private var key: String? = null
    @Volatile private var editAt = 0L
    private val latest = java.util.concurrent.atomic.AtomicReference<String?>(null)

    fun adopt(f: VScreenTyping.FieldNow?) { sent = f?.text.orEmpty(); start = sent; key = f?.key }
    /** The user typed in the last moment, or a write is on its way: do not follow the field now. */
    fun busy() = SystemClock.uptimeMillis() - editAt < 1_500 || latest.get() != null

    fun push(v: String) {
        editAt = SystemClock.uptimeMillis()
        latest.set(v)
        typingLane.execute {
            val x = latest.getAndSet(null) ?: return@execute
            val st = BackgroundOperation.state.value
            val id = st.displayId ?: return@execute
            if (st.takeoverSessionId != session) return@execute
            // 1.3.0 (test M): the lesson's "before typing" screen is read before the first character lands.
            if (x != start) runCatching { Teach.beforeTyping()?.get(1500, java.util.concurrent.TimeUnit.MILLISECONDS) }
            runCatching { VScreenTyping.setField(id, x, sent) }.onSuccess { sent = x; Teach.typingNow(if (x != start) x else null) }
        }
    }

    /** The field changed by itself: true when the box should show [g]. Another field: what was typed is one step. */
    fun follow(g: VScreenTyping.FieldNow): Boolean {
        if (g.key != key) { Teach.commitTyping(); key = g.key; start = g.text; sent = g.text; return true }
        if (g.password || g.text == sent) return false
        sent = g.text; start = g.text; Teach.typingNow(null)
        return true
    }
}

/** Where the request card is (kept while the app runs): top or bottom, open or one line, or a ball in the bar. */
private object BriefPlace {
    var open by mutableStateOf(true)
    var bottom by mutableStateOf(false)
    var ball by mutableStateOf(false)
    var dx by mutableStateOf(0f)
    var dy by mutableStateOf(0f)
}

internal fun vscreenUserInput(sessionId: String?, op: (Int) -> Unit) {
    if (sessionId == null) return
    val s = BackgroundOperation.state.value
    val id = s.displayId ?: return
    if (s.takeoverSessionId != sessionId) return
    Thread { runCatching { op(id) } }.apply { isDaemon = true; name = "vscreen-user-input" }.start()
}

/** The system's gesture areas along the window's edges (window pixels). */
internal data class GestureEdges(val l: Float, val t: Float, val r: Float, val b: Float, val w: Float, val h: Float) {
    fun has(p: Offset): Boolean = w > 0f && h > 0f && (p.x < l || p.y < t || p.x > w - r || p.y > h - b)
}
