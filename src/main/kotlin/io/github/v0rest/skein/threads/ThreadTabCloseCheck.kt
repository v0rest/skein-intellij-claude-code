package io.github.v0rest.skein.threads

import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFilePreCloseCheck
import io.github.v0rest.skein.terminal.CloseGate
import io.github.v0rest.skein.terminal.TerminalAdapter
import kotlinx.coroutines.CancellationException

/**
 * Keeps Skein thread editor tabs open when the user closes them (a ×, Close, Close All, Close Others) while one of them
 * still runs its shell, until Skein has ended those threads and closes the tabs itself, in one batch
 * ([TerminalAdapter.allowsClose], [CloseGate]): closed at once, a tab would leave its shell and claude hanging with a
 * dead terminal.
 *
 * The IDE (2026.3, javap on 2026-11-09) asks these checks only from `FileEditorManagerImpl.closeFileWithChecks` and
 * `closeFilesWithChecks`. Those are called by the user's closes (the tab's close button, the Close and Close All family
 * of actions, the tab's own close shortcut, the Switcher) and by Skein's own close after the ending, which this check
 * lets through because Skein released the tab first. Moving a tab to another split or window, closing the project and
 * quitting the IDE close files without asking, so they never reach this check; it passes anyway once the project closes
 * or the IDE quits. `VirtualFilePreCloseCheck` is `@ApiStatus.Experimental`; its `filterFilesToClose` is
 * `@ApiStatus.Internal` and not implemented here.
 *
 * Application-level, so it sees every file any project closes: anything that is not a thread tab passes at once, and
 * any failure lets the close through.
 */
class ThreadTabCloseCheck : VirtualFilePreCloseCheck {
    override fun canCloseFile(file: VirtualFile): Boolean = canCloseFiles(listOf(file))

    /**
     * The IDE asks about a whole close in one call (its default `filterFilesToClose` comes here), and the platform's
     * default would stop at the first file it may not close: Skein looks at all the thread tabs of the close together,
     * so one close ends every running thread among them and closes all those tabs in one batch. When it says no, the
     * IDE keeps every file of that close open, as it does for any check that says no to one of them; with one editor
     * window Skein then closes the other files of that close itself, right away ([TerminalAdapter.allowsClose]), so a
     * Close All still closes everything in one click. Those closes come back here with no thread tab, and pass.
     */
    override fun canCloseFiles(files: Collection<VirtualFile>): Boolean {
        if (files.none { TerminalAdapter.threadIdOf(it) != null }) return true
        return try {
            // each project looks at its own thread tabs only, and every one of them is asked; the whole close goes along,
            // for the files a project closes itself after it said no
            CloseGate.askAll(ProjectManager.getInstance().openProjects.toList()) { project ->
                project.isDisposed || project.getServiceIfCreated(ThreadService::class.java)?.allowsTabClose(files) ?: true
            }
        } catch (e: Throwable) {
            if (e is CancellationException || e is ControlFlowException) throw e
            LOG.warn("Skein could not check its thread tabs before they closed; they close at once", e)
            true
        }
    }

    private companion object {
        private val LOG = logger<ThreadTabCloseCheck>()
    }
}
