package io.github.v0rest.skein.git

import io.github.v0rest.skein.claude.Worktrees
import io.github.v0rest.skein.ui.WorktreeStart
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** [WorktreeExcludes] with a made-up git: the order of its steps, its file handling and what it leaves behind. */
class WorktreeExcludesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val line = "/.claude/worktrees/"
    /** Something git already tracks in the folder. */
    private val trackedOld = GitResult(0, ".claude/worktrees/old\n", "", false)

    /** How Skein asks git what it tracks in [folder]: in any case, and with pathspec magic on whatever the environment says. */
    private fun lsFiles(folder: String) = listOf("-c", "core.fsmonitor=false", "--no-literal-pathspecs", "ls-files", "--", ":(icase)$folder")

    /** How Skein lists the stash before it writes an absent guard: every entry, with its name. */
    private val stashList = listOf("stash", "list", "--format=%H%x09%gd")

    /** How Skein asks whether stash [sha] has untracked or ignored files at all (its third parent). */
    private fun untrackedPart(sha: String) = listOf("rev-parse", "-q", "--verify", "$sha^3")

    /** How Skein lists one level of a stash's tree. */
    private fun lsTree(tree: String) = listOf("-c", "core.fsmonitor=false", "ls-tree", "--name-only", tree)

    /** The trees of a stash [sha] whose untracked part holds the guard under [claude] (in whatever case the disk had). */
    private fun stashedGuard(sha: String, claude: String = ".claude") =
        mapOf("$sha^3" to "a.c\n$claude\n", "$sha^3:$claude" to "worktrees\n", "$sha^3:$claude/worktrees" to ".gitignore\n")

    /** The refusal while stash [name] in [owner] holds the guard at [path]. */
    private fun stashRefusal(name: String, owner: Path, path: String = ".claude/worktrees/.gitignore") =
        ExcludeResult.Failed("$name in $owner holds the guard $path; Skein writes the guard again once no stash holds one, and popping that stash brings it back, while dropping it would also lose any other files it holds")

    /** The refusal for a guard git tracks at [entry] in [owner] that is not on disk. */
    private fun missingRefusal(owner: Path, entry: String = ".claude/worktrees/.gitignore") = ExcludeResult.Failed(
        "git tracks ${owner.resolve(entry)} but it is missing here; restore that file from git " +
            "(in a sparse checkout, add ${entry.substringBeforeLast('/')} to it) and Skein uses it as it is",
    )

    /**
     * git as far as Skein asks it: `rev-parse --git-path` names [answer] (relative at the top level), `ls-files` lists
     * [tracked] after [duringTracked] ran, `stash list` answers [stash], `rev-parse --verify <sha>^3` finds the stashes in
     * [untracked] (or answers [verify] when given), and `ls-tree` lists what [trees] holds for a tree, failing for any
     * other; [guardAtTracked] is the guard's text when git was asked, [dirs] the folders git ran in.
     */
    private inner class FakeGit(
        private val repository: Path,
        private val answer: String = ".git/info/exclude",
        private val gitPath: GitResult? = null,
        private val tracked: GitResult = GitResult(0, "", "", false),
        private val duringTracked: () -> Unit = {},
        private val stash: GitResult = GitResult(0, "", "", false),
        private val untracked: Set<String> = emptySet(),
        private val verify: GitResult? = null,
        private val trees: Map<String, String> = emptyMap(),
    ) : GitRunner {
        val calls: MutableList<List<String>> = Collections.synchronizedList(mutableListOf())
        val dirs: MutableList<Path> = Collections.synchronizedList(mutableListOf())
        var guardAtTracked: String? = null

        override fun run(dir: Path, vararg args: String): GitResult {
            calls += args.toList()
            dirs.add(dir)
            // the git command, after any `-c name=value` and options
            return when (args.filterIndexed { i, a -> a != "-c" && (i == 0 || args[i - 1] != "-c") }.firstOrNull { !it.startsWith("--") }) {
                "rev-parse" ->
                    if ("--verify" in args) {
                        verify ?: if (args.last().removeSuffix("^3") in untracked) GitResult(0, "0123abcd\n", "", false) else GitResult(1, "", "", false)
                    } else {
                        gitPath ?: GitResult(0, "$answer\n", "", false)
                    }
                "stash" -> stash
                "ls-tree" -> trees[args.last()]?.let { GitResult(0, it, "", false) } ?: GitResult(128, "", "fatal: not a tree object\n", false)
                "ls-files" -> {
                    val guard = dir.resolve(args.last().removePrefix(":(icase)")).resolve(".gitignore")
                    guardAtTracked = if (Files.isRegularFile(guard)) Files.readString(guard) else null
                    duringTracked()
                    tracked
                }
                else -> GitResult(1, "", "unexpected", false)
            }
        }
    }

    private fun repository(name: String = "acme"): Path = tmp.root.toPath().resolve(name).also { Files.createDirectories(it.resolve(".git")) }

    private fun worktrees(repo: Path): Path = repo.resolve(".claude/worktrees")

    private fun guardOf(repo: Path): Path = worktrees(repo).resolve(".gitignore")

    private fun ok(repo: Path, line: Boolean, guard: Boolean, file: Path = repo.resolve(".git/info/exclude")) =
        ExcludeResult.Ok(line, guard, file, guardOf(repo))

    @Test
    fun theNamesComeFromWorktrees() {
        assertEquals(line, Worktrees.EXCLUDE_LINE)
        assertEquals(".gitignore", Worktrees.GUARD_NAME)
        assertEquals("*\n", Worktrees.GUARD_TEXT)
    }

    /** The line first; then git, asked only what it tracks in the folder, before the folders and the guard are made. */
    @Test
    fun theTrackedFilesAreCheckedBeforeTheGuardIsWritten() {
        val repo = repository()
        val git = FakeGit(repo)
        assertEquals(ok(repo, line = true, guard = true), WorktreeExcludes(git).ensureExcluded(repo))
        assertEquals(listOf(listOf("rev-parse", "--git-path", "info/exclude"), lsFiles(".claude/worktrees"), stashList), git.calls)
        assertEquals(listOf(repo, repo, repo), git.dirs)
        assertNull(git.guardAtTracked)
        assertEquals("*\n", Files.readString(guardOf(repo)))
        assertEquals("$line\n", Files.readString(repo.resolve(".git/info/exclude")))
    }

    /** A `.claude` that is a repository of its own holds the folder: the outer repository never looks inside it. */
    @Test
    fun aClaudeRepositoryOfItsOwnIsAskedItself() {
        val repo = repository()
        val claude = repo.resolve(".claude")
        Files.createDirectories(claude.resolve(".git"))
        Files.createDirectories(worktrees(repo))
        Files.writeString(guardOf(repo), "*\n")
        // the guard path git lists there is relative to `.claude`
        val git = FakeGit(repo, tracked = GitResult(0, "worktrees/.gitignore\n", "", false))
        assertEquals(ok(repo, line = true, guard = false), WorktreeExcludes(git).ensureExcluded(repo))
        assertEquals(listOf(lsFiles("worktrees")), git.calls.drop(1))
        assertEquals(listOf(repo, claude), git.dirs)
    }

    /** With `.claude` a repository of its own, the missing guard and the stash are that repository's. */
    @Test
    fun aClaudeRepositoryOfItsOwnNamesItselfForTheMissingGuardAndTheStash() {
        val repo = repository()
        val claude = repo.resolve(".claude")
        Files.createDirectories(claude.resolve(".git"))
        val missing = FakeGit(repo, tracked = GitResult(0, "worktrees/.gitignore\n", "", false))
        assertEquals(missingRefusal(claude, "worktrees/.gitignore"), WorktreeExcludes(missing).ensureExcluded(repo))

        val trees = mapOf("aaa^3" to "worktrees\n", "aaa^3:worktrees" to ".gitignore\n")
        val git = FakeGit(repo, stash = GitResult(0, "aaa\tstash@{0}\n", "", false), untracked = setOf("aaa"), trees = trees)
        assertEquals(stashRefusal("stash@{0}", claude, "worktrees/.gitignore"), WorktreeExcludes(git).ensureExcluded(repo))
        assertEquals(listOf(stashList, untrackedPart("aaa"), lsTree("aaa^3"), lsTree("aaa^3:worktrees")), git.calls.drop(2))
        assertEquals(List(4) { claude }, git.dirs.drop(2))
    }

    @Test
    fun aSecondCallWritesNothing() {
        val repo = repository()
        val file = repo.resolve(".git/info/exclude")
        Files.createDirectories(file.parent)
        Files.writeString(file, "# local\n*.log")
        val git = FakeGit(repo)
        assertEquals(ok(repo, line = true, guard = true), WorktreeExcludes(git).ensureExcluded(repo))
        assertEquals("# local\n*.log\n$line\n", Files.readString(file))
        val second = WorktreeExcludes(git).ensureExcluded(repo)
        assertEquals(ok(repo, line = false, guard = false), second)
        assertFalse((second as ExcludeResult.Ok).added)
        assertEquals("# local\n*.log\n$line\n", Files.readString(file))
        assertEquals("*\n", Files.readString(guardOf(repo)))
    }

    /** A `.gitignore` already in the folder is the user's: never touched, byte for byte, and used only when it is `*` alone. */
    @Test
    fun anExistingGuardIsNeverTouched() {
        val repo = repository()
        Files.createDirectories(worktrees(repo))
        val mine = "# mine\r\n*\n".toByteArray()
        Files.write(guardOf(repo), mine)
        assertEquals(ok(repo, line = true, guard = false), WorktreeExcludes(FakeGit(repo)).ensureExcluded(repo))
        assertArrayEquals(mine, Files.readAllBytes(guardOf(repo)))

        val more = "*\n!.gitignore\n".toByteArray()
        Files.write(guardOf(repo), more)
        val refused = WorktreeExcludes(FakeGit(repo)).ensureExcluded(repo)
        assertEquals(
            ExcludeResult.Failed("the existing file ${guardOf(repo)} must contain only the line * for Skein to keep the worktrees out of git; Skein leaves it as it is"),
            refused,
        )
        assertArrayEquals(more, Files.readAllBytes(guardOf(repo)))
    }

    /** git does not read a `.gitignore` that is a link, so it guards nothing; Skein leaves the link as it is. */
    @Test
    fun aGuardThatIsALinkIsRefused() {
        val repo = repository()
        Files.createDirectories(worktrees(repo))
        val target = Files.writeString(tmp.root.toPath().resolve("star"), "*\n")
        Files.createSymbolicLink(guardOf(repo), target)
        val result = WorktreeExcludes(FakeGit(repo)).ensureExcluded(repo)
        assertTrue(result.toString(), result is ExcludeResult.Failed)
        assertTrue(Files.isSymbolicLink(guardOf(repo)))
        assertEquals("*\n", Files.readString(target))
    }

    /** A guard git tracks counts, and only the guard: anything else tracked there is in commits already. */
    @Test
    fun trackedContentRefusesTheStart() {
        val repo = repository()
        // git tracks the guard and it is on disk: used as it is
        Files.createDirectories(worktrees(repo))
        Files.writeString(guardOf(repo), "*\n")
        assertTrue(WorktreeExcludes(FakeGit(repo, tracked = GitResult(0, ".claude/worktrees/.gitignore\n", "", false))).ensureExcluded(repo) is ExcludeResult.Ok)

        val both = GitResult(0, ".claude/worktrees/.gitignore\n.claude/worktrees/old\n", "", false)
        assertEquals(ExcludeResult.Failed("git already tracks .claude/worktrees/old"), WorktreeExcludes(FakeGit(repo, tracked = both)).ensureExcluded(repo))
        assertEquals("*\n", Files.readString(guardOf(repo)))

        // nothing on disk yet: nothing is made
        val other = repository("beta")
        assertEquals(ExcludeResult.Failed("git already tracks .claude/worktrees/old"), WorktreeExcludes(FakeGit(other, tracked = trackedOld)).ensureExcluded(other))
        assertFalse("nothing was made", Files.exists(other.resolve(".claude")))
    }

    @Test
    fun aMissingExcludeFileIsCreated() {
        val repo = repository()
        assertEquals(ok(repo, line = true, guard = true), WorktreeExcludes(FakeGit(repo)).ensureExcluded(repo))
        assertEquals("$line\n", Files.readString(repo.resolve(".git/info/exclude")))
    }

    /** From a linked worktree git names the shared exclude file by its absolute path. */
    @Test
    fun anAbsoluteExcludePathIsTakenAsIs() {
        val repo = repository()
        val shared = tmp.root.toPath().resolve("main/.git/info/exclude")
        val result = WorktreeExcludes(FakeGit(repo, answer = shared.toString())).ensureExcluded(repo)
        assertEquals(ok(repo, line = true, guard = true, file = shared), result)
        assertEquals("$line\n", Files.readString(shared))
        assertFalse(Files.exists(repo.resolve(".git/info")))
    }

    @Test
    fun aGitFailureRefusesTheStart() {
        val repo = repository()
        // git cannot even name the file: nothing is written and no folder is made
        val noFile = FakeGit(repo, gitPath = GitResult(128, "", "fatal: not a git repository (or any of the parent directories): .git\n", false))
        val result = WorktreeExcludes(noFile).ensureExcluded(repo)
        assertEquals(ExcludeResult.Failed("not a git repository (or any of the parent directories): .git"), result)
        assertEquals("Skein could not keep the worktree out of git: not a git repository (or any of the parent directories): .git", WorktreeStart.excludeRefusal(result))
        assertEquals(1, noFile.calls.size)
        assertFalse(Files.exists(repo.resolve(".claude")))
        assertFalse(Files.exists(repo.resolve(".git/info")))

        // git fails later: the guard and the folders this call made are taken back, the line stays
        val failures = listOf(
            GitResult(128, "", "fatal: bad config line 1\n", false) to "bad config line 1",
            GitResult(-1, "", "", true) to "git could not be run: git did not finish in time",
            GitResult(-1, "", "Cannot run program \"git\"", false) to "git could not be run: Cannot run program \"git\"",
        )
        for ((answer, reason) in failures) {
            val failed = WorktreeExcludes(FakeGit(repo, tracked = answer)).ensureExcluded(repo)
            assertEquals(ExcludeResult.Failed(reason), failed)
            assertEquals("Skein could not keep the worktree out of git: $reason", WorktreeStart.excludeRefusal(failed))
            assertFalse(Files.exists(repo.resolve(".claude")))
        }
        assertEquals("$line\n", Files.readString(repo.resolve(".git/info/exclude")))
        assertNull(WorktreeStart.excludeRefusal(ok(repo, line = true, guard = true)))
    }

    @Test
    fun onlyWhatThisCallMadeIsRemoved() {
        val repo = repository()
        Files.createDirectories(repo.resolve(".claude"))
        assertTrue(WorktreeExcludes(FakeGit(repo, tracked = trackedOld)).ensureExcluded(repo) is ExcludeResult.Failed)
        assertTrue("it was there before", Files.isDirectory(repo.resolve(".claude")))
        assertFalse(Files.exists(worktrees(repo)))
    }

    /**
     * The folder holds a worktree already and git tracks something else there: the call fails, but the guard it wrote
     * stays (it ignores itself), since without it the worktree could show up in git.
     */
    @Test
    fun aFailureNextToAnExistingWorktreeKeepsTheGuard() {
        val repo = repository()
        val gitkeep = GitResult(0, ".claude/worktrees/.gitkeep\n", "", false)
        // a folder with no `.git` in it is no worktree: nothing is written
        Files.createDirectories(worktrees(repo).resolve("notes"))
        assertEquals(ExcludeResult.Failed("git already tracks .claude/worktrees/.gitkeep"), WorktreeExcludes(FakeGit(repo, tracked = gitkeep)).ensureExcluded(repo))
        assertFalse(Files.exists(guardOf(repo)))

        // a worktree has a `.git` file
        Files.createDirectories(worktrees(repo).resolve("task-1"))
        Files.writeString(worktrees(repo).resolve("task-1/.git"), "gitdir: elsewhere\n")
        val result = WorktreeExcludes(FakeGit(repo, tracked = gitkeep)).ensureExcluded(repo)
        assertEquals(ExcludeResult.Failed("git already tracks .claude/worktrees/.gitkeep", keptGuard = guardOf(repo)), result)
        assertEquals("*\n", Files.readString(guardOf(repo)))
        assertTrue(Files.isDirectory(worktrees(repo).resolve("task-1")))
    }

    /** An embedded repository has a `.git` folder, and counts as a worktree too. */
    @Test
    fun aGitFolderCountsAsAWorktree() {
        val repo = repository()
        Files.createDirectories(worktrees(repo).resolve("old/.git"))
        val result = WorktreeExcludes(FakeGit(repo, tracked = trackedOld)).ensureExcluded(repo)
        assertEquals(ExcludeResult.Failed("git already tracks .claude/worktrees/old", keptGuard = guardOf(repo)), result)
        assertEquals("*\n", Files.readString(guardOf(repo)))
    }

    /**
     * A guard `git stash -a` took is not written back, or `git stash pop` would fail on it. Every stash entry is asked,
     * one with no untracked part is skipped, and the guard's path is looked up one level at a time in any case, since
     * the stash keeps the case the disk had. Only when Skein is about to write an absent guard.
     */
    @Test
    fun aGuardInAStashIsNotWrittenBack() {
        val repo = repository()
        Files.createDirectories(worktrees(repo).resolve("task-1"))
        val list = GitResult(0, "aaa\tstash@{0}\nbbb\tstash@{1}\nccc\tstash@{2}\n", "", false)
        // bbb has a `.claude/worktrees` without the guard, ccc has the guard under `.Claude`
        val trees = mapOf("bbb^3" to ".claude\n", "bbb^3:.claude" to "worktrees\n", "bbb^3:.claude/worktrees" to "notes\n") + stashedGuard("ccc", ".Claude")
        val git = FakeGit(repo, stash = list, untracked = setOf("bbb", "ccc"), trees = trees)
        assertEquals(stashRefusal("stash@{2}", repo, ".Claude/worktrees/.gitignore"), WorktreeExcludes(git).ensureExcluded(repo))
        assertEquals(
            listOf(
                stashList,
                untrackedPart("aaa"),
                untrackedPart("bbb"), lsTree("bbb^3"), lsTree("bbb^3:.claude"), lsTree("bbb^3:.claude/worktrees"),
                untrackedPart("ccc"), lsTree("ccc^3"), lsTree("ccc^3:.Claude"), lsTree("ccc^3:.Claude/worktrees"),
            ),
            git.calls.drop(2),
        )
        assertFalse(Files.exists(guardOf(repo)))

        // stashes that do not hold it: written
        val other = FakeGit(repo, stash = GitResult(0, "aaa\tstash@{0}\nbbb\tstash@{1}\n", "", false), untracked = setOf("bbb"), trees = trees)
        assertEquals(ok(repo, line = false, guard = true), WorktreeExcludes(other).ensureExcluded(repo))

        // a guard on disk: the stash is not asked
        val present = FakeGit(repo, stash = list, untracked = setOf("ccc"), trees = trees)
        assertEquals(ok(repo, line = false, guard = false), WorktreeExcludes(present).ensureExcluded(repo))
        assertEquals(2, present.calls.size)
    }

    /** git cannot list the stash, or cannot say what an entry holds: Skein cannot know, so it writes nothing. */
    @Test
    fun aStashGitCannotReadWritesNothing() {
        val repo = repository()
        val entry = GitResult(0, "aaa\tstash@{0}\n", "", false)
        val cases = listOf(
            FakeGit(repo, stash = GitResult(-1, "", "", true)) to "git could not be run: git did not finish in time",
            FakeGit(repo, stash = entry, verify = GitResult(-1, "", "", true)) to "could not read stash@{0} in $repo: git could not be run: git did not finish in time",
            FakeGit(repo, stash = entry, verify = GitResult(128, "", "fatal: bad object\n", false)) to "could not read stash@{0} in $repo: bad object",
            FakeGit(repo, stash = entry, untracked = setOf("aaa")) to "could not read stash@{0} in $repo: not a tree object",
        )
        for ((git, reason) in cases) {
            assertEquals(ExcludeResult.Failed(reason), WorktreeExcludes(git).ensureExcluded(repo))
            assertFalse(Files.exists(repo.resolve(".claude")))
        }
    }

    /** A guard git tracks but that is not on disk (deleted, or left out by a sparse checkout) is never written. */
    @Test
    fun aTrackedGuardMissingOnDiskIsNeverWritten() {
        val repo = repository()
        val git = FakeGit(repo, tracked = GitResult(0, ".claude/worktrees/.gitignore\n", "", false))
        assertEquals(missingRefusal(repo), WorktreeExcludes(git).ensureExcluded(repo))
        assertFalse("nothing was made in the working tree", Files.exists(repo.resolve(".claude")))

        // named in another case, as git with `icase` lists it, it is still the guard, checked by the path git names
        val other = repository("beta")
        val capitalised = FakeGit(other, tracked = GitResult(0, ".Claude/Worktrees/.GitIgnore\n", "", false))
        assertEquals(missingRefusal(other, ".Claude/Worktrees/.GitIgnore"), WorktreeExcludes(capitalised).ensureExcluded(other))

        // the folder is there, the guard is not
        Files.createDirectories(worktrees(repo).resolve("task-1"))
        assertTrue(WorktreeExcludes(git).ensureExcluded(repo) is ExcludeResult.Failed)
        assertFalse(Files.exists(guardOf(repo)))
    }

    /** A guard that appeared while git was asked is someone else's: Skein does not write over it and leaves it as it is. */
    @Test
    fun aGuardWrittenMeanwhileIsLeftAsItIs() {
        val repo = repository()
        val git = FakeGit(repo, duringTracked = {
            Files.createDirectories(worktrees(repo))
            Files.writeString(guardOf(repo), "*\n!keep\n")
        })
        assertEquals(
            ExcludeResult.Failed("the existing file ${guardOf(repo)} must contain only the line * for Skein to keep the worktrees out of git; Skein leaves it as it is"),
            WorktreeExcludes(git).ensureExcluded(repo),
        )
        assertEquals("*\n!keep\n", Files.readString(guardOf(repo)))
    }

    /** Something other than a regular file at the guard's path (a folder, a FIFO) is never read: reading a FIFO would block. */
    @Test(timeout = 20_000)
    fun aGuardThatIsNoRegularFileIsRefused() {
        val repo = repository()
        Files.createDirectories(guardOf(repo))
        assertEquals(
            ExcludeResult.Failed("${guardOf(repo)} is not a regular file, so git reads no rules from it; Skein leaves it as it is"),
            WorktreeExcludes(FakeGit(repo)).ensureExcluded(repo),
        )
        assertTrue(Files.isDirectory(guardOf(repo)))

        val beta = repository("beta")
        Files.createDirectories(worktrees(beta))
        val made = try {
            ProcessBuilder("mkfifo", guardOf(beta).toString()).start().waitFor() == 0
        } catch (_: java.io.IOException) {
            false
        }
        assumeTrue("mkfifo is not available", made)
        assertEquals(
            ExcludeResult.Failed("${guardOf(beta)} is not a regular file, so git reads no rules from it; Skein leaves it as it is"),
            WorktreeExcludes(FakeGit(beta)).ensureExcluded(beta),
        )
    }

    /** The guard holds when, read the way git reads it, its only rule is `*`. */
    @Test
    fun theGuardMustHoldOnlyTheStarLine() {
        for (text in listOf("*", "*\n", "# notes\n*\n", "*\n# trailing\n", "\n   \n*\n", "*\r\n", "*  \n", "\n\n*")) {
            assertTrue(text, WorktreeExcludes.guardHolds(text))
        }
        for (text in listOf("*\n!.gitignore\n", "# only a comment\n", "", "x\n", " *\n", "/*\n", "*\n*\n", "**\n", "*\\ \n", "\uFEFF*\n", "*\tx\n")) {
            assertFalse(text, WorktreeExcludes.guardHolds(text))
        }
    }

    /** A link in place of either folder would take the writes out of the repository: refused before anything is written. */
    @Test
    fun aLinkInPlaceOfAFolderIsRefusedBeforeAnyWrite() {
        val outside = tmp.newFolder("outside").toPath()
        val repo = repository()
        Files.createSymbolicLink(repo.resolve(".claude"), outside)
        val git = FakeGit(repo)
        assertEquals(ExcludeResult.Failed("${repo.resolve(".claude")} is a link, and Skein writes nothing through it"), WorktreeExcludes(git).ensureExcluded(repo))
        assertEquals(emptyList<List<String>>(), git.calls)
        assertEquals(0L, Files.list(outside).use { it.count() })
        assertFalse(Files.exists(repo.resolve(".git/info")))

        val beta = repository("beta")
        Files.createDirectories(beta.resolve(".claude"))
        Files.createSymbolicLink(worktrees(beta), outside)
        assertEquals(ExcludeResult.Failed("${worktrees(beta)} is a link, and Skein writes nothing through it"), WorktreeExcludes(FakeGit(beta)).ensureExcluded(beta))
        assertEquals(0L, Files.list(outside).use { it.count() })
    }

    @Test
    fun aFileWhereAFolderGoesIsRefusedBeforeAnyWrite() {
        val repo = repository()
        Files.writeString(repo.resolve(".claude"), "not a folder")
        val git = FakeGit(repo)
        assertEquals(ExcludeResult.Failed("${repo.resolve(".claude")} is not a folder"), WorktreeExcludes(git).ensureExcluded(repo))
        assertEquals("not a folder", Files.readString(repo.resolve(".claude")))
        assertEquals(emptyList<List<String>>(), git.calls)
    }

    /** A failed write says why, and names the path once. */
    @Test
    fun aFailedWriteSaysWhy() {
        val repo = repository()
        Files.writeString(repo.resolve(".git/info"), "a file where the folder goes")
        val file = repo.resolve(".git/info/exclude")
        val result = WorktreeExcludes(FakeGit(repo)).ensureExcluded(repo) as ExcludeResult.Failed
        assertTrue(result.reason, result.reason.startsWith("could not "))
        assertEquals(result.reason, 1, Regex(Regex.escape(file.toString())).findAll(result.reason).count())
        assertFalse(result.reason, result.reason.endsWith(file.toString()))
        assertFalse(Files.exists(repo.resolve(".claude")))
    }

    /** Two calls at once: the second waits until the first is done, so it cannot take back what the first checks. */
    @Test
    fun callsRunOneAtATime() {
        val repo = repository()
        val probing = CountDownLatch(1)
        val release = CountDownLatch(1)
        val first = FakeGit(repo, duringTracked = {
            probing.countDown()
            release.await(5, TimeUnit.SECONDS)
        })
        val second = FakeGit(repo)
        val results = Collections.synchronizedList(mutableListOf<ExcludeResult>())
        val a = Thread { results += WorktreeExcludes(first).ensureExcluded(repo) }.apply { start() }
        assertTrue(probing.await(5, TimeUnit.SECONDS))
        val b = Thread { results += WorktreeExcludes(second).ensureExcluded(repo) }.apply { start() }
        Thread.sleep(200)
        assertEquals("the second call has not started", emptyList<List<String>>(), second.calls)
        release.countDown()
        a.join(5_000)
        b.join(5_000)
        assertEquals(setOf(ok(repo, line = true, guard = true), ok(repo, line = false, guard = false)), results.toSet())
        assertEquals("$line\n", Files.readString(repo.resolve(".git/info/exclude")))
    }

    @Test
    fun theLineGoesOnALineOfItsOwn() {
        assertEquals("$line\n", WorktreeExcludes.withExcludeLine(""))
        assertEquals("*.log\n$line\n", WorktreeExcludes.withExcludeLine("*.log"))
        assertEquals("*.log\n$line\n", WorktreeExcludes.withExcludeLine("*.log\n"))
        assertEquals("*.log\r\n$line\n", WorktreeExcludes.withExcludeLine("*.log\r\n"))
    }

    /** git strips a `\r` at the end of a line and trailing spaces, nothing else. */
    @Test
    fun aLineAlreadyPresentLeavesTheTextUnchanged() {
        for (text in listOf("$line\n", "*.log\n$line", "$line\r\n*.log\n", "$line  \n", "$line \r\n")) {
            assertEquals(text, WorktreeExcludes.withExcludeLine(text))
        }
        // a line that only starts the same, a commented one, one with a tab or a leading blank is not the line
        for (other in listOf("$line*\n", "#$line\n", "$line\t\n", " $line\n", "$line\\ \n")) {
            assertEquals(other + "$line\n", WorktreeExcludes.withExcludeLine(other))
        }
    }

    @Test
    fun theTextIsAddedNotRewritten() {
        val current = "a\nb"
        assertTrue(WorktreeExcludes.withExcludeLine(current).startsWith(current))
    }

    /** The balloon says what Skein wrote; nothing written, no balloon. */
    @Test
    fun theStartMessageSaysWhatWasWritten() {
        val repo = Path.of("/w/acme")
        fun result(line: Boolean, guard: Boolean, file: Path = repo.resolve(".git/info/exclude")) =
            ExcludeResult.Ok(line, guard, file, repo.resolve(".claude/worktrees/.gitignore"))
        assertEquals(
            "Skein added .claude/worktrees/.gitignore and the line /.claude/worktrees/ to .git/info/exclude so git ignores the worktrees in /w/acme.",
            WorktreeStart.excludeAdded(repo, result(line = true, guard = true)),
        )
        assertEquals("Skein added .claude/worktrees/.gitignore so git ignores the worktrees in /w/acme.", WorktreeStart.excludeAdded(repo, result(line = false, guard = true)))
        assertEquals(
            "Skein added the line /.claude/worktrees/ to /w/main/.git/info/exclude so git ignores the worktrees in /w/acme.",
            WorktreeStart.excludeAdded(repo, result(line = true, guard = false, file = Path.of("/w/main/.git/info/exclude"))),
        )
        assertNull(WorktreeStart.excludeAdded(repo, result(line = false, guard = false)))
        assertEquals("Skein could not keep the worktree out of git: busy", WorktreeStart.excludeFailed("busy"))
    }

    /** Resume and restore start the thread anyway, since its work is there already, and only warn. */
    @Test
    fun theResumeWarningNamesTheRepositoryAndTheReason() {
        assertEquals(
            "Skein could not keep the worktrees of /w/acme out of git, so git add -A could pick the worktree up: busy",
            WorktreeStart.excludeWarning("/w/acme", "busy"),
        )
    }

    /** A guard kept after a refusal is said where the text is made: in the start's refusal and in the warning's reason. */
    @Test
    fun aKeptGuardIsSaidInTheText() {
        val guard = Path.of("/w/acme/.claude/worktrees/.gitignore")
        val kept = ExcludeResult.Failed("busy", keptGuard = guard)
        val said = "busy; Skein kept the guard $guard it wrote, since worktrees live in that folder and it changes nothing git tracks"
        assertEquals(said, WorktreeStart.excludeReason(kept))
        assertEquals("busy", WorktreeStart.excludeReason(ExcludeResult.Failed("busy")))
        assertEquals("Skein could not keep the worktree out of git: $said", WorktreeStart.excludeRefusal(kept))
    }
}
