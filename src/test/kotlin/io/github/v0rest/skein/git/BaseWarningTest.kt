package io.github.v0rest.skein.git

import io.github.v0rest.skein.claude.BaseRef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Path

class BaseWarningTest {
    private fun counts(ahead: Int, behind: Int, shallow: Boolean = false) = UpstreamCounts("origin/main", ahead, behind, shallow)

    @Test
    fun parsesGitsLeftRightCount() {
        assertEquals(Pair(2, 3), BaseWarning.parseCounts("2\t3\n"))
        assertEquals(Pair(0, 0), BaseWarning.parseCounts("0 0"))
        assertNull(BaseWarning.parseCounts(""))
        assertNull(BaseWarning.parseCounts("1"))
        assertNull(BaseWarning.parseCounts("a\tb"))
        assertNull(BaseWarning.parseCounts("-1\t2"))
    }

    @Test
    fun levelOrAheadOnlyIsQuiet() {
        assertNull(BaseWarning.decide("main", counts(0, 0)))
        assertNull(BaseWarning.decide("main", counts(4, 0)))
    }

    @Test
    fun noUpstreamOrNoBranchIsQuiet() {
        assertNull(BaseWarning.decide("main", null))
        assertNull(BaseWarning.decide(null, counts(0, 5)))
    }

    @Test
    fun headStartsFromTheLocalBranch() {
        assertTrue(BaseWarning.startsFromLocalBranch(BaseRef.HEAD) { true })
    }

    @Test
    fun aFreshBaseWithAnOriginStartsFromTheRemote() {
        assertFalse(BaseWarning.startsFromLocalBranch(BaseRef.FRESH) { true })
        var asked = false
        BaseWarning.startsFromLocalBranch(BaseRef.HEAD) { asked = true; true }
        assertFalse("head needs no remote check", asked)
    }

    @Test
    fun aFreshBaseWithoutAnOriginFallsBackToTheLocalBranch() {
        assertTrue(BaseWarning.startsFromLocalBranch(BaseRef.FRESH) { false })
    }

    @Test
    fun behindSaysSoAndNamesTheLastFetch() {
        val text = BaseWarning.decide("main", counts(0, 3))
        assertEquals("main is 3 commits behind origin/main as of your last fetch, and the worktree starts from main as it is now", text)
    }

    @Test
    fun oneCommitIsSingular() {
        assertTrue(BaseWarning.decide("main", counts(0, 1))!!.contains("1 commit behind"))
    }

    @Test
    fun divergedSaysBothCounts() {
        val text = BaseWarning.decide("feature", UpstreamCounts("origin/feature", 2, 3))
        assertEquals("feature has diverged from origin/feature, 2 commits ahead and 3 behind as of your last fetch, and the worktree starts from feature as it is now", text)
    }

    @Test
    fun aShallowRepositoryGetsNoNumbers() {
        val text = BaseWarning.decide("main", counts(1, 1, shallow = true))!!
        assertEquals("main differs from origin/main as of your last fetch, the shallow history cannot tell by how much, and the worktree starts from main as it is now", text)
        assertNull(BaseWarning.decide("main", counts(2, 0, shallow = true)))
    }

    private val repo = Path.of("/w/acme-wt")
    private val main = Path.of("/w/acme")

    @Test
    fun theModeIsReadFromTheMainCheckoutRoot() {
        var read: Path? = null
        BaseWarning.compute(repo, { main }, { read = it; BaseRef.FRESH }, { true }, { "main" }, { counts(0, 2) })
        assertEquals(main, read)
    }

    @Test
    fun aFreshBaseWithAnOriginAsksNeitherTheBranchNorTheCounts() {
        var branchAsked = false
        var countsAsked = false
        val text = BaseWarning.compute(repo, { main }, { BaseRef.FRESH }, { true }, { branchAsked = true; "main" }, { countsAsked = true; counts(0, 2) })
        assertNull(text)
        assertFalse(branchAsked)
        assertFalse(countsAsked)
    }

    @Test
    fun aHeadBaseSkipsTheOriginCheckAndWarns() {
        var originAsked = false
        val text = BaseWarning.compute(repo, { main }, { BaseRef.HEAD }, { originAsked = true; true }, { "main" }, { counts(0, 2) })
        assertFalse(originAsked)
        assertTrue(text!!.startsWith("main is 2 commits behind origin/main"))
    }

    @Test
    fun aFreshBaseWithoutAnOriginWarnsLikeHead() {
        assertTrue(BaseWarning.compute(repo, { main }, { BaseRef.FRESH }, { false }, { "main" }, { counts(0, 2) })!!.contains("behind"))
    }
}
