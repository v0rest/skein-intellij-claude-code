package io.github.v0rest.skein.threads

import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.BranchChangeListener

/**
 * Hears a branch switch made through the IDE (`BranchChangeListener`, no ApiStatus, javap of 2026.3) for the project's
 * threads - right before it Skein marks its thread tabs so the IDE's restore of the branch's workspace closes them only to
 * reopen, and once it is over Skein lifts the marks the restore did not use ([ThreadService.branchWillChange]). A
 * project listener of the optional descriptor `io.github.v0rest.skein-branches.xml`, loaded only while the IDE's Version
 * Control is on, and the only class of Skein that touches it (ArchitectureTest), so Skein adds no need for Version Control
 * of its own. It costs nothing before the thread service exists, and nothing is done then.
 */
class BranchSwitchListener(private val project: Project) : BranchChangeListener {
    override fun branchWillChange(branchName: String) {
        project.getServiceIfCreated(ThreadService::class.java)?.branchWillChange()
    }

    override fun branchHasChanged(branchName: String) {
        project.getServiceIfCreated(ThreadService::class.java)?.branchHasChanged()
    }
}
