package io.github.v0rest.skein.git

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/** What git said; [exitCode] is -1 when git could not be started or was stopped at its timeout ([timedOut]). */
data class GitResult(val exitCode: Int, val stdout: String, val stderr: String, val timedOut: Boolean) {
    val ok: Boolean get() = exitCode == 0 && !timedOut

    /** False when git could not be started or did not finish: then its answer says nothing about the repository. */
    val ran: Boolean get() = exitCode >= 0 && !timedOut

    /** Why git failed, for a person to read: that it did not finish in time, or the first line it wrote ([firstLine]). */
    fun reason(): String = if (timedOut) TIMED_OUT else firstLine(stderr)

    /**
     * What git said when it refused, all of it and as it said it, for a person to read before deciding: its error
     * output cut after [limit] characters, or [reason] when it wrote nothing or did not finish.
     */
    fun message(limit: Int = MESSAGE_LIMIT): String {
        val text = stderr.trim()
        if (timedOut || text.isEmpty()) return reason()
        return if (text.length <= limit) text else text.take(limit).trimEnd() + "…"
    }

    companion object {
        const val TIMED_OUT = "git did not finish in time"
        private const val NO_REASON = "git gave no reason"

        /** How much of git's own words [message] keeps. */
        const val MESSAGE_LIMIT = 400

        /** The first line git wrote, without its `fatal:` or `error:`; git's hints on later lines are left out. */
        fun firstLine(stderr: String): String {
            val line = stderr.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: return NO_REASON
            return line.removePrefix("fatal:").removePrefix("error:").trim().ifEmpty { NO_REASON }
        }
    }
}

/** Runs git in a folder. Blocking: call it off the EDT. */
interface GitRunner {
    fun run(dir: Path, vararg args: String): GitResult
}

/**
 * [git] with `--no-optional-locks` before every command, which git documents as the same as `GIT_OPTIONAL_LOCKS=0`: a
 * read never takes the index lock to refresh the index, so it cannot get in the way of the user's own git in that
 * worktree. Skein changes no process environment, hence the option instead of the variable.
 */
class NoOptionalLocks(private val git: GitRunner) : GitRunner {
    override fun run(dir: Path, vararg args: String): GitResult = git.run(dir, "--no-optional-locks", *args)
}

/**
 * The only place Skein starts a process, and it only ever runs git. Never throws. git still running after
 * [timeoutMillis] is stopped and its result reads timed out.
 */
class ProcessGitRunner(private val timeoutMillis: Int = TIMEOUT_MILLIS) : GitRunner {
    override fun run(dir: Path, vararg args: String): GitResult {
        val commandLine = GeneralCommandLine("git", *args)
            .withWorkingDirectory(dir)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        return try {
            val output = CapturingProcessHandler(commandLine).runProcess(timeoutMillis)
            GitResult(if (output.isTimeout) -1 else output.exitCode, output.stdout, output.stderr, output.isTimeout)
        } catch (e: ExecutionException) {
            GitResult(-1, "", e.message ?: "git could not be started", false)
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000
    }
}
