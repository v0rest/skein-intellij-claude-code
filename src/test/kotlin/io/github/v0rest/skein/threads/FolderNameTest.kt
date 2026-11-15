package io.github.v0rest.skein.threads

import io.github.v0rest.skein.paths.PathStyle.UNIX
import io.github.v0rest.skein.paths.PathStyle.WINDOWS
import org.junit.Assert.assertEquals
import org.junit.Test

/** The title of a plain thread's tab: its folder's last name, on any OS the IDE's `C:/...` or a native `C:\...` path. */
class FolderNameTest {
    @Test
    fun aWindowsFolderIsNamedByItsLastName() {
        assertEquals("demo-shop", ThreadCoordinator.folderName("C:/Users/skein/demo-shop", WINDOWS))
        assertEquals("demo-shop", ThreadCoordinator.folderName("C:\\Users\\skein\\demo-shop\\", WINDOWS))
    }

    @Test
    fun aRootIsNamedByItself() {
        assertEquals("C:/", ThreadCoordinator.folderName("C:/", WINDOWS))
        assertEquals("/", ThreadCoordinator.folderName("/", UNIX))
    }

    @Test
    fun aUnixFolderIsNamedByItsLastName() = assertEquals("acme", ThreadCoordinator.folderName("/w/acme", UNIX))
}
