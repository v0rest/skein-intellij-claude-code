package io.github.v0rest.skein.ui

import com.intellij.ui.JBColor
import io.github.v0rest.skein.threads.ThreadColor
import java.awt.Color

/**
 * The one palette for thread colours - a tint for the row's background, a stronger colour for the editor tab and a
 * saturated one for the stripe at the row's left edge, each with a light and a dark variant.
 *
 * Under the New UI's Islands themes the tab gets more than the row, because that painter lays an unselected coloured
 * tab, and a selected one whose tab group has no focus, at 40% over the tab bar, and only a selected tab with the focus
 * shows the full colour, under the tab's text. So the dark tab colours are lighter than the row tints and the light ones
 * darker - white and a light grey text such as #DFE1E5 read at 4.5:1 or more on the dark ones, a dimmer text such as
 * classic Darcula's #BBBBBB only at about 3:1. The other painters (classic themes, the New UI without Islands) show a
 * selected coloured tab at full strength whether it has the focus or not, so there the tab takes the row's tint, which
 * is made for text on top ([tabVariantsFor]).
 *
 * The row tints keep the list's own text readable (5:1 or more for a light grey list text on the dark tints, 12:1 or more
 * for black on the light ones), and the dark stripes stand out from a selected row too (3:1 or more against the usual
 * dark selection colours). ThreadColorsTest holds the numbers.
 */
object ThreadColors {
    /** Light and dark variant of the editor tab's colour, stronger than [rowTintVariants] since the tab shows it at 40% mostly. */
    internal fun tabVariants(color: ThreadColor): Pair<Color, Color> = when (color) {
        ThreadColor.RED -> Color(0xFF9EA7) to Color(0xA83E4F)
        ThreadColor.ORANGE -> Color(0xFDA767) to Color(0x9A4F00)
        ThreadColor.YELLOW -> Color(0xCFC154) to Color(0x6F6400)
        ThreadColor.GREEN -> Color(0x7CD591) to Color(0x007332)
        ThreadColor.BLUE -> Color(0x7EC6FF) to Color(0x0067A2)
        ThreadColor.PURPLE -> Color(0xD7A6FC) to Color(0x804CA2)
    }

    /** Light and dark variant of the row background. */
    internal fun rowTintVariants(color: ThreadColor): Pair<Color, Color> = when (color) {
        ThreadColor.RED -> Color(0xFFB3B9) to Color(0x8E3543)
        ThreadColor.ORANGE -> Color(0xFFB988) to Color(0x824200)
        ThreadColor.YELLOW -> Color(0xDBCD67) to Color(0x5D5400)
        ThreadColor.GREEN -> Color(0x8CE29F) to Color(0x006029)
        ThreadColor.BLUE -> Color(0x79D9FF) to Color(0x005789)
        ThreadColor.PURPLE -> Color(0xE0B7FF) to Color(0x6B4188)
    }

    /** Light and dark variant of the saturated mark. */
    internal fun markVariants(color: ThreadColor): Pair<Color, Color> = when (color) {
        ThreadColor.RED -> Color(0xE54B67) to Color(0xF37182)
        ThreadColor.ORANGE -> Color(0xC56700) to Color(0xF88E37)
        ThreadColor.YELLOW -> Color(0x9B8C00) to Color(0xCFBD1B)
        ThreadColor.GREEN -> Color(0x008E40) to Color(0x48BE6B)
        ThreadColor.BLUE -> Color(0x008AD6) to Color(0x20A1F4)
        ThreadColor.PURPLE -> Color(0x9C50CC) to Color(0xBE81E9)
    }

    /**
     * Light and dark variant of a thread's editor tab colour: [tabVariants] when the Islands painter draws the tabs and
     * shows the colour at 40% mostly, else the row's tint, since the other painters show a selected tab at full strength
     * under its text.
     */
    internal fun tabVariantsFor(color: ThreadColor, islands: Boolean): Pair<Color, Color> =
        if (islands) tabVariants(color) else rowTintVariants(color)

    /** The colour of a thread's editor tab ([ThreadTabColorProvider]); [islands] is whether the Islands painter draws the tabs. */
    fun tab(color: ThreadColor, islands: Boolean): JBColor = tabVariantsFor(color, islands).let { JBColor(it.first, it.second) }

    /** The background of a thread row in the list. */
    fun rowTint(color: ThreadColor): JBColor = rowTintVariants(color).let { JBColor(it.first, it.second) }

    /** The saturated colour of the stripe at a row's left edge, visible also on a selected row. */
    fun mark(color: ThreadColor): JBColor = markVariants(color).let { JBColor(it.first, it.second) }
}
