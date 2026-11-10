package io.github.v0rest.skein.ui

import com.intellij.openapi.util.ScalableIcon
import com.intellij.ui.BadgeIcon
import com.intellij.util.ui.EmptyIcon
import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolWindowBadgeTest {
    private fun thread(id: String, state: ThreadState) =
        SkeinThread(id = id, folder = "/w/acme", sessionId = null, name = id, state = state, stateSince = 0, createdAt = 0, anchor = null, pinned = false)

    @Test
    fun theBadgeShowsWhileAThreadNeedsYou() {
        assertFalse(badged(emptyList()))
        assertTrue(badged(listOf(thread("a", ThreadState.Working), thread("b", ThreadState.NeedsYou(WaitReason.PERMISSION, 1)))))
        assertFalse(badged(listOf(thread("a", ThreadState.YourTurn(1)))))
    }

    private val calm = listOf(thread("a", ThreadState.Working))
    private val waiting = listOf(thread("a", ThreadState.NeedsYou(WaitReason.PERMISSION, 1)))

    @Test
    fun noIconIsTouchedBeforeTheFirstBadge() {
        // the platform puts the plugin.xml icon on after init: an early "plain" would beat it to the stripe
        assertEquals(emptyList<Boolean>(), runBlocking { badgeFlips(flowOf(emptyList(), calm, calm)).toList() })
    }

    @Test
    fun theBadgedIconCanBeScaledByTheStripe() {
        // the new UI's stripe button casts every tool window icon to ScalableIcon before it scales it; an icon that is
        // not one makes each setIcon throw a ClassCastException on the EDT, and the dot never shows
        val icon = ToolWindowBadge.badgedIcon(EmptyIcon.create(20))
        assertTrue(icon is ScalableIcon)
        // scaling keeps the dot
        assertTrue((icon as ScalableIcon).scale(2f) is BadgeIcon)
    }

    @Test
    fun theBadgeFlipsOnlyWhenItsStateChanges() {
        val flips = runBlocking { badgeFlips(flowOf(emptyList(), calm, waiting, waiting, calm, emptyList(), waiting)).toList() }
        assertEquals(listOf(true, false, true), flips)
    }
}
