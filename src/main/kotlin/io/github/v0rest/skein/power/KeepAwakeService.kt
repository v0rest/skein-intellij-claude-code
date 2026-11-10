package io.github.v0rest.skein.power

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.components.service
import com.intellij.openapi.components.serviceIfCreated
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.util.SystemInfo
import io.github.v0rest.skein.settings.SkeinSettings
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch

/**
 * Holds or releases [blocker] only when the wish changes. Every blocker call runs under this object's monitor, so
 * a [dispose] from another thread cannot interleave with an update, and nothing is held after it.
 */
class KeepAwakeLogic(private val blocker: SleepBlocker) {
    private var held = false
    private var disposed = false

    @Synchronized
    fun update(enabled: Boolean, holds: Boolean) {
        if (disposed) return
        val want = enabled && holds
        if (want == held) return
        held = want
        if (want) blocker.hold() else blocker.release()
    }

    @Synchronized
    fun dispose() {
        disposed = true
        if (!held) return
        held = false
        blocker.release()
    }
}

/**
 * Keeps the computer from idle-sleeping while a thread of any open project works, if the user turned that on.
 * All work runs on one dispatcher and never throws into the caller.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Service(Service.Level.APP)
class KeepAwakeService(private val scope: CoroutineScope) : Disposable {
    private val dispatcher = Dispatchers.Default.limitedParallelism(1)
    private val logic = KeepAwakeLogic(if (SystemInfo.isMac) MacSleepBlocker() else NoSleepBlocker)

    init {
        ApplicationManager.getApplication().messageBus.connect(this)
            .subscribe(SkeinSettings.TOPIC, SkeinSettings.Listener { refresh() })
        scope.launch(dispatcher) {
            while (true) {
                delay(TICK_MILLIS)
                apply()
            }
        }
    }

    /** Schedules a re-check; returns at once and never throws. */
    fun refresh() {
        try {
            scope.launch(dispatcher) { apply() }
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.warn("Skein could not schedule the keep-awake check", e)
        }
    }

    private suspend fun apply() {
        try {
            val settings = SkeinSettings.getInstance().state
            val threads = openProjectThreads()
            logic.update(settings.keepAwake, KeepAwakePolicy.holds(threads, System.currentTimeMillis(), settings.keepAwakeBackgroundMinutes))
        } catch (_: ProcessCanceledException) {
            // a project or service going away mid-read: skip this round
        } catch (e: CancellationException) {
            // a project closing mid-read also ends up here; only our own cancellation ends the loop
            currentCoroutineContext().ensureActive()
        } catch (e: Throwable) {
            LOG.warn("Skein could not update the keep-awake state", e)
        }
    }

    private fun openProjectThreads(): List<SkeinThread> =
        ProjectManager.getInstance().openProjects
            .filter { !it.isDisposed }
            .flatMap { it.serviceIfCreated<ThreadService>()?.threads?.value.orEmpty() }

    override fun dispose() {
        try {
            logic.dispose()
        } catch (e: Throwable) {
            LOG.warn("Skein could not release the keep-awake state", e)
        }
    }

    companion object {
        private val LOG = logger<KeepAwakeService>()
        private const val TICK_MILLIS = 30_000L

        fun getInstance(): KeepAwakeService = service()

        /** For callers that must not fail: schedules a re-check, and swallows whatever a service in shutdown throws. */
        fun refreshQuietly() {
            try {
                getInstance().refresh()
            } catch (_: ProcessCanceledException) {
            } catch (_: CancellationException) {
            } catch (e: Throwable) {
                LOG.warn("Skein could not ask for a keep-awake check", e)
            }
        }
    }
}
