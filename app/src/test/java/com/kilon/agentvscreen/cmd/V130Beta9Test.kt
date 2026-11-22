package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.core.MotionTrack
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class V130Beta9Test {
    private val cols = 24
    private val rows = 40

    /** A video (rows 6..20) whose middle moves all the time and whose danmaku touch each other cell now and then. */
    private fun videoLook(k: Int) = BooleanArray(cols * rows) { i ->
        val x = i % cols; val y = i / cols
        when {
            y in 10..14 && x in 4..18 -> true
            y in 6..20 -> (x * 7 + y * 3 + k) % 5 == 0
            else -> false
        }
    }

    @Test fun movingAreaIsTheWholeVideoNotItsBusyMiddle() {
        val t = MotionTrack(cols, rows)
        for (k in 0 until 30) t.feed(k * 100L, videoLook(k).also { it[34 * cols + 3] = k % 2 == 0 })
        val m = t.moving(3_000L)
        assertNotNull(m)
        // Danmaku rows above and below the busy middle, and its sides, are inside the area.
        assertTrue(m!![7 * cols + 1]); assertTrue(m[19 * cols + 22]); assertTrue(m[12 * cols + 0])
        // Outside the video, and a lone blinking cell, are not.
        assertFalse(m[25 * cols + 10]); assertFalse(m[34 * cols + 3]); assertFalse(m[2 * cols + 10])
    }

    @Test fun aLookNeedsLessHistoryThanAWaitAfterAnAction() {
        val t = MotionTrack(cols, rows)
        for (k in 0 until 14) t.feed(k * 100L, videoLook(k))
        assertNull(t.moving(1_300L))
        assertNotNull(t.moving(1_300L, MotionTrack.LOOK_SPAN_MS))
    }

    @Test fun pageSlidingInIsNotAVideoEvenForALook() {
        val t = MotionTrack(cols, rows)
        // 0.6 s of everything changing: too short even for a look.
        for (k in 0 until 7) t.feed(k * 100L, BooleanArray(cols * rows) { true })
        assertNull(t.moving(600L, MotionTrack.LOOK_SPAN_MS))
    }
}
