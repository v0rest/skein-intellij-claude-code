package io.github.v0rest.skein.git

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import com.intellij.execution.process.ProcessEvent
import com.intellij.execution.process.ProcessListener
import com.intellij.execution.process.ProcessOutputType
import com.intellij.openapi.util.Key
import com.intellij.util.io.BaseOutputReader
import java.nio.charset.Charset
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

/**
 * What git wrote to its standard output, byte for byte, for output that is not text, a file's content say; [result]
 * says how git ended and has no stdout of its own.
 */
class GitBytes(val result: GitResult, val bytes: ByteArray) {
    val ok: Boolean get() = result.ok
}

/** Runs git in a folder. Blocking: call it off the EDT. */
interface GitRunner {
    fun run(dir: Path, vararg args: String): GitResult

    /**
     * [run] for output that is not text: git's standard output as the bytes it wrote ([GitBytes]). [ProcessGitRunner]
     * reads them as they come; this default, for runners that answer with text such as a test's scripted git, hands
     * their text back in UTF-8.
     */
    fun bytes(dir: Path, vararg args: String): GitBytes {
        val result = run(dir, *args)
        return GitBytes(result.copy(stdout = ""), result.stdout.toByteArray(StandardCharsets.UTF_8))
    }
}

/**
 * [git] with `--no-optional-locks` before every command, which git documents as the same as `GIT_OPTIONAL_LOCKS=0`: a
 * read never takes the index lock to refresh the index, so it cannot get in the way of the user's own git in that
 * worktree. Skein changes no process environment, hence the option instead of the variable.
 */
class NoOptionalLocks(private val git: GitRunner) : GitRunner {
    override fun run(dir: Path, vararg args: String): GitResult = git.run(dir, "--no-optional-locks", *args)

    override fun bytes(dir: Path, vararg args: String): GitBytes = git.bytes(dir, "--no-optional-locks", *args)
}

/**
 * The only place Skein starts a process, and it only ever runs git. Never throws. git still running after
 * [timeoutMillis] is stopped and its result reads timed out.
 */
class ProcessGitRunner(private val timeoutMillis: Int = TIMEOUT_MILLIS) : GitRunner {
    override fun run(dir: Path, vararg args: String): GitResult = capture(dir, args, StandardCharsets.UTF_8, raw = null)

    /**
     * git's output as it wrote it: read as ISO-8859-1, which maps every byte to the character of the same value, not
     * split into lines, which would fold a CRLF into a LF ([RawOutput]), and taken as it arrives, before the platform's
     * capture lets a backspace delete the character before it. Its error output is UTF-8 text again for
     * [GitResult.reason].
     */
    override fun bytes(dir: Path, vararg args: String): GitBytes {
        val stdout = StringBuilder()
        val result = capture(dir, args, StandardCharsets.ISO_8859_1, stdout)
        val stderr = String(result.stderr.toByteArray(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8)
        val bytes = synchronized(stdout) { stdout.toString() }.toByteArray(StandardCharsets.ISO_8859_1)
        return GitBytes(result.copy(stdout = "", stderr = stderr), bytes)
    }

    /** git's text as the platform captures it, and with [raw] also its standard output as it came. */
    private fun capture(dir: Path, args: Array<out String>, charset: Charset, raw: StringBuilder?): GitResult {
        val commandLine = GeneralCommandLine("git", *args)
            .withWorkingDirectory(dir)
            .withCharset(charset)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
        return try {
            val handler = if (raw == null) CapturingProcessHandler(commandLine) else RawOutput(commandLine).also { it.addProcessListener(Collect(raw)) }
            val output = handler.runProcess(timeoutMillis)
            GitResult(if (output.isTimeout) -1 else output.exitCode, if (raw == null) output.stdout else "", output.stderr, output.isTimeout)
        } catch (e: ExecutionException) {
            GitResult(-1, "", e.message ?: "git could not be started", false)
        }
    }

    /** The platform's capture with git's output handed on in chunks rather than lines, so no line end changes. */
    private class RawOutput(commandLine: GeneralCommandLine) : CapturingProcessHandler(commandLine) {
        override fun readerOptions(): BaseOutputReader.Options = UNSPLIT
    }

    /** Appends the standard output to [into] chunk by chunk, as the reader hands it on. */
    private class Collect(private val into: StringBuilder) : ProcessListener {
        override fun onTextAvailable(event: ProcessEvent, outputType: Key<*>) {
            if (ProcessOutputType.isStdout(outputType)) synchronized(into) { into.append(event.text) }
        }
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000

        val UNSPLIT: BaseOutputReader.Options = object : BaseOutputReader.Options() {
            override fun splitToLines(): Boolean = false
        }
    }
}
