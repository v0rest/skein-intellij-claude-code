package io.github.v0rest.skein.claude

import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import io.github.v0rest.skein.paths.Containment
import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle
import java.io.IOException
import java.io.Reader
import java.io.StringReader

/** Whether Claude Code was told to trust a folder, as its global config says ([FolderTrustReader]). */
enum class FolderTrust {
    /** The folder, or one above it, had its trust question answered yes. */
    TRUSTED,

    /** The config was read and nothing in it trusts the folder, so claude asks before it does anything there. */
    NOT_TRUSTED,

    /** No config, one Skein cannot read or does not recognise, or a doubt - Skein then behaves as if it never asked. */
    UNKNOWN,
}

/**
 * claude's answer to its folder-trust question, from Claude Code's global config `.claude.json`
 * ([ClaudeHome.readConfig]) - `projects["<absolute folder>"].hasTrustDialogAccepted`, the folder as claude wrote it
 * (forward slashes on Windows). Public reports say a folder below a trusted one counts as trusted and the home folder
 * itself is never stored as trusted - unconfirmed, so a yes on any folder above counts and the home folder gets no
 * rule of its own. The file holds the user's account too, so the read streams through it and keeps nothing but each
 * folder's answer, and nothing from it is ever logged. Never throws, never writes.
 */
object FolderTrustReader {
    /** What one entry of `projects` says. */
    enum class Accepted {
        /** `hasTrustDialogAccepted` is true. */
        YES,

        /** It is false or missing. */
        NO,

        /** The entry or the value is not what Claude Code writes, so it tells nothing for sure. */
        ODD,
    }

    /** The file grows with every project, and past this a read would hold up the caller for too long, so the answer is UNKNOWN. */
    const val MAX_BYTES: Long = 64L shl 20

    private const val PROJECTS = "projects"
    private const val ACCEPTED = "hasTrustDialogAccepted"

    /**
     * The trust of [folder], named as the IDE has it and as the disk resolves it ([PhysicalPath]), since claude keys
     * the file by its own working directory. Reads the whole file on every call, blocking, so never on the EDT.
     */
    fun read(home: ClaudeHome, folder: String, style: PathStyle = PathStyle.current, maxBytes: Long = MAX_BYTES): FolderTrust =
        try {
            decide(home.readConfig(maxBytes, ::parse), listOf(folder, PhysicalPath.of(folder, style)).distinct(), style)
        } catch (e: RuntimeException) {
            FolderTrust.UNKNOWN
        }

    /** The trust of [folders] (names of one folder) in the config [text], null for no file. */
    fun of(text: String?, folders: List<String>, style: PathStyle = PathStyle.current): FolderTrust =
        decide(text?.let { parse(StringReader(it)) }, folders, style)

    /**
     * Each folder of `projects` with its answer, the last of two equal keys winning as in JavaScript, or null when the
     * text is not one strict JSON object with a `projects` object in it. Everything else is skipped without being kept.
     */
    fun parse(reader: Reader): Map<String, Accepted>? =
        try {
            val json = JsonReader(reader).apply { strictness = Strictness.STRICT }
            var projects: Map<String, Accepted>? = null
            json.beginObject()
            while (json.hasNext()) {
                if (json.nextName() == PROJECTS) projects = projectsOf(json) ?: return null else json.skipValue()
            }
            json.endObject()
            if (json.peek() != JsonToken.END_DOCUMENT) null else projects
        } catch (e: IOException) {
            null
        } catch (e: RuntimeException) {
            // a token of another type than the shape promised - Gson says so with IllegalStateException
            null
        }

    private fun projectsOf(json: JsonReader): Map<String, Accepted>? {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return null
        }
        val projects = LinkedHashMap<String, Accepted>()
        json.beginObject()
        while (json.hasNext()) {
            val folder = json.nextName()
            projects.remove(folder) // a later equal key replaces the earlier one
            projects[folder] = acceptedOf(json)
        }
        json.endObject()
        return projects
    }

    private fun acceptedOf(json: JsonReader): Accepted {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue()
            return Accepted.ODD
        }
        var accepted = Accepted.NO
        json.beginObject()
        while (json.hasNext()) {
            if (json.nextName() != ACCEPTED) {
                json.skipValue()
            } else if (json.peek() == JsonToken.BOOLEAN) {
                accepted = if (json.nextBoolean()) Accepted.YES else Accepted.NO
            } else {
                json.skipValue()
                accepted = Accepted.ODD
            }
        }
        json.endObject()
        return accepted
    }

    /**
     * TRUSTED when a yes stands on one of [folders] or on a folder above it. A doubt - a yes whose folder only the disk
     * could place ([PathRules.containment] UNSURE), an entry Skein cannot read on the folder or above it, a folder that is
     * not absolute, no [projects] at all - is UNKNOWN, never NOT_TRUSTED, since only NOT_TRUSTED changes what Skein shows.
     */
    fun decide(projects: Map<String, Accepted>?, folders: List<String>, style: PathStyle = PathStyle.current): FolderTrust {
        if (projects == null || folders.isEmpty()) return FolderTrust.UNKNOWN
        val each = folders.map { decideOne(projects, it, style) }
        return when {
            FolderTrust.TRUSTED in each -> FolderTrust.TRUSTED
            FolderTrust.UNKNOWN in each -> FolderTrust.UNKNOWN
            else -> FolderTrust.NOT_TRUSTED
        }
    }

    private fun decideOne(projects: Map<String, Accepted>, folder: String, style: PathStyle): FolderTrust {
        if (!PathRules.isAbsolute(folder, style)) return FolderTrust.UNKNOWN
        var doubt = false
        for ((key, accepted) in projects) {
            when (PathRules.containment(folder, key, style)) {
                Containment.OUTSIDE -> Unit
                Containment.INSIDE -> when (accepted) {
                    Accepted.YES -> return FolderTrust.TRUSTED
                    Accepted.NO -> Unit
                    Accepted.ODD -> doubt = true
                }
                Containment.UNSURE -> if (accepted != Accepted.NO) doubt = true
            }
        }
        return if (doubt) FolderTrust.UNKNOWN else FolderTrust.NOT_TRUSTED
    }
}
