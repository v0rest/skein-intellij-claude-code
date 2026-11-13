package io.github.v0rest.skein.ui

import io.github.v0rest.skein.git.ChangeKind
import io.github.v0rest.skein.git.ChangesLookup
import io.github.v0rest.skein.git.NoOptionalLocks
import io.github.v0rest.skein.git.WorktreeChangesReader
import io.github.v0rest.skein.testutil.RealGit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * [WorktreeDisk] on a real disk with real links: the worktree side of a file is read only at a path inside the worktree
 * that no link on the way leads out of. Skipped where links cannot be made.
 */
class WorktreeDiskTest {
    @get:Rule
    val folder = TemporaryFolder()

    private fun dir(path: Path): Path = Files.createDirectories(path)

    private fun file(path: Path, text: String): Path = Files.writeString(dir(path.parent).resolve(path.fileName), text)

    private fun link(path: Path, target: Path): Path =
        try {
            Files.createSymbolicLink(path, target)
        } catch (e: UnsupportedOperationException) {
            assumeTrue("no links here: $e", false)
            path
        }

    @Test
    fun aFileAFolderAMissingPathAndALinkItselfAreWhatTheyAre() {
        val wt = dir(folder.root.toPath().resolve("wt"))
        file(wt.resolve("src/a.c"), "int a;\n")
        dir(wt.resolve("nested"))
        link(wt.resolve("l"), Path.of("src/a.c"))
        assertEquals(DiskSide.File(7), WorktreeDisk.of(wt, "src/a.c"))
        assertEquals(DiskSide.Folder, WorktreeDisk.of(wt, "nested/"))
        assertEquals(DiskSide.Missing, WorktreeDisk.of(wt, "src/b.c"))
        assertEquals(DiskSide.Missing, WorktreeDisk.of(wt, "gone/b.c"))
        // a file where a folder on the way should be: the path is not there
        assertEquals(DiskSide.Missing, WorktreeDisk.of(wt, "src/a.c/x"))
        // the link itself, by where it points, never followed
        assertEquals(DiskSide.Link("src/a.c"), WorktreeDisk.of(wt, "l"))
    }

    /** A folder replaced by a link to a folder outside the worktree: the file behind it is never the worktree side. */
    @Test
    fun aLinkOnTheWayLeadsOutside() {
        val wt = dir(folder.root.toPath().resolve("wt"))
        val outside = dir(folder.root.toPath().resolve("outside"))
        file(outside.resolve("x.c"), "secret\n")
        link(wt.resolve("dir"), outside)
        assertEquals(DiskSide.Outside, WorktreeDisk.of(wt, "dir/x.c"))
        // even a link that stays inside the worktree is not followed on the way
        dir(wt.resolve("real"))
        file(wt.resolve("real/y.c"), "y\n")
        link(wt.resolve("alias"), wt.resolve("real"))
        assertEquals(DiskSide.Outside, WorktreeDisk.of(wt, "alias/y.c"))
    }

    /** The worktree reached through a link of its own still counts from its real path. */
    @Test
    fun aWorktreeReachedThroughALinkIsReadFromItsRealPath() {
        val wt = dir(folder.root.toPath().resolve("wt"))
        file(wt.resolve("a.c"), "int a;\n")
        val via = link(folder.root.toPath().resolve("via"), wt)
        assertEquals(DiskSide.File(7), WorktreeDisk.of(via, "a.c"))
        assertEquals(wt.toRealPath().resolve("a.c"), WorktreeDisk.pathOf(via, "a.c"))
    }

    @Test
    fun aPathGitWouldNeverWriteIsOutside() {
        val wt = dir(folder.root.toPath().resolve("wt"))
        file(folder.root.toPath().resolve("x.c"), "x\n")
        assertEquals(DiskSide.Outside, WorktreeDisk.of(wt, "../x.c"))
        assertEquals(DiskSide.Outside, WorktreeDisk.of(wt, "./x.c"))
        assertEquals(DiskSide.Outside, WorktreeDisk.of(wt, "/x.c"))
    }

    /**
     * The reported case end to end: tracked `dir/x.c`, then `dir` replaced by a link to a folder outside. git lists the
     * file as deleted and the link as untracked, and neither shows what lies outside.
     */
    @Test
    fun aTrackedFolderReplacedByALinkShowsADeletionAndTheLink() {
        val git = RealGit(folder)
        assumeTrue(git.available)
        val repo = git.repository("acme", files = mapOf("dir/x.c" to "int x;\n")).toRealPath()
        assertTrue(git.run(repo, "branch", "-M", "main").ok)
        assertTrue(git.run(repo, "worktree", "add", "-q", "-b", "worktree-task-1", "--", ".claude/worktrees/task-1").ok)
        val wt = repo.resolve(".claude/worktrees/task-1")
        val outside = dir(folder.root.toPath().resolve("outside"))
        file(outside.resolve("x.c"), "secret\n")
        Files.delete(wt.resolve("dir/x.c"))
        Files.delete(wt.resolve("dir"))
        link(wt.resolve("dir"), outside)
        val found = WorktreeChangesReader(NoOptionalLocks(git)).read(repo, wt, "main") as ChangesLookup.Found
        assertEquals(listOf("dir" to ChangeKind.UNTRACKED, "dir/x.c" to ChangeKind.DELETED), found.files.map { it.path to it.kind })
        val plans = found.files.associate { it.path to ShowChanges.plan(it, WorktreeDisk.of(wt, it.path)) }
        assertEquals(DiffPlan.Show(BaseView.BYTES, WorktreeView.NONE), plans["dir/x.c"])
        assertEquals(DiffPlan.Show(BaseView.NONE, WorktreeView.LINK), plans["dir"])
    }

    /** A tracked file deleted and a folder made at its path: the deletion, not the folder. */
    @Test
    fun aDeletedFileWithAFolderInItsPlaceIsADeletion() {
        val git = RealGit(folder)
        assumeTrue(git.available)
        val repo = git.repository("acme", files = mapOf("gone.c" to "int gone;\n")).toRealPath()
        assertTrue(git.run(repo, "branch", "-M", "main").ok)
        assertTrue(git.run(repo, "worktree", "add", "-q", "-b", "worktree-task-1", "--", ".claude/worktrees/task-1").ok)
        val wt = repo.resolve(".claude/worktrees/task-1")
        Files.delete(wt.resolve("gone.c"))
        file(wt.resolve("gone.c/inner.c"), "i\n")
        val found = WorktreeChangesReader(NoOptionalLocks(git)).read(repo, wt, "main") as ChangesLookup.Found
        val gone = found.files.single { it.path == "gone.c" }
        assertEquals(ChangeKind.DELETED, gone.kind)
        assertEquals(DiffPlan.Show(BaseView.BYTES, WorktreeView.NONE), ShowChanges.plan(gone, WorktreeDisk.of(wt, gone.path)))
    }
}
