package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.WaitReason

/**
 * The shape of the orange glyph of a thread that waits for the user. All three are orange, since all three need the
 * user. The shape tells a permission prompt (a lock) from a question (a speech bubble) from any other dialog (the
 * plain dot). The bubble is not a question mark, which the list already uses for a thread of unknown state. Which one
 * a row gets is decided here, and nowhere else switches on the reason.
 */
enum class WaitGlyph {
    PERMISSION, QUESTION, DOT;

    companion object {
        fun of(reason: WaitReason): WaitGlyph = when (reason) {
            WaitReason.PERMISSION -> PERMISSION
            WaitReason.QUESTION -> QUESTION
            WaitReason.OTHER -> DOT
        }
    }
}
