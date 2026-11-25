package io.github.v0rest.skein.threads

import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** The project's [SessionMarks], kept in the workspace file. */
@Service(Service.Level.PROJECT)
@State(name = "SkeinSessionMarks", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class SessionMarksService : PersistentStateComponent<SessionMarksService.Saved> {
    class Saved : BaseState() {
        var pinned by list<String>()
        var names by map<String, String>()
        var done by map<String, Long>()

        /** [SessionMarks.claudeRenames], kept so a rename still waiting reaches Claude Code after an IDE restart too. */
        var claudeRenames by map<String, String>()

        /** [SessionMarks.colors], by the colour's name. */
        var colors by map<String, String>()
    }

    private val current = MutableStateFlow(SessionMarks())

    val marks: StateFlow<SessionMarks> = current.asStateFlow()

    fun update(change: (SessionMarks) -> SessionMarks) {
        current.update(change)
    }

    /**
     * Takes [name], the rename waiting for [sessionId]'s claude, for the line about to go: true, and it stops waiting,
     * when it still waits ([SessionMarks.waitsFor]); false, and nothing changes, when the Rename dialog dropped or
     * replaced it meanwhile, and then the line must not go. One atomic step, whatever else updates the marks.
     */
    fun claimClaudeRename(sessionId: String, name: String): Boolean {
        while (true) {
            val now = current.value
            if (!now.waitsFor(sessionId, name)) return false
            if (current.compareAndSet(now, now.claudeRenameSent(sessionId, name))) return true
        }
    }

    override fun getState(): Saved {
        val marks = current.value
        return Saved().apply {
            pinned.addAll(marks.pinned)
            names.putAll(marks.names)
            done.putAll(marks.done)
            claudeRenames.putAll(marks.claudeRenames)
            colors.putAll(marks.colors.mapValues { it.value.name })
        }
    }

    override fun loadState(state: Saved) {
        val names = state.names.toMap()
        // a waiting rename is always the session's Skein name too; one that is not (a hand-edited file) is not sent
        val claudeRenames = state.claudeRenames.filter { (id, name) -> names[id] == name }
        // a colour this version does not know (a hand-edited file, a later version's colour) is left out
        val colors = state.colors.mapNotNull { (id, name) -> ThreadColor.entries.firstOrNull { it.name == name }?.let { id to it } }.toMap()
        current.value = SessionMarks(pinned = state.pinned.toSet(), names = names, done = state.done.toMap(), claudeRenames = claudeRenames, colors = colors)
    }

    companion object {
        fun getInstance(project: Project): SessionMarksService = project.service()
    }
}
