package io.github.v0rest.skein.ui.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.project.DumbAwareAction
import io.github.v0rest.skein.threads.ThreadService
import io.github.v0rest.skein.ui.ProjectFolders
import io.github.v0rest.skein.ui.ThreadHere

/**
 * New Thread Here, in the Project view's and an editor tab's context menu, starts a plain thread in the selected
 * folder, or in a file's folder, as "+" starts one in the project folder. [ThreadHere] decides the folder, and where it
 * finds none (a library jar, a scratch file, a folder outside the project or in a worktree, items of different folders)
 * or Skein is off here, the action does not show. It reads the selection and the project's roots only, never the disk.
 */
class NewThreadHereAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = folderOf(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val folder = folderOf(e) ?: return
        ThreadService.getInstance(project).newThread(folder)
    }

    private fun folderOf(e: AnActionEvent): String? {
        val project = e.project ?: return null
        if (project.isDisposed || ThreadService.getInstance(project).unsupported != null) return null
        // an editor tab hands over its one file, the Project view every selected node's file
        val files = e.getData(CommonDataKeys.VIRTUAL_FILE_ARRAY)?.toList() ?: listOfNotNull(e.getData(CommonDataKeys.VIRTUAL_FILE))
        val items = files.map { ThreadHere.Item(it.path, it.isDirectory, it.isInLocalFileSystem) }
        // the update already has read access and the EDT's actionPerformed takes it here, a quick look either way
        val folders = ReadAction.computeBlocking<ProjectFolders, RuntimeException> { ProjectFolders.of(project) }
        return ThreadHere.folderFor(items, folders.all, folders.base)
    }
}
