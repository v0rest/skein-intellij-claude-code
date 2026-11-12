package io.github.v0rest.skein.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.util.SystemInfo
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

/** Settings | Tools | Skein: notifications, sounds, keep-awake, where thread tabs open and the thread list. */
class SkeinConfigurable : BoundConfigurable("Skein") {
    /** One choice of how long background work keeps the computer awake; [minutes] is what the setting stores. */
    private enum class Background(val minutes: Int, val label: String) {
        NO(0, "No"),
        MIN_15(15, "15 min"),
        MIN_30(30, "30 min"),
        HOUR(60, "1 hour"),
        FOREVER(-1, "As long as it runs");

        override fun toString() = label
    }

    override fun createPanel(): DialogPanel {
        val state = SkeinSettings.getInstance().state
        return panel {
            group("Notifications") {
                row { checkBox("Notify when a thread needs you").bindSelected(state::notifyNeedsYou) }
                row { checkBox("Notify when a thread finishes its turn").bindSelected(state::notifyTurnFinished) }
                row { comment("Skein uses the IDE's notifications. Whether they also appear in the system notification centre is set in Settings | Appearance & Behavior | Notifications.") }
            }
            group("Sounds") {
                row { checkBox("Play a sound when a thread needs you").bindSelected(state::soundNeedsYou) }
                row { checkBox("Play a sound when a thread finishes its turn").bindSelected(state::soundTurnFinished) }
            }
            group("Keep awake") {
                row {
                    checkBox("Keep the computer awake while a thread works")
                        .bindSelected(state::keepAwake)
                        .enabled(SystemInfo.isMac)
                }
                row("When a turn ends but a background process still runs:") {
                    // A stored value that is not one of the five shows as 30 min and is not rewritten until Apply.
                    comboBox(Background.entries)
                        .bindItem(
                            { Background.entries.firstOrNull { it.minutes == state.keepAwakeBackgroundMinutes } ?: Background.MIN_30 },
                            { state.keepAwakeBackgroundMinutes = (it ?: Background.MIN_30).minutes },
                        )
                        .enabled(SystemInfo.isMac)
                }
                if (!SystemInfo.isMac) {
                    row { comment("Available on macOS only") }
                }
            }
            group("Thread tabs") {
                row { checkBox("Open threads in the editor area").bindSelected(state::threadsInEditor) }
                row {
                    comment(
                        "Off keeps new threads as tabs in the Terminal tool window. Threads already open stay where they are, " +
                            "and the thread colour shows on editor tabs only.",
                    )
                }
            }
            group("Thread list") {
                row { checkBox("Show the count of threads that need you in the main toolbar").bindSelected(state::showWaitingCounter) }
                row { comment("Shown in the new UI's main toolbar.") }
                row { checkBox("Show the model and the context in use on each thread row").bindSelected(state::showContext) }
            }
        }
    }

    override fun apply() {
        super.apply()
        SkeinSettings.publishChanged()
    }
}
