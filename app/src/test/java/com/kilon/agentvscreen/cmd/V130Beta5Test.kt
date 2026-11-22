package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.kilon.agentvscreen.api.ApiConfig
import com.kilon.agentvscreen.core.VScreenRecorder
import com.kilon.agentvscreen.host.Access
import com.kilon.agentvscreen.host.Prefs
import com.kilon.agentvscreen.host.Tailnet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/** 1.3.0-beta5: the Connect tile lists each way agents come in; the sheet draws actions on the frame before them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(application = android.app.Application::class)
class V130Beta5Test {
    @Test fun connectTileIsPerWay() {
        val app = RuntimeEnvironment.getApplication()
        ApiConfig.init(app)
        com.kilon.agentvscreen.api.AgentLedger.init(app)
        Prefs.setServiceOn(app, false); Prefs.setBlocked(app, false)
        ApiConfig.tailnetMode = Tailnet.Mode.OFF
        val local = Access.ways(app)
        assertEquals(1, local.size)
        assertTrue(local[0].line(), !local[0].line().contains("Tailscale"))
        ApiConfig.tailnetMode = Tailnet.Mode.BUILTIN
        val both = Access.ways(app)
        assertEquals(2, both.size)
        assertTrue(Access.tile(app).contains("\n"))
        Prefs.setBlocked(app, true)
        assertTrue(!Access.tile(app).contains("\n"))
        Prefs.setBlocked(app, false); ApiConfig.tailnetMode = Tailnet.Mode.OFF
    }

    /** Renders a sheet (only when AVS_SHOTS is set) to look at the rings, numbers and captions. */
    @Test fun sheetShot() {
        val out = System.getenv("AVS_SHOTS") ?: return
        val cells = (0 until 6).map { i ->
            val b = Bitmap.createBitmap(138, 300, Bitmap.Config.ARGB_8888)
            val c = Canvas(b); c.drawColor(Color.rgb(240 - i * 10, 244, 250))
            val p = Paint().apply { color = Color.rgb(120, 120, 140) }
            for (r in 0 until 8) c.drawRect(10f, 30f + r * 32, 128f, 50f + r * 32, p)
            val marks = if (i in 1..3) listOf(VScreenRecorder.Mark(i, 1000L * i, "action", "tap 登录", if (i == 2) listOf(intArrayOf(540, 1600), intArrayOf(540, 700)) else listOf(intArrayOf(300 + 100 * i, 500 + 300 * i)))) else emptyList()
            Cell(10_000L + 900L * i, b, if (marks.isEmpty()) (if (i == 0) "开头" else "动作3 后 +0.4s") else "动作$i 前 tap 登录 (${300 + 100 * i},${500 + 300 * i})", marks, if (marks.isEmpty()) emptyList() else listOf(i))
        }
        val cols = 2; val rows = 3
        val bmp = Bitmap.createBitmap(cols * (138 + CELL_PAD) + CELL_PAD, rows * (300 + CAPTION + CELL_PAD) + CELL_PAD, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp); c.drawColor(Color.WHITE)
        drawCells(c, 0, 0, cells, cols, 138, 300, 1080, 2340, 16_000L)
        FileOutputStream(File(out, "sheet.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
