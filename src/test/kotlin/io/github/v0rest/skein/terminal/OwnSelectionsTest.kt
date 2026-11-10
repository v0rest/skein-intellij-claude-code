package io.github.v0rest.skein.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Closing a batch of thread tabs makes the IDE select a neighbour of each tab that goes; Skein must not take that as the
 * user opening the neighbour, which for a restored thread would type `claude --resume` into a tab the user is closing.
 */
class OwnSelectionsTest {
    private var now = 10_000L
    private val own = OwnSelections(quietMillis = 1_000) { now }

    @Test
    fun aSelectionIsTheUsersWhenSkeinCausesNone() {
        assertTrue(own.userCaused())
    }

    @Test
    fun aSelectionWhileSkeinClosesTabsIsNotTheUsers() {
        own.begin()
        assertFalse(own.userCaused())
        own.begin() // a nested operation, such as a reselect inside a close
        own.end()
        assertFalse("still inside the outer operation", own.userCaused())
        own.end()
        assertFalse("the IDE's selection events arrive a little later", own.userCaused())
        now += 999
        assertFalse(own.userCaused())
        now += 1
        assertTrue(own.userCaused())
    }
}
