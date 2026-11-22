package com.kilon.agentvscreen.host

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * 1.4.0-beta11 (rainbow: "通知都点击不了…用户不知道去哪里处理 agent 的请求"; several agents use the app). Everything
 * that waits on the user, from every agent, has one home: the 等你处理 group at the top of the app's first page.
 *  - questions and lessons (UserAsk, Teach.lesson),
 *  - apps an agent handed to the user (VScreenOwnership.given): hand back or open,
 *  - agents that asked for the screen while another agent had it ([waiting]).
 * Every notification the app posts opens the app at the place where it is handled ([open]); a notification nobody can
 * tap is not allowed (VScreenHandoff.alert takes a destination, not an optional one).
 */
object Needs {
    /** Pages a notification can open (MainActivity.routeOf / its tabs). */
    enum class Page { HOME, RECORDS, CONNECT, SCHEDULES }

    /** One tap target for a notification: the app on the main display, on [page]. [code] keeps intents apart. */
    fun open(ctx: Context, page: Page, code: Int): PendingIntent =
        PendingIntent.getActivity(ctx, 7800 + (code and 0xff),
            Intent(ctx, com.kilon.agentvscreen.ui.MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(com.kilon.agentvscreen.ui.MainActivity.EXTRA_PAGE, page.name),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT, com.kilon.agentvscreen.ui.MainDisplay.options())

    /**
     * 1.4.0-beta11 (rainbow: in a scheduled run the agent may not be there). A run stopped at a check: the agent that
     * ran it hears it now (an event, for a run it did not wait on), and if it has not been back within
     * [CHECK_NUDGE_MS] the user is told who is waiting to be looked at. Nothing goes on by itself.
     */
    const val CHECK_NUDGE_MS = 5 * 60_000L

    fun checkpoint(ctx: Context, session: String, what: String, app: String?) {
        val at = System.currentTimeMillis()
        val agent = com.kilon.agentvscreen.api.Agents.ofSession(session)
        quiet("checkpoint-event") { com.kilon.agentvscreen.api.EventHub.post(ctx, session, "checkpoint", what.take(60),
            com.kilon.agentvscreen.tr("流程停在检查点：$what。先看画面（act 不带步骤），再按上次回执里的命令接着跑", "The run stopped at a checkpoint: $what. Look at the screen (act with no steps), then go on with the command from that reply")) }
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (com.kilon.agentvscreen.api.AgentLedger.of(agent).lastCallAt > at) return@postDelayed
            val who = com.kilon.agentvscreen.api.Agents.name(agent)
            com.kilon.agentvscreen.core.VScreenHandoff.alert(ctx, 8400 + (agent.hashCode() and 0x3f),
                com.kilon.agentvscreen.tr("「$who」的流程停在检查点", "\"$who\"'s run stopped at a checkpoint"),
                com.kilon.agentvscreen.tr("要它看的是：$what。它还没回来看，手机不会自己往下做。可以让它来接着，或者你自己在副屏上看一下。",
                    "It was to look at: $what. It has not come back yet, and the phone does not go on by itself. Ask it to carry on, or look at the screen yourself."),
                open(ctx, Page.HOME, 8))
        }, CHECK_NUDGE_MS)
    }

    /** An agent that was refused the screen because [owner] had it. */
    data class Waiting(val session: String, val owner: String, val at: Long)

    /** How long a refused agent stays listed (it retries on its own; this is for the user to see who is waiting). */
    const val WAIT_SHOWN_MS = 120_000L

    private val _waiting = MutableStateFlow<Map<String, Waiting>>(emptyMap())
    val waiting: StateFlow<Map<String, Waiting>> = _waiting.asStateFlow()

    fun refused(session: String, owner: String?) {
        if (owner == null || owner == session) return
        _waiting.update { it + (session to Waiting(session, owner, System.currentTimeMillis())) }
    }

    /** [session] has the screen now (or went away): it no longer waits. */
    fun got(session: String) { if (_waiting.value.containsKey(session)) _waiting.update { it - session } }

    /** Waiting agents still worth showing, by agent (one line per agent, not per session). */
    fun waitingNow(now: Long = System.currentTimeMillis(), owner: String?): List<Waiting> =
        _waiting.value.values.filter { now - it.at < WAIT_SHOWN_MS && it.session != owner }
            .groupBy { com.kilon.agentvscreen.api.Agents.ofSession(it.session) }.map { (_, l) -> l.maxBy { it.at } }
            .sortedBy { it.at }
}
