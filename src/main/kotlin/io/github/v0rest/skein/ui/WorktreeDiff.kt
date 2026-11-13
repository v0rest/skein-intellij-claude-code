package io.github.v0rest.skein.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffDialogHints
import com.intellij.diff.DiffManager
import com.intellij.diff.chains.DiffRequestChain
import com.intellij.diff.chains.DiffRequestProducer
import com.intellij.diff.chains.DiffRequestProducerException
import com.intellij.diff.chains.SimpleDiffRequestChain
import com.intellij.diff.contents.DiffContent
import com.intellij.diff.requests.DiffRequest
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.ide.trustedProjects.TrustedProjects
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.EDT
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.progress.ProcessCanceledException
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolder
import com.intellij.openapi.util.text.StringUtil
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

    /** The thread Show Changes is for in the thread menu: the one selected thread, when it is a worktree thread. */
    fun worktreeThread(selected: List<SkeinThread>): SkeinThread? = selected.singleOrNull()?.takeIf { it.worktree?.let(Worktrees::isValidName) == true }
}

/**
 * Show Changes: the IDE's diff viewer over every file that differs between a worktree's merge-base with its base and the
 * worktree as it is on disk, one entry per file in one window ([WorktreeChangesReader]). The list is read off the EDT
 * with a progress in the status bar, and a refusal or an empty list is a balloon instead of an empty window. Each file
 * is read only when the viewer gets to it, by the platform's own background loading ([FileProducer]). The base side is
 * read-only, the worktree side is the file itself, so the IDE edits and saves it as any file. Skein writes nothing and
 * registers no VCS root for this: git runs through Skein's own runner, without optional locks and with `core.fsmonitor`
 * off, and only in a trusted project, since git can start programs a repository's configuration names.
 */
@Service(Service.Level.PROJECT)
class WorktreeDiff(private val project: Project, private val scope: CoroutineScope) {
    private val reader = WorktreeChangesReader(NoOptionalLocks(ProcessGitRunner(GIT_TIMEOUT_MILLIS)))

    /** The worktrees whose Show Changes is under way, so a quick second click does not open a second window. */
    private val busy: MutableSet<String> = ConcurrentHashMap.newKeySet()

    private sealed interface Outcome {
        data class Said(val text: String, val type: NotificationType) : Outcome

        class Diff(val chain: DiffRequestChain) : Outcome
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
                val outcome = withBackgroundProgress(project, ShowChanges.progress(name)) { withContext(Dispatchers.IO) { look(repository, name, recorded) } }
                when (outcome) {
                    is Outcome.Said -> balloon(outcome.text, outcome.type)
                    is Outcome.Diff -> withContext(Dispatchers.EDT) {
                        if (!project.isDisposed) DiffManager.getInstance().showDiff(project, outcome.chain, DiffDialogHints.DEFAULT)
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

    /** What to show for the worktree: the diff, or a balloon's words. Blocking, off the EDT. */
    private fun look(repository: String, name: String, recorded: String?): Outcome {
        if (!TrustedProjects.isProjectTrusted(project)) return Outcome.Said(ShowChanges.untrusted(name), NotificationType.WARNING)
        val real = GitToplevel.resolved(repository) ?: return Outcome.Said(ShowChanges.refused(name, "its repository $repository is gone"), NotificationType.WARNING)
        if (ClaudeHome.default().holdsWorktreesOf(Path.of(real))) return Outcome.Said(ShowChanges.refused(name, WorktreeClose.CLAUDE_HOME), NotificationType.WARNING)
        val worktree = Path.of(Worktrees.dirOf(real, name))
        return when (val found = reader.read(Path.of(real), worktree, recorded)) {
            is ChangesLookup.Refused -> Outcome.Said(ShowChanges.refused(name, found.reason), NotificationType.WARNING)
            is ChangesLookup.Found ->
                if (found.files.isEmpty()) {
                    Outcome.Said(ShowChanges.noChanges(name, found.base.name), NotificationType.INFORMATION)
                } else {
                    val left = { file: ChangedFile -> ShowChanges.baseTitle(found.base.name, found.mergeBase, file) }
                    Outcome.Diff(SimpleDiffRequestChain.fromProducers(found.files.map { FileProducer(project, reader, worktree, it, left(it), ShowChanges.worktreeTitle(name)) }))
                }
        }
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

        fun getInstance(project: Project): WorktreeDiff = project.service()
    }
}

/**
 * One file of Show Changes, read when the diff viewer gets to it, on the platform's background thread: what the file is
 * on disk inside the worktree ([WorktreeDisk]), then its bytes at the merge-base through git, each side only up to
 * [ShowChanges.LIMIT].
 */
private class FileProducer(
    private val project: Project,
    private val reader: WorktreeChangesReader,
    private val worktree: Path,
    private val file: ChangedFile,
    private val baseTitle: String,
    private val worktreeTitle: String,
) : DiffRequestProducer {
    private val title = ChangeList.label(file)

    override fun getName(): String = title

    @Throws(DiffRequestProducerException::class)
    override fun process(context: UserDataHolder, indicator: ProgressIndicator): DiffRequest {
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
