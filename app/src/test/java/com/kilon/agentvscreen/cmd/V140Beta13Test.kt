package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.core.VScreenShell
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.4.0-beta13: keyboard shortcuts and typing one character at a time. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V140Beta13Test {
    @Test fun shortcutsReadAsKeyAndModifiers() {
        assertEquals(29 to listOf(113), VScreenShell.combo("ctrl+a"))
        assertEquals(61 to listOf(59), VScreenShell.combo("Shift+Tab"))
        assertEquals(54 to listOf(113, 59), VScreenShell.combo("ctrl+shift+z"))
        assertEquals(134 to listOf(57), VScreenShell.combo("alt+f4"))
        assertNull(VScreenShell.combo("enter"))
        assertEquals(7, VScreenShell.keyCode("0")); assertEquals(16, VScreenShell.keyCode("9"))
    }

    @Test fun systemShortcutsAreRefused() {
        assertTrue(runCatching { VScreenShell.combo("meta+d") }.isFailure)
        assertTrue(runCatching { VScreenShell.combo("alt+tab") }.isFailure)
        assertTrue(runCatching { VScreenShell.combo("hyper+a") }.isFailure)
        assertNotNull(VScreenShell.keyProblem("win+r"))
    }

    @Test fun keyStepTakesShortcuts() {
        val k = Steps.parse("key ctrl+c")
        assertEquals(StepKind.KEY, k.kind); assertEquals("ctrl+c", k.text)
        assertTrue(runCatching { Steps.parse("key meta+d") }.isFailure)
    }

    @Test fun typeSlowOnlyAfterQuotedText() {
        val a = Steps.parse("type \"你好 world\" slow")
        assertEquals("你好 world", a.text); assertEquals(Steps.SLOW_TYPE_MS, a.ms)
        val b = Steps.parse("type \"abc\" slow 200ms")
        assertEquals("abc", b.text); assertEquals(200L, b.ms)
        // Unquoted words that end in slow are text, typed at once.
        val c = Steps.parse("type drive slow")
        assertEquals("drive slow", c.text); assertNull(c.ms)
        val d = Steps.parse("type hello")
        assertNull(d.ms)
    }

    @Test fun playAudioReadsBothRecordsAndAnExplicitUidDefaultIsSilent() {
        val PA = com.kilon.agentvscreen.core.PlayAudio
        val honor = "Uid mode: PLAY_AUDIO: default\nPLAY_AUDIO: ignore; time=+3m\n"
        assertEquals(com.kilon.agentvscreen.core.PlayAudio.Modes("ignore", "default"), PA.parse(honor))
        assertTrue(PA.silent(PA.parse(honor)))
        // The case the beta12 repair left: package allowed, the UID's explicit default still mutes.
        assertTrue(PA.silent(PA.parse("Uid mode: PLAY_AUDIO: default\nPLAY_AUDIO: allow\n")))
        assertTrue(!PA.silent(PA.parse("Uid mode: PLAY_AUDIO: allow\nPLAY_AUDIO: allow\n")))
        assertTrue(!PA.silent(PA.parse("No operations.\n")))
        // AOSP plays only on exactly MODE_ALLOWED: an explicit package `default` mutes as well (what older repairs wrote).
        assertTrue(PA.silent(PA.parse("PLAY_AUDIO: default\n")))
        assertTrue(!PA.silent(PA.parse("PLAY_AUDIO: foreground\n")))
        assertEquals("ignore", com.kilon.agentvscreen.core.VScreenDisplayService.parsePlayAudio(honor))
    }

    @Test fun aScanIsReadPerUserAndPackage() {
        val PA = com.kilon.agentvscreen.core.PlayAudio
        val out = "@@ 0 tv.danmaku.bili 10234\nUid mode: PLAY_AUDIO: default\nPLAY_AUDIO: allow\n" +
            "@@ 0 com.example.ok 10300\nNo operations.\n" +
            "@@ 128 tv.danmaku.bili 12810234\nPLAY_AUDIO: ignore\n"
        val hits = PA.parseScan(out)
        assertEquals(3, hits.size)
        assertEquals(listOf(0 to "tv.danmaku.bili", 128 to "tv.danmaku.bili"), hits.filter { PA.silent(it.modes) }.map { it.user to it.pkg })
        assertEquals(10234, hits[0].uid)
        val after = PA.parseScan("@@after 0 tv.danmaku.bili\nUid mode: PLAY_AUDIO: allow\nPLAY_AUDIO: allow\n")
        assertTrue(after.size == 1 && !PA.silent(after[0].modes))
        assertTrue(PA.giveBack(128, "tv.danmaku.bili", 12810234).contains("--user 128 --uid 12810234 PLAY_AUDIO allow"))
        assertTrue(PA.denied("java.lang.SecurityException: uid 2000 does not have android.permission.MANAGE_APP_OPS_MODES"))
    }

    @Test fun theShellScriptsParse() {
        val PA = com.kilon.agentvscreen.core.PlayAudio
        for (script in listOf(PA.SCAN, PA.giveBack(0, "a.b", 10123), PA.giveBack(10, "a.b", null))) {
            val p = ProcessBuilder("sh", "-n", "-c", script).redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            assertEquals(out, 0, p.waitFor())
        }
    }

    /** rainbow: HTTP, MCP and CLI must be the same. All three end in one argv for one handler; check the typed form. */
    @Test fun typedCallsGiveTheSameArgvAsTheCommandLine() {
        val split = com.kilon.agentvscreen.api.ApiServer::splitCommand
        fun typed(cmd: String, args: String?, opts: org.json.JSONObject = org.json.JSONObject()) = CommandSpec.argvOf(CommandSpec.of(cmd)!!, args, opts)
        assertEquals(split("act \"tap 1; key ctrl+a; type \\\"hi\\\" slow 200ms\" --human"),
            typed("act", "tap 1; key ctrl+a; type \"hi\" slow 200ms", org.json.JSONObject().put("human", true)))
        assertEquals(split("sound mute 微信"), typed("sound", "mute 微信"))
        assertEquals(split("sound plays com.example.music on"), typed("sound", "plays com.example.music on"))
        assertEquals(split("stop 计算器"), typed("stop", "计算器"))
        // Every option of every command is in its typed tool's schema.
        for (c in CommandSpec.commands) {
            val props = CommandSpec.inputSchema(c).getJSONObject("properties")
            for (o in c.allOpts.filter { !it.deprecated }) assertTrue("${c.name} --${o.name}", props.has(o.name.replace('-', '_')))
        }
    }
}
