package com.kilon.agentvscreen.ui

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** The two panes of the one screen page: the live screen, and saving a lesson. */
enum class Pane { LIVE, REVIEW }

/**
 * 1.4.0-beta11 (DESIGN-SCREEN-LESSON-1.4 §2.2): which of our screens the user is looking at, and the ONLY way to open
 * the screen page. Only a direct tap of the user calls [openLive] / [openReview] (全屏, 去保存, 完成, a notification);
 * a lesson, the background service and the agents never open a page (they change cards and post notifications).
 * Before, seven places started pages on their own and a hand-back or a release threw the user onto the save page.
 */
object Surface {
    const val EXTRA = "pane"
    private val _page = MutableStateFlow<Pane?>(null)
    /** The pane the screen page shows while it is in front; null when it is not. Reported by the page itself. */
    val page: StateFlow<Pane?> = _page.asStateFlow()
    internal fun report(p: Pane?) { _page.value = p }

    private fun intent(ctx: Context, p: Pane) = Intent(ctx, FullscreenActivity::class.java).putExtra(EXTRA, p.name)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
    fun liveIntent(ctx: Context) = intent(ctx, Pane.LIVE)
    fun reviewIntent(ctx: Context) = intent(ctx, Pane.REVIEW)

    /** 全屏 / 查看副屏: the live screen (a lesson being recorded goes on there). */
    fun openLive(ctx: Context, say: Boolean = false) = MainDisplay.start(ctx, liveIntent(ctx).putExtra(SAY, say))
    /** Open the live pane with the request card's 说一句 box open (from the floating window, which cannot type). */
    const val SAY = "say"
    /** 去保存 / 完成: the lesson's save pane. Works without a screen. */
    fun openReview(ctx: Context) = MainDisplay.start(ctx, reviewIntent(ctx))
}
