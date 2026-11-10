package io.github.v0rest.skein.ui.actions

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import io.github.v0rest.skein.claude.ClaudeHome
import io.github.v0rest.skein.git.ExcludeResult
import io.github.v0rest.skein.git.GitToplevel
import io.github.v0rest.skein.git.ProcessGitRunner
import io.github.v0rest.skein.git.WorktreeExcludes
import io.github.v0rest.skein.git.WorktreeGit
import io.github.v0rest.skein.notify.ThreadNotifier
import io.github.v0rest.skein.threads.ThreadService
import io.github.v0rest.skein.ui.WorktreeNameDialog
import io.github.v0rest.skein.ui.WorktreeStart
import java.nio.file.Path

/**
 * Starts a thread that runs `claude --worktree <name>`: pick the folder, check the repository, ask for the name, keep
 * the repository's worktrees out of git, note the branch the repository has checked out as the thread's base, launch.
 */
class NewWorktreeThreadAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && ThreadService.getInstance(project).unsupported == null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        NewThreadAction.chooseFolder(project, e.inputEvent?.component, "Worktree Thread In") { folder ->
            ApplicationManager.getApplication().executeOnPooledThread { prepare(project, folder) }
        }
    }

    /** Off the EDT: the repository, the Claude home and git, all read only; only the dialog goes to the EDT. */
    private fun prepare(project: Project, folder: String) {
        if (project.isDisposed) return
        try {
            val repository = GitToplevel.of(Path.of(folder)) ?: return balloon(project, "Not a git repository: $folder")
            val repo = repository.toString()
            // a repository around the Claude home (a dotfiles repository in the home folder) would get its worktrees there
            if (ClaudeHome.default().holdsWorktreesOf(repository)) return balloon(project, WorktreeStart.insideClaudeHome(repo))
            val runner = ProcessGitRunner()
            val git = WorktreeGit(runner)
            WorktreeStart.headRefusal(git.head(repository))?.let { return balloon(project, it) }
            // git lists real paths, while a project opened through a link names the repository by the link
            val repositories = setOfNotNull(repo, GitToplevel.resolved(repo))
            val existing = WorktreeStart.existingNames(repositories, git.list(repository))
            // a thread still queued or starting has no worktree git could list yet, but its name is taken
            val inUse = WorktreeStart.threadNames(repositories, ThreadService.getInstance(project).threads.value, GitToplevel::resolved)
            // a branch left behind by an earlier worktree of the same name (git refused to delete it)
            val branches = git.branches(repository)
            ApplicationManager.getApplication().invokeLater({
                val dialog = WorktreeNameDialog(project, existing, inUse, branches)
                if (!dialog.showAndGet()) return@invokeLater // cancelled: nothing was written
                val name = dialog.name
                // an existing worktree reopens: the repository's branch now says nothing about where it started
                val reopening = name in existing
                ApplicationManager.getApplication().executeOnPooledThread { launch(project, repository, name, reopening) }
            }, ModalityState.defaultModalityState(), project.disposed)
        } catch (e: ProcessCanceledException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not prepare a worktree thread", e)
            balloon(project, "Could not start a thread in a worktree: ${e.message}")
        }
    }

    companion object {
        private val LOG = logger<NewWorktreeThreadAction>()

        /**
         * Off the EDT, once the name is chosen: a worktree is a repository inside the working tree, so git must ignore it
         * before the first one is made; then the thread starts. A new worktree records the branch the repository has
         * checked out right now as the thread's base ([io.github.v0rest.skein.threads.SkeinThread.base]); one that is
         * [reopening] records none, since that branch says nothing about where it started.
         */
        private fun launch(project: Project, repository: Path, name: String, reopening: Boolean) {
            if (project.isDisposed) return
            try {
                val runner = ProcessGitRunner()
                val excluded = WorktreeExcludes(runner).ensureExcluded(repository)
                WorktreeStart.excludeRefusal(excluded)?.let { return balloon(project, it) }
                if (excluded is ExcludeResult.Ok) WorktreeStart.excludeAdded(repository, excluded)?.let { balloon(project, it, NotificationType.INFORMATION) }
                val base = if (reopening) null else WorktreeGit(runner).currentBranch(repository)
                ThreadService.getInstance(project).newWorktreeThread(repository.toString(), name, base)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Skein could not start a worktree thread", e)
                balloon(project, "Could not start a thread in a worktree: ${e.message}")
            }
        }

        /**
         * Starts `claude --worktree <name>` on the existing worktree [name] of [repository] (the Worktrees section's
         * Open when no session ran there), with the checks of a new worktree thread but no name to ask: the repository
         * is still one, does not contain the Claude home, has a commit, and keeps its worktrees out of git. No base is
         * recorded. Blocking: off the EDT; what stops it is said in a balloon.
         */
        fun reopen(project: Project, repository: Path, name: String) {
            if (project.isDisposed) return
            try {
                if (!GitToplevel.isRepository(repository)) return balloon(project, "Not a git repository: $repository")
                if (ClaudeHome.default().holdsWorktreesOf(repository)) return balloon(project, WorktreeStart.insideClaudeHome(repository.toString()))
                WorktreeStart.headRefusal(WorktreeGit(ProcessGitRunner()).head(repository))?.let { return balloon(project, it) }
                launch(project, repository, name, reopening = true)
            } catch (e: ProcessCanceledException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Skein could not open a worktree", e)
                balloon(project, "Could not start a thread in a worktree: ${e.message}")
            }
        }

        private fun balloon(project: Project, text: String, type: NotificationType = NotificationType.WARNING) {
            if (project.isDisposed) return
            // the text is HTML to the platform, and a path or git's reason may hold a `<`
            NotificationGroupManager.getInstance().getNotificationGroup(ThreadNotifier.GROUP_ID)
                .createNotification(StringUtil.escapeXmlEntities(text), type)
                .notify(project)
        }
    }
}
