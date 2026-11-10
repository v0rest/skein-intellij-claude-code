package io.github.v0rest.skein.claude

import java.io.IOException
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * A session's transcript under the Claude home: `projects/<slug of the cwd claude started in>/<sessionId>.jsonl`.
 * Claude Code slugs its resolved `getcwd()`, so the cwd its status file reported comes first, but only when a
 * transcript is there (the status cwd may follow a `cd`); then the thread's folder with symlinks resolved, then the
 * folder as given.
 */
object Transcripts {
    /** How much of a transcript's end one read takes: enough for the last few entries, bounded whatever the session's size. */
    const val TAIL_BYTES = 64 * 1024

    fun path(home: ClaudeHome, folder: String, sessionCwd: String?, sessionId: String): Path {
        val candidates = listOfNotNull(sessionCwd?.takeIf(String::isNotBlank), resolved(folder), folder).distinct()
            .map { home.projectsDir.resolve(Slug.of(it)).resolve("$sessionId.jsonl") }
        return candidates.firstOrNull { home.stamp(it) != null } ?: candidates.last()
    }

    /**
     * The whole lines in the last [TAIL_BYTES] bytes of the transcript, oldest first; empty when the file is missing
     * or unreadable. When the file is longer than the chunk, the line the chunk cuts at its start is left out; a line
     * cut at the end of the file stays for the parser to skip. An entry longer than the chunk is lost with it, so a
     * huge last answer gives no content.
     */
    fun tailLines(home: ClaudeHome, folder: String, sessionCwd: String?, sessionId: String): List<String> {
        val file = path(home, folder, sessionCwd, sessionId)
        val size = home.stamp(file)?.size ?: return emptyList()
        val bytes = home.readTail(file, TAIL_BYTES) ?: return emptyList()
        val lines = String(bytes, Charsets.UTF_8).split('\n')
        return if (size > TAIL_BYTES) lines.drop(1) else lines
    }

    /** The folder with symlinks resolved, as `getcwd()` reports it; the folder itself when it does not exist or is odd. */
    private fun resolved(folder: String): String =
        try {
            Path.of(folder).toAbsolutePath().normalize().toRealPath().toString()
        } catch (e: IOException) {
            folder
        } catch (e: InvalidPathException) {
            folder
        }
}
