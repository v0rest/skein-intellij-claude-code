package io.github.v0rest.skein.notify

import com.intellij.ide.impl.ProjectUtil
import com.intellij.notification.Notification
import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroup
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.SystemNotifications
import io.github.v0rest.skein.claude.ClaudeHome
import io.github.v0rest.skein.claude.SessionTitles
import io.github.v0rest.skein.claude.Transcripts
import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.settings.SkeinSettings
import io.github.v0rest.skein.threads.DisplayNames
import io.github.v0rest.skein.threads.NotificationKind
import io.github.v0rest.skein.threads.SessionMarksService
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import io.github.v0rest.skein.ui.SkeinIcons
import java.awt.Toolkit

/** The words of Skein's notifications and whether the user wants them. */
object NotificationText {
    /** The balloon's title: the thread's Skein name, nothing else. */
    fun title(name: String): String = name

    /** The balloon's subtitle: what happened. */
    fun subtitle(thread: SkeinThread, kind: NotificationKind): String = when (kind) {
        NotificationKind.TURN_FINISHED -> "finished its turn"
        NotificationKind.NEEDS_YOU -> when ((thread.state as? ThreadState.NeedsYou)?.reason) {
            WaitReason.PERMISSION -> "needs your permission"
            WaitReason.QUESTION -> "has a question"
            WaitReason.OTHER, null -> "needs you"
        }
    }

    /**
     * The balloon's text: the session's name as the History shows it, or null when there is none yet or it only
     * repeats the [title]. No answer, tool call or question of claude's goes here.
     */
    fun text(title: String, sessionName: String?): String? =
        sessionName?.let(NotificationContent::collapse)?.takeIf { it.isNotEmpty() && it != title.trim() }?.let(NotificationContent::cut)

    /** The session's name: the user's own one from the marks, else the transcript's title; a failing [transcriptTitle] gives null. */
    fun sessionName(markName: String?, transcriptTitle: () -> String?): String? =
        markName ?: try {
            transcriptTitle()
        } catch (e: Exception) {
            null
        }

    /**
     * The text of the OS notification, plain: the event, then the [content] (see [text]) on its own line when there is
     * one. The macOS sender strips anything shaped like a tag, so `<` and `>` become their single-angle-quote cousins.
     */
    fun osText(subtitle: String, content: String?): String =
        (if (content == null) subtitle else "$subtitle\n$content").replace('<', '‹').replace('>', '›')

    fun enabled(kind: NotificationKind, settings: SkeinSettings.State): Boolean = when (kind) {
        NotificationKind.NEEDS_YOU -> settings.notifyNeedsYou
        NotificationKind.TURN_FINISHED -> settings.notifyTurnFinished
    }

    fun sound(kind: NotificationKind, settings: SkeinSettings.State): Boolean = when (kind) {
        NotificationKind.NEEDS_YOU -> settings.soundNeedsYou
        NotificationKind.TURN_FINISHED -> settings.soundTurnFinished
    }

    fun missingStatusFiles(minVersion: String): String =
        "Skein could not read session status. Check that claude starts in the IDE terminal and is Claude Code $minVersion or newer."
}

/**
 * Shows Skein's notifications in the project, one balloon per thread, and, when the IDE is in the background, sends
 * the same words to the system notification centre itself (the platform forwards nothing there on its own; the
 * user's switch under Settings | Appearance & Behavior | Notifications is honoured by the platform's sender).
 * [notify] reads the transcript on the thread it is called from, so it runs off the EDT; the balloons themselves
 * are touched on the EDT only.
 */
class ThreadNotifier(private val project: Project, private val open: (threadId: String) -> Unit) {
    private val balloons = Balloons<Notification> { it.expire() }

    fun notify(thread: SkeinThread, kind: NotificationKind) {
        val settings = SkeinSettings.getInstance().state
        if (NotificationText.sound(kind, settings)) Toolkit.getDefaultToolkit().beep()
        if (!NotificationText.enabled(kind, settings)) {
            // the thread moved on, so the balloon it had describes a state that no longer holds
            dismiss(thread.id)
            return
        }
        val names = SessionMarksService.getInstance(project).marks.value.names
        val title = NotificationText.title(DisplayNames.thread(thread, names))
        val subtitle = NotificationText.subtitle(thread, kind)
        val content = NotificationText.text(title, sessionNameQuietly(thread, names))
        onEdt {
            // title and content are HTML to the platform: a `<` in a name must stay a `<`
            val notification = group(kind).createNotification(StringUtil.escapeXmlEntities(title), content?.let(StringUtil::escapeXmlEntities).orEmpty(), NotificationType.INFORMATION)
                .setSubtitle(subtitle)
                .setIcon(SkeinIcons.forKind(kind))
                .addAction(
                    NotificationAction.createSimpleExpiring("Open") {
                        ProjectUtil.focusProjectWindow(project, true)
                        open(thread.id)
                    },
                )
            notification.whenExpired { balloons.expired(thread.id, notification) }
            balloons.replace(thread.id, notification)
            notification.notify(project)
            if (!ApplicationManager.getApplication().isActive) notifySystemQuietly(title, NotificationText.osText(subtitle, content))
        }
    }

    /** Set once the OS notification failed, so one broken notifier does not fill the log on every event. */
    private var systemNotifyFailed = false

    /** The IDE is in the background: the same words to the OS notification centre; a failure there is logged once and never costs the balloon. */
    private fun notifySystemQuietly(title: String, text: String) {
        try {
            SystemNotifications.getInstance().notify(SYSTEM_NOTIFICATION_NAME, title, text)
        } catch (e: Exception) {
            if (!systemNotifyFailed) LOG.warn("Skein could not send a system notification", e)
            systemNotifyFailed = true
        }
    }

    /** The thread came into view or its tab closed: its balloon goes away. */
    fun dismiss(threadId: String) = onEdt { balloons.dismiss(threadId) }

    fun missingStatusFiles(minVersion: String) {
        group(GROUP_ID).createNotification(NotificationText.missingStatusFiles(minVersion), NotificationType.WARNING).notify(project)
    }

    /**
     * The session's name as the History shows it: the user's own name from the marks, else the title in the transcript
     * (read off the EDT, bounded, only when there is no own name). Null for a thread without a session, a session without
     * a name, or any failure: that is an empty text, never a lost balloon.
     */
    private fun sessionNameQuietly(thread: SkeinThread, names: Map<String, String>): String? {
        val sessionId = thread.sessionId ?: return null
        return NotificationText.sessionName(names[sessionId]) {
            val home = ClaudeHome.default()
            SessionTitles.read(home, Transcripts.path(home, thread.folder, thread.sessionCwd, sessionId))
        }
    }

    private fun onEdt(block: () -> Unit) = ApplicationManager.getApplication().invokeLater(block, ModalityState.any(), project.disposed)

    private fun group(kind: NotificationKind): NotificationGroup = group(
        when (kind) {
            NotificationKind.NEEDS_YOU -> NEEDS_YOU_GROUP_ID
            NotificationKind.TURN_FINISHED -> GROUP_ID
        },
    )

    private fun group(id: String): NotificationGroup = NotificationGroupManager.getInstance().getNotificationGroup(id)

    companion object {
        private val LOG = logger<ThreadNotifier>()

        /** Balloons that fade: a finished turn, Skein's warnings. */
        const val GROUP_ID = "Skein"

        /** Balloons that stay until acted on: a thread that needs the user. */
        const val NEEDS_YOU_GROUP_ID = "Skein.NeedsYou"

        /** What the OS notification centre files Skein's notifications under. */
        private const val SYSTEM_NOTIFICATION_NAME = "Skein"
    }
}
