package com.kilon.agentvscreen.cmd

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.4.0-beta11: the teach recorder names places by the step language's own rule (numbers are words too). */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V140Beta11Test {
    @Test fun aNumberAloneIsAWordAndCoordinatesAreNot() {
        assertTrue(Steps.readsAsWords("8"))
        assertTrue(Steps.readsAsWords("12"))
        assertTrue(Steps.readsAsWords("加"))
        assertTrue(Steps.readsAsWords("设置"))
        assertTrue(Steps.readsAsWords("Wi-Fi 设置"))
        // Words with a space are written quoted (`tap "12 34"`), which reads as those words, not a point.
        assertTrue(Steps.readsAsWords("12 34"))
        assertFalse(Steps.readsAsWords("#3"))
    }

    @Test fun checkStepReadsWhatAndUnless() {
        val a = Steps.parse("check \"确认收件人\"")
        assertEquals(StepKind.CHECK, a.kind); assertEquals("确认收件人", a.text); assertNull(a.until)
        val b = Steps.parse("check \"确认金额\" unless text:支付成功")
        assertEquals("确认金额", b.text); assertEquals("text:支付成功", b.until!!.single().raw)
        assertFalse(b.isAction)
        assertTrue(runCatching { Steps.parse("check") }.isFailure)
    }

    @Test fun lessonWritesChecksAfterTheirSteps() {
        val lines = listOf(TeachLine("tap 设置", null, "", "tap"), TeachLine("tap 蓝牙", null, "", "tap", check = "看蓝牙开了没有"))
        val (steps, _) = Teach.build(lines, setOf(0, 1), emptySet())
        assertEquals("tap 设置; tap 蓝牙; check \"看蓝牙开了没有\"", steps)
        val (s2, _) = Teach.build(lines, setOf(0, 1), emptySet(), mapOf(0 to "看一下"))
        assertEquals("tap 设置; check \"看一下\"; tap 蓝牙", s2)
        Steps.parseAll(steps)
    }

    @Test
    fun gestureEdgesBelongToTheSystem() {
        val e = com.kilon.agentvscreen.ui.GestureEdges(30f, 80f, 30f, 60f, 1080f, 2400f)
        assertTrue(e.has(androidx.compose.ui.geometry.Offset(540f, 2380f))) // home swipe from the bottom
        assertTrue(e.has(androidx.compose.ui.geometry.Offset(5f, 1200f)))    // back from the left edge
        assertFalse(e.has(androidx.compose.ui.geometry.Offset(540f, 1200f)))
    }

    @Test
    fun lessonStartsAsNone() {
        assertTrue(Teach.lesson.value is Lesson.None || Teach.lesson.value is Lesson.Review)
        assertNull(Lesson.None.askId)
    }
}
