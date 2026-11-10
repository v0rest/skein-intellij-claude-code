package io.github.v0rest.skein.testutil

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.io.path.isDirectory
import kotlin.io.path.readBytes
import kotlin.io.path.relativeTo
import kotlin.streams.asSequence

/** Relative path -> sha-256 of the content, or "<dir>" for a directory. Equal maps mean an unchanged tree. */
object TreeDigest {
    fun of(root: Path): Map<String, String> =
        Files.walk(root).use { stream ->
            stream.asSequence()
                .filter { it != root }
                .map { path ->
                    val key = path.relativeTo(root).toString()
                    key to if (path.isDirectory()) "<dir>" else sha256(path.readBytes())
                }
                .sortedBy { it.first }
                .toMap()
        }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
