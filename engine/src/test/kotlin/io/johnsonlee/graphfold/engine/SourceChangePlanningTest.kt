package io.johnsonlee.graphfold.engine

import io.johnsonlee.graphfold.engine.git.ChangedFile
import io.johnsonlee.graphfold.engine.git.ChangedHunk
import io.johnsonlee.graphfold.engine.git.ChangedLineRange
import io.johnsonlee.graphfold.engine.source.IndexedSource
import io.johnsonlee.graphfold.engine.source.JavaSourceIndex
import io.johnsonlee.graphfold.engine.source.SymbolKind
import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SourceChangePlanningTest {
    @TempDir lateinit var temporary: Path

    @Test fun `addition and deletion retain the available revision and declaration operations`() {
        val text = "class Example { int value() { return 1; } }"
        val head = source(text, "Example.java", Revision.HEAD)
        val added = ChangedFile(null, head.path, emptyList(), listOf(ChangedLineRange(1, 1)))
        val planned = planSourceChanges(null, head, added)
        assertEquals(setOf("Example", "Example.value()I"), planned.map { it.identity }.toSet())
        assertTrue(planned.all { it.basePath == null && it.headPath == head.path })
        assertTrue(planned.flatMap { it.locations }.all { it.revision == Revision.HEAD })
        assertTrue(planned.any { ChangeKind.METHOD_ADDED in it.changes })
        val base = source(text, "Example.java", Revision.BASE)
        val deleted = ChangedFile(base.path, null, listOf(ChangedLineRange(1, 1)), emptyList())
        val removed = planSourceChanges(base, null, deleted)
        assertTrue(removed.flatMap { it.locations }.all { it.revision == Revision.BASE })
        assertTrue(removed.any { ChangeKind.METHOD_REMOVED in it.changes })
    }

    @Test fun `replacement unifies both exact changed fragments and keeps inline provenance`() {
        val before = source("class Example { int value() { return 1; } }", "Example.java", Revision.BASE)
        val after = source("class Example { int value() { return 2; } }", "Example.java", Revision.HEAD)
            .let { it.copy(symbols = it.symbols.map { symbol -> symbol.copy(inlined = symbol.kind == SymbolKind.METHOD) }) }
        val change = ChangedFile(before.path, after.path, emptyList(), emptyList(),
            hunks = listOf(ChangedHunk(1, 1, 1, 1)))
        val obligation = planSourceChanges(before, after, change).single()
        assertEquals("Example.value()I", obligation.identity)
        assertTrue(obligation.inlined)
        assertEquals(listOf(Revision.BASE, Revision.HEAD), obligation.locations.map { it.revision })
        assertTrue(obligation.locations.all { it.column.end - it.column.start == 1 })
        assertEquals(setOf(ChangeKind.BODY_CHANGED), obligation.changes)
    }

    @Test fun `move only changes bind classes once without inventing changed text ranges`() {
        for ((destination, kind) in listOf("new/Example.java" to ChangeKind.FILE_MOVED, "NewName.java" to ChangeKind.FILE_RENAMED)) {
            val before = source("class Example { int value() { return 1; } }", "old/Example.java", Revision.BASE)
            val after = source(before.text, destination, Revision.HEAD)
            val change = ChangedFile(before.path, after.path, emptyList(), emptyList())
            val obligation = planSourceChanges(before, after, change).single()
            assertEquals("Example", obligation.identity)
            assertEquals(SymbolKind.CLASS, obligation.kind)
            assertEquals(setOf(kind), obligation.changes)
            assertTrue(obligation.locations.isEmpty())
            assertEquals(before.path, obligation.basePath)
            assertEquals(after.path, obligation.headPath)
            assertEquals(listOf("Example"), planSourceChanges(null, after, change).map { it.identity })
            assertEquals(listOf("Example"), planSourceChanges(before, null, change).map { it.identity })
            assertTrue(planSourceChanges(null, null, change).isEmpty())
        }
    }

    private fun source(text: String, path: String, revision: Revision): IndexedSource {
        val root = temporary.resolve(revision.name)
        val file = root.resolve(path)
        Files.createDirectories(file.parent)
        Files.writeString(file, text)
        return JavaSourceIndex(listOf(root), emptyList()).use { it.index(root, path, revision) }
    }
}
