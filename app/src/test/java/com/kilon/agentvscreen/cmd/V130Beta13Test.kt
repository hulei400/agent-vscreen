package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import com.kilon.agentvscreen.core.MotionTrack
import com.kilon.agentvscreen.core.VScreenOcr
import com.kilon.agentvscreen.core.VScreenPerception
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class V130Beta13Test {
    private val cols = 24
    private val rows = 40

    /** A banner (rows 3..7) that slides for 0.5 s every 3 s and stands still in between. */
    @Test fun aCarouselThatSlidesNowAndThenIsMoving() {
        val t = MotionTrack(cols, rows)
        for (k in 0 until 100) {
            val at = k * 100L
            val sliding = (at % 3_000L) < 500L
            t.feed(at, BooleanArray(cols * rows) { i -> sliding && (i / cols) in 3..7 })
        }
        val m = t.moving(9_900L)
        assertNotNull(m)
        assertTrue(m!![5 * cols + 12]); assertFalse(m[20 * cols + 12])
    }

    /** Our own scroll moves the whole page for a second: not a moving area, while a strip that keeps moving is. */
    @Test fun ourOwnScrollIsNotTheIconRowMoving() {
        val t = MotionTrack(cols, rows)
        val strip = { i: Int -> (i / cols) in 20..22 }
        for (k in -60 until 40) {
            val at = k * 100L
            val scrolling = at in 1_000L..2_000L
            t.feed(at, BooleanArray(cols * rows) { i -> scrolling || strip(i) })
        }
        t.action(1_000L, 2_000L)
        val m = t.moving(3_900L)
        assertNotNull(m)
        assertTrue(m!![21 * cols + 5])
        // Everything else changed only during our scroll: not moving.
        assertFalse(m[5 * cols + 5]); assertFalse(m[35 * cols + 5])
    }

    @Test fun withoutTheActionMarkTheScrollWouldSwallowThePage() {
        val t = MotionTrack(cols, rows)
        for (k in 0 until 40) {
            val at = k * 100L
            t.feed(at, BooleanArray(cols * rows) { i -> at in 1_000L..2_000L || (i / cols) in 20..22 })
        }
        // No action mark: the area grows over the scrolled page (what made collect stop after one screen).
        assertTrue(t.moving(3_900L)!![5 * cols + 5])
    }

    @Test fun ocrPiecesOfOneLineAreOneText() {
        fun item(text: String, l: Int, r: Int, top: Int = 400) = VScreenOcr.Item(text, Rect(l, top, r, top + 40), emptyList())
        val out = VScreenPerception.joinSplit(listOf(item("播", 612, 640), item("Auracast 音频广", 300, 608), item("关闭", 900, 980), item("设备名称", 300, 460, 600)))
        assertEquals(listOf("Auracast 音频广播", "关闭", "设备名称"), out)
    }
}
