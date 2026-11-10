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
    }

    private val current = MutableStateFlow(SessionMarks())

    val marks: StateFlow<SessionMarks> = current.asStateFlow()

    fun update(change: (SessionMarks) -> SessionMarks) {
        current.update(change)
    }

    override fun getState(): Saved {
        val marks = current.value
        return Saved().apply {
            pinned.addAll(marks.pinned)
            names.putAll(marks.names)
            done.putAll(marks.done)
        }
    }

    override fun loadState(state: Saved) {
        current.value = SessionMarks(state.pinned.toSet(), state.names.toMap(), state.done.toMap())
    }

    companion object {
        fun getInstance(project: Project): SessionMarksService = project.service()
    }
}
