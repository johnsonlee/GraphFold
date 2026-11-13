package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceRange
import io.johnsonlee.graphfold.engine.git.changedFile
import org.eclipse.jgit.diff.Edit
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffLocationsTest {
    @Test
    fun `replacement excludes equal prefix and suffix using UTF16 columns`() {
        val base = "String s = \"😀\"; return a + b;"
        val head = "String s = \"😀\"; return a - b;"
        val positions = DiffLocations.locations(change(Edit(0, 1, 0, 1)), base, head)
        assertEquals(listOf(Revision.BASE, Revision.HEAD), positions.map { it.revision })
        assertEquals(SourceRange(base.indexOf('+') + 1, base.indexOf('+') + 2), positions.first().column)
        assertEquals(SourceRange(head.indexOf('-') + 1, head.indexOf('-') + 2), positions.last().column)
    }

    @Test
    fun `insertions and deletions preserve their proper revision and line`() {
        val added = changedFile(null, "new.java", edits = listOf(Edit(0, 0, 0, 2)))
        val inserted = DiffLocations.locations(added, null, "first\nsecond\n").single()
        assertEquals(Revision.HEAD, inserted.revision)
        assertEquals(SourceRange(1, 2), inserted.line)
        assertEquals(SourceRange(1, 7), inserted.column)
        val deleted = changedFile("old.java", null, edits = listOf(Edit(0, 2, 0, 0)))
        assertEquals(Revision.BASE, DiffLocations.locations(deleted, "first\nsecond\n", null).single().revision)
    }

    @Test
    fun `multiple hunks and multiline replacements do not include unrelated context`() {
        val change = change(Edit(0, 2, 0, 2), Edit(3, 4, 3, 4))
        val positions = DiffLocations.locations(change, "prefix a\nold tail\ncontext\nx", "prefix b\nnew tail\ncontext\ny")
        assertEquals(4, positions.size)
        assertEquals(SourceRange(1, 2), positions.first().line)
        assertEquals(SourceRange(8, 4), positions.first().column)
        assertEquals(SourceRange(4, 4), positions.last().line)
        assertTrue(positions.none { it.line.start == 3 })
    }

    @Test
    fun `native edits preserve precise CRLF and whitespace locations`() {
        val change = changedFile("before.java", "after.java", edits = listOf(Edit(0, 1, 1, 2)))
        val positions = DiffLocations.locations(change, "  old\r\n", "context\r\n   \r\n")
        assertEquals(SourceRange(3, 6), positions.first().column)
        assertEquals(SourceRange(3, 4), positions.last().column)
        assertTrue(DiffLocations.locations(changedFile(null, null), null, null).isEmpty())
    }

    @Test
    fun `unchanged rename creates no changed character locations`() {
        assertTrue(DiffLocations.locations(change(Edit(0, 1, 0, 1)), "same", "same").isEmpty())
    }

    @Test
    fun `missing source mapping preserves available hunk locations without inventing the other revision`() {
        val modified = change(Edit(1, 2, 1, 2))
        val head = DiffLocations.locations(modified, null, "context\nreturn 2;").single()
        assertEquals(Revision.HEAD, head.revision)
        assertEquals(SourceRange(2, 2), head.line)
        assertEquals(SourceRange(1, 10), head.column)
        val base = DiffLocations.locations(modified, "context\nreturn 1;", null).single()
        assertEquals(Revision.BASE, base.revision)
        assertEquals(head.line, base.line)
        assertEquals(head.column, base.column)
        assertTrue(DiffLocations.locations(modified, null, null).isEmpty())
        // A diff can describe an added/deleted file whose optional mapping is entirely unavailable.
        assertTrue(DiffLocations.locations(change(Edit(0, 0, 0, 1)), null, null).isEmpty())
        assertTrue(DiffLocations.locations(change(Edit(0, 1, 0, 0)), null, null).isEmpty())
    }

    @Test
    fun `missing paths never fabricate a location from text alone`() {
        val absentPaths = changedFile(null, null, edits = listOf(Edit(0, 1, 0, 1)))
        assertTrue(DiffLocations.locations(absentPaths, "old", "new").isEmpty())
    }

    @Test
    fun `single blank line insertion and deletion retain zero width diff locations`() {
        val added = change(Edit(1, 1, 1, 2))
        val insertion = DiffLocations.locations(added, "first\nlast\n", "first\n\nlast\n").single()
        assertEquals(Revision.HEAD, insertion.revision)
        assertEquals(SourceRange(2, 2), insertion.line)
        assertEquals(SourceRange(1, 1), insertion.column)
        val removed = change(Edit(1, 2, 1, 1))
        val deletion = DiffLocations.locations(removed, "first\n\nlast\n", "first\nlast\n").single()
        assertEquals(Revision.BASE, deletion.revision)
        assertEquals(insertion.line, deletion.line)
        assertEquals(insertion.column, deletion.column)
    }

    @Test
    fun `empty side without changed lines never fabricates a blank line deletion`() {
        val addition = change(Edit(0, 0, 0, 1))
        val locations = DiffLocations.locations(addition, "", "new")
        assertEquals(listOf(Revision.HEAD), locations.map { it.revision })
        assertEquals(SourceRange(1, 4), locations.single().column)
        assertTrue(DiffLocations.locations(change(Edit(0, 0, 0, 0)), "", "").isEmpty())
        assertTrue(DiffLocations.locations(change(Edit(0, 1, 0, 1)), "", "").isEmpty())
    }

    private fun change(vararg edits: Edit) =
        changedFile("Example.java", "Example.java", edits = edits.toList())
}
