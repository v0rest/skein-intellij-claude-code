package io.github.v0rest.skein

import io.github.v0rest.skein.testutil.SourceRules
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
            .map { SourceRules.relative(mainRoot, it) }

    /** Skein sends nothing over the network, so no main source hands git a command that talks to a remote. */
    @Test
    fun noGitCommandThatTalksToARemote() {
        val talksToARemote = Regex(
            "\"(fetch|pull|push|ls-remote|clone)\"|\"remote\"\\s*,\\s*\"(show|set-head|prune|update)\"|\"submodule\"\\s*,\\s*\"update\"",
        )
        val offenders = SourceRules.kotlinFiles(mainRoot)
            .filter { f -> talksToARemote.containsMatchIn(SourceRules.codeLines(f).joinToString("\n")) }
            .map { SourceRules.relative(mainRoot, it) }
        assertEquals(emptyList<String>(), offenders)
    }

    /** The folder name as a string literal (`".claude"`, `"~/.claude/..."`), not the `skein.claude` package name. */
    @Test
    fun onlyClaudeHomeMentionsDotClaude() {
        assertEquals(
            listOf("io/github/v0rest/skein/claude/ClaudeHome.kt", "io/github/v0rest/skein/claude/Worktrees.kt"),
            filesContaining("\".claude", "/.claude").sorted(),
        )
    }

    /** The path rules work on text alone, so the macOS suite checks the Windows rules too and no answer hangs on a disk. */
    @Test
    fun pathRulesArePure() {
        val offenders = SourceRules.kotlinFiles(mainRoot.resolve("io/github/v0rest/skein/paths")).flatMap { f ->
            SourceRules.offenders(SourceRules.codeLines(f), listOf("Files.", "Path(", "Paths.", "java.nio.file", "java.io.", "toRealPath"))
        }
        assertEquals(emptyList<String>(), offenders)
    }

    /**
     * Paths are compared and taken apart through PathRules, which knows both separators. A `/` by hand stays only where
     * git's own names are read or built, which use `/` on every system, and in the Unix branch of FolderPaths, so a new
     * one anywhere else is a Windows bug waiting. The list is per file, so a new use in one of these files is the
     * reviewer's to catch.
     */
    @Test
    fun slashesAreHandledByHandOnlyWhereGitsOwnNamesAre() {
        val byHand = filesContaining(
            "substringAfterLast('/')", "substringAfterLast(\"/\")", "substringBeforeLast('/')", "substringBeforeLast(\"/\")",
            "lastIndexOf('/')", "lastIndexOf(\"/\")", "split('/')", "split(\"/\")", "trimEnd('/')", "removeSuffix(\"/\")",
            "startsWith(\"/\")", "endsWith(\"/\")",
        )
        assertEquals(
            listOf(
                "io/github/v0rest/skein/git/WorktreeCounts.kt", // a branch name's rules
                "io/github/v0rest/skein/git/WorktreeExcludes.kt", // exclude lines and the guard's path in git's tree
                "io/github/v0rest/skein/git/WorktreeGit.kt", // git's status names, a folder ending in `/`
                "io/github/v0rest/skein/ui/FolderPaths.kt", // the Unix branch
                "io/github/v0rest/skein/ui/WorktreeDiff.kt", // names relative to the repository, as git lists them
            ),
            byHand.filterNot { it.startsWith("io/github/v0rest/skein/paths/") }.sorted(),
        )
    }

    /**
     * The sound rules - what is stored, what the OS offers, play or beep, the list's items, which output plays - work on
     * what they are handed, so they are tested for all three systems on any one. The disk is read in SoundDisk. Java Sound
     * is touched in JavaSoundOutput alone, which only ever reads a file, and the player makes that output only off macOS -
     * on macOS Java Sound opens the input side too, and the system asks for the microphone. There the sound goes through
     * MacSystemSound, which never mentions Java Sound.
     */
    @Test
    fun soundRulesArePureAndJavaSoundStaysOffMacOs() {
        val sound = mainRoot.resolve("io/github/v0rest/skein/sound")
        val pure = listOf("SoundChoice.kt", "SoundCatalog.kt", "SoundPlan.kt", "SoundMenu.kt", "SoundWalk.kt", "SoundSlot.kt", "SoundOutput.kt")
            .map { sound.resolve(it) }
        val offenders = pure.flatMap { f ->
            SourceRules.offenders(
                SourceRules.codeLines(f),
                listOf("Files.", "Path.of", "Paths.", "java.nio.file", "java.io.", "javax.sound", "AudioSystem", "com.sun.jna", "Native."),
            ).map { "${SourceRules.relative(mainRoot, f)}: $it" }
        }
        assertEquals(emptyList<String>(), offenders)
        assertEquals(listOf("io/github/v0rest/skein/sound/JavaSoundOutput.kt"), filesContaining("javax.sound", "AudioSystem"))
        val javaSound = SourceRules.codeLines(sound.resolve("JavaSoundOutput.kt"))
        assertEquals(listOf("Files.newInputStream(Path.of(path)).buffered().use { input ->"), SourceRules.offenders(javaSound, listOf("Files.", "Path.of")))
        // every line of the macOS file, its imports included
        val mac = sound.resolve("MacSystemSound.kt").toFile().readLines()
        assertEquals(emptyList<String>(), SourceRules.offenders(mac, listOf("javax.sound", "AudioSystem", "Clip", "JavaSoundOutput", "java.io.", "java.nio")))
        // the output is chosen in one place, by the tested rule, and made only there
        assertEquals(listOf("io/github/v0rest/skein/sound/SoundPlayer.kt"), filesContaining("JavaSoundOutput(", "MacSoundOutput(", "SoundOutputs.kind("))
        val player = SourceRules.codeLines(sound.resolve("SoundPlayer.kt"))
        assertEquals(
            listOf(
                "output ?: when (SoundOutputs.kind(SoundOs.current)) {",
                "SoundOutputs.Kind.SYSTEM_SOUND_SERVICE -> MacSoundOutput()",
                "SoundOutputs.Kind.JAVA_SOUND -> JavaSoundOutput()",
            ),
            SourceRules.offenders(player, listOf("JavaSoundOutput(", "MacSoundOutput(", "SoundOutputs.kind(")),
        )
        // the notifier hands the event's own choice to the player, and the bare beep is the player's fallback alone
        assertEquals(listOf("io/github/v0rest/skein/sound/SoundPlayer.kt"), filesContaining(".beep()"))
        val notifier = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/notify/ThreadNotifier.kt"))
        assertEquals(
            listOf("if (NotificationText.sound(kind, settings)) SoundPlayer.getInstance().play(kind, NotificationText.soundChoice(kind, settings))"),
            SourceRules.offenders(notifier, listOf("SoundPlayer.")),
        )
    }

    /**
     * The settings page never reads the disk on the EDT - a share that hangs would freeze the IDE. Its sounds and the
     * checks of the stored and chosen files go through SoundPicker.onPool, which reads on a pooled thread and only shows
     * the answer on the EDT, and the player reads in its own pooled work.
     */
    @Test
    fun theSoundsPageReadsTheDiskOffTheEdt() {
        assertEquals(
            listOf("io/github/v0rest/skein/settings/SoundPicker.kt", "io/github/v0rest/skein/sound/SoundPlayer.kt"),
            filesContaining("SoundDisk.", "SoundDisk::").sorted(),
        )
        val picker = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/settings/SoundPicker.kt"))
        assertEquals(
            listOf(
                "onPool({ mapOf(file to SoundDisk.problem(file, emptyList())) }) { mark(it) }",
                "val sounds = SoundDisk.systemSounds()",
                "sounds.map { it.name } to choices.associateWith { SoundDisk.problem(it, sounds) }",
            ),
            SourceRules.offenders(picker, listOf("SoundDisk.", "SoundDisk::")),
        )
        val load = picker.indexOf("val sounds = SoundDisk.systemSounds()")
        assertEquals("onPool({", picker[load - 1])
        val onPool = picker.indexOf("private fun <T> onPool(read: () -> T, show: (T) -> Unit) {")
        assertEquals(
            listOf(
                "val application = ApplicationManager.getApplication()", "application.executeOnPooledThread {", "val answer = read()",
                "application.invokeLater({ show(answer) }, ModalityState.any())",
            ),
            picker.subList(onPool + 1, onPool + 5),
        )
        val player = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/sound/SoundPlayer.kt"))
        assertEquals(listOf("val plan = SoundDisk.plan(choice)"), SourceRules.offenders(player, listOf("SoundDisk.", "SoundDisk::")))
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
            SourceRules.offenders(SourceRules.codeLines(f), writeApis).map { "${SourceRules.relative(mainRoot, f)}: $it" }
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

    /**
     * The IDE's "closes to reopen" mark lives in `FileEditorManagerKeys`, in the platform's implementation module, where
     * several keys are `@ApiStatus.Internal`, so Skein touches it in ReopenMark alone, by the field itself and never looked up
     * by its name, so the dependency stays in sight and a platform change breaks the build, not a thread.
     */
    @Test
    fun theIdesReopenMarkIsTouchedInReopenMarkAlone() {
        val reopenMark = listOf("io/github/v0rest/skein/terminal/ReopenMark.kt")
        assertEquals(reopenMark, filesContaining("FileEditorManagerKeys"))
        assertEquals(reopenMark, filesContaining("CLOSING_TO_REOPEN"))
        assertEquals(emptyList<String>(), filesContaining("findKeyByName"))
    }

    /**
     * Version Control is a bundled plugin a user can turn off, and Skein runs without it. Its classes are touched in
     * BranchSwitchListener alone, which only the optional descriptor registers, loaded while Version Control is there, so
     * nothing that always loads needs them.
     */
    @Test
    fun versionControlStaysInTheOptionalPart() {
        val listener = listOf("io/github/v0rest/skein/threads/BranchSwitchListener.kt")
        assertEquals(listener, filesContaining("com.intellij.openapi.vcs", "git4idea", "com.intellij.dvcs", "com.intellij.vcs."))
        // no code that always loads names the listener, the optional descriptor alone registers it
        assertEquals(listener, filesContaining("BranchSwitchListener"))
        val descriptor = Path.of("src/main/resources/META-INF/plugin.xml").toFile().readText()
        assertTrue(descriptor.contains("<depends optional=\"true\" config-file=\"io.github.v0rest.skein-branches.xml\">com.intellij.modules.vcs</depends>"))
        for (word in listOf("com.intellij.openapi.vcs", "BranchSwitchListener", "intellij.platform.vcs", "<depends>com.intellij.platform.vcs")) {
            assertFalse(word, descriptor.contains(word))
        }
        val optional = Path.of("src/main/resources/META-INF/io.github.v0rest.skein-branches.xml").toFile().readText()
        assertTrue(optional.contains("<listener class=\"io.github.v0rest.skein.threads.BranchSwitchListener\" topic=\"com.intellij.openapi.vcs.BranchChangeListener\"/>"))
    }

    @Test
    fun noInternalTerminalSessionUse() {
        assertEquals(emptyList<String>(), filesContaining("getSessionDeferred", "sessionDeferred", "TerminalSession", "getProcessId"))
    }

    /**
     * The native libraries Skein loads are IOKit for the power assertion (SleepBlocker), libc for the SIGHUP a closing
     * thread's claude and shell get (HangUp), and CoreFoundation and AudioToolbox for a sound on macOS (MacSystemSound),
     * and nothing else touches JNA loading.
     */
    @Test
    fun onlySleepBlockerHangUpAndTheMacSoundLoadNativeLibraries() {
        assertEquals(
            listOf("io/github/v0rest/skein/power/SleepBlocker.kt", "io/github/v0rest/skein/process/HangUp.kt", "io/github/v0rest/skein/sound/MacSystemSound.kt"),
            filesContaining("Native.load(", "NativeLibrary.getInstance(").sorted(),
        )
        val mac = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/sound/MacSystemSound.kt"))
        assertEquals(
            listOf(
                "private val coreFoundation by lazy { Native.load(\"CoreFoundation\", CoreFoundationLib::class.java) }",
                "private val audioToolbox by lazy { Native.load(\"AudioToolbox\", AudioToolbox::class.java) }",
            ),
            SourceRules.offenders(mac, listOf("Native.load(")),
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
            SourceRules.offenders(f.toFile().readLines(), needles).map { "${SourceRules.relative(mainRoot, f)}: ${it.trim()}" }
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
                .map { SourceRules.relative(mainRoot, f) to it }
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
     * merge-base by its object name, and nothing of it runs before the check that the owner trusts the project. The one
     * thing it saves is the editor's unsaved changes to files in that worktree, right before git reads it.
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
            SourceRules.codeLines(f).filter { it.contains("WorktreeChangesReader(") && !it.startsWith("class ") }.map { SourceRules.relative(mainRoot, f) }
        }
        assertEquals(listOf("io/github/v0rest/skein/ui/WorktreeDiff.kt"), made)
        // the one save of the editor's documents: before the list is read, only those of files in that worktree
        assertEquals(listOf("io/github/v0rest/skein/ui/WorktreeDiff.kt"), filesContaining("saveDocument", "saveAllDocuments"))
        val saves = view.filter { it.contains("saveDocument") }
        assertEquals(saves.toString(), 1, saves.size)
        assertTrue(saves.single(), saves.single().contains("documents.saveDocuments {") && saves.single().contains("ShowChanges.inWorktree("))
    }

    /**
     * Skein types into a terminal in two places only: into a shell in TerminalAdapter.type, behind ClaudeCommand, and into
     * a running claude in ClaudeRename.send, `/rename` and a name its rule let through. Neither can be widened.
     */
    @Test
    fun onlyTwoPlacesTypeIntoATerminal() {
        assertEquals(
            listOf("io/github/v0rest/skein/terminal/ClaudeRename.kt", "io/github/v0rest/skein/terminal/TerminalAdapter.kt"),
            filesContaining("sendText(", "createSendTextBuilder(").sorted(),
        )
        val adapter = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/terminal/TerminalAdapter.kt"))
        assertEquals(listOf("view.sendText(command + \"\\n\")"), SourceRules.offenders(adapter, listOf("sendText(", "createSendTextBuilder(")))
        assertTrue("type() must check ClaudeCommand.isAllowed", adapter.any { it.contains("ClaudeCommand.isAllowed(command)") })
        assertEquals(emptyList<String>(), SourceRules.offenders(adapter, listOf("allowed:", "(String) -> Boolean")))
        // the rename: one builder line, which sends the line built from a name the rule checked right before
        val rename = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/terminal/ClaudeRename.kt"))
        val sends = SourceRules.offenders(rename, listOf("sendText(", "createSendTextBuilder(", ".send("))
        assertEquals(listOf("view.createSendTextBuilder().shouldExecute().send(line)"), sends)
        val send = rename.indexOf(sends.single())
        assertEquals("val line = line(name)", rename[send - 1])
        assertEquals("internal fun send(view: TerminalView, name: String) {", rename[send - 2])
        assertTrue("line() must check the rule", rename.any { it.contains("require(isAllowed(name))") })
        // never bracketed paste or another key in front, which would change what claude takes the line for (a builder
        // exists only where createSendTextBuilder is called, which the lines above pin)
        assertEquals(emptyList<String>(), filesContaining("useBracketedPasteMode", "requireBracketedPasteMode", "sendEndKeyBeforeText"))
    }

    /**
     * `/rename` is typed into a running claude from one call in TerminalAdapter.sendRename, right after its last look in
     * the same EDT turn and the clear of the waiting mark, and the command itself is written in ClaudeRename alone. The
     * look hands every part of the tab's state to ClaudeRename.tabReady, and the look off the EDT before it goes through
     * RenameGate.lastLook, both unit-tested.
     */
    @Test
    fun theRenameGoesFromOnePlaceRightAfterItsLastLook() {
        assertEquals(listOf("io/github/v0rest/skein/terminal/ClaudeRename.kt"), filesContaining("\"/rename"))
        assertEquals(listOf("io/github/v0rest/skein/terminal/TerminalAdapter.kt"), filesContaining("ClaudeRename.send(", "ClaudeRename::send"))
        val adapter = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/terminal/TerminalAdapter.kt"))
        val calls = SourceRules.offenders(adapter, listOf("ClaudeRename.send("))
        assertEquals(listOf("ClaudeRename.send(view, name)"), calls)
        val send = adapter.indexOf(calls.single())
        // the look on the EDT, the fresh look off it, and the last look in the very EDT turn of the send, in that order -
        // on the EDT itself, where the Rename dialog applies its answer, so the claim and the send cannot be split
        assertEquals(
            listOf(
                "val edt = Dispatchers.EDT + ModalityState.any().asContextElement()",
                "if (!withContext(edt) { readyForRename(handle) }) return false",
                "if (!withContext(Dispatchers.IO) { stillIdle() }) return false",
                "return withContext(edt) {",
                "if (!readyForRename(handle)) return@withContext false",
                "if (!beforeSend()) return@withContext false",
            ),
            adapter.subList(send - 6, send),
        )
        // the tab's look: every part of it handed to the tested rule, and nothing else decides
        val ready = adapter.indexOf("private fun readyForRename(handle: Handle): Boolean =")
        assertEquals(
            listOf(
                "ClaudeRename.tabReady(", "disposed = handle.disposed,", "ending = handle.endingSince != null,", "released = handle.released,",
                "running = handle.session?.value == TerminalViewSessionState.Running,", "inputClean = inputClean(handle),", ")",
            ),
            adapter.subList(ready + 1, ready + 8),
        )
        val service = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/threads/ThreadService.kt"))
        assertEquals(
            listOf("RenameGate.lastLook(claude, mapper.startOf(claude.pid), anchor, mapper.anchorOf(claude.pid), file, sessionId, vim)"),
            SourceRules.offenders(service, listOf("RenameGate.lastLook(", "RenameGate.stillIdle(")),
        )
        // a tab's input starts dirty when Skein opens it and whenever Skein starts claude in it, never clean by hand
        assertEquals(
            listOf("handle.draft = DraftGuard.LAUNCHED", "handle.draft?.let { handle.draft = DraftGuard.LAUNCHED }"),
            SourceRules.offenders(adapter, listOf("DraftGuard.LAUNCHED", "DraftGuard.Draft(", "clean = true")),
        )
    }

    /**
     * The adapter and the service only gather what they hear and read for the rename; every decision is the tested rules'
     * (DraftGuard, RenameGate), handed exactly these values, so gutting the glue fails here. The service's state for it
     * is set up before its init starts the loops that use it.
     */
    @Test
    fun theRenameGlueHandsEveryDecisionToTheTestedRules() {
        val adapter = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/terminal/TerminalAdapter.kt"))
        for (line in listOf(
            "return DraftGuard.inputClean(handle.draft, handle.provenSentAt, monotonicMillis())",
            "handle.provenSentAt = DraftGuard.proven(handle.draft, since, handle.provenSentAt)",
            "this.enterMaySend = enterMaySend",
            "val draft = DraftGuard.settled(handle.draft ?: return, handle.provenSentAt)",
            "handle.draft = DraftGuard.next(draft, key, enterMaySend = handle.id in enterMaySend, at = monotonicMillis(), wallAt = System.currentTimeMillis())",
            "Toolkit.getDefaultToolkit().addAWTEventListener(composedText, AWTEvent.INPUT_METHOD_EVENT_MASK)",
            "if (!ComposedText.counts(event.id)) return@AWTEventListener",
            "val handle = ComposedText.holderOf(event.source, handles.values) { it.view?.component }",
            "if (handle != null) onKey(handle, DraftGuard.Key.OTHER)",
            "if (DraftGuard.touchesInput(ActionManager.getInstance().getId(action))) onKey(handle, DraftGuard.Key.OTHER)",
            "onKey(handle, DraftGuard.classify(key.id, key.keyCode, key.keyChar, key.modifiersEx))",
        )) {
            assertEquals(line, 1, adapter.count { it == line })
        }
        val serviceFile = mainRoot.resolve("io/github/v0rest/skein/threads/ThreadService.kt")
        val service = SourceRules.codeLines(serviceFile)
        for (line in listOf(
            "val seen = RenameGate.seen(threads)",
            "terminal.claudeSeen(seen.enterMaySend, seen.atWorkSince)",
            "val vimMode = target != null && vimByThread[target.thread.id] == true",
            "when (RenameGate.decide(name, target?.runsSession == true, target?.thread?.state, target?.claude != null, clean, vimMode)) {",
            "vimByThread = latest.associate { it.id to editorMode.vim(RenameGate.folders(it)) }",
            "stillIdle = { claudeStillIdle(claude, anchor, sessionId, RenameGate.folders(target.thread)) },",
            "val vim = editorMode.vim(folders)",
            "beforeSend = { SessionMarksService.getInstance(project).claimClaudeRename(sessionId, name) },",
        )) {
            assertEquals(line, 1, service.count { it == line })
        }
        // the dialog's box and answer go through the tested rules, the answer judged on the marks as they are then
        val menu = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/ui/actions/ThreadMenu.kt"))
        for (line in listOf(
            "val before = MenuState.SessionNames(current.names[sessionId], current.claudeRenames[sessionId])",
            "val box = MenuState.renameBox(waiting = before.waiting != null, remembered = settings.renameInClaude, vimMode = vimMode)",
            "marks.update { MenuState.applyRename(it, sessionId, dialog.entered, shown, fallback, before, dialog.inClaude, boxDisabled = !box.enabled) }",
        )) {
            assertEquals(line, 1, menu.count { it == line })
        }
        val dialog = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/ui/RenameDialog.kt"))
        for (line in listOf(
            "private val claudeBox = JBCheckBox(\"Also rename in Claude Code\", box.ticked).apply { isEnabled = box.enabled }",
            "get() = claudeBox.isSelected && box.enabled",
            "cell(claudeBox).comment(box.comment)",
        )) {
            assertEquals(line, 1, dialog.count { it == line })
        }
        // property initialisers run in order with init, whose loops would otherwise meet them still unset
        val raw = serviceFile.toFile().readLines().map { it.trim() }
        val init = raw.indexOf("init {")
        for (declaration in listOf(
            "private val editorMode = ClaudeEditorModeWatch(ClaudeHome.default())", "private var vimByThread: Map<String, Boolean> = emptyMap()",
            "private var editorModeFailed = false", "private val renamesOnTheirWay = HashSet<String>()", "private var renameLookFailed = false",
            "private var claudeSeenFailed = false",
        )) {
            assertTrue(declaration, raw.indexOf(declaration) in 0 until init)
        }
    }

    /**
     * A native library loaded through JNA stays in the IDE until it exits, so a plugin that loads one cannot be unloaded
     * and must ask for a restart to be installed, updated or removed - or the IDE says it "didn't unload fully".
     */
    @Test
    fun nativeLibrariesMeanTheRestartIsDeclared() {
        val descriptor = Path.of("src/main/resources/META-INF/plugin.xml").toFile().readText()
        val loadsNative = filesContaining("Native.load(").isNotEmpty()
        assertTrue("main loads a native library, so plugin.xml needs require-restart=\"true\"",
            !loadsNative || Regex("<idea-plugin[^>]*require-restart=\"true\"").containsMatchIn(descriptor))
    }

    /** Developer smoke checks live outside the plugin, so the Marketplace build never carries them. */
    @Test
    fun noDeveloperHooksInThePlugin() {
        assertEquals(emptyList<String>(), filesContaining("SKEIN_SMOKE", "ApplicationManager.getApplication().exit"))
        val descriptor = Path.of("src/main/resources/META-INF/plugin.xml").toFile().readText()
        assertTrue("plugin.xml registers a smoke hook", !descriptor.contains("smoke", ignoreCase = true))
    }

    /**
     * The fake claude in tools/fake-claude and its variables are for the tests and the sandbox only. No main source and
     * no line of plugin.xml names them, so the plugin never behaves differently next to the fake.
     */
    @Test
    fun theFakeClaudeStaysOutOfThePlugin() {
        assertEquals(emptyList<String>(), filesContaining("fake-claude", "FAKE_CLAUDE", "--fake-check-scenario"))
        val descriptor = Path.of("src/main/resources/META-INF/plugin.xml").toFile().readText()
        assertTrue("plugin.xml names the fake claude", !descriptor.contains("fake-claude") && !descriptor.contains("FAKE_CLAUDE"))
    }

    @Test
    fun mainSourcesExist() {
        assertTrue(SourceRules.kotlinFiles(mainRoot).isNotEmpty())
    }

    /**
     * A Swing label shows a text that starts with <html> as HTML, remote images included, and Show Changes' messages can
     * start with a worktree file's name - every one of them goes through ShowChanges.plain.
     */
    @Test
    fun showChangesMessagesNeverReachSwingAsMarkup() {
        val lines = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/ui/WorktreeDiff.kt")).filter { "MessageDiffRequest(" in it }
        assertTrue(lines.isNotEmpty())
        assertEquals(emptyList<String>(), lines.filter { "plain(" !in it })
    }

    /** Claude Code's `.claude.json` holds the user's account: only the trust reader reads it, through ClaudeHome. */
    @Test
    fun onlyFolderTrustReadsTheClaudeConfig() {
        assertEquals(
            listOf("io/github/v0rest/skein/claude/ClaudeHome.kt", "io/github/v0rest/skein/claude/FolderTrust.kt"),
            filesContaining("readConfig(", "readAtMost(", "configFile").sorted(),
        )
    }

    /** Nothing read from `.claude.json` may reach a log - only each folder's trust leaves the reader. */
    @Test
    fun folderTrustNeverLogs() {
        val lines = SourceRules.codeLines(mainRoot.resolve("io/github/v0rest/skein/claude/FolderTrust.kt"))
        assertEquals(emptyList<String>(), SourceRules.offenders(lines, listOf("logger", "LOG.", "thisLogger", "println", "System.out", "System.err", "printStackTrace")))
    }
}
