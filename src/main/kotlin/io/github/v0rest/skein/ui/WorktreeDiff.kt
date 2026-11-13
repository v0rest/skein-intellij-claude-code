package io.github.v0rest.skein.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.editor.DiffEditorTabFilesManager
import com.intellij.diff.editor.DiffEditorViewerFileEditor
import com.intellij.diff.editor.DiffVirtualFileWithTabName
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.diff.tools.combined.CombinedBlockProducer
import com.intellij.diff.tools.combined.CombinedDiffComponentProcessor
import com.intellij.diff.tools.combined.CombinedDiffManager
import com.intellij.diff.tools.combined.CombinedDiffVirtualFile
import com.intellij.diff.tools.combined.CombinedPathBlockId
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.EDT
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.ControlFlowException
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.fileEditor.FileEditorManagerListener
import com.intellij.openapi.fileTypes.FileType
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.text.StringUtil
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.LocalFilePath
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.platform.ide.progress.withBackgroundProgress
import io.github.v0rest.skein.claude.ClaudeHome
import io.github.v0rest.skein.claude.Worktrees
import io.github.v0rest.skein.git.BlobRead
import io.github.v0rest.skein.git.ChangeKind
import io.github.v0rest.skein.git.ChangeList
import io.github.v0rest.skein.git.ChangedFile
import io.github.v0rest.skein.git.ChangesLookup
import io.github.v0rest.skein.git.GitToplevel
import io.github.v0rest.skein.git.NoOptionalLocks
import io.github.v0rest.skein.git.ProcessGitRunner
import io.github.v0rest.skein.git.WorktreeChangesReader
import io.github.v0rest.skein.notify.ThreadNotifier
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.WorktreeClose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.InvalidPathException
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** What a file is in the worktree, on disk, as Show Changes finds it. */
sealed interface DiskSide {
    data object Missing : DiskSide

    data class File(val size: Long) : DiskSide

    /** A link, by where it points, which is what git stores for one. */
    data class Link(val target: String) : DiskSide

    data object Folder : DiskSide

    /** A pipe, a socket or a device. */
    data object Special : DiskSide

    /** Reached only through a link on the way, or not a path inside the worktree at all: never read. */
    data object Outside : DiskSide
}

/**
 * Where a file of Show Changes lies in the worktree, looked at only inside it: the path is walked from the worktree's
 * real path one name at a time, and a link on the way stops the walk, so a file behind a link to somewhere else is
 * never taken for the worktree side. A link at the end is the link itself, by where it points, never followed.
 * Blocking.
 */
object WorktreeDisk {
    /** Where the worktree side of [relative] (a path as git writes it) is read from: under [worktree]'s real path. */
    fun pathOf(worktree: Path, relative: String): Path = worktree.toRealPath().resolve(relative.removeSuffix("/"))

    /**
     * What [relative] is under [worktree]. A name git never writes (empty, `.` or `..`) and a link on the way make it
     * [DiskSide.Outside], a missing folder or a file on the way [DiskSide.Missing], and a file is one only while its
     * real path still lies in the worktree's.
     */
    fun of(worktree: Path, relative: String): DiskSide {
        val root = worktree.toRealPath()
        val names = relative.removeSuffix("/").split('/')
        if (names.any { it.isEmpty() || it == "." || it == ".." }) return DiskSide.Outside
        var at = root
        for (name in names.dropLast(1)) {
            at = at.resolve(name)
            if (Files.isSymbolicLink(at)) return DiskSide.Outside
            if (!Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS)) return DiskSide.Missing
        }
        at = at.resolve(names.last())
        return when {
            Files.isSymbolicLink(at) -> DiskSide.Link(Files.readSymbolicLink(at).toString())
            Files.isDirectory(at, LinkOption.NOFOLLOW_LINKS) -> DiskSide.Folder
            Files.isRegularFile(at, LinkOption.NOFOLLOW_LINKS) -> if (at.toRealPath().startsWith(root)) DiskSide.File(Files.size(at)) else DiskSide.Outside
            Files.exists(at, LinkOption.NOFOLLOW_LINKS) -> DiskSide.Special
            else -> DiskSide.Missing
        }
    }
}

/** The base side of a file in the diff: nothing (an added file), its bytes, or where a link pointed, as text. */
enum class BaseView { NONE, BYTES, LINK }

/** The worktree side of a file in the diff: nothing (a deleted file), the file itself, or where a link points. */
enum class WorktreeView { NONE, FILE, LINK }

/** How one file of Show Changes is shown: a note instead of a diff, or a diff of its two sides. */
sealed interface DiffPlan {
    data class Note(val text: String) : DiffPlan

    data class Show(val base: BaseView, val worktree: WorktreeView) : DiffPlan
}

/** The rules and words of Show Changes, a worktree's files against the commit it shares with its base. Pure. */
object ShowChanges {
    /** The most Show Changes reads of either side of one file; a bigger one gets a note and is never loaded. */
    const val LIMIT: Long = 10L * 1024 * 1024

    /**
     * How [file] is shown, with the worktree side as [disk] found it ([WorktreeDisk]), before anything is read. A
     * submodule gets a note. A deletion shows its base against an empty side whatever lies at its path now, since that
     * is not the file git deleted. Otherwise a folder (a repository of its own, say), anything but a file or a link, a
     * path behind a link, a file bigger than [limit] and one gone since the list was made get a note. The base side's
     * size is known only once git is asked ([tooLargeAtBase]).
     */
    fun plan(file: ChangedFile, disk: DiskSide, limit: Long = LIMIT): DiffPlan {
        val base = file.base
        if (base?.submodule == true) return DiffPlan.Note("${file.path} is a submodule, and Show Changes compares files only.")
        val left = when {
            base == null -> BaseView.NONE
            base.link -> BaseView.LINK
            else -> BaseView.BYTES
        }
        if (file.kind == ChangeKind.DELETED) {
            return if (base == null) DiffPlan.Note("${file.path} is no longer in the worktree.") else DiffPlan.Show(left, WorktreeView.NONE)
        }
        when (disk) {
            DiskSide.Folder -> return DiffPlan.Note("${file.path} is a folder in the worktree, a repository of its own say, and Show Changes compares files only.")
            DiskSide.Special -> return DiffPlan.Note("${file.path} is not a regular file in the worktree, and Show Changes compares files only.")
            DiskSide.Outside -> return DiffPlan.Note("${file.path} lies behind a link in the worktree, and Show Changes reads nothing outside the worktree.")
            is DiskSide.File -> if (disk.size > limit) return DiffPlan.Note("${file.path} is ${size(disk.size)} in the worktree, more than the ${size(limit)} Show Changes reads.")
            DiskSide.Missing -> if (base == null) return DiffPlan.Note("${file.path} is no longer in the worktree.")
            is DiskSide.Link -> Unit
        }
        val right = when (disk) {
            DiskSide.Missing -> WorktreeView.NONE
            is DiskSide.Link -> WorktreeView.LINK
            else -> WorktreeView.FILE
        }
        return DiffPlan.Show(left, right)
    }

    /** The note for a file whose base side git says is [size] bytes, more than [limit]. */
    fun tooLargeAtBase(file: ChangedFile, size: Long, limit: Long): String =
        "${file.from ?: file.path} is ${size(size)} at the base, more than the ${size(limit)} Show Changes reads."

    fun size(bytes: Long): String = when {
        bytes < 1024 -> "$bytes bytes"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024)).replace(".0 MB", " MB")
    }

    /** The base side's title: the base branch at the merge-base, and where the file was there when it moved since. */
    fun baseTitle(base: String, mergeBase: String, file: ChangedFile): String = "$base at ${mergeBase.take(7)}" + (file.from?.let { ", $it" } ?: "")

    fun worktreeTitle(name: String): String = "worktree $name"

    fun progress(name: String): String = "Reading the changes of the worktree $name"

    fun refused(name: String, reason: String): String = "Skein cannot show the changes of the worktree $name: $reason"

    fun noChanges(name: String, base: String): String = "The worktree $name has no changes against $base."

    fun untrusted(name: String): String =
        "Skein shows the changes of the worktree $name only in a trusted project, since git can start programs a repository's configuration names."

    /**
     * Whether an editor's unsaved change to the file at [path] ([canonical] with its links resolved) is saved before
     * the list is read: the file lies in one of the worktree's folders [dirs] ([WorktreeCloseFlow.anyInWorktree]).
     */
    fun inWorktree(path: String, canonical: String?, dirs: Set<String>): Boolean = WorktreeCloseFlow.anyInWorktree(listOf(path to canonical), dirs)

    /** The worktree [name]'s folder under [repository] as named and under its [real] path, the links resolved. */
    fun worktreeDirs(repository: String, name: String, real: String?): Set<String> =
        setOfNotNull(Worktrees.dirOf(repository, name), real?.let { Worktrees.dirOf(it, name) })

    /** The most files the combined view lists, a block each; past it the rest is one note ([capped]). */
    const val MAX_BLOCKS = 200

    /** The [files] the combined view lists, and how many [more] there are past the cap. */
    data class Shown(val files: List<ChangedFile>, val more: Int)

    /** The worktree's tab: its name and how many files differ, all of them, listed or not. */
    fun tabTitle(name: String, count: Int): String = "Changes in $name (${String.format(Locale.ROOT, "%,d", count)} ${if (count == 1) "file" else "files"})"

    /**
     * The first [max] of [files] and how many more: each block lays out a header and a viewer, so an untracked folder
     * no ignore rule hides, with thousands of files, would make the tab unusable.
     */
    fun capped(files: List<ChangedFile>, max: Int = MAX_BLOCKS): Shown = Shown(files.take(max), (files.size - max).coerceAtLeast(0))

    /** The last block's note when [more] files are past the cap of [max]. */
    fun moreNote(more: Int, max: Int): String =
        if (more == 1) "1 more file is not shown here, past the $max Show Changes lists." else "$more more files are not shown here, past the $max Show Changes lists."

    /** The path a block stands for: [file]'s own in [worktree], a folder's without its trailing slash. */
    fun blockPath(worktree: String, file: ChangedFile): String = worktree.trimEnd('/') + "/" + file.path.removeSuffix("/")

    /** Whether [file] is a folder git lists as one (a repository inside the worktree). */
    fun isFolder(file: ChangedFile): Boolean = file.path.endsWith("/")

    fun noCombinedView(name: String): String =
        "Skein cannot show the changes of the worktree $name: the IDE's combined diff comes with its bundled Version Control plugin, which is turned off."

    /** The thread Show Changes is for in the thread menu: the one selected thread, when it is a worktree thread. */
    fun worktreeThread(selected: List<SkeinThread>): SkeinThread? = selected.singleOrNull()?.takeIf { it.worktree?.let(Worktrees::isValidName) == true }
}

/**
 * The Show Changes tab of each worktree, by the worktree's path: a second Show Changes refreshes it instead of opening
 * another. A tab is remembered from when it opens until it closes, or until it is asked for and is no longer open.
 * Safe from any thread.
 */
class DiffTabs<T : Any> {
    private val tabs = HashMap<String, T>()

    /** The tab of [key] while [isOpen] says it is still open; one that is not is forgotten. */
    fun current(key: String, isOpen: (T) -> Boolean): T? = synchronized(tabs) {
        val tab = tabs[key] ?: return null
        if (isOpen(tab)) return tab
        tabs.remove(key)
        null
    }

    fun opened(key: String, tab: T) {
        synchronized(tabs) { tabs[key] = tab }
    }

    /** [tab] closed: forgotten, unless its worktree has a newer one by now. */
    fun closed(tab: T) {
        synchronized(tabs) { tabs.values.removeIf { it == tab } }
    }
}

/**
 * Show Changes: the IDE's combined diff over every file that differs between a worktree's merge-base with its base and
 * the worktree as it is on disk, all in one tab, a block per file one under the other ([WorktreeChangesReader]). The tab
 * names the worktree and the count of files, each block the file. One tab per worktree: a second Show Changes saves the
 * worktree's unsaved files, reads the list again and puts it in the tab it opened before ([DiffTabs]). The list is read
 * off the EDT with a progress in the status bar, and a refusal or an empty list is a balloon instead of an empty tab,
 * which leaves an open tab as it is. Each file is read only when the combined view gets to its block, by the platform's
 * own background loading ([FileProducer]). The combined view is `@ApiStatus.Experimental` API, and the IDE's bundled
 * Version Control plugin provides it; without that plugin Show Changes says so. The base side is
 * read-only, the worktree side is the file itself, so the IDE edits and saves it as any file. Skein writes nothing and
 * registers no VCS root for this: git runs through Skein's own runner, without optional locks and with `core.fsmonitor`
 * off, and only in a trusted project, since git can start programs a repository's configuration names.
 */
@Service(Service.Level.PROJECT)
class WorktreeDiff(private val project: Project, private val scope: CoroutineScope) {
    private val reader = WorktreeChangesReader(NoOptionalLocks(ProcessGitRunner(GIT_TIMEOUT_MILLIS)))

    /** The worktrees whose Show Changes is under way, so a quick second click does not open a second window. */
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** The tab Show Changes opened for each worktree, by the worktree's path, so a second one refreshes it. */
    private val tabs = DiffTabs<WorktreeChangesFile>()

    /** Counts the lists read, so every block of a new one has an id of its own and is loaded anew. */
    private val generation = AtomicLong()

    init {
        project.messageBus.connect(scope).subscribe(
            FileEditorManagerListener.FILE_EDITOR_MANAGER,
            object : FileEditorManagerListener {
                override fun fileClosed(source: FileEditorManager, file: VirtualFile) {
                    if (file is WorktreeChangesFile) tabs.closed(file)
                }
            },
        )
    }

    private sealed interface Outcome {
        data class Said(val text: String, val type: NotificationType) : Outcome

        /** The worktree's tab to open or refresh, keyed by the worktree's [key], titled [title], with its [blocks]. */
        class Diff(val key: String, val title: String, val blocks: List<CombinedBlockProducer>) : Outcome
    }

    /**
     * Shows the changes of the worktree [name] of [repository] (as the project or the thread names it) against the
     * branch [recorded] for its thread, or else the repository's base. Never blocks, never throws.
     */
    fun show(repository: String, name: String, recorded: String?) {
        if (!Worktrees.isValidName(name)) return
        val key = Worktrees.dirOf(repository, name)
        if (!busy.add(key)) return
        scope.launch {
            try {
                val outcome = withBackgroundProgress(project, ShowChanges.progress(name)) {
                    withContext(Dispatchers.IO) {
                        if (TrustedProjects.isProjectTrusted(project)) saveEditsIn(repository, name)
                        look(repository, name, recorded)
                    }
                }
                when (outcome) {
                    is Outcome.Said -> balloon(outcome.text, outcome.type)
                    is Outcome.Diff -> withContext(Dispatchers.EDT) {
                        if (!project.isDisposed) open(outcome)
                    }
                }
            } catch (_: ProcessCanceledException) {
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LOG.warn("Skein could not show the changes of the worktree $name", e)
                balloon(ShowChanges.refused(name, e.message ?: e.javaClass.simpleName), NotificationType.WARNING)
            } finally {
                busy.remove(key)
            }
        }
    }

    /**
     * Saves what the editor holds unsaved for the files in the worktree [name] of [repository], and nothing else, so an
     * edit just made on a diff's worktree side is in the list git reads instead of waiting for the IDE to save it. On
     * the EDT, as saving takes; called off it, and waits.
     */
    private fun saveEditsIn(repository: String, name: String) {
        val dirs = ShowChanges.worktreeDirs(repository, name, GitToplevel.resolved(repository))
        val documents = FileDocumentManager.getInstance()
        ApplicationManager.getApplication().invokeAndWait({
            if (project.isDisposed) return@invokeAndWait
            documents.saveDocuments { document -> documents.getFile(document)?.let { ShowChanges.inWorktree(it.path, it.canonicalPath, dirs) } == true }
        }, ModalityState.defaultModalityState())
    }

    /** What to show for the worktree: the diff, or a balloon's words. Blocking, off the EDT. */
    private fun look(repository: String, name: String, recorded: String?): Outcome {
        if (!TrustedProjects.isProjectTrusted(project)) return Outcome.Said(ShowChanges.untrusted(name), NotificationType.WARNING)
        if (project.getService(CombinedDiffManager::class.java) == null) return Outcome.Said(ShowChanges.noCombinedView(name), NotificationType.WARNING)
        val real = GitToplevel.resolved(repository) ?: return Outcome.Said(ShowChanges.refused(name, "its repository $repository is gone"), NotificationType.WARNING)
        if (ClaudeHome.default().holdsWorktreesOf(Path.of(real))) return Outcome.Said(ShowChanges.refused(name, WorktreeClose.CLAUDE_HOME), NotificationType.WARNING)
        val worktree = Path.of(Worktrees.dirOf(real, name))
        return when (val found = reader.read(Path.of(real), worktree, recorded)) {
            is ChangesLookup.Refused -> Outcome.Said(ShowChanges.refused(name, found.reason), NotificationType.WARNING)
            is ChangesLookup.Found ->
                if (found.files.isEmpty()) {
                    Outcome.Said(ShowChanges.noChanges(name, found.base.name), NotificationType.INFORMATION)
                } else {
                    Outcome.Diff(worktree.toString(), ShowChanges.tabTitle(name, found.files.size), blocks(worktree, name, found))
                }
        }
    }

    /**
     * The blocks of [found], in its order: a block per file up to [ShowChanges.MAX_BLOCKS], then one note for the rest.
     * A block's id holds the file's own path, named by its label ([BlockPath]), and this list's generation, so a refresh
     * loads every block anew rather than keep what an earlier list loaded.
     */
    private fun blocks(worktree: Path, name: String, found: ChangesLookup.Found): List<CombinedBlockProducer> {
        val tag = generation.incrementAndGet()
        val shown = ShowChanges.capped(found.files)
        val files = producers(project, reader, worktree, name, found.copy(files = shown.files)).zip(shown.files) { producer, file ->
            val path = BlockPath(ShowChanges.blockPath(worktree.toString(), file), ShowChanges.isFolder(file), ChangeList.label(file))
            CombinedBlockProducer(CombinedPathBlockId(path, status(file.kind), tag), producer)
        }
        if (shown.more == 0) return files
        val label = "… and ${shown.more} more"
        val note = NoteProducer(label, ShowChanges.moreNote(shown.more, ShowChanges.MAX_BLOCKS))
        return files + CombinedBlockProducer(CombinedPathBlockId(BlockPath(worktree.toString(), true, label), FileStatus.NOT_CHANGED, tag), note)
    }

    private fun status(kind: ChangeKind): FileStatus = when (kind) {
        ChangeKind.ADDED -> FileStatus.ADDED
        ChangeKind.UNTRACKED -> FileStatus.UNKNOWN
        ChangeKind.DELETED -> FileStatus.DELETED
        ChangeKind.MODIFIED, ChangeKind.RENAMED, ChangeKind.COPIED -> FileStatus.MODIFIED
    }

    /**
     * Opens the worktree's tab, or refreshes the one Show Changes opened for it before, in place: its title and the
     * blocks of every view of it, then brings it to the front. The tab is a file of Skein's own ([WorktreeChangesFile]),
     * opened as the IDE opens a diff, in the editor or in a separate window as its settings say. EDT.
     */
    private fun open(outcome: Outcome.Diff) {
        val editors = FileEditorManager.getInstance(project)
        val tab = tabs.current(outcome.key) { editors.isFileOpen(it) }
        if (tab != null) {
            tab.update(outcome.title, outcome.blocks)
            editors.getAllEditors(tab).filterIsInstance<DiffEditorViewerFileEditor>()
                .mapNotNull { it.editorViewer as? CombinedDiffComponentProcessor }
                .forEach { it.setBlocks(outcome.blocks) }
            editors.updateFilePresentation(tab)
            DiffEditorTabFilesManager.getInstance(project).showDiffFile(tab, true)
            return
        }
        val file = WorktreeChangesFile(outcome.key, outcome.title, outcome.blocks)
        tabs.opened(outcome.key, file)
        DiffEditorTabFilesManager.getInstance(project).showDiffFile(file, true)
    }

    private fun balloon(text: String, type: NotificationType) {
        if (project.isDisposed) return
        // the text is HTML to the platform, and a path may hold a `<`
        NotificationGroupManager.getInstance().getNotificationGroup(ThreadNotifier.GROUP_ID)
            .createNotification(StringUtil.escapeXmlEntities(text), type)
            .notify(project)
    }

    companion object {
        private val LOG = logger<WorktreeDiff>()

        /** How long one git call of Show Changes may take: the person waits for it, with a progress, but not forever. */
        const val GIT_TIMEOUT_MILLIS = 30_000

        /** The place the combined view is made for, as the platform's diff settings know it. */
        private const val DIFF_PLACE = "Skein.WorktreeChanges"

        fun getInstance(project: Project): WorktreeDiff = project.service()

        /** A producer per file of [found] in the worktree [name], each named by the file's label ([ChangeList.label]). */
        internal fun producers(project: Project, reader: WorktreeChangesReader, worktree: Path, name: String, found: ChangesLookup.Found): List<DiffRequestProducer> =
            found.files.map { file ->
                FileProducer(project, reader, worktree, file, ChangeList.label(file), ShowChanges.baseTitle(found.base.name, found.mergeBase, file), ShowChanges.worktreeTitle(name))
            }

        /**
         * The combined view for a [WorktreeChangesFile], made by the IDE's [CombinedDiffManager], which its bundled
         * Version Control plugin provides; checked before git runs ([look]).
         */
        internal fun combinedView(project: Project, blocks: List<CombinedBlockProducer>): CombinedDiffComponentProcessor {
            val manager = project.getService(CombinedDiffManager::class.java) ?: throw IllegalStateException("the IDE's combined diff is not available")
            return manager.createProcessor(DIFF_PLACE, emptyList(), emptyList()).also { it.setBlocks(blocks) }
        }
    }
}

/**
 * One file of Show Changes, read when the combined view gets to its block, on the platform's background thread: what the
 * file is on disk inside the worktree ([WorktreeDisk]), then its bytes at the merge-base through git, each side only up
 * to [ShowChanges.LIMIT].
 */
private class FileProducer(
    private val project: Project,
    private val reader: WorktreeChangesReader,
    private val worktree: Path,
    private val file: ChangedFile,
    private val title: String,
    private val baseTitle: String,
    private val worktreeTitle: String,
) : DiffRequestProducer {
    /** The file's label, as its block's title. */
    override fun getName(): String = title

    /**
     * The request for the file, or a note with the reason when it cannot be read. Never throws but for cancellation: the
     * combined view loads a batch of blocks in one go, and a throw would leave the rest of them loading for good.
     */
    override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest =
        try {
            request(indicator)
        } catch (e: DiffRequestProducerException) {
            MessageDiffRequest(title, e.message ?: "Skein cannot show ${file.path}")
        } catch (e: Exception) {
            if (e is ControlFlowException || e is CancellationException) throw e
            MessageDiffRequest(title, "Skein cannot show ${file.path}: ${e.message ?: e.javaClass.simpleName}")
        }

    private fun request(indicator: ProgressIndicator): DiffRequest {
        val disk = try {
            WorktreeDisk.of(worktree, file.path)
        } catch (_: InvalidPathException) {
            throw DiffRequestProducerException("Skein cannot read the path ${file.path}")
        } catch (e: IOException) {
            throw DiffRequestProducerException("Skein cannot read ${file.path} in the worktree: ${e.message}")
        }
        val plan = when (val planned = ShowChanges.plan(file, disk)) {
            is DiffPlan.Note -> return MessageDiffRequest(title, planned.text)
            is DiffPlan.Show -> planned
        }
        val factory = DiffContentFactory.getInstance()
        val local = if (plan.worktree == WorktreeView.FILE) {
            val path = try {
                WorktreeDisk.pathOf(worktree, file.path)
            } catch (e: IOException) {
                throw DiffRequestProducerException("Skein cannot read ${file.path} in the worktree: ${e.message}")
            }
            LocalFileSystem.getInstance().refreshAndFindFileByNioFile(path) ?: throw DiffRequestProducerException("Skein cannot read ${file.path} in the worktree")
        } else {
            null
        }
        indicator.checkCanceled()
        val left = when (plan.base) {
            BaseView.NONE -> factory.createEmpty()
            BaseView.BYTES, BaseView.LINK -> {
                val bytes = when (val read = reader.blob(worktree, file.base!!.id, ShowChanges.LIMIT)) {
                    is BlobRead.Loaded -> read.bytes
                    is BlobRead.TooLarge -> return MessageDiffRequest(title, ShowChanges.tooLargeAtBase(file, read.size, ShowChanges.LIMIT))
                    is BlobRead.Failed -> throw DiffRequestProducerException("git could not read ${file.from ?: file.path} at the base: ${read.reason}")
                }
                if (plan.base == BaseView.LINK) factory.create(project, String(bytes, StandardCharsets.UTF_8)) else base(factory, bytes, local)
            }
        }
        indicator.checkCanceled()
        val right = when (plan.worktree) {
            WorktreeView.NONE -> factory.createEmpty()
            WorktreeView.LINK -> factory.create(project, (disk as DiskSide.Link).target)
            WorktreeView.FILE -> factory.create(project, local!!)
        }
        return SimpleDiffRequest(title, left, right, baseTitle, worktreeTitle)
    }

    /**
     * The base side from its bytes, read-only, as text or as binary the way the platform decides it: with the worktree's
     * file for its type and encoding when there is one, else by the file's name.
     */
    private fun base(factory: DiffContentFactory, bytes: ByteArray, local: VirtualFile?): DiffContent {
        if (local != null) return factory.createFromBytes(project, bytes, local)
        val name = (file.from ?: file.path).substringAfterLast('/')
        return factory.createFromBytes(project, bytes, FileTypeManager.getInstance().getFileTypeByFileName(name), name)
    }
}

/** A block that only says something: the note past the cap ([ShowChanges.moreNote]). */
private class NoteProducer(private val title: String, private val text: String) : DiffRequestProducer {
    override fun getName(): String = title

    override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest = MessageDiffRequest(title, text)
}

/**
 * The path of a block: the file's own path in the worktree, so the header's icon and the IDE's navigation find the
 * file, named by its [label] ([ChangeList.label]) and with no parent, so the header reads as the label alone.
 */
private class BlockPath(path: String, folder: Boolean, private val label: String) : LocalFilePath(path, folder) {
    override fun getName(): String = label

    override fun getParentPath(): FilePath? = null

    /** By the file itself while it is there, else by its own name: the label would name no type. */
    override fun getFileType(): FileType =
        virtualFile?.let { FileTypeManager.getInstance().getFileTypeByFile(it) } ?: FileTypeManager.getInstance().getFileTypeByFileName(path.substringAfterLast('/'))
}

/**
 * The tab of one worktree's changes: a combined diff file of Skein's own, titled by [title] ([ShowChanges.tabTitle]),
 * whose views show [blocks]. [update] gives it a refreshed list; the views already open get theirs from [WorktreeDiff].
 */
internal class WorktreeChangesFile(key: String, title: String, blocks: List<CombinedBlockProducer>) :
    CombinedDiffVirtualFile(title, "Skein/$key"),
    DiffVirtualFileWithTabName {
    @Volatile
    private var title: String = title

    @Volatile
    private var blocks: List<CombinedBlockProducer> = blocks

    override fun createViewer(project: Project): CombinedDiffComponentProcessor = WorktreeDiff.combinedView(project, blocks)

    override fun getEditorTabName(project: Project, editors: List<FileEditor>): String = title

    fun update(title: String, blocks: List<CombinedBlockProducer>) {
        this.title = title
        this.blocks = blocks
    }
}
