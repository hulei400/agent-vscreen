package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.tr

import com.kilon.agentvscreen.api.EventHub
import com.kilon.agentvscreen.core.VScreenRules
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** `events [--wait T]`: take the events posted for this session (a keep with --keep wake saw its condition). */
internal fun VScreenOffloadHandler.eventsCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    val s = sid ?: VScreenOffloadHandler.DEFAULT_SESSION
    val want = boundedMs("wait", args.getLong("wait") ?: 0L, 0L, 120_000L)
    // 1.1: events come only from this session's running --keep wake watches. With none running, waiting cannot bring
    // anything, so the reply says that at once instead of silently returning early or uselessly blocking.
    // 1.3.0-beta16: a question waiting for the user and the user holding the screen bring events too (answer, hand back).
    val watching = VScreenRules.running().count { it.spec.wake && it.spec.sid == s } + com.kilon.agentvscreen.host.UserAsk.openFor(s) +
        (if (com.kilon.agentvscreen.core.BackgroundOperation.state.value.let { it.ownerSessionId == s && (it.takeoverSessionId != null || it.userStopped) }) 1 else 0)
    val t0 = android.os.SystemClock.elapsedRealtime()
    val again = args.hasFlag("again")
    val evs = EventHub.take(s, if (watching > 0) want else 0L, again)
    if (again && evs.isEmpty()) return text(tr("没有上一批：上一次 events 没有取到事件，或那一批已经被之后的 events 确认掉了。", "No previous batch: the last events call returned none, or that batch was already confirmed by a later events call."))
    val waited = android.os.SystemClock.elapsedRealtime() - t0
    if (evs.isEmpty()) return text(when {
        watching == 0 && want > 0 -> tr("没有新事件，也没等：会话 $s 没有正在运行的 --keep wake 盯守，等也不会来事件。先 act --stop \"条件\" --keep wake 开始盯，keep 看列表。", "No new events, and no wait: session $s has no running --keep wake watch, so waiting would bring nothing. Start one with act --stop \"condition\" --keep wake; keep lists them.")
        watching == 0 -> tr("没有新事件（会话 $s 没有正在运行的 --keep wake 盯守）。", "No new events (session $s has no running --keep wake watch).")
        want > 0 -> tr("等了 ${waited / 1000}s，没有新事件（会话 $s 有 $watching 个盯守在运行）。", "Waited ${waited / 1000}s, no new events (session $s has $watching watches running).")
        else -> tr("没有新事件（会话 $s 有 $watching 个盯守在运行；--wait 60s 可以等）。", "No new events (session $s has $watching watches running; --wait 60s waits for one).")
    })
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.ROOT)
    val body = evs.joinToString("\n\n") { e -> "#${e.optLong("seq")} [${fmt.format(Date(e.optLong("at")))}${ago(e.optLong("at"))}] ${e.optString("kind")} \"${e.optString("name")}\"" + (if (e.optBoolean("repeat")) tr("（重发：App 重启前已经给过，没有确认；同一个 #编号）", " (repeat: handed out before the app restarted and never confirmed; same #number)") else "") + "\n${e.optString("text")}" } +
        "\n\n" + tr("（这一批会保留到你下一次 events；这次回复没收到就 events --again 重取，按 #编号 去重）", "(this batch is kept until your next events call; if this reply got lost, fetch it again with events --again and de-duplicate by #number)")
    return NativeOffloadResult(0, body + "\n", "OK", org.json.JSONObject().put("events", org.json.JSONArray(evs)))
}

/** 1.3.0: an event from long ago says so; a clock time alone made an unconfirmed one from yesterday look new. */
private fun ago(at: Long): String {
    val m = (System.currentTimeMillis() - at) / 60_000
    return when {
        m < 30 -> ""
        m < 120 -> tr("，$m 分钟前", ", $m min ago")
        m < 48 * 60 -> tr("，${m / 60} 小时前", ", ${m / 60} h ago")
        else -> tr("，${m / 1440} 天前", ", ${m / 1440} days ago")
    }
}
