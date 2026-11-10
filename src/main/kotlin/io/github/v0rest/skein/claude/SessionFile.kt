package io.github.v0rest.skein.claude

import com.google.gson.JsonObject

/**
 * What a running `claude` is doing, as its status file says. BACKGROUND is `shell`: the turn ended but a background
 * shell task claude started is still running, and claude resumes on its own when it finishes; Claude Code itself shows
 * that as working. UNKNOWN is a status value Skein has never seen.
 */
enum class ClaudeActivity { IDLE, BUSY, BACKGROUND, WAITING, UNKNOWN }

/** Why a waiting `claude` waits: the `waitingFor` value, reduced to what the UI distinguishes. */
enum class WaitReason { PERMISSION, QUESTION, OTHER }

fun waitReasonOf(waitingFor: String?): WaitReason = when (waitingFor) {
    "permission prompt" -> WaitReason.PERMISSION
    "input needed" -> WaitReason.QUESTION
    else -> WaitReason.OTHER
}

/**
 * One `sessions/<pid>.json` file as Claude Code 2.1.x writes it; only the fields Skein uses. [nameDerived] is true when
 * Claude Code made the [name] up itself (`nameSource: "derived"`, such as "acme-c8") because nobody named the session.
 */
data class SessionFile(
    val pid: Long,
    val sessionId: String?,
    val cwd: String?,
    val name: String?,
    val activity: ClaudeActivity,
    val rawStatus: String?,
    val waitingFor: String?,
    val statusUpdatedAt: Long?,
    val startedAt: Long?,
    val procStart: String?,
    val version: String?,
    val kind: String?,
    val nameDerived: Boolean = false,
)

sealed interface SessionFileParse {
    data class Ok(val file: SessionFile) : SessionFileParse

    /** Empty or cut-off JSON: Claude Code rewrites the file in place, so a reader can see it half-written. */
    data object Incomplete : SessionFileParse

    /** JSON that parses but is not the shape Skein knows. */
    data class Unrecognized(val reason: String) : SessionFileParse
}

object SessionFileParser {
    fun parse(text: String): SessionFileParse {
        if (text.isBlank()) return SessionFileParse.Incomplete
        val element = Json.parseOrNull(text) ?: return SessionFileParse.Incomplete
        val obj = element as? JsonObject ?: return SessionFileParse.Unrecognized("not a JSON object")
        val pid = obj.longOrNull("pid") ?: return SessionFileParse.Unrecognized("pid missing or not a number")
        val status = obj.stringOrNull("status") ?: return SessionFileParse.Unrecognized("status missing or not a string")
        return SessionFileParse.Ok(
            SessionFile(
                pid = pid,
                sessionId = obj.stringOrNull("sessionId"),
                cwd = obj.stringOrNull("cwd"),
                name = obj.stringOrNull("name"),
                activity = activityOf(status),
                rawStatus = status,
                waitingFor = obj.stringOrNull("waitingFor"),
                statusUpdatedAt = obj.longOrNull("statusUpdatedAt"),
                startedAt = obj.longOrNull("startedAt"),
                procStart = obj.stringOrNull("procStart"),
                version = obj.stringOrNull("version"),
                kind = obj.stringOrNull("kind"),
                nameDerived = obj.stringOrNull("nameSource") == "derived",
            ),
        )
    }

    private fun activityOf(status: String): ClaudeActivity = when (status) {
        "idle" -> ClaudeActivity.IDLE
        "shell" -> ClaudeActivity.BACKGROUND
        "busy" -> ClaudeActivity.BUSY
        "waiting" -> ClaudeActivity.WAITING
        else -> ClaudeActivity.UNKNOWN
    }
}
