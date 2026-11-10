package io.github.v0rest.skein.ui

import com.intellij.ui.JBColor
import io.github.v0rest.skein.threads.ThreadColor
import java.awt.Color

/** The one palette for thread colours: a pastel for row and editor-tab backgrounds, a saturated one for the mark. */
object ThreadColors {
    /** Light and dark variant of the pastel background. */
    internal fun tintVariants(color: ThreadColor): Pair<Color, Color> = when (color) {
        ThreadColor.RED -> Color(0xFAD4D4) to Color(0x4F2B2D)
        ThreadColor.ORANGE -> Color(0xFCE3CC) to Color(0x4F3A27)
        ThreadColor.YELLOW -> Color(0xF7EFC0) to Color(0x4A4528)
        ThreadColor.GREEN -> Color(0xD6EDD3) to Color(0x2B4330)
        ThreadColor.BLUE -> Color(0xC8E6F0) to Color(0x2B3A52)
        ThreadColor.PURPLE -> Color(0xE6D9F5) to Color(0x3E3050)
    }

    /** Light and dark variant of the row background: about twice as strong as [tintVariants], which the editor tab keeps. */
    internal fun rowTintVariants(color: ThreadColor): Pair<Color, Color> = when (color) {
        ThreadColor.RED -> Color(0xF3AFAF) to Color(0x6E3338)
        ThreadColor.ORANGE -> Color(0xF8C998) to Color(0x6E4A27)
        ThreadColor.YELLOW -> Color(0xF0DE80) to Color(0x625824)
        ThreadColor.GREEN -> Color(0xAEDCA8) to Color(0x316040)
        ThreadColor.BLUE -> Color(0x93CFE6) to Color(0x30517F)
        ThreadColor.PURPLE -> Color(0xCDB0EC) to Color(0x58407F)
    }

    /** Light and dark variant of the saturated mark. */
    internal fun markVariants(color: ThreadColor): Pair<Color, Color> = when (color) {
        ThreadColor.RED -> Color(0xE05555) to Color(0xD9686B)
        ThreadColor.ORANGE -> Color(0xC8641A) to Color(0xE59A52)
        ThreadColor.YELLOW -> Color(0xA88600) to Color(0xD6B84A)
        ThreadColor.GREEN -> Color(0x2E8B3A) to Color(0x6BB86F)
        ThreadColor.BLUE -> Color(0x3D82E0) to Color(0x5A94E0)
        ThreadColor.PURPLE -> Color(0x9055D6) to Color(0xA57BDB)
    }

    /** The pastel background for a row or an editor tab. */
    fun of(color: ThreadColor): JBColor = tintVariants(color).let { JBColor(it.first, it.second) }

    /** The background of a thread row in the list. */
    fun rowTint(color: ThreadColor): JBColor = rowTintVariants(color).let { JBColor(it.first, it.second) }

    /** The saturated colour of the stripe at a row's left edge, visible also on a selected row. */
    fun mark(color: ThreadColor): JBColor = markVariants(color).let { JBColor(it.first, it.second) }
}
