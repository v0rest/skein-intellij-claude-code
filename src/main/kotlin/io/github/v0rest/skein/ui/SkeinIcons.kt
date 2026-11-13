package io.github.v0rest.skein.ui

import com.intellij.openapi.util.IconLoader
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.JBColor
import com.intellij.ui.scale.JBUIScale
import com.intellij.util.ui.JBUI
import io.github.v0rest.skein.threads.NotificationKind
import io.github.v0rest.skein.threads.ThreadState
import java.awt.BasicStroke
import java.awt.Color
import java.awt.Component
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.geom.Arc2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Line2D
import java.awt.geom.RoundRectangle2D
import java.awt.Polygon
import javax.swing.Icon
import javax.swing.UIManager

object SkeinIcons {
    @JvmField
    val TOOL_WINDOW: Icon = IconLoader.getIcon("/icons/skein.svg", SkeinIcons::class.java)

    /**
     * The History's eye while done sessions are hidden: the platform's eye, crossed out. The 2026.3 platform has no
     * crossed-out or hidden eye of its own (`AllIcons.Actions.ToggleVisibility` is an eye beside a list,
     * `AllIcons.General.HideToolWindow` a minimise bar), so this one is drawn on the platform's eye.
     */
    val DONE_SESSIONS_HIDDEN: Icon = IconLoader.getIcon("/icons/doneSessionsHidden.svg", SkeinIcons::class.java)

    /** Orange: a thread waits for the user. */
    val NEEDS_YOU_COLOR = JBColor(Color(0xD97D0D), Color(0xE5A44C))

    /** Blue: a thread finished its turn and the user has not looked yet. */
    val YOUR_TURN_COLOR = JBColor(Color(0x3574F0), Color(0x548AF7))

    private val QUIET_COLOR = JBColor(Color(0xA8ADBD), Color(0x6F737A))

    /** One ring icon per shape, the only place a [WaitGlyph] becomes an icon. All of them are [NEEDS_YOU_COLOR]. */
    private val WAIT_ICONS: Map<WaitGlyph, Icon> = WaitGlyph.entries.associateWith { RingIcon(NEEDS_YOU_COLOR, it) }

    /**
     * The orange dot inside a ring, the glyph of a thread that waits for the user for any reason other than a
     * permission or a question. A notification and the toolbar counter use it for every reason.
     */
    val NEEDS_YOU: Icon = WAIT_ICONS.getValue(WaitGlyph.DOT)

    /** The orange ring with [glyph] in it. */
    internal fun forGlyph(glyph: WaitGlyph): Icon = WAIT_ICONS.getValue(glyph)

    /** The grey question mark of a thread whose state is not known. */
    private val UNKNOWN: Icon = QuestionIcon(QUIET_COLOR)

    /** The blue dot of a thread whose turn finished. */
    val YOUR_TURN: Icon = DotIcon(YOUR_TURN_COLOR, filled = true)
    private val QUIET: Icon = DotIcon(QUIET_COLOR, filled = true)
    private val OFF: Icon = DotIcon(QUIET_COLOR, filled = false)

    /**
     * The icon a notification carries. For a thread that needs you it is always the plain dot, while the row may show
     * a lock or a bubble.
     */
    fun forKind(kind: NotificationKind): Icon = when (kind) {
        NotificationKind.NEEDS_YOU -> NEEDS_YOU
        NotificationKind.TURN_FINISHED -> YOUR_TURN
    }

    fun forState(state: ThreadState): Icon = when (state) {
        is ThreadState.NeedsYou -> forGlyph(WaitGlyph.of(state.reason))
        is ThreadState.YourTurn -> YOUR_TURN
        ThreadState.Working, ThreadState.WorkingUnknown, ThreadState.Background, ThreadState.Starting -> AnimatedIcon.Default.INSTANCE
        ThreadState.Idle -> QUIET
        ThreadState.Unknown -> UNKNOWN
        ThreadState.NotStarted, ThreadState.Exited, is ThreadState.RunningElsewhere -> OFF
    }

    private class DotIcon(private val color: Color, private val filled: Boolean) : Icon {
        override fun getIconWidth(): Int = JBUI.scale(16)

        override fun getIconHeight(): Int = JBUI.scale(16)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.color = color
                val d = JBUI.scale(8)
                val offset = (iconWidth - d) / 2
                if (filled) g2.fillOval(x + offset, y + offset, d, d) else g2.drawOval(x + offset, y + offset, d - 1, d - 1)
            } finally {
                g2.dispose()
            }
        }
    }

    /** A ring with [inside] in it, the dot, a lock or a speech bubble, all in [color]. */
    private class RingIcon(private val color: Color, private val inside: WaitGlyph) : Icon {
        override fun getIconWidth(): Int = JBUI.scale(16)

        override fun getIconHeight(): Int = JBUI.scale(16)

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE)
                g2.color = color
                val stroke = JBUIScale.scale(1.5f)
                g2.stroke = BasicStroke(stroke)
                val ring = JBUI.scale(14).toFloat() - stroke
                val ringOffset = (iconWidth - ring) / 2f
                g2.draw(Ellipse2D.Float(x + ringOffset, y + ringOffset, ring, ring))
                when (inside) {
                    WaitGlyph.DOT -> {
                        val d = JBUI.scale(8).toFloat()
                        val offset = (iconWidth - d) / 2f
                        g2.fill(Ellipse2D.Float(x + offset, y + offset, d, d))
                    }
                    WaitGlyph.PERMISSION -> paintLock(g2, x, y)
                    WaitGlyph.QUESTION -> paintBubble(g2, x, y)
                }
            } finally {
                g2.dispose()
            }
        }

        /** A padlock about 6 px wide inside the 14 px ring, a filled body with a shackle arc over it. */
        private fun paintLock(g2: Graphics2D, x: Int, y: Int) {
            val cx = x + iconWidth / 2f
            val bodyW = JBUI.scale(6).toFloat()
            val bodyH = JBUIScale.scale(4.5f)
            val bodyTop = y + iconHeight / 2f - JBUIScale.scale(0.5f)
            g2.fill(RoundRectangle2D.Float(cx - bodyW / 2f, bodyTop, bodyW, bodyH, JBUIScale.scale(1f), JBUIScale.scale(1f)))
            val shackleW = JBUIScale.scale(3.6f)
            g2.stroke = BasicStroke(JBUIScale.scale(1.2f), BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND)
            val left = cx - shackleW / 2f
            val right = cx + shackleW / 2f
            val arcTop = bodyTop - JBUIScale.scale(1f) - shackleW / 2f
            g2.draw(Arc2D.Float(left, arcTop, shackleW, shackleW, 0f, 180f, Arc2D.OPEN))
            g2.draw(Line2D.Float(left, arcTop + shackleW / 2f, left, bodyTop))
            g2.draw(Line2D.Float(right, arcTop + shackleW / 2f, right, bodyTop))
        }

        /** A speech bubble about 8 px wide inside the ring, a rounded body with a tail at the lower left. */
        private fun paintBubble(g2: Graphics2D, x: Int, y: Int) {
            val left = x + iconWidth / 2f - JBUIScale.scale(4f)
            val top = y + iconHeight / 2f - JBUIScale.scale(4f)
            val w = JBUIScale.scale(8f)
            val h = JBUIScale.scale(5.5f)
            g2.fill(RoundRectangle2D.Float(left, top, w, h, JBUIScale.scale(2.5f), JBUIScale.scale(2.5f)))
            val tail = Polygon()
            val bottom = top + h
            tail.addPoint(Math.round(left + JBUIScale.scale(1.5f)), Math.round(bottom - JBUIScale.scale(1f)))
            tail.addPoint(Math.round(left + JBUIScale.scale(4f)), Math.round(bottom - JBUIScale.scale(1f)))
            tail.addPoint(Math.round(left + JBUIScale.scale(1.5f)), Math.round(bottom + JBUIScale.scale(2.5f)))
            g2.fill(tail)
        }
    }

    /**
     * A bold "?" in the IDE's label font, sized so the mark stands about as tall as the 8 px dots. The font is derived
     * once per IDE font and scale, not on every paint. EDT only, like all painting.
     */
    private class QuestionIcon(private val color: Color) : Icon {
        private var baseFont: Font? = null
        private var fontSize = 0f
        private var font: Font? = null

        override fun getIconWidth(): Int = JBUI.scale(16)

        override fun getIconHeight(): Int = JBUI.scale(16)

        private fun font(): Font {
            val base = UIManager.getFont("Label.font") ?: FALLBACK_FONT
            val size = JBUIScale.scale(FONT_SIZE)
            val cached = font
            if (cached != null && base === baseFont && size == fontSize) return cached
            baseFont = base
            fontSize = size
            return base.deriveFont(Font.BOLD, size).also { font = it }
        }

        override fun paintIcon(c: Component?, g: Graphics, x: Int, y: Int) {
            val g2 = g.create() as Graphics2D
            try {
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g2.color = color
                g2.font = font()
                val fm = g2.fontMetrics
                // centred on the cap height (about 0.7 of the ascent), which is what the eye reads as the mark's middle
                val capHeight = fm.ascent * CAP_HEIGHT_OF_ASCENT
                g2.drawString(TEXT, x + (iconWidth - fm.stringWidth(TEXT)) / 2f, y + (iconHeight + capHeight) / 2f)
            } finally {
                g2.dispose()
            }
        }

        private companion object {
            const val TEXT = "?"

            /** A bold "?" this size is about 8 px tall at 100 %, the height of the dots. */
            const val FONT_SIZE = 11f
            const val CAP_HEIGHT_OF_ASCENT = 0.72f

            /** For a look and feel without a label font, which the IDE always has. */
            val FALLBACK_FONT = Font(Font.SANS_SERIF, Font.PLAIN, 13)
        }
    }
}
