package io.github.v0rest.skein.settings

import org.junit.Assert.assertTrue
import org.junit.Test

class SkeinSettingsTest {
    /** Off is the choice to keep threads in the Terminal tool window; out of the box they open in the editor, as before. */
    @Test
    fun threadsOpenInTheEditorByDefault() = assertTrue(SkeinSettings.State().threadsInEditor)
}
