package com.kilon.agentvscreen.cmd

import android.graphics.Rect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.3.0-beta18: a lesson's tap that follows the typed parameter; tab bars found by their shape. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V130Beta18Test {

    @Test fun aTapOnTheResultOfTheTypedWordsGoesToThePlaceNotTheWords() {
        val lines = listOf(
            TeachLine("tap 搜索设置项", "text:搜索历史", "", "tap", at = 600 to 200, words = "搜索设置项"),
            TeachLine("type", null, "", "type", typed = "声音"),
            TeachLine("tap 声音 · 声音和触感", "text:声音和触感", "", "tap", at = 400 to 520, words = "声音 · 声音和触感"),
        )
        val (body, n) = Teach.build(lines, setOf(0, 1, 2), emptySet())
        assertEquals(1, n)
        assertEquals("tap 搜索设置项 until text:搜索历史; type {1}; tap 400 520 until change", body)
    }

    @Test fun aLiteralTypingKeepsTheWordsOfTheNextTap() {
        val lines = listOf(
            TeachLine("type", null, "", "type", typed = "声音"),
            TeachLine("tap 声音 · 声音和触感", "text:声音和触感", "", "tap", at = 400 to 520, words = "声音 · 声音和触感"),
        )
        assertEquals("type 声音; tap 声音 · 声音和触感 until text:声音和触感", Teach.build(lines, setOf(0, 1), setOf(0)).first)
    }

    @Test fun aBottomTabBarIsFoundByItsShape() {
        var n = 0
        fun tab(text: String, l: Int) = El(++n, text, Rect(l, 2650, l + 250, 2780), true, clickable = true)
        val card = El(++n, "中信证券", Rect(0, 2300, 1264, 2780), true, clickable = true)
        val bar = tabBarBoxes(listOf(card, tab("首页", 20), tab("应用", 330), tab("小游戏", 640), tab("我的", 950)), 1264, 2800)
        assertTrue(bar.size >= 4)
        assertTrue(bar.none { it == card.box })
    }

    @Test fun threeTagsInTheMiddleOfThePageAreNotABar() {
        var n = 0
        fun chip(text: String, l: Int) = El(++n, text, Rect(l, 1400, l + 250, 1480), true, clickable = true)
        assertTrue(tabBarBoxes(listOf(chip("a", 20), chip("b", 330), chip("c", 640), chip("d", 950)), 1264, 2800).isEmpty())
    }

    @Test fun lessonSearchMatchesEveryWordInNameAppOrSteps() {
        val f = arrayOf("搜蓝牙", "设置", "com.android.settings", "tap 搜索设置项; type {1}")
        assertTrue(com.kilon.agentvscreen.ui.lessonMatches("设置 蓝牙", *f))
        assertTrue(com.kilon.agentvscreen.ui.lessonMatches("SETTINGS", *f))
        assertTrue(!com.kilon.agentvscreen.ui.lessonMatches("蓝牙 浏览器", *f))
    }
}
