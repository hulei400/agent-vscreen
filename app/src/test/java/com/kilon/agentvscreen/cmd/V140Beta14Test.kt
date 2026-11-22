package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.4.0-beta14: collect leaves out a search bar drawn with no readable name (test R8). */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V140Beta14Test {
    @Test fun topSearchBarIsNotAnEntry() {
        val els = listOf(
            El(1, "搜索设置项", Rect(40, 300, 1220, 400), true, clickable = true),
            El(2, "WLAN", Rect(100, 600, 1200, 700), true, clickable = true),
            El(3, "蓝牙", Rect(100, 750, 1200, 850), true, clickable = true))
        assertEquals(listOf("WLAN", "蓝牙"), entriesOfEls(els, 1264, 2800).map { it.first })
    }

    @Test fun aRowThatSaysSearchFurtherDownStays() {
        val els = listOf(
            El(1, "WLAN", Rect(100, 300, 1200, 400), true, clickable = true),
            El(2, "搜索历史", Rect(100, 600, 1200, 700), true, clickable = true))
        assertEquals(listOf("WLAN", "搜索历史"), entriesOfEls(els, 1264, 2800).map { it.first })
    }

    @Test fun searchBoxByItsSpokenNameIsNotAnEntry() {
        val els = listOf(
            El(1, "搜索框 · MOMO陌陌", Rect(40, 200, 1220, 300), true, clickable = true),
            El(2, "微信", Rect(100, 600, 1200, 700), true, clickable = true))
        assertEquals(listOf("微信"), entriesOfEls(els, 1264, 2800).map { it.first })
    }
}
