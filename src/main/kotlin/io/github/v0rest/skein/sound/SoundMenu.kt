package io.github.v0rest.skein.sound

import io.github.v0rest.skein.paths.PathRules
import io.github.v0rest.skein.paths.PathStyle

/**
 * An item of an event's sound list in Settings | Tools | Skein. Equal by its choice, as the bound combo box needs, and
 * its text is what type-ahead and a screen reader go by - [name], and for a file the [folder] it lies in.
 */
class SoundItem(val choice: SoundChoice, val name: String, val folder: String? = null) {
    override fun equals(other: Any?): Boolean = other is SoundItem && other.choice == choice

    override fun hashCode(): Int = choice.hashCode()

    override fun toString(): String = SoundMenu.label(this, null)
}

/** The items of an event's sound list and their words - the settings page only shows them. */
object SoundMenu {
    /** A folder's text longer than this keeps its first and last names only, the list stays narrow enough to read. */
    private const val MAX_FOLDER = 40

    /**
     * The beep, the OS's sounds by [systemNames], then the [kept] choices that are not among those - the stored one, even
     * a file that is gone or a sound this system lacks, and files chosen while the page is open. The stored choice has to
     * be an item, a combo box refuses to select anything else, so the page would open changed and Apply would turn the
     * choice into the beep. The page shows the beep and the stored choice at once and the OS's sounds once they are read.
     */
    fun items(systemNames: List<String>, kept: List<SoundChoice>, home: String, style: PathStyle = PathStyle.current): List<SoundItem> {
        val choices = listOf(SoundChoice.Beep) + systemNames.map { SoundChoice.System(it) } +
            kept.filterIsInstance<SoundChoice.System>() + kept.filterIsInstance<SoundChoice.File>()
        return choices.distinct().map { item(it, home, style) }
    }

    /** [items] with a file just chosen at the end, unless it is there already. */
    fun withChosen(items: List<SoundItem>, file: SoundChoice.File, home: String, style: PathStyle = PathStyle.current): List<SoundItem> {
        val item = item(file, home, style)
        return if (item in items) items else items + item
    }

    fun item(choice: SoundChoice, home: String, style: PathStyle = PathStyle.current): SoundItem = when (choice) {
        SoundChoice.Beep -> SoundItem(choice, "System beep")
        is SoundChoice.System -> SoundItem(choice, choice.name)
        is SoundChoice.File -> SoundItem(choice, PathRules.name(choice.path, style), folderText(choice.path, home, style))
    }

    /** What an item plays, and nothing selected is the beep. */
    fun choiceOf(item: SoundItem?): SoundChoice = item?.choice ?: SoundChoice.Beep

    /**
     * An item's words - its name, then in brackets its folder for a file, so two `chime.wav` from different folders differ,
     * and why it would beep, so a file that is gone still shows as the one chosen.
     */
    fun label(item: SoundItem, problem: SoundProblem?): String {
        val why = when (problem) {
            null -> null
            SoundProblem.MISSING -> "missing"
            SoundProblem.UNSUPPORTED -> "cannot play"
            SoundProblem.TOO_LARGE -> "over 5 MB"
            SoundProblem.UNREADABLE -> "cannot read"
        }
        val details = listOfNotNull(item.folder, why)
        return if (details.isEmpty()) item.name else "${item.name} (${details.joinToString(", ")})"
    }

    /** The combo's tooltip - a file's full path, nothing for the beep and the OS's sounds. */
    fun tooltip(choice: SoundChoice): String? = (choice as? SoundChoice.File)?.path

    /**
     * The folder a file lies in, short - the home as `~`, and a long one as its first name, `…` and its last name, the
     * full path being in the tooltip.
     */
    fun folderText(path: String, home: String, style: PathStyle = PathStyle.current): String {
        val folder = PathRules.parent(path, style) ?: return ""
        val separator = style.separator.toString()
        val underHome = home.takeIf { PathRules.isAbsolute(it, style) }?.let { PathRules.segmentsUnder(folder, it, style) }
        val head = if (underHome != null) "~" else PathRules.root(folder, style)
        val names = underHome ?: PathRules.segments(folder, style)
        fun text(names: List<String>): String = when {
            names.isEmpty() -> head
            underHome != null -> head + separator + names.joinToString(separator)
            else -> head + names.joinToString(separator)
        }
        val full = text(names)
        return if (full.length <= MAX_FOLDER || names.size <= 2) full else text(listOf(names.first(), "…", names.last()))
    }

    /**
     * The choice for a file the chooser gave, its path in the system's own form - the IDE's file chooser writes forward
     * slashes on Windows too. Null for a file the player of [os] does not take or a path that is not a full one.
     */
    fun chosenFile(path: String, os: SoundOs = SoundOs.current): SoundChoice.File? {
        val style = os.style
        if (!PathRules.isAbsolute(path, style) || !SoundCatalog.isSupported(PathRules.name(path, style), os)) return null
        return SoundChoice.File(PathRules.canonical(path, style))
    }
}
