package io.github.v0rest.skein.testutil

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.name
import kotlin.io.path.readLines
import kotlin.streams.asSequence

/** Text-level scanning of Kotlin sources for the architecture tests. Comment lines are skipped. */
object SourceRules {
    fun kotlinFiles(root: Path): List<Path> =
        Files.walk(root).use { s -> s.asSequence().filter { it.name.endsWith(".kt") }.sorted().toList() }

    fun codeLines(file: Path): List<String> = codeLines(file.readLines())

    fun codeLines(lines: List<String>): List<String> =
        lines.map { it.trim() }.filterNot { it.isEmpty() || it.startsWith("//") || it.startsWith("/*") || it.startsWith("*") }

    /** Lines containing any needle as a plain substring. */
    fun offenders(lines: List<String>, needles: Collection<String>): List<String> =
        lines.filter { line -> needles.any { line.contains(it) } }

    /** Lines containing any needle as a whole word (identifier boundaries), so `copyOf` does not match `copy`. */
    fun wordOffenders(lines: List<String>, words: Collection<String>): List<String> {
        val patterns = words.map { Regex("(?<![A-Za-z0-9_])" + Regex.escape(it) + "(?![A-Za-z0-9_])") }
        return lines.filter { line -> patterns.any { it.containsMatchIn(line) } }
    }
}
