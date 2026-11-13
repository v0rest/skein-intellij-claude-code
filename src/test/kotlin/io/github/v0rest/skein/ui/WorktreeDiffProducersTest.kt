package io.github.v0rest.skein.ui

import com.intellij.openapi.project.Project
import io.github.v0rest.skein.git.Base
import io.github.v0rest.skein.git.BaseObject
import io.github.v0rest.skein.git.ChangeKind
import io.github.v0rest.skein.git.ChangedFile
import io.github.v0rest.skein.git.ChangesLookup
import io.github.v0rest.skein.git.GitResult
import io.github.v0rest.skein.git.GitRunner
import io.github.v0rest.skein.git.WorktreeChangesReader
import com.intellij.diff.requests.MessageDiffRequest
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.util.UserDataHolderBase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Path

/**
 * The producers Show Changes hands the combined view: each one named by the file's label, the block's title, and none
 * ever throwing, since the combined view loads a batch of blocks in one go and a throw would leave the rest loading.
 */
class WorktreeDiffProducersTest {
    /** A project nothing here may touch: naming a producer reads nothing. */
    private val project = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Project::class.java)) { _, method, _ ->
        throw UnsupportedOperationException(method.name)
    } as Project

    /** A progress that is never cancelled, with no application behind it. */
    private val indicator = Proxy.newProxyInstance(javaClass.classLoader, arrayOf(ProgressIndicator::class.java)) { _, method, _ ->
        if (method.returnType == java.lang.Boolean.TYPE) false else null
    } as ProgressIndicator

    private val git = object : GitRunner {
        override fun run(dir: Path, vararg args: String): GitResult = throw UnsupportedOperationException(args.joinToString(" "))
    }

    @Test
    fun eachProducerIsNamedByTheFilesLabel() {
        val blob = BaseObject("78981922613b2afb6025042ff6bd878ac1994e85", "100644")
        val found = ChangesLookup.Found(
            Base("main", "refs/heads/main"),
            "7ee31195368ab795369f35d97eaff4ac19a71233",
            listOf(
                ChangedFile(ChangeKind.UNTRACKED, "docs/new-idea.md"),
                ChangedFile(ChangeKind.RENAMED, "src/b.c", "src/a.c", blob),
                ChangedFile(ChangeKind.MODIFIED, "src/c.c", base = blob),
            ),
        )
        val producers = WorktreeDiff.producers(project, WorktreeChangesReader(git), Path.of("/w/acme/.claude/worktrees/task-old"), "task-old", found)
        assertEquals(
            listOf("docs/new-idea.md (untracked)", "src/b.c (renamed from src/a.c)", "src/c.c"),
            producers.map { it.name },
        )
    }

    /** A worktree folder gone since the list was read: a note in its block, never an exception. */
    @Test
    fun aFailureIsANoteNotAnException() {
        val found = ChangesLookup.Found(Base("main", "refs/heads/main"), "7ee3119", listOf(ChangedFile(ChangeKind.UNTRACKED, "a.c")))
        val gone = Path.of("/nonexistent-skein-test/.claude/worktrees/task-old")
        val request = WorktreeDiff.producers(project, WorktreeChangesReader(git), gone, "task-old", found).single()
            .process(UserDataHolderBase(), indicator)
        assertTrue(request.toString(), request is MessageDiffRequest)
        assertEquals("a.c (untracked)", request.title)
        assertTrue((request as MessageDiffRequest).message, request.message.startsWith("Skein cannot read a.c in the worktree"))
    }
}
