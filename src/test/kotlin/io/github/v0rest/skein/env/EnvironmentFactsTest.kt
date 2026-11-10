package io.github.v0rest.skein.env

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentFactsTest {
    @Test
    fun remoteDevHostCommandsAreRecognised() {
        for (command in listOf("remoteDevHost", "cwmHost", "cwmHostNoLobby", "serverMode", "splitMode", "ijLightHost", "rdserver-headless")) {
            assertTrue(command, EnvironmentProbe.isRemoteDevHost(listOf("-Xmx2g", command, "/w/p")))
        }
    }

    @Test
    fun anOrdinaryLaunchIsNotARemoteHost() {
        assertFalse(EnvironmentProbe.isRemoteDevHost(emptyList()))
        assertFalse(EnvironmentProbe.isRemoteDevHost(listOf("/w/p")))
        assertFalse(EnvironmentProbe.isRemoteDevHost(listOf("-Didea.x=1", "diff", "a", "b")))
    }

    @Test
    fun onlyTheCommandPositionCounts() =
        assertFalse(EnvironmentProbe.isRemoteDevHost(listOf("/w/p", "serverMode")))

    @Test
    fun jetBrainsClientIsRecognisedByItsPlatformPrefix() {
        assertTrue(EnvironmentProbe.isThinClient("JetBrainsClient"))
        assertFalse(EnvironmentProbe.isThinClient("idea"))
        assertFalse(EnvironmentProbe.isThinClient(null))
    }
}
