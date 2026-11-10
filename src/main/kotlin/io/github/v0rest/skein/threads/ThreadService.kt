package io.github.v0rest.skein.threads

import com.intellij.ide.ActivityTracker
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.Disposable
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.application.ApplicationActivationListener
import com.intellij.openapi.application.ApplicationManager
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
import io.github.v0rest.skein.claude.ClaudeCodeRequirement
import io.github.v0rest.skein.claude.ClaudeHome
import io.github.v0rest.skein.claude.FileStamp
import io.github.v0rest.skein.claude.HistoryEntry
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
import io.github.v0rest.skein.settings.SkeinSettings
import io.github.v0rest.skein.terminal.TabsInView
import io.github.v0rest.skein.terminal.TerminalAdapter
import io.github.v0rest.skein.terminal.ThreadTerminal
import io.github.v0rest.skein.terminal.ThreadTerminalListener
import io.github.v0rest.skein.ui.WorktreeCloseFlow
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlinx.coroutines.withContext
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
    private val adapter = TerminalAdapter(project, scope, mapper::ideChildList)
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
        },
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

    /** The project started closing: a worktree thread's close no longer asks anything. */
    @Volatile
    private var projectClosing = false

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
                override fun projectClosing(project: Project) {
                    if (project == this@ThreadService.project) projectClosing = true
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
     * [CloseSequence]), asking nothing itself: the IDE does not ask for a Skein editor tab (a tab left in the Terminal
     * tool window may get the IDE's own question), so the caller asks first and passes the busy threads the user
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
     * A thread Skein cannot tie to its shell is closed as it always was, without a signal. False, and the tabs close at
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

    private fun closing(): Boolean = projectClosing || project.isDisposed

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
    }

    /** Set once the name carry failed, so a service in shutdown does not fill the log on every tick. */
    private var nameCarryFailed = false

    /** Moves Skein names along `/clear` (see [DisplayNames.carry]) and returns the names now; never throws. */
    private fun carryNamesQuietly(sessions: Map<String, String?>): Map<String, String> {
        try {
            val marks = SessionMarksService.getInstance(project)
            val names = marks.marks.value.names
            val carried = DisplayNames.carry(sessionByThread, sessions, names)
            if (carried != names) marks.update { it.copy(names = DisplayNames.carry(sessionByThread, sessions, it.names)) }
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

    private fun snapshot(): SessionSnapshot = monitor.snapshots.value

    private fun now(): Long = System.currentTimeMillis()

    companion object {
        private val LOG = logger<ThreadService>()
        private const val TICK_MILLIS = 1_000L

        /** How long a Close waits for a hung-up thread's terminal session to end before it closes the tab anyway. */
        private const val END_WAIT_MILLIS = 5_000L

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
