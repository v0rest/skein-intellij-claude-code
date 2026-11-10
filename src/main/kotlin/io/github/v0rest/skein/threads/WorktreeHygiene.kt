package io.github.v0rest.skein.threads

import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.openapi.application.readAction
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.Project
import io.github.v0rest.skein.git.GitToplevel
import io.github.v0rest.skein.git.NoOptionalLocks
import io.github.v0rest.skein.git.ProcessGitRunner
import io.github.v0rest.skein.git.WorktreeGit
import io.github.v0rest.skein.git.WorktreeReader
import io.github.v0rest.skein.ui.ProjectFolders
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * The counts on the worktree thread rows and the Worktrees section ([WorktreeSweep]), looked at again, off the EDT and
 * one look at a time ([SweepGate], [GAP_MILLIS] from the end of one look to the start of the next), when a worktree
 * thread finishes a turn or comes or goes ([worktreeSweepTriggers]), when the Skein panel or the section comes into
 * view, at the minute check ([ThreadService]), and at once after Skein's own Open or Remove ([requestNow]). No VCS
 * listener and no file watcher: while no Skein panel of the project shows, nothing runs, and in a project the owner
 * has not trusted nothing runs either, since `git status` can start programs a repository's configuration names (the
 * owner's own Open or Remove still runs git). The section's rows get their counts only while it is open; closed, its
 * header needs only the folders. git runs with [GIT_TIMEOUT_MILLIS] per call, without optional locks
 * ([NoOptionalLocks]) and with `core.fsmonitor` off for `git status`. [state] changes only when what it holds changes,
 * so the rows repaint only then.
 */
@Service(Service.Level.PROJECT)
class WorktreeHygiene(private val project: Project, private val scope: CoroutineScope) {
    private val gate = SweepGate(GAP_MILLIS)
    private val results = MutableStateFlow(WorktreeSweep.Result.EMPTY)

    /** What the last look found. */
    val state: StateFlow<WorktreeSweep.Result> = results.asStateFlow()

    /** A Skein panel of the project is showing; looks run only then. */
    @Volatile
    private var shown = false

    /** The Worktrees section is open, so its rows need their counts. */
    @Volatile
    private var sectionOpen = false

    /** Set once a look failed, so a broken one does not fill the log; touched only by the one loop. */
    private var failed = false

    private val sweep: WorktreeSweep by lazy {
        val git = NoOptionalLocks(ProcessGitRunner(GIT_TIMEOUT_MILLIS))
        WorktreeSweep(WorktreeGit(git), WorktreeReader(git), WorktreeSweep.RealDisk)
    }

    init {
        val threads = ThreadService.getInstance(project)
        if (threads.unsupported == null) {
            scope.launch(Dispatchers.IO) { gate.serve(::sweepQuietly) }
            scope.launch { worktreeSweepTriggers(threads.threads).collect { request() } }
        }
    }

    /** Asks for a look; nothing happens while no Skein panel of the project shows. Never blocks, never throws. */
    fun request() {
        if (shown) gate.request()
    }

    /** A look right away, without the gap ([SweepGate.requestNow]): after Skein's own Open or Remove. Never throws. */
    fun requestNow() {
        if (shown) gate.requestNow()
    }

    /**
     * The Worktrees section opened or closed. Opened, its rows need counts: right away when the last look left them
     * out, else with the usual gap.
     */
    fun setSectionOpen(open: Boolean) {
        sectionOpen = open
        if (!open) return
        if (results.value.keptCounted) request() else requestNow()
    }

    /** The Skein panel came into view or left it; coming into view asks for a look. EDT or not, never throws. */
    fun setShown(showing: Boolean) {
        val was = shown
        shown = showing
        if (showing && !was) gate.request()
    }

    /** One look, on Dispatchers.IO; never throws but for cancellation. */
    private suspend fun sweepQuietly() {
        try {
            if (!shown || project.isDisposed) return
            // a repository's configuration can make git status start a program: only in a project the owner trusts
            if (!TrustedProjects.isProjectTrusted(project)) {
                results.value = WorktreeSweep.Result.EMPTY
                return
            }
            val folders = readAction { ProjectFolders.of(project) }
            val repositories = folders.all.mapNotNull { folder ->
                try {
                    GitToplevel.of(Path.of(folder))?.toString()
                } catch (_: InvalidPathException) {
                    null
                }
            }
            results.value = sweep.run(repositories, ThreadService.getInstance(project).threads.value, keptCounts = sectionOpen)
            failed = false
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!failed) LOG.warn("Skein could not look at its worktrees", e)
            failed = true
        }
    }

    companion object {
        private val LOG = logger<WorktreeHygiene>()

        /** At most one look starts in this time. */
        const val GAP_MILLIS = 15_000L

        /** How long one git call of a look may take before it is stopped and the row says `?`. */
        const val GIT_TIMEOUT_MILLIS = 15_000

        fun getInstance(project: Project): WorktreeHygiene = project.service()
    }
}
