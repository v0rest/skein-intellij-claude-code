package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import com.intellij.ui.JBColor
import java.awt.image.BufferedImage

/** Which glyph a waiting thread gets, and the words its tooltip uses. Both stay orange, only the shape differs. */
class WaitGlyphTest {
    @Test
    fun eachReasonHasItsOwnGlyph() {
        assertEquals(WaitGlyph.PERMISSION, WaitGlyph.of(WaitReason.PERMISSION))
        assertEquals(WaitGlyph.QUESTION, WaitGlyph.of(WaitReason.QUESTION))
        assertEquals(WaitGlyph.DOT, WaitGlyph.of(WaitReason.OTHER))
    }

    @Test
    fun theThreeReasonsAreThreeDifferentIcons() {
        val icons = WaitReason.entries.map { SkeinIcons.forState(ThreadState.NeedsYou(it, 0)) }
        assertEquals(3, icons.toSet().size)
    }

    @Test
    fun theSinceTimeDoesNotChangeTheGlyph() {
        assertSame(
            SkeinIcons.forState(ThreadState.NeedsYou(WaitReason.QUESTION, 1)),
            SkeinIcons.forState(ThreadState.NeedsYou(WaitReason.QUESTION, 99)),
        )
    }

    @Test
    fun aNotificationKeepsTheDotWhateverTheReason() {
        assertSame(SkeinIcons.NEEDS_YOU, SkeinIcons.forKind(io.github.v0rest.skein.threads.NotificationKind.NEEDS_YOU))
        assertSame(SkeinIcons.NEEDS_YOU, SkeinIcons.forState(ThreadState.NeedsYou(WaitReason.OTHER, 0)))
    }

    @Test
    fun theTooltipSaysWhatTheThreadWaitsFor() {
        assertEquals("waiting for your permission", StatusText.tooltipLine(ThreadState.NeedsYou(WaitReason.PERMISSION, 0)))
        assertEquals("waiting for your answer", StatusText.tooltipLine(ThreadState.NeedsYou(WaitReason.QUESTION, 0)))
        assertEquals("needs you", StatusText.tooltipLine(ThreadState.NeedsYou(WaitReason.OTHER, 0)))
        assertEquals("idle", StatusText.tooltipLine(ThreadState.Idle))
        assertEquals("your turn", StatusText.tooltipLine(ThreadState.YourTurn(0)))
    }

    @Test
    fun theRowTooltipUsesThoseWords() {
        fun tip(r: WaitReason) = RowText.tooltip(SkeinThread("t1", "/w/acme", null, "acme", ThreadState.NeedsYou(r, 0), 0, 0, null))
        assertEquals("<html>waiting for your permission", tip(WaitReason.PERMISSION))
        assertEquals("<html>waiting for your answer", tip(WaitReason.QUESTION))
        assertNotEquals(tip(WaitReason.PERMISSION), tip(WaitReason.OTHER))
    }

    private fun paint(glyph: WaitGlyph): BufferedImage {
        val icon = SkeinIcons.forGlyph(glyph)
        val image = BufferedImage(icon.iconWidth, icon.iconHeight, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        try {
            icon.paintIcon(null, g, 0, 0)
        } finally {
            g.dispose()
        }
        return image
    }

    private fun BufferedImage.inked(x: Int, y: Int) = (getRGB(x, y) ushr 24) > 128

    /** Ink inside the ring, where only the glyph itself draws (the ring's inner edge is about 5.5 px from the middle). */
    private fun BufferedImage.innerInk() = (0 until height).sumOf { y ->
        (0 until width).count { x -> (x - 7.5) * (x - 7.5) + (y - 7.5) * (y - 7.5) < 4.5 * 4.5 && inked(x, y) }
    }

    /** Each shape is told apart by three pixels of the 16 px icon: the middle of the shackle, and two inside the glyph. */
    @Test
    fun eachGlyphHasItsOwnShapeAndNoneIsEmpty() {
        val dot = paint(WaitGlyph.DOT)
        val lock = paint(WaitGlyph.PERMISSION)
        val bubble = paint(WaitGlyph.QUESTION)
        listOf(dot, lock, bubble).forEach { assertTrue("something inside the ring", it.innerInk() > 15) }
        // the dot fills the middle and the lower right, the lock has a hollow shackle, the bubble leaves the lower right empty
        assertTrue(dot.inked(8, 6) && dot.inked(9, 11))
        assertTrue(!lock.inked(8, 6) && lock.inked(9, 11))
        assertTrue(bubble.inked(8, 6) && !bubble.inked(9, 11))
    }

    @Test
    fun everyGlyphIsOrangeInBothThemes() {
        for (dark in listOf(false, true)) {
            JBColor.setDark(dark)
            try {
                for (glyph in WaitGlyph.entries) {
                    val image = paint(glyph)
                    val colours = (0 until image.height).flatMap { y -> (0 until image.width).map { x -> image.getRGB(x, y) } }
                        .filter { (it ushr 24) == 255 }.map { it and 0xFFFFFF }.toSet()
                    assertEquals("$glyph dark=$dark", setOf(SkeinIcons.NEEDS_YOU_COLOR.rgb and 0xFFFFFF), colours)
                }
            } finally {
                JBColor.setDark(false)
            }
        }
    }

    @Test
    fun eachReasonGetsItsOwnGlyph() {
        assertSame(SkeinIcons.forGlyph(WaitGlyph.PERMISSION), SkeinIcons.forState(ThreadState.NeedsYou(WaitReason.PERMISSION, 0)))
        assertSame(SkeinIcons.forGlyph(WaitGlyph.QUESTION), SkeinIcons.forState(ThreadState.NeedsYou(WaitReason.QUESTION, 0)))
        assertSame(SkeinIcons.forGlyph(WaitGlyph.DOT), SkeinIcons.forState(ThreadState.NeedsYou(WaitReason.OTHER, 0)))
    }
}
