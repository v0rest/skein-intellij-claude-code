package io.github.v0rest.skein

import io.github.v0rest.skein.testutil.SourceRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

/**
 * Text-level tripwires over src/main/kotlin for the rules the design makes mandatory. They catch the ordinary ways of
 * breaking a rule, not every conceivable one; the byte-for-byte lifecycle test on a fake home is the real guarantee.
 */
class ArchitectureTest {
    private val mainRoot: Path = Path.of("src/main/kotlin")
    private val claudeHome: Path = mainRoot.resolve("io/github/v0rest/skein/claude/ClaudeHome.kt")
    private val worktreeExcludes: Path = mainRoot.resolve("io/github/v0rest/skein/git/WorktreeExcludes.kt")
    private val leftoverFolder: Path = mainRoot.resolve("io/github/v0rest/skein/git/LeftoverWorktreeFolder.kt")
    private val worktreeGit: Path = mainRoot.resolve("io/github/v0rest/skein/git/WorktreeGit.kt")
    private val closeFlow: Path = mainRoot.resolve("io/github/v0rest/skein/ui/WorktreeCloseFlow.kt")

    /**
     * Write-capable file APIs no main source of Skein needs: the IDE persists plugin state, and Skein itself writes
     * nothing but the exclude line in a repository's `info/exclude`, its worktrees folder and the `.gitignore` there
     * ([worktreeExcludes]), and deletes nothing but a folder git does not list under a worktrees folder, once the owner
     * agreed ([leftoverFolder]).
     */
    private val writeApis = listOf(
        "Files.write", "Files.newOutputStream", "Files.newBufferedWriter", "Files.createFile", "Files.createDirectories",
        "Files.createDirectory", "Files.createTempFile", "Files.createTempDirectory", "Files.createLink",
        "Files.createSymbolicLink", "Files.delete", "Files.move", "Files.copy", "Files.setLastModifiedTime",
        "Files.setAttribute", "Files.setPosixFilePermissions", "Files.setOwner",
        "StandardOpenOption.WRITE", "StandardOpenOption.APPEND", "StandardOpenOption.CREATE",
        "StandardOpenOption.TRUNCATE_EXISTING", "StandardOpenOption.DELETE_ON_CLOSE",
        "FileOutputStream", "FileWriter", "PrintWriter", "RandomAccessFile", "FileChannel.open",
        "writeText", "writeBytes", "writeLines", "appendText", "appendBytes", "appendLines", "outputStream()",
        "bufferedWriter(", "printWriter(", "deleteExisting", "deleteIfExists", "deleteRecursively", "moveTo(",
        "copyTo(", "createFile(", "createDirectories(", "createDirectory(", "createTempFile(", "createTempDirectory(",
        "createSymbolicLinkPointingTo", "createLinkPointingTo", "setLastModifiedTime(", "setPosixFilePermissions(",
        ".toFile()", "java.io.File(", "VfsUtil.createChild", "createChildData", "createChildDirectory", "getOutputStream(",
        "setBinaryContent", "WriteAction", "writeAction", "moveToTrash", "walkFileTree",
        // the platform's and the usual libraries' deletes, recursive or not
        "FileUtil.delete", "FileUtilRt.delete", "NioFiles.delete", "VfsUtil.delete", "VfsUtilCore.delete", "FileUtils.delete",
        "FileUtils.forceDelete", "FileUtils.cleanDirectory", "PathUtils.delete", "PathUtils.cleanDirectory", "MoreFiles.delete",
        "deleteDirectory", "deleteContents", "deleteOnExit", "deleteWithRenaming", "asyncDelete", "deleteChildren",
        "util.io.delete", "deleteRecursivelyIfExists", "FileUtilRt.deleteRecursively",
    )

    private fun filesContaining(vararg needles: String): List<String> =
        SourceRules.kotlinFiles(mainRoot)
            .filter { f -> SourceRules.offenders(SourceRules.codeLines(f), needles.toList()).isNotEmpty() }
            .map { mainRoot.relativize(it).toString() }

    /** The folder name as a string literal (`".claude"`, `"~/.claude/..."`), not the `skein.claude` package name. */
    @Test
    fun onlyClaudeHomeMentionsDotClaude() {
        assertEquals(
            listOf("io/github/v0rest/skein/claude/ClaudeHome.kt", "io/github/v0rest/skein/claude/Worktrees.kt"),
            filesContaining("\".claude", "/.claude").sorted(),
        )
    }

    @Test
    fun worktreesIsPure() {
        val lines = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/claude/Worktrees.kt"))
        assertEquals(emptyList<String>(), SourceRules.offenders(lines, listOf("Files.", "Path(", "Paths.", "java.nio.file")))
    }

    @Test
    fun claudeHomeHasNoWriteCalls() {
        val lines = SourceRules.codeLines(claudeHome)
        val bareWords = listOf(
            "WRITE", "APPEND", "CREATE", "createFile", "createDirectories", "delete", "move", "copy",
            "setLastModifiedTime", "newOutputStream", "newBufferedWriter",
        )
        assertEquals(emptyList<String>(), SourceRules.offenders(lines, writeApis) + SourceRules.wordOffenders(lines, bareWords))
    }

    @Test
    fun noWriteCallsAnywhereInMain() {
        val offenders = SourceRules.kotlinFiles(mainRoot).filter { it != worktreeExcludes && it != leftoverFolder }.flatMap { f ->
            SourceRules.offenders(SourceRules.codeLines(f), writeApis).map { "${mainRoot.relativize(f)}: $it" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /**
     * The one write under a `.git` folder: WorktreeExcludes creates the folder of the exclude file git names and appends
     * to that file. In the working tree it makes the worktrees folder one level at a time and writes the guard only as a
     * new file (`CREATE_NEW`), so no existing file is ever truncated or rewritten; it takes back only what it made, with
     * the plain delete that refuses a folder that is not empty: nothing recursive, nothing that empties a file.
     */
    @Test
    fun worktreeExcludesOnlyAppendsOrCreatesNewAndRemovesWhatItMade() {
        val lines = SourceRules.codeLines(worktreeExcludes)
        val allowed = listOf(
            "Files.createDirectories", "createDirectories(", "Files.createDirectory", "createDirectory(", "Files.write",
            "StandardOpenOption.CREATE", "StandardOpenOption.APPEND", "StandardOpenOption.WRITE", "Files.delete",
            "Files.newOutputStream",
        )
        assertEquals(emptyList<String>(), SourceRules.offenders(lines, writeApis.filterNot { it in allowed }))
        assertTrue("the file must be the one git names", lines.any { it.contains("\"--git-path\", \"info/exclude\"") })
        val writes = lines.filter { it.contains("Files.write") || it.contains("Files.newOutputStream") }
        assertEquals(writes.toString(), 2, writes.size)
        assertTrue(
            "every write appends or makes a new file",
            writes.all { it.contains("StandardOpenOption.APPEND") || it.contains("StandardOpenOption.CREATE_NEW") },
        )
        assertTrue("never truncates", lines.none { it.contains("TRUNCATE_EXISTING") })
        assertEquals("one plain delete, of a file or folder this call made", 1, lines.count { it.contains("Files.delete(") })
        assertEquals(emptyList<String>(), SourceRules.offenders(lines, listOf("deleteIfExists", "deleteRecursively", "walk(", "Files.list(")))
    }

    /**
     * The one recursive delete: LeftoverWorktreeFolder hands a folder it built from the repository and a worktree name
     * to the platform's recursive delete, which removes a link inside as a link, after refusing a link on the way.
     * Nothing else there writes, and only the remove flow calls it, after its own question and checks.
     */
    @Test
    fun theOneRecursiveDeleteIsALeftoverWorktreeFolder() {
        val lines = SourceRules.codeLines(leftoverFolder)
        assertEquals(listOf("NioFiles.deleteRecursively(folder)"), SourceRules.offenders(lines, writeApis))
        assertTrue("the folder is built from the repository and a checked name", lines.any { it.contains("Path.of(Worktrees.dirOf(repository, name))") })
        assertTrue("the name is checked", lines.any { it.contains("Worktrees.isValidName(name)") })
        assertTrue("a link on the way is refused", lines.any { it.contains("Files.isSymbolicLink(step)") })
        assertTrue("a folder with a .git of its own is refused", lines.any { it.contains("Files.exists(folder.resolve(\".git\"), LinkOption.NOFOLLOW_LINKS)") })
        assertEquals(listOf("io/github/v0rest/skein/git/LeftoverWorktreeFolder.kt"), filesContaining("deleteRecursively", "NioFiles.delete"))
        assertEquals(
            listOf("io/github/v0rest/skein/ui/WorktreeCloseFlow.kt"),
            filesContaining("LeftoverWorktreeFolder::delete", "LeftoverWorktreeFolder.delete"),
        )
        // the flow holds it as deleteLeftover, called on one line, after its own question and checks, never passed on
        val flow = SourceRules.codeLines(closeFlow)
        assertEquals(1, flow.count { it.contains("deleteLeftover(") })
        assertEquals(1, flow.count { it.contains("LeftoverWorktreeFolder::delete") })
        assertEquals(emptyList<String>(), filesContaining("::deleteLeftover"))
    }

    @Test
    fun noInternalTerminalSessionUse() {
        assertEquals(emptyList<String>(), filesContaining("getSessionDeferred", "sessionDeferred", "TerminalSession", "getProcessId"))
    }

    /**
     * The native libraries Skein loads are IOKit for the power assertion (SleepBlocker) and libc for the SIGHUP a closing
     * thread's claude and shell get (HangUp); nothing else touches JNA loading.
     */
    @Test
    fun onlySleepBlockerAndHangUpLoadNativeLibraries() {
        assertEquals(
            listOf("io/github/v0rest/skein/power/SleepBlocker.kt", "io/github/v0rest/skein/process/HangUp.kt"),
            filesContaining("Native.load(", "NativeLibrary.getInstance(").sorted(),
        )
    }

    /** The one call that signals a process is libc's kill, with SIGHUP and nothing else, behind the pid guard. */
    @Test
    fun theOnlySignalIsAGuardedHangUp() {
        val hangUp = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/process/HangUp.kt"))
        val kills = hangUp.filter { it.contains(".kill(") }
        assertEquals(kills.toString(), 1, kills.size)
        assertTrue(kills.single().contains("HangUps.SIGHUP"))
        assertTrue("the pid guard comes first", hangUp.any { it.contains("!HangUps.isSignallable(pid)") })
        assertEquals(listOf("io/github/v0rest/skein/process/HangUp.kt"), filesContaining(".kill(", "sendSignal", "destroyForcibly", ".destroy()"))
    }

    @Test
    fun noProcessEnvironmentChanges() {
        assertEquals(
            emptyList<String>(),
            filesContaining("envVariables(", "environment().put", "environment()[", "withEnvironment", "withEnvironmentVariable", "ProcessBuilder("),
        )
    }

    /** Processes start in one place, which runs git only. */
    @Test
    fun onlyGitKtStartsProcesses() {
        assertEquals(
            listOf("io/github/v0rest/skein/git/Git.kt"),
            filesContaining("GeneralCommandLine(", "OSProcessHandler(", "ExecUtil.", "Runtime.getRuntime().exec", "ProcessBuilder("),
        )
    }

    /**
     * Skein never reads or writes the project's VCS directory mappings: the platform saves them to a file many
     * repositories commit. Every line counts here, comments included.
     */
    @Test
    fun skeinNeverTouchesVcsMappings() {
        val needles = listOf("ProjectLevelVcsManager", "VcsDirectoryMapping", "setDirectoryMappings", "vcs.xml")
        val offenders = SourceRules.kotlinFiles(mainRoot).flatMap { f ->
            SourceRules.offenders(f.toFile().readLines(), needles).map { "${mainRoot.relativize(f)}: ${it.trim()}" }
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /**
     * Skein never forces a branch delete or anything else git does, but for one call: git's single force on `worktree
     * remove`, on one line of WorktreeGit.kt, called once, by the remove flow, where only the Worktrees section's Remove…
     * reaches it after git refused and the owner said yes to a second question (WorktreeRemoveKeptTest).
     */
    @Test
    fun gitForcesOnlyTheWorktreeRemoveTheOwnerConfirmedTwice() {
        assertEquals(emptyList<String>(), filesContaining("\"-f\"", "\"-D\"", "\"-ff\"", "\"-df\"", "\"-fd\"", "--force-"))
        assertEquals(listOf("io/github/v0rest/skein/git/WorktreeGit.kt"), filesContaining("--force"))
        val forced = SourceRules.codeLines(worktreeGit).filter { it.contains("--force") }
        assertEquals(forced.toString(), 1, forced.size)
        assertTrue(forced.single(), forced.single().contains("\"worktree\", \"remove\", \"--force\", \"--\", worktree.toString()"))
        // a call or a method reference, which could hand the force on to anything
        assertEquals(listOf("io/github/v0rest/skein/git/WorktreeGit.kt", "io/github/v0rest/skein/ui/WorktreeCloseFlow.kt"), filesContaining("forceRemove(", "::forceRemove").sorted())
        assertEquals(1, SourceRules.codeLines(closeFlow).count { it.contains("forceRemove(") || it.contains("::forceRemove") })
        assertEquals(emptyList<String>(), filesContaining("::forceRemove"))
        // and only after git refused for changed or untracked files
        assertTrue(SourceRules.codeLines(closeFlow).any { it.contains("!WorktreeGit.refusedForChanges(removed)") })
    }

    /**
     * The one lock Skein lifts: `git worktree unlock` on one line of WorktreeGit.kt ([liftLock]), with `--` before the
     * path and the conventions of Skein's other git calls in a working tree, called once, by the remove flow, for a lock
     * Claude Code left behind whose claude has ended, after the owner's yes (WorktreeRemoveKeptTest). Right before it
     * git lists the worktree once more and the lock must still be the one the question named; right after it comes the
     * plain remove, with nothing but the check of git's answer in between. Skein never locks a worktree itself and
     * never asks git for its force twice, which a locked worktree would take.
     */
    @Test
    fun gitLiftsALockInOnePlaceRightBeforeThePlainRemoveAndNeverForcesTwice() {
        assertEquals(listOf("io/github/v0rest/skein/git/WorktreeGit.kt"), filesContaining("\"unlock\""))
        val unlocks = SourceRules.codeLines(worktreeGit).filter { it.contains("\"unlock\"") }
        assertEquals(unlocks.toString(), 1, unlocks.size)
        assertTrue(
            unlocks.single(),
            unlocks.single().contains(
                "fun liftLock(repository: Path, worktree: Path): GitResult = " +
                    "git.run(repository, *NO_FSMONITOR, \"--no-optional-locks\", \"worktree\", \"unlock\", \"--\", worktree.toString())",
            ),
        )
        // a call or a method reference, which could hand the lift on to anything
        assertEquals(
            listOf("io/github/v0rest/skein/git/WorktreeGit.kt", "io/github/v0rest/skein/ui/WorktreeCloseFlow.kt"),
            filesContaining("liftLock(", "::liftLock").sorted(),
        )
        assertEquals(1, SourceRules.codeLines(worktreeGit).count { it.contains("liftLock(") })
        val flow = SourceRules.codeLines(closeFlow)
        assertEquals(1, flow.count { it.contains("liftLock(") })
        assertEquals(emptyList<String>(), filesContaining("::liftLock"))
        // the lock judged once more right before, and the plain remove right after, with no other git call or question
        val lift = flow.indexOfFirst { it.contains("val lifted = removals.liftLock(repository, worktree)") }
        assertTrue(lift > 0)
        assertTrue(flow[lift - 1], flow[lift - 1].contains("!= lift) return Removal.LockChanged"))
        val plainRemove = (lift until flow.size).first { flow[it].contains("removals.remove(repository, worktree)") }
        assertEquals(listOf("if (!lifted.ok) return Removal.NotLifted(reason(lifted))", "}"), flow.subList(lift + 1, plainRemove))
        // never a lock of Skein's own, never git's force twice
        assertEquals(emptyList<String>(), filesContaining("\"lock\"", "\"--lock\""))
        assertEquals(emptyList<String>(), filesContaining("-f -f", "\"-f\", \"-f\"", "\"--force\", \"--force\"", "\"--force\", \"-f\"", "\"-f\", \"--force\""))
        val forced = SourceRules.codeLines(worktreeGit).single { it.contains("--force") }
        assertEquals(forced, 1, Regex("--force|\"-f\"").findAll(forced).count())
    }

    /**
     * Every `git status` Skein runs turns `core.fsmonitor` off, since a repository's configuration can name a program
     * there; and the automatic look at the worktrees runs only in a trusted project.
     */
    @Test
    fun gitStatusNeverStartsAnFsmonitorAndTheLookWaitsForTrust() {
        // every git call that reads the work tree: status, ls-files, ls-tree, diff against it and worktree remove (which runs a status)
        val readers = listOf("\"status\"", "\"ls-files\"", "\"ls-tree\"", "\"diff\"", "\"worktree\", \"remove\"")
        val status = SourceRules.kotlinFiles(mainRoot).flatMap { f ->
            SourceRules.codeLines(f).filter { line -> (line.contains(".run(") || line.contains(".bytes(")) && readers.any { line.contains(it) } }
                .map { mainRoot.relativize(f).toString() to it }
        }
        assertTrue(status.toString(), status.isNotEmpty())
        assertEquals(emptyList<Pair<String, String>>(), status.filterNot { it.second.contains("NO_FSMONITOR") })
        val lines = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/threads/WorktreeHygiene.kt"))
        val trust = lines.indexOfFirst { it.contains("TrustedProjects.isProjectTrusted(project)") }
        val look = lines.indexOfFirst { it.contains("sweep.run(") }
        assertTrue("the trust check comes before the look", trust in 0 until look)
    }

    /**
     * Show Changes reads a worktree and writes nothing: its `git diff` against the working tree runs without colour and
     * without an external diff program, git's bytes are read in three places only, the two `-z` lists and a blob at the
     * merge-base by its object name, and nothing of it runs before the check that the owner trusts the project.
     */
    @Test
    fun showChangesOnlyReadsAndOnlyInATrustedProject() {
        val changes = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/git/WorktreeChanges.kt"))
        val diff = changes.single { it.contains("\"diff\"") }
        assertTrue(diff, listOf("NO_FSMONITOR", "\"--no-ext-diff\"", "\"--no-color\"", "\"--raw\"").all { diff.contains(it) })
        assertEquals(
            listOf("io/github/v0rest/skein/git/Git.kt", "io/github/v0rest/skein/git/WorktreeChanges.kt"),
            filesContaining(".bytes(").sorted(),
        )
        assertEquals(
            listOf(
                "val diff = git.bytes(worktree, *WorktreeGit.NO_FSMONITOR, \"diff\", \"--no-color\", \"--no-ext-diff\", \"--raw\", \"-z\", \"-M\", " +
                    "\"--no-abbrev\", mergeBase, \"--\")",
                "val untracked = git.bytes(worktree, *WorktreeGit.NO_FSMONITOR, \"ls-files\", \"--others\", \"--exclude-standard\", \"-z\")",
                "val content = git.bytes(worktree, \"cat-file\", \"blob\", id)",
            ),
            SourceRules.offenders(changes, listOf(".bytes(")),
        )
        val view = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/ui/WorktreeDiff.kt"))
        val trust = view.indexOfFirst { it.contains("TrustedProjects.isProjectTrusted(project)") }
        val read = view.indexOfFirst { it.contains("reader.read(") }
        assertTrue("the trust check comes before git runs", trust in 0 until read)
        // and that view, behind the check, is the one place that makes the reader
        val made = SourceRules.kotlinFiles(mainRoot).flatMap { f ->
            SourceRules.codeLines(f).filter { it.contains("WorktreeChangesReader(") && !it.startsWith("class ") }.map { mainRoot.relativize(f).toString() }
        }
        assertEquals(listOf("io/github/v0rest/skein/ui/WorktreeDiff.kt"), made)
    }

    /** Typing into a shell happens in one place, behind ClaudeCommand, with no way to widen what gets through. */
    @Test
    fun onlyTerminalAdapterTypesIntoAShell() {
        assertEquals(listOf("io/github/v0rest/skein/terminal/TerminalAdapter.kt"), filesContaining("sendText(", "createSendTextBuilder("))
        val adapter = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/terminal/TerminalAdapter.kt"))
        assertTrue("type() must check ClaudeCommand.isAllowed", adapter.any { it.contains("ClaudeCommand.isAllowed(command)") })
        assertEquals(emptyList<String>(), SourceRules.offenders(adapter, listOf("allowed:", "(String) -> Boolean")))
    }

    /** Developer smoke checks live outside the plugin, so the Marketplace build never carries them. */
    @Test
    fun noDeveloperHooksInThePlugin() {
        assertEquals(emptyList<String>(), filesContaining("SKEIN_SMOKE", "ApplicationManager.getApplication().exit"))
        val descriptor = Path.of("src/main/resources/META-INF/plugin.xml").toFile().readText()
        assertTrue("plugin.xml registers a smoke hook", !descriptor.contains("smoke", ignoreCase = true))
    }

    @Test
    fun mainSourcesExist() {
        assertTrue(SourceRules.kotlinFiles(mainRoot).isNotEmpty())
    }
}
