package com.kilon.agentvscreen.cmd

import android.content.Context
import com.kilon.agentvscreen.host.UserAsk
import org.json.JSONObject
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

/** 1.4.0-beta10: a teach request is owned by the lesson while the user works on it; save always reaches the agent. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V140Beta10Test {
    private val ctx: Context get() = RuntimeEnvironment.getApplication()

    private fun fresh(): UserAsk.Ask {
        UserAsk.reset(); UserAsk.bind(null)
        return UserAsk.create(ctx, UserAsk.Kind.TEACH, "teach:k", "教一下", "")
    }

    @Test fun boundRequestSurvivesTurnEndCallerCancelAndScreenClose() {
        val a = fresh()
        UserAsk.bind(a.id)
        UserAsk.withdrawSession(ctx, a.session)
        UserAsk.withdraw(ctx, a)
        UserAsk.screenGone(ctx, "released")
        assertNotNull(UserAsk.byId(a.id))
        assertNull(a.answer)
        assertTrue(UserAsk.taken(a))
        UserAsk.bind(null)
    }

    @Test fun unboundRequestStillGoesWithTheTurn() {
        val a = fresh()
        UserAsk.withdrawSession(ctx, a.session)
        assertNull(UserAsk.byId(a.id))
    }

    @Test fun boundRequestDoesNotExpire() {
        UserAsk.reset(); UserAsk.bind(null)
        val a = UserAsk.create(ctx, UserAsk.Kind.TEACH, "teach:x", "教一下", "", validMs = 1)
        Thread.sleep(5)
        assertFalse(a.live)
        UserAsk.bind(a.id)
        assertTrue(a.live)
        UserAsk.bind(null)
    }

    @Test fun saveAnswersTheOpenRequest() {
        val a = fresh()
        UserAsk.deliver(ctx, a.id, a.key, a.token, a.tokenName, a.session, a.title, JSONObject().put("segment", "s1"))
        assertEquals(true, a.answer)
        assertEquals("s1", a.reply?.optString("segment"))
    }

    @Test fun saveAfterTheRequestWentAwayIsKeptUnderTheSameKey() {
        val a = fresh()
        UserAsk.withdraw(ctx, a)
        assertNull(UserAsk.byId(a.id))
        UserAsk.deliver(ctx, a.id, a.key, a.token, a.tokenName, a.session, a.title, JSONObject().put("segment", "s2"))
        val got = UserAsk.find(a.key, a.token)
        assertNotNull(got)
        assertEquals(true, got!!.answer)
        assertEquals("s2", got.reply?.optString("segment"))
    }
}
