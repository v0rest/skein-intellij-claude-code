package io.github.v0rest.skein.sound

import io.github.v0rest.skein.sound.DiskEntry.Kind.FILE
import io.github.v0rest.skein.sound.DiskEntry.Kind.FOLDER
import io.github.v0rest.skein.sound.DiskEntry.Kind.OTHER
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * A made-up `/usr/share/sounds`: folders by their names below the root, each with what it lists, null for one that
 * cannot be read, and which real folder a name stands for, so a link can lead elsewhere or back up.
 */
class SoundWalkTest {
    private class Tree(val folders: Map<String, List<DiskEntry>?>, val links: Map<String, String> = emptyMap()) {
        /** A path below the root as the walk sees it, with the links on the way resolved, as the disk would. */
        fun real(names: List<String>): String {
            var path = ""
            for (name in names) {
                path = if (path.isEmpty()) name else "$path/$name"
                path = links[path] ?: path
            }
            return path
        }

        fun walk(depth: Int = 4, max: Int = 100) = SoundWalk.files(
            depth, max, { SoundCatalog.isOffered(it, SoundOs.LINUX) },
            children = { folders[real(it)] },
            identity = { real(it) },
        ).map { it.joinToString("/") }
    }

    private fun file(name: String) = DiskEntry(name, FILE)
    private fun folder(name: String) = DiskEntry(name, FOLDER)

    @Test
    fun theWavFilesOfEverySubfolderAndNothingElse() {
        val tree = Tree(
            mapOf(
                "" to listOf(folder("freedesktop"), folder("alsa"), file("README"), folder("sound-icons")),
                "freedesktop" to listOf(folder("stereo")),
                "freedesktop/stereo" to listOf(file("bell.oga"), file("complete.oga")),
                "alsa" to listOf(file("Noise.wav"), file("Front_Center.wav"), DiskEntry("broken.wav", OTHER)),
                "sound-icons" to listOf(file("prompt.wav"), file(".hidden.wav")),
            ),
        )
        assertEquals(listOf("alsa/Front_Center.wav", "alsa/Noise.wav", "sound-icons/prompt.wav"), tree.walk())
    }

    /** One folder that cannot be read costs its own files, never its neighbours'. */
    @Test
    fun aFolderThatCannotBeReadIsPassedOver() {
        val tree = Tree(
            mapOf(
                "" to listOf(folder("a"), folder("b"), folder("c")),
                "a" to listOf(file("one.wav")),
                "b" to null,
                "c" to listOf(file("three.wav")),
            ),
        )
        assertEquals(listOf("a/one.wav", "c/three.wav"), tree.walk())
        // and a root that cannot be read lists nothing
        assertEquals(emptyList<String>(), Tree(mapOf("" to null)).walk())
    }

    @Test
    fun aLinkToAFolderIsFollowedAndALoopEnds() {
        val tree = Tree(
            mapOf(
                "" to listOf(folder("themes"), folder("yaru")),
                "themes" to listOf(folder("current"), folder("back")),
                "/opt/yaru-sounds" to listOf(file("drip.wav"), folder("up")),
            ),
            links = mapOf("yaru" to "/opt/yaru-sounds", "themes/current" to "/opt/yaru-sounds", "themes/back" to "", "/opt/yaru-sounds/up" to ""),
        )
        // the theme's folder lies outside, through a link, and is walked once though two links lead there
        assertEquals(listOf("themes/current/drip.wav"), tree.walk())
    }

    @Test
    fun filesDeeperThanTheDepthAreLeftOut() {
        val tree = Tree(
            mapOf(
                "" to listOf(file("top.wav"), folder("a")),
                "a" to listOf(folder("b")),
                "a/b" to listOf(folder("c"), file("three.wav")),
                "a/b/c" to listOf(file("four.wav"), folder("d")),
                "a/b/c/d" to listOf(file("five.wav")),
            ),
        )
        assertEquals(listOf("a/b/c/four.wav", "a/b/three.wav", "top.wav"), tree.walk(depth = 4))
        // macOS and Windows look right in the folder only
        assertEquals(listOf("top.wav"), tree.walk(depth = 1))
    }

    /** Only sounds count towards the cap, and the folders go in name order, so the same ones make it every time. */
    @Test
    fun theCapCountsSoundsOnlyAndCutsTheSameOnesEveryTime() {
        val tree = Tree(
            mapOf(
                "" to listOf(folder("zeta"), folder("Alpha"), folder("beta")),
                "Alpha" to listOf(file("a1.oga"), file("a2.oga"), file("a3.oga"), file("a.wav")),
                "beta" to listOf(file("b2.wav"), file("b1.wav")),
                "zeta" to listOf(file("z.wav")),
            ),
        )
        assertEquals(listOf("Alpha/a.wav", "beta/b1.wav"), tree.walk(max = 2))
        assertEquals(listOf("Alpha/a.wav", "beta/b1.wav", "beta/b2.wav", "zeta/z.wav"), tree.walk(max = 10))
    }
}
