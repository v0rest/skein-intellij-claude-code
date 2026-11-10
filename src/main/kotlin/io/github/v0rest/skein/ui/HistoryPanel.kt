package io.github.v0rest.skein.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.readAction
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.DumbAwareToggleAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.IconUtil
import com.intellij.util.ui.JBUI
import io.github.v0rest.skein.claude.ClaudeHome
import io.github.v0rest.skein.claude.HistoryEntry
import io.github.v0rest.skein.claude.HistoryScanner
import io.github.v0rest.skein.claude.SessionMonitor
import io.github.v0rest.skein.claude.SessionSnapshot
import io.github.v0rest.skein.claude.Worktrees
import io.github.v0rest.skein.claude.liveIn
import io.github.v0rest.skein.git.GitToplevel
import io.github.v0rest.skein.threads.ResumeOutcome
import io.github.v0rest.skein.threads.SessionMarksService
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadService
import io.github.v0rest.skein.ui.actions.HistoryMenu
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.GridBagLayout
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.SwingConstants

/**
 * When an open history needs a rescan: a thread came or went, or its session changed, since a closed or `/clear`-ed
 * thread lands in history. A status flip does not, and neither does the value a state flow replays on subscribe: the
 * panel scans when it opens.
 */
internal fun historyRescans(threads: Flow<List<SkeinThread>>): Flow<Unit> =
    threads.map { list -> list.mapTo(HashSet()) { it.id to it.sessionId } }.distinctUntilChanged().drop(1).map { }

/** The ids of the sessions running right now, emitted when one starts or stops, not on every status change. */
internal fun liveSessionIds(snapshots: Flow<SessionSnapshot>): Flow<Set<String>> =
    snapshots.map { snapshot -> snapshot.sessions.values.mapNotNullTo(HashSet()) { it.sessionId } }.distinctUntilChanged()

/**
 * The history under the threads, closed until the owner opens it. It scans transcripts only while open, off the EDT. A
 * click selects a session, and a double click, Enter or the menu's Open resumes it in a new thread (or selects the
 * thread that already has it), with the thread list's selection rules ([RowClicks]).
 */
class HistoryPanel(private val project: Project, private val scope: CoroutineScope) {
    private val service = ThreadService.getInstance(project)
    private val marks = SessionMarksService.getInstance(project)
    private val scanner by lazy { HistoryScanner(ClaudeHome.default()) }
    private val scanLock = Mutex()
    private val model = CollectionListModel<HistoryItem>()
    private val list = JBList(model).apply {
        cellRenderer = HistoryRowRenderer(names = { marks.marks.value.names })
        emptyText.text = HistoryText.empty(scannedCount = 0, shownCount = 0)
    }
    private val scroll = JBScrollPane(list).apply {
        isVisible = false
    }
    private val header = JBLabel("History", AllIcons.General.ArrowRight, SwingConstants.LEFT).apply {
        border = JBUI.Borders.empty(6, 8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private var expanded = false

    /** Whether sessions marked done are listed; the eye in the header switches it. EDT only. */
    private var showDone = false

    /** The eye tinted with the accent colour, and that colour's RGB, so a theme change makes it again. EDT only. */
    private var tintedEye: Pair<Int, Icon>? = null

    /**
     * The eye in the header's right corner, shown while the history is open: done sessions listed or not. Its icon and
     * tooltip say which ([DoneEye]), and the toolbar draws it pressed while they are listed.
     */
    private val showDoneToggle = object : DumbAwareToggleAction(DoneEye.of(false).tooltip, null, SkeinIcons.DONE_SESSIONS_HIDDEN) {
        // showDone is a plain field of the panel, read and written on the EDT only
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

        override fun isSelected(e: AnActionEvent): Boolean = showDone

        override fun setSelected(e: AnActionEvent, state: Boolean) {
            showDone = state
            rebuild()
        }

        override fun update(e: AnActionEvent) {
            super.update(e)
            val eye = DoneEye.of(showDone)
            e.presentation.icon = when (eye.look) {
                DoneEye.Look.SHOWN -> accentEye()
                DoneEye.Look.HIDDEN -> SkeinIcons.DONE_SESSIONS_HIDDEN
            }
            e.presentation.text = eye.tooltip
        }
    }

    /** The platform's eye in the theme's link colour, the accent a theme keeps readable on its panels. EDT only. */
    private fun accentEye(): Icon {
        val rgb = JBUI.CurrentTheme.Link.Foreground.ENABLED.rgb
        tintedEye?.let { (tintedRgb, icon) -> if (tintedRgb == rgb) return icon }
        return IconUtil.colorize(AllIcons.Actions.Show, Color(rgb, true)).also { tintedEye = rgb to it }
    }

    private val headerToolbar = ActionManager.getInstance().createActionToolbar(HEADER_TOOLBAR_PLACE, DefaultActionGroup(showDoneToggle), true).apply {
        isReservePlaceAutoPopupIcon = false
        component.isOpaque = false
        // the right inset of the tool window's own title actions, so the eye sits clear of the edge as theirs do
        component.border = JBUI.Borders.emptyRight(8)
    }

    // centres the toolbar in the header's height, whatever height its button asks for
    private val headerActions = JPanel(GridBagLayout()).apply {
        isOpaque = false
        isVisible = false
        add(headerToolbar.component)
    }
    private val headerBar = JPanel(BorderLayout()).apply {
        add(header, BorderLayout.CENTER)
        add(headerActions, BorderLayout.EAST)
    }

    // the last scan, kept so a pin, a done mark or the eye can re-arrange the rows without reading any transcript
    private var scanned: List<HistoryEntry> = emptyList()
    private var scannedFolders: ProjectFolders? = null

    /** Told the new state each time the history opens or closes, so the tool window can lay itself out. */
    var onToggle: ((Boolean) -> Unit)? = null

    // closed, the history's own line separates it from the threads; open, the divider above it draws that line
    private val topLine = JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor())

    val component: JComponent = JPanel(BorderLayout()).apply {
        border = topLine
        add(headerBar, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    /**
     * The thread list's selection rules ([RowClicks]), opening on a double click: a click selects a session, a double
     * click on it or Enter resumes it, and no row stays selected after that.
     */
    private val clicks = RowClicks(list, { it.entry.sessionId }, RowSelection.Opening.DOUBLE_CLICK) { resume(it.entry) }

    /** The sessions whose resume is under way, so a quick second double click or Enter cannot ask or open twice. */
    private val resuming: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        headerToolbar.targetComponent = component
        header.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = toggle()
            },
        )
        // before the menu, so a right click has selected its row when the menu reads the selection
        clicks.install()
        PopupHandler.installPopupMenu(
            list,
            HistoryMenu(
                project,
                selected = { list.selectedValuesList.map { it.row } },
                // the way a double click resumes, through the same guard, and no row stays selected after it
                open = { list.selectedValuesList.singleOrNull()?.let(clicks::openRow) },
            ).group(),
            "SkeinHistoryPopup",
        )
        scope.launch(Dispatchers.EDT) {
            historyRescans(service.threads).collect { if (expanded) reload() }
        }
        scope.launch(Dispatchers.EDT) {
            // a rename changes no transcript: the rows only need to be drawn again
            marks.marks.map { it.names }.distinctUntilChanged().collect { list.repaint() }
        }
        scope.launch(Dispatchers.EDT) {
            // a pin or a done mark moves rows: arrange the last scan again
            marks.marks.map { it.pinned to it.done }.distinctUntilChanged().drop(1).collect { if (expanded) rebuild() }
        }
        scope.launch(Dispatchers.EDT) {
            // a session that starts or stops anywhere flips its "running" label; that needs no rescan
            liveSessionIds(SessionMonitor.getInstance().snapshots).collect(::relabel)
        }
    }

    private fun toggle() {
        expanded = !expanded
        header.icon = if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        scroll.isVisible = expanded
        headerActions.isVisible = expanded
        component.border = if (expanded) JBUI.Borders.empty() else topLine
        onToggle?.invoke(expanded)
        component.revalidate()
        if (expanded) reload()
    }

    /** Opens the history, as the owner left it the last time. */
    fun open() {
        if (!expanded) toggle()
    }

    /** Updates the "running" labels of the rows already listed, without reading any transcript. */
    private fun relabel(live: Set<String>) {
        val rows = model.items
        if (rows.none { (it.entry.sessionId in live) != it.running }) return
        replaceRows(rows.map { HistoryItem(it.row, it.label, it.entry.sessionId in live) })
    }

    /** Arranges the last scan again with the current marks and the done-sessions eye ([showDone]), without a rescan. */
    private fun rebuild() {
        val folders = scannedFolders ?: return
        val snapshot = SessionMonitor.getInstance().snapshots.value
        val rows = HistoryView.arrange(scanned, marks.marks.value, showDone).map { r ->
            val entry = r.entry
            HistoryItem(r, HistoryLabels.secondary(entry, folders.base, folders::label), entry.liveIn(snapshot) != null)
        }
        replaceRows(rows)
        list.emptyText.text = HistoryText.empty(scanned.size, rows.size)
        header.text = if (rows.isEmpty()) "History" else "History (${rows.size})"
    }

    /** Counts the reloads asked for; a reload that is no longer the latest leaves the marks and the list alone. EDT only. */
    private var reloadGeneration = 0

    /** Whether the last scan was incomplete, so the warning goes out once per spell of failures, not on every rescan. */
    private var warnedIncomplete = false

    /** What the IO side of a reload brings to the EDT. */
    private class Sampled(val scan: HistoryScanner.Scan, val examined: Set<String>, val onDisk: Set<String>)

    private fun reload() {
        val generation = ++reloadGeneration
        scope.launch {
            val folders = readAction { ProjectFolders.of(project) }
            val sampled = withContext(Dispatchers.IO) {
                val scan = scanLock.withLock { scanner.scan(scanRoots(folders)) }
                // the marks are sampled here, so only these may be pruned: one made during the scan waits for the next
                val examined = marks.marks.value.ids()
                Sampled(scan, examined, stillOnDisk(examined - scan.entries.mapTo(HashSet()) { it.sessionId }))
            }
            withContext(Dispatchers.EDT) {
                // a newer reload is on its way or done: its sample is fresher than this one
                if (generation != reloadGeneration) return@withContext
                val scan = sampled.scan
                if (!scan.complete && !warnedIncomplete) {
                    LOG.warn("Skein could not list every session folder under ${ClaudeHome.default().projectsDir}; the History may be incomplete and no mark was dropped")
                }
                warnedIncomplete = !scan.complete
                // the labels come from the sessions live right now, read on the EDT where relabel runs too, so a slow
                // scan cannot put back a "running" label that relabel has already taken away; the held sessions are
                // read here too, right before the marks change, so a session a thread took during the scan is kept
                val canPrune = folders.all.isNotEmpty() && scan.complete
                val keep = heldNow() + sampled.onDisk
                marks.update { current -> HistoryView.reconcile(scan.entries, current, keep, canPrune, sampled.examined) }
                scanned = scan.entries
                scannedFolders = folders
                rebuild()
            }
        }
    }

    /** The folders to scan: the project's own plus the worktrees folder of each repository they sit in. Reads the disk, so off the EDT. */
    private fun scanRoots(folders: ProjectFolders): List<String> {
        val worktreeParents = folders.all.mapNotNull { folder ->
            try {
                GitToplevel.of(Path.of(folder))?.let { Worktrees.parentOf(it.toString()) }
            } catch (e: InvalidPathException) {
                null
            }
        }
        return (folders.all + worktreeParents).distinct()
    }

    /** The sessions held by a thread in any open project; cheap, and safe on the EDT. */
    private fun heldNow(): Set<String> =
        ProjectManager.getInstance().openProjects.filterNot { it.isDisposed }.flatMapTo(HashSet()) { ThreadService.getInstance(it).heldSessionIds() }

    /**
     * Which of the marked sessions the scan did not list ([missing]) still have a transcript on disk and so keep their
     * marks (a file mid-rewrite is skipped by a scan for a moment). A listing that fails or finds nothing while marks
     * exist is no proof of absence: then all of them are kept this round.
     */
    private fun stillOnDisk(missing: Set<String>): Set<String> {
        if (missing.isEmpty()) return emptySet()
        val home = ClaudeHome.default()
        val folders = home.listOrNull(home.projectsDir) ?: return HistoryView.keptAlive(missing, null)
        val onDisk = HashSet<String>()
        for (folder in folders) {
            // a stray file among the project folders (a Finder .DS_Store) is not a folder that failed to list
            if (!Files.isDirectory(folder)) continue
            val files = home.listOrNull(folder, "*.jsonl") ?: return HistoryView.keptAlive(missing, null)
            files.mapTo(onDisk) { HistoryView.sessionIdOf(it) }
        }
        return HistoryView.keptAlive(missing, onDisk)
    }

    /**
     * Replaces the rows and selects again, by session, what [RowClicks.kept] keeps: the selection while the list is in
     * use, so a refresh never undoes a multi-selection made for the menu, and nothing once the user is elsewhere.
     */
    private fun replaceRows(rows: List<HistoryItem>) {
        val selected = clicks.kept()
        model.replaceAll(rows)
        val indices = rows.indices.filter { rows[it].entry.sessionId in selected }.toIntArray()
        if (indices.isNotEmpty()) list.selectedIndices = indices
    }

    /**
     * Resumes [entry] in a new thread, or brings forward the thread that has it. A second call while the first is under
     * way does nothing: once the first is done, the session has its thread and a second call only brings it forward.
     */
    private fun resume(entry: HistoryEntry) {
        val sessionId = entry.sessionId
        if (!resuming.add(sessionId)) return
        scope.launch {
            try {
                resumeOnce(entry)
            } finally {
                resuming.remove(sessionId)
            }
        }
    }

    private suspend fun resumeOnce(entry: HistoryEntry) = resumeSession(project, entry)

    private companion object {
        val LOG = logger<HistoryPanel>()

        const val HEADER_TOOLBAR_PLACE = "SkeinHistoryHeader"
    }
}

/**
 * Resumes [entry] in a new thread, or brings forward the thread that has it, as the History does: a session running in
 * another terminal is resumed only after the owner agrees to a second claude on it, a session id Skein does not type is
 * refused with a word, and a session that opens is no longer marked done. Shared with the Worktrees section's Open.
 */
internal suspend fun resumeSession(project: Project, entry: HistoryEntry) {
    val service = ThreadService.getInstance(project)
    val marks = SessionMarksService.getInstance(project)
    val undone = { marks.update { it.undone(listOf(entry.sessionId)) } }
    when (service.resume(entry)) {
        is ResumeOutcome.RunningElsewhere -> {
            val confirmed = withContext(Dispatchers.EDT) {
                Messages.showYesNoDialog(
                    project,
                    "This session is running in another terminal. Resuming it here starts a second claude on the same conversation.",
                    "Resume Session",
                    "Resume Here",
                    "Cancel",
                    Messages.getWarningIcon(),
                ) == Messages.YES
            }
            if (confirmed && service.resume(entry, force = true).let { it is ResumeOutcome.Opened || it is ResumeOutcome.Selected }) undone()
        }
        ResumeOutcome.Refused -> withContext(Dispatchers.EDT) {
            Messages.showInfoMessage(project, "Skein resumes only sessions with a standard Claude Code session id.", "Resume Session")
        }
        is ResumeOutcome.Opened, is ResumeOutcome.Selected -> undone()
    }
}
