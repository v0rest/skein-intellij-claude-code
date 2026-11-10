package io.github.v0rest.skein.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Path

class GitToplevelTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun findsTheNearestFolderWithGit() {
        val repo = tmp.root.toPath().resolve("repo")
        val deep = Files.createDirectories(repo.resolve("svc/api"))
        Files.createDirectories(repo.resolve(".git"))
        assertEquals(repo, GitToplevel.of(deep))
    }

    @Test
    fun aGitFileCountsLikeAGitFolder() {
        val repo = tmp.root.toPath().resolve("repo")
        Files.createDirectories(repo)
        Files.writeString(repo.resolve(".git"), "gitdir: /w/acme/.git/worktrees/feat-x\n")
        assertEquals(repo, GitToplevel.of(repo))
    }

    /** Resume and restore only log for a repository folder that is gone or no longer a repository. */
    @Test
    fun aRepositoryIsAFolderWithAGitEntry() {
        val repo = tmp.root.toPath().resolve("repo")
        Files.createDirectories(repo.resolve(".git"))
        val linked = Files.createDirectories(tmp.root.toPath().resolve("linked"))
        Files.writeString(linked.resolve(".git"), "gitdir: /w/acme/.git/worktrees/feat-x\n")
        val plain = Files.createDirectories(tmp.root.toPath().resolve("plain"))
        assertEquals(true, GitToplevel.isRepository(repo))
        assertEquals(true, GitToplevel.isRepository(linked))
        assertEquals(false, GitToplevel.isRepository(plain))
        assertEquals(false, GitToplevel.isRepository(tmp.root.toPath().resolve("gone")))
    }

    /** The check is bounded to the temp folder, so a `.git` somewhere above the machine's temp path cannot leak in. */
    @Test
    fun noneOutsideARepository() {
        val root = tmp.root.toPath()
        val deep = Files.createDirectories(root.resolve("a/b"))
        assertNull(GitToplevel.of(deep) { it.startsWith(root) && Files.exists(it.resolve(".git")) })
    }
}
