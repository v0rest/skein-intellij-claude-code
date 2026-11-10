package io.github.v0rest.skein.threads

import io.github.v0rest.skein.git.Changes
import io.github.v0rest.skein.git.GitEntry
import io.github.v0rest.skein.git.GitResult
import io.github.v0rest.skein.git.GitRunner
import io.github.v0rest.skein.git.WorktreeCounts
import io.github.v0rest.skein.git.WorktreeGit
import io.github.v0rest.skein.git.WorktreeReader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/** [WorktreeSweep] over a scripted git and disk, and the section's list ([KeptWorktrees]). */
class WorktreeSweepTest {
    private val repo = "/w/acme"
    private val parent = "/w/acme/.claude/worktrees"

    private class FakeGit(private val answer: (Path, List<String>) -> GitResult) : GitRunner {
        val calls = mutableListOf<String>()

        override fun run(dir: Path, vararg args: String): GitResult {
            calls += args.joinToString(" ") + " @" + dir
            return answer(dir, args.toList())
        }
    }

    private fun ok(out: String = "") = GitResult(0, out, "", false)

    private fun listing(vararg blocks: String) = "worktree $repo\nHEAD 1111\nbranch refs/heads/main\n\n" + blocks.joinToString("") { "$it\n\n" }

    private fun registered(name: String, branch: String? = "worktree-$name", locked: Boolean = false, prunable: Boolean = false) =
        "worktree $parent/$name\nHEAD 2222\n" + (branch?.let { "branch refs/heads/$it" } ?: "detached") +
            (if (locked) "\nlocked" else "") + (if (prunable) "\nprunable gitdir file points to non-existent location" else "")

    /** A worktree with one changed file, two commits ahead of whatever base it is asked about. */
    private fun answers(list: GitResult): (Path, List<String>) -> GitResult = { _, args ->
        when {
            args.take(2) == listOf("worktree", "list") -> list
            "status" in args -> ok("## b\u0000 M a.kt\u0000")
            args.first() == "rev-parse" && "--git-path" in args -> ok("m1\nm2\nm3\nm4\n")
            args.first() == "rev-parse" -> ok("abc")
            args.first() == "rev-list" -> ok("2\n")
            args.first() == "symbolic-ref" -> GitResult(1, "", "", false)
            else -> GitResult(1, "", "unexpected", false)
        }
    }

    private class Disk(
        val folders: Map<String, List<String>> = emptyMap(),
        val existing: Set<String>? = null,
        val real: Map<String, String> = emptyMap(),
        val claudeHome: Set<String> = emptySet(),
        val gitEntries: Map<String, GitEntry> = emptyMap(),
    ) : WorktreeSweep.Disk {
        override fun realPath(folder: String): String? = real[folder.trimEnd('/')] ?: folder.trimEnd('/')

        override fun isRepository(folder: Path): Boolean = true

        override fun holdsClaudeHome(repository: Path): Boolean = repository.toString() in claudeHome

        override fun folders(dir: Path): List<String> = folders[dir.toString()].orEmpty()

        override fun exists(path: Path): Boolean = existing?.contains(path.toString()) ?: true

        override fun gitEntry(folder: Path): GitEntry? = gitEntries[folder.toString()]
    }

    private fun sweep(git: FakeGit, disk: WorktreeSweep.Disk = Disk()) = WorktreeSweep(WorktreeGit(git), WorktreeReader(git) { false }, disk)

    private fun thread(id: String, worktree: String? = null, folder: String = repo, base: String? = null, sessionCwd: String? = null) =
        SkeinThread(id, folder, null, id, ThreadState.Idle, 0, 0, null, sessionCwd = sessionCwd, worktree = worktree, base = base)

    private val counted = WorktreeCounts.Counted(Changes(1, listOf("a.kt")), detached = false, base = "main", ahead = 2)

    /** A kept worktree has no recorded base: the repository's first fallback, which the scripted git has, counts. */
    private val keptCounted = counted.copy(base = "origin/main")

    @Test
    fun theOrphanSetHoldsKeptAndUnregisteredFoldersAndHeldOnesWithoutCounts() {
        val git = FakeGit(answers(ok(listing(registered("held"), registered("kept"), registered("loose", branch = null)))))
        val disk = Disk(folders = mapOf(parent to listOf("held", "kept", "loose", "stray", ".hidden")))
        val result = sweep(git, disk).run(listOf(repo), listOf(thread("t1", worktree = "held", base = "main")))
        assertEquals(mapOf("t1" to counted), result.counts)
        assertEquals(
            listOf(
                KeptWorktree(repo, "$parent/held", "held", KeptKind.Registered("worktree-held", locked = false), WorktreeCounts.NotShown),
                KeptWorktree(repo, "$parent/kept", "kept", KeptKind.Registered("worktree-kept", locked = false), keptCounted),
                KeptWorktree(repo, "$parent/loose", "loose", KeptKind.Registered(null, locked = false), keptCounted),
                KeptWorktree(repo, "$parent/stray", "stray", KeptKind.Unregistered, WorktreeCounts.NotShown),
            ),
            result.kept,
        )
        // git runs in folders it lists only, never in the stray one or the held one a second time
        assertTrue(git.calls.toString(), git.calls.none { it.endsWith("@$parent/stray") })
        assertEquals(1, git.calls.count { " status " in " $it" && it.endsWith("@$parent/held") })
    }

    /** A folder a thread holds is listed by the look, and left out on the EDT with the threads as they are then. */
    @Test
    fun theSectionLeavesOutWhatAThreadOfThisWindowHolds() {
        val kept = listOf("held", "kept", "cwd", "plain").map { KeptWorktree(repo, "$parent/$it", it, KeptKind.Unregistered, WorktreeCounts.NotShown) }
        val threads = listOf(
            thread("t1", worktree = "held"),
            thread("t2", sessionCwd = "$parent/cwd/src"),
            thread("t3", folder = "$parent/plain"),
            thread("t4", worktree = "kept", folder = "/w/other"),
        )
        assertEquals(listOf("kept"), KeptWorktrees.visible(kept, threads, emptyMap()).map { it.name })
    }

    @Test
    fun aThreadNamingTheRepositoryByALinkStillHoldsItsWorktree() {
        val kept = listOf(KeptWorktree("/link/acme", "$parent/acme", "acme", KeptKind.Unregistered, WorktreeCounts.NotShown))
        val thread = thread("t1", worktree = "acme", folder = "/link/acme")
        assertEquals(emptyList<KeptWorktree>(), KeptWorktrees.visible(kept, listOf(thread), mapOf("/link/acme" to repo)))
        assertEquals(1, KeptWorktrees.visible(kept, listOf(thread), emptyMap()).size)
    }

    @Test
    fun aFailedListingMarksEveryFolderUnknownAndEveryCountAQuestionMark() {
        val git = FakeGit(answers(GitResult(128, "", "fatal: not a git repository\n", false)))
        val result = sweep(git, Disk(folders = mapOf(parent to listOf("kept")))).run(listOf(repo), listOf(thread("t1", worktree = "held")))
        assertEquals(mapOf("t1" to WorktreeCounts.Failed("not a git repository")), result.counts)
        assertEquals(listOf(KeptWorktree(repo, "$parent/kept", "kept", KeptKind.Unknown("not a git repository"), WorktreeCounts.Failed("not a git repository"))), result.kept)
        assertEquals(listOf("worktree list --porcelain @$repo"), git.calls)
    }

    @Test
    fun noWorktreesFolderAndNoWorktreeThreadRunsNoGit() {
        val git = FakeGit(answers(ok(listing())))
        assertEquals(WorktreeSweep.Result(emptyMap(), emptyList(), mapOf(repo to repo)), sweep(git).run(listOf(repo), listOf(thread("t1"))))
        assertEquals(emptyList<String>(), git.calls)
    }

    @Test
    fun aGoneFolderAPrunableEntryAndAnUnlistedThreadFolderShowNothing() {
        val git = FakeGit(answers(ok(listing(registered("gone"), registered("stale", prunable = true)))))
        val disk = Disk(existing = setOf("$parent/stale", "$parent/unlisted"))
        val threads = listOf(thread("t1", worktree = "gone"), thread("t2", worktree = "stale"), thread("t3", worktree = "unlisted"))
        val result = sweep(git, disk).run(listOf(repo), threads)
        assertEquals(mapOf("t1" to WorktreeCounts.NotShown, "t2" to WorktreeCounts.NotShown, "t3" to WorktreeCounts.NotShown), result.counts)
        assertEquals(listOf("worktree list --porcelain @$repo"), git.calls)
    }

    @Test
    fun aRepositoryHoldingTheClaudeHomeIsLeftAlone() {
        val git = FakeGit(answers(ok(listing(registered("kept")))))
        val result = sweep(git, Disk(folders = mapOf(parent to listOf("kept")), claudeHome = setOf(repo))).run(listOf(repo), listOf(thread("t1", worktree = "kept")))
        assertEquals(mapOf("t1" to WorktreeCounts.NotShown), result.counts)
        assertEquals(emptyList<KeptWorktree>(), result.kept)
        assertEquals(emptyList<String>(), git.calls)
    }

    /** Each repository once by its real path, and its fallback base read once for all its worktrees. */
    @Test
    fun eachRepositoryIsLookedAtOnceAndItsFallbackBaseReadOnce() {
        val git = FakeGit(answers(ok(listing(registered("a"), registered("b"), registered("c")))))
        val disk = Disk(folders = mapOf(parent to listOf("a", "b", "c")), real = mapOf("/link/acme" to repo))
        val result = sweep(git, disk).run(listOf(repo, "/link/acme/"), listOf(thread("t1", worktree = "a", folder = "/link/acme")))
        assertEquals(1, git.calls.count { it.startsWith("worktree list") })
        assertEquals(1, git.calls.count { it.startsWith("symbolic-ref") })
        assertEquals(keptCounted, result.counts["t1"])
        assertEquals(listOf("a", "b", "c"), result.kept.map { it.name })
        assertEquals(listOf(repo, repo, repo), result.kept.map { it.repository })
    }

    @Test
    fun aThreadsRecordedBaseIsTheOneItsCountsUse() {
        val git = FakeGit(answers(ok(listing(registered("a")))))
        sweep(git).run(listOf(repo), listOf(thread("t1", worktree = "a", base = "develop")))
        assertTrue(git.calls.toString(), git.calls.contains("rev-list --count refs/heads/develop..HEAD @$parent/a"))
    }

    @Test
    fun aLockedWorktreeIsSaidSo() {
        val git = FakeGit(answers(ok(listing(registered("a", locked = true)))))
        val result = sweep(git, Disk(folders = mapOf(parent to listOf("a")))).run(listOf(repo), emptyList())
        assertEquals(KeptKind.Registered("worktree-a", locked = true), result.kept.single().kind)
    }

    @Test
    fun whatOneRepositoryThrowsStaysWithIt() {
        val git = FakeGit(answers(ok(listing(registered("a")))))
        val disk = object : WorktreeSweep.Disk by Disk(folders = mapOf(parent to listOf("a"))) {
            override fun folders(dir: Path): List<String> = if (dir.toString().startsWith("/w/broken")) error("disk on fire") else listOf("a")
        }
        val result = sweep(git, disk).run(listOf("/w/broken", repo), listOf(thread("t1", worktree = "x", folder = "/w/broken")))
        assertEquals(WorktreeCounts.Failed("disk on fire"), result.counts["t1"])
        assertEquals(listOf("a"), result.kept.map { it.name })
    }

    @Test
    fun theFallbackBaseIsReadAgainstTheRepository() {
        var asked: Path? = null
        val git = FakeGit { dir, args ->
            if (args.first() == "symbolic-ref") asked = dir
            answers(ok(listing(registered("a"))))(dir, args)
        }
        sweep(git, Disk(folders = mapOf(parent to listOf("a")))).run(listOf(repo), emptyList())
        assertEquals(Path.of(repo), asked)
    }

    /** A look that throws half way through a repository shows none of its folders, not a part of them. */
    @Test
    fun aRepositoryWhoseLookThrowsShowsNoneOfItsFolders() {
        val git = FakeGit(answers(ok(listing(registered("a"), registered("b")))))
        val disk = object : WorktreeSweep.Disk by Disk(folders = mapOf(parent to listOf("a", "b"))) {
            override fun exists(path: Path): Boolean = if (path.toString().endsWith("/b")) error("disk on fire") else true
        }
        assertEquals(emptyList<KeptWorktree>(), sweep(git, disk).run(listOf(repo), emptyList()).kept)
    }

    /** A folder git does not list here but with a `.git` of its own is a worktree linked elsewhere or a repository, never a leftover. */
    @Test
    fun aFolderWithAGitOfItsOwnIsNoLeftover() {
        val git = FakeGit(answers(ok(listing())))
        val disk = Disk(folders = mapOf(parent to listOf("linked", "clone", "stray")), gitEntries = mapOf("$parent/linked" to GitEntry.FILE, "$parent/clone" to GitEntry.FOLDER))
        val kinds = sweep(git, disk).run(listOf(repo), emptyList()).kept.associate { it.name to it.kind }
        assertEquals(mapOf("clone" to KeptKind.Unlinked(ownRepository = true), "linked" to KeptKind.Unlinked(ownRepository = false), "stray" to KeptKind.Unregistered), kinds)
        assertEquals(listOf("worktree list --porcelain @$repo"), git.calls)
    }

    /** Closed, the section needs the folders and their kind alone: no git runs in a kept worktree, thread rows still count. */
    @Test
    fun aClosedSectionGetsNoCountsButTheThreadRowsDo() {
        val git = FakeGit(answers(ok(listing(registered("held"), registered("kept")))))
        val disk = Disk(folders = mapOf(parent to listOf("held", "kept")))
        val result = sweep(git, disk).run(listOf(repo), listOf(thread("t1", worktree = "held", base = "main")), keptCounts = false)
        assertEquals(mapOf("t1" to counted), result.counts)
        assertEquals(listOf(WorktreeCounts.NotShown, WorktreeCounts.NotShown), result.kept.map { it.counts })
        assertEquals(false, result.keptCounted)
        assertTrue(git.calls.toString(), git.calls.none { it.endsWith("@$parent/kept") })
    }
}
