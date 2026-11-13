package io.github.v0rest.skein.ui

import io.github.v0rest.skein.ui.ThreadHere.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Where New Thread Here starts a thread for what is selected in the Project view or an editor tab. */
class ThreadHereTest {
    /** The project's base folder, and its folders as [ProjectFolders.all] gives them, the base and then a content root outside it. */
    private val base = "/w/acme"
    private val folders = listOf("/w/acme", "/w/other")

    private fun file(path: String) = Item(path, isFolder = false)

    private fun folder(path: String) = Item(path, isFolder = true)

    @Test
    fun aFileStartsInItsFolder() = assertEquals("/w/acme/docs", ThreadHere.folderFor(listOf(file("/w/acme/docs/guide.md")), folders, base))

    @Test
    fun aFolderStartsInItself() = assertEquals("/w/acme/web/src", ThreadHere.folderFor(listOf(folder("/w/acme/web/src")), folders, base))

    @Test
    fun theBaseFolderAndAFileAtItsTopStartInTheBase() {
        assertEquals("/w/acme", ThreadHere.folderFor(listOf(folder("/w/acme")), folders, base))
        assertEquals("/w/acme", ThreadHere.folderFor(listOf(file("/w/acme/README.md")), folders, base))
    }

    @Test
    fun aRootOutsideTheBaseCountsToo() = assertEquals("/w/other/docs", ThreadHere.folderFor(listOf(folder("/w/other/docs")), folders, base))

    @Test
    fun outsideTheProjectThereIsNone() {
        assertNull(ThreadHere.folderFor(listOf(folder("/w/zeta")), folders, base))
        assertNull(ThreadHere.folderFor(listOf(file("/w/zeta/notes.txt")), folders, base))
        // the folder above the project is not in it
        assertNull(ThreadHere.folderFor(listOf(folder("/w")), folders, base))
        assertNull(ThreadHere.folderFor(listOf(file("/w/acme.txt")), folders, base))
    }

    @Test
    fun aSiblingWithTheSamePrefixIsOutside() {
        assertNull(ThreadHere.folderFor(listOf(folder("/w/acme-old")), folders, base))
        assertNull(ThreadHere.folderFor(listOf(file("/w/acme-old/a.txt")), folders, base))
    }

    @Test
    fun aScratchFileIsOutside() =
        assertNull(ThreadHere.folderFor(listOf(file("/cfg/JetBrains/IntelliJIdea2026.3/scratches/scratch_1.kts")), folders, base))

    @Test
    fun aFileOffTheLocalDiskHasNone() {
        // a class inside a library jar, a file of another file system
        assertNull(ThreadHere.folderFor(listOf(Item("/w/acme/lib/demo.jar!/io/acme/Demo.class", isFolder = false, isLocal = false)), folders, base))
        assertNull(ThreadHere.folderFor(listOf(Item("/w/acme/lib/demo.jar!/io/acme", isFolder = true, isLocal = false)), folders, base))
    }

    @Test
    fun aWorktreeFolderHasNone() {
        // a worktree has its own way in, the Worktrees section's Open, and a plain thread there would not be one
        assertNull(ThreadHere.folderFor(listOf(folder("/w/acme/.claude/worktrees/task-1/src")), folders, base))
        assertNull(ThreadHere.folderFor(listOf(folder("/w/acme/.claude/worktrees/task-1")), folders, base))
        assertNull(ThreadHere.folderFor(listOf(file("/w/acme/.claude/worktrees/task-1/src/App.kt")), folders, base))
    }

    @Test
    fun aWorktreeUnderARootOutsideTheBaseHasNoneEither() =
        assertNull(ThreadHere.folderFor(listOf(folder("/w/other/.claude/worktrees/task-2")), folders, base))

    /** A worktree opened as a project of its own is the project folder there, and the action shows as anywhere else. */
    @Test
    fun aProjectThatIsAWorktreeShowsItEverywhere() {
        val worktree = "/w/acme/.claude/worktrees/task-1"
        val own = listOf(worktree)
        assertEquals(worktree, ThreadHere.folderFor(listOf(folder(worktree)), own, worktree))
        assertEquals("$worktree/src", ThreadHere.folderFor(listOf(folder("$worktree/src")), own, worktree))
        assertEquals("$worktree/docs", ThreadHere.folderFor(listOf(file("$worktree/docs/guide.md")), own, worktree))
        // a project folder further down in the worktree counts the same
        assertEquals("$worktree/app/src", ThreadHere.folderFor(listOf(folder("$worktree/app/src")), listOf("$worktree/app"), "$worktree/app"))
    }

    @Test
    fun aWorktreeInsideTheWorktreeProjectHasNone() {
        // the project is worktree task-1, and Claude Code made another worktree inside it
        val worktree = "/w/acme/.claude/worktrees/task-1"
        assertNull(ThreadHere.folderFor(listOf(folder("$worktree/.claude/worktrees/task-9")), listOf(worktree), worktree))
    }

    @Test
    fun severalItemsOfOneFolderStartOneThreadThere() {
        assertEquals("/w/acme/docs", ThreadHere.folderFor(listOf(file("/w/acme/docs/a.md"), file("/w/acme/docs/b.md")), folders, base))
        // a folder and a file right inside it lead to the same folder
        assertEquals("/w/acme/docs", ThreadHere.folderFor(listOf(folder("/w/acme/docs"), file("/w/acme/docs/a.md")), folders, base))
    }

    @Test
    fun severalItemsOfDifferentFoldersHaveNone() {
        assertNull(ThreadHere.folderFor(listOf(file("/w/acme/docs/a.md"), file("/w/acme/web/b.md")), folders, base))
        // a folder and its parent are two different folders too
        assertNull(ThreadHere.folderFor(listOf(folder("/w/acme/docs"), folder("/w/acme")), folders, base))
        assertNull(ThreadHere.folderFor(listOf(file("/w/acme/docs/a.md"), Item("/w/acme/lib/demo.jar!/A.class", isFolder = false, isLocal = false)), folders, base))
    }

    @Test
    fun nothingSelectedHasNone() = assertNull(ThreadHere.folderFor(emptyList(), folders, base))

    @Test
    fun aProjectWithoutFoldersHasNone() = assertNull(ThreadHere.folderFor(listOf(folder("/w/acme")), emptyList(), null))

    @Test
    fun trailingSlashesDoNotMatter() {
        assertEquals("/w/acme/docs", ThreadHere.folderFor(listOf(folder("/w/acme/docs/")), listOf("/w/acme/"), "/w/acme/"))
        assertEquals("/w/acme", ThreadHere.folderFor(listOf(folder("/w/acme/")), listOf("/w/acme"), "/w/acme"))
    }
}
