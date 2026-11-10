package io.github.v0rest.skein.testutil

import io.github.v0rest.skein.claude.ClaudeHome
import java.nio.file.Path

/** A ClaudeHome that counts the bytes each read returned, per file, so tests can bound I/O. */
class CountingClaudeHome(root: Path) : ClaudeHome(root) {
    val bytesRead = mutableMapOf<Path, Long>()

    private fun count(path: Path, bytes: Int) {
        bytesRead[path] = (bytesRead[path] ?: 0L) + bytes
    }

    override fun readText(path: Path, maxBytes: Int): String? =
        super.readText(path, maxBytes)?.also { count(path, it.toByteArray().size) }

    override fun readHead(path: Path, maxBytes: Int): ByteArray? = super.readHead(path, maxBytes)?.also { count(path, it.size) }

    override fun readTail(path: Path, maxBytes: Int): ByteArray? = super.readTail(path, maxBytes)?.also { count(path, it.size) }
}
