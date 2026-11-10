package io.github.v0rest.skein.notify

import io.github.v0rest.skein.claude.WaitReason.OTHER
import io.github.v0rest.skein.claude.WaitReason.PERMISSION
import io.github.v0rest.skein.claude.WaitReason.QUESTION
import io.github.v0rest.skein.settings.SkeinSettings
import io.github.v0rest.skein.threads.NotificationKind.NEEDS_YOU
import io.github.v0rest.skein.threads.NotificationKind.TURN_FINISHED
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import io.github.v0rest.skein.threads.ThreadState.NeedsYou
import io.github.v0rest.skein.threads.ThreadState.YourTurn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationTextTest {
    private fun thread(name: String, state: ThreadState) =
        SkeinThread(id = "t1", folder = "/p", sessionId = null, name = name, state = state, stateSince = 0, createdAt = 0, anchor = null)

    @Test
    fun titleIsTheNameAlone() {
        assertEquals("acme", NotificationText.title("acme"))
        assertEquals("Fix login", NotificationText.title("Fix login"))
    }

    @Test
    fun subtitleNamesTheEvent() {
        assertEquals("needs your permission", NotificationText.subtitle(thread("acme", NeedsYou(PERMISSION, 0)), NEEDS_YOU))
        assertEquals("has a question", NotificationText.subtitle(thread("acme", NeedsYou(QUESTION, 0)), NEEDS_YOU))
        assertEquals("needs you", NotificationText.subtitle(thread("acme", NeedsYou(OTHER, 0)), NEEDS_YOU))
        assertEquals("finished its turn", NotificationText.subtitle(thread("acme", YourTurn(0)), TURN_FINISHED))
    }

    @Test
    fun needsYouSubtitleFallsBackWhenTheStateMovedOn() =
        assertEquals("needs you", NotificationText.subtitle(thread("acme", ThreadState.Working), NEEDS_YOU))

    @Test
    fun osTextIsPlainSubtitleThenContent() {
        assertEquals("needs your permission", NotificationText.osText("needs your permission", null))
        assertEquals("needs your permission\nBash: a ‹ b", NotificationText.osText("needs your permission", "Bash: a < b"))
    }

    @Test
    fun osTextKeepsAngleBracketsFromLookingLikeTags() =
        // the macOS sender strips anything shaped like a tag, so the brackets become their single-quote cousins
        assertEquals("finished its turn\nChanged it to List‹String›.", NotificationText.osText("finished its turn", "Changed it to List<String>."))

    @Test
    fun textIsTheSessionNameWhenItDiffersFromTheTitle() =
        assertEquals("Fix the login", NotificationText.text("acme", "Fix the login"))

    @Test
    fun textStaysEmptyWhenTheNameRepeatsTheTitleOrThereIsNone() {
        assertNull(NotificationText.text("acme", "acme"))
        assertNull(NotificationText.text("acme", " acme "))
        assertNull(NotificationText.text("acme", null))
        assertNull(NotificationText.text("acme", "  "))
    }

    @Test
    fun osTextCarriesTheNameOnItsOwnLineOrTheSubtitleAlone() {
        assertEquals("needs your permission\nFix the login", NotificationText.osText("needs your permission", NotificationText.text("acme", "Fix the login")))
        assertEquals("has a question", NotificationText.osText("has a question", NotificationText.text("acme", "acme")))
        assertEquals("finished its turn", NotificationText.osText("finished its turn", NotificationText.text("acme", null)))
    }

    @Test
    fun aLongNameIsCutAt120CodePointsWithAnEllipsis() {
        val text = NotificationText.text("acme", "n".repeat(300))!!
        assertEquals(120, text.codePointCount(0, text.length))
        assertTrue(text.endsWith("…"))
    }

    @Test
    fun aMultiLineNameBecomesOneLine() {
        assertEquals("Fix the login now", NotificationText.text("acme", "Fix the\n  login\r\n\tnow "))
        assertEquals("needs you\nFix the login", NotificationText.osText("needs you", NotificationText.text("acme", "Fix\nthe login")))
    }

    @Test
    fun theMarksNameWinsOverTheTranscriptTitle() =
        assertEquals("Mine", NotificationText.sessionName("Mine") { error("must not be read") })

    @Test
    fun theTranscriptTitleIsUsedWithoutAMarksName() = assertEquals("Login fix", NotificationText.sessionName(null) { "Login fix" })

    @Test
    fun aFailingReaderGivesNoName() = assertNull(NotificationText.sessionName(null) { throw java.io.IOException("gone") })

    @Test
    fun noNameAnywhereGivesNoName() = assertNull(NotificationText.sessionName(null) { null })

    @Test
    fun togglesDefaultOn() {
        val s = SkeinSettings.State()
        assertTrue(NotificationText.enabled(NEEDS_YOU, s))
        assertTrue(NotificationText.enabled(TURN_FINISHED, s))
    }

    @Test
    fun togglesRespected() {
        val s = SkeinSettings.State().apply { notifyTurnFinished = false }
        assertFalse(NotificationText.enabled(TURN_FINISHED, s))
        assertTrue(NotificationText.enabled(NEEDS_YOU, s))
        s.notifyNeedsYou = false
        assertFalse(NotificationText.enabled(NEEDS_YOU, s))
    }

    @Test
    fun soundDefaultsAreOnForNeedsYouAndOffForTurnFinished() {
        val s = SkeinSettings.State()
        assertTrue(NotificationText.sound(NEEDS_YOU, s))
        assertFalse(NotificationText.sound(TURN_FINISHED, s))
    }

    @Test
    fun soundAndPopupSwitchIndependently() {
        val s = SkeinSettings.State().apply { notifyTurnFinished = false; soundTurnFinished = true }
        assertTrue(NotificationText.sound(TURN_FINISHED, s))
        assertFalse(NotificationText.enabled(TURN_FINISHED, s))
        s.notifyNeedsYou = true
        s.soundNeedsYou = false
        assertFalse(NotificationText.sound(NEEDS_YOU, s))
        assertTrue(NotificationText.enabled(NEEDS_YOU, s))
    }

    @Test
    fun missingStatusFilesNamesTheVersion() =
        assertEquals("Skein could not read session status. Check that claude starts in the IDE terminal and is Claude Code 2.1.0 or newer.", NotificationText.missingStatusFiles("2.1.0"))
}
