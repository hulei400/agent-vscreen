package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.3.0-beta7 示教: what the user did becomes the steps an agent writes, with targets and waits from the screen. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V130Beta7Test {
    private var n = 0
    private fun el(text: String, l: Int, t: Int, r: Int, b: Int, focused: Boolean = false, password: Boolean = false, editable: Boolean = false) =
        El(++n, text, Rect(l, t, r, b), true, clickable = true, editable = editable, focused = focused, password = password)
    private fun snap(pkg: String, vararg els: El) = TeachSnap(pkg, els.toList())

    private fun tap(x: Int, y: Int, ms: Long, before: TeachSnap) = TeachEv.Touch(1000, x, y).also { it.end = 1000 + ms; it.before = before }

    @Test fun tapOnWordsWaitsForWhatCameNext() {
        val home = snap("com.example.shop", el("搜索", 100, 100, 300, 160), el("首页", 0, 2000, 200, 2100))
        val search = snap("com.example.shop", el("搜索框", 0, 80, 1000, 160, focused = true, editable = true), el("历史记录", 0, 300, 400, 360), el("首页", 0, 2000, 200, 2100))
        val results = snap("com.example.shop", el("咖啡豆 500g", 0, 400, 1000, 500), el("综合排序", 0, 200, 300, 260))
        val evs = listOf<TeachEv>(
            tap(200, 130, 80, home),
            TeachEv.Typed(2000, "咖啡").also { it.before = search },
            TeachEv.Key(3000, "enter").also { it.before = search },
        )
        val lines = Teach.translate(evs, results)
        assertEquals(3, lines.size)
        assertEquals("tap 搜索", lines[0].head)
        assertEquals("text:历史记录", lines[0].until)
        assertEquals("type", lines[1].kind)
        assertEquals("咖啡", lines[1].typed)
        assertEquals("key enter", lines[2].head)
        assertTrue(lines[2].until!!.startsWith("text:"))
        val (steps, params) = Teach.build(lines, lines.indices.toSet(), emptySet())
        assertEquals(1, params)
        assertEquals("tap 搜索 until text:历史记录; type {1}; key enter until ${lines[2].until}", steps)
        // Every step the lesson wrote is one an agent could have written.
        Steps.split(steps).forEach { Steps.parse(Teach.fill(it, listOf("茶叶")).first) }
        val (kept, p2) = Teach.build(lines, lines.indices.toSet(), setOf(1))
        assertEquals(0, p2)
        assertTrue(kept, kept.contains("type 咖啡"))
    }

    @Test fun pointWhenWordsDoNotNameIt() {
        // The same words twice on the screen: a tap on one of them is kept as a point.
        val s = snap("a.b", el("购买", 0, 0, 100, 50), el("购买", 0, 500, 100, 550))
        val l = Teach.translate(listOf(tap(50, 520, 60, s)), s)
        assertEquals("tap 50 520", l[0].head)
        assertNull(l[0].until)
        assertTrue(l[0].warn != null) // nothing changed afterwards: the user is told it may have missed
    }

    @Test fun holdSwipeAndDrag() {
        val s = snap("a.b", el("条目", 0, 0, 500, 100))
        val hold = tap(250, 50, 900, s)
        val swipe = TeachEv.Touch(1000, 500, 1500).also { e -> e.before = s; (1..10).forEach { e.path += intArrayOf(500, 1500 - it * 100, it * 30) }; e.end = 1300 }
        val drag = TeachEv.Touch(1000, 100, 100).also { e -> e.before = s
            (1..10).forEach { e.path += intArrayOf(100 + it * 50, 100, it * 40) }; (1..10).forEach { e.path += intArrayOf(600, 100 + it * 50, 400 + it * 40) }; e.end = 1800 }
        val l = Teach.translate(listOf(hold, swipe, drag), s)
        assertEquals("press 条目 900ms", l[0].head)
        assertEquals("swipe 500 1500 500 500 300ms", l[1].head)
        assertTrue(l[2].head, l[2].head.startsWith("drag 100 100 ") && l[2].head.endsWith(" 800ms"))
        l.forEach { Steps.parse(it.head) }
    }

    @Test fun appChangeAndGoneAreResults() {
        val a = snap("a.b", el("分享", 0, 0, 100, 50), el("取消", 0, 100, 100, 150))
        val b = snap("c.d")
        assertEquals("app:c.d", Teach.changeCond(a, b)!!.first)
        val c = snap("a.b", el("分享", 0, 0, 100, 50))
        assertEquals("gone:取消", Teach.changeCond(a, c)!!.first)
        // A clock or a counter is not a sign that it worked.
        val d = snap("a.b", el("分享", 0, 0, 100, 50), el("取消", 0, 100, 100, 150), el("12:30", 0, 200, 100, 250))
        assertNull(Teach.changeCond(a, d))
    }

    @Test fun passwordsAreNeverKept() {
        val s = snap("a.b", el("", 0, 0, 100, 50, focused = true, password = true, editable = true))
        val l = Teach.translate(listOf(TeachEv.Typed(1, "hunter2").also { it.before = s }), null)
        assertTrue(l[0].secret)
        val (steps, p) = Teach.build(l, setOf(0), setOf(0))
        assertEquals("type {1}", steps); assertEquals(1, p)
    }

    @Test fun fillReportsMissing() {
        assertEquals("type 咖啡" to emptyList<Int>(), Teach.fill("type {1}", listOf("咖啡")))
        assertEquals(listOf(2), Teach.fill("type {1}; type {2}", listOf("a")).second)
    }
}
