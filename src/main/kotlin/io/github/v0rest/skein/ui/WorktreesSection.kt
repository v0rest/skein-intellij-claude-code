package io.github.v0rest.skein.ui

import com.intellij.icons.AllIcons
import com.intellij.ide.actions.RevealFileAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.actionSystem.Separator
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.text.StringUtil
import com.intellij.ui.CollectionListModel
import com.intellij.ui.PopupHandler
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBList
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBFont
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.NamedColorUtil
import com.intellij.util.ui.UIUtil
import io.github.v0rest.skein.claude.ClaudeHome
import io.github.v0rest.skein.claude.HistoryEntry
import io.github.v0rest.skein.claude.HistoryScanner
import io.github.v0rest.skein.claude.SessionMonitor
import io.github.v0rest.skein.claude.WorktreeRef
import io.github.v0rest.skein.claude.Worktrees
import io.github.v0rest.skein.git.GitToplevel
import io.github.v0rest.skein.notify.ThreadNotifier
import io.github.v0rest.skein.threads.KeptKind
import io.github.v0rest.skein.threads.KeptWorktree
import io.github.v0rest.skein.threads.KeptWorktrees
import io.github.v0rest.skein.threads.ThreadService
import io.github.v0rest.skein.threads.WorktreeClose
import io.github.v0rest.skein.threads.WorktreeHygiene
import io.github.v0rest.skein.ui.actions.NewWorktreeThreadAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.awt.BorderLayout
import java.awt.Component
import java.awt.Cursor
import java.awt.Font
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap
import javax.swing.JComponent
import javax.swing.JList
import javax.swing.JPanel
import javax.swing.ListCellRenderer
import javax.swing.SwingConstants

/**
 * "Worktrees (N)" under the threads: the folders under the worktrees folder of each repository Skein knows that no
 * thread of this window holds ([io.github.v0rest.skein.threads.KeptWorktrees.visible]), shown only while there is one,
 * open or closed as the owner left it, with the History's header and rows. A double click, Enter or the menu's Open
 * opens a thread there ([openKept]); the menu also shows the worktree's changes in the IDE's diff viewer
 * ([WorktreeDiff]), reveals the folder in the file manager and removes it ([WorktreeCloseFlow]). Opening the section
 * asks for a look with the rows' counts ([WorktreeHygiene.setSectionOpen]), and Skein's own Open or Remove asks for one
 * right away when it is done. A folder with a `.git` that git does not list
 * here says so, with the `git worktree repair` hint in its tooltip; Open and Remove… explain and leave it. EDT only,
 * but for the flows it starts.
 */
internal class WorktreesSection(private val project: Project, private val scope: CoroutineScope) {
    private val hygiene = WorktreeHygiene.getInstance(project)
    private val model = CollectionListModel<KeptWorktree>()
    private val list = JBList(model).apply { cellRenderer = KeptWorktreeRowRenderer(project.basePath) }
    private val scroll = JBScrollPane(list).apply { isVisible = false }
    private val header = JBLabel(TITLE, AllIcons.General.ArrowRight, SwingConstants.LEFT).apply {
        border = JBUI.Borders.empty(6, 8)
        cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
    }
    private var expanded = false

    /** Told when the owner opens or closes the section, so the panel puts it under its divider or back at the bottom. */
    var onToggle: ((Boolean) -> Unit)? = null

    // closed, the section's own line separates it from the threads; open, the divider above it draws that line
    private val topLine = JBUI.Borders.customLineTop(JBUI.CurrentTheme.ToolWindow.borderColor())

    /** The section: its own line on top while closed, as History has. Hidden while no folder is listed. */
    val component: JComponent = JPanel(BorderLayout()).apply {
        border = topLine
        isVisible = false
        add(header, BorderLayout.NORTH)
        add(scroll, BorderLayout.CENTER)
    }

    private val clicks = RowClicks(list, KeptWorktree::path, RowSelection.Opening.DOUBLE_CLICK) { open(it) }

    /** The folders whose Open or Remove is under way, so a quick second one cannot ask or open twice. */
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        header.addMouseListener(
            object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) = toggle()
            },
        )
        // before the menu, so a right click has selected its row when the menu reads the selection
        clicks.install()
        PopupHandler.installPopupMenu(list, menu(), POPUP_PLACE)
    }

    /** Lists [rows]; the section shows only while there is one. Nothing changes when they did not. */
    fun show(rows: List<KeptWorktree>) {
        if (rows == model.items) return
        val selected = clicks.kept()
        model.replaceAll(rows)
        val indices = rows.indices.filter { rows[it].path in selected }.toIntArray()
        if (indices.isNotEmpty()) list.selectedIndices = indices
        header.text = if (rows.isEmpty()) TITLE else "$TITLE (${rows.size})"
        component.isVisible = rows.isNotEmpty()
        component.revalidate()
        component.repaint()
    }

    /** Opens the section, as at a start after the owner left it open; an open one stays as it is. */
    fun open() {
        if (!expanded) toggle()
    }

    private fun toggle() {
        expanded = !expanded
        header.icon = if (expanded) AllIcons.General.ArrowDown else AllIcons.General.ArrowRight
        scroll.isVisible = expanded
        component.border = if (expanded) JBUI.Borders.empty() else topLine
        component.revalidate()
        onToggle?.invoke(expanded)
        hygiene.setSectionOpen(expanded)
    }

    private fun selected(): KeptWorktree? = list.selectedValuesList.singleOrNull()

    private fun menu(): DefaultActionGroup {
        val open = object : DumbAwareAction("Open") {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selected()?.kind is KeptKind.Registered
            }

            override fun actionPerformed(e: AnActionEvent) {
                selected()?.let(clicks::openRow)
            }
        }
        val showChanges = object : DumbAwareAction("Show Changes") {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selected()?.kind is KeptKind.Registered
            }

            // no thread recorded a base for a kept worktree: the repository's base counts, as for the row's counts
            override fun actionPerformed(e: AnActionEvent) {
                val row = selected()?.takeIf { it.kind is KeptKind.Registered } ?: return
                WorktreeDiff.getInstance(project).show(row.repository, row.name, recorded = null)
            }
        }
        val reveal = object : DumbAwareAction(RevealFileAction.getActionName()) {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.text = RevealFileAction.getActionName()
                e.presentation.isEnabled = selected() != null && RevealFileAction.isSupported()
            }

            override fun actionPerformed(e: AnActionEvent) {
                selected()?.let { RevealFileAction.openFile(Path.of(it.path)) }
            }
        }
        val remove = object : DumbAwareAction("Remove…") {
            override fun getActionUpdateThread() = ActionUpdateThread.EDT

            override fun update(e: AnActionEvent) {
                e.presentation.isEnabled = selected() != null
            }

            override fun actionPerformed(e: AnActionEvent) {
                selected()?.let(::remove)
            }
        }
        return DefaultActionGroup(open, showChanges, reveal, Separator.getInstance(), remove)
    }

    /** Opens a thread in [row]'s worktree ([openKept]); a folder git does not list is no worktree to open. */
    private fun open(row: KeptWorktree) {
        when (val kind = row.kind) {
            is KeptKind.Registered -> once(row) { openKept(project, row) }
            KeptKind.Unregistered -> balloon(project, "git does not list ${row.path} as a worktree, so Skein does not start claude there")
            is KeptKind.Unlinked -> balloon(project, "Skein does not start claude in ${row.path}: ${WorktreeBadge.hint(kind, row.path).joinToString(" ")}")
            is KeptKind.Unknown -> balloon(project, "Skein could not open ${row.path}: ${kind.reason}")
        }
    }

    private fun remove(row: KeptWorktree) {
        val threads = ThreadService.getInstance(project)
        once(row) {
            WorktreeCloseFlow.removeKept(
                project,
                row.repository,
                row.name,
                SessionMonitor.getInstance().snapshots,
                inFlight = threads.closingWorktrees,
                active = { !project.isDisposed },
            )
        }
    }

    /** Runs [block] for [row] unless one is under way for it, then asks for a look right away. */
    private fun once(row: KeptWorktree, block: suspend () -> Unit) {
        if (!busy.add(row.path)) return
        scope.launch {
            try {
                block()
            } catch (_: ProcessCanceledException) {
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Skein could not act on the worktree ${row.name}", e)
            } finally {
                busy.remove(row.path)
                hygiene.requestNow()
            }
        }
    }

    companion object {
        private val LOG = logger<WorktreesSection>()
        const val TITLE = "Worktrees"
        private const val POPUP_PLACE = "SkeinWorktreesPopup"

        /**
         * Opens a thread in [row]'s worktree: the newest History session whose cwd lies in that worktree is resumed as
         * the History resumes ([resumeSession], which asks before a second claude on a session running elsewhere);
         * without one, a new `claude --worktree <name>` starts there with the checks of a new worktree thread
         * ([NewWorktreeThreadAction.reopen]), unless a Skein thread of another window already works in it. Off the EDT
         * but for its questions.
         */
        suspend fun openKept(project: Project, row: KeptWorktree) {
            val newest = withContext(Dispatchers.IO) { newestSession(row) }
            if (newest != null) return resumeSession(project, newest)
            val elsewhere = withContext(Dispatchers.IO) { KeptWorktrees.held(row.path, WorktreeCloseFlow.threadsOfOpenWindows(), GitToplevel::resolved) }
            if (elsewhere) return balloon(project, "Skein did not start claude in the worktree ${row.name}: ${WorktreeClose.OTHER_THREAD}")
            withContext(Dispatchers.IO) { NewWorktreeThreadAction.reopen(project, Path.of(row.repository), row.name) }
        }

        /** The newest History session that ran in [row]'s worktree (not one nested inside it), or null. Reads transcripts. */
        private fun newestSession(row: KeptWorktree): HistoryEntry? {
            val repositories = setOfNotNull(row.repository.trimEnd('/'), GitToplevel.resolved(row.repository), Worktrees.split(row.path)?.repository)
            val own = repositories.mapTo(HashSet()) { WorktreeRef(it, row.name) }
            return HistoryScanner(ClaudeHome.default()).scan(listOf(row.path)).entries
                .filter { Worktrees.split(it.cwd) in own }
                .maxByOrNull { it.lastActivity }
        }

        private fun balloon(project: Project, text: String) {
            if (project.isDisposed) return
            // the text is HTML to the platform, and a path may hold a `<`
            NotificationGroupManager.getInstance().getNotificationGroup(ThreadNotifier.GROUP_ID)
                .createNotification(StringUtil.escapeXmlEntities(text), NotificationType.INFORMATION)
                .notify(project)
        }
    }
}

/**
 * One row of the Worktrees section, with the History row's anatomy ([TwoLineRow]): a branch glyph (a folder for one
 * git does not list), the worktree's name and its counts on line 1 ([WorktreeBadge], as on a worktree thread's row),
 * and on line 2 its branch, "detached HEAD" or "not a git worktree" ([WorktreeBadge.kind]). The tooltip is the folder,
 * named from the project's [projectBase] when it lies inside the project ([WorktreeBadge.folder]), then the counts'
 * lines, wrapped ([WorktreeBadge.sectionTooltip]). No I/O: the look read everything.
 */
internal class KeptWorktreeRowRenderer(private val projectBase: String? = null) : ListCellRenderer<KeptWorktree> {
    private val row = TwoLineRow()

    /** The list font the small one was taken with; it is taken again only when that changes (theme, font size). */
    private var baseFont: Font? = null
    private var smallFont: Font? = null

    override fun getListCellRendererComponent(list: JList<out KeptWorktree>, value: KeptWorktree?, index: Int, selected: Boolean, cellHasFocus: Boolean): Component {
        val item = value ?: return row
        val base = list.font
        if (base !== baseFont) {
            baseFont = base
            smallFont = JBFont.small()
        }
        val focused = list.hasFocus()
        val foreground = if (selected) UIUtil.getListSelectionForeground(focused) else list.foreground
        val grey = if (selected && focused) foreground else NamedColorUtil.getInactiveTextColor()
        row.background = if (selected) UIUtil.getListSelectionBackground(focused) else list.background
        row.glyph.icon = if (item.kind is KeptKind.Registered) AllIcons.Vcs.Branch else AllIcons.Nodes.Folder
        row.title.text = item.name
        row.title.font = base
        row.title.foreground = foreground
        row.pin.isVisible = false
        row.pin.icon = null
        row.age.isVisible = false
        row.age.text = ""
        val second = WorktreeBadge.kind(item.kind)
        row.detail.text = second
        for (label in row.greyLabels) {
            label.font = smallFont
            label.foreground = grey
        }
        ThreadRowRenderer.showBadge(row, WorktreeBadge.of(item.counts), smallFont, grey, if (selected && focused) foreground else null)
        val folder = WorktreeBadge.folder(item.repository, item.name, item.path, projectBase)
        row.toolTipText = WorktreeBadge.sectionTooltip(folder, WorktreeBadge.hint(item.kind, item.path), WorktreeBadge.tooltip(item.counts))
        row.accessibleContext.accessibleName = listOfNotNull(item.name, second, WorktreeBadge.spoken(item.counts)).joinToString(", ")
        row.invalidate() // the next validate lays the row out for this folder, whatever the last one was
        return row
    }
}
