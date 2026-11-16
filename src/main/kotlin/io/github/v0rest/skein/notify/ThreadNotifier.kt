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
import com.intellij.openapi.wm.IdeFocusManager
import com.intellij.openapi.wm.IdeFrame
import com.intellij.ui.SystemNotifications
import com.intellij.util.EnvironmentUtil
import io.github.v0rest.skein.claude.ClaudeHome
import io.github.v0rest.skein.claude.ClaudeLocator
import io.github.v0rest.skein.claude.SessionTitles
import io.github.v0rest.skein.claude.Transcripts
import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.settings.SkeinSettings
import io.github.v0rest.skein.sound.SoundChoice
import io.github.v0rest.skein.sound.SoundChoices
import io.github.v0rest.skein.sound.SoundPlayer
import io.github.v0rest.skein.threads.DisplayNames
import io.github.v0rest.skein.threads.NotificationKind
import io.github.v0rest.skein.threads.SessionMarksService
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import io.github.v0rest.skein.ui.SkeinIcons
import io.github.v0rest.skein.ui.WorktreeStart
import java.nio.file.Files
import java.nio.file.Path

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
            WaitReason.TRUST -> "asks whether to trust this folder"
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

    /** Where a thread's notification shows: [inFront] in the project the owner looks at instead of its own, [system] also in the OS. */
    data class Placement(val inFront: Boolean, val system: Boolean)

    /**
     * When another project's window - or its tab, when the IDE keeps projects as tabs of one window - is in front, or
     * was the last one before the IDE went to the background, a balloon in this project's own frame would sit where
     * nobody sees it and only the sound would come through, so it shows in that project instead ([fromProject] names
     * its own), where the owner looks or comes back to. With the IDE in the background the OS gets the same words too.
     */
    fun placement(appActive: Boolean, anotherProjectInFront: Boolean): Placement =
        Placement(inFront = anotherProjectInFront, system = !appActive)

    /** The subtitle of a balloon shown in another project: which project the thread is in, then what happened. */
    fun fromProject(projectName: String, subtitle: String): String = "${StringUtil.escapeXmlEntities(projectName)} · $subtitle"

    fun enabled(kind: NotificationKind, settings: SkeinSettings.State): Boolean = when (kind) {
        NotificationKind.NEEDS_YOU -> settings.notifyNeedsYou
        NotificationKind.TURN_FINISHED -> settings.notifyTurnFinished
    }

    fun sound(kind: NotificationKind, settings: SkeinSettings.State): Boolean = when (kind) {
        NotificationKind.NEEDS_YOU -> settings.soundNeedsYou
        NotificationKind.TURN_FINISHED -> settings.soundTurnFinished
    }

    /** The sound the event plays when its switch is on - whatever the setting holds, at worst the beep. */
    fun soundChoice(kind: NotificationKind, settings: SkeinSettings.State, style: PathStyle = PathStyle.current): SoundChoice = SoundChoices.parse(
        when (kind) {
            NotificationKind.NEEDS_YOU -> settings.soundNeedsYouChoice
            NotificationKind.TURN_FINISHED -> settings.soundTurnFinishedChoice
        },
        style,
    )

    /**
     * The balloon's content, so a folder name is escaped like the other balloons' text. The installer's folder is a
     * hint only: Skein knows that no status file came, and PATH it can read is the IDE's, not the terminal's.
     */
    fun missingStatusFiles(minVersion: String, found: ClaudeLocator.Result = ClaudeLocator.Result.NotFound): String {
        val version = "Check that claude starts in the IDE terminal and is Claude Code $minVersion or newer."
        return when (found) {
            is ClaudeLocator.Result.OnlyInInstallerFolder ->
                "Skein could not read session status. claude may not start in the IDE's terminal, because ${StringUtil.escapeXmlEntities(found.directory)} " +
                    "is not on the PATH the IDE was started with. Add it to your user PATH and restart the IDE (and JetBrains Toolbox, if it started the IDE). $version"
            else -> "Skein could not read session status. $version"
        }
    }

    /**
     * The balloon's content when `claude --worktree` ended in a [repository] Claude Code was not told to trust - the
     * dialog's own words ([WorktreeStart.untrustedRepository]), escaped, since a folder name may hold a `<`.
     */
    fun untrustedWorktree(repository: String, style: PathStyle = PathStyle.current): String =
        StringUtil.escapeXmlEntities(WorktreeStart.untrustedRepository(repository, style))
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
        if (NotificationText.sound(kind, settings)) SoundPlayer.getInstance().play(kind, NotificationText.soundChoice(kind, settings))
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
            // the project is closing: its balloons were taken down, and one coming later would outlive it elsewhere
            if (closed) return@onEdt
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
            val front = frontProject()
            val place = NotificationText.placement(ApplicationManager.getApplication().isActive, anotherProjectInFront = front != null)
            if (place.inFront && front != null) {
                notification.setSubtitle(NotificationText.fromProject(project.name, subtitle))
                notification.notify(front)
            } else {
                notification.notify(project)
            }
            if (place.system) notifySystemQuietly(title, NotificationText.osText(subtitle, content))
        }
    }

    /**
     * The open project whose window or tab the owner was in last, when it is another one than this. A detached editor
     * window counts as none: a balloon for its project would go to that project's main frame, which may be a hidden tab.
     * EDT only.
     */
    private fun frontProject(): Project? {
        val frame = IdeFocusManager.getGlobalInstance().lastFocusedFrame
        if (frame == null || frame is IdeFrame.Child) return null
        return frame.project?.takeIf { it !== project && !it.isDisposed && it.isOpen }
    }

    /**
     * The thread's project is closing: its balloons go now, on the EDT and not through [onEdt], which drops work once
     * the project is disposed - one shown in another project would otherwise stay there with an Open that leads nowhere.
     */
    fun dismissAllNow() {
        closed = true
        balloons.dismissAll()
    }

    /** Set once the project is closing, so a notify already on its way to the EDT shows nothing. */
    @Volatile
    private var closed = false

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
        // PATH and the disk are read on a pooled thread, the balloon is shown from the EDT like the others
        ApplicationManager.getApplication().executeOnPooledThread {
            val text = NotificationText.missingStatusFiles(minVersion, locateClaude())
            onEdt { group(GROUP_ID).createNotification(text, NotificationType.WARNING).notify(project) }
        }
    }

    /** A worktree thread's `claude --worktree` ended in a [repository] nobody told Claude Code to trust; says what to do. */
    fun untrustedWorktree(repository: String) =
        onEdt { group(GROUP_ID).createNotification(NotificationText.untrustedWorktree(repository), NotificationType.WARNING).notify(project) }

    private fun locateClaude(): ClaudeLocator.Result =
        ClaudeLocator.locateIn(PathStyle.current, EnvironmentUtil.getEnvironmentMap(), System.getProperty("user.home").orEmpty()) { Files.isRegularFile(Path.of(it)) }

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
