package io.github.v0rest.skein.threads

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchSlotTest {
    @Test
    fun oneHolderAtATime() {
        val slot = LaunchSlot(timeoutMillis = 30_000)
        assertTrue(slot.tryAcquire("a", now = 0))
        assertTrue(slot.tryAcquire("a", now = 1))
        assertFalse(slot.tryAcquire("b", now = 2))
        slot.release("b")
        assertEquals("a", slot.holder(now = 3))
        slot.release("a")
        assertTrue(slot.tryAcquire("b", now = 4))
    }

    @Test
    fun anExpiredHolderIsLetGo() {
        val slot = LaunchSlot(timeoutMillis = 30_000)
        assertTrue(slot.tryAcquire("a", now = 0))
        assertEquals("a", slot.holder(now = 30_000))
        assertNull(slot.holder(now = 30_001))
        assertTrue(slot.tryAcquire("b", now = 30_001))
    }

    @Test
    fun anAnchorCanBeClaimedOnlyOnce() {
        val slot = LaunchSlot()
        val one = Any()
        val two = Any()
        assertTrue(slot.claimAnchor(one, 10))
        assertTrue(slot.claimAnchor(one, 10))
        assertFalse(slot.claimAnchor(two, 10))
        assertEquals(setOf(10L), slot.ownedAnchors())
        slot.releaseAnchor(one, 10)
        assertTrue(slot.claimAnchor(two, 10))
    }

    @Test
    fun forgettingAnOwnerDropsItsAnchorsAndItsLaunch() {
        val slot = LaunchSlot()
        val owner = Any()
        slot.claimAnchor(owner, 10)
        slot.claimAnchor(owner, 11)
        assertTrue(slot.tryAcquire("t1", now = 0, owner = owner))
        slot.forget(owner)
        assertTrue(slot.ownedAnchors().isEmpty())
        assertNull(slot.holder(now = 1))
    }
}
