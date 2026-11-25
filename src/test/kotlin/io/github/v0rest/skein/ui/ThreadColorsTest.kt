package io.github.v0rest.skein.ui

import io.github.v0rest.skein.threads.ThreadColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.Color

class ThreadColorsTest {
    private val all = ThreadColor.entries

    private fun hex(c: Color) = "#%06X".format(c.rgb and 0xFFFFFF)

    private fun hex(pair: Pair<Color, Color>) = hex(pair.first) to hex(pair.second)

    @Test
    fun theRowsTabsAndStripesHaveTheChosenPalette() {
        val rows = mapOf(
            ThreadColor.RED to ("#FFB3B9" to "#8E3543"),
            ThreadColor.ORANGE to ("#FFB988" to "#824200"),
            ThreadColor.YELLOW to ("#DBCD67" to "#5D5400"),
            ThreadColor.GREEN to ("#8CE29F" to "#006029"),
            ThreadColor.BLUE to ("#79D9FF" to "#005789"),
            ThreadColor.PURPLE to ("#E0B7FF" to "#6B4188"),
        )
        val tabs = mapOf(
            ThreadColor.RED to ("#FF9EA7" to "#A83E4F"),
            ThreadColor.ORANGE to ("#FDA767" to "#9A4F00"),
            ThreadColor.YELLOW to ("#CFC154" to "#6F6400"),
            ThreadColor.GREEN to ("#7CD591" to "#007332"),
            ThreadColor.BLUE to ("#7EC6FF" to "#0067A2"),
            ThreadColor.PURPLE to ("#D7A6FC" to "#804CA2"),
        )
        val stripes = mapOf(
            ThreadColor.RED to ("#E54B67" to "#F37182"),
            ThreadColor.ORANGE to ("#C56700" to "#F88E37"),
            ThreadColor.YELLOW to ("#9B8C00" to "#CFBD1B"),
            ThreadColor.GREEN to ("#008E40" to "#48BE6B"),
            ThreadColor.BLUE to ("#008AD6" to "#20A1F4"),
            ThreadColor.PURPLE to ("#9C50CC" to "#BE81E9"),
        )
        all.forEach { color ->
            assertEquals("$color row", rows.getValue(color), hex(ThreadColors.rowTintVariants(color)))
            assertEquals("$color tab", tabs.getValue(color), hex(ThreadColors.tabVariants(color)))
            assertEquals("$color stripe", stripes.getValue(color), hex(ThreadColors.markVariants(color)))
        }
    }

    @Test
    fun everyThreadColorHasADistinctColour() {
        assertEquals("row tints, light", all.size, all.map { ThreadColors.rowTintVariants(it).first.rgb }.toSet().size)
        assertEquals("row tints, dark", all.size, all.map { ThreadColors.rowTintVariants(it).second.rgb }.toSet().size)
        assertEquals("tabs, light", all.size, all.map { ThreadColors.tabVariants(it).first.rgb }.toSet().size)
        assertEquals("tabs, dark", all.size, all.map { ThreadColors.tabVariants(it).second.rgb }.toSet().size)
        assertEquals("marks, light", all.size, all.map { ThreadColors.markVariants(it).first.rgb }.toSet().size)
        assertEquals("marks, dark", all.size, all.map { ThreadColors.markVariants(it).second.rgb }.toSet().size)
    }

    @Test
    fun theTablesCoverEveryThreadColour() {
        assertEquals(6, all.size)
        all.forEach { color ->
            val (lightRow, darkRow) = ThreadColors.rowTintVariants(color)
            val (lightTab, darkTab) = ThreadColors.tabVariants(color)
            val (lightMark, darkMark) = ThreadColors.markVariants(color)
            assertNotEquals("$color row must differ between light and dark", lightRow.rgb, darkRow.rgb)
            assertNotEquals("$color tab must differ between light and dark", lightTab.rgb, darkTab.rgb)
            assertNotEquals("$color mark must differ between light and dark", lightMark.rgb, darkMark.rgb)
            assertNotEquals("$color light mark must not be its row", lightRow.rgb, lightMark.rgb)
            assertNotEquals("$color dark mark must not be its row", darkRow.rgb, darkMark.rgb)
            assertNotEquals("$color light tab must not be its row", lightRow.rgb, lightTab.rgb)
            assertNotEquals("$color dark tab must not be its row", darkRow.rgb, darkTab.rgb)
        }
    }

    /** WCAG relative luminance, with the sRGB threshold of WCAG 2.x. */
    private fun luminance(c: Color): Double {
        fun lin(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(luminance(a), luminance(b)).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    /**
     * The IDE paints an unselected coloured editor tab, and a selected one without the focus, with the tab colour at 40%
     * over the tab bar (the New UI's Islands painter), so the tab gets a stronger colour than the row, the other way
     * round from the old pastel tab - lighter than the row tint in the dark variant, darker in the light one.
     */
    @Test
    fun theTabColourIsStrongerThanTheRowTint() {
        all.forEach { color ->
            val (tabLight, tabDark) = ThreadColors.tabVariants(color)
            val (rowLight, rowDark) = ThreadColors.rowTintVariants(color)
            assertTrue("$color light: ${luminance(tabLight)} vs ${luminance(rowLight)}", luminance(tabLight) <= luminance(rowLight) * 0.9)
            assertTrue("$color dark: ${luminance(tabDark)} vs ${luminance(rowDark)}", luminance(tabDark) >= luminance(rowDark) * 1.3)
        }
    }

    /** The row's text stays readable on its tint - the list text of a dark theme, and black in a light one. */
    @Test
    fun listTextReadsOnEveryRowTint() {
        // the list text of a dark theme with a light grey one, and of Islands Dark
        val darkListText = Color(0xD0D0D9)
        val islandsDark = Color(0xD1D3D9)
        all.forEach { color ->
            val (light, dark) = ThreadColors.rowTintVariants(color)
            assertTrue("$color dark: ${contrast(darkListText, dark)}", contrast(darkListText, dark) >= 5.0)
            assertTrue("$color dark, Islands Dark: ${contrast(islandsDark, dark)}", contrast(islandsDark, dark) >= 5.1)
            assertTrue("$color light: ${contrast(Color.BLACK, light)}", contrast(Color.BLACK, light) >= 12.0)
        }
    }

    /**
     * Under the Islands painter a selected focused tab shows its colour at full strength under the tab's text. White
     * stands for the brightest text of a dark theme, and a light grey one such as #DFE1E5 still reads at 4.5:1. Classic
     * themes such as Darcula, whose #BBBBBB would read at about 3:1 here, never get these colours - their painter shows a
     * selected tab at full strength always, so it gets the row's tint ([theTabTakesTheStrongColourOnlyUnderTheIslandsPainter]).
     */
    @Test
    fun tabTextReadsOnEveryTabColour() {
        val lightGreyText = Color(0xDFE1E5)
        all.forEach { color ->
            val (light, dark) = ThreadColors.tabVariants(color)
            assertTrue("$color dark: ${contrast(Color.WHITE, dark)}", contrast(Color.WHITE, dark) >= 6.0)
            assertTrue("$color dark, light grey text: ${contrast(lightGreyText, dark)}", contrast(lightGreyText, dark) >= 4.5)
            assertTrue("$color light: ${contrast(Color.BLACK, light)}", contrast(Color.BLACK, light) >= 10.0)
        }
    }

    /**
     * The Islands painter shows the tab colour at 40% mostly, so it gets the stronger tab colour; the other painters
     * show a selected tab at full strength under its text, so they get the row's tint, made for text on top - classic
     * Darcula's dim #BBBBBB still reads at about 4:1 there, a light grey #DFE1E5 at 5:1 or more.
     */
    @Test
    fun theTabTakesTheStrongColourOnlyUnderTheIslandsPainter() {
        val dimText = Color(0xBBBBBB)
        val lightGreyText = Color(0xDFE1E5)
        all.forEach { color ->
            assertEquals("$color islands", ThreadColors.tabVariants(color), ThreadColors.tabVariantsFor(color, islands = true))
            val other = ThreadColors.tabVariantsFor(color, islands = false)
            assertEquals("$color other painters", ThreadColors.rowTintVariants(color), other)
            assertTrue("$color dark, dim text: ${contrast(dimText, other.second)}", contrast(dimText, other.second) >= 3.9)
            assertTrue("$color dark, light grey text: ${contrast(lightGreyText, other.second)}", contrast(lightGreyText, other.second) >= 5.0)
            assertTrue("$color light: ${contrast(Color.BLACK, other.first)}", contrast(Color.BLACK, other.first) >= 12.0)
        }
    }

    /** The stripe stays on a selected row, so in a dark theme it stands out from the selection colours too. */
    @Test
    fun theDarkStripeStandsOutOnASelectedRow() {
        val selections = listOf(Color(0x3C505A), Color(0x2A4371))
        all.forEach { color ->
            val stripe = ThreadColors.markVariants(color).second
            selections.forEach { selection ->
                assertTrue("$color on ${hex(selection)}: ${contrast(stripe, selection)}", contrast(stripe, selection) >= 3.0)
            }
        }
    }
}
