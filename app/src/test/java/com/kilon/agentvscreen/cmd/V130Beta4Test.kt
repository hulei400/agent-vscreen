package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.BuildConfig
import com.kilon.agentvscreen.api.AgentLedger
import com.kilon.agentvscreen.api.AgentNews
import com.kilon.agentvscreen.host.Prefs
import com.kilon.agentvscreen.host.ServiceControl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** 1.3.0-beta4: agents hear about updates until they confirm their note; hidden (wakeable) vs blocked. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V130Beta4Test {
    @Test fun newsSinceAVersion() {
        assertTrue(AgentNews.since(BuildConfig.VERSION_CODE).isEmpty())
        val all = AgentNews.since(0)
        assertTrue(all.isNotEmpty())
        assertTrue(all.all { it.changed <= BuildConfig.VERSION_CODE })
        assertTrue(AgentNews.since(15).none { it.changed <= 15 })
        assertEquals("1.3.0-beta2", AgentNews.nameOf(16))
    }

    @Test fun noticeUntilConfirmed() {
        val app = RuntimeEnvironment.getApplication()
        AgentLedger.init(app)
        val t = "tok-notice"
        assertEquals(0, AgentLedger.ackVersion(t))
        assertNotNull(AgentNews.notice(t, freshStretch = false))
        // Not again right away (every command would carry it) ...
        assertNull(AgentNews.notice(t, freshStretch = false))
        // ... but again at a new stretch of use.
        assertNotNull(AgentNews.notice(t, freshStretch = true))
        AgentLedger.ack(t)
        assertEquals(BuildConfig.VERSION_CODE, AgentLedger.ackVersion(t))
        assertNull(AgentNews.notice(t, freshStretch = true))
    }

    @Test fun hiddenIsNotBlocked() {
        val app = RuntimeEnvironment.getApplication()
        Prefs.setServiceOn(app, false); Prefs.setBlocked(app, false)
        assertEquals(ServiceControl.State.HIDDEN, ServiceControl.now(app))
        assertTrue(ServiceControl.title(app).isNotBlank())
        assertNotNull(ServiceControl.sub(app))
        // Hidden is not "agents may not": readiness does not block on it ...
        assertTrue(com.kilon.agentvscreen.host.Readiness.now(app).none { it.code == "service_off" })
        // ... blocked does, and nothing wakes it.
        Prefs.setBlocked(app, true)
        assertEquals(ServiceControl.State.BLOCKED, ServiceControl.now(app))
        assertFalse(ServiceControl.wake(app, "test"))
        assertTrue(com.kilon.agentvscreen.host.Readiness.now(app).any { it.code == "service_off" && it.blocking })
        ServiceControl.allow(app, "test")
        assertEquals(ServiceControl.State.HIDDEN, ServiceControl.now(app))
        Prefs.setServiceOn(app, true)
        assertEquals(ServiceControl.State.ON, ServiceControl.now(app))
        assertNull(ServiceControl.sub(app))
    }

    @Test fun staleBlockedFlagDoesNotBlockLiveService() {
        // v20: a persisted agents_blocked flag can go stale — the service can be back (system restart
        // after an o-kill, accessibility bind) while the flag still says blocked, and launch/shell work.
        // Readiness must not report service_off when the service is alive; a genuinely blocked (dead)
        // service still reports it.
        val live = com.kilon.agentvscreen.host.Readiness.Inputs(
            serviceOn = false, serviceAlive = true, foreground = true,
            privilege = "READY", connection = "READY", needsReconnect = false, userStopped = false,
            frozen = false, remoteMode = "off", remoteUp = true)
        assertTrue(com.kilon.agentvscreen.host.Readiness.evaluate(live).none { it.code == "service_off" })
        val dead = live.copy(serviceAlive = false)
        assertTrue(com.kilon.agentvscreen.host.Readiness.evaluate(dead).any { it.code == "service_off" && it.blocking })
    }
}
