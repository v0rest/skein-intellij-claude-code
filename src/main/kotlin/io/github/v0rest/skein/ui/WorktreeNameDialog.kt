package io.github.v0rest.skein.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import javax.swing.JComponent
import javax.swing.JTextField
import javax.swing.ListSelectionModel

/**
 * Asks for the name of the worktree a new thread starts in; an existing name reopens that worktree. The field starts
 * with the first `task-N` that neither git ([existing]) nor one of the project's threads ([inUse]) has and whose
 * `worktree-<name>` branch is not left among the repository's [branches], and left empty it stands for that name.
 */
class WorktreeNameDialog(
    project: Project,
    private val existing: Set<String>,
    inUse: Set<String>,
    branches: Set<String>,
) : DialogWrapper(project) {
    private val suggested = WorktreeStart.defaultName(existing, inUse, branches)
    private val nameField = JTextField(suggested, 24)

    val name: String
        get() = WorktreeStart.chosenName(nameField.text, suggested)

    init {
        title = "New Thread in Worktree"
        init()
        // checks the field as it is typed in, shows the rule next to it, and keeps OK disabled while it does not hold
        initValidation()
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? =
        if (WorktreeStart.isAcceptable(nameField.text)) null else ValidationInfo(INVALID, nameField)

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") {
            cell(nameField).align(AlignX.FILL).comment(if (existing.isEmpty()) null else "An existing name reopens that worktree")
        }
        if (existing.isNotEmpty()) {
            row {
                val list = JBList(existing.sorted()).apply { selectionMode = ListSelectionModel.SINGLE_SELECTION }
                list.addListSelectionListener { e ->
                    if (!e.valueIsAdjusting) list.selectedValue?.let { nameField.text = it }
                }
                cell(JBScrollPane(list)).align(AlignX.FILL)
            }
        }
    }

    companion object {
        const val INVALID = "Letters, digits, '.', '_' and '-', up to 64, not starting with '-' or '.'"
    }
}
