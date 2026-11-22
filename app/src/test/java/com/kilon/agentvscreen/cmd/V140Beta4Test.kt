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

/** 1.4.0-beta4: lessons record intent (seek, set a value), keep their start; feedback; text in an area. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V140Beta4Test {
    private var n = 0
    private fun el(text: String, l: Int, t: Int, r: Int, b: Int) = El(++n, text, Rect(l, t, r, b), true, clickable = true)
    private fun swipe(x: Int, y1: Int, y2: Int, before: TeachSnap?) = TeachEv.Touch(1000, x, y1).also { t ->
        (1..5).forEach { k -> t.path += intArrayOf(x, y1 + (y2 - y1) * k / 5, k * 40) }
        t.end = 1200; t.before = before
    }
    private fun tap(x: Int, y: Int, before: TeachSnap?) = TeachEv.Touch(2000, x, y).also { it.end = 2080; it.before = before }

    private fun wheel(h: String, top: String) = listOf(el("新建闹钟", 300, 100, 700, 180), el("取消", 100, 1900, 300, 2000),
        el("%02d".format(h.toInt() - 1), 200, 700, 300, 780), el(h, 200, 800, 300, 880), el("%02d".format(h.toInt() + 1), 200, 900, 300, 980), el(top, 600, 800, 700, 880))

    @Test fun wheelTurnsBecomeOneSetValueStep() {
        val a = TeachSnap("clock", wheel("08", "00"), "Edit")
        val b = TeachSnap("clock", wheel("07", "00"), "Edit")
        val c = TeachSnap("clock", wheel("07", "00"), "Edit")
        val lines = Teach.translate(listOf(swipe(250, 700, 900, a), swipe(250, 700, 760, b)), c)
        assertEquals(1, lines.size)
        assertEquals("value", lines[0].kind)
        assertEquals("07", lines[0].typed)
        assertTrue(lines[0].head, lines[0].head.startsWith("repeat 40 { swipe 250 "))
        assertTrue(lines[0].until!!, lines[0].until!!.startsWith("text:07 in "))
        // The value is a blank unless kept as it was.
        val (body, p) = Teach.build(lines, setOf(0), emptySet())
        assertEquals(1, p)
        assertTrue(body, body.contains("until text:{1} in "))
        assertTrue(Teach.build(lines, setOf(0), setOf(0)).first.contains("until text:07 in "))
        // And the step parses, with the area on the text condition.
        val st = Steps.parse(Teach.fill(body, listOf("09")).first)
        assertEquals(StepKind.REPEAT, st.kind)
    }

    @Test fun scrollingToAnItemThenTappingItIsSeek() {
        val a = TeachSnap("settings", listOf(el("设置", 100, 50, 300, 120), el("WLAN", 100, 300, 400, 380), el("蓝牙", 100, 400, 400, 480)), "Main")
        val b = TeachSnap("settings", listOf(el("设置", 100, 50, 300, 120), el("电池", 100, 300, 400, 380), el("关于手机", 100, 1400, 400, 1480)), "Main")
        val evs = listOf(swipe(500, 1500, 500, a), swipe(500, 1500, 500, a), tap(200, 1440, b))
        val lines = Teach.translate(evs, b)
        assertEquals("seek", lines[0].kind)
        assertEquals("text:关于手机", lines[0].until)
        assertEquals("tap", lines[1].kind)
    }

    @Test fun startIsCheckedByAppAndWords() {
        val start = Teach.startJson(TeachSnap("clock", listOf(el("闹钟", 0, 0, 100, 50), el("世界时钟", 200, 0, 400, 50), el("秒表", 500, 0, 600, 50)), "com.x/.Alarm"))!!
        assertNull(Teach.atStart(start, "clock", "com.x/.Alarm") { it in setOf("闹钟", "世界时钟") })
        assertNotNull(Teach.atStart(start, "clock", "com.x/.Edit") { false })
        assertNotNull(Teach.atStart(start, "other", "com.x/.Alarm") { true })
    }

    @Test fun textConditionTakesAnArea() {
        val c = Steps.cond("text:07 in 100,200,300,400")
        assertEquals("07", c.text)
        assertEquals(Rect(100, 200, 300, 400), c.area)
        assertNull(Steps.cond("text:in the box").area)
    }

    @Test fun feedbackIsItsOwnCode() {
        val e = feedbackOf(org.json.JSONObject().put("kind", "feedback").put("text", "不是这个页面"))!!
        assertEquals("USER_FEEDBACK", e.code)
        assertEquals("不是这个页面", e.data!!.optString("feedback"))
        assertNull(feedbackOf(org.json.JSONObject().put("kind", "text").put("text", "hi")))
    }
}
