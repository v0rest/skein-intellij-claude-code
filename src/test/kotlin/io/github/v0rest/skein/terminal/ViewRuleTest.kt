package io.github.v0rest.skein.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class ViewRuleTest {
    @Test
    fun theSelectedTabOfTheFocusedWindowIsInView() =
        assertEquals(setOf("t1"), ViewRule.inView(appActive = true, focusInThisProject = true, selectedThreadOfFocusedWindow = "t1"))

    @Test
    fun nothingIsInViewWhenTheIdeIsInTheBackground() =
        assertEquals(emptySet<String>(), ViewRule.inView(appActive = false, focusInThisProject = true, selectedThreadOfFocusedWindow = "t1"))

    @Test
    fun nothingIsInViewWhenAnotherProjectHasFocus() =
        assertEquals(emptySet<String>(), ViewRule.inView(appActive = true, focusInThisProject = false, selectedThreadOfFocusedWindow = "t1"))

    @Test
    fun aNonThreadEditorMeansNoThreadInView() =
        assertEquals(emptySet<String>(), ViewRule.inView(appActive = true, focusInThisProject = true, selectedThreadOfFocusedWindow = null))
}
