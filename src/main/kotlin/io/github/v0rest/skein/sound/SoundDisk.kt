package io.github.v0rest.skein.sound

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.BasicFileAttributes

/**
 * What the disk holds for Skein's sounds - the OS's sound folders and a chosen file. Reads only, never throws, and is
 * called off the EDT, since a share that hangs would otherwise freeze the IDE.
 */
object SoundDisk {
    /** Linux keeps a theme's sounds a few folders down (`alsa/x.wav`, `gnome/default/alerts/x.ogg`), never deeper. */
    private const val DEPTH = 4

    /** More sounds than any theme holds, so a folder full of them cannot hold up the list. */
    private const val MAX_FILES = 2_000

    /** The OS's own sounds as [SoundCatalog] offers them. */
    fun systemSounds(os: SoundOs = SoundOs.current): List<SystemSound> =
        SoundCatalog.sounds(os, SoundCatalog.folders(os, System.getProperty("user.home").orEmpty(), System.getenv("SystemRoot"))) { list(it, os) }

    /**
     * A regular file's size and when it last changed, null when there is none at [path] - a path the system cannot take
     * throws, and the decision beeps then.
     */
    fun facts(path: String): FileFacts? {
        val file = Path.of(path)
        return if (Files.isRegularFile(file)) FileFacts(Files.size(file), Files.getLastModifiedTime(file).toMillis()) else null
    }

    /** What [choice] does now - the OS's sounds are looked up again, so a sound removed since the page was open beeps. */
    fun plan(choice: SoundChoice): SoundPlan = SoundDecision.plan(choice, { SoundCatalog.find(systemSounds(), it) }, ::facts)

    /** Why [choice] would beep, with the OS's [sounds] already read, or null when it plays as chosen. */
    fun problem(choice: SoundChoice, sounds: List<SystemSound>): SoundProblem? =
        (SoundDecision.plan(choice, { SoundCatalog.find(sounds, it) }, ::facts) as? SoundPlan.Beep)?.problem

    /**
     * A folder's sound files, relative to it, through [SoundWalk]. Links are followed both ways the disk is asked, and
     * a folder's identity is its file key, or its real path where the system has no keys (Windows).
     */
    internal fun list(folder: SoundFolder, os: SoundOs = SoundOs.current, max: Int = MAX_FILES): List<String> {
        val root = try {
            Path.of(folder.path)
        } catch (e: Exception) {
            return emptyList()
        }
        fun at(names: List<String>): Path = names.fold(root) { path, name -> path.resolve(name) }
        return SoundWalk.files(
            depth = if (folder.deep) DEPTH else 1,
            max = max,
            accept = { SoundCatalog.isOffered(it, os) },
            children = { names -> entries(at(names)) },
            identity = { names -> identity(at(names)) },
        ).map { it.joinToString(os.style.separator.toString()) }
    }

    private fun entries(folder: Path): List<DiskEntry>? = try {
        Files.newDirectoryStream(folder).use { stream ->
            stream.map { entry ->
                val kind = when {
                    Files.isDirectory(entry) -> DiskEntry.Kind.FOLDER
                    Files.isRegularFile(entry) -> DiskEntry.Kind.FILE
                    else -> DiskEntry.Kind.OTHER
                }
                DiskEntry(entry.fileName.toString(), kind)
            }
        }
    } catch (e: Exception) {
        null
    }

    private fun identity(folder: Path): Any? = try {
        Files.readAttributes(folder, BasicFileAttributes::class.java).fileKey() ?: folder.toRealPath()
    } catch (e: Exception) {
        null
    }
}
