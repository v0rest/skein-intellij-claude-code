package io.github.v0rest.skein.ui

import io.github.v0rest.skein.git.BaseObject
import io.github.v0rest.skein.git.ChangeKind
import io.github.v0rest.skein.git.ChangedFile
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** [ShowChanges]: how each file of a worktree's Show Changes is shown, and the words around it. */
class ShowChangesTest {
    private val blob = BaseObject("78981922613b2afb6025042ff6bd878ac1994e85", "100644")
    private val modified = ChangedFile(ChangeKind.MODIFIED, "src/a.c", base = blob)
    private val small = DiskSide.File(100)

    private fun show(base: BaseView, worktree: WorktreeView) = DiffPlan.Show(base, worktree)

    @Test
    fun aChangedFileShowsBothSides() {
        assertEquals(show(BaseView.BYTES, WorktreeView.FILE), ShowChanges.plan(modified, small, limit = 1000))
    }

    @Test
    fun anAddedOrUntrackedFileHasAnEmptyBaseSide() {
        for (kind in listOf(ChangeKind.ADDED, ChangeKind.UNTRACKED)) {
            assertEquals(show(BaseView.NONE, WorktreeView.FILE), ShowChanges.plan(ChangedFile(kind, "x.c"), small, limit = 1000))
        }
    }

    @Test
    fun aDeletedFileHasAnEmptyWorktreeSide() {
        val deleted = ChangedFile(ChangeKind.DELETED, "x.c", base = blob)
        assertEquals(show(BaseView.BYTES, WorktreeView.NONE), ShowChanges.plan(deleted, DiskSide.Missing, limit = 1000))
    }

    /** A link is compared by where it points, as git stores it, on either side. */
    @Test
    fun aLinkShowsWhereItPoints() {
        val link = ChangedFile(ChangeKind.MODIFIED, "l", base = BaseObject(blob.id, BaseObject.LINK))
        assertEquals(show(BaseView.LINK, WorktreeView.LINK), ShowChanges.plan(link, DiskSide.Link("a.c"), limit = 1000))
        assertEquals(show(BaseView.BYTES, WorktreeView.LINK), ShowChanges.plan(modified, DiskSide.Link("a.c"), limit = 1000))
    }

    @Test
    fun aSubmoduleAFolderOrASpecialFileIsANote() {
        val submodule = ChangedFile(ChangeKind.MODIFIED, "vendor/lib", base = BaseObject(blob.id, BaseObject.SUBMODULE))
        assertEquals(DiffPlan.Note("vendor/lib is a submodule, and Show Changes compares files only."), ShowChanges.plan(submodule, DiskSide.Folder, 1000))
        assertEquals(
            DiffPlan.Note("nested/ is a folder in the worktree, a repository of its own say, and Show Changes compares files only."),
            ShowChanges.plan(ChangedFile(ChangeKind.UNTRACKED, "nested/"), DiskSide.Folder, 1000),
        )
        assertEquals(
            DiffPlan.Note("src/a.c is not a regular file in the worktree, and Show Changes compares files only."),
            ShowChanges.plan(modified, DiskSide.Special, 1000),
        )
    }

    @Test
    fun aFileOverTheLimitIsANoteAndNeverRead() {
        assertEquals(
            DiffPlan.Note("src/a.c is 12.5 MB in the worktree, more than the 10 MB Show Changes reads."),
            ShowChanges.plan(modified, DiskSide.File(13_107_200), limit = 10L * 1024 * 1024),
        )
        assertEquals(show(BaseView.BYTES, WorktreeView.FILE), ShowChanges.plan(modified, DiskSide.File(1000), limit = 1000))
        assertEquals("src/a.c is 2 KB at the base, more than the 1000 bytes Show Changes reads.", ShowChanges.tooLargeAtBase(modified, 2048, 1000))
    }

    /**
     * A deleted file shows its base against an empty side, whatever lies at its path now: a folder or a link made in its
     * place, or a file reached through a link to somewhere outside the worktree, is never read as its worktree side.
     */
    @Test
    fun aDeletionShowsItsBaseWhateverIsOnDiskNow() {
        val deleted = ChangedFile(ChangeKind.DELETED, "gone.c", base = blob)
        for (disk in listOf(DiskSide.Missing, DiskSide.Folder, DiskSide.File(100), DiskSide.Link("elsewhere"), DiskSide.Special, DiskSide.Outside)) {
            assertEquals(disk.toString(), show(BaseView.BYTES, WorktreeView.NONE), ShowChanges.plan(deleted, disk, limit = 1000))
        }
        val link = ChangedFile(ChangeKind.DELETED, "l", base = BaseObject(blob.id, BaseObject.LINK))
        assertEquals(show(BaseView.LINK, WorktreeView.NONE), ShowChanges.plan(link, DiskSide.Folder, limit = 1000))
    }

    @Test
    fun aPathBehindALinkIsANoteAndNeverRead() {
        assertEquals(
            DiffPlan.Note("src/a.c lies behind a link in the worktree, and Show Changes reads nothing outside the worktree."),
            ShowChanges.plan(modified, DiskSide.Outside, limit = 1000),
        )
    }

    /** The list was made a moment ago; a file added then and gone now has nothing on either side. */
    @Test
    fun aFileGoneSinceTheListIsANote() {
        assertEquals(
            DiffPlan.Note("x.c is no longer in the worktree."),
            ShowChanges.plan(ChangedFile(ChangeKind.UNTRACKED, "x.c"), DiskSide.Missing, 1000),
        )
        // a changed file deleted since then is a deletion now, which the diff shows as it is
        assertEquals(show(BaseView.BYTES, WorktreeView.NONE), ShowChanges.plan(modified, DiskSide.Missing, 1000))
    }

    @Test
    fun sizesReadAsAPersonSaysThem() {
        assertEquals("512 bytes", ShowChanges.size(512))
        assertEquals("2 KB", ShowChanges.size(2048))
        assertEquals("10 MB", ShowChanges.size(10L * 1024 * 1024))
        assertEquals("1.5 MB", ShowChanges.size(1536L * 1024))
    }

    @Test
    fun theSidesSayWhatTheyAre() {
        val mergeBase = "7ee31195368ab795369f35d97eaff4ac19a71233"
        assertEquals("main at 7ee3119", ShowChanges.baseTitle("main", mergeBase, modified))
        assertEquals(
            "origin/main at 7ee3119, src/old.c",
            ShowChanges.baseTitle("origin/main", mergeBase, ChangedFile(ChangeKind.RENAMED, "src/new.c", "src/old.c", blob)),
        )
        assertEquals("worktree task-1", ShowChanges.worktreeTitle("task-1"))
    }

    @Test
    fun theBalloonsSayWhy() {
        assertEquals(
            "Skein cannot show the changes of the worktree task-1: it has no commit in common with main",
            ShowChanges.refused("task-1", "it has no commit in common with main"),
        )
        assertEquals("The worktree task-1 has no changes against main.", ShowChanges.noChanges("task-1", "main"))
    }

    private fun thread(id: String, worktree: String?) = SkeinThread(id, "/w/acme", "s-$id", id, ThreadState.Idle, 0, 0, null, worktree = worktree)

    /** The thread menu offers it for one worktree thread; for anything else it is not there. */
    @Test
    fun theThreadMenuOffersItForOneWorktreeThread() {
        val wt = thread("t1", "task-1")
        assertEquals(wt, ShowChanges.worktreeThread(listOf(wt)))
        assertNull(ShowChanges.worktreeThread(listOf(thread("t2", null))))
        assertNull(ShowChanges.worktreeThread(listOf(wt, thread("t3", "task-3"))))
        assertNull(ShowChanges.worktreeThread(emptyList()))
        assertNull("a name Claude Code would not take", ShowChanges.worktreeThread(listOf(thread("t4", "../x"))))
    }
}
