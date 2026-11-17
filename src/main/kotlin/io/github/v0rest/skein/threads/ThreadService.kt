package io.github.v0rest.skein.threads

import com.intellij.ide.ActivityTracker
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.BaseState
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.StoragePathMacros
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.IdeFrame
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.platform.ide.progress.runWithModalProgressBlocking
import io.github.v0rest.skein.claude.ClaudeCodeRequirement
import io.github.v0rest.skein.claude.ClaudeEditorModeWatch
import io.github.v0rest.skein.claude.ClaudeHome
import io.github.v0rest.skein.claude.FileStamp
import io.github.v0rest.skein.claude.FolderTrustReader
import io.github.v0rest.skein.claude.HistoryEntry
import io.github.v0rest.skein.claude.SessionFileParse
import io.github.v0rest.skein.claude.SessionFileParser
import io.github.v0rest.skein.claude.SessionMonitor
import io.github.v0rest.skein.claude.SessionSnapshot
import io.github.v0rest.skein.claude.Transcripts
import io.github.v0rest.skein.env.EnvironmentGate
import io.github.v0rest.skein.env.Unsupported
import io.github.v0rest.skein.env.currentEnvFacts
import io.github.v0rest.skein.git.ExcludeResult
import io.github.v0rest.skein.git.GitToplevel
import io.github.v0rest.skein.git.ProcessGitRunner
import io.github.v0rest.skein.git.WorktreeExcludes
import io.github.v0rest.skein.notify.ThreadNotifier
import io.github.v0rest.skein.power.KeepAwakeService
import io.github.v0rest.skein.process.JdkProcessProbe
import io.github.v0rest.skein.process.LibcHangUp
import io.github.v0rest.skein.process.ProcessId
import io.github.v0rest.skein.settings.SkeinSettings
import io.github.v0rest.skein.terminal.ClaudeRename
import io.github.v0rest.skein.terminal.CloseGate
import io.github.v0rest.skein.terminal.TabsInView
import io.github.v0rest.skein.terminal.TerminalAdapter
import io.github.v0rest.skein.terminal.ThreadTerminal
import io.github.v0rest.skein.terminal.ThreadTerminalListener
import io.github.v0rest.skein.ui.WorktreeCloseFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * The project's threads: drives [ThreadCoordinator] from one confined dispatcher with the session snapshots, the
 * terminal's tab events and the UI's requests, publishes the rows, and keeps the thread list in the workspace file
 * so an IDE restart can restore it. Nothing here runs on the EDT except what [TerminalAdapter] does itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Service(Service.Level.PROJECT)
@State(name = "SkeinThreads", storages = [Storage(StoragePathMacros.WORKSPACE_FILE)])
class ThreadService(private val project: Project, private val scope: CoroutineScope) : PersistentStateComponent<ThreadService.Saved>, Disposable {
    class Saved : BaseState() {
        var threads by list<SavedThread>()
    }

    class SavedThread : BaseState() {
        var id by string()
        var folder by string()
        var sessionId by string()
        var name by string()
        var createdAt by property(0L)
        var pinned by property(false)
        var color by string()
        var worktree by string()

        /** [SkeinThread.base]. */
        var base by string()
    }

    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val mapper = ThreadMapper(JdkProcessProbe())
    private val adapter = TerminalAdapter(project, scope, mapper::ideChildList, inEditor = { SkeinSettings.getInstance().state.threadsInEditor })
    private val terminal: ThreadTerminal = adapter
    private val closeSequence = CloseSequence(LibcHangUp(), terminal, mapper, END_WAIT_MILLIS) { LOG.info(it) }
    private val monitor = SessionMonitor.getInstance()
    private val notifier = ThreadNotifier(project, open = ::select)
    private val coordinator = ThreadCoordinator(
        terminal,
        mapper,
        LaunchSlotService.getInstance().slot,
        object : CoordinatorEvents {
            override fun notify(thread: SkeinThread, kind: NotificationKind): Unit = notifier.notify(thread, kind)

            override fun viewed(threadId: String): Unit = notifier.dismiss(threadId)

            override fun closed(thread: SkeinThread) {
                notifier.dismiss(thread.id)
                if (thread.worktree != null) closeWorktree(thread)
            }

            override fun leftNeedsYou(threadId: String): Unit = notifier.dismiss(threadId)

            override fun missingStatusFiles(): Unit = notifier.missingStatusFiles(ClaudeCodeRequirement.MIN_VERSION)

            override fun untrustedWorktree(thread: SkeinThread): Unit = notifier.untrustedWorktree(thread.folder)
        },
        // read on the confined dispatcher, once per launch that timed out with a reason to ask
        trust = { folder -> FolderTrustReader.read(ClaudeHome.default(), folder) },
    )
    private val rows = MutableStateFlow<List<SkeinThread>>(emptyList())

    @Volatile
    private var latest: List<SkeinThread> = emptyList()

    @Volatile
    private var saved: Saved = Saved()

    @Volatile
    private var restoreRequested = false

    /** Until the saved threads are back, a save must write the loaded list, not the empty live one. */
    @Volatile
    private var restored = false

    /**
     * Whether the project closes - a worktree thread's close no longer asks anything then. Set before the project saves
     * when Skein ends its threads then ([endThreadsBeforeTheProjectCloses]), else when it closes, and lifted again for a
     * close refused after that ([watchForARefusedClose]).
     */
    private val projectClose = ProjectClosing()

    /**
     * The worktree folders a close flow or the Worktrees section's Remove is asking about or removing, so a second one
     * for the same folder asks nothing.
     */
    internal val closingWorktrees: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * For each repository (by real path) whose worktrees Skein could not keep out of git, the reason last shown, so the
     * minute check ([watchWorktrees]) does not show it again; gone once a check holds.
     */
    private val worktreeWarnings: MutableMap<String, String> = ConcurrentHashMap()

    /** The repositories as [WorktreeWatch] checks them: git, the disk and the Claude home. Blocking. */
    private val worktreeChecks = object : WorktreeWatch.Checks {
        override fun realPath(folder: String): String? = GitToplevel.resolved(folder)

        override fun isRepository(folder: Path): Boolean = GitToplevel.isRepository(folder)

        override fun holdsClaudeHome(repository: Path): Boolean = ClaudeHome.default().holdsWorktreesOf(repository)

        override fun ensure(repository: Path): ExcludeResult = WorktreeExcludes(ProcessGitRunner()).ensureExcluded(repository)
    }

    /** The rows of the panel, ordered. */
    val threads: StateFlow<List<SkeinThread>> = rows.asStateFlow()

    private val front = MutableStateFlow<String?>(null)

    /** The thread whose tab is the selected editor, focus or not ([TabsInView.inFront]); refreshed every tick. */
    val inFront: StateFlow<String?> = front.asStateFlow()

    private val previewLines = MutableStateFlow<Map<String, String>>(emptyMap())

    /**
     * What each thread did last ([ThreadPreviews]), by thread id; a thread with nothing usable has no entry. Read from
     * the transcripts on Dispatchers.IO after the rows are published, so it may trail them by a moment.
     */
    val previews: StateFlow<Map<String, String>> = previewLines.asStateFlow()

    private val contextUses = MutableStateFlow<Map<String, ContextUse>>(emptyMap())

    /**
     * The model and context in use of each thread ([ContextUse]), by thread id, read with [previews] from the same
     * transcript tail.
     */
    val contexts: StateFlow<Map<String, ContextUse>> = contextUses.asStateFlow()

    /** Touched only by the one preview collector started in `init`. */
    private val previewCache = PreviewCache(read = ::transcriptStamp, tail = ::transcriptTail, now = ::now)

    /** The rows last published, waiting for a preview refresh; only the newest list is kept. */
    private val previewRequests = Channel<List<SkeinThread>>(Channel.CONFLATED)

    /** The sessions this project's threads hold, the saved ones not restored yet included. */
    fun heldSessionIds(): Set<String> = HeldSessions.of(restored, threads.value, saved.threads.map { it.sessionId })

    /** Claude Code's vim mode setting, each file read again only when it changed ([ClaudeEditorModeWatch]); never on the EDT. */
    private val editorMode = ClaudeEditorModeWatch(ClaudeHome.default())

    /** Whether each thread's claude is in vim mode, by thread id, as [watchEditorMode] last read it; a thread missing is not. */
    @Volatile
    private var vimByThread: Map<String, Boolean> = emptyMap()

    /** Set once a look at the vim mode failed, so a broken one does not fill the log every few seconds; IO only. */
    @Volatile
    private var editorModeFailed = false

    /** The sessions whose waiting rename is on its way into claude now, so a look meanwhile does not type it twice; confined only. */
    private val renamesOnTheirWay = HashSet<String>()

    /** Set once a look at the waiting renames failed, so a broken one does not fill the log every second; confined only. */
    private var renameLookFailed = false

    /** Set once telling the key watch failed, so a service in shutdown does not fill the log on every tick; confined only. */
    private var claudeSeenFailed = false

    /** Why Skein is off in this IDE, or null when it runs (local macOS or Linux). */
    val unsupported: Unsupported? = EnvironmentGate.check(currentEnvFacts(project))

    init {
        terminal.setListener(
            object : ThreadTerminalListener {
                override fun tabClosed(tabId: String) = confined { coordinator.onTabClosed(tabId) }

                override fun tabSelected(tabId: String) = confined { coordinator.onTabSelected(tabId, snapshot(), now()) }

                override fun closeRequested(tabIds: List<String>): Boolean = closeFromTabs(tabIds)
            },
        )
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(
            ProjectCloseListener.TOPIC,
            object : ProjectCloseListener {
                override fun projectClosingBeforeSave(project: Project) {
                    if (project == this@ThreadService.project) endThreadsBeforeTheProjectCloses()
                }

                override fun projectClosing(project: Project) {
                    if (project != this@ThreadService.project) return
                    projectClose.platformCloses()
                    // a balloon of this project may show in another project's window, which outlives this one
                    notifier.dismissAllNow()
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(
            ApplicationActivationListener.TOPIC,
            object : ApplicationActivationListener {
                override fun applicationActivated(ideFrame: IdeFrame) {
                    confinedSuspend {
                        for (id in terminal.tabsInView().inView) coordinator.onTabSelected(id, snapshot(), now())
                    }
                }
            },
        )
        if (unsupported == null) scope.launch(confined) {
            // every new snapshot at once, and a tick every second so timeouts and queued launches move on even
            // when the session directory is quiet and the snapshot does not change
            merge(monitor.snapshots.map { }, ticks()).collect {
                guarded {
                    // one trip to the EDT for both; the marker first, so a failing coordinator step never holds it up
                    val tabs = terminal.tabsInView()
                    front.value = tabs.inFront
                    coordinator.onSnapshot(snapshot(), viewed = { it in tabs.inView }, now = now())
                }
                publish()
                pushTitlesQuietly()
                requestPreviews()
                // every status change and every second: a key typed meanwhile only ever makes a rename wait longer
                sendWaitingRenamesQuietly()
            }
        }
        if (unsupported == null) scope.launch(confined) {
            // a Skein rename reaches the tab at once, not with the next tick
            guarded { SessionMarksService.getInstance(project).marks.map { it.names }.distinctUntilChanged().collect { pushTitlesQuietly() } }
        }
        if (unsupported == null) scope.launch(Dispatchers.IO) {
            for (threads in previewRequests) refreshPreviews(threads)
        }
        if (unsupported == null) scope.launch(Dispatchers.IO) { watchWorktrees() }
        if (unsupported == null) scope.launch(Dispatchers.IO) { watchEditorMode() }
    }

    fun newThread(folder: String) {
        if (unsupported == null) confined { coordinator.newThread(folder, now()) }
    }

    /**
     * The caller has already kept the repository's worktrees out of git ([WorktreeExcludes]); [base] is the branch the
     * repository had checked out ([SkeinThread.base]).
     */
    fun newWorktreeThread(folder: String, name: String, base: String? = null) {
        if (unsupported == null) confined { coordinator.newWorktreeThread(folder, name, now(), base) }
    }

    fun setPinned(threadIds: Collection<String>, pinned: Boolean) {
        if (unsupported == null) confined { coordinator.pin(threadIds, pinned) }
    }

    fun setColor(threadIds: Collection<String>, color: ThreadColor?) {
        if (unsupported != null) return
        val ids = threadIds.toList()
        scope.launch(confined) {
            guarded { coordinator.color(ids, color) }
            publish() // the tab colour provider reads the published rows, so they must be current before the refresh
            pushTitlesQuietly()
            for (id in ids) terminal.refreshPresentation(id)
        }
    }

    /**
     * Ends the threads and then closes their tabs as their own close buttons do ([ThreadCoordinator.close],
     * [CloseSequence]), asking nothing itself: the IDE does not ask for a Skein editor tab (a tab in the Terminal tool
     * window may get the IDE's own question), so the caller asks first and passes the busy threads the user
     * [confirmed]. A thread that turned busy since stays open, and one balloon says so. A tab that closes is reported
     * back and handled like any other close, its worktree question included.
     */
    fun close(threadIds: Collection<String>, confirmed: Set<String>) {
        if (unsupported != null) return
        endThenClose(threadIds.toList(), confirmed.toSet())
    }

    /**
     * The user closed thread tabs in the IDE while one of them still runs its shell
     * ([ThreadTerminalListener.closeRequested]): Skein takes that close over, ends the threads and closes all those
     * tabs itself in one batch, the one in front last, asking nothing (that close was the user's own), and returns true.
     * A thread whose shell Skein cannot prove is closed as it always was, without a signal. False, and the tabs close at
     * once as before, when Skein is off, the project closes or a thread is unknown. Called on the EDT; returns at once,
     * never throws.
     */
    private fun closeFromTabs(threadIds: List<String>): Boolean {
        try {
            if (unsupported != null || closing() || threadIds.isEmpty()) return false
            val known = latest.mapTo(HashSet()) { it.id }
            if (!known.containsAll(threadIds)) return false
            endThenClose(threadIds, confirmed = threadIds.toSet(), fromTabs = true)
            return true
        } catch (_: ProcessCanceledException) {
        } catch (_: CancellationException) {
        } catch (e: Throwable) {
            LOG.warn("Skein could not end its threads before their tabs closed", e)
        }
        return false
    }

    /**
     * The coordinator decides on the confined dispatcher which threads close and what to end in each; the
     * [CloseSequence] then runs on the service scope, never on the confined dispatcher, which it would hold up for the
     * whole wait. For a close the IDE asked about ([fromTabs]), a thread that is not closed after all, or an ending that
     * fails before it gets to the close, lets go of its tab ([ThreadTerminal.clearEnding]), so the next × goes through.
     */
    private fun endThenClose(ids: List<String>, confirmed: Set<String>, fromTabs: Boolean = false) {
        scope.launch(confined) {
            var handedOn: Set<String> = emptySet()
            try {
                guarded {
                    val outcome = coordinator.close(ids, confirmed, snapshot())
                    if (outcome.leftOpen.isNotEmpty()) announceLeftOpenQuietly(outcome.leftOpen)
                    if (outcome.closing.isNotEmpty()) {
                        scope.launch { runCloseSequenceQuietly(outcome.closing) }
                        handedOn = outcome.closing.mapTo(HashSet()) { it.tab.id }
                    }
                }
            } finally {
                if (fromTabs && handedOn.size < ids.size) clearEndingQuietly(ids.filter { it !in handedOn })
            }
            publish()
            pushTitlesQuietly()
            requestPreviews()
        }
    }

    /** One [CloseSequence.run]; an ending that does not get to its close lets go of its tabs. Never throws but for cancellation. */
    private suspend fun runCloseSequenceQuietly(closing: List<Ending>) {
        var done = false
        try {
            closeSequence.run(closing)
            done = true
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.warn("Skein could not close the tabs of its threads", e)
        } finally {
            if (!done) clearEndingQuietly(closing.map { it.tab.id })
        }
    }

    private fun clearEndingQuietly(threadIds: List<String>) {
        try {
            terminal.clearEnding(threadIds)
        } catch (e: Throwable) {
            if (e is CancellationException || e is ProcessCanceledException) return
            LOG.warn("Skein could not let go of the tabs it meant to close", e)
        }
    }

    /**
     * What Skein's close check asks about [files], a whole close ([TerminalAdapter.allowsClose]): false keeps the
     * thread tabs among them open while Skein ends their threads and then closes them, and Skein closes the other files
     * of that close itself; true for anything else. EDT only; never throws but for cancellation.
     */
    fun allowsTabClose(files: Collection<VirtualFile>): Boolean = unsupported != null || adapter.allowsClose(files)

    /** One balloon for the threads Close left open ([CloseRule.leftOpenText]); never throws but for cancellation. */
    private fun announceLeftOpenQuietly(threads: List<SkeinThread>) {
        try {
            val names = SessionMarksService.getInstance(project).marks.value.names
            balloon(CloseRule.leftOpenText(threads.map { DisplayNames.thread(it, names) }), NotificationType.INFORMATION)
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.warn("Skein could not say which threads it left open", e)
        }
    }

    fun select(threadId: String) {
        if (unsupported == null) confined { coordinator.select(threadId, snapshot(), now()) }
    }

    suspend fun resume(entry: HistoryEntry, force: Boolean = false): ResumeOutcome {
        if (unsupported != null) return ResumeOutcome.Refused
        val outcome = withContext(confined) {
            coordinator.resume(entry, snapshot(), now(), force).also {
                publish()
                pushTitlesQuietly()
            }
        }
        if (outcome is ResumeOutcome.Opened) {
            // the worktree may have been made outside Skein, in a repository that does not ignore it yet
            val thread = latest.firstOrNull { it.id == outcome.threadId }
            if (thread?.worktree != null) excludeWorktrees(setOf(thread.folder))
        }
        return outcome
    }

    /**
     * Reopens the threads saved in the workspace once the tool windows exist (terminal tabs need them) and the
     * session directory has been read once (so a session running elsewhere is not resumed on an empty snapshot).
     */
    fun restoreOnce() {
        if (restoreRequested || unsupported != null) return
        restoreRequested = true
        ToolWindowManager.getInstance(project).invokeLater {
            confinedSuspend {
                monitor.awaitFirstPoll()
                val threads = saved.threads.mapNotNull(::restored)
                try {
                    coordinator.restore(threads, snapshot(), now())
                } finally {
                    // whatever restore managed to open is what the next save must record, not the stale loaded list
                    restored = true
                    // a restored worktree thread may relaunch `claude --worktree` when first opened, and its worktree
                    // may have been made outside Skein
                    excludeWorktrees(coordinator.threads.filter { it.worktree != null }.mapTo(LinkedHashSet()) { it.folder })
                }
            }
        }
    }

    override fun getState(): Saved {
        if (!restored) return saved
        val state = Saved()
        for (t in latest) state.threads.add(saved(t))
        return state
    }

    override fun loadState(state: Saved) {
        saved = state
    }

    override fun dispose() {
        coordinator.dispose()
    }

    private fun confined(block: () -> Unit): Unit = confinedSuspend { block() }

    private fun confinedSuspend(block: suspend () -> Unit) {
        scope.launch(confined) {
            guarded { block() }
            publish()
            pushTitlesQuietly()
            requestPreviews()
        }
    }

    /** The title last handed to each thread's tab ([TabTitles]), by thread id; touched only from the confined dispatcher. */
    private var pushedTitles: Map<String, String> = emptyMap()

    /** Set once a title push failed, so a service in shutdown does not fill the log on every tick; confined only. */
    private var titlesFailed = false

    /**
     * Names each thread's tab after the thread ([TabTitles.changed]) where that changed since the last push. Called
     * after [publish], never inside it, on the confined dispatcher; never blocks, never throws but for cancellation.
     */
    private fun pushTitlesQuietly() {
        try {
            val threads = latest
            val names = SessionMarksService.getInstance(project).marks.value.names
            for ((threadId, title) in TabTitles.changed(pushedTitles, threads, names)) terminal.rename(threadId, title)
            pushedTitles = TabTitles.remembered(threads, names)
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!titlesFailed) LOG.warn("Skein could not name the tabs of its threads", e)
            titlesFailed = true
        }
    }

    /**
     * Hands the rows just published to the preview collector (a full request is replaced). While the rows show no
     * preview and no context (Show Last Message and Show Model and Context off) no transcript is read and the previews
     * go, and the first call after it is switched on asks again. Never blocks, never throws but for cancellation.
     */
    private fun requestPreviews() {
        if (!previewsShown()) {
            previewLines.value = emptyMap()
            contextUses.value = emptyMap()
            return
        }
        previewRequests.trySend(latest)
    }

    /** Set once reading the preview setting failed, so a service in shutdown does not fill the log on every tick; confined only. */
    private var previewSettingFailed = false

    /**
     * Whether the rows show what each thread did last or its model and context ([SkeinSettings.State.showPreview],
     * [SkeinSettings.State.showContext]); true when it cannot be read. Never throws but for cancellation.
     */
    private fun previewsShown(): Boolean {
        try {
            val state = SkeinSettings.getInstance().state
            return state.showPreview || state.showContext
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!previewSettingFailed) LOG.warn("Skein could not read whether its rows show the last message", e)
            previewSettingFailed = true
        }
        return true
    }

    /** Set once a preview refresh failed, so a broken one does not fill the log every second; touched only by the preview collector. */
    private var previewFailed = false

    /** One [PreviewCache.update] for [threads], on Dispatchers.IO; never throws but for cancellation. Blocking. */
    private fun refreshPreviews(threads: List<SkeinThread>) {
        try {
            val found = previewCache.refresh(threads)
            previewLines.value = found.lines
            contextUses.value = found.contexts
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!previewFailed) LOG.warn("Skein could not read what its threads did last", e)
            previewFailed = true
        }
    }

    /** The stamp of [thread]'s transcript; null without a session, a transcript, or when it cannot be read. Blocking. */
    private fun transcriptStamp(thread: SkeinThread): FileStamp? {
        val sessionId = thread.sessionId ?: return null
        return try {
            val home = ClaudeHome.default()
            home.stamp(Transcripts.path(home, thread.folder, thread.sessionCwd, sessionId))
        } catch (e: Exception) {
            LOG.debug("Skein could not look at the transcript of thread ${thread.id}", e)
            null
        }
    }

    /** The tail of [thread]'s transcript ([Transcripts.tailLines]); empty when there is none or it cannot be read. Blocking. */
    private fun transcriptTail(thread: SkeinThread): List<String> {
        val sessionId = thread.sessionId ?: return emptyList()
        return try {
            Transcripts.tailLines(ClaudeHome.default(), thread.folder, thread.sessionCwd, sessionId)
        } catch (e: Exception) {
            LOG.debug("Skein could not read the transcript tail of thread ${thread.id}", e)
            emptyList()
        }
    }

    /**
     * Keeps the worktrees of each of [repositories] out of git ([WorktreeExcludes]), best effort, on the service scope
     * and off the EDT, never on the confined dispatcher: what it writes is announced, and a failure is a warning, not a
     * refusal, since refusing a resume or a restore would strand work already there.
     */
    private fun excludeWorktrees(repositories: Set<String>) {
        if (repositories.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            showWorktreeChecks { WorktreeWatch.check(WorktreeWatch.distinct(repositories, worktreeChecks::realPath), periodic = false, worktreeChecks, worktreeWarnings) }
        }
    }

    /**
     * Every [WorktreeWatch.PERIOD_MILLIS], one [WorktreeWatch.tick] over the project's worktree threads, if it has any:
     * git removes or replaces the guard as an ignored file now and then, and this puts it back. Runs on Dispatchers.IO
     * for as long as the service scope does.
     */
    private suspend fun watchWorktrees() {
        while (true) {
            delay(WorktreeWatch.PERIOD_MILLIS)
            if (closing()) continue
            showWorktreeChecks { WorktreeWatch.tick(latest, worktreeChecks, worktreeWarnings) }
            // the same minute looks at the worktree rows and the Worktrees section again ([WorktreeHygiene])
            requestWorktreeSweepQuietly(now = false)
        }
    }

    /**
     * Asks [WorktreeHygiene] for a look at the worktrees, [now] without its usual gap (after Skein's own Remove); never
     * throws but for cancellation.
     */
    private fun requestWorktreeSweepQuietly(now: Boolean) {
        try {
            val hygiene = WorktreeHygiene.getInstance(project)
            if (now) hygiene.requestNow() else hygiene.request()
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.warn("Skein could not ask for a look at its worktrees", e)
        }
    }

    /** Runs [checks] and shows its balloons, a warning logged too. Never throws but for cancellation. Blocking. */
    private fun showWorktreeChecks(checks: () -> List<WorktreeWatch.Balloon>) {
        try {
            for (shown in checks()) {
                when (shown) {
                    is WorktreeWatch.Balloon.Info -> balloon(shown.text, NotificationType.INFORMATION)
                    is WorktreeWatch.Balloon.Warning -> {
                        LOG.warn(shown.text)
                        balloon(shown.text, NotificationType.WARNING)
                    }
                }
            }
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            LOG.warn("Skein could not keep the worktrees of its worktree threads out of git", e)
        }
    }

    private fun balloon(text: String, type: NotificationType) {
        if (project.isDisposed) return
        // the text is HTML to the platform, and a path or git's reason may hold a `<`
        NotificationGroupManager.getInstance().getNotificationGroup(ThreadNotifier.GROUP_ID)
            .createNotification(StringUtil.escapeXmlEntities(text), type)
            .notify(project)
    }

    /**
     * Offers to remove a closed thread's worktree ([WorktreeCloseFlow]) on the service scope: never on the confined
     * dispatcher, which only launches it, and nothing from the flow reaches the threads. Once it is done, the worktrees
     * get a new look ([WorktreeHygiene]): a kept one joins the Worktrees section, a removed one leaves it.
     */
    private fun closeWorktree(thread: SkeinThread) {
        if (closing()) return
        scope.launch {
            try {
                WorktreeCloseFlow.run(
                    project,
                    thread,
                    monitor.snapshots,
                    threads = { threads.value },
                    inFlight = closingWorktrees,
                    active = { !closing() },
                )
                requestWorktreeSweepQuietly(now = true)
            } catch (_: ProcessCanceledException) {
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LOG.warn("Skein could not offer to remove a closed thread's worktree", e)
            }
        }
    }

    private fun closing(): Boolean = projectClose.closing || project.isDisposed

    /**
     * The project closes - File | Close Project, its window's close, another project opened in that window, a forced
     * close, and the IDE quitting closes each open project the same way (javap of ProjectManagerImpl, 2026.3). Its
     * terminals go with it, and a closed terminal's pty is no longer read (IJPL-259859), so a claude that writes while it
     * exits would hang on for good. `ProjectCloseListener.projectClosingBeforeSave` comes on the EDT once every veto was
     * asked (`canClose`) and before the save, the cancel of the project's scope and the terminal's own
     * `closeTerminalProcesses` at `projectClosing`. So Skein ends every thread here as a Close does, claude first and
     * then the shell, while the terminals are open and read, and the close goes on once that is done or one deadline,
     * [PROJECT_CLOSE_MILLIS] from here, has passed - for the look at what runs in the threads and their ending together.
     *
     * The flags come first - from here on the terminal closes each tab whose shell ended by itself, and such a close must
     * not drop the thread from the state the project saves next, nor ask about a worktree. The wait runs under the
     * platform's modal progress, as the terminal's own one does, so the IDE stays painted and the tick holding the
     * coordinator can finish its trip to the EDT (it asks for any modality). Never vetoes and never asks - a failure, or
     * Cancel in the progress, logs at most and the close goes on as before. Nothing on Windows, where the IDE ends claude
     * and its shell itself, nor for a project without threads.
     */
    private fun endThreadsBeforeTheProjectCloses() {
        try {
            if (unsupported != null || !CloseGate.closeCanHang || latest.isEmpty()) return
            projectClose.endingThreads()
            adapter.projectCloses()
            // off the EDT a close is not one EDT turn, and the watch could come in the middle of it
            if (ApplicationManager.getApplication().isDispatchThread) watchForARefusedClose()
            val deadline = System.nanoTime() + PROJECT_CLOSE_MILLIS * NANOS_PER_MILLI
            // the look runs as soon as the coordinator is free, and what it finds counts whenever it comes before the deadline
            val look = scope.async(confined) {
                try {
                    coordinator.endingsAtProjectClose(snapshot())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Skein could not tell what runs in its threads before the project closed", e)
                    emptyList()
                }
            }
            val end: suspend () -> Unit = {
                val endings = withTimeoutOrNull(millisLeft(deadline)) { look.await() }
                if (endings == null) {
                    look.cancel()
                    LOG.info("Skein could not tell what runs in its threads in time, and hangs up nothing before the project closes")
                } else {
                    closeSequence.endOnly(endings, withinMillis = millisLeft(deadline))
                }
            }
            // the sequence keeps to the deadline itself, so this only stops a step that would never return
            val bounded: suspend () -> Unit = { withTimeoutOrNull(millisLeft(deadline) + PROJECT_CLOSE_MARGIN_MILLIS) { end() } }
            if (ApplicationManager.getApplication().isDispatchThread) {
                runWithModalProgressBlocking(project, "Ending Claude Code in Skein threads") { bounded() }
            } else {
                runBlocking { bounded() }
            }
        } catch (_: ProcessCanceledException) {
        } catch (_: CancellationException) {
        } catch (e: Throwable) {
            LOG.warn("Skein could not end its threads before the project closed", e)
        }
    }

    private fun millisLeft(deadline: Long): Long = ((deadline - System.nanoTime()) / NANOS_PER_MILLI).coerceAtLeast(0)

    /**
     * A close refused after [endThreadsBeforeTheProjectCloses] - a failed save the person does not want to close over -
     * leaves the project open with the closing flags set, so a tab's close would end nothing there any more. Everything
     * from that hook to the project's dispose runs on the EDT in one go (`closeProject`, and the batch of an IDE that
     * quits), and the only events it lets through meanwhile come from modal loops - the save's progress, the terminal's
     * own progress, a dialog - which a non-modal runnable never runs in. So this runnable comes once the close attempt
     * is over, which is why it is queued only for a close on the EDT. A project that did close is disposed by then,
     * which expires it, and one still open gets both flags back, its tabs that the IDE closed meanwhile counted as
     * closed now.
     */
    private fun watchForARefusedClose() {
        ApplicationManager.getApplication().invokeLater(
            {
                try {
                    if (projectClose.closeAttemptOver(projectOpen = !project.isDisposed && project.isOpen)) adapter.projectStaysOpen()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Skein could not take its threads back after a refused project close", e)
                }
            },
            ModalityState.nonModal(),
            project.disposed,
        )
    }

    /** One failing step must never freeze every thread's status. */
    private suspend fun guarded(block: suspend () -> Unit) {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not update its threads", e)
        }
    }

    private fun ticks() = flow {
        while (true) {
            delay(TICK_MILLIS)
            emit(Unit)
        }
    }

    /** Which session each thread had at the last [publish]; touched only from the confined dispatcher. */
    private var sessionByThread: Map<String, String?> = emptyMap()

    private fun publish() {
        latest = coordinator.threads
        rows.value = latest
        val sessions = latest.associate { it.id to it.sessionId }
        val names = carryNamesQuietly(sessions)
        sessionByThread = sessions
        KeepAwakeService.refreshQuietly()
        nudgeToolbarQuietly(latest, names)
        claudeSeenQuietly(latest)
    }

    /** Tells the key watch what each thread's claude does now ([RenameGate.seen]); never throws. */
    private fun claudeSeenQuietly(threads: List<SkeinThread>) {
        try {
            val seen = RenameGate.seen(threads)
            terminal.claudeSeen(seen.enterMaySend, seen.atWorkSince)
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!claudeSeenFailed) LOG.warn("Skein could not tell its key watch what its threads do", e)
            claudeSeenFailed = true
        }
    }

    /** Set once the name carry failed, so a service in shutdown does not fill the log on every tick. */
    private var nameCarryFailed = false

    /**
     * Moves Skein names along `/clear` (see [DisplayNames.carry]), and a rename waiting for Claude Code with its name
     * ([SessionMarks.carry]), and returns the names now; never throws.
     */
    private fun carryNamesQuietly(sessions: Map<String, String?>): Map<String, String> {
        try {
            val marks = SessionMarksService.getInstance(project)
            val current = marks.marks.value
            if (current.carry(sessionByThread, sessions) != current) marks.update { it.carry(sessionByThread, sessions) }
            return marks.marks.value.names
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!nameCarryFailed) LOG.warn("Skein could not carry a thread's name to its new session", e)
            nameCarryFailed = true
        }
        return emptyMap()
    }

    /** Needs-you ids and names at the last [publish]; touched only from the confined dispatcher. */
    private var waitingShown: List<Pair<String, String>> = emptyList()

    /** The main toolbar does not re-run `update` while the IDE idles, so wake it when what the counter shows changes. Never throws. */
    private fun nudgeToolbarQuietly(threads: List<SkeinThread>, names: Map<String, String>) {
        try {
            val waiting = threads.filter { it.state is ThreadState.NeedsYou }.map { it.id to DisplayNames.thread(it, names) }
            if (waiting == waitingShown) return
            waitingShown = waiting
            ActivityTracker.getInstance().inc()
        } catch (_: ProcessCanceledException) {
        } catch (_: CancellationException) {
        } catch (e: Throwable) {
            LOG.warn("Skein could not refresh the toolbar counter", e)
        }
    }

    /**
     * Whether Claude Code's input is in vim mode for a session in any of [folders] ([ClaudeEditorModeWatch]): then the
     * Rename dialog offers no rename in Claude Code. Blocking, a few file stats unless a file changed; never on the EDT.
     */
    fun claudeInVimMode(folders: Collection<String>): Boolean = editorMode.vim(folders)

    /**
     * Every [EDITOR_MODE_MILLIS], whether each thread's claude is in vim mode ([vimByThread]), for the look at the waiting
     * renames. IO, for as long as the service runs: a failed look is logged once and the next one comes all the same, so
     * a stuck value cannot hold a rename back for good.
     */
    private suspend fun watchEditorMode() {
        while (true) {
            try {
                vimByThread = latest.associate { it.id to editorMode.vim(RenameGate.folders(it)) }
            } catch (_: ProcessCanceledException) {
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                if (!editorModeFailed) LOG.warn("Skein could not read whether Claude Code is in vim mode", e)
                editorModeFailed = true
            }
            delay(EDITOR_MODE_MILLIS)
        }
    }

    /**
     * Types each rename waiting for Claude Code ([SessionMarks.claudeRenames]) into the claude of the thread that holds
     * its session, once [RenameGate] says so, and leaves the others waiting for the next look. A name Skein would never
     * type stops waiting. Confined; never throws but for cancellation.
     */
    private fun sendWaitingRenamesQuietly() {
        try {
            val marks = SessionMarksService.getInstance(project)
            val waiting = marks.marks.value.claudeRenames
            if (waiting.isEmpty()) return
            val snapshot = snapshot()
            for ((sessionId, name) in waiting) {
                if (sessionId in renamesOnTheirWay) continue
                val target = coordinator.renameTarget(sessionId, snapshot)
                val clean = target != null && terminal.inputClean(target.tab)
                val vimMode = target != null && vimByThread[target.thread.id] == true
                when (RenameGate.decide(name, target?.runsSession == true, target?.thread?.state, target?.claude != null, clean, vimMode)) {
                    RenameGate.Decision.WAIT -> Unit
                    RenameGate.Decision.DROP -> {
                        LOG.warn("Skein will not type the name waiting for session $sessionId into claude, so it stays in Skein only")
                        marks.update { it.claudeRenameSent(sessionId, name) }
                    }
                    RenameGate.Decision.SEND -> if (target != null) sendRename(target, sessionId, name)
                }
            }
        } catch (_: ProcessCanceledException) {
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (!renameLookFailed) LOG.warn("Skein could not look at the renames waiting for Claude Code", e)
            renameLookFailed = true
        }
    }

    /**
     * Hands one rename to the terminal ([ThreadTerminal.sendRename]) on the service scope, never on the confined
     * dispatcher, and asks it to look once more right before: the claude is still the same process under the tab's
     * shell, and its status file, read fresh, says idle for that session. A rename that went stops waiting and is never
     * typed again; one that failed on the way may have gone, so it stops waiting too. One the last look turned down
     * waits on. Then the transcript is watched for Claude Code's own record of the name ([confirmRename]).
     */
    private fun sendRename(target: RenameTarget, sessionId: String, name: String) {
        val claude = target.claude ?: return
        val anchor = target.thread.anchor ?: return
        renamesOnTheirWay += sessionId
        scope.launch {
            var gone = false
            try {
                gone = terminal.sendRename(
                    target.tab,
                    name,
                    stillIdle = { claudeStillIdle(claude, anchor, sessionId, RenameGate.folders(target.thread)) },
                    // in the EDT turn of the send: only a rename that still waits goes, and an IDE that quits right after has
                    // nothing left to type again
                    beforeSend = { SessionMarksService.getInstance(project).claimClaudeRename(sessionId, name) },
                )
                if (gone) LOG.info("Skein typed a waiting rename into the claude of thread ${target.thread.id}")
            } catch (_: ProcessCanceledException) {
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                gone = true
                LOG.warn("Skein could not type a waiting rename into the claude of thread ${target.thread.id}; it does not try again", e)
            } finally {
                val sent = gone
                scope.launch(confined) {
                    renamesOnTheirWay -= sessionId
                    if (sent) {
                        SessionMarksService.getInstance(project).update { it.claudeRenameSent(sessionId, name) }
                        // only once it stopped waiting, so the confirmation never meets it still there
                        confirmRename(target.thread, sessionId, name)
                    }
                }
            }
        }
    }

    /**
     * The last look before a rename goes, off the EDT ([RenameGate.lastLook]) on what is read now: [claude]'s start and
     * the shell it hangs under, its status file and Claude Code's vim mode for the user and the thread's [folders].
     * Blocking; false on any doubt.
     */
    private fun claudeStillIdle(claude: ProcessId, anchor: Long, sessionId: String, folders: List<String>): Boolean = try {
        val home = ClaudeHome.default()
        val text = home.readText(home.sessionsDir.resolve("${claude.pid}.json"), STATUS_MAX_BYTES)
        val file = text?.let { (SessionFileParser.parse(it) as? SessionFileParse.Ok)?.file }
        val vim = editorMode.vim(folders)
        RenameGate.lastLook(claude, mapper.startOf(claude.pid), anchor, mapper.anchorOf(claude.pid), file, sessionId, vim)
    } catch (e: Exception) {
        LOG.debug("Skein could not check the claude of session $sessionId before a rename", e)
        false
    }

    /**
     * Watches the session's transcript for up to [CONFIRM_MILLIS] after a rename went, on Dispatchers.IO: once Claude
     * Code shows [name] as the session's own name ([ClaudeRename.confirmedBy]), the Skein name goes
     * ([SessionMarks.claudeNameConfirmed]), so a later `/rename` in claude is followed again. Until then the Skein name
     * keeps the row right, and if the record never comes nothing else happens.
     */
    private fun confirmRename(thread: SkeinThread, sessionId: String, name: String) {
        scope.launch(Dispatchers.IO) {
            val until = now() + CONFIRM_MILLIS
            while (now() < until) {
                delay(CONFIRM_POLL_MILLIS)
                val confirmed = try {
                    ClaudeRename.confirmedBy(Transcripts.tailLines(ClaudeHome.default(), thread.folder, thread.sessionCwd, sessionId), name)
                } catch (e: Exception) {
                    LOG.debug("Skein could not read the transcript of session $sessionId", e)
                    false
                }
                if (confirmed) {
                    SessionMarksService.getInstance(project).update { it.claudeNameConfirmed(sessionId, name) }
                    return@launch
                }
            }
            LOG.info("Claude Code did not show the name Skein typed into session $sessionId; its Skein name stays")
        }
    }

    private fun snapshot(): SessionSnapshot = monitor.snapshots.value

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private val LOG = logger<ThreadService>()
        private const val TICK_MILLIS = 1_000L

        /** How long a Close waits for a hung-up thread's terminal session to end before it closes the tab anyway. */
        private const val END_WAIT_MILLIS = 5_000L

        /**
         * How long a project's close waits for Skein in all - the look at what runs in its threads and their ending
         * together - since the close waits for that.
         */
        private const val PROJECT_CLOSE_MILLIS = 2_000L

        /** How long past [PROJECT_CLOSE_MILLIS] the close waits at most for a step that ought to have kept to it. */
        private const val PROJECT_CLOSE_MARGIN_MILLIS = 300L

        private const val NANOS_PER_MILLI = 1_000_000L

        /** How long after a rename went Skein looks for Claude Code's record of it in the transcript, and how often. */
        private const val CONFIRM_MILLIS = 60_000L
        private const val CONFIRM_POLL_MILLIS = 1_000L

        /** A status file is a few hundred bytes; anything far larger is not one. */
        private const val STATUS_MAX_BYTES = 64 * 1024

        /** How often Skein looks whether Claude Code's vim mode setting changed. */
        private const val EDITOR_MODE_MILLIS = 10_000L

        fun getInstance(project: Project): ThreadService = project.service()

        /** What the workspace file keeps of [thread]. */
        internal fun saved(thread: SkeinThread): SavedThread =
            SavedThread().apply {
                id = thread.id
                folder = thread.folder
                sessionId = thread.sessionId
                name = thread.name
                createdAt = thread.createdAt
                pinned = thread.pinned
                color = thread.color?.name
                worktree = thread.worktree
                base = thread.base
            }

        /** The thread [saved] stands for, not started yet; null without an id or a folder. */
        internal fun restored(saved: SavedThread): SkeinThread? {
            val id = saved.id ?: return null
            val folder = saved.folder ?: return null
            return SkeinThread(
                id, folder, saved.sessionId, saved.name ?: folder, ThreadState.NotStarted, 0, saved.createdAt, null, saved.pinned,
                ThreadColor.entries.firstOrNull { it.name == saved.color }, worktree = saved.worktree, base = saved.base,
            )
        }
    }
}
