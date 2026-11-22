package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.4.0-beta5: points as shares of the screen, taps beside words, lessons written that way. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V140Beta5Test {
    private var n = 0
    private fun el(text: String, l: Int, t: Int, r: Int, b: Int) = El(++n, text, Rect(l, t, r, b), true, clickable = true)

    @After fun reset() { Steps.frame = { null } }

    @Test fun sharesBecomePixelsOfTheScreenInUse() {
        Steps.frame = { 1000 to 2000 }
        assertEquals(StepTarget.At(500, 500), Steps.parse("tap 50% 25%").target)
        Steps.frame = { 2000 to 1000 }
        assertEquals(StepTarget.At(1000, 250), Steps.parse("tap 50% 25%").target)
        val sw = Steps.parse("swipe 50% 80% 50% 20% 300ms")
        assertEquals(listOf(1000 to 800, 1000 to 200), sw.points)
        // One share alone is words (a battery level), and typing is left as written.
        assertEquals(StepTarget.Words("50%"), Steps.parse("tap 50%").target)
        assertEquals("50% 25%", Steps.parse("type 50% 25%").text)
        // Areas of a condition too.
        assertEquals(Rect(200, 100, 400, 200), Steps.cond(Steps.resolvePct("text:07 in 10%,10%,20%,20%")).area)
        assertEquals(Rect(200, 100, 400, 200), Steps.parse("wait text:07 in 10%,10%,20%,20%").until!![0].area)
    }

    @Test fun wordsTakeAnOffset() {
        assertEquals(StepTarget.Words("蓝牙", 0, 120, -8), Steps.parse("tap 蓝牙 +120,-8").target)
        assertEquals(StepTarget.Words("搜索", 2, 40, 0), Steps.parse("tap 搜索@2 +40 +0").target)
        Steps.frame = { 1000 to 2000 }
        assertEquals(StepTarget.Words("蓝牙", 0, 600, -20), Steps.parse("tap 蓝牙 +60%,-1%").target)
        // A word that is a number is never an offset.
        assertEquals(StepTarget.Words("12 34"), Steps.parse("tap \"12 34\"").target)
    }

    @Test fun aTapOnNoWordsIsWrittenBesideTheNearestWordsOnItsRow() {
        val sn = TeachSnap("settings", listOf(el("设置", 100, 50, 300, 120), el("蓝牙", 100, 400, 300, 480), el("WLAN", 100, 300, 300, 380)), "Main")
        val tap = TeachEv.Touch(2000, 900, 440).also { it.end = 2080; it.before = sn }
        val lines = Teach.translate(listOf(tap), null, 1000 to 2000)
        val head = lines.single().head
        assertEquals("tap 蓝牙 +70%,+0%", head)
        Steps.frame = { 1000 to 2000 }
        assertEquals(StepTarget.Words("蓝牙", 0, 700, 0), Steps.parse(head).target)
    }

    @Test fun lessonPointsAreShares() {
        val tap = TeachEv.Touch(2000, 500, 1000).also { it.end = 2080; it.before = TeachSnap("x", listOf(el("标题", 0, 0, 100, 50)), "A") }
        assertEquals("tap 50% 50%", Teach.translate(listOf(tap), null, 1000 to 2000).single().head)
        val sw = TeachEv.Touch(1000, 500, 1600).also { t -> (1..5).forEach { k -> t.path += intArrayOf(500, 1600 - 200 * k, k * 40) }; t.end = 1200 }
        assertTrue(Teach.translate(listOf(sw), null, 1000 to 2000).single().head.startsWith("swipe 50% 80% 50% 30% "))
        // Without a size (no screen) pixels stay.
        assertEquals("tap 500 1000", Teach.translate(listOf(tap), null).single().head)
    }

    @Test fun pctReadsBack() {
        assertEquals("12.5%", Steps.pct(125, 1000))
        assertEquals("50%", Steps.pct(500, 1000))
    }
}

/** 1.4.0-beta6: an offset after a word that is a number; @N counts every listed place. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V140Beta6Test {
    @After fun reset() { Steps.frame = { null } }

    @Test fun offsetAfterANumberWord() {
        assertEquals(StepTarget.Words("8", 0, 50, 100), Steps.parse("tap 8 +50,+100").target)
        assertEquals(StepTarget.Words("8", 0, 50, 100), Steps.parse("tap 8 +50 +100").target)
        Steps.frame = { 1000 to 2000 }
        assertEquals(StepTarget.Words("8", 0, 500, 2000), Steps.parse("tap 8 +50%,+100%").target)
    }

    @Test fun nthCountsListedPlacesUnfolded() {
        val word = El(1, "搜索", Rect(100, 500, 170, 560), true)
        val box = El(2, "搜索", Rect(80, 490, 1180, 570), true, editable = true, kind = ElKind.EMPTY_INPUT, hint = "搜索")
        val hits = wordHits(listOf(word, box), "搜索", hints = true)
        assertTrue(hits.size >= 1)
        assertEquals(1, wordHits(listOf(word, El(3, "搜索", Rect(120, 505, 160, 555), false)), "搜索").size)
        assertEquals(2, wordHits(listOf(word, El(3, "搜索", Rect(120, 505, 160, 555), false)), "搜索", hints = true).size)
    }
}
