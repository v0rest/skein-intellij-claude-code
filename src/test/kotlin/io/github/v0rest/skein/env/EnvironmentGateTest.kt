package io.github.v0rest.skein.env

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentGateTest {
    @Test
    fun macOrLinuxLocalIsSupported() = assertNull(EnvironmentGate.check(EnvFacts(false, false, false, "/w/p")))

    @Test
    fun noProjectPathIsStillSupportedLocally() = assertNull(EnvironmentGate.check(EnvFacts(false, false, false, null)))

    @Test
    fun windows() = assertEquals(Unsupported.WINDOWS, EnvironmentGate.check(EnvFacts(true, false, false, "C:\\w\\p")))

    @Test
    fun wslProject() = assertEquals(Unsupported.WSL, EnvironmentGate.check(EnvFacts(true, false, false, "\\\\wsl.localhost\\Ubuntu\\home\\u\\p")))

    @Test
    fun legacyWslPathAnyCase() {
        assertEquals(Unsupported.WSL, EnvironmentGate.check(EnvFacts(true, false, false, "\\\\wsl$\\Ubuntu\\home\\u\\p")))
        assertEquals(Unsupported.WSL, EnvironmentGate.check(EnvFacts(true, false, false, "\\\\WSL.LOCALHOST\\Ubuntu\\p")))
        assertEquals(Unsupported.WSL, EnvironmentGate.check(EnvFacts(true, false, false, "//wsl.localhost/Ubuntu/home/u/p")))
    }

    @Test
    fun remoteHost() = assertEquals(Unsupported.REMOTE, EnvironmentGate.check(EnvFacts(false, true, false, "/w/p")))

    @Test
    fun thinClient() = assertEquals(Unsupported.REMOTE, EnvironmentGate.check(EnvFacts(false, false, true, "/w/p")))

    @Test
    fun remoteWinsOverWindows() = assertEquals(Unsupported.REMOTE, EnvironmentGate.check(EnvFacts(true, true, false, "C:\\w\\p")))

    @Test
    fun messagesSayWhy() {
        assertEquals("Skein supports local macOS and Linux for now. Windows support needs its own work and is not there yet.", Unsupported.WINDOWS.message)
        assertEquals("Skein does not support projects in WSL yet: claude and its session files live inside the Linux VM, out of Skein's reach.", Unsupported.WSL.message)
        assertEquals("Skein does not support Remote Development yet: claude runs on the remote host while this window runs locally.", Unsupported.REMOTE.message)
        assertTrue(Unsupported.entries.all { it.message.isNotBlank() })
    }
}
