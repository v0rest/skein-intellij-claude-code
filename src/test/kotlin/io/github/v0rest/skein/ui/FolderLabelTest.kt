package io.github.v0rest.skein.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The grey note a plain thread started in a folder inside the project carries on its row. */
class FolderLabelTest {
    private val base = "/w/acme"

    @Test
    fun aThreadInTheBaseFolderHasNone() = assertNull(FolderLabel.of("Fix login", "/w/acme", base))

    @Test
    fun aSubfolderShowsItsPathFromTheBase() {
        assertEquals("docs", FolderLabel.of("Fix the guide", "/w/acme/docs", base))
        assertEquals("web/src", FolderLabel.of("src", "/w/acme/web/src", base))
    }

    @Test
    fun noneWhileTheNameAlreadySaysIt() = assertNull(FolderLabel.of("docs", "/w/acme/docs", base))

    @Test
    fun aFolderOutsideTheBaseHasNone() {
        // a content root of its own is named after its folder already, as "+" has always done
        assertNull(FolderLabel.of("Fix login", "/w/other", base))
        assertNull(FolderLabel.of("Fix login", "/w/acme-old/docs", base))
    }

    @Test
    fun noneWithoutABase() = assertNull(FolderLabel.of("Fix login", "/w/acme/docs", null))

    @Test
    fun trailingSlashesDoNotMatter() {
        assertNull(FolderLabel.of("Fix login", "/w/acme/", base))
        assertEquals("docs", FolderLabel.of("Fix login", "/w/acme/docs/", "/w/acme/"))
    }
}
