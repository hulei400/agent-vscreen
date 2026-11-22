package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import com.kilon.agentvscreen.core.BackgroundOperation.ActionError
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.3.0-beta18: one step language for act, segments and lessons: patterns, @N, try / if / repeat. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V130Beta18ScriptTest {

    private fun el(text: String, l: Int, t: Int, r: Int, b: Int, editable: Boolean = false) = El(0, text, Rect(l, t, r, b), true, clickable = true, editable = editable)

    private fun refused(body: String) = try { Steps.parseAll(body); fail("parsed: $body") } catch (e: ActionError) { assertEquals("INVALID_STEP", e.code) }

    @Test fun bracesKeepTheirStepsTogether() {
        val parts = Steps.split("tap 设置; if text:关闭 { tap 关闭; wait 1s } else { back }; type {1}")
        assertEquals(listOf("tap 设置", "if text:关闭 { tap 关闭; wait 1s } else { back }", "type {1}"), parts)
    }

    @Test fun ifElseTryRepeatParse() {
        val s = Steps.parseAll("if text:关闭 2s { tap 关闭 } else { back; wait 1s }; try tap 跳过; repeat 3 { scroll down }; repeat { scroll down } until text:到底了 3s")
        assertEquals(listOf("if", "try", "repeat", "repeat"), s.map { it.kind.key })
        assertEquals("text", s[0].until!![0].kind.key); assertEquals(2_000L, s[0].untilMs)
        assertEquals(listOf("tap"), s[0].body.map { it.kind.key }); assertEquals(listOf("key", "sleep"), s[0].alt.map { it.kind.key })
        assertEquals("tap", s[1].body.single().kind.key)
        assertEquals(3, s[2].times); assertEquals(null, s[2].until)
        assertEquals(Steps.REPEAT_DEFAULT, s[3].times); assertEquals("到底了", s[3].until!![0].text); assertEquals(3_000L, s[3].untilMs)
        assertFalse(s.any { it.isAction })
    }

    @Test fun nestedBlocksElseIfAndNewLines() {
        val s = Steps.parseAll("repeat 2 {\n  try { tap 下一个; wait text:完成 }\n  if gone:加载中 { tap 刷新 } else if text:重试 { tap 重试 }\n}")
        val r = s.single()
        assertEquals(listOf("try", "if"), r.body.map { it.kind.key })
        assertEquals(listOf("tap", "wait"), r.body[0].body.map { it.kind.key })
        assertEquals("if", r.body[1].alt.single().kind.key)
        assertEquals(8, Steps.count(s))
    }

    @Test fun valueBlanksAreNotBlocks() {
        val s = Steps.parseAll("if text:{1} { tap *{1}*@1 }")
        assertEquals("{1}", s[0].until!![0].text)
        assertEquals(StepTarget.Words("*{1}*", 1), s[0].body[0].target)
    }

    @Test fun badBlocksSayHow() {
        refused("if text:a tap b")
        refused("if text:a { tap b")
        refused("repeat { tap a }")
        refused("repeat 99 { tap a }")
        refused("try { }")
        refused("else { tap a }")
        refused("if text:a { tap b } tap c")
    }

    @Test fun wordsAtN() {
        assertEquals(StepTarget.Words("删除", 2), Steps.parse("tap 删除@2").target)
        assertEquals(StepTarget.Words("删除", -1), Steps.parse("tap 删除@-1").target)
        assertEquals(StepTarget.Words("a@b.com"), Steps.parse("tap a@b.com").target)
    }

    @Test fun patterns() {
        assertTrue(TextPat("*蓝牙*").exact("已连接的 蓝牙 设备"))
        assertTrue(TextPat("共*条").exact("共 12 条"))
        assertFalse(TextPat("共*条").exact("共 12 条结果"))
        assertTrue(TextPat("共*条").hit("共12条"))
        assertTrue(TextPat("设置").hit("搜索设置项")); assertFalse(TextPat("设置").exact("搜索设置项"))
        assertTrue(TextPat("a.b").exact("a.b")); assertFalse(TextPat("a.b").exact("axb"))
        assertTrue(TextPat("*a.b*").exact("xa.by")); assertFalse(TextPat("*a.b*").exact("xaxby"))
    }

    @Test fun hitsInReadingOrderPatternSkipsTheTypedBox() {
        val els = listOf(
            el("蓝牙耳机 专区", 500, 400, 900, 460),
            el("蓝牙", 100, 100, 900, 160, editable = true),
            el("蓝牙设置", 100, 300, 900, 360),
            el("蓝牙 · 连接与共享", 100, 404, 450, 456),
        )
        assertEquals(listOf("蓝牙设置", "蓝牙 · 连接与共享", "蓝牙耳机 专区"), wordHits(els, "*蓝牙*").map { it.text })
        assertEquals(listOf("蓝牙"), wordHits(els, "蓝牙").map { it.text })
        assertEquals(listOf("蓝牙"), wordHits(listOf(els[1]), "*蓝牙*").map { it.text })
    }

    @Test fun aLessonPicksTheNthResultOfTheValue() {
        val snap = TeachSnap("com.android.settings", listOf(
            el("声音", 100, 100, 900, 160, editable = true),
            el("声音和触感", 100, 300, 900, 360),
            el("通知声音", 100, 400, 900, 460),
        ))
        val lines = listOf(
            TeachLine("tap 搜索设置项", "text:搜索历史", "", "tap", at = 600 to 130, words = "搜索设置项"),
            TeachLine("type", null, "", "type", typed = "声音"),
            TeachLine("tap 通知声音", "text:通知声音", "", "tap", at = 400 to 430, words = "通知声音", snap = snap),
        )
        val (body, n) = Teach.build(lines, setOf(0, 1, 2), emptySet())
        assertEquals(1, n)
        assertEquals("tap 搜索设置项 until text:搜索历史; type {1}; tap *{1}*@2 until change", body)
        val filled = Steps.split(body).map { Teach.fill(it, listOf("蓝牙")).first }
        assertEquals(StepTarget.Words("*蓝牙*", 2), Steps.parse(filled[2]).target)
    }

    @Test fun outlineShowsBlocks() {
        assertEquals(listOf(0 to "tap 设置", 0 to "if text:关闭 {", 1 to "tap 关闭", 0 to "} else {", 1 to "back", 0 to "}", 0 to "type {1}"),
            Steps.outline("tap 设置; if text:关闭 { tap 关闭 } else { back }; type {1}"))
    }
}
