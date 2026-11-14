package com.kilon.agentvscreen.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Terminal log: full commands, exit codes, history, cap. Runs on the JVM with a fake executor. */
class VScreenTerminalTest {
    private val realExecutor = VScreenTerminal.executor

    @After
    fun restore() {
        VScreenTerminal.executor = realExecutor
        VScreenTerminal.clear()
    }

    @Test
    fun runRecordsFullEntry() {
        VScreenTerminal.executor = { _, _ -> VScreenTerminal.ShellResult(0, "hello\n", "") }
        val e = VScreenTerminal.run("echo hello", 5_000L)
        assertEquals("echo hello", e.command)
        assertEquals(0, e.exitCode)
        assertEquals("hello\n", e.stdout)
        assertFalse(e.timedOut)
        assertFalse(e.notReady)
        assertEquals(1, VScreenTerminal.log.value.size)
        assertEquals(e, VScreenTerminal.log.value.first())
    }

    @Test
    fun nonZeroExitAndTimeout() {
        VScreenTerminal.executor = { _, _ -> VScreenTerminal.ShellResult(124, "partial", "") }
        val e = VScreenTerminal.run("sleep 99", 1_000L)
        assertTrue(e.timedOut)
        assertEquals(124, e.exitCode)
        assertEquals("partial", e.stdout)
    }

    @Test
    fun notReadyDetected() {
        VScreenTerminal.executor = { _, _ -> VScreenTerminal.ShellResult(126, "", "shizuku not ready (state=NOT_RUNNING)") }
        assertTrue(VScreenTerminal.run("id", 5_000L).notReady)
    }

    @Test
    fun historyMostRecentFirstDedupsConsecutive() {
        VScreenTerminal.executor = { _, _ -> VScreenTerminal.ShellResult(0, "", "") }
        VScreenTerminal.run("a", 5_000L)
        VScreenTerminal.run("a", 5_000L)
        VScreenTerminal.run("b", 5_000L)
        assertEquals(listOf("b", "a"), VScreenTerminal.history())
    }

    @Test
    fun logCapsAtMaxEntries() {
        VScreenTerminal.executor = { _, _ -> VScreenTerminal.ShellResult(0, "", "") }
        repeat(VScreenTerminal.MAX_ENTRIES + 5) { VScreenTerminal.run("cmd $it", 5_000L) }
        assertEquals(VScreenTerminal.MAX_ENTRIES, VScreenTerminal.log.value.size)
        assertEquals("cmd 5", VScreenTerminal.log.value.first().command)
    }

    @Test
    fun timeoutIsClamped() {
        var got = -1L
        VScreenTerminal.executor = { _, t -> got = t; VScreenTerminal.ShellResult(0, "", "") }
        VScreenTerminal.run("true", 999_000L)
        assertEquals(VScreenTerminal.MAX_TIMEOUT_MS, got)
        VScreenTerminal.run("true", 10L)
        assertEquals(1_000L, got)
    }

    @Test
    fun clearEmpties() {
        VScreenTerminal.executor = { _, _ -> VScreenTerminal.ShellResult(0, "", "") }
        VScreenTerminal.run("a", 5_000L)
        VScreenTerminal.clear()
        assertTrue(VScreenTerminal.log.value.isEmpty())
        assertTrue(VScreenTerminal.history().isEmpty())
    }
}
