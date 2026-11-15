package io.github.v0rest.skein.threads

import io.github.v0rest.skein.git.Changes
import io.github.v0rest.skein.git.GitEntry
import io.github.v0rest.skein.git.GitResult
import io.github.v0rest.skein.git.GitRunner
import io.github.v0rest.skein.git.WorktreeCounts
import io.github.v0rest.skein.git.WorktreeGit
import io.github.v0rest.skein.git.WorktreeReader
import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.testutil.Fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * [WorktreeSweep] over a scripted git and disk, and the section's list ([KeptWorktrees]). Folders are the system's
 * own ([Fixture.root]), git's listing writes them as git does ([Fixture.forwardRoot], `C:/w/acme` on Windows).
 */
class WorktreeSweepTest {
    private val repo = Fixture.root("w/acme")
    private val parent = Fixture.root("w/acme/.claude/worktrees")

    /** The worktree folder [name] as the system writes it. */
    private fun wt(name: String) = Fixture.root("w/acme/.claude/worktrees/$name")

    private class FakeGit(private val answer: (Path, List<String>) -> GitResult) : GitRunner {
        val calls = mutableListOf<String>()

        override fun run(dir: Path, vararg args: String): GitResult {
            calls += args.joinToString(" ") + " @" + dir
            return answer(dir, args.toList())
        }
    }

    private fun ok(out: String = "") = GitResult(0, out, "", false)

    private fun listing(vararg blocks: String) = "worktree ${Fixture.forwardRoot("w/acme")}\nHEAD 1111\nbranch refs/heads/main\n\n" + blocks.joinToString("") { "$it\n\n" }

    private fun registered(name: String, branch: String? = "worktree-$name", locked: Boolean = false, prunable: Boolean = false) =
        "worktree ${Fixture.forwardRoot("w/acme/.claude/worktrees/$name")}\nHEAD 2222\n" + (branch?.let { "branch refs/heads/$it" } ?: "detached") +
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
        // as toRealPath gives it: the system's own shape, links resolved
        override fun realPath(folder: String): String? = PathRules.canonical(folder).let { real[it] ?: it }

        override fun isRepository(folder: Path): Boolean = true

        override fun holdsClaudeHome(repository: Path): Boolean = repository.toString() in claudeHome

        override fun folders(dir: Path): List<String> = folders[dir.toString()].orEmpty()

        override fun exists(path: Path): Boolean = existing?.contains(path.toString()) ?: true

        override fun gitEntry(folder: Path): GitEntry? = gitEntries[folder.toString()]
    }

    private fun sweep(git: FakeGit, disk: WorktreeSweep.Disk = Disk(), style: PathStyle = PathStyle.current) =
        WorktreeSweep(WorktreeGit(git, style), WorktreeReader(git) { false }, disk, style)

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
                KeptWorktree(repo, wt("held"), "held", KeptKind.Registered("worktree-held", locked = false), WorktreeCounts.NotShown),
                KeptWorktree(repo, wt("kept"), "kept", KeptKind.Registered("worktree-kept", locked = false), keptCounted),
                KeptWorktree(repo, wt("loose"), "loose", KeptKind.Registered(null, locked = false), keptCounted),
                KeptWorktree(repo, wt("stray"), "stray", KeptKind.Unregistered, WorktreeCounts.NotShown),
            ),
            result.kept,
        )
        // git runs in folders it lists only, never in the stray one or the held one a second time
        assertTrue(git.calls.toString(), git.calls.none { it.endsWith("@${wt("stray")}") })
        assertEquals(1, git.calls.count { " status " in " $it" && it.endsWith("@${wt("held")}") })
    }

    /** A folder a thread holds is listed by the look, and left out on the EDT with the threads as they are then. */
    @Test
    fun theSectionLeavesOutWhatAThreadOfThisWindowHolds() {
        val kept = listOf("held", "kept", "cwd", "plain").map { KeptWorktree(repo, wt(it), it, KeptKind.Unregistered, WorktreeCounts.NotShown) }
        val threads = listOf(
            thread("t1", worktree = "held"),
            thread("t2", sessionCwd = Fixture.root("w/acme/.claude/worktrees/cwd/src")),
            thread("t3", folder = wt("plain")),
            thread("t4", worktree = "kept", folder = Fixture.root("w/other")),
        )
        assertEquals(listOf("kept"), KeptWorktrees.visible(kept, threads, emptyMap()).map { it.name })
    }

    @Test
    fun aThreadNamingTheRepositoryByALinkStillHoldsItsWorktree() {
        val link = Fixture.root("link/acme")
        val kept = listOf(KeptWorktree(link, wt("acme"), "acme", KeptKind.Unregistered, WorktreeCounts.NotShown))
        val thread = thread("t1", worktree = "acme", folder = link)
        assertEquals(emptyList<KeptWorktree>(), KeptWorktrees.visible(kept, listOf(thread), mapOf(link to repo)))
        assertEquals(1, KeptWorktrees.visible(kept, listOf(thread), emptyMap()).size)
    }

    @Test
    fun aFailedListingMarksEveryFolderUnknownAndEveryCountAQuestionMark() {
        val git = FakeGit(answers(GitResult(128, "", "fatal: not a git repository\n", false)))
        val result = sweep(git, Disk(folders = mapOf(parent to listOf("kept")))).run(listOf(repo), listOf(thread("t1", worktree = "held")))
        assertEquals(mapOf("t1" to WorktreeCounts.Failed("not a git repository")), result.counts)
        assertEquals(listOf(KeptWorktree(repo, wt("kept"), "kept", KeptKind.Unknown("not a git repository"), WorktreeCounts.Failed("not a git repository"))), result.kept)
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
        val disk = Disk(existing = setOf(wt("stale"), wt("unlisted")))
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
        val link = Fixture.root("link/acme")
        val disk = Disk(folders = mapOf(parent to listOf("a", "b", "c")), real = mapOf(link to repo))
        val result = sweep(git, disk).run(listOf(repo, link + "/"), listOf(thread("t1", worktree = "a", folder = link)))
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
        assertTrue(git.calls.toString(), git.calls.contains("rev-list --count refs/heads/develop..HEAD @${wt("a")}"))
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
            override fun folders(dir: Path): List<String> = if (dir.startsWith(Fixture.path("w/broken"))) error("disk on fire") else listOf("a")
        }
        val broken = Fixture.root("w/broken")
        val result = sweep(git, disk).run(listOf(broken, repo), listOf(thread("t1", worktree = "x", folder = broken)))
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
            override fun exists(path: Path): Boolean = if (path.toString() == wt("b")) error("disk on fire") else true
        }
        assertEquals(emptyList<KeptWorktree>(), sweep(git, disk).run(listOf(repo), emptyList()).kept)
    }

    /** A folder git does not list here but with a `.git` of its own is a worktree linked elsewhere or a repository, never a leftover. */
    @Test
    fun aFolderWithAGitOfItsOwnIsNoLeftover() {
        val git = FakeGit(answers(ok(listing())))
        val disk = Disk(folders = mapOf(parent to listOf("linked", "clone", "stray")), gitEntries = mapOf(wt("linked") to GitEntry.FILE, wt("clone") to GitEntry.FOLDER))
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
        assertTrue(git.calls.toString(), git.calls.none { it.endsWith("@${wt("kept")}") })
    }

    /**
     * Windows on any system: the project names the repository as the IDE does (`C:/w/acme`), the disk gives the real
     * path with backslashes, git lists `C:/w/acme/...` and a thread names it in yet another case. Each is one folder, so
     * the thread gets its counts and every listed folder is a worktree git lists.
     */
    @Test
    fun windowsShapesOfOneFolderAreOneFolder() {
        val style = PathStyle.WINDOWS
        val git = FakeGit(
            answers(
                ok(
                    "worktree C:/w/acme\nHEAD 1111\nbranch refs/heads/main\n\n" +
                        "worktree C:/w/acme/.claude/worktrees/held\nHEAD 2222\nbranch refs/heads/worktree-held\n\n" +
                        "worktree C:/w/acme/.claude/worktrees/Kept\nHEAD 2222\nbranch refs/heads/worktree-kept\nlocked\n\n",
                ),
            ),
        )
        val disk = object : WorktreeSweep.Disk by Disk() {
            override fun realPath(folder: String): String = "C:\\w\\acme"

            override fun folders(dir: Path): List<String> = if (dir.toString() == "C:\\w\\acme\\.claude\\worktrees") listOf("held", "kept", "task-1.", "stray") else emptyList()
        }
        val threads = listOf(thread("t1", worktree = "held", folder = "c:\\W\\Acme\\", base = "main"))
        val result = sweep(git, disk, style).run(listOf("C:/w/acme"), threads)
        assertEquals(mapOf("t1" to counted), result.counts)
        assertEquals(
            listOf(
                KeptWorktree("C:\\w\\acme", "C:\\w\\acme\\.claude\\worktrees\\held", "held", KeptKind.Registered("worktree-held", locked = false), WorktreeCounts.NotShown),
                KeptWorktree("C:\\w\\acme", "C:\\w\\acme\\.claude\\worktrees\\kept", "kept", KeptKind.Registered("worktree-kept", locked = true), keptCounted),
                KeptWorktree("C:\\w\\acme", "C:\\w\\acme\\.claude\\worktrees\\stray", "stray", KeptKind.Unregistered, WorktreeCounts.NotShown),
            ),
            result.kept,
        )
        // one look at the repository, whichever shape named it; `task-1.` is no worktree name on Windows, so no row
        assertEquals(1, git.calls.count { it.startsWith("worktree list") })
        assertEquals(listOf("C:\\w\\acme"), WorktreeWatch.distinct(listOf("C:/w/acme", "c:\\w\\ACME\\"), style) { null }.keys.map { PathRules.canonical(it, style) })
    }

    /**
     * A folder the disk cannot resolve keeps the shape it was named in: a thread naming the repository in another case
     * on Windows still belongs to it and gets its counts.
     */
    @Test
    fun anUnresolvedThreadFolderInAnotherCaseIsTheSameRepositoryOnWindows() {
        val git = FakeGit(answers(ok("worktree C:/w/acme\nHEAD 1111\nbranch refs/heads/main\n\nworktree C:/w/acme/.claude/worktrees/held\nHEAD 2222\nbranch refs/heads/worktree-held\n\n")))
        val disk = object : WorktreeSweep.Disk by Disk() {
            override fun realPath(folder: String): String? = null
        }
        val result = sweep(git, disk, PathStyle.WINDOWS).run(listOf("C:/w/acme"), listOf(thread("t1", worktree = "held", folder = "c:\\W\\ACME", base = "main")))
        assertEquals(mapOf("t1" to counted), result.counts)
        assertEquals(1, git.calls.count { it.startsWith("worktree list") })
    }

    /** A Windows status file's cwd, an IDE folder and the section's row each in their own shape hold the folder all the same. */
    @Test
    fun aThreadHoldsItsWorktreeInAnyWindowsShape() {
        val style = PathStyle.WINDOWS
        val dir = "C:\\w\\acme\\.claude\\worktrees\\task-1"
        val none: (String) -> String? = { null }
        fun held(thread: SkeinThread) = KeptWorktrees.held(dir, listOf(thread), none, style)
        assertTrue(held(thread("t1", worktree = "task-1", folder = "C:/w/acme")))
        assertTrue(held(thread("t1", worktree = "Task-1", folder = "c:\\w\\ACME")))
        assertTrue(held(thread("t2", folder = "C:/w/other", sessionCwd = "C:\\w\\acme\\.claude\\worktrees\\task-1\\src")))
        assertTrue(held(thread("t3", folder = "C:/w/acme/.claude/worktrees/task-1/src")))
        assertFalse(held(thread("t4", worktree = "task-10", folder = "C:/w/acme")))
        assertFalse(held(thread("t5", folder = "C:/w/other", sessionCwd = "C:\\w\\acme\\.claude\\worktrees\\task-10")))
        assertFalse(held(thread("t6", worktree = "task-1", folder = "D:/w/acme")))
        // on Unix the case counts
        assertFalse(KeptWorktrees.held("/w/acme/.claude/worktrees/task-1", listOf(thread("t1", worktree = "Task-1", folder = "/w/ACME")), none, PathStyle.UNIX))
    }

    /**
     * Whether a folder is held keeps it from going, so a place the text cannot place for sure holds it: a `..` on the
     * way, or on Windows a name Win32 would trim. An empty one names no place.
     */
    @Test
    fun aPlaceThatMayLieInTheFolderHoldsIt() {
        val none: (String) -> String? = { null }
        val dir = "/w/acme/.claude/worktrees/task-1"
        fun cwd(cwd: String) = listOf(thread("t1", folder = "/w/other", sessionCwd = cwd))
        assertTrue(KeptWorktrees.held(dir, cwd("/w/acme/.claude/worktrees/task-1/sub/.."), none, PathStyle.UNIX))
        assertTrue(KeptWorktrees.held(dir, cwd("/w/acme/.claude/worktrees/x/../task-1"), none, PathStyle.UNIX))
        assertFalse(KeptWorktrees.held(dir, cwd("/w/acme/.claude/worktrees/task-10"), none, PathStyle.UNIX))
        assertFalse(KeptWorktrees.held(dir, listOf(thread("t1", folder = "", sessionCwd = " ")), { "" }, PathStyle.UNIX))
        val windows = "C:\\w\\acme\\.claude\\worktrees\\task-1"
        assertTrue(KeptWorktrees.held(windows, listOf(thread("t1", folder = "C:/w/other", sessionCwd = "C:\\w\\acme\\.claude\\worktrees\\task-1.")), none, PathStyle.WINDOWS))
        assertFalse(KeptWorktrees.held(windows, listOf(thread("t1", folder = "C:/w/other", sessionCwd = "C:\\w\\acme\\.claude\\worktrees\\task-2")), none, PathStyle.WINDOWS))
        assertTrue(KeptWorktrees.held(windows, listOf(thread("t1", folder = "C:/w/acme", worktree = "task-1.")), none, PathStyle.WINDOWS))
    }

    /** A claude's cwd on a subst drive (or through a junction) holds the folder by its real path. */
    @Test
    fun aSessionCwdHoldsTheFolderByItsRealPathToo() {
        val dir = "C:\\work\\acme\\.claude\\worktrees\\task-1"
        val thread = thread("t1", folder = "C:/w/other", sessionCwd = "W:\\acme\\.claude\\worktrees\\task-1\\src")
        val real: (String) -> String? = { if (it.startsWith("W:")) "C:\\work" + it.removePrefix("W:") else null }
        assertFalse(KeptWorktrees.held(dir, listOf(thread), { null }, PathStyle.WINDOWS))
        assertTrue(KeptWorktrees.held(dir, listOf(thread), real, PathStyle.WINDOWS))
        assertFalse(KeptWorktrees.held("C:\\work\\acme\\.claude\\worktrees\\task-2", listOf(thread), real, PathStyle.WINDOWS))
        // the cwd as written still counts when its real path says somewhere else
        val inside = thread("t2", folder = "C:/w/other", sessionCwd = "C:\\work\\acme\\.claude\\worktrees\\task-1\\src")
        assertTrue(KeptWorktrees.held(dir, listOf(inside), { "D:\\elsewhere" }, PathStyle.WINDOWS))
    }

    /**
     * A subst drive is no reparse point, so toRealPath keeps `W:`: the disk's own word ([SameFolder]) holds the folder
     * then, for a claude's cwd only.
     */
    @Test
    fun aSessionCwdHoldsTheFolderByTheDisksWord() {
        val dir = "C:\\work\\acme\\.claude\\worktrees\\task-1"
        val cwd = "W:\\acme\\.claude\\worktrees\\task-1\\src"
        val none: (String) -> String? = { null }
        val same = { d: String, c: String -> d == dir && c == cwd }
        assertTrue(KeptWorktrees.held(dir, listOf(thread("t1", folder = "C:/w/other", sessionCwd = cwd)), none, PathStyle.WINDOWS, same))
        assertFalse(KeptWorktrees.held(dir, listOf(thread("t1", folder = "C:/w/other", sessionCwd = cwd)), none, PathStyle.WINDOWS))
        // a thread's folder is no session cwd: the disk is not asked for it
        assertFalse(KeptWorktrees.held(dir, listOf(thread("t1", folder = cwd)), none, PathStyle.WINDOWS, same))
        // the sweep asks its disk
        val git = FakeGit(answers(ok(listing(registered("held")))))
        val disk = object : WorktreeSweep.Disk by Disk(folders = mapOf(parent to listOf("held"))) {
            override fun sameFolder(dir: String, cwd: String): Boolean = dir == wt("held") && cwd == Fixture.root("v/elsewhere")
        }
        val result = sweep(git, disk).run(listOf(repo), listOf(thread("t1", folder = Fixture.root("w/other"), sessionCwd = Fixture.root("v/elsewhere"))))
        assertEquals(WorktreeCounts.NotShown, result.kept.single().counts)
    }

    /** isSameFile is asked for the cwd and each folder above it; a look that throws is no answer, and the walk goes on. */
    @Test
    fun theDisksWordComesFromTheCwdOrAFolderAboveIt() {
        val dir = Fixture.root("w/acme/.claude/worktrees/task-1")
        val cwd = Fixture.root("v/acme/.claude/worktrees/task-1/src/deep")
        val asked = mutableListOf<String>()
        val answers: (Path, Path) -> Boolean = { a, b ->
            asked += b.toString()
            when (b) {
                Fixture.path("v/acme/.claude/worktrees/task-1/src/deep") -> throw java.nio.file.NoSuchFileException(b.toString())
                Fixture.path("v/acme/.claude/worktrees/task-1/src") -> false
                Fixture.path("v/acme/.claude/worktrees/task-1") -> a == Path.of(dir)
                else -> false
            }
        }
        assertTrue(SameFolder.holds(dir, cwd, answers))
        assertEquals(3, asked.size)
        assertFalse(SameFolder.holds(dir, Fixture.root("v/other"), answers))
        assertFalse(SameFolder.holds(dir, cwd) { _, _ -> throw java.io.IOException("unreadable") })
        // the real disk: a folder is the same file as itself reached by another text
        val real = Fixture.tempDirectory("same-folder")
        try {
            val inside = Files.createDirectories(real.resolve("wt/src"))
            assertTrue(SameFolder.holds(real.resolve("wt").toString(), inside.resolve("../src").toString()))
            assertFalse(SameFolder.holds(real.resolve("other").toString(), inside.toString()))
        } finally {
            Files.walk(real).sorted(Comparator.reverseOrder()).forEach(Files::delete)
        }
    }
}
