package com.kilon.agentvscreen.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.geometry.Offset
import kotlin.math.roundToInt
import androidx.compose.ui.platform.LocalViewConfiguration
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.kilon.agentvscreen.R
import com.kilon.agentvscreen.tr
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.VScreenShell
import kotlin.math.abs

/**
 * True when the background screen is live right now: a display exists, it is not
 * paused, and an app is running on it. The app lookup reads the display's cached
 * task list — cheap — but it still runs off the main thread by convention.
 */
internal suspend fun hasLiveScreenApp(): Boolean {
    val s = BackgroundOperation.state.value
    val id = s.displayId ?: return false
    if (s.idle || s.powerSaving) return false
    return withContext(Dispatchers.IO) {
        runCatching { VScreenShell.topActivity(id) != null }.getOrDefault(false)
    }
}

/** What the picture tab says when there is no screen picture to show — no spinner. */
internal fun noPictureText(): String = tr(
    "还没有副屏画面：只跑命令时看“终端”页，开了应用这里才有画面",
    "No screen picture yet — shell output is on the “Terminal” tab; open an app and it shows here",
)

/** [vscreen] The live picture of the background screen (floating window and full screen). */
@Composable
fun VScreenLiveFrame(sessionId: String, modifier: Modifier = Modifier, interactive: Boolean = false) {
    // Follow the session's CURRENT screen. Before, the frame only recomposed on the old screen's
    // frame counter: after a rebuild (orientation / restart) the window kept the released screen
    // and showed "loading" or a stale picture forever, while the agent worked on the new one.
    val st = BackgroundOperation.state.collectAsState().value
    var tick by remember { mutableIntStateOf(0) }
    val display = remember(sessionId, st.displayId, st.ownerSessionId, tick) { BackgroundOperation.previewDisplayFor(sessionId) }
    LaunchedEffect(sessionId, display) {
        while (true) {
            delay(1000)
            if (BackgroundOperation.previewDisplayFor(sessionId) !== display) { tick++; break }
        }
    }
    if (display == null) {
        Box(modifier.background(Color(0xFFF2F2F7)), contentAlignment = Alignment.Center) {
            Text(noPictureText(), fontSize = 10.sp, color = Color.Gray, textAlign = TextAlign.Center,
                lineHeight = 13.sp, modifier = Modifier.padding(horizontal = 14.dp))
        }
        return
    }
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    // 1.2.0-beta2: the viewer's own pictures, at the size shown and at a rate made for eyes (30 a second while the
    // user controls the screen, 20 while watching), instead of the agent's full-size frames at 8 a second.
    val fast = st.takeoverSessionId == sessionId
    DisposableEffect(display, fast, viewSize) {
        val long = maxOf(viewSize.width, viewSize.height)
        if (long > 0) display.setPreview(if (fast) 33L else 50L, long)
        onDispose { }
    }
    DisposableEffect(display) { onDispose { display.clearPreview() } }
    // Recompose on each new frame while this composable is on screen.
    val version = display.frameVersion.collectAsState().value
    val shown = display.previewVersion.collectAsState().value
    val frame = remember(display, version, shown) { display.previewFrame() ?: display.latestFrame() }
    // A frame that is one flat colour (app gone, page not drawn yet) reads as a broken white box. Checked when the
    // agent's frame changes (8 a second at most), not for every picture shown.
    val blank = remember(display, version) { display.latestFrame()?.let { !it.isRecycled && isBlank(it) } == true }
    // v40: a blank frame with no app on the screen is the normal idle state, not a capture problem.
    val noApp = produceState(false, blank, display) {
        value = blank && withContext(Dispatchers.IO) {
            runCatching { VScreenShell.topActivity(display.displayId) == null }.getOrDefault(false)
        }
    }.value
    // Whether the picture tab has nothing to show at all: no display, a paused
    // screen, or a screen with no app on it. A first frame is only ever
    // "loading" on a live screen that is just starting; anything else is a
    // steady state, and the tab says so instead of spinning forever. The fast
    // path (no display / paused) is decided here; the app check is async.
    val noPicture = androidx.compose.runtime.produceState(st.idle || st.powerSaving || display == null, display, st.idle, st.powerSaving) {
        value = !hasLiveScreenApp()
    }.value

    Box(
        modifier
            .background(Color.White)
            .onSizeChanged { viewSize = it }
            .then(
                if (!interactive) Modifier
                else Modifier
                    .pointerInput(sessionId, display.generation) {
                        detectTapGestures(
                            onLongPress = { p ->
                                val (x, y) = map(p, viewSize, display.width, display.height)
                                vscreenUserInput(sessionId) { VScreenShell.longPress(it, x, y, 650) }
                            },
                        ) { p ->
                            val (x, y) = map(p, viewSize, display.width, display.height)
                            BackgroundOperation.userTap(sessionId, x, y)
                        }
                    }
                    .pointerInput(sessionId, display.generation, "drag") {
                        var start = Offset.Zero
                        var end = Offset.Zero
                        var startedAt = 0L
                        detectDragGestures(
                            onDragStart = { start = it; end = it; startedAt = System.currentTimeMillis() },
                            onDrag = { change, delta -> change.consume(); end += delta },
                            onDragEnd = {
                                val (x1, y1) = map(start, viewSize, display.width, display.height)
                                val (x2, y2) = map(end, viewSize, display.width, display.height)
                                val ms = (System.currentTimeMillis() - startedAt).toInt().coerceIn(80, 1500)
                                BackgroundOperation.userSwipe(sessionId, x1, y1, x2, y2, ms)
                            },
                        )
                    },
            ),
        contentAlignment = Alignment.Center,
    ) {
        if (frame != null && !frame.isRecycled) {
            // Always draw the real frame (a white loading page with a little text is content, and
            // a coarse sample used to replace it with "blank"); only label a truly flat frame.
            Image(
                bitmap = frame.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.FillBounds,
                modifier = Modifier.fillMaxSize(),
            )
            if (blank) Text(stringResource(if (noApp) R.string.vscreen_no_app else R.string.vscreen_blank_frame), fontSize = 9.sp, color = Color.Gray,
                modifier = Modifier.align(Alignment.BottomCenter).background(Color(0xCCFFFFFF)).padding(horizontal = 6.dp, vertical = 2.dp))
        } else if (noPicture) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(horizontal = 14.dp)) {
                Text(noPictureText(), fontSize = 10.sp, color = Color.Gray, textAlign = TextAlign.Center, lineHeight = 13.sp)
            }
        } else {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                Text(tr("正在加载…", "Loading…"), fontSize = 9.sp, color = Color.Gray)
            }
        }
    }
}

private fun map(p: Offset, view: IntSize, w: Int, h: Int): Pair<Int, Int> {
    if (view.width == 0 || view.height == 0 || w <= 0 || h <= 0 || !p.x.isFinite() || !p.y.isFinite()) return 0 to 0
    val x = (p.x / view.width * w).roundToInt().coerceIn(0, w - 1)
    val y = (p.y / view.height * h).roundToInt().coerceIn(0, h - 1)
    return x to y
}

/** True when a 47x47 sample of the frame is all (almost) the same colour. */
private fun isBlank(b: android.graphics.Bitmap): Boolean = runCatching {
    val first = b.getPixel(b.width / 2, b.height / 2)
    for (i in 1..47) for (j in 1..47) {
        val c = b.getPixel((b.width * i / 48).coerceAtMost(b.width - 1), (b.height * j / 48).coerceAtMost(b.height - 1))
        val d = abs(((c shr 16) and 0xff) - ((first shr 16) and 0xff)) +
            abs(((c shr 8) and 0xff) - ((first shr 8) and 0xff)) + abs((c and 0xff) - (first and 0xff))
        if (d > 24) return@runCatching false
    }
    true
}.getOrDefault(false)
