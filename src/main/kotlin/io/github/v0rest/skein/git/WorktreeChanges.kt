package io.github.v0rest.skein.git

import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path

/** How a file differs between a worktree's base and the worktree as it is on disk. */
enum class ChangeKind { ADDED, MODIFIED, DELETED, RENAMED, COPIED, UNTRACKED }

/**
 * A file as the merge-base has it: git's object [id] and its [mode] as git writes it, `100644` or `100755` for a file,
 * [LINK] for a link (the object holds where it points) and [SUBMODULE] for a submodule (a commit of another repository).
 */
data class BaseObject(val id: String, val mode: String) {
    val link: Boolean get() = mode == LINK
    val submodule: Boolean get() = mode == SUBMODULE

    companion object {
        const val LINK = "120000"
        const val SUBMODULE = "160000"
    }
}

/**
 * One file of Show Changes: [path] is where it is in the worktree (where it was, for a deleted one), [from] where it was
 * at the merge-base when that is another path (a rename or a copy), and [base] the file at the merge-base, null when the
 * merge-base has none (an added or untracked file).
 */
data class ChangedFile(val kind: ChangeKind, val path: String, val from: String? = null, val base: BaseObject? = null)

/** The files that differ between a worktree's merge-base and its folder on disk, from git's own lists. Pure. */
object ChangeList {
    private const val NO_MODE = "000000"

    /**
     * `git diff --raw -z <merge-base>`: per file a header
     * `:<old mode> <new mode> <old object> <new object> <letter>[score]`, then its path, or the old and the new path for
     * a rename (`R`) or a copy (`C`), each field ended by a NUL. Only the
     * letter says what happened: the new object is all zeros for every file git reads from the working tree, changed
     * ones included. A type change (`T`) and any letter git may add later count as modified. With `-z` git does not
     * quote paths, so a space, a line break or a leading colon in one stays, and [text] is git's output decoded as
     * UTF-8, which keeps a name that is UTF-8 on disk as it is there. An entry cut short ends the list.
     */
    fun parseRaw(text: String): List<ChangedFile> {
        val fields = text.split('\u0000')
        val files = ArrayList<ChangedFile>()
        var i = 0
        while (i < fields.size) {
            val header = fields[i++]
            if (header.isEmpty()) continue
            val parts = header.removePrefix(":").split(' ')
            if (!header.startsWith(":") || parts.size < 5 || parts[4].isEmpty()) break
            val (oldMode, _, oldObject) = parts
            val letter = parts[4][0]
            val paths = if (letter == 'R' || letter == 'C') 2 else 1
            if (i + paths > fields.size || fields.subList(i, i + paths).any { it.isEmpty() }) break
            val path = fields[i + paths - 1]
            val from = if (paths == 2) fields[i] else null
            i += paths
            val base = if (oldMode == NO_MODE || oldObject.all { it == '0' }) null else BaseObject(oldObject, oldMode)
            val kind = when (letter) {
                'A' -> ChangeKind.ADDED
                'D' -> ChangeKind.DELETED
                'R' -> ChangeKind.RENAMED
                'C' -> ChangeKind.COPIED
                else -> ChangeKind.MODIFIED
            }
            files += ChangedFile(kind, path, from, base)
        }
        return files
    }

    /**
     * `git ls-files --others --exclude-standard -z`: the untracked files that no ignore rule hides, one by one, inside
     * untracked folders too. A folder with a repository of its own comes as one entry ending in `/`.
     */
    fun parseUntracked(text: String): List<String> = text.split('\u0000').filter { it.isNotEmpty() }

    /**
     * [tracked] and [untracked] as one list sorted by path. A file git lists as deleted and as untracked was taken out
     * of the index but is still on disk (`git rm --cached`), so it is one modified file.
     */
    fun of(tracked: List<ChangedFile>, untracked: List<String>): List<ChangedFile> {
        val deleted = tracked.filter { it.kind == ChangeKind.DELETED }.associateBy { it.path }
        val back = untracked.filterTo(HashSet()) { it in deleted }
        val files = tracked.filterNot { it.kind == ChangeKind.DELETED && it.path in back } +
            untracked.map { path -> deleted[path]?.let { ChangedFile(ChangeKind.MODIFIED, path, base = it.base) } ?: ChangedFile(ChangeKind.UNTRACKED, path) }
        return files.sortedBy { it.path }
    }

    /** How the diff window names [file]: its path, and what happened to it unless it was just modified. */
    fun label(file: ChangedFile): String = when (file.kind) {
        ChangeKind.MODIFIED -> file.path
        ChangeKind.ADDED -> "${file.path} (added)"
        ChangeKind.UNTRACKED -> "${file.path} (untracked)"
        ChangeKind.DELETED -> "${file.path} (deleted)"
        ChangeKind.RENAMED -> "${file.path} (renamed from ${file.from})"
        ChangeKind.COPIED -> "${file.path} (copied from ${file.from})"
    }
}

/** What Show Changes found in a worktree, or why it shows nothing. */
sealed interface ChangesLookup {
    /** The worktree's [base], the commit the two share ([mergeBase]) and the [files] that differ from that commit. */
    data class Found(val base: Base, val mergeBase: String, val files: List<ChangedFile>) : ChangesLookup

    /** Why there is nothing to show, for a person to read after "cannot show the changes": the folder is gone, say. */
    data class Refused(val reason: String) : ChangesLookup
}

/** A file's content at the merge-base, read as bytes, or why not. */
sealed interface BlobRead {
    class Loaded(val bytes: ByteArray) : BlobRead

    /** Bigger than Show Changes reads: never loaded. */
    data class TooLarge(val size: Long) : BlobRead

    data class Failed(val reason: String) : BlobRead
}

/**
 * Reads what Show Changes shows of a worktree: every file that differs between the commit its branch shares with its
 * base (the merge-base, so commits the base got later do not count) and the worktree as it is on disk, committed,
 * staged, unstaged and untracked alike, and later, one at a time, a file's bytes at that commit. The base is the one the
 * row's counts use ([WorktreeReader.base]). Only for a folder git lists as a worktree of the repository: in any other
 * folder git would answer for the repository around it. Every call goes through [git], one after the other, and
 * nothing is written. Never throws for git's sake. Blocking: off the EDT.
 */
class WorktreeChangesReader(private val git: GitRunner, private val exists: (Path) -> Boolean = { Files.exists(it) }) {
    private val reader = WorktreeReader(git, exists)
    private val worktrees = WorktreeGit(git)

    /**
     * The changes of [worktree], a worktree of [repository] (both with their links resolved, as git lists them), against
     * the branch [recorded] for its thread or else the repository's base.
     */
    fun read(repository: Path, worktree: Path, recorded: String?): ChangesLookup {
        if (!exists(worktree)) return ChangesLookup.Refused(GONE)
        when (val listing = worktrees.listing(repository)) {
            is WorktreeListing.Failed -> return ChangesLookup.Refused(listing.reason)
            is WorktreeListing.Listed -> {
                val entry = listing.worktrees.firstOrNull { it.path.trimEnd('/') == worktree.toString() }
                if (entry == null || entry.prunable || entry.bare) return ChangesLookup.Refused(notListed(worktree))
            }
        }
        when (val operation = reader.operation(worktree)) {
            is WorktreeReader.Probe.Failed -> return ChangesLookup.Refused(operation.reason)
            is WorktreeReader.Probe.Running -> return ChangesLookup.Refused(inProgress(operation.operation))
            WorktreeReader.Probe.None -> Unit
        }
        val base = when (val found = reader.base(worktree, recorded) { reader.fallbackBase(repository) }) {
            is BaseLookup.Failed -> return ChangesLookup.Refused(found.reason)
            BaseLookup.NoBase -> return ChangesLookup.Refused(NO_BASE)
            is BaseLookup.Found -> found.base
        }
        val shared = git.run(worktree, "merge-base", base.ref, "HEAD")
        // exit 1 and nothing said: the two have no commit in common
        if (shared.ran && shared.exitCode == 1 && shared.stderr.isBlank()) return ChangesLookup.Refused(noCommonCommit(base.name))
        if (!shared.ok) return ChangesLookup.Refused(failure(shared))
        val mergeBase = shared.stdout.trim().takeIf { it.isNotEmpty() && it.none(Char::isWhitespace) } ?: return ChangesLookup.Refused(NO_MERGE_BASE)
        // as bytes: a text capture folds a CRLF and lets a backspace eat a character, both real in a name
        // no colour and no external diff program, whatever the repository's or the user's configuration says
        val diff = git.bytes(worktree, *WorktreeGit.NO_FSMONITOR, "diff", "--no-color", "--no-ext-diff", "--raw", "-z", "-M", "--no-abbrev", mergeBase, "--")
        if (!diff.ok) return ChangesLookup.Refused(failure(diff.result))
        val untracked = git.bytes(worktree, *WorktreeGit.NO_FSMONITOR, "ls-files", "--others", "--exclude-standard", "-z")
        if (!untracked.ok) return ChangesLookup.Refused(failure(untracked.result))
        val files = ChangeList.of(ChangeList.parseRaw(utf8(diff)), ChangeList.parseUntracked(utf8(untracked)))
        return ChangesLookup.Found(base, mergeBase, files)
    }

    /**
     * The bytes of git's object [id] (a [BaseObject]), read in [worktree]: first its size, and only when that is at most
     * [limit] its content, as git stored it.
     */
    fun blob(worktree: Path, id: String, limit: Long): BlobRead {
        val sized = git.run(worktree, "cat-file", "-s", id)
        if (!sized.ok) return BlobRead.Failed(failure(sized))
        val size = sized.stdout.trim().toLongOrNull() ?: return BlobRead.Failed("git gave no size")
        if (size > limit) return BlobRead.TooLarge(size)
        val content = git.bytes(worktree, "cat-file", "blob", id)
        if (!content.ok) return BlobRead.Failed(failure(content.result))
        if (content.bytes.size.toLong() != size) return BlobRead.Failed("git gave ${content.bytes.size} of $size bytes")
        return BlobRead.Loaded(content.bytes)
    }

    /** git's `-z` output as text: the names in it are the bytes on disk, which git leaves to be UTF-8. */
    private fun utf8(output: GitBytes): String = String(output.bytes, StandardCharsets.UTF_8)

    /** Why [result] failed: git's reason, that it did not finish in time, or that it could not be started. */
    private fun failure(result: GitResult): String = if (result.ran || result.timedOut) result.reason() else "git could not be run: " + result.reason()

    companion object {
        const val GONE = "its folder is gone"
        const val NO_BASE = "there is no branch to compare it with - none of origin/HEAD, origin/main, origin/master, main and master exists"
        const val NO_MERGE_BASE = "git named no commit in common"

        fun notListed(worktree: Path): String = "git does not list $worktree as a worktree"

        fun noCommonCommit(base: String): String = "it has no commit in common with $base"

        /** "a rebase is in progress there - finish or abort it first", from the operation's own sentence. */
        fun inProgress(operation: Operation): String = operation.described.replaceFirstChar { it.lowercase() } + " there - finish or abort it first"
    }
}
