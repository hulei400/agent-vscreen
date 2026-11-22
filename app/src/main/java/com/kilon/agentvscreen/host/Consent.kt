package com.kilon.agentvscreen.host

import android.content.Context
import com.kilon.agentvscreen.cmd.Call
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import com.kilon.agentvscreen.tr
import kotlinx.coroutines.flow.StateFlow

/**
 * 1.2: the user's yes, given on the phone, for the steps an agent must not decide alone: a payment or irreversible
 * button, force-stopping an app, changing system settings, taking the app off the user's main screen, and anything in
 * an app the user set to "ask every time".
 *
 * Before 1.2 the agent added `--confirmed` itself after "asking the user": the tool could not tell a real yes from a
 * model that simply added the flag. Now the question appears on the phone (a heads-up notification with Allow / Deny,
 * and a card in the app), the command waits up to [WAIT_MS], and only the user's tap lets it through. No answer in
 * time is CONFIRM_PENDING: the request stays open for [VALID_MS], so the user can still allow it and the agent's
 * resend goes through. A user who trusts a token can give it a period without questions in the app (per token, with
 * an end time); those passes are written to the audit log like every answer.
 */
object Consent {
    const val WAIT_MS = 60_000L
    const val VALID_MS = 5 * 60_000L
    const val ACTION = UserAsk.ACTION

    /** Questions waiting for the user (1.3: every kind of question, see [UserAsk]). */
    val open: StateFlow<List<UserAsk.Ask>> get() = UserAsk.open

    /**
     * Returns when the user allowed [key]; throws USER_DENIED / CONFIRM_PENDING otherwise. [reusable]: an allow covers
     * the same key of the same token for [VALID_MS] (an app set to "ask every time" is asked once per few minutes, not
     * once per tap); otherwise an allow is used up by one pass.
     */
    fun require(ctx: Context, key: String, title: String, detail: String, reusable: Boolean = false, kind: String = "pay") {
        val call = Call.current()
        val name = call?.tokenName ?: "main"; val session = call?.session ?: "default"
        if (call?.trusted == true) { Audit.consent(name, session, "$title — $detail", "pre-authorized"); return }
        if (!asks(kind, runCatching { com.kilon.agentvscreen.api.ApiConfig.autonomy }.getOrDefault("auto"))) {
            Audit.consent(name, session, "$title — $detail", "automatic"); return
        }
        val now = System.currentTimeMillis()
        val old = UserAsk.find(key, call?.tokenId ?: "main")?.takeIf { it.kind == UserAsk.Kind.CONFIRM }
        val ask = when {
            old?.answer == true && now - old.answeredAt <= VALID_MS -> { UserAsk.markUsed(old); return }
            old != null && old.answer == null -> old
            else -> UserAsk.create(ctx, UserAsk.Kind.CONFIRM, key, title, detail, reusable, validMs = VALID_MS)
        }
        UserAsk.await(ask, WAIT_MS)
        when (ask.answer) {
            true -> { UserAsk.markUsed(ask); return }
            false -> throw ActionError("USER_DENIED", tr("用户在手机上拒绝了：$title。没有做。不要换一种方式再试，告诉用户你停在了这里", "The user denied it on the phone: $title. Not done. Don't try another way; tell the user where you stopped"))
            null -> throw ActionError("CONFIRM_PENDING", tr("需要用户在手机上确认：$title（$detail）。已在手机上弹出通知，等了 ${WAIT_MS / 1000} 秒没有答复，这一步没有做。告诉用户去手机通知栏或 Agent-VScreen 里点“允许”；${VALID_MS / 60_000} 分钟内允许后，把这条命令原样重发一次",
                "The user must confirm on the phone: $title ($detail). A notification is showing; no answer within ${WAIT_MS / 1000} s, so this step was not done. Tell the user to tap Allow in the phone's notifications or in Agent-VScreen; once allowed (within ${VALID_MS / 60_000} minutes), send this command again as it is"))
        }
    }

    /**
     * 1.2.0-beta2: which kinds of step ask the user at each autonomy level. One table, shown to the user as it is.
     * Kinds: "pay" (a button that moves money), "app" (an app the user set to ask every time), "spend" (a step toward
     * buying), "irreversible" (delete, uninstall …), "system" (force-stop, taking an app off the main screen).
     * The default level lets the agent run hands-off: only money and the user's own per-app rule stop it.
     */
    fun asks(kind: String, level: String): Boolean = when (kind) {
        "pay", "app" -> true
        else -> level == "careful"
    }

    /** The user's tap (notification action or the card in the app). */
    fun answer(ctx: Context, id: Int, allow: Boolean) = UserAsk.answer(ctx, id, allow)

    /** For tests. */
    internal fun reset() = UserAsk.reset()
    internal fun pendingId(key: String): Int? = UserAsk.pendingId(key)
}
