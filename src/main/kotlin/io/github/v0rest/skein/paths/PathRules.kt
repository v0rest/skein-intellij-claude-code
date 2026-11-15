package io.github.v0rest.skein.paths

/**
 * How a path stands to a folder as far as its text can tell. [UNSURE] is an answer of its own - a `..` only the disk
 * can resolve, a name Windows may trim, a relative path - and each question picks the side it needs through
 * [PathRules.inside] or [PathRules.mayBeInside].
 */
enum class Containment { INSIDE, OUTSIDE, UNSURE }

/**
 * Equality, containment and splitting of local paths given as text, the one place that knows how Windows and Unix
 * paths differ. Skein gets the same folder in several shapes - backslashes from a status file and java.nio on Windows,
 * forward slashes from git and the IDE's virtual files, a drive letter in either case - and every comparison has to
 * see one folder there.
 *
 * Pure string work - nothing here touches the disk, follows a link or asks Windows for a long name, so a path is
 * judged as written, and links, mapped drives or another name for the same server are not seen. `.` names drop out
 * (they never change the folder), `..` stays as a name (only the disk could say where it leads). Every function takes
 * any text and never throws.
 */
object PathRules {
    /**
     * The path for display and for keeping - native separators, repeated ones collapsed, none at the end. A root keeps
     * its own (`/`, `C:\`, `\\server\share\`), and on Windows so does a last name ending in a dot or a space - Win32
     * trims every trailing dot and space at a path's end but only a single dot in the middle, so `C:\w\acme \` is the
     * folder `acme ` and `C:\w\acme ` is `acme`. The drive
     * letter comes out upper-case whatever the input had, as java.nio's real paths have it. On Windows git's form
     * `C:/w/acme` comes back as `C:\w\acme`, `\\?\C:\x` as `C:\x` and `\\?\UNC\server\share\x` as `\\server\share\x` -
     * but the `\\?\` stays wherever Windows would read the rest differently without it (a name ending in a dot or a
     * space, a second prefix behind `UNC\`), and a relative path keeps a leading `.\` that stops its first name from
     * reading as a drive. Only `\\?\` written with backslashes is that literal prefix - `//?/` and `\\?/` are
     * normalised like any path, so they lose the prefix even then. So the canonical text names what the input names
     * and is absolute exactly when the input is. The case of the names stays the input's, so on Windows two canonical
     * texts of one folder can still differ in case - [key] is the text to compare.
     */
    fun canonical(path: String, style: PathStyle = PathStyle.current): String = parse(path, style).let { render(it.root, it.names, style, it.trailing) }

    /**
     * The text that two paths share exactly when [same] says they are one - [canonical], and on Windows upper-cased
     * one UTF-16 unit at a time, as NTFS compares names. For sets and maps, not for display.
     */
    fun key(path: String, style: PathStyle = PathStyle.current): String =
        parse(path, style).let { parts -> render(fold(parts.root, style), parts.names.map { fold(it, style) }, style, parts.trailing) }

    /**
     * Whether [a] and [b] name one path by the rules of [style], with no look at the disk. Strict - two texts Windows
     * might or might not open as one folder (`C:\w\task-1.` and `C:\w\task-1`, or `\\?\C:\w\acme.` and
     * `C:\w\acme.`) are not the same.
     */
    fun same(a: String, b: String, style: PathStyle = PathStyle.current): Boolean = key(a, style) == key(b, style)

    /**
     * How [child] stands to [parent]. [Containment.INSIDE] when it is [parent] or lies below it by whole names
     * (`C:\w\acme2` is not inside `C:\w\acme`), [Containment.OUTSIDE] when the text rules that out, and
     * [Containment.UNSURE] when only the disk could tell - either path not absolute (an empty one included), a `..`
     * past the names the two share, on Windows a name ending in a dot or a space (Win32 trims those, a `\\?\` path
     * keeps them), or a device root such as `\\?\Volume{...}\` against another root it may stand for. Callers ask
     * through [inside] or [mayBeInside], whichever side of the doubt their question needs.
     */
    fun containment(child: String, parent: String, style: PathStyle = PathStyle.current): Containment =
        containment(parse(child, style), parse(parent, style), style)

    /**
     * Surely inside, for questions where yes lets Skein act on [child] because it lies in [parent] - a doubt counts
     * as no.
     */
    fun inside(child: String, parent: String, style: PathStyle = PathStyle.current): Boolean =
        containment(child, parent, style) == Containment.INSIDE

    /**
     * Maybe inside, for questions where yes holds an action back, such as whether a folder is still held or in use
     * before it goes - a doubt counts as yes.
     */
    fun mayBeInside(child: String, parent: String, style: PathStyle = PathStyle.current): Boolean =
        containment(child, parent, style) != Containment.OUTSIDE

    /** The names of [child] below [parent], empty for [parent] itself, null unless [child] is surely [inside] it. */
    fun segmentsUnder(child: String, parent: String, style: PathStyle = PathStyle.current): List<String>? {
        val c = parse(child, style)
        val p = parse(parent, style)
        return if (containment(c, p, style) == Containment.INSIDE) c.names.drop(p.names.size) else null
    }

    /**
     * [segmentsUnder] as one text with native separators (`src\main`), empty for [parent] itself, null unless surely
     * inside. For git's form (pathspecs, exclude lines) join [segmentsUnder] with `/` instead.
     */
    fun relative(child: String, parent: String, style: PathStyle = PathStyle.current): String? =
        segmentsUnder(child, parent, style)?.joinToString(style.separator.toString())

    /**
     * Whether [path] starts at a root of its own. On Windows that is a drive root (`C:\`), a UNC share
     * (`\\server\share`, the `\\?\` forms included) or a device root (`\\?\Volume{...}\`) - `\w\acme` hangs off
     * whatever drive is current and `C:w` off the drive's current folder, so neither is, nor a bare `\\?\UNC`.
     */
    fun isAbsolute(path: String, style: PathStyle = PathStyle.current): Boolean = parse(path, style).absolute

    /**
     * The root of [path] as [canonical] writes it - `/` or empty on Unix, and on Windows `C:\`, `C:` (drive-relative),
     * `\` (the current drive's root), `\\server\share\`, a kept device root such as `\\?\Volume{...}\`, or empty for a
     * relative path.
     */
    fun root(path: String, style: PathStyle = PathStyle.current): String = parse(path, style).root

    /**
     * The names after the [root], without empty and `.` ones. [join] of the two gives the [canonical] text back, but
     * for a Windows path that ends in a name Win32 trims there (`C:\w\acme.`) - [join] takes the name whole and writes
     * a separator after it.
     */
    fun segments(path: String, style: PathStyle = PathStyle.current): List<String> = parse(path, style).names

    /** The last name of [path], empty for a root or an empty path. */
    fun name(path: String, style: PathStyle = PathStyle.current): String = parse(path, style).names.lastOrNull().orEmpty()

    /**
     * The folder [path] lies in, as [canonical] text - the root for a name right below it, null for a root itself. Its
     * last name stood in the middle of [path], so on Windows one ending in a dot or a space keeps a separator after it
     * (`C:\w\acme \x` gives `C:\w\acme \`).
     */
    fun parent(path: String, style: PathStyle = PathStyle.current): String? {
        val parts = parse(path, style)
        return if (parts.names.isEmpty()) null else canonical(text(parts, parts.names.dropLast(1), style, closing = true), style)
    }

    /**
     * [base] with [names] below it, as [canonical] text. A name may hold several (`.claude/worktrees`), split by the
     * separators of [style]. The names are taken as relative, even one that starts with a separator, and each one
     * whole - on Windows a last one ending in a dot or a space gets a separator after it, which keeps it as it stood in
     * the middle of the path it came from (`C:\w` and `acme ` give `C:\w\acme \`). A `\\?\` that [base] was written
     * with stays when a joined name needs it (`\\?\C:\w` and `acme.` give `\\?\C:\w\acme.`).
     */
    fun join(base: String, vararg names: String, style: PathStyle = PathStyle.current): String = join(base, names.asList(), style)

    fun join(base: String, names: List<String>, style: PathStyle = PathStyle.current): String {
        val parts = parse(base, style)
        val more = names.flatMap { split(it, style) }
        return canonical(text(parts, parts.names + more, style, closing = more.isNotEmpty() || parts.trailing), style)
    }

    /** Whether two single names are one by the case rule of [style], as a walk over [segments] needs. */
    fun sameName(a: String, b: String, style: PathStyle = PathStyle.current): Boolean =
        fold(a, style) == fold(b, style)

    private const val UP = ".."
    private const val UNC = "\\\\"
    private const val LONG = "\\\\?\\"

    /**
     * A root as [canonical] writes it and the names after it. [doubtful] - a name (or a server or share) that Win32
     * would trim. [device] - a `\\?\` or `\\.\` root kept as written. [long] - a `\\?\` was dropped in front of a
     * drive or a share, which [join] puts back. [trailing] - a separator follows a last name ending in a dot or a
     * space, which may read differently without it. [odd] - a UNC server named `?` or `.`, which must never collapse
     * into a device prefix.
     */
    private class Parts(
        val root: String,
        val names: List<String>,
        val absolute: Boolean,
        val doubtful: Boolean = false,
        val device: Boolean = false,
        val long: Boolean = false,
        val trailing: Boolean = false,
        val odd: Boolean = false,
    )

    private fun containment(c: Parts, p: Parts, style: PathStyle): Containment {
        if (!c.absolute || !p.absolute || c.doubtful || p.doubtful) return Containment.UNSURE
        // a device root may be the very drive or share the other path names
        if (fold(c.root, style) != fold(p.root, style)) return if (c.device || p.device) Containment.UNSURE else Containment.OUTSIDE
        val shared = c.names.zip(p.names).takeWhile { (a, b) -> sameName(a, b, style) }.size
        return when {
            // past the shared names a .. may lead anywhere, into the parent or out of it
            UP in c.names.drop(shared) -> Containment.UNSURE
            shared == p.names.size -> Containment.INSIDE
            UP in p.names.drop(shared) -> Containment.UNSURE
            else -> Containment.OUTSIDE
        }
    }

    private fun parse(path: String, style: PathStyle): Parts = when (style) {
        PathStyle.UNIX -> (if (path.startsWith('/')) "/" else "").let { Parts(it, split(path, style), absolute = it.isNotEmpty()) }
        PathStyle.WINDOWS -> path.replace('/', '\\').let {
            when {
                !isDevice(it) -> parsePlain(it)
                // only \\?\ written with backslashes turns Win32's normalising off, //?/ and \\?/ do not (VM probes)
                it[2] == '?' && !isDevice(path) -> parseNormalised(it)
                else -> parseDevice(it)
            }
        }
    }

    /** `\\?\...` and `\\.\...`, Windows' device paths, in the text as written. */
    private fun isDevice(path: String): Boolean =
        path.length >= 3 && path.startsWith(UNC) && (path[2] == '?' || path[2] == '.') && (path.length == 3 || path[3] == '\\')

    /**
     * A device path - `\\?\` dropped where that changes nothing, else kept with its first name (or `UNC\server\share`)
     * as the root and the rest read as written.
     */
    private fun parseDevice(path: String): Parts {
        val prefix = path.take(3) + "\\"
        val rest = path.drop(4)
        if (path[2] == '?') unprefixed(rest)?.let { return it }
        // Win32 normalises a \\.\ path like a plain one, trimming a last name with no separator after it
        val normalised = path[2] == '.'
        val pieces = rest.split('\\')
        if (pieces.size > 1 && pieces[0].equals("UNC", ignoreCase = true)) {
            // server and share as in a plain UNC root
            val all = pieces.drop(1).filter(String::isNotEmpty)
            val top = all.take(2)
            val names = all.drop(2).filter { it != "." }
            return Parts(
                prefix + pieces[0] + "\\" + top.joinToString("") { "$it\\" }, names, absolute = top.size == 2,
                doubtful = (top + names).any(::trimmable), device = true, trailing = normalised && trailing(path, names),
            )
        }
        val first = pieces[0].let { if (it.length == 2 && hasDrive(it)) it.uppercase() else it }
        // the root ends in a separator only when the input had one after it, so `\\?\C:` (a volume) stays apart from `\\?\C:\`
        val closed = pieces.size > 1
        val names = pieces.drop(1).filter { it.isNotEmpty() && it != "." }
        return Parts(
            root = prefix + first + if (closed) "\\" else "",
            names = names,
            absolute = closed && first.isNotEmpty(),
            doubtful = (listOf(first) + names).any(::trimmable),
            device = true,
            trailing = normalised && trailing(path, names),
        )
    }

    /**
     * `\\?\C:\x` as `C:\x` and `\\?\UNC\s\sh\x` as `\\s\sh\x`, but only where Windows reads the rest the same without
     * the prefix, else null. The prefix turns off Win32's trimming of trailing dots and spaces, so a name ending in one
     * keeps it. And it is dropped once - when that shows another prefix (`\\?\UNC\?\C:\x`), the first one stays. A `.`
     * name, a `/` or a doubled separator under the prefix make a path Windows cannot open at all, so reading them the
     * usual way never points it at another folder.
     */
    private fun unprefixed(rest: String): Parts? {
        // a server named ? or . counts as doubtful, which keeps `\\?\UNC\?\C:\x` as it is
        val parts = behind(rest)?.let(::parsePlain) ?: return null
        return if (parts.doubtful) null else Parts(parts.root, parts.names, parts.absolute, long = true)
    }

    /**
     * `//?/C:/x`, `\\?/C:\x` and the like, with every `/` already turned into `\`. Win32 normalises them as it does a
     * plain path, so they read as the drive or share behind the prefix, trimmed names and all. Where there is none, or
     * the share would collapse into a device prefix (`//?/UNC/?/C:/x`), they read as `\\.\`, the device form Win32
     * normalises too (my reading, not checked on the VM) - never as the literal `\\?\`.
     */
    private fun parseNormalised(path: String): Parts =
        behind(path.drop(4))?.let(::parsePlain)?.takeUnless { it.odd } ?: parseDevice("\\\\." + path.drop(3))

    /** The plain path behind a device prefix - `C:\x` for `C:\x`, `\\s\sh\x` for `UNC\s\sh\x` - null for another device. */
    private fun behind(rest: String): String? = when {
        rest.startsWith("UNC\\", ignoreCase = true) -> UNC + rest.substring(4)
        rest.length >= 3 && hasDrive(rest) && rest[2] == '\\' -> rest
        else -> null
    }

    /** A Windows path with no device prefix and only `\` for a separator. */
    private fun parsePlain(path: String): Parts {
        if (hasDrive(path)) {
            val rest = path.substring(2)
            val drive = path[0].uppercaseChar() + ":"
            val rooted = rest.startsWith('\\')
            return plain(path, if (rooted) "$drive\\" else drive, emptyList(), split(rest, PathStyle.WINDOWS), absolute = rooted)
        }
        if (path.startsWith(UNC)) {
            // server and share belong to the root, however many separators came between them
            val tail = path.substring(2)
            val all = tail.split('\\').filter(String::isNotEmpty)
            val top = all.take(2)
            // `\\\?\C:\x` collapsed would read as the device path `\\?\C:\x`, so such a server keeps one separator more
            val looksLikeDevice = top.firstOrNull() == "?" || top.firstOrNull() == "."
            val lead = if (looksLikeDevice && tail.startsWith('\\')) "$UNC\\" else UNC
            return plain(path, lead + top.joinToString("") { "$it\\" }, top, all.drop(2).filter { it != "." }, absolute = top.size == 2, odd = looksLikeDevice)
        }
        return plain(path, if (path.startsWith('\\')) "\\" else "", emptyList(), split(path, PathStyle.WINDOWS), absolute = false)
    }

    private fun plain(path: String, root: String, top: List<String>, names: List<String>, absolute: Boolean, odd: Boolean = false) =
        Parts(root, names, absolute, doubtful = odd || (top + names).any(::trimmable), trailing = trailing(path, names), odd = odd)

    /** A name Win32 opens without its trailing dots and spaces - `..` among them. */
    private fun trimmable(name: String): Boolean = name.endsWith('.') || name.endsWith(' ')

    /**
     * A name that may read differently with a separator after it - at a path's end Win32 trims every trailing dot and
     * space, in the middle only a single dot (VM). A name with just one trailing dot reads the same both ways, and
     * counts here all the same, the strict side. Not `..`, which is resolved before any trimming.
     */
    private fun closable(name: String): Boolean = trimmable(name) && name != UP

    /**
     * Whether [path] (a Windows text with `\` only) ends in a separator after a last name that needs one. A final `\.`
     * does not count - my reading is that Win32 drops it together with its separator before it trims, not checked on
     * the VM.
     */
    private fun trailing(path: String, names: List<String>): Boolean = path.endsWith('\\') && names.lastOrNull()?.let(::closable) == true

    private fun hasDrive(path: String): Boolean = path.length >= 2 && path[1] == ':' && path[0].let { it in 'a'..'z' || it in 'A'..'Z' }

    private fun split(names: String, style: PathStyle): List<String> {
        val separated = if (style == PathStyle.WINDOWS) names.replace('/', '\\') else names
        return separated.split(style.separator).filter { it.isNotEmpty() && it != "." }
    }

    /**
     * [root] and [names] as text, with the `\\?\` back in front when [parts] had one dropped. [closing] - the last name
     * stood in the middle of a path, so on Windows it gets a separator when it ends in a dot or a space.
     */
    private fun text(parts: Parts, names: List<String>, style: PathStyle, closing: Boolean): String {
        val plain = render(parts.root, names, style, closing && style == PathStyle.WINDOWS && names.lastOrNull()?.let(::closable) == true)
        if (!parts.long) return plain
        return LONG + if (plain.startsWith(UNC)) "UNC\\" + plain.substring(2) else plain
    }

    private fun render(root: String, names: List<String>, style: PathStyle, trailing: Boolean = false): String {
        if (names.isEmpty()) return root
        val separator = style.separator
        val head = when {
            // `C:` as the first name of a relative path would read as a drive once the `.\` before it is gone
            root.isEmpty() && style == PathStyle.WINDOWS && hasDrive(names[0]) -> ".$separator"
            // a bare drive takes its names straight on (`C:w`), and most roots end in their separator already
            root.isEmpty() || root.endsWith(separator) || (root.length == 2 && hasDrive(root)) -> root
            else -> root + separator
        }
        return head + names.joinToString(separator.toString()) + if (trailing) separator.toString() else ""
    }

    /**
     * Windows names compare as NTFS compares them, upper-cased one UTF-16 unit at a time - never the full mapping that
     * writes `ß` as `SS`. Lower case would merge names NTFS keeps apart (the Kelvin sign and k) and split names it
     * merges (the two lower-case sigmas). Closer to NTFS, not exact - its upcase table is written per volume.
     */
    private fun fold(text: String, style: PathStyle): String =
        if (style == PathStyle.WINDOWS) buildString(text.length) { text.forEach { append(upper(it)) } } else text

    /**
     * The dotless i and the long s are the two letters Unicode upper-cases to ASCII (to I and S), and NTFS does not -
     * on the VM `C:\W` + U+0131 + `ndows` and `C:\Window` + U+017F opened nothing.
     */
    private fun upper(c: Char): Char = if (c == '\u0131' || c == '\u017F') c else c.uppercaseChar()
}
