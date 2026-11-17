package io.github.v0rest.skein.threads

/**
 * Whether the project counts as closing for Skein ([ThreadService]). Skein ends its threads before the project saves
 * ([endingThreads]), earlier than the platform says the project closes for sure ([platformCloses]). A close can still be
 * refused in between - a failed save the person does not want to close over - and
 * then the project stays open with the flag set, so a tab's close would end nothing any more. [closeAttemptOver] comes
 * once that close attempt is over and lifts the flag again, but only for a project still open whose close the platform
 * never confirmed. IDE-free, and safe on any thread.
 */
class ProjectClosing {
    @Volatile
    var closing = false
        private set

    @Volatile
    private var platformSaidSo = false

    /** Skein is about to end the threads of a project that closes. */
    fun endingThreads() {
        closing = true
    }

    /** `ProjectCloseListener.projectClosing` - the project closes for sure. */
    fun platformCloses() {
        platformSaidSo = true
        closing = true
    }

    /**
     * The close attempt is over, and [projectOpen] says whether the project is still open. True when that lifted the
     * flag - a close refused after Skein ended the threads - so the caller lets the terminal side go too.
     */
    fun closeAttemptOver(projectOpen: Boolean): Boolean {
        if (!closing || platformSaidSo || !projectOpen) return false
        closing = false
        return true
    }
}
