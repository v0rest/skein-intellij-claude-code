package io.github.v0rest.skein.threads

import com.intellij.util.xmlb.XmlSerializer
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionMarksServiceTest {
    private val a = "55555555-5555-4555-8555-555555555555"
    private val b = "66666666-6666-4666-8666-666666666666"
    private val c = "77777777-7777-4777-8777-777777777777"

    @Test
    fun stateRoundTripKeepsPinsNamesAndDone() {
        val marks = SessionMarks(pinned = setOf(a), names = mapOf(b to "Fix login & <co>"), done = mapOf(c to 42L))
        val service = SessionMarksService().apply { update { marks } }
        val fresh = SessionMarksService().apply { loadState(service.state) }
        assertEquals(marks, fresh.marks.value)
    }

    @Test
    fun theSavedStateSurvivesTheXmlTheWorkspaceFileHolds() {
        val marks = SessionMarks(pinned = setOf(a), names = mapOf(b to "Fix login & <co>"), done = mapOf(c to 42L))
        val service = SessionMarksService().apply { update { marks } }
        val element = XmlSerializer.serialize(service.state)
        val fresh = SessionMarksService().apply { loadState(XmlSerializer.deserialize(element, SessionMarksService.Saved::class.java)) }
        assertEquals(marks, fresh.marks.value)
    }
}
