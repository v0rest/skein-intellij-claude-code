package io.github.v0rest.skein.claude

import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.testutil.CanonicalTemporaryFolder
import io.github.v0rest.skein.testutil.Fixture
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.util.UUID

/**
 * The History's rules for Windows paths, run on any OS: the scanner takes the path style, so a transcript written the
 * way Claude Code writes it on Windows (a `C:\...` cwd, JSON-escaped, in a folder named by its slug) is read on the Mac too.
 */
class HistoryScannerWindowsTest {
    @get:Rule
    val tmp = CanonicalTemporaryFolder()

    private lateinit var home: ClaudeHome
    private lateinit var scanner: HistoryScanner
    /** Roots that exist on no machine, so a run on the VM judges the text and the disk is never asked. */
    private val demo = "C:\\w\\0c3b7a1e-5d2f-4e8a-9b61-7f4d2a9c1e55\\demo-shop"
    private val inIdeSpelling = demo.replace('\\', '/')
    private val factsFolder = "C:\\Users\\skein\\demo-shop"

    @Before
    fun setUp() {
        home = ClaudeHome(tmp.root.toPath().resolve(".claude"))
        Files.createDirectories(home.projectsDir)
        scanner = HistoryScanner(home, PathStyle.WINDOWS)
    }

    private fun u(name: String): String = UUID.nameUUIDFromBytes(name.toByteArray()).toString()

    /** A transcript in the folder Claude Code names after [cwd], with that cwd in its first entry as Windows writes it. */
    private fun transcript(cwd: String, id: String, folder: String = Slug.of(cwd)) {
        val dir = Files.createDirectories(home.projectsDir.resolve(folder))
        Files.write(dir.resolve("$id.jsonl"), listOf("""{"type":"user","cwd":"${Fixture.jsonEscaped(cwd)}","sessionId":"$id","message":{"role":"user","content":"hi"}}"""))
    }

    private fun ids(vararg roots: String) = scanner.scan(roots.toList()).entries.map { it.sessionId }

    @Test
    fun theSlugOfAWindowsFolderTurnsEveryOtherCharacterIntoADash() {
        assertEquals("C--Users-skein-demo-shop", Slug.of(factsFolder))
        assertEquals("C--Users-skein-demo-shop--claude-worktrees-task-1", Slug.of("$factsFolder\\.claude\\worktrees\\task-1"))
    }

    @Test
    fun aSessionInTheProjectFolderIsListedWhicheverWayTheRootIsWritten() {
        transcript(demo, u("a"))
        assertEquals(listOf(u("a")), ids(demo))
        // the IDE hands the base over with forward slashes
        assertEquals(listOf(u("a")), ids(inIdeSpelling))
        assertEquals(listOf(u("a")), ids("$inIdeSpelling/"))
    }

    @Test
    fun theDriveLetterAndTheNamesMatchInAnyCase() {
        transcript(demo, u("a"))
        assertEquals(listOf(u("a")), ids(demo.lowercase()))
        assertEquals(listOf(u("a")), ids(demo.uppercase()))
    }

    @Test
    fun aSubfolderAndAWorktreeAreInsideTheProject() {
        transcript("$demo\\web\\src", u("sub"))
        transcript("$demo\\.claude\\worktrees\\task-1", u("wt"))
        assertEquals(setOf(u("sub"), u("wt")), ids(demo).toSet())
    }

    @Test
    fun aSiblingWithTheSamePrefixStaysOut() {
        transcript(demo, u("a"))
        transcript("${demo}2", u("sibling"))
        transcript("$demo-old\\api", u("old"))
        assertEquals(listOf(u("a")), ids(demo))
    }

    @Test
    fun anotherDriveStaysOut() {
        transcript(demo, u("a"))
        transcript("D" + demo.drop(1), u("other-drive"), folder = Slug.of(demo))
        assertEquals(listOf(u("a")), ids(demo))
    }

    @Test
    fun aRootThatIsNoAbsolutePathListsNothing() {
        transcript(demo, u("a"))
        assertEquals(emptyList<String>(), ids("demo-shop", "\\Users\\skein\\demo-shop", ""))
    }

    @Test
    fun aCwdWithADoubtfulPathStaysOut() {
        // a `..` that leaves the project could still land inside, only the disk would tell - a session is not shown on a
        // guess. The folder must not exist on any machine the test runs on: where it does (a Windows box with a real
        // C:\Users\skein\demo-shop), the disk resolves the `..` and the session rightly counts as inside
        val missing = "C:\\w\\${u("no-such-folder")}\\demo-shop"
        transcript("$missing\\..\\demo-shop", u("dotdot"), folder = Slug.of(missing))
        assertEquals(emptyList<String>(), ids(missing))
    }
}
