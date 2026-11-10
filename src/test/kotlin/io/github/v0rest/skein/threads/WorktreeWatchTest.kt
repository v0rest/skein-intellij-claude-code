package io.github.v0rest.skein.threads

import io.github.v0rest.skein.git.ExcludeResult
import io.github.v0rest.skein.threads.WorktreeWatch.Balloon
import io.github.v0rest.skein.threads.WorktreeWatch.Outcome
import org.junit.Assert.assertEquals
import org.junit.Test
import java.nio.file.Path

/** What the minute check of the worktree threads' repositories looks at and what it says. */
class WorktreeWatchTest {
    private fun thread(id: String, folder: String, worktree: String? = null) =
        SkeinThread(id, folder, null, id, ThreadState.NotStarted, 0, 0, null, worktree = worktree)

    private val acme = "/w/acme"

    private fun ok(line: Boolean, guard: Boolean) =
        ExcludeResult.Ok(line, guard, Path.of("$acme/.git/info/exclude"), Path.of("$acme/.claude/worktrees/.gitignore"))

    @Test
    fun onlyWorktreeThreadsCountOncePerRepository() {
        val threads = listOf(
            thread("1", acme, worktree = "task-1"),
            thread("2", "/w/beta"),
            thread("3", "/w/acme/", worktree = "task-2"),
            thread("4", "/links/acme", worktree = "task-3"),
            thread("5", "/w/gamma", worktree = "task-1"),
            thread("6", "/w/gone", worktree = "task-1"),
        )
        val real = mapOf(acme to acme, "/w/acme/" to acme, "/links/acme" to acme, "/w/gamma" to "/w/gamma")
        assertEquals(
            mapOf(acme to acme, "/w/gamma" to "/w/gamma", "/w/gone" to "/w/gone"),
            WorktreeWatch.repositories(threads) { real[it] },
        )
        assertEquals(emptyMap<String, String>(), WorktreeWatch.repositories(listOf(thread("2", "/w/beta"))) { it })
    }

    /** The minute check says what it put back, and nothing when the guard and the line were there. */
    @Test
    fun aGuardPutBackIsSaid() {
        assertEquals(
            Outcome(Balloon.Info("Skein put back .claude/worktrees/.gitignore so git keeps ignoring the worktrees in /w/acme."), null),
            WorktreeWatch.outcome(acme, ok(line = false, guard = true), periodic = true, lastWarned = null),
        )
        assertEquals(
            Outcome(
                Balloon.Info("Skein put back .claude/worktrees/.gitignore and the line /.claude/worktrees/ in .git/info/exclude so git keeps ignoring the worktrees in /w/acme."),
                null,
            ),
            WorktreeWatch.outcome(acme, ok(line = true, guard = true), periodic = true, lastWarned = null),
        )
        assertEquals(
            Outcome(Balloon.Info("Skein put back the line /.claude/worktrees/ in .git/info/exclude so git keeps ignoring the worktrees in /w/acme."), null),
            WorktreeWatch.outcome(acme, ok(line = true, guard = false), periodic = true, lastWarned = null),
        )
        assertEquals(Outcome(null, null), WorktreeWatch.outcome(acme, ok(line = false, guard = false), periodic = true, lastWarned = "busy"))
    }

    /** A failure that lasts is said once, a new reason again, and once the check holds the next failure is said again. */
    @Test
    fun theMinuteCheckWarnsOncePerReason() {
        val warning = Balloon.Warning("Skein could not keep the worktrees of /w/acme out of git, so git add -A could pick the worktree up: busy")
        assertEquals(Outcome(warning, "busy"), WorktreeWatch.outcome(acme, ExcludeResult.Failed("busy"), periodic = true, lastWarned = null))
        assertEquals(Outcome(null, "busy"), WorktreeWatch.outcome(acme, ExcludeResult.Failed("busy"), periodic = true, lastWarned = "busy"))
        assertEquals(
            Outcome(Balloon.Warning("Skein could not keep the worktrees of /w/acme out of git, so git add -A could pick the worktree up: locked"), "locked"),
            WorktreeWatch.outcome(acme, ExcludeResult.Failed("locked"), periodic = true, lastWarned = "busy"),
        )
    }

    /**
     * The repositories as a tick sees them: [real] gives the real paths, [repositories] the folders that are still
     * repositories, [home] the one holding the Claude home, [results] what each check returns; [ensured] records the checks.
     */
    private class FakeChecks(
        val real: Map<String, String>,
        val repositories: Set<String>,
        val home: String? = null,
        val results: MutableMap<String, ExcludeResult> = mutableMapOf(),
    ) : WorktreeWatch.Checks {
        val ensured = mutableListOf<String>()

        override fun realPath(folder: String): String? = real[folder]

        override fun isRepository(folder: Path): Boolean = folder.toString() in repositories

        override fun holdsClaudeHome(repository: Path): Boolean = repository.toString() == home

        override fun ensure(repository: Path): ExcludeResult {
            ensured += repository.toString()
            return results.getValue(repository.toString())
        }
    }

    private val guardBack = Balloon.Info("Skein put back .claude/worktrees/.gitignore so git keeps ignoring the worktrees in /w/acme.")

    /** One tick checks each repository of the worktree threads once, in the minute check's words, and skips what it must. */
    @Test
    fun aTickChecksEachRepositoryOnce() {
        val threads = listOf(
            thread("1", acme, worktree = "task-1"),
            thread("2", "/links/acme", worktree = "task-2"),
            thread("3", "/w/beta"),
            thread("4", "/w/gone", worktree = "task-1"),
            thread("5", "/w/home", worktree = "task-1"),
            thread("6", "/w/gamma", worktree = "task-1"),
        )
        val checks = FakeChecks(
            real = mapOf(acme to acme, "/links/acme" to acme, "/w/beta" to "/w/beta", "/w/home" to "/w/home", "/w/gamma" to "/w/gamma"),
            repositories = setOf(acme, "/w/beta", "/w/home", "/w/gamma"),
            home = "/w/home",
            results = mutableMapOf(acme to ok(line = false, guard = true), "/w/gamma" to ExcludeResult.Failed("busy")),
        )
        val warnings = mutableMapOf<String, String>()
        assertEquals(
            listOf(guardBack, Balloon.Warning("Skein could not keep the worktrees of /w/gamma out of git, so git add -A could pick the worktree up: busy")),
            WorktreeWatch.tick(threads, checks, warnings),
        )
        assertEquals(listOf(acme, "/w/gamma"), checks.ensured)
        assertEquals(mapOf("/w/gamma" to "busy"), warnings)
    }

    /** A check that throws is reported for its own repository and does not stop the others. */
    @Test
    fun aCheckThatThrowsStopsOnlyItsOwnRepository() {
        val threads = listOf(thread("1", "/w/gamma", worktree = "task-1"), thread("2", acme, worktree = "task-1"))
        val checks = object : WorktreeWatch.Checks {
            val ensured = mutableListOf<String>()

            override fun realPath(folder: String): String = folder

            override fun isRepository(folder: Path): Boolean = true

            override fun holdsClaudeHome(repository: Path): Boolean = false

            override fun ensure(repository: Path): ExcludeResult {
                ensured += repository.toString()
                if (repository.toString() == "/w/gamma") throw IllegalStateException("broken")
                return ok(line = false, guard = true)
            }
        }
        val warnings = mutableMapOf<String, String>()
        assertEquals(
            listOf(Balloon.Warning("Skein could not keep the worktrees of /w/gamma out of git, so git add -A could pick the worktree up: broken"), guardBack),
            WorktreeWatch.tick(threads, checks, warnings),
        )
        assertEquals(listOf("/w/gamma", acme), checks.ensured)
    }

    /**
     * Everything a check does for one repository is isolated: a repository whose `isRepository` throws gets its warning
     * and the next one is still checked; a real path that throws counts as none.
     */
    @Test
    fun aRepositoryThatThrowsAnywhereStopsOnlyItself() {
        val threads = listOf(thread("1", "/w/gamma", worktree = "task-1"), thread("2", "/w/delta", worktree = "task-1"), thread("3", acme, worktree = "task-1"))
        val checks = object : WorktreeWatch.Checks {
            val ensured = mutableListOf<String>()

            override fun realPath(folder: String): String = if (folder == "/w/delta") throw IllegalStateException("unreadable") else folder

            override fun isRepository(folder: Path): Boolean = if (folder.toString() == "/w/gamma") throw IllegalStateException("broken") else true

            override fun holdsClaudeHome(repository: Path): Boolean = false

            override fun ensure(repository: Path): ExcludeResult {
                ensured += repository.toString()
                return ok(line = false, guard = true)
            }
        }
        val warnings = mutableMapOf<String, String>()
        val balloons = WorktreeWatch.tick(threads, checks, warnings)
        assertEquals(Balloon.Warning("Skein could not keep the worktrees of /w/gamma out of git, so git add -A could pick the worktree up: broken"), balloons.first())
        assertEquals(listOf("/w/delta", acme), checks.ensured)
        assertEquals(3, balloons.size)
        assertEquals(mapOf("/w/gamma" to "broken"), warnings)
    }

    /** A resume that kept the guard says so; the tick after it finds the guard there and the same reason is not shown again. */
    @Test
    fun aKeptGuardDoesNotWarnTwice() {
        val guard = Path.of("$acme/.claude/worktrees/.gitignore")
        val checks = FakeChecks(
            real = mapOf(acme to acme),
            repositories = setOf(acme),
            results = mutableMapOf(acme to ExcludeResult.Failed("git already tracks .claude/worktrees/.gitkeep", keptGuard = guard)),
        )
        val warnings = mutableMapOf<String, String>()
        val resumed = WorktreeWatch.check(WorktreeWatch.distinct(setOf(acme), checks::realPath), periodic = false, checks, warnings)
        assertEquals(
            listOf(
                Balloon.Warning(
                    "Skein could not keep the worktrees of /w/acme out of git, so git add -A could pick the worktree up: git already tracks " +
                        ".claude/worktrees/.gitkeep; Skein kept the guard $guard it wrote, since worktrees live in that folder and it changes nothing git tracks",
                ),
            ),
            resumed,
        )
        checks.results[acme] = ExcludeResult.Failed("git already tracks .claude/worktrees/.gitkeep")
        assertEquals(emptyList<Balloon>(), WorktreeWatch.tick(listOf(thread("1", acme, worktree = "task-1")), checks, warnings))
    }

    /** Across ticks a warning is shown once per reason, a new reason is shown, and a check that holds forgets it. */
    @Test
    fun ticksWarnOncePerReasonAndForgetOnOk() {
        val threads = listOf(thread("1", acme, worktree = "task-1"))
        val checks = FakeChecks(real = mapOf(acme to acme), repositories = setOf(acme))
        val warnings = mutableMapOf<String, String>()
        fun warning(reason: String) = Balloon.Warning("Skein could not keep the worktrees of /w/acme out of git, so git add -A could pick the worktree up: $reason")
        fun tick(result: ExcludeResult): List<Balloon> {
            checks.results[acme] = result
            return WorktreeWatch.tick(threads, checks, warnings)
        }
        assertEquals(listOf(warning("busy")), tick(ExcludeResult.Failed("busy")))
        assertEquals(emptyList<Balloon>(), tick(ExcludeResult.Failed("busy")))
        assertEquals(listOf(warning("locked")), tick(ExcludeResult.Failed("locked")))
        assertEquals(emptyList<Balloon>(), tick(ok(line = false, guard = false)))
        assertEquals(emptyMap<String, String>(), warnings)
        assertEquals(listOf(warning("locked")), tick(ExcludeResult.Failed("locked")))
        assertEquals(listOf(guardBack), tick(ok(line = false, guard = true)))
        assertEquals(6, checks.ensured.size)
        // no worktree thread, no check
        assertEquals(emptyList<Balloon>(), WorktreeWatch.tick(listOf(thread("2", acme)), checks, warnings))
        assertEquals(6, checks.ensured.size)
    }

    /** A resume or a restore runs the same check, says what it wrote, and warns every time. */
    @Test
    fun aResumeCheckWarnsEveryTime() {
        val checks = FakeChecks(real = mapOf(acme to acme), repositories = setOf(acme), results = mutableMapOf(acme to ExcludeResult.Failed("busy")))
        val warnings = mutableMapOf(acme to "busy")
        val repositories = WorktreeWatch.distinct(setOf(acme), checks::realPath)
        assertEquals(
            listOf(Balloon.Warning("Skein could not keep the worktrees of /w/acme out of git, so git add -A could pick the worktree up: busy")),
            WorktreeWatch.check(repositories, periodic = false, checks, warnings),
        )
        checks.results[acme] = ok(line = false, guard = true)
        assertEquals(
            listOf(Balloon.Info("Skein added .claude/worktrees/.gitignore so git ignores the worktrees in /w/acme.")),
            WorktreeWatch.check(repositories, periodic = false, checks, warnings),
        )
        assertEquals(emptyMap<String, String>(), warnings)
    }

    /** A resume or a restore is asked for: it says what it wrote, and warns every time. */
    @Test
    fun aResumeSaysWhatItWroteAndAlwaysWarns() {
        assertEquals(
            Outcome(Balloon.Info("Skein added .claude/worktrees/.gitignore so git ignores the worktrees in /w/acme."), null),
            WorktreeWatch.outcome(acme, ok(line = false, guard = true), periodic = false, lastWarned = "busy"),
        )
        assertEquals(
            Outcome(Balloon.Warning("Skein could not keep the worktrees of /w/acme out of git, so git add -A could pick the worktree up: busy"), "busy"),
            WorktreeWatch.outcome(acme, ExcludeResult.Failed("busy"), periodic = false, lastWarned = "busy"),
        )
    }
}
