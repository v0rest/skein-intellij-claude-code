package io.github.v0rest.skein.claude

import io.github.v0rest.skein.testutil.CanonicalTemporaryFolder
import io.github.v0rest.skein.testutil.CountingClaudeHome
import io.github.v0rest.skein.testutil.Fixture
import io.github.v0rest.skein.testutil.TreeDigest
import io.github.v0rest.skein.testutil.sessionFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.nio.file.attribute.PosixFilePermissions
import java.util.UUID

class HistoryScannerTest {
    @get:Rule
    val tmp = CanonicalTemporaryFolder()

    private lateinit var home: CountingClaudeHome
    private lateinit var scanner: HistoryScanner
    private val roots = listOf(r("w/acme"))

    /** A portable fixture path: `/w/acme` on Unix, `C:\w\acme` on Windows, so the slugs and the cwd inside a transcript agree on both. */
    private fun r(relative: String) = Fixture.root(relative)

    /** A stable UUID per readable name: Claude Code session ids are UUIDs, and Skein only lists those. */
    private fun u(name: String): String = UUID.nameUUIDFromBytes(name.toByteArray()).toString()

    @Before
    fun setUp() {
        home = CountingClaudeHome(tmp.root.toPath().resolve(".claude"))
        Files.createDirectories(home.projectsDir)
        scanner = HistoryScanner(home)
    }

    private fun user(cwd: String, id: String) = """{"type":"user","cwd":"${Fixture.jsonEscaped(cwd)}","sessionId":"$id","message":{"role":"user","content":"hi"}}"""
    private fun custom(t: String) = """{"type":"custom-title","customTitle":"$t"}"""
    private fun continuedIn(id: String) = """{"type":"continued-in","continuedInSessionId":"$id"}"""
    private fun relocated(cwd: String) = """{"type":"relocated","relocatedCwd":"${Fixture.jsonEscaped(cwd)}"}"""
    private val filler = """{"type":"assistant","message":{"content":"${"x".repeat(1000)}"}}"""

    /** A first user entry with extra top-level [fields], e.g. `"entrypoint":"cli"`. */
    private fun userWith(cwd: String, id: String, fields: String) =
        """{"type":"user","cwd":"${Fixture.jsonEscaped(cwd)}","sessionId":"$id",$fields,"message":{"role":"user","content":"hi"}}"""

    /** A first entry longer than the head Skein reads, with its cwd after the message as current transcripts have it. */
    private fun hugeFirst(id: String, cwd: String) =
        """{"type":"user","message":{"role":"user","content":"${"x".repeat(100_000)}"},"entrypoint":"cli","cwd":"${Fixture.jsonEscaped(cwd)}","sessionId":"$id"}"""

    /** Writes `<projects>/<slug of cwd>/<id>.jsonl` with the given lines and mtime (seconds since the epoch, for ordering). */
    private fun transcript(
        cwd: String,
        id: String,
        mtimeSeconds: Long,
        vararg extraLines: String,
        folderSlug: String = Slug.of(cwd),
        first: String = user(cwd, id),
    ): Path {
        val folder = home.projectsDir.resolve(folderSlug)
        Files.createDirectories(folder)
        val path = folder.resolve("$id.jsonl")
        Files.write(path, listOf(first) + extraLines)
        Files.setLastModifiedTime(path, FileTime.fromMillis(mtimeSeconds * 1000))
        return path
    }

    @Test
    fun aWorktreeSessionCarriesItsName() {
        transcript(r("w/acme/.claude/worktrees/feat-x"), u("wt-1"), 100)
        val entry = scanner.scan(roots).entries.single()
        assertEquals("feat-x", entry.worktree)
    }

    @Test
    fun worktreeSessionsOfAParentRepositoryAreListed() {
        transcript(r("w/mono/.claude/worktrees/feat-x"), u("wt-2"), 100)
        transcript(r("w/mono/other"), u("other-1"), 200)
        val entries = scanner.scan(listOf(r("w/mono/svc"), r("w/mono/.claude/worktrees"))).entries
        assertEquals(listOf(u("wt-2")), entries.map { it.sessionId })
        assertEquals("feat-x", entries.single().worktree)
    }

    @Test
    fun aSessionOutsideTheWorktreesFolderHasNoWorktree() {
        transcript(r("w/acme/docs"), u("plain-1"), 100)
        assertNull(scanner.scan(roots).entries.single().worktree)
    }

    @Test
    fun slugMatchesClaudeCode() {
        assertEquals("-private-tmp-build-501--Users-u-p", Slug.of("/private/tmp/build-501/-Users-u-p"))
        assertEquals("-Users-dev-projects-acme", Slug.of("/Users/dev/projects/acme"))
        assertEquals("-w-p-a-b", Slug.of("/w/p/a.b"))
    }

    @Test
    fun listsRootAndSubfolderSessions() {
        transcript(r("w/acme"), u("root-1"), 100, custom("root"))
        transcript(r("w/acme/docs"), u("sub-1"), 200, custom("guide"))
        val ids = scanner.scan(roots).entries.map { it.sessionId to it.cwd }
        assertEquals(listOf(u("sub-1") to r("w/acme/docs"), u("root-1") to r("w/acme")), ids)
    }

    /**
     * A thread started in a folder deep inside the project, as New Thread Here does. Claude Code keeps its transcript in
     * the folder named after that cwd, whose name starts with the project's own slug, and the cwd inside decides.
     */
    @Test
    fun sessionsDeepInsideTheProjectAreListed() {
        transcript(r("w/acme/web/src/main"), u("deep-1"), 100)
        transcript(r("w/acme/my app.v2"), u("odd-1"), 200)
        val ids = scanner.scan(roots).entries.map { it.sessionId to it.cwd }
        assertEquals(listOf(u("odd-1") to r("w/acme/my app.v2"), u("deep-1") to r("w/acme/web/src/main")), ids)
    }

    @Test
    fun aSubfolderOfARootOutsideTheBaseIsListed() {
        transcript(r("elsewhere/repo/docs"), u("docs-1"), 100)
        assertEquals(listOf(u("docs-1")), scanner.scan(listOf(r("w/acme"), r("elsewhere/repo"))).entries.map { it.sessionId })
    }

    @Test
    fun aScriptedRunInASubfolderStaysOut() {
        transcript(r("w/acme/docs"), u("typed"), 100, first = userWith(r("w/acme/docs"), u("typed"), "\"entrypoint\":\"cli\""))
        transcript(r("w/acme/docs"), u("py"), 200, first = userWith(r("w/acme/docs"), u("py"), "\"entrypoint\":\"sdk-py\""))
        assertEquals(listOf(u("typed")), scanner.scan(roots).entries.map { it.sessionId })
    }

    /**
     * Subfolders come in through the folder names alone, so a project whose folder name does not start with the
     * project's own slug is never read. A sibling like /w/acme-old does start with it and is read, then left out by the
     * cwd inside (see [excludesPrefixSibling]).
     */
    @Test
    fun projectsWhoseFolderNameDoesNotStartWithOursAreNotRead() {
        transcript(r("w/acme/docs"), u("ours-1"), 100)
        val foreign = listOf(transcript(r("w/zeta"), u("zeta-1"), 200), transcript(r("w/zeta/docs"), u("zeta-2"), 300), transcript(r("other/acme/docs"), u("far-1"), 400))
        assertEquals(listOf(u("ours-1")), scanner.scan(roots).entries.map { it.sessionId })
        assertEquals(emptyList<Path>(), foreign.filter { it in home.bytesRead })
    }

    @Test
    fun contentRootOutsideTheBaseIsScannedToo() {
        transcript(r("elsewhere/repo"), u("x-1"), 100)
        assertEquals(listOf(u("x-1")), scanner.scan(listOf(r("w/acme"), r("elsewhere/repo"))).entries.map { it.sessionId })
    }

    @Test
    fun excludesPrefixSibling() {
        transcript(r("w/acme"), u("root-1"), 100)
        transcript(r("w/acme-old"), u("old-1"), 200)
        assertEquals(listOf(u("root-1")), scanner.scan(roots).entries.map { it.sessionId })
    }

    @Test
    fun excludesOutsideWorktree() {
        transcript(r("w/acme"), u("root-1"), 100)
        transcript(r("tmp/wt-x"), u("wt-1"), 200)
        assertEquals(listOf(u("root-1")), scanner.scan(roots).entries.map { it.sessionId })
    }

    @Test
    fun trustsTheTranscriptCwdNotTheFolderName() {
        // the slug is ambiguous: a/b and a-b share a folder; only the cwd inside the transcript decides
        transcript(r("w/acme-x"), u("foreign-1"), 100, folderSlug = Slug.of(r("w/acme-x")))
        transcript(r("w/acme/x"), u("ours-1"), 200, folderSlug = Slug.of(r("w/acme-x")))
        assertEquals(listOf(u("ours-1")), scanner.scan(roots).entries.map { it.sessionId })
    }

    @Test
    fun skipsSubdirectoriesInsideTranscriptFolder() {
        transcript(r("w/acme"), u("root-1"), 100)
        val sub = home.projectsDir.resolve(Slug.of(r("w/acme"))).resolve("${u("root-1")}/subagents")
        Files.createDirectories(sub)
        Files.write(sub.resolve("${u("agent-1")}.jsonl"), listOf(user(r("w/acme"), u("agent-1"))))
        assertEquals(listOf(u("root-1")), scanner.scan(roots).entries.map { it.sessionId })
    }

    @Test
    fun skipsFilesWhoseNameIsNotASessionId() {
        transcript(r("w/acme"), u("root-1"), 100)
        transcript(r("w/acme"), "x; touch pwned", 200)
        transcript(r("w/acme"), "notes", 300)
        assertEquals(listOf(u("root-1")), scanner.scan(roots).entries.map { it.sessionId })
    }

    @Test
    fun hidesContinuedSession() {
        transcript(r("w/acme"), u("a"), 100, """{"type":"continued-in","continuedInSessionId":"${u("b")}"}""")
        transcript(r("w/acme"), u("b"), 200)
        assertEquals(listOf(u("b")), scanner.scan(roots).entries.map { it.sessionId })
    }

    @Test
    fun keepsContinuedSessionWhoseSuccessorIsNotListed() {
        transcript(r("w/acme"), u("a"), 100, continuedIn(u("gone")))
        val entries = scanner.scan(roots).entries
        assertEquals(listOf(u("a")), entries.map { it.sessionId })
        assertEquals(u("gone"), entries.single().continuedIn)
    }

    @Test
    fun aSessionContinuedInAScriptedRunStaysHidden() {
        transcript(r("w/acme"), u("a"), 100, continuedIn(u("b")))
        transcript(r("w/acme"), u("b"), 200, first = userWith(r("w/acme"), u("b"), "\"entrypoint\":\"sdk-py\""))
        assertEquals(emptyList<String>(), scanner.scan(roots).entries.map { it.sessionId })
    }

    @Test
    fun hidesSessionsAProgramStartedThroughAnSdk() {
        // a session typed into a terminal records the entrypoint cli, one the Agent SDK started records sdk-py
        transcript(r("w/acme"), u("typed"), 100, first = userWith(r("w/acme"), u("typed"), "\"entrypoint\":\"cli\""))
        transcript(r("w/acme"), u("py"), 200, first = userWith(r("w/acme"), u("py"), "\"entrypoint\":\"sdk-py\""))
        transcript(r("w/acme"), u("other-sdk"), 300, first = userWith(r("w/acme"), u("other-sdk"), "\"entrypoint\":\"sdk-anything\""))
        assertEquals(listOf(u("typed")), scanner.scan(roots).entries.map { it.sessionId })
    }

    @Test
    fun aSessionSomeoneTypedIntoAtEitherEndStaysListed() {
        // typed into first, a script later
        val scriptLater = List(100) { filler } + """{"type":"assistant","entrypoint":"sdk-py","message":{"content":"x"}}"""
        transcript(r("w/acme"), u("typed-first"), 100, *scriptLater.toTypedArray(), first = userWith(r("w/acme"), u("typed-first"), "\"entrypoint\":\"cli\""))
        // a script started it, the owner went on with it in a terminal
        val typedLater = List(100) { filler } + """{"type":"user","entrypoint":"cli","message":{"role":"user","content":"go on"}}"""
        transcript(r("w/acme"), u("taken-over"), 200, *typedLater.toTypedArray(), first = userWith(r("w/acme"), u("taken-over"), "\"entrypoint\":\"sdk-py\""))
        assertEquals(listOf(u("taken-over"), u("typed-first")), scanner.scan(roots).entries.map { it.sessionId })
    }

    @Test
    fun anEntrypointOnlyAtTheEndCountsToo() {
        // when the head Skein reads records none, the latest one at the end of the transcript decides
        val lines = List(100) { filler } + """{"type":"assistant","entrypoint":"sdk-py","message":{"content":"done"}}"""
        transcript(r("w/acme"), u("late"), 100, *lines.toTypedArray())
        assertEquals(emptyList<String>(), scanner.scan(roots).entries.map { it.sessionId })
    }

    @Test
    fun aSessionThatMovedHereIsListedUnderItsNewFolder() {
        transcript(r("w/acme"), u("moved-in"), 100, relocated(r("w/acme")), first = user(r("w/elsewhere"), u("moved-in")))
        val entry = scanner.scan(roots).entries.single()
        assertEquals(u("moved-in"), entry.sessionId)
        assertEquals(r("w/acme"), entry.cwd)
    }

    @Test
    fun aSessionThatMovedAwayLeavesThisProject() {
        transcript(r("w/acme-api"), u("moved-out"), 100, relocated(r("w/acme-api")), first = user(r("w/acme"), u("moved-out")))
        assertEquals(emptyList<String>(), scanner.scan(roots).entries.map { it.sessionId })
    }

    @Test
    fun aMovedSessionStaysInItsNewFolderWhenTheMoveIsFarBack() {
        // the entries written since the move carry the new cwd, even when the move itself is further back than the tail
        val since = List(300) { """{"type":"assistant","cwd":"${Fixture.jsonEscaped(r("w/acme"))}","message":{"content":"${"x".repeat(1000)}"}}""" }
        transcript(r("w/acme"), u("moved-long-ago"), 100, relocated(r("w/acme")), *since.toTypedArray(), first = user(r("w/elsewhere"), u("moved-long-ago")))
        assertEquals(r("w/acme"), scanner.scan(roots).entries.single().cwd)
    }

    @Test
    fun theLatestMoveWins() {
        transcript(
            r("w/acme"), u("twice"), 100,
            relocated(r("w/elsewhere")), relocated(r("w/acme/sub")),
            first = user(r("w/elsewhere"), u("twice")),
        )
        assertEquals(r("w/acme/sub"), scanner.scan(roots).entries.single().cwd)
    }

    @Test
    fun aFirstEntryLongerThanTheHeadStillListsTheSession() {
        // current transcripts carry cwd after the message, so a huge first prompt leaves the head without one
        val folder = home.projectsDir.resolve(Slug.of(r("w/acme")))
        Files.createDirectories(folder)
        Files.write(
            folder.resolve("${u("huge-new")}.jsonl"),
            listOf(hugeFirst(u("huge-new"), r("w/acme")), """{"type":"assistant","cwd":"${Fixture.jsonEscaped(r("w/acme"))}","message":{"content":"ok"}}"""),
        )
        assertEquals(listOf(u("huge-new")), scanner.scan(roots).entries.map { it.sessionId })
    }

    @Test
    fun aShellCdDoesNotMoveASessionWhoseHeadHasNoCwd() {
        // the latest cwd recorded in the transcript's own folder counts, not a folder a shell cd'd into later
        val folder = home.projectsDir.resolve(Slug.of(r("w/acme")))
        Files.createDirectories(folder)
        Files.write(
            folder.resolve("${u("drift")}.jsonl"),
            listOf(
                hugeFirst(u("drift"), r("w/acme")),
                """{"type":"assistant","cwd":"${Fixture.jsonEscaped(r("w/acme"))}","message":{"content":"ok"}}""",
                """{"type":"assistant","cwd":"${Fixture.jsonEscaped(r("w/acme/sub"))}","message":{"content":"after cd sub"}}""",
            ),
        )
        assertEquals(r("w/acme"), scanner.scan(roots).entries.single().cwd)
    }

    @Test
    fun anEntryCarriesTheEarlierPartsOfItsConversation() {
        // a -> b -> c: only c is listed, and it carries a and b, so their marks can follow it
        val a = transcript(r("w/acme"), u("a"), 100, continuedIn(u("b")))
        val b = transcript(r("w/acme"), u("b"), 200, continuedIn(u("c")))
        transcript(r("w/acme"), u("c"), 300)
        val entry = scanner.scan(roots).entries.single()
        assertEquals(u("c"), entry.sessionId)
        assertEquals(setOf(a, b), entry.earlierParts.toSet())
    }

    @Test
    fun anEntryKnowsWhereItsTranscriptIs() {
        val path = transcript(r("w/acme"), u("root-1"), 100)
        assertEquals(path, scanner.scan(roots).entries.single().transcript)
    }

    @Test
    fun newestFirstByModificationTime() {
        transcript(r("w/acme"), u("old"), 100)
        transcript(r("w/acme"), u("new"), 300)
        transcript(r("w/acme"), u("mid"), 200)
        val entries = scanner.scan(roots).entries
        assertEquals(listOf(u("new"), u("mid"), u("old")), entries.map { it.sessionId })
        assertEquals(300_000L, entries.first().lastActivity)
    }

    @Test
    fun titleFallsBackToSessionId() {
        transcript(r("w/acme"), u("root-1"), 100)
        assertEquals(u("root-1"), scanner.scan(roots).entries.single().title)
    }

    @Test
    fun titleComesFromTheTail() {
        transcript(r("w/acme"), u("root-1"), 100, custom("first"), custom("renamed"))
        assertEquals("renamed", scanner.scan(roots).entries.single().title)
    }

    @Test
    fun transcriptReadsAreBounded() {
        val filler = """{"type":"assistant","message":{"content":"${"x".repeat(1000)}"}}"""
        val lines = listOf(user(r("w/acme"), u("big")), custom("early title")) + List(20_000) { filler }
        val path = transcript(r("w/acme"), u("big"), 100, *lines.drop(1).toTypedArray())
        assertTrue(Files.size(path) > 20_000_000)
        assertEquals("early title", scanner.scan(roots).entries.single().title)
        assertTrue("read ${home.bytesRead[path]} bytes", home.bytesRead.getValue(path) <= 64 * 1024 + 256 * 1024 + 256 * 1024)
    }

    @Test
    fun aSymlinkedRootFindsSessionsRecordedUnderTheRealPath() {
        Fixture.assumeNotWindows("a symbolic link needs a privilege there")
        val real = Fixture.tempDirectory("skein-root")
        val link = real.resolveSibling(real.fileName.toString() + "-link")
        try {
            Files.createSymbolicLink(link, real)
            transcript(real.toString(), u("real"), 100)
            assertEquals(listOf(u("real")), scanner.scan(listOf(link.toString())).entries.map { it.sessionId })
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(real)
        }
    }

    @Test
    fun anEarlyRenameBeatsALaterPromptInTheTail() {
        val filler = """{"type":"assistant","message":{"content":"${"x".repeat(1000)}"}}"""
        val lines = listOf(custom("my name")) + List(400) { filler } + listOf("""{"type":"last-prompt","lastPrompt":"latest prompt"}""")
        transcript(r("w/acme"), u("big"), 100, *lines.toTypedArray())
        assertEquals("my name", scanner.scan(roots).entries.single().title)
    }

    @Test
    fun aHugeFirstPromptDoesNotHideTheSession() {
        val hugeFirst = """{"type":"user","cwd":"${Fixture.jsonEscaped(r("w/acme"))}","sessionId":"${u("huge")}","message":{"role":"user","content":"${"x".repeat(200_000)}"}}"""
        val folder = home.projectsDir.resolve(Slug.of(r("w/acme")))
        Files.createDirectories(folder)
        Files.write(folder.resolve("${u("huge")}.jsonl"), listOf(hugeFirst))
        assertEquals(listOf(u("huge")), scanner.scan(roots).entries.map { it.sessionId })
    }

    @Test
    fun unchangedTranscriptIsNotReadAgain() {
        val path = transcript(r("w/acme"), u("root-1"), 100, custom("t"))
        scanner.scan(roots)
        val once = home.bytesRead.getValue(path)
        scanner.scan(roots)
        assertEquals(once, home.bytesRead.getValue(path))
    }

    @Test
    fun liveInFindsTheRunningSession() {
        transcript(r("w/acme"), u("root-1"), 100)
        val entry = scanner.scan(roots).entries.single()
        val live = sessionFile(pid = 9, sessionId = u("root-1"))
        assertEquals(live, entry.liveIn(SessionSnapshot(mapOf(9L to live), emptySet())))
        assertNull(entry.liveIn(SessionSnapshot.EMPTY))
    }

    @Test
    fun missingProjectsDirIsEmpty() {
        assertEquals(emptyList<HistoryEntry>(), HistoryScanner(ClaudeHome(tmp.root.toPath().resolve("nowhere"))).scan(roots).entries)
    }

    @Test
    fun aMissingOrUnreadableProjectsDirMakesTheScanIncomplete() {
        val missing = HistoryScanner(ClaudeHome(tmp.root.toPath().resolve("nowhere"))).scan(roots)
        assertFalse("a projects dir that does not exist", missing.complete)
        assertEquals(emptyList<HistoryEntry>(), missing.entries)
        val fileHome = ClaudeHome(tmp.root.toPath().resolve("file-home"))
        Files.createDirectories(fileHome.root)
        Files.writeString(fileHome.projectsDir, "not a directory")
        assertFalse("a projects dir that is a regular file", HistoryScanner(fileHome).scan(roots).complete)
        transcript(r("w/acme"), u("root-1"), 100)
        assertTrue("a projects dir listed to the end", scanner.scan(roots).complete)
    }

    @Test
    fun aProjectFolderThatCannotBeListedMakesTheScanIncomplete() {
        Fixture.assumeNotWindows("POSIX permissions")
        assumeFalse("root reads any folder", System.getProperty("user.name") == "root")
        transcript(r("w/acme"), u("root-1"), 100)
        val locked = Files.createDirectories(home.projectsDir.resolve(Slug.of(r("w/acme/locked"))))
        Files.setPosixFilePermissions(locked, emptySet())
        try {
            val scan = scanner.scan(roots)
            assertFalse(scan.complete)
            assertEquals("what could be listed is still there", listOf(u("root-1")), scan.entries.map { it.sessionId })
        } finally {
            Files.setPosixFilePermissions(locked, PosixFilePermissions.fromString("rwxr-xr-x"))
        }
    }

    @Test
    fun scanNeverWrites() {
        transcript(r("w/acme"), u("root-1"), 100, custom("t"))
        transcript(r("w/acme/sub"), u("sub-1"), 200)
        val before = TreeDigest.of(home.root)
        repeat(3) { scanner.scan(roots) }
        assertEquals(before, TreeDigest.of(home.root))
    }

    /** A `..` in a folder that is not there could lead anywhere, so the session is not listed on a guess. */
    @Test
    fun aCwdWithADotDotInAMissingFolderStaysOut() {
        transcript(r("w/acme/../acme/x"), u("dotdot"), 100, folderSlug = Slug.of(r("w/acme")))
        assertEquals(emptyList<String>(), scanner.scan(roots).entries.map { it.sessionId })
    }

    /** A root that is no path reads no project folder at all - an empty slug would be the prefix of every one. */
    @Test
    fun anEmptyRootReadsNoProjectFolder() {
        val path = transcript(r("w/acme"), u("root-1"), 100)
        assertEquals(emptyList<String>(), scanner.scan(listOf("")).entries.map { it.sessionId })
        assertEquals(emptyList<Path>(), listOf(path).filter { it in home.bytesRead })
    }

    /** Claude Code may slug the folder as written or with its links resolved - the folder named after either is read, the cwd inside decides. */
    @Test
    fun aRootThroughALinkReadsTheFolderNamedAfterTheLinkToo() {
        Fixture.assumeNotWindows("a symbolic link needs a privilege there")
        val real = Fixture.tempDirectory("skein-root")
        val link = real.resolveSibling(real.fileName.toString() + "-link")
        try {
            Files.createSymbolicLink(link, real)
            transcript(real.toString(), u("by-link"), 100, folderSlug = Slug.of(link.toString()))
            transcript(real.toString(), u("by-real"), 200)
            assertEquals(listOf(u("by-real"), u("by-link")), scanner.scan(listOf(link.toString())).entries.map { it.sessionId })
        } finally {
            Files.deleteIfExists(link)
            Files.deleteIfExists(real)
        }
    }
}
