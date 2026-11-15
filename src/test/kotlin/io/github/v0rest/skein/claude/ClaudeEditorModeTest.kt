package io.github.v0rest.skein.claude

import io.github.v0rest.skein.testutil.CanonicalTemporaryFolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.Reader
import java.io.StringReader
import java.nio.file.Files
import java.nio.file.Path

/**
 * Claude Code's vim mode, `"editorMode": "vim"`: in its home's `settings.json`, in a project's `.claude/settings.json` or
 * `.claude/settings.local.json`, or, for an older Claude Code, in its global config `.claude.json`, which is read only
 * while the home's `settings.json` sets no mode. Files built by hand; the global config's other values (made-up account
 * details) are skipped.
 */
class ClaudeEditorModeTest {
    @get:Rule
    val tmp = CanonicalTemporaryFolder()

    @Test
    fun theHomesSettingsDecideTheUsersModeAndTheGlobalConfigOnlyWithoutOne() {
        var configReads = 0
        val config = { configReads++; "vim" }
        assertEquals("normal", ClaudeEditorMode.userMode("normal", config))
        assertEquals("the global config is not even read then", 0, configReads)
        assertEquals("vim", ClaudeEditorMode.userMode(null, config))
        assertEquals(1, configReads)
    }

    @Test
    fun vimForTheUserOrInAnyProjectFileCounts() {
        assertTrue(ClaudeEditorMode.isVim("vim", emptyList()))
        assertTrue(ClaudeEditorMode.isVim(null, listOf(null, "vim")))
        assertTrue("vim anywhere is vim, the safe side", ClaudeEditorMode.isVim("normal", listOf("vim")))
        assertFalse(ClaudeEditorMode.isVim("normal", listOf("normal", null)))
        assertFalse(ClaudeEditorMode.isVim(null, emptyList()))
    }

    @Test
    fun onlyTheTopLevelStringCounts() {
        assertEquals("vim", ClaudeEditorMode.settingsMode("""{"model":"x","editorMode":"vim","permissions":{"allow":[]}}"""))
        assertNull(ClaudeEditorMode.settingsMode("""{"env":{"editorMode":"vim"}}"""))
        assertNull(ClaudeEditorMode.settingsMode("""{"editorMode":true}"""))
        assertNull(ClaudeEditorMode.settingsMode("""{"editorMode":["vim"]}"""))
        assertEquals("the last of two wins, as in JavaScript", "normal", ClaudeEditorMode.settingsMode("""{"editorMode":"vim","editorMode":"normal"}"""))
    }

    /** A file Claude Code itself would reject, or none, says nothing: as without the setting. */
    @Test
    fun aFileThatIsNotStrictJsonSaysNothing() {
        assertNull(ClaudeEditorMode.settingsMode(null))
        assertNull(ClaudeEditorMode.settingsMode(""))
        assertNull(ClaudeEditorMode.settingsMode("""{"editorMode":"vim",}"""))
        assertNull(ClaudeEditorMode.settingsMode("""// vim
{"editorMode":"vim"}"""))
        assertNull(ClaudeEditorMode.settingsMode("""{"editorMode":"vim"} trailing"""))
        assertNull(ClaudeEditorMode.modeOf(StringReader("""{"editorMode":"vim""")))
    }

    @Test
    fun theGlobalConfigIsStreamedForThatOneValue() {
        val config = """{"numStartups":3,"userID":"0000","oauthAccount":{"emailAddress":"user@example.invalid"},"projects":{"/w/acme":{"hasTrustDialogAccepted":true}},"editorMode":"vim"}"""
        assertEquals("vim", ClaudeEditorMode.modeOf(StringReader(config)))
    }

    @Test
    fun theWatchFollowsTheHomesSettingsAndTheGlobalConfig() {
        val home = home()
        val watch = ClaudeEditorModeWatch(home)
        assertFalse("no files at all", watch.vim(emptyList()))
        Files.writeString(home.configFile!!, """{"numStartups":1,"editorMode":"vim"}""")
        assertTrue("an older Claude Code's global config", watch.vim(emptyList()))
        Files.writeString(home.root.resolve("settings.json"), """{"editorMode":"normal"}""")
        assertFalse("the home's settings decide", watch.vim(emptyList()))
        Files.writeString(home.root.resolve("settings.json"), """{"editorMode":"vim"}""")
        assertTrue(watch.vim(emptyList()))
        Files.writeString(home.root.resolve("settings.json"), """{"model":"x"}""")
        assertTrue("no mode in the settings: the global config again", watch.vim(emptyList()))
        Files.delete(home.configFile!!)
        assertFalse(watch.vim(emptyList()))
    }

    /** The global config, which can be large, is parsed only while the settings set no mode, and only once it changed. */
    @Test
    fun theGlobalConfigIsParsedOnlyWhenItCountsAndChanged() {
        val home = home()
        Files.writeString(home.configFile!!, """{"numStartups":1,"editorMode":"vim"}""")
        val watch = ClaudeEditorModeWatch(home)
        assertTrue(watch.vim(emptyList()))
        assertTrue(watch.vim(emptyList()))
        assertEquals("unchanged, not parsed again", 1, home.configReads)
        Files.writeString(home.root.resolve("settings.json"), """{"editorMode":"normal"}""")
        Files.writeString(home.configFile!!, """{"numStartups":22,"editorMode":"vim"}""")
        assertFalse(watch.vim(emptyList()))
        assertEquals("the settings set a mode, so the changed config is not parsed", 1, home.configReads)
        assertEquals("missing once, then changed", 2, home.settingsReads)
        assertFalse(watch.vim(emptyList()))
        assertEquals("unchanged settings are not read again", 2, home.settingsReads)
    }

    @Test
    fun aProjectsOwnSettingsCountForItsFolderOnly() {
        val home = home()
        val watch = ClaudeEditorModeWatch(home)
        val project = tmp.newFolder("demo-shop").toPath()
        val settingsDir = Files.createDirectories(project.resolve(".claude"))
        Files.writeString(settingsDir.resolve("settings.local.json"), """{"editorMode":"vim"}""")
        assertTrue(watch.vim(listOf(project.toString())))
        assertFalse("another folder", watch.vim(listOf(tmp.newFolder("acme").toString())))
        Files.delete(settingsDir.resolve("settings.local.json"))
        Files.writeString(settingsDir.resolve("settings.json"), """{"editorMode":"vim"}""")
        assertTrue(watch.vim(listOf(project.toString())))
        Files.writeString(settingsDir.resolve("settings.json"), """{"editorMode":"normal"}""")
        assertFalse(watch.vim(listOf(project.toString())))
    }

    /** A home that counts how often its settings file is read and its global config parsed. */
    private class Counting(root: Path, config: Path) : ClaudeHome(root, config) {
        var settingsReads = 0
        var configReads = 0

        override fun readText(path: Path, maxBytes: Int): String? = super.readText(path, maxBytes).also { if (path.fileName.toString() == "settings.json") settingsReads++ }

        override fun <T : Any> readConfig(maxBytes: Long, parse: (Reader) -> T?): T? = super.readConfig(maxBytes, parse).also { configReads++ }
    }

    private fun home(): Counting = Counting(tmp.newFolder("claude-home").toPath(), tmp.root.toPath().resolve("claude.json"))
}
