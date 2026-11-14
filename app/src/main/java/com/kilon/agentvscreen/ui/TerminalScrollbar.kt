package com.kilon.agentvscreen.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * A slim, always-visible vertical scrollbar for the terminal / command-history lists.
 *
 * Used in all three places a command list shows: the floating window's terminal page
 * (Overlay), the full-screen terminal page and the full-screen "历史命令" page
 * (FullscreenActivity / TerminalHistoryView). It sits at the list's right edge as a
 * sibling (inside the same Box as the LazyColumn), so it never interferes with the
 * list's own modifiers (the floating window's control/scrollControl registration
 * stays on the LazyColumn itself).
 *
 * Note: Compose's own VerticalScrollbar / rememberScrollbarAdapter are desktop-only
 * APIs — AndroidX foundation 1.9.1 has no Scrollbar classes at all (verified in the
 * published AAR), so this is a small hand-rolled one instead. The thumb's size and
 * position come from `LazyListState.layoutInfo` (reading it subscribes the
 * composable, so the thumb follows every scroll), and dragging the strip scrolls
 * the list via `scrollBy` (the drag distance is scaled by total/viewport height).
 * It hides on its own when the list fits on screen and there is nothing to scroll.
 *
 * Style matches the dark terminal: a thin rounded white thumb on no track, so it
 * reads on the dark background without taking width away from the text (the lists
 * keep a small end contentPadding for exactly this strip).
 */
@Composable
fun BoxScope.TerminalScrollbar(listState: LazyListState) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var trackHeightPx by remember { mutableIntStateOf(0) }
    val layoutInfo = listState.layoutInfo
    val visible = layoutInfo.visibleItemsInfo
    val viewportHeight = layoutInfo.viewportSize.height.toFloat()
    if (visible.isEmpty() || layoutInfo.totalItemsCount == 0 || viewportHeight <= 0f || trackHeightPx <= 0) {
        // Nothing to show yet: keep a size-measuring strip so we learn the track height.
        Box(Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.dp)
            .onSizeChanged { trackHeightPx = it.height })
        return
    }
    // Estimate the full content height from the average visible item; the 8/10dp
    // arrangement spacing is not in the item sizes, so this is a slight underestimate
    // (thumb a touch bigger than the true fraction) — fine for a scrollbar.
    val avgItemHeight = visible.sumOf { it.size }.toFloat() / visible.size
    val totalHeight = avgItemHeight * layoutInfo.totalItemsCount
    if (totalHeight <= viewportHeight) return // List fits: nothing to scroll.
    val maxScroll = totalHeight - viewportHeight
    val scrollOffset = listState.firstVisibleItemIndex * avgItemHeight + listState.firstVisibleItemScrollOffset
    val thumbHeightPx = maxOf(
        (viewportHeight / totalHeight * trackHeightPx),
        with(density) { 24.dp.toPx() },
    )
    val thumbOffsetPx =
        ((trackHeightPx - thumbHeightPx) * (scrollOffset / maxScroll).coerceIn(0f, 1f)).toInt()
    val dragRatio = totalHeight / viewportHeight
    Box(
        Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.dp)
            .onSizeChanged { trackHeightPx = it.height }
            .draggable(
                orientation = Orientation.Vertical,
                state = rememberDraggableState { delta ->
                    scope.launch { listState.scrollBy(delta * dragRatio) }
                },
            ),
    ) {
        Box(
            Modifier.align(Alignment.TopStart)
                .offset { IntOffset(0, thumbOffsetPx) }
                .width(4.dp)
                .height(with(density) { thumbHeightPx.toDp() })
                .clip(RoundedCornerShape(50))
                .background(Color(0x59FFFFFF)),
        )
    }
}
