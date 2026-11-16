package io.github.v0rest.skein.sound

import io.github.v0rest.skein.paths.PathStyle.UNIX
import io.github.v0rest.skein.paths.PathStyle.WINDOWS
import io.github.v0rest.skein.sound.SoundChoice.Beep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundChoicesTest {
    @Test
    fun nothingStoredIsTheBeepAndTheBeepStoresNothing() {
        assertEquals(Beep, SoundChoices.parse(null, UNIX))
        assertEquals(Beep, SoundChoices.parse("", UNIX))
        // nothing written keeps skein.xml as it was before there was a choice
        assertNull(SoundChoices.format(Beep))
    }

    @Test
    fun aSystemSoundIsStoredByItsName() {
        assertEquals("system:Glass", SoundChoices.format(SoundChoice.System("Glass")))
        assertEquals(SoundChoice.System("Glass"), SoundChoices.parse("system:Glass", UNIX))
        assertEquals(SoundChoice.System("Windows Notify System Generic"), SoundChoices.parse("system:Windows Notify System Generic", WINDOWS))
    }

    @Test
    fun aFileIsStoredByItsAbsolutePath() {
        assertEquals("file:/Users/dev/Sounds/chime.wav", SoundChoices.format(SoundChoice.File("/Users/dev/Sounds/chime.wav")))
        assertEquals(SoundChoice.File("/Users/dev/Sounds/chime.wav"), SoundChoices.parse("file:/Users/dev/Sounds/chime.wav", UNIX))
        assertEquals(SoundChoice.File("C:\\Users\\dev\\chime.wav"), SoundChoices.parse("file:C:\\Users\\dev\\chime.wav", WINDOWS))
        // a path with the separator in it the value uses itself stays whole
        assertEquals(SoundChoice.File("/Users/dev/a:b.wav"), SoundChoices.parse("file:/Users/dev/a:b.wav", UNIX))
    }

    @Test
    fun everyChoiceComesBackAsItWasStored() {
        for (choice in listOf(Beep, SoundChoice.System("Tink"), SoundChoice.File("/home/dev/ding.aiff"))) {
            assertEquals(choice, SoundChoices.parse(SoundChoices.format(choice), UNIX))
        }
    }

    /** A value from a newer Skein, a hand edit or a broken file plays the beep, it never fails. */
    @Test
    fun anUnknownOrBrokenValueIsTheBeep() {
        for (stored in listOf(
            "Glass", "system:", "system: ", "file:", "theme:Glass", "System:Glass", "FILE:/a.wav", " system:Glass", "beep",
            "system:../Glass", "system:a/b", "system:a\\b", "system:.", "system:..", "system:Gl\u0000ass",
        )) {
            assertEquals(stored, Beep, SoundChoices.parse(stored, UNIX))
        }
    }

    /** A relative path would hang off whatever folder the IDE runs in. */
    @Test
    fun aFileMustBeAbsolute() {
        assertEquals(Beep, SoundChoices.parse("file:chime.wav", UNIX))
        assertEquals(Beep, SoundChoices.parse("file:Sounds/chime.wav", UNIX))
        assertEquals(Beep, SoundChoices.parse("file:\\Users\\dev\\chime.wav", WINDOWS))
        assertEquals(Beep, SoundChoices.parse("file:C:chime.wav", WINDOWS))
        assertEquals(SoundChoice.File("\\\\server\\share\\chime.wav"), SoundChoices.parse("file:\\\\server\\share\\chime.wav", WINDOWS))
    }
}
