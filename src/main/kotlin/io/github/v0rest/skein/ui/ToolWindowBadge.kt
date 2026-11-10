package io.github.v0rest.skein.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.EDT
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.ToolWindow
import com.intellij.ui.BadgeIcon
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.swing.Icon

/** The tool window's stripe icon shows a dot while any thread waits for the user. */
fun badged(threads: List<SkeinThread>): Boolean = threads.any { it.state is ThreadState.NeedsYou }

/**
 * What the stripe icon has to show, as [threads] change: nothing until a thread first needs the user, then each flip
 * of [badged]. The platform puts the plugin.xml icon on only after the factory's `init`, so an icon set before the
 * first badge would take its place.
 */
internal fun badgeFlips(threads: Flow<List<SkeinThread>>): Flow<Boolean> = threads.map(::badged).distinctUntilChanged().dropWhile { !it }

/** Keeps the stripe icon of [toolWindow] in step with [threads]: the plain Spark, or the Spark with the needs-you dot. */
object ToolWindowBadge {
    /** Collects on the EDT and calls `setIcon` only when the badge flips ([badgeFlips]); stops with [parent]. */
    fun bind(toolWindow: ToolWindow, threads: Flow<List<SkeinThread>>, parent: Disposable) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.EDT)
        Disposer.register(parent) { scope.cancel() }
        scope.launch {
            // read at the first badge, when the platform has long put on the icon it made from plugin.xml; the badge
            // paints on top of that one, and the new UI's stripe scales the icon inside the badge (skein@20x20.svg)
            var icons: Pair<Icon, Icon>? = null
            badgeFlips(threads).collect { on ->
                val (plain, withDot) = icons ?: (toolWindow.icon ?: SkeinIcons.TOOL_WINDOW).let { it to badgedIcon(it) }.also { icons = it }
                toolWindow.setIcon(if (on) withDot else plain)
            }
        }
    }

    /**
     * [base] with the needs-you dot, drawn by the platform's own [BadgeIcon], the badge its tool windows use: the new
     * UI's stripe button scales every tool window icon as a ScalableIcon, which [BadgeIcon] is (through HoledIcon)
     */
    internal fun badgedIcon(base: Icon): Icon = BadgeIcon(base, SkeinIcons.NEEDS_YOU_COLOR)
}
