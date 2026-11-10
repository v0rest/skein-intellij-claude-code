package io.github.v0rest.skein.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ContentRootsTest {
    @Test
    fun baseFirstThenRootsSortedDeduplicated() =
        assertEquals(listOf("/w/p", "/w/q", "/w/r"), ContentRoots.choices("/w/p", listOf("/w/r", "/w/p", "/w/q"), null))

    @Test
    fun lastUsedDoesNotChangeTheOrder() =
        assertEquals(listOf("/w/p", "/w/q", "/w/r"), ContentRoots.choices("/w/p", listOf("/w/r", "/w/q"), "/w/r"))

    @Test
    fun singleRootMeansNoPopup() = assertEquals(listOf("/w/p"), ContentRoots.choices("/w/p", listOf("/w/p"), null))

    @Test
    fun missingBaseUsesTheRoots() = assertEquals(listOf("/w/a", "/w/b"), ContentRoots.choices(null, listOf("/w/b", "/w/a"), null))

    @Test
    fun preselectedIsLastUsedWhenStillPresentElseFirst() {
        assertEquals("/w/q", ContentRoots.preselected(listOf("/w/p", "/w/q"), "/w/q"))
        assertEquals("/w/p", ContentRoots.preselected(listOf("/w/p", "/w/q"), "/gone"))
        assertEquals("/w/p", ContentRoots.preselected(listOf("/w/p", "/w/q"), null))
    }

    /** No folder holds a `.git` of its own. */
    private val noRepositories: (String) -> Boolean = { false }

    @Test
    fun sourceSetRootsStayHidden() {
        // the base is a repository top, its source sets never are
        val repositories = setOf("/w/acme")
        assertEquals(listOf("/w/acme"), ContentRoots.startFolders("/w/acme", listOf("/w/acme/src/main", "/w/acme/src/test"), repositories::contains))
        assertEquals(listOf("/w/acme"), ContentRoots.startFolders("/w/acme", listOf("/w/acme/src/main", "/w/acme/src/test"), noRepositories))
    }

    @Test
    fun anInnerRepositoryIsOffered() {
        // a workspace folder holding separate repositories as modules: each repository top is a place to start
        val repositories = setOf("/w/acme/api", "/w/acme/web")
        val roots = listOf("/w/acme/web", "/w/acme/api/src/main", "/w/acme/api/", "/w/other", "/w/acme/docs")
        assertEquals(listOf("/w/acme", "/w/acme/api", "/w/acme/web", "/w/other"), ContentRoots.startFolders("/w/acme", roots, repositories::contains))
    }

    @Test
    fun onlyRootsInsideTheBaseAreAskedAbout() {
        val asked = mutableListOf<String>()
        ContentRoots.startFolders("/w/acme", listOf("/w/acme", "/w/acme/api/", "/w/other", "/w/acme-docs")) { asked.add(it); false }
        assertEquals(listOf("/w/acme/api"), asked)
    }

    @Test
    fun separateRootsStayAfterTheBase() =
        assertEquals(listOf("/w/acme", "/w/other", "/w/zeta"), ContentRoots.startFolders("/w/acme", listOf("/w/zeta", "/w/other"), noRepositories))

    @Test
    fun missingBaseOffersTheRoots() =
        assertEquals(listOf("/w/a", "/w/b"), ContentRoots.startFolders(null, listOf("/w/b", "/w/a"), noRepositories))

    @Test
    fun aRootAboveTheBaseStays() =
        assertEquals(listOf("/w/acme/sub", "/w/acme"), ContentRoots.startFolders("/w/acme/sub", listOf("/w/acme"), noRepositories))

    @Test
    fun aSiblingWithTheSamePrefixIsNotNested() =
        assertEquals(listOf("/w/acme", "/w/acme-docs"), ContentRoots.startFolders("/w/acme", listOf("/w/acme-docs"), noRepositories))

    @Test
    fun rootBaseKeepsNoRoots() = assertEquals(listOf("/"), ContentRoots.startFolders("/", listOf("/w/a"), noRepositories))
}
