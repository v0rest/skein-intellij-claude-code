package io.github.v0rest.skein.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewRuleTest {
    /** [ViewRule.inView], every tab named here settled in its place unless [settled] says otherwise. */
    private fun inView(
        appActive: Boolean = true,
        focusInThisProject: Boolean = true,
        selected: String? = null,
        terminalWindow: Set<String> = emptySet(),
        settled: Set<String> = terminalWindow + listOfNotNull(selected),
    ) = ViewRule.inView(appActive, focusInThisProject, selected, terminalWindow, settled)

    @Test
    fun theSelectedTabOfTheFocusedWindowIsInView() = assertEquals(setOf("t1"), inView(selected = "t1"))

    @Test
    fun nothingIsInViewWhenTheIdeIsInTheBackground() = assertEquals(emptySet<String>(), inView(appActive = false, selected = "t1"))

    @Test
    fun nothingIsInViewWhenAnotherProjectHasFocus() = assertEquals(emptySet<String>(), inView(focusInThisProject = false, selected = "t1"))

    @Test
    fun aNonThreadEditorMeansNoThreadInView() = assertEquals(emptySet<String>(), inView(selected = null))

    @Test
    fun aThreadSelectedInTheVisibleTerminalWindowIsInView() = assertEquals(setOf("t2"), inView(terminalWindow = setOf("t2")))

    @Test
    fun bothTheEditorAndTheTerminalWindowThreadAreInView() =
        assertEquals(setOf("t1", "t2"), inView(selected = "t1", terminalWindow = setOf("t2")))

    @Test
    fun theTerminalWindowThreadIsNotInViewInTheBackground() =
        assertEquals(emptySet<String>(), inView(appActive = false, terminalWindow = setOf("t2")))

    @Test
    fun theTerminalWindowThreadIsNotInViewWhenAnotherProjectHasFocus() =
        assertEquals(emptySet<String>(), inView(focusInThisProject = false, terminalWindow = setOf("t2")))

    /**
     * A tab still on its way to its place - Skein's own move into the editor, which selects it in the Terminal tool window
     * and then in the editor for a moment - is not in view yet, wherever the IDE shows it then. A restore's tab that only
     * flashes past must not count as looked at.
     */
    @Test
    fun aTabNotSettledInItsPlaceIsNotInView() {
        assertEquals(emptySet<String>(), inView(selected = "t2", settled = setOf("t1")))
        assertEquals(setOf("t1"), inView(selected = "t2", terminalWindow = setOf("t1", "t3"), settled = setOf("t1")))
    }

    @Test
    fun theEditorThreadIsInFrontBeforeTheTerminalWindowOne() =
        assertEquals("t1", ViewRule.inFront(selectedEditorThread = "t1", threadsSelectedInTerminalWindow = setOf("t2")))

    @Test
    fun theTerminalWindowThreadIsInFrontWithoutAThreadEditor() =
        assertEquals("t2", ViewRule.inFront(selectedEditorThread = null, threadsSelectedInTerminalWindow = setOf("t2")))

    @Test
    fun nothingIsInFrontWithoutEither() =
        assertEquals(null, ViewRule.inFront(selectedEditorThread = null, threadsSelectedInTerminalWindow = emptySet()))
}
