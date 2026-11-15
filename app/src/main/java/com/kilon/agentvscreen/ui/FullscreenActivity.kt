package com.kilon.agentvscreen.ui

import com.kilon.agentvscreen.tr

import android.os.Bundle
import android.os.SystemClock
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloseFullscreen
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
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
import androidx.compose.runtime.mutableFloatStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * The background screen full screen, as a native page: edge-to-edge and immersive, the picture fitted with the same
 * breathing border as the floating window. Hold the picture 1 s to take over; then taps, long presses and swipes go
 * to the background screen, and the bottom bar gives back and hand-back. Typing works like the floating window's
 * (see [ImeBridge]): after a takeover tap on an input box, the system keyboard pops up and the text is committed
 * to that box. Stop, hand back and "move this app to my screen" are one tap away.
 */
class FullscreenActivity : ComponentActivity() {
    // The keyboard bridge, same ImeBridge as the floating window — a hidden 1x1 sink in this window
    // summons the system keyboard for the background screen's focused field. An activity window is
    // focusable already, so setFocusable is a no-op here.
    private var imeBridge: ImeBridge? = null
    // The floating window shows the same picture: it steps aside while this page is in front.
    override fun onStart() { super.onStart(); Overlay.fullscreenShown(true) }
    override fun onStop() { imeBridge?.close(); Overlay.fullscreenShown(false); super.onStop() }
    override fun onDestroy() { imeBridge?.detach(); imeBridge = null; super.onDestroy() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 历史命令存在文件里；服务一般已经初始化过，这里再调一次是防万一（幂等）。
        VScreenTerminalHistory.init(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            AvsTheme {
                val s by BackgroundOperation.state.collectAsState()
                // No background screen and no session yet is fine: the page shows the terminal
                // and its history, and picks the screen up when an app is launched later.
                FullScreen(s.ownerSessionId)
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

        Box(Modifier.fillMaxSize().background(Color(0xFF07080D))) {
            // The picture, fitted, with its border — or the terminal when the shared page says so
            // (no background screen yet, or the user switched to it; the screen is picked up when
            // an app is launched). The floating window shows the same page: one state, both places.
            if (s.page == BackgroundOperation.ViewPage.SCREEN && s.displayId != null && session != null) {
            BoxWithConstraints(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(top = 56.dp, bottom = 96.dp, start = 14.dp, end = 14.dp),
                contentAlignment = Alignment.Center) {
                val aspect = Overlay.aspect()
                val boxAspect = maxWidth / maxHeight
                val pw = if (aspect > boxAspect) maxWidth else maxHeight * aspect
                val ph = pw / aspect
                Box(Modifier.size(pw, ph).glowBorder(mode, 22.dp, width = 3.dp, pad = 10.dp)) {
                    Box(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(Palette.ink)
                        .pointerInput(session, takenOver) {
                            awaitEachGesture {
                                val down = awaitFirstDown()
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
                                        hold = ((elapsed - 150L) / 850f).coerceIn(0f, 1f); holdAt = down.position
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
                        VScreenLiveFrame(session, Modifier.fillMaxSize(), interactive = false)
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

            // Top bar.
            Row(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Circle(Icons.Filled.CloseFullscreen, tr("退出全屏", "Exit full screen"), Color(0x33FFFFFF)) { finish() }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(appLabel ?: if (s.displayId == null) tr("终端", "Terminal") else tr("后台副屏", "Background screen"), color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (mode == Mode.ACTING) Brush.sweepGradient(Palette.rainbow) else SolidColor(Palette.of(mode))))
                        Spacer(Modifier.width(5.dp))
                        // Same text as the floating window's title pill (the agent's
                        // current step when set); long text scrolls sideways, like there.
                        val word = agentTitleText(s, left)
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
                when (mode) {
                    Mode.ACTING, Mode.STANDBY -> Pill(tr("停止 AI", "Stop AI"), Icons.Filled.Stop, Palette.stopped, Color.White) { BackgroundOperation.userStop() }
                    // "Close screen" only makes sense once there is a screen; with none, the
                    // left "exit full screen" button is the way out.
                    else -> {
                        if (s.displayId != null && session != null) Circle(Icons.Filled.Close, tr("关闭副屏", "Close screen"), Color(0x33FFFFFF)) {
                            BackgroundOperation.releaseNow(session); finish()
                        }
                    }
                }
            }

            // Hint.
            var now by remember { mutableLongStateOf(0L) }
            LaunchedEffect(hintAt) { now = SystemClock.uptimeMillis(); delay(2600); now = SystemClock.uptimeMillis() }
            AnimatedVisibility(now - hintAt < 2500 && hintAt > 0, Modifier.align(Alignment.Center), enter = fadeIn(), exit = fadeOut()) {
                Text(tr("长按画面 1 秒接管，接管后点、长按、滑动都会落到副屏上", "Hold the picture 1 s to take over; then taps, long presses and swipes go to the screen"), color = Color.White, fontSize = 13.sp,
                    modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color(0xE612141C)).padding(horizontal = 14.dp, vertical = 8.dp))
            }

            // Bottom bar.
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().navigationBarsPadding().padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                // 底部药丸条：窄屏上三个按钮放不下就自动换行，不裁掉。
                FlowRow(Modifier.clip(RoundedCornerShape(30.dp)).background(Color(0xE612141C)).padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    itemVerticalAlignment = Alignment.CenterVertically) {
                    if (takenOver) {
                        Circle(Icons.AutoMirrored.Filled.ArrowBack, tr("返回", "Back"), Color(0x22FFFFFF)) { vscreenUserInput(session) { VScreenShell.key(it, "back") } }
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
                                    toast(msg); if (ok) runOnUiThread { finish() }
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
    LaunchedEffect(entries.size, autoScroll) {
        if (entries.isEmpty() || !autoScroll) return@LaunchedEffect
        listState.scrollToItem(entries.size - 1)
    }
    Column(Modifier.fillMaxSize().clip(RoundedCornerShape(22.dp)).background(Color(0xFF0B0D14)).padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tr("终端", "Terminal"), color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.width(6.dp))
            Text(tr("KernelSU root · uid 0", "KernelSU root · uid 0"), color = Color(0x88FFFFFF), fontSize = 12.sp)
            Spacer(Modifier.width(6.dp))
            Text(tr("长按命令复制", "Long-press a command to copy"), color = Color(0x66FFFFFF), fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            // r38: 自动滚到底部开关（小药丸，同“清空”一样高，点一下切换；开=蓝字带 ✓，关=灰字）。
            Text(if (autoScroll) tr("自动滚 ✓", "Auto-scroll ✓") else tr("自动滚", "Auto-scroll"),
                color = if (autoScroll) Color(0xFF7CC8FF) else Color(0x66FFFFFF), fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x22FFFFFF))
                    .clickable { BackgroundOperation.setTermAutoscroll(!autoScroll) }.padding(horizontal = 10.dp, vertical = 4.dp))
            Spacer(Modifier.width(6.dp))
            Text(tr("清空", "Clear"), color = Color(0xFF7CC8FF), fontSize = 13.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(Color(0x22FFFFFF))
                    .clickable { VScreenTerminal.clear() }.padding(horizontal = 10.dp, vertical = 4.dp))
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
        return pictureToScreen(p.x.coerceIn(0f, w - 1), p.y.coerceIn(0f, h - 1), r)
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
internal fun vscreenUserInput(sessionId: String?, op: (Int) -> Unit) {
    if (sessionId == null) return
    val s = BackgroundOperation.state.value
    val id = s.displayId ?: return
    if (s.takeoverSessionId != sessionId) return
    Thread { runCatching { op(id) } }.apply { isDaemon = true; name = "vscreen-user-input" }.start()
}
