package io.github.v0rest.skein.threads

import io.github.v0rest.skein.git.WorktreeState
import org.junit.Assert.assertEquals
import org.junit.Test

class WorktreeCloseTest {
    @Test
    fun aGoneWorktreeNeedsNoQuestion() {
        assertEquals(CloseOffer.Nothing, WorktreeClose.offer(exists = false, liveInside = false, state = null))
        assertEquals(
            CloseOffer.Nothing,
            WorktreeClose.offer(exists = false, liveInside = true, state = WorktreeState(1, 1), otherThread = true, unsavedEdits = true),
        )
    }

    @Test
    fun removeIsNotOfferedWhileASessionRunsInside() {
        assertEquals(
            CloseOffer.KeepOnly("another session is running in this worktree"),
            WorktreeClose.offer(exists = true, liveInside = true, state = WorktreeState(0, 0)),
        )
    }

    @Test
    fun anotherSkeinThreadInTheWorktreeIsSaidSo() {
        assertEquals(
            CloseOffer.KeepOnly("another Skein thread uses this worktree"),
            WorktreeClose.offer(exists = true, liveInside = false, state = WorktreeState(0, 0), otherThread = true),
        )
    }

    /** Edits that live only in an editor are invisible to git, so they are the first thing that stops a remove. */
    @Test
    fun unsavedEditorChangesComeFirst() {
        assertEquals(
            CloseOffer.KeepOnly("unsaved changes in the editor"),
            WorktreeClose.offer(exists = true, liveInside = true, state = WorktreeState(0, 0), otherThread = true, unsavedEdits = true),
        )
    }

    @Test
    fun aCleanWorktreeOffersRemove() {
        assertEquals(CloseOffer.KeepOrRemove(0, 0), WorktreeClose.offer(exists = true, liveInside = false, state = WorktreeState(0, 0)))
    }

    /** A worktree inside this one is invisible to git status here, and git worktree remove would delete it too. */
    @Test
    fun aWorktreeInsideKeepsThisOne() {
        assertEquals(
            CloseOffer.KeepOnly("another worktree lies inside this one"),
            WorktreeClose.offer(exists = true, liveInside = false, state = WorktreeState(0, 0), nestedWorktree = true),
        )
        assertEquals(
            CloseOffer.KeepOnly("another worktree lies inside this one"),
            WorktreeClose.offer(exists = true, liveInside = false, state = null, nestedWorktree = true),
        )
    }

    @Test
    fun detachedCommitsNothingElseHoldsKeepTheWorktree() {
        assertEquals(
            CloseOffer.KeepOnly("commits that no branch holds"),
            WorktreeClose.offer(exists = true, liveInside = false, state = WorktreeState(0, 0), detachedCommits = 1),
        )
        assertEquals(CloseOffer.KeepOrRemove(0, 3), WorktreeClose.offer(exists = true, liveInside = false, state = WorktreeState(0, 3), detachedCommits = 0))
    }

    @Test
    fun aGitFailureOffersKeepOnly() {
        assertEquals(CloseOffer.KeepOnly("git could not read the worktree"), WorktreeClose.offer(exists = true, liveInside = false, state = null))
    }

    /** A repository of its own inside, ignored or not, or a look that could not tell, leaves only Keep. */
    @Test
    fun aRepositoryInsideLeavesOnlyKeep() {
        val inner = WorktreeClose.innerRepositories(listOf("vendor/lib/", "tools/x/"), "/w/acme/.claude/worktrees/acme")
        assertEquals(
            "repositories of their own lie inside it, and removing it would delete them with their commits: vendor/lib/, tools/x/. " +
                "Move or delete those folders first, or remove the worktree with git by hand: git worktree remove /w/acme/.claude/worktrees/acme",
            inner,
        )
        assertEquals(CloseOffer.KeepOnly(inner), WorktreeClose.offer(exists = true, liveInside = false, state = WorktreeState(0, 0), inner = inner))
        val unknown = WorktreeClose.innerUnknown("git did not finish in time", "/w/acme/.claude/worktrees/acme")
        assertEquals(CloseOffer.KeepOnly(unknown), WorktreeClose.offer(exists = true, liveInside = false, state = WorktreeState(0, 0), inner = unknown))
    }

    /** The reason names at most five folders, and says what the owner can do instead. */
    @Test
    fun theKeepReasonListsFiveFoldersAndWhatToDo() {
        val paths = listOf("a/", "b/", "c/", "d/", "e/", "f/", "g/")
        assertEquals(
            "repositories of their own lie inside it, and removing it would delete them with their commits: a/, b/, c/, d/, e/ and 2 more. " +
                "Move or delete those folders first, or remove the worktree with git by hand: git worktree remove /w/acme/.claude/worktrees/acme",
            WorktreeClose.innerRepositories(paths, "/w/acme/.claude/worktrees/acme"),
        )
        assertEquals(
            "git could not say whether a repository of its own lies inside it: git did not finish in time. " +
                "Check it for one yourself, then remove the worktree with git by hand: git worktree remove /w/acme/.claude/worktrees/acme",
            WorktreeClose.innerUnknown("git did not finish in time", "/w/acme/.claude/worktrees/acme"),
        )
    }
}
