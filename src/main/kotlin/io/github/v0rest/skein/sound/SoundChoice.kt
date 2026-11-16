package io.github.v0rest.skein.sound

import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle

/** The sound an event plays, as the user chose it in Settings | Tools | Skein. */
sealed interface SoundChoice {
    /** The system beep - what Skein played before there was a choice, and still the default. */
    data object Beep : SoundChoice

    /**
     * One of the OS's own alert sounds, by the name the settings page shows. It is looked up on disk again every time it
     * plays, so a value that travelled to another machine or outlived an OS update finds the sound there or beeps.
     */
    data class System(val name: String) : SoundChoice

    /** A sound file of the user's own, by its absolute path. */
    data class File(val path: String) : SoundChoice
}

/**
 * How a [SoundChoice] is kept in skein.xml - `system:Glass`, `file:/path/to/chime.wav`, nothing at all for the beep. The
 * prefix names the kind, so a later Skein can add one, and an older one reads a value it does not know as the beep.
 */
object SoundChoices {
    private const val SYSTEM = "system:"
    private const val FILE = "file:"

    /** The choice a stored value names - empty, unknown or broken values are the beep and never an error. */
    fun parse(stored: String?, style: PathStyle = PathStyle.current): SoundChoice = when {
        stored == null -> SoundChoice.Beep
        stored.startsWith(SYSTEM) -> stored.removePrefix(SYSTEM).takeIf(::isName)?.let { SoundChoice.System(it) } ?: SoundChoice.Beep
        // the path is taken as written, it was written in the system's own form when the file was chosen
        stored.startsWith(FILE) -> stored.removePrefix(FILE).takeIf { PathRules.isAbsolute(it, style) }?.let { SoundChoice.File(it) } ?: SoundChoice.Beep
        else -> SoundChoice.Beep
    }

    /** The value to store, null for the beep, so a page left at the default writes nothing. */
    fun format(choice: SoundChoice): String? = when (choice) {
        SoundChoice.Beep -> null
        is SoundChoice.System -> SYSTEM + choice.name
        is SoundChoice.File -> FILE + choice.path
    }

    /**
     * A system sound's name comes from a file name in the OS's sound folder, so one with a separator, a control character
     * or nothing but dots was never written by Skein.
     */
    private fun isName(name: String): Boolean =
        name.isNotBlank() && name != "." && name != ".." && name.none { it == '/' || it == '\\' || it.isISOControl() }
}
