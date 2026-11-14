package io.github.v0rest.skein.ui

import io.github.v0rest.skein.testutil.Fixture
import org.junit.Assert.assertEquals
import org.junit.Test

class ContentRootsTest {
    /** A project folder as the IDE writes it, `/w/acme` on Unix and `C:/w/acme` on Windows. */
    private fun f(relative: String) = Fixture.forwardRoot(relative)

    @Test
    fun baseFirstThenRootsSortedDeduplicated() =
        assertEquals(listOf(f("w/p"), f("w/q"), f("w/r")), ContentRoots.choices(f("w/p"), listOf(f("w/r"), f("w/p"), f("w/q")), null))

    @Test
    fun lastUsedDoesNotChangeTheOrder() =
        assertEquals(listOf(f("w/p"), f("w/q"), f("w/r")), ContentRoots.choices(f("w/p"), listOf(f("w/r"), f("w/q")), f("w/r")))

    @Test
    fun singleRootMeansNoPopup() = assertEquals(listOf(f("w/p")), ContentRoots.choices(f("w/p"), listOf(f("w/p")), null))

    @Test
    fun missingBaseUsesTheRoots() = assertEquals(listOf(f("w/a"), f("w/b")), ContentRoots.choices(null, listOf(f("w/b"), f("w/a")), null))

    @Test
    fun preselectedIsLastUsedWhenStillPresentElseFirst() {
        assertEquals(f("w/q"), ContentRoots.preselected(listOf(f("w/p"), f("w/q")), f("w/q")))
        assertEquals(f("w/p"), ContentRoots.preselected(listOf(f("w/p"), f("w/q")), f("gone")))
        assertEquals(f("w/p"), ContentRoots.preselected(listOf(f("w/p"), f("w/q")), null))
    }

    /** No folder holds a `.git` of its own. */
    private val noRepositories: (String) -> Boolean = { false }

    @Test
    fun sourceSetRootsStayHidden() {
        // the base is a repository top, its source sets never are
        val repositories = setOf(f("w/acme"))
        assertEquals(listOf(f("w/acme")), ContentRoots.startFolders(f("w/acme"), listOf(f("w/acme/src/main"), f("w/acme/src/test")), repositories::contains))
        assertEquals(listOf(f("w/acme")), ContentRoots.startFolders(f("w/acme"), listOf(f("w/acme/src/main"), f("w/acme/src/test")), noRepositories))
    }

    @Test
    fun anInnerRepositoryIsOffered() {
        // a workspace folder holding separate repositories as modules: each repository top is a place to start
        val repositories = setOf(f("w/acme/api"), f("w/acme/web"))
        val roots = listOf(f("w/acme/web"), f("w/acme/api/src/main"), f("w/acme/api") + "/", f("w/other"), f("w/acme/docs"))
        assertEquals(listOf(f("w/acme"), f("w/acme/api"), f("w/acme/web"), f("w/other")), ContentRoots.startFolders(f("w/acme"), roots, repositories::contains))
    }

    @Test
    fun onlyRootsInsideTheBaseAreAskedAbout() {
        val asked = mutableListOf<String>()
        ContentRoots.startFolders(f("w/acme"), listOf(f("w/acme"), f("w/acme/api") + "/", f("w/other"), f("w/acme-docs"))) { asked.add(it); false }
        assertEquals(listOf(f("w/acme/api")), asked)
    }

    @Test
    fun separateRootsStayAfterTheBase() =
        assertEquals(listOf(f("w/acme"), f("w/other"), f("w/zeta")), ContentRoots.startFolders(f("w/acme"), listOf(f("w/zeta"), f("w/other")), noRepositories))

    @Test
    fun missingBaseOffersTheRoots() =
        assertEquals(listOf(f("w/a"), f("w/b")), ContentRoots.startFolders(null, listOf(f("w/b"), f("w/a")), noRepositories))

    @Test
    fun aRootAboveTheBaseStays() =
        assertEquals(listOf(f("w/acme/sub"), f("w/acme")), ContentRoots.startFolders(f("w/acme/sub"), listOf(f("w/acme")), noRepositories))

    @Test
    fun aSiblingWithTheSamePrefixIsNotNested() =
        assertEquals(listOf(f("w/acme"), f("w/acme-docs")), ContentRoots.startFolders(f("w/acme"), listOf(f("w/acme-docs")), noRepositories))

    @Test
    fun rootBaseKeepsNoRoots() = assertEquals(listOf(f("")), ContentRoots.startFolders(f(""), listOf(f("w/a")), noRepositories))
}
