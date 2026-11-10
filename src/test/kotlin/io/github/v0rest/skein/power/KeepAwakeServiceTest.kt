package io.github.v0rest.skein.power

import org.junit.Assert.assertEquals
import org.junit.Test

class KeepAwakeServiceTest {
    private class CountingBlocker : SleepBlocker {
        var holds = 0
        var releases = 0
        override fun hold() { holds++ }
        override fun release() { releases++ }
    }

    @Test fun holdsOnceAndReleasesOnceAcrossManyUpdates() {
        val blocker = CountingBlocker()
        val logic = KeepAwakeLogic(blocker)
        repeat(5) { logic.update(enabled = true, holds = true) }
        assertEquals(1, blocker.holds)
        repeat(5) { logic.update(enabled = true, holds = false) }
        assertEquals(1, blocker.releases)
        assertEquals(1, blocker.holds)
    }

    @Test fun turningTheSettingOffReleases() {
        val blocker = CountingBlocker()
        val logic = KeepAwakeLogic(blocker)
        logic.update(enabled = true, holds = true)
        logic.update(enabled = false, holds = true)
        assertEquals(1, blocker.releases)
        logic.update(enabled = false, holds = true)
        assertEquals(1, blocker.holds)
    }

    @Test fun updateAfterDisposeDoesNotHold() {
        val blocker = CountingBlocker()
        val logic = KeepAwakeLogic(blocker)
        logic.dispose()
        logic.update(enabled = true, holds = true)
        assertEquals(0, blocker.holds)
    }

    @Test fun disposingReleasesAHeldAssertion() {
        val blocker = CountingBlocker()
        val logic = KeepAwakeLogic(blocker)
        logic.update(enabled = true, holds = true)
        logic.dispose()
        assertEquals(1, blocker.releases)
        logic.dispose()
        assertEquals(1, blocker.releases)
        val never = CountingBlocker()
        KeepAwakeLogic(never).dispose()
        assertEquals(0, never.releases)
    }
}
