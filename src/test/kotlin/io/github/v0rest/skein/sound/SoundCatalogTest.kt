package io.github.v0rest.skein.sound

import io.github.v0rest.skein.sound.SoundOs.LINUX
import io.github.v0rest.skein.sound.SoundOs.MAC
import io.github.v0rest.skein.sound.SoundOs.WINDOWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundCatalogTest {
    private fun sounds(os: SoundOs, folders: List<SoundFolder>, listings: Map<String, List<String>>) =
        SoundCatalog.sounds(os, folders) { listings[it.path] ?: emptyList() }

    @Test
    fun macOsLooksInTheSystemFolderThenTheUsersOwn() {
        assertEquals(
            listOf(SoundFolder("/System/Library/Sounds", deep = false), SoundFolder("/Users/dev/Library/Sounds", deep = false)),
            SoundCatalog.folders(MAC, home = "/Users/dev", windowsRoot = null),
        )
        // without a home there is no folder of the user's to look in
        assertEquals(listOf(SoundFolder("/System/Library/Sounds", deep = false)), SoundCatalog.folders(MAC, home = "", windowsRoot = null))
    }

    @Test
    fun windowsLooksInMediaUnderItsOwnFolder() {
        assertEquals(listOf(SoundFolder("D:\\Win\\Media", deep = false)), SoundCatalog.folders(WINDOWS, home = "C:\\Users\\dev", windowsRoot = "D:\\Win"))
        // no SystemRoot, or one that is not a full path, gives the usual place
        assertEquals(listOf(SoundFolder("C:\\Windows\\Media", deep = false)), SoundCatalog.folders(WINDOWS, home = "C:\\Users\\dev", windowsRoot = null))
        assertEquals(listOf(SoundFolder("C:\\Windows\\Media", deep = false)), SoundCatalog.folders(WINDOWS, home = "C:\\Users\\dev", windowsRoot = "Windows"))
    }

    @Test
    fun linuxLooksThroughTheSoundsFolderAndItsSubfolders() =
        assertEquals(listOf(SoundFolder("/usr/share/sounds", deep = true)), SoundCatalog.folders(LINUX, home = "/home/dev", windowsRoot = null))

    @Test
    fun macOsOffersItsAlertSoundsByNameSortedAndOnce() {
        val folders = SoundCatalog.folders(MAC, home = "/Users/dev", windowsRoot = null)
        val found = sounds(
            MAC, folders,
            mapOf(
                "/System/Library/Sounds" to listOf("Tink.aiff", "Basso.aiff", "Glass.aiff", "Funk.aiff"),
                // the user's own - a copy of Glass, Apple's own .caf, a μ-law .au the service may play silently, Finder's leftovers
                "/Users/dev/Library/Sounds" to listOf("Glass.aiff", "bell.aif", "Chirp.caf", "old.au", ".DS_Store", "._bell.aif", "notes.txt", "Pling.wav"),
            ),
        )
        assertEquals(listOf("Basso", "bell", "Chirp", "Funk", "Glass", "Pling", "Tink"), found.map { it.name })
        // a name in both folders is the system's, system:Glass means the OS's own Glass
        assertEquals("/System/Library/Sounds/Glass.aiff", SoundCatalog.find(found, "Glass"))
        assertEquals("/Users/dev/Library/Sounds/bell.aif", SoundCatalog.find(found, "bell"))
    }

    @Test
    fun windowsOffersTheWavFilesOfMedia() {
        val folders = SoundCatalog.folders(WINDOWS, home = "C:\\Users\\dev", windowsRoot = "C:\\Windows")
        val found = sounds(
            WINDOWS, folders,
            mapOf("C:\\Windows\\Media" to listOf("Windows Notify System Generic.wav", "chimes.wav", "Alarm01.WAV", "Ring01.wav", "desktop.ini", "Windows Background.wav", "ding.caf")),
        )
        assertEquals(listOf("Alarm01", "chimes", "Ring01", "Windows Background", "Windows Notify System Generic"), found.map { it.name })
        assertEquals("C:\\Windows\\Media\\chimes.wav", SoundCatalog.find(found, "chimes"))
        assertEquals("C:\\Windows\\Media\\Alarm01.WAV", SoundCatalog.find(found, "Alarm01"))
    }

    @Test
    fun linuxOffersTheWavFilesUnderSoundsButNotFreedesktopsOga() {
        val folders = SoundCatalog.folders(LINUX, home = "/home/dev", windowsRoot = null)
        val found = sounds(
            LINUX, folders,
            mapOf(
                "/usr/share/sounds" to listOf(
                    "freedesktop/stereo/bell.oga", "freedesktop/stereo/complete.oga", "alsa/Front_Center.wav", "alsa/Noise.wav",
                    "sound-icons/prompt.wav", "gnome/default/alerts/drip.ogg", "sound-icons/Noise.wav", "sound-icons/beep.au", "x/ding.caf",
                ),
            ),
        )
        assertEquals(listOf("beep", "Front_Center", "Noise", "prompt"), found.map { it.name })
        // the same name in two subfolders is the first by path, so it does not change from one look to the next
        assertEquals("/usr/share/sounds/alsa/Noise.wav", SoundCatalog.find(found, "Noise"))
        assertEquals("/usr/share/sounds/sound-icons/prompt.wav", SoundCatalog.find(found, "prompt"))
    }

    @Test
    fun namesThatDifferInCaseOnlyAreOneSound() {
        val folders = listOf(SoundFolder("/usr/share/sounds", deep = true))
        val found = sounds(LINUX, folders, mapOf("/usr/share/sounds" to listOf("b/Bell.wav", "a/bell.wav")))
        assertEquals(listOf("bell"), found.map { it.name })
        assertEquals("/usr/share/sounds/a/bell.wav", SoundCatalog.find(found, "bell"))
    }

    @Test
    fun aFolderThatIsNotThereOffersNothing() {
        assertEquals(emptyList<SystemSound>(), sounds(MAC, SoundCatalog.folders(MAC, home = "/Users/dev", windowsRoot = null), emptyMap()))
    }

    @Test
    fun findIsByTheExactName() {
        val found = listOf(SystemSound("Glass", "/System/Library/Sounds/Glass.aiff"))
        assertEquals("/System/Library/Sounds/Glass.aiff", SoundCatalog.find(found, "Glass"))
        assertNull(SoundCatalog.find(found, "glass"))
        assertNull(SoundCatalog.find(found, "Hero"))
    }

    /** Java Sound off macOS, and on macOS the alert sound service, which knows linear PCM or IMA4 in caf, aif and wav. */
    @Test
    fun theFilesEachSystemPlays() {
        for (os in listOf(WINDOWS, LINUX)) {
            for (name in listOf("a.wav", "a.WAV", "a.aiff", "a.AIFF", "a.aif", "a.au", "x.y.wav")) assertTrue("$os $name", SoundCatalog.isSupported(name, os))
            for (name in listOf("a.caf", "a.oga", "a.ogg", "a.mp3", "a.m4a", "wav", ".wav", "a.wav.txt", "a", "")) assertFalse("$os $name", SoundCatalog.isSupported(name, os))
        }
        for (name in listOf("a.wav", "a.WAV", "a.aiff", "a.aif", "a.caf", "a.CAF", "x.y.wav")) assertTrue(name, SoundCatalog.isSupported(name, MAC))
        // a μ-law .au may play silently there, so it is not offered on macOS at all
        for (name in listOf("a.au", "a.oga", "a.mp3", "wav", ".caf", "a")) assertFalse(name, SoundCatalog.isSupported(name, MAC))
        assertEquals(listOf("aiff", "aif", "wav", "caf"), SoundCatalog.extensions(MAC))
        assertEquals(listOf("wav", "aiff", "aif", "au"), SoundCatalog.extensions(WINDOWS))
        assertEquals(listOf("wav", "aiff", "aif", "au"), SoundCatalog.extensions(LINUX))
    }

    @Test
    fun aSoundIsNamedAfterItsFileWithoutTheExtension() {
        assertEquals("Glass", SoundCatalog.nameOf("Glass.aiff"))
        assertEquals("Windows Notify System Generic", SoundCatalog.nameOf("Windows Notify System Generic.wav"))
        assertEquals("x.y", SoundCatalog.nameOf("x.y.wav"))
    }
}
