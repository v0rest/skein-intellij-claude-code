package io.github.v0rest.skein.terminal

import io.github.v0rest.skein.testutil.FakeThreadTerminal
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Closing the tab in front lets the IDE select a neighbour, which Skein takes as the user opening that thread; in a
 * batch the neighbour could be the next tab to close, so the one in front goes last.
 */
class CloseOrderTest {
    private fun tabs(vararg ids: String) = ids.map { FakeThreadTerminal.Tab(it, "/w/acme") }

    @Test
    fun theTabInFrontClosesLast() {
        assertEquals(listOf("t1", "t3", "t2"), CloseOrder.frontLast(tabs("t1", "t2", "t3"), front = "t2").map { it.id })
        assertEquals(listOf("t2", "t3", "t1"), CloseOrder.frontLast(tabs("t1", "t2", "t3"), front = "t1").map { it.id })
    }

    @Test
    fun withNoTabOfTheBatchInFrontTheOrderStays() {
        assertEquals(listOf("t1", "t2"), CloseOrder.frontLast(tabs("t1", "t2"), front = null).map { it.id })
        assertEquals(listOf("t1", "t2"), CloseOrder.frontLast(tabs("t1", "t2"), front = "t9").map { it.id })
        assertEquals(listOf("t1"), CloseOrder.frontLast(tabs("t1"), front = "t1").map { it.id })
    }
}
