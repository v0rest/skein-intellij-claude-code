package io.github.v0rest.skein.env

/** What Skein needs to know about where it runs. */
data class EnvFacts(val isWindows: Boolean, val isRemoteDevHost: Boolean, val isThinClient: Boolean, val projectPath: String?)

/** The environments Skein v1 does not support, each with the sentence the user sees. */
enum class Unsupported(val message: String) {
    WINDOWS("Skein supports local macOS and Linux for now. Windows support needs its own work and is not there yet."),
    WSL("Skein does not support projects in WSL yet: claude and its session files live inside the Linux VM, out of Skein's reach."),
    REMOTE("Skein does not support Remote Development yet: claude runs on the remote host while this window runs locally."),
}

/** Local macOS and Linux only; everything else turns Skein off with a reason. */
object EnvironmentGate {
    fun check(facts: EnvFacts): Unsupported? = when {
        facts.isRemoteDevHost || facts.isThinClient -> Unsupported.REMOTE
        facts.projectPath?.let(::isWslPath) == true -> Unsupported.WSL
        facts.isWindows -> Unsupported.WINDOWS
        else -> null
    }

    private fun isWslPath(path: String): Boolean {
        val p = path.replace('/', '\\').lowercase()
        return p.startsWith("\\\\wsl$\\") || p.startsWith("\\\\wsl.localhost\\")
    }
}
