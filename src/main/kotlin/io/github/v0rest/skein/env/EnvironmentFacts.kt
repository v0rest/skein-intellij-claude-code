package io.github.v0rest.skein.env

import com.intellij.openapi.project.Project
import com.intellij.platform.ide.productMode.IdeProductMode

/**
 * How Skein tells where it runs without internal API. The platform's own checks (AppMode.isRemoteDevHost,
 * PlatformUtils.isJetBrainsClient) are `@ApiStatus.Internal` at class level, so this reads the same facts from public
 * sources, two witnesses each. The platform's product mode (`IdeProductMode.isBackend` / `isFrontend`, public static
 * with no ApiStatus mark, checked with javap on the 2026.3 jars on 2026-11-14) follows those internal checks. The
 * command the IDE process was launched with (AppMode derives remote-dev host mode from the same command names, checked
 * with javap on 2026-11-06) and the `idea.platform.prefix` system property that PlatformUtils compares with
 * "JetBrainsClient" stay as the second witness. The command alone misses a host whose native launcher starts the JVM
 * in its own process, and every host on Windows, where the JDK reports no arguments for any process, the IDE's own
 * included (checked on Windows 11 on 2026-11-14).
 */
object EnvironmentProbe {
    private val REMOTE_DEV_HOST_COMMANDS = setOf(
        "remoteDevHost", "cwmHost", "cwmHostNoLobby", "serverMode", "splitMode", "ijLightHost", "rdserver-headless",
    )

    /** True when the launch command (the first argument that is not an option) is a remote-development host mode. */
    fun isRemoteDevHost(processArguments: List<String>): Boolean =
        processArguments.firstOrNull { !it.startsWith("-") } in REMOTE_DEV_HOST_COMMANDS

    /** A remote-development host by either witness: the product mode says backend, or the launch command says so. */
    fun isRemoteDevHost(backendMode: Boolean, processArguments: List<String>): Boolean = backendMode || isRemoteDevHost(processArguments)

    fun isThinClient(platformPrefix: String?): Boolean = platformPrefix == "JetBrainsClient"

    /** The JetBrains Client (or a light frontend) by either witness: the product mode, or the platform prefix. */
    fun isThinClient(frontendMode: Boolean, platformPrefix: String?): Boolean = frontendMode || isThinClient(platformPrefix)
}

fun currentEnvFacts(project: Project): EnvFacts = EnvFacts(
    isRemoteDevHost = EnvironmentProbe.isRemoteDevHost(
        backendMode = IdeProductMode.isBackend,
        processArguments = ProcessHandle.current().info().arguments().orElse(emptyArray()).toList(),
    ),
    isThinClient = EnvironmentProbe.isThinClient(frontendMode = IdeProductMode.isFrontend, platformPrefix = System.getProperty("idea.platform.prefix")),
    projectPath = project.basePath,
)
