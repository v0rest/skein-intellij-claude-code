package io.github.v0rest.skein.ui

import io.github.v0rest.skein.paths.PathStyle
import io.github.v0rest.skein.testutil.Fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The grey note a plain thread started in a folder inside the project carries on its row. */
class FolderLabelTest {
    /** A project folder as the IDE writes it, `/w/acme` on Unix and `C:/w/acme` on Windows. */
    private fun f(relative: String) = Fixture.forwardRoot(relative)

    private val base = f("w/acme")

    @Test
    fun aThreadInTheBaseFolderHasNone() = assertNull(FolderLabel.of("Fix login", f("w/acme"), base))

    @Test
    fun aSubfolderShowsItsPathFromTheBase() {
        assertEquals("docs", FolderLabel.of("Fix the guide", f("w/acme/docs"), base))
        assertEquals("web" + PathStyle.current.separator + "src", FolderLabel.of("src", f("w/acme/web/src"), base))
    }

    @Test
    fun noneWhileTheNameAlreadySaysIt() = assertNull(FolderLabel.of("docs", f("w/acme/docs"), base))

    @Test
    fun aFolderOutsideTheBaseHasNone() {
        // a content root of its own is named after its folder already, as "+" has always done
        assertNull(FolderLabel.of("Fix login", f("w/other"), base))
        assertNull(FolderLabel.of("Fix login", f("w/acme-old/docs"), base))
    }

    @Test
    fun noneWithoutABase() = assertNull(FolderLabel.of("Fix login", f("w/acme/docs"), null))

    @Test
    fun trailingSlashesDoNotMatter() {
        assertNull(FolderLabel.of("Fix login", f("w/acme") + "/", base))
        assertEquals("docs", FolderLabel.of("Fix login", f("w/acme/docs") + "/", f("w/acme") + "/"))
    }
}
