package io.github.v0rest.skein.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.ui.AnimatedIcon
import com.intellij.ui.CollectionListModel
import com.intellij.ui.SimpleTextAttributes
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import io.github.v0rest.skein.git.WorktreeCounts
import io.github.v0rest.skein.settings.SkeinSettings
import io.github.v0rest.skein.threads.ContextUse
import io.github.v0rest.skein.threads.KeptWorktrees
import io.github.v0rest.skein.threads.SessionMarksService
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadService
import io.github.v0rest.skein.threads.WorktreeHygiene
import io.github.v0rest.skein.ui.actions.NewThreadAction
import io.github.v0rest.skein.ui.actions.ThreadMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.awt.event.HierarchyEvent
import javax.swing.JComponent

/**
 * The Skein tool window content: the live threads on top, the Worktrees section under them while it lists a folder
 * ([WorktreesSection], under its own divider while open), and the history below, laid out by [HistorySplit]. While it
 * shows, it lets [WorktreeHygiene] look at the worktrees, and passes the counts on to the rows.
 */
class ThreadListPanel(private val project: Project, parent: Disposable) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s -> Disposer.register(parent) { s.cancel() } }
    private val service = ThreadService.getInstance(project)
    private val marks = SessionMarksService.getInstance(project)
    private val hygiene = WorktreeHygiene.getInstance(project)

    /** The worktree counts of the threads, by thread id, as [WorktreeHygiene.state] last said; line 1 of the rows. EDT only. */
    private var worktreeCounts: Map<String, WorktreeCounts> = emptyMap()
    private val model = CollectionListModel<SkeinThread>()
    private var lastThreads: List<SkeinThread> = emptyList()
    private var headers: Map<String, String> = emptyMap()

    /** The thread whose tab is in front, as [ThreadService.inFront] last said; the rows mark it. EDT only. */
    private var front: String? = null

    /** What each thread did last, as [ThreadService.previews] last said; line 2 of the rows. EDT only. */
    private var previews: Map<String, String> = emptyMap()

    /**
     * The model and context in use of each thread, as [ThreadService.contexts] last said; the right of line 2. EDT
     * only.
     */
    private var contexts: Map<String, ContextUse> = emptyMap()

    /** Puts [front] back to what [ThreadService.inFront] says a little after a click moved it ahead of the tick. EDT only. */
    private var frontCheck: Job? = null
    private val list = JBList(model).apply {
        cellRenderer = SectionHeaderRenderer(
            ThreadRowRenderer(
                names = { marks.marks.value.names },
                inFront = { front },
                showAge = { SkeinSettings.getInstance().state.showAge },
                previews = { previews },
                showPreview = { SkeinSettings.getInstance().state.showPreview },
                contexts = { contexts },
                showContext = { SkeinSettings.getInstance().state.showContext },
                worktreeCounts = { worktreeCounts },
                base = { project.basePath },
            ),
            headers = { headers },
        )
        putClientProperty(AnimatedIcon.ANIMATION_IN_RENDERER_ALLOWED, true)
        emptyText.text = "No threads yet"
        emptyText.appendSecondaryText("Start claude in a new tab", SimpleTextAttributes.LINK_ATTRIBUTES) {
            NewThreadAction.start(project, this)
        }
    }
    private val clicks = RowClicks(list, SkeinThread::id) { open(it.id) }
    private val history = HistoryPanel(project, scope)
    private val worktrees = WorktreesSection(project, scope)
    private val worktreesSplit = SectionSplit(
        JBScrollPane(list),
        worktrees.component,
        WORKTREES_PROPORTION_KEY,
        WORKTREES_PROPORTION,
        SectionSplit.IdeOpenMemory(WORKTREES_OPEN_KEY),
    )
    private val split = HistorySplit(worktreesSplit.component, history.component)

    val component: JComponent = split.component

    init {
        history.onToggle = split::show
        worktrees.onToggle = worktreesSplit::show
        if (worktreesSplit.wasOpen) worktrees.open()
        if (split.wasOpen) history.open()
        // before the menu, so a right click has selected its row when the menu reads the selection
        clicks.install()
        ThreadMenu(project, list).install()
        scope.launch(Dispatchers.EDT) {
            service.threads.collect { threads ->
                lastThreads = threads
                showSections()
            }
        }
        ApplicationManager.getApplication().messageBus.connect(parent).subscribe(
            SkeinSettings.TOPIC,
            SkeinSettings.Listener {
                ApplicationManager.getApplication().invokeLater(
                    {
                        showSections()
                        list.repaint() // a row setting (the age, the preview, the context) changes no thread, only how the rows look
                    },
                    ModalityState.any(),
                )
            },
        )
        scope.launch(Dispatchers.EDT) {
            service.inFront.collect {
                front = it
                list.repaint()
            }
        }
        scope.launch(Dispatchers.EDT) {
            // a new preview changes no thread, only line 2 of its row
            service.previews.collect {
                previews = it
                list.repaint()
            }
        }
        scope.launch(Dispatchers.EDT) {
            // a new model or context changes no thread either
            service.contexts.collect {
                contexts = it
                list.repaint()
            }
        }
        scope.launch(Dispatchers.EDT) {
            // the threads as they are now decide which folders the section lists, so an opened worktree leaves it at once
            combine(service.threads, hygiene.state) { threads, found -> threads to found }.collect { (threads, found) ->
                if (found.counts != worktreeCounts) {
                    worktreeCounts = found.counts
                    list.repaint() // new counts change no thread, only line 1 of a worktree thread's row
                }
                worktrees.show(KeptWorktrees.visible(found.kept, threads, found.resolved))
            }
        }
        // looks at the worktrees run only while the panel shows: the tool window opened, or the panel came back into it
        component.addHierarchyListener { e ->
            if (e.changeFlags and HierarchyEvent.SHOWING_CHANGED.toLong() != 0L) hygiene.setShown(component.isShowing)
        }
        Disposer.register(parent) { hygiene.setShown(false) }
        scope.launch(Dispatchers.EDT) {
            // a rename changes no thread, only the names the rows show
            marks.marks.map { it.names }.distinctUntilChanged().collect { list.repaint() }
        }
        scope.launch(Dispatchers.EDT) {
            // the ages in the rows ("4 min") move on even when no status changes
            while (true) {
                delay(AGE_REFRESH_MILLIS)
                list.repaint()
            }
        }
    }

    /**
     * Applies grouping and the filter to the last thread list and shows the result, keeping by thread id the selection
     * [RowSelection] keeps: one row or several, only while the list is in use.
     */
    private fun showSections() {
        val state = SkeinSettings.getInstance().state
        val sections = ThreadSections.of(lastThreads, state.groupThreadsByState, state.onlyWaitingThreads)
        val shown = sections.flatMap { it.threads }
        val selected = clicks.kept()
        headers = ThreadSections.headers(sections)
        list.emptyText.clear()
        if (lastThreads.isEmpty()) {
            list.emptyText.text = "No threads yet"
            list.emptyText.appendSecondaryText("Start claude in a new tab", SimpleTextAttributes.LINK_ATTRIBUTES) {
                NewThreadAction.start(project, list)
            }
        } else {
            list.emptyText.text = "No threads waiting for you"
        }
        model.replaceAll(shown)
        val indices = shown.indices.filter { shown[it].id in selected }.toIntArray()
        if (indices.isNotEmpty()) list.selectedIndices = indices
    }

    /**
     * Brings the thread's tab forward ([RowClicks] then clears the selection). The in-front marker moves at once
     * instead of at the next tick. If the tab did not come forward, [ThreadService.inFront] may not change and so not
     * correct the marker; a check a little later puts back what it says. EDT only.
     */
    private fun open(threadId: String) {
        service.select(threadId)
        front = threadId
        list.repaint()
        frontCheck?.cancel()
        frontCheck = scope.launch(Dispatchers.EDT) {
            delay(FRONT_CHECK_MILLIS)
            front = service.inFront.value
            list.repaint()
        }
    }

    companion object {
        private const val AGE_REFRESH_MILLIS = 30_000L

        /** Two of ThreadService's 1 s ticks: by then the tab a click brought forward is what it reports. */
        private const val FRONT_CHECK_MILLIS = 2_000L

        const val WORKTREES_PROPORTION_KEY = "Skein.Worktrees.Proportion"
        const val WORKTREES_OPEN_KEY = "Skein.Worktrees.Open"

        /** The threads' share above the Worktrees section when it first opens, before the owner drags the divider. */
        const val WORKTREES_PROPORTION = 0.7f
    }
}
