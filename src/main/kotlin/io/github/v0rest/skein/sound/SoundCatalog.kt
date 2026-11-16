package io.github.v0rest.skein.sound

import com.intellij.openapi.util.SystemInfo
import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle

/** The system whose sound folders Skein looks in. Product code passes [current], tests pass all three. */
enum class SoundOs {
    MAC, WINDOWS, LINUX;

    val style: PathStyle get() = if (this == WINDOWS) PathStyle.WINDOWS else PathStyle.UNIX

    companion object {
        /** Any Unix that is not macOS looks where Linux does - a folder that is not there offers nothing. */
        val current: SoundOs = when {
            SystemInfo.isMac -> MAC
            SystemInfo.isWindows -> WINDOWS
            else -> LINUX
        }
    }
}

/** A folder the OS keeps its alert sounds in, [deep] when they sit in its subfolders and not right in it. */
data class SoundFolder(val path: String, val deep: Boolean)

/** One of the OS's own sounds as the settings page offers it - [name] is its file name without the extension. */
data class SystemSound(val name: String, val path: String)

/**
 * Which of the OS's own sounds Skein offers, from what the disk lists in the OS's sound folders. Pure - the listing comes
 * in from outside, so the rules of all three systems are tested on any of them.
 */
object SoundCatalog {
    /**
     * The files each system's player takes, by extension in any case. The macOS alert sound service knows linear PCM or
     * IMA4 in `.caf`, `.aif` and `.wav`, and may play a μ-law `.au` silently, so that one is left out there. Java Sound on
     * Windows and Linux plays `.wav`, `.aiff` and `.au` out of the box but no `.caf`. A `.wav` someone put into
     * `~/Library/Sounds` shows too, while freedesktop's `.oga`, `.ogg` and `.mp3` never do, they could only ever beep. A
     * file the system then refuses beeps as well.
     */
    fun extensions(os: SoundOs): List<String> = if (os == SoundOs.MAC) MAC_EXTENSIONS else JAVA_SOUND_EXTENSIONS

    private val MAC_EXTENSIONS = listOf("aiff", "aif", "wav", "caf")
    private val JAVA_SOUND_EXTENSIONS = listOf("wav", "aiff", "aif", "au")

    private const val MAC_SYSTEM = "/System/Library/Sounds"
    private const val LINUX_SOUNDS = "/usr/share/sounds"
    private const val WINDOWS_ROOT = "C:\\Windows"

    /**
     * Where to look, the folder that wins a name both have first. On macOS that is the system's folder, so `system:Glass`
     * always means the OS's own Glass. On Windows the folder follows `SystemRoot` ([windowsRoot]), which is `C:\Windows`
     * on nearly every machine. Linux keeps its sounds in subfolders per theme or package, so that one is [SoundFolder.deep].
     */
    fun folders(os: SoundOs, home: String, windowsRoot: String?): List<SoundFolder> = when (os) {
        SoundOs.MAC -> listOfNotNull(
            SoundFolder(MAC_SYSTEM, deep = false),
            home.takeIf { PathRules.isAbsolute(it, PathStyle.UNIX) }?.let { SoundFolder(PathRules.join(it, "Library", "Sounds", style = PathStyle.UNIX), deep = false) },
        )
        SoundOs.WINDOWS -> {
            val root = windowsRoot?.takeIf { PathRules.isAbsolute(it, PathStyle.WINDOWS) } ?: WINDOWS_ROOT
            listOf(SoundFolder(PathRules.join(root, "Media", style = PathStyle.WINDOWS), deep = false))
        }
        SoundOs.LINUX -> listOf(SoundFolder(LINUX_SOUNDS, deep = true))
    }

    /**
     * The sounds to offer, sorted by name, each name once. [listing] gives a folder's files as paths relative to it, and
     * an empty list for a folder that is not there. Names that differ in case only are one sound to the eye, so the first
     * one wins - by [folders] order, then by path, so the answer does not change from one look to the next.
     */
    fun sounds(os: SoundOs, folders: List<SoundFolder>, listing: (SoundFolder) -> List<String>): List<SystemSound> {
        val style = os.style
        val byName = LinkedHashMap<String, SystemSound>()
        for (folder in folders) {
            for (relative in listing(folder).sortedWith(String.CASE_INSENSITIVE_ORDER.thenBy { it })) {
                val file = PathRules.name(relative, style)
                if (!isOffered(file, os)) continue
                val name = nameOf(file)
                byName.putIfAbsent(name.lowercase(), SystemSound(name, PathRules.join(folder.path, relative, style = style)))
            }
        }
        return byName.values.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, SystemSound::name).thenBy { it.name })
    }

    /** The file of the sound named exactly [name], or null when this system has none by that name. */
    fun find(sounds: List<SystemSound>, name: String): String? = sounds.firstOrNull { it.name == name }?.path

    /** Whether a file of this name is offered - one Java Sound plays and not hidden, Finder's `._x.aiff` leftovers are no sounds. */
    fun isOffered(fileName: String, os: SoundOs): Boolean = !fileName.startsWith('.') && isSupported(fileName, os)

    /** Whether a file of this name is one the player of [os] takes ([extensions]). */
    fun isSupported(fileName: String, os: SoundOs): Boolean {
        val dot = fileName.lastIndexOf('.')
        return dot > 0 && fileName.substring(dot + 1).lowercase() in extensions(os)
    }

    /** The name a sound file shows by, its file name without the extension. */
    fun nameOf(fileName: String): String = fileName.substringBeforeLast('.')
}
