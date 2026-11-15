package io.github.v0rest.skein.ui

import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.components.JBTextField
import com.intellij.ui.dsl.builder.AlignX
import com.intellij.ui.dsl.builder.panel
import io.github.v0rest.skein.ui.actions.MenuState
import javax.swing.JComponent

/**
 * The Rename dialog of a thread and of a History session: the name Skein shows, starting with [current] (blank goes back
 * to the usual name), and "Also rename in Claude Code", starting as [box] says ([MenuState.renameBox]), with which Skein
 * also types `/rename` into the session's claude once that is safe ([io.github.v0rest.skein.threads.RenameGate]). A name
 * the shell underneath claude would read cannot go with the box ticked, and the dialog says why
 * ([MenuState.claudeNameProblem]); [fallback] and [existing] tell it whether OK would send anything at all. With Claude
 * Code in vim mode the box cannot be changed, and its words say why.
 */
class RenameDialog(
    project: Project,
    current: String,
    private val box: MenuState.RenameBox,
    private val fallback: String,
    private val existing: String?,
) : DialogWrapper(project) {
    private val nameField = JBTextField(current, 30)
    private val claudeBox = JBCheckBox("Also rename in Claude Code", box.ticked).apply { isEnabled = box.enabled }

    /** What the field holds when OK is pressed. */
    val entered: String
        get() = nameField.text

    /** The box as it is left with OK; a box that cannot be changed says nothing about Claude Code. */
    val inClaude: Boolean
        get() = claudeBox.isSelected && box.enabled

    init {
        title = "Rename"
        init()
        // checks the field as it is typed in and as the box changes, and keeps OK off while the name cannot go
        initValidation()
    }

    override fun getPreferredFocusedComponent(): JComponent = nameField

    override fun doValidate(): ValidationInfo? =
        MenuState.claudeNameProblem(nameField.text, fallback, existing, inClaude)?.let { ValidationInfo(it, nameField) }

    override fun createCenterPanel(): JComponent = panel {
        row("Name:") {
            cell(nameField).align(AlignX.FILL).comment("Shown in Skein. Leave it empty to go back to the usual name.")
        }
        row {
            cell(claudeBox).comment(box.comment)
        }
    }
}
