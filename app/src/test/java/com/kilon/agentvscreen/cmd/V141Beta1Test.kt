package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.I18n
import com.kilon.agentvscreen.api.AgentNews
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.api.Tokens
import com.kilon.agentvscreen.core.BackgroundOperation
import com.kilon.agentvscreen.core.TaskLog
import com.kilon.agentvscreen.core.VScreenDisplay
import com.kilon.agentvscreen.core.VScreenDownscale
import com.kilon.agentvscreen.core.VScreenPaddleOcr
import com.kilon.agentvscreen.core.VScreenRules
import com.kilon.agentvscreen.host.Wake
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/** 1.4.1-beta1: per-agent (agentId, name) isolation for schedules & keeps, late-downscale notes, runtime I/O fixes, and Operit METADATA escaping. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V141Beta1Test {
    private lateinit var app: android.app.Application
    private lateinit var handler: VScreenOffloadHandler

    @Before fun setUp() {
        app = RuntimeEnvironment.getApplication()
        I18n.choice = I18n.Lang.ZH
        ApiConfig.init(app)
        Tokens.init(app)
        runCatching { BackgroundOperation.init(app) }
        VScreenRules.init(app)
        VScreenRules.resetForTest()
        Wake.resetForTest()
        File(app.filesDir, "vscreen/wake.json").delete()
        File(app.filesDir, "vscreen/keeps.json").delete()
        File(app.filesDir, "tasks").deleteRecursively()
        TaskLog.init(app)
        handler = VScreenOffloadHandler(app)
    }

    @After fun tearDown() {
        VScreenRules.resetForTest()
        Wake.resetForTest()
        I18n.choice = I18n.Lang.ZH
    }

    @Test fun versionAndNewsNameMatch141() {
        assertEquals("1.4.1", AgentNews.nameOf(51))
        assertEquals("1.4.1-beta1", AgentNews.nameOf(50))
    }

    @Test fun lateDownscaleHintAndNoteConsumeOnce() {
        val hint = VScreenDownscale.fitHint("com.example.demo")
        assertTrue(hint, hint.contains("launch com.example.demo --restart"))
        assertFalse(hint, hint.contains("screen fit"))
        assertNull(VScreenDownscale.takeNote(99))
    }

    @Test fun schedulesAndKeepsIsolateByAgentAndMigrateLegacyToMain() {
        val second = Tokens.create("operit", "operate").first

        // Legacy wake.json without "agent" field must migrate to Tokens.MAIN without loss.
        File(app.filesDir, "vscreen").mkdirs()
        File(app.filesDir, "vscreen/wake.json").writeText(
            JSONObject().put("entries", JSONArray().put(
                JSONObject().put("name", "checkin").put("when", "07:00").put("for", 15).put("session", "default").put("by", "main").put("at", 1000L)
            )).toString()
        )
        Wake.resetForTest()
        val legacyLoaded = Wake.list(app)
        assertEquals(1, legacyLoaded.size)
        assertEquals(Tokens.MAIN, legacyLoaded.single().agentId)
        assertEquals("checkin", legacyLoaded.single().name)

        // Second agent creates a schedule with the exact same name "checkin" — both coexist!
        Call.with(Call("${second.id}.s1", second.id, second.name, second.scopes, "local")) {
            val r = handler.handle(NativeOffloadRequest(listOf("vscreen", "schedule", "08:30", "--name", "checkin"), "${second.id}.s1"))
            assertEquals(r.output, 0, r.exitCode)
        }
        assertEquals(2, Wake.list(app).size) // App UI sees ALL schedules across agents.

        // Legacy keeps.json without "agent" field must also migrate to Tokens.MAIN without loss.
        val noopHost = object : VScreenRules.Host { override fun check(l: VScreenRules.Live, d: VScreenDisplay): String? = null }
        VScreenRules.hostFactory = { noopHost }
        File(app.filesDir, "vscreen/keeps.json").writeText(
            JSONObject().put("seq", 1).put("keeps", JSONArray().put(
                JSONObject().put("name", "盯1").put("sid", "default").put("cond", "text:到货")
                    .put("every", 1_000L).put("ends_at", System.currentTimeMillis() + 600_000L).put("wake", true).put("notify", false)
            )).toString()
        )
        assertEquals(1, VScreenRules.restore())
        assertEquals(Tokens.MAIN, VScreenRules.list().single().spec.agentId)

        // Second agent starts a watch with the exact same name "盯1" — both coexist!
        VScreenRules.start(
            VScreenRules.Spec("盯1", "${second.id}.s1", "text:降价", null, 1_000L, 600_000L, wake = false, notify = true, agentId = second.id),
            noopHost,
        )
        assertEquals(2, VScreenRules.list().size) // App UI sees ALL watches across agents.

        // status for main returns ONLY main's schedule & keep.
        Call.with(Call("default", Tokens.MAIN, "main", setOf("look", "operate", "system"), "local")) {
            val st = handler.handle(NativeOffloadRequest(listOf("vscreen", "status"), "default")).data!!.getJSONObject("result")
            val sch = st.getJSONArray("schedules")
            val kps = st.getJSONArray("keeps")
            assertEquals(1, sch.length())
            assertEquals("07:00", sch.getJSONObject(0).getString("when"))
            assertEquals(Tokens.MAIN, sch.getJSONObject(0).getString("agent"))
            assertEquals(1, kps.length())
            assertTrue(kps.getJSONObject(0).getString("condition").contains("到货"))
        }

        // status for second agent returns ONLY second agent's schedule & keep.
        Call.with(Call("${second.id}.s1", second.id, second.name, second.scopes, "local")) {
            val st = handler.handle(NativeOffloadRequest(listOf("vscreen", "status"), "${second.id}.s1")).data!!.getJSONObject("result")
            val sch = st.getJSONArray("schedules")
            val kps = st.getJSONArray("keeps")
            assertEquals(1, sch.length())
            assertEquals("08:30", sch.getJSONObject(0).getString("when"))
            assertEquals(second.id, sch.getJSONObject(0).getString("agent"))
            assertEquals(1, kps.length())
            assertTrue(kps.getJSONObject(0).getString("condition").contains("降价"))

            // Stopping "盯1" as second agent stops ONLY second agent's "盯1", leaving main's running!
            val off = handler.handle(NativeOffloadRequest(listOf("vscreen", "keep", "off", "盯1"), "${second.id}.s1"))
            assertEquals(off.output, 0, off.exitCode)
        }
        assertEquals(1, VScreenRules.running().size)
        assertEquals(Tokens.MAIN, VScreenRules.running().single().spec.agentId)
    }

    /** Review fix: 1.4.0 records had no "agent" field but their session already named the agent (beta7 prefix). */
    @Test fun legacyPrefixedSessionsKeepTheirAgentAndForgetClearsThem() {
        val second = Tokens.create("operit", "operate").first
        File(app.filesDir, "vscreen").mkdirs()
        File(app.filesDir, "vscreen/wake.json").writeText(
            JSONObject().put("entries", JSONArray()
                .put(JSONObject().put("name", "w1").put("when", "07:00").put("for", 10).put("session", "${second.id}.s1").put("by", "operit").put("at", 1L))
                .put(JSONObject().put("name", "w1").put("when", "08:00").put("for", 10).put("session", "default").put("by", "main").put("at", 2L))
                .put(JSONObject().put("name", "gone").put("when", "09:00").put("for", 10).put("session", "tk_forgotten.s1").put("by", "x").put("at", 3L))
            ).toString()
        )
        Wake.resetForTest()
        val byName = Wake.list(app).associateBy { it.text }
        assertEquals(3, byName.size)
        assertEquals(second.id, byName.getValue("07:00").agentId)
        assertEquals(Tokens.MAIN, byName.getValue("08:00").agentId)
        assertEquals(Tokens.MAIN, byName.getValue("09:00").agentId) // an unknown prefix is the main token's

        val noopHost = object : VScreenRules.Host { override fun check(l: VScreenRules.Live, d: VScreenDisplay): String? = null }
        VScreenRules.hostFactory = { noopHost }
        File(app.filesDir, "vscreen/keeps.json").writeText(
            JSONObject().put("seq", 1).put("keeps", JSONArray().put(
                JSONObject().put("name", "盯1").put("sid", "${second.id}.s1").put("cond", "text:到货")
                    .put("every", 1_000L).put("ends_at", System.currentTimeMillis() + 600_000L).put("wake", true).put("notify", false)
            )).toString()
        )
        assertEquals(1, VScreenRules.restore())
        assertEquals(second.id, VScreenRules.list().single().spec.agentId)

        // Forgetting the agent takes its schedules and watches with it; main's stay.
        com.kilon.agentvscreen.api.Agents.forget(app, second.id)
        assertTrue(Wake.list(app).none { it.agentId == second.id })
        assertEquals(2, Wake.list(app).size)
        assertTrue(VScreenRules.list().none { it.spec.agentId == second.id })
    }

    @Test fun operitMetadataParsesCleanlyWithRealNewlines() {
        val js = File("../integrations/operit/dist/packages/agent_vscreen.js")
        if (!js.isFile) return
        val raw = js.readText()
        val a = raw.indexOf("/* METADATA")
        val b = raw.indexOf("*/", a)
        assertTrue("METADATA header missing", a >= 0 && b > a)
        val metaJson = raw.substring(a + "/* METADATA".length, b).trim()
        val meta = JSONObject(metaJson)
        val tools = meta.getJSONArray("tools")
        var adviceZh: String? = null
        var adviceEn: String? = null
        for (i in 0 until tools.length()) {
            val t = tools.getJSONObject(i)
            if (t.getString("name") == "usage_advice") {
                adviceZh = t.getJSONObject("description").getString("zh")
                adviceEn = t.getJSONObject("description").getString("en")
            }
        }
        assertNotNull(adviceZh)
        assertNotNull(adviceEn)
        // Must contain actual decoded newlines, NOT double-escaped literal "\n".
        assertTrue("zh advice should have real newlines", adviceZh!!.contains("\n- "))
        assertFalse("zh advice still has double-escaped \\n", adviceZh.contains("\\n"))
        assertTrue("en advice should have real newlines", adviceEn!!.contains("\n- "))
        assertFalse("en advice still has double-escaped \\n", adviceEn.contains("\\n"))
    }

    @Test fun taskLogAppendsAndCompactsCleanly() {
        TaskLog.onCommand("s1", "main", listOf("launch", "设置"), "OK", 0)
        TaskLog.onCommand("s1", "main", listOf("done"), "OK", 0)
        TaskLog.onCommand("s2", "main", listOf("launch", "时钟"), "OK", 0)
        TaskLog.onCommand("s2", "main", listOf("done"), "OK", 0)
        TaskLog.flushForTest()

        val logFile = File(app.filesDir, "tasks/log.jsonl")
        assertTrue(logFile.isFile)
        assertEquals(2, logFile.readLines().count { it.isNotBlank() })

        // Re-init reloads from append-only log.jsonl without losing tasks.
        TaskLog.init(app)
        assertEquals(2, TaskLog.recent(10).size)

        // Drive past COMPACT_AT (400) and verify log.jsonl compacts down to KEEP (200).
        for (i in 1..405) {
            TaskLog.onCommand("b$i", "main", listOf("launch", "设置"), "OK", 0)
            TaskLog.onTurnEnd("b$i")
        }
        TaskLog.flushForTest()
        val linesAfterCompact = logFile.readLines().filter { it.isNotBlank() }
        assertTrue("expected compacted lines <= 210, got ${linesAfterCompact.size}", linesAfterCompact.size in TaskLog.KEEP..(TaskLog.KEEP + 10))
        TaskLog.init(app)
        assertEquals(TaskLog.KEEP, TaskLog.recent(500).size)
    }

    @Test fun paddleOcrModelFileExtractsOnceWithoutRewrite() {
        val det1 = VScreenPaddleOcr.modelFile(app, "det.onnx")
        assertTrue(det1.isFile && det1.length() > 100_000L)
        val mtime = det1.lastModified()
        val det2 = VScreenPaddleOcr.modelFile(app, "det.onnx")
        assertEquals(det1.absolutePath, det2.absolutePath)
        assertEquals(mtime, det2.lastModified())
    }

    @Test fun commandParsingAndTemplatePlaceholdersHandleEdgeCases() {
        // Structured ask/teach preserves apostrophes instead of splitting or throwing unclosed quote.
        val askCmd = CommandSpec.of("ask")!!
        assertEquals(listOf("ask", "Which user's account?"), CommandSpec.argvOf(askCmd, "Which user's account?", null))
        assertEquals(listOf("teach", "Tap the 'OK' button"), CommandSpec.argvOf(CommandSpec.of("teach")!!, "\"Tap the 'OK' button\"", null))

        // 10+ placeholders in Teach.fill.
        val params = (1..12).map { "v$it" }
        val (filled, missing) = Teach.fill("{1}-{9}-{10}-{12}", params)
        assertEquals("v1-v9-v10-v12", filled)
        assertTrue(missing.isEmpty())

        // Single-quoted literal with % is not mangled by resolvePct; uppercase UNTIL is stripped from type text.
        val st1 = Steps.parse("tap '100% 纯棉'")
        assertEquals(StepTarget.Words("100% 纯棉"), st1.target)
        val st2 = Steps.parse("type hello UNTIL text:done")
        assertEquals("hello", st2.text)
        assertEquals("text:done", st2.until!!.single().raw)

        // fixWhat respects dynamic language switching.
        I18n.choice = I18n.Lang.EN
        val badEn = handler.handle(NativeOffloadRequest(listOf("vscreen", "fix", "not_a_real_code"), "default"))
        assertTrue(badEn.output, badEn.output.contains("Unknown problem code"))
    }
}
