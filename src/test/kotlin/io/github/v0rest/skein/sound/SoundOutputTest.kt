package io.github.v0rest.skein.sound

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class SoundOutputTest {
    /** Java Sound on macOS opens the input side too, and macOS asks for the microphone - so never there. */
    @Test
    fun macOsPlaysThroughItsAlertSoundServiceTheOthersThroughJavaSound() {
        assertEquals(SoundOutputs.Kind.SYSTEM_SOUND_SERVICE, SoundOutputs.kind(SoundOs.MAC))
        assertEquals(SoundOutputs.Kind.JAVA_SOUND, SoundOutputs.kind(SoundOs.WINDOWS))
        assertEquals(SoundOutputs.Kind.JAVA_SOUND, SoundOutputs.kind(SoundOs.LINUX))
    }

    /** The native calls as a log, with what CoreFoundation and AudioServices answer set per test. */
    private class FakeCalls : SystemSoundCalls<String> {
        val calls = ArrayList<String>()
        var noUrlFor = setOf<String>()
        var statusFor = mapOf<String, Int>()
        var throwOnCreate = false
        private var nextId = 100

        override fun url(path: String): String? {
            calls += "url $path"
            return if (path in noUrlFor) null else "url:$path"
        }

        override fun create(url: String): SystemSoundCalls.Created {
            calls += "create $url"
            if (throwOnCreate) throw UnsatisfiedLinkError("no AudioServicesCreateSystemSoundID")
            val status = statusFor[url.removePrefix("url:")] ?: 0
            return SystemSoundCalls.Created(status, if (status == 0) nextId++ else 0)
        }

        override fun play(id: Int) {
            calls += "play $id"
        }

        override fun dispose(id: Int) {
            calls += "dispose $id"
        }

        override fun release(url: String) {
            calls += "release $url"
        }
    }

    private val glass = "/System/Library/Sounds/Glass.aiff"
    private val facts = FileFacts(475_278, modified = 1_700_000_000_000)

    @Test
    fun aFileGetsItsSoundOnceAndItsUrlGoesRightAfter() {
        val fake = FakeCalls()
        val sounds = SystemSounds(fake)
        sounds.play(glass, facts)
        sounds.play(glass, facts)
        assertEquals(listOf("url $glass", "create url:$glass", "release url:$glass", "play 100", "play 100"), fake.calls)
    }

    @Test
    fun noUrlMeansNoSound() {
        val fake = FakeCalls().apply { noUrlFor = setOf(glass) }
        val sounds = SystemSounds(fake)
        assertFailure("no URL") { sounds.play(glass, facts) }
        assertEquals(listOf("url $glass"), fake.calls)
    }

    /** A file the service refuses, a format it does not know say, beeps - and is asked again next time, it may be fixed. */
    @Test
    fun aNonZeroStatusMeansNoSoundAndNothingKept() {
        val fake = FakeCalls().apply { statusFor = mapOf("/Users/dev/song.caf" to -1500) }
        val sounds = SystemSounds(fake)
        assertFailure("-1500") { sounds.play("/Users/dev/song.caf", facts) }
        assertFailure("-1500") { sounds.play("/Users/dev/song.caf", facts) }
        assertEquals(2, fake.calls.count { it.startsWith("create") })
        assertEquals(2, fake.calls.count { it.startsWith("release") })
        assertEquals(0, fake.calls.count { it.startsWith("play") })
    }

    @Test
    fun theUrlIsReleasedEvenWhenTheNativeCallFails() {
        val fake = FakeCalls().apply { throwOnCreate = true }
        try {
            SystemSounds(fake).play(glass, facts)
            fail("the error should reach the player, which beeps")
        } catch (e: UnsatisfiedLinkError) {
            assertEquals(listOf("url $glass", "create url:$glass", "release url:$glass"), fake.calls)
        }
    }

    /** A file overwritten under the same name plays its new sound, and the old one's ID goes. */
    @Test
    fun aFileThatChangedGetsANewSound() {
        val fake = FakeCalls()
        val sounds = SystemSounds(fake)
        sounds.play(glass, facts)
        fake.calls.clear()
        sounds.play(glass, facts.copy(modified = facts.modified + 1_000))
        assertEquals(listOf("dispose 100", "url $glass", "create url:$glass", "release url:$glass", "play 101"), fake.calls)
        fake.calls.clear()
        sounds.play(glass, facts.copy(size = 1, modified = facts.modified + 1_000))
        assertEquals(listOf("dispose 101", "url $glass", "create url:$glass", "release url:$glass", "play 102"), fake.calls)
        // and unchanged since, it plays the sound it has
        fake.calls.clear()
        sounds.play(glass, facts.copy(size = 1, modified = facts.modified + 1_000))
        assertEquals(listOf("play 102"), fake.calls)
        fake.calls.clear()
        sounds.disposeAll()
        assertEquals(listOf("dispose 102"), fake.calls)
    }

    /** Disposing right after the play would cut the sound, so the sounds go when the IDE closes. */
    @Test
    fun everySoundGoesWhenThePlayerDoes() {
        val fake = FakeCalls()
        val sounds = SystemSounds(fake)
        sounds.play(glass, facts)
        sounds.play("/System/Library/Sounds/Tink.aiff", facts)
        fake.calls.clear()
        sounds.disposeAll()
        assertEquals(listOf("dispose 100", "dispose 101"), fake.calls)
        sounds.disposeAll()
        assertEquals(2, fake.calls.size)
    }

    @Test
    fun theSoundPlayedLongestAgoGoesWhenTooManyAreKept() {
        val fake = FakeCalls()
        val sounds = SystemSounds(fake)
        for (i in 0 until SystemSounds.MAX_IDS) sounds.play("/Users/dev/s$i.wav", facts)
        // the first one played again, so the second is now the one played longest ago
        sounds.play("/Users/dev/s0.wav", facts)
        fake.calls.clear()
        sounds.play("/Users/dev/one-more.wav", facts)
        assertTrue(fake.calls.toString(), "dispose 101" in fake.calls)
        assertEquals(1, fake.calls.count { it.startsWith("dispose") })
        fake.calls.clear()
        sounds.play("/Users/dev/s0.wav", facts)
        assertEquals(listOf("play 100"), fake.calls)
    }

    private fun assertFailure(part: String, block: () -> Unit) {
        try {
            block()
            fail("expected a SoundFailure")
        } catch (e: SoundFailure) {
            assertTrue(e.message, e.message.orEmpty().contains(part))
        }
    }
}
