package com.kilon.agentvscreen.host

import android.os.Bundle
import com.kilon.agentvscreen.core.RootMain
import com.kilon.agentvscreen.host.RootBackend.Phase
import com.kilon.agentvscreen.host.ShizukuManager.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 1.4.2-beta3: the root way in. What su's answer means, the launch script, root phases in the shared states. */
@RunWith(RobolectricTestRunner::class)
class RootWayTest {
    @Test fun suAnswers() {
        assertNull(RootBackend.verdict(true, 0, "AVS started 1234 2000"))
        assertNull(RootBackend.verdict(true, 0, "AVS reused 1234 0"))
        assertEquals(Phase.DENIED, RootBackend.verdict(true, 1, "Permission denied")!!.first)
        assertEquals(Phase.DENIED, RootBackend.verdict(false, -1, "")!!.first)
        val f = RootBackend.verdict(true, 0, "AVS failed\nException in thread main: boom")!!
        assertEquals(Phase.FAILED, f.first); assertTrue(f.second, f.second.contains("boom"))
    }

    @Test fun scriptAdoptsOrStartsAsShell() {
        val s = RootBackend.script("com.kilon.agentvscreen", 54, "/data/app/~~a==/com.kilon.agentvscreen-b==/base.apk", 0)
        assertTrue(s.contains("A='/data/app/~~a==/com.kilon.agentvscreen-b==/base.apk'"))
        assertTrue(s.contains("V=54"))
        assertTrue(s.contains("su 2000 -c"))
        assertTrue(s.contains("--nice-name=${RootMain.NICE_NAME} ${RootMain::class.java.name} com.kilon.agentvscreen \$V \$A 0"))
        assertTrue(s.contains("echo x > \$P.req"))
        assertTrue(s.contains("P=/data/local/tmp/agent-vscreen-root\n"))
        assertTrue(s.endsWith("exit 0\n"))
    }

    @Test fun rootPhasesInSharedStates() {
        assertEquals(State.READY, Privileged.rootState(Phase.IDLE, alive = true))
        assertEquals(State.NOT_INSTALLED, Privileged.rootState(Phase.NO_SU, false))
        assertEquals(State.NOT_RUNNING, Privileged.rootState(Phase.STARTING, false))
        assertEquals(State.NOT_RUNNING, Privileged.rootState(Phase.FAILED, false))
        assertEquals(State.NEED_PERMISSION, Privileged.rootState(Phase.DENIED, false))
        assertEquals(State.NEED_PERMISSION, Privileged.rootState(Phase.IDLE, false))
    }

    @Test fun providerCallSignatures() {
        val s = String::class.java; val b = Bundle::class.java
        val r = RootMain.callArgs(arrayOf(s, s, s, s, s, b), "x.root", Bundle())
        assertNotNull(r); assertEquals("x.root", r!![2]); assertEquals(RootMain.METHOD, r[3])
        val q = RootMain.callArgs(arrayOf(s, s, s, s, b), "x.root", Bundle())
        assertEquals("x.root", q!![1]); assertEquals(RootMain.METHOD, q[2])
        assertNull(RootMain.callArgs(arrayOf(s, b), "x.root", Bundle()))
    }
}
