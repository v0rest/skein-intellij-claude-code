package io.github.v0rest.skein.ui

import io.github.v0rest.skein.git.Changes
import io.github.v0rest.skein.git.Operation
import io.github.v0rest.skein.git.WorktreeCounts
import io.github.v0rest.skein.threads.KeptKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WorktreeBadgeTest {
    private fun counted(changed: Int = 0, ahead: Int? = 0, base: String? = "main", detached: Boolean = false) =
        WorktreeCounts.Counted(Changes.of((1..changed).map { "f$it.kt" }), detached, base, ahead)

    @Test
    fun dirtyAndAheadShowsBothWithTheChangesInAmber() {
        assertEquals(Badge("✎3", "↑2", Badge.Look.AMBER), WorktreeBadge.of(counted(changed = 3, ahead = 2)))
        assertEquals(Badge("✎1", null, Badge.Look.AMBER), WorktreeBadge.of(counted(changed = 1)))
        assertEquals(Badge(null, "↑4", Badge.Look.GREY), WorktreeBadge.of(counted(ahead = 4)))
    }

    @Test
    fun cleanAndLevelShowsNothing() {
        assertNull(WorktreeBadge.of(counted()))
        assertNull(WorktreeBadge.of(WorktreeCounts.NotShown))
        assertNull(WorktreeBadge.of(null))
    }

    @Test
    fun anOperationInProgressShowsItsWordInsteadOfTheNumbers() {
        assertEquals(Badge("rebase", null, Badge.Look.AMBER), WorktreeBadge.of(WorktreeCounts.InProgress(Operation.REBASE, Changes.of(listOf("a.kt")))))
        assertEquals(Badge("merge", null, Badge.Look.AMBER), WorktreeBadge.of(WorktreeCounts.InProgress(Operation.MERGE, Changes.NONE)))
        assertEquals(Badge("pick", null, Badge.Look.AMBER), WorktreeBadge.of(WorktreeCounts.InProgress(Operation.CHERRY_PICK, Changes.NONE)))
    }

    @Test
    fun aDetachedHeadOrNoBaseShowsTheChangesOnly() {
        assertEquals(Badge("✎2", null, Badge.Look.AMBER), WorktreeBadge.of(counted(changed = 2, ahead = null, base = null, detached = true)))
        assertEquals(Badge("✎2", null, Badge.Look.AMBER), WorktreeBadge.of(counted(changed = 2, ahead = null, base = null)))
        assertNull(WorktreeBadge.of(counted(ahead = null, base = null, detached = true)))
    }

    /** git that failed or timed out is a grey question mark with its reason, never zeros. */
    @Test
    fun aFailureIsAGreyQuestionMarkWithTheReason() {
        assertEquals(Badge("?", null, Badge.Look.GREY), WorktreeBadge.of(WorktreeCounts.Failed("git did not finish in time")))
        assertEquals(listOf("git could not read the worktree: git did not finish in time"), WorktreeBadge.tooltip(WorktreeCounts.Failed("git did not finish in time")))
    }

    @Test
    fun theTooltipListsUpToTenPathsThenHowManyMore() {
        val lines = WorktreeBadge.tooltip(counted(changed = 13, ahead = 2))
        assertEquals((1..10).map { "$it. f$it.kt" } + listOf("… and 3 more", "2 commits not in main"), lines)
    }

    @Test
    fun theTooltipSaysHowTheBranchStands() {
        assertEquals(listOf("Clean, level with main"), WorktreeBadge.tooltip(counted()))
        assertEquals(listOf("1. f1.kt", "Level with origin/main"), WorktreeBadge.tooltip(counted(changed = 1, base = "origin/main")))
        assertEquals(listOf("1 commit not in main"), WorktreeBadge.tooltip(counted(ahead = 1)))
        assertEquals(listOf("1. f1.kt", "Detached HEAD, on no branch"), WorktreeBadge.tooltip(counted(changed = 1, ahead = null, base = null, detached = true)))
        assertEquals(listOf("Clean, detached HEAD"), WorktreeBadge.tooltip(counted(ahead = null, base = null, detached = true)))
        assertEquals(listOf("Clean, no base branch to compare with"), WorktreeBadge.tooltip(counted(ahead = null, base = null)))
        assertEquals(listOf("A rebase is in progress", "1. a.kt"), WorktreeBadge.tooltip(WorktreeCounts.InProgress(Operation.REBASE, Changes.of(listOf("a.kt")))))
        assertEquals(emptyList<String>(), WorktreeBadge.tooltip(WorktreeCounts.NotShown))
    }

    private val nbsp = "\u00A0"

    /** A long line wraps after a `/` or at a space, the continuation indented; Swing's HTML itself never breaks a path. */
    @Test
    fun aLongLineWrapsAfterASlashOrAtASpace() {
        assertEquals(listOf("1. short.kt"), WorktreeBadge.wrap("1. short.kt", max = 20, indent = 3))
        assertEquals(
            listOf("1. docs/very/long/", "$nbsp$nbsp${nbsp}path/name.md"),
            WorktreeBadge.wrap("1. docs/very/long/path/name.md", max = 20, indent = 3),
        )
        assertEquals(listOf("A worktree git links", "to another path."), WorktreeBadge.wrap("A worktree git links to another path.", max = 20, indent = 0))
        // a name with no break in it is cut where it must be
        assertEquals(listOf("abcdefghij", "${nbsp}klmnopqrs", "${nbsp}tuvwxyz"), WorktreeBadge.wrap("abcdefghijklmnopqrstuvwxyz", max = 10, indent = 1))
    }

    @Test
    fun theTooltipNumbersThePathsAndWrapsTheLongOnesUnderTheirNumber() {
        val long = "docs/" + "a".repeat(30) + "/" + "b".repeat(30) + ".md"
        val counts = WorktreeCounts.Counted(Changes.of(listOf("x.kt", long)), detached = false, base = "main", ahead = 1)
        val lines = WorktreeBadge.tooltip(counts)
        assertEquals("1. x.kt", lines[0])
        assertEquals("2. docs/" + "a".repeat(30) + "/", lines[1])
        assertEquals("$nbsp$nbsp$nbsp" + "b".repeat(30) + ".md", lines[2])
        assertEquals("1 commit not in main", lines.last())
        assertTrue(lines.all { it.length <= WorktreeBadge.MAX_CHARS })
    }

    /** The section row names its folder from the project's base, and the whole path only for one outside the project. */
    @Test
    fun theFolderIsNamedFromTheProjectBase() {
        assertEquals(".claude/worktrees/task-old", WorktreeBadge.folder("/w/acme", "task-old", "/private/w/acme/.claude/worktrees/task-old", "/w/acme"))
        assertEquals(".claude/worktrees/task-old", WorktreeBadge.folder("/w/acme/", "task-old", "/w/acme/.claude/worktrees/task-old", "/w/acme/"))
        assertEquals("libs/demo-shop/.claude/worktrees/task-1", WorktreeBadge.folder("/w/acme/libs/demo-shop", "task-1", "/w/acme/libs/demo-shop/.claude/worktrees/task-1", "/w/acme"))
        assertEquals("/elsewhere/shop/.claude/worktrees/task-1", WorktreeBadge.folder("/elsewhere/shop", "task-1", "/elsewhere/shop/.claude/worktrees/task-1", "/w/acme"))
        assertEquals("/w/acme-old/.claude/worktrees/task-1", WorktreeBadge.folder("/w/acme-old", "task-1", "/w/acme-old/.claude/worktrees/task-1", "/w/acme"))
        assertEquals("/w/acme/.claude/worktrees/task-1", WorktreeBadge.folder("/w/acme", "task-1", "/w/acme/.claude/worktrees/task-1", null))
    }

    /** The tooltip is as wide as its longest line, and given a body width only when a line had to wrap. */
    @Test
    fun aTooltipThatWrapsGetsABodyWidth() {
        assertEquals("<html>.claude/worktrees/task-1<br>1. a.kt<br>1 commit not in main", WorktreeBadge.sectionTooltip(".claude/worktrees/task-1", emptyList(), listOf("1. a.kt", "1 commit not in main"), widthPx = 420))
        val wrapped = WorktreeBadge.sectionTooltip(".claude/worktrees/task-1", listOf("x".repeat(70)), emptyList(), widthPx = 420)
        assertTrue(wrapped, wrapped.startsWith("<html><body style=\"width: 420px\">.claude/worktrees/task-1<br>"))
        assertEquals("<html>&lt;b&gt;", WorktreeBadge.sectionTooltip("<b>", emptyList(), emptyList(), widthPx = 420))
    }

    @Test
    fun aScreenReaderHearsTheCountsInWords() {
        assertEquals("3 changed files, 1 commit not in main", WorktreeBadge.spoken(counted(changed = 3, ahead = 1)))
        assertNull(WorktreeBadge.spoken(counted()))
        assertEquals("A merge is in progress", WorktreeBadge.spoken(WorktreeCounts.InProgress(Operation.MERGE, Changes.NONE)))
    }

    @Test
    fun aSectionRowSaysWhatTheFolderIs() {
        assertEquals("worktree-acme", WorktreeBadge.kind(KeptKind.Registered("worktree-acme", locked = false)))
        assertEquals("detached HEAD · locked", WorktreeBadge.kind(KeptKind.Registered(null, locked = true)))
        assertEquals("not a git worktree", WorktreeBadge.kind(KeptKind.Unregistered))
        assertEquals("git could not list the worktrees", WorktreeBadge.kind(KeptKind.Unknown("fatal")))
    }

    @Test
    fun aFolderWithAGitOfItsOwnSaysWhatItIsAndHowToRepairIt() {
        assertEquals("worktree git links elsewhere", WorktreeBadge.kind(KeptKind.Unlinked(ownRepository = false)))
        assertEquals("a repository of its own", WorktreeBadge.kind(KeptKind.Unlinked(ownRepository = true)))
        val hint = WorktreeBadge.hint(KeptKind.Unlinked(ownRepository = false), "/w/acme/.claude/worktrees/acme")
        assertEquals("To link it here again, run git worktree repair /w/acme/.claude/worktrees/acme in the repository.", hint.last())
        assertEquals(emptyList<String>(), WorktreeBadge.hint(KeptKind.Unregistered, "/w/acme/.claude/worktrees/acme"))
    }
}
