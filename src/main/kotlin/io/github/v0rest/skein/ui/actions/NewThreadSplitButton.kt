package io.github.v0rest.skein.ui.actions

import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.SplitButtonAction
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.openapi.ui.popup.util.PopupUtil
import javax.swing.JComponent

/**
 * "+" with a chevron in the title bar: a click on the "+" always starts a New Thread, the chevron opens New Thread and
 * New Thread in Worktree…. Takes the registered actions, so both keep the texts and icons the Tools menu shows.
 *
 * The chevron's menu runs its items with the tool window's [content] as their context, not the button. The new UI
 * hides the title bar buttons of a tool window that is not active once the pointer leaves it, and the IDE skips an
 * action whose context component is no longer showing, so an item picked from the button's own menu did nothing.
 */
class NewThreadSplitButton(
    private val newThread: AnAction,
    newWorktreeThread: AnAction,
    private val content: JComponent,
) : SplitButtonAction(DefaultActionGroup(newThread, newWorktreeThread)) {
    /** A dynamic split button would make the last action picked from the chevron the button's click. */
    override fun useDynamicSplitButton(): Boolean = false

    /** New Thread by name, not by being first in the group. */
    override fun getMainAction(e: AnActionEvent): AnAction = newThread

    /** Shows the menu itself and returns true, so the button never opens its own. */
    override fun showPopup(e: AnActionEvent): Boolean {
        val context = DataManager.getInstance().getDataContext(content)
        val popup = JBPopupFactory.getInstance()
            .createActionGroupPopup(null, actionGroup, context, JBPopupFactory.ActionSelectionAid.SPEEDSEARCH, true, e.place)
        val button = e.inputEvent?.component
        if (button != null && button.isShowing) {
            // a press on the button while the menu is open only closes the menu, so the click that follows does not reopen it
            PopupUtil.setPopupToggleComponent(popup, button)
            popup.showUnderneathOf(button)
        } else {
            popup.showInBestPositionFor(context)
        }
        return true
    }
}
