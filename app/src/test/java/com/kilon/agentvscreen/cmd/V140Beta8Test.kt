package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.4.0-beta8: @N order, collect entries. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V140Beta8Test {
    @Test fun sameRowCountsByTheMiddleAsTheListDoes() {
        val word = El(1, "搜索", Rect(100, 510, 172, 552), true, clickable = true)
        val box = El(2, "搜索", Rect(40, 490, 1224, 572), true, clickable = true)
        val hits = wordHits(listOf(word, box), "搜索", hints = true)
        assertEquals(listOf(word, box), hits)
    }

    @Test fun collectLeavesOutInputsAndLoneNumbers() {
        val els = listOf(
            El(1, "", Rect(40, 200, 1200, 300), true, editable = true, kind = ElKind.EMPTY_INPUT, hint = "EditText"),
            El(2, "语音搜索", Rect(1100, 220, 1180, 280), true, clickable = true),
            El(3, "281", Rect(100, 900, 160, 940), false),
            El(4, "蓝牙", Rect(100, 1200, 300, 1260), true))
        val got = entriesOfEls(els, 1264, 2800).map { it.first }
        assertEquals(listOf("蓝牙"), got)
        assertTrue(got.none { it.contains("281") })
    }
}
