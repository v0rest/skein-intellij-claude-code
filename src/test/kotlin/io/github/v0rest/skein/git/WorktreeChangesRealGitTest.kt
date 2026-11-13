package io.github.v0rest.skein.git

import io.github.v0rest.skein.testutil.RealGit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * [WorktreeChangesReader] against the real git, in a temporary repository with a worktree under `.claude/worktrees`,
 * through [NoOptionalLocks] as Skein runs it: the file list and the base side's bytes. Skipped when git is not
 * installed.
 */
class WorktreeChangesRealGitTest {
    @get:Rule
    val folder = TemporaryFolder()

    private lateinit var git: RealGit
    private lateinit var reader: WorktreeChangesReader
    private lateinit var repo: Path
    private lateinit var wt: Path
    private lateinit var branchedAt: String

    private val oldText = (1..20).joinToString("") { "line $it of a file long enough to be found again\n" }

    @Before
    fun aRepositoryWithAWorktree() {
        git = RealGit(folder)
        assumeTrue(git.available)
        reader = WorktreeChangesReader(NoOptionalLocks(git))
        repo = git.repository("acme", files = mapOf("gone.c" to "int gone;\n", "old.c" to oldText)).toRealPath()
        ok(repo, "branch", "-M", "main")
        ok(repo, "worktree", "add", "-q", "-b", "worktree-task-1", "--", ".claude/worktrees/task-1")
        wt = repo.resolve(".claude/worktrees/task-1")
        branchedAt = ok(repo, "rev-parse", "main").stdout.trim()
    }

    private fun ok(dir: Path, vararg args: String): GitResult = git.run(dir, *args).also { assertTrue(args.joinToString(" ") + ": " + it.stderr, it.ok) }

    private fun commit(dir: Path, file: String, text: String) {
        write(dir, file, text)
        ok(dir, "add", "--", file)
        ok(dir, "commit", "-q", "-m", "change $file")
    }

    private fun write(dir: Path, file: String, text: String) {
        val path = dir.resolve(file)
        Files.createDirectories(path.parent)
        Files.writeString(path, text)
    }

    private fun read(recorded: String? = "main"): ChangesLookup = reader.read(repo, wt, recorded)

    private fun found(recorded: String? = "main"): ChangesLookup.Found = read(recorded).let { it as? ChangesLookup.Found ?: throw AssertionError(it.toString()) }

    private fun file(path: String): ChangedFile = found().files.single { it.path == path }

    private fun baseBytes(path: String): ByteArray = reader.blob(wt, file(path).base!!.id, limit = 1_000_000).let { it as? BlobRead.Loaded ?: throw AssertionError(it.toString()) }.bytes

    @Test
    fun committedStagedUnstagedAndUntrackedComeTogetherOnePerFile() {
        commit(wt, ".gitignore", "*.log\n")
        commit(wt, "one.c", "1\n")
        write(wt, "a.c", "int b;\n")
        write(wt, "staged.c", "s\n")
        ok(wt, "add", "--", "staged.c")
        write(wt, "new file.c", "n\n")
        write(wt, "debug.log", "ignored\n")
        Files.delete(wt.resolve("gone.c"))
        Files.createDirectories(wt.resolve("src"))
        ok(wt, "mv", "--", "old.c", "src/new.c")
        val found = found()
        assertEquals(Base("main", "refs/heads/main"), found.base)
        assertEquals(branchedAt, found.mergeBase)
        assertEquals(
            listOf(
                ".gitignore" to ChangeKind.ADDED,
                "a.c" to ChangeKind.MODIFIED,
                "gone.c" to ChangeKind.DELETED,
                "new file.c" to ChangeKind.UNTRACKED,
                "one.c" to ChangeKind.ADDED,
                "src/new.c" to ChangeKind.RENAMED,
                "staged.c" to ChangeKind.ADDED,
            ),
            found.files.map { it.path to it.kind },
        )
        assertEquals("old.c", file("src/new.c").from)
    }

    /**
     * Names git writes as they are with `-z`: a CR before a LF, a backspace, a tab, a leading colon, non-ASCII. They come
     * back byte for byte, untracked and tracked alike, so each names a file that is there.
     */
    @Test
    fun oddNamesComeBackAsTheyAreOnDisk() {
        val odd = listOf("cr\r\nlf.c", "bs\bx.c", "tab\there.c", ":colon.c", "ünïcode файл.c")
        for (name in odd) commit(repo, "tracked-$name", "base\n")
        ok(wt, "merge", "-q", "--ff-only", "main")
        for (name in odd) {
            write(wt, name, "new\n")
            write(wt, "tracked-$name", "changed\n")
        }
        val files = found().files
        assertEquals((odd.map { "tracked-$it" } + odd).sorted(), files.map { it.path })
        assertEquals(odd.map { ChangeKind.MODIFIED }, files.filter { it.path.startsWith("tracked-") }.map { it.kind })
        for (file in files) assertTrue(file.path, Files.isRegularFile(wt.resolve(file.path)))
    }

    @Test
    fun anUnstagedEditAloneIsListed() {
        write(wt, "a.c", "int b;\n")
        assertEquals(listOf(ChangeKind.MODIFIED), found().files.map { it.kind })
        assertArrayEquals("int a;\n".toByteArray(), baseBytes("a.c"))
    }

    @Test
    fun aCleanWorktreeHasNoChanges() {
        assertEquals(emptyList<ChangedFile>(), found().files)
    }

    /** Commits the base gets after the worktree branched off are not the worktree's changes: the merge-base counts. */
    @Test
    fun theBaseMovingOnLaterDoesNotShowAndItsOldContentIsTheBaseSide() {
        commit(repo, "a.c", "int main_side;\n")
        commit(repo, "main-only.c", "m\n")
        commit(wt, "one.c", "1\n")
        write(wt, "a.c", "int worktree_side;\n")
        val found = found()
        assertEquals(branchedAt, found.mergeBase)
        assertEquals(listOf("a.c", "one.c"), found.files.map { it.path })
        assertArrayEquals("int a;\n".toByteArray(), baseBytes("a.c"))
    }

    @Test
    fun aRenamesBaseSideIsTheOldFile() {
        ok(wt, "mv", "--", "old.c", "new.c")
        Files.writeString(wt.resolve("new.c"), oldText + "one more line\n")
        assertEquals(ChangedFile(ChangeKind.RENAMED, "new.c", "old.c", file("new.c").base), file("new.c"))
        assertArrayEquals(oldText.toByteArray(), baseBytes("new.c"))
    }

    /** The base side is the blob as git stored it: CRLF, a lone CR, a NUL and bytes that are not UTF-8 come back as they were. */
    @Test
    fun theBaseBytesComeBackExactly() {
        val bytes = byteArrayOf(
            'a'.code.toByte(), '\r'.code.toByte(), '\n'.code.toByte(), 'b'.code.toByte(), '\r'.code.toByte(), 'c'.code.toByte(),
            0, '\n'.code.toByte(), 0x80.toByte(), 0xC3.toByte(), 0x28, 0xFF.toByte(), 0xFE.toByte(), '\r'.code.toByte(),
        ) + ByteArray(256) { it.toByte() }
        Files.write(wt.resolve("raw.bin"), bytes)
        ok(wt, "-c", "core.autocrlf=false", "add", "--", "raw.bin")
        ok(wt, "commit", "-q", "-m", "raw")
        ok(repo, "merge", "-q", "--ff-only", "worktree-task-1") // the base now has it too
        Files.write(wt.resolve("raw.bin"), byteArrayOf(1, 2, 3))
        assertArrayEquals(bytes, baseBytes("raw.bin"))
    }

    @Test
    fun aBlobOverTheLimitIsNotRead() {
        write(wt, "a.c", "int b;\n")
        assertEquals(BlobRead.TooLarge(7), reader.blob(wt, file("a.c").base!!.id, limit = 6))
    }

    @Test
    fun withoutARecordedBaseTheUsualNamesCount() {
        commit(wt, "one.c", "1\n")
        assertEquals(Base("main", "refs/heads/main"), found(recorded = null).base)
    }

    @Test
    fun aDetachedHeadIsComparedWithTheBaseToo() {
        commit(wt, "one.c", "1\n")
        ok(wt, "checkout", "-q", "--detach")
        assertEquals(listOf("one.c"), found().files.map { it.path })
    }

    @Test
    fun aRepositoryOfItsOwnInsideIsOneFolderEntry() {
        val nested = wt.resolve("nested")
        Files.createDirectories(nested)
        ok(nested, "init", "-q")
        assertEquals(listOf("nested/" to ChangeKind.UNTRACKED), found().files.map { it.path to it.kind })
    }

    @Test
    fun aStoppedRebaseIsRefused() {
        commit(repo, "a.c", "int main_side;\n")
        commit(wt, "a.c", "int worktree_side;\n")
        assertTrue(!git.run(wt, "rebase", "main").ok)
        assertEquals(ChangesLookup.Refused("a rebase is in progress there - finish or abort it first"), read())
    }

    @Test
    fun aBranchWithNoCommitInCommonIsRefused() {
        ok(wt, "checkout", "-q", "--orphan", "lonely")
        ok(wt, "commit", "-q", "-m", "alone")
        assertEquals(ChangesLookup.Refused("it has no commit in common with main"), read())
    }

    /** A leftover folder: git would answer for the repository around it, so it is refused before git runs there. */
    @Test
    fun aFolderGitDoesNotListIsRefused() {
        val leftover = repo.resolve(".claude/worktrees/leftover")
        Files.createDirectories(leftover)
        assertEquals(ChangesLookup.Refused(WorktreeChangesReader.notListed(leftover)), reader.read(repo, leftover, "main"))
    }

    @Test
    fun aFolderThatIsGoneIsRefused() {
        val gone = repo.resolve(".claude/worktrees/task-9")
        assertEquals(ChangesLookup.Refused(WorktreeChangesReader.GONE), reader.read(repo, gone, "main"))
    }
}
