package io.github.v0rest.skein.power

import com.intellij.openapi.diagnostic.logger
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.platform.mac.CoreFoundation
import com.sun.jna.ptr.IntByReference
import kotlinx.coroutines.CancellationException

/** Asks the system not to idle-sleep; the display may still sleep. Both calls are cheap and safe to repeat. */
interface SleepBlocker {
    fun hold()
    fun release()
}

/** For systems with no in-process way to do it. */
object NoSleepBlocker : SleepBlocker {
    override fun hold() = Unit
    override fun release() = Unit
}

/** A power assertion made in-process through IOKit, so Skein starts no process for it. */
class MacSleepBlocker : SleepBlocker {
    @Suppress("FunctionName")
    interface IOKit : Library {
        fun IOPMAssertionCreateWithName(type: CoreFoundation.CFStringRef, level: Int, name: CoreFoundation.CFStringRef, id: IntByReference): Int
        fun IOPMAssertionRelease(id: Int): Int
    }

    private var iokit: IOKit? = null
    private var broken = false
    private var assertionId: Int? = null

    override fun hold() {
        if (broken || assertionId != null) return
        try {
            val lib = iokit ?: Native.load("IOKit", IOKit::class.java).also { iokit = it }
            var type: CoreFoundation.CFStringRef? = null
            var name: CoreFoundation.CFStringRef? = null
            try {
                type = CoreFoundation.CFStringRef.createCFString(ASSERTION_TYPE)
                name = CoreFoundation.CFStringRef.createCFString(ASSERTION_NAME)
                val id = IntByReference()
                val result = lib.IOPMAssertionCreateWithName(type, LEVEL_ON, name, id)
                if (result == SUCCESS) assertionId = id.value else giveUp("IOPMAssertionCreateWithName returned $result", null)
            } finally {
                name?.release()
                type?.release()
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            giveUp("a native call failed", e)
        }
    }

    override fun release() {
        val id = assertionId ?: return
        assertionId = null
        try {
            val result = iokit?.IOPMAssertionRelease(id)
            if (result != null && result != SUCCESS) LOG.warn("Skein: IOPMAssertionRelease returned $result")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            giveUp("a native call failed", e)
        }
    }

    private fun giveUp(why: String, e: Throwable?) {
        broken = true
        LOG.warn("Skein cannot keep the computer awake on this system: $why", e)
    }

    private companion object {
        private val LOG = logger<MacSleepBlocker>()
        const val ASSERTION_TYPE = "PreventUserIdleSystemSleep"
        const val ASSERTION_NAME = "Skein: an agent is working"
        const val LEVEL_ON = 255
        const val SUCCESS = 0
    }
}
