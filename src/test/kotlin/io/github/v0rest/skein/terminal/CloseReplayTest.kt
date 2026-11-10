package io.github.v0rest.skein.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * When Skein says no to a close because it ends thread tabs first, the IDE keeps every file of that close open; Skein
 * then closes the other files of it itself, so one Close All closes everything. Only with one editor window: with
 * several, the IDE does not say which window the close was for.
 */
class CloseReplayTest {
    private val threadTabs = setOf("t1", "t2")
    private val open = mutableSetOf("Main.kt", "t1", "README.md", "t2", "build.gradle.kts")

    private fun replay(request: List<String>, windows: Int = 1) =
        CloseReplay.filesToReplay(request, { it in threadTabs }, windows) { it in open }

    @Test
    fun withOneWindowTheOtherFilesOfTheCloseAreClosedByTheThreadTabsAreNot() {
        assertEquals(listOf("Main.kt", "README.md", "build.gradle.kts"), replay(listOf("Main.kt", "t1", "README.md", "t2", "build.gradle.kts")))
    }

    @Test
    fun withSeveralWindowsNothingIsClosedBySkein() {
        assertEquals(emptyList<String>(), replay(listOf("Main.kt", "t1", "README.md"), windows = 2))
        assertEquals(emptyList<String>(), replay(listOf("Main.kt", "t1", "README.md"), windows = 0))
    }

    /** A file closed (or reopened, so no longer the editor the close was about) since is left as it is. */
    @Test
    fun aFileNoLongerOpenIsSkipped() {
        open -= "README.md"
        assertEquals(listOf("Main.kt"), replay(listOf("Main.kt", "t1", "README.md")))
    }

    /** Close Others leaves the active file out of the close, and so does Skein: only what the IDE asked about goes. */
    @Test
    fun closeOthersKeepsTheActiveFile() {
        open += "Active.kt"
        val replayed = replay(listOf("Main.kt", "t1", "README.md"))
        assertEquals(listOf("Main.kt", "README.md"), replayed)
        assertEquals(false, "Active.kt" in replayed)
    }

    /** A close of thread tabs alone (a ×) leaves nothing else to close. */
    @Test
    fun aCloseOfThreadTabsAloneReplaysNothing() {
        assertEquals(emptyList<String>(), replay(listOf("t1")))
        assertEquals(emptyList<String>(), replay(listOf("t1", "t2")))
    }
}
