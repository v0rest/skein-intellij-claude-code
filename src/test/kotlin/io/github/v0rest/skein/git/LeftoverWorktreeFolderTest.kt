package io.github.v0rest.skein.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/** The one recursive delete, on a real disk in a temporary folder. */
class LeftoverWorktreeFolderTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun repo(): Path = folder.newFolder("acme").toPath().toRealPath()

    private fun leftover(repo: Path, name: String = "acme"): Path =
        Files.createDirectories(repo.resolve(".claude/worktrees/$name/src")).parent.also { Files.writeString(it.resolve("src/a.c"), "int a;\n") }

    @Test
    fun theFolderAndEverythingInItGo() {
        val repo = repo()
        val dir = leftover(repo)
        Files.writeString(repo.resolve(".claude/worktrees/.gitignore"), "*\n")
        assertNull(LeftoverWorktreeFolder.delete(repo.toString(), "acme"))
        assertFalse(Files.exists(dir))
        // the guard and the worktrees folder stay
        assertTrue(Files.exists(repo.resolve(".claude/worktrees/.gitignore")))
    }

    /** A link inside goes as a link: what it points to, outside the folder, stays whole. */
    @Test
    fun aLinkInsideIsRemovedNotFollowed() {
        val repo = repo()
        val dir = leftover(repo)
        val outside = folder.newFolder("keep").toPath()
        Files.writeString(outside.resolve("precious.txt"), "keep me\n")
        Files.createSymbolicLink(dir.resolve("link"), outside)
        Files.createSymbolicLink(dir.resolve("file-link"), outside.resolve("precious.txt"))
        assertNull(LeftoverWorktreeFolder.delete(repo.toString(), "acme"))
        assertFalse(Files.exists(dir))
        assertEquals("keep me\n", Files.readString(outside.resolve("precious.txt")))
    }

    @Test
    fun aFolderThatIsALinkIsRefused() {
        val repo = repo()
        val outside = folder.newFolder("elsewhere").toPath()
        Files.writeString(outside.resolve("a.c"), "int a;\n")
        Files.createDirectories(repo.resolve(".claude/worktrees"))
        Files.createSymbolicLink(repo.resolve(".claude/worktrees/acme"), outside)
        assertNotNull(LeftoverWorktreeFolder.delete(repo.toString(), "acme"))
        assertTrue(Files.exists(outside.resolve("a.c")))
        assertTrue(Files.isSymbolicLink(repo.resolve(".claude/worktrees/acme")))
    }

    @Test
    fun aLinkOnTheWayIsRefused() {
        val repo = repo()
        val elsewhere = folder.newFolder("elsewhere").toPath()
        Files.createDirectories(elsewhere.resolve("acme"))
        Files.createDirectories(repo.resolve(".claude"))
        Files.createSymbolicLink(repo.resolve(".claude/worktrees"), elsewhere)
        assertNotNull(LeftoverWorktreeFolder.delete(repo.toString(), "acme"))
        assertTrue(Files.isDirectory(elsewhere.resolve("acme")))
    }

    @Test
    fun aNameClaudeCodeWouldNotUseOrAFileIsRefused() {
        val repo = repo()
        leftover(repo)
        assertNotNull(LeftoverWorktreeFolder.delete(repo.toString(), ".."))
        assertNotNull(LeftoverWorktreeFolder.delete(repo.toString(), ""))
        Files.writeString(repo.resolve(".claude/worktrees/plain"), "x")
        assertNotNull(LeftoverWorktreeFolder.delete(repo.toString(), "plain"))
        assertTrue(Files.exists(repo.resolve(".claude/worktrees/plain")))
        assertTrue(Files.exists(repo.resolve(".claude/worktrees/acme/src/a.c")))
    }

    /** A `.git` file or folder makes it a worktree or a repository, never a leftover: nothing is deleted. */
    @Test
    fun aFolderWithAGitEntryIsRefused() {
        val repo = repo()
        val withFile = leftover(repo, "linked")
        Files.writeString(withFile.resolve(".git"), "gitdir: /w/elsewhere/.git/worktrees/linked\n")
        val withFolder = leftover(repo, "clone")
        Files.createDirectories(withFolder.resolve(".git"))
        assertNotNull(LeftoverWorktreeFolder.delete(repo.toString(), "linked"))
        assertNotNull(LeftoverWorktreeFolder.delete(repo.toString(), "clone"))
        assertTrue(Files.exists(withFile.resolve("src/a.c")))
        assertTrue(Files.exists(withFolder.resolve("src/a.c")))
    }

    /** A clone or a worktree link one level down or deeper holds work too: refused, whatever lies around it. */
    @Test
    fun aGitEntryDeeperInsideIsRefused() {
        val repo = repo()
        val clone = leftover(repo, "clone")
        Files.createDirectories(clone.resolve("src/lib/.git"))
        val linked = leftover(repo, "linked")
        Files.writeString(Files.createDirectories(linked.resolve("a/b")).resolve(".git"), "gitdir: /w/elsewhere/.git/worktrees/b\n")
        assertNotNull(LeftoverWorktreeFolder.delete(repo.toString(), "clone"))
        assertNotNull(LeftoverWorktreeFolder.delete(repo.toString(), "linked"))
        assertTrue(Files.exists(clone.resolve("src/a.c")))
        assertTrue(Files.exists(linked.resolve("src/a.c")))
    }

    /** A link inside to a repository elsewhere is not followed: the link goes, the repository stays. */
    @Test
    fun aLinkToARepositoryElsewhereIsNotLookedInto() {
        val repo = repo()
        val dir = leftover(repo)
        val outside = folder.newFolder("outside-repo").toPath()
        Files.createDirectories(outside.resolve(".git"))
        Files.createSymbolicLink(dir.resolve("lib"), outside)
        assertNull(LeftoverWorktreeFolder.delete(repo.toString(), "acme"))
        assertTrue(Files.isDirectory(outside.resolve(".git")))
    }

    /** A look that cannot finish counts as a repository inside: nothing is deleted. */
    @Test
    fun aLookThatCannotFinishRefuses() {
        val repo = repo()
        val dir = leftover(repo)
        for (i in 1..5) Files.writeString(dir.resolve("f$i.txt"), "$i\n")
        assertNotNull(LeftoverWorktreeFolder.gitInside(dir, limit = 3))
        assertNull(LeftoverWorktreeFolder.gitInside(dir))
    }

    /** A folder inside that cannot be read could hide a repository: the walk gives up and nothing is deleted. */
    @Test
    fun anUnreadableFolderInsideRefuses() {
        val repo = repo()
        val dir = leftover(repo)
        val locked = Files.createDirectories(dir.resolve("locked"))
        Files.writeString(locked.resolve("x.c"), "int x;\n")
        withUnreadable(locked) {
            assertNotNull(LeftoverWorktreeFolder.gitInside(dir))
            assertNotNull(LeftoverWorktreeFolder.delete(repo.toString(), "acme"))
            assertTrue(Files.exists(dir.resolve("src/a.c")))
        }
        assertTrue(Files.exists(locked.resolve("x.c")))
    }
}

/**
 * Runs [block] with [dir] unreadable to its owner (no permissions at all), which are put back afterwards; skipped where
 * that cannot be done or the owner reads it anyway (root, a file system without POSIX permissions).
 */
internal fun withUnreadable(dir: Path, block: () -> Unit) {
    val before = try {
        Files.getPosixFilePermissions(dir)
    } catch (_: UnsupportedOperationException) {
        assumeTrue("no POSIX permissions here", false)
        return
    }
    try {
        Files.setPosixFilePermissions(dir, emptySet())
        val readable = try {
            Files.newDirectoryStream(dir).use { it.iterator().hasNext() }
            true
        } catch (_: java.io.IOException) {
            false
        }
        assumeTrue("the folder is still readable here", !readable)
        block()
    } finally {
        Files.setPosixFilePermissions(dir, before)
    }
}
