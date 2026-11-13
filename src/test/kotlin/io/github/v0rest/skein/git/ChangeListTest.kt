package io.github.v0rest.skein.git

import org.junit.Assert.assertEquals
import org.junit.Test

/** [ChangeList]: git's raw diff and untracked list turned into Show Changes' files, one per file. */
class ChangeListTest {
    private val a = "78981922613b2afb6025042ff6bd878ac1994e85"
    private val b = "21b49001011914f5f85b9e093bde0d93215cd62a"
    private val zero = "0".repeat(40)

    /** Fields as `git diff --raw -z` writes them: each one ended by a NUL. */
    private fun z(vararg fields: String) = fields.joinToString("\u0000", postfix = "\u0000")

    @Test
    fun aModifiedFileKeepsItsObjectAtTheMergeBase() {
        // the all-zero object on the right means "read the working tree", not that the file is gone
        assertEquals(
            listOf(ChangedFile(ChangeKind.MODIFIED, "a.c", base = BaseObject(a, "100644"))),
            ChangeList.parseRaw(z(":100644 100644 $a $zero M", "a.c")),
        )
    }

    @Test
    fun anAddedFileHasNoBase() {
        assertEquals(listOf(ChangedFile(ChangeKind.ADDED, "new.c")), ChangeList.parseRaw(z(":000000 100644 $zero $b A", "new.c")))
    }

    @Test
    fun aDeletedFileKeepsItsBase() {
        assertEquals(
            listOf(ChangedFile(ChangeKind.DELETED, "gone.c", base = BaseObject(a, "100644"))),
            ChangeList.parseRaw(z(":100644 000000 $a $zero D", "gone.c")),
        )
    }

    @Test
    fun aRenameHasTheOldPathAndTheNewOneAndTheOldObject() {
        assertEquals(
            listOf(ChangedFile(ChangeKind.RENAMED, "src/new.c", from = "src/old.c", base = BaseObject(a, "100644"))),
            ChangeList.parseRaw(z(":100644 100755 $a $zero R086", "src/old.c", "src/new.c")),
        )
    }

    @Test
    fun aCopyHasItsSourceToo() {
        assertEquals(
            listOf(ChangedFile(ChangeKind.COPIED, "b.c", from = "a.c", base = BaseObject(a, "100644"))),
            ChangeList.parseRaw(z(":100644 100644 $a $b C075", "a.c", "b.c")),
        )
    }

    @Test
    fun aTypeChangeAndAnUnknownLetterCountAsModified() {
        assertEquals(
            listOf(
                ChangedFile(ChangeKind.MODIFIED, "link.c", base = BaseObject(a, "100644")),
                ChangedFile(ChangeKind.MODIFIED, "odd.c", base = BaseObject(b, "100644")),
            ),
            ChangeList.parseRaw(z(":100644 120000 $a $zero T", "link.c", ":100644 100644 $b $zero X", "odd.c")),
        )
    }

    @Test
    fun aLinkAndASubmoduleAtTheBaseAreSaidSo() {
        val files = ChangeList.parseRaw(z(":120000 120000 $a $zero M", "link", ":160000 160000 $b $zero M", "vendor/lib"))
        assertEquals(listOf(true, false), files.map { it.base!!.link })
        assertEquals(listOf(false, true), files.map { it.base!!.submodule })
    }

    /** Paths come as git wrote them with `-z`: no quoting, and anything but a NUL in them, a colon at the start too. */
    @Test
    fun pathsAreTakenAsTheyAre() {
        val odd = listOf("with space.c", "line\nbreak.c", ":colon.c", "ünïcode/файл.c", "tab\there.c")
        val text = z(*odd.flatMap { listOf(":100644 100644 $a $zero M", it) }.toTypedArray())
        assertEquals(odd, ChangeList.parseRaw(text).map { it.path })
    }

    @Test
    fun noOutputIsNoChange() {
        assertEquals(emptyList<ChangedFile>(), ChangeList.parseRaw(""))
        assertEquals(emptyList<String>(), ChangeList.parseUntracked(""))
    }

    /** A header with fewer fields than git writes, or one cut short, ends the list rather than shifting what follows. */
    @Test
    fun aBrokenEntryEndsTheList() {
        assertEquals(
            listOf(ChangedFile(ChangeKind.MODIFIED, "a.c", base = BaseObject(a, "100644"))),
            ChangeList.parseRaw(z(":100644 100644 $a $zero M", "a.c", ":100644 M", "b.c", ":100644 100644 $a $zero M", "c.c")),
        )
        assertEquals(emptyList<ChangedFile>(), ChangeList.parseRaw(z(":100644 100644 $a $zero R100", "old.c")))
    }

    @Test
    fun untrackedPathsAreListedOneByOne() {
        assertEquals(
            listOf("x.c", "dir/with space.c", "nested/"),
            ChangeList.parseUntracked(z("x.c", "dir/with space.c", "nested/")),
        )
    }

    @Test
    fun trackedAndUntrackedComeTogetherSortedByPath() {
        val tracked = listOf(
            ChangedFile(ChangeKind.MODIFIED, "src/b.c", base = BaseObject(a, "100644")),
            ChangedFile(ChangeKind.RENAMED, "a/new.c", from = "z/old.c", base = BaseObject(b, "100644")),
        )
        assertEquals(
            listOf("a/new.c", "notes.txt", "src/b.c", "src/c.c"),
            ChangeList.of(tracked, listOf("src/c.c", "notes.txt")).map { it.path },
        )
        assertEquals(ChangedFile(ChangeKind.UNTRACKED, "notes.txt"), ChangeList.of(tracked, listOf("notes.txt")).first { it.path == "notes.txt" })
    }

    /** `git rm --cached` leaves the file on disk: git lists it as deleted and as untracked, and it is one changed file. */
    @Test
    fun aFileDeletedFromTheIndexButStillOnDiskIsOneModifiedFile() {
        val deleted = ChangedFile(ChangeKind.DELETED, "kept.c", base = BaseObject(a, "100644"))
        assertEquals(
            listOf(ChangedFile(ChangeKind.MODIFIED, "kept.c", base = BaseObject(a, "100644"))),
            ChangeList.of(listOf(deleted), listOf("kept.c")),
        )
    }

    @Test
    fun theLabelSaysWhatHappenedToTheFile() {
        val base = BaseObject(a, "100644")
        assertEquals("src/a.c", ChangeList.label(ChangedFile(ChangeKind.MODIFIED, "src/a.c", base = base)))
        assertEquals("src/a.c (added)", ChangeList.label(ChangedFile(ChangeKind.ADDED, "src/a.c")))
        assertEquals("src/a.c (untracked)", ChangeList.label(ChangedFile(ChangeKind.UNTRACKED, "src/a.c")))
        assertEquals("src/a.c (deleted)", ChangeList.label(ChangedFile(ChangeKind.DELETED, "src/a.c", base = base)))
        assertEquals("src/b.c (renamed from src/a.c)", ChangeList.label(ChangedFile(ChangeKind.RENAMED, "src/b.c", "src/a.c", base)))
        assertEquals("src/b.c (copied from src/a.c)", ChangeList.label(ChangedFile(ChangeKind.COPIED, "src/b.c", "src/a.c", base)))
    }
}
