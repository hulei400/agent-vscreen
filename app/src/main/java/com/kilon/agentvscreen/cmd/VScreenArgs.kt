package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.tr

import android.graphics.Rect
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import kotlin.math.roundToInt

// [vscreen] Argument shapes every vscreen command shares, parsed in one place so the
// same spelling works everywhere and a bad value is refused instead of silently replaced.

/** "x1,y1,x2,y2" (also with Chinese commas, any corner order) → Rect. */
internal fun parseRect(v: String, what: String): Rect {
    val n = v.split(',', '，').mapNotNull { it.trim().toDoubleOrNull()?.roundToInt() }
    if (n.size != 4) throw ActionError("INVALID_ARGS", tr("$what 写成 x1,y1,x2,y2（收到 $v）", "write $what as x1,y1,x2,y2 (got $v)"))
    return Rect(minOf(n[0], n[2]), minOf(n[1], n[3]), maxOf(n[0], n[2]), maxOf(n[1], n[3]))
}

/** 500ms / 5s / 30m / 2h; a bare number is milliseconds; null → [def]. */
internal fun parseDuration(v: String?, def: Long): Long {
    val t = v?.trim() ?: return def
    return VScreenOptions.durationMs(t) ?: throw ActionError("INVALID_ARGS", tr("时长写成 500ms / 5s / 30m / 2h（不带单位就是毫秒）：$v", "write a duration as 500ms / 5s / 30m / 2h (no unit means milliseconds): $v"))
}

/** A value the tool had to change (clamped) is said in the result instead of silently. */
private val adjustedNotes = ThreadLocal<MutableList<String>>()

internal fun adjusted(what: String) {
    (adjustedNotes.get() ?: ArrayList<String>().also { adjustedNotes.set(it) }) += what
}

/** Take the notes collected during one command (and clear them). */
internal fun takeAdjusted(): List<String> = adjustedNotes.get()?.toList().orEmpty().also { adjustedNotes.remove() }

/** A duration in words an agent reads back: 90s, 2m, 1m30s, 500ms. */
internal fun durWords(ms: Long): String = when {
    ms < 1000 -> "${ms}ms"
    ms % 60_000 == 0L -> "${ms / 60_000}m"
    ms < 60_000 -> if (ms % 1000 == 0L) "${ms / 1000}s" else "%.1fs".format(ms / 1000.0)
    else -> "${ms / 60_000}m${(ms % 60_000) / 1000}s"
}

/**
 * 1.3.0-beta14: a duration kept within [lo]..[hi] — a value above is cut AND the cut is said in the result (task
 * --wait 10m used to return after 120 s without a word). Every duration limit goes through here or [capMs].
 */
internal fun boundedMs(name: String, v: Long, lo: Long, hi: Long): Long {
    if (v < lo) throw OffloadArgs.BadOption(tr("--$name ${durWords(v)} 太短（可用 ${durWords(lo)}–${durWords(hi)}）", "--$name ${durWords(v)} is too short (allowed ${durWords(lo)}–${durWords(hi)})"))
    return capMs("--$name", v, hi).coerceAtLeast(lo)
}

/**
 * [v] cut to [hi], saying so: [what] names where the duration was written (an option, a step). [withValue] false when
 * [what] already shows the duration (a step's own words: "wait 5m", not "wait 5m 5m").
 */
internal fun capMs(what: String, v: Long, hi: Long, withValue: Boolean = true): Long {
    val w = if (withValue) "$what ${durWords(v)}" else what
    if (v > hi) adjusted(tr("$w 超过上限，只等 ${durWords(hi)}", "$w is over the limit; waited at most ${durWords(hi)}"))
    return minOf(v, hi)
}

internal fun bounded(name: String, v: Int, lo: Int, hi: Int): Int = bounded(name, v.toLong(), lo.toLong(), hi.toLong()).toInt()

/** [v] kept within [lo]..[hi]; a value outside is clamped AND reported in the result. */
internal fun bounded(name: String, v: Long, lo: Long, hi: Long): Long {
    // 1.3 beta2: below the smallest allowed value is a mistake (collect --pages -1 ran 1 page), not something to guess.
    if (v < lo) throw OffloadArgs.BadOption(tr("--$name $v 太小（可用 $lo–$hi）", "--$name $v is too small (allowed $lo–$hi)"))
    if (v > hi) adjusted(tr("--$name $v 超出范围，按 ${v.coerceIn(lo, hi)} 执行（可用 $lo–$hi）", "--$name $v is out of range, used ${v.coerceIn(lo, hi)} (allowed $lo–$hi)"))
    return v.coerceIn(lo, hi)
}
