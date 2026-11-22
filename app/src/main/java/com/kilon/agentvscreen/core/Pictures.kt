package com.kilon.agentvscreen.core

import android.graphics.Bitmap
import java.io.File
import java.io.FileOutputStream

/**
 * 1.4.0-beta2: the one place a picture is encoded. Size and JPEG quality for every use are in [Use]; no other file
 * calls Bitmap.compress (a test keeps it that way). Before this, five places each picked their own numbers, so how
 * clear a picture was depended on which command made it, and a picture sent to a model was decoded and encoded a
 * second time.
 */
object Pictures {
    /** [side]: longest side in pixels (0 = the caller already sized it). */
    enum class Use(val side: Int, val quality: Int) {
        /** look --detail low | normal | high (and act's own picture, which is normal). */
        LOOK_LOW(768, 60), LOOK(1280, 72), LOOK_HIGH(2400, 85),
        /** The copy of a picture that goes into a model context by itself (no one asked for it): see ImagePolicy. */
        SENT(1024, 60),
        /** A picture shown to the user with a question. */
        ASK(1600, 85),
        /** A lesson's contact sheet, kept with the lesson. */
        LESSON(0, 75),
        /** A task's small frame in the app's 记录 (TaskLog). */
        THUMB(240, 70),
        /** 1.4.0-beta6 (rainbow: the opened frame was blurred, kept at 240 px): a task's frame, opened full screen. */
        TASK(1280, 75),
        /** 1.4.0-beta5: a task step's picture, opened on the task page. */
        STEP(1280, 75),
        /** A frame of the recording track (many, kept minutes). */
        RECORDING(0, 60),
    }

    /** [bmp] no longer than [side] (the same bitmap when it already fits). */
    fun fit(bmp: Bitmap, side: Int): Bitmap {
        val long = maxOf(bmp.width, bmp.height)
        if (side <= 0 || long <= side) return bmp
        return Bitmap.createScaledBitmap(bmp, maxOf(1, bmp.width * side / long), maxOf(1, bmp.height * side / long), true)
    }

    /** Write [bmp] as JPEG for [use] (sized to use.side unless 0). [quality] overrides only for a caller that has its own table entry. */
    fun write(bmp: Bitmap, f: File, use: Use, quality: Int = use.quality, fit: Boolean = true): File {
        val b = if (fit) fit(bmp, use.side) else bmp
        try { FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.JPEG, quality, it) } } finally { if (b !== bmp) b.recycle() }
        return f
    }
}
