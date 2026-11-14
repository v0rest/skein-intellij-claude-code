package io.github.v0rest.skein.claude

import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.testutil.CanonicalTemporaryFolder
import io.github.v0rest.skein.testutil.Fixture
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files

class PhysicalPathTest {
    @get:Rule
    val tmp = CanonicalTemporaryFolder()

    @Test
    fun aFolderThatIsNotThereComesBackCanonical() {
        assertEquals(Fixture.root("w/none/a"), PhysicalPath.of(Fixture.root("w/none/a") + PathStyle.current.separator))
    }

    @Test
    fun aMissingFolderHasItsDotsFoldedUnlessToldNotTo() {
        val missing = Fixture.root("w/none/a/../b")
        assertEquals(Fixture.root("w/none/b"), PhysicalPath.of(missing))
        assertEquals(Fixture.root("w/none/b"), PhysicalPath.of(missing, foldMissing = true))
        assertEquals(missing, PhysicalPath.of(missing, foldMissing = false))
    }

    @Test
    fun aFolderThatExistsHasItsDotsResolvedByTheDiskWhateverFoldMissingSays() {
        val real = Files.createDirectories(tmp.root.toPath().resolve("a").resolve("b"))
        val dotted = "$real${PathStyle.current.separator}..${PathStyle.current.separator}b"
        assertEquals(real.toString(), PhysicalPath.of(dotted, foldMissing = false))
    }

    @Test
    fun aLinkIsResolvedForThisMachinesStyleOnly() {
        Fixture.assumeNotWindows("a symbolic link needs a privilege there")
        val real = Files.createDirectories(tmp.root.toPath().resolve("real"))
        val link = Files.createSymbolicLink(tmp.root.toPath().resolve("link"), real)
        assertEquals(real.toString(), PhysicalPath.of(link.toString(), PathStyle.UNIX))
        // another style is judged as text, the disk is not asked
        assertEquals(io.github.v0rest.skein.paths.PathRules.canonical(link.toString(), PathStyle.WINDOWS), PhysicalPath.of(link.toString(), PathStyle.WINDOWS))
    }

    @Test
    fun aWindowsPathIsJudgedAsTextOnAnyOs() {
        assertEquals("C:\\w\\none\\a", PhysicalPath.of("c:/w/none//a/", PathStyle.WINDOWS))
        assertEquals("C:\\w\\none\\a\\..\\b", PhysicalPath.of("C:\\w\\none\\a\\..\\b", PathStyle.WINDOWS, foldMissing = false))
    }

    @Test
    fun aRelativePathIsNeverAskedOfTheDisk() = assertEquals("a${PathStyle.current.separator}b", PhysicalPath.of("a//b"))
}
