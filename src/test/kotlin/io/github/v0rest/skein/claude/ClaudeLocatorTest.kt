package io.github.v0rest.skein.claude

import io.github.v0rest.skein.claude.ClaudeLocator.Result
import io.github.v0rest.skein.paths.PathStyle.UNIX
import io.github.v0rest.skein.paths.PathStyle.WINDOWS
import org.junit.Assert.assertEquals
import org.junit.Test

/** Whether a `claude` typed into a terminal is found, as a pure function of PATH, the home folder and the files there. */
class ClaudeLocatorTest {
    private val home = "C:\\Users\\skein"
    private val installer = "C:\\Users\\skein\\.local\\bin"

    private fun windows(path: List<String>, vararg files: String) = ClaudeLocator.locate(WINDOWS, path, home) { it in files }

    @Test
    fun aClaudeOnPathIsFound() {
        assertEquals(Result.OnPath, windows(listOf("C:\\Windows", installer), "C:\\Users\\skein\\.local\\bin\\claude.exe"))
        assertEquals(Result.OnPath, windows(listOf("C:\\Users\\skein\\AppData\\Roaming\\npm"), "C:\\Users\\skein\\AppData\\Roaming\\npm\\claude.cmd"))
    }

    @Test
    fun aQuotedPathEntryIsRead() =
        assertEquals(Result.OnPath, windows(listOf("\"C:\\Program Files\\Claude\""), "C:\\Program Files\\Claude\\claude.exe"))

    @Test
    fun onlyTheInstallersFolderMeansItJustNeedsToJoinPath() =
        assertEquals(Result.OnlyInInstallerFolder(installer), windows(listOf("C:\\Windows"), "C:\\Users\\skein\\.local\\bin\\claude.exe"))

    @Test
    fun nothingAnywhereIsNotFound() = assertEquals(Result.NotFound, windows(listOf("C:\\Windows")))

    @Test
    fun aRelativeOrEmptyPathEntryIsNotTheCurrentFolderOfTheTerminal() {
        assertEquals(Result.NotFound, windows(listOf("", ".", "bin"), "bin\\claude.exe", ".\\claude.exe"))
    }

    @Test
    fun aHomeThatIsNoAbsolutePathFindsNoInstallerFolder() =
        assertEquals(Result.NotFound, ClaudeLocator.locate(WINDOWS, emptyList(), "skein") { true })

    @Test
    fun elsewhereTheInstallerFolderIsNotLookedAtAndTheNameHasNoExtension() {
        assertEquals(Result.OnPath, ClaudeLocator.locate(UNIX, listOf("/usr/bin", "/home/skein/.local/bin"), "/home/skein") { it == "/home/skein/.local/bin/claude" })
        assertEquals(Result.NotFound, ClaudeLocator.locate(UNIX, listOf("/usr/bin"), "/home/skein") { it == "/home/skein/.local/bin/claude" })
    }

    @Test
    fun pathIsSplitOnTheListSeparatorOfTheStyle() {
        assertEquals(listOf("C:\\Windows", "C:\\Program Files\\Git"), ClaudeLocator.splitPath("C:\\Windows;;C:\\Program Files\\Git;", WINDOWS))
        assertEquals(listOf("/usr/bin", "/bin"), ClaudeLocator.splitPath("/usr/bin::/bin", UNIX))
        assertEquals(emptyList<String>(), ClaudeLocator.splitPath(null, WINDOWS))
    }

    @Test
    fun aPathEntryWithSpacesAroundItIsRead() =
        assertEquals(Result.OnPath, windows(listOf("  C:\\Tools  "), "C:\\Tools\\claude.exe"))

    @Test
    fun theInstallersExeIsNoClaudeOffWindows() =
        assertEquals(Result.NotFound, ClaudeLocator.locate(UNIX, listOf("/usr/bin"), "/home/skein") { it == "/home/skein/.local/bin/claude.exe" })

    /** A PATH entry the file system refuses (a quote) must not lose the answer. */
    @Test
    fun anExistsCheckThatThrowsCountsAsNotThere() {
        val touchy = { path: String -> if (path.contains('"')) throw IllegalArgumentException(path) else path == "C:\\Tools\\claude.exe" }
        assertEquals(Result.OnPath, ClaudeLocator.locate(WINDOWS, listOf("C:\\Bad\"Dir", "C:\\Tools"), home, touchy))
        assertEquals(Result.NotFound, ClaudeLocator.locate(WINDOWS, listOf("C:\\Bad\"Dir"), home, touchy))
    }

    @Test
    fun theEnvironmentsPathIsFoundWhateverItsCase() {
        val exists = { path: String -> path == "C:\\Tools\\claude.exe" }
        assertEquals(Result.OnPath, ClaudeLocator.locateIn(WINDOWS, mapOf("Path" to "C:\\Windows;C:\\Tools"), home, exists))
        assertEquals(Result.OnPath, ClaudeLocator.locateIn(WINDOWS, mapOf("PATH" to "C:\\Tools"), home, exists))
        assertEquals(Result.NotFound, ClaudeLocator.locateIn(WINDOWS, mapOf("Other" to "C:\\Tools"), home, exists))
    }
}
