package io.github.v0rest.skein.sound

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import io.github.v0rest.skein.threads.NotificationKind
import java.awt.Toolkit
import java.util.concurrent.ConcurrentHashMap

/**
 * Plays an event's chosen sound at the system's volume, through the macOS alert sound service or, on Windows and Linux,
 * Java Sound ([SoundOutputs]). Everything runs on a pooled thread, never on the EDT, and the output is made on the first
 * sound, so nothing native loads before one plays. Whatever goes wrong - a file that is gone, a format the system cannot
 * read, no audio device, a native call that fails - the event beeps as it did before there was a choice, and the log
 * says so once per choice - no balloon, no exception.
 */
@Service(Service.Level.APP)
class SoundPlayer : Disposable {
    /** Each event's sounds ([SoundSlot]), each guarded by itself. */
    private val slots = NotificationKind.entries.associateWith { SoundSlot<Any>(::monotonicMillis, ::stopQuietly) }

    /** What was logged already, by stored value and what went wrong, so a broken choice does not fill the log on every event. */
    private val logged: MutableSet<String> = ConcurrentHashMap.newKeySet()

    @Volatile
    private var output: SoundOutput? = null

    @Volatile
    private var disposed = false

    /** Plays [choice] for [kind] and returns at once, [preview] for the play button in Settings, which never waits ([SoundGate]). */
    fun play(kind: NotificationKind, choice: SoundChoice, preview: Boolean = false) {
        try {
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    playNow(kind, choice, preview)
                } catch (e: Throwable) {
                    logOnce(choice, "Skein's player", e)
                    beep()
                }
            }
        } catch (e: Exception) {
            // the pool refuses work only while the IDE shuts down, and then the beep is all there is time for
            beep()
        }
    }

    private fun playNow(kind: NotificationKind, choice: SoundChoice, preview: Boolean) {
        val plan = SoundDisk.plan(choice)
        val slot = slots.getValue(kind)
        synchronized(slot) {
            if (disposed) return
            slot.play(preview) {
                when (plan) {
                    is SoundPlan.Beep -> {
                        plan.problem?.let { logOnce(choice, it.name, null) }
                        beep()
                        null
                    }
                    is SoundPlan.PlayFile -> start(slot, choice, plan)
                }
            }
        }
    }

    /**
     * The file playing, and what cuts it short if anything does, or null when it could not start - then the event beeps
     * and the log says why, once. A native call can fail with an Error, not an Exception, so both beep.
     */
    private fun start(slot: SoundSlot<Any>, choice: SoundChoice, file: SoundPlan.PlayFile): Any? = try {
        // a sound that stopped by itself is closed on the pool, not on Java Sound's own event thread, and that waits for
        // the slot's lock, so a sound that ends at once still finds itself in the slot
        output().start(file.path, file.facts) { sound -> ended(slot, sound) }
    } catch (e: Throwable) {
        logOnce(choice, "playing the file", e)
        beep()
        null
    }

    /** Made on the first sound, on the pool - the macOS one loads its native libraries only when it plays. */
    private fun output(): SoundOutput = output ?: synchronized(this) {
        output ?: when (SoundOutputs.kind(SoundOs.current)) {
            SoundOutputs.Kind.SYSTEM_SOUND_SERVICE -> MacSoundOutput()
            SoundOutputs.Kind.JAVA_SOUND -> JavaSoundOutput()
        }.also { output = it }
    }

    /** A clock that never steps back, for [SoundGate]. */
    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

    /** The sound stopped by itself, so it closes. */
    private fun ended(slot: SoundSlot<Any>, sound: Any) {
        try {
            ApplicationManager.getApplication().executeOnPooledThread {
                synchronized(slot) { slot.ended(sound) }
                stopQuietly(sound)
            }
        } catch (e: Exception) {
            stopQuietly(sound)
        }
    }

    private fun stopQuietly(sound: Any) {
        try {
            output?.stop(sound)
        } catch (e: Throwable) {
            LOG.debug("Skein could not close a sound", e)
        }
    }

    private fun beep() {
        try {
            Toolkit.getDefaultToolkit().beep()
        } catch (e: Exception) {
            LOG.debug("Skein could not beep", e)
        }
    }

    /** Info, not an error - a sound file the user moved away is not the IDE's fault. */
    private fun logOnce(choice: SoundChoice, what: String, e: Throwable?) {
        val value = SoundChoices.format(choice).orEmpty()
        if (!logged.add("$value|$what")) return
        if (e == null) LOG.info("Skein plays the system beep for $value: $what") else LOG.info("Skein plays the system beep for $value: $what failed", e)
    }

    override fun dispose() {
        disposed = true
        for (slot in slots.values) {
            synchronized(slot) { slot.stopAll() }
        }
        try {
            output?.dispose()
        } catch (e: Throwable) {
            LOG.debug("Skein could not let go of its sounds", e)
        }
    }

    companion object {
        private val LOG = logger<SoundPlayer>()

        fun getInstance(): SoundPlayer = service()
    }
}
