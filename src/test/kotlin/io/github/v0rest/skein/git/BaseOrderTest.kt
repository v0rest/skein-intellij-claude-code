package io.github.v0rest.skein.git

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BaseOrderTest {
    private fun refs(vararg present: String): (Base) -> RefState = { if (it.ref in present) RefState.Found else RefState.Missing }

    @Test
    fun theRecordedBranchComesFirstThenTheRemoteDefaultThenTheUsualNames() {
        assertEquals(
            listOf("refs/heads/develop", "refs/remotes/origin/trunk", "refs/remotes/origin/main", "refs/remotes/origin/master", "refs/heads/main", "refs/heads/master"),
            BaseOrder.candidates(recorded = "develop", originHead = "origin/trunk").map { it.ref },
        )
        assertEquals(
            listOf("origin/main", "origin/master", "main", "master"),
            BaseOrder.candidates(recorded = null, originHead = null).map { it.name },
        )
    }

    @Test
    fun aNameIsTriedOnce() {
        assertEquals(
            listOf("refs/heads/main", "refs/remotes/origin/main", "refs/remotes/origin/master", "refs/heads/master"),
            BaseOrder.candidates(recorded = "main", originHead = "origin/main").map { it.ref },
        )
    }

    @Test
    fun theFirstThatExistsWins() {
        assertEquals(BaseLookup.Found(Base("develop", "refs/heads/develop")), BaseOrder.first(BaseOrder.candidates("develop", null), refs("refs/heads/develop", "refs/heads/main")))
        // the recorded branch is gone: the remote default stands in
        assertEquals(
            BaseLookup.Found(Base("origin/trunk", "refs/remotes/origin/trunk")),
            BaseOrder.first(BaseOrder.candidates("develop", "origin/trunk"), refs("refs/remotes/origin/trunk", "refs/heads/main")),
        )
        assertEquals(BaseLookup.Found(Base("origin/master", "refs/remotes/origin/master")), BaseOrder.first(BaseOrder.candidates(null, null), refs("refs/remotes/origin/master", "refs/heads/main")))
        assertEquals(BaseLookup.Found(Base("master", "refs/heads/master")), BaseOrder.first(BaseOrder.candidates(null, null), refs("refs/heads/master")))
    }

    @Test
    fun noneThereIsNoBase() {
        assertEquals(BaseLookup.NoBase, BaseOrder.first(BaseOrder.candidates("develop", null), refs()))
    }

    /** Skipping ahead past a git failure would count against another branch without a word. */
    @Test
    fun aGitFailureEndsTheSearch() {
        val tried = mutableListOf<String>()
        val result = BaseOrder.first(BaseOrder.candidates(null, null)) {
            tried += it.ref
            if (it.ref == "refs/remotes/origin/master") RefState.Failed("git did not finish in time") else RefState.Missing
        }
        assertEquals(BaseLookup.Failed("git did not finish in time"), result)
        assertEquals(listOf("refs/remotes/origin/main", "refs/remotes/origin/master"), tried)
    }

    /** The recorded name comes from a workspace file a person may edit, and becomes a git argument. */
    @Test
    fun aRecordedNameThatIsNoBranchNameIsLeftOut() {
        for (bad in listOf("", "-x", "--output=/w/acme", "a..b", "a b", "HEAD@{1}", "main~1", "main^", "a:b", "/main", "main/", "a\nb")) {
            assertNull(bad, BaseOrder.recorded(bad))
            assertFalse(bad, BaseOrder.isBranchName(bad))
        }
        for (good in listOf("main", "feature/acme-1", "release_2.0", "ü-branch")) assertTrue(good, BaseOrder.isBranchName(good))
        assertEquals(Base("feature/acme-1", "refs/heads/feature/acme-1"), BaseOrder.recorded("feature/acme-1"))
        assertNull(BaseOrder.recorded(null))
    }

    @Test
    fun theRemoteDefaultIsReadAsARemoteBranch() {
        assertEquals(Base("origin/main", "refs/remotes/origin/main"), BaseOrder.originDefault("origin/main\n"))
        assertNull(BaseOrder.originDefault("  "))
    }
}
