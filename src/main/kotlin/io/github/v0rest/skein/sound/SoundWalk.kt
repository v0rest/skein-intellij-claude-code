package io.github.v0rest.skein.sound

/** One entry of a folder as the disk lists it, links followed - a folder, a regular file, or anything else. */
data class DiskEntry(val name: String, val kind: Kind) {
    enum class Kind { FOLDER, FILE, OTHER }
}

/**
 * The walk through a sound folder, pure - the disk comes in through two functions, so the rules are tested on a made-up
 * tree. A folder that cannot be read is passed over and the rest of the walk goes on. Links to folders are followed, and
 * a folder reached a second time, through a link back up, is not walked again. Only the files [accept] lets through count
 * towards [max], and each folder is walked in name order, so the cap cuts the same files on every look.
 */
object SoundWalk {
    /**
     * The accepted files under the root, each as the names below the root, sorted. [depth] - how many levels of names a
     * file may have, 1 for right in the root. [children] lists a folder by its names below the root, null when it cannot
     * be read, and [identity] tells which folder that really is, null when the disk would not say, and then only [depth]
     * stops a loop.
     */
    fun files(
        depth: Int,
        max: Int,
        accept: (String) -> Boolean,
        children: (List<String>) -> List<DiskEntry>?,
        identity: (List<String>) -> Any?,
    ): List<List<String>> {
        val found = ArrayList<List<String>>()
        val seen = HashSet<Any>()

        fun visit(folder: List<String>) {
            val id = identity(folder)
            if (id != null && !seen.add(id)) return
            val entries = children(folder) ?: return
            for (entry in entries.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER, DiskEntry::name).thenBy { it.name })) {
                if (found.size >= max) return
                val names = folder + entry.name
                when (entry.kind) {
                    DiskEntry.Kind.FILE -> if (accept(entry.name)) found += names
                    DiskEntry.Kind.FOLDER -> if (names.size < depth) visit(names)
                    DiskEntry.Kind.OTHER -> Unit
                }
            }
        }

        visit(emptyList())
        return found.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.joinToString("\u0000") })
    }
}
