package io.github.v0rest.skein.env

import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.testutil.Fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentGateTest {
    private fun local(projectPath: String?) = EnvFacts(isRemoteDevHost = false, isThinClient = false, projectPath = projectPath)

    @Test
    fun macOrLinuxLocalIsSupported() = assertNull(EnvironmentGate.check(local(Fixture.root("w/p", PathStyle.UNIX))))

    @Test
    fun noProjectPathIsStillSupportedLocally() = assertNull(EnvironmentGate.check(local(null)))

    /** Native Windows is supported, the project given as java.nio or as the IDE writes it, on a drive or a share. */
    @Test
    fun windowsLocalIsSupported() {
        for (path in listOf(
            Fixture.root("w/p", PathStyle.WINDOWS),
            Fixture.forwardRoot("w/p", PathStyle.WINDOWS),
            """\\acme-nas\projects\p""",
            "//acme-nas/projects/p",
            """\\?\C:\w\p""",
            // a share that only begins like WSL's
            """\\wsl-backup\p""",
            """\\wsl.localhost.acme\p""",
        )) {
            assertNull(path, EnvironmentGate.check(local(path)))
        }
    }

    @Test
    fun wslProject() = assertEquals(Unsupported.WSL, EnvironmentGate.check(local("""\\wsl.localhost\Ubuntu\home\u\p""")))

    /** WSL's shares in any case and with either separator, as java.nio and as the IDE write them. */
    @Test
    fun legacyWslPathAnyCase() {
        for (path in listOf(
            """\\wsl$\Ubuntu\home\u\p""",
            """\\WSL.LOCALHOST\Ubuntu\p""",
            """\\Wsl$\Ubuntu\p""",
            "//wsl.localhost/Ubuntu/home/u/p",
            "//wsl$/Ubuntu/home/u/p",
            "//WSL$/Ubuntu/p",
            """\\wsl.localhost/Ubuntu/p""",
            """/\wsl$\Ubuntu\p""",
        )) {
            assertEquals(path, Unsupported.WSL, EnvironmentGate.check(local(path)))
        }
    }

    @Test
    fun remoteHost() = assertEquals(Unsupported.REMOTE, EnvironmentGate.check(EnvFacts(isRemoteDevHost = true, isThinClient = false, projectPath = "/w/p")))

    @Test
    fun thinClient() = assertEquals(Unsupported.REMOTE, EnvironmentGate.check(EnvFacts(isRemoteDevHost = false, isThinClient = true, projectPath = "/w/p")))

    @Test
    fun remoteWinsOverWsl() =
        assertEquals(Unsupported.REMOTE, EnvironmentGate.check(EnvFacts(isRemoteDevHost = true, isThinClient = false, projectPath = """\\wsl$\Ubuntu\p""")))

    /** Windows is no longer a reason to turn Skein off; WSL and Remote Development are the only ones left. */
    @Test
    fun onlyWslAndRemoteDevelopmentTurnSkeinOff() = assertEquals(setOf(Unsupported.WSL, Unsupported.REMOTE), Unsupported.entries.toSet())

    @Test
    fun messagesSayWhy() {
        assertEquals("Skein does not support projects in WSL yet: claude and its session files live inside the Linux VM, out of Skein's reach.", Unsupported.WSL.message)
        assertEquals("Skein does not support Remote Development yet: claude runs on the remote host while this window runs locally.", Unsupported.REMOTE.message)
        assertTrue(Unsupported.entries.all { it.message.isNotBlank() })
    }
}
