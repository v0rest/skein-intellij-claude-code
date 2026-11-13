package io.github.v0rest.skein.claude

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class WorktreeBaseRefTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private fun json(ref: String) = """{"worktree": {"baseRef": "$ref"}}"""

    @Test
    fun noSettingMeansFresh() {
        assertEquals(BaseRef.FRESH, WorktreeBaseRef.of(emptyList()))
        assertEquals(BaseRef.FRESH, WorktreeBaseRef.of(listOf(null, "{}", """{"worktree": {}}""")))
    }

    @Test
    fun headAndFreshAreRead() {
        assertEquals(BaseRef.HEAD, WorktreeBaseRef.of(listOf(json("head"))))
        assertEquals(BaseRef.FRESH, WorktreeBaseRef.of(listOf(json("fresh"))))
    }

    @Test
    fun theFirstFileThatSetsItWinsInTheOrderGiven() {
        assertEquals(BaseRef.HEAD, WorktreeBaseRef.of(listOf(null, json("head"), json("fresh"))))
        assertEquals(BaseRef.FRESH, WorktreeBaseRef.of(listOf(json("fresh"), json("head"))))
    }

    @Test
    fun aBrokenFileOrAValueClaudeCodeDoesNotKnowIsSkipped() {
        assertEquals(BaseRef.HEAD, WorktreeBaseRef.of(listOf("{not json", """{"worktree": "head"}""", json("main"), json("head"))))
        assertEquals(BaseRef.FRESH, WorktreeBaseRef.of(listOf("[1]", """{"worktree": {"baseRef": 3}}""")))
    }

    @Test
    fun projectLocalBeatsProjectBeatsUser() {
        val home = ClaudeHome(tmp.newFolder("home").toPath())
        val repo = tmp.newFolder("acme").toPath()
        Files.writeString(home.root.resolve("settings.json"), json("head"))
        assertEquals(BaseRef.HEAD, WorktreeBaseRef.read(home, repo))
        write(repo, "settings.json", json("fresh"))
        assertEquals(BaseRef.FRESH, WorktreeBaseRef.read(home, repo))
        write(repo, "settings.local.json", json("head"))
        assertEquals(BaseRef.HEAD, WorktreeBaseRef.read(home, repo))
    }

    @Test
    fun missingFilesGiveFresh() {
        val home = ClaudeHome(tmp.newFolder("home").toPath())
        assertEquals(BaseRef.FRESH, WorktreeBaseRef.read(home, tmp.newFolder("acme").toPath()))
    }

    private fun write(repo: Path, file: String, text: String) {
        val dir = repo.resolve(Worktrees.PROJECT_SETTINGS_DIR)
        Files.createDirectories(dir)
        Files.writeString(dir.resolve(file), text)
    }

    @Test
    fun aFileClaudeCodeWouldRejectIsSkippedSoTheNextLevelDecides() {
        val commented = "{\n  // keep the base\n  \"worktree\": {\"baseRef\": \"fresh\"}\n}"
        assertEquals(BaseRef.HEAD, WorktreeBaseRef.of(listOf(commented, json("head"))))
        // a trailing comma is rejected by lenient parsing too, so this one says nothing about strictness
        assertEquals(BaseRef.HEAD, WorktreeBaseRef.of(listOf("""{"worktree": {"baseRef": "fresh",}}""", json("head"))))
        assertEquals(BaseRef.HEAD, WorktreeBaseRef.of(listOf("{'worktree': {'baseRef': 'fresh'}}", json("head"))))
        assertEquals(BaseRef.HEAD, WorktreeBaseRef.of(listOf("{worktree: {baseRef: \"fresh\"}}", json("head"))))
        assertEquals(BaseRef.HEAD, WorktreeBaseRef.of(listOf("""{"worktree": {"baseRef": "fresh"}} trailing""", json("head"))))
    }
}
