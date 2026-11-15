package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.HistoryEntry
import io.github.v0rest.skein.paths.PathStyle.WINDOWS
import io.github.v0rest.skein.ui.ThreadHere.Item
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The project-folder rules with Windows paths, on any OS. The IDE writes a folder as `C:/Users/skein/demo-shop`,
 * Claude Code's transcripts as `C:\Users\skein\demo-shop`, and the two are one folder.
 */
class FolderPathsWindowsTest {
    private val base = "C:/Users/skein/demo-shop"

    @Test
    fun choicesDropADuplicateWrittenAnotherWay() {
        val roots = listOf("c:/users/skein/DEMO-SHOP/", "C:/Users/skein/libs/core", "C:\\Users\\skein\\libs\\core")
        assertEquals(listOf(base, "C:/Users/skein/libs/core"), ContentRoots.choices(base, roots, null, WINDOWS))
    }

    @Test
    fun aTrailingSeparatorGoesButADriveRootKeepsIt() {
        assertEquals(listOf("C:/w"), ContentRoots.choices("C:/w/", emptyList(), null, WINDOWS))
        assertEquals(listOf("C:/"), ContentRoots.choices("C:/", emptyList(), null, WINDOWS))
    }

    @Test
    fun sourceSetRootsInsideTheBaseStayHiddenWhateverTheirCase() {
        val roots = listOf("C:/USERS/skein/demo-shop/src/main", "C:/Users/skein/demo-shop/api", "C:/Users/skein/demo-shop2")
        val folders = ContentRoots.startFolders(base, roots, { it.endsWith("/api") }, WINDOWS)
        assertEquals(listOf(base, "C:/Users/skein/demo-shop/api", "C:/Users/skein/demo-shop2"), folders)
    }

    @Test
    fun preselectedMatchesTheLastUsedFolderInAnyCase() {
        val choices = listOf(base, "C:/Users/skein/libs/core")
        assertEquals("C:/Users/skein/libs/core", ContentRoots.preselected(choices, "c:\\users\\skein\\libs\\CORE", WINDOWS))
        assertEquals(base, ContentRoots.preselected(choices, "C:/gone", WINDOWS))
    }

    @Test
    fun theLabelOfASubfolderUsesTheNativeSeparator() {
        assertEquals("web\\src", FolderLabel.of("src", "C:/Users/skein/demo-shop/web/src", base, WINDOWS))
        assertNull(FolderLabel.of("Fix login", "c:/users/skein/DEMO-SHOP", base, WINDOWS))
        assertNull(FolderLabel.of("Fix login", "C:/Users/skein/demo-shop2/docs", base, WINDOWS))
        assertNull(FolderLabel.of("docs", "C:/Users/skein/demo-shop/docs", base, WINDOWS))
    }

    @Test
    fun theProjectFoldersLabelTheirFolders() {
        val folders = ProjectFolders(base, listOf("C:/Users/skein/libs/core"), WINDOWS)
        assertEquals("demo-shop", folders.label(base))
        assertEquals("web\\src", folders.label("C:/Users/skein/demo-shop/web/src"))
        assertEquals("C:/Users/skein/libs/core", folders.label("C:/Users/skein/libs/core"))
        assertEquals(listOf(base, "C:/Users/skein/libs/core"), folders.all)
    }

    @Test
    fun theHistoryNamesNothingForTheBaseItself() {
        // the transcript's cwd has backslashes, the base has slashes
        val entry = HistoryEntry("s", "C:\\Users\\skein\\demo-shop", "t", 0L, null)
        assertNull(HistoryLabels.secondary(entry, base, { "folder" }, WINDOWS))
        val sub = entry.copy(cwd = "C:\\Users\\skein\\demo-shop\\docs")
        assertEquals("docs", HistoryLabels.secondary(sub, base, { it.substringAfterLast('\\') }, WINDOWS))
    }

    @Test
    fun newThreadHereKeepsTheIdesSpelling() {
        val projectFolders = listOf(base)
        val file = Item("C:/Users/skein/demo-shop/web/App.kt", isFolder = false)
        assertEquals("C:/Users/skein/demo-shop/web", ThreadHere.folderFor(listOf(file), projectFolders, base, WINDOWS))
        val folder = Item("C:/Users/skein/demo-shop/web/", isFolder = true)
        assertEquals("C:/Users/skein/demo-shop/web", ThreadHere.folderFor(listOf(file, folder), projectFolders, base, WINDOWS))
    }

    @Test
    fun newThreadHereRefusesOutsideAndLookAlikeFolders() {
        val projectFolders = listOf(base)
        for (path in listOf("C:/Users/skein/demo-shop2/a.txt", "D:/Users/skein/demo-shop/a.txt", "C:/Users/skein/a.txt", "C:/Users/skein/demo-shop/../x/a.txt")) {
            assertNull(path, ThreadHere.folderFor(listOf(Item(path, isFolder = false)), projectFolders, base, WINDOWS))
        }
    }

    @Test
    fun aFolderInAWorktreeHasNoneUnlessTheProjectIsThatWorktree() {
        val worktree = "C:/Users/skein/demo-shop/.claude/worktrees/task-1"
        val src = Item("$worktree/src", isFolder = true)
        assertNull(ThreadHere.folderFor(listOf(src), listOf(base), base, WINDOWS))
        assertEquals("$worktree/src", ThreadHere.folderFor(listOf(src), listOf(worktree), worktree, WINDOWS))
    }

    /** Hiding a root or offering a folder lets Skein act, so a path only the disk could place counts as outside. */
    @Test
    fun aDoubtfulRootStaysOnOfferAndADoubtfulFolderIsRefused() {
        val dotDot = "C:/Users/skein/demo-shop/api/../web"
        val dotted = "C:/Users/skein/demo-shop/web."
        assertEquals(listOf(base, dotDot, dotted), ContentRoots.startFolders(base, listOf(dotDot, dotted), { false }, WINDOWS))
        assertNull(ThreadHere.folderFor(listOf(Item("$dotDot/a.txt", isFolder = false)), listOf(base), base, WINDOWS))
        assertNull(ThreadHere.folderFor(listOf(Item(dotted, isFolder = true)), listOf(base), base, WINDOWS))
    }
}
