package io.github.v0rest.skein.notify

import io.github.v0rest.skein.claude.WaitReason.OTHER
import io.github.v0rest.skein.claude.WaitReason.PERMISSION
import io.github.v0rest.skein.claude.WaitReason.QUESTION
import io.github.v0rest.skein.threads.NotificationKind.NEEDS_YOU
import io.github.v0rest.skein.threads.NotificationKind.TURN_FINISHED
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class NotificationContentTest {
    private fun assistant(vararg blocks: String) =
        """{"type":"assistant","uuid":"55555555-5555-4555-8555-555555555555","message":{"role":"assistant","content":[${blocks.joinToString(",")}]}}"""

    private fun user(text: String) = """{"type":"user","message":{"role":"user","content":"$text"}}"""

    private fun toolResult(id: String) =
        """{"type":"user","message":{"role":"user","content":[{"type":"tool_result","tool_use_id":"$id","content":"ok"}]}}"""

    private fun text(t: String) = """{"type":"text","text":"$t"}"""

    private fun toolUse(name: String, input: String, id: String = "toolu_01") = """{"type":"tool_use","id":"$id","name":"$name","input":$input}"""

    private fun bash(command: String, id: String = "toolu_01") = toolUse("Bash", """{"command":"$command","description":"run it"}""", id)

    private fun permission(vararg lines: String) = NotificationContent.fromTail(lines.toList(), NEEDS_YOU, PERMISSION)

    @Test
    fun turnFinishedShowsTheFirstSentenceOfTheLastAnswer() {
        val lines = listOf(
            user("fix the chart"),
            assistant(text("Looking at it now. One moment.")),
            assistant(bash("./gradlew test")),
            toolResult("toolu_01"),
            assistant(text("I fixed the chart. The build is green now.")),
        )
        assertEquals("I fixed the chart.", NotificationContent.fromTail(lines, TURN_FINISHED, null))
        // the newest entry only called a tool: the answer before it, in the same turn, is still the last text
        assertEquals("I fixed the chart.", NotificationContent.fromTail(lines + assistant(bash("git status")), TURN_FINISHED, null))
    }

    @Test
    fun aTurnWithoutTextShowsNothing() {
        val lines = listOf(
            assistant(text("The earlier answer.")),
            user("now run it"),
            assistant(bash("./gradlew test", id = "toolu_02")),
            toolResult("toolu_02"),
        )
        assertNull(NotificationContent.fromTail(lines, TURN_FINISHED, null))
        assertNull(NotificationContent.fromTail(lines, NEEDS_YOU, OTHER))
        // a tool result or a meta entry is not the user's prompt: the answer before it still counts
        val meta = """{"type":"user","isMeta":true,"message":{"role":"user","content":"<local-command-caveat>x</local-command-caveat>"}}"""
        assertEquals("Answer.", NotificationContent.fromTail(listOf(assistant(text("Answer.")), meta, toolResult("toolu_09")), TURN_FINISHED, null))
        // a prompt given as text blocks is a prompt too
        val blocks = """{"type":"user","message":{"role":"user","content":[{"type":"text","text":"and now?"}]}}"""
        assertNull(NotificationContent.fromTail(listOf(assistant(text("Answer.")), blocks), TURN_FINISHED, null))
    }

    @Test
    fun markdownIsStripped() {
        val answer = text("## Result\\n\\n- **Fixed** `Foo.kt`, see [the docs](https://example.test/x) for more!\\n- Next: run it.")
        assertEquals("Fixed Foo.kt, see the docs for more!", NotificationContent.fromTail(listOf(assistant(answer)), TURN_FINISHED, null))
        assertEquals("Fixed Foo.kt.", NotificationContent.fromTail(listOf(assistant(text("## Result\\n\\nFixed Foo.kt. More."))), TURN_FINISHED, null))
        val list = text("- **Fixed** `Foo.kt`, see [the docs](https://example.test/x) for more!\\n- Next: run it.")
        assertEquals("Fixed Foo.kt, see the docs for more!", NotificationContent.fromTail(listOf(assistant(list)), TURN_FINISHED, null))
        val names = text("Kept __init__.py and **kwargs as they were, *only* the _order_ changed.")
        assertEquals("Kept __init__.py and **kwargs as they were, only the order changed.", NotificationContent.fromTail(listOf(assistant(names)), TURN_FINISHED, null))
        // a pair right before a full stop is still a pair; a dot that goes on with a word is a file name
        assertEquals("Tests are green.", NotificationContent.fromTail(listOf(assistant(text("Tests are **green**."))), TURN_FINISHED, null))
        assertEquals("Updated Foo.kt.", NotificationContent.fromTail(listOf(assistant(text("Updated **Foo.kt**."))), TURN_FINISHED, null))
        assertEquals("Updated Foo.kt.", NotificationContent.fromTail(listOf(assistant(text("Updated __Foo.kt__."))), TURN_FINISHED, null))
        val fenceFirst = text("```kotlin\\nval x = 1\\n```\\nThe snippet above does it. Try it.")
        assertEquals("The snippet above does it.", NotificationContent.fromTail(listOf(assistant(fenceFirst)), TURN_FINISHED, null))
        val tilde = text("~~~\\nraw block\\n~~~\\nDone with the block.")
        assertEquals("Done with the block.", NotificationContent.fromTail(listOf(assistant(tilde)), TURN_FINISHED, null))
        val unclosed = text("Here it is:\\n```\\nnever closed")
        assertEquals("Here it is:", NotificationContent.fromTail(listOf(assistant(unclosed)), TURN_FINISHED, null))
    }

    @Test
    fun aHeadingOrABlankLineEndsTheFirstSentence() {
        assertEquals("I fixed it.", NotificationContent.fromTail(listOf(assistant(text("## Summary\\nI fixed it. Then more."))), TURN_FINISHED, null))
        // a heading after a sentence without a stop ends it, it is never glued on
        assertEquals("Fixed the chart", NotificationContent.fromTail(listOf(assistant(text("Fixed the chart\\n## Next\\nRun it."))), TURN_FINISHED, null))
        assertEquals("First paragraph without a stop", NotificationContent.fromTail(listOf(assistant(text("First paragraph without a stop\\n\\nSecond one."))), TURN_FINISHED, null))
        assertEquals("One line\\nwrapped.".replace("\\n", " "), NotificationContent.fromTail(listOf(assistant(text("One line\\nwrapped. Next."))), TURN_FINISHED, null))
        assertEquals("修正しました。", NotificationContent.fromTail(listOf(assistant(text("修正しました。次はテストです。"))), TURN_FINISHED, null))
    }

    @Test
    fun aListItemOrABoldLabelLineEndsOrOpensASentence() {
        assertEquals("Added X", NotificationContent.fromTail(listOf(assistant(text("## Changes\\n- Added X\\n- Removed Y"))), TURN_FINISHED, null))
        assertEquals("I did two things:", NotificationContent.fromTail(listOf(assistant(text("I did two things:\\n1. Added X\\n2. Removed Y"))), TURN_FINISHED, null))
        assertEquals("Fixed it.", NotificationContent.fromTail(listOf(assistant(text("**Summary**\\n\\nFixed it."))), TURN_FINISHED, null))
        assertEquals("Summary", NotificationContent.fromTail(listOf(assistant(text("**Summary**\\n"))), TURN_FINISHED, null))
        // bold at the start of a sentence is not a label
        assertEquals("Important: run it.", NotificationContent.fromTail(listOf(assistant(text("**Important**: run it."))), TURN_FINISHED, null))
    }

    @Test
    fun aHeadingAloneIsUsedOnlyWhenNothingElseIsThere() {
        assertEquals("Done", NotificationContent.fromTail(listOf(assistant(text("## Done"))), TURN_FINISHED, null))
        assertEquals("Done", NotificationContent.fromTail(listOf(assistant(text("## Done\\n\\n### Next steps"))), TURN_FINISHED, null))
        assertEquals("All green.", NotificationContent.fromTail(listOf(assistant(text("# All green. Ship it."))), TURN_FINISHED, null))
        assertEquals("Ship it.", NotificationContent.fromTail(listOf(assistant(text("# All green\\n## Next\\nShip it."))), TURN_FINISHED, null))
    }

    @Test
    fun longTextIsCutAt120WithAnEllipsis() {
        val content = NotificationContent.fromTail(listOf(assistant(text("x".repeat(200)))), TURN_FINISHED, null)!!
        assertEquals("x".repeat(119) + "…", content)
        assertEquals(120, content.length)
    }

    @Test
    fun permissionShowsTheToolAndItsCommand() {
        assertEquals("Bash: ./gradlew test", permission(assistant(text("Running the tests.")), assistant(bash("./gradlew test\\n./gradlew verifyPlugin"))))
    }

    @Test
    fun permissionNamesTheOldestUnresolvedTool() {
        val content = permission(
            assistant(toolUse("Read", """{"file_path":"/w/acme/a.kt"}""", id = "r1")),
            assistant(bash("./deploy.sh", id = "b1")),
            assistant(toolUse("Read", """{"file_path":"/w/acme/b.kt"}""", id = "r2")),
            toolResult("r1"),
        )
        assertEquals("Bash: ./deploy.sh", content)
    }

    @Test
    fun aResolvedToolIsNotNamed() {
        assertNull(permission(assistant(bash("./deploy.sh", id = "b1")), toolResult("b1")))
        // an unresolved tool of an earlier turn is not the prompt either
        assertNull(permission(assistant(bash("./deploy.sh", id = "b1")), user("stop, do something else"), assistant(text("Sure."))))
    }

    @Test
    fun editShowsTheFileName() {
        val content = permission(assistant(toolUse("Edit", """{"file_path":"/w/acme/src/Foo.kt","old_string":"a","new_string":"b"}""")))!!
        assertEquals("Edit: Foo.kt", content)
        assertFalse(content.contains("/w/acme"))
        assertEquals("MultiEdit: Bar.kt", permission(assistant(toolUse("MultiEdit", """{"file_path":"/w/acme/src/Bar.kt","edits":[]}"""))))
    }

    @Test
    fun otherToolsShowTheirName() {
        assertEquals("TodoWrite", permission(assistant(toolUse("TodoWrite", """{"todos":[]}"""))))
        assertEquals("WebFetch: https://example.test/page", permission(assistant(toolUse("WebFetch", """{"url":"https://example.test/page","prompt":"read it"}"""))))
        assertEquals("Grep: TODO", permission(assistant(toolUse("Grep", """{"pattern":"TODO","path":"/w/acme"}"""))))
        assertEquals("Agent: Review the diff", permission(assistant(toolUse("Agent", """{"description":"Review the diff","prompt":"..."}"""))))
        assertEquals("Task: Scan the logs", permission(assistant(toolUse("Task", """{"description":"Scan the logs","prompt":"..."}"""))))
        // a known tool without the argument Skein expects falls back to its name
        assertEquals("Bash", permission(assistant(toolUse("Bash", """{"description":"run it"}"""))))
        assertEquals("Edit", permission(assistant(toolUse("Edit", """{"old_string":"a"}"""))))
    }

    private val ask = toolUse("AskUserQuestion", """{"questions":[{"question":"Which environment?","header":"Env","options":[{"label":"dev"},{"label":"prod"}]}]}""", id = "q1")

    @Test
    fun aQuestionShowsItsText() {
        val lines = listOf(assistant(text("One thing before I go on.")), assistant(ask))
        assertEquals("Which environment?", NotificationContent.fromTail(lines, NEEDS_YOU, QUESTION))
        // no question tool in the tail: the last answer stands in
        assertEquals("One thing before I go on.", NotificationContent.fromTail(lines.take(1), NEEDS_YOU, QUESTION))
        assertEquals("One thing before I go on.", NotificationContent.fromTail(lines.take(1), NEEDS_YOU, OTHER))
    }

    @Test
    fun anAnsweredQuestionIsNotShown() {
        val lines = listOf(assistant(text("One thing before I go on.")), assistant(ask), toolResult("q1"))
        assertEquals("One thing before I go on.", NotificationContent.fromTail(lines, NEEDS_YOU, QUESTION))
    }

    @Test
    fun nothingUsableGivesNull() {
        assertNull(NotificationContent.fromTail(emptyList(), TURN_FINISHED, null))
        assertNull(NotificationContent.fromTail(listOf(user("hello")), TURN_FINISHED, null))
        assertNull(NotificationContent.fromTail(listOf(assistant(text(""))), TURN_FINISHED, null))
        assertNull(permission(assistant(text("No tool was called."))))
        // a line cut at the end of the file is skipped, the entry before it still counts
        val cut = """{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"half"""
        assertEquals("Whole answer.", NotificationContent.fromTail(listOf(assistant(text("Whole answer.")), cut), TURN_FINISHED, null))
    }

    @Test
    fun workingShowsTheNewestRunningTool() {
        val lines = listOf(
            user("fix the chart"),
            assistant(text("Looking at it now.")),
            assistant(toolUse("Read", """{"file_path":"/w/acme/src/Chart.kt"}""", id = "r1")),
            assistant(bash("./gradlew test", id = "b1")),
        )
        assertEquals("Bash: ./gradlew test", NotificationContent.working(lines))
        // a call with its result is no longer running: the newest one still running is shown
        assertEquals("Read: Chart.kt", NotificationContent.working(lines + toolResult("b1")))
        // two calls in one entry: the later block is the newer
        val oneEntry = listOf(user("go on"), assistant(toolUse("Grep", """{"pattern":"TODO"}""", id = "g1"), bash("git status", id = "b2")))
        assertEquals("Bash: git status", NotificationContent.working(oneEntry))
    }

    @Test
    fun workingFallsBackToTheLastSentence() {
        val lines = listOf(
            user("fix the chart"),
            assistant(text("Looking at it now. One moment.")),
            assistant(bash("./gradlew test")),
            toolResult("toolu_01"),
            assistant(text("The build is green. Writing the notes.")),
        )
        assertEquals("The build is green.", NotificationContent.working(lines))
        // a call left without a result in an earlier turn is not running now
        assertEquals("Sure.", NotificationContent.working(listOf(assistant(bash("./deploy.sh", id = "b1")), user("stop, do something else"), assistant(text("Sure.")))))
        // nothing running and nothing said in this turn
        assertNull(NotificationContent.working(listOf(assistant(text("An older answer.")), user("now run it"))))
        assertNull(NotificationContent.working(emptyList()))
    }

    @Test
    fun contentAsAStringStillWorks() {
        val old = """{"type":"assistant","message":{"role":"assistant","content":"Plain answer. More follows."}}"""
        assertEquals("Plain answer.", NotificationContent.fromTail(listOf(old), TURN_FINISHED, null))
    }
}
