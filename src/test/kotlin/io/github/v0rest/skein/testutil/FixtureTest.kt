package io.github.v0rest.skein.testutil

import com.google.gson.JsonParser
import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.paths.PathStyle.UNIX
import io.github.v0rest.skein.paths.PathStyle.WINDOWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path

/** The portable fixture helpers give each style the shape its own tools would, and real paths on this machine. */
class FixtureTest {
    @get:Rule
    val tmp = CanonicalTemporaryFolder()

    @Test
    fun rootsTakeTheStylesShape() {
        assertEquals("/w/acme", Fixture.root("w/acme", UNIX))
        assertEquals("""C:\w\acme""", Fixture.root("w/acme", WINDOWS))
        assertEquals("""C:\w\acme\.claude\worktrees\task-1""", Fixture.root("w/acme/.claude/worktrees/task-1", WINDOWS))
        assertEquals("/", Fixture.root("", UNIX))
        assertEquals("""C:\""", Fixture.root("", WINDOWS))
        assertEquals("""C:\w\acme""", Fixture.root("/w/acme/", WINDOWS))
    }

    /** Git and the IDE's virtual files write a Windows path with forward slashes, the same folder for the path rules. */
    @Test
    fun forwardRootsAreTheSameFolder() {
        assertEquals("C:/w/acme", Fixture.forwardRoot("w/acme", WINDOWS))
        assertEquals("/w/acme", Fixture.forwardRoot("w/acme", UNIX))
        assertEquals("C:/", Fixture.forwardRoot("", WINDOWS))
        for (style in PathStyle.entries) {
            assertTrue(PathRules.same(Fixture.root("w/acme", style), Fixture.forwardRoot("w/acme", style), style))
            assertEquals(Fixture.root("w/acme", style), PathRules.canonical(Fixture.forwardRoot("w/acme", style), style))
        }
    }

    /** This failed on the Windows runner - `/w/acme` is no absolute path there, so java.nio put the current drive in front. */
    @Test
    fun theRunningOsRootIsAbsoluteThere() {
        val acme = Fixture.path("w/acme")
        assertTrue(acme.toString(), acme.isAbsolute)
        assertEquals(Fixture.root("w/acme"), acme.toString())
        assertEquals(acme, acme.toAbsolutePath())
        assertEquals(acme.resolve("src"), Fixture.path("w/acme/src"))
    }

    /** A Windows cwd in a transcript line has its backslashes doubled, and the line still reads back as that cwd. */
    @Test
    fun jsonEscapedRoundTripsThroughGson() {
        val texts = listOf(
            """C:\Users\skein\demo-shop""", """\\server\share\x""", "/w/acme", """say "hi"""", "tab\there", "line\nbreak", "\u0001", "naïve ✓",
        )
        for (text in texts) {
            val line = """{"cwd":"${Fixture.jsonEscaped(text)}"}"""
            assertEquals(line, text, JsonParser.parseString(line).asJsonObject.get("cwd").asString)
        }
        assertEquals("""C:\\w\\acme""", Fixture.jsonEscaped("""C:\w\acme"""))
        assertEquals("/w/acme", Fixture.jsonEscaped("/w/acme"))
    }

    /** On the Mac the temp folder sits behind the /var link, on Windows often behind a short name such as SKEIN~1. */
    @Test
    fun theTemporaryFolderIsItsRealPath() {
        val root = tmp.root.toPath()
        assertEquals(root.toRealPath(), root)
        val acme = tmp.newFolder("acme").toPath()
        assertEquals(acme.toRealPath(), acme)
        assertEquals(root, acme.parent)
        val file = tmp.newFile("a.c").toPath()
        assertEquals(file.toRealPath(), file)
    }

    @Test
    fun aTempDirectoryIsItsRealPath() {
        val dir = Fixture.tempDirectory("skein-fixture")
        try {
            assertEquals(dir.toRealPath(), dir)
            assertTrue(Files.isDirectory(dir))
        } finally {
            Files.delete(dir)
        }
    }

    @Test
    fun aRealGitRunsInTheCanonicalFolder() {
        val git = RealGit(tmp)
        assumeTrue("git is not installed", git.available)
        val repo = git.repository()
        val top = git.run(repo, "rev-parse", "--show-toplevel").stdout.trim()
        // git prints forward slashes on Windows, so the comparison goes through the path rules
        assertTrue("$top vs $repo", PathRules.same(top, repo.toString()))
        assertEquals(Path.of(PathRules.canonical(top)), repo)
    }
}
