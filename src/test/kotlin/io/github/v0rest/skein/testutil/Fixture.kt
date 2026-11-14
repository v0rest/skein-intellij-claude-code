package io.github.v0rest.skein.testutil

import com.intellij.openapi.util.SystemInfo
import io.github.v0rest.skein.paths.PathStyle
import org.junit.Assume.assumeFalse
import org.junit.Assume.assumeTrue
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Portable fixture paths. A test written with `/w/acme` breaks on Windows, where that is no absolute path and
 * java.nio puts the current drive in front, so roots come from here - `/w/acme` on Unix, `C:\w\acme` on Windows.
 * The style can be given, so a test on the Mac builds Windows inputs too. These are plain builders, they do not use
 * the path rules they help to test.
 */
object Fixture {
    private const val DRIVE = "C:"

    /** [relative] (written with `/`) under the style's root - `/w/acme` on Unix, `C:\w\acme` on Windows. */
    fun root(relative: String, style: PathStyle = PathStyle.current): String = when (style) {
        PathStyle.UNIX -> "/" + names(relative).joinToString("/")
        PathStyle.WINDOWS -> "$DRIVE\\" + names(relative).joinToString("\\")
    }

    /** The same folder with forward slashes, as git and the IDE's virtual files write it on Windows - `C:/w/acme`. */
    fun forwardRoot(relative: String, style: PathStyle = PathStyle.current): String = when (style) {
        PathStyle.UNIX -> root(relative, style)
        PathStyle.WINDOWS -> "$DRIVE/" + names(relative).joinToString("/")
    }

    /** [root] for the running OS as a java.nio path, which only exists in that OS's shape. */
    fun path(relative: String): Path = Path.of(root(relative))

    /**
     * [text] as it stands between the quotes of a JSON string, quotes not included, so it drops into a line like
     * `"cwd":"${Fixture.jsonEscaped(cwd)}"`. A Windows cwd needs it for its backslashes.
     */
    fun jsonEscaped(text: String): String = buildString(text.length) {
        for (c in text) {
            when {
                c == '\\' -> append("\\\\")
                c == '"' -> append("\\\"")
                c == '\n' -> append("\\n")
                c == '\r' -> append("\\r")
                c == '\t' -> append("\\t")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
        }
    }

    /**
     * A new temp folder by its real path, for tests that make their own with Files.createTempDirectory. The caller
     * removes it, as before.
     */
    fun tempDirectory(prefix: String): Path = Files.createTempDirectory(prefix).toRealPath()

    /** Skips a test that only means something on real Windows (its git, its file system). */
    fun assumeWindows(reason: String) = assumeTrue(reason, SystemInfo.isWindows)

    /** Skips a test Windows cannot run - a POSIX shell, a CR in a file name, a link without the privilege. */
    fun assumeNotWindows(reason: String) = assumeFalse(reason, SystemInfo.isWindows)

    private fun names(relative: String): List<String> = relative.split('/').filter(String::isNotEmpty)
}

/**
 * A drop-in for JUnit's TemporaryFolder whose root and every file and folder made in it come by their real path. The
 * system's temp folder is often reached through a link or a short name - `/var` is a link to `/private/var` on the
 * Mac, and on Windows it can be a short name such as `C:\Users\SKEIN~1\...` - while git, the IDE and toRealPath
 * report the long, resolved name, so a test comparing the two would fail for no fault of the product. RealGit takes
 * it as it is.
 */
class CanonicalTemporaryFolder : TemporaryFolder(Path.of(System.getProperty("java.io.tmpdir")).toRealPath().toFile())
