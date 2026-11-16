package io.github.v0rest.skein.sound

import io.github.v0rest.skein.paths.PathRules

/** Why a chosen sound plays as the beep instead, and the settings page marks the choice with it too. */
enum class SoundProblem {
    /** The file is gone, or this system has no sound by that name. */
    MISSING,

    /** Not a kind of file Skein plays, by its extension. */
    UNSUPPORTED,

    /** Bigger than [SoundDecision.MAX_BYTES]. */
    TOO_LARGE,

    /** The disk would not say. */
    UNREADABLE,
}

/** What an event does, play a file or beep - for the beep chosen [problem] is null. */
sealed interface SoundPlan {
    /** [facts] tell a file overwritten under the same name from the one played before. */
    data class PlayFile(val path: String, val facts: FileFacts) : SoundPlan

    data class Beep(val problem: SoundProblem?) : SoundPlan
}

/**
 * What the disk says of a regular file, its size and when it last changed in milliseconds - the caller gives null for
 * anything else or nothing at all.
 */
data class FileFacts(val size: Long, val modified: Long)

/** Play this file or beep, decided before the system is asked, so the player and the settings page agree. Never throws. */
object SoundDecision {
    /**
     * A clip holds the whole sound in memory, and an alert sound is a second or two, so a bigger file is most likely a song
     * picked by mistake.
     */
    const val MAX_BYTES = 5L * 1024 * 1024

    /**
     * [systemSound] gives the file of the OS's sound by name or null, [facts] what the disk says of a file, and [os] whose
     * player takes which files.
     */
    fun plan(choice: SoundChoice, systemSound: (String) -> String?, facts: (String) -> FileFacts?, os: SoundOs = SoundOs.current): SoundPlan {
        val path = when (choice) {
            SoundChoice.Beep -> return SoundPlan.Beep(null)
            is SoundChoice.File -> choice.path
            is SoundChoice.System -> try {
                systemSound(choice.name) ?: return SoundPlan.Beep(SoundProblem.MISSING)
            } catch (e: Exception) {
                return SoundPlan.Beep(SoundProblem.UNREADABLE)
            }
        }
        // the extension first, a song.mp3 needs no look at the disk to know it will beep
        if (!SoundCatalog.isSupported(PathRules.name(path, os.style), os)) return SoundPlan.Beep(SoundProblem.UNSUPPORTED)
        val file = try {
            facts(path) ?: return SoundPlan.Beep(SoundProblem.MISSING)
        } catch (e: Exception) {
            return SoundPlan.Beep(SoundProblem.UNREADABLE)
        }
        return if (file.size > MAX_BYTES) SoundPlan.Beep(SoundProblem.TOO_LARGE) else SoundPlan.PlayFile(path, file)
    }
}

/**
 * One sound per event at a time, but only within a burst. A sound of the same event that comes within [BURST_MILLIS] of
 * the last one starting stays silent, so three threads finishing at once sound once, and after that a new one stops the old
 * clip, however long it is, and plays. The play button in Settings never waits, so trying one choice after another is
 * not held up.
 */
object SoundGate {
    const val BURST_MILLIS = 1_500L

    /**
     * [lastStart] - when the event's last sound started, null when none did yet, taken right after it started, on a
     * clock that never steps back ([now] on the same one).
     */
    fun skip(lastStart: Long?, now: Long, preview: Boolean): Boolean =
        !preview && lastStart != null && now >= lastStart && now - lastStart < BURST_MILLIS
}
