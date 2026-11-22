package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.tr

/** `trail [--last N] [--clear]`: the passive trail of the user's touches while operating the screen (takeover).
 * Each line is time + what was done (named by the control it hit, from the screen reading) + the page it was on +
 * what the screen showed — the trajectory together with the screen content, so the agent can learn how the user
 * did it. Read-only; needs no screen lease. */
internal fun VScreenOffloadHandler.trailCmd(sid: String?, args: OffloadArgs): NativeOffloadResult {
    if (args.hasFlag("clear")) {
        Teach.trailClear()
        return text(tr("轨迹已清空。", "Trail cleared."))
    }
    val n = (args.getLong("last") ?: 20L).toInt().coerceIn(1, 200)
    val lines = Teach.trailLines(n)
    if (lines.isEmpty()) return text(tr("轨迹还是空的：用户接管操作一次就会记下来。", "Trail is still empty: it fills up once the user operates the screen."))
    val body = lines.joinToString("\n")
    return NativeOffloadResult(0, body + "\n", "OK", org.json.JSONObject().put("trail", org.json.JSONArray(lines)))
}
