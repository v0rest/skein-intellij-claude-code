package io.github.v0rest.skein.claude

import io.github.v0rest.skein.claude.FolderTrust.NOT_TRUSTED
import io.github.v0rest.skein.claude.FolderTrust.TRUSTED
import io.github.v0rest.skein.claude.FolderTrust.UNKNOWN
import io.github.v0rest.skein.claude.FolderTrustReader.Accepted
import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.paths.PathStyle.UNIX
import io.github.v0rest.skein.paths.PathStyle.WINDOWS
import io.github.v0rest.skein.testutil.CanonicalTemporaryFolder
import io.github.v0rest.skein.testutil.Fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Claude Code's answer to its folder-trust question, read from a `.claude.json` built by hand: `projects` keyed by
 * folder, each with `hasTrustDialogAccepted`. The real file holds account details too, so these fixtures carry a few
 * made-up neighbours of that kind to show they are skipped.
 */
class FolderTrustTest {
    @get:Rule
    val tmp = CanonicalTemporaryFolder()

    /** A `.claude.json` with these projects (folder to its entry's JSON), each key JSON-escaped. */
    private fun config(vararg projects: Pair<String, String>): String =
        projects.joinToString(",", prefix = """{"numStartups":3,"projects":{""", postfix = """},"userID":"0000"}""") { (folder, entry) ->
            "\"${Fixture.jsonEscaped(folder)}\":$entry"
        }

    private val yes = """{"allowedTools":[],"hasTrustDialogAccepted":true}"""
    private val no = """{"allowedTools":[],"hasTrustDialogAccepted":false}"""
    private val unanswered = """{"allowedTools":[]}"""

    private fun trust(text: String?, folder: String, style: PathStyle = UNIX) = FolderTrustReader.of(text, listOf(folder), style)

    @Test
    fun aFolderWhoseQuestionWasAnsweredYesIsTrusted() {
        assertEquals(TRUSTED, trust(config("/w/acme" to yes), "/w/acme"))
    }

    @Test
    fun aFolderAnsweredNoUnansweredOrMissingIsNotTrusted() {
        assertEquals(NOT_TRUSTED, trust(config("/w/acme" to no), "/w/acme"))
        assertEquals(NOT_TRUSTED, trust(config("/w/acme" to unanswered), "/w/acme"))
        assertEquals(NOT_TRUSTED, trust(config("/w/beta" to yes), "/w/acme"))
        assertEquals(NOT_TRUSTED, trust(config(), "/w/acme"))
    }

    /** Public reports say a folder below a trusted one is trusted too; whole names count, and trust never flows upwards. */
    @Test
    fun aTrustedFolderAboveCountsButANeighbourOrAFolderBelowDoesNot() {
        assertEquals(TRUSTED, trust(config("/w" to yes), "/w/acme/src"))
        assertEquals(TRUSTED, trust(config("/w/acme" to no, "/w" to yes), "/w/acme"))
        assertEquals(NOT_TRUSTED, trust(config("/w/acme" to yes), "/w/acme2"))
        assertEquals(NOT_TRUSTED, trust(config("/w/acme/src" to yes), "/w/acme"))
    }

    /** Claude Code writes Windows keys with forward slashes (`C:/Users/skein/demo-shop`); either separator and any case match there. */
    @Test
    fun windowsKeysMatchInEitherSeparatorAndAnyCase() {
        val file = config("C:/Users/skein/demo-shop" to yes)
        assertEquals(TRUSTED, trust(file, "C:\\Users\\skein\\demo-shop", WINDOWS))
        assertEquals(TRUSTED, trust(file, "c:\\USERS\\Skein\\Demo-Shop", WINDOWS))
        assertEquals(TRUSTED, trust(file, "C:/Users/skein/demo-shop/src", WINDOWS))
        assertEquals(NOT_TRUSTED, trust(file, "C:\\Users\\skein\\demo-shop2", WINDOWS))
        assertEquals(NOT_TRUSTED, trust(file, "D:\\Users\\skein\\demo-shop", WINDOWS))
        assertEquals(TRUSTED, trust(config("C:\\Users\\skein\\demo-shop" to yes), "C:/Users/skein/demo-shop", WINDOWS))
    }

    /** Unix compares exactly, and a Windows-shaped key is no absolute folder there, so a yes on it is a doubt, not a no. */
    @Test
    fun unixKeysCompareExactly() {
        assertEquals(NOT_TRUSTED, trust(config("/w/Acme" to yes), "/w/acme"))
        assertEquals(UNKNOWN, trust(config("C:/w/acme" to yes), "/w/acme"))
    }

    @Test
    fun aKeyWithATrailingSeparatorIsTheSameFolder() {
        assertEquals(TRUSTED, trust(config("/w/acme/" to yes), "/w/acme"))
        assertEquals(TRUSTED, trust(config("C:/w/acme/" to yes), "C:\\w\\acme", WINDOWS))
        assertEquals(TRUSTED, trust(config("C:\\w\\acme\\" to yes), "C:\\w\\acme\\", WINDOWS))
    }

    /**
     * Public reports say Claude Code never stores the home folder itself as trusted, so it asks there every time: the
     * home folder without a yes is not trusted, and neither is a new folder below it. A yes on the home folder, as an
     * older file might hold, still counts for the folders below it - that side of the doubt keeps Skein as it was.
     */
    @Test
    fun theHomeFolderIsAFolderLikeAnyOther() {
        val home = "/home/demo"
        assertEquals(NOT_TRUSTED, trust(config("$home/acme" to yes), home))
        assertEquals(NOT_TRUSTED, trust(config(home to no, "$home/acme" to yes), "$home/beta"))
        assertEquals(TRUSTED, trust(config(home to yes), "$home/beta"))
    }

    /** A yes whose folder only the disk could place (a relative key, a `..`, a Windows name ending in a dot) is a doubt. */
    @Test
    fun aYesOnAFolderTheTextCannotPlaceIsUnknown() {
        assertEquals(UNKNOWN, trust(config("w/acme" to yes), "/w/acme"))
        assertEquals(UNKNOWN, trust(config("/w/beta/../acme" to yes), "/w/acme"))
        assertEquals(UNKNOWN, trust(config("C:/w/acme." to yes), "C:\\w\\acme", WINDOWS))
        // a no there changes nothing, and a sure yes wins over any doubt
        assertEquals(NOT_TRUSTED, trust(config("w/acme" to no, "/w/beta/../acme" to unanswered), "/w/acme"))
        assertEquals(TRUSTED, trust(config("w/acme" to yes, "/w" to yes), "/w/acme"))
    }

    /** A value Skein cannot read for the folder or one above it is a doubt; an odd entry elsewhere is none of its business. */
    @Test
    fun anOddEntryForTheFolderIsUnknown() {
        assertEquals(UNKNOWN, trust(config("/w/acme" to """{"hasTrustDialogAccepted":"yes"}"""), "/w/acme"))
        assertEquals(UNKNOWN, trust(config("/w/acme" to """{"hasTrustDialogAccepted":null}"""), "/w/acme"))
        assertEquals(UNKNOWN, trust(config("/w" to "true"), "/w/acme"))
        assertEquals(NOT_TRUSTED, trust(config("/w/beta" to "true", "/w/gamma" to """{"hasTrustDialogAccepted":1}"""), "/w/acme"))
    }

    @Test
    fun noFileABrokenOneOrAnUnexpectedShapeIsUnknown() {
        for (text in listOf(
            null, "", "   ", "{", "[]", "\"projects\"", "null",
            """{"userID":"0000"}""", // no projects at all
            """{"projects":[]}""", """{"projects":null}""", """{"projects":"/w/acme"}""",
            """{"projects":{"/w/acme":{"hasTrustDialogAccepted":true}""", // cut inside projects
            """{"projects":{"/w/acme":{"hasTrustDialogAccepted":tru}}}""",
            """{"projects":{"/w/acme":{"hasTrustDialogAccepted":true}}} {}""", // more after the object
            """{"projects":{"/w/acme":{"hasTrustDialogAccepted":true,}}}""", // not strict JSON
            """{/* a comment */"projects":{"/w/acme":{"hasTrustDialogAccepted":true}}}""",
            """{'projects':{'/w/acme':{'hasTrustDialogAccepted':true}}}""",
        )) {
            assertEquals("text $text", UNKNOWN, trust(text, "/w/acme"))
        }
    }

    @Test
    fun aRelativeOrBlankFolderIsUnknown() {
        assertEquals(UNKNOWN, trust(config("/w/acme" to yes), "w/acme"))
        assertEquals(UNKNOWN, trust(config("/w/acme" to no), ""))
    }

    /** The folder as the IDE names it and as the disk resolves it: a yes on either one counts, a doubt on either one stays. */
    @Test
    fun anyNameOfTheFolderCounts() {
        val names = listOf("/w/link/acme", "/w/real/acme")
        assertEquals(TRUSTED, FolderTrustReader.of(config("/w/real/acme" to yes), names, UNIX))
        assertEquals(TRUSTED, FolderTrustReader.of(config("/w/link/acme" to yes), names, UNIX))
        assertEquals(NOT_TRUSTED, FolderTrustReader.of(config("/w/real/beta" to yes), names, UNIX))
        assertEquals(UNKNOWN, FolderTrustReader.of(config("/w/real/acme" to no, "../acme" to yes), names, UNIX))
        assertEquals(UNKNOWN, FolderTrustReader.of(config("/w/real/acme" to yes), emptyList(), UNIX))
    }

    /** Only each folder's answer comes out of the file; every other value is skipped unread, the last of two equal keys wins. */
    @Test
    fun onlyEachFoldersAnswerIsKept() {
        val text = """
            {"oauthAccount":{"emailAddress":"someone@example.invalid","organizationName":"Acme"},
             "projects":{
               "/w/acme":{"allowedTools":["Bash(git status)"],"history":[{"display":"hi","pastedContents":{}}],
                          "mcpServers":{"x":{"command":"npx","args":["-y"]}},"hasTrustDialogAccepted":true,"lastCost":0.5},
               "/w/beta":{"hasTrustDialogAccepted":false,"hasTrustDialogAccepted":true},
               "/w/gamma":{"projectOnboardingSeenCount":2},
               "/w/delta":[1,2],
               "/w/acme":{"hasTrustDialogAccepted":false}
             },
             "projects2":{"/w/zeta":{"hasTrustDialogAccepted":true}},
             "cachedStatsigGates":{"a":true}}
        """.trimIndent()
        val parsed = FolderTrustReader.parse(StringReader(text))
        assertEquals(
            mapOf("/w/acme" to Accepted.NO, "/w/beta" to Accepted.YES, "/w/gamma" to Accepted.NO, "/w/delta" to Accepted.ODD),
            parsed,
        )
    }

    @Test
    fun aMissingProjectsObjectParsesToNothing() {
        assertNull(FolderTrustReader.parse(StringReader("""{"userID":"0000"}""")))
        assertEquals(emptyMap<String, Accepted>(), FolderTrustReader.parse(StringReader("""{"projects":{}}""")))
    }

    // the file itself, through the Claude home

    private fun homeWith(text: String?): Pair<ClaudeHome, Path> {
        val config = tmp.root.toPath().resolve("config.json")
        if (text != null) Files.writeString(config, text)
        return ClaudeHome(tmp.root.toPath().resolve("home"), configFile = config) to config
    }

    @Test
    fun theFileIsReadThroughTheClaudeHome() {
        val folder = tmp.newFolder("acme").toPath().toString()
        val (home, _) = homeWith(config(folder to yes))
        assertEquals(TRUSTED, FolderTrustReader.read(home, folder))
        assertEquals(NOT_TRUSTED, FolderTrustReader.read(home, tmp.newFolder("beta").toPath().toString()))
    }

    @Test
    fun aMissingFileOrAHomeWithoutOneIsUnknown() {
        val folder = tmp.newFolder("acme").toPath().toString()
        assertEquals(UNKNOWN, FolderTrustReader.read(homeWith(null).first, folder))
        assertEquals(UNKNOWN, FolderTrustReader.read(ClaudeHome(tmp.root.toPath().resolve("home")), folder))
    }

    @Test
    fun aFileLargerThanTheLimitOrNotUtf8IsUnknown() {
        val folder = tmp.newFolder("acme").toPath().toString()
        val text = config(folder to yes)
        val (home, file) = homeWith(text)
        assertEquals(UNKNOWN, FolderTrustReader.read(home, folder, maxBytes = text.length - 1L))
        assertEquals(TRUSTED, FolderTrustReader.read(home, folder, maxBytes = text.length.toLong()))
        Files.write(file, byteArrayOf('{'.code.toByte(), 0xC3.toByte(), 0x28, '}'.code.toByte()))
        assertEquals(UNKNOWN, FolderTrustReader.read(home, folder))
    }

    /** A folder reached through a link counts by its real path too, the one claude reports as its cwd. */
    @Test
    fun aFolderThroughALinkCountsByItsRealPath() {
        Fixture.assumeNotWindows("a symbolic link needs a privilege on Windows")
        val real = tmp.newFolder("real-acme").toPath()
        val link = Files.createSymbolicLink(tmp.root.toPath().resolve("acme-link"), real)
        val (home, _) = homeWith(config(real.toString() to yes))
        assertEquals(TRUSTED, FolderTrustReader.read(home, link.toString()))
    }

    @Test
    fun readingLeavesTheFileAsItWas() {
        val folder = tmp.newFolder("acme").toPath().toString()
        val (home, file) = homeWith(config(folder to yes))
        val before = Files.readAllBytes(file)
        val modified = Files.getLastModifiedTime(file)
        FolderTrustReader.read(home, folder)
        assertEquals(before.toList(), Files.readAllBytes(file).toList())
        assertEquals(modified, Files.getLastModifiedTime(file))
    }
}
