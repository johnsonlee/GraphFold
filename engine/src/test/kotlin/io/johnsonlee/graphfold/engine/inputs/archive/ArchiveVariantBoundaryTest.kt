package io.johnsonlee.graphfold.engine.inputs.archive

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArchiveVariantBoundaryTest {
    @Test fun `overflowed MR release directory is not a selectable version`() {
        assertNull(archiveVariant("META-INF/versions/999999999999999999999999/A.class"))
        assertEquals(17 to "A.class", archiveVariant("META-INF/versions/17/A.class"))
    }

}
