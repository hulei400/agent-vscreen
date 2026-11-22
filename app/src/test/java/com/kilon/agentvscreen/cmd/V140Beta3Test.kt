package com.kilon.agentvscreen.cmd

import com.kilon.agentvscreen.api.ImagePolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 1.4.0-beta3: fixes from the beta2 device test. */
@RunWith(RobolectricTestRunner::class)
class V140Beta3Test {
    /** A card whose node says one name does not swallow a different word drawn inside it. */
    @Test fun elementCoversOnlyWordsItSays() {
        assertFalse(saysWord("荣耀字体", "honorsans"))
        assertTrue(saysWord("显示和亮度", "显示和亮度"))
        assertTrue(saysWord("显示和亮度", "亮度"))
        assertTrue(saysWord("应用市场", "应用市坊"))   // one misread glyph
        assertFalse(saysWord("设置", "搜索"))
    }

    private fun res(changed: Boolean, strip: Boolean = false, steps: Int = 1, exit: Int = 0) = NativeOffloadResult(exit, "", null,
        JSONObject().put("picture_changed", changed).put("steps", JSONArray().apply { repeat(steps) { put(JSONObject()) } })
            .put("screen", JSONObject().put("image_has_strip", strip)))

    /** The picture follows the report's own change judgment (beta2 used a whole-frame hash a carousel flipped). */
    @Test fun pictureOnlyWhenNewOrNeeded() {
        val shots = listOf("vscreen_act_1_1.jpg")
        assertEquals(0, ImagePolicy.pick(res(false), shots).size)
        assertEquals(1, ImagePolicy.pick(res(true), shots).size)
        assertEquals(1, ImagePolicy.pick(res(false, strip = true), shots).size)
        assertEquals(1, ImagePolicy.pick(res(false, steps = 0), shots).size)
        assertEquals(1, ImagePolicy.pick(res(false, exit = 1), shots).size)
    }
}
