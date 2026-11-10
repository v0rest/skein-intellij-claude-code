package io.github.v0rest.skein.terminal

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.impl.SimpleDataContext
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.application.asContextElement
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerEvent
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileEditor.FileEditorOpenRequest
import com.intellij.openapi.fileEditor.ex.FileEditorManagerEx
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectCloseListener
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.terminal.TerminalTitle
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManagerEvent
import com.intellij.ui.content.ContentManagerListener
import io.github.v0rest.skein.process.ProcessList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.terminal.view.TerminalContentChangeEvent
import org.jetbrains.plugins.terminal.view.TerminalOutputModelListener
import java.awt.KeyboardFocusManager
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.util.concurrent.ConcurrentHashMap

/**
 * The only code that talks to the terminal plugin (IntelliJ 2026.3, build 263.7307.200; API checked with javap on
 * 2026-11-06 and, for the title, 2026-11-09; no `@ApiStatus.Internal` member is used):
 *
 * - `TerminalToolWindowTabsManager`, `TerminalToolWindowTab` and `TerminalToolWindowTabBuilder` are Experimental and
 *   NonExtendable. The builder methods used here (workingDirectory, tabName, requestFocus, deferSessionStartUntilUiShown,
 *   closeOnProcessTermination, restoreOnProjectReopen, createTab) are not Internal; emulatorType, userDefinedName,
 *   shouldAddToToolWindow, pendingContent, sourceNavigationProjectResolver and startupFusInfo are, and are not used.
 * - `TerminalView.sendText`, `getOutputModels`, `getSessionState`, `getPreferredFocusableComponent`, `getTitle` are
 *   Experimental; `getSessionDeferred` and `setTopComponent` are Internal and not used. `TerminalOutputModel.addListener`
 *   and `getTextLength` are Experimental.
 * - The tab's title is `com.intellij.terminal.TerminalTitle` (platform, not the terminal plugin): the class, `change`
 *   and `State.userDefinedTitle` carry no ApiStatus; `buildTitle(boolean)` and `buildFullTitle(boolean)` are
 *   Experimental and `State.trackTerminalApplicationTitleChanges` is Internal, none of them used. The user title comes
 *   before the title the program in the terminal sets (claude's "✳ Claude Code"), which `tabName` does not.
 * - Moving a tab into the editor area is done by the bundled action `Terminal.MoveToEditor`
 *   (MoveTerminalSessionToEditorAction), invoked through ActionManager with a data context that carries the Terminal
 *   tool window and its content manager, which is what ToolWindowContextMenuActionBase reads. Doing it by hand would
 *   need TerminalViewVirtualFile (Kotlin-internal) and FileEditorManagerKeys (@Internal). The editor file the action
 *   opened is found as the one new entry in FileEditorManager.openFiles. If the move ever fails, the tab stays in the
 *   Terminal tool window, where Skein follows it through the tool window's content manager.
 * - Closing a tab from Skein takes the tab's own close path, so the same close events run: in the editor
 *   `FileEditorManagerEx.closeFileWithChecks(file, window)`, which is what the editor tab's close button (`CloseTab`)
 *   calls, for each window `FileEditorManagerEx.getWindows` lists whose `EditorWindow.isFileOpen`; in the Terminal
 *   tool window `ContentManager.removeContent(content, true)`. None of these carries an ApiStatus annotation (javap,
 *   2026-11-09). The content is the one `moveToEditor` already reads.
 * - A terminal tab closed while its shell runs leaves the shell, and a claude in it, hanging forever: the IDE keeps the
 *   tab's pty open but stops reading it (IJPL-259859). So Skein ends the thread first ([awaitEnded] waits for
 *   `TerminalView.getSessionState`, Experimental, to turn Terminated) and closes the tab after. For the editor tab's
 *   own ×, Close All and Close Others, Skein's `VirtualFilePreCloseCheck` (Experimental, [allowsClose]) keeps a running
 *   thread tab open until then, and all the thread tabs of one close are ended and closed together; the tab Skein
 *   closes itself is released, so that check lets it through. The IDE's own checks do not cover this terminal file.
 *   Skein's own closes count as its own selections ([OwnSelections]), so the neighbour the IDE selects is not taken for
 *   the user opening it. In the Terminal tool window (the degraded path) the menu's Close ends the thread before the
 *   removal, which runs the tool window's close listeners and may ask the IDE's own question too; the tool window tab's
 *   own close button is left as it was.
 * - Right before Skein sends a command into a tab it lists the IDE's child processes with their start instants
 *   ([ThreadTab.shellsAtCommand]): a close hangs up a thread's shell only if it is among them (and, the coordinator
 *   checks, was not open before the tab's shell started), so a terminal the user opened afterwards is never signalled.
 *   Only process start instants are compared, never with the wall clock. [sessionStarted] tells the coordinator when a
 *   tab opened out of view has started its shell.
 * - Whether the IDE quits for sure comes from `AppLifecycleListener.appWillBeClosed` (no ApiStatus);
 *   `Application.isExitInProgress` is deprecated.
 */
class TerminalAdapter(
    private val project: Project,
    private val scope: CoroutineScope,
    /** The IDE's child processes right now ([ThreadTab.shellsAtCommand]); blocking, so never called on the EDT. */
    private val ideShells: () -> ProcessList,
) : ThreadTerminal {
    private class Handle(override val id: String, override val folder: String) : ThreadTab {
        val tab = CompletableDeferred<TerminalToolWindowTab>()
        val file = CompletableDeferred<VirtualFile>()

        @Volatile
        override var shellsAtCommand: ShellsAtCommand = ShellsAtCommand.NoCommandYet

        /** The state of the tab's terminal session, set on the EDT before [tab] completes. */
        @Volatile
        var session: StateFlow<TerminalViewSessionState>? = null

        /** When Skein started ending the thread before closing its tab, in monotonic milliseconds, or null; see [CloseGate]. */
        @Volatile
        var endingSince: Long? = null

        /** Skein closes the tab itself now, so its close check lets it through. */
        @Volatile
        var released = false

        /** The title Skein last asked for; kept here when it comes before the tab, which applies it once it exists. */
        @Volatile
        var title: String? = null

        /** The tab's title, set on the EDT once the tab exists; EDT only. */
        var tabTitle: TerminalTitle? = null

        /**
         * The tab's content in the Terminal tool window, kept when the move into the editor starts, so a tab the move
         * left there can still be closed; EDT only.
         */
        var content: Content? = null
    }

    private val handles = ConcurrentHashMap<String, Handle>()

    @Volatile
    private var listener: ThreadTerminalListener? = null

    @Volatile
    private var projectClosing = false

    /** The IDE quits for sure (its own exit checks passed); set once, never cleared. */
    @Volatile
    private var appClosing = false

    /** Selections Skein causes itself (opening, moving, reselecting, closing) are not the user opening a thread. */
    private val own = OwnSelections(QUIET_AFTER_PROGRAMMATIC_MILLIS, ::monotonicMillis)

    private fun userCaused(): Boolean = own.userCaused()

    init {
        project.messageBus.connect(scope).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (projectClosing) return
                    val id = file.getUserData(THREAD_ID) ?: return
                    // moving a tab to another split or a new window closes and reopens the same file: wait, then decide
                    scope.launch(Dispatchers.EDT) {
                        delay(REOPEN_GRACE_MILLIS)
                        if (!projectClosing && !FileEditorManager.getInstance(project).isFileOpen(file)) closed(id)
                    }
                }

                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (!userCaused()) return
                    val id = event.newFile?.getUserData(THREAD_ID) ?: return
                    listener?.tabSelected(id)
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(
            ProjectCloseListener.TOPIC,
            object : ProjectCloseListener {
                override fun projectClosing(project: Project) {
                    if (project == this@TerminalAdapter.project) projectClosing = true
                }
            },
        )
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(
            AppLifecycleListener.TOPIC,
            object : AppLifecycleListener {
                // not appClosing, which comes before the exit checks: a cancelled exit would leave the close check off
                override fun appWillBeClosed(isRestart: Boolean) {
                    appClosing = true
                }
            },
        )
    }

    override fun setListener(listener: ThreadTerminalListener) {
        this.listener = listener
    }

    override fun open(threadId: String, folder: String, tabName: String, focus: Boolean): ThreadTab {
        val handle = Handle(threadId, folder)
        handles[threadId] = handle
        scope.launch(Dispatchers.EDT) {
            try {
                val tab = try {
                    TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
                        .workingDirectory(folder)
                        .tabName(tabName)
                        .requestFocus(false)
                        .deferSessionStartUntilUiShown(!focus)
                        .closeOnProcessTermination(false)
                        .restoreOnProjectReopen(false)
                        .createTab()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Skein could not open a terminal tab in $folder", e)
                    handle.tab.completeExceptionally(e)
                    handle.file.completeExceptionally(e)
                    closed(threadId) // a thread without a tab must not linger in the list or come back on every restart
                    return@launch
                }
                try {
                    handle.session = tab.view.sessionState
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // without it the close check lets the tab close at once and a close does not wait, as before
                    LOG.warn("Skein cannot follow the terminal session of thread $threadId", e)
                }
                handle.tab.complete(tab)
                try {
                    handle.tabTitle = tab.view.title
                    applyTitle(handle) // a rename that came before the tab
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Skein could not name the tab of thread $threadId", e)
                }
                watchFocus(threadId, tab)
                val file = try {
                    moveToEditor(handle, tab, focus)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Skein could not move the terminal tab into the editor; it stays in the Terminal tool window", e)
                    handle.file.completeExceptionally(e)
                    followInToolWindow(threadId, tab)
                    selectInToolWindow(tab)
                    return@launch
                }
                markThread(file, threadId)
                handle.file.complete(file)
                // the tab was drawn before it knew its thread: ask for its colour again now, so a restored coloured
                // thread is tinted without waiting for a later re-query
                try {
                    FileEditorManager.getInstance(project).updateFilePresentation(file)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Skein could not refresh the tab of thread $threadId", e)
                }
            } catch (e: Throwable) {
                // whatever stopped the setup, nothing may wait for this tab forever (a Close waits for its editor file)
                val failure = IllegalStateException("the terminal tab of thread $threadId was not set up", e)
                handle.tab.completeExceptionally(failure)
                handle.file.completeExceptionally(failure)
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Skein could not set up the terminal tab of thread $threadId", e)
            }
        }
        return handle
    }

    /**
     * Runs the bundled Terminal.MoveToEditor action on [tab] and returns the editor file it opened. Keeps the tab's
     * content on [handle] first, so a tab the move leaves in the Terminal tool window can be closed there.
     */
    private suspend fun moveToEditor(handle: Handle, tab: TerminalToolWindowTab, focus: Boolean): VirtualFile {
        val content = tab.content
        handle.content = content
        val action = ActionManager.getInstance().getAction(MOVE_TO_EDITOR_ACTION_ID)
            ?: throw IllegalStateException("action $MOVE_TO_EDITOR_ACTION_ID is not registered")
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?: throw IllegalStateException("no $TERMINAL_TOOL_WINDOW_ID tool window")
        val editors = FileEditorManager.getInstance(project)
        val previouslySelected = editors.selectedEditor?.file
        val before = editors.openFiles.toSet()
        own.begin()
        try {
            toolWindow.contentManager.setSelectedContent(content)
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(PlatformDataKeys.TOOL_WINDOW, toolWindow)
                .add(PlatformDataKeys.TOOL_WINDOW_CONTENT_MANAGER, toolWindow.contentManager)
                .build()
            ActionUtil.performAction(action, AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null))
            val file = (editors.openFiles.toSet() - before).singleOrNull()
                ?: throw IllegalStateException("$MOVE_TO_EDITOR_ACTION_ID opened no editor for the terminal tab")
            if (!focus && previouslySelected != null) {
                try {
                    // without focus: a focus change would be reported later as the user opening that thread
                    show(previouslySelected, false)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Skein could not bring back the editor that was selected before", e)
                }
            }
            return file
        } finally {
            own.end()
        }
    }

    private suspend fun show(file: VirtualFile, focus: Boolean) {
        FileEditorManager.getInstance(project).openFile(file, FileEditorOpenRequest.withFocus(focus).withReuseOpen(true))
    }

    /** Clicking into a thread's terminal counts as opening it, even when its tab was already the selected one. */
    private fun watchFocus(threadId: String, tab: TerminalToolWindowTab) {
        tab.view.preferredFocusableComponent.addFocusListener(
            object : FocusAdapter() {
                override fun focusGained(e: FocusEvent) {
                    if (userCaused()) listener?.tabSelected(threadId)
                }
            },
        )
    }

    /** The degraded path: the tab stayed in the Terminal tool window, so follow it there. */
    private fun followInToolWindow(threadId: String, tab: TerminalToolWindowTab) {
        val contentManager = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)?.contentManager ?: return
        contentManager.addContentManagerListener(
            object : ContentManagerListener {
                override fun contentRemoved(event: ContentManagerEvent) {
                    if (event.content != tab.content || projectClosing) return
                    contentManager.removeContentManagerListener(this)
                    closed(threadId)
                }

                override fun selectionChanged(event: ContentManagerEvent) {
                    if (userCaused() && event.content == tab.content && event.operation == ContentManagerEvent.ContentOperation.add) {
                        listener?.tabSelected(threadId)
                    }
                }
            },
        )
    }

    private fun closed(threadId: String) {
        if (handles.remove(threadId) != null) listener?.tabClosed(threadId)
    }

    override fun type(tab: ThreadTab, command: String) {
        val handle = tab as Handle
        // the last guard before a shell: only `claude` and `claude --resume <uuid>` ever get through, no exceptions
        if (!ClaudeCommand.isAllowed(command)) {
            LOG.warn("Skein refused to type a command that is not plain claude")
            return
        }
        scope.launch(Dispatchers.EDT) {
            val view = try {
                handle.tab.await().view
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                return@launch
            }
            if (view.sessionState.first { it != TerminalViewSessionState.NotStarted } == TerminalViewSessionState.Terminated) {
                LOG.warn("Skein will not type into a terminal whose shell has exited")
                return@launch
            }
            val readiness = PromptReadiness()
            val startedAt = monotonicMillis()
            val subscription = Disposer.newDisposable("skein prompt readiness")
            try {
                val output = view.outputModels.regular
                output.addListener(
                    subscription,
                    object : TerminalOutputModelListener {
                        override fun afterContentChanged(event: TerminalContentChangeEvent) {
                            readiness.onOutput(monotonicMillis())
                        }
                    },
                )
                // a shell that already printed its prompt produces no new output; count what is there as output
                if (output.textLength > 0) readiness.onOutput(monotonicMillis())
                while (!readiness.shouldSend(monotonicMillis(), startedAt)) delay(READINESS_POLL_MILLIS)
            } finally {
                Disposer.dispose(subscription)
            }
            // right before the text goes, off the EDT: the tab's shell, which printed the prompt, is among them
            handle.shellsAtCommand = shellsNow(handle)
            view.sendText(command + "\n")
        }
    }

    override fun select(tab: ThreadTab) {
        val handle = tab as Handle
        scope.launch(Dispatchers.EDT) {
            val file = try {
                handle.file.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            own.begin()
            try {
                if (file != null) {
                    show(file, true)
                } else {
                    try {
                        selectInToolWindow(handle.tab.await())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LOG.warn("Skein has no terminal to select for thread ${handle.id}", e)
                    }
                }
            } finally {
                own.end()
            }
        }
    }

    override fun close(tabs: List<ThreadTab>) {
        val closing = tabs.map { it as Handle }
        scope.launch(Dispatchers.EDT) {
            // read when the closing starts: the tab in front goes last, so the neighbour the IDE then selects is not
            // the next one to close (Skein takes that selection as the user opening the thread)
            val front = try {
                FileEditorManager.getInstance(project).selectedEditor?.file?.getUserData(THREAD_ID)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                null
            }
            CloseOrder.closeInOrder(closing, front, own) { handle -> closeTab(handle) }
        }
    }

    /**
     * Closes one tab where it is. A tab still on its way into the editor is closed once it got there; one that has not
     * got there after [TAB_READY_WAIT_MILLIS] is skipped with a warning, so it cannot hold up the rest of a batch. EDT only.
     */
    private suspend fun closeTab(handle: Handle) {
        val file = try {
            withTimeoutOrNull(TAB_READY_WAIT_MILLIS) { handle.file.await() } ?: run {
                LOG.warn("Skein did not close the tab of thread ${handle.id}: it has not reached the editor or the Terminal tool window")
                handle.endingSince = null
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        // Skein's own close, after the thread was ended (or the wait timed out): its close check lets this one through
        handle.released = true
        try {
            if (file != null) closeEditorTab(file) else closeToolWindowTab(handle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not close the tab of thread ${handle.id}", e)
        }
        try {
            // something else kept the tab open: a later close asks again and ends what runs in it first
            if (stillOpen(handle, file)) {
                handle.released = false
                handle.endingSince = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not tell whether the tab of thread ${handle.id} closed", e)
        }
    }

    /** Whether [handle]'s tab is still there: in the editor when it has an editor [file], else in the Terminal tool window. EDT only. */
    private fun stillOpen(handle: Handle, file: VirtualFile?): Boolean {
        if (file != null) return FileEditorManager.getInstance(project).isFileOpen(file)
        val content = handle.content ?: return false
        val manager = content.manager ?: return false
        return manager.getIndexOfContent(content) >= 0
    }

    /**
     * What the editor tab's own close button does (`CloseTab` calls `closeFileWithChecks`), in each editor window that
     * shows [file], stopping at a window where a pre-close check keeps it. Skein's own check lets the file through, as
     * the caller released it; no check of the IDE or the terminal plugin asks about this terminal file. The
     * `fileClosed` listener then reports the close. EDT only.
     */
    private fun closeEditorTab(file: VirtualFile) {
        val editors = FileEditorManagerEx.getInstanceEx(project)
        for (window in editors.windows) {
            // a pinned tab closes too: CloseTab would unpin it first behind a registry key, but Close from the menu means close
            if (window.isFileOpen(file) && !editors.closeFileWithChecks(file, window)) return
        }
    }

    /**
     * What the Terminal tool window's tab close button does on the degraded path: the content is removed through its
     * manager, so the tool window's close listeners run as for that button and may ask the IDE's own question after
     * Skein's, or keep the tab; the listener [followInToolWindow] added reports a close that happens. EDT only.
     */
    private fun closeToolWindowTab(handle: Handle) {
        val content = handle.content
        val manager = content?.manager
        if (content == null || manager == null || manager.getIndexOfContent(content) < 0) {
            LOG.warn("Skein found no tab to close for thread ${handle.id} in the Terminal tool window")
            return
        }
        manager.removeContent(content, true)
        // a close listener of the tool window may keep the tab (the IDE's own question answered no)
        if (manager.getIndexOfContent(content) >= 0) LOG.info("The Terminal tool window kept the tab of thread ${handle.id} open")
    }

    override fun markEnding(tabs: List<ThreadTab>) {
        val now = monotonicMillis()
        for (tab in tabs) (tab as Handle).endingSince = now
    }

    override fun clearEnding(threadIds: Collection<String>) {
        for (id in threadIds) handles[id]?.endingSince = null
    }

    override fun sessionRunning(tab: ThreadTab): Boolean = (tab as Handle).session?.value == TerminalViewSessionState.Running

    // the platform turns the state Running once it has the started session (TerminalViewImpl.connectToSession), a moment
    // after it spawned the shell: NotStarted is the last sign that the tab's shell may not exist yet
    override fun sessionStarted(tab: ThreadTab): Boolean =
        (tab as Handle).session?.value.let { it != null && it != TerminalViewSessionState.NotStarted }

    override suspend fun awaitEnded(tab: ThreadTab) {
        val handle = tab as Handle
        try {
            handle.tab.await()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return // no tab, so no session to wait for
        }
        val session = handle.session ?: return
        session.first { it == TerminalViewSessionState.Terminated }
    }

    /** The IDE's child processes now ([ideShells]), listed off the EDT. */
    private suspend fun shellsNow(handle: Handle): ShellsAtCommand = try {
        ShellsAtCommand.Listed(withContext(Dispatchers.IO) { ideShells() })
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // without them Skein cannot prove the tab's shell, so a close hangs up nothing in this tab
        LOG.warn("Skein could not list the IDE's processes before typing into the tab of thread ${handle.id}", e)
        ShellsAtCommand.NotListed
    }

    /**
     * What Skein's close check ([io.github.v0rest.skein.threads.ThreadTabCloseCheck]) answers when the user closes
     * [files] in the editor, one tab or a whole Close All: true to let them close, false to keep them open while Skein
     * ends the threads and then closes their tabs itself, all the thread tabs of that close in one batch ([CloseGate]).
     * After a false the IDE keeps every file of that close open, so Skein closes the others right away itself
     * ([replayOthersQuietly]). Files that are not this project's thread tabs do not count. EDT only (the IDE asks its
     * close checks there); never throws but for cancellation.
     */
    fun allowsClose(files: Collection<VirtualFile>): Boolean {
        val allowed = threadTabsMayClose(files)
        if (!allowed) replayOthersQuietly(files)
        return allowed
    }

    private fun threadTabsMayClose(files: Collection<VirtualFile>): Boolean {
        val mine = LinkedHashMap<String, Pair<VirtualFile, Handle>>()
        for (file in files) {
            val threadId = threadIdOf(file) ?: continue
            val handle = handles[threadId] ?: continue
            mine[threadId] = file to handle
        }
        if (mine.isEmpty()) return true
        val now = monotonicMillis()
        val windows = FileEditorManagerEx.getInstanceEx(project).windows
        val tabs = mine.map { (id, entry) ->
            val (file, handle) = entry
            CloseGate.Tab(
                id = id,
                released = handle.released,
                running = handle.session?.value == TerminalViewSessionState.Running,
                views = windows.count { it.isFileOpen(file) },
                endingForMillis = handle.endingSince?.let { now - it },
            )
        }
        val batch = CloseGate.decideBatch(tabs, closing = projectClosing || appClosing || project.isDisposed)
        if (batch.pass) return true
        if (batch.take.isEmpty()) return false // only tabs Skein is ending already: it closes them itself
        val taken = batch.take.mapNotNull { mine[it]?.second }
        for (handle in taken) handle.endingSince = now
        // the listener ends the threads and closes their tabs; without one, or when it cannot, they close as before
        if (listener?.closeRequested(batch.take) == true) return false
        for (handle in taken) handle.endingSince = null
        return batch.waiting.isEmpty()
    }

    /** Set once a replay failed, so a broken one does not fill the log; EDT only. */
    private var replayFailed = false

    /**
     * Closes the other files of a close Skein just said no to ([CloseReplay]): right after the check returns, on the EDT,
     * each through `closeFileWithChecks` as the IDE would, so its own checks (an unsaved change, other pre-close checks)
     * still run for them, and as Skein's own selections ([own]), so the tabs the IDE selects meanwhile do not count as
     * the user opening a thread. Only with one editor window; a file closed or opened again meanwhile is left alone.
     * The thread tabs of that close go after their ending, as before. Never throws but for cancellation; a failure
     * leaves the files open, as the IDE did, and is logged once.
     */
    private fun replayOthersQuietly(request: Collection<VirtualFile>) {
        try {
            val editors = FileEditorManagerEx.getInstanceEx(project)
            val windows = editors.windows
            val window = windows.singleOrNull() ?: return
            val files = CloseReplay.filesToReplay(request.toList(), { threadIdOf(it) != null }, windows.size) { window.isFileOpen(it) }
            if (files.isEmpty()) return
            // the editor each file shows now: a file closed and opened again meanwhile is not what this close was about
            val shown = files.associateWith { editors.getEditorList(it).firstOrNull() }
            scope.launch(Dispatchers.EDT) {
                try {
                    val windowsNow = editors.windows
                    val still = CloseReplay.filesToReplay(files, { false }, windowsNow.size) { file ->
                        windowsNow.singleOrNull() === window && window.isFileOpen(file) &&
                            editors.getEditorList(file).firstOrNull().let { it != null && it === shown[file] }
                    }
                    if (still.isEmpty()) return@launch
                    own.begin()
                    try {
                        for (file in still) if (window.isFileOpen(file)) editors.closeFileWithChecks(file, window)
                    } finally {
                        own.end()
                    }
                } catch (e: Throwable) {
                    if (e is CancellationException || e is ControlFlowException) throw e
                    replayFailed(e)
                }
            }
        } catch (e: Throwable) {
            if (e is CancellationException || e is ControlFlowException) throw e
            replayFailed(e)
        }
    }

    private fun replayFailed(e: Throwable) {
        if (!replayFailed) LOG.warn("Skein could not close the other files of a close it held for its threads; they stay open", e)
        replayFailed = true
    }

    override fun refreshPresentation(threadId: String) {
        val handle = handles[threadId] ?: return
        scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            try {
                // a tab whose move into the editor failed completed with that failure: there is no editor tab to refresh
                if (!handle.file.isCompleted || handle.file.isCancelled) return@launch
                val file = handle.file.await()
                FileEditorManager.getInstance(project).updateFilePresentation(file)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Skein could not refresh the tab of thread $threadId", e)
            }
        }
    }

    override fun rename(threadId: String, title: String) {
        val handle = handles[threadId] ?: return
        handle.title = title
        // any modality, like refreshPresentation: a tab title is no reason to wait behind an open modal dialog
        scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) { applyTitle(handle) }
    }

    /**
     * Gives the tab the title Skein last asked for as its user title. EDT only; does nothing before the tab exists, and
     * `open` calls it once the tab does, so a title kept on the handle before then is not lost.
     */
    private fun applyTitle(handle: Handle) {
        val tabTitle = handle.tabTitle ?: return
        val title = handle.title ?: return
        try {
            tabTitle.change { userDefinedTitle = title }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not name the tab of thread ${handle.id}", e)
        }
    }

    private fun selectInToolWindow(tab: TerminalToolWindowTab) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID) ?: return
        toolWindow.activate { toolWindow.contentManager.setSelectedContent(tab.content, true) }
    }

    // any modality: this read-only check must not wait behind an open modal dialog, or every status would freeze with it
    override suspend fun tabsInView(): TabsInView = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
        val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val focusInThisProject = active != null && DataManager.getInstance().getDataContext(active).getData(CommonDataKeys.PROJECT) == project
        val selected = FileEditorManager.getInstance(project).selectedEditor?.file?.getUserData(THREAD_ID)
        TabsInView(
            inView = ViewRule.inView(
                appActive = ApplicationManager.getApplication().isActive,
                focusInThisProject = focusInThisProject,
                selectedThreadOfFocusedWindow = selected,
            ),
            // no focus check: the list marks the tab in front also while the IDE is in the background
            inFront = selected,
        )
    }

    private fun monotonicMillis(): Long = System.nanoTime() / 1_000_000

    companion object {
        private val LOG = logger<TerminalAdapter>()
        private val THREAD_ID = Key.create<String>("skein.threadId")

        /** The thread whose terminal this editor file shows, or null for any other file. */
        fun threadIdOf(file: VirtualFile): String? = file.getUserData(THREAD_ID)

        /** Remembers on the editor file which thread it shows; [threadIdOf] reads it back. */
        internal fun markThread(file: VirtualFile, threadId: String) = file.putUserData(THREAD_ID, threadId)

        private const val TERMINAL_TOOL_WINDOW_ID = "Terminal"
        private const val MOVE_TO_EDITOR_ACTION_ID = "Terminal.MoveToEditor"
        private const val READINESS_POLL_MILLIS = 100L
        private const val REOPEN_GRACE_MILLIS = 1_000L
        private const val QUIET_AFTER_PROGRAMMATIC_MILLIS = 1_000L

        /** How long a Close waits for a tab that is still being set up; it normally takes well under a second. */
        private const val TAB_READY_WAIT_MILLIS = 5_000L
    }
}
