package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PreparedScopeSelectionTest {
    @TempDir lateinit var temporary: Path

    @Test fun `revision container must select its application scopes before dependency normalization`() {
        val one = InputProject(Files.createDirectory(temporary.resolve("one")), emptyList(), emptyList())
        val two = InputProject(Files.createDirectory(temporary.resolve("two")), emptyList(), emptyList())
        val project = one.copy(analysisScopes = linkedMapOf(":one" to one, ":two" to two))
        assertTrue(assertFailsWith<IllegalArgumentException> {
            project.toStaticScope(temporary.resolve("invalid"))
        }.message.orEmpty().contains("Select one prepared scope"))
        val revision = prepareRevision("revision", temporary, project, temporary.resolve("prepared"), DependencyInputCache())
        assertEquals(setOf(":one", ":two"), revision.scopes.keys)
        assertEquals(one.classes, revision.scopes.getValue(":one").classes)
        assertEquals(two.classes, revision.scopes.getValue(":two").classes)
        assertEquals(null, revision.inventory)
    }
}
