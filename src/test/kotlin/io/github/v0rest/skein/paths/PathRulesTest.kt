package io.github.v0rest.skein.paths

import com.intellij.openapi.util.SystemInfo
import io.github.v0rest.skein.paths.Containment.INSIDE
import io.github.v0rest.skein.paths.Containment.OUTSIDE
import io.github.v0rest.skein.paths.Containment.UNSURE
import io.github.v0rest.skein.paths.PathStyle.UNIX
import io.github.v0rest.skein.paths.PathStyle.WINDOWS
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The path rules with both styles on any OS, so a run on the Mac checks the Windows rules too. Windows paths come in
 * every shape Skein meets there - backslashes from the status file and java.nio, forward slashes from git and the
 * IDE's virtual files, either case of the drive letter. Rows with letters outside ASCII use ordinary strings, since a
 * `\u` escape means nothing inside a raw one.
 */
class PathRulesTest {
    /** Each row is (input, canonical text). */
    private fun assertCanonical(style: PathStyle, vararg rows: Pair<String, String>) {
        val wrong = rows.filter { (input, expected) -> PathRules.canonical(input, style) != expected }
            .map { (input, expected) -> "$input -> ${PathRules.canonical(input, style)}, expected $expected" }
        assertEquals(emptyList<String>(), wrong)
    }

    private fun row(child: String, parent: String, expected: Containment) = Triple(child, parent, expected)

    /** Each row is (child, parent, the exact answer) - a row that only checked `inside` would let UNSURE turn into OUTSIDE. */
    private fun assertContainment(style: PathStyle, vararg rows: Triple<String, String, Containment>) {
        val wrong = rows.filter { (child, parent, expected) -> PathRules.containment(child, parent, style) != expected }
            .map { (child, parent, expected) -> "$child in $parent -> ${PathRules.containment(child, parent, style)}, expected $expected" }
        assertEquals(emptyList<String>(), wrong)
    }

    @Test
    fun windowsCanonicalTextIsNativeWithAnUpperCaseDriveAndNoTrailingSeparator() = assertCanonical(
        WINDOWS,
        """C:\w\acme""" to """C:\w\acme""",
        """c:/w/acme/""" to """C:\w\acme""",
        """C:/Users/x/acme/.claude/worktrees/task-1""" to """C:\Users\x\acme\.claude\worktrees\task-1""",
        """C:\w\\acme\\\""" to """C:\w\acme""",
        """C:\w/acme\""" to """C:\w\acme""",
        """C:\w\.\acme""" to """C:\w\acme""",
        // the case of everything but the drive letter is the input's
        """c:\Users\Skein\Demo-Shop""" to """C:\Users\Skein\Demo-Shop""",
        // a root keeps its separator, like / on Unix
        """C:\""" to """C:\""",
        """c:/""" to """C:\""",
        """C:\\\""" to """C:\""",
        // drive-relative and rooted-without-drive forms stay what they are
        """C:""" to """C:""",
        """c:foo\bar""" to """C:foo\bar""",
        """\w\acme""" to """\w\acme""",
        """w/acme/""" to """w\acme""",
        "" to "",
        // .. is kept as written - see aDotDotMakesItUnsureUnlessBothShareIt
        """C:\w\acme\..\beta""" to """C:\w\acme\..\beta""",
        // a relative path keeps the .\ that stops its first name from reading as a drive
        """.\C:\x""" to """.\C:\x""",
        """./c:x/""" to """.\c:x""",
        """.\w\x""" to """w\x""",
    )

    @Test
    fun windowsLongPathAndUncForms() = assertCanonical(
        WINDOWS,
        """\\?\C:\w\acme""" to """C:\w\acme""",
        """\\?\c:\w\acme\""" to """C:\w\acme""",
        """\\?\UNC\server\share\acme""" to """\\server\share\acme""",
        """\\?\unc\server\share\acme""" to """\\server\share\acme""",
        """\\server\share\acme\""" to """\\server\share\acme""",
        // collapsing separators never eats the two that start a UNC path, in either separator
        """//server/share/acme""" to """\\server\share\acme""",
        """\\server\\share\\\acme""" to """\\server\share\acme""",
        """\\server\share""" to """\\server\share\""",
        """\\server\share\""" to """\\server\share\""",
        """\\server\share\.\x""" to """\\server\share\x""",
        // WSL stays a UNC path, so EnvironmentGate still sees it for what it is
        """\\wsl$\Ubuntu\home\u\acme""" to """\\wsl$\Ubuntu\home\u\acme""",
        """//wsl.localhost/Ubuntu/home/u/acme/""" to """\\wsl.localhost\Ubuntu\home\u\acme""",
        // the \\?\ stays where Windows would read the rest differently without it - it keeps a trailing dot or space
        """\\?\c:\w\acme.""" to """\\?\C:\w\acme.""",
        """\\?\C:\w\acme \x\""" to """\\?\C:\w\acme \x""",
        """\\?\UNC\server\share\x.""" to """\\?\UNC\server\share\x.""",
        // dropped once, it would show a second prefix, so the first one stays
        """\\?\UNC\?\C:\x""" to """\\?\UNC\?\C:\x""",
        """\\?\UNC\\?\C:\x""" to """\\?\UNC\?\C:\x""",
        """\\?\\C:\x""" to """\\?\\C:\x""",
        // and a UNC server named ? keeps the separator that stops it from reading as one
        """\\\?\C:\x""" to """\\\?\C:\x""",
        // other device paths are kept as written - a volume, the volume device behind C:, a pipe
        """$VOLUME\x\""" to """$VOLUME\x""",
        """\\?\C:""" to """\\?\C:""",
        """\\.\pipe\x""" to """\\.\pipe\x""",
    )

    /**
     * Only `\\?\` written with backslashes keeps Win32 from trimming. On the VM `//?/C:/Windows.` and `\\?/C:/Windows.`
     * opened C:\Windows and `\\?\C:\Windows.` opened nothing, so the other spellings read like any path - the drive or
     * share behind them, or `\\.\` where there is none, never the literal prefix.
     */
    @Test
    fun onlyTheBackslashPrefixIsLiteral() {
        assertCanonical(
            WINDOWS,
            "//?/C:/w/acme." to """C:\w\acme.""",
            """\\?/C:\w\acme.""" to """C:\w\acme.""",
            "//?/c:/w/acme" to """C:\w\acme""",
            """/\?\C:\w\acme """ to """C:\w\acme """,
            "//?/UNC/server/share/x." to """\\server\share\x.""",
            "//?/Volume{0}/x." to """\\.\Volume{0}\x.""",
            "//?/C:" to """\\.\C:""",
            // a share named ? would collapse into the literal prefix, so it keeps the device form
            "//?/UNC/?/C:/x" to """\\.\UNC\?\C:\x""",
        )
        val different = listOf(
            "//?/C:/w/acme." to """\\?\C:\w\acme.""",
            "//?/C:/w/acme " to """\\?\C:\w\acme """,
            """\\?/C:\w\acme.""" to """\\?\C:\w\acme.""",
            "//?/C:/w/acme./x" to """\\?\C:\w\acme.\x""",
            "//?/UNC/server/share/x." to """\\?\UNC\server\share\x.""",
            "//?/Volume{0}/x." to """\\?\Volume{0}\x.""",
        )
        assertEquals(emptyList<Pair<String, String>>(), different.filter { (a, b) -> PathRules.same(a, b, WINDOWS) || PathRules.same(b, a, WINDOWS) })
        // with no name Win32 trims, both spellings open one folder
        assertTrue(PathRules.same("//?/C:/w/acme", """\\?\C:\w\acme""", WINDOWS))
        assertTrue(PathRules.same("//?/C:/w/acme.", """C:\w\acme.""", WINDOWS))
        assertContainment(
            WINDOWS,
            row("//?/C:/w/acme/x", """C:\w\acme""", INSIDE),
            row("//?/C:/w/acme./x", """C:\w\acme""", UNSURE),
            row("//?/Volume{0}/x", """\\?\Volume{0}""", UNSURE),
        )
        assertTrue(PathRules.isAbsolute("//?/C:/x", WINDOWS))
        assertFalse(PathRules.isAbsolute("//?/C:", WINDOWS))
        // nor does join put a literal prefix back for a base that never had one
        assertEquals("""C:\w\acme.\""", PathRules.join("//?/C:/w", "acme.", style = WINDOWS))
    }

    /**
     * Win32 trims trailing dots and spaces off a path's last name, but a name with a separator after it reads as in the
     * middle of a path (VM - `C:\Windows ` opens C:\Windows, `C:\Windows \System32` opens nothing). So `C:\w\acme \` is
     * the folder `acme ` and `C:\w\acme ` is `acme`, and the separator after such a name stays. [PathRules.parent] and
     * [PathRules.join] write one after such a name, as it stood in the middle of the path it came from.
     */
    @Test
    fun aSeparatorAfterANameEndingInADotOrASpaceStays() {
        assertCanonical(
            WINDOWS,
            """C:\w\acme \""" to """C:\w\acme \""",
            "c:/w/acme /" to """C:\w\acme \""",
            """C:\w\acme \\\""" to """C:\w\acme \""",
            """C:\w\acme.\""" to """C:\w\acme.\""",
            """C:\w\acme..\""" to """C:\w\acme..\""",
            """C:\w\acme \.\""" to """C:\w\acme \""",
            """\\server\share\x.\""" to """\\server\share\x.\""",
            """\\.\C:\w\acme.\""" to """\\.\C:\w\acme.\""",
            "acme /" to """acme \""",
            // a final \. goes with its separator before any trimming, as in Win32's own normalising - not checked on the VM
            """C:\w\acme \.""" to """C:\w\acme """,
            // .. is resolved before any trimming, so a separator after it changes nothing
            """C:\w\..\""" to """C:\w\..""",
            // a kept \\?\ path is read as written, with or without the separator
            """\\?\C:\w\acme.\""" to """\\?\C:\w\acme.""",
        )
        assertCanonical(UNIX, "/w/acme /" to "/w/acme ", "/w/acme./" to "/w/acme.")
        val different = listOf(
            """C:\w\acme \""" to """C:\w\acme """,
            """C:\w\acme.\""" to """C:\w\acme.""",
            """C:\w\acme..\""" to """C:\w\acme..""",
            """\\.\C:\w\acme \""" to """\\.\C:\w\acme """,
        )
        assertEquals(emptyList<Pair<String, String>>(), different.filter { (a, b) -> PathRules.same(a, b, WINDOWS) || PathRules.same(b, a, WINDOWS) })
        assertTrue(PathRules.same("""C:\w\acme \""", "c:/W/ACME //", WINDOWS))
        assertTrue(PathRules.same("""C:\w\..\""", """C:\w\..""", WINDOWS))
        assertTrue(PathRules.same("""\\?\C:\w\acme.\""", """\\?\C:\w\acme.""", WINDOWS))
        assertEquals("""C:\W\ACME \""", PathRules.key("c:/w/acme /", WINDOWS))

        // a parent's last name came from the middle of the path
        assertEquals("""C:\w\acme \""", PathRules.parent("""C:\w\acme \x""", WINDOWS))
        assertEquals("""C:\w\acme.\""", PathRules.parent("""C:\w\acme.\x""", WINDOWS))
        assertEquals("""C:\w""", PathRules.parent("""C:\w\acme \""", WINDOWS))
        assertEquals("""C:\w\..""", PathRules.parent("""C:\w\..\x""", WINDOWS))
        assertEquals("/w/acme ", PathRules.parent("/w/acme /x", UNIX))
        // join takes a name whole
        assertEquals("""C:\w\acme \""", PathRules.join("""C:\w""", "acme ", style = WINDOWS))
        assertEquals("""C:\w\acme \x""", PathRules.join("""C:\w\acme \""", "x", style = WINDOWS))
        assertEquals("""C:\w\acme \""", PathRules.join("""C:\w\acme \""", style = WINDOWS))
        assertEquals("""C:\w\acme """, PathRules.join("""C:\w\acme """, style = WINDOWS))
        assertEquals("""\\?\C:\w\acme """, PathRules.join("""\\?\C:\w""", "acme ", style = WINDOWS))
        assertEquals("/w/acme ", PathRules.join("/w", "acme ", style = UNIX))
        // so a worktree folder built with join from a name ending in a dot is not strictly the same as git's listing
        // of that very name - Worktrees.kt accepts `task-1.`, see "For brief 1" in the report
        val built = PathRules.join("""C:\w\acme""", ".claude", "worktrees", "task-1.", style = WINDOWS)
        assertEquals("""C:\w\acme\.claude\worktrees\task-1.\""", built)
        assertFalse(PathRules.same(built, "C:/w/acme/.claude/worktrees/task-1.", WINDOWS))
        // Worktrees.split's walk - the repository rebuilt from a worktree cwd's names is the folder that cwd lies in
        for (cwd in listOf("""C:\w\acme.\.claude\worktrees\x""", """C:\w\acme \.claude\worktrees\x""", """C:\w\acme\.claude\worktrees\x""")) {
            val repository = PathRules.join(PathRules.root(cwd, WINDOWS), PathRules.segments(cwd, WINDOWS).subList(0, 2), WINDOWS)
            val up = PathRules.parent(PathRules.parent(PathRules.parent(cwd, WINDOWS)!!, WINDOWS)!!, WINDOWS)
            assertEquals(cwd, up, repository)
        }
    }

    @Test
    fun unixCanonicalTextCollapsesSeparatorsAndKeepsBackslashesInNames() = assertCanonical(
        UNIX,
        "/w/acme" to "/w/acme",
        "/w/acme/" to "/w/acme",
        "/w//acme///" to "/w/acme",
        "//w/acme" to "/w/acme",
        "/w/./acme" to "/w/acme",
        "/" to "/",
        "///" to "/",
        "w/acme/" to "w/acme",
        "" to "",
        """/w/a\b""" to """/w/a\b""",
        """C:\w\acme""" to """C:\w\acme""",
        "/w/acme/../beta" to "/w/acme/../beta",
        "./C:/x" to "C:/x",
    )

    @Test
    fun windowsComparesCaseInsensitivelyAndEitherSeparator() {
        val same = listOf(
            """C:\w\acme""" to """c:/w/acme/""",
            """C:\w\acme""" to """C:\W\ACME""",
            """C:\Users\x\acme\.claude\worktrees\task-1""" to """C:/Users/x/acme/.Claude/Worktrees/task-1""",
            """\\?\C:\w\acme""" to """C:\w\acme""",
            """\\?\UNC\server\share\x""" to """//SERVER/share/x/""",
            """C:\""" to """c:""" + "/",
            """C:\w\\acme""" to """C:\w\acme\""",
            """\\server\share\.\x""" to """\\server\share\x""",
            // NTFS upper-cases both lower-case sigmas to one letter
            "C:\\\u03C2" to "C:\\\u03C3",
            // one text, dot and all, names one folder however Windows reads the dot
            """C:\w\acme.""" to """c:/W/ACME.""",
            """\\?\C:\w\acme.""" to """\\?\c:\w\ACME.""",
        )
        val different = listOf(
            """C:\w\acme""" to """D:\w\acme""",
            """C:\w\acme""" to """C:\w\acme2""",
            """C:\w\acme""" to """\w\acme""",
            """C:\w\acme""" to """C:w\acme""",
            """C:\w\acme""" to """w\acme""",
            """\\server\share\x""" to """\\other\share\x""",
            """C:\w\acme\..\acme""" to """C:\w\acme""",
            // letters NTFS keeps apart, though lower case would merge them - Kelvin, dotted I, Ohm, Angstrom
            "C:\\wor\u212Atrees" to "C:\\worktrees",
            "C:\\\u0130" to "C:\\i",
            "C:\\\u2126" to "C:\\\u03C9",
            "C:\\\u212B" to "C:\\\u00E5",
            // and letters Unicode upper-cases to ASCII while NTFS does not (VM) - the dotless i, the long s, the dotted I
            "C:\\w\\k\u0131l\u0131c" to "C:\\w\\kilic",
            "C:\\w\\worktree\u017F" to "C:\\w\\worktrees",
            "C:\\W\u0130NDOWS" to "C:\\WINDOWS",
            // one letter at a time, never the full mapping that writes ß as SS
            "C:\\w\\stra\u00DFe" to "C:\\w\\STRASSE",
            // with the prefix a trailing dot is part of the name, without it Win32 drops it
            """\\?\C:\w\acme.""" to """C:\w\acme.""",
            // Win32 opens one folder for these, but same stays on the strict side
            """C:\w\task-1.""" to """C:\w\task-1""",
            """C:\w\acme """ to """C:\w\acme""",
            """$VOLUME\x""" to """C:\x""",
            """.\C:\x""" to """C:\x""",
            // a dotless i is no drive letter, though it upper-cases to I
            "\u0131:\\x" to "i:\\x",
        )
        assertEquals(emptyList<Pair<String, String>>(), same.filterNot { (a, b) -> PathRules.same(a, b, WINDOWS) && PathRules.same(b, a, WINDOWS) })
        assertEquals(emptyList<Pair<String, String>>(), different.filter { (a, b) -> PathRules.same(a, b, WINDOWS) || PathRules.same(b, a, WINDOWS) })
    }

    @Test
    fun unixComparesCaseSensitivelyAndABackslashIsPartOfAName() {
        assertTrue(PathRules.same("/w/acme", "/w/acme/", UNIX))
        assertTrue(PathRules.same("/w//acme", "/w/acme", UNIX))
        assertFalse(PathRules.same("/w/acme", "/w/Acme", UNIX))
        assertFalse(PathRules.same("""a\b""", "a/b", UNIX))
        assertFalse(PathRules.same("/w/acme", "w/acme", UNIX))
        assertEquals(listOf("""a\b"""), PathRules.segments("""a\b""", UNIX))
        assertEquals(listOf("a", "b"), PathRules.segments("""a\b""", WINDOWS))
    }

    @Test
    fun keysAreUpperCaseOnWindowsAndExactOnUnix() {
        assertEquals("""C:\USERS\SKEIN\DEMO-SHOP""", PathRules.key("""c:/Users/Skein/Demo-Shop/""", WINDOWS))
        assertEquals("""\\SERVER\SHARE\X""", PathRules.key("""//server/share/x""", WINDOWS))
        assertEquals("""\\?\C:\W\ACME.""", PathRules.key("""\\?\c:\w\acme.""", WINDOWS))
        // the dotless i stays, so its name never reads as a drive
        assertEquals("\u0131:\\X", PathRules.key("\u0131:\\x", WINDOWS))
        assertEquals("C:\\W\\K\u0131L\u0131C\\STRA\u00DFE\\WORKTREE\u017F", PathRules.key("c:/w/k\u0131l\u0131c/stra\u00DFe/worktree\u017F", WINDOWS))
        assertEquals("/Users/Skein/Demo-Shop", PathRules.key("/Users/Skein/Demo-Shop/", UNIX))
    }

    @Test
    fun containmentGoesByWholeNames() {
        assertContainment(
            WINDOWS,
            row("""C:\w\acme""", """C:\w\acme""", INSIDE),
            row("""C:\w\acme\src""", """C:\w\acme""", INSIDE),
            row("""c:/w/acme/.claude/worktrees/task-1""", """C:\W\Acme\""", INSIDE),
            row("""C:\w\acme""", """C:\""", INSIDE),
            row("""C:\""", """c:/""", INSIDE),
            row("""\\?\C:\w\acme\src""", """C:\w\acme""", INSIDE),
            row("""\\server\share\x\y""", """\\server\share""", INSIDE),
            row("""\\SERVER\share\x""", """\\server\share""", INSIDE),
            row("""$VOLUME\x\y""", """$VOLUME\x""", INSIDE),
            row("""C:\w\acme2""", """C:\w\acme""", OUTSIDE),
            row("""C:\w\acme-old\src""", """C:\w\acme""", OUTSIDE),
            row("""C:\w""", """C:\w\acme""", OUTSIDE),
            row("""D:\w\acme""", """C:\w""", OUTSIDE),
            row("""\\other\share\x""", """\\server\share""", OUTSIDE),
            row("""\\server\share\x""", """C:\""", OUTSIDE),
            // the Kelvin sign is not k to NTFS, so this lies in a sibling of worktrees
            row("C:\\w\\acme\\.claude\\wor\u212Atrees\\task-1", """C:\w\acme\.claude\worktrees""", OUTSIDE),
            // nor is the long s an s
            row("C:\\w\\acme\\.claude\\worktree\u017F\\task-1", """C:\w\acme\.claude\worktrees""", OUTSIDE),
        )
        assertContainment(
            UNIX,
            row("/w/acme", "/w/acme", INSIDE),
            row("/w/acme/src", "/w/acme/", INSIDE),
            row("/w/acme", "/", INSIDE),
            row("/", "/", INSIDE),
            row("/w/acme2", "/w/acme", OUTSIDE),
            row("/w/Acme/src", "/w/acme", OUTSIDE),
            row("/w", "/w/acme", OUTSIDE),
            row("""/w/acme\src""", "/w/acme", OUTSIDE),
        )
    }

    /** A relative path hangs off a folder these rules never see, so only an absolute one can be placed by its text. */
    @Test
    fun aPathThatIsNotAbsoluteIsUnsure() {
        assertContainment(
            WINDOWS,
            // C:'s current folder may well be C:\
            row("""C:w\x""", """C:\w""", UNSURE),
            row("""C:w""", """C:\""", UNSURE),
            row("""\w\acme""", """C:\w""", UNSURE),
            row("""C:\w\acme""", """\w""", UNSURE),
            row("""w\acme\x""", """w\acme""", UNSURE),
            row("""\\server""", """\\server""", UNSURE),
            row("""C:\w\acme""", "", UNSURE),
            row("", "", UNSURE),
        )
        assertContainment(
            UNIX,
            row("w/acme/x", "w/acme", UNSURE),
            row("/w/acme", "w", UNSURE),
            row("w/acme", "/w", UNSURE),
            row("/w/acme", "", UNSURE),
        )
        assertTrue(PathRules.mayBeInside("""C:w\x""", """C:\w""", WINDOWS))
        assertFalse(PathRules.inside("""C:w\x""", """C:\w""", WINDOWS))
        assertTrue(PathRules.mayBeInside("/w/acme", "", UNIX))
        assertFalse(PathRules.inside("/w/acme", "", UNIX))
    }

    /**
     * A `..` past the names two paths share may lead anywhere once the disk resolves it, into the parent or out of
     * it, so the answer is UNSURE both ways - a check that guards a delete never says yes to it, and a check that
     * holds a delete back never says no.
     */
    @Test
    fun aDotDotMakesItUnsureUnlessBothShareIt() {
        val task = "/w/acme/.claude/worktrees/task-1"
        assertContainment(
            UNIX,
            row("/w/acme/../beta", "/w/acme", UNSURE),
            row("/w/acme/src/../..", "/w/acme", UNSURE),
            // a thread in task-1/sub/.. may still hold task-1, as far as the text can tell
            row("$task/sub/..", task, UNSURE),
            // a .. before the names part ways may still lead into the parent
            row("/w/beta/../acme/x", "/w/acme", UNSURE),
            row("/w/acme", "/w/acme/x/..", UNSURE),
            // a .. the parent shares, written the same, is the same folder either way
            row("/w/../acme/src", "/w/../acme", INSIDE),
            row("/w/acme/..x/y", "/w/acme", INSIDE),
            row("/w/beta/x", "/w/acme/..x", OUTSIDE),
        )
        assertContainment(
            WINDOWS,
            row("""C:\w\acme\..\beta""", """C:\w\acme""", UNSURE),
            row("""C:\w\beta\..\acme\x""", """C:\w\acme""", UNSURE),
        )
        assertFalse(PathRules.inside("$task/sub/..", task, UNIX))
        assertTrue(PathRules.mayBeInside("$task/sub/..", task, UNIX))
        assertNull(PathRules.segmentsUnder("$task/sub/..", task, UNIX))
    }

    /**
     * Win32 trims trailing dots and spaces from a name and a `\\?\` path keeps them, so such a name may or may not be
     * the folder it reads as. Worktrees.kt lets a worktree be called `task-1.`, which Win32 opens as `task-1`.
     */
    @Test
    fun aWindowsNameEndingInADotOrASpaceIsUnsure() {
        assertContainment(
            WINDOWS,
            row("""\\?\C:\w\acme.\x""", """C:\w\acme.""", UNSURE),
            row("""C:\w\acme.\x""", """C:\w\acme.""", UNSURE),
            row("""C:\w\acme\x""", """C:\w\acme.""", UNSURE),
            // the VM trims one dot off a name in the middle (`C:\Windows.\System32` opened), so this is surely inside
            // for Win32, but not under a \\?\ - UNSURE
            row("""C:\w\acme.\x""", """C:\w\acme""", UNSURE),
            // the VM keeps a space in the middle (`C:\Windows \System32` opened nothing), so OUTSIDE would be right
            // for Win32 - UNSURE on purpose, one rule for every such name, and a doubt never lets a delete through
            row("""C:\w\acme \x""", """C:\w\acme""", UNSURE),
            row("""C:\w\.claude\worktrees\task-1.""", """C:\w\.claude\worktrees\task-1""", UNSURE),
            row("""C:\w\acme\...""", """C:\w\acme""", UNSURE),
            row("""C:\w\acme\. """, """C:\w\acme""", UNSURE),
            row("""\\server.\share\x""", """\\server\share""", UNSURE),
            // a share name ending in a space under the device form is as doubtful as a folder name
            row("""\\.\UNC\server\share \x""", """\\.\UNC\server\share """, UNSURE),
        )
        assertNull(PathRules.segmentsUnder("""C:\w\acme\...""", """C:\w\acme""", WINDOWS))
        assertNull(PathRules.segmentsUnder("""C:\w\acme\. """, """C:\w\acme""", WINDOWS))
        assertNull(PathRules.relative("""C:\w\acme.\x""", """C:\w\acme.""", WINDOWS))
        // on Unix a dot or a space is an ordinary last letter
        assertContainment(UNIX, row("/w/acme./x", "/w/acme.", INSIDE), row("/w/acme/x", "/w/acme.", OUTSIDE))
    }

    /** A device path such as a volume GUID may be any drive, so against another root it is UNSURE, never OUTSIDE. */
    @Test
    fun aDeviceRootAgainstAnotherRootIsUnsure() {
        assertContainment(
            WINDOWS,
            row("""$VOLUME\x""", """C:\""", UNSURE),
            row("""C:\x""", """$VOLUME\""", UNSURE),
            row("""\\.\C:\x""", """C:\""", UNSURE),
            row("""\\?\UNC\?\C:\x""", """C:\""", UNSURE),
            row("""\\?\UNC\?\C:\x""", """\\?\UNC\?\C:""", INSIDE),
        )
    }

    @Test
    fun segmentsUnderAndRelative() {
        assertEquals(listOf(".claude", "worktrees", "task-1"), PathRules.segmentsUnder("C:/Users/x/acme/.claude/worktrees/task-1", """C:\Users\x\acme""", WINDOWS))
        assertEquals(emptyList<String>(), PathRules.segmentsUnder("""C:\w\acme\""", "c:/w/acme", WINDOWS))
        assertNull(PathRules.segmentsUnder("""C:\w\acme2""", """C:\w\acme""", WINDOWS))
        assertEquals(listOf("src", "main"), PathRules.segmentsUnder("/w/acme/src/main/", "/w/acme", UNIX))
        assertNull(PathRules.segmentsUnder("/w/acme", "/w/acme/src", UNIX))
        assertNull(PathRules.segmentsUnder("w/acme/x", "w/acme", UNIX))
        assertNull(PathRules.segmentsUnder("/w/acme", "", UNIX))

        assertEquals("""src\main""", PathRules.relative("C:/w/acme/src/main", """C:\w\acme""", WINDOWS))
        assertEquals("src/main", PathRules.relative("/w/acme/src/main", "/w/acme", UNIX))
        assertEquals("", PathRules.relative("/w/acme", "/w/acme/", UNIX))
        assertNull(PathRules.relative("/w/beta", "/w/acme", UNIX))
        assertEquals("w/acme", PathRules.relative("/w/acme", "/", UNIX))
    }

    @Test
    fun absoluteNeedsARootOfItsOwn() {
        val absolute = listOf(
            """C:\""", """c:/w/acme""", """\\server\share""", """\\server\share\x""", """\\?\C:\x""", """\\?\UNC\server\share\x""",
            """\\?\UNC\server\share""", """\\wsl$\Ubuntu\home""", """$VOLUME\x""", """$VOLUME\""", """\\?\UNC\?\C:\x""", """\\.\pipe\x""",
        )
        val relative = listOf(
            """\w\acme""", """C:""", """C:w\acme""", """w\acme""", "", """\\server""", "/w/acme", """.\C:\x""",
            // a device or UNC root with a part missing is no folder
            """\\?\UNC""", """\\?\UNC\""", """\\?\UNC\server""", """\\?\C:""", VOLUME, """\\?\""", """\\?\\C:\x""",
        )
        assertEquals(emptyList<String>(), absolute.filterNot { PathRules.isAbsolute(it, WINDOWS) })
        assertEquals(emptyList<String>(), relative.filter { PathRules.isAbsolute(it, WINDOWS) })
        assertTrue(PathRules.isAbsolute("/w/acme", UNIX))
        assertTrue(PathRules.isAbsolute("/", UNIX))
        assertFalse(PathRules.isAbsolute("w/acme", UNIX))
        assertFalse(PathRules.isAbsolute("""C:\w\acme""", UNIX))
        assertFalse(PathRules.isAbsolute("", UNIX))
    }

    @Test
    fun rootAndSegments() {
        assertEquals("""C:\""", PathRules.root("c:/w/acme", WINDOWS))
        assertEquals("""\\server\share\""", PathRules.root("//server/share/x", WINDOWS))
        assertEquals("""\""", PathRules.root("""\w\acme""", WINDOWS))
        assertEquals("C:", PathRules.root("""C:w""", WINDOWS))
        assertEquals("", PathRules.root("""w\acme""", WINDOWS))
        assertEquals("""$VOLUME\""", PathRules.root("""$VOLUME\x""", WINDOWS))
        assertEquals("""\\?\C:\""", PathRules.root("""\\?\C:\w\acme.""", WINDOWS))
        assertEquals("/", PathRules.root("/w/acme", UNIX))
        assertEquals("", PathRules.root("w/acme", UNIX))
        assertEquals(listOf("w", "acme"), PathRules.segments("""\\?\C:\w\.\acme\""", WINDOWS))
        assertEquals(listOf("x"), PathRules.segments("""\\server\share\x""", WINDOWS))
        assertEquals(emptyList<String>(), PathRules.segments("/", UNIX))
        // the root and the segments put back together give the canonical text
        val inputs = listOf(
            """c:/w/acme/""", """\\?\UNC\server\share\x""", """\w\acme""", """C:w""", """w\acme""", """C:\""",
            """\\?\C:\w\acme.""", """\\?\UNC\?\C:\x""", """.\C:\x""", """$VOLUME\x""",
        )
        for (p in inputs) {
            assertEquals(p, PathRules.canonical(p, WINDOWS), PathRules.join(PathRules.root(p, WINDOWS), PathRules.segments(p, WINDOWS), WINDOWS))
        }
    }

    @Test
    fun nameParentAndJoin() {
        assertEquals("acme", PathRules.name("""C:/w/acme/""", WINDOWS))
        assertEquals("", PathRules.name("""C:\""", WINDOWS))
        assertEquals("""a\b""", PathRules.name("""/w/a\b""", UNIX))
        assertEquals("b", PathRules.name("""/w/a\b""", WINDOWS))

        assertEquals("""C:\w""", PathRules.parent("""c:/w/acme/""", WINDOWS))
        assertEquals("""C:\""", PathRules.parent("""C:\w""", WINDOWS))
        assertNull(PathRules.parent("""C:\""", WINDOWS))
        assertEquals("/", PathRules.parent("/w", UNIX))
        assertNull(PathRules.parent("/", UNIX))
        assertEquals("", PathRules.parent("acme", UNIX))
        assertNull(PathRules.parent("", UNIX))
        // a parent is canonical, so it keeps a \\?\ only while a name in it still needs one
        assertEquals("""\\?\C:\w\acme.""", PathRules.parent("""\\?\C:\w\acme.\x""", WINDOWS))
        assertEquals("""C:\w""", PathRules.parent("""\\?\C:\w\acme.""", WINDOWS))

        assertEquals("""C:\w\acme\.claude\worktrees\task-1""", PathRules.join("c:/w/acme/", ".claude", "worktrees", "task-1", style = WINDOWS))
        assertEquals("""C:\w\acme\.claude\worktrees""", PathRules.join("""C:\w\acme""", ".claude/worktrees", style = WINDOWS))
        assertEquals("""C:\x""", PathRules.join("""C:\""", "x", style = WINDOWS))
        assertEquals("/w/acme/.claude/worktrees/task-1", PathRules.join("/w/acme/", ".claude", "worktrees", "task-1", style = UNIX))
        assertEquals("/x", PathRules.join("/", "x", style = UNIX))
        assertEquals("x/y", PathRules.join("", "x", "y", style = UNIX))
        assertEquals("/w/acme", PathRules.join("/w/acme", style = UNIX))
        assertEquals("/w/acme/x", PathRules.join("/w/acme", listOf("x"), UNIX))
        // a \\?\ the base was written with stays where the joined name needs it, and goes where it does not
        assertEquals("""\\?\C:\w\acme.""", PathRules.join("""\\?\C:\w""", "acme.", style = WINDOWS))
        assertEquals("""\\?\UNC\server\share\x.""", PathRules.join("""\\?\UNC\server\share""", "x.", style = WINDOWS))
        assertEquals("""C:\w\acme""", PathRules.join("""\\?\C:\w""", "acme", style = WINDOWS))
        assertEquals(""".\C:\x""", PathRules.join("", "C:", "x", style = WINDOWS))
    }

    /** One name, as Worktrees matches `.claude` and `worktrees` while it walks a path's segments. */
    @Test
    fun sameNameFollowsTheStyleCaseRule() {
        assertTrue(PathRules.sameName(".claude", ".Claude", WINDOWS))
        assertTrue(PathRules.sameName("worktrees", "WORKTREES", WINDOWS))
        assertFalse(PathRules.sameName(".claude", ".Claude", UNIX))
        assertTrue(PathRules.sameName(".claude", ".claude", UNIX))
        assertFalse(PathRules.sameName("acme", "acme2", WINDOWS))
        assertFalse(PathRules.sameName("wor\u212Atrees", "worktrees", WINDOWS))
        assertFalse(PathRules.sameName("worktree\u017F", "WORKTREES", WINDOWS))
        assertFalse(PathRules.sameName("ske\u0131n", "SKEIN", WINDOWS))
        assertTrue(PathRules.sameName("ske\u0131n", "SKE\u0131N", WINDOWS))
    }

    /**
     * Rules that hold for any text, checked over [corpus] in both styles. Each failure is listed by the input that
     * broke it.
     */
    @Test
    fun propertiesHoldForOddInputs() {
        val wrong = mutableListOf<String>()
        for (style in PathStyle.entries) {
            for (p in corpus) {
                val canonical = PathRules.canonical(p, style)
                val again = PathRules.canonical(canonical, style)
                if (again != canonical) wrong += "$style canonical moves on: $p -> $canonical -> $again"
                if (!PathRules.same(p, canonical, style)) wrong += "$style not the same as its canonical text: $p, $canonical"
                if (PathRules.isAbsolute(p, style) != PathRules.isAbsolute(canonical, style)) wrong += "$style canonical text changes absolute: $p -> $canonical"
                if (PathRules.containment(p, p, style) == OUTSIDE) wrong += "$style outside itself: $p"
                // a path not written with the literal prefix never gets one, Win32 would stop trimming its names
                if (style == WINDOWS && !p.startsWith("""\\?\""") && p != """\\?""" && canonical.startsWith("""\\?\""")) {
                    wrong += "$style canonical text turns literal: $p -> $canonical"
                }
                if (style == WINDOWS && p.startsWith("""\\?\""") && PathRules.segments(p, style).any { it.endsWith('.') || it.endsWith(' ') }) {
                    val normalised = "//?/" + p.drop(4)
                    if (PathRules.same(p, normalised, style)) wrong += "$style literal prefix the same as a normalised one: $p, $normalised"
                }
                val name = PathRules.name(p, style)
                // join takes a name whole, so a last name Win32 trims at the very end (no separator after it) comes back
                // with one - the only way root, segments, parent and name lose what the path read
                val endsOpen = style == WINDOWS && (name.endsWith('.') || name.endsWith(' ')) && name != ".." && !canonical.endsWith('\\')
                val rebuilt = PathRules.join(PathRules.root(p, style), PathRules.segments(p, style), style)
                if (rebuilt != canonical && !(endsOpen && rebuilt == "$canonical\\")) wrong += "$style root and segments give $rebuilt, canonical is $canonical: $p"
                val up = PathRules.parent(p, style)
                if (up != null) {
                    val back = PathRules.join(up, name, style = style)
                    // a parent is canonical, so it may also drop a \\?\ that only the last name needed
                    val fine = if (endsOpen) back == "$canonical\\" || canonical.startsWith("""\\?\""") else PathRules.same(back, p, style)
                    if (!fine) wrong += "$style parent and name do not give it back: $p -> $up + $name = $back"
                }
            }
            for (a in corpus) {
                for (b in corpus) {
                    val ab = PathRules.containment(a, b, style)
                    val ba = PathRules.containment(b, a, style)
                    val same = PathRules.same(a, b, style)
                    if (same && (ab == OUTSIDE || ba == OUTSIDE)) wrong += "$style same yet outside: $a, $b"
                    if (ab == INSIDE && ba == INSIDE && !same) wrong += "$style inside each other yet not the same: $a, $b"
                    if ((PathRules.segmentsUnder(a, b, style) != null) != (ab == INSIDE)) wrong += "$style segmentsUnder disagrees with $ab: $a in $b"
                    // one path, so every third path sees the two alike
                    if (same && a != b) {
                        corpus.firstOrNull { x ->
                            PathRules.containment(a, x, style) != PathRules.containment(b, x, style) ||
                                PathRules.containment(x, a, style) != PathRules.containment(x, b, style)
                        }?.let { x -> wrong += "$style same, but $x tells them apart: $a, $b" }
                    }
                }
            }
        }
        assertEquals(emptyList<String>(), wrong.take(20))
    }

    /** Nothing here throws, whatever text a status file, git or the IDE hands over. */
    @Test
    fun oddInputNeverThrows() {
        val odd = listOf("", "/", "\\", "\\\\", "\\\\\\", "\\\\?\\", "\\\\?\\UNC", "\\\\?\\UNC\\", "\\\\.\\pipe\\x", ":", "::", "C", "1:\\x", "C:\\\u0000", "..", ".", "a/..", "\\\\?\\C:", "\\\\?", "\\\\.", "\uD800")
        for (style in PathStyle.entries) for (p in odd) {
            PathRules.canonical(p, style)
            PathRules.key(p, style)
            PathRules.same(p, p, style)
            PathRules.containment(p, p, style)
            PathRules.inside(p, p, style)
            PathRules.mayBeInside(p, "/", style)
            PathRules.segmentsUnder(p, "/", style)
            PathRules.relative(p, p, style)
            PathRules.isAbsolute(p, style)
            PathRules.root(p, style)
            PathRules.segments(p, style)
            PathRules.name(p, style)
            PathRules.parent(p, style)
            PathRules.join(p, "x", style = style)
        }
        // a drive is a letter, so a digit with a colon is just a name
        assertEquals("""1:\x""", PathRules.canonical("1:/x", WINDOWS))
        assertFalse(PathRules.isAbsolute("1:/x", WINDOWS))
    }

    @Test
    fun theDefaultStyleIsTheRunningOs() {
        assertEquals(if (SystemInfo.isWindows) WINDOWS else UNIX, PathStyle.current)
        assertEquals(PathRules.canonical("/w/acme/", PathStyle.current), PathRules.canonical("/w/acme/"))
        assertEquals(if (SystemInfo.isWindows) '\\' else '/', PathStyle.current.separator)
    }

    private companion object {
        const val VOLUME = """\\?\Volume{00000000-0000-0000-0000-000000000000}"""

        /** Every prefix with every head and tail, so the mix reaches shapes no row above names, and a few Unix ones. */
        val corpus: List<String> = run {
            val prefixes = listOf("", """.\""", "./", """\\?\""", """\\?\\""", """\\?\UNC\""", """\\.\""", "//?/", """\\?/""", "//?/UNC/")
            val heads = listOf("", """C:\""", "c:", "C:w", """\""", """\\server\share\""", """\\server""", """?\C:\""", "Volume{0}\\", "UNC", "/")
            val tails = listOf("", """w\acme""", "w/Acme/", "acme.", "acme ", """acme \""", "acme./", """acme \.""", """..\b""", """.\x""", "\u212A", "\u03C2", "\u017F")
            prefixes.flatMap { prefix -> heads.flatMap { head -> tails.map { tail -> prefix + head + tail } } } + listOf(
                "/w/acme", "/w/acme/", "//w//acme", "/w/./acme", "/w/acme/..", "/w/../acme", "/w/acme/x/..", "/w/Acme", "/w/a\\b",
                "a\\b", "a/b", ".", "..", ":", "::", "1:\\x", "C:\\\u0000", "\u0131:\\x", "i:\\x", "\\\\\\", "\\\\", "C:\\w\\acme\\..",
                "C:\\\u03C3", "C:\\K", "C:\\k", "C:\\S", "C:\\s", "C:\\I", "C:\\\u0131","\\\\\\?\\C:\\x", "\\\\?\\UNC\\\\?\\C:\\x", "\\\\\\.\\x", "\\\\?\\UNC\\?\\C:",
            )
        }
    }
}
