package io.github.v0rest.skein.claude

import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

class TranscriptsTest {
    private val session = "55555555-5555-4555-8555-555555555555"

    private fun tempHome(): ClaudeHome = ClaudeHome(Files.createTempDirectory("skein-transcripts").toRealPath().resolve("home"))

    /** Writes an empty transcript for [session] under the slug of [cwd], as Claude Code would. */
    private fun transcriptFor(home: ClaudeHome, cwd: String): Path {
        val file = home.projectsDir.resolve(Slug.of(cwd)).resolve("$session.jsonl")
        Files.createDirectories(file.parent)
        Files.writeString(file, "")
        return file
    }

    @Test
    fun pathFollowsTheFolderSlug() {
        val home = ClaudeHome(Path.of("/h/.claude"))
        assertEquals(Path.of("/h/.claude/projects/-w-acme/$session.jsonl"), Transcripts.path(home, "/w/acme", sessionCwd = null, sessionId = session))
    }

    @Test
    fun theSessionsOwnCwdWinsWhenItsTranscriptIsThere() {
        val home = tempHome()
        val expected = transcriptFor(home, "/real/acme")
        transcriptFor(home, "/w/acme")
        assertEquals(expected, Transcripts.path(home, "/w/acme", sessionCwd = "/real/acme", sessionId = session))
    }

    @Test
    fun aSessionCwdWithoutATranscriptFallsBackToTheFolder() {
        val home = tempHome()
        val expected = transcriptFor(home, "/w/acme")
        // claude reported a cwd it moved to with a `cd`; the transcript still lives under the folder it started in
        assertEquals(expected, Transcripts.path(home, "/w/acme", sessionCwd = "/w/acme/sub", sessionId = session))
        // nothing anywhere: the folder as given
        assertEquals(home.projectsDir.resolve("-w-gone").resolve("$session.jsonl"), Transcripts.path(home, "/w/gone", sessionCwd = "/w/gone/sub", sessionId = session))
    }

    @Test
    fun aSymlinkedFolderFindsItsTranscript() {
        val root = Files.createTempDirectory("skein-transcripts").toRealPath()
        val real = Files.createDirectory(root.resolve("real"))
        val link = root.resolve("link")
        try {
            Files.createSymbolicLink(link, real)
        } catch (e: Exception) {
            assumeTrue("symlinks are not available here", false)
        }
        val home = ClaudeHome(root.resolve("home"))
        val expected = transcriptFor(home, real.toString())
        assertEquals(expected, Transcripts.path(home, link.toString(), sessionCwd = null, sessionId = session))
        // a folder that does not exist is slugged as given
        assertEquals(home.projectsDir.resolve("-w-gone").resolve("$session.jsonl"), Transcripts.path(home, "/w/gone", sessionCwd = null, sessionId = session))
    }

    @Test
    fun tailLinesDropTheLineCutAtTheStartAndKeepTheOneAtTheEnd() {
        val home = tempHome()
        val file = transcriptFor(home, "/w/acme")
        val filler = "{\"type\":\"user\",\"message\":\"" + "x".repeat(Transcripts.TAIL_BYTES) + "\"}\n"
        Files.writeString(file, filler + "{\"type\":\"assistant\"}\n{\"type\":\"cut")
        assertEquals(listOf("{\"type\":\"assistant\"}", "{\"type\":\"cut"), Transcripts.tailLines(home, "/w/acme", "/w/acme", session))
        Files.writeString(file, "{\"a\":1}\n{\"b\":2}\n")
        assertEquals(listOf("{\"a\":1}", "{\"b\":2}", ""), Transcripts.tailLines(home, "/w/acme", "/w/acme", session))
        assertEquals(emptyList<String>(), Transcripts.tailLines(home, "/w/other", "/w/other", session))
    }

    @Test
    fun aFileOfExactlyTheTailSizeKeepsItsFirstLine() {
        val home = tempHome()
        val file = transcriptFor(home, "/w/acme")
        val second = "{\"b\":2}"
        val first = "{\"a\":\"" + "x".repeat(Transcripts.TAIL_BYTES - second.length - 1 - 8) + "\"}"
        val text = first + "\n" + second
        assertEquals(Transcripts.TAIL_BYTES, text.length)
        Files.writeString(file, text)
        assertEquals(listOf(first, second), Transcripts.tailLines(home, "/w/acme", "/w/acme", session))
    }
}
