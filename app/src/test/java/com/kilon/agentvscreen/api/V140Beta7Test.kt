package com.kilon.agentvscreen.api

import android.content.Context
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** 1.4.0-beta7: each connected agent is its own token, with its own record. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V140Beta7Test {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    @Test fun sessionNamesItsAgent() {
        Tokens.init(ctx)
        val (t, _) = Tokens.create("cloud", "system")
        assertEquals(t.id, Agents.ofSession("${t.id}.default"))
        assertEquals(Tokens.MAIN, Agents.ofSession("default"))
        assertEquals(Tokens.MAIN, Agents.ofSession("nosuch.default"))
        assertEquals(Tokens.MAIN, Agents.ofSession(null))
        assertEquals("cloud", Agents.nameOfSession("${t.id}.x"))
    }

    @Test fun oldRecordMovesToTheFirstAgentAndAgentsStaySeparate() {
        val p = ctx.getSharedPreferences("agent_vscreen_ledger", Context.MODE_PRIVATE)
        p.edit().clear().putLong("install_at", 1000L).putLong("last_call_at", 2000L).commit()
        AgentLedger.init(ctx)
        assertEquals(1000L, AgentLedger.of(Tokens.MAIN).installAt)
        assertTrue(!p.contains("install_at"))
        AgentLedger.installed("abc")
        assertTrue(AgentLedger.of("abc").installAt > 1000L)
        assertEquals(1000L, AgentLedger.of(Tokens.MAIN).installAt)
        assertEquals(1000L, AgentLedger.snap.value.installAt)
        AgentLedger.forget("abc")
        assertEquals(0L, AgentLedger.of("abc").installAt)
        AgentLedger.init(ctx)
        assertEquals(0L, AgentLedger.of("abc").installAt)
        assertEquals(1000L, AgentLedger.of(Tokens.MAIN).installAt)
    }
}
