package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.SessionSnapshot
import io.github.v0rest.skein.git.GitResult
import io.github.v0rest.skein.git.GitRunner
import io.github.v0rest.skein.git.WorktreeGit
import io.github.v0rest.skein.git.WorktreeReader
import io.github.v0rest.skein.process.JdkProcessProbe
import io.github.v0rest.skein.process.ProcessProbe
import io.github.v0rest.skein.threads.KeptKind
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import io.github.v0rest.skein.threads.WorktreeClose
import io.github.v0rest.skein.threads.WorktreeSweep
import io.github.v0rest.skein.testutil.RealGit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * The Worktrees section's Remove… ([WorktreeCloseFlow.removeKept]) against the real git and a real disk, with the
 * flow's own checks (only the editors and the Claude home are stubbed): the cases where a recursive delete or git's
 * force would lose committed work. Skipped when git is not installed.
 */
class WorktreeRemoveRealGitTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var git: RealGit
    private val events = mutableListOf<String>()
    private val reports = mutableListOf<String>()

    @Before
    fun gitIsThere() {
        git = RealGit(folder)
        assumeTrue(git.available)
    }

    private fun ok(dir: Path, vararg args: String): GitResult = git.run(dir, *args).also { assertTrue(args.joinToString(" ") + ": " + it.stderr, it.ok) }

    /** A repository `acme` on `main` with the worktree `.claude/worktrees/x` on `worktree-x`; the worktree's folder. */
    private fun repositoryWithWorktree(repo: Path = git.repository("acme").toRealPath()): Path {
        ok(repo, "branch", "-M", "main")
        ok(repo, "worktree", "add", "-q", "-b", "worktree-x", "--", ".claude/worktrees/x")
        return repo.resolve(".claude/worktrees/x")
    }

    private fun flow(runner: GitRunner = git, probe: ProcessProbe = JdkProcessProbe(), remove: Boolean = true, force: Boolean = true) = WorktreeCloseFlow(
        git = WorktreeGit(runner),
        snapshots = MutableStateFlow(SessionSnapshot.EMPTY),
        ask = { _, _ ->
            events += "ask"
            remove
        },
        confirmForce = { _, _ ->
            events += "force?"
            force
        },
        confirmDelete = { _, _ ->
            events += "delete?"
            true
        },
        report = { text, _ -> reports += text },
        unsavedEdits = { false },
        insideClaudeHome = { false },
        probe = probe,
    )

    private fun remove(repo: Path, name: String = "x") = runBlocking { flow().removeKept(repo.toString(), name) }

    /**
     * The repository moved: git still names the worktree under the old path (prunable there), and the folder under the
     * new one keeps its `.git` file and the work in it. It is a worktree, not a leftover, and nothing deletes it.
     */
    @Test
    fun aWorktreeOfAMovedRepositoryIsNeverDeletedAsALeftover() {
        val wt = repositoryWithWorktree()
        Files.writeString(wt.resolve("notes.txt"), "work\n")
        val moved = folder.root.toPath().toRealPath().resolve("moved")
        Files.move(wt.parent.parent.parent, moved)
        val look = WorktreeSweep(WorktreeGit(git), WorktreeReader(git), WorktreeSweep.RealDisk).run(listOf(moved.toString()), emptyList())
        assertEquals(listOf(KeptKind.Unlinked(ownRepository = false)), look.kept.map { it.kind })
        remove(moved)
        assertTrue(reports.toString(), Files.exists(moved.resolve(".claude/worktrees/x/notes.txt")))
        assertTrue(Files.exists(moved.resolve(".claude/worktrees/x/.git")))
        assertFalse(events.toString(), "delete?" in events)
        assertEquals(1, reports.size)
        assertTrue(reports.single(), reports.single().contains("git worktree repair"))
    }

    /** git refuses a worktree whose submodule lives under its git dir; its force would delete those commits. */
    @Test
    fun aWorktreeWithASubmoduleIsNeverForced() {
        val sub = git.repository("sub")
        val repo = git.repository("acme").toRealPath()
        ok(repo, "-c", "protocol.file.allow=always", "submodule", "add", "-q", "--", sub.toString(), "lib")
        ok(repo, "commit", "-q", "-m", "add lib")
        val wt = repositoryWithWorktree(repo)
        ok(wt, "-c", "protocol.file.allow=always", "submodule", "update", "--init", "-q")
        remove(repo)
        assertFalse(events.toString(), "force?" in events)
        assertTrue(Files.isDirectory(wt))
        assertEquals(1, reports.size)
        assertTrue(reports.single(), reports.single().contains("submodules"))
    }

    /** A repository of its own inside the worktree makes git refuse as for any untracked file, and its force would delete it. */
    @Test
    fun aWorktreeHoldingARepositoryOfItsOwnIsNeverForced() {
        val wt = repositoryWithWorktree()
        val nested = Files.createDirectories(wt.resolve("vendor/lib"))
        ok(nested, "init", "-q")
        Files.writeString(nested.resolve("lib.c"), "int lib;\n")
        ok(nested, "add", "-A")
        ok(nested, "commit", "-q", "-m", "lib")
        remove(wt.parent.parent.parent)
        assertEquals(emptyList<String>(), events)
        assertTrue(Files.exists(nested.resolve(".git")))
        assertEquals(listOf("Worktree x was not removed: " + WorktreeClose.innerRepositories(listOf("vendor/lib/"), wt.toString())), reports)
    }

    /** What the force is for: changed and untracked files, after the second yes. */
    @Test
    fun aDirtyWorktreeIsForcedAfterTheSecondYes() {
        val wt = repositoryWithWorktree()
        Files.writeString(wt.resolve("notes.txt"), "scratch\n")
        remove(wt.parent.parent.parent)
        assertEquals(listOf("ask", "force?"), events)
        assertFalse(Files.exists(wt))
        assertEquals(listOf("Worktree x and its branch worktree-x were removed"), reports)
    }

    /** A clone or a copy under the worktrees folder that git does not list keeps its `.git` and is never deleted. */
    @Test
    fun aFolderWithAGitEntryIsNeverDeletedAsALeftover() {
        val repo = git.repository("acme").toRealPath()
        val copy = Files.createDirectories(repo.resolve(".claude/worktrees/copy"))
        Files.writeString(copy.resolve(".git"), "gitdir: /w/elsewhere/.git/worktrees/copy\n")
        Files.writeString(copy.resolve("work.txt"), "work\n")
        remove(repo, "copy")
        assertTrue(Files.exists(copy.resolve("work.txt")))
        assertFalse("delete?" in events)
        assertEquals(1, reports.size)
    }

    /** A clone with a commit of its own in an ignored folder of the worktree: git status cannot see it, the plain remove deletes it. */
    private fun ignoredClone(wt: Path): Path {
        val clone = Files.createDirectories(wt.resolve("vendor/lib"))
        ok(clone, "init", "-q")
        Files.writeString(clone.resolve("lib.c"), "int lib;\n")
        ok(clone, "add", "-A")
        ok(clone, "commit", "-q", "-m", "lib")
        return clone
    }

    @Test
    fun anIgnoredCloneInsideBlocksThePlainRemove() {
        val wt = repositoryWithWorktree(git.repository("acme", gitignore = listOf("vendor/")).toRealPath())
        val clone = ignoredClone(wt)
        remove(wt.parent.parent.parent)
        assertTrue(Files.exists(clone.resolve(".git")))
        assertEquals(emptyList<String>(), events)
        assertTrue(reports.toString(), reports.single().contains("vendor/lib/"))
    }

    @Test
    fun anIgnoredCloneInsideBlocksTheForce() {
        val wt = repositoryWithWorktree(git.repository("acme", gitignore = listOf("vendor/")).toRealPath())
        val clone = ignoredClone(wt)
        Files.writeString(wt.resolve("notes.txt"), "scratch\n")
        remove(wt.parent.parent.parent)
        assertTrue(Files.exists(clone.resolve(".git")))
        assertFalse(events.toString(), "force?" in events || "ask" in events)
    }

    /** The tab-close question keeps the worktree as well. */
    @Test
    fun closingKeepsAWorktreeWithAnIgnoredCloneInside() {
        val repo = git.repository("acme", gitignore = listOf("vendor/")).toRealPath()
        val wt = repositoryWithWorktree(repo)
        val clone = ignoredClone(wt)
        val thread = SkeinThread("55555555-5555-4555-8555-555555555555", repo.toString(), null, "x", ThreadState.Exited, 0, 0, null, worktree = "x")
        runBlocking { flow().run(thread) }
        assertTrue(Files.exists(clone.resolve(".git")))
        assertEquals(emptyList<String>(), events)
        assertTrue(reports.toString(), reports.single().startsWith("Worktree x is kept: ") && reports.single().contains("vendor/lib/"))
    }

    /** Ignored build output alone is no reason to keep a worktree: the question says it goes, and it goes. */
    @Test
    fun ignoredBuildOutputAloneStillRemovesAfterTheQuestion() {
        val wt = repositoryWithWorktree(git.repository("acme", gitignore = listOf("build/")).toRealPath())
        Files.createDirectories(wt.resolve("build/classes"))
        Files.writeString(wt.resolve("build/classes/a.o"), "o\n")
        remove(wt.parent.parent.parent)
        assertEquals(listOf("ask"), events)
        assertFalse(Files.exists(wt))
        assertEquals(listOf("Worktree x and its branch worktree-x were removed"), reports)
    }

    /** The look for repositories inside does not finish (a huge ignored tree, say): nothing is offered. */
    @Test
    fun aLookForRepositoriesInsideThatTimesOutOffersNothing() {
        val wt = repositoryWithWorktree()
        val slow = object : GitRunner {
            override fun run(dir: Path, vararg args: String): GitResult =
                if ("--ignored=traditional" in args) GitResult(-1, "", "", true) else git.run(dir, *args)
        }
        runBlocking { flow(slow).removeKept(wt.parent.parent.parent.toString(), "x") }
        assertTrue(Files.isDirectory(wt))
        assertEquals(emptyList<String>(), events)
        assertTrue(reports.toString(), reports.single().contains(GitResult.TIMED_OUT))
    }

    /** A leftover folder with a clone one level down holds commits too: it is not deleted. */
    @Test
    fun aLeftoverWithACloneInsideIsNotDeleted() {
        val repo = git.repository("acme").toRealPath()
        val clone = Files.createDirectories(repo.resolve(".claude/worktrees/stray/lib"))
        ok(clone, "init", "-q")
        Files.writeString(clone.resolve("lib.c"), "int lib;\n")
        ok(clone, "add", "-A")
        ok(clone, "commit", "-q", "-m", "lib")
        remove(repo, "stray")
        assertTrue(Files.exists(clone.resolve("lib.c")))
        assertFalse(events.toString(), "delete?" in events)
        assertEquals(1, reports.size)
    }

    /** A leftover folder with a folder inside that cannot be read could hide a repository: nothing is offered. */
    @Test
    fun aLeftoverWithAnUnreadableFolderInsideIsNotDeleted() {
        val repo = git.repository("acme").toRealPath()
        val locked = Files.createDirectories(repo.resolve(".claude/worktrees/stray/locked"))
        Files.writeString(locked.resolve("x.c"), "int x;\n")
        io.github.v0rest.skein.git.withUnreadable(locked) {
            remove(repo, "stray")
            assertFalse(events.toString(), "delete?" in events)
            assertEquals(1, reports.size)
        }
        assertTrue(Files.exists(locked.resolve("x.c")))
    }

    /** `git worktree lock` with [reason], as Claude Code locks the worktree it makes. */
    private fun lock(wt: Path, reason: String) = ok(wt.parent.parent.parent, "worktree", "lock", "--reason", reason, "--", wt.toString())

    /** The lock's reason as `git worktree list --porcelain` shows it, or null when the worktree is not locked. */
    private fun lockOf(wt: Path): String? {
        val entry = WorktreeGit(git).list(wt.parent.parent.parent).single { it.path == wt.toString() }
        return if (entry.locked) entry.lockReason.orEmpty() else null
    }

    /** Claude Code's lock reason for [pid], with [start] written as `ps` writes it, in [zone] (the system's by default). */
    private fun claudeReason(pid: Long, start: Instant, zone: ZoneId = ZoneId.systemDefault()) =
        "claude session x (pid $pid start ${DateTimeFormatter.ofPattern("EEE MMM ppd HH:mm:ss uuuu", Locale.US).format(start.atZone(zone))})"

    /** The pid of a process that has ended. */
    private fun deadPid(): Long {
        val process = ProcessBuilder("true").start()
        assertTrue(process.waitFor(30, TimeUnit.SECONDS))
        assertFalse(ProcessHandle.of(process.pid()).map { it.isAlive }.orElse(false))
        return process.pid()
    }

    /** A live `sleep` and its start, or the test is skipped when the system does not say when it started. */
    private fun <T> withSleep(block: (pid: Long, start: Instant) -> T): T {
        val sleep = ProcessBuilder("sleep", "60").start()
        try {
            val start = JdkProcessProbe().startInstant(sleep.pid())
            assumeTrue(start != null)
            return block(sleep.pid(), start!!)
        } finally {
            sleep.destroy()
            sleep.waitFor(30, TimeUnit.SECONDS)
        }
    }

    /** The probe as Skein's, but [claudePid] runs `claude`: the live process stands in for the claude that locked. */
    private fun claudeAt(claudePid: Long): ProcessProbe = object : ProcessProbe by JdkProcessProbe() {
        override fun command(pid: Long): List<String> = if (pid == claudePid) listOf("/w/bin/claude", "--worktree", "x") else JdkProcessProbe().command(pid)
    }

    /** Claude Code's lock with its claude gone, as the owner found it: the yes lifts it, and git removes the worktree. */
    @Test
    fun aStaleClaudeCodeLockIsLiftedAndTheWorktreeRemovedAfterTheQuestion() {
        val wt = repositoryWithWorktree()
        lock(wt, claudeReason(deadPid(), Instant.parse("2026-11-02T08:30:41Z")))
        remove(wt.parent.parent.parent)
        assertEquals(listOf("ask"), events)
        assertFalse(Files.exists(wt))
        assertEquals(listOf("Worktree x and its branch worktree-x were removed"), reports)
    }

    @Test
    fun cancelLeavesTheStaleLock() {
        val wt = repositoryWithWorktree()
        val reason = claudeReason(deadPid(), Instant.parse("2026-11-02T08:30:41Z"))
        lock(wt, reason)
        runBlocking { flow(remove = false).removeKept(wt.parent.parent.parent.toString(), "x") }
        assertEquals(listOf("ask"), events)
        assertEquals(reason, lockOf(wt))
        assertTrue(Files.isDirectory(wt))
    }

    /** A live process stands in for the claude that locked: Skein refuses before any question and the lock stays. */
    @Test
    fun aLiveClaudeKeepsItsLockAndItsWorktree() {
        val wt = repositoryWithWorktree()
        withSleep { pid, start ->
            val reason = claudeReason(pid, start)
            lock(wt, reason)
            runBlocking { flow(probe = claudeAt(pid)).removeKept(wt.parent.parent.parent.toString(), "x") }
            assertEquals(emptyList<String>(), events)
            assertEquals(listOf("Worktree x was not removed: Claude Code's lock on it names pid $pid, which still runs and may be the claude that locked it"), reports)
            assertEquals(reason, lockOf(wt))
            assertTrue(Files.isDirectory(wt))
        }
    }

    /** The pid lives, but `sleep` holds it and not claude: the lock is stale, and the worktree goes after the question. */
    @Test
    fun aPidAnotherProgramHoldsNowDoesNotKeepTheLock() {
        val wt = repositoryWithWorktree()
        withSleep { pid, start ->
            // three days before the `sleep` started, in UTC and in the system's zone alike
            lock(wt, claudeReason(pid, start.minusSeconds(3 * 86_400)))
            remove(wt.parent.parent.parent)
        }
        assertEquals(listOf("ask"), events)
        assertFalse(Files.exists(wt))
    }

    /**
     * The pid lives and started when the lock says, written in the system's zone or in UTC as Claude Code was seen to
     * write it: that start vouches, though `sleep` is no command Skein knows as claude, and nothing is lifted.
     */
    @Test
    fun aMatchingStartKeepsTheLockWhateverTheCommand() {
        for (zone in listOf(ZoneId.systemDefault(), java.time.ZoneOffset.UTC)) {
            val wt = repositoryWithWorktree(git.repository("acme-$zone".replace('/', '-')).toRealPath())
            events.clear()
            reports.clear()
            withSleep { pid, start ->
                val reason = claudeReason(pid, start, zone)
                lock(wt, reason)
                remove(wt.parent.parent.parent)
                assertEquals(zone.toString(), emptyList<String>(), events)
                assertEquals(zone.toString(), listOf("Worktree x was not removed: Claude Code's lock on it names pid $pid, which still runs and may be the claude that locked it"), reports)
                assertEquals(reason, lockOf(wt))
            }
            assertTrue(Files.isDirectory(wt))
        }
    }

    /** A lock someone else made: git refuses, Skein shows it, and the lock and the worktree stay. */
    @Test
    fun aForeignLockIsRefusedAndStays() {
        val wt = repositoryWithWorktree()
        lock(wt, "on a stick")
        remove(wt.parent.parent.parent)
        assertEquals(listOf("ask"), events)
        assertEquals("on a stick", lockOf(wt))
        assertTrue(Files.isDirectory(wt))
        assertTrue(reports.single(), reports.single().startsWith("Worktree x was not removed: fatal: cannot remove a locked working tree"))
    }

    /** Lifted, then git refuses for the untracked file: the force only after the second yes. */
    @Test
    fun aStaleLockWithUntrackedFilesIsLiftedThenForcedOnlyAfterTheSecondYes() {
        val wt = repositoryWithWorktree()
        Files.writeString(wt.resolve("notes.txt"), "scratch\n")
        lock(wt, claudeReason(deadPid(), Instant.parse("2026-11-02T08:30:41Z")))
        remove(wt.parent.parent.parent)
        assertEquals(listOf("ask", "force?"), events)
        assertFalse(Files.exists(wt))
        assertEquals(listOf("Worktree x and its branch worktree-x were removed"), reports)
    }

    /** No to the force: the worktree and its file stay, and the lock stays lifted, since nothing held it. */
    @Test
    fun noToTheForceKeepsTheWorktreeUnlocked() {
        val wt = repositoryWithWorktree()
        Files.writeString(wt.resolve("notes.txt"), "scratch\n")
        lock(wt, claudeReason(deadPid(), Instant.parse("2026-11-02T08:30:41Z")))
        runBlocking { flow(force = false).removeKept(wt.parent.parent.parent.toString(), "x") }
        assertEquals(listOf("ask", "force?"), events)
        assertTrue(Files.exists(wt.resolve("notes.txt")))
        assertEquals(null, lockOf(wt))
    }

    /** The tab's close: Remove lifts the lock its own claude left, and a refusal keeps the worktree, unlocked, and says so. */
    @Test
    fun closingATabLiftsTheStaleLockOnRemove() {
        val repo = git.repository("acme").toRealPath()
        val wt = repositoryWithWorktree(repo)
        lock(wt, claudeReason(deadPid(), Instant.parse("2026-11-02T08:30:41Z")))
        val thread = SkeinThread("55555555-5555-4555-8555-555555555555", repo.toString(), null, "x", ThreadState.Exited, 0, 0, null, worktree = "x")
        runBlocking { flow().run(thread) }
        assertEquals(listOf("ask"), events)
        assertFalse(Files.exists(wt))
        assertEquals(listOf("Worktree x and its branch worktree-x were removed"), reports)
    }

    @Test
    fun closingATabWithUntrackedFilesLiftsTheLockButNeverForces() {
        val repo = git.repository("acme").toRealPath()
        val wt = repositoryWithWorktree(repo)
        Files.writeString(wt.resolve("notes.txt"), "scratch\n")
        lock(wt, claudeReason(deadPid(), Instant.parse("2026-11-02T08:30:41Z")))
        val thread = SkeinThread("55555555-5555-4555-8555-555555555555", repo.toString(), null, "x", ThreadState.Exited, 0, 0, null, worktree = "x")
        runBlocking { flow().run(thread) }
        assertEquals(listOf("ask"), events)
        assertTrue(Files.exists(wt.resolve("notes.txt")))
        assertEquals(null, lockOf(wt))
        assertTrue(reports.single(), reports.single().startsWith(WorktreeCloseText.notRemovedLockLifted("x", "")))
    }
}
