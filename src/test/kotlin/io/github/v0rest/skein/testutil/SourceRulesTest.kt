package io.github.v0rest.skein.testutil

import org.junit.Assert.assertEquals
import org.junit.Test

/** Proves the scanners used by ArchitectureTest have teeth: each rule catches the forms it is meant to catch. */
class SourceRulesTest {
    @Test
    fun codeLinesDropCommentsAndBlanks() {
        val lines = listOf("// Files.write here is fine", " * KDoc Files.write", "/* block */", "", "val x = 1")
        assertEquals(listOf("val x = 1"), SourceRules.codeLines(lines))
    }

    @Test
    fun offendersMatchSubstrings() {
        val lines = listOf("path.writeText(\"x\")", "Files.newByteChannel(p, READ)", "import java.nio.file.StandardOpenOption.WRITE")
        assertEquals(listOf("path.writeText(\"x\")", "import java.nio.file.StandardOpenOption.WRITE"), SourceRules.offenders(lines, listOf("writeText", "StandardOpenOption.WRITE")))
    }

    @Test
    fun wordOffendersMatchWholeWordsOnly() {
        val lines = listOf("val a = bytes.copyOf(3)", "Files.copy(a, b)", "copy(a, b)", "val moved = x", "Files.move(a, b)")
        assertEquals(listOf("Files.copy(a, b)", "copy(a, b)", "Files.move(a, b)"), SourceRules.wordOffenders(lines, listOf("copy", "move")))
    }
}
