package io.github.v0rest.skein.threads

import io.github.v0rest.skein.testutil.FakeThreadTerminal
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Tabs the IDE's restore left in no tab, their terminals alive ([LostTabs.endThenRelease]) - whatever happens to their
 * ending, the tabs are released in the end, so no live claude stays behind with no tab.
 */
class LostTabsTest {
    private val terminal = FakeThreadTerminal()
    private val tab = terminal.open("t1", "/p", "acme", focus = false, start = false)
    private val released = mutableListOf<List<String>>()
    private val ran = mutableListOf<List<Ending>>()
    private val logged = mutableListOf<String>()

    private fun end(endings: suspend () -> List<Ending>, run: suspend (List<Ending>) -> Unit = { ran += it }) = runBlocking {
        LostTabs.endThenRelease(listOf("t1"), endings, run, release = { released += it }, log = { logged += it })
    }

    @Test
    fun anEndingThatThrowsStillReleasesTheTabs() {
        end(endings = { error("the coordinator could not look") })
        assertEquals(listOf(listOf("t1")), released)
        assertEquals(emptyList<List<Ending>>(), ran)
        assertEquals(1, logged.size)
    }

    /** A thread the coordinator does not know any more has nothing to end, and its tab goes all the same. */
    @Test
    fun noEndingReleasesTheTabs() {
        end(endings = { emptyList() })
        assertEquals(listOf(listOf("t1")), released)
    }

    /** The close sequence ends the threads and closes their tabs itself, so nothing is released twice. */
    @Test
    fun anEndingThatRanLeavesTheReleaseToTheCloseSequence() {
        val ending = Ending(tab, shell = null, claude = null)
        end(endings = { listOf(ending) })
        assertEquals(listOf(listOf(ending)), ran)
        assertEquals(emptyList<List<String>>(), released)
    }

    @Test
    fun aCloseSequenceThatThrowsStillReleasesTheTabs() {
        end(endings = { listOf(Ending(tab, shell = null, claude = null)) }, run = { error("the hang-up failed") })
        assertEquals(listOf(listOf("t1")), released)
        assertEquals(1, logged.size)
    }
}
