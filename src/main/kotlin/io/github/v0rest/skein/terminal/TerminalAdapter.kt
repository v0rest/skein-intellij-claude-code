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
import com.intellij.terminal.frontend.toolwindow.TerminalTabsManagerListener
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTab
import com.intellij.terminal.frontend.toolwindow.TerminalToolWindowTabsManager
import com.intellij.terminal.frontend.view.TerminalViewSessionState
import com.intellij.ui.content.Content
import com.intellij.ui.content.ContentManager
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
import java.util.Collections
import java.util.WeakHashMap
import java.util.concurrent.ConcurrentHashMap
import javax.swing.SwingUtilities

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
 *   opened is found as the one new entry in FileEditorManager.openFiles. With the setting off ([inEditor]), or if the
 *   move fails, the tab stays in the Terminal tool window.
 * - A tab is one `TerminalToolWindowTab` with one `Content` wherever it goes (intellij-community, tag
 *   idea/263.7307.200, read 2026-11-12): Move to Editor detaches it (`detachTab` removes the content with
 *   `Content.TEMPORARY_REMOVED_KEY` set and publishes `TerminalTabsManagerListener.tabDetached`), a drag of the editor
 *   tab into the Terminal tool window attaches the same tab and content again (`attachTab`, then `tabAdded`), and the
 *   terminal's editor shows `tab.content.component`. The tab is closed for good only when its content is disposed,
 *   which ends its terminal: the editor disposes it when the file closes for good (not when it closes to reopen, as on a
 *   move), the tool window when the content is removed with dispose. So Skein reports a thread closed when its content
 *   is disposed (`Disposer.tryRegister`, no ApiStatus), never because an editor file closed or a content left the tool
 *   window, and looks up where the tab is when it needs to ([TabPlaces]): the Terminal tool window when
 *   `TerminalToolWindowTabsManager.getTabs` (Experimental, EDT) lists it, else the open editor file one of whose
 *   `FileEditorManager.getAllEditors` (no ApiStatus) shows the tab's content component. A file found that way, and one
 *   `fileOpened` reports, is marked with the thread. `TerminalTabsManagerListener.TOPIC` and its `tabAdded` are
 *   Experimental, `ToolWindow.getContentManagerIfCreated`, `Content.TEMPORARY_REMOVED_KEY` and
 *   `TerminalToolWindowTabsManager.closeTab` (Experimental) are what else is new (javap, 2026-11-12).
 * - Closing a tab from Skein takes the tab's own close path, so the same close events run: in the editor
 *   `FileEditorManagerEx.closeFileWithChecks(file, window)`, which is what the editor tab's close button (`CloseTab`)
 *   calls, for each window `FileEditorManagerEx.getWindows` lists whose `EditorWindow.isFileOpen`; in the Terminal
 *   tool window `ContentManager.removeContent(content, true)` on the content's own manager (a pane of a split tool window
 *   has its own). None of these carries an ApiStatus annotation (javap, 2026-11-09).
 * - A terminal tab closed while its shell runs leaves the shell, and a claude in it, hanging forever: the IDE keeps the
 *   tab's pty open but stops reading it (IJPL-259859). So Skein ends the thread first ([awaitEnded] waits for
 *   `TerminalView.getSessionState`, Experimental, to turn Terminated) and closes the tab after. For the editor tab's
 *   own ×, Close All and Close Others, Skein's `VirtualFilePreCloseCheck` (Experimental, [allowsClose]) keeps a running
 *   thread tab open until then, and all the thread tabs of one close are ended and closed together; the tab Skein
 *   closes itself is released, so that check lets it through. The IDE's own checks do not cover this terminal file.
 *   Skein's own closes count as its own selections ([OwnSelections]), so the neighbour the IDE selects is not taken for
 *   the user opening it. In the Terminal tool window the same holds through a `ContentManagerListener` on every content
 *   manager a thread tab sits in: its `contentRemoveQuery` keeps a running thread's tab ([CloseGate.decideTerminalWindowClose])
 *   until Skein has ended the thread and removes the content itself, and lets a removal marked with
 *   `Content.TEMPORARY_REMOVED_KEY` (a move, never a close) through. The terminal plugin's own close question
 *   (`TerminalTabCloseListenerImpl`) is another listener on that content and may come first.
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
    /** Whether a thread opened now goes into the editor area; false keeps it in the Terminal tool window. */
    private val inEditor: () -> Boolean,
) : ThreadTerminal {
    private class Handle(override val id: String, override val folder: String) : ThreadTab {
        val tab = CompletableDeferred<TerminalToolWindowTab>()

        /** Completed once the tab has its first place, the editor or the Terminal tool window; exceptionally when its setup failed. */
        val placed = CompletableDeferred<Unit>()

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

        /** The tab's content, set on the EDT once the tab exists; the same object wherever the tab goes. */
        @Volatile
        var content: Content? = null

        /** The editor file that last showed the tab, found or marked on the EDT; it may have closed since. */
        @Volatile
        var file: VirtualFile? = null

        /** The tab's content was disposed: the tab is closed for good. */
        @Volatile
        var disposed = false
    }

    /** Where a tab is now ([locate]): [file] for [TabPlaces.Where.EDITOR], [content] for [TabPlaces.Where.TERMINAL_WINDOW]. */
    private data class Place(val where: TabPlaces.Where?, val file: VirtualFile? = null, val content: Content? = null)

    private val handles = ConcurrentHashMap<String, Handle>()

    /** The Terminal tool window content managers [terminalWindowListener] listens to; EDT only. */
    private val hookedManagers: MutableSet<ContentManager> = Collections.newSetFromMap(WeakHashMap())

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
                // no fileClosed: a file closes for a move to another split or window and for a drag into the Terminal
                // tool window too; a thread is closed when its tab's content is disposed ([watchDisposal])

                /** A thread's tab that moved into the editor (Move to Editor, a drag) shows up as a new file: mark it. */
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    try {
                        if (file.getUserData(THREAD_ID) != null) return
                        val handle = handles.values.firstOrNull { shows(source, file, it) } ?: return
                        adoptFile(handle, file)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LOG.warn("Skein could not tell whether an opened editor shows one of its threads", e)
                    }
                }

                override fun selectionChanged(event: FileEditorManagerEvent) {
                    if (!userCaused()) return
                    val id = event.newFile?.getUserData(THREAD_ID) ?: return
                    listener?.tabSelected(id)
                }
            },
        )
        project.messageBus.connect(scope).subscribe(
            TerminalTabsManagerListener.TOPIC,
            object : TerminalTabsManagerListener {
                /** A thread's tab dragged into the Terminal tool window, maybe into a pane of its own: listen to that pane. */
                override fun tabAdded(tab: TerminalToolWindowTab) {
                    try {
                        val handle = handles.values.firstOrNull { it.content != null && it.content === tab.content } ?: return
                        hookManagerOf(handle)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LOG.warn("Skein could not follow a thread tab into the Terminal tool window", e)
                    }
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
        val toEditor = try {
            inEditor()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not read where threads open; this one opens in the editor", e)
            true
        }
        scope.launch(Dispatchers.EDT) {
            try {
                // a tab created without focus still becomes the Terminal tool window's selected one: kept to give it back
                val selectedBefore = if (toEditor || focus) null else terminalWindowSelection()
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
                    handle.placed.completeExceptionally(e)
                    closed(threadId) // a thread without a tab must not linger in the list or come back on every restart
                    return@launch
                }
                handle.content = tab.content
                watchDisposal(handle, tab.content)
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
                if (!toEditor) {
                    stayInTerminalWindow(handle, tab.content, focus, selectedBefore)
                    return@launch
                }
                val file = try {
                    moveToEditor(tab, focus)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Skein could not move the terminal tab into the editor; it stays in the Terminal tool window", e)
                    stayInTerminalWindow(handle, tab.content, focus, null)
                    return@launch
                }
                adoptFile(handle, file)
                handle.placed.complete(Unit)
            } catch (e: Throwable) {
                // whatever stopped the setup, nothing may wait for this tab forever (a Close waits for its place)
                val failure = IllegalStateException("the terminal tab of thread $threadId was not set up", e)
                handle.tab.completeExceptionally(failure)
                handle.placed.completeExceptionally(failure)
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Skein could not set up the terminal tab of thread $threadId", e)
            }
        }
        return handle
    }

    /**
     * Runs the bundled Terminal.MoveToEditor action on [tab] and returns the editor file it opened. A move that fails
     * leaves the tab in the Terminal tool window, where the caller keeps it.
     */
    private suspend fun moveToEditor(tab: TerminalToolWindowTab, focus: Boolean): VirtualFile {
        val content = tab.content
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

    /**
     * The tab stays in the Terminal tool window (the setting, or a failed move): listen to its pane, and with [focus]
     * bring the tool window up on it; without, give the pane back the tab it had selected ([selectedBefore]). EDT only.
     */
    private fun stayInTerminalWindow(handle: Handle, content: Content, focus: Boolean, selectedBefore: Content?) {
        hookManagerOf(handle)
        handle.placed.complete(Unit)
        try {
            if (focus) {
                selectInToolWindow(content)
            } else if (selectedBefore != null && selectedBefore !== content) {
                own.begin()
                try {
                    selectedBefore.manager?.setSelectedContent(selectedBefore, false)
                } finally {
                    own.end()
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not show the Terminal tool window tab of thread ${handle.id}", e)
        }
    }

    /** The Terminal tool window's selected tab in its main pane, or null. EDT only. */
    private fun terminalWindowSelection(): Content? = try {
        ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)?.contentManagerIfCreated?.selectedContent
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /**
     * Reports the thread closed once [content] is disposed, wherever the tab is then: that is when the IDE closes a
     * terminal tab for good. Not while the project closes, as before. EDT (the callback runs where the content is disposed).
     */
    private fun watchDisposal(handle: Handle, content: Content) {
        val gone = {
            handle.disposed = true
            if (!projectClosing) closed(handle.id)
        }
        if (!Disposer.tryRegister(content) { gone() }) gone()
    }

    /** Marks [file] as showing [handle]'s thread and asks the IDE to draw its tab again, so its colour shows. EDT only. */
    private fun adoptFile(handle: Handle, file: VirtualFile) {
        markThread(file, handle.id)
        handle.file = file
        try {
            FileEditorManager.getInstance(project).updateFilePresentation(file)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not refresh the tab of thread ${handle.id}", e)
        }
    }

    /** Whether an editor of [file] shows [handle]'s tab: the terminal's editor component is the tab's content component. EDT only. */
    private fun shows(editors: FileEditorManager, file: VirtualFile, handle: Handle): Boolean {
        val content = handle.content ?: return false
        if (handle.disposed) return false
        val component = content.component
        return editors.getAllEditors(file).any { it.component === component || SwingUtilities.isDescendingFrom(component, it.component) }
    }

    /** Where [handle]'s tab is now ([TabPlaces]); a newly found editor file is marked. EDT only. */
    private fun locate(handle: Handle): Place {
        val content = handle.content ?: return Place(null)
        if (handle.disposed || projectClosing) return Place(TabPlaces.where(projectClosing, handle.disposed, false, false))
        val inTerminalWindow = TerminalToolWindowTabsManager.getInstance(project).tabs.any { it.content === content }
        val editors = FileEditorManager.getInstance(project)
        val file = if (inTerminalWindow) {
            null
        } else {
            handle.file?.takeIf { editors.isFileOpen(it) && shows(editors, it, handle) }
                ?: editors.openFiles.firstOrNull { shows(editors, it, handle) }?.also { adoptFile(handle, it) }
        }
        return when (val where = TabPlaces.where(projectClosing = false, disposed = false, inTerminalWindow, file != null)) {
            TabPlaces.Where.TERMINAL_WINDOW -> Place(where, content = content)
            TabPlaces.Where.EDITOR -> Place(where, file = file)
            else -> Place(where)
        }
    }

    /** Listens to the content manager [handle]'s tab sits in, once per manager (a split Terminal tool window has one per pane). EDT only. */
    private fun hookManagerOf(handle: Handle) {
        val manager = handle.content?.manager ?: return
        if (hookedManagers.add(manager)) manager.addContentManagerListener(terminalWindowListener)
    }

    private fun handleOf(content: Content?): Handle? = content?.let { c -> handles.values.firstOrNull { it.content === c } }

    /**
     * On every Terminal tool window pane that holds a thread tab: a close of a running thread's tab waits until Skein
     * has ended the thread ([holdsTerminalWindowClose]); a tab that moved to another pane gets that pane listened to; a
     * selection the user makes counts as opening the thread. Never throws but for cancellation.
     */
    private val terminalWindowListener = object : ContentManagerListener {
        override fun contentRemoveQuery(event: ContentManagerEvent) {
            try {
                if (event.isConsumed) return // the IDE's own question was answered no
                val content = event.content
                val handle = handleOf(content) ?: return
                if (holdsTerminalWindowClose(handle, move = content.getUserData(Content.TEMPORARY_REMOVED_KEY) == true)) event.consume()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Skein could not check a thread tab before the Terminal tool window closed it; it closes at once", e)
            }
        }

        override fun contentRemoved(event: ContentManagerEvent) {
            val handle = handleOf(event.content) ?: return
            // a split or a move between panes adds the content to another manager right after
            scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                delay(REOPEN_GRACE_MILLIS)
                try {
                    hookManagerOf(handle)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Skein could not follow the tab of thread ${handle.id} in the Terminal tool window", e)
                }
            }
        }

        override fun selectionChanged(event: ContentManagerEvent) {
            if (!userCaused() || event.operation != ContentManagerEvent.ContentOperation.add) return
            val handle = handleOf(event.content) ?: return
            listener?.tabSelected(handle.id)
        }
    }

    /**
     * Whether the Terminal tool window keeps [handle]'s tab open now, as [threadTabsMayClose] does for the editor: a
     * running thread is ended first and Skein removes the content itself afterwards; one Skein ends already stays until
     * then; a [move] or anything else goes ahead. EDT only.
     */
    private fun holdsTerminalWindowClose(handle: Handle, move: Boolean): Boolean {
        val now = monotonicMillis()
        val tab = CloseGate.Tab(
            id = handle.id,
            released = handle.released,
            running = handle.session?.value == TerminalViewSessionState.Running,
            views = 1,
            endingForMillis = handle.endingSince?.let { now - it },
        )
        val batch = CloseGate.decideTerminalWindowClose(tab, closing = projectClosing || appClosing || project.isDisposed, move = move)
        if (batch.pass) return false
        if (batch.take.isEmpty()) return true // Skein ends it already and closes the tab itself
        handle.endingSince = now
        if (listener?.closeRequested(batch.take) == true) return true
        handle.endingSince = null
        return batch.waiting.isNotEmpty()
    }

    /** The threads whose tab is the selected one of a pane of the Terminal tool window while it is visible. EDT only. */
    private fun threadsSelectedInTerminalWindow(): Set<String> {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID) ?: return emptySet()
        if (!toolWindow.isVisible) return emptySet()
        return handles.values.filter { handle ->
            val content = handle.content
            content != null && !handle.disposed && content.manager?.selectedContent === content
        }.mapTo(LinkedHashSet()) { it.id }
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
            try {
                handle.placed.await()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // a setup that failed after the tab was made still leaves a tab to show
                if (handle.content == null) {
                    LOG.warn("Skein has no terminal to select for thread ${handle.id}", e)
                    return@launch
                }
            }
            own.begin()
            try {
                val place = settledPlace(handle)
                when (place.where) {
                    TabPlaces.Where.EDITOR -> show(place.file!!, true)
                    TabPlaces.Where.TERMINAL_WINDOW -> selectInToolWindow(place.content!!)
                    else -> LOG.info("Skein found no tab to select for thread ${handle.id}")
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Skein could not select the tab of thread ${handle.id}", e)
            } finally {
                own.end()
            }
        }
    }

    /** [locate], and once more after [REOPEN_GRACE_MILLIS] when the tab is on its way between the editor and the tool window. EDT only. */
    private suspend fun settledPlace(handle: Handle): Place {
        val place = locate(handle)
        if (place.where != TabPlaces.Where.MOVING) return place
        delay(REOPEN_GRACE_MILLIS)
        return locate(handle)
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
     * Closes one tab where it is now. A tab still being set up is closed once it has its place; one that has none after
     * [TAB_READY_WAIT_MILLIS] is skipped with a warning, so it cannot hold up the rest of a batch. A tab on its way
     * between the editor and the Terminal tool window is looked for once more a moment later, and one still in neither
     * place then is closed through the terminal plugin, which releases its content. EDT only.
     */
    private suspend fun closeTab(handle: Handle) {
        try {
            withTimeoutOrNull(TAB_READY_WAIT_MILLIS) { handle.placed.await() } ?: run {
                LOG.warn("Skein did not close the tab of thread ${handle.id}: it has not reached the editor or the Terminal tool window")
                handle.endingSince = null
                return
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // a setup that failed after the tab was made leaves a tab to close; without one the thread was reported closed
            if (handle.content == null) {
                handle.endingSince = null
                return
            }
        }
        val place = try {
            settledPlace(handle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not tell where the tab of thread ${handle.id} is", e)
            Place(null)
        }
        if (place.where == TabPlaces.Where.MOVING) {
            releaseStrandedTab(handle)
            return
        }
        if (place.where != TabPlaces.Where.EDITOR && place.where != TabPlaces.Where.TERMINAL_WINDOW) {
            if (place.where != TabPlaces.Where.GONE) LOG.warn("Skein found no tab to close for thread ${handle.id}")
            handle.endingSince = null
            return
        }
        // Skein's own close, after the thread was ended (or the wait timed out): its close checks let this one through
        handle.released = true
        try {
            if (place.where == TabPlaces.Where.EDITOR) closeEditorTab(place.file!!) else closeToolWindowTab(handle, place.content!!)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not close the tab of thread ${handle.id}", e)
        }
        try {
            // something else kept the tab open: a later close asks again and ends what runs in it first
            if (stillOpen(place)) {
                handle.released = false
                handle.endingSince = null
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not tell whether the tab of thread ${handle.id} closed", e)
        }
    }

    /**
     * A tab alive but in neither place after the second look, which no move the IDE makes should leave: closed through
     * `TerminalToolWindowTabsManager.closeTab` (Experimental), which releases a content no manager holds; the disposal
     * then reports the thread closed. EDT only.
     */
    private suspend fun releaseStrandedTab(handle: Handle) {
        LOG.warn("The tab of thread ${handle.id} is in neither the editor nor the Terminal tool window; Skein releases it")
        handle.released = true
        try {
            TerminalToolWindowTabsManager.getInstance(project).closeTab(handle.tab.await())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not release the tab of thread ${handle.id}", e)
            handle.released = false
            handle.endingSince = null
        }
    }

    /** Whether the tab is still where [place] found it: its editor file open, or its content in its pane. EDT only. */
    private fun stillOpen(place: Place): Boolean {
        place.file?.let { return FileEditorManager.getInstance(project).isFileOpen(it) }
        val content = place.content ?: return false
        val manager = content.manager ?: return false
        return manager.getIndexOfContent(content) >= 0
    }

    /**
     * What the editor tab's own close button does (`CloseTab` calls `closeFileWithChecks`), in each editor window that
     * shows [file], stopping at a window where a pre-close check keeps it. Skein's own check lets the file through, as
     * the caller released it; no check of the IDE or the terminal plugin asks about this terminal file. The file's
     * close disposes the tab's content, which [watchDisposal] reports. EDT only.
     */
    private fun closeEditorTab(file: VirtualFile) {
        val editors = FileEditorManagerEx.getInstanceEx(project)
        for (window in editors.windows) {
            // a pinned tab closes too: CloseTab would unpin it first behind a registry key, but Close from the menu means close
            if (window.isFileOpen(file) && !editors.closeFileWithChecks(file, window)) return
        }
    }

    /**
     * What the Terminal tool window's tab close button does: the content is removed through its own manager, so the
     * tool window's close listeners run as for that button (Skein's lets it through, the caller released the tab) and
     * may ask the IDE's own question, or keep the tab; a close that happens disposes the content, which [watchDisposal]
     * reports. EDT only.
     */
    private fun closeToolWindowTab(handle: Handle, content: Content) {
        val manager = content.manager
        if (manager == null || manager.getIndexOfContent(content) < 0) {
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
                // only an editor tab has a colour to redraw; a tab still being set up gets it once it reaches the editor
                if (!handle.placed.isCompleted) return@launch
                val place = locate(handle)
                if (place.where != TabPlaces.Where.EDITOR) return@launch
                FileEditorManager.getInstance(project).updateFilePresentation(place.file!!)
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

    /** Brings the Terminal tool window up with [content] selected in its pane. EDT only. */
    private fun selectInToolWindow(content: Content) {
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID) ?: return
        toolWindow.activate { (content.manager ?: toolWindow.contentManager).setSelectedContent(content, true) }
    }

    // any modality: this read-only check must not wait behind an open modal dialog, or every status would freeze with it
    override suspend fun tabsInView(): TabsInView = withContext(Dispatchers.EDT + ModalityState.any().asContextElement()) {
        // each tick also listens to a pane a thread tab reached unseen (a split made after the tab came there)
        for (handle in handles.values) {
            try {
                if (handle.disposed || !handle.placed.isCompleted) continue
                hookManagerOf(handle)
                // and finds the editor file of a tab that moved there when fileOpened could not tell it apart yet
                val file = handle.file
                if (file == null || !FileEditorManager.getInstance(project).isFileOpen(file)) locate(handle)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Skein could not follow the tab of thread ${handle.id} in the Terminal tool window", e)
            }
        }
        val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val focusInThisProject = active != null && DataManager.getInstance().getDataContext(active).getData(CommonDataKeys.PROJECT) == project
        val selected = FileEditorManager.getInstance(project).selectedEditor?.file?.getUserData(THREAD_ID)
        val inTerminalWindow = try {
            threadsSelectedInTerminalWindow()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
        TabsInView(
            inView = ViewRule.inView(
                appActive = ApplicationManager.getApplication().isActive,
                focusInThisProject = focusInThisProject,
                selectedThreadOfFocusedWindow = selected,
                threadsSelectedInTerminalWindow = inTerminalWindow,
            ),
            // no focus check: the list marks the tab in front also while the IDE is in the background
            inFront = ViewRule.inFront(selected, inTerminalWindow),
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
