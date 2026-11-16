package io.github.v0rest.skein.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** An event's sounds on a fake clock, with clips that are names and a list of the ones stopped. */
class SoundSlotTest {
    private var now = 0L
    private val stopped = ArrayList<String>()
    private val slot = SoundSlot<String>(clock = { now }, stop = { stopped += it })

    private fun play(clip: String?, preview: Boolean = false, takes: Long = 0) = slot.play(preview) {
        now += takes
        clip
    }

    @Test
    fun threadsFinishingTogetherSoundOnce() {
        assertTrue(play("glass"))
        now = 300
        assertFalse(play("glass"))
        now = 1_499
        assertFalse(play("glass"))
        assertEquals(emptyList<String>(), stopped)
    }

    @Test
    fun afterTheBurstANewSoundStopsTheOldOneHoweverLongItIs() {
        assertTrue(play("a song"))
        now = 1_500
        assertTrue(play("glass"))
        assertEquals(listOf("a song"), stopped)
    }

    /** The clock is read once the sound started, so a slow open does not eat into the burst. */
    @Test
    fun theBurstCountsFromTheStartNotTheAsk() {
        assertTrue(play("glass", takes = 2_000))
        now = 3_000
        assertFalse(play("glass"))
        now = 3_500
        assertTrue(play("glass"))
    }

    @Test
    fun thePlayButtonCutsInAnyTime() {
        assertTrue(play("a song"))
        now = 10
        assertTrue(play("glass", preview = true))
        assertEquals(listOf("a song"), stopped)
    }

    /** A beep, or a file that could not start and beeped, is a sound of the event all the same. */
    @Test
    fun aBeepStartsABurstToo() {
        assertTrue(play(null))
        now = 100
        assertFalse(play("glass"))
    }

    @Test
    fun aClipThatEndedByItselfIsNotStoppedAgain() {
        assertTrue(play("glass"))
        slot.ended("glass")
        now = 2_000
        assertTrue(play("tink"))
        assertEquals(emptyList<String>(), stopped)
        // another clip ending leaves the one playing alone
        slot.ended("glass")
        slot.stopAll()
        assertEquals(listOf("tink"), stopped)
    }
}
