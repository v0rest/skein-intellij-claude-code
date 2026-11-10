package io.github.v0rest.skein.ui

import io.github.v0rest.skein.claude.WaitReason
import io.github.v0rest.skein.threads.SkeinThread
import io.github.v0rest.skein.threads.ThreadState
import org.junit.Assert.assertEquals
import org.junit.Test

class ThreadSectionsTest {
    private fun thread(id: String, state: ThreadState, pinned: Boolean = false) =
        SkeinThread(id = id, folder = "/w/acme", sessionId = null, name = id, state = state, stateSince = 0, createdAt = 0, anchor = null, pinned = pinned)

    private val needs = ThreadState.NeedsYou(WaitReason.OTHER, 1)
    private val turn = ThreadState.YourTurn(2)

    private fun ids(section: Section) = section.threads.map { it.id }

    @Test
    fun ungroupedIsOneSectionInTheUsualOrder() {
        val threads = listOf(thread("a", ThreadState.Idle, pinned = true), thread("b", needs), thread("c", ThreadState.Working))
        val sections = ThreadSections.of(threads, grouped = false, onlyWaiting = false)
        assertEquals(1, sections.size)
        assertEquals(null, sections[0].title)
        assertEquals(listOf("a", "b", "c"), ids(sections[0]))
    }

    @Test
    fun groupedFollowsThePinnedNeedsYouYourTurnWorkingOtherOrder() {
        val threads = listOf(
            thread("p", ThreadState.Working, pinned = true),
            thread("n", needs),
            thread("y", turn),
            thread("w1", ThreadState.Working),
            thread("w2", ThreadState.Background),
            thread("w3", ThreadState.Starting),
            thread("w4", ThreadState.WorkingUnknown),
            thread("o1", ThreadState.Idle),
            thread("o2", ThreadState.Exited),
            thread("o3", ThreadState.NotStarted),
            thread("o4", ThreadState.RunningElsewhere(7)),
            thread("o5", ThreadState.Unknown),
        )
        val sections = ThreadSections.of(threads, grouped = true, onlyWaiting = false)
        assertEquals(listOf("Pinned", "Needs you", "Your turn", "Working", "Other"), sections.map { it.title })
        assertEquals(listOf("p"), ids(sections[0]))
        assertEquals(listOf("n"), ids(sections[1]))
        assertEquals(listOf("y"), ids(sections[2]))
        assertEquals(listOf("w1", "w2", "w3", "w4"), ids(sections[3]))
        assertEquals(listOf("o1", "o2", "o3", "o4", "o5"), ids(sections[4]))
    }

    @Test
    fun theFilterKeepsWaitingAndPinnedThreads() {
        val threads = listOf(
            thread("p", ThreadState.Idle, pinned = true),
            thread("n", needs),
            thread("y", turn),
            thread("w", ThreadState.Working),
            thread("i", ThreadState.Idle),
        )
        val flat = ThreadSections.of(threads, grouped = false, onlyWaiting = true)
        assertEquals(1, flat.size)
        assertEquals(listOf("p", "n", "y"), ids(flat[0]))
        val grouped = ThreadSections.of(threads, grouped = true, onlyWaiting = true)
        assertEquals(listOf("Pinned", "Needs you", "Your turn"), grouped.map { it.title })
    }

    @Test
    fun emptySectionsAreLeftOut() {
        val sections = ThreadSections.of(listOf(thread("y", turn), thread("i", ThreadState.Idle)), grouped = true, onlyWaiting = false)
        assertEquals(listOf("Your turn", "Other"), sections.map { it.title })
        assertEquals(emptyList<Section>(), ThreadSections.of(emptyList(), grouped = true, onlyWaiting = false))
        assertEquals(emptyList<Section>(), ThreadSections.of(emptyList(), grouped = false, onlyWaiting = false))
    }

    @Test
    fun headersNameTheFirstThreadOfEachTitledSection() {
        val threads = listOf(thread("p", ThreadState.Idle, pinned = true), thread("n1", needs), thread("n2", needs), thread("w", ThreadState.Working))
        val headers = ThreadSections.headers(ThreadSections.of(threads, grouped = true, onlyWaiting = false))
        assertEquals(mapOf("p" to "Pinned", "n1" to "Needs you", "w" to "Working"), headers)
    }

    @Test
    fun anUntitledSectionHasNoHeader() {
        val threads = listOf(thread("a", needs), thread("b", ThreadState.Idle))
        assertEquals(emptyMap<String, String>(), ThreadSections.headers(ThreadSections.of(threads, grouped = false, onlyWaiting = false)))
    }
}
