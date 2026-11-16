package io.github.v0rest.skein.sound

import io.github.v0rest.skein.paths.PathStyle.UNIX
import io.github.v0rest.skein.paths.PathStyle.WINDOWS
import io.github.v0rest.skein.sound.SoundChoice.Beep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SoundMenuTest {
    private val names = listOf("Basso", "Glass", "Tink")
    private val home = "/Users/dev"

    private fun items(systemNames: List<String>, kept: List<SoundChoice>) = SoundMenu.items(systemNames, kept, home, UNIX)

    private fun choices(items: List<SoundItem>) = items.map { it.choice }

    @Test
    fun theBeepComesFirstThenTheOsSounds() =
        assertEquals(
            listOf(Beep, SoundChoice.System("Basso"), SoundChoice.System("Glass"), SoundChoice.System("Tink")),
            choices(items(names, kept = listOf(Beep))),
        )

    /** Before the OS's sounds are read the page shows the beep and the stored choice, so it opens as stored. */
    @Test
    fun theStoredChoiceIsThereBeforeTheOsSoundsAre() {
        assertEquals(listOf(Beep, SoundChoice.System("Glass")), choices(items(emptyList(), kept = listOf(SoundChoice.System("Glass")))))
        assertEquals(listOf(Beep), choices(items(emptyList(), kept = listOf(Beep))))
    }

    @Test
    fun aStoredSystemSoundAmongTheOsSoundsIsNotListedTwice() =
        assertEquals(choices(items(names, kept = emptyList())), choices(items(names, kept = listOf(SoundChoice.System("Glass")))))

    /** The stored choice must be an item, or the page would open changed and Apply would turn it into the beep. */
    @Test
    fun aStoredChoiceTheOsLacksAndAFileAreItemsOfTheirOwn() =
        assertEquals(
            listOf(
                Beep, SoundChoice.System("Basso"), SoundChoice.System("Glass"), SoundChoice.System("Tink"),
                SoundChoice.System("Sosumi"), SoundChoice.File("/Users/dev/chime.wav"),
            ),
            choices(items(names, kept = listOf(SoundChoice.File("/Users/dev/chime.wav"), SoundChoice.System("Sosumi"), SoundChoice.File("/Users/dev/chime.wav")))),
        )

    /** Filling the list keeps what it held, a file chosen while the OS's sounds were still being read included. */
    @Test
    fun theOsSoundsJoinWhatTheListHeld() {
        val chime = SoundChoice.File("/Users/dev/chime.wav")
        val before = SoundMenu.withChosen(items(emptyList(), kept = listOf(SoundChoice.System("Glass"))), chime, home, UNIX)
        assertEquals(
            listOf(Beep, SoundChoice.System("Basso"), SoundChoice.System("Glass"), SoundChoice.System("Tink"), chime),
            choices(items(names, kept = choices(before))),
        )
    }

    @Test
    fun aChosenFileJoinsAtTheEndOnce() {
        val before = items(names, kept = emptyList())
        val chime = SoundChoice.File("/Users/dev/chime.wav")
        val after = SoundMenu.withChosen(before, chime, home, UNIX)
        assertEquals(choices(before) + chime, choices(after))
        assertEquals(choices(after), choices(SoundMenu.withChosen(after, chime, home, UNIX)))
    }

    /** Equal by the choice alone, so the item the page binds and the one in the list are one. */
    @Test
    fun itemsAreEqualByTheirChoice() {
        assertEquals(SoundItem(SoundChoice.System("Glass"), "Glass"), SoundItem(SoundChoice.System("Glass"), "something else"))
        assertEquals(SoundItem(Beep, "a").hashCode(), SoundItem(Beep, "b").hashCode())
        assertNotEquals(SoundItem(SoundChoice.System("Glass"), "Glass"), SoundItem(SoundChoice.System("Tink"), "Glass"))
    }

    /** Type-ahead and a screen reader read the item's text, never a class name. */
    @Test
    fun anItemReadsAsItsWords() {
        assertEquals("System beep", SoundMenu.item(Beep, home, UNIX).toString())
        assertEquals("Glass", SoundMenu.item(SoundChoice.System("Glass"), home, UNIX).toString())
        assertEquals("chime.wav (~/Sounds)", SoundMenu.item(SoundChoice.File("/Users/dev/Sounds/chime.wav"), home, UNIX).toString())
    }

    @Test
    fun aFileShowsItsFolderSoTwoOfTheSameNameDiffer() {
        val a = SoundMenu.item(SoundChoice.File("/Users/dev/Sounds/chime.wav"), home, UNIX)
        val b = SoundMenu.item(SoundChoice.File("/Volumes/Share/chime.wav"), home, UNIX)
        assertEquals("chime.wav (~/Sounds)", SoundMenu.label(a, null))
        assertEquals("chime.wav (/Volumes/Share)", SoundMenu.label(b, null))
        assertEquals("chime.wav (~)", SoundMenu.label(SoundMenu.item(SoundChoice.File("/Users/dev/chime.wav"), home, UNIX), null))
        assertEquals("chime.wav (/)", SoundMenu.label(SoundMenu.item(SoundChoice.File("/chime.wav"), home, UNIX), null))
        assertEquals(
            "chime.wav (C:\\Media)",
            SoundMenu.label(SoundMenu.item(SoundChoice.File("C:\\Media\\chime.wav"), "C:\\Users\\dev", WINDOWS), null),
        )
        assertEquals(
            "chime.wav (~\\Music)",
            SoundMenu.label(SoundMenu.item(SoundChoice.File("C:\\Users\\dev\\Music\\chime.wav"), "C:\\Users\\dev", WINDOWS), null),
        )
    }

    @Test
    fun aLongFolderKeepsItsFirstAndLastNames() {
        assertEquals("~/Music/…/Alerts", SoundMenu.folderText("/Users/dev/Music/Collections/Sound Effects 2026/Notifications/Alerts/x.wav", home, UNIX))
        assertEquals("/Volumes/…/Alerts", SoundMenu.folderText("/Volumes/Long Share Name/Some Folder Name/Alerts/x.wav", home, UNIX))
        // short enough, or nothing to leave out
        assertEquals("~/Music/Alerts", SoundMenu.folderText("/Users/dev/Music/Alerts/x.wav", home, UNIX))
        assertEquals("~/A very long folder name of forty-five chars/x", SoundMenu.folderText("/Users/dev/A very long folder name of forty-five chars/x/y.wav", home, UNIX))
        // without a home, or one that is not a full path, the path is shown from its root
        assertEquals("/Users/dev/Sounds", SoundMenu.folderText("/Users/dev/Sounds/x.wav", "", UNIX))
        assertEquals("/Users/dev/Sounds", SoundMenu.folderText("/Users/dev/Sounds/x.wav", "dev", UNIX))
    }

    @Test
    fun labels() {
        assertEquals("System beep", SoundMenu.label(SoundMenu.item(Beep, home, UNIX), null))
        assertEquals("Glass", SoundMenu.label(SoundMenu.item(SoundChoice.System("Glass"), home, UNIX), null))
    }

    /** A choice that cannot play still shows, marked, and plays as the beep. */
    @Test
    fun aChoiceThatCannotPlaySaysWhy() {
        val chime = SoundMenu.item(SoundChoice.File("/Users/dev/chime.wav"), home, UNIX)
        assertEquals("Sosumi (missing)", SoundMenu.label(SoundMenu.item(SoundChoice.System("Sosumi"), home, UNIX), SoundProblem.MISSING))
        assertEquals("chime.wav (~, missing)", SoundMenu.label(chime, SoundProblem.MISSING))
        assertEquals("chime.wav (~, over 5 MB)", SoundMenu.label(chime, SoundProblem.TOO_LARGE))
        assertEquals("chime.wav (~, cannot read)", SoundMenu.label(chime, SoundProblem.UNREADABLE))
        assertEquals("song.mp3 (~, cannot play)", SoundMenu.label(SoundMenu.item(SoundChoice.File("/Users/dev/song.mp3"), home, UNIX), SoundProblem.UNSUPPORTED))
    }

    @Test
    fun theTooltipIsAFilesFullPath() {
        assertEquals("/Users/dev/Sounds/chime.wav", SoundMenu.tooltip(SoundChoice.File("/Users/dev/Sounds/chime.wav")))
        assertNull(SoundMenu.tooltip(SoundChoice.System("Glass")))
        assertNull(SoundMenu.tooltip(Beep))
    }

    @Test
    fun aFileFromTheChooserIsKeptInTheSystemsOwnForm() {
        assertEquals(SoundChoice.File("/Users/dev/Sounds/chime.wav"), SoundMenu.chosenFile("/Users/dev/Sounds/chime.wav", SoundOs.MAC))
        // the IDE's file chooser gives forward slashes on Windows too
        assertEquals(SoundChoice.File("C:\\Users\\dev\\chime.WAV"), SoundMenu.chosenFile("C:/Users/dev/chime.WAV", SoundOs.WINDOWS))
        assertEquals(SoundChoice.File("/home/dev/ding.aif"), SoundMenu.chosenFile("/home/dev/ding.aif", SoundOs.LINUX))
    }

    @Test
    fun aChosenFileOfAKindTheSystemDoesNotPlayIsRefused() {
        assertEquals(SoundChoice.File("/Users/dev/chirp.caf"), SoundMenu.chosenFile("/Users/dev/chirp.caf", SoundOs.MAC))
        assertNull(SoundMenu.chosenFile("/Users/dev/old.au", SoundOs.MAC))
        assertEquals(SoundChoice.File("/home/dev/old.au"), SoundMenu.chosenFile("/home/dev/old.au", SoundOs.LINUX))
        assertNull(SoundMenu.chosenFile("/home/dev/chirp.caf", SoundOs.LINUX))
    }

    @Test
    fun aChosenFileSkeinCannotPlayOrPlaceIsRefused() {
        assertNull(SoundMenu.chosenFile("/Users/dev/song.mp3", SoundOs.MAC))
        assertNull(SoundMenu.chosenFile("/Users/dev/Sounds", SoundOs.MAC))
        assertNull(SoundMenu.chosenFile("chime.wav", SoundOs.LINUX))
        assertNull(SoundMenu.chosenFile("", SoundOs.LINUX))
    }

    @Test
    fun whatAnItemPlays() {
        assertEquals(SoundChoice.System("Glass"), SoundMenu.choiceOf(SoundMenu.item(SoundChoice.System("Glass"), home, UNIX)))
        assertEquals(Beep, SoundMenu.choiceOf(null))
    }
}
