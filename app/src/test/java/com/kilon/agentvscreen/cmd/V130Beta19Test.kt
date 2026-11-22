package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Random

/** 1.3.0-beta19: human pacing stays within its bounds. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V130Beta19Test {

    @Test fun pausesHoldsAndGapsStayInRange() {
        val h = Human(Random(7))
        val pauses = (1..2000).map { h.pauseMs() }
        assertTrue(pauses.all { it in 150L..2_600L })
        assertTrue(pauses.average() in 350.0..800.0)
        assertTrue(pauses.distinct().size > 300)
        assertTrue((1..500).map { h.holdMs() }.all { it in 55..140 })
        assertTrue((1..500).map { h.keyGapMs() }.all { it in 70L..260L })
        assertTrue((1..500).map { h.vary(1000L, 0.2) }.all { it in 800L..1200L })
    }

    @Test fun tapsLandInsideTheElementAndOnTheScreen() {
        val h = Human(Random(3))
        val box = Rect(100, 200, 140, 230)
        repeat(1000) { val (x, y) = h.near(120, 215, box, 1080, 2400); assertTrue(x in 102..138 && y in 202..228) }
        repeat(1000) { val (x, y) = h.near(2, 2398, null, 1080, 2400); assertTrue(x in 0..12 && y in 2388..2399) }
        val moved = (1..200).count { h.near(540, 1200, Rect(400, 1100, 680, 1300), 1080, 2400) != (540 to 1200) }
        assertTrue(moved > 150)
    }

    @Test fun typingPiecesJoinBackAndKeepEmojiWhole() {
        val h = Human(Random(11))
        val text = "蓝牙耳机😀 abc"
        repeat(50) {
            val p = h.pieces(text)
            assertEquals(text, p.joinToString(""))
            assertTrue(p.all { it.isNotEmpty() && !Character.isHighSurrogate(it.last()) })
        }
    }

    @Test fun anUntilThatNamesTheTypedValueBeforeTheTypingIsDropped() {
        // Test M: the search box tapped twice; the second tap's reading came after the typing.
        val snap = TeachSnap("com.android.settings", listOf(
            El(0, "声音", Rect(100, 100, 900, 160), true, clickable = true, editable = true),
            El(0, "声音和触感", Rect(100, 300, 900, 360), true, clickable = true),
            El(0, "声随心动 · 铃声主题", Rect(100, 400, 900, 460), true, clickable = true),
        ))
        val lines = listOf(
            TeachLine("tap 搜索设置项", "text:向上导航", "", "tap", at = 600 to 130, words = "搜索设置项"),
            TeachLine("tap 搜索设置项", "text:声音", "", "tap", at = 600 to 130, words = "搜索设置项"),
            TeachLine("type", null, "", "type", typed = "声音"),
            TeachLine("tap 声音和触感", "text:声随心动 · 铃声主题", "", "tap", at = 400 to 330, words = "声音和触感", snap = snap),
        )
        val (body, _) = Teach.build(lines, setOf(0, 1, 2, 3), emptySet())
        assertEquals("tap 搜索设置项 until text:向上导航; tap 搜索设置项; type {1}; tap *{1}*@1 until change", body)
    }

    @Test fun versionsCompareAsReleases() {
        val u = com.kilon.agentvscreen.host.Updates
        assertTrue(u.compare("1.3.0", "1.3.0-beta19") > 0)
        assertTrue(u.compare("v1.3.0-beta19", "1.3.0-beta2") > 0)
        assertTrue(u.compare("1.2.10", "1.2.9") > 0)
        assertTrue(u.compare("1.2.0", "1.3.0-beta1") < 0)
        assertEquals(0, u.compare("v1.2.0", "1.2.0"))
        val r = u.parse("""[{"tag_name":"v1.2.0","name":"1.2.0","body":"notes","html_url":"https://x/r","draft":false,"prerelease":false,"assets":[{"name":"a.apk","browser_download_url":"https://x/a.apk"}]},{"tag_name":"v9.9.9","draft":true}]""")
        assertEquals(1, r.size); assertEquals("1.2.0", r[0].version); assertEquals("https://x/a.apk", r[0].apk)
    }
}
