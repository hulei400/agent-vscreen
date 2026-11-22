package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.3.0-beta14: lessons judge "what the step caused" by the same rule as replay; limits that were cut say so. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V130Beta14Test {
    private var n = 0
    private fun el(text: String, l: Int, t: Int, r: Int, b: Int) = El(++n, text, Rect(l, t, r, b), true, clickable = true)
    private fun tap(x: Int, y: Int, before: TeachSnap?) = TeachEv.Touch(1000, x, y).also { it.end = 1080; it.before = before }

    @Test fun stepThatChangedNothingGetsNoUntilAndAWarning() {
        // Already on the alarm page; tapping its own tab changes nothing. Title-bar words were there all along.
        val page = TeachSnap("clock", listOf(el("闹钟", 0, 2000, 200, 2100), el("更多选项", 900, 50, 1000, 150), el("7:00", 100, 400, 500, 500)), "Clock")
        val same = TeachSnap("clock", listOf(el("闹钟", 0, 2000, 200, 2100), el("更多选项", 900, 50, 1000, 150), el("7:00", 100, 400, 500, 500)), "Clock")
        val l = Teach.translate(listOf(tap(100, 2050, page)), same)
        assertNull(l[0].until)
        assertNotNull(l[0].warn)
    }

    @Test fun wordsAlreadyAtTheirPlaceAreNotWhatTheStepCaused() {
        val before = TeachSnap("clock", listOf(el("新建", 400, 1900, 600, 2000), el("更多选项", 900, 50, 1000, 150)), "Clock")
        val after = TeachSnap("clock", listOf(el("更多选项", 900, 50, 1000, 150), el("取消", 100, 1900, 300, 2000), el("确定", 700, 1900, 900, 2000)), "Clock")
        val l = Teach.translate(listOf(tap(500, 1950, before)), after)
        assertEquals("text:取消", l[0].until)
    }

    @Test fun aFailedReadingIsUnknownNotBlank() {
        val after = TeachSnap("clock", listOf(el("更多选项", 900, 50, 1000, 150)), "Clock")
        val l = Teach.translate(listOf(tap(500, 500, null)), after)
        assertNull(l[0].until)
    }

    @Test fun mirroredTypingIsWhatTheBoxFinallyReads() {
        val page = TeachSnap("settings", listOf(el("搜索设置项", 100, 100, 900, 200)), "Search")
        val evs = listOf(TeachEv.Typed(1000, "蓝", whole = true).also { it.before = page }, TeachEv.Typed(1100, "蓝牙耳", whole = true), TeachEv.Typed(1200, "蓝牙", whole = true))
        val l = Teach.translate(evs, page)
        assertEquals(1, l.size)
        assertEquals("蓝牙", l[0].typed)
    }

    @Test fun cutDurationsAreSaid() {
        takeAdjusted()
        assertEquals(120_000L, boundedMs("wait", 600_000L, 0L, 120_000L))
        val notes = takeAdjusted()
        assertEquals(1, notes.size)
        assertTrue(notes[0], notes[0].contains("10m") && notes[0].contains("2m"))
        assertEquals("1m30s", durWords(90_000L))
    }
}
