package io.github.v0rest.skein.ui.actions

import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.ReadAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.SimpleListCellRenderer
import io.github.v0rest.skein.git.GitToplevel
import io.github.v0rest.skein.threads.ThreadService
import io.github.v0rest.skein.ui.ContentRoots
import io.github.v0rest.skein.ui.ProjectFolders
import java.awt.Component
import java.nio.file.InvalidPathException
import java.nio.file.Path
import javax.swing.JList

/** "+": a new thread in the project folder, or in a content root the user picks when there are several. */
class NewThreadAction : DumbAwareAction() {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        e.presentation.isEnabled = project != null && ThreadService.getInstance(project).unsupported == null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        start(project, e.inputEvent?.component)
    }

    companion object {
        private const val LAST_FOLDER = "skein.lastFolder"
        private val LOG = logger<NewThreadAction>()

        fun start(project: Project, anchor: Component?) {
            val service = ThreadService.getInstance(project)
            chooseFolder(project, anchor, "New Thread In") { service.newThread(it) }
        }

        /**
         * The project's only start folder goes straight to [onChosen]; with several, a popup asks and remembers the pick.
         * The folders are worked out off the EDT, since a root inside the project folder is offered only when it is a
         * repository of its own, which takes a look at the disk; the popup and [onChosen] run on the EDT.
         */
        fun chooseFolder(project: Project, anchor: Component?, title: String, onChosen: (String) -> Unit) {
            val modality = ModalityState.defaultModalityState()
            ApplicationManager.getApplication().executeOnPooledThread {
                if (project.isDisposed) return@executeOnPooledThread
                try {
                    val folders = ReadAction.computeBlocking<ProjectFolders, RuntimeException> { ProjectFolders.of(project) }
                    val choices = folders.startFolders(::isRepositoryTop)
                    ApplicationManager.getApplication().invokeLater({ offer(project, anchor, title, folders, choices, onChosen) }, modality, project.disposed)
                } catch (e: ProcessCanceledException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Skein could not list the folders a thread can start in", e)
                }
            }
        }

        /** On the EDT: [choices] as worked out by [chooseFolder]. */
        private fun offer(project: Project, anchor: Component?, title: String, folders: ProjectFolders, choices: List<String>, onChosen: (String) -> Unit) {
            if (choices.isEmpty()) return
            if (choices.size == 1) {
                onChosen(choices.single())
                return
            }
            val properties = PropertiesComponent.getInstance(project)
            val popup = JBPopupFactory.getInstance().createPopupChooserBuilder(choices)
                .setTitle(title)
                .setRenderer(
                    object : SimpleListCellRenderer<String>() {
                        override fun customize(list: JList<out String>, value: String?, index: Int, selected: Boolean, hasFocus: Boolean) {
                            text = value?.let(folders::label).orEmpty()
                            toolTipText = value
                        }
                    },
                )
                .setSelectedValue(ContentRoots.preselected(choices, properties.getValue(LAST_FOLDER)), true)
                .setItemChosenCallback { folder ->
                    properties.setValue(LAST_FOLDER, folder)
                    onChosen(folder)
                }
                .createPopup()
            if (anchor != null && anchor.isShowing) popup.showUnderneathOf(anchor) else popup.showCenteredInCurrentWindow(project)
        }

        /** Whether [folder] holds a `.git` of its own, as a repository's top does and a source set never does. Blocking. */
        private fun isRepositoryTop(folder: String): Boolean =
            try {
                GitToplevel.isRepository(Path.of(folder))
            } catch (_: InvalidPathException) {
                false
            }
    }
}
