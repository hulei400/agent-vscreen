package com.kilon.agentvscreen.cmd

import android.graphics.Bitmap
import com.kilon.agentvscreen.core.VScreenMap
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

/** 1.3.0: the lesson's contact sheet. */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class)
class V130Test2 {
    private fun pic() = TeachPic(Bitmap.createBitmap(TeachPic.W, 480, Bitmap.Config.RGB_565), 0.25f)

    @Test fun spreadKeepsTheEndsAndTheCount() {
        val xs = (1..20).toList()
        val s = Teach.spread(xs, 7)
        assertEquals(7, s.size); assertEquals(1, s.first()); assertEquals(20, s.last())
        assertEquals(listOf(1, 2, 3), Teach.spread(listOf(1, 2, 3), 7))
        assertEquals(emptyList<Int>(), Teach.spread(xs, 0))
    }

    @Test fun theSheetHasAtMostEightCellsInRowsOfFour() {
        val lines = (1..12).map { TeachLine("tap 项$it", null, "", "tap", at = 100 to 200, pic = pic()) }
        val r = Teach.Result("s", null, null, null, "com.x", lines, pic())
        val sheet = Teach.sheet(r, lines.indices.toSet())!!
        // 8 cells: 2 rows of 4.
        assertEquals(4 * TeachPic.W + 5 * 6, sheet.width)
        assertEquals(2 * (480 + 26) + 3 * 6, sheet.height)
        // Steps without pictures and no end picture: nothing to show.
        assertNull(Teach.sheet(Teach.Result("s", null, null, null, "com.x", listOf(TeachLine("back", null, "", "key")), null), setOf(0)))
        // Unticked steps are left out.
        val one = Teach.sheet(Teach.Result("s", null, null, null, "com.x", lines, null), setOf(3))!!
        assertEquals(TeachPic.W + 2 * 6, one.width)
    }

    @Test fun thePictureGoesWithItsSegment() {
        VScreenMap.init(RuntimeEnvironment.getApplication())
        VScreenMap.saveSegment("com.pic.test", "找设置", "tap 设置")
        assertNotNull(VScreenMap.savePicture("com.pic.test", "找设置", pic().bmp))
        assertNotNull(VScreenMap.picture("com.pic.test", "找设置"))
        assertFalse(VScreenMap.pictureStale("com.pic.test", "找设置"))
        assertTrue(VScreenMap.deleteSegment("com.pic.test", "找设置"))
        assertNull(VScreenMap.picture("com.pic.test", "找设置"))
    }
}
