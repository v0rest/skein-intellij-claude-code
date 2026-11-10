package io.github.v0rest.skein.git

import io.github.v0rest.skein.claude.Worktrees
import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.DirectoryIteratorException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.FileSystemException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardOpenOption

sealed interface ExcludeResult {
    /**
     * git ignores the worktrees folder. [lineAdded] when this call appended [Worktrees.EXCLUDE_LINE] to [excludeFile],
     * [guardAdded] when it wrote [guardFile].
     */
    data class Ok(val lineAdded: Boolean, val guardAdded: Boolean, val excludeFile: Path, val guardFile: Path) : ExcludeResult {
        /** This call wrote something. */
        val added: Boolean get() = lineAdded || guardAdded
    }

    /**
     * A link or file stands in the way, git could not be asked, a write failed, or git would still see the folder.
     * [keptGuard] when this call wrote the guard there and kept it next to worktrees already in the folder; the text
     * that shows the failure says so ([io.github.v0rest.skein.ui.WorktreeStart.excludeReason]).
     */
    data class Failed(val reason: String, val keptGuard: Path? = null) : ExcludeResult
}

/**
 * Keeps Claude Code's worktree folders out of git. A worktree is a repository of its own inside the main working tree,
 * so `git status` lists it as untracked and `git add -A` would add it as an embedded repository.
 *
 * The guard is a file `.claude/worktrees/.gitignore` holding `*`: the deepest `.gitignore` wins over every other rule,
 * so while it is there it ignores everything in that folder, itself included, whatever a parent `.gitignore`, the
 * branch checked out or a `.claude` repository of its own says. A `.gitignore` already there is never touched. As a
 * second layer the line [Worktrees.EXCLUDE_LINE] goes into the repository's `info/exclude`, a local file git never
 * commits. Then, before anything is made in the working tree, git is asked what it tracks in the folder, in the
 * repository that holds it (the `.claude` one when `.claude` is a repository of its own, since the outer one never
 * looks inside it) and in any case: a guard git tracks but that is not on disk is refused, so Skein's text never
 * lands on a committed path, and anything else tracked there is refused too. An absent guard is not written while a
 * stash entry holds it either (in any case, and made in any worktree of the repository), since `git stash pop` would
 * then fail on it. Last, the guard, read the way git reads
 * it, must be a regular file holding the one rule `*` and nothing else ([guardHolds]). Asking git instead would not
 * do: the `info/exclude` line answers for the whole folder before git even reads the guard, and no other clone or
 * branch has that line. On a failure a guard this call wrote stays when worktrees already live in the folder, since it
 * changes nothing git tracks, and the failure names it ([ExcludeResult.Failed.keptGuard]); otherwise it goes while it is still exactly as written, and the
 * folders this call made go while they are empty. The `info/exclude` line stays.
 *
 * The guard is an ignored file, so git treats it as expendable: `git clean -X`, `git stash -a` and a switch to a
 * branch that tracks or drops it can remove or replace it. Calling this again puts it back, except while a stash
 * holds it.
 *
 * Skein never writes through a link here and never creates a change git could commit: the guard ignores itself and
 * the folders are empty otherwise. This is the only place Skein writes under a `.git` folder, and there it only ever
 * appends that line. Calls run one at a time in the IDE. Blocking: call it off the EDT. A git or file failure comes
 * back as [ExcludeResult.Failed].
 */
class WorktreeExcludes(private val git: GitRunner) {
    fun ensureExcluded(repository: Path): ExcludeResult = synchronized(LOCK) { ensure(repository) }

    private fun ensure(repository: Path): ExcludeResult {
        val worktrees = Path.of(Worktrees.parentOf(repository.toString()))
        val folders = listOf(worktrees.parent, worktrees)
        // a link would take every write below out of the repository, so nothing is written at all
        for (dir in folders) inTheWay(dir)?.let { return ExcludeResult.Failed(it) }

        val named = git.run(repository, "rev-parse", "--git-path", "info/exclude")
        if (!named.ok) return ExcludeResult.Failed(failure(named))
        val answer = named.stdout.trimEnd('\n', '\r')
        if (answer.isEmpty()) return ExcludeResult.Failed("git named no exclude file")
        // relative at the top of the repository, absolute from a linked worktree
        val file = repository.resolve(answer)
        val lineAdded = when (val line = addLine(file)) {
            is Line.Failed -> return ExcludeResult.Failed(line.reason)
            is Line.Done -> line.added
        }

        // a `.claude` that is a repository of its own (or a submodule) holds the folder: the outer one never looks inside
        val claude = worktrees.parent
        val owner = if (Files.exists(claude.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) claude else repository
        val folder = owner.relativize(worktrees).toString()
        val guardPath = "$folder/${Worktrees.GUARD_NAME}"

        // git is asked before anything is made in the folder, so the guard is never written over a path git tracks.
        // Pathspecs are case-sensitive even where the disk is not, hence `icase`; `--no-literal-pathspecs` keeps a
        // GIT_LITERAL_PATHSPECS in the environment from turning that magic into a name that matches nothing
        val tracked = git.run(owner, *WorktreeGit.NO_FSMONITOR, "--no-literal-pathspecs", "ls-files", "--", ":(icase)$folder")
        if (!tracked.ok) return ExcludeResult.Failed(failure(tracked))
        val (guards, others) = tracked.stdout.lines().filter { it.isNotEmpty() }.partition { it.equals(guardPath, ignoreCase = true) }
        // a guard git tracks but that is not on disk (deleted, or left out by a sparse checkout): Skein's text there
        // would be a change to the committed file
        guards.firstOrNull { !Files.exists(owner.resolve(it), LinkOption.NOFOLLOW_LINKS) }?.let {
            return ExcludeResult.Failed(
                "git tracks ${owner.resolve(it)} but it is missing here; restore that file from git " +
                    "(in a sparse checkout, add ${it.substringBeforeLast('/')} to it) and Skein uses it as it is",
            )
        }
        // anything else git tracks in the folder is in commits already, whatever is ignored from now on
        val trackedOther = others.firstOrNull()?.let { "git already tracks $it" }
        // with no worktree there yet there is nothing to protect, so nothing is made
        if (trackedOther != null && !holdsWorktree(worktrees)) return ExcludeResult.Failed(trackedOther)

        // a guard `git stash -a` took: written back, it would make `git stash pop` fail on it
        val guard = worktrees.resolve(Worktrees.GUARD_NAME)
        if (!Files.exists(guard, LinkOption.NOFOLLOW_LINKS)) inAStash(owner, guardPath)?.let { return ExcludeResult.Failed(it) }

        val made = mutableListOf<Path>()
        for (dir in folders) {
            try {
                if (makeFolder(dir)) made.add(dir)
            } catch (e: IOException) {
                removeEmpty(made)
                return ExcludeResult.Failed("could not create $dir: ${describe(e)}")
            }
        }

        val guardAdded = try {
            writeGuard(guard)
        } catch (e: IOException) {
            removeEmpty(made)
            return ExcludeResult.Failed("could not write $guard: ${describe(e)}")
        }

        fun failed(reason: String): ExcludeResult {
            if (guardAdded && stillAsWritten(guard)) {
                // next to worktrees the guard stays: it changes nothing git tracks, and they could show up without it
                if (holdsWorktree(worktrees)) return ExcludeResult.Failed(reason, keptGuard = guard)
                deleteQuietly(guard)
            }
            removeEmpty(made)
            return ExcludeResult.Failed(reason)
        }

        trackedOther?.let { return failed(it) }

        // whether Skein just wrote it or it was there, the guard must say `*` and nothing else
        val text = try {
            when {
                // git does not read a `.gitignore` that is a link
                Files.isSymbolicLink(guard) -> null
                // a folder or a FIFO there holds no rules, and reading a FIFO would block under the lock
                !Files.isRegularFile(guard, LinkOption.NOFOLLOW_LINKS) ->
                    return failed("$guard is not a regular file, so git reads no rules from it; Skein leaves it as it is")
                else -> String(Files.readAllBytes(guard), StandardCharsets.UTF_8)
            }
        } catch (e: IOException) {
            return failed("could not read $guard: ${describe(e)}")
        }
        return when {
            text != null && guardHolds(text) -> ExcludeResult.Ok(lineAdded, guardAdded, file, guard)
            guardAdded -> failed("$guard must contain only the line * for Skein to keep the worktrees out of git")
            text == null -> failed("the existing file $guard is a link, which git does not read; Skein leaves it as it is")
            else -> failed("the existing file $guard must contain only the line * for Skein to keep the worktrees out of git; Skein leaves it as it is")
        }
    }

    /** Whether [dir] holds a worktree: a folder in it with a `.git` file or folder. False when it cannot be read. */
    private fun holdsWorktree(dir: Path): Boolean =
        try {
            Files.newDirectoryStream(dir).use { entries ->
                entries.any { Files.isDirectory(it, LinkOption.NOFOLLOW_LINKS) && Files.exists(it.resolve(".git"), LinkOption.NOFOLLOW_LINKS) }
            }
        } catch (_: IOException) {
            false
        } catch (_: DirectoryIteratorException) {
            false
        }

    /**
     * Why the absent guard ([guardPath] in [owner]) must not be written: a stash entry holds it among its untracked or
     * ignored files (the third parent), or git could not say. Null when none does. `refs/stash` is shared by every
     * worktree of the repository, so a stash made in another one counts too.
     */
    private fun inAStash(owner: Path, guardPath: String): String? {
        val entries = git.run(owner, "stash", "list", "--format=%H%x09%gd")
        if (!entries.ok) return failure(entries)
        for (entry in entries.stdout.lines().filter(String::isNotBlank)) {
            val sha = entry.substringBefore('\t').trim()
            val name = entry.substringAfter('\t', sha).trim()
            val untracked = git.run(owner, "rev-parse", "-q", "--verify", "$sha^3")
            if (untracked.ran && untracked.exitCode == 1) continue // no untracked part
            if (!untracked.ok) return "could not read $name in $owner: ${failure(untracked)}"
            when (val found = lookUp(owner, "$sha^3", guardPath.split('/'))) {
                is Lookup.Found -> return "$name in $owner holds the guard ${found.path}; Skein writes the guard again once no stash holds one, and popping that stash brings it back, while dropping it would also lose any other files it holds"
                is Lookup.Unknown -> return "could not read $name in $owner: ${found.reason}"
                Lookup.Missing -> {}
            }
        }
        return null
    }

    private sealed interface Lookup {
        data class Found(val path: String) : Lookup

        data object Missing : Lookup

        data class Unknown(val reason: String) : Lookup
    }

    /**
     * [parts] under the tree [tree] one level at a time, each name in any case: a stash keeps the case the disk had,
     * and a tree path is compared by case. [found] is the path matched so far.
     */
    private fun lookUp(owner: Path, tree: String, parts: List<String>, found: List<String> = emptyList()): Lookup {
        if (parts.isEmpty()) return Lookup.Found(found.joinToString("/"))
        val listed = git.run(owner, *WorktreeGit.NO_FSMONITOR, "ls-tree", "--name-only", if (found.isEmpty()) tree else "$tree:${found.joinToString("/")}")
        if (!listed.ok) return Lookup.Unknown(failure(listed))
        for (name in listed.stdout.lines().filter { it.equals(parts.first(), ignoreCase = true) }) {
            val deeper = lookUp(owner, tree, parts.drop(1), found + name)
            if (deeper != Lookup.Missing) return deeper
        }
        return Lookup.Missing
    }

    /** Why [dir] must not be written into: it is a link, or something other than a folder; null when it is fine or absent. */
    private fun inTheWay(dir: Path): String? = when {
        Files.isSymbolicLink(dir) -> "$dir is a link, and Skein writes nothing through it"
        Files.exists(dir, LinkOption.NOFOLLOW_LINKS) && !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) -> "$dir is not a folder"
        else -> null
    }

    private sealed interface Line {
        data class Done(val added: Boolean) : Line

        data class Failed(val reason: String) : Line
    }

    /** Appends the line to [file] unless a line says it already; never rewrites what is there. */
    private fun addLine(file: Path): Line {
        val current = try {
            String(Files.readAllBytes(file), StandardCharsets.UTF_8)
        } catch (_: NoSuchFileException) {
            ""
        } catch (e: IOException) {
            return Line.Failed("could not read $file: ${describe(e)}")
        }
        val next = withExcludeLine(current)
        if (next == current) return Line.Done(added = false)
        return try {
            Files.createDirectories(file.parent)
            Files.writeString(file, next.substring(current.length), StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND)
            Line.Done(added = true)
        } catch (e: IOException) {
            Line.Failed("could not write $file: ${describe(e)}")
        }
    }

    /** True when this call made [dir]; false when a folder was there already. */
    private fun makeFolder(dir: Path): Boolean {
        if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) return false
        return try {
            Files.createDirectory(dir)
            true
        } catch (e: FileAlreadyExistsException) {
            // made meanwhile by someone else is fine; anything else of that name is not
            if (Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS)) false else throw e
        }
    }

    /**
     * True when this call wrote [guard]; a file already there, or one made meanwhile, is never touched. A write that
     * fails after the file was made takes that file back.
     */
    private fun writeGuard(guard: Path): Boolean {
        val out = try {
            Files.newOutputStream(guard, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)
        } catch (_: FileAlreadyExistsException) {
            return false
        }
        try {
            out.use { it.write(Worktrees.GUARD_TEXT.toByteArray(StandardCharsets.UTF_8)) }
        } catch (e: IOException) {
            deleteQuietly(guard)
            throw e
        }
        return true
    }

    private fun stillAsWritten(guard: Path): Boolean =
        try {
            Files.isRegularFile(guard, LinkOption.NOFOLLOW_LINKS) && String(Files.readAllBytes(guard), StandardCharsets.UTF_8) == Worktrees.GUARD_TEXT
        } catch (_: IOException) {
            false
        }

    /** Deletes [made], deepest first; a folder that is no longer empty is left as it is, and so is everything above it. */
    private fun removeEmpty(made: List<Path>) {
        for (dir in made.asReversed()) if (!deleteQuietly(dir)) return
    }

    /** The plain delete, which refuses a folder that is not empty; false when [path] stays. */
    private fun deleteQuietly(path: Path): Boolean =
        try {
            Files.delete(path)
            true
        } catch (_: IOException) {
            false
        }

    private fun failure(result: GitResult): String = if (result.ran) result.reason() else "git could not be run: " + result.reason()

    /** What went wrong, without repeating the path the caller names. */
    private fun describe(e: IOException): String =
        if (e is FileSystemException) e.reason ?: e.javaClass.simpleName else e.message ?: e.javaClass.simpleName

    companion object {
        /**
         * Whether a `.gitignore` with [text] ignores everything in its folder, itself included, whatever any other rule
         * says, for as long as it is on disk: read the way git reads it (a `\r` before the line break and unescaped
         * trailing spaces dropped, blank lines and `#` comments skipped), its one rule is `*`. Anything more could
         * un-ignore something, the guard itself included.
         */
        fun guardHolds(text: String): Boolean =
            text.split('\n').map { trimTrailingSpaces(it.removeSuffix("\r")) }.filter { it.isNotEmpty() && !it.startsWith("#") } == listOf("*")

        /** [line] without its trailing spaces, unless a backslash escapes one (that space then stays). */
        private fun trimTrailingSpaces(line: String): String {
            var end = line.length
            while (end > 0 && line[end - 1] == ' ') end--
            if (end == line.length) return line
            // an odd run of backslashes before the spaces escapes the first space
            var slashes = 0
            while (end - 1 - slashes >= 0 && line[end - 1 - slashes] == '\\') slashes++
            return if (slashes % 2 == 1) line.substring(0, end + 1) else line.substring(0, end)
        }

        /** Every call in the IDE, one at a time: one call must not take back folders another one is checking. */
        private val LOCK = Any()

        /**
         * [current] with [Worktrees.EXCLUDE_LINE] on a line of its own at the end, or [current] itself when a line already
         * says it as git reads it: git drops a `\r` before the line break and trailing spaces, nothing else. Only ever
         * adds to the end, so it can be appended.
         */
        fun withExcludeLine(current: String): String {
            if (current.split('\n').any { it.removeSuffix("\r").trimEnd(' ') == Worktrees.EXCLUDE_LINE }) return current
            val separator = if (current.isEmpty() || current.endsWith("\n")) "" else "\n"
            return current + separator + Worktrees.EXCLUDE_LINE + "\n"
        }
    }
}
