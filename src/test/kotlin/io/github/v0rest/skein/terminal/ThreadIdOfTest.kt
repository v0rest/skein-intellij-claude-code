package io.github.v0rest.skein.terminal

import com.intellij.testFramework.LightVirtualFile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ThreadIdOfTest {
    @Test
    fun aFileWithoutTheMarkBelongsToNoThread() {
        assertNull(TerminalAdapter.threadIdOf(LightVirtualFile("notes.txt")))
    }

    @Test
    fun aMarkedFileGivesItsThreadId() {
        val file = LightVirtualFile("terminal")
        TerminalAdapter.markThread(file, "55555555-5555-4555-8555-555555555555")
        assertEquals("55555555-5555-4555-8555-555555555555", TerminalAdapter.threadIdOf(file))
    }
}
