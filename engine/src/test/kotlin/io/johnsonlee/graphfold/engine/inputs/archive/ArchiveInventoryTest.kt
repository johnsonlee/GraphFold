package io.johnsonlee.graphfold.engine.inputs.archive

import java.nio.file.Path
import org.junit.jupiter.api.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ArchiveInventoryTest {
    @Test fun `one application cannot hide another applications changed same-named class`() {
        fun project(value: String) = InputProject(Path.of("unused"), emptyList(), emptyList(),
            production = ProductionInventory(mapOf("class/App.class" to value), emptyMap()))
        val before = aggregateInputInventory(linkedMapOf("one" to project("first"), "two" to project("second")))
        val after = aggregateInputInventory(linkedMapOf("one" to project("changed"), "two" to project("second")))
        assertNotEquals(before.entries, after.entries)
    }

    @Test fun `an archive scope without output evidence cannot become a complete revision inventory`() {
        assertFailsWith<IllegalStateException> {
            aggregateInputInventory(mapOf("missing" to InputProject(Path.of("unused"), emptyList(), emptyList())))
        }
    }
}
