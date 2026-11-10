package io.github.v0rest.skein.claude

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class SessionTitlesTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val id = "55555555-5555-4555-8555-555555555555"
    private val home get() = ClaudeHome(tmp.root.toPath().resolve(".claude"))

    private fun transcript(vararg lines: String): Path {
        val file = home.projectsDir.resolve("-w-acme").resolve("$id.jsonl")
        Files.createDirectories(file.parent)
        Files.write(file, lines.toList())
        return file
    }

    private val filler = """{"type":"assistant","message":{"content":"${"x".repeat(1000)}"}}"""

    @Test
    fun theTitleIsTheHistoryOne() {
        val file = transcript("""{"type":"last-prompt","lastPrompt":"Fix the login"}""", """{"type":"ai-title","aiTitle":"Login fix"}""")
        assertEquals("Login fix", SessionTitles.read(home, file))
    }

    @Test
    fun anEarlyRenameBeyondTheTailStillWins() {
        val lines = listOf("""{"type":"custom-title","customTitle":"Billing"}""") + List(HistoryScanner.TAIL_BYTES / 1000 + 20) { filler }
        assertEquals("Billing", SessionTitles.read(home, transcript(*lines.toTypedArray())))
    }

    @Test
    fun noTitleGivesNull() = assertNull(SessionTitles.read(home, transcript("""{"type":"user","cwd":"/w/acme","message":{"role":"user","content":"hi"}}""")))

    @Test
    fun aMissingFileGivesNull() = assertNull(SessionTitles.read(home, home.projectsDir.resolve("-w-acme").resolve("$id.jsonl")))

    private val conversation = arrayOf(
        """{"type":"assistant","message":{"content":[{"type":"text","text":"Secret answer."}]}}""",
        """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t1","name":"Bash","input":{"command":"secret-cmd"}}]}}""",
        """{"type":"assistant","message":{"content":[{"type":"tool_use","id":"t2","name":"AskUserQuestion","input":{"questions":[{"question":"Secret question?"}]}}]}}""",
    )

    @Test
    fun answersToolCallsAndQuestionsNeverBecomeTheTitle() {
        assertEquals("Login fix", SessionTitles.read(home, transcript(*conversation, """{"type":"ai-title","aiTitle":"Login fix"}""")))
        assertNull(SessionTitles.read(home, transcript(*conversation)))
    }
}
