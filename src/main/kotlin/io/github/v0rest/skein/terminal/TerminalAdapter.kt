package io.github.v0rest.skein.terminal

import com.intellij.ide.AppLifecycleListener
import com.intellij.ide.DataManager
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionPlaces
import com.intellij.openapi.actionSystem.ActionUiKind
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.PlatformDataKeys
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.actionSystem.ex.AnActionListener
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
import com.intellij.terminal.frontend.view.TerminalKeyEvent
import com.intellij.terminal.frontend.view.TerminalKeyEventsListener
import com.intellij.terminal.frontend.view.TerminalView
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.jetbrains.plugins.terminal.view.TerminalContentChangeEvent
import org.jetbrains.plugins.terminal.view.TerminalOutputModelListener
import java.awt.AWTEvent
import java.awt.EventQueue
import java.awt.KeyboardFocusManager
import java.awt.Toolkit
import java.awt.event.AWTEventListener
import java.awt.event.FocusAdapter
import java.awt.event.FocusEvent
import java.awt.event.KeyEvent
import java.awt.event.MouseEvent
import java.lang.ref.WeakReference
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
 *   need TerminalViewVirtualFile (Kotlin-internal) and the IDE's reopen mark on it, which Skein touches only in
 *   [ReopenMark]. The editor file the action opened is found as the open file whose editor shows the tab ([shows]).
 *   The action's openFile runs the EDT's queue while it waits for the editor, so the tabs bound for the editor are set
 *   up and moved one at a time ([moves]) - else a restore's moves nest, and the next tab's setup runs inside the move
 *   before it. With the setting off ([inEditor]), or if the move fails before the tab left it, the tab stays in the
 *   Terminal tool window.
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
 *   the user opening it - only once it stays in front a moment does a restored one start
 *   ([io.github.v0rest.skein.threads.InViewStart]). In the Terminal tool window the same holds through a
 *   `ContentManagerListener` on every content manager a thread tab sits in: its `contentRemoveQuery` keeps a running
 *   thread's tab ([CloseGate.decideTerminalWindowClose]) until Skein has ended the thread and removes the content
 *   itself, and lets a removal marked with `Content.TEMPORARY_REMOVED_KEY` (a move, never a close) through. The
 *   terminal plugin's own close question (`TerminalTabCloseListenerImpl`) is another listener on that content and may
 *   come first.
 * - Right before Skein sends a command into a tab it lists the IDE's child processes with their start instants
 *   ([ThreadTab.shellsAtCommand]): a close hangs up a thread's shell only if it is among them (and, the coordinator
 *   checks, was not open before the tab's shell started), so a terminal the user opened afterwards is never signalled.
 *   Only process start instants are compared, never with the wall clock. [sessionStarted] tells the coordinator when a
 *   tab opened out of view has started its shell.
 * - Whether the IDE quits for sure comes from `AppLifecycleListener.appWillBeClosed` (no ApiStatus);
 *   `Application.isExitInProgress` is deprecated.
 * - The draft guard ([DraftGuard]) hears the keys typed into a thread's tab through `TerminalView.addKeyEventsListener`
 *   with a `TerminalKeyEventsListener` (both Experimental, javap 2026-11-15): its `beforeKeyEvent` comes for every key
 *   press and typed key that reaches the terminal, before the terminal sends it on, with the AWT event
 *   (`TerminalKeyEvent.getAwtEvent`, Experimental), and Skein always answers false, which leaves the key to the terminal.
 *   The terminal's own key dispatcher (TerminalEventDispatcher, javap 2026-11-15) hands the shortcuts of a list of
 *   actions to the IDE instead, and those never reach the listener: paste (`Terminal.Paste`, `Terminal.PasteFromHistory`),
 *   Escape (`Terminal.Escape`) and the completion inserts among them. So `AnActionListener.beforeActionPerformed` (no
 *   ApiStatus) on the application bus marks the tab dirty for an action run with that tab's view as
 *   `TerminalView.DATA_KEY`, unless it only looks ([DraftGuard.touchesInput]); the context menu's Paste comes that way
 *   too; `DATA_KEY` holds the very view the tab has (TerminalViewImpl puts itself there, javap 2026-11-15). An input
 *   method's text comes as `InputMethodEvent`s on whichever editor the terminal shows, the alternate screen's included,
 *   so one app-wide `Toolkit.addAWTEventListener` for them ([composedText]) marks the tab whose view component holds the
 *   event's source. A file dropped on the tab reaches none of these, and neither does text another tool puts into claude.
 *   The same watch tells whether the user typed into a tab at all ([typedInto]) - a restored thread there starts only
 *   when opened. Skein's own move into the editor is no such action - its data context carries no `TerminalView`.
 * - `/rename` goes through `TerminalView.createSendTextBuilder` (Experimental) with `shouldExecute`, in
 *   [ClaudeRename.send] alone ([sendRename]).
 * - The IDE's restore of a branch's workspace, its Rollback and the Tasks plugin's Load Context close every editor with
 *   `FileEditorManagerImpl.closeAllFiles`, which asks no close check ([TabRestore], javap 2026-11-17) - `EditorsSplitters`
 *   takes the editor windows down first, then closes each file through `EditorWindow.closeFile`, which publishes
 *   `FileEditorManagerListener.Before.beforeFileClosed` (no ApiStatus) inside a write-intent read action, removes the tab,
 *   disposes the file's editors (the terminal's editor disposes the tab's content there unless the file carries the
 *   reopen mark) and then publishes `fileClosed`. So [beforeClose] is the last moment the terminal is open and read, and
 *   decides there. A middle click or a Shift+click on an editor tab, and a Shift+Alt+click that closes the window's
 *   other tabs (`TabMouseListener.mouseReleased` -> `FileEditorManagerImpl.closeFile(file, window)`), the Switcher's close and a floating editor window's own close ask
 *   no check either and go through the same `EditorWindow.closeFile`, one file with its window still there, so
 *   [beforeClose] meets them too (javap 2026-11-18). `BranchChangeListener` (no ApiStatus) tells [branchWillChange]
 *   and [branchHasChanged], and `FileEditorOpenRequest.withSelectAsCurrent` (Experimental, like the rest of that class)
 *   reopens a tab without selecting it.
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

        /**
         * Completed once the tab's setup has its turn - it holds [moves], or needs no move - so a Close gives a tab queued
         * behind a restore's moves its few seconds only from then, and exceptionally when its setup failed before that.
         */
        val turn = CompletableDeferred<Unit>()

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

        /** The tab's terminal view, set on the EDT once the tab exists. */
        @Volatile
        var view: TerminalView? = null

        /**
         * What the key watch knows of claude's input in the tab ([DraftGuard]), changed on the EDT only; null while the tab
         * is not watched (not set up yet, or the watch could not be added), which never counts as clean.
         */
        @Volatile
        var draft: DraftGuard.Draft? = null

        /**
         * A key, a paste, the terminal's Escape or an input method's text came into the tab since its setup ([typedInto]),
         * set on the EDT and never cleared - the tab and its shell are the same until the thread gets a new tab.
         */
        @Volatile
        var keysSeen = false

        /**
         * The Enter, by its [DraftGuard.Draft.sentAt], that a turn seen right after it showed sent the draft
         * ([DraftGuard.proven], [claudeSeen]); null before any. Written from one thread at a time.
         */
        @Volatile
        var provenSentAt: Long? = null

        /** The editor file Skein put its reopen mark on for a branch switch ([ReopenMark]), or null. EDT only. */
        var markedFile: VirtualFile? = null

        /** When Skein put that mark there, in monotonic milliseconds ([TabRestore.toLift]). EDT only. */
        var markedAt = 0L

        /**
         * How Skein met the IDE closing this tab past its close check ([TabRestore.beforeClose]), set right before the
         * close disposes anything and kept until the tab is open again or gone, else null. EDT only.
         */
        var pastCheck: TabRestore.Close? = null

        /** The tab was the selected editor when that close began ([TabRestore.selects]). EDT only. */
        var wasInFront = false

        /**
         * Skein's close check let this tab's file close on the user's word ([allowsClose]), which the IDE does in the same
         * turn of the EDT, and cleared right after it. EDT only.
         */
        var askedToClose = false
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

    /** The threads whose claude is idle, where an Enter on a draft may send it, as Skein last saw them ([claudeSeen]). */
    @Volatile
    private var enterMaySend: Set<String> = emptySet()

    /**
     * An input method's text (a CJK input method, an accent picked from a menu) in any thread's tab, whichever editor the
     * terminal shows: it marks that tab as typed into. Called on the EDT for every input method event in the IDE, so it
     * does nothing but find the tab whose view holds the event's source.
     */
    private val composedText = AWTEventListener { event ->
        try {
            if (!ComposedText.counts(event.id)) return@AWTEventListener
            val handle = ComposedText.holderOf(event.source, handles.values) { it.view?.component }
            if (handle != null) onKey(handle, DraftGuard.Key.OTHER)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            LOG.warn("Skein could not tell which thread an input method typed into", e)
        }
    }

    /** Selections Skein causes itself (opening, moving, reselecting, closing) are not the user opening a thread. */
    private val own = OwnSelections(QUIET_AFTER_PROGRAMMATIC_MILLIS, ::monotonicMillis)

    private fun userCaused(): Boolean = own.userCaused()

    /**
     * Held while a tab bound for the editor is set up and moved there ([open]), so the tabs of a restore go one at a time -
     * Terminal.MoveToEditor's openFile runs the EDT's queue while it waits for the editor, and the next tab's setup would
     * run inside it. A New Thread or a History resume opened meanwhile waits its turn too, a tab for the Terminal tool
     * window does not. Taken nowhere else - it is not reentrant, and a move's own events (fileOpened, a tick's locate)
     * adopt the tab while it is held.
     */
    private val moves = Mutex()

    init {
        project.messageBus.connect(scope).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                // a file closes for a move to another split or window and for a drag into the Terminal tool window too, so
                // a thread is closed when its tab's content is disposed ([watchDisposal]), and fileClosed only brings back a tab
                // the IDE closed with Skein's reopen mark ([afterClose])
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    try {
                        afterClose(file)
                    } catch (e: Throwable) {
                        if (e is CancellationException || e is ControlFlowException) throw e
                        LOG.warn("Skein could not look at a closed editor tab", e)
                    }
                }

                /**
                 * A thread's tab that moved into the editor (Move to Editor, a drag) shows up as a new file, and Skein marks it. A
                 * thread's own file opening again after a close past the check was a drag's drop ([seenAgain]).
                 */
                override fun fileOpened(source: FileEditorManager, file: VirtualFile) {
                    try {
                        val known = file.getUserData(THREAD_ID)
                        if (known != null) {
                            handles[known]?.takeIf { it.file === file }?.let(::seenAgain)
                            return
                        }
                        val handle = handles.values.firstOrNull { shows(source, file, it) } ?: return
                        adoptFile(handle, file)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LOG.warn("Skein could not tell whether an opened editor shows one of its threads", e)
                    }
                }

                override fun selectionChanged(event: FileEditorManagerEvent) {
                    // whoever caused it - what the close of a whole editor area later finds in front ([Burst])
                    lastFront = event.newFile?.getUserData(THREAD_ID)
                    if (!userCaused()) return
                    val id = event.newFile?.getUserData(THREAD_ID) ?: return
                    listener?.tabSelected(id)
                }
            },
        )
        project.messageBus.connect(scope).subscribe(
            FileEditorManagerListener.Before.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener.Before {
                // per file, inside the IDE's close and before it disposes the file's editor, which ends a thread's terminal
                override fun beforeFileClosed(source: FileEditorManager, file: VirtualFile) {
                    try {
                        beforeClose(file)
                    } catch (e: Throwable) {
                        if (e is CancellationException || e is ControlFlowException) throw e
                        LOG.warn("Skein could not look at an editor tab before it closed", e)
                    }
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
                        seenAgain(handle)
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
                    if (project != this@TerminalAdapter.project) return
                    projectClosing = true
                    // the project's close disposes every editor, and a marked one would leave its terminal running unseen
                    liftAllMarksQuietly()
                }
            },
        )
        Toolkit.getDefaultToolkit().addAWTEventListener(composedText, AWTEvent.INPUT_METHOD_EVENT_MASK)
        scope.coroutineContext[Job]?.invokeOnCompletion { Toolkit.getDefaultToolkit().removeAWTEventListener(composedText) }
        // a project or an IDE that closes in the middle of a branch switch leaves no mark behind on a live terminal, lifted
        // on the EDT, where the marks are kept
        scope.coroutineContext[Job]?.invokeOnCompletion {
            val application = ApplicationManager.getApplication()
            if (application.isDispatchThread) liftAllMarksQuietly() else application.invokeLater({ liftAllMarksQuietly() }, ModalityState.any())
        }
        ApplicationManager.getApplication().messageBus.connect(scope).subscribe(
            AnActionListener.TOPIC,
            object : AnActionListener {
                // a paste or Escape in a thread's tab is an IDE action, which the key watch never hears
                override fun beforeActionPerformed(action: AnAction, event: AnActionEvent) {
                    try {
                        val view = event.getData(TerminalView.DATA_KEY) ?: return
                        val handle = handles.values.firstOrNull { it.view === view } ?: return
                        if (DraftGuard.touchesInput(ActionManager.getInstance().getId(action))) onKey(handle, DraftGuard.Key.OTHER)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        LOG.warn("Skein could not tell whether an action changed the input of a thread", e)
                    }
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

    /**
     * The project closes from now on, which `ProjectCloseListener.projectClosing` would say only later. The service ends
     * the threads before the project saves, and the terminal then closes each tab whose shell ended by itself - such a
     * tab is no thread closing, so the saved threads keep it, and the close check lets every tab go. Set once, never
     * cleared, like the listener's own flag.
     */
    fun projectCloses() {
        projectClosing = true
        liftAllMarksQuietly()
    }

    /**
     * The counterpart of [projectCloses], for a close refused after Skein ended the threads - the project stays open.
     * A tab's close is a thread's close again, and a tab the IDE closed meanwhile, its shell ended, is reported closed
     * now, so its row does not stay behind. EDT.
     */
    fun projectStaysOpen() {
        projectClosing = false
        for (handle in handles.values) if (handle.disposed) closed(handle)
    }

    override fun open(threadId: String, folder: String, tabName: String, focus: Boolean, start: Boolean): ThreadTab {
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
                // in the editor one tab at a time - the move's openFile runs the EDT's queue while it waits for the editor,
                // and the next thread's setup would run inside it - a restore of several threads nested its moves that way
                if (toEditor) {
                    moves.withLock { setUp(handle, tabName, focus, start, toEditor = true) }
                } else {
                    setUp(handle, tabName, focus, start, toEditor = false)
                }
            } catch (e: Throwable) {
                // whatever stopped the setup, nothing may wait for this tab forever (a Close waits for its place)
                val failure = IllegalStateException("the terminal tab of thread $threadId was not set up", e)
                handle.turn.completeExceptionally(failure)
                handle.tab.completeExceptionally(failure)
                handle.placed.completeExceptionally(failure)
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Skein could not set up the terminal tab of thread $threadId", e)
            }
        }
        return handle
    }

    /**
     * Sets up [handle]'s tab on the EDT - creates it in the Terminal tool window, follows it, and with [toEditor] moves it
     * into the editor, where the caller holds [moves]. Completes the handle's turn first, its tab once it exists and its
     * place once it has one. Whatever it throws, the caller's catch turns into a failed setup.
     */
    private suspend fun setUp(handle: Handle, tabName: String, focus: Boolean, start: Boolean, toEditor: Boolean) {
        handle.turn.complete(Unit)
        // a tab created without focus still becomes the Terminal tool window's selected one: kept to give it back
        val selectedBefore = if (toEditor || focus) null else terminalWindowSelection()
        // the selection a new tab makes in the Terminal tool window is Skein's own - a tool window pane Skein listens to
        // already would take it for the user opening the thread, and a restored one would start at once
        own.begin()
        val tab = try {
            TerminalToolWindowTabsManager.getInstance(project).createTabBuilder()
                .workingDirectory(handle.folder)
                .tabName(tabName)
                .requestFocus(false)
                .deferSessionStartUntilUiShown(!start)
                .closeOnProcessTermination(false)
                .restoreOnProjectReopen(false)
                .createTab()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not open a terminal tab in ${handle.folder}", e)
            handle.tab.completeExceptionally(e)
            handle.placed.completeExceptionally(e)
            closed(handle) // a thread without a tab must not linger in the list or come back on every restart
            return
        } finally {
            own.end()
        }
        handle.content = tab.content
        watchDisposal(handle, tab.content)
        try {
            handle.session = tab.view.sessionState
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // without it the close check lets the tab close at once and a close does not wait, as before
            LOG.warn("Skein cannot follow the terminal session of thread ${handle.id}", e)
        }
        handle.tab.complete(tab)
        try {
            handle.tabTitle = tab.view.title
            applyTitle(handle) // a rename that came before the tab
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not name the tab of thread ${handle.id}", e)
        }
        watchFocus(handle.id, tab)
        watchKeys(handle, tab)
        if (!toEditor) {
            stayInTerminalWindow(handle, tab.content, focus, selectedBefore)
            return
        }
        val file = try {
            moveToEditor(handle, tab, focus)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // the action may have taken the tab out of the Terminal tool window before it failed, so where the tab
            // is now decides, and an editor file that shows it is marked on the way
            val after = TabPlaces.afterFailedMove(whereQuietly(handle))
            LOG.warn("Skein could not move the terminal tab of thread ${handle.id} into the editor; ${after.words}", e)
            if (after == TabPlaces.AfterFailedMove.STAYS) stayInTerminalWindow(handle, tab.content, focus, null) else handle.placed.complete(Unit)
            return
        }
        adoptFile(handle, file)
        handle.placed.complete(Unit)
    }

    /**
     * Runs the bundled Terminal.MoveToEditor action on [handle]'s [tab] and returns the editor file it opened, the open
     * file whose editor shows this very tab ([shows]), so a file the IDE or another plugin opened meanwhile is never
     * taken for it. Without [focus] the editor selected before comes back in front, found file or not - with the moves
     * one at a time that keeps a restore's first tab in front, or the editor the user had there. A move that throws or
     * shows the tab in no editor leaves the caller to look where the tab is.
     */
    private suspend fun moveToEditor(handle: Handle, tab: TerminalToolWindowTab, focus: Boolean): VirtualFile {
        val content = tab.content
        val action = ActionManager.getInstance().getAction(MOVE_TO_EDITOR_ACTION_ID)
            ?: throw IllegalStateException("action $MOVE_TO_EDITOR_ACTION_ID is not registered")
        val toolWindow = ToolWindowManager.getInstance(project).getToolWindow(TERMINAL_TOOL_WINDOW_ID)
            ?: throw IllegalStateException("no $TERMINAL_TOOL_WINDOW_ID tool window")
        val editors = FileEditorManager.getInstance(project)
        val previouslySelected = editors.selectedEditor?.file
        own.begin()
        try {
            toolWindow.contentManager.setSelectedContent(content)
            val context = SimpleDataContext.builder()
                .add(CommonDataKeys.PROJECT, project)
                .add(PlatformDataKeys.TOOL_WINDOW, toolWindow)
                .add(PlatformDataKeys.TOOL_WINDOW_CONTENT_MANAGER, toolWindow.contentManager)
                .build()
            ActionUtil.performAction(action, AnActionEvent.createEvent(action, context, null, ActionPlaces.UNKNOWN, ActionUiKind.NONE, null))
            val file = editors.openFiles.firstOrNull { shows(editors, it, handle) }
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
            return file ?: throw IllegalStateException("$MOVE_TO_EDITOR_ACTION_ID opened no editor that shows the terminal tab")
        } finally {
            own.end()
        }
    }

    /** Where [handle]'s tab is now ([locate]), null when that cannot be told. EDT only. */
    private fun whereQuietly(handle: Handle): TabPlaces.Where? =
        try {
            locate(handle).where
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            null
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
     * Follows the keys typed into the tab for the draft guard ([DraftGuard]), for as long as the tab's content lives,
     * wherever it goes (an input method's text comes through [composedText]). The tab starts dirty until the first turn the person starts
     * ([DraftGuard.LAUNCHED]). A tab whose watch cannot be added stays unwatched, never counts as clean and counts as
     * typed into ([typedInto]). EDT only.
     */
    private fun watchKeys(handle: Handle, tab: TerminalToolWindowTab) {
        try {
            val view = tab.view
            handle.view = view
            view.addKeyEventsListener(
                tab.content,
                object : TerminalKeyEventsListener {
                    override fun beforeKeyEvent(event: TerminalKeyEvent): Boolean {
                        try {
                            val key = event.awtEvent
                            onKey(handle, DraftGuard.classify(key.id, key.keyCode, key.keyChar, key.modifiersEx))
                        } catch (e: Exception) {
                            // a key the guard could not read may have left a draft, and may have typed into the shell
                            handle.keysSeen = true
                            handle.draft = handle.draft?.let { DraftGuard.next(it, DraftGuard.Key.OTHER, enterMaySend = false, at = monotonicMillis(), wallAt = System.currentTimeMillis()) }
                            if (e is CancellationException) throw e
                            LOG.warn("Skein could not read a key typed into thread ${handle.id}", e)
                        }
                        // never takes the key: the terminal sends it on as always
                        return false
                    }
                },
            )
            handle.draft = DraftGuard.LAUNCHED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein cannot follow the keys typed into thread ${handle.id}, so it types no rename there and starts it only when opened", e)
        }
    }

    /**
     * One key or input-changing action in [handle]'s tab, for the draft guard, after what claude did meanwhile, and for the
     * start in view ([typedInto]) first, which a paste into a tab whose key watch failed counts for too. EDT only.
     */
    private fun onKey(handle: Handle, key: DraftGuard.Key) {
        if (DraftGuard.reachesInput(key)) handle.keysSeen = true
        val draft = DraftGuard.settled(handle.draft ?: return, handle.provenSentAt)
        handle.draft = DraftGuard.next(draft, key, enterMaySend = handle.id in enterMaySend, at = monotonicMillis(), wallAt = System.currentTimeMillis())
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
     * terminal tab for good. Not while the project closes, as before. A tab a clear took past Skein's close check
     * ([Handle.pastCheck]) is no closed thread - it is reported lost, and the thread comes back in a new tab, while one
     * the user closed on its own past the check is a closed thread like any other ([TabRestore.gone]). EDT (the callback
     * runs where the content is disposed).
     */
    private fun watchDisposal(handle: Handle, content: Content) {
        val gone = {
            handle.disposed = true
            handle.markedFile = null
            when (TabRestore.gone(projectClosing, handle.pastCheck)) {
                TabRestore.Gone.NOTHING -> Unit
                TabRestore.Gone.CLOSED -> closed(handle)
                TabRestore.Gone.BACK_AS_RESUME -> lost(handle)
                TabRestore.Gone.TERMINAL_DIED -> {
                    LOG.warn(
                        "The IDE ended the terminal of thread ${handle.id} although Skein had marked its tab to reopen, so claude " +
                            "there could not be ended first; Skein opens the thread again as a Resume",
                    )
                    lost(handle)
                }
            }
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

    /** Only [handle] itself, since a thread that came back after a lost tab has a new handle under the same id. */
    private fun closed(handle: Handle) {
        if (handles.remove(handle.id, handle)) listener?.tabClosed(handle.id)
    }

    /** [handle]'s tab is gone past Skein's close check, and the thread gets a new one ([ThreadTerminalListener.tabLost]). */
    private fun lost(handle: Handle) {
        if (handles.remove(handle.id, handle)) listener?.tabLost(handle.id, select = handle.wasInFront)
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
            // the claude this starts may ask something first while its status already says idle, so the tab is dirty until
            // the first turn the person starts (back on the EDT, where the keys arrive too)
            handle.draft?.let { handle.draft = DraftGuard.LAUNCHED }
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
            // read when the closing starts - the tab in front goes last, so the neighbour the IDE then shows is not the
            // next one to close (its shell would start, and a restored one left in front a moment starts claude)
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
     * Closes one tab where it is now. A tab still being set up is closed once it has its place - one queued behind a
     * restore's moves is waited for until its setup has its turn, for at most [MOVE_TURN_WAIT_MILLIS], and from then a
     * tab that has no place after [TAB_READY_WAIT_MILLIS] is skipped with a warning, so it cannot hold up the rest of a
     * batch. A tab on its way between the editor and the Terminal tool window is looked for once more a moment later, and
     * one still in neither place then is closed through the terminal plugin, which releases its content. EDT only.
     */
    private suspend fun closeTab(handle: Handle) {
        try {
            val placed = withTimeoutOrNull(MOVE_TURN_WAIT_MILLIS) { handle.turn.await() } != null &&
                withTimeoutOrNull(TAB_READY_WAIT_MILLIS) { handle.placed.await() } != null
            if (!placed) {
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
        // Skein's own close, after the thread was ended (or the wait timed out) - its close checks let this one through,
        // and a reopen mark from a branch switch comes off, or the editor would keep the terminal running unseen
        handle.released = true
        liftMark(handle)
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

    // a tab whose key watch is not there (not set up yet, or it could not be added) may have been typed into unheard
    override fun typedInto(tab: ThreadTab): Boolean = (tab as Handle).let { it.keysSeen || it.draft == null }

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
        if (allowed) closingOnTheUsersWord(files) else replayOthersQuietly(files)
        return allowed
    }

    /**
     * The close check let [files] close. The IDE closes them right after, in the same turn of the EDT, and that close is
     * the user's own or Skein's, never one to bring back ([TabRestore.beforeClose]). A reopen mark from a branch switch
     * comes off them, so their terminal ends as a close's does. The flag goes with the next turn of the EDT at this
     * modality, which a dialog another listener of that close may show does not bring forward. Never throws but for
     * cancellation.
     */
    private fun closingOnTheUsersWord(files: Collection<VirtualFile>) {
        try {
            val asked = files.mapNotNull { file -> threadIdOf(file)?.let { handles[it] }?.takeIf { it.file === file } }
            if (asked.isEmpty()) return
            for (handle in asked) {
                handle.askedToClose = true
                liftMark(handle)
            }
            ApplicationManager.getApplication().invokeLater({ for (handle in asked) handle.askedToClose = false }, ModalityState.current())
        } catch (e: Throwable) {
            if (e is CancellationException || e is ControlFlowException) throw e
            LOG.warn("Skein could not note a close of its thread tabs on the user's word", e)
        }
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

    /** The thread whose tab was the selected editor last, as the IDE said ([selectionChanged]) and each tick saw it. EDT only. */
    private var lastFront: String? = null

    /**
     * The closes of editor files in one turn of the EDT - `closeAllFiles` closes them one by one - as its first
     * `beforeFileClosed` found things. [front] is the thread whose tab was in front then, since by the later files the IDE
     * may have moved the selection. [wholeArea] turns true once a file of it closed with its window taken down, a clear of
     * the editor area ([TabRestore.wholeArea]), and stays so. [ended] are the threads Skein ended for it, all in one go.
     */
    private class Burst(val front: String?) {
        var wholeArea = false
        val ended = HashSet<String>()
    }

    /** The close under way, or null. EDT only. */
    private var burst: Burst? = null

    private fun burstNow(): Burst = burst ?: Burst(lastFront).also { started ->
        burst = started
        // the next turn of the EDT at this modality, so not one inside the modal progress an ending runs during the close
        ApplicationManager.getApplication().invokeLater({ burstOver(started) }, ModalityState.current())
    }

    /**
     * The turn of [over] is done. A tab Skein ended with the clear but that the clear did not take after all (another
     * plugin closing only the active editor window), or a single close that did not happen, stays where it is and keeps
     * no past-check close, so a later close of it is an ordinary one. EDT.
     */
    private fun burstOver(over: Burst) {
        if (burst === over) burst = null
        for (id in over.ended) {
            val handle = handles[id] ?: continue
            val file = handle.file
            if (!handle.disposed && TabRestore.forgottenIfStillOpen(handle.pastCheck) && file != null && FileEditorManager.getInstance(project).isFileOpen(file)) {
                handle.pastCheck = null
                handle.wasInFront = false
            }
        }
    }

    /**
     * Right before the IDE closes [file] and disposes its editor, which ends a thread's terminal ([TabRestore.beforeClose]).
     * A clear of the editor area with Skein's reopen mark is let through, and its tab opens again after ([afterClose]). A
     * marked tab closed on its own keeps its terminal until a few seconds tell a drag from a real close ([afterClose]). A
     * clear without the mark gets the claude of every thread tab it takes ended now, together and while the tabs are open
     * and read, and each thread back as a Resume once its tab is gone ([endWithTheClear], [watchDisposal]). A running tab
     * closed on its own without the mark - a middle click or a Shift+click on it - gets its claude ended now the same way,
     * and the thread goes as after Close ([endBeforeASingleClose]). What is ended now is [TabRestore.endNow]'s to say.
     * Anything else goes as it always did. Any answer that does not keep the mark takes it off first, so the terminal goes
     * the way the answer says. EDT, inside the IDE's close, a write-intent read action. Whether the editor area is being
     * cleared is told by the editor windows, which `closeAllFiles` takes down before it closes their files, while a single
     * close leaves the file in its window until after this.
     */
    private fun beforeClose(file: VirtualFile) {
        if (handles.isEmpty()) return
        val burst = burstNow()
        burst.wholeArea = TabRestore.wholeArea(burst.wholeArea, windowsGone = !inAnEditorWindow(file))
        val handle = threadIdOf(file)?.let { handles[it] }?.takeIf { it.file === file && !it.disposed } ?: return
        val close = TabRestore.beforeClose(
            reopenMark = ReopenMark.isSet(file),
            markedBySkein = handle.markedFile === file,
            released = handle.released,
            askedByUser = handle.askedToClose,
            ending = handle.endingSince != null,
            closing = projectClosing || appClosing || project.isDisposed,
            wholeArea = burst.wholeArea,
            running = handle.session?.value == TerminalViewSessionState.Running,
            closeCanHang = CloseGate.closeCanHang,
        )
        if (!TabRestore.keepsMark(close)) liftMark(handle)
        // a past-check close of an earlier turn that never came is not this one's
        handle.pastCheck = close.takeIf { it != TabRestore.Close.LET_GO }
        if (close == TabRestore.Close.LET_GO) return
        handle.wasInFront = burst.front == handle.id
        when (TabRestore.endNow(close, endedThisTurn = handle.id in burst.ended)) {
            TabRestore.EndNow.WITH_THE_CLEAR -> endWithTheClear(burst, handle)
            TabRestore.EndNow.THIS_TAB -> endBeforeASingleClose(burst, handle)
            TabRestore.EndNow.NOTHING -> Unit
        }
    }

    /** Whether an editor window lists [file] now - `closeAllFiles` has taken the windows down before its files close. EDT. */
    private fun inAnEditorWindow(file: VirtualFile): Boolean = FileEditorManagerEx.getInstanceEx(project).windows.any { it.isFileOpen(file) }

    /**
     * The first past-check close of a clear ([TabRestore.Close.END_THEN_RESUME]). Every thread tab the clear takes
     * ([TabRestore.endTogether] - an editor tab no window lists any more, not in the Terminal tool window, not kept by
     * Skein's mark, not one Skein or the user closes) is ended in one call, each on its own and all under one deadline, as
     * a project's close ends its threads, so a slow claude early in the clear costs the later ones nothing. [closing],
     * the tab closing now, goes whatever the look finds. A tab of the clear that turns up later in the same turn (a
     * floating editor window's) gets its own call. EDT, inside the close.
     */
    private fun endWithTheClear(burst: Burst, closing: Handle) {
        val inTerminalWindow = try {
            TerminalToolWindowTabsManager.getInstance(project).tabs.mapNotNull { it.content }.toSet()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            emptySet()
        }
        val candidates = handles.values.filter { it.id !in burst.ended && !it.disposed && it.placed.isCompleted }
        val ids = TabRestore.endTogether(
            candidates.map { handle ->
                val file = handle.file
                TabRestore.ClearTab(
                    id = handle.id,
                    inThisClear = file != null && handle.content !in inTerminalWindow && threadIdOf(file) == handle.id && !inAnEditorWindow(file),
                    released = handle.released,
                    ending = handle.endingSince != null,
                    askedByUser = handle.askedToClose,
                    keptByMark = file != null && handle.markedFile === file && ReopenMark.isSet(file),
                )
            },
        ).toMutableList()
        if (closing.id !in ids) ids += closing.id
        val ending = ids.mapNotNull { handles[it] }
        for (handle in ending) {
            handle.pastCheck = TabRestore.Close.END_THEN_RESUME
            handle.wasInFront = burst.front == handle.id
        }
        burst.ended += ids
        // a clear of restored threads nobody opened has nothing to end, so nothing to wait for either
        val anyRunning = ending.any { it.session?.value == TerminalViewSessionState.Running }
        LOG.info("The IDE clears the editor area past Skein's close check; Skein ends the claude of thread(s) ${ids.joinToString(", ")} first and opens them again as a Resume")
        tellPastCheck(TabRestore.PastCheckEnding.ofTheClear(ids, anyRunning))
    }

    /**
     * One running thread tab the IDE closes on its own past Skein's close check, unmarked ([TabRestore.Close.END_THEN_CLOSE])
     * - a middle click or a Shift+click on it, a Shift+Alt+click on another tab of its window, the Switcher, a floating
     * editor window's close, a plugin. Its claude is ended now, then its shell, under the same short deadline a clear has,
     * while the tab is still open and read. The close then goes on, and the disposed tab reports the thread closed
     * ([watchDisposal]), worktree question and all. Each such close has a deadline of its own, so several closed in one go
     * (a Shift+Alt+click, a floating editor window's close, a plugin's loop) wait for them one after another. EDT, inside
     * the close.
     */
    private fun endBeforeASingleClose(burst: Burst, closing: Handle) {
        burst.ended += closing.id
        LOG.info("The IDE closes the tab of thread ${closing.id} on its own past Skein's close check; Skein ends its claude first, as Close does")
        tellPastCheck(TabRestore.PastCheckEnding.ofASingleClose(closing.id))
    }

    /** Hands [ending] to the listener, which ends what runs in those tabs while the IDE's close waits. EDT, inside the close. */
    private fun tellPastCheck(ending: TabRestore.PastCheckEnding) {
        listener?.tabsClosingPastCheck(ending.ids, ending.withinMillis, ending.comesBack)
    }

    /** The tabs the IDE closed with Skein's reopen mark, their terminals alive, waiting to open again ([reopenAll]). EDT only. */
    private val reopening = LinkedHashSet<Handle>()

    /**
     * The IDE closed [file], and a thread tab it closed with Skein's reopen mark kept its terminal - one whose terminal went
     * anyway was reported already. In a clear ([TabRestore.Close.REOPEN_SAME]) the tab opens again once the restore has
     * opened the branch's own editors, in the next non-modal turn of the EDT, never inside a dialog or a progress. Closed
     * on its own ([TabRestore.Close.END_IF_CLOSED]), it is looked at [TabRestore.SINGLE_CLOSE_WAIT_MILLIS] later, at any
     * modality, unless it turns up again first ([seenAgain], [lookAfterSingleClose]). EDT.
     */
    private fun afterClose(file: VirtualFile) {
        val handle = threadIdOf(file)?.let { handles[it] }?.takeIf { it.file === file } ?: return
        if (handle.disposed) return
        when (handle.pastCheck) {
            TabRestore.Close.REOPEN_SAME -> {
                val first = reopening.isEmpty()
                reopening += handle
                if (first) scope.launch(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) { reopenAll() }
            }
            TabRestore.Close.END_IF_CLOSED -> {
                // settled by the close itself - a change that comes during the wait is no reason to take the close back
                val turn = EventQueue.getCurrentEvent()
                singleCloses.closed(
                    handle.id,
                    changedBefore = TabRestore.changedBefore(handle.markedAt, lastBranchChangeAt),
                    turn = turn,
                    // a middle click, a Shift+click or a Shift+Alt+click on the tabs, or a shortcut - the user's own close,
                    // whatever the branch change says
                    byUserInput = TabRestore.closedByUsersInput(mouse = turn is MouseEvent, key = turn is KeyEvent, id = turn?.id ?: 0),
                )
                scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) {
                    delay(TabRestore.SINGLE_CLOSE_WAIT_MILLIS)
                    lookAfterSingleClose(handle)
                }
            }
            else -> Unit
        }
    }

    /** The marked tabs closed on their own that Skein waits for, cancelled when they turn up again ([seenAgain]). EDT only. */
    private val singleCloses = TabRestore.SingleCloses()

    /** When the last branch switch through the IDE was over (`branchHasChanged`), in monotonic milliseconds, or null. */
    @Volatile
    private var lastBranchChangeAt: Long? = null

    /**
     * The event the EDT dispatched when that change came, held weakly, and null off the EDT. A restore runs inside that very
     * dispatch, so a tab closed in it is the restore's ([TabRestore.SingleCloses]).
     */
    @Volatile
    private var lastBranchChangeTurn: WeakReference<AWTEvent>? = null

    /**
     * [handle]'s tab shows again, in an editor window or the Terminal tool window. If Skein waited for it after a close past
     * the check, that close was a move - a drag's drop opens the file again in a coroutine of its own, after the turn of
     * the close - so the wait is cancelled, nothing is ended, and Skein's mark comes off (the drag took the IDE's off
     * already). EDT.
     */
    private fun seenAgain(handle: Handle) {
        if (!singleCloses.seenAgain(handle.id)) return
        if (handle.pastCheck == TabRestore.Close.END_IF_CLOSED) handle.pastCheck = null
        liftMark(handle)
        LOG.info("The tab of thread ${handle.id} turned up again after a close past Skein's close check, so it was moved and nothing is ended")
    }

    /**
     * A marked tab closed on its own, [TabRestore.SINGLE_CLOSE_WAIT_MILLIS] later and not seen again meanwhile
     * ([TabRestore.afterSingleClose]). A drag's drop would have shown it again by now, cancelling this look. One still in
     * no place was closed for good, and its terminal lives only thanks to the mark. Closed by the user's own mouse or keys,
     * or before the branch change it was marked for, it was the user's middle click, Shift+click or Shift+Alt+click, or a
     * plugin's close, so its claude is ended the way Close ends it, then the shell, and the thread goes. Closed with that
     * change already there or in its very turn of the EDT, the restore closed it (a floating editor window's tab), so it is ended cleanly and the thread comes
     * back as a Resume - settled at the close, never by a change during the wait ([TabRestore.SingleCloses]). Skein never
     * opens it again itself. EDT.
     */
    private suspend fun lookAfterSingleClose(handle: Handle) {
        val lost = singleCloses.take(handle.id, lastChangeTurn = lastBranchChangeTurn?.get()) ?: return
        try {
            val where = if (handle.disposed) TabPlaces.Where.GONE else locate(handle).where
            when (TabRestore.afterSingleClose(where, handle.released, lost)) {
                TabRestore.SingleClose.NOTHING -> Unit
                TabRestore.SingleClose.CLOSE -> {
                    LOG.info("The tab of thread ${handle.id} was closed past Skein's close check while it carried a reopen mark; Skein ends its claude as Close does")
                    endStrandedQuietly(handle)
                }
                TabRestore.SingleClose.LOSE -> {
                    LOG.info("The IDE's restore closed the tab of thread ${handle.id} on its own; Skein ends its claude and opens it again as a Resume")
                    handle.pastCheck = TabRestore.Close.END_THEN_RESUME
                    loseStrandedQuietly(handle)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not look at the tab of thread ${handle.id} after it closed", e)
        } finally {
            if (handle.pastCheck == TabRestore.Close.END_IF_CLOSED) handle.pastCheck = null
            liftMark(handle)
        }
    }

    /**
     * Opens the tabs of [reopening] again, as Skein's own selections ([own]), so nothing in them counts as the user opening
     * a thread, which would start a restored one nobody had opened. The one that was in front comes last and selected
     * ([TabRestore.selects]), and the others open beside the editor the restore selected. EDT.
     */
    private suspend fun reopenAll() {
        val batch = reopening.toList()
        reopening.clear()
        own.begin()
        try {
            for (handle in batch.sortedBy { it.wasInFront }) reopenSame(handle)
        } finally {
            own.end()
        }
    }

    /**
     * Opens [handle]'s own editor file again, the same terminal in it, while the tab is in neither place
     * ([TabRestore.reopensSame]), and takes Skein's mark off. A tab that cannot open again keeps a terminal nobody sees,
     * so it is ended the way a Close ends it. EDT.
     */
    private suspend fun reopenSame(handle: Handle) {
        val file = handle.file
        try {
            val where = if (file == null || handle.disposed) TabPlaces.Where.GONE else locate(handle).where
            if (file != null && TabRestore.reopensSame(where, handle.released)) {
                val started = handle.session?.value.let { it != null && it != TerminalViewSessionState.NotStarted }
                val select = TabRestore.selects(handle.wasInFront, started)
                val editors = FileEditorManager.getInstance(project)
                editors.openFile(file, FileEditorOpenRequest.withFocus(false).withSelectAsCurrent(select).withReuseOpen(true))
                if (editors.isFileOpen(file)) {
                    LOG.info("Skein opened the tab of thread ${handle.id} again after the IDE restored a workspace")
                } else {
                    // a live terminal with no tab must not be left behind
                    LOG.warn("The tab of thread ${handle.id} did not open again after the IDE restored a workspace, so Skein ends that thread")
                    endStrandedQuietly(handle)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not open the tab of thread ${handle.id} again after the IDE restored a workspace, so it ends that thread", e)
            endStrandedQuietly(handle)
        } finally {
            handle.pastCheck = null
            handle.wasInFront = false
            liftMark(handle)
        }
    }

    /**
     * Closes the tabs of the threads [threadIds] that the restore left in no tab, without ending anything first - the
     * last resort when their ending could not run ([io.github.v0rest.skein.threads.LostTabs]). Each tab is released as
     * [close] releases a tab in no place, and the past-check close it carries brings its thread back as a Resume. Returns
     * at once, never throws.
     */
    fun releaseLost(threadIds: Collection<String>) {
        try {
            close(threadIds.mapNotNull { handles[it] }.filter { !it.disposed })
        } catch (e: Throwable) {
            if (e is CancellationException || e is ControlFlowException) throw e
            LOG.warn("Skein could not release the tabs the restore left in no tab", e)
        }
    }

    /**
     * A live terminal no tab shows that the restore took ([TabRestore.SingleClose.LOSE]) - ended the way the clear's tabs are
     * ended, and once its tab is released the thread comes back as a Resume ([watchDisposal] reads [Handle.pastCheck]),
     * else released at once when that cannot run. EDT.
     */
    private suspend fun loseStrandedQuietly(handle: Handle) {
        try {
            if (listener?.tabsLostPastCheck(listOf(handle.id)) == true) return
            releaseStrandedTab(handle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not end thread ${handle.id}, whose terminal the restore left in no tab", e)
        }
    }

    /**
     * A live terminal no tab shows, ended and closed the way a Close does - claude, then the shell, and the thread goes -
     * else released ([releaseStrandedTab]) when that cannot run. EDT.
     */
    private suspend fun endStrandedQuietly(handle: Handle) {
        try {
            if (listener?.closeRequested(listOf(handle.id)) == true) return
            releaseStrandedTab(handle)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not end thread ${handle.id}, whose terminal no tab shows", e)
        }
    }

    /**
     * A branch switch made through the IDE is about to start (`BranchChangeListener.branchWillChange`). Every thread tab
     * open in the editor, its terminal started and not on its way out, gets Skein's reopen mark ([TabRestore.toMark],
     * [ReopenMark]), so the IDE's restore of the branch's workspace closes it only to open it again. A restored thread
     * nobody opened gets none, since opening its tab again would start it - the restore closes it, and it comes back
     * not started unless its new tab ends up in front ([lost]). A file the IDE itself marked for a move under way is left
     * alone. On the EDT, where the IDE's Git tells it, and from another thread it goes there first.
     */
    fun branchWillChange() = onEdt("mark its thread tabs before a branch switch") {
        val editors = FileEditorManager.getInstance(project)
        val now = monotonicMillis()
        val tabs = handles.values.map { handle ->
            val file = handle.file
            TabRestore.Tab(
                id = handle.id,
                inEditor = file != null && editors.isFileOpen(file) && shows(editors, file, handle),
                markedByTheIde = file != null && handle.markedFile !== file && ReopenMark.isSet(file),
                disposed = handle.disposed,
                released = handle.released,
                ending = handle.endingSince != null,
                started = handle.session?.value.let { it != null && it != TerminalViewSessionState.NotStarted },
                closingPastCheck = handle.pastCheck != null,
            )
        }
        val ids = TabRestore.toMark(tabs).toSet()
        for (handle in handles.values) {
            val file = handle.file ?: continue
            if (handle.id !in ids) continue
            if (handle.markedFile !== file) liftMark(handle)
            ReopenMark.set(file)
            handle.markedFile = file
            handle.markedAt = now
        }
        if (ids.isNotEmpty()) LOG.info("Skein marked ${ids.size} thread tab(s) to open again after a branch switch")
    }

    /**
     * The branch switch is over (`BranchChangeListener.branchHasChanged`). The IDE restores the branch's workspace in its
     * own listener of that event, or right after it, queued without modality (`TransactionGuard.submitTransaction`), when
     * a dialog stands in its way. A second later, queued the same way and so behind it, Skein lifts the marks the restore
     * did not use ([TabRestore.toLift]), so they never meet the user's next close. A restore that came later still finds
     * no mark and takes the fallback ([TabRestore.Close.END_THEN_RESUME]). Returns at once, on any thread.
     */
    fun branchHasChanged() {
        val changedAt = monotonicMillis()
        lastBranchChangeTurn = EventQueue.getCurrentEvent()?.let(::WeakReference)
        lastBranchChangeAt = changedAt
        scope.launch {
            delay(SETTLE_MILLIS)
            withContext(Dispatchers.EDT + ModalityState.nonModal().asContextElement()) {
                try {
                    liftMarks(changedAt)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LOG.warn("Skein could not take its reopen marks off after a branch switch", e)
                }
            }
        }
    }

    /**
     * Lifts the marks [TabRestore.toLift] names - those from before the branch change that was over at [changedAt] and
     * that no restore used, or with null only old ones. EDT.
     */
    private fun liftMarks(changedAt: Long?) {
        val marked = handles.values.filter { it.markedFile != null }
        if (marked.isEmpty()) return
        val awaiting = marked.filter { it.pastCheck == TabRestore.Close.REOPEN_SAME }.mapTo(HashSet()) { it.id }
        val lift = TabRestore.toLift(marked.associate { it.id to it.markedAt }, awaiting, changedAt, monotonicMillis()).toSet()
        for (handle in marked) if (handle.id in lift) liftMark(handle)
    }

    /** Takes Skein's reopen mark off [handle]'s file, if Skein put one there. EDT. */
    private fun liftMark(handle: Handle) {
        val file = handle.markedFile ?: return
        handle.markedFile = null
        ReopenMark.clear(file)
    }

    /** Every mark of Skein's off, for a project that closes. Never throws but for cancellation. */
    private fun liftAllMarksQuietly() {
        try {
            for (handle in handles.values) liftMark(handle)
        } catch (e: Throwable) {
            if (e is CancellationException || e is ControlFlowException) throw e
            LOG.warn("Skein could not take its reopen marks off", e)
        }
    }

    /** Runs [block] on the EDT now, or there as soon as possible from another thread, and logs a failure as Skein could not [what]. */
    private fun onEdt(what: String, block: () -> Unit) {
        val run = {
            try {
                block()
            } catch (e: Throwable) {
                if (e is CancellationException || e is ControlFlowException) throw e
                LOG.warn("Skein could not $what", e)
            }
        }
        val application = ApplicationManager.getApplication()
        if (application.isDispatchThread) run() else application.invokeLater(run, ModalityState.any())
    }

    override fun inputClean(tab: ThreadTab): Boolean {
        val handle = tab as Handle
        return DraftGuard.inputClean(handle.draft, handle.provenSentAt, monotonicMillis())
    }

    override fun claudeSeen(enterMaySend: Set<String>, atWorkSince: Map<String, Long>) {
        this.enterMaySend = enterMaySend
        for ((id, since) in atWorkSince) {
            val handle = handles[id] ?: continue
            handle.provenSentAt = DraftGuard.proven(handle.draft, since, handle.provenSentAt)
        }
    }

    /**
     * Checks on the EDT, asks [stillIdle] off it, then checks again, asks [beforeSend] whether the rename still waits and
     * sends in one turn of the EDT, where the keys typed into the tab arrive too and the Rename dialog applies its answer,
     * so neither a key nor a newer answer can slip in between the last look and the line. Any modality, like the other
     * look-ups here: a dialog open elsewhere in the IDE takes no keys from the tab.
     */
    override suspend fun sendRename(tab: ThreadTab, name: String, stillIdle: () -> Boolean, beforeSend: () -> Boolean): Boolean {
        val handle = tab as Handle
        if (!ClaudeRename.isAllowed(name)) return false
        val view = handle.view ?: return false
        val edt = Dispatchers.EDT + ModalityState.any().asContextElement()
        if (!withContext(edt) { readyForRename(handle) }) return false
        if (!withContext(Dispatchers.IO) { stillIdle() }) return false
        return withContext(edt) {
            if (!readyForRename(handle)) return@withContext false
            if (!beforeSend()) return@withContext false
            ClaudeRename.send(view, name)
            true
        }
    }

    /** [ClaudeRename.tabReady] for [handle] as it is now. EDT only. */
    private fun readyForRename(handle: Handle): Boolean =
        ClaudeRename.tabReady(
            disposed = handle.disposed,
            ending = handle.endingSince != null,
            released = handle.released,
            running = handle.session?.value == TerminalViewSessionState.Running,
            inputClean = inputClean(handle),
        )

    override fun refreshPresentation(threadId: String) {
        val handle = handles[threadId] ?: return
        scope.launch(Dispatchers.EDT + ModalityState.any().asContextElement()) {
            try {
                // a tab still being set up is waited for - the rows its colour reads may have come after it was drawn, as
                // a restore's do - and only an editor tab has a colour to redraw
                try {
                    handle.placed.await()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (handle.content == null) return@launch
                }
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
        try {
            // a mark whose branch switch never ended (a checkout that failed) does not wait for ever
            liftMarks(changedAt = null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LOG.warn("Skein could not take its old reopen marks off", e)
        }
        val active = KeyboardFocusManager.getCurrentKeyboardFocusManager().activeWindow
        val focusInThisProject = active != null && DataManager.getInstance().getDataContext(active).getData(CommonDataKeys.PROJECT) == project
        val selected = FileEditorManager.getInstance(project).selectedEditor?.file?.getUserData(THREAD_ID)
        lastFront = selected
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
                // a tab in the middle of Skein's own move is selected for a moment on its way, and that is no look at it
                settled = handles.values.filter { it.placed.isCompleted && !it.disposed }.mapTo(HashSet()) { it.id },
            ),
            // no focus check: the list marks the tab in front also while the IDE is in the background
            inFront = ViewRule.inFront(selected, inTerminalWindow),
            // the modal stack's top, whatever modality this trip runs with
            modal = ModalityState.current() != ModalityState.nonModal(),
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

        /**
         * How long a Close waits for a tab that is still being set up, from when its setup has its turn ([Handle.turn]) -
         * the setup and the move normally take well under a second.
         */
        private const val TAB_READY_WAIT_MILLIS = 5_000L

        /**
         * How long a Close waits for a tab queued behind the moves of a restore to get its turn ([moves]) - one move after
         * another, each normally well under a second. Skein's own close counts every selection as its own meanwhile.
         */
        private const val MOVE_TURN_WAIT_MILLIS = 30_000L

        /** How long after a branch switch Skein lifts the reopen marks the restore did not use ([branchHasChanged]). */
        private const val SETTLE_MILLIS = 1_000L
    }
}
