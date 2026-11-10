package io.github.v0rest.skein.settings

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.SimplePersistentStateComponent
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
import com.intellij.util.messages.Topic

/** Skein's own settings, stored with the IDE's settings (skein.xml), never under `.claude`. */
@Service(Service.Level.APP)
@State(name = "SkeinSettings", storages = [Storage("skein.xml")])
class SkeinSettings : SimplePersistentStateComponent<SkeinSettings.State>(State()) {
    class State : BaseState() {
        var notifyNeedsYou by property(true)
        var notifyTurnFinished by property(true)
        var groupThreadsByState by property(false)
        var onlyWaitingThreads by property(false)
        var soundNeedsYou by property(true)
        var soundTurnFinished by property(false)
        var keepAwake by property(false)

        /** Show the toolbar counter of threads that need you. */
        var showWaitingCounter by property(true)

        /** Show on each thread row how long it has been in its state ("now", "4 min"). */
        var showAge by property(true)

        /** Show under each thread's name what it did last (the line its notification would carry); off shows the state words. */
        var showPreview by property(true)

        /** Show on line 2 of each thread row the model family and the context in use ("Opus · 385k"). */
        var showContext by property(true)

        /** How long background work keeps the computer awake: 0 = not at all, -1 = as long as it runs, otherwise minutes. */
        var keepAwakeBackgroundMinutes by property(30)
    }

    /** Told when a setting that changes how the thread list looks was changed; open panels re-apply it. */
    fun interface Listener {
        fun changed()
    }

    companion object {
        @Topic.AppLevel
        val TOPIC: Topic<Listener> = Topic(Listener::class.java, Topic.BroadcastDirection.NONE)

        fun getInstance(): SkeinSettings = service()

        /** Call after writing a setting that the open panels show; the listeners run on the thread that calls. */
        fun publishChanged() {
            ApplicationManager.getApplication().messageBus.syncPublisher(TOPIC).changed()
        }
    }
}
