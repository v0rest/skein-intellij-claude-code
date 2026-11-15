package io.github.v0rest.skein.env

/** What Skein needs to know about where it runs. */
data class EnvFacts(val isRemoteDevHost: Boolean, val isThinClient: Boolean, val projectPath: String?)

/** The environments Skein does not support, each with the sentence the user sees. */
enum class Unsupported(val message: String) {
    WSL("Skein does not support projects in WSL yet: claude and its session files live inside the Linux VM, out of Skein's reach."),
    REMOTE("Skein does not support Remote Development yet: claude runs on the remote host while this window runs locally."),
}

/** Local macOS, Linux and Windows; a project in WSL or Remote Development turns Skein off with a reason. */
object EnvironmentGate {
    fun check(facts: EnvFacts): Unsupported? = when {
        facts.isRemoteDevHost || facts.isThinClient -> Unsupported.REMOTE
        facts.projectPath?.let(::isWslPath) == true -> Unsupported.WSL
        else -> null
    }

    private fun isWslPath(path: String): Boolean {
        val p = path.replace('/', '\\').lowercase()
        return p.startsWith("\\\\wsl$\\") || p.startsWith("\\\\wsl.localhost\\")
    }
}
