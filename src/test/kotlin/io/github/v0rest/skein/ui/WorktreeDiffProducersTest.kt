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
}
