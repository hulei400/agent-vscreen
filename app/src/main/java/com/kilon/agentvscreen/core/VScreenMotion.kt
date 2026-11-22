package com.kilon.agentvscreen.core

import android.graphics.Rect

/**
 * 1.3: the parts of the picture that keep moving on their own: a video, a live stream, a carousel, a game scene.
 *
 * Before, every look treated the whole picture alike. A playing video never "settled", so every action waited out its
 * 3 s settle limit; the words in it (danmaku, subtitles) filled 【这段时间】 with noise; a snapshot whose picture changed
 * while it was read was read again, so on a video page nearly every look ran OCR twice. The perception thread now
 * feeds every frame's changed cells here, and a cell that changed in most looks for a while is "moving". Waits judge
 * settling outside those areas, OCR words inside them are not listed, and the reply says which area is playing.
 *
 * A moving area is decided from the frames BEFORE an action (the caller takes [Mask] then), so the motion an action
 * causes itself (a scroll, a page sliding in) is never mistaken for a video: it lasts a second or two, not [MIN_SPAN_MS].
 * Pure (no Android pixels): fed with cell grids, testable on the JVM.
 */
class MotionTrack(val cols: Int, val rows: Int) {
    companion object {
        /** Motion must have gone on at least this long before it counts as moving (a fling or a page change is shorter). */
        const val MIN_SPAN_MS = 2_500L
        const val WINDOW_MS = 3_000L
        /**
         * 1.3.0: a carousel slides every few seconds and stands still in between, so in a 3 s window it hardly moves.
         * Over this longer window, an area that moved in at least [MIN_BURSTS] separate bursts keeps moving too.
         */
        const val LONG_WINDOW_MS = 10_000L
        const val MIN_BURSTS = 2
        /** A burst ends after this long without change in a cell. */
        const val BURST_GAP_MS = 800L
        /** 1.3.0: looks from an action of ours until this long after it are not the page moving on its own. */
        const val ACTION_MS = 1_500L
        /** Share of looks in the window in which a cell changed. */
        const val RATE = 0.55
        /** 1.3.0: share of looks in which a cell changed for it to belong to a moving area around a busy core. */
        const val RATE_TOUCHED = 0.15
        /** 1.3.0: for a look (no action of ours before it), motion this long already counts. */
        const val LOOK_SPAN_MS = 1_200L
        const val MIN_LOOKS = 10
        /** Areas smaller than this many cells (a blinking cursor, a ticking clock digit) are not "playing". */
        const val MIN_CELLS = 3
    }

    private val looks = ArrayDeque<Pair<Long, BooleanArray>>()
    /** Our own actions (taps, swipes, keys) on this screen: (start, end). */
    private val actions = ArrayDeque<Pair<Long, Long>>()

    @Synchronized fun feed(atMs: Long, cells: BooleanArray) {
        if (cells.size != cols * rows) return
        looks.addLast(atMs to cells)
        while (looks.isNotEmpty() && atMs - looks.first().first > LONG_WINDOW_MS) looks.removeFirst()
    }

    /**
     * 1.3.0: an action of ours happened at [atMs]. The motion it causes (a scroll, a page sliding in) is left out.
     * Before, a scroll right before the check made the whole page look "moving", and collect stopped after one screen
     * ("swiping no longer moves the picture") because every change was inside the moving area.
     */
    @Synchronized fun action(startMs: Long, endMs: Long = startMs) {
        actions.addLast(startMs to endMs)
        while (actions.isNotEmpty() && endMs - actions.first().second > LONG_WINDOW_MS + ACTION_MS) actions.removeFirst()
    }

    @Synchronized fun clear() { looks.clear(); actions.clear() }

    private fun ours(t: Long) = actions.any { (a, b) -> t >= a - 100L && t <= b + ACTION_MS }

    /** How much of (t, now] lies in our actions' spans: the window is measured in the page's own time. */
    private fun excludedAfter(t: Long, now: Long): Long = actions.sumOf { (a, b) ->
        maxOf(0L, minOf(now, b + ACTION_MS) - maxOf(t, a - 100L))
    }

    /**
     * Cells that keep moving now (null = none, or not enough looks to say). [minSpanMs]: how long the motion must have
     * gone on; shorter is fine when no action of ours can have caused it (a look).
     *
     * 1.3.0: a playing area is a region, not just its busiest cells. Danmaku and subtitles cross the whole video but
     * touch each cell only now and then, so only the video's busy middle passed [RATE]. Now: cells that changed in at
     * least [RATE_TOUCHED] of the looks are joined into areas; an area that holds a busy core (at least [MIN_CELLS]
     * cells over [RATE], or that moved in [MIN_BURSTS] separate bursts over [LONG_WINDOW_MS]) is moving as a whole,
     * as its bounding rectangle. Looks during and just after our own actions are left out.
     */
    @Synchronized fun moving(nowMs: Long, minSpanMs: Long = MIN_SPAN_MS): BooleanArray? {
        val own = looks.filter { nowMs - it.first <= LONG_WINDOW_MS && !ours(it.first) }
        val fresh = own.filter { nowMs - it.first - excludedAfter(it.first, nowMs) <= WINDOW_MS }
        val n = cols * rows
        // Continuous motion, from the short window.
        val counts = IntArray(n)
        for ((_, c) in fresh) for (i in c.indices) if (c[i]) counts[i]++
        val shortOk = fresh.size >= (if (minSpanMs >= MIN_SPAN_MS) MIN_LOOKS else 6) &&
            fresh.last().first - fresh.first().first >= minSpanMs - 500L
        // Repeated motion, from the long window: separate bursts per cell.
        val bursts = IntArray(n)
        val lastAt = LongArray(n)
        val longCounts = IntArray(n)
        for ((t, c) in own) for (i in c.indices) if (c[i]) {
            longCounts[i]++
            if (lastAt[i] == 0L || t - lastAt[i] > BURST_GAP_MS) bursts[i]++
            lastAt[i] = t
        }
        val longOk = own.size >= MIN_LOOKS && own.last().first - own.first().first >= 5_000L
        val core = BooleanArray(n) { (shortOk && counts[it] >= fresh.size * RATE) || (longOk && bursts[it] >= MIN_BURSTS) }
        val touched = BooleanArray(n) {
            core[it] || (shortOk && counts[it] >= 2 && counts[it] >= fresh.size * RATE_TOUCHED) || (longOk && longCounts[it] >= 2 && bursts[it] >= MIN_BURSTS)
        }
        val keep = BooleanArray(n)
        val seen = BooleanArray(n)
        for (s in touched.indices) {
            if (!touched[s] || seen[s]) continue
            // One area: touched cells joined, also across a one-cell gap (danmaku leaves holes).
            val group = ArrayList<Int>(); val st = ArrayDeque<Int>(); st.addLast(s); seen[s] = true
            while (st.isNotEmpty()) {
                val i = st.removeLast(); group += i
                val cx = i % cols; val cy = i / cols
                for (dy in -2..2) for (dx in -2..2) {
                    if (dx == 0 && dy == 0) continue
                    val nx = cx + dx; val ny = cy + dy
                    if (nx !in 0 until cols || ny !in 0 until rows) continue
                    val j = ny * cols + nx
                    if (touched[j] && !seen[j]) { seen[j] = true; st.addLast(j) }
                }
            }
            if (group.count { core[it] } < MIN_CELLS) continue
            val x0 = group.minOf { it % cols }; val x1 = group.maxOf { it % cols }
            val y0 = group.minOf { it / cols }; val y1 = group.maxOf { it / cols }
            for (y in y0..y1) for (x in x0..x1) keep[y * cols + x] = true
        }
        return keep.takeIf { k -> k.any { it } }
    }
}

/** The moving areas at one moment, in screen coordinates (and the grid behind them). */
class Mask(val cells: BooleanArray, val cols: Int, val rows: Int, val screenW: Int, val screenH: Int) {
    val rects: List<Rect> by lazy { VScreenActivity.boxes(cells, cols, rows, screenW, screenH) }
    val share: Double get() = cells.count { it }.toDouble() / maxOf(1, cells.size)
    /** 1.4.0-beta5: moving over most of the picture = the app itself moves (a game, an animated background), not a video in a page. */
    val mostly: Boolean get() = share > MOSTLY
    companion object { const val MOSTLY = 0.5 }

    /** Whether screen point (x, y) lies in a moving cell. */
    fun covers(x: Int, y: Int): Boolean {
        if (x !in 0 until screenW || y !in 0 until screenH) return false
        val cx = (x.toLong() * cols / screenW).toInt().coerceIn(0, cols - 1)
        val cy = (y.toLong() * rows / screenH).toInt().coerceIn(0, rows - 1)
        return cells[cy * cols + cx]
    }

    /** Most of [r] (screen coordinates) is moving. */
    fun coversMostOf(r: Rect): Boolean {
        if (r.width() <= 0 || r.height() <= 0) return covers(r.centerX(), r.centerY())
        var n = 0; var hit = 0
        for (i in 0..4) for (j in 0..2) {
            n++; if (covers(r.left + r.width() * (2 * i + 1) / 10, r.top + r.height() * (2 * j + 1) / 6)) hit++
        }
        return hit * 2 > n
    }

    /** The same areas in the coordinates of a [w]x[h] small copy of the picture. */
    fun rectsIn(w: Int, h: Int): List<Rect> = rects.map { r ->
        Rect(r.left * w / screenW, r.top * h / screenH, maxOf(r.left * w / screenW + 1, r.right * w / screenW), maxOf(r.top * h / screenH + 1, r.bottom * h / screenH))
    }

    /** Clear [cells] where this mask is set (same grid). */
    fun minus(cells: BooleanArray): BooleanArray = if (cells.size != this.cells.size) cells else BooleanArray(cells.size) { cells[it] && !this.cells[it] }

    fun line(zh: Boolean): String = rects.take(3).joinToString(if (zh) "、" else ", ") { "[${it.left},${it.top},${it.right},${it.bottom}]" }
}
