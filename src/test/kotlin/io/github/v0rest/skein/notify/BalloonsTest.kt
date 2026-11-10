package io.github.v0rest.skein.notify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BalloonsTest {
    private val expired = mutableListOf<String>()
    private val balloons = Balloons<String> { expired += it }

    @Test
    fun aNewBalloonReplacesTheThreadsOldOne() {
        balloons.replace("t1", "first")
        balloons.replace("t1", "second")
        assertEquals(listOf("first"), expired)
        assertEquals("second", balloons.current("t1"))
        // the old balloon's own expiry callback arrives late: the new one stays
        balloons.expired("t1", "first")
        assertEquals("second", balloons.current("t1"))
    }

    @Test
    fun dismissExpiresAndForgets() {
        balloons.replace("t1", "first")
        balloons.replace("t2", "other")
        balloons.dismiss("t1")
        assertEquals(listOf("first"), expired)
        assertNull(balloons.current("t1"))
        assertEquals("other", balloons.current("t2"))
        balloons.dismiss("t1")
        balloons.dismiss("nope")
        assertEquals(listOf("first"), expired)
    }

    @Test
    fun expiringOnItsOwnForgets() {
        balloons.replace("t1", "first")
        balloons.expired("t1", "first")
        assertNull(balloons.current("t1"))
        assertEquals(emptyList<String>(), expired)
        balloons.dismiss("t1")
        assertEquals(emptyList<String>(), expired)
    }
}
