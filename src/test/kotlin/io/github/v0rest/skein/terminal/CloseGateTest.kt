package io.github.v0rest.skein.terminal

import io.github.v0rest.skein.terminal.CloseGate.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What Skein's close check answers when the IDE closes a tab (its ×, Close All, Close Others): a Skein thread tab whose
 * shell still runs stays open until Skein has ended the thread there, every other close goes ahead at once.
 */
class CloseGateTest {
    private fun decide(
        skeinTab: Boolean = true,
        closing: Boolean = false,
        released: Boolean = false,
        running: Boolean = true,
        views: Int = 1,
        endingForMillis: Long? = null,
    ) = CloseGate.decide(skeinTab, closing, released, running, views, endingForMillis)

    @Test
    fun aRunningThreadTabIsEndedFirst() {
        assertEquals(Decision.END_FIRST, decide())
    }

    @Test
    fun anyOtherTabClosesAtOnce() {
        assertEquals(Decision.PASS, decide(skeinTab = false))
    }

    /** The shell exited, or never started (a restored tab not opened yet): nothing is left to hang. */
    @Test
    fun aThreadTabWhoseSessionDoesNotRunClosesAtOnce() {
        assertEquals(Decision.PASS, decide(running = false))
    }

    /** Skein's own close after the ending, the timeout included, must get through. */
    @Test
    fun aTabSkeinHandsBackClosesAtOnce() {
        assertEquals(Decision.PASS, decide(released = true))
        assertEquals(Decision.PASS, decide(released = true, endingForMillis = 100))
    }

    /** Closing the project or quitting the IDE is never held up, not even by a running thread. */
    @Test
    fun closingTheProjectOrTheIdeIsNeverHeldUp() {
        assertEquals(Decision.PASS, decide(closing = true))
        assertEquals(Decision.PASS, decide(closing = true, endingForMillis = 100))
    }

    /** Closing one of two views of the same tab leaves the terminal running in the other. */
    @Test
    fun closingOneOfTwoViewsDoesNotEndTheThread() {
        assertEquals(Decision.PASS, decide(views = 2))
    }

    /** A second × while Skein is ending the thread keeps the tab and starts nothing new. */
    @Test
    fun aSecondCloseWhileEndingWaits() {
        assertEquals(Decision.WAIT, decide(endingForMillis = 0))
        assertEquals(Decision.WAIT, decide(endingForMillis = CloseGate.ENDING_GRACE_MILLIS - 1))
    }

    /**
     * An ending that never got to its close (something failed and its mark stayed) gives up: past the grace the next ×
     * closes the tab the old way instead of holding it again.
     */
    @Test
    fun anEndingThatNeverClosedLetsTheNextCloseThroughAfterTheGrace() {
        assertEquals(Decision.PASS, decide(endingForMillis = CloseGate.ENDING_GRACE_MILLIS))
        assertEquals(Decision.PASS, decide(endingForMillis = CloseGate.ENDING_GRACE_MILLIS * 10))
    }

    private fun tab(id: String, released: Boolean = false, running: Boolean = true, views: Int = 1, endingForMillis: Long? = null) =
        CloseGate.Tab(id, released, running, views, endingForMillis)

    /** A × on one running thread tab: Skein takes that tab. */
    @Test
    fun aBatchOfOneRunningTabIsTaken() {
        assertEquals(CloseGate.Batch(pass = false, take = listOf("t1"), waiting = emptyList()), CloseGate.decideBatch(listOf(tab("t1")), closing = false))
    }

    /**
     * Close All with running threads in it: Skein takes every thread tab of it, the ones not running too (a restored
     * tab never opened), so all of them close in one batch with the tab in front last; the IDE keeps the whole close
     * open, so a tab left out would stay. Left out: a tab Skein closes right now, and one also open in another window.
     */
    @Test
    fun aCloseAllWithRunningThreadsTakesEveryThreadTabOfIt() {
        val batch = CloseGate.decideBatch(
            listOf(tab("t1"), tab("r1", running = false), tab("t2"), tab("x1", released = true), tab("v1", views = 2)),
            closing = false,
        )
        assertEquals(CloseGate.Batch(pass = false, take = listOf("t1", "r1", "t2"), waiting = emptyList()), batch)
    }

    /** No thread tab of the close runs: it goes ahead as the IDE decides. */
    @Test
    fun aCloseWithoutRunningThreadsPasses() {
        assertTrue(CloseGate.decideBatch(listOf(tab("r1", running = false), tab("t1", released = true)), closing = false).pass)
        assertTrue(CloseGate.decideBatch(listOf(tab("t1"), tab("t2")), closing = true).pass)
    }

    /** Tabs Skein is ending already stay open and close when that is done; nothing new starts for them. */
    @Test
    fun tabsAlreadyBeingEndedWait() {
        assertEquals(
            CloseGate.Batch(pass = false, take = emptyList(), waiting = listOf("t1")),
            CloseGate.decideBatch(listOf(tab("t1", endingForMillis = 100)), closing = false),
        )
        assertEquals(
            CloseGate.Batch(pass = false, take = listOf("t2", "r1"), waiting = listOf("t1")),
            CloseGate.decideBatch(listOf(tab("t1", endingForMillis = 100), tab("t2"), tab("r1", running = false)), closing = false),
        )
    }

    /** Every project is asked about its thread tabs in a close, not only up to the first that says no. */
    @Test
    fun askAllAsksAboutEveryItem() {
        val asked = mutableListOf<String>()
        assertFalse(CloseGate.askAll(listOf("a", "b", "c")) { asked += it; it == "c" })
        assertEquals(listOf("a", "b", "c"), asked)
        assertTrue(CloseGate.askAll(listOf("a", "b")) { true })
        assertTrue(CloseGate.askAll(emptyList<String>()) { false })
    }

    private fun terminalWindowClose(
        running: Boolean = true,
        released: Boolean = false,
        endingForMillis: Long? = null,
        closing: Boolean = false,
        move: Boolean = false,
    ) = CloseGate.decideTerminalWindowClose(CloseGate.Tab("t1", released, running, views = 1, endingForMillis), closing, move)

    /** Move to Editor and a drag out of the Terminal tool window remove the content too, but nothing closes. */
    @Test
    fun aMoveOutOfTheTerminalWindowPasses() {
        val batch = terminalWindowClose(move = true)
        assertTrue(batch.pass)
        assertEquals(emptyList<String>(), batch.take)
    }

    @Test
    fun projectClosingPassesInTheTerminalWindow() {
        assertTrue(terminalWindowClose(closing = true).pass)
    }

    @Test
    fun aRunningTabInTheTerminalWindowIsEndedFirst() {
        val batch = terminalWindowClose()
        assertFalse(batch.pass)
        assertEquals(listOf("t1"), batch.take)
        assertEquals(emptyList<String>(), batch.waiting)
    }

    /** A restored thread whose shell never started has nothing to hang. */
    @Test
    fun aTabThatDoesNotRunClosesAtOnceInTheTerminalWindow() {
        assertTrue(terminalWindowClose(running = false).pass)
    }

    @Test
    fun aReleasedTabPassesInTheTerminalWindow() {
        assertTrue(terminalWindowClose(released = true).pass)
    }

    @Test
    fun anEndingUnderWayWaitsInTheTerminalWindow() {
        val batch = terminalWindowClose(endingForMillis = 1_000)
        assertFalse(batch.pass)
        assertEquals(emptyList<String>(), batch.take)
        assertEquals(listOf("t1"), batch.waiting)
    }

    /** Whatever views the caller counted, a Terminal tool window tab is one view: two would let it close unended. */
    @Test
    fun aTerminalWindowTabCountsAsOneView() {
        val batch = CloseGate.decideTerminalWindowClose(CloseGate.Tab("t1", released = false, running = true, views = 2, endingForMillis = null), closing = false, move = false)
        assertEquals(listOf("t1"), batch.take)
    }
}
