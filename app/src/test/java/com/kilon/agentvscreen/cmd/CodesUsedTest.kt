package com.kilon.agentvscreen.cmd

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File

/**
 * 1.4.0-beta2: result codes are written as words in many places (errors, notes, refused steps, guard verdicts). Every
 * one of them must be a row of the Codes table, so a misspelt code fails here and not in an agent's reply.
 */
class CodesUsedTest {
    @Test fun everyCodeWrittenIsInTheTable() {
        val src = File("src/main/java")
        if (!src.isDirectory) return
        val pat = Regex("""(?:ActionError|StepRefused|Deny|notes\.add|Note)\(\s*"([A-Z][A-Z0-9_]+)"""")
        val used = src.walk().filter { it.isFile && it.extension == "kt" }.flatMap { f -> pat.findAll(f.readText()).map { it.groupValues[1] } }.toSet()
        val missing = used.filter { Codes.of(it) == null }.sorted()
        assertEquals("codes used but not in Codes", emptyList<String>(), missing)
        assertEquals("Notes.INFO codes not in Codes", emptyList<String>(), Notes.INFO.filter { Codes.of(it) == null })
    }
}
