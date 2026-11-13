package io.github.v0rest.skein.git

import io.github.v0rest.skein.testutil.RealGit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/** [WorktreeGit.upstreamCounts] against the real git, in a clone of a local "remote" as Skein runs it, and skipped without git. */
class BaseWarningRealGitTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var git: RealGit
    private lateinit var wtGit: WorktreeGit
    private lateinit var upstream: Path
    private lateinit var clone: Path

    @Before
    fun aCloneOfARemote() {
        git = RealGit(folder)
        assumeTrue(git.available)
        wtGit = WorktreeGit(NoOptionalLocks(git))
        upstream = git.repository("remote").toRealPath()
        ok(upstream, "branch", "-M", "main")
        clone = folder.root.toPath().resolve("acme")
        ok(folder.root.toPath(), "clone", "-q", upstream.toString(), clone.toString())
        ok(clone, "checkout", "-q", "main")
    }

    private fun ok(dir: Path, vararg args: String): GitResult = git.run(dir, *args).also { assertTrue(args.joinToString(" ") + ": " + it.stderr, it.ok) }

    private fun commit(dir: Path, file: String) {
        Files.writeString(dir.resolve(file), "x\n")
        ok(dir, "add", "--", file)
        ok(dir, "commit", "-q", "-m", "add $file")
    }

    @Test
    fun aLevelCloneIsZeroZero() {
        assertEquals(UpstreamCounts("origin/main", 0, 0, false), wtGit.upstreamCounts(clone))
    }

    @Test
    fun behindOnlyAsOfTheLastFetch() {
        commit(upstream, "one.c")
        // not fetched yet, so the clone knows nothing of it
        assertEquals(UpstreamCounts("origin/main", 0, 0, false), wtGit.upstreamCounts(clone))
        ok(clone, "fetch", "-q")
        assertEquals(UpstreamCounts("origin/main", 0, 1, false), wtGit.upstreamCounts(clone))
    }

    @Test
    fun divergedCountsBothSides() {
        commit(upstream, "one.c")
        commit(upstream, "two.c")
        commit(clone, "mine.c")
        ok(clone, "fetch", "-q")
        assertEquals(UpstreamCounts("origin/main", 1, 2, false), wtGit.upstreamCounts(clone))
    }

    @Test
    fun aBranchWithoutUpstreamGivesNull() {
        ok(clone, "checkout", "-q", "-b", "local-only")
        assertNull(wtGit.upstreamCounts(clone))
    }

    @Test
    fun aDetachedHeadGivesNull() {
        ok(clone, "checkout", "-q", "--detach")
        assertNull(wtGit.upstreamCounts(clone))
    }

    @Test
    fun aRepositoryWithoutARemoteGivesNull() {
        assertNull(wtGit.upstreamCounts(upstream))
    }

    @Test
    fun aGoneUpstreamBranchGivesNull() {
        ok(clone, "checkout", "-q", "-b", "gone")
        ok(clone, "push", "-q", "-u", "origin", "gone")
        ok(upstream, "branch", "-q", "-D", "gone")
        ok(clone, "fetch", "-q", "--prune")
        assertNull(wtGit.upstreamCounts(clone))
    }

    @Test
    fun aFolderThatIsNoRepositoryGivesNull() {
        assertNull(wtGit.upstreamCounts(folder.newFolder("plain").toPath()))
    }

    @Test
    fun aLocalUpstreamBranchIsNoRemoteTrackingBranch() {
        ok(clone, "checkout", "-q", "-b", "feature", "--track", "main")
        commit(clone, "f.c")
        ok(clone, "checkout", "-q", "main")
        commit(clone, "m.c")
        ok(clone, "checkout", "-q", "feature")
        assertNull(wtGit.upstreamCounts(clone))
    }

    @Test
    fun aRepositoryWithoutAnyRemoteHasNoOrigin() {
        assertTrue(wtGit.hasOrigin(clone))
        assertFalse(wtGit.hasOrigin(upstream))
    }

    @Test
    fun aShallowCloneIsFlagged() {
        commit(upstream, "one.c")
        val shallow = folder.root.toPath().resolve("shallow")
        ok(folder.root.toPath(), "clone", "-q", "--depth", "1", upstream.toUri().toString(), shallow.toString())
        assertEquals(true, wtGit.upstreamCounts(shallow)?.shallow)
        assertEquals(false, wtGit.upstreamCounts(clone)?.shallow)
    }

    @Test
    fun theMainCheckoutRootOfALinkedWorktreeIsTheMainCheckout() {
        val real = clone.toRealPath()
        ok(clone, "worktree", "add", "-q", "-b", "worktree-x", "--", ".claude/worktrees/x")
        assertEquals(real, wtGit.mainCheckoutRoot(clone.resolve(".claude/worktrees/x")))
        assertEquals(real, wtGit.mainCheckoutRoot(clone))
    }
}
