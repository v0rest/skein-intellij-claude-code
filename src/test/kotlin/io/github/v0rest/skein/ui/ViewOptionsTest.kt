package io.github.v0rest.skein.ui

import io.github.v0rest.skein.settings.SkeinSettings
import io.github.v0rest.skein.ui.actions.ViewOptions
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewOptionsTest {
    /** The menu's texts in order, each with the one setting it flips, written here straight on the state. */
    private val expected: List<Pair<String, (SkeinSettings.State) -> Unit>> = listOf(
        "Only Threads Waiting for Me" to { s -> s.onlyWaitingThreads = !s.onlyWaitingThreads },
        "Group by State" to { s -> s.groupThreadsByState = !s.groupThreadsByState },
        "Show Last Message" to { s -> s.showPreview = !s.showPreview },
        "Show Time" to { s -> s.showAge = !s.showAge },
        "Show Model and Context" to { s -> s.showContext = !s.showContext },
    )

    @Test
    fun everyViewOptionTogglesItsSetting() {
        assertEquals(expected.map { it.first }, ViewOptions.fields.map { it.text })
        for ((option, want) in ViewOptions.fields.zip(expected)) {
            val (text, flip) = want
            val state = SkeinSettings.State()
            option.set(state, !option.get(state))
            // its own setting flipped and nothing else changed
            assertEquals(text, SkeinSettings.State().apply(flip), state)
            assertEquals(text, !option.get(SkeinSettings.State()), option.get(state))
            option.set(state, !option.get(state))
            assertEquals(text, SkeinSettings.State(), state)
        }
    }

    @Test
    fun theModelAndContextAreShownByDefault() {
        assertEquals(true, SkeinSettings.State().showContext)
    }
}
