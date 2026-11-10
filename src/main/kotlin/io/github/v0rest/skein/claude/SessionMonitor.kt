package io.github.v0rest.skein.claude

import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import io.github.v0rest.skein.process.JdkProcessProbe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Polls the session directory once a second, off the EDT, while anyone collects [snapshots]. */
@Service(Service.Level.APP)
class SessionMonitor(scope: CoroutineScope) {
    private val watcher by lazy { SessionDirectoryWatcher(ClaudeHome.default(), JdkProcessProbe()) }
    private val state = MutableStateFlow(SessionSnapshot.EMPTY)
    private val polled = MutableStateFlow(false)

    val snapshots: StateFlow<SessionSnapshot> = state.asStateFlow()

    /** Suspends until the directory has been read once, so callers do not act on the initial empty snapshot. */
    suspend fun awaitFirstPoll() {
        polled.first { it }
    }

    init {
        scope.launch(Dispatchers.IO) {
            state.subscriptionCount.map { it > 0 }.distinctUntilChanged().collectLatest { active ->
                if (active) {
                    while (true) {
                        try {
                            state.value = watcher.poll()
                            polled.value = true
                        } catch (e: RuntimeException) {
                            LOG.warn("Skein could not read the Claude Code session directory", e)
                        }
                        delay(POLL_MILLIS)
                    }
                }
            }
        }
    }

    companion object {
        const val POLL_MILLIS = 1_000L
        private val LOG = logger<SessionMonitor>()

        fun getInstance(): SessionMonitor = service()
    }
}
