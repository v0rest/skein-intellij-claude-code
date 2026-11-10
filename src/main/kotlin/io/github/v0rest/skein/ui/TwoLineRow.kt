package io.github.v0rest.skein.ui

import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import java.awt.Color
import java.awt.Dimension
import java.awt.Graphics
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import kotlin.math.max
import kotlin.math.min

/**
 * The reused row component of the thread list, the History and the Worktrees section, laid out by hand so the title
 * gives way and the age never does. Line 1: a glyph, the title, a pin, a worktree's counts ([changes] and [ahead],
 * hidden unless shown, which keep their width like the age) and the age on the right. Line 2, small: [detail], cut at the row's end,
 * then a coloured word ([status], hidden unless shown) that keeps its width. A thread row also has [context] at the
 * right end of line 2, under the age: the model and the context in use, hidden unless shown, which keeps its width as
 * well, so the detail gives way first. A thread row may also carry a colour
 * [stripe] at the left edge and the in-front marker; the glyph sits after the stripe's room either way, so the two lists
 * line up. Line 2 keeps its height when empty, so every row of a list is as tall as the others.
 */
internal class TwoLineRow : JPanel(null) {
    internal val glyph = JLabel()
    internal val title = textLabel()
    internal val pin = JLabel()
    internal val age = textLabel()

    /** A worktree's uncommitted paths (`✎3`), the word of an operation in progress, or `?`; before [ahead]. */
    internal val changes = textLabel().apply { isVisible = false }

    /** The commits on a worktree's branch that its base does not have (`↑2`), right before the age. */
    internal val ahead = textLabel().apply { isVisible = false }
    internal val detail = textLabel()
    internal val status = textLabel().apply { isVisible = false }
    internal val context = textLabel().apply { isVisible = false }
    internal val greyLabels = arrayOf(age, detail, context)
    private val firstLine = arrayOf(glyph, title, pin, changes, ahead, age)

    /** The saturated colour of the stripe at the left edge, or null for a row without a colour. */
    internal var stripe: Color? = null

    /** The thread's tab is the one in front and the row is not selected. */
    internal var frontMarker = false

    init {
        isOpaque = true
        for (c in firstLine) add(c)
        add(detail)
        add(status)
        add(context)
    }

    // the width does not depend on the title, so the list never grows wider than the tool window to fit one
    override fun getPreferredSize(): Dimension {
        val ageWidth = if (age.isVisible) JBUI.scale(GAP_BEFORE_AGE) + age.preferredSize.width else 0
        val width = textStart() + JBUI.scale(MIN_NAME_WIDTH) + countsRoom() + ageWidth + JBUI.scale(PAD_RIGHT)
        val height = JBUI.scale(PAD_V) + firstLineHeight() + JBUI.scale(LINE_GAP) + secondLineHeight() + JBUI.scale(PAD_V)
        return Dimension(width, height)
    }

    override fun doLayout() {
        val top = JBUI.scale(PAD_V)
        val line = firstLineHeight()
        var end = width - JBUI.scale(PAD_RIGHT)
        put(glyph, JBUI.scale(STRIPE_WIDTH + PAD_LEFT), glyph.preferredSize.width, top, line)
        if (age.isVisible) {
            val ageWidth = age.preferredSize.width
            put(age, end - ageWidth, ageWidth, top, line)
            end -= ageWidth + JBUI.scale(GAP_BEFORE_AGE)
        }
        // the counts end where the age begins, and keep their width as it does
        var countsX = end
        for (label in arrayOf(ahead, changes)) {
            if (!label.isVisible) continue
            val w = label.preferredSize.width
            put(label, countsX - w, w, top, line)
            countsX -= w + JBUI.scale(GAP_BETWEEN_COUNTS)
        }
        end -= countsRoom()
        val nameX = textStart()
        val x = put(title, nameX, min(title.preferredSize.width, nameRoom(width)), top, line)
        if (pin.isVisible) put(pin, x + JBUI.scale(GAP_SMALL), min(pin.preferredSize.width, end - x - JBUI.scale(GAP_SMALL)), top, line)
        layOutSecondLine(nameX, top + line + JBUI.scale(LINE_GAP))
    }

    /** Line 2 at [x], [y]: the detail takes what the status word leaves ([detailRoom]), and is cut there. */
    private fun layOutSecondLine(x: Int, y: Int) {
        val height = secondLineHeight()
        val room = detailRoom(width)
        if (context.isVisible) {
            val contextWidth = contextWidth(width)
            context.setBounds(width - JBUI.scale(PAD_RIGHT) - contextWidth, y, contextWidth, height)
        }
        if (!status.isVisible) {
            detail.setBounds(x, y, room, height)
            return
        }
        val detailWidth = min(detail.preferredSize.width, room)
        detail.setBounds(x, y, detailWidth, height)
        status.setBounds(x + detailWidth + statusGap(), y, statusWidth(width), height)
    }

    /** The width line 2 gives the detail in a row [rowWidth] wide, once the status word, when shown, has its own. */
    internal fun detailRoom(rowWidth: Int): Int {
        var room = max(0, rowWidth - JBUI.scale(PAD_RIGHT) - textStart())
        if (context.isVisible) room = max(0, room - JBUI.scale(GAP_BEFORE_CONTEXT) - contextWidth(rowWidth))
        return if (status.isVisible) max(0, room - statusGap() - statusWidth(rowWidth)) else room
    }

    /**
     * The width the model and context get on line 2 of a row [rowWidth] wide: all of their text, never wrapped,
     * unless the line itself is narrower.
     */
    private fun contextWidth(rowWidth: Int): Int = min(context.preferredSize.width, max(0, rowWidth - JBUI.scale(PAD_RIGHT) - textStart()))

    private fun statusWidth(rowWidth: Int): Int = min(status.preferredSize.width, max(0, rowWidth - JBUI.scale(PAD_RIGHT) - textStart()))

    private fun statusGap(): Int = if (detail.text.isNullOrEmpty()) 0 else JBUI.scale(GAP_BEFORE_STATUS)

    /** The width the title gets in a row [rowWidth] wide, once the age, the counts and the pin have theirs. */
    internal fun nameRoom(rowWidth: Int): Int {
        var end = rowWidth - JBUI.scale(PAD_RIGHT)
        if (age.isVisible) end -= age.preferredSize.width + JBUI.scale(GAP_BEFORE_AGE)
        end -= countsRoom()
        val pinRoom = if (pin.isVisible) JBUI.scale(GAP_SMALL) + pin.preferredSize.width else 0
        return max(0, end - textStart() - pinRoom)
    }

    override fun paintComponent(g: Graphics) {
        g.color = background
        g.fillRect(0, 0, width, height)
        val stripeWidth = JBUI.scale(STRIPE_WIDTH)
        stripe?.let {
            g.color = it
            g.fillRect(0, 0, stripeWidth, height)
        }
        if (frontMarker) {
            // beside the stripe, so a coloured thread in front shows both
            g.color = UIUtil.getListSelectionBackground(true)
            g.fillRect(if (stripe != null) stripeWidth else 0, 0, JBUI.scale(MARKER_WIDTH), height)
        }
    }

    /** The room the counts take on line 1, the gap before them included; 0 while neither is shown. */
    private fun countsRoom(): Int {
        val shown = arrayOf(changes, ahead).filter { it.isVisible }
        if (shown.isEmpty()) return 0
        return shown.sumOf { it.preferredSize.width } + JBUI.scale(GAP_BETWEEN_COUNTS) * (shown.size - 1) + JBUI.scale(GAP_BEFORE_COUNTS)
    }

    /** Where the title and the second line start: after the stripe, the padding and the glyph. */
    private fun textStart(): Int = JBUI.scale(STRIPE_WIDTH + PAD_LEFT) + glyph.preferredSize.width + JBUI.scale(GAP_AFTER_GLYPH)

    private fun firstLineHeight(): Int {
        var height = 0
        for (c in firstLine) if (c.isVisible) height = max(height, c.preferredSize.height)
        return height
    }

    /** One line of the detail's font, also while it is empty (an empty label asks for no height at all). */
    private fun secondLineHeight(): Int {
        val font = detail.font ?: return detail.preferredSize.height
        val height = detail.getFontMetrics(font).height
        return if (status.isVisible) max(height, status.preferredSize.height) else height
    }

    /** Places [c] at [x], [width] wide (never negative), centred in the first line; returns where it ends. */
    private fun put(c: JComponent, x: Int, width: Int, top: Int, line: Int): Int {
        val w = max(0, width)
        val h = c.preferredSize.height
        c.setBounds(x, top + (line - h) / 2, w, h)
        return x + w
    }

    private companion object {
        const val STRIPE_WIDTH = 4
        const val MARKER_WIDTH = 2
        const val PAD_LEFT = 4
        const val PAD_RIGHT = 8
        const val PAD_V = 3
        const val LINE_GAP = 1
        const val GAP_AFTER_GLYPH = 2
        const val GAP_SMALL = 2
        const val GAP_BEFORE_AGE = 8
        const val GAP_BEFORE_STATUS = 6
        const val GAP_BEFORE_CONTEXT = 8
        const val GAP_BEFORE_COUNTS = 8
        const val GAP_BETWEEN_COUNTS = 4
        const val MIN_NAME_WIDTH = 40

        /** A label for text Skein did not write (names come from Claude Code and the user): never read as HTML. */
        fun textLabel(): JLabel = JLabel().apply { putClientProperty("html.disable", true) }
    }
}
