package com.kilon.agentvscreen.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/** 1.4.0-beta2: every JPEG is encoded in Pictures.kt, with its numbers in Pictures.Use. */
class PicturesTest {
    @Test fun onlyPicturesEncodes() {
        val src = File("src/main/java")
        if (!src.isDirectory) return
        val bad = src.walk().filter { it.isFile && it.extension == "kt" && it.name != "Pictures.kt" }
            .filter { f -> f.readText().let { it.contains(".compress(") || it.contains("CompressFormat") } }
            .map { it.name }.toList()
        assertEquals("encode pictures with Pictures.write", emptyList<String>(), bad)
    }
}
