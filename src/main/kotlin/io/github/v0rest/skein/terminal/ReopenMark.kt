package io.github.v0rest.skein.terminal

import com.intellij.openapi.fileEditor.FileEditorManagerKeys
import com.intellij.openapi.vfs.VirtualFile

/**
 * The one place Skein touches the IDE's "closes to reopen" mark, `FileEditorManagerKeys.CLOSING_TO_REOPEN`, on a thread's
 * editor file ([TabRestore]). It is a public key of the platform's implementation module (`intellij.platform.ide.impl`) -
 * the object and this field carry no ApiStatus annotation, only `@JvmField`, while some other keys of that object are
 * `@ApiStatus.Internal` (javap and the Kotlin metadata of 2026.3, build 263.7307.200), so the plugin verifier has nothing
 * to flag here. The IDE puts it on a file it closes only to open again right after - a tab dragged to another split or
 * window, a terminal moved into the editor - and the terminal's editor (`TerminalViewFileEditor.dispose`) honours it by
 * leaving its terminal running.
 *
 * Skein relies on it because nothing else keeps a terminal alive through the IDE's restore of a branch's workspace, which
 * closes every editor past any close check. Marked before a branch switch, a thread's terminal lives through that close
 * and Skein opens the same tab again right after, so claude never notices. It is still the platform's own detail that may
 * change, so it is referenced here directly, never looked up by name, and nowhere else (ArchitectureTest). An IDE that
 * drops or ignores it costs the thread its terminal, and Skein then brings the thread back as a Resume.
 */
internal object ReopenMark {
    /** Puts the mark on [file], the way the IDE does before a move. EDT. */
    fun set(file: VirtualFile) = file.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, true)

    /** Takes the mark off [file] again, as the IDE does after a move - null, not false. EDT. */
    fun clear(file: VirtualFile) = file.putUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN, null)

    /** Whether [file] carries the mark now, Skein's or the IDE's own. */
    fun isSet(file: VirtualFile): Boolean = file.getUserData(FileEditorManagerKeys.CLOSING_TO_REOPEN) == true
}
