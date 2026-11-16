package io.github.v0rest.skein.sound

import io.github.v0rest.skein.sound.SoundChoice.Beep
import io.github.v0rest.skein.sound.SoundPlan.PlayFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundDecisionTest {
    private val glass = "/System/Library/Sounds/Glass.aiff"
    private val systemSounds = mapOf("Glass" to glass)
    private val glassFacts = FileFacts(475_278, modified = 1_700_000_000_000)
    private val chimeFacts = FileFacts(4_044, modified = 1_700_000_100_000)
    private val disk = mapOf(glass to glassFacts, "/Users/dev/chime.wav" to chimeFacts)

    private fun plan(choice: SoundChoice, os: SoundOs = SoundOs.MAC, facts: (String) -> FileFacts? = { disk[it] }) =
        SoundDecision.plan(choice, { systemSounds[it] }, facts, os)

    @Test
    fun theBeepIsAChoiceNotAProblem() = assertEquals(SoundPlan.Beep(null), plan(Beep))

    @Test
    fun aSystemSoundPlaysItsFile() = assertEquals(PlayFile(glass, glassFacts), plan(SoundChoice.System("Glass")))

    @Test
    fun aFilePlaysWhenItIsThere() {
        assertEquals(PlayFile("/Users/dev/chime.wav", chimeFacts), plan(SoundChoice.File("/Users/dev/chime.wav")))
        assertEquals(PlayFile("/Users/dev/chime.wav", chimeFacts), plan(SoundChoice.File("/Users/dev/chime.wav"), SoundOs.LINUX))
    }

    /** The extension each system's player takes - a .caf on macOS only, an .au everywhere else. */
    @Test
    fun theExtensionFollowsTheSystem() {
        val facts = { _: String -> FileFacts(10, 0) }
        assertEquals(SoundPlan.Beep(SoundProblem.UNSUPPORTED), plan(SoundChoice.File("/Users/dev/old.au"), SoundOs.MAC, facts))
        assertEquals(PlayFile("/Users/dev/chirp.caf", FileFacts(10, 0)), plan(SoundChoice.File("/Users/dev/chirp.caf"), SoundOs.MAC, facts))
        assertEquals(SoundPlan.Beep(SoundProblem.UNSUPPORTED), plan(SoundChoice.File("/home/dev/chirp.caf"), SoundOs.LINUX, facts))
        assertEquals(PlayFile("/home/dev/old.au", FileFacts(10, 0)), plan(SoundChoice.File("/home/dev/old.au"), SoundOs.LINUX, facts))
        assertEquals(PlayFile("C:\\Users\\dev\\old.au", FileFacts(10, 0)), plan(SoundChoice.File("C:\\Users\\dev\\old.au"), SoundOs.WINDOWS, facts))
    }

    /** skein.xml from another machine, or an OS update that dropped the sound. */
    @Test
    fun aSystemSoundThisOsLacksBeeps() =
        assertEquals(SoundPlan.Beep(SoundProblem.MISSING), plan(SoundChoice.System("Sosumi")))

    @Test
    fun aFileThatIsGoneBeeps() =
        assertEquals(SoundPlan.Beep(SoundProblem.MISSING), plan(SoundChoice.File("/Users/dev/gone.wav")))

    /** Checked before the disk is asked at all. */
    @Test
    fun aFileOfAKindTheSystemDoesNotPlayBeeps() {
        var asked = false
        assertEquals(
            SoundPlan.Beep(SoundProblem.UNSUPPORTED),
            plan(SoundChoice.File("/Users/dev/song.mp3")) { asked = true; FileFacts(10, 0) },
        )
        assertFalse(asked)
    }

    @Test
    fun aFileOverFiveMegabytesBeeps() {
        val path = "/Users/dev/long.wav"
        assertEquals(PlayFile(path, FileFacts(5L * 1024 * 1024, 0)), plan(SoundChoice.File(path)) { FileFacts(5L * 1024 * 1024, 0) })
        assertEquals(SoundPlan.Beep(SoundProblem.TOO_LARGE), plan(SoundChoice.File(path)) { FileFacts(5L * 1024 * 1024 + 1, 0) })
    }

    @Test
    fun aDiskThatFailsBeeps() {
        assertEquals(SoundPlan.Beep(SoundProblem.UNREADABLE), plan(SoundChoice.File("/Users/dev/chime.wav")) { throw IllegalStateException("denied") })
        assertEquals(
            SoundPlan.Beep(SoundProblem.UNREADABLE),
            SoundDecision.plan(SoundChoice.System("Glass"), { throw IllegalStateException("denied") }, { disk[it] }, SoundOs.MAC),
        )
    }

    @Test
    fun aSecondSoundOfTheSameEventInABurstStaysSilent() {
        // nothing played yet
        assertFalse(SoundGate.skip(lastStart = null, now = 1_000, preview = false))
        // three threads finishing at once sound once
        assertTrue(SoundGate.skip(lastStart = 1_000, now = 1_000, preview = false))
        assertTrue(SoundGate.skip(lastStart = 1_000, now = 2_499, preview = false))
        // after a second and a half a new event plays, and the player stops the old clip first, however long it is
        assertFalse(SoundGate.skip(lastStart = 1_000, now = 2_500, preview = false))
        assertFalse(SoundGate.skip(lastStart = 1_000, now = 61_000, preview = false))
    }

    @Test
    fun thePlayButtonNeverWaits() =
        assertFalse(SoundGate.skip(lastStart = 1_000, now = 1_001, preview = true))

    /** The clock never steps back, but if it did, a start in the future must not silence the event. */
    @Test
    fun aStartAheadOfNowSilencesNothing() =
        assertFalse(SoundGate.skip(lastStart = 5_000, now = 1_000, preview = false))
}
