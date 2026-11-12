package io.github.v0rest.skein.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewRuleTest {
    @Test
    fun theSelectedTabOfTheFocusedWindowIsInView() =
        assertEquals(setOf("t1"), ViewRule.inView(appActive = true, focusInThisProject = true, selectedThreadOfFocusedWindow = "t1", threadsSelectedInTerminalWindow = emptySet()))

    @Test
    fun nothingIsInViewWhenTheIdeIsInTheBackground() =
        assertEquals(emptySet<String>(), ViewRule.inView(appActive = false, focusInThisProject = true, selectedThreadOfFocusedWindow = "t1", threadsSelectedInTerminalWindow = emptySet()))

    @Test
    fun nothingIsInViewWhenAnotherProjectHasFocus() =
        assertEquals(emptySet<String>(), ViewRule.inView(appActive = true, focusInThisProject = false, selectedThreadOfFocusedWindow = "t1", threadsSelectedInTerminalWindow = emptySet()))

    @Test
    fun aNonThreadEditorMeansNoThreadInView() =
        assertEquals(emptySet<String>(), ViewRule.inView(appActive = true, focusInThisProject = true, selectedThreadOfFocusedWindow = null, threadsSelectedInTerminalWindow = emptySet()))

    @Test
    fun aThreadSelectedInTheVisibleTerminalWindowIsInView() =
        assertEquals(setOf("t2"), ViewRule.inView(appActive = true, focusInThisProject = true, selectedThreadOfFocusedWindow = null, threadsSelectedInTerminalWindow = setOf("t2")))

    @Test
    fun bothTheEditorAndTheTerminalWindowThreadAreInView() =
        assertEquals(setOf("t1", "t2"), ViewRule.inView(appActive = true, focusInThisProject = true, selectedThreadOfFocusedWindow = "t1", threadsSelectedInTerminalWindow = setOf("t2")))

    @Test
    fun theTerminalWindowThreadIsNotInViewInTheBackground() =
        assertEquals(emptySet<String>(), ViewRule.inView(appActive = false, focusInThisProject = true, selectedThreadOfFocusedWindow = null, threadsSelectedInTerminalWindow = setOf("t2")))

    @Test
    fun theTerminalWindowThreadIsNotInViewWhenAnotherProjectHasFocus() =
        assertEquals(emptySet<String>(), ViewRule.inView(appActive = true, focusInThisProject = false, selectedThreadOfFocusedWindow = null, threadsSelectedInTerminalWindow = setOf("t2")))

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
