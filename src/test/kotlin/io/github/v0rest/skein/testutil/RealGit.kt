package io.github.v0rest.skein.testutil

import com.intellij.execution.ExecutionException
import com.intellij.execution.configurations.GeneralCommandLine
import com.intellij.execution.process.CapturingProcessHandler
import io.github.v0rest.skein.git.GitBytes
import io.github.v0rest.skein.git.GitResult
import io.github.v0rest.skein.git.GitRunner
import io.github.v0rest.skein.git.ProcessGitRunner
import org.junit.Assert.assertTrue
import org.junit.rules.TemporaryFolder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/**
 * The real git for tests in a temporary folder, with the machine's own settings kept out of the way: no global excludes
 * file (one might ignore the worktrees already), no hooks, no commit signing, a made-up identity for the commits, and
 * git's messages in English whatever the machine's language ([EnglishGit]), since Skein matches one of them.
 */
class RealGit(private val folder: TemporaryFolder) : GitRunner {
    private val runner = EnglishGit()
    private val bytesRunner = ProcessGitRunner(30_000)
    private val settings = listOf(
        "-c", "core.excludesFile=" + folder.newFile(),
        "-c", "core.hooksPath=" + folder.newFolder(),
        "-c", "commit.gpgsign=false",
        "-c", "user.name=Test",
        "-c", "user.email=test@example.invalid",
    )

    /** False when git is not installed: the tests that need it are skipped then. */
    val available: Boolean get() = runner.run(folder.root.toPath(), "--version").ok

    override fun run(dir: Path, vararg args: String): GitResult = runner.run(dir, *(settings + args).toTypedArray())

    /** Bytes as Skein itself reads them ([ProcessGitRunner.bytes]), with the same settings; a file's bytes speak no language. */
    override fun bytes(dir: Path, vararg args: String): GitBytes = bytesRunner.bytes(dir, *(settings + args).toTypedArray())

    /**
     * A new repository [name] with one commit that holds `a.c`, the [gitignore] lines as `.gitignore` when given, and
     * [files] (relative path to text).
     */
    fun repository(name: String = "acme", gitignore: List<String>? = null, files: Map<String, String> = emptyMap()): Path {
        val repo = folder.newFolder(name).toPath()
        assertTrue(run(repo, "init", "-q").ok)
        Files.writeString(repo.resolve("a.c"), "int a;\n")
        if (gitignore != null) Files.writeString(repo.resolve(".gitignore"), gitignore.joinToString("\n", postfix = "\n"))
        for ((path, text) in files) {
            val file = repo.resolve(path)
            Files.createDirectories(file.parent)
            Files.writeString(file, text)
        }
        assertTrue(run(repo, "add", "-A").ok)
        assertTrue(run(repo, "commit", "-q", "-m", "one").ok)
        return repo
    }
}

/**
 * [io.github.v0rest.skein.git.ProcessGitRunner] as a test runs it: the same git, with `LC_ALL=C` so it speaks English. Only tests change a
 * process environment; Skein itself never does.
 */
class EnglishGit(private val timeoutMillis: Int = 30_000) : GitRunner {
    override fun run(dir: Path, vararg args: String): GitResult {
        val commandLine = GeneralCommandLine("git", *args)
            .withWorkingDirectory(dir)
            .withCharset(StandardCharsets.UTF_8)
            .withParentEnvironmentType(GeneralCommandLine.ParentEnvironmentType.CONSOLE)
            .withEnvironment("LC_ALL", "C")
        return try {
            val output = CapturingProcessHandler(commandLine).runProcess(timeoutMillis)
            GitResult(if (output.isTimeout) -1 else output.exitCode, output.stdout, output.stderr, output.isTimeout)
        } catch (e: ExecutionException) {
            GitResult(-1, "", e.message ?: "git could not be started", false)
        }
    }
}
