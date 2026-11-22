package com.kilon.agentvscreen.cmd

import android.content.Context
import com.kilon.agentvscreen.api.EventHub
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/** 1.3.0-beta17: old unconfirmed events do not come back after every restart; cut limits are said once. */
@RunWith(RobolectricTestRunner::class)
class V130Beta17Test {
    private fun ev(seq: Long, at: Long, repeat: Boolean = false) = JSONObject().put("seq", seq).put("session", "s").put("kind", "keep")
        .put("name", "n$seq").put("text", "t").put("at", at).also { if (repeat) it.put("repeat", true) }

    private fun restartWith(handed: JSONArray, handedAt: Long?): List<JSONObject> {
        val ctx: Context = RuntimeEnvironment.getApplication()
        EventHub.resetForTest()
        val f = File(File(ctx.filesDir, "vscreen").apply { mkdirs() }, "events.json")
        val o = JSONObject().put("seq", 10).put("queues", JSONObject()).put("handed", JSONObject().put("s", handed))
        if (handedAt != null) o.put("handed_at", JSONObject().put("s", handedAt))
        f.writeText(o.toString())
        EventHub.init(ctx)
        return EventHub.take("s", 0L)
    }

    @Test fun anOldUnconfirmedBatchIsNotGivenAgainAfterARestart() {
        val old = System.currentTimeMillis() - EventHub.REDELIVER_MS - 60_000L
        assertTrue(restartWith(JSONArray().put(ev(3, old)), old).isEmpty())
    }

    @Test fun aRecentUnconfirmedBatchIsGivenAgainOnceAsARepeat() {
        val now = System.currentTimeMillis()
        val got = restartWith(JSONArray().put(ev(3, now)), now)
        assertEquals(1, got.size)
        assertTrue(got[0].optBoolean("repeat"))
        // Already given again once: a further restart does not bring it a third time.
        assertTrue(restartWith(JSONArray().put(ev(3, now, repeat = true)), now).isEmpty())
    }

    @Test fun aFileFromBeforeThisVersionUsesTheEventTimes() {
        val old = System.currentTimeMillis() - 24 * 3_600_000L
        assertTrue(restartWith(JSONArray().put(ev(3, old)), null).isEmpty())
    }

    @Test fun aCutStepDurationIsWrittenOnce() {
        takeAdjusted()
        Steps.parse("wait 5m")
        val note = takeAdjusted().joinToString()
        assertTrue(note, note.contains("5m"))
        assertFalse(note, note.contains("5m 5m"))
    }
}
