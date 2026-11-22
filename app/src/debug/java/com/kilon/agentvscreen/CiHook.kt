package com.kilon.agentvscreen

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.kilon.agentvscreen.host.HostService
import java.io.File

/**
 * 1.4.2, debug builds only: lets the CI emulator tests (tools/android-smoke/agent_e2e.py) reach the app the way an agent
 * does, without tapping through the UI to copy the token. `adb shell am broadcast -a com.kilon.agentvscreen.CI_SEED`
 * turns the service on and writes "<main token>\n<port>" to files/ci.txt, read with `run-as` (debug builds are debuggable).
 * Release builds have neither this class nor its receiver (src/debug), and only the shell may send it (DUMP).
 */
class CiHook : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext
        val pending = goAsync()
        Thread({
            try {
                // 1.4.2-beta3: `--es via shizuku|root` switches the way in, as the setup / settings choice does.
                intent.getStringExtra("via")?.let { v ->
                    com.kilon.agentvscreen.host.Privileged.Via.entries.firstOrNull { it.key == v }?.let { com.kilon.agentvscreen.host.Privileged.choose(app, it) }
                    return@Thread
                }
                com.kilon.agentvscreen.host.Prefs.setServiceOn(app, true)
                HostService.start(app)
                // The main token's text (the token list keeps only its hash). Written at once, then again with the port.
                val token = com.kilon.agentvscreen.api.ApiConfig.token
                val out = File(app.filesDir, "ci.txt")
                out.writeText("$token\n0\n")
                var port = 0
                repeat(120) { if (port == 0) { port = HostService.server?.boundPort ?: 0; if (port == 0) Thread.sleep(500) } }
                out.writeText("$token\n$port\n")
            } finally { pending.finish() }
        }, "vscreen-ci-seed").start()
    }
}
