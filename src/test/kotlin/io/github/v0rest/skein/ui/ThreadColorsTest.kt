package io.github.v0rest.skein.ui

import io.github.v0rest.skein.threads.ThreadColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ThreadColorsTest {
    private val all = ThreadColor.entries

    @Test
    fun everyThreadColorHasADistinctColour() {
        assertEquals("row tints, light", all.size, all.map { ThreadColors.tintVariants(it).first.rgb }.toSet().size)
        assertEquals("row tints, dark", all.size, all.map { ThreadColors.tintVariants(it).second.rgb }.toSet().size)
        assertEquals("marks, light", all.size, all.map { ThreadColors.markVariants(it).first.rgb }.toSet().size)
        assertEquals("marks, dark", all.size, all.map { ThreadColors.markVariants(it).second.rgb }.toSet().size)
    }

    @Test
    fun theTablesCoverEveryThreadColour() {
        assertEquals(6, all.size)
        all.forEach { color ->
            val (lightTint, darkTint) = ThreadColors.tintVariants(color)
            val (lightMark, darkMark) = ThreadColors.markVariants(color)
            assertNotEquals("$color tint must differ between light and dark", lightTint.rgb, darkTint.rgb)
            assertNotEquals("$color mark must differ between light and dark", lightMark.rgb, darkMark.rgb)
            assertNotEquals("$color light mark must not be its tint", lightTint.rgb, lightMark.rgb)
            assertNotEquals("$color dark mark must not be its tint", darkTint.rgb, darkMark.rgb)
        }
    }

    private fun luminance(c: java.awt.Color): Double {
        fun lin(v: Int): Double = (v / 255.0).let { if (it <= 0.03928) it / 12.92 else Math.pow((it + 0.055) / 1.055, 2.4) }
        return 0.2126 * lin(c.red) + 0.7152 * lin(c.green) + 0.0722 * lin(c.blue)
    }

    @Test
    fun rowTintIsStrongerThanTheTabTint() {
        all.forEach { color ->
            val (tabLight, tabDark) = ThreadColors.tintVariants(color)
            val (rowLight, rowDark) = ThreadColors.rowTintVariants(color)
            assertTrue("$color light: ${luminance(rowLight)} vs ${luminance(tabLight)}", luminance(rowLight) <= luminance(tabLight) * 0.92)
            assertTrue("$color dark: ${luminance(rowDark)} vs ${luminance(tabDark)}", luminance(rowDark) >= luminance(tabDark) * 1.08)
        }
    }

    @Test
    fun rowTintsAreDistinctAndNeverTheirMark() {
        assertEquals(all.size, all.map { ThreadColors.rowTintVariants(it).first.rgb }.toSet().size)
        assertEquals(all.size, all.map { ThreadColors.rowTintVariants(it).second.rgb }.toSet().size)
        all.forEach { color ->
            val (rowLight, rowDark) = ThreadColors.rowTintVariants(color)
            val (markLight, markDark) = ThreadColors.markVariants(color)
            assertNotEquals(rowLight.rgb, markLight.rgb)
            assertNotEquals(rowDark.rgb, markDark.rgb)
            assertNotEquals(rowLight.rgb, rowDark.rgb)
            assertNotEquals(rowLight.rgb, ThreadColors.tintVariants(color).first.rgb)
        }
    }
}
