package io.github.v0rest.skein.sound

import io.github.v0rest.skein.paths.PathStyle
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/** The walk on a real disk with links, a folder that cannot be read and a file that is not one. Unix only - links need rights on Windows. */
class SoundDiskTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun sound(path: Path) {
        Files.createDirectories(path.parent)
        Files.write(path, byteArrayOf(0))
    }

    @Test
    fun theWalkFollowsLinksPassesOverWhatItCannotReadAndEndsALoop() {
        assumeTrue(PathStyle.current == PathStyle.UNIX && "posix" in FileSystems.getDefault().supportedFileAttributeViews())
        val root = tmp.newFolder("sounds").toPath()
        val elsewhere = tmp.newFolder("yaru").toPath()
        sound(root.resolve("alsa/Noise.wav"))
        sound(root.resolve("alsa/bell.oga"))
        sound(root.resolve("closed/hidden.wav"))
        sound(root.resolve("sound-icons/prompt.wav"))
        sound(elsewhere.resolve("drip.wav"))
        Files.createSymbolicLink(root.resolve("theme"), elsewhere)
        Files.createSymbolicLink(elsewhere.resolve("up"), root)
        Files.createSymbolicLink(root.resolve("dangling.wav"), root.resolve("gone.wav"))
        val closed = root.resolve("closed")
        Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("---------"))
        try {
            // a test run with root rights reads the folder anyway, and there is nothing to check then
            assumeTrue(!Files.isReadable(closed))
            assertEquals(
                listOf("alsa/Noise.wav", "sound-icons/prompt.wav", "theme/drip.wav"),
                SoundDisk.list(SoundFolder(root.toString(), deep = true), SoundOs.LINUX),
            )
            assertEquals(emptyList<String>(), SoundDisk.list(SoundFolder(root.resolve("missing").toString(), deep = true), SoundOs.LINUX))
            // the cap keeps the first sounds in name order
            assertEquals(listOf("alsa/Noise.wav"), SoundDisk.list(SoundFolder(root.toString(), deep = true), SoundOs.LINUX, max = 1))
        } finally {
            Files.setPosixFilePermissions(closed, PosixFilePermissions.fromString("rwx------"))
        }
    }
}
