package io.github.v0rest.skein.env

import com.intellij.openapi.project.Project
import com.intellij.openapi.util.SystemInfo

/**
 * How Skein tells where it runs without internal API. The platform's own checks (AppMode.isRemoteDevHost,
 * PlatformUtils.isJetBrainsClient) are `@ApiStatus.Internal` at class level, so this reads the same facts from public
 * sources: the command the IDE process was launched with (AppMode derives remote-dev host mode from the same
 * command names, checked with javap on 2026-11-06), and the `idea.platform.prefix` system property that
 * PlatformUtils compares with "JetBrainsClient".
 */
object EnvironmentProbe {
    private val REMOTE_DEV_HOST_COMMANDS = setOf(
        "remoteDevHost", "cwmHost", "cwmHostNoLobby", "serverMode", "splitMode", "ijLightHost", "rdserver-headless",
    )

    /** True when the launch command (the first argument that is not an option) is a remote-development host mode. */
    fun isRemoteDevHost(processArguments: List<String>): Boolean =
        processArguments.firstOrNull { !it.startsWith("-") } in REMOTE_DEV_HOST_COMMANDS

    fun isThinClient(platformPrefix: String?): Boolean = platformPrefix == "JetBrainsClient"
}

fun currentEnvFacts(project: Project): EnvFacts = EnvFacts(
    isWindows = SystemInfo.isWindows,
    isRemoteDevHost = EnvironmentProbe.isRemoteDevHost(ProcessHandle.current().info().arguments().orElse(emptyArray()).toList()),
    isThinClient = EnvironmentProbe.isThinClient(System.getProperty("idea.platform.prefix")),
    projectPath = project.basePath,
)
