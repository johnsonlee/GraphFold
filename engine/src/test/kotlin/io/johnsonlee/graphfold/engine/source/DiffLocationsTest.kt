package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceRange
import io.johnsonlee.graphfold.engine.git.ChangedFile
import io.johnsonlee.graphfold.engine.git.ChangedHunk
import io.johnsonlee.graphfold.engine.git.ChangedLineRange
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DiffLocationsTest {
    @Test
    fun `replacement excludes equal prefix and suffix using UTF16 columns`() {
        val base = "String s = \"😀\"; return a + b;"
        val head = "String s = \"😀\"; return a - b;"
        val positions = DiffLocations.locations(change(ChangedHunk(1, 1, 1, 1)), base, head)
        assertEquals(listOf(Revision.BASE, Revision.HEAD), positions.map { it.revision })
        assertEquals(SourceRange(base.indexOf('+') + 1, base.indexOf('+') + 2), positions.first().column)
        assertEquals(SourceRange(head.indexOf('-') + 1, head.indexOf('-') + 2), positions.last().column)
    }

    @Test
    fun `insertions and deletions preserve their proper revision and line`() {
        val added = ChangedFile(null, "new.java", emptyList(), listOf(ChangedLineRange(1, 2)),
            hunks = listOf(ChangedHunk(0, 0, 1, 2)))
        val inserted = DiffLocations.locations(added, null, "first\nsecond\n").single()
        assertEquals(Revision.HEAD, inserted.revision)
        assertEquals(SourceRange(1, 2), inserted.line)
        assertEquals(SourceRange(1, 7), inserted.column)
        val deleted = added.copy(basePath = "old.java", headPath = null, hunks = listOf(ChangedHunk(1, 2, 0, 0)))
        assertEquals(Revision.BASE, DiffLocations.locations(deleted, "first\nsecond\n", null).single().revision)
    }

    @Test
    fun `multiple hunks and multiline replacements do not include unrelated context`() {
        val change = change(ChangedHunk(1, 2, 1, 2), ChangedHunk(4, 1, 4, 1))
        val positions = DiffLocations.locations(change, "prefix a\nold tail\ncontext\nx", "prefix b\nnew tail\ncontext\ny")
        assertEquals(4, positions.size)
        assertEquals(SourceRange(1, 2), positions.first().line)
        assertEquals(SourceRange(8, 4), positions.first().column)
        assertEquals(SourceRange(4, 4), positions.last().line)
        assertTrue(positions.none { it.line.start == 3 })
    }

    @Test
    fun `line fallback handles CRLF blank lines and validates source bounds`() {
        val change = ChangedFile("before.java", "after.java", listOf(ChangedLineRange(1, 1)), listOf(ChangedLineRange(2, 2)))
        val positions = DiffLocations.locations(change, "  old\r\n", "context\r\n   \r\n")
        assertEquals(SourceRange(3, 6), positions.first().column)
        assertEquals(SourceRange(1, 4), positions.last().column)
        assertFailsWith<IllegalArgumentException> {
            DiffLocations.locations(change.copy(headLines = listOf(ChangedLineRange(9, 9))), "old", "new")
        }
        assertTrue(DiffLocations.locations(ChangedFile(null, null, emptyList(), emptyList()), null, null).isEmpty())
    }

    @Test
    fun `unchanged rename creates no changed character locations`() {
        assertTrue(DiffLocations.locations(change(ChangedHunk(1, 1, 1, 1)), "same", "same").isEmpty())
        assertFailsWith<IllegalArgumentException> {
            DiffLocations.locations(change(ChangedHunk(1, 1, 1, 1)), null, "new")
        }
    }

    @Test
    fun `missing paths never fabricate a location from text alone`() {
        val absentPaths = ChangedFile(null, null, listOf(ChangedLineRange(1, 1)), listOf(ChangedLineRange(1, 1)))
        assertTrue(DiffLocations.locations(absentPaths, "old", "new").isEmpty())
        assertTrue(DiffLocations.locations(absentPaths.copy(hunks = listOf(ChangedHunk(1, 1, 1, 1))), "old", "new").isEmpty())
    }

    @Test
    fun `single blank line insertion and deletion retain zero width diff locations`() {
        val added = change(ChangedHunk(1, 0, 2, 1))
        val insertion = DiffLocations.locations(added, "first\nlast\n", "first\n\nlast\n").single()
        assertEquals(Revision.HEAD, insertion.revision)
        assertEquals(SourceRange(2, 2), insertion.line)
        assertEquals(SourceRange(1, 1), insertion.column)
        val removed = change(ChangedHunk(2, 1, 1, 0))
        val deletion = DiffLocations.locations(removed, "first\n\nlast\n", "first\nlast\n").single()
        assertEquals(Revision.BASE, deletion.revision)
        assertEquals(insertion.line, deletion.line)
        assertEquals(insertion.column, deletion.column)
    }

    private fun change(vararg hunks: ChangedHunk) =
        ChangedFile("Example.java", "Example.java", emptyList(), emptyList(), hunks = hunks.toList())
}
