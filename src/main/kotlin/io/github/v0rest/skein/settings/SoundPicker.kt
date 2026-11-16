package io.github.v0rest.skein.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.fileChooser.FileChooser
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory
import com.intellij.openapi.ui.ComboBox
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.Cell
import com.intellij.ui.dsl.builder.Row
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.selected
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import io.github.v0rest.skein.sound.SoundCatalog
import io.github.v0rest.skein.sound.SoundChoice
import io.github.v0rest.skein.sound.SoundChoices
import io.github.v0rest.skein.sound.SoundDisk
import io.github.v0rest.skein.sound.SoundItem
import io.github.v0rest.skein.sound.SoundMenu
import io.github.v0rest.skein.sound.SoundOs
import io.github.v0rest.skein.sound.SoundPlayer
import io.github.v0rest.skein.sound.SoundProblem
import io.github.v0rest.skein.threads.NotificationKind
import kotlin.reflect.KMutableProperty0

/**
 * An event's sound on the Skein settings page - the list, ▶, which plays what the list shows now, applied or not, and
 * Choose File…. The list starts with the beep and the [stored] choice, shown plainly, and fills with the OS's sounds and
 * the marks of choices that would beep once [load] has read the disk on a pooled thread - a share that hangs must never
 * freeze the IDE. Choose File… is a button, not an item, so type-ahead or the arrow keys never open the chooser.
 * EDT only, but for [load]'s own work.
 */
internal class SoundPicker(private val kind: NotificationKind, private val stored: KMutableProperty0<String?>) {
    private val home = System.getProperty("user.home").orEmpty()
    private val initial = SoundChoices.parse(stored.get())
    private val model = CollectionComboBoxModel(SoundMenu.items(emptyList(), listOf(initial), home))

    /** Why a choice would beep, for its words in the list - a choice not in here shows plainly. */
    private val problems = HashMap<SoundChoice, SoundProblem>()

    private lateinit var combo: ComboBox<SoundItem>

    private val choice: SoundChoice get() = SoundMenu.choiceOf(combo.selectedItem as? SoundItem)

    /** The list, ▶ and Choose File… in [row], after the event's switch [box], whose tick turns them on. */
    fun cells(row: Row, box: Cell<JBCheckBox>) {
        combo = row.comboBox(model, textListCellRenderer { item -> item?.let { SoundMenu.label(it, problems[it.choice]) } })
            .bindItem({ SoundMenu.item(SoundChoices.parse(stored.get()), home) }, { stored.set(SoundChoices.format(SoundMenu.choiceOf(it))) })
            .enabledIf(box.selected)
            .component
        combo.addActionListener { combo.toolTipText = SoundMenu.tooltip(choice) }
        row.button("▶") { SoundPlayer.getInstance().play(kind, choice, preview = true) }
            .applyToComponent { toolTipText = "Play this sound once" }
            .accessibleName("Play this sound once")
            .enabledIf(box.selected)
        row.button("Choose File…") { choose() }
            .enabledIf(box.selected)
    }

    /** The OS's sounds and the stored choices' checks came back from the pool, so the list fills and its selection stays. */
    private fun fill(names: List<String>, found: Map<SoundChoice, SoundProblem?>) {
        mark(found)
        model.replaceAll(SoundMenu.items(names, model.items.map { it.choice }, home))
    }

    private fun mark(found: Map<out SoundChoice, SoundProblem?>) {
        for ((choice, problem) in found) if (problem == null) problems.remove(choice) else problems[choice] = problem
        combo.repaint()
    }

    /** The IDE's file chooser, and the file joins the list selected, shown plainly until its own check comes back. */
    private fun choose() {
        val descriptor = FileChooserDescriptorFactory.singleFile()
            .withTitle("Choose a Sound")
            .withExtensionFilter("Sound files", *SoundCatalog.extensions(SoundOs.current).toTypedArray())
        val chosen = FileChooser.chooseFile(descriptor, combo, null, null) ?: return
        if (!chosen.isInLocalFileSystem) return
        val file = SoundMenu.chosenFile(chosen.path) ?: return
        // a copy, since the model's own list empties while it is replaced
        model.replaceAll(SoundMenu.withChosen(model.items.toList(), file, home))
        combo.selectedItem = SoundMenu.item(file, home)
        onPool({ mapOf(file to SoundDisk.problem(file, emptyList())) }) { mark(it) }
    }

    companion object {
        /** One look at the disk for all of a page's [pickers] - the OS's sounds once, then each stored choice. */
        fun load(pickers: List<SoundPicker>) {
            val choices = pickers.map { it.initial }
            onPool({
                val sounds = SoundDisk.systemSounds()
                sounds.map { it.name } to choices.associateWith { SoundDisk.problem(it, sounds) }
            }) { (names, found) -> pickers.forEach { it.fill(names, found) } }
        }

        /**
         * [read] on a pooled thread, then [show] its answer on the EDT. Any modality, since the settings dialog is modal
         * and only this page's own list changes.
         */
        private fun <T> onPool(read: () -> T, show: (T) -> Unit) {
            val application = ApplicationManager.getApplication()
            application.executeOnPooledThread {
                val answer = read()
                application.invokeLater({ show(answer) }, ModalityState.any())
            }
        }
    }
}
