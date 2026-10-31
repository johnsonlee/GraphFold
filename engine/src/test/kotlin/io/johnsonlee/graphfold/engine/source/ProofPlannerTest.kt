package io.johnsonlee.graphfold.engine.source

import io.johnsonlee.graphfold.engine.Revision
import io.johnsonlee.graphfold.engine.SourceLocation
import io.johnsonlee.graphfold.engine.SourceRange
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProofPlannerTest {
    @TempDir lateinit var temporary: Path

    @Test
    fun `same-line method changes are attributed by columns not enclosing line`() {
        val text = "class Example { int first() { return 1; } int second() { return 2; } }"
        val source = index(text)
        val at = text.indexOf("return 2") + "return ".length
        val change = location(1, at + 1, 1, at + 2)
        val obligations = ProofPlanner.plan(source, listOf(change, change))
        assertEquals(listOf("Example.second()I"), obligations.map { it.identity })
        assertEquals(listOf(change), obligations.single().locations)
    }

    @Test
    fun `imports outside type ranges create obligations for every file type`() {
        val source = index("import java.util.List;\nclass Example {}\nclass Other {}")
        val change = location(1, 8, 1, 22)
        val obligations = ProofPlanner.plan(source, listOf(change))
        assertEquals(setOf("Example", "Other"), obligations.map { it.identity }.toSet())
        assertTrue(obligations.all { it.kind == SymbolKind.CLASS && it.locations == listOf(change) })
    }

    @Test
    fun `fragment crossing members ignores intervening whitespace`() {
        val source = index("""
            class Example {
                int first() { return 1; }
                int second() { return 2; }
            }
        """.trimIndent())
        val change = location(2, 10, 3, 25)
        val obligations = ProofPlanner.plan(source, listOf(change))
        assertEquals(setOf("Example.first()I", "Example.second()I"), obligations.map { it.identity }.toSet())
        assertTrue(obligations.all { obligation -> obligation.locations.all { it.line.start >= 2 && it.line.end <= 3 } })
    }

    @Test
    fun `nested class header goes to innermost class and deletion keeps base revision`() {
        val text = "class Example { static class Nested {} }"
        val base = index(text, Revision.BASE)
        val change = location(1, text.indexOf("Nested") + 1, 1, text.indexOf("Nested") + 7, Revision.BASE)
        val obligation = ProofPlanner.plan(base, listOf(change)).single()
        assertEquals("Example${'$'}Nested", obligation.identity)
        assertEquals(Revision.BASE, obligation.locations.single().revision)
        val head = index(text)
        val headChange = change.copy(revision = Revision.HEAD)
        assertEquals(listOf(headChange), ProofPlanner.plan(head, listOf(headChange)).single().locations)
    }

    @Test
    fun `empty symbol index never fabricates an owner`() {
        val source = IndexedSource("Example.java", "", emptyList(), listOf("parse failure"))
        assertTrue(ProofPlanner.plan(source, listOf(location(1, 1, 1, 2))).isEmpty())
    }

    @Test
    fun `one hunk spanning separate top level headers preserves every changed class`() {
        val old = "class A {} class B {}"
        val new = "class A { } class B implements java.io.Serializable {}"
        val change = io.johnsonlee.graphfold.engine.git.ChangedFile("Example.java", "Example.java", emptyList(), emptyList(),
            hunks = listOf(io.johnsonlee.graphfold.engine.git.ChangedHunk(1, 1, 1, 1)))
        val locations = DiffLocations.locations(change, old, new)
        listOf(Revision.BASE to old, Revision.HEAD to new).forEach { (revision, text) ->
            val obligations = ProofPlanner.plan(index(text, revision), locations.filter { it.revision == revision })
            assertEquals(setOf("A", "B"), obligations.map { it.identity }.toSet())
            assertTrue(obligations.all { it.locations.all { location -> location.revision == revision } })
        }
    }

    @Test
    fun `hunk spanning sibling nested headers retains both nested declarations`() {
        val text = "class Example { static class First {} static class Second {} }"
        val source = index(text)
        val change = location(1, text.indexOf("First") + 1, 1, text.indexOf("Second") + "Second".length + 1)
        val identities = ProofPlanner.plan(source, listOf(change)).map { it.identity }.toSet()
        assertTrue("Example${'$'}First" in identities)
        assertTrue("Example${'$'}Second" in identities)
        assertTrue("Example" in identities)
    }

    @Test
    fun `added Java method line indentation does not invent a class declaration change`() {
        val text = "class Example {\n    public int added() { return 1; }\n}"
        val source = index(text)
        val change = location(2, 1, 2, text.lines()[1].length + 1)
        val obligations = ProofPlanner.plan(source, listOf(change))
        assertEquals(listOf("Example.added()I"), obligations.map { it.identity })
        assertEquals(5, obligations.single().locations.single().column.start)
    }

    @Test
    fun `added Kotlin method line indentation stays owned by the emitted method`() {
        val text = "class Example {\n    fun added(): Int = 1\n}"
        val path = "Example.kt"
        val methodRange = location(2, 5, 2, text.lines()[1].length + 1).copy(path = path)
        val source = IndexedSource(path, text, listOf(
            SourceSymbol("Example", "Example", SymbolKind.CLASS, location(1, 1, 3, 2).copy(path = path)),
            SourceSymbol("Example.added()I", "Example", SymbolKind.METHOD, methodRange),
        ), emptyList())
        val obligations = ProofPlanner.plan(source, listOf(methodRange.copy(column = SourceRange(1, methodRange.column.end))))
        assertEquals(listOf("Example.added()I"), obligations.map { it.identity })
        assertEquals(listOf(methodRange), obligations.single().locations)
    }

    @Test
    fun `actual class header tokens outside a member retain class and method obligations`() {
        val text = "final class Example {\n    int changed() { return 1; }\n}"
        val source = index(text)
        val change = location(1, 1, 2, text.lines()[1].length + 1)
        assertEquals(setOf("Example", "Example.changed()I"),
            ProofPlanner.plan(source, listOf(change)).map { it.identity }.toSet())
    }

    private fun index(text: String, revision: Revision = Revision.HEAD): IndexedSource {
        Files.writeString(temporary.resolve("Example.java"), text)
        return JavaSourceIndex(listOf(temporary), emptyList()).use { it.index(temporary, "Example.java", revision) }
    }

    private fun location(line: Int, column: Int, endLine: Int, endColumn: Int, revision: Revision = Revision.HEAD) =
        SourceLocation(revision, "Example.java", SourceRange(line, endLine), SourceRange(column, endColumn))
}
