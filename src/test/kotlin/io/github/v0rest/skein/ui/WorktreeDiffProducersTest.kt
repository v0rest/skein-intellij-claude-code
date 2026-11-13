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
import org.junit.Test
import java.lang.reflect.Proxy
import java.nio.file.Path

/**
 * The producers Show Changes hands the diff window: each one's name is the full title, since the platform names its
 * loading and error pages, and so the tab while a file loads or fails, after the producer.
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
    fun eachProducerIsNamedByItsFullTitle() {
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
            listOf(
                "task-old · 1/3 · docs/new-idea.md (untracked)",
                "task-old · 2/3 · src/b.c (renamed from src/a.c)",
                "task-old · 3/3 · src/c.c",
            ),
            producers.map { it.name },
        )
    }

    /** Past the cap the chain ends with a note naming how many more, numbered as its last entry, and nothing is read for it. */
    @Test
    fun pastTheCapTheLastEntryIsANote() {
        val files = (1..5).map { ChangedFile(ChangeKind.UNTRACKED, "f$it.c") }
        val found = ChangesLookup.Found(Base("main", "refs/heads/main"), "7ee3119", files)
        val producers = WorktreeDiff.producers(project, WorktreeChangesReader(git), Path.of("/w/acme/.claude/worktrees/task-old"), "task-old", found, max = 3)
        assertEquals(
            listOf(
                "task-old · 1/4 · f1.c (untracked)",
                "task-old · 2/4 · f2.c (untracked)",
                "task-old · 3/4 · f3.c (untracked)",
                "task-old · 4/4 · … and 2 more",
            ),
            producers.map { it.name },
        )
        val note = producers.last().process(UserDataHolderBase(), indicator) as MessageDiffRequest
        assertEquals("task-old · 4/4 · … and 2 more", note.title)
        assertEquals(ShowChanges.moreNote(2, 3), note.message)
    }
}
