package io.github.v0rest.skein.settings

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SkeinSettingsTest {
    /** Off is the choice to keep threads in the Terminal tool window; out of the box they open in the editor, as before. */
    @Test
    fun threadsOpenInTheEditorByDefault() = assertTrue(SkeinSettings.State().threadsInEditor)

    /** Rename also renames in Claude Code until the user unticks the box once. */
    @Test
    fun renameAlsoReachesClaudeCodeByDefault() = assertTrue(SkeinSettings.State().renameInClaude)

    /** Nothing stored is the system beep, so skein.xml from before the sound choice plays as it did. */
    @Test
    fun soundChoicesStartAsTheSystemBeep() {
        assertNull(SkeinSettings.State().soundNeedsYouChoice)
        assertNull(SkeinSettings.State().soundTurnFinishedChoice)
    }
}
