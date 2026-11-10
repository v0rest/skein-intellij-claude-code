package io.github.v0rest.skein.git

import io.github.v0rest.skein.testutil.RealGit
import org.junit.Assert.assertArrayEquals
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

/**
 * [WorktreeExcludes] against the real git, for every repository setup a review found: each time Skein must say it is
 * fine, and then git, given a real worktree under `.claude/worktrees`, must neither show nor add anything there.
 * Skipped when git is not installed.
 */
class WorktreeExcludesRealGitTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var git: RealGit

    @Before
    fun gitIsThere() {
        git = RealGit(folder)
        assumeTrue(git.available)
    }

    /** The lines of `git status --porcelain -uall` and `git add -A --dry-run` in [dir] that name [prefix], in any case. */
    private fun seen(dir: Path, prefix: String = ".claude/worktrees"): List<String> {
        val status = git.run(dir, "status", "--porcelain", "-uall")
        val add = git.run(dir, "add", "-A", "--dry-run")
        assertTrue(status.stderr, status.ok)
        assertTrue(add.stderr, add.ok)
        return (status.stdout.lines() + add.stdout.lines()).filter { it.lowercase().contains(prefix) }
    }

    private fun ensure(repo: Path): ExcludeResult = WorktreeExcludes(git).ensureExcluded(repo)

    private fun addWorktree(repo: Path, name: String = "task-1") {
        val added = git.run(repo, "worktree", "add", "-q", "-b", "worktree-$name", "--", ".claude/worktrees/$name")
        assertTrue(added.stderr, added.ok)
    }

    /** Skein says it is fine, and a worktree made afterwards stays out of git status and git add -A. */
    private fun staysOut(repo: Path) {
        val result = ensure(repo)
        assertTrue(result.toString(), result is ExcludeResult.Ok)
        addWorktree(repo)
        assertEquals(emptyList<String>(), seen(repo))
    }

    @Test
    fun noGitignore() = staysOut(git.repository())

    @Test
    fun aRuleForAFileNamedX() = staysOut(git.repository(gitignore = listOf("x")))

    @Test
    fun aRuleIgnoringTheClaudeFolder() = staysOut(git.repository(gitignore = listOf(".claude/")))

    @Test
    fun aWhitelistGitignore() = staysOut(git.repository(gitignore = listOf("*", "!*/", "!*.c")))

    @Test
    fun aClaudeFolderWhitelist() = staysOut(
        git.repository(
            gitignore = listOf(".claude/**", "!.claude/**/", "!.claude/commands/**"),
            files = mapOf(".claude/commands/review.md" to "review\n"),
        ),
    )

    @Test
    fun aRuleUnignoringTheWorktreesFolder() = staysOut(git.repository(gitignore = listOf("!.claude/worktrees/")))

    @Test
    fun aRuleUnignoringEverythingInTheWorktreesFolder() = staysOut(git.repository(gitignore = listOf("!.claude/worktrees/**")))

    @Test
    fun aRuleUnignoringEverything() = staysOut(git.repository(gitignore = listOf("!*")))

    @Test
    fun aClaudeFolderGitignoreUnignoringTheWorktrees() = staysOut(git.repository(files = mapOf(".claude/.gitignore" to "!/worktrees/\n")))

    /** A committed `.gitignore` changes with the branch: the worktree must stay out on a whitelist branch too. */
    @Test
    fun aSwitchToAWhitelistBranchAfterTheWorktreeExists() {
        val repo = git.repository()
        assertTrue(git.run(repo, "switch", "-q", "-c", "whitelist").ok)
        Files.writeString(repo.resolve(".gitignore"), "*\n!*/\n!*.c\n!.gitignore\n")
        assertTrue(git.run(repo, "add", "-A").ok)
        assertTrue(git.run(repo, "commit", "-q", "-m", "whitelist").ok)
        assertTrue(git.run(repo, "switch", "-q", "-").ok)

        staysOut(repo)
        assertTrue(git.run(repo, "switch", "-q", "whitelist").ok)
        assertEquals(emptyList<String>(), seen(repo))
    }

    /** A `.claude` folder kept in a repository of its own: neither repository may pick the worktree up. */
    @Test
    fun aClaudeFolderThatIsItsOwnRepository() {
        val repo = git.repository()
        val claude = repo.resolve(".claude")
        Files.createDirectories(claude.resolve("commands"))
        Files.writeString(claude.resolve("commands/review.md"), "review\n")
        assertTrue(git.run(claude, "init", "-q").ok)
        assertTrue(git.run(claude, "add", "-A").ok)
        assertTrue(git.run(claude, "commit", "-q", "-m", "commands").ok)

        staysOut(repo)
        assertEquals(emptyList<String>(), seen(claude, "worktrees"))
    }

    /**
     * A `.Claude` folder with a case-sensitive git: the worktree stays out on every disk. On a case-insensitive one
     * `.Claude` is the folder the worktrees go to, and the guard must be in it; that part is skipped on a case-sensitive
     * disk, where `.claude` is another folder.
     */
    @Test
    fun aCapitalisedClaudeFolderWithCaseSensitiveGit() {
        val repo = git.repository(files = mapOf(".Claude/commands/review.md" to "review\n"))
        assertTrue(git.run(repo, "config", "core.ignorecase", "false").ok)
        staysOut(repo)
        assumeTrue("the disk is case-sensitive", caseInsensitiveDisk())
        // the names as the disk keeps them: one `.claude` folder whatever the case, and it is the `.Claude` one
        val onDisk = Files.list(repo).use { s -> s.map { it.fileName.toString() }.filter { it.equals(".claude", ignoreCase = true) }.toList() }
        assertEquals(listOf(".Claude"), onDisk)
        val holder = repo.resolve(".Claude/worktrees")
        assertTrue("the worktree is in that folder", Files.isDirectory(holder.resolve("task-1")))
        assertTrue("the guard is next to it", Files.isRegularFile(holder.resolve(".gitignore")))
    }

    /** A guard file someone committed without `*`: Skein refuses and never changes it. */
    @Test
    fun aTrackedGuardWithoutTheRuleRefusesAndIsNotChanged() {
        val repo = git.repository(files = mapOf(".claude/worktrees/.gitignore" to "# notes\n"))
        val guard = repo.resolve(".claude/worktrees/.gitignore")
        val before = Files.readAllBytes(guard)
        val result = ensure(repo)
        assertTrue(result.toString(), result is ExcludeResult.Failed)
        assertArrayEquals(before, Files.readAllBytes(guard))
        assertEquals("", git.run(repo, "status", "--porcelain").stdout)
    }

    /**
     * A guard of the user's own with no rule: today the `info/exclude` line hides the folder, but no other clone or
     * branch has that line, so Skein refuses and leaves the file as it is.
     */
    @Test
    fun anUntrackedGuardWithoutTheRuleRefusesThoughTheLineIsThere() {
        val repo = git.repository()
        val guard = repo.resolve(".claude/worktrees/.gitignore")
        Files.createDirectories(guard.parent)
        Files.writeString(guard, "# nothing here\n")
        val result = ensure(repo)
        assertEquals(
            ExcludeResult.Failed("the existing file $guard must contain only the line * for Skein to keep the worktrees out of git; Skein leaves it as it is"),
            result,
        )
        assertEquals("# nothing here\n", Files.readString(guard))
        assertTrue("the line is there all the same", Files.readAllLines(repo.resolve(".git/info/exclude")).contains("/.claude/worktrees/"))
    }

    /** A guard that ignores everything but itself would be committed on a whitelist branch: refused, left byte for byte. */
    @Test
    fun anExistingGuardThatUnignoresItselfRefuses() {
        val repo = git.repository()
        val guard = repo.resolve(".claude/worktrees/.gitignore")
        Files.createDirectories(guard.parent)
        Files.writeString(guard, "*\n!.gitignore\n")
        val before = Files.readAllBytes(guard)
        val result = ensure(repo)
        assertTrue(result.toString(), result is ExcludeResult.Failed)
        assertArrayEquals(before, Files.readAllBytes(guard))
    }

    /** A guard with `*` that someone committed (git add skips it, so through the index) holds in every clone: fine as it is. */
    @Test
    fun aTrackedGuardWithTheRuleIsFine() {
        val repo = git.repository()
        val guard = repo.resolve(".claude/worktrees/.gitignore")
        Files.createDirectories(guard.parent)
        Files.writeString(guard, "*\n")
        assertTrue(git.run(repo, "update-index", "--add", "--", ".claude/worktrees/.gitignore").ok)
        assertTrue(git.run(repo, "commit", "-q", "-m", "guard").ok)
        assertEquals(listOf(".claude/worktrees/.gitignore"), git.run(repo, "ls-files", "--", ".claude/worktrees").stdout.lines().filter(String::isNotEmpty))
        staysOut(repo)
        assertEquals("*\n", Files.readString(guard))
    }

    /** A guard of the user's own that ignores the folder is used as it is, byte for byte. */
    @Test
    fun anUntrackedGuardIsLeftAsItIs() {
        val repo = git.repository()
        val guard = repo.resolve(".claude/worktrees/.gitignore")
        Files.createDirectories(guard.parent)
        Files.writeString(guard, "# mine\n*\n")
        val before = Files.readAllBytes(guard)
        staysOut(repo)
        assertArrayEquals(before, Files.readAllBytes(guard))
    }

    /** A `.claude` that is a link would take every write outside the repository: refused before anything is written. */
    @Test
    fun aClaudeLinkToAFolderOutsideIsRefused() {
        val repo = git.repository()
        val outside = folder.newFolder("outside").toPath()
        Files.createSymbolicLink(repo.resolve(".claude"), outside)
        val result = ensure(repo)
        assertTrue(result.toString(), result is ExcludeResult.Failed)
        assertEquals("nothing was made at the link's target", 0L, Files.list(outside).use { it.count() })
    }

    /**
     * A worktree someone committed as an embedded repository is already in git: Skein refuses. The guard it wrote next
     * to it stays, since it ignores itself and changes nothing git tracks.
     */
    @Test
    fun aTrackedGitlinkUnderTheWorktreesFolderIsRefused() {
        val repo = git.repository()
        val old = repo.resolve(".claude/worktrees/old")
        Files.createDirectories(old)
        Files.writeString(old.resolve("a.c"), "int b;\n")
        assertTrue(git.run(old, "init", "-q").ok)
        assertTrue(git.run(old, "add", "-A").ok)
        assertTrue(git.run(old, "commit", "-q", "-m", "old").ok)
        assertTrue(git.run(repo, "add", "--", ".claude/worktrees/old").ok)
        assertTrue(git.run(repo, "commit", "-q", "-m", "embedded").ok)

        val result = ensure(repo)
        val guard = repo.resolve(".claude/worktrees/.gitignore")
        assertEquals(ExcludeResult.Failed("git already tracks .claude/worktrees/old", keptGuard = guard), result)
        assertEquals("*\n", Files.readString(guard))
        assertEquals("", git.run(repo, "status", "--porcelain").stdout)
    }

    /** Commits [text] as the guard through the index, since `git add` skips a `.gitignore` that ignores itself. */
    private fun commitGuard(repo: Path, text: String): Path {
        val guard = repo.resolve(".claude/worktrees/.gitignore")
        Files.createDirectories(guard.parent)
        Files.writeString(guard, text)
        assertTrue(git.run(repo, "update-index", "--add", "--", ".claude/worktrees/.gitignore").ok)
        assertTrue(git.run(repo, "commit", "-q", "-m", "guard").ok)
        return guard
    }

    /** The refusal for the committed guard of [repo] when it is not on disk. */
    private fun missingRefusal(repo: Path) = ExcludeResult.Failed(
        "git tracks ${repo.resolve(".claude/worktrees/.gitignore")} but it is missing here; restore that file from git " +
            "(in a sparse checkout, add .claude/worktrees to it) and Skein uses it as it is",
    )

    /** The refusal while stash [name] in [repo] holds the guard at [path]. */
    private fun stashRefusal(repo: Path, path: String = ".claude/worktrees/.gitignore", name: String = "stash@{0}") =
        ExcludeResult.Failed("$name in $repo holds the guard $path; Skein writes the guard again once no stash holds one, and popping that stash brings it back, while dropping it would also lose any other files it holds")

    /** Whether the disk this test runs on ignores case in file names. */
    private fun caseInsensitiveDisk(): Boolean {
        val probe = folder.newFile("case-probe").toPath()
        return Files.exists(probe.resolveSibling("CASE-PROBE"))
    }

    /** The status lines that say a tracked file was changed: what Skein must never cause. */
    private fun modified(repo: Path): List<String> =
        git.run(repo, "status", "--porcelain").stdout.lines().filter { it.length > 2 && (it[0] == 'M' || it[1] == 'M') }

    /** A committed guard deleted here: writing Skein's text there would be a change to the team's file, so nothing is written. */
    @Test
    fun aTrackedGuardMissingOnDiskIsNeverWritten() {
        val repo = git.repository()
        val guard = commitGuard(repo, "# notes\n*\n")
        Files.delete(guard)
        val result = ensure(repo)
        assertEquals(missingRefusal(repo), result)
        assertFalse(Files.exists(guard))
        assertEquals(emptyList<String>(), modified(repo))
        assertEquals(listOf(" D .claude/worktrees/.gitignore"), git.run(repo, "status", "--porcelain").stdout.lines().filter(String::isNotEmpty))

        // restored from git, the team's guard is used as it is
        assertTrue(git.run(repo, "checkout", "--", ".claude/worktrees/.gitignore").ok)
        staysOut(repo)
        assertEquals("# notes\n*\n", Files.readString(guard))
        assertEquals(emptyList<String>(), modified(repo))
    }

    /** A sparse checkout that leaves `.claude` out: the committed guard is not on disk, and Skein makes nothing there. */
    @Test
    fun aTrackedGuardLeftOutOfASparseCheckoutIsNeverWritten() {
        val repo = git.repository(files = mapOf("src/b.c" to "int b;\n"))
        val guard = commitGuard(repo, "# notes\n*\n")
        assumeTrue("this git has no cone sparse checkout", git.run(repo, "sparse-checkout", "set", "--cone", "src").ok)
        assertFalse("the sparse checkout left .claude out", Files.exists(repo.resolve(".claude")))
        val result = ensure(repo)
        assertEquals(missingRefusal(repo), result)
        assertFalse("nothing was made", Files.exists(repo.resolve(".claude")))
        assertEquals("", git.run(repo, "status", "--porcelain").stdout)

        // the folder added to the sparse checkout, the team's guard is back and used as it is
        val added = git.run(repo, "sparse-checkout", "add", ".claude/worktrees")
        assertTrue(added.stderr, added.ok)
        assertEquals("# notes\n*\n", Files.readString(guard))
        staysOut(repo)
        assertEquals("# notes\n*\n", Files.readString(guard))
        assertEquals(emptyList<String>(), modified(repo))
    }

    /** Pathspecs are case-sensitive even where the file system is not: content tracked under `.Claude` counts too. */
    @Test
    fun trackedContentInACapitalisedWorktreesFolderIsRefused() {
        val repo = git.repository(files = mapOf(".Claude/worktrees/old/f" to "f\n"))
        assertEquals(ExcludeResult.Failed("git already tracks .Claude/worktrees/old/f"), ensure(repo))
        assertEquals("", git.run(repo, "status", "--porcelain").stdout)
    }

    /**
     * A worktree already in the folder and something else git tracks there: the call fails, but the guard it wrote stays,
     * since it ignores itself and the worktree would show up without it.
     */
    @Test
    fun aFailureNextToAnExistingWorktreeKeepsTheGuard() {
        val repo = git.repository(gitignore = listOf("*", "!*/", "!*.c", "!.gitignore", "!.gitkeep"), files = mapOf(".claude/worktrees/.gitkeep" to ""))
        addWorktree(repo)
        assertTrue("without a guard the whitelist shows the worktree", seen(repo).isNotEmpty())
        val result = ensure(repo)
        val guard = repo.resolve(".claude/worktrees/.gitignore")
        assertEquals(ExcludeResult.Failed("git already tracks .claude/worktrees/.gitkeep", keptGuard = guard), result)
        assertEquals("*\n", Files.readString(guard))
        assertEquals(emptyList<String>(), seen(repo))
        assertEquals("", git.run(repo, "status", "--porcelain").stdout)
    }

    /** The same refusal with no worktree in the folder: nothing of Skein's is written there. */
    @Test
    fun aFailureWithNoWorktreeInTheFolderLeavesNoGuard() {
        val repo = git.repository(files = mapOf(".claude/worktrees/.gitkeep" to ""))
        assertEquals(ExcludeResult.Failed("git already tracks .claude/worktrees/.gitkeep"), ensure(repo))
        assertFalse(Files.exists(repo.resolve(".claude/worktrees/.gitignore")))
        assertEquals("", git.run(repo, "status", "--porcelain").stdout)
    }

    /**
     * `git stash -a` takes the guard into the stash; writing it back would make `git stash pop` fail on it. Skein waits,
     * and the pop brings it back.
     */
    @Test
    fun aGuardInAStashIsNotWrittenBack() {
        val repo = git.repository()
        staysOut(repo)
        val guard = repo.resolve(".claude/worktrees/.gitignore")
        Files.writeString(repo.resolve("a.c"), "int a2;\n")
        val stashed = git.run(repo, "stash", "-a")
        assertTrue(stashed.stderr, stashed.ok)
        assertFalse("the stash took the guard", Files.exists(guard))

        assertEquals(stashRefusal(repo), ensure(repo))
        assertFalse("nothing was written", Files.exists(guard))

        val popped = git.run(repo, "stash", "pop")
        assertTrue(popped.stdout + popped.stderr, popped.ok)
        assertEquals("*\n", Files.readString(guard))
        assertEquals("int a2;\n", Files.readString(repo.resolve("a.c")))
        assertEquals(ExcludeResult.Ok(false, false, repo.resolve(".git/info/exclude"), guard), ensure(repo))
        assertEquals(emptyList<String>(), seen(repo))
    }

    /**
     * With `.Claude` on a case-insensitive disk the stash keeps the guard as `.Claude/worktrees/.gitignore`, a path git
     * compares by case: Skein still finds it and writes nothing, so `git stash pop` works.
     */
    @Test
    fun aGuardStashedUnderACapitalisedFolderIsNotWrittenBack() {
        assumeTrue("the disk is case-sensitive", caseInsensitiveDisk())
        val repo = git.repository(files = mapOf(".Claude/commands/r.md" to "r\n"))
        staysOut(repo)
        val guard = repo.resolve(".claude/worktrees/.gitignore")
        val stashed = git.run(repo, "stash", "-a")
        assertTrue(stashed.stderr, stashed.ok)
        assertFalse("the stash took the guard", Files.exists(guard))

        assertEquals(stashRefusal(repo, ".Claude/worktrees/.gitignore"), ensure(repo))
        assertFalse("nothing was written", Files.exists(guard))

        val popped = git.run(repo, "stash", "pop")
        assertTrue(popped.stdout + popped.stderr, popped.ok)
        assertEquals("*\n", Files.readString(guard))
        assertEquals(emptyList<String>(), seen(repo))
    }

    /** `git clean -fdX` treats the guard as an ignored file and removes it; the next check puts it back. */
    @Test
    fun aGuardRemovedByGitCleanIsPutBack() {
        val repo = git.repository(gitignore = listOf("*", "!*/", "!*.c", "!.gitignore"))
        staysOut(repo)
        val guard = repo.resolve(".claude/worktrees/.gitignore")
        assertTrue(git.run(repo, "clean", "-fdX").ok)
        assertFalse("git clean removed the guard", Files.exists(guard))
        assertTrue("and the whitelist now shows the worktree", seen(repo).isNotEmpty())

        val again = ensure(repo)
        assertEquals(ExcludeResult.Ok(false, true, repo.resolve(".git/info/exclude"), guard), again)
        assertEquals("*\n", Files.readString(guard))
        assertEquals(emptyList<String>(), seen(repo))
        assertEquals("", git.run(repo, "status", "--porcelain").stdout)
    }

    @Test
    fun aSecondCallWritesNothing() {
        val repo = git.repository()
        val first = ensure(repo)
        assertTrue(first.toString(), first is ExcludeResult.Ok && first.added)
        val second = ensure(repo)
        assertTrue(second.toString(), second is ExcludeResult.Ok && !second.added)
    }
}
