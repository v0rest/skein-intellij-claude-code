package io.github.v0rest.skein.claude

import io.github.v0rest.skein.testutil.TreeDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class ClaudeHomeTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun root(): Path = tmp.root.toPath().resolve(".claude")
    private fun home() = ClaudeHome(root())
    private fun write(relative: String, content: String) {
        val path = root().resolve(relative)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }

    @Test
    fun readTextReturnsNullForMissingFile() {
        assertNull(home().readText(root().resolve("sessions/1.json")))
    }

    @Test
    fun readTextReturnsContent() {
        write("sessions/1.json", """{"pid":1}""")
        assertEquals("""{"pid":1}""", home().readText(root().resolve("sessions/1.json")))
    }

    @Test
    fun readTextReturnsNullWhenLargerThanLimit() {
        write("projects/p/a.jsonl", "0123456789")
        assertNull(home().readText(root().resolve("projects/p/a.jsonl"), maxBytes = 5))
    }

    @Test
    fun readTailReturnsLastBytes() {
        write("projects/p/a.jsonl", "0123456789")
        assertEquals("789", String(home().readTail(root().resolve("projects/p/a.jsonl"), 3)!!))
    }

    @Test
    fun readTailOfShortFileReturnsWholeFile() {
        write("projects/p/a.jsonl", "01")
        assertEquals("01", String(home().readTail(root().resolve("projects/p/a.jsonl"), 3)!!))
    }

    @Test
    fun readHeadReturnsFirstBytes() {
        write("projects/p/a.jsonl", "0123456789")
        assertEquals("012", String(home().readHead(root().resolve("projects/p/a.jsonl"), 3)!!))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsPathOutsideRoot() {
        home().readText(root().resolve("../etc/passwd"))
    }

    @Test
    fun listReturnsEmptyForMissingDir() {
        assertEquals(emptyList<Path>(), home().list(root().resolve("sessions")))
    }

    @Test
    fun listOrNullTellsAMissingOrUnreadableDirFromAnEmptyOne() {
        assertNull(home().listOrNull(root().resolve("sessions")))
        write("sessions/1.json", "{}")
        assertNull("a regular file is not a directory", home().listOrNull(root().resolve("sessions/1.json")))
        assertEquals(listOf(root().resolve("sessions/1.json")), home().listOrNull(root().resolve("sessions")))
        Files.createDirectories(root().resolve("projects"))
        assertEquals(emptyList<Path>(), home().listOrNull(root().resolve("projects")))
    }

    @Test
    fun listFiltersByGlob() {
        write("sessions/1.json", "{}")
        write("sessions/1.key", "k")
        assertEquals(listOf(root().resolve("sessions/1.json")), home().list(root().resolve("sessions"), "*.json"))
    }

    @Test
    fun stampReportsSizeAndMtime() {
        write("sessions/1.json", "{}")
        val stamp = home().stamp(root().resolve("sessions/1.json"))!!
        assertEquals(2L, stamp.size)
        assertEquals(Files.getLastModifiedTime(root().resolve("sessions/1.json")).toMillis(), stamp.modifiedMillis)
        assertNull(home().stamp(root().resolve("sessions/2.json")))
    }

    @Test
    fun resolveRootUsesClaudeConfigDirWhenSet() {
        val root = ClaudeHome.resolveRoot(mapOf("CLAUDE_CONFIG_DIR" to "/opt/claude-cfg"), userHome = "/Users/u")
        assertEquals(Path.of("/opt/claude-cfg"), root)
    }

    @Test
    fun resolveRootPrefersTheShellEnvironment() {
        val root = ClaudeHome.resolveRoot(shellEnv = mapOf("CLAUDE_CONFIG_DIR" to "/from/zshrc"), processEnv = mapOf("CLAUDE_CONFIG_DIR" to "/from/dock"), userHome = "/Users/u")
        assertEquals(Path.of("/from/zshrc"), root)
        assertEquals(Path.of("/from/dock"), ClaudeHome.resolveRoot(shellEnv = emptyMap(), processEnv = mapOf("CLAUDE_CONFIG_DIR" to "/from/dock"), userHome = "/Users/u"))
    }

    @Test
    fun resolveRootFallsBackToUserHome() {
        assertEquals(Path.of("/Users/u/.claude"), ClaudeHome.resolveRoot(emptyMap(), userHome = "/Users/u"))
    }

    @Test
    fun resolveRootIgnoresBlankOrRelativeConfigDir() {
        assertEquals(Path.of("/Users/u/.claude"), ClaudeHome.resolveRoot(mapOf("CLAUDE_CONFIG_DIR" to "  "), userHome = "/Users/u"))
        assertEquals(Path.of("/Users/u/.claude"), ClaudeHome.resolveRoot(mapOf("CLAUDE_CONFIG_DIR" to "cfg"), userHome = "/Users/u"))
    }

    @Test
    fun readsLeaveTreeUnchanged() {
        write("sessions/1.json", "{}")
        write("projects/p/a.jsonl", "x")
        val before = TreeDigest.of(root())
        assertEquals(setOf("sessions", "sessions/1.json", "projects", "projects/p", "projects/p/a.jsonl"), before.keys)
        val h = home()
        h.list(h.sessionsDir).forEach { h.readText(it) }
        h.readTail(root().resolve("projects/p/a.jsonl"), 10)
        h.readHead(root().resolve("projects/p/a.jsonl"), 10)
        h.stamp(root().resolve("projects/p/a.jsonl"))
        assertEquals(before, TreeDigest.of(root()))
    }

    /**
     * A dotfiles repository in the home folder: Claude Code would put its worktrees inside its own home, so Skein starts,
     * maps and removes none there. Elsewhere, and with the home moved away by `CLAUDE_CONFIG_DIR`, nothing is refused.
     */
    @Test
    fun aRepositoryHoldingTheClaudeHomeIsRefused() {
        val home = ClaudeHome(Path.of("/w/home/.claude"))
        assertTrue(home.holdsWorktreesOf(Path.of("/w/home")))
        assertTrue(home.holdsWorktreesOf(Path.of("/w/home/")))
        assertTrue(home.holds(Path.of("/w/home/.claude/worktrees/feat-x")))
        assertFalse(home.holdsWorktreesOf(Path.of("/w/home/projects/acme")))
        assertFalse(home.holdsWorktreesOf(Path.of("/w/acme")))
        assertFalse(ClaudeHome(Path.of("/w/claude-config")).holdsWorktreesOf(Path.of("/w/home")))
        // a sibling folder whose name starts the same is not inside
        assertFalse(home.holds(Path.of("/w/home/.claude-old/worktrees")))
    }

    /** The home and the repository compared with their links resolved, as far as the folders exist yet. */
    @Test
    fun theClaudeHomeIsFoundThroughLinks() {
        val base = tmp.root.toPath()
        val userHome = Files.createDirectories(base.resolve("home"))
        Files.createDirectories(userHome.resolve(".claude"))
        // CLAUDE_CONFIG_DIR names a link to the home's real folder
        val link = Files.createSymbolicLink(base.resolve("config-link"), userHome.resolve(".claude"))
        assertTrue(ClaudeHome(link).holdsWorktreesOf(userHome))
        // a repository whose .claude folder is a link into the home
        val acme = Files.createDirectories(base.resolve("acme"))
        Files.createSymbolicLink(acme.resolve(".claude"), userHome.resolve(".claude"))
        assertTrue(ClaudeHome(userHome.resolve(".claude")).holdsWorktreesOf(acme))
        assertFalse(ClaudeHome(userHome.resolve(".claude")).holdsWorktreesOf(Files.createDirectories(base.resolve("beta"))))
    }
}
