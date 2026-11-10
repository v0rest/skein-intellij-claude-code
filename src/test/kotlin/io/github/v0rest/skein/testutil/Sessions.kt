package io.github.v0rest.skein.testutil

import io.github.v0rest.skein.claude.ClaudeActivity
import io.github.v0rest.skein.claude.SessionFile

/** A SessionFile with the fields a test cares about; everything else null. */
fun sessionFile(
    pid: Long = 1,
    activity: ClaudeActivity = ClaudeActivity.IDLE,
    waitingFor: String? = null,
    sessionId: String? = null,
    cwd: String? = null,
    name: String? = null,
    startedAt: Long? = null,
    statusUpdatedAt: Long? = null,
    kind: String? = "interactive",
    nameDerived: Boolean = false,
): SessionFile = SessionFile(
    pid = pid,
    sessionId = sessionId,
    cwd = cwd,
    name = name,
    activity = activity,
    rawStatus = activity.name.lowercase(),
    waitingFor = waitingFor,
    statusUpdatedAt = statusUpdatedAt,
    startedAt = startedAt,
    procStart = null,
    version = "2.1.318",
    kind = kind,
    nameDerived = nameDerived,
)
