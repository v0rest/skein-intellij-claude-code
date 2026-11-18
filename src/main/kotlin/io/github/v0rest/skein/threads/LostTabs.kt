package io.github.v0rest.skein.threads

import kotlinx.coroutines.CancellationException

/**
 * The thread tabs the IDE's restore closed one by one past Skein's close check while Skein's reopen mark kept their
 * terminals alive ([io.github.v0rest.skein.terminal.TabRestore.SingleClose.LOSE]). Their claude is ended cleanly and the
 * tabs are released, and each thread then comes back as a Resume. IDE-free, so the one rule here is tested on its own -
 * the tabs are released whatever their ending did, or a live claude would stay behind with no tab.
 */
internal object LostTabs {
    /**
     * Ends the threads [threadIds] - [endings] says what to end in each, [run] ends them and closes their tabs
     * ([CloseSequence.run]) - and when that does not get to the close, because [endings] threw or found nothing or [run]
     * threw, [release] closes the tabs of [threadIds] all the same. A failure is [log]ged, never thrown, but for
     * cancellation, which still releases the tabs.
     */
    suspend fun endThenRelease(
        threadIds: List<String>,
        endings: suspend () -> List<Ending>,
        run: suspend (List<Ending>) -> Unit,
        release: (List<String>) -> Unit,
        log: (String) -> Unit,
    ) {
        var closed = false
        try {
            val found = endings()
            if (found.isNotEmpty()) {
                run(found)
                closed = true
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            log("Skein could not end threads ${threadIds.joinToString(", ")} before releasing the tabs the restore left in no tab: $e")
        } finally {
            if (!closed) release(threadIds)
        }
    }
}
