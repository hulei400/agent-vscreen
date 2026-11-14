package com.kilon.agentvscreen.ui

import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * A slim, always-visible vertical scrollbar for the terminal / command-history lists.
 *
 * Used in all three places a command list shows: the floating window's terminal page
 * (Overlay), the full-screen terminal page and the full-screen "历史命令" page
 * (FullscreenActivity / TerminalHistoryView). It sits at the list's right edge as a
 * sibling (inside the same Box as the LazyColumn), so it never interferes with the
 * list's own modifiers (the floating window's control/scrollControl registration
 * stays on the LazyColumn itself). Dragging the thumb scrolls the list (handled by
 * VerticalScrollbar itself); it hides on its own when the list is short enough
 * that there is nothing to scroll.
 *
 * Style matches the dark terminal: a thin rounded white thumb on no track, so it
 * reads on the dark background without taking width away from the text (the lists
 * keep a small end contentPadding for exactly this strip).
 */
@Composable
fun BoxScope.TerminalScrollbar(listState: LazyListState) {
    VerticalScrollbar(
        adapter = rememberScrollbarAdapter(listState),
        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        style = ScrollbarStyle(
            minimalHeight = 24.dp,
            thickness = 4.dp,
            padding = 2.dp,
            shape = RoundedCornerShape(50),
            hoverColor = Color(0x99FFFFFF),
            unhoverColor = Color(0x59FFFFFF),
        ),
    )
}
