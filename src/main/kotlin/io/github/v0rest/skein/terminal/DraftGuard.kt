package io.github.v0rest.skein.terminal

import java.awt.event.InputEvent
import java.awt.event.KeyEvent

/**
 * The draft guard: whether claude's input box in a thread's tab may hold something typed and not sent. Skein cannot see
 * the input box, and a `/rename` typed after a draft would go to the model together with it as one message, which starts
 * a turn (measured on the Windows VM, 2026-11-15). So a tab counts as clean only when nothing was typed into it since an
 * Enter that sent what was there. Every other key makes it dirty, and so does an IDE action that may change the input
 * ([touchesInput]), since a paste or the terminal's Escape is an action, not a key the terminal's key listener hears, and
 * an input method's text. When unsure, it is dirty.
 *
 * Not every Enter sends the input either: in a dialog it answers that, after a `\` it makes a new line, on a suggestion
 * it may only take the suggestion into the input, and on a command such as `/config` it may leave a menu open, which a
 * `/rename` and Enter would then pick from. So an Enter on a draft counts only once a turn is seen starting within
 * [TURN_WINDOW_MILLIS] after it ([proven], [settled]): claude went to work right then, so the input was sent. A turn
 * claude starts on its own later (a background agent that finished, a scheduled wakeup) proves nothing, and a turn seen
 * only when it already waits on a dialog proves nothing either, since the Enter may have been the answer to that dialog
 * ([io.github.v0rest.skein.threads.RenameGate.atWork]).
 *
 * A claude Skein has just started in a tab starts dirty ([LAUNCHED]): Claude Code may ask something at startup (an MCP
 * server to approve and the like) while its status already says idle, and an Enter there picks the default answer. So
 * the first rename of a fresh or resumed thread goes after the first turn the person starts.
 */
object DraftGuard {
    /** A key as the guard sees it ([classify]). */
    enum class Key {
        /** A plain Enter, pressed. */
        ENTER,

        /** A typed `\`: claude takes `\` and Enter as a new line, not as sending. */
        BACKSLASH,

        /** Anything else that reaches claude. */
        OTHER,

        /** Nothing that reaches claude's input: a modifier or lock key alone, a release, the typed half of an Enter. */
        NONE,
    }

    /**
     * What the guard knows of a tab's input: [clean] when nothing was typed since an Enter sent it, [afterBackslash] when
     * the last key was a `\`, [lastKeyAt] when the last key counted came, in the caller's steady clock, or null for none
     * yet. [sentAt] is when an Enter on the draft may have sent it, in wall-clock millis, waiting for a turn to show that
     * it did ([settled]); null when there is no such Enter since the last key.
     */
    data class Draft(val clean: Boolean = true, val afterBackslash: Boolean = false, val lastKeyAt: Long? = null, val sentAt: Long? = null)

    /** A tab where Skein has just started claude: dirty until the first turn the person starts ([proven]). */
    val LAUNCHED = Draft(clean = false)

    /**
     * How soon after an Enter a turn must be seen starting to show that Enter sent the draft: claude goes to work at
     * once, and Skein sees it within its poll and tick, about two seconds.
     */
    const val TURN_WINDOW_MILLIS = 5_000L

    /**
     * How long after the last key nothing is typed into claude: right after a key the person is at work in that tab, and
     * the status Skein reads trails by up to a second's poll.
     */
    const val SETTLE_MILLIS = 3_000L

    /** The modifier keys that, with Enter, make it a new line in claude's input rather than sending it. */
    private const val ENTER_MODIFIERS = InputEvent.SHIFT_DOWN_MASK or InputEvent.CTRL_DOWN_MASK or InputEvent.ALT_DOWN_MASK or
        InputEvent.META_DOWN_MASK or InputEvent.ALT_GRAPH_DOWN_MASK

    /** Keys that put nothing into the input when pressed alone. */
    private val NOTHING = setOf(
        KeyEvent.VK_SHIFT, KeyEvent.VK_CONTROL, KeyEvent.VK_ALT, KeyEvent.VK_META, KeyEvent.VK_ALT_GRAPH, KeyEvent.VK_WINDOWS,
        KeyEvent.VK_CAPS_LOCK, KeyEvent.VK_NUM_LOCK, KeyEvent.VK_SCROLL_LOCK,
    )

    /**
     * The terminal's own actions that only look at the tab or move it, never write into it: copy, find, scrolling, the tab's
     * name and place. Every other `Terminal.` action counts as a key.
     */
    private val LOOK_ONLY = setOf(
        "Terminal.CopySelectedText", "Terminal.CopyBlock", "Terminal.Find", "Terminal.LineUp", "Terminal.LineDown",
        "Terminal.PageUp", "Terminal.PageDown", "Terminal.RenameSession", "Terminal.RenameSessionInEditor",
        "Terminal.MoveToEditor", "Terminal.Settings", "Terminal.NewTab", "Terminal.ToggleEmulatorBadge",
        "Terminal.SwitchFocusToEditor", "Terminal.CloseTab", "Terminal.MoveToolWindowTabLeft", "Terminal.MoveToolWindowTabRight",
    )

    /**
     * One AWT key event the terminal reports, by its [id] (pressed, typed, released), [keyCode], [keyChar] and
     * [modifiersEx]. An Enter comes as a press and a typed `\n`, so only the press counts; with a modifier it makes a new
     * line instead of sending.
     */
    fun classify(id: Int, keyCode: Int, keyChar: Char, modifiersEx: Int): Key = when (id) {
        KeyEvent.KEY_PRESSED -> when {
            keyCode in NOTHING -> Key.NONE
            keyCode == KeyEvent.VK_ENTER -> if (modifiersEx and ENTER_MODIFIERS == 0) Key.ENTER else Key.OTHER
            else -> Key.OTHER
        }
        KeyEvent.KEY_TYPED -> when (keyChar) {
            '\n', '\r' -> Key.NONE
            '\\' -> Key.BACKSLASH
            else -> Key.OTHER
        }
        else -> Key.NONE
    }

    /**
     * The draft after [key] came [at] (steady clock) and [wallAt] (wall clock). An Enter on an empty input adds nothing to
     * it. On a draft it may send it only while claude is idle ([enterMaySend]) and not right after a `\`; then it waits
     * for a turn to show it did ([settled]), and in any other case it leaves the draft as it was: a dialog took it, or a
     * new line.
     */
    fun next(draft: Draft, key: Key, enterMaySend: Boolean, at: Long, wallAt: Long): Draft = when (key) {
        Key.NONE -> draft
        Key.OTHER -> Draft(clean = false, afterBackslash = false, lastKeyAt = at)
        Key.BACKSLASH -> Draft(clean = false, afterBackslash = true, lastKeyAt = at)
        Key.ENTER -> when {
            draft.clean -> draft.copy(afterBackslash = false, lastKeyAt = at)
            enterMaySend && !draft.afterBackslash -> draft.copy(afterBackslash = false, lastKeyAt = at, sentAt = wallAt)
            else -> draft.copy(afterBackslash = false, lastKeyAt = at)
        }
    }

    /**
     * Whether [key] typed into the tab, for the start in view ([io.github.v0rest.skein.threads.InViewStart]) - anything
     * that reaches the terminal, an Enter too, but not [Key.NONE], so a modifier pressed on the way to another window
     * leaves a tab free to start.
     */
    fun reachesInput(key: Key): Boolean = key != Key.NONE

    /** Whether a turn seen starting at [turnStartedAt] shows the Enter at [sentAt] sent the draft (wall clock both). */
    fun turnProves(sentAt: Long, turnStartedAt: Long): Boolean =
        turnStartedAt > sentAt && turnStartedAt - sentAt <= TURN_WINDOW_MILLIS

    /**
     * The Enter, by its [Draft.sentAt], that a turn has shown sent the draft, after Skein saw claude at work since
     * [atWorkSince] (null when it is not): the [draft]'s own Enter when that turn proves it ([turnProves]), else what was
     * [proven] before.
     */
    fun proven(draft: Draft?, atWorkSince: Long?, proven: Long?): Long? {
        val sentAt = draft?.sentAt ?: return proven
        return if (atWorkSince != null && turnProves(sentAt, atWorkSince)) sentAt else proven
    }

    /** The draft once its Enter was [proven] to have sent it: the input is clean. */
    fun settled(draft: Draft, proven: Long?): Draft =
        if (!draft.clean && draft.sentAt != null && draft.sentAt == proven) draft.copy(clean = true, sentAt = null) else draft

    /** Whether the input is clean ([settled] by [proven]) and no key came for [SETTLE_MILLIS] before [now] (steady clock). */
    fun allowsSend(draft: Draft, proven: Long?, now: Long): Boolean =
        settled(draft, proven).clean && draft.lastKeyAt.let { it == null || now - it >= SETTLE_MILLIS }

    /** [allowsSend] for a tab's [draft], where a tab that is not watched (null) is never clean, since nobody can tell. */
    fun inputClean(draft: Draft?, proven: Long?, now: Long): Boolean = draft != null && allowsSend(draft, proven, now)

    /**
     * Whether an IDE action run in a thread's terminal may change claude's input, by its id: any paste, and the terminal's
     * own actions but the look-only ones; an action without an id too, since nobody can tell. The IDE's other actions
     * (Recent Files, a tool window) do not touch the terminal's input.
     */
    fun touchesInput(actionId: String?): Boolean =
        actionId == null || actionId.contains("Paste", ignoreCase = true) || (actionId.startsWith("Terminal.") && actionId !in LOOK_ONLY)
}
